package top.imsyy.splayer_next.android

import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import top.imsyy.splayer_next.android.bridge.AudioTags
import top.imsyy.splayer_next.android.bridge.DocumentIo
import top.imsyy.splayer_next.android.bridge.Downloads
import top.imsyy.splayer_next.android.bridge.LyriconBridge
import top.imsyy.splayer_next.android.bridge.LibraryProvider
import top.imsyy.splayer_next.android.bridge.Pickers
import top.imsyy.splayer_next.android.playback.PlaybackEngine
import top.imsyy.splayer_next.android.playback.PlaybackService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * channel 分发器，未实现的 channel 返回失败占位，保证前端不因缺能力而崩溃
 */
class BridgeDispatcher(private val activity: MainActivity) {

    private val pool =
        Executors.newFixedThreadPool(4) { runnable ->
            Thread({
                ThreadTuning.applyWorkerThread()
                runnable.run()
            }, "bridge-worker")
        }

    /**
     * 网络通道独立池
     *
     * net:fetch 是同步 OkHttp 调用，放通用池里一旦遇到长请求（如 AMLL 搜索），
     * 4 个线程被占满后所有原生调用都会排队超时，表现成整个应用「断网」。
     */
    private val netPool =
        Executors.newCachedThreadPool { runnable ->
            Thread({
                ThreadTuning.applyWorkerThread()
                runnable.run()
            }, "bridge-net")
        }

    init {
        attach(activity)
    }

    /**
     * 判断 channel 是否必须在主线程执行
     * ExoPlayer 与所有窗口操作都校验调用线程，跨线程会直接抛异常
     * @param channel - IPC channel 名
     * @returns 需要在主线程执行时为真
     */
    private fun needsMainThread(channel: String): Boolean =
        channel.startsWith("player:") || channel.startsWith("window:") ||
            channel.startsWith("theme:") || channel.startsWith("picker:")

    /**
     * 主线程执行体
     * @param channel - IPC channel 名
     * @param args - 参数数组
     * @returns 结果信封文本
     */
    private fun envelopeOf(channel: String, args: JSONArray): String {
        return runCatching {
            JSONObject().put("ok", true).put("data", dispatch(channel, args))
        }.getOrElse {
            Log.w("SPlayerBridge", "channel failed: " + channel, it)
            JSONObject().put("ok", false).put("error", it.message ?: "dispatch failed")
        }.toString()
    }

