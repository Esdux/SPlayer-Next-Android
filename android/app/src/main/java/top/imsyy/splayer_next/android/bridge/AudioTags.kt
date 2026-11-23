package top.imsyy.splayer_next.android.bridge

import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.images.Artwork
import org.json.JSONArray
import org.json.JSONObject
import top.imsyy.splayer_next.android.AppPaths
import top.imsyy.splayer_next.android.SPlayerContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 音频标签写入
 *
 * jaudiotagger 只处理 mp3/flac/m4a/ogg，写入前先把音频复制成缓存目录里的临时副本，
 * 标签写到副本上，成功后再替换回原文件；SAF 文档（content://）在覆盖前会保留原始字节，
 * 覆盖失败时用备份回写，任何一步失败都不改动已经下载的音频
 */
object AudioTags {

    /** 日志 tag */
    private const val TAG = "SPlayerTags"

    /** 封面体积上限，超过直接跳过，避免把超大图塞进标签 */
    private const val MAX_COVER_BYTES = 5 * 1024 * 1024

    /** 临时文件前缀，统一落在 {app-data}/cache */
    private const val TEMP_PREFIX = "tag-"

    /** 取封面用的 UA，部分图床会按 UA 拒绝 */
    private const val COVER_UA =
        "Mozilla/5.0 (Linux; Android 14; SPlayer Next) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/124.0.0.0 Mobile Safari/537.36"

    /** 可写标签的扩展名，其余格式只落盘 */
    private val WRITABLE_EXTS = setOf("mp3", "flac", "m4a", "ogg")

    /** 扩展名允许的 MIME，用于扩展名与响应头交叉校验 */
    private val MIME_BY_EXT = mapOf(
        "mp3" to setOf("audio/mpeg", "audio/mp3", "audio/x-mpeg"),
        "flac" to setOf("audio/flac", "audio/x-flac"),
        "m4a" to setOf("audio/mp4", "audio/x-m4a", "audio/m4a", "video/mp4"),
        "ogg" to setOf("audio/ogg", "application/ogg", "audio/vorbis"),
    )

    /** 可以直接采信的图片 MIME，其余按文件头判断 */
    private val IMAGE_MIMES = setOf("image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif")

    /** 文本类标签字段，空串表示清除 */
    private val TEXT_FIELDS = listOf("title", "artist", "album", "albumArtist", "genre")

    /** 数字类标签字段，0 表示清除 */
    private val NUMBER_FIELDS = listOf("year", "trackNumber", "discNumber")

    /** 取封面单独建客户端，超时比下载短，失败也不拖住下载收尾 */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 应用上下文 */
    private val context: android.content.Context
        get() = SPlayerContext.get()

    /**
     * 单次写入结果
     * @property ok - 是否成功
     * @property message - 失败原因，成功时为 null
     */
    data class Outcome(val ok: Boolean, val message: String? = null)

    /**
     * 扩展名是否在可写标签范围内
     * @param ext - 不含点的扩展名
     * @returns 可写为真
     */
    fun isWritableExt(ext: String): Boolean = WRITABLE_EXTS.contains(ext.lowercase())

    /**
     * 写入一个音频文件的标签
     * @param path - content:// 或绝对路径
     * @param fields - 文本/数字字段，缺失的键表示不修改
     * @param lyrics - 内嵌歌词，null 表示不修改，空串表示清除
     * @param coverUrl - 远程封面地址，取不到时跳过封面
     * @param coverFile - 本地封面文件，与 coverUrl 互斥
     * @param extHint - 已知扩展名，content:// 拿不到文件名时用它
     * @returns 写入结果
     */
    fun write(
        path: String,
        fields: JSONObject,
        lyrics: String?,
        coverUrl: String?,
        coverFile: File?,
        extHint: String? = null,
    ): Outcome {
        val target = resolveTarget(path, extHint) ?: return Outcome(false, "无法识别音频格式")
        if (!isWritable(target.ext, target.mime)) return Outcome(false, "不支持的标签格式: " + target.ext)
        val file = target.file
        if (file != null && (!file.isFile || file.length() <= 0L)) return Outcome(false, "音频文件不存在")

        AppPaths.cacheDir.mkdirs()
        val tagged = File(AppPaths.cacheDir, TEMP_PREFIX + System.nanoTime() + "." + target.ext)
        val warnings = ArrayList<String>()
        return try {
            copySource(target, tagged)
            val audioFile = AudioFileIO.read(tagged)
            val tag = audioFile.getTagOrCreateAndSetDefault()
            applyFields(tag, fields, lyrics, warnings)
            applyCover(tag, loadCover(coverUrl, coverFile), warnings)
            AudioFileIO.write(audioFile)
            if (warnings.isNotEmpty()) {
                Log.w(TAG, "部分标签未写入 " + path + " -> " + warnings.joinToString())
            }
            val uri = target.uri
            val outcome = if (uri != null) commitUri(uri, tagged) else commitFile(file!!, tagged)
            if (outcome.ok) refreshMediaStore(path)
            outcome
        } catch (error: Throwable) {
            Log.w(TAG, "写标签失败: " + path, error)
            Outcome(false, error.message ?: "写标签失败")
        } finally {
            tagged.delete()
        }
    }

