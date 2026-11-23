package top.imsyy.splayer_next.android

import android.webkit.WebView
import org.json.JSONObject

/**
 * 原生到 JS 的事件总线，所有推送都经由 window.__androidEvent
 */
object EventBus {

    private var webView: WebView? = null

    /**
     * 绑定当前的 WebView
     * @param view - 承载 SPA 的 WebView
     */
    fun bind(view: WebView) {
        webView = view
    }

    /**
     * 推送事件
     * @param channel - 事件 channel
     * @param payload - 事件数据
     */
    fun emit(channel: String, payload: JSONObject) {
        emitRaw(channel, payload.toString())
    }

    /**
     * 推送数组事件
     * @param channel - 事件 channel
     * @param payload - 事件数组
     */
    fun emitArray(channel: String, payload: org.json.JSONArray) {
        emitRaw(channel, payload.toString())
    }

    /**
     * 推送原始文本
     * @param channel - 事件 channel
     * @param json - JSON 文本
     */
    fun emitRaw(channel: String, json: String) {
        val view = webView ?: return
        // 走 evaluateJavascript：postWebMessage 在本工程里实测未能把事件派发到处理器
        val script = "window.__androidEvent && window.__androidEvent(" +
            JSONObject.quote(channel) + "," + JSONObject.quote(json) + ")"
        view.post { runCatching { view.evaluateJavascript(script, null) } }
    }
}