    /**
     * 同步分发
     * @param channel - IPC channel 名
     * @param payload - JSON 数组字符串
     * @returns JSON 字符串结果
     */
    fun invokeSync(channel: String, payload: String): String {
        val args = parse(payload)
        if (!needsMainThread(channel) || Looper.myLooper() == Looper.getMainLooper()) {
            return envelopeOf(channel, args)
        }
        val holder = AtomicReference<String>()
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            holder.set(envelopeOf(channel, args))
            latch.countDown()
        }
        // 主线程派发超时压到 10s：卡住的是整个 JavaBridge 线程，宁可快速失败
        latch.await(10, TimeUnit.SECONDS)
        return holder.get() ?: JSONObject()
            .put("ok", false)
            .put("error", "main thread dispatch timeout: " + channel)
            .toString()
    }

    /**
     * 异步分发，播放与窗口类走主线程，其余走线程池
     * @param callId - 调用标识
     * @param channel - IPC channel 名
     * @param payload - JSON 数组字符串
     */
    fun invokeAsync(callId: String, channel: String, payload: String) {
        // 目录选择要等用户操作，这里交出去，由 Activity 回调时再回复
        if (channel == "library:addScanDir") {
            activity.beginScanDirPick(callId)
            return
        }
        // picker 要等系统选择结果，同样延迟到 Activity 回调时回投
        if (channel.startsWith("picker:")) {
            activity.beginPicker(callId, channel, parse(payload))
            return
        }
        val args = parse(payload)
        val work = Runnable { reply(callId, envelopeOf(channel, args)) }
        when {
            needsMainThread(channel) -> activity.runOnUiThread(work)
            channel.startsWith("net:") -> netPool.execute(work)
            else -> pool.execute(work)
        }
    }

    /**
     * 解析参数
     * @param payload - JSON 数组字符串
     * @returns JSON 数组，非法时为单元素空数组
     */
    private fun parse(payload: String): JSONArray = runCatching { JSONArray(payload) }.getOrElse { JSONArray() }

    /**
     * 回投结果给 JS
     * @param callId - 调用标识
     * @param json - 结果 JSON
     */
    companion object {

        /** 回调用的 Activity，由实例创建时登记 */
        @Volatile
        private var replyActivity: MainActivity? = null

        /**
         * 登记回调 Activity
         * @param activity - 承载 WebView 的 Activity
         */
        fun attach(activity: MainActivity) {
            replyActivity = activity
        }

        /**
         * 以成功信封回复一个延迟调用
         * @param callId - 调用标识
         * @param data - 业务数据
         */
        fun replyOk(callId: String, data: Any) {
            reply(callId, JSONObject().put("ok", true).put("data", data).toString())
        }

        /**
         * 回复一个延迟调用
         * @param callId - 调用标识
         * @param json - 结果 JSON 文本
         */
        fun reply(callId: String, json: String) {
            val activity = replyActivity ?: return
            val escapedCallId = JSONObject.quote(callId)
            val escapedJson = JSONObject.quote(json)
            activity.runOnUiThread {
                activity.webView.evaluateJavascript(
                    "window.__androidReply && window.__androidReply(" + escapedCallId + ", " + escapedJson + ")",
                    null,
                )
            }
        }
    }

    /**
     * 按 channel 路由
     * @param channel - IPC channel 名
     * @param args - 参数数组
     * @returns 前端可直接消费的结果对象
     */
    private fun dispatch(channel: String, args: JSONArray): Any {
        return when (channel) {
            "player:getStatus" -> ok(PlaybackEngine.status())
            "player:load" -> {
                val source = args.optString(0)
                val options = args.optJSONObject(1)
                val autoPlay = options?.optBoolean("autoPlay", true) ?: true
                ok(PlaybackEngine.load(source, autoPlay, options?.optJSONObject("meta")))
            }
            "player:play" -> { PlaybackEngine.play(); ok(null) }
            "player:pause" -> { PlaybackEngine.pause(); ok(null) }
            "player:stop" -> { PlaybackEngine.stop(); ok(null) }
            "player:seek" -> { PlaybackEngine.seek(args.optLong(0)); ok(null) }
            "player:setVolume" -> { PlaybackEngine.setVolume(args.optDouble(0, 1.0).toFloat()); ok(null) }
            "player:getVolume" -> ok(PlaybackEngine.volume().toDouble())
            "player:setSpeed" -> { PlaybackEngine.setSpeed(args.optDouble(0, 1.0).toFloat()); ok(null) }
            "player:setPitch", "player:setPitchSync",
            "player:setNormalizationEnabled", "player:setPauseOnDeviceSwitch",
            "player:syncPlayMode", "player:syncQueueIndex" -> ok(null)
            "player:setEqualizerEnabled" -> {
                PlaybackEngine.setEqualizerEnabled(args.optBoolean(0, false))
                ok(null)
            }
            "player:setEqualizerBands" -> {
                PlaybackEngine.setEqualizerBands(gainsOf(args.optJSONArray(0)))
                ok(null)
            }
            "player:setPreampGain" -> {
                PlaybackEngine.setPreampGain(args.optDouble(0, 0.0).toFloat())
                ok(null)
            }
            // 频谱读取的是系统输出混音，需要 RECORD_AUDIO 运行时授权，申请后由 Activity 回调补建
            "player:setFftEnabled" -> {
                val enabled = args.optBoolean(0, false)
                if (enabled) activity.ensureAudioCapturePermission()
                PlaybackEngine.setFftEnabled(enabled)
                ok(null)
            }
            "player:setFadeDuration" -> {
                PlaybackEngine.setFadeDuration(args.optLong(0).toInt())
                ok(null)
            }
            "player:getFadeDuration" -> ok(PlaybackEngine.fadeDuration().toDouble())
            "player:syncLikeState" -> {
                PlaybackService.setLiked(args.optBoolean(0))
                ok(null)
            }
            "player:respondQueue", "player:dispatch",
            -> ok(null)
            "player:getStreamInfo" -> ok(PlaybackEngine.streamInfo())
            "player:getFftData" -> ok(PlaybackEngine.fftData())
            "player:getOutputDevices" -> ok(
                JSONArray().put(
                    JSONObject().put("id", "builtin").put("name", "本机扬声器").put("isDefault", true),
                ),
            )
            "player:getDefaultDeviceName", "player:getSelectedDeviceName" -> ok("本机扬声器")
            "player:setOutputDevice" -> ok(null)
            "player:getCoverRaw" -> ok(null)
            "player:prepareNext" -> ok(
                PlaybackEngine.prepareNext(
                    args.optString(0),
                    args.optString(1),
                    args.optLong(2, 0),
                ),
            )
            "player:cancelPrepared" -> {
                PlaybackEngine.cancelPrepared(args.optString(0).ifEmpty { null })
                ok(null)
            }
            // 交替返回 { success, data }，与桌面端同一形状；失败由前端回落到正常切歌
            "player:transitionPrepared" -> PlaybackEngine.transitionPrepared(
                args.optString(0),
                args.optString(1),
                args.optLong(2, 0),
                args.optJSONObject(4)?.optJSONObject("meta"),
            )
            "player:readLyricFile" -> ok(null)
            "library:scan" -> {
                val count = LibraryProvider.scan(activity)
                EventBus.emit(
                    "library:scanProgress",
                    JSONObject().put("phase", "done").put("total", count).put("scanned", count),
                )
                ok(JSONObject().put("success", true).put("count", count))
            }
            "library:cancelScan" -> ok(JSONObject().put("success", true))
            "library:getScanDirs" -> ok(
                JSONObject().put("success", true).put("data", ScanDirs.labels(activity)),
            )
            "library:addScanDir" -> ok(JSONObject().put("success", false).put("error", "请使用系统目录选择器"))
            "library:removeScanDir" -> {
                ScanDirs.remove(activity, args.optString(0))
                ok(JSONObject().put("success", true))
            }
            "library:getAlbumTracks" -> ok(
                JSONObject().put("success", true)
                    .put("data", LibraryProvider.albumTracks(args.optString(0))),
            )
            "library:getArtistTracks" -> ok(
                JSONObject().put("success", true)
                    .put("data", LibraryProvider.artistTracks(args.optString(0))),
            )
            "library:getTracksByIds" -> {
                val ids = args.optJSONArray(0) ?: JSONArray()
                val list = ArrayList<String>(ids.length())
                for (i in 0 until ids.length()) list.add(ids.optString(i))
                ok(JSONObject().put("success", true).put("data", LibraryProvider.byIds(list)))
            }
            "library:searchTracks" -> ok(
                JSONObject().put("success", true).put("data", LibraryProvider.search(args.optString(0))),
            )
            "library:quickSearch" -> ok(
                JSONObject().put("success", true).put("data", LibraryProvider.search(args.optString(0), 20)),
            )
            "library:getTrackCount" -> ok(LibraryProvider.count())
            "library:isScanning" -> ok(false)
            "library:getRandomTrack" -> ok(LibraryProvider.randomOne())
            "library:getRandomTracks" -> ok(LibraryProvider.randomMany(args.optInt(0, 20)))
            "library:readTags" -> ok(LibraryProvider.readTags(args.optString(0)))
            "library:writeTags" -> {
                val raw = args.opt(0)
                val edits = when (raw) {
                    is JSONArray -> raw
                    is JSONObject -> JSONArray().put(raw)
                    else -> JSONArray()
                }
                if (edits.length() == 0) {
                    ok(JSONObject().put("success", false).put("error", "缺少标签编辑参数"))
                } else {
                    val outcomes = AudioTags.writeBatch(edits)
                    var written = 0
                    for (index in 0 until outcomes.length()) {
                        if (outcomes.optJSONObject(index)?.optBoolean("success") == true) written++
                    }
                    Log.i("SPlayerBridge", "writeTags " + written + "/" + outcomes.length())
                    ok(JSONObject().put("success", true).put("data", outcomes))
                }
            }
            "library:fetchArtistAvatar" -> ok(
                JSONObject().put("success", true).put("data", JSONObject.NULL),
            )
            "library:prefetchArtistAvatars" -> ok(JSONObject().put("success", true).put("data", JSONObject()))
            "library:pickCoverImage" -> ok(
                JSONObject().put("success", false).put("error", "暂不支持"),
            )
            "library:deleteTracks" -> ok(
                JSONObject().put("success", false).put("error", "暂不支持删除系统媒体库文件"),
            )
            "library:getTracks" -> {
                // 首次进入曲库时还没有任何扫描结果，这里同步补一次，避免界面长期为空
                Log.i("SPlayerBridge", "getTracks, cached=" + LibraryProvider.count())
                // 只补一次：60s 内刚扫过就不重复全量查询 MediaStore
                if (LibraryProvider.count() == 0 && !LibraryProvider.isFresh()) {
                    LibraryProvider.scan(activity)
                }
                ok(JSONObject().put("success", true).put("data", LibraryProvider.all()))
            }
            "library:lookupMusicId" -> {
                // 本地文件的 163key 里带 QQ/网易通用的 musicId，用它把本地曲映射到在线收藏
                val path = args.optString(0)
                val id = runCatching {
                    if (path.isEmpty()) null
                    else top.imsyy.splayer_next.android.bridge.Local163Key.musicIdOf(
                        activity,
                        android.net.Uri.parse(path),
                    )
                }.getOrNull()
                ok(JSONObject().put("success", true).put("data", id ?: JSONObject.NULL))
            }
            "library:getAlbums" -> ok(JSONObject().put("success", true).put("data", LibraryProvider.albums()))
            "library:getArtists" -> ok(JSONObject().put("success", true).put("data", LibraryProvider.artists()))
            "net:fetch" -> ok(NetClient.fetch(args.optJSONObject(0) ?: JSONObject()))
            // fetchBytes 的入参是裸 url 字符串，不是请求对象
            "net:fetchBytes" -> {
                val arg = args.opt(0)
                val raw = if (arg is JSONObject) arg.optString("url") else args.optString(0)
                // 曲库封面是 WebView 内部虚拟域名，原生网络解析不到，直接从 MediaStore 取字节
                if (raw.contains("appassets.androidplatform.net/cover/")) {
                    val albumId = raw.substringAfterLast('/')
                    val bytes = LibraryProvider.albumArt(activity, albumId)
                    Log.i("SPlayerBridge", "albumArt " + albumId + " -> " + (bytes?.size ?: -1))
                    if (bytes == null) throw IllegalStateException("album art unavailable: " + albumId)
                    Log.i("SPlayerBridge", "albumArt ok, bytes=" + bytes.size)
                    ok(
                        JSONObject()
                            .put("success", true)
                            .put("status", 200)
                            .put("bodyBase64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)),
                    )
                } else {
                    val payload = if (arg is JSONObject) arg else JSONObject().put("url", raw)
                    // 与封面分支保持同一形状：bodyBase64 放在顶层，前端才能直接读到
                    val result = NetClient.fetch(payload)
                    val body = result.optString("bodyBase64")
                    if (body.isEmpty()) {
                        ok(JSONObject().put("success", false).put("data", -1))
                    } else {
                        ok(
                            JSONObject()
                                .put("success", true)
                                .put("status", result.optInt("status"))
                                .put("bodyBase64", body),
                        )
                    }
                }
            }
            "system:setGestureExclusion" -> {
                val rect = args.optJSONObject(0)
                activity.runOnUiThread { activity.applyGestureExclusion(rect) }
                ok(null)
            }
            // picker 选中的 uri 可能被持久授权，释放时按读写两种标志各试一次
            "picker:release" -> {
                val raw = args.optJSONObject(0)?.optString("uri").orEmpty()
                    .ifEmpty { args.optString(0) }
                if (raw.isNotEmpty()) Pickers.releasePersistable(activity, android.net.Uri.parse(raw))
                ok(true)
            }
            "files:stat" -> ok(DocumentIo.stat(activity, android.net.Uri.parse(args.optString(0))))
            "files:readText" -> ok(DocumentIo.readText(activity, android.net.Uri.parse(args.optString(0))))
            "files:readBase64" -> ok(
                DocumentIo.readBase64(activity, android.net.Uri.parse(args.optString(0))),
            )
            "files:writeText" -> {
                DocumentIo.writeText(
                    activity,
                    android.net.Uri.parse(args.optString(0)),
                    args.optString(1),
                )
                ok(true)
            }
            "files:writeBase64" -> {
                DocumentIo.writeBase64(
                    activity,
                    android.net.Uri.parse(args.optString(0)),
                    base64Of(args.opt(1)),
                )
                ok(true)
            }
            "files:thumbnailBase64" -> ok(
                DocumentIo.thumbnailBase64(
                    activity,
                    android.net.Uri.parse(args.optString(0)),
                    args.optInt(1, 300),
                ),
            )
            "files:copyToBucket" -> ok(
                DocumentIo.copyToBucket(
                    activity,
                    android.net.Uri.parse(args.optString(0)),
                    args.optString(1),
                ),
            )
            "files:clearBucket" -> {
                DocumentIo.clearBucket(args.optString(0))
                ok(true)
            }
            // 下载引擎：前端 handlers/pickers.ts 转发到这里，channel 带 engine 前缀避免与本地 handler 同名递归
            "download:engine:start", "download:engine:retry" ->
                Downloads.start(args.optJSONObject(0) ?: org.json.JSONObject())
            "download:engine:startMany" ->
                Downloads.startMany(args.optJSONObject(0) ?: org.json.JSONObject())
            "download:engine:list" -> Downloads.list()
            "download:engine:cancel" -> {
                val taskId = args.optJSONObject(0)?.optString("taskId").orEmpty()
                org.json.JSONObject().put("ok", Downloads.cancel(taskId))
            }
            "download:engine:remove" -> {
                val taskId = args.optJSONObject(0)?.optString("taskId").orEmpty()
                org.json.JSONObject().put("ok", Downloads.remove(taskId))
            }
            "download:engine:clearFinished" ->
                org.json.JSONObject().put("ok", true).put("removed", Downloads.clearFinished())
            "download:engine:lookup" ->
                Downloads.lookup(args.optJSONObject(0)?.optJSONObject("track") ?: org.json.JSONObject())
                    ?: org.json.JSONObject.NULL
            "download:engine:resolution" -> {
                val payload = args.optJSONObject(0) ?: org.json.JSONObject()
                org.json.JSONObject().put(
                    "ok",
                    Downloads.submitResolution(
                        payload.optString("taskId"),
                        payload.optJSONObject("res"),
                    ),
                )
            }
            "download:engine:resolveFailed" -> {
                val taskId = args.optJSONObject(0)?.optString("taskId").orEmpty()
                org.json.JSONObject().put("ok", Downloads.failResolution(taskId))
            }
            // Lyricon（词幕）推送：歌曲/歌词、播放位置、播放状态
            "lyricon:setSong" -> {
                LyriconBridge.ensure(activity.applicationContext)
                LyriconBridge.setSong(args.optJSONObject(0) ?: JSONObject())
            }
            "lyricon:setPosition" -> {
                LyriconBridge.setPosition(args.optLong(0))
                ok(null)
            }
            "lyricon:setPlaying" -> {
                LyriconBridge.setPlaybackState(args.optBoolean(0))
                ok(null)
            }
            "lyricon:release" -> {
                LyriconBridge.release()
                ok(null)
            }
            "system:consumePendingAudioFiles" -> ok(PendingIntake.drain())
            "system:consumePendingProtocolUrl" -> ok(null)
            "system:openLogsDir", "system:showInExplorer", "system:relaunch" -> ok(null)
            "window:minimize" -> { activity.runOnUiThread { activity.moveTaskToBack(true) }; ok(null) }
            "window:quit" -> { activity.runOnUiThread { activity.finish() }; ok(null) }
            "window:toggleFullscreen" -> {
                activity.runOnUiThread { activity.toggleFullscreenMode() }
                val enabled = activity.isFullscreenMode()
                EventBus.emit("window:fullscreenChange", JSONObject().put("value", enabled))
                ok(null)
            }
            "window:isFullscreen" -> ok(activity.isFullscreenMode())
            // 系统媒体控制开关：关闭时停止媒体前台服务
            // 用在线曲目 id 反查本地文件（163 key 对照表）
            "library:findLocalByKey" -> {
                val key = args.optString(0)
                val title = args.optString(1)
                val artist = args.optString(2)
                val duration = args.optLong(3)
                val helper = top.imsyy.splayer_next.android.bridge.Local163Key
                val path = helper.findLocal(activity, key)
                    ?: helper.findByFingerprint(activity, title, artist, duration)
                android.util.Log.i(
                    "SPlayer163",
                    "findLocal key=" + key + " title=" + title + " artist=" + artist +
                        " dur=" + duration + " hit=" + (path != null),
                )
                ok(JSONObject().put("path", path).put("count", helper.size(activity)))
            }
            // 歌曲缓存未实现，返回空表示「没有缓存」，不能返回错误对象
            "cache:song:lookup" -> ok(null)
            "cache:song:fetch" -> ok(null)
            "cache:song:cancel" -> ok(null)
            "system:setMediaControlEnabled" -> {
                PlaybackEngine.setMediaControlEnabled(args.optBoolean(0, true))
                ok(null)
            }
            // 未实现的 channel 以 IpcResponse 形状失败返回，避免前端 Promise 链整体 reject
            else -> ok(
                JSONObject().put("success", false).put("error", "channel not implemented: " + channel),
            )
        }
    }

    /**
     * 解析二进制参数：JS 侧 ArrayBuffer 被编成 {__t:"ab", b64}，透传时在原生侧解包
     * @param value - base64 文本或编码信封
     * @returns base64 文本
     */
    /**
     * 把前端下发的增益数组转成 FloatArray
     * @param array - 10 段 dB 数组
     * @returns 与入参等长的增益数组，非法项按 0 处理
     */
    private fun gainsOf(array: JSONArray?): FloatArray {
        if (array == null) return FloatArray(0)
        val gains = FloatArray(array.length())
        for (i in gains.indices) gains[i] = array.optDouble(i, 0.0).toFloat()
        return gains
    }

    /**
     * 解析二进制参数：JS 侧 ArrayBuffer 被编成 {__t:"ab", b64}，透传时在原生侧解包
     * @param value - base64 文本或编码信封
     * @returns base64 文本
     */
    private fun base64Of(value: Any?): String {
        if (value is JSONObject && value.optString("__t") == "ab") return value.optString("b64")
        return value?.toString() ?: ""
    }

    /**
     * 包装为前端 IpcResponse 形状
     * @param data - 业务数据
     * @returns { success, data }
     */
    private fun ok(data: Any?): JSONObject {
        // 已经自带 success 的业务对象直接返回，避免被再包一层 data 让前端读到嵌套结构
        if (data is JSONObject && data.has("success")) return data
        val payload = JSONObject().put("success", true)
        if (data != null) payload.put("data", data)
        return payload
    }
}