    /**
     * 批量写标签，形状对齐 TagEditRequest[]
     * @param edits - 标签编辑请求数组
     * @returns TagWriteOutcome[]
     */
    fun writeBatch(edits: JSONArray): JSONArray {
        val outcomes = JSONArray()
        for (index in 0 until edits.length()) {
            val edit = edits.optJSONObject(index) ?: continue
            val path = if (edit.isNull("path")) "" else edit.optString("path").trim()
            if (path.isEmpty()) {
                outcomes.put(JSONObject().put("path", "").put("success", false).put("error", "缺少文件路径"))
                continue
            }
            val lyrics = if (edit.isNull("lyrics")) null else edit.optString("lyrics")
            val coverUrl = if (edit.isNull("coverUrl")) {
                null
            } else {
                edit.optString("coverUrl").trim().ifEmpty { null }
            }
            val coverFile = if (edit.isNull("coverPath")) {
                null
            } else {
                edit.optString("coverPath").trim().ifEmpty { null }?.let { File(it) }?.takeIf { it.isFile }
            }
            val result = write(path, fieldsOf(edit), lyrics, coverUrl, coverFile)
            val item = JSONObject().put("path", path).put("success", result.ok)
            result.message?.let { item.put("error", it) }
            outcomes.put(item)
        }
        return outcomes
    }

    /**
     * 从编辑请求里挑出标签字段，未出现的键保持不修改
     * @param edit - 单条 TagEditRequest
     * @returns 字段对象
     */
    private fun fieldsOf(edit: JSONObject): JSONObject {
        val fields = JSONObject()
        for (name in TEXT_FIELDS) {
            if (edit.has(name) && !edit.isNull(name)) fields.put(name, edit.optString(name))
        }
        for (name in NUMBER_FIELDS) {
            if (edit.has(name) && !edit.isNull(name)) fields.put(name, edit.optInt(name, 0))
        }
        return fields
    }

