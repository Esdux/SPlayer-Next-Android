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

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 执行一次 HTTP 请求
     * @param payload - { url, method?, headers?, bodyBase64?, timeoutMs? }
     * @returns { status, headers, bodyBase64, url }
     */
    fun fetch(payload: JSONObject): JSONObject {
        val url = payload.getString("url")
        val method = payload.optString("method", "GET").uppercase()
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
        val response = client.newCall(builder.build()).execute()
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
        return result
    }
}
