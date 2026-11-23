package top.imsyy.splayer_next.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户授权过的扫描目录
 *
 * 安卓侧曲库由 MediaStore 托管，这里保存的是用户经 SAF 授权的目录，
 * 用于在界面上体现「已添加的文件夹」并支持移除。
 */
object ScanDirs {

    private const val PREF_NAME = "splayer_scan_dirs"
    private const val KEY_ITEMS = "items"

    /**
     * 读取原始条目
     * @param context - 上下文
     * @returns 条目数组，每项含 uri 与 label
     */
    private fun entries(context: Context): JSONArray {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, "[]") ?: "[]"
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    /**
     * 目录显示名列表
     * @param context - 上下文
     * @returns 名称数组
     */
    fun labels(context: Context): JSONArray {
        val items = entries(context)
        val result = JSONArray()
        for (i in 0 until items.length()) {
            items.optJSONObject(i)?.optString("label")?.let { if (it.isNotEmpty()) result.put(it) }
        }
        return result
    }

    /**
     * 新增目录
     * @param context - 上下文
     * @param label - 显示名
     * @param uri - SAF tree uri
     */
    fun add(context: Context, label: String, uri: String) {
        val items = entries(context)
        for (i in 0 until items.length()) {
            if (items.optJSONObject(i)?.optString("uri") == uri) return
        }
        items.put(JSONObject().put("label", label).put("uri", uri))
        persist(context, items)
    }

    /**
     * 按显示名移除
     * @param context - 上下文
     * @param label - 显示名
     */
    fun remove(context: Context, label: String) {
        val items = entries(context)
        val next = JSONArray()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optString("label") == label) continue
            next.put(item)
        }
        persist(context, next)
    }

    /**
     * 写回存储
     * @param context - 上下文
     * @param items - 条目数组
     */
    private fun persist(context: Context, items: JSONArray) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, items.toString())
            .apply()
    }
}
