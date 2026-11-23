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
    /**
     * 由前端声明是否接管系统返回：全屏播放页打开时先回歌词页、再收起播放页
     * @param enabled - 是否接管
     */
    /**
     * 退到后台（根路由时返回键的落点）
     */
    @JavascriptInterface
    fun moveTaskToBack() {
        MainActivity.moveToBackground()
    }

    @JavascriptInterface
    fun setBackHandler(enabled: Boolean) {
        MainActivity.setBackHandlerEnabled(enabled)
    }

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
