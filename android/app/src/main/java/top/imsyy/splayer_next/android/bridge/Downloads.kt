package top.imsyy.splayer_next.android.bridge

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import top.imsyy.splayer_next.android.AppPaths
import top.imsyy.splayer_next.android.EventBus
import top.imsyy.splayer_next.android.SPlayerContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 音频下载引擎
 *
 * 前端 DownloadApi 的实现在 JS 侧（handlers/pickers.ts），真正拉流落盘在这里：
 * 队列并发上限 2，轮到某任务且请求里没有 url 时，经 download:resolve 向渲染层即时索取，
 * 解析结果由 download:resolution 回传后才开始传输，与桌面 downloadManager 的时序保持一致。
 *
 * 任务表常驻内存并落盘到 {app-data}/downloads.json，进程重启后未结束的任务标记为 interrupted。
 */
object Downloads {

    /** 日志 tag */
    private const val TAG = "SPlayerDownloads"

    /** 并发上限 */
    private const val MAX_CONCURRENCY = 2

    /** 单任务最多尝试次数（首发 1 次 + 重试 1 次） */
    private const val MAX_ATTEMPTS = 2

    /** 重试前的等待，给源站一点缓冲 */
    private const val RETRY_DELAY_MS = 600L

    /** 解析下载地址的等待上限 */
    private const val RESOLVE_TIMEOUT_MS = 60_000L

    /** 进度推送节流间隔 */
    private const val PROGRESS_INTERVAL_MS = 250L

    /** 读写缓冲 */
    private const val BUFFER_SIZE = 64 * 1024

    /** 重名后缀上限 */
    private const val SINK_MAX_SEQ = 50

    /** 文件名长度上限，留出扩展名与重名后缀的空间 */
    private const val MAX_NAME_LENGTH = 96

    /** 未结束状态 */
    private val ACTIVE_STATUS = setOf("queued", "downloading")

    /** 下载记录文件名 */
    private const val STORE_NAME = "downloads.json"

    /** 音频 UA，部分 CDN 会按 UA 拒绝 */
    private const val AUDIO_UA =
        "Mozilla/5.0 (Linux; Android 14; SPlayer Next) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.0.0 Mobile Safari/537.36"

    /** 明确的非音频响应，命中即判失败，避免把错误页存成音频 */
    private val REJECTED_MIME_PREFIXES = listOf(
        "text/html",
        "application/json",
        "application/xml",
        "text/xml",
    )

    /** 可接受的音频扩展名 */
    private val KNOWN_EXTS = listOf(
        "flac", "mp3", "m4a", "aac", "ogg", "opus", "wav", "ape", "wma", "dsf", "alac",
    )

    /** 扩展名 → 写文件用的 MIME，SAF 建文档必须给类型 */
    private val MIME_BY_EXT = mapOf(
        "flac" to "audio/flac",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "ogg" to "audio/ogg",
        "opus" to "audio/opus",
        "wav" to "audio/wav",
    )

    /** Content-Type → 扩展名，URL 与 declaredFormat 都拿不到时兜底 */
    private val EXT_BY_MIME = mapOf(
        "audio/flac" to "flac",
        "audio/x-flac" to "flac",
        "audio/mpeg" to "mp3",
        "audio/mp3" to "mp3",
        "audio/mp4" to "m4a",
        "audio/x-m4a" to "m4a",
        "audio/aac" to "aac",
        "audio/ogg" to "ogg",
        "audio/opus" to "opus",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
    )

    /** 文件名里必须替掉的字符，含路径分隔符与控制字符 */
    private val ILLEGAL_NAME = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

    /** 连续空白 */
    private val BLANK_RUN = Regex("\\s+")

    /** 打开长连接，下载大文件时不设整体超时 */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** 下载协程作用域，独立于 Activity 生命周期，退出页面也继续下 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 任务表互斥锁 */
    private val lock = Any()

    /** 全部任务（含已结束），按创建顺序 */
    private val records = LinkedHashMap<String, TaskRow>()

    /** 未结束任务，含取消控制器 */
    private val pendingMap = HashMap<String, Pending>()

    /** 等待调度的 taskId，FIFO */
    private val queue = ArrayDeque<String>()

    /** 正在执行的 taskId */
    private val activeIds = HashSet<String>()

    /** 等待渲染层回传解析结果的门 */
    private val gates = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    /** 记录是否已从磁盘加载 */
    @Volatile
    private var loaded = false

    /** 应用上下文 */
    private val context: Context
        get() = SPlayerContext.get()

