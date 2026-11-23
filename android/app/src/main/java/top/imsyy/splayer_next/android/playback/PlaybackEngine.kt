package top.imsyy.splayer_next.android.playback

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.json.JSONArray
import org.json.JSONObject
import top.imsyy.splayer_next.android.SPlayerContext
import top.imsyy.splayer_next.android.audiofx.AudioEffects
import top.imsyy.splayer_next.android.bridge.LyriconBridge

/**
 * 播放引擎：A/B 双实例预载与交叉淡入，替代桌面端 Rust audio-engine
 *
 * 只有 activeSlot 指向的实例对外发声并派发事件，空闲实例专供预载；
 * 交接完成后旧实例立即释放，任何异常都在活动实例上直接切歌，不让播放卡住
 */
@UnstableApi
object PlaybackEngine {

    /** 网易 CDN 校验的浏览器 UA，与网页版保持一致 */
    private const val WEB_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0"

    /** A/B 槽位数量 */
    private const val SLOT_COUNT = 2

    /** 交叉淡化的调度节拍，贴近屏幕刷新率 */
    private const val FADE_TICK_MS = 16L

    /** 淡化时长上限，避免渲染层传来异常值把整首歌压没 */
    private const val MAX_FADE_MS = 8000

    /** 频谱推送节拍，与渲染层 50ms 的插值周期对齐 */
    private const val FFT_PUSH_MS = 50L

    /** 双实例槽位，下标 0/1 轮换 */
    private val slots = arrayOfNulls<ExoPlayer>(SLOT_COUNT)

    /** 当前活动槽位，只有它对外发声并派发事件 */
    private var activeSlot = 0

    private var appContext: Context? = null
    private var mediaControlEnabled = true

    /** 用户意图：true 表示应当处于播放态，任何加载完成后都据此续播 */
    @Volatile
    var userWantsPlay = false
        private set
    private val handler = Handler(Looper.getMainLooper())
    private var eventSink: ((String, String) -> Unit)? = null
    private var lastState = "idle"
    private var tickRunning = false

    /** 渲染层是否申请了频谱，暂停后仍保留意图，恢复播放时自动续采 */
    private var wantFft = false

    /** 频谱推送循环是否在跑 */
    private var fftTickRunning = false

    /** 当前曲目原始信息，供 getStreamInfo 使用 */
    private var currentSource: String = ""

    /** 预载切换后实例自身没有 MediaMetadata，通知栏与锁屏靠这几个字段回填 */
    private var mediaTitle: String = ""
    private var mediaArtist: String = ""
    private var mediaArtwork: Uri? = null

    /** 用户设定音量，交叉淡化按它做等功率分配 */
    private var userVolume = 1f

    /** 淡化时长，0 表示直接切歌；由渲染层 setFadeDuration 下发 */
    private var fadeDurationMs = 200

    /** 外部监听（媒体服务等），随活动实例迁移 */
    private val externalListeners = mutableListOf<Player.Listener>()

    /** 已预热的下一首，位于空闲槽位 */
    private var prepared: PreparedSlot? = null

    /** 进行中的过渡计划 */
    private var transition: TransitionPlan? = null

    /** 活动实例，未初始化时抛异常 */
    private val player: ExoPlayer
        get() = slots[activeSlot] ?: throw IllegalStateException("PlaybackEngine 未初始化")

    /**
     * 预载槽位
     * @property id - 预载任务标识，由渲染层生成
     * @property source - 已就绪的音源地址
     * @property startMs - 交接后的曲目起点
     * @property player - 承载预载音源的空闲实例
     */
    private class PreparedSlot(
        val id: String,
        val source: String,
        val startMs: Long,
        val player: ExoPlayer,
    )

    /**
     * 交叉过渡计划
     * @property player - 预载实例，过渡完成后成为活动实例
     * @property source - 下一曲音源
     * @property meta - 下一曲权威元数据
     * @property fadeMs - 淡出与淡入的时长
     */
    private class TransitionPlan(
        val player: ExoPlayer,
        val source: String,
        val meta: JSONObject?,
        val fadeMs: Long,
    ) {
        /** 已进入淡化阶段，曲尾事件不再重复触发 */
        var started = false

        /** 延迟调度句柄，取消时用它摘掉 */
        var task: Runnable? = null
    }

