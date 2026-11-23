package top.imsyy.splayer_next.android.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * picker 通道的参数解析与 SAF 持久授权辅助，launcher 由 MainActivity 持有
 */
object Pickers {

    /** 未指定类型时的通配 MIME */
    private const val DEFAULT_MIME = "*/*"

    /** 未指定名称时的默认文件名 */
    private const val DEFAULT_FILE_NAME = "splayer-file"

    /** 缺省 MIME：通用二进制 */
    private const val DEFAULT_CREATE_MIME = "application/octet-stream"

    /** 配置对象缺失时的占位 */
    private val EMPTY = JSONObject()

    /**
     * 解析位置参数里的配置对象
     * @param args - 桥参数数组
     * @returns 配置对象，缺失时为空对象
     */
    fun config(args: JSONArray): JSONObject = args.optJSONObject(0) ?: EMPTY

    /**
     * 解析允许的 MIME 列表
     * @param config - picker 配置
     * @returns 至少一个 MIME
     */
    fun mimeTypes(config: JSONObject): Array<String> {
        val array = config.optJSONArray("mimeTypes") ?: return arrayOf(DEFAULT_MIME)
        val list = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val mime = array.optString(i).trim()
            if (mime.isNotEmpty()) list.add(mime)
        }
        return if (list.isEmpty()) arrayOf(DEFAULT_MIME) else list.toTypedArray()
    }

    /**
     * 新建文档的默认文件名
     * @param config - picker 配置
     * @returns 文件名，缺失时用占位名
     */
    fun fileName(config: JSONObject): String =
        config.optString("fileName").trim().ifEmpty { DEFAULT_FILE_NAME }

    /**
     * 新建文档的 MIME
     * @param config - picker 配置
     * @returns MIME，缺失时按二进制处理
     */
    fun createMime(config: JSONObject): String =
        config.optString("mimeType").trim().ifEmpty { DEFAULT_CREATE_MIME }

    /**
     * 是否申请持久授权
     * @param config - picker 配置
     * @returns 默认申请
     */
    fun persistable(config: JSONObject): Boolean = config.optBoolean("persistable", true)

    /**
     * 多选结果编码：没有选中时回投 null
     * @param uris - 选中的文档
     * @returns uri 数组，空列表为 JSON null
     */
    fun uriList(uris: List<Uri>): Any =
        if (uris.isEmpty()) {
            JSONObject.NULL
        } else {
            JSONArray().apply { uris.forEach { put(it.toString()) } }
        }

    /**
     * 申请持久授权，只拿到读权限时按读申请
     * @param context - 上下文
     * @param uri - 文档或目录 uri
     */
    fun takePersistable(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure {
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /**
     * 释放持久授权
     * @param context - 上下文
     * @param uri - 文档或目录 uri
     */
    fun releasePersistable(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        runCatching {
            resolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure {
            runCatching {
                resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