    /**
     * 单条任务记录
     *
     * 用显式字段而不是 JSONObject，避免多线程改同一对象；
     * 对外的 DownloadTask 形状由 [toJson] 现算。
     */
    private class TaskRow(
        val taskId: String,
        /** 前端 Track 原文，回投时原样还原 */
        val trackJson: String,
        val source: String,
        val trackId: String,
        val serverId: String?,
        val originalId: String?,
        val serverRevision: Long,
        val qualityLevel: String,
        initStatus: String,
        initReceived: Long,
        initTotal: Long,
        initFilePath: String?,
        initErrorCode: String?,
        initTagWarning: Boolean,
        val createdAt: Long,
        initFinishedAt: Long?,
    ) {
        /** 任务状态：queued / downloading / done / failed / canceled / interrupted */
        @Volatile
        var status: String = initStatus

        /** 已接收字节 */
        @Volatile
        var received: Long = initReceived

        /** 总字节，未知为 0 */
        @Volatile
        var total: Long = initTotal

        /** 完成后最终文件路径 */
        @Volatile
        var filePath: String? = initFilePath

        /** 失败原因码 */
        @Volatile
        var errorCode: String? = initErrorCode

        /** 音频已落盘但标签写入失败 */
        @Volatile
        var tagWarning: Boolean = initTagWarning

        /** 结束时间 */
        @Volatile
        var finishedAt: Long? = initFinishedAt

        /**
         * 转成前端 DownloadTask
         * @returns 与桌面同形的任务对象
         */
        fun toJson(): JSONObject {
            val json = JSONObject()
                .put("taskId", taskId)
                .put("status", status)
                .put("track", runCatching { JSONObject(trackJson) }.getOrElse { JSONObject() })
                .put("qualityLevel", qualityLevel)
                .put("received", received)
                .put("total", total)
                .put("tagWarning", tagWarning)
                .put("createdAt", createdAt)
            filePath?.let { json.put("filePath", it) }
            errorCode?.let { json.put("errorCode", it) }
            finishedAt?.let { json.put("finishedAt", it) }
            return json
        }

        /**
         * 落盘用扁平结构
         * @returns 可重新加载的记录对象
         */
        fun toStoreJson(): JSONObject = JSONObject()
            .put("taskId", taskId)
            .put("trackJson", trackJson)
            .put("source", source)
            .put("trackId", trackId)
            .put("serverId", serverId ?: JSONObject.NULL)
            .put("originalId", originalId ?: JSONObject.NULL)
            .put("serverRevision", serverRevision)
            .put("qualityLevel", qualityLevel)
            .put("status", status)
            .put("received", received)
            .put("total", total)
            .put("filePath", filePath ?: JSONObject.NULL)
            .put("errorCode", errorCode ?: JSONObject.NULL)
            .put("tagWarning", tagWarning)
            .put("createdAt", createdAt)
            .put("finishedAt", finishedAt ?: JSONObject.NULL)
    }

    /**
     * 运行态任务
     * @property req - 前端请求原文
     * @property row - 对外记录
     * @property dedupeKey - 同曲同音质去重键
     * @property dirUri - start 时前端带来的 SAF tree uri，空串表示用应用目录
     */
    private class Pending(
        val req: JSONObject,
        val row: TaskRow,
        val dedupeKey: String,
        val dirUri: String,
    ) {
        /** 当前网络请求，取消时直接 abort */
        @Volatile
        var call: Call? = null

        /** 当前协程，取消时打断读流 */
        @Volatile
        var job: Job? = null

        /** 已被取消，收尾时按 canceled 而不是 failed 处理 */
        @Volatile
        var canceled: Boolean = false

        /** 已被删除，收尾的状态广播不再推送，避免删行后复活 */
        @Volatile
        var removed: Boolean = false
    }

    /**
     * 落盘目标
     * @property stream - 输出流
     * @property finalPath - 对外公布的最终路径（content uri 或绝对路径）
     * @property onCommit - 收尾动作（File 情况把 .part 改名）
     * @property onAbort - 失败清理
     */
    private class Sink(
        val stream: OutputStream,
        val finalPath: String,
        private val onCommit: () -> Unit,
        private val onAbort: () -> Unit,
    ) {
        /**
         * 关闭并提交
         * @returns 最终路径
         */
        fun closeAndCommit(): String {
            stream.close()
            onCommit()
            return finalPath
        }

        /**
         * 关闭并清理半成品
         */
        fun abort() {
            runCatching { stream.close() }
            runCatching { onAbort() }
        }
    }

