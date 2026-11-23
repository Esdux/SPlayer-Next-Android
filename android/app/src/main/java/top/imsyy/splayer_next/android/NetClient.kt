package top.imsyy.splayer_next.android

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 统一网络出口，替代桌面端 undici，用于绕过 WebView 的同源限制
 */
object NetClient {

    /** 浏览器 UA：网易封面与 CDN 会校验 UA，缺失直接 403 */
    private const val WEB_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0"

    /**
     * 补默认 UA 与 Referer，避免图片 CDN 直接拒绝
     */
    private val defaultHeadersInterceptor = okhttp3.Interceptor { chain ->
        val origin = chain.request()
        val builder = origin.newBuilder()
        if (origin.header("User-Agent") == null) builder.header("User-Agent", WEB_UA)
        if (origin.header("Referer") == null) builder.header("Referer", "https://music.163.com/")
        chain.proceed(builder.build())
    }

    /** 证书与域名错配的 CDN：酷狗 mobilecdn 实际出示的是 *.cdn.myqcloud.com 泛域名证书 */
    private val hostnameRelaxed = setOf("mobilecdn.kugou.com")

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // 整体调用上限：任何阶段卡住都不会无限占着线程
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor(defaultHeadersInterceptor)
        .hostnameVerifier { host, session ->
            host in hostnameRelaxed ||
                javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
        }
        .build()

    /**
     * 不跟随 302 的客户端，扫码登录需要从跳转响应里取 Set-Cookie
     */
    private val noRedirectClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(defaultHeadersInterceptor)
        .build()

    /**
     * 执行一次 HTTP 请求
     * @param payload - { url, method?, headers?, bodyBase64?, timeoutMs? }
     * @returns { status, headers, bodyBase64, url }
     */
    fun fetch(payload: JSONObject): JSONObject {
        val url = payload.getString("url")
        android.util.Log.d("SPlayerNet", "fetch " + payload.optString("method", "GET") + " " + url + " bodyLen=" + payload.optString("bodyBase64", "").length)
        val method = payload.optString("method", "GET").uppercase()
        // 单次请求超时毫秒，0 表示沿用全局配置
        val timeoutMs = payload.optLong("timeoutMs", 0L)
        val builder = Request.Builder().url(url)
        payload.optJSONObject("headers")?.let { headers ->
            for (key in headers.keys()) builder.header(key, headers.optString(key))
        }
        val bodyBytes = payload.optString("bodyBase64", "")
        val body = if (bodyBytes.isEmpty()) null else Base64.getDecoder().decode(bodyBytes)
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(
                (body ?: ByteArray(0)).toRequestBody(
                    payload.optString("contentType", "application/octet-stream").toMediaType(),
                ),
            )
            "PUT" -> builder.put(
                (body ?: ByteArray(0)).toRequestBody(
                    payload.optString("contentType", "application/octet-stream").toMediaType(),
                ),
            )
            "DELETE" -> builder.delete()
            "HEAD" -> builder.head()
            else -> builder.method(method, body?.toRequestBody(null))
        }
        val followRedirects = payload.optBoolean("followRedirects", true)
        val base = if (followRedirects) client else noRedirectClient
        // 带单次超时的请求从对应基类派生，否则沿用全局客户端
        val active = if (timeoutMs > 0) {
            base.newBuilder()
                .callTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
        } else {
            base
        }
        val response = active.newCall(builder.build()).execute()
        val result = JSONObject()
        result.put("status", response.code)
        val headers = JSONObject()
        response.headers.forEach { (name, value) ->
            if (headers.has(name)) {
                headers.put(name, headers.getString(name) + ", " + value)
            } else {
                headers.put(name, value)
            }
        }
        result.put("headers", headers)
        result.put("url", response.request.url.toString())
        result.put("bodyBase64", Base64.getEncoder().encodeToString(response.body?.bytes() ?: ByteArray(0)))
        response.close()
        android.util.Log.d("SPlayerNet", "resp " + result.optInt("status") + " b64len=" + result.optString("bodyBase64").length)
        return result
    }
}