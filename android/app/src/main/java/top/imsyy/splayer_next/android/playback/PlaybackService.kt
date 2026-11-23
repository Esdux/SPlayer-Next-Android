package top.imsyy.splayer_next.android.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.exoplayer.ExoPlayer
import android.support.v4.media.session.MediaSessionCompat
import org.json.JSONObject
import top.imsyy.splayer_next.android.EventBus
import top.imsyy.splayer_next.android.R

/**
 * 媒体播放前台服务：用系统标准的媒体会话通知（MediaSessionCompat + MediaStyle）
 *
 * 通知卡片由系统渲染，控制中心的播放/暂停/切歌直接作用在 [PlaybackEngine] 的播放器上；
 * 上一首/下一首转给前端队列，因为原生只持有当前一首
 */
class PlaybackService : android.app.Service() {

    private var session: MediaSessionCompat? = null
    private var lastTitle = ""
    private var lastArtist = ""

    /** 媒体会话发起切歌后，等播放器就绪要自动续播 */
    private var pendingAutoPlay = false

    /** 通知按钮广播接收器 */
    private val mediaReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val player = PlaybackEngine.currentPlayer() ?: return
            when (intent?.action) {
                // 统一走引擎，实例切换期间按钮才会作用在活动实例上
                ACTION_TOGGLE -> if (player.isPlaying) PlaybackEngine.pause() else PlaybackEngine.play()
                ACTION_PREV -> EventBus.emit("system:mediaPrevious", JSONObject())
                ACTION_NEXT -> EventBus.emit("system:mediaNext", JSONObject())
                ACTION_LIKE -> EventBus.emit("system:toggleLike", JSONObject())
                ACTION_EXIT -> {
                    // 通知上的 × 直接结束应用
                    stopSelf()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
            Handler(Looper.getMainLooper()).postDelayed({ updateNotification() }, 150)
        }
    }

    /** 主线程任务队列，用于通知进度刷新 */
    private val uiHandler = Handler(Looper.getMainLooper())

    /** 是否继续刷新通知进度 */
    private var ticking = false

    /** 每秒同步一次播放位置，通知与锁屏进度才会随手走动 */
    private val progressTicker = object : Runnable {
        override fun run() {
            if (!ticking) return
            if (PlaybackEngine.currentPlayer()?.isPlaying == true) syncSessionState()
            uiHandler.postDelayed(this, 1000)
        }
    }