    /**
     * 入队一个下载
     * @param payload - { req: DownloadRequest, dirUri?: string }
     * @returns EnqueueResult 形状 { ok, reason? }
     */
    fun start(payload: JSONObject): JSONObject {
        val req = payload.optJSONObject("req") ?: return result(false, "invalid")
        val dirUri = payload.optString("dirUri").trim()
        val taskId = req.optString("taskId").trim()
        if (taskId.isEmpty()) return result(false, "invalid")
        ensureLoaded()

        var row: TaskRow? = null
        val outcome = synchronized(lock) {
            if (pendingMap.containsKey(taskId)) return@synchronized result(false, "queued")
            val key = dedupeKeyOf(req)
            // 只和仍在队列/下载中的任务比对，历史任务（done/failed/interrupted）不算重复
            if (pendingMap.values.any { it.dedupeKey == key && it.row.status in ACTIVE_STATUS }) {
                return@synchronized result(false, "queued")
            }
            if (isDownloadedLocked(req)) return@synchronized result(false, "downloaded")
            val track = req.optJSONObject("track") ?: JSONObject()
            val created = TaskRow(
                taskId = taskId,
                trackJson = track.toString(),
                source = track.optString("source"),
                trackId = track.optString("id"),
                serverId = track.optString("serverId").takeIf { it.isNotEmpty() },
                originalId = track.optString("originalId").takeIf { it.isNotEmpty() },
                serverRevision = track.optLong("serverRevision", 0L),
                qualityLevel = req.optString("qualityLevel"),
                initStatus = "queued",
                initReceived = 0L,
                initTotal = req.optLong("declaredSize", 0L),
                initFilePath = null,
                initErrorCode = null,
                initTagWarning = false,
                createdAt = System.currentTimeMillis(),
                initFinishedAt = null,
            )
            records[taskId] = created
            pendingMap[taskId] = Pending(req, created, key, dirUri)
            queue.addLast(taskId)
            row = created
            result(true, null)
        }

        val created = row
        if (created != null) {
            persist()
            broadcastState(created)
            schedule()
        }
        return outcome
    }

    /**
     * 批量入队，形状对齐 download:startMany
     * @param payload - { reqs: DownloadRequest[], dirUri?: string }
     * @returns EnqueueResult[]
     */
    fun startMany(payload: JSONObject): JSONArray {
        val reqs = payload.optJSONArray("reqs") ?: JSONArray()
        val dirUri = payload.optString("dirUri").trim()
        val out = JSONArray()
        for (index in 0 until reqs.length()) {
            val req = reqs.optJSONObject(index) ?: continue
            val payload = JSONObject().put("req", req).put("dirUri", dirUri)
            out.put(start(payload))
        }
        return out
    }

    /**
     * 列出全部任务，最新在前
     * @returns DownloadTask[]
     */
    fun list(): JSONArray {
        ensureLoaded()
        val snapshot = synchronized(lock) {
            records.values.sortedByDescending { it.createdAt }.map { it.toJson() }
        }
        val array = JSONArray()
        snapshot.forEach { array.put(it) }
        return array
    }

    /**
     * 取消任务：排队中直接移出，进行中打断网络请求
     * @param taskId - 任务标识
     * @returns 是否命中任务
     */
    fun cancel(taskId: String): Boolean {
        ensureLoaded()
        val target = synchronized(lock) { pendingMap[taskId] } ?: return false
        target.canceled = true
        val active = synchronized(lock) { activeIds.contains(taskId) }
        if (active) {
            target.call?.cancel()
            target.job?.cancel()
            return true
        }
        synchronized(lock) {
            queue.remove(taskId)
            pendingMap.remove(taskId)
            target.row.status = "canceled"
            target.row.finishedAt = System.currentTimeMillis()
        }
        persist()
        broadcastState(target.row)
        return true
    }

    /**
     * 删除一条任务记录并清理已落盘文件
     * @param taskId - 任务标识
     * @returns 是否命中任务
     */
    fun remove(taskId: String): Boolean {
        ensureLoaded()
        val target = synchronized(lock) { pendingMap[taskId] }
        if (target != null) {
            target.removed = true
            if (synchronized(lock) { activeIds.contains(taskId) }) {
                target.canceled = true
                target.call?.cancel()
                target.job?.cancel()
            } else {
                synchronized(lock) {
                    queue.remove(taskId)
                    pendingMap.remove(taskId)
                }
            }
        }
        val filePath = synchronized(lock) {
            val path = records[taskId]?.filePath
            records.remove(taskId)
            path
        }
        persist()
        filePath?.let { deleteFile(it) }
        return true
    }