    /**
     * 初始化引擎并安装监听
     * @param context - 应用上下文
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (slots[activeSlot] != null) return
        slots[activeSlot] = buildPlayer()
        attachExternal(player)
        startTicker()
    }

    /**
     * 创建播放器实例
     * 在线音源走 http CDN，必须带浏览器 UA 与 Referer，否则被 403
     * @returns 已挂好内部监听、音量为用户音量的实例
     */
    private fun buildPlayer(): ExoPlayer {
        val ctx = appContext ?: throw IllegalStateException("PlaybackEngine 未初始化")
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(WEB_UA)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to "https://music.163.com/"))
        val dataSourceFactory = DefaultDataSource.Factory(ctx, httpFactory)
        val instance = ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        instance.setHandleAudioBecomingNoisy(true)
        // 预载实例在 prepareNext 里会被显式静音，这里统一给用户音量，避免活动实例被误置 0
        instance.volume = userVolume
        attachInternalListener(instance)
        return instance
    }

    /**
     * 给实例挂内部监听，回调只处理活动实例的事件
     * @param instance - 新建或复用的播放器实例
     */
    private fun attachInternalListener(instance: ExoPlayer) {
        instance.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // 预载实例的缓冲过程不对外可见，事件只从活动实例发
                if (slots[activeSlot] !== instance) return
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        val plan = transition
                        if (plan != null) {
                            // 还在等待交接点就走到了曲尾，提前启动淡化，避免丢一次切歌
                            handler.post { runCatching { beginCrossfade(plan, instance) } }
                        } else {
                            setState("stopped")
                            emit("ended", null)
                        }
                    }
                    Player.STATE_BUFFERING -> setState("loading")
                    Player.STATE_READY -> setState(if (instance.isPlaying) "playing" else "paused")
                    Player.STATE_IDLE -> if (lastState == "loading") setState("idle")
                }
                // READY 之后音频会话已经分配，效果器要在这里绑定
                syncAudioEffects(instance)
                pushStatus()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (slots[activeSlot] !== instance) return
                setState(if (isPlaying) "playing" else "paused")
                pushStatus()
                LyriconBridge.setPlaybackState(isPlaying)
                if (isPlaying) {
                    startTicker()
                    syncAudioEffects(instance)
                    startFftTicker()
                } else {
                    stopTicker()
                    stopFftTicker(pushSilent = true)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (slots[activeSlot] !== instance) return
                emit("sourceError", JSONObject())
            }
        })
    }

    /**
     * 登记外部监听，切歌时随活动实例迁移
     * @param listener - 媒体服务等外部监听
     */
    fun addListener(listener: Player.Listener) {
        if (externalListeners.contains(listener)) return
        externalListeners.add(listener)
        slots[activeSlot]?.addListener(listener)
    }

    /**
     * 摘掉外部监听，从所有槽位移除
     * @param listener - 之前登记的监听
     */
    fun removeListener(listener: Player.Listener) {
        externalListeners.remove(listener)
        slots.forEach { instance -> instance?.let { runCatching { it.removeListener(listener) } } }
    }

    /**
     * 把外部监听挂到指定实例
     * @param instance - 即将成为活动的实例
     */
    private fun attachExternal(instance: ExoPlayer) {
        externalListeners.forEach { runCatching { instance.addListener(it) } }
    }

    /**
     * 把外部监听从指定实例摘掉
     * @param instance - 即将释放的实例
     */
    private fun detachExternal(instance: ExoPlayer) {
        externalListeners.forEach { runCatching { instance.removeListener(it) } }
    }

    /**
     * 取空闲实例，没有就按需创建，保证 A/B 两槽各自独立
     * @returns 可承载预载的空闲实例，创建失败时为 null
     */
    private fun idleInstance(): ExoPlayer? {
        val index = (activeSlot + 1) % SLOT_COUNT
        slots[index]?.let { return it }
        return runCatching { buildPlayer() }.onSuccess { slots[index] = it }.getOrNull()
    }

    /**
     * 释放实例并归还槽位
     * @param instance - 要释放的播放器
     * @param index - 实例所在的槽位下标
     */
    private fun releaseInstance(instance: ExoPlayer, index: Int) {
        detachExternal(instance)
        // 效果器挂在被释放实例的会话上，先解绑再放播放器，避免效果器访问已销毁的会话
        runCatching {
            if (AudioEffects.sessionId() > 0 && AudioEffects.sessionId() == instance.audioSessionId) {
                AudioEffects.release()
            }
        }
        runCatching { instance.stop() }
        runCatching { instance.clearMediaItems() }
        runCatching { instance.release() }
        if (slots[index] === instance) slots[index] = null
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
     * 组装状态快照，全部取自活动实例
     * @returns PlayerStatus 结构的 JSON
     */
    fun status(): JSONObject {
        val duration = if (player.duration > 0) player.duration else 0L
        return JSONObject()
            .put("state", if (player.isPlaying) "playing" else if (lastState == "idle") "idle" else lastState)
            .put("position", player.currentPosition.coerceAtLeast(0))
            .put("duration", duration)
            .put("volume", userVolume.toDouble())
            .put("speed", player.playbackParameters.speed.toDouble())
            .put("isFinished", player.playbackState == Player.STATE_ENDED)
    }

    /**
     * 推送 status 事件
     */
    private fun pushStatus() {
        if (slots[activeSlot] == null) return
        emit("status", status())
    }

    /** 启动 200ms 位置推送 */
    private fun startTicker() {
        if (tickRunning) return
        tickRunning = true
        handler.post(object : Runnable {
            override fun run() {
                if (!tickRunning) return
                val current = slots[activeSlot]
                if (current != null && current.isPlaying) {
                    val payload = JSONObject()
                        .put("position", current.currentPosition.coerceAtLeast(0))
                        .put("duration", if (current.duration > 0) current.duration else 0L)
                    emit("position", payload)
                    LyriconBridge.setPosition(player.currentPosition.coerceAtLeast(0))
                    handler.postDelayed(this, 200)
                } else {
                    // 暂停时降频，恢复播放后下一拍就会回到 200ms
                    handler.postDelayed(this, 500)
                }
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
     * 把封面地址转成系统通知能读取的 Uri，本地曲用 MediaStore 的 albumart
     * @param meta - 渲染层元数据
     * @returns 可读取的封面 Uri，无法解析时为 null
     */
    private fun artworkUri(meta: JSONObject?): Uri? {
        val cover = meta?.optString("cover").orEmpty().ifEmpty { meta?.optString("coverOriginal").orEmpty() }
        if (cover.isEmpty()) return null
        if (cover.contains("/cover/")) {
            val albumId = cover.substringAfterLast("/cover/").substringBefore("?")
            if (albumId.isNotEmpty()) {
                return Uri.parse("content://media/external/audio/albumart/" + albumId)
            }
        }
        return if (cover.startsWith("http") || cover.startsWith("file")) Uri.parse(cover) else null
    }

    /**
     * 把渲染层传来的歌手数组拼成可读文本，避免通知与锁屏露出 JSON
     * @param meta - 渲染层元数据
     * @returns 歌手名以斜杠连接
     */
    private fun artistsText(meta: JSONObject?): String {
        val raw = meta?.opt("artists") ?: return ""
        if (raw is JSONArray) {
            val names = (0 until raw.length()).mapNotNull { index ->
                raw.optJSONObject(index)?.optString("name")?.takeIf { it.isNotBlank() }
            }
            return names.joinToString(" / ")
        }
        return raw.toString()
    }

    /**
     * 登记下一曲元数据，预载实例没有 MediaMetadata，通知栏从这里读
     * @param meta - 渲染层下发的权威曲目
     */
    private fun applyPendingMeta(meta: JSONObject?) {
        if (meta == null) return
        val title = meta.optString("title")
        if (title.isNotEmpty()) mediaTitle = title
        val artists = artistsText(meta)
        if (artists.isNotEmpty()) mediaArtist = artists
        artworkUri(meta)?.let { mediaArtwork = it }
    }

    /**
     * 判断音源是否可交给 ExoPlayer
     * 前端在取流失败时可能把错误对象当地址传进来
     * @param source - 音源地址
     * @returns 协议受支持时为真
     */
    private fun isPlayableSource(source: String): Boolean =
        source.startsWith("http") || source.startsWith("file") || source.startsWith("content")

    /**
     * 组装前端 LoadResult 结构
     * @param source - 音源地址
     * @param meta - 渲染层权威元数据
     * @param positionMs - 起始位置
     * @param state - 播放状态
     * @param info - 已读取的音频元信息，省略时就地读取
     * @returns { detail, mediaInfo, playback } 结构
     */
    private fun buildLoadResult(
        source: String,
        meta: JSONObject?,
        positionMs: Long,
        state: String,
        // 渲染层已经给了标题就跳过 MediaMetadataRetriever，避免在主线程做文件 IO
        info: JSONObject = if (meta?.optString("title").orEmpty().isNotEmpty()) {
            JSONObject()
        } else {
            readMeta(source)
        },
    ): JSONObject {
        meta?.optString("title")?.takeIf { it.isNotEmpty() }?.let { info.put("title", it) }
        val detail = JSONObject()
            .put(
                "quality",
                info.optJSONObject("quality")
                    ?: JSONObject().put("sampleRate", 44100).put("channels", 2).put("bitsPerSample", 16)
                        .put("bitRate", 0).put("codec", ""),
            )
            .put("externalLyrics", JSONArray())
        return JSONObject()
            .put("detail", detail)
            .put("mediaInfo", info)
            .put(
                "playback",
                JSONObject()
                    .put("position", positionMs.coerceAtLeast(0))
                    .put("state", state)
                    .put("speed", slots[activeSlot]?.playbackParameters?.speed?.toDouble() ?: 1.0)
                    .put("timestamp", System.currentTimeMillis()),
            )
    }

    /**
     * 加载音源，一律落在活动实例上
     * 手动切歌会作废预载槽位，避免交接时读到与队列不一致的下一首
     * @param source - 本地路径或网络地址
     * @param autoPlay - 是否自动播放
     * @param meta - 渲染层权威元数据
     * @returns IpcResponse<LoadResult> 的 data 部分
     */
    fun load(source: String, autoPlay: Boolean, meta: JSONObject?): JSONObject {
        cancelTransition()
        cancelPrepared()
        currentSource = source
        android.util.Log.d(
            "SPlayerMedia",
            "load autoPlay=" + autoPlay + " wants=" + userWantsPlay + " src=" + source.take(60),
        )
        if (!isPlayableSource(source)) {
            android.util.Log.w("SPlayerMedia", "非法音源，忽略加载")
            return JSONObject()
                .put("detail", JSONObject())
                .put("mediaInfo", JSONObject())
                .put("playback", JSONObject().put("state", lastState))
        }
        // 媒体项带上渲染层给的标题与歌手，通知栏与锁屏才能显示曲名
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(meta?.optString("title").orEmpty())
            .setArtist(artistsText(meta))
            .setArtworkUri(artworkUri(meta))
            .build()
        player.setMediaItem(
            MediaItem.Builder().setUri(source).setMediaMetadata(metadata).build(),
        )
        // 切歌重试会把 autoPlay 传成 false，用户意图仍在播放就继续播，避免「点一下才响」
        if (autoPlay) userWantsPlay = true
        val effectiveAutoPlay = autoPlay || userWantsPlay
        player.prepare()
        player.playWhenReady = effectiveAutoPlay
        // 自动播放的加载一律登记，就绪后由服务保证真的开始播
        if (effectiveAutoPlay) PlaybackService.markPendingAutoPlay()
        // 媒体项就绪后再拉起前台服务，Media3 才能立即发布通知并保持前台
        if (autoPlay) startMediaService()
        val info = readMeta(source)
        mediaTitle = info.optString("title", "")
        mediaArtist = artistsText(meta)
        mediaArtwork = artworkUri(meta)
        val nextState = if (effectiveAutoPlay) "playing" else "paused"
        setState(nextState)
        return buildLoadResult(source, meta, 0, nextState, info)
    }

    /**
     * 预载下一首到空闲实例，不影响当前播放
     * 返回 ready 只表示槽位已经建立，真正的就绪状态在交接时再校验
     * @param id - 预载任务标识，交接或取消时用它匹配
     * @param source - 本地文件或已完成下载的缓存文件
     * @param startMs - 交接后的起点，单位毫秒
     * @returns { ready } 形式的预载结果
     */
    fun prepareNext(id: String, source: String, startMs: Long): JSONObject {
        cancelPrepared()
        if (!isPlayableSource(source)) {
            android.util.Log.w("SPlayerMedia", "预载音源非法，跳过")
            return JSONObject().put("ready", false)
        }
        val instance = idleInstance() ?: return JSONObject().put("ready", false)
        return runCatching {
            instance.stop()
            instance.clearMediaItems()
            // 预载实例全程静音，只有进入过渡时才由淡入曲线抬到用户音量
            instance.volume = 0f
            instance.playbackParameters =
                instance.playbackParameters.withSpeed(slots[activeSlot]?.playbackParameters?.speed ?: 1f)
            // 先清掉播放意图再 prepare，避免预载实例短暂出声
            instance.playWhenReady = false
            instance.setMediaItem(MediaItem.Builder().setUri(source).build(), startMs.coerceAtLeast(0))
            instance.prepare()
            prepared = PreparedSlot(id, source, startMs.coerceAtLeast(0), instance)
            JSONObject().put("ready", true)
        }.getOrElse { error ->
            android.util.Log.w("SPlayerMedia", "预载失败，回落到直接切歌", error)
            val index = slots.indexOfFirst { it === instance }
            if (index >= 0) releaseInstance(instance, index)
            JSONObject().put("ready", false)
        }
    }

    /**
     * 释放预载槽位
     * 槽位实例已不在空闲位置时只清空媒体，避免把活动实例停掉
     * @param id - 预载任务标识，省略时释放任意槽位
     */
    fun cancelPrepared(id: String? = null) {
        val slot = prepared ?: return
        if (id != null && slot.id != id) return
        prepared = null
        val index = slots.indexOfFirst { it === slot.player }
        if (index >= 0 && index != activeSlot) {
            releaseInstance(slot.player, index)
        } else {
            runCatching {
                slot.player.stop()
                slot.player.clearMediaItems()
                slot.player.volume = userVolume
            }
        }
    }

    /**
     * 由剩余时长裁剪淡化长度，避免淡化压过整首歌
     * @param remainingMs - 剩余墙钟时间
     * @returns 实际使用的淡化时长，0 表示直接切换
     */
    private fun fadeForRemaining(remainingMs: Long): Long {
        if (fadeDurationMs <= 0) return 0
        return fadeDurationMs.toLong().coerceAtMost(remainingMs.coerceAtLeast(0))
    }

    /**
     * 过渡失败的统一返回
     * @param reason - 失败原因，交给渲染层决定是否回落到正常切歌
     * @returns { success: false, error } 结构
     */
    private fun transitionFailed(reason: String): JSONObject =
        JSONObject().put("success", false).put("error", reason)

    /**
     * 消费预载槽位并交叉切换到下一曲
     * 剩余时长决定开始淡化的时刻，淡出与淡入在曲尾前同步进行；
     * 返回的 data 供渲染层立即更新界面，音频按计划在交接点完成切换
     * @param id - 预载槽位标识
     * @param source - 下一曲音源，用于校验槽位是否被替换
     * @param remainingMs - 当前曲目剩余的墙钟时间
     * @param meta - 下一曲权威元数据
     * @returns { success, data } 形式的加载结果，失败时附带原因
     */
    fun transitionPrepared(id: String, source: String, remainingMs: Long, meta: JSONObject?): JSONObject {
        if (!userWantsPlay) return transitionFailed("播放已暂停")
        val slot = prepared ?: return transitionFailed("预载槽位不存在")
        if (slot.id != id || slot.source != source) return transitionFailed("预载槽位已更换")
        if (slot.player.playerError != null ||
            slot.player.playbackState == Player.STATE_IDLE ||
            slot.player.playbackState == Player.STATE_ENDED
        ) {
            cancelPrepared(id)
            return transitionFailed("预载音源未就绪")
        }
        val old = slots[activeSlot] ?: return transitionFailed("播放器未初始化")
        cancelTransition()
        val fadeMs = fadeForRemaining(remainingMs)
        val delayMs = (remainingMs - fadeMs).coerceAtLeast(0)
        val plan = TransitionPlan(slot.player, slot.source, meta, fadeMs)
        plan.task = Runnable { runCatching { beginCrossfade(plan, old) } }
        prepared = null
        transition = plan
        handler.postDelayed(plan.task!!, delayMs)
        return JSONObject().put("success", true)
            .put("data", buildLoadResult(slot.source, meta, slot.startMs, "playing"))
    }

    /**
     * 启动交叉淡化：旧实例按等功率曲线降到静音，预载实例同步升到用户音量
     * @param plan - 过渡计划
     * @param old - 淡化前的活动实例
     */
    private fun beginCrossfade(plan: TransitionPlan, old: ExoPlayer) {
        if (transition !== plan || plan.started) return
        plan.started = true
        if (slots[activeSlot] !== old || slots.none { it === plan.player }) {
            fallbackSwitch(plan)
            return
        }
        if (plan.player.playbackState != Player.STATE_READY) {
            // 预载还没真正就绪，放弃渐变改硬切，避免淡出后长时间静音
            fallbackSwitch(plan)
            return
        }
        val started = runCatching {
            plan.player.volume = 0f
            plan.player.playWhenReady = true
        }.isSuccess
        if (!started) {
            fallbackSwitch(plan)
            return
        }
        if (plan.fadeMs <= 0) {
            finishCrossfade(plan, old)
            return
        }
        val startedAt = System.currentTimeMillis()
        handler.post(object : Runnable {
            override fun run() {
                if (transition !== plan) return
                val progress = ((System.currentTimeMillis() - startedAt).toFloat() / plan.fadeMs)
                    .coerceIn(0f, 1f)
                // 等功率交叉：两段信号按 cos/sin 分配，交叉点的听感不会被压塌
                val fadeIn = kotlin.math.sin(progress.toDouble() * Math.PI / 2).toFloat()
                old.volume = (userVolume * (1f - fadeIn)).coerceIn(0f, 1f)
                plan.player.volume = (userVolume * fadeIn).coerceIn(0f, 1f)
                if (progress >= 1f) {
                    finishCrossfade(plan, old)
                } else {
                    handler.postDelayed(this, FADE_TICK_MS)
                }
            }
        })
    }

    /**
     * 完成切换：活动实例与媒体会话绑定改到预载实例，旧实例释放
     * @param plan - 过渡计划
     * @param old - 已淡出的旧实例
     */
    private fun finishCrossfade(plan: TransitionPlan, old: ExoPlayer) {
        if (transition !== plan) return
        transition = null
        plan.task?.let { handler.removeCallbacks(it) }
        val targetIndex = slots.indexOfFirst { it === plan.player }
        if (targetIndex < 0) {
            fallbackSwitch(plan)
            return
        }
        val oldIndex = slots.indexOfFirst { it === old }
        // 先把活动指针切到新实例，旧实例随后的状态回调就不会再被当成活动实例
        activeSlot = targetIndex
        plan.player.volume = userVolume
        currentSource = plan.source
        applyPendingMeta(plan.meta)
        // 效果器先挂到新实例的会话，再释放旧实例，否则频谱会跟着旧会话一起被释放
        syncAudioEffects(plan.player)
        if (oldIndex >= 0 && oldIndex != activeSlot) releaseInstance(old, oldIndex)
        attachExternal(plan.player)
        setState("playing")
        pushStatus()
    }

    /**
     * 过渡失败后的兜底：直接把预载实例设为活动实例，宁可硬切也不能卡住播放
     * @param plan - 过渡计划
     */
    private fun fallbackSwitch(plan: TransitionPlan) {
        transition = null
        plan.task?.let { handler.removeCallbacks(it) }
        val target = plan.player
        val switched = runCatching {
            val targetIndex = slots.indexOfFirst { it === target }
            if (targetIndex < 0) throw IllegalStateException("预载实例已释放")
            val old = slots[activeSlot]
            val oldIndex = slots.indexOfFirst { it === old }
            target.volume = userVolume
            target.playWhenReady = true
            activeSlot = targetIndex
            currentSource = plan.source
            applyPendingMeta(plan.meta)
            // 同上：先绑新会话再释放旧实例
            syncAudioEffects(target)
            if (old != null && old !== target && oldIndex >= 0) releaseInstance(old, oldIndex)
            attachExternal(target)
            setState("playing")
            pushStatus()
        }.isSuccess
        if (!switched) {
            // 预载实例已经不可用，最后回到活动实例上直接加载新曲
            runCatching { load(plan.source, true, plan.meta) }
                .onFailure { android.util.Log.w("SPlayerMedia", "兜底切歌失败", it) }
        }
    }

    /**
     * 取消尚未完成或进行中的过渡，恢复旧实例音量
     * 用户手动切歌、跳转、暂停时调用，避免淡出状态残留
     */
    private fun cancelTransition() {
        val plan = transition ?: return
        transition = null
        plan.task?.let { handler.removeCallbacks(it) }
        slots[activeSlot]?.let { runCatching { it.volume = userVolume } }
        if (slots.none { it === plan.player }) return
        runCatching {
            plan.player.stop()
            plan.player.clearMediaItems()
            plan.player.volume = userVolume
        }
    }

    /**
     * 对外暴露当前活动实例，供媒体会话服务共用
     * @returns 已初始化的 ExoPlayer，未初始化时为 null
     */
    fun currentPlayer(): ExoPlayer? = slots[activeSlot]

    /**
     * 当前曲目标题，预载切换后通知栏从这里回填
     * @returns 曲名，未知时为空串
     */
    fun currentTitle(): String = mediaTitle

    /**
     * 当前曲目歌手文本
     * @returns 歌手名，未知时为空串
     */
    fun currentArtist(): String = mediaArtist

    /**
     * 当前曲目封面地址
     * @returns 封面 Uri，未知时为 null
     */
    fun currentArtworkUri(): Uri? = mediaArtwork

    /**
     * 设置是否启用系统媒体控制（通知栏与锁屏），关闭时停止前台服务
     * @param enabled - 是否启用
     */
    fun setMediaControlEnabled(enabled: Boolean) {
        mediaControlEnabled = enabled
        val ctx = appContext ?: return
        if (!enabled) {
            runCatching { ctx.stopService(android.content.Intent(ctx, PlaybackService::class.java)) }
        }
    }

    /** 播放时拉起媒体会话服务，通知栏与锁屏才能控制同一实例 */
    private fun startMediaService() {
        val ctx = appContext
        android.util.Log.d("SPlayerMedia", "startMediaService ctx=" + (ctx != null) + " enabled=" + mediaControlEnabled)
        if (ctx == null) return
        if (!mediaControlEnabled) return
        runCatching {
            // 服务在 onCreate 里立刻 startForeground，所以可以安全使用前台启动
            androidx.core.content.ContextCompat.startForegroundService(
                ctx,
                android.content.Intent(ctx, PlaybackService::class.java),
            )
        }.onFailure { android.util.Log.w("SPlayerMedia", "启动媒体服务失败", it) }
    }

    /**
     * 播放
     */
    fun play() {
        cancelTransition()
        userWantsPlay = true
        startMediaService()
        player.play()
    }

    /**
     * 暂停
     * 过渡窗口内界面已经显示下一曲，先落定切换再暂停，避免 UI 与音频错位
     */
    fun pause() {
        transition?.let { fallbackSwitch(it) }
        userWantsPlay = false
        player.pause()
    }

    /**
     * 停止并清空
     */
    fun stop() {
        // 前端在取流重试时会调 stop 做清理，这不是用户的暂停意图，
        // 因此这里不能清 userWantsPlay，否则下一次切歌会被判成暂停
        cancelTransition()
        cancelPrepared()
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
        // 过渡期间拖动的是下一曲的进度条，必须先把实例落定到预载实例
        transition?.let { fallbackSwitch(it) }
        player.seekTo(positionMs)
        emit("seek", JSONObject().put("position", positionMs))
    }

    /**
     * 设置音量，过渡期间由交叉淡化自行分配两个实例的音量
     * @param volume - 0..1
     */
    fun setVolume(volume: Float) {
        userVolume = volume.coerceIn(0f, 1f)
        if (transition == null) slots[activeSlot]?.volume = userVolume
    }

    /**
     * 设置倍速
     * @param speed - 0.5..2.0
     */
    fun setSpeed(speed: Float) {
        val params = player.playbackParameters.withSpeed(speed.coerceIn(0.25f, 4f))
        player.playbackParameters = params
        // 预载实例跟着一起变速，交接后不会突然回到原速
        prepared?.player?.playbackParameters = params
    }

    /**
     * 当前音量
     * @returns 0..1
     */
    fun volume(): Float = userVolume

    /**
     * 设置淡入淡出时长
     * @param ms - 毫秒，0 表示直接切歌
     */
    fun setFadeDuration(ms: Int) {
        fadeDurationMs = ms.coerceIn(0, MAX_FADE_MS)
    }

    /**
     * 当前淡入淡出时长
     * @returns 毫秒
     */
    fun fadeDuration(): Int = fadeDurationMs

    /**
     * 把效果器同步到活动实例的音频会话
     * 会话 id 在音频轨道创建之后才有值，取不到就保持未绑定，等下一次回调再试
     * @param instance - 当前活动实例
     */
    private fun syncAudioEffects(instance: ExoPlayer) {
        val ctx = appContext ?: return
        val session = runCatching { instance.audioSessionId }.getOrDefault(0)
        if (session <= 0) return
        AudioEffects.attach(ctx, session)
    }

    /**
     * 开启或关闭频谱推送
     * @param enabled - 是否推送，关闭时立即释放采集器
     */
    fun setFftEnabled(enabled: Boolean) {
        wantFft = enabled
        AudioEffects.setFftEnabled(appContext, enabled)
        if (!enabled) {
            stopFftTicker(pushSilent = false)
            return
        }
        slots[activeSlot]?.let { syncAudioEffects(it) }
        if (slots[activeSlot]?.isPlaying == true) startFftTicker()
    }

    /**
     * 录音权限授予后补建频谱采集，此前因为没有权限被降级成空数据
     */
    fun onCapturePermissionGranted() {
        if (!wantFft) return
        val ctx = appContext ?: return
        val session = AudioEffects.sessionId()
        if (session > 0) AudioEffects.attach(ctx, session)
        slots[activeSlot]?.let { syncAudioEffects(it) }
        if (slots[activeSlot]?.isPlaying == true) startFftTicker()
    }

    /**
     * 启动 50ms 频谱推送，与渲染层的插值周期对齐
     */
    private fun startFftTicker() {
        if (fftTickRunning || !wantFft) return
        fftTickRunning = true
        handler.post(object : Runnable {
            override fun run() {
                if (!fftTickRunning) return
                AudioEffects.latestFftFrame()?.let { emit("fftData", it) }
                handler.postDelayed(this, FFT_PUSH_MS)
            }
        })
    }

    /**
     * 停止频谱推送
     * @param pushSilent - 是否补一帧全零，让渲染层柱子回落而不是停在最后一帧
     */
    private fun stopFftTicker(pushSilent: Boolean) {
        if (!fftTickRunning) return
        fftTickRunning = false
        if (pushSilent) emit("fftData", AudioEffects.silentFftFrame())
    }

    /**
     * 设置均衡器开关
     * @param enabled - 是否启用
     */
    fun setEqualizerEnabled(enabled: Boolean) {
        AudioEffects.setEqualizerEnabled(enabled)
        slots[activeSlot]?.let { syncAudioEffects(it) }
    }

    /**
     * 下发 10 段均衡器增益
     * @param gains - 10 个 dB 值，顺序与渲染层 EqualizerDialog 一致
     */
    fun setEqualizerBands(gains: FloatArray) {
        AudioEffects.setBands(gains)
        slots[activeSlot]?.let { syncAudioEffects(it) }
    }

    /**
     * 下发前置增益
     * @param db - dB 值，范围 -12..12
     */
    fun setPreampGain(db: Float) {
        AudioEffects.setPreampGain(db)
        slots[activeSlot]?.let { syncAudioEffects(it) }
    }

    /**
     * 拉取最新一帧频谱，供 getFftData 使用
     * @returns {ldata,rdata}，还没有数据时返回两个空数组
     */
    fun fftData(): JSONObject =
        AudioEffects.latestFftFrame()
            ?: JSONObject().put("ldata", JSONArray()).put("rdata", JSONArray())

    /**
     * 释放效果器与频谱推送，Activity 销毁时调用
     * ExoPlayer 由前台服务持有，这里不释放，避免退到后台时中断播放
     */
    fun releaseAudioEffects() {
        stopFftTicker(pushSilent = false)
        wantFft = false
        AudioEffects.release()
    }

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
        .put("isEqualizerActive", AudioEffects.isEqualizerActive())
        .put("isTempoActive", false)
        .put("speed", slots[activeSlot]?.playbackParameters?.speed?.toDouble() ?: 1.0)
        .put("isNormalizationActive", false)
        .put("isLimiterActive", false)

    /**
     * 当前原始音源
     * @returns 路径或 URL
     */
    fun source(): String = currentSource
}