    /**
     * 播放器监听：元数据与播放状态变化时刷新通知
     */
    private val playerListener = object : androidx.media3.common.Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
            updateNotification()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateNotification()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // 只要用户意图是播放，任何一次加载就绪都续播，避免切歌停在暂停态
            val currentPlayer = PlaybackEngine.currentPlayer()
            if (PlaybackEngine.userWantsPlay &&
                currentPlayer != null &&
                playbackState == androidx.media3.common.Player.STATE_READY &&
                !currentPlayer.isPlaying
            ) {
                currentPlayer.play()
            }
            updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("SPlayerMedia", "service onCreate enter")
        PlaybackEngine.init(applicationContext)
        val player = PlaybackEngine.currentPlayer()
        android.util.Log.d("SPlayerMedia", "player ready=" + (player != null))
        if (player == null) {
            stopSelf()
            return
        }
        session = MediaSessionCompat(this, "SPlayerNext").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(
                object : MediaSessionCompat.Callback() {
                    // 回调会跟着实例切换走，必须每次取当前活动实例
                    override fun onPlay() = PlaybackEngine.play()
                    override fun onPause() = PlaybackEngine.pause()
                    override fun onSeekTo(pos: Long) = PlaybackEngine.seek(pos)
                    override fun onSkipToNext() {
                        pendingAutoPlay = true
                        EventBus.emit("system:mediaNext", JSONObject())
                        ensurePlayingAfterSwitch()
                    }
                    override fun onSkipToPrevious() {
                        pendingAutoPlay = true
                        EventBus.emit("system:mediaPrevious", JSONObject())
                        ensurePlayingAfterSwitch()
                    }

                    override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                        when (action) {
                            ACTION_LIKE -> EventBus.emit("system:toggleLike", JSONObject())
                            ACTION_EXIT -> {
                                // 通知卡片上的 × 直接结束应用
                                stopSelf()
                                android.os.Process.killProcess(android.os.Process.myPid())
                            }
                        }
                    }
                },
            )
            isActive = true
        }
        current = this
        createChannel()
        // 会话元数据与播放状态同步给系统，控制中心才能显示曲名、封面与时间轴
        syncSessionState()
        Handler(Looper.getMainLooper()).postDelayed({ syncSessionState() }, 600)
        val filter = android.content.IntentFilter().apply {
            addAction(ACTION_TOGGLE)
            addAction(ACTION_PREV)
            addAction(ACTION_NEXT)
            addAction(ACTION_LIKE)
            addAction(ACTION_EXIT)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            mediaReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        startForeground(NOTIFICATION_ID, buildNotification())

        // 曲目或播放状态变化时刷新通知
        PlaybackEngine.addListener(playerListener)
        ticking = true
        uiHandler.post(progressTicker)
        Handler(Looper.getMainLooper()).postDelayed({ updateNotification() }, 300)
        android.util.Log.d("SPlayerMedia", "service onCreate done")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 切歌后兜底：前端取流完成再确认一次播放状态
     *
     * 下一首常常因为取地址期间播放器处于空闲而被当成暂停，这里统一补一次 play
     */
    private fun ensurePlayingAfterSwitch() {
        Handler(Looper.getMainLooper()).postDelayed({
            val player = PlaybackEngine.currentPlayer()
            // 取流可能还没完成，这里只补一次状态同步；真正续播交给 onPlaybackStateChanged
            if (player != null && player.playbackState == androidx.media3.common.Player.STATE_READY && !player.isPlaying) {
                player.play()
            }
            syncSessionState()
        }, 1500)
    }

    /** 重建通知并推给系统 */
    private fun updateNotification() {
        syncSessionState()
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    /** 把曲目元数据与播放进度同步到媒体会话 */
    private fun syncSessionState() {
        val session = session ?: return
        val player = PlaybackEngine.currentPlayer() ?: return
        val (title, artist, artwork) = currentMetadata(player)
        // 切歌瞬间新元数据还没下发，此时写空值会让控制中心闪「暂无播放」，保持上一次
        if (title.isEmpty() && lastTitle.isNotEmpty()) return
        val builder = android.support.v4.media.MediaMetadataCompat.Builder()
            .putString(
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE,
                title,
            )
            .putString(
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST,
                artist,
            )
            .putLong(
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION,
                player.duration.coerceAtLeast(0),
            )
        artwork?.let { uri ->
            builder.putString(
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI,
                uri.toString(),
            )
            // 控制中心读的是会话里的位图，只给 URI 时部分 ROM 不会自己去拉图
            artworkCache[uri.toString()]?.let { bitmap ->
                builder.putBitmap(
                    android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART,
                    bitmap,
                )
            }
            // 顺带触发一次下载，下载完成后 updateNotification 会重新写入
            artworkBitmap(uri)
        }
        session.setMetadata(builder.build())
        session.setPlaybackState(
            android.support.v4.media.session.PlaybackStateCompat.Builder()
                .setActions(
                    android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_PAUSE or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_SEEK_TO or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
                )
                .addCustomAction(
                    android.support.v4.media.session.PlaybackStateCompat.CustomAction.Builder(
                        ACTION_LIKE,
                        if (liked) "取消收藏" else "收藏",
                        if (liked) R.drawable.ic_media_like_on else R.drawable.ic_media_like,
                    ).build(),
                )
                .addCustomAction(
                    android.support.v4.media.session.PlaybackStateCompat.CustomAction.Builder(
                        ACTION_EXIT,
                        "关闭应用",
                        R.drawable.ic_media_close,
                    ).build(),
                )
                .setState(
                    when {
                        player.isPlaying -> android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
                        // 换曲取流期间报缓冲，避免控制中心显示成已暂停
                        player.playbackState == androidx.media3.common.Player.STATE_BUFFERING ->
                            android.support.v4.media.session.PlaybackStateCompat.STATE_BUFFERING
                        else -> android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
                    },
                    player.currentPosition.coerceAtLeast(0),
                    if (player.isPlaying) 1f else 0f,
                )
                .build(),
        )
    }

    /**
     * 构造通知按钮的广播 PendingIntent
     * @param action - 广播动作
     * @param requestCode - 区分按钮的请求码
     * @returns 不可变广播
     */
    private fun actionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(action).setPackage(packageName)
        return PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * 取当前曲目元数据
     * 预载切换后活动实例自身没有 MediaMetadata，标题与封面由引擎回填
     * @param player - 当前活动实例
     * @returns 标题、歌手与封面地址
     */
    private fun currentMetadata(player: ExoPlayer): Triple<String, String, Uri?> {
        val md = player.mediaMetadata
        return Triple(
            md.title?.toString().orEmpty().ifEmpty { PlaybackEngine.currentTitle() },
            md.artist?.toString().orEmpty().ifEmpty { PlaybackEngine.currentArtist() },
            md.artworkUri ?: PlaybackEngine.currentArtworkUri(),
        )
    }

    /**
     * 组装系统媒体通知
     * @returns 带封面与控制按钮的媒体通知
     */
    private fun buildNotification(): android.app.Notification {
        val player = PlaybackEngine.currentPlayer()
        val snapshot = player?.let { currentMetadata(it) }
        val title = snapshot?.first.orEmpty().ifEmpty { lastTitle }.ifEmpty { "SPlayer Next" }
        val artist = snapshot?.second.orEmpty().ifEmpty { lastArtist }
        lastTitle = title
        lastArtist = artist
        val playing = player?.isPlaying == true
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        // 播放/暂停/切歌按钮由系统媒体卡渲染，这里只提供标题、封面与时间轴
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(artist)
            .setLargeIcon(artworkBitmap(snapshot?.third))
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setShowWhen(false)
            .addAction(
                if (liked) R.drawable.ic_media_like_on else R.drawable.ic_media_like,
                if (liked) "取消收藏" else "收藏",
                actionIntent(ACTION_LIKE, 11),
            )
            .addAction(R.drawable.ic_media_close, "关闭应用", actionIntent(ACTION_EXIT, 12))
            .setStyle(
                MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0, 1),
            )
            .build()
    }

    /** 封面下载客户端 */
    private val httpClient = okhttp3.OkHttpClient()

    /** 封面位图缓存，最多 16 张，长时间播放不会把每首原图都留在内存 */
    private val artworkCache = object : android.util.LruCache<String, Bitmap>(16) {}

    /**
     * 按通知所需尺寸采样解码，避免整张原图进内存
     * @param bytes - 图片字节
     * @returns 位图；无法解码时为 null
     */
    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** 正在下载中的封面地址，避免重复请求 */
    private val artworkLoading = HashSet<String>()

    /**
     * 取封面位图：本地走 ContentResolver，在线走 OkHttp（后台线程）
     * @param uri - 封面地址
     * @returns 已缓存的位图；未就绪时返回 null 并触发后台下载
     */
    private fun artworkBitmap(uri: Uri?): Bitmap? {
        if (uri == null) return null
        val key = uri.toString()
        artworkCache.get(key)?.let { return it }
        if (artworkLoading.add(key)) {
            Thread {
                val bitmap = runCatching {
                    val bytes = if (key.startsWith("http")) {
                        val request = okhttp3.Request.Builder().url(key).build()
                        httpClient.newCall(request).execute().use { it.body?.bytes() }
                    } else {
                        contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }
                    if (bytes == null) null else decodeSampled(bytes)
                }.getOrNull()
                artworkLoading.remove(key)
                if (bitmap != null) {
                    artworkCache.put(key, bitmap)
                    Handler(Looper.getMainLooper()).post { updateNotification() }
                }
            }.start()
        }
        return null
    }

    /** 创建媒体通知渠道 */
    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "正在播放", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(mediaReceiver) }
        ticking = false
        uiHandler.removeCallbacks(progressTicker)
        // 全局播放器比服务活得久，服务反复创建会累积匿名监听，必须摘掉
        PlaybackEngine.removeListener(playerListener)
        runCatching { session?.setCallback(null) }
        session?.release()
        session = null
        current = null
        artworkCache.evictAll()
        super.onDestroy()
    }

    companion object {
        /** 由引擎在自动播放加载时登记，服务就绪后据此续播 */
        private var pendingMarker = false

        /** 标记下一次就绪需要自动播放 */
        fun markPendingAutoPlay() {
            pendingMarker = true
        }

        /** 媒体通知固定 id */
        const val NOTIFICATION_ID = 1001

        /** 媒体通知渠道 */
        const val CHANNEL_ID = "splayer_playback"

        /** 播放/暂停广播 */
        const val ACTION_TOGGLE = "top.imsyy.splayer_next.android.MEDIA_TOGGLE"

        /** 上一首广播 */
        const val ACTION_PREV = "top.imsyy.splayer_next.android.MEDIA_PREV"

        /** 下一首广播 */
        const val ACTION_NEXT = "top.imsyy.splayer_next.android.MEDIA_NEXT"

        /** 通知栏红心广播 */
        const val ACTION_LIKE = "top.imsyy.splayer_next.android.MEDIA_LIKE"

        /** 通知栏关闭应用广播 */
        const val ACTION_EXIT = "top.imsyy.splayer_next.android.MEDIA_EXIT"

        /** 当前服务实例，供引擎按收藏状态刷新通知 */
        private var current: PlaybackService? = null

        /** 通知栏红心状态，由前端 player:syncLikeState 同步 */
        @Volatile
        var liked = false
            private set

        /**
         * 更新收藏状态并刷新通知图标
         * @param value - 是否已收藏
         */
        fun setLiked(value: Boolean) {
            if (liked == value) return
            liked = value
            val service = current ?: return
            Handler(Looper.getMainLooper()).post {
                // 自定义按钮的图标跟着收藏状态换，必须同时刷新会话与通知
                service.syncSessionState()
                service.updateNotification()
            }
        }
    }
}
