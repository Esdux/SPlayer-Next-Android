package top.imsyy.splayer_next.android.playback

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject
import top.imsyy.splayer_next.android.SPlayerContext

/**
 * 单例播放引擎，替代桌面端 Rust audio-engine
 */
@UnstableApi
object PlaybackEngine {

    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())
    private var eventSink: ((String, String) -> Unit)? = null
    private var lastState = "idle"
    private var tickRunning = false

    /** 当前曲目原始信息，供 getStreamInfo 使用 */
    private var currentSource: String = ""
    private var currentTitle: String = ""

    /**
     * 初始化引擎并安装监听
     * @param context - 应用上下文
     */
    fun init(context: Context) {
        if (::player.isInitialized) return
        player = ExoPlayer.Builder(context.applicationContext).build().apply {
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_ENDED -> {
                            setState("stopped")
                            emit("ended", null)
                        }
                        Player.STATE_BUFFERING -> setState("loading")
                        Player.STATE_READY -> setState(if (isPlaying) "playing" else "paused")
                        Player.STATE_IDLE -> if (lastState == "loading") setState("idle")
                    }
                    pushStatus()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    setState(if (isPlaying) "playing" else "paused")
                    pushStatus()
                    if (isPlaying) startTicker() else stopTicker()
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    emit("sourceError", JSONObject())
                }
            })
            volume = 1f
        }
        startTicker()
    }

    /**
     * 绑定事件出口
     * @param sink - (channel, json) 推送函数
     */
    fun bind(sink: (String, String) -> Unit) {
        eventSink = sink
    }

    /**
     * 推送事件
     * @param type - 事件类型
     * @param data - 附加数据
     */
    private fun emit(type: String, data: JSONObject?) {
        val event = JSONObject().put("type", type)
        if (data != null) event.put("data", data)
        eventSink?.invoke("player:event", event.toString())
    }

    /**
     * 更新状态机并广播
     * @param state - 目标状态
     */
    private fun setState(state: String) {
        if (lastState == state) return
        lastState = state
    }

    /**
     * 组装状态快照
     * @returns PlayerStatus 结构的 JSON
     */
    fun status(): JSONObject {
        val duration = if (player.duration > 0) player.duration else 0L
        return JSONObject()
            .put("state", if (player.isPlaying) "playing" else if (lastState == "idle") "idle" else lastState)
            .put("position", player.currentPosition.coerceAtLeast(0))
            .put("duration", duration)
            .put("volume", player.volume.toDouble())
            .put("speed", player.playbackParameters.speed.toDouble())
            .put("isFinished", player.playbackState == Player.STATE_ENDED)
    }

    /**
     * 推送 status 事件
     */
    private fun pushStatus() {
        if (!::player.isInitialized) return
        emit("status", status())
    }

    /** 启动 200ms 位置推送 */
    private fun startTicker() {
        if (tickRunning) return
        tickRunning = true
        handler.post(object : Runnable {
            override fun run() {
                if (!tickRunning) return
                if (::player.isInitialized && player.isPlaying) {
                    val payload = JSONObject()
                        .put("position", player.currentPosition.coerceAtLeast(0))
                        .put("duration", if (player.duration > 0) player.duration else 0L)
                    emit("position", payload)
                }
                handler.postDelayed(this, 200)
            }
        })
    }

    /** 停止位置推送 */
    private fun stopTicker() {
        tickRunning = false
    }

    /**
     * 读取音频元信息
     * @param source - 本地路径或 content URI
     * @returns title/artist/album/duration/cover 的 JSON
     */
    private fun readMeta(source: String): JSONObject {
        val meta = JSONObject()
        meta.put("duration", 0)
        runCatching {
            val retriever = MediaMetadataRetriever()
            if (source.startsWith("content://")) {
                retriever.setDataSource(SPlayerContext.get(), Uri.parse(source))
            } else {
                retriever.setDataSource(source)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { meta.put("title", it) }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { artist ->
                meta.put("artists", JSONArray().put(JSONObject().put("name", artist)))
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { album ->
                meta.put("album", JSONObject().put("name", album))
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                meta.put("duration", it)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.let { bitrate ->
                meta.put(
                    "quality",
                    JSONObject()
                        .put("sampleRate", 44100)
                        .put("channels", 2)
                        .put("bitsPerSample", 16)
                        .put("bitRate", bitrate)
                        .put("codec", ""),
                )
            }
            retriever.release()
        }
        return meta
    }

    /**
     * 加载音源
     * @param source - 本地路径或网络地址
     * @param autoPlay - 是否自动播放
     * @param meta - 渲染层权威元数据
     * @returns IpcResponse<LoadResult> 的 data 部分
     */
    fun load(source: String, autoPlay: Boolean, meta: JSONObject?): JSONObject {
        currentSource = source
        player.setMediaItem(MediaItem.fromUri(source))
        player.prepare()
        player.playWhenReady = autoPlay
        val info = readMeta(source)
        meta?.optString("title")?.takeIf { it.isNotEmpty() }?.let { info.put("title", it) }
        currentTitle = info.optString("title", "")
        val detail = JSONObject()
            .put("quality", info.optJSONObject("quality") ?: JSONObject().put("sampleRate", 44100).put("channels", 2).put("bitsPerSample", 16).put("bitRate", 0).put("codec", ""))
            .put("externalLyrics", JSONArray())
        val nextState = if (autoPlay) "playing" else "paused"
        val result = JSONObject()
        result.put("detail", detail)
        result.put("mediaInfo", info)
        result.put(
            "playback",
            JSONObject()
                .put("position", 0)
                .put("state", nextState)
                .put("speed", player.playbackParameters.speed.toDouble())
                .put("timestamp", System.currentTimeMillis()),
        )
        setState(nextState)
        return result
    }

    /**
     * 播放
     */
    fun play() {
        player.play()
    }

    /**
     * 暂停
     */
    fun pause() {
        player.pause()
    }

    /**
     * 停止并清空
     */
    fun stop() {
        player.stop()
        player.clearMediaItems()
        lastState = "stopped"
        pushStatus()
    }

    /**
     * 跳转
     * @param positionMs - 目标毫秒
     */
    fun seek(positionMs: Long) {
        player.seekTo(positionMs)
        emit("seek", JSONObject().put("position", positionMs))
    }

    /**
     * 设置音量
     * @param volume - 0..1
     */
    fun setVolume(volume: Float) {
        player.volume = volume.coerceIn(0f, 1f)
    }

    /**
     * 设置倍速
     * @param speed - 0.5..2.0
     */
    fun setSpeed(speed: Float) {
        player.playbackParameters = player.playbackParameters.withSpeed(speed.coerceIn(0.25f, 4f))
    }

    /**
     * 当前音量
     * @returns 0..1
     */
    fun volume(): Float = if (::player.isInitialized) player.volume else 1f

    /**
     * 流信息，安卓侧按通用值上报
     * @returns AudioStreamInfo 结构
     */
    fun streamInfo(): JSONObject = JSONObject()
        .put("deviceName", "Android Audio")
        .put("isExclusive", false)
        .put("outputSampleRate", 48000)
        .put("outputChannels", 2)
        .put("outputBits", 16)
        .put("sourceSampleRate", 44100)
        .put("sourceBits", 16)
        .put("isResampling", true)
        .put("isEqualizerActive", false)
        .put("isTempoActive", false)
        .put("speed", if (::player.isInitialized) player.playbackParameters.speed.toDouble() else 1.0)
        .put("isNormalizationActive", false)
        .put("isLimiterActive", false)

    /**
     * 当前原始音源
     * @returns 路径或 URL
     */
    fun source(): String = currentSource
}