    /**
     * 解析写入目标：区分 SAF 文档与普通文件，并确定扩展名与 MIME
     * @param path - content:// 或绝对路径
     * @param extHint - 已知扩展名
     * @returns 写入目标，无法识别时为 null
     */
    private fun resolveTarget(path: String, extHint: String?): Target? {
        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            val name = queryColumn(uri, MediaStore.MediaColumns.DISPLAY_NAME) ?: uri.lastPathSegment
            val mime = queryColumn(uri, MediaStore.MediaColumns.MIME_TYPE)
            val ext = normalizeExt(extHint)
                ?: extensionOf(name)
                ?: extensionOf(uri.lastPathSegment)
                ?: extOfMime(mime)
                ?: return null
            return Target(uri, null, ext, mime)
        }
        val file = File(path)
        val ext = normalizeExt(extHint) ?: extensionOf(file.name) ?: return null
        return Target(null, file, ext, null)
    }

    /**
     * 扩展名与 MIME 是否都允许写标签
     * @param ext - 不含点的扩展名
     * @param mime - 源 MIME，未知时为 null
     * @returns 允许写为真
     */
    private fun isWritable(ext: String, mime: String?): Boolean {
        if (!WRITABLE_EXTS.contains(ext)) return false
        val declared = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (declared.isEmpty()) return true
        val allowed = MIME_BY_EXT[ext] ?: return true
        return declared in allowed || declared.startsWith("audio/") || declared == "application/ogg"
    }

    /**
     * 归一化外部传入的扩展名
     * @param raw - 可能带点的扩展名
     * @returns 不含点的扩展名，非法时为 null
     */
    private fun normalizeExt(raw: String?): String? {
        val ext = raw?.trim()?.removePrefix(".")?.lowercase().orEmpty()
        return ext.takeIf { it.length in 2..5 && it.all { ch -> ch.isLetterOrDigit() } }
    }

    /**
     * 从文件名取扩展名
     * @param name - 文件名或路径末段，可能为空
     * @returns 不含点的扩展名，没有时为 null
     */
    private fun extensionOf(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        return normalizeExt(name.substring(dot + 1))
    }

    /**
     * 从 MIME 反推扩展名，MediaStore 拿不到文件名时兜底
     * @param mime - 源 MIME
     * @returns 扩展名，未命中为 null
     */
    private fun extOfMime(mime: String?): String? {
        val declared = mime?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return MIME_BY_EXT.entries.firstOrNull { declared in it.value }?.key
    }

    /**
     * 读 ContentResolver 的单列值，列不存在时静默返回 null
     * @param uri - 目标文档
     * @param column - 列名
     * @returns 列值
     */
    private fun queryColumn(uri: Uri, column: String): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()

    /**
     * 把源音频复制到临时副本
     * @param target - 源目标
     * @param dest - 临时文件
     */
    private fun copySource(target: Target, dest: File) {
        val stream = if (target.uri != null) {
            context.contentResolver.openInputStream(target.uri) ?: throw IOException("无法读取源文件")
        } else {
            target.file!!.inputStream()
        }
        stream.use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
    }

    /**
     * 按字段逐个写入，单个字段不支持时只记警告
     * @param tag - jaudiotagger 标签
     * @param fields - 字段对象
     * @param lyrics - 内嵌歌词
     * @param warnings - 警告收集
     */
    private fun applyFields(tag: Tag, fields: JSONObject, lyrics: String?, warnings: MutableList<String>) {
        setText(tag, FieldKey.TITLE, fields, "title", warnings)
        setText(tag, FieldKey.ARTIST, fields, "artist", warnings)
        setText(tag, FieldKey.ALBUM, fields, "album", warnings)
        setText(tag, FieldKey.ALBUM_ARTIST, fields, "albumArtist", warnings)
        setText(tag, FieldKey.GENRE, fields, "genre", warnings)
        setNumber(tag, FieldKey.YEAR, fields, "year", warnings)
        setNumber(tag, FieldKey.TRACK, fields, "trackNumber", warnings)
        setNumber(tag, FieldKey.DISC_NO, fields, "discNumber", warnings)
        if (lyrics != null) setValue(tag, FieldKey.LYRICS, lyrics, "lyrics", warnings)
    }

    /**
     * 写文本字段，存在即覆盖，空串清除
     * @param tag - 标签
     * @param key - 目标字段
     * @param fields - 字段对象
     * @param name - JSON 键名
     * @param warnings - 警告收集
     */
    private fun setText(tag: Tag, key: FieldKey, fields: JSONObject, name: String, warnings: MutableList<String>) {
        if (!fields.has(name)) return
        setValue(tag, key, fields.optString(name).trim(), name, warnings)
    }

    /**
     * 写数字字段，0 清除
     * @param tag - 标签
     * @param key - 目标字段
     * @param fields - 字段对象
     * @param name - JSON 键名
     * @param warnings - 警告收集
     */
    private fun setNumber(tag: Tag, key: FieldKey, fields: JSONObject, name: String, warnings: MutableList<String>) {
        if (!fields.has(name)) return
        val value = fields.optInt(name, 0)
        setValue(tag, key, if (value > 0) value.toString() else "", name, warnings)
    }

    /**
     * 单个字段写入入口，失败不中断其他字段
     * @param tag - 标签
     * @param key - 目标字段
     * @param value - 字段值，空串表示清除
     * @param name - 日志用的字段名
     * @param warnings - 警告收集
     */
    private fun setValue(tag: Tag, key: FieldKey, value: String, name: String, warnings: MutableList<String>) {
        if (value.isEmpty()) {
            runCatching { tag.deleteField(key) }.onFailure { warnings.add(name + ": " + it.message) }
            return
        }
        runCatching { tag.setField(key, value) }.onFailure { warnings.add(name + ": " + it.message) }
    }

    /**
     * 写内嵌封面，失败只记警告
     * @param tag - 标签
     * @param cover - 封面数据
     * @param warnings - 警告收集
     */
    private fun applyCover(tag: Tag, cover: Cover?, warnings: MutableList<String>) {
        if (cover == null) return
        runCatching {
            val artwork = AndroidArtwork()
            artwork.setBinaryData(cover.bytes)
            artwork.setMimeType(cover.mime)
            // 尺寸由 BitmapFactory 量，避开 ImageIO 依赖
            val bitmap = BitmapFactory.decodeByteArray(cover.bytes, 0, cover.bytes.size)
            if (bitmap != null) {
                artwork.setWidth(bitmap.width)
                artwork.setHeight(bitmap.height)
                bitmap.recycle()
            }
            tag.setField(artwork)
        }.onFailure {
            warnings.add("cover: " + it.message)
            Log.w(TAG, "写封面失败", it)
        }
    }

    /**
     * 取封面字节，本地文件优先于远程地址
     * @param coverUrl - 远程封面
     * @param coverFile - 本地封面
     * @returns 封面数据，取不到为 null
     */
    private fun loadCover(coverUrl: String?, coverFile: File?): Cover? {
        if (coverFile != null && coverFile.isFile) {
            val bytes = runCatching { coverFile.readBytes() }.getOrNull() ?: return null
            if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return null
            return Cover(bytes, mimeOfBytes(bytes))
        }
        val url = coverUrl?.trim().orEmpty()
        if (url.isEmpty()) return null
        return fetchCover(url)
    }

    /**
     * 下载封面，非图片响应或超限直接放弃
     * @param url - 封面地址
     * @returns 封面数据，失败为 null
     */
    private fun fetchCover(url: String): Cover? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val request = Request.Builder().url(url).header("User-Agent", COVER_UA).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val declared = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                if (declared == null || !declared.startsWith("image/")) return null
                val body = response.body ?: return null
                if (body.contentLength() > MAX_COVER_BYTES) return null
                val bytes = body.bytes()
                if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return null
                Cover(bytes, normalizeImageMime(declared, bytes))
            }
        } catch (error: Throwable) {
            Log.w(TAG, "封面下载失败: " + url, error)
            null
        }
    }

    /**
     * 按文件头判定图片 MIME，声明值不可信时以字节为准
     * @param declared - 响应声明的 MIME
     * @param bytes - 图片字节
     * @returns 图片 MIME
     */
    private fun normalizeImageMime(declared: String, bytes: ByteArray): String {
        if (declared in IMAGE_MIMES) return declared
        return mimeOfBytes(bytes)
    }

    /**
     * 从魔数推断图片 MIME
     * @param bytes - 图片字节
     * @returns 图片 MIME
     */
    private fun mimeOfBytes(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "image/jpeg"
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) return "image/png"
        if (bytes.size >= 12 &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        ) {
            return "image/webp"
        }
        if (bytes.size >= 3 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) {
            return "image/gif"
        }
        return "image/jpeg"
    }

    /**
     * 把带标签的副本替换回普通文件，失败保留原文件
     * @param dest - 原文件
     * @param tagged - 已写标签的副本
     * @returns 替换结果
     */
    private fun commitFile(dest: File, tagged: File): Outcome {
        val sibling = File(dest.parentFile, dest.name + ".tagtmp")
        return try {
            tagged.copyTo(sibling, overwrite = true)
            // 同目录改名是原子的，目标已存在时由文件系统覆盖
            if (sibling.renameTo(dest)) {
                Outcome(true)
            } else {
                val backup = File(AppPaths.cacheDir, TEMP_PREFIX + "backup-" + System.nanoTime())
                dest.copyTo(backup, overwrite = true)
                try {
                    sibling.copyTo(dest, overwrite = true)
                    Outcome(true)
                } catch (error: Throwable) {
                    runCatching { backup.copyTo(dest, overwrite = true) }
                    Outcome(false, "替换失败: " + error.message)
                } finally {
                    backup.delete()
                }
            }
        } catch (error: Throwable) {
            Outcome(false, "替换失败: " + error.message)
        } finally {
            sibling.delete()
        }
    }

    /**
     * 覆盖 SAF 文档，失败用备份回写
     * @param uri - 目标文档
     * @param tagged - 已写标签的副本
     * @returns 覆盖结果
     */
    private fun commitUri(uri: Uri, tagged: File): Outcome {
        val backup = File(AppPaths.cacheDir, TEMP_PREFIX + "backup-" + System.nanoTime())
        return try {
            val source = context.contentResolver.openInputStream(uri) ?: return Outcome(false, "无法读取原文档")
            source.use { input -> backup.outputStream().use { output -> input.copyTo(output) } }
            try {
                writeUri(uri, tagged)
                Outcome(true)
            } catch (error: Throwable) {
                val restored = runCatching { writeUri(uri, backup) }.isSuccess
                Outcome(false, if (restored) "写入失败，已回滚" else "写入失败且回滚失败: " + error.message)
            }
        } catch (error: Throwable) {
            Outcome(false, "写入失败: " + error.message)
        } finally {
            backup.delete()
        }
    }

    /**
     * 用 truncate 模式把本地文件写进文档
     * @param uri - 目标文档
     * @param source - 数据来源文件
     */
    private fun writeUri(uri: Uri, source: File) {
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("无法打开目标文档")
        output.use { out -> source.inputStream().use { it.copyTo(out) } }
    }

    /**
     * 写完后让 MediaStore 重新读取该文件标签，失败不影响写入结果
     * @param path - content:// 或绝对路径
     */
    private fun refreshMediaStore(path: String) {
        runCatching {
            val filePath = if (path.startsWith("content://")) {
                queryColumn(Uri.parse(path), MediaStore.MediaColumns.DATA)
            } else {
                path
            }
            if (!filePath.isNullOrBlank()) {
                MediaScannerConnection.scanFile(context, arrayOf(filePath), null, null)
            }
        }.onFailure { Log.w(TAG, "刷新媒体库失败: " + path, it) }
    }

    /**
     * 写入目标
     * @property uri - SAF 文档，普通文件时为 null
     * @property file - 普通文件，SAF 时为 null
     * @property ext - 不含点的扩展名
     * @property mime - 源 MIME，未知为 null
     */
    private class Target(val uri: Uri?, val file: File?, val ext: String, val mime: String?)

    /**
     * 纯字节封面实现
     *
     * jaudiotagger 自带的 StandardArtwork 在 getImage/setImageFromData 里依赖 javax.imageio，
     * 安卓没有这个包；标签写入只需要字节、MIME、宽高与图片类型，这里只实现这些字段
     */
    private class AndroidArtwork : Artwork {

        private var data: ByteArray = ByteArray(0)
        private var mime: String = "image/jpeg"
        private var description: String = ""
        private var width: Int = 0
        private var height: Int = 0
        private var pictureType: Int = 0
        private var linked: Boolean = false
        private var imageUrl: String = ""

        override fun getBinaryData(): ByteArray = data

        override fun setBinaryData(bytes: ByteArray?) {
            data = bytes ?: ByteArray(0)
        }

        override fun getMimeType(): String = mime

        override fun setMimeType(type: String?) {
            mime = type ?: "image/jpeg"
        }

        override fun getDescription(): String = description

        override fun setDescription(text: String?) {
            description = text.orEmpty()
        }

        override fun getWidth(): Int = width

        override fun setWidth(value: Int) {
            width = value
        }

        override fun getHeight(): Int = height

        override fun setHeight(value: Int) {
            height = value
        }

        override fun isLinked(): Boolean = linked

        override fun setLinked(value: Boolean) {
            linked = value
        }

        override fun getImageUrl(): String = imageUrl

        override fun setImageUrl(url: String?) {
            imageUrl = url.orEmpty()
        }

        override fun getPictureType(): Int = pictureType

        override fun setPictureType(type: Int) {
            pictureType = type
        }

        override fun setImageFromData(): Boolean = false

        override fun getImage(): Any? = null

        override fun setFromFile(file: File?) = Unit

        override fun setFromMetadataBlockDataPicture(picture: MetadataBlockDataPicture?) = Unit
    }

    /**
     * 封面数据
     * @property bytes - 图片字节
     * @property mime - 图片 MIME
     */
    private class Cover(val bytes: ByteArray, val mime: String)
}
