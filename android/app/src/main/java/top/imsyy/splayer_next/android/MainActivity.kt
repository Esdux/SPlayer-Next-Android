package top.imsyy.splayer_next.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import top.imsyy.splayer_next.android.bridge.LibraryProvider
import top.imsyy.splayer_next.android.bridge.Pickers
import top.imsyy.splayer_next.android.playback.PlaybackEngine
import kotlin.math.roundToInt

/**
 * 承载 Vue 单页应用的 WebView 壳
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** 录音权限的请求码，与曲库读取的 1、网页录音的 2 区分 */
        private const val REQ_RECORD_AUDIO = 3

        /** 前端是否接管系统返回（默认接管，由前端逐级消费，没有可退层级时再交还系统） */
        @Volatile
        private var backHandlerEnabled = true

        /**
         * 由 WebView 桥调用，切换返回接管状态
         * @param enabled - 是否接管
         */
        fun setBackHandlerEnabled(enabled: Boolean) {
            backHandlerEnabled = enabled
        }

        /** 由桥调用，把任务退到后台 */
        fun moveToBackground() {
            current?.moveTaskToBack(true)
        }

        /** 当前实例，供静态桥方法使用 */
        @Volatile
        private var current: MainActivity? = null
    }

    lateinit var webView: WebView
        private set

    /** 根容器，旋转时与 WebView 一起重新测量 */
    private var rootContainer: FrameLayout? = null

    /**
     * 在进度条那段排除系统手势，避免底部手势导航在中途取消水平拖动
     * @param rect - 进度条矩形（CSS 像素，含 top / bottom），null 表示清除排除
     */
    fun applyGestureExclusion(rect: JSONObject?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val view = webView
        if (view.width <= 0 || view.height <= 0) return
        if (rect == null) {
            view.systemGestureExclusionRects = emptyList()
            return
        }
        val density = resources.displayMetrics.density
        // WebView 给系统栏让过 padding，CSS 视口原点在 padding 内侧，这里补回偏移
        val offsetY = view.paddingTop
        val top = (offsetY + rect.optDouble("top", 0.0) * density).roundToInt().coerceIn(0, view.height)
        val bottom = (offsetY + rect.optDouble("bottom", 0.0) * density)
            .roundToInt()
            .coerceIn(top, view.height)
        if (bottom - top < 8) return
        view.systemGestureExclusionRects = listOf(Rect(0, top, view.width, bottom))
    }

    /** 页面完成挂载后置位，用于区分冷启动与热启动的文件投递路径 */
    @Volatile
    private var pageReady = false

    private val host by lazy { HostBridge(this) }

    /** 等待目录选择结果的调用标识 */
    private var scanDirCallId: String? = null

    /** 系统目录选择器：选完把 tree uri 持久授权并回填列表 */
    private val scanDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val callId = scanDirCallId
        scanDirCallId = null
        if (callId == null) return@registerForActivityResult
        if (uri == null) {
            BridgeDispatcher.replyOk(
                callId,
                JSONObject().put("success", false).put("error", "已取消选择"),
            )
            return@registerForActivityResult
        }
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val label = uri.lastPathSegment
            ?.substringAfterLast(':')
            ?.substringAfterLast('/')
            ?.ifEmpty { null }
            ?: uri.toString()
        ScanDirs.add(this, label, uri.toString())
        BridgeDispatcher.replyOk(
            callId,
            JSONObject().put("success", true).put("data", label),
        )
        EventBus.emit("library:scanDirsChanged", JSONObject().put("dirs", ScanDirs.labels(this)))
    }

    /** 正在等待结果的 picker 调用标识 */
    private var pickerCallId: String? = null

    /** 本次目录选择是否申请持久授权 */
    private var pickerPersistable = true

    /** 单选文档 */
    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> finishPicker(uri?.toString() ?: JSONObject.NULL) }

    /** 多选文档 */
    private val openFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> finishPicker(Pickers.uriList(uris)) }

    /** 目录选择，选中即申请持久授权 */
    private val pickerTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null && pickerPersistable) Pickers.takePersistable(this, uri)
        finishPicker(uri?.toString() ?: JSONObject.NULL)
    }

    /** 新建 JSON 文档 */
    private val createJsonLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> finishPicker(uri?.toString() ?: JSONObject.NULL) }

    /** 新建通用二进制文档 */
    private val createBinaryLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> finishPicker(uri?.toString() ?: JSONObject.NULL) }

    /**
     * 发起系统文件选择，结果由对应 launcher 回调回投
     * @param callId - 桥调用标识
     * @param channel - picker channel
     * @param args - 位置参数
     */
    fun beginPicker(callId: String, channel: String, args: org.json.JSONArray) {
        if (pickerCallId != null) {
            BridgeDispatcher.reply(callId, pickerError("已有文件选择正在进行"))
            return
        }
        val config = Pickers.config(args)
        pickerCallId = callId
        pickerPersistable = Pickers.persistable(config)
        val launched = runCatching {
            when (channel) {
                "picker:openFile" -> openFileLauncher.launch(Pickers.mimeTypes(config))
                "picker:openFiles" -> openFilesLauncher.launch(Pickers.mimeTypes(config))
                "picker:openTree" -> pickerTreeLauncher.launch(null)
                "picker:createFile" -> {
                    val name = Pickers.fileName(config)
                    if (Pickers.createMime(config) == "application/json") {
                        createJsonLauncher.launch(name)
                    } else {
                        createBinaryLauncher.launch(name)
                    }
                }
                else -> throw IllegalArgumentException("unknown picker channel: " + channel)
            }
        }
        if (launched.isFailure) {
            pickerCallId = null
            BridgeDispatcher.reply(
                callId,
                pickerError(launched.exceptionOrNull()?.message ?: "无法打开文件选择器"),
            )
        }
    }

    /**
     * 回投 picker 结果并清空等待状态
     * @param data - uri 字符串、uri 数组或 JSON null
     */
    private fun finishPicker(data: Any) {
        val callId = pickerCallId ?: return
        pickerCallId = null
        BridgeDispatcher.replyOk(callId, data)
    }

    /**
     * 构造失败信封，与 BridgeDispatcher 的响应形状保持一致
     * @param message - 失败原因
     * @returns JSON 文本
     */
    private fun pickerError(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()

    /**
     * 发起目录选择，结果在回调里回复前端
     * @param callId - 桥调用的标识
     */
    fun beginScanDirPick(callId: String) {
        scanDirCallId = callId
        runCatching { scanDirLauncher.launch(null) }
            .onFailure {
                scanDirCallId = null
                BridgeDispatcher.replyOk(
                    callId,
                    JSONObject().put("success", false).put("error", "无法打开目录选择器"),
                )
            }
    }

    /** 封面输出通道，按专辑 ID 从 MediaStore 读取内嵌封面 */
    private val coverHandler = object : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val albumId = path.trim('/').toLongOrNull() ?: return null
            val uri = ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                albumId,
            )
            val stream = runCatching { contentResolver.openInputStream(uri) }.getOrNull() ?: return null
            return WebResourceResponse(
                "image/jpeg",
                null,
                200,
                "OK",
                mapOf("Cache-Control" to "max-age=86400"),
                stream,
            )
        }
    }

    /** 主题背景图输出通道，读 app-data/cache/backgrounds 下的文件 */
    private val backgroundHandler = object : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val name = path.trim('/')
            // 只接受平坦文件名，挡掉 ../ 之类的目录穿越
            if (name.isEmpty() || name.contains('/') || name.contains("..")) return null
            val file = java.io.File(java.io.File(AppPaths.cacheDir, "backgrounds"), name)
            if (!file.isFile) return null
            val mime = when (file.extension.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"
                else -> "image/jpeg"
            }
            val stream = runCatching { file.inputStream() }.getOrNull() ?: return null
            return WebResourceResponse(
                mime,
                null,
                200,
                "OK",
                mapOf("Cache-Control" to "max-age=86400"),
                stream,
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        // 启动后在后台预热一次曲库扫描，进入音乐库即可直接看到结果
        android.os.Handler(mainLooper).postDelayed({
            Thread { runCatching { LibraryProvider.scan(this) } }.start()
        }, 1500)

        super.onCreate(savedInstanceState)
        current = this
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val container = FrameLayout(this)
        rootContainer = container
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.parseColor("#101014"))
            // 可见时让渲染进程保持"重要"优先级，歌词/转场才不会被系统降级；不可见时让出资源
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                // 各平台封面/歌词资源大量是 http，直接全部放行，避免被页面策略拦掉
      mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // 跟随系统字体缩放，WebView 只影响文本，不影响布局基准
                textZoom = (resources.configuration.fontScale * 100).toInt().coerceIn(100, 160)
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = object : WebViewClient() {
                private val loader: WebViewAssetLoader = WebViewAssetLoader.Builder()
                    .setDomain("appassets.androidplatform.net")
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this@MainActivity))
                    .addPathHandler("/cover/", coverHandler)
                    .addPathHandler("/bg/", backgroundHandler)
                    .build()

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    // 页面加载后重新推一次安全区：inset 回调常常早于页面就绪，之前的注入会被新文档清空
                    pushSafeArea()
                    // 留出 Vue 挂载与事件订阅的时间，之后到达的打开请求直接推送
                    view.postDelayed({ pageReady = true }, 1500)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val url = request.url
                    if (url.host == "appassets.androidplatform.net") return false
                    return host.handleExternalUrl(url.toString())
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    val text = "[" + msg.messageLevel() + "] " + msg.message() +
                        " @" + msg.sourceId() + ":" + msg.lineNumber()
                    android.util.Log.d("SPlayerWeb", text)
                    return true
                }

                /**
                 * 网页请求录音权限：授予麦克风并在未授权时拉起系统申请
                 * @param request - 网页权限请求
                 */
                override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                    if (request == null) return
                    val wantsAudio = request.resources.any {
                        it == android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    }
                    if (!wantsAudio) {
                        request.deny()
                        return
                    }
                    val granted = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        request.grant(arrayOf(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        return
                    }
                    // 先拉起系统授权；用户同意后再点识别即可录音
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
                    request.deny()
                }
            }
            addJavascriptInterface(host, "AndroidHost")
        }

        container.addView(webView)
        setContentView(container)
        applySystemBars(container)
        EventBus.bind(webView)
        requestAudioPermission()
        intake(intent)
        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
    }

    /**
     * 适配状态栏与导航栏：内容整体让出系统栏高度，避免顶栏被状态栏压住
     *
     * Android 15 起强制 edge-to-edge，window.statusBarColor 不再生效，
     * 所以改为读取系统栏 inset 后给 WebView 加内边距，并把高度透传给前端。
     * @param container - 根容器
     */
    private fun applySystemBars(container: FrameLayout) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 应用是深色主题，状态栏与导航栏都用浅色图标
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(container) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            // 键盘高度也要推给前端，否则 adjustResize 在 edge-to-edge 下不生效、输入框被盖住
            insetImePx = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            // WebView 铺满整屏，让全屏播放页的背景渲染器能延伸到状态栏与导航栏后面；
            // 普通页面由前端用 --android-safe-top / --android-safe-bottom 自行避让
            container.setPadding(0, 0, 0, 0)
            insetTopPx = bars.top
            insetBottomPx = bars.bottom
            android.util.Log.d("SPlayerInsets", "top=" + bars.top + " bottom=" + bars.bottom)
            pushSafeArea()
            insets
        }
        ViewCompat.requestApplyInsets(container)
    }

    /** 是否处于沉浸全屏（隐藏状态栏与导航栏） */
    @Volatile
    private var immersive = false

    /** 最近一次系统栏 inset（像素），沉浸时前端应视为 0 */
    private var insetTopPx = 0
    private var insetBottomPx = 0

    /** 最近一次键盘 inset（像素） */
    private var insetImePx = 0

    /** 把安全区高度推给前端；沉浸全屏时归零，让 UI 跟随隐藏状态栏后的空间 */
    private fun pushSafeArea() {
        val density = resources.displayMetrics.density
        val topDp = if (immersive) 0f else insetTopPx / density
        val bottomDp = if (immersive) 0f else insetBottomPx / density
        val imeDp = insetImePx / density
        webView.post {
            webView.evaluateJavascript(
                "document.documentElement.style.setProperty('--android-safe-top','" + topDp +
                    "px');document.documentElement.style.setProperty('--android-safe-bottom','" + bottomDp +
                    "px');document.documentElement.style.setProperty('--android-ime-bottom','" + imeDp +
                    "px');",
                null,
            )
        }
    }

    /**
     * 当前是否沉浸全屏
     * @returns 沉浸状态
     */
    fun isFullscreenMode(): Boolean = immersive

    /** 切换沉浸全屏 */
    fun toggleFullscreenMode() {
        setFullscreenMode(!immersive)
    }

    /**
     * 设置沉浸全屏：隐藏状态栏与导航栏，滑动边缘可临时唤出
     * @param enabled - 是否隐藏系统栏
     */
    fun setFullscreenMode(enabled: Boolean) {
        immersive = enabled
        WindowCompat.getInsetsController(window, window.decorView).apply {
            if (enabled) {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
        // 系统栏显隐后重新计算安全区，UI 跟着可用空间变化
        pushSafeArea()
    }

    /**
     * 处理热启动时新到达的打开请求
     * @param intent - 新 Intent
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intake(intent)
    }

    /** 回到前台时重推安全区，避免 insets 未再回调导致顶栏顶进状态栏 */
    override fun onResume() {
        super.onResume()
        ViewCompat.requestApplyInsets(findViewById(android.R.id.content))
        pushSafeArea()
    }

    /**
     * 说明：不要在后台上调用 webView.onPause()。
     * 它会让 WebView 在主线程同步等待渲染线程，后台被判定 ANR 后整进程被 SIGKILL
     * （实测 exit-info: reason=SIGNALED、anrType=5、timeout=133s），播放随之中断。
     * 非前台降载改由 setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, true) 承担。
     */

    /**
     * 分拣外部打开请求：冷启动入队等前端拉取，热启动直接推事件
     * @param intent - 外部唤起 Intent
     */
    private fun intake(intent: android.content.Intent?) {
        val uris = PendingIntake.collect(intent ?: return)
        if (uris.isEmpty()) return
        if (pageReady) {
            EventBus.emitArray("system:open-files", org.json.JSONArray(uris))
        } else {
            PendingIntake.push(uris)
        }
    }

    /**
     * 确保录音权限可用，频谱采集（Visualizer）读取系统输出混音依赖它
     * 已授权时直接通知播放引擎补建采集器，未授权则拉起系统申请
     */
    fun ensureAudioCapturePermission() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            PlaybackEngine.onCapturePermissionGranted()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
    }

    /**
     * 申请音频读取权限，Android 13 起按媒体类型授权
     */
    private fun requestAudioPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), 1)
        }
    }

    /**
     * 权限授予后立即预热曲库，缩短首次进入音乐库的等待
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) return
        // 录音权限只服务频谱采集，拿到后立即补建采集器，不需要预热曲库
        if (requestCode == REQ_RECORD_AUDIO) {
            PlaybackEngine.onCapturePermissionGranted()
            return
        }
        Thread { runCatching { LibraryProvider.scan(this) } }.start()
    }

    override fun onBackPressed() {
        // 全屏播放页接管时交给前端逐级退出，避免直接回到桌面
        if (backHandlerEnabled) {
            EventBus.emit("system:back", org.json.JSONObject())
            return
        }
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    /** 销毁时释放播放器与效果器，并把未完成的 picker 调用回投失败，避免前端挂到超时 */
    /**
     * 旋转/尺寸变化时让 WebView 重新铺满并重推安全区
     *
     * 清单里声明了 configChanges，Activity 不会重建，必须自己 relayout，
     * 否则 WebView 会停在旋转前的宽度，横屏只占左侧一条。
     * @param newConfig - 新配置
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        webView?.let { view ->
            view.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            view.requestLayout()
        }
        // 容器本身也要重新测量，否则 WebView 会停在旋转前的高度
        rootContainer?.requestLayout()
        window.decorView.requestLayout()
        window.decorView.post { pushSafeArea() }
    }

    override fun onDestroy() {
        pickerCallId?.let { BridgeDispatcher.reply(it, pickerError("页面已销毁")) }
        pickerCallId = null
        // 效果器持有音频会话，随 Activity 一起释放；ExoPlayer 由前台服务继续持有
        PlaybackEngine.releaseAudioEffects()
        super.onDestroy()
    }
}