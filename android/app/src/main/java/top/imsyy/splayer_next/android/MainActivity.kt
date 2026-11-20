package top.imsyy.splayer_next.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Color
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
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import top.imsyy.splayer_next.android.bridge.LibraryProvider

/**
 * 承载 Vue 单页应用的 WebView 壳
 */
class MainActivity : ComponentActivity() {

    lateinit var webView: WebView
        private set

    /** 页面完成挂载后置位，用于区分冷启动与热启动的文件投递路径 */
    @Volatile
    private var pageReady = false

    private val host by lazy { HostBridge(this) }

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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val container = FrameLayout(this)
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.parseColor("#101014"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                textZoom = 100
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = object : WebViewClient() {
                private val loader: WebViewAssetLoader = WebViewAssetLoader.Builder()
                    .setDomain("appassets.androidplatform.net")
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this@MainActivity))
                    .addPathHandler("/cover/", coverHandler)
                    .build()

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
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
            }
            addJavascriptInterface(host, "AndroidHost")
        }

        container.addView(webView)
        setContentView(container)
        EventBus.bind(webView)
        requestAudioPermission()
        intake(intent)
        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
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
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Thread { runCatching { LibraryProvider.scan(this) } }.start()
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
