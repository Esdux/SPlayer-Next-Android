package top.imsyy.splayer_next.android

import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface

/**
 * 暴露给 WebView 的原生入口，只做参数搬运，业务在 [BridgeDispatcher]
 */
class HostBridge(private val activity: MainActivity) {

    private val dispatcher by lazy { BridgeDispatcher(activity) }

    /**
     * 握手与运行时信息
     * @returns 壳版本与平台标识
     */
    @JavascriptInterface
    fun ping(): String = "SPlayer-Next-Android shell 1 @api" + android.os.Build.VERSION.SDK_INT

    /**
     * 同步调用入口，JS 侧以 JSON 字符串收发
     * @param channel - IPC channel 名
     * @param payload - JSON 数组字符串形式的参数
     * @returns JSON 形式的结果
     */
    @JavascriptInterface
    fun invoke(channel: String, payload: String): String = dispatcher.invokeSync(channel, payload)

    /**
     * 异步调用入口，结果通过 evaluateJavascript 回投
     * @param callId - 调用标识
     * @param channel - IPC channel 名
     * @param payload - JSON 数组字符串形式的参数
     */
    @JavascriptInterface
    fun invokeAsync(callId: String, channel: String, payload: String) {
        dispatcher.invokeAsync(callId, channel, payload)
    }

    /**
     * 打开外部链接
     * @param url - 目标地址
     * @returns 是否已接管
     */
    fun handleExternalUrl(url: String): Boolean = runCatching {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    }.getOrDefault(false)
}