    /**
     * 清空已结束任务记录，保留文件本体
     * @returns 清理的条数
     */
    fun clearFinished(): Int {
        ensureLoaded()
        var removed = 0
        synchronized(lock) {
            val iterator = records.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.status !in ACTIVE_STATUS && !pendingMap.containsKey(entry.key)) {
                    iterator.remove()
                    removed++
                }
            }
        }
        persist()
        return removed
    }

    /**
     * 查找仍存在的已下载文件
     * @param track - 歌曲身份
     * @returns 最近完成且仍可读的路径，未命中为 null
     */
    fun lookup(track: JSONObject): String? {
        ensureLoaded()
        val source = track.optString("source")
        if (source.isEmpty() || source == "local") return null
        val trackId = track.optString("id")
        val revision = track.optLong("serverRevision", 0L)
        val candidates = synchronized(lock) {
            records.values
                .filter {
                    it.status == "done" && it.filePath != null &&
                        it.source == source && it.trackId == trackId &&
                        (source != "streaming" || it.serverRevision == revision)
                }
                .sortedByDescending { it.finishedAt ?: it.createdAt }
                .mapNotNull { it.filePath }
        }
        return candidates.firstOrNull { exists(it) }
    }

    /**
     * 渲染层回传解析结果
     * @param taskId - 任务标识
     * @param res - DownloadResolution
     * @returns 是否有任务在等待
     */
    fun submitResolution(taskId: String, res: JSONObject?): Boolean {
        val gate = gates[taskId] ?: return false
        gate.complete(res ?: JSONObject())
        return true
    }

    /**
     * 渲染层解析失败
     * @param taskId - 任务标识
     * @returns 是否有任务在等待
     */
    fun failResolution(taskId: String): Boolean {
        val gate = gates[taskId] ?: return false
        gate.completeExceptionally(IOException("resolve failed"))
        return true
    }

    /**
     * 构造入队结果
     * @param ok - 是否入队
     * @param reason - 拒绝原因
     * @returns EnqueueResult
     */
    private fun result(ok: Boolean, reason: String?): JSONObject {
        val json = JSONObject().put("ok", ok)
        if (reason != null) json.put("reason", reason)
        return json
    }

    /**
     * 下载去重键，流媒体按服务器版本区分
     * @param req - 下载请求
     * @returns 去重键
     */
    private fun dedupeKeyOf(req: JSONObject): String {
        val track = req.optJSONObject("track") ?: JSONObject()
        val revision = if (track.optString("source") == "streaming") {
            track.optLong("serverRevision", 0L)
        } else {
            0L
        }
        return track.optString("source") + ":" + track.optString("id") + ":" +
            req.optString("qualityLevel") + ":" + revision
    }

    /**
     * 是否已下载过同曲同音质且文件仍在
     * @param req - 下载请求
     * @returns 命中为真
     */
    private fun isDownloadedLocked(req: JSONObject): Boolean {
        val track = req.optJSONObject("track") ?: return false
        val source = track.optString("source")
        val trackId = track.optString("id")
        val quality = req.optString("qualityLevel")
        val revision = track.optLong("serverRevision", 0L)
        val hit = records.values.firstOrNull {
            it.status == "done" && it.qualityLevel == quality && it.filePath != null &&
                it.source == source && it.trackId == trackId &&
                (source != "streaming" || it.serverRevision == revision)
        } ?: return false
        return hit.filePath?.let { exists(it) } ?: false
    }

    /**
     * 判断路径是否仍可读
     * @param path - content uri 或绝对路径
     * @returns 可读为真
     */
    private fun exists(path: String): Boolean {
        if (path.startsWith("content://")) {
            return runCatching {
                context.contentResolver.openAssetFileDescriptor(Uri.parse(path), "r")?.use { true } ?: false
            }.getOrDefault(false)
        }
        val file = File(path)
        return file.isFile && file.length() > 0
    }

    /**
     * 判断 MIME 是否是明确的错误页
     * @param mime - Content-Type
     * @returns 命中为真
     */
    private fun isRejectedMime(mime: String?): Boolean {
        val value = mime?.lowercase() ?: return false
        return REJECTED_MIME_PREFIXES.any { value.startsWith(it) }
    }

    /**
     * 按 URL、声明格式、Content-Type 依次推断扩展名
     * @param declaredFormat - 前端已知格式（如 flac/mp3）
     * @param contentType - 响应 Content-Type
     * @param url - 音频地址
     * @returns 不含点的扩展名
     */
    private fun resolveExtension(declaredFormat: String, contentType: String?, url: String): String {
        val fromUrl = Regex("\\.([a-z0-9]{2,5})$", RegexOption.IGNORE_CASE)
            .find(url.substringBefore('?').substringBefore('#'))
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()
        if (fromUrl != null && KNOWN_EXTS.contains(fromUrl)) return fromUrl
        val declared = declaredFormat.trim().removePrefix(".").lowercase()
        if (declared.isNotEmpty() && KNOWN_EXTS.contains(declared)) return declared
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (mime != null && EXT_BY_MIME.containsKey(mime)) return EXT_BY_MIME.getValue(mime)
        return "mp3"
    }

    /**
     * 扩展名对应的写文件 MIME
     * @param ext - 不含点的扩展名
     * @returns MIME，未知按二进制
     */
    private fun mimeOfExt(ext: String): String = MIME_BY_EXT[ext] ?: "application/octet-stream"

    /**
     * 由曲目信息拼文件名主干，已做穿越与非法字符清洗
     * @param track - 前端 Track
     * @returns 安全文件名（不含扩展名）
     */
    private fun safeBaseName(track: JSONObject): String {
        val title = track.optString("title").trim()
        val artists = ArrayList<String>()
        val array = track.optJSONArray("artists")
        if (array != null) {
            for (index in 0 until array.length()) {
                val name = array.optJSONObject(index)?.optString("name")?.trim().orEmpty()
                if (name.isNotEmpty()) artists.add(name)
            }
        }
        val raw = if (artists.isEmpty()) title else title + " - " + artists.joinToString("、")
        val cleaned = sanitize(raw)
        return if (cleaned.isEmpty()) "splayer-audio" else cleaned
    }

    /**
     * 清洗文件名：去掉路径分隔符、控制字符与穿越片段
     * @param raw - 原始名称
     * @returns 可交给 SAF 与 File 的名称
     */
    private fun sanitize(raw: String): String {
        var name = raw.replace(ILLEGAL_NAME, " ").replace(BLANK_RUN, " ").trim()
        // 连续的点可能被拼成 .. 穿越，先压成单个点
        while (name.contains("..")) name = name.replace("..", ".")
        name = name.trim('.', ' ')
        if (name.length > MAX_NAME_LENGTH) name = name.substring(0, MAX_NAME_LENGTH).trim()
        return name
    }

    /**
     * 选择落盘目标：优先 SAF tree，其次应用下载目录
     * @param dirUri - 已选目录的 tree uri，空串表示未选择
     * @param fileName - 带扩展名的文件名
     * @param ext - 扩展名，用于选 MIME
     * @returns 写入目标
     */
    private fun openSink(dirUri: String, fileName: String, ext: String): Sink {
        if (dirUri.isNotEmpty()) {
            val saf = runCatching { openSafSink(dirUri, fileName, mimeOfExt(ext)) }.getOrNull()
            if (saf != null) return saf
            Log.w(TAG, "SAF 目录不可写，回落到应用下载目录: " + dirUri)
        }
        return openFileSink(fileName)
    }

    /**
     * 在 SAF tree 下建文档写入
     * @param dirUri - tree uri
     * @param fileName - 目标文件名
     * @param mime - 文档 MIME
     * @returns 写入目标
     */
    private fun openSafSink(dirUri: String, fileName: String, mime: String): Sink {
        val tree = Uri.parse(dirUri)
        val dir = DocumentFile.fromTreeUri(context, tree)
            ?: throw IOException("下载目录不可用: " + dirUri)
        if (!dir.canWrite()) throw IOException("下载目录不可写")
        val name = uniqueSafName(dir, fileName)
        val doc = dir.createFile(mime, name) ?: throw IOException("无法创建下载文件: " + name)
        val stream = context.contentResolver.openOutputStream(doc.uri, "wt")
            ?: throw IOException("无法打开下载文件: " + name)
        return Sink(stream, doc.uri.toString(), onCommit = {}, onAbort = {
            runCatching { doc.delete() }
        })
    }

    /**
     * SAF 目录重名时追加序号
     * @param dir - 目标目录
     * @param fileName - 原始文件名
     * @returns 不冲突的文件名
     */
    private fun uniqueSafName(dir: DocumentFile, fileName: String): String {
        if (dir.findFile(fileName) == null) return fileName
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        for (seq in 2..SINK_MAX_SEQ) {
            val candidate = base + " (" + seq + ")" + ext
            if (dir.findFile(candidate) == null) return candidate
        }
        throw IOException("同名文件过多: " + fileName)
    }

    /**
     * 在应用下载目录写入，先落 .part 再改名
     * @param fileName - 目标文件名
     * @returns 写入目标
     */
    private fun openFileSink(fileName: String): Sink {
        val dir = AppPaths.downloadsDir.apply { mkdirs() }
        val prefix = dir.canonicalPath + File.separator
        val target = uniqueFile(dir, fileName)
        if (!target.canonicalPath.startsWith(prefix)) throw IOException("非法下载路径: " + fileName)
        val part = File(dir, target.name + ".part")
        val stream = part.outputStream()
        return Sink(stream, target.absolutePath, onCommit = {
            if (!part.renameTo(target)) throw IOException("保存下载文件失败: " + target.name)
        }, onAbort = {
            runCatching { part.delete() }
            runCatching { target.delete() }
        })
    }

    /**
     * 普通目录重名时追加序号
     * @param dir - 目标目录
     * @param fileName - 原始文件名
     * @returns 不冲突的路径
     */
    private fun uniqueFile(dir: File, fileName: String): File {
        val first = File(dir, fileName)
        if (!first.exists()) return first
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        for (seq in 2..SINK_MAX_SEQ) {
            val candidate = File(dir, base + " (" + seq + ")" + ext)
            if (!candidate.exists()) return candidate
        }
        throw IOException("同名文件过多: " + fileName)
    }

    /**
     * 调度队列，最多同时跑 [MAX_CONCURRENCY] 个任务
     */
    private fun schedule() {
        while (true) {
            var taskId: String? = null
            synchronized(lock) {
                if (activeIds.size < MAX_CONCURRENCY && queue.isNotEmpty()) {
                    val next = queue.removeFirst()
                    activeIds.add(next)
                    taskId = next
                }
            }
            val next = taskId
            if (next == null) return
            val pending = synchronized(lock) { pendingMap[next] }
            if (pending == null) {
                synchronized(lock) { activeIds.remove(next) }
                continue
            }
            val job = scope.launch { runTask(pending) }
            pending.job = job
        }
    }

    /**
     * 执行一个任务，含一次失败重试
     * @param pending - 运行态任务
     */
    private suspend fun runTask(pending: Pending) {
        val row = pending.row
        try {
            markDownloading(pending)
            var attempt = 0
            while (true) {
                attempt++
                try {
                    performDownload(pending)
                    return
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (pending.canceled || pending.removed) throw CancellationException("canceled")
                    if (attempt >= MAX_ATTEMPTS) throw error
                    Log.w(TAG, "下载失败，准备重试 " + row.trackId + ": " + error.message)
                    delay(RETRY_DELAY_MS)
                }
            }
        } catch (error: CancellationException) {
            markFinished(pending, "canceled", null)
        } catch (error: Throwable) {
            Log.w(TAG, "下载失败: " + row.trackId, error)
            markFinished(pending, "failed", errorCodeOf(error))
        } finally {
            cleanup(pending)
        }
    }

    /**
     * 标记进入下载中并推送
     * @param pending - 运行态任务
     */
    private fun markDownloading(pending: Pending) {
        if (pending.removed) return
        pending.row.status = "downloading"
        pending.row.finishedAt = null
        broadcastState(pending.row)
        persist()
    }

    /**
     * 把异常归到前端 ErrorCode
     * @param error - 下载过程中抛出的异常
     * @returns 错误码字符串
     */
    private fun errorCodeOf(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.startsWith("HTTP") -> "NETWORK_ERROR"
            message.contains("解析") -> "URL_RESOLVE_FAILED"
            message.contains("不是音频") || message.contains("响应为空") -> "NETWORK_ERROR"
            else -> "UNKNOWN"
        }
    }

    /**
     * 收尾并推送终态
     * @param pending - 运行态任务
     * @param status - done / failed / canceled
     * @param message - 失败时的 ErrorCode
     */
    private fun markFinished(pending: Pending, status: String, message: String?) {
        val row = pending.row
        row.status = status
        row.finishedAt = System.currentTimeMillis()
        if (status == "failed") row.errorCode = message ?: "UNKNOWN"
        if (pending.removed) return
        broadcastState(row)
        persist()
    }

    /**
     * 从运行态表移除，并让出并发位
     * @param pending - 运行态任务
     */
    private fun cleanup(pending: Pending) {
        synchronized(lock) {
            activeIds.remove(pending.row.taskId)
            pendingMap.remove(pending.row.taskId)
        }
        persist()
        schedule()
    }

    /**
     * 真正的传输：解析地址、建文件、边下边报进度
     * @param pending - 运行态任务
     */
    private suspend fun performDownload(pending: Pending) {
        val req = pending.req
        val row = pending.row
        val url = ensureUrl(pending)
        val track = req.optJSONObject("track") ?: JSONObject()
        val baseName = safeBaseName(track)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", AUDIO_UA)
            .get()
            .build()
        val call = client.newCall(request)
        pending.call = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP " + response.code)
                val body = response.body ?: throw IOException("响应为空")
                val mime = response.header("Content-Type")
                if (isRejectedMime(mime)) throw IOException("响应不是音频: " + mime)
                val ext = resolveExtension(req.optString("declaredFormat"), mime, url)
                val total = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
                    ?: req.optLong("declaredSize", 0L)
                row.total = total
                broadcastProgress(row)
                val sink = openSink(pending.dirUri, baseName + "." + ext, ext)
                var received = 0L
                var lastEmit = 0L
                try {
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            // 每次读块让出一次，取消能及时生效
                            yield()
                            val read = input.read(buffer)
                            if (read < 0) break
                            sink.stream.write(buffer, 0, read)
                            received += read
                            row.received = received
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                                lastEmit = now
                                if (pending.removed) throw CancellationException("removed")
                                broadcastProgress(row)
                            }
                        }
                    }
                    if (received <= 0L) throw IOException("下载内容为空")
                    row.received = received
                    if (row.total <= 0L) row.total = received
                    val committed = sink.closeAndCommit()
                    row.filePath = committed
                    // 标签失败不能让已落盘的音频回到 failed，只置 tagWarning
                    runCatching { applyTags(pending, committed, ext) }.onFailure {
                        pending.row.tagWarning = true
                        Log.w(TAG, "标签写入异常，音频已保留", it)
                    }
                } catch (error: Throwable) {
                    sink.abort()
                    throw error
                }
            }
        } finally {
            pending.call = null
        }
        markFinished(pending, "done", null)
    }

    /**
     * 落盘后写入标题/歌手/专辑/封面/歌词
     *
     * 只有 mp3/flac/m4a/ogg 写标签，其余格式按“只落盘”处理；写失败不改任务状态，只置 tagWarning
     * @param pending - 运行态任务
     * @param path - 已落盘的音频路径
     * @param ext - 下载时确定的扩展名
     */
    private fun applyTags(pending: Pending, path: String, ext: String) {
        if (!AudioTags.isWritableExt(ext)) return
        val req = pending.req
        val options = req.optJSONObject("tagOptions") ?: return
        val track = req.optJSONObject("track") ?: JSONObject()
        val fields = JSONObject()
        if (options.optBoolean("embedMeta", false)) {
            val title = track.optString("title").trim()
            if (title.isNotEmpty()) fields.put("title", title)
            val artists = ArrayList<String>()
            val array = track.optJSONArray("artists")
            if (array != null) {
                for (index in 0 until array.length()) {
                    val name = array.optJSONObject(index)?.optString("name")?.trim().orEmpty()
                    if (name.isNotEmpty()) artists.add(name)
                }
            }
            if (artists.isNotEmpty()) fields.put("artist", artists.joinToString("/"))
            val album = track.optJSONObject("album")
            if (album != null) {
                val albumName = album.optString("name").trim()
                if (albumName.isNotEmpty()) fields.put("album", albumName)
                val albumArtist = album.optString("artist").trim()
                if (albumArtist.isNotEmpty()) fields.put("albumArtist", albumArtist)
                val year = album.optInt("year", 0)
                if (year > 0) fields.put("year", year)
            }
            val trackNo = track.optInt("track", 0)
            if (trackNo > 0) fields.put("trackNumber", trackNo)
            val discNo = track.optInt("disc", 0)
            if (discNo > 0) fields.put("discNumber", discNo)
        }
        val lyrics = if (options.optBoolean("embedLyric", false) && !req.isNull("lyricText")) {
            req.optString("lyricText").takeIf { it.isNotBlank() }
        } else {
            null
        }
        val coverUrl = if (options.optBoolean("embedCover", false) && !req.isNull("coverUrl")) {
            req.optString("coverUrl").takeIf { it.isNotBlank() }
        } else {
            null
        }
        if (fields.length() == 0 && lyrics == null && coverUrl == null) return
        val result = AudioTags.write(path, fields, lyrics, coverUrl, null, ext)
        if (!result.ok) {
            pending.row.tagWarning = true
            Log.w(TAG, "标签写入失败，音频已保留: " + path + " -> " + result.message)
        }
    }

    /**
     * 取下载地址；请求里没有就轮到时向渲染层索取
     * @param pending - 运行态任务
     * @returns 可下载的音频地址
     */
    private suspend fun ensureUrl(pending: Pending): String {
        val req = pending.req
        val direct = req.optString("url").trim()
        if (direct.isNotEmpty()) return direct
        val gate = CompletableDeferred<JSONObject>()
        gates[pending.row.taskId] = gate
        EventBus.emit("download:resolve", resolvePayload(pending))
        val resolved = try {
            withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { gate.await() }
        } finally {
            gates.remove(pending.row.taskId)
        }
        if (resolved == null) throw IOException("解析下载地址超时")
        if (!req.has("declaredFormat") && resolved.has("declaredFormat")) {
            req.put("declaredFormat", resolved.optString("declaredFormat"))
        }
        val url = resolved.optString("url").trim()
        if (url.isEmpty()) throw IOException("下载地址为空")
        return url
    }

    /**
     * 下发解析请求的载荷，形状对齐 DownloadResolvePayload
     * @param pending - 运行态任务
     * @returns 解析请求对象
     */
    private fun resolvePayload(pending: Pending): JSONObject {
        val req = pending.req
        return JSONObject()
            .put("taskId", pending.row.taskId)
            .put("track", req.optJSONObject("track") ?: JSONObject())
            .put("qualityLevel", req.optString("qualityLevel"))
            .put("tagOptions", req.optJSONObject("tagOptions") ?: JSONObject())
            .put("coverUrl", req.opt("coverUrl") ?: JSONObject.NULL)
            .put("usePlaybackForDownload", req.optBoolean("usePlaybackForDownload", false))
            .put("lyricFileFormat", req.optString("lyricFileFormat", "lrc"))
    }

    /**
     * 推送任务状态
     * @param row - 任务记录
     */
    private fun broadcastState(row: TaskRow) {
        EventBus.emit("download:state", row.toJson())
    }

    /**
     * 推送进度
     * @param row - 任务记录
     */
    private fun broadcastProgress(row: TaskRow) {
        EventBus.emit(
            "download:progress",
            JSONObject()
                .put("taskId", row.taskId)
                .put("received", row.received)
                .put("total", row.total),
        )
    }

    /**
     * 删除已下载文件，SAF 文档与普通文件分别处理
     * @param path - content uri 或绝对路径
     */
    private fun deleteFile(path: String) {
        runCatching {
            if (path.startsWith("content://")) {
                DocumentFile.fromSingleUri(context, Uri.parse(path))?.delete()
            } else {
                File(path).delete()
            }
        }.onFailure { Log.w(TAG, "删除下载文件失败: " + path, it) }
    }

    /**
     * 首次访问时从磁盘恢复任务表
     */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            loaded = true
            runCatching { loadLocked() }.onFailure { Log.w(TAG, "读取下载记录失败", it) }
        }
    }

    /**
     * 加载 records，未结束的任务按中断处理
     */
    private fun loadLocked() {
        val file = storeFile()
        if (!file.isFile) return
        val array = runCatching { JSONArray(file.readText()) }.getOrElse { return }
        for (index in 0 until array.length()) {
            val raw = array.optJSONObject(index) ?: continue
            val taskId = raw.optString("taskId")
            if (taskId.isEmpty()) continue
            val status = raw.optString("status")
            val interrupted = if (status in ACTIVE_STATUS) "interrupted" else status
            val row = TaskRow(
                taskId = taskId,
                trackJson = raw.optString("trackJson", "{}"),
                source = raw.optString("source"),
                trackId = raw.optString("trackId"),
                serverId = (raw.opt("serverId") as? String)?.takeIf { it.isNotEmpty() },
                originalId = (raw.opt("originalId") as? String)?.takeIf { it.isNotEmpty() },
                serverRevision = raw.optLong("serverRevision", 0L),
                qualityLevel = raw.optString("qualityLevel"),
                initStatus = interrupted,
                initReceived = raw.optLong("received", 0L),
                initTotal = raw.optLong("total", 0L),
                initFilePath = (raw.opt("filePath") as? String)?.takeIf { it.isNotEmpty() },
                initErrorCode = (raw.opt("errorCode") as? String)?.takeIf { it.isNotEmpty() },
                initTagWarning = raw.optBoolean("tagWarning", false),
                createdAt = raw.optLong("createdAt", 0L),
                initFinishedAt = if (raw.has("finishedAt") && !raw.isNull("finishedAt")) {
                    raw.optLong("finishedAt", 0L)
                } else {
                    null
                },
            )
            records[taskId] = row
        }
    }

    /**
     * 把任务表写回磁盘，先写临时文件再改名
     */
    private fun persist() {
        val snapshot = synchronized(lock) { records.values.map { it.toStoreJson() } }
        val array = JSONArray()
        snapshot.forEach { array.put(it) }
        val target = storeFile()
        runCatching {
            AppPaths.root.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(target)) {
                target.writeText(array.toString())
                temp.delete()
            }
        }.onFailure { Log.w(TAG, "下载记录落盘失败", it) }
    }

    /**
     * 下载记录文件
     * @returns {app-data}/downloads.json
     */
    private fun storeFile(): File = File(AppPaths.root, STORE_NAME)
}
