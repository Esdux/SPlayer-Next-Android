package top.imsyy.splayer_next.android

import android.content.Intent
import android.net.Uri
import org.json.JSONArray

/**
 * 冷启动与热启动的外部音频入口暂存队列
 */
object PendingIntake {

    private val queue = ArrayDeque<String>()

    /**
     * 从 Intent 中取出待播放的音频地址
     * @param intent - 外部唤起 Intent
     * @returns 音频地址列表（content:// 或 file:// 形式）
     */
    fun collect(intent: Intent): List<String> {
        val result = ArrayList<String>()
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { result.add(it.toString()) }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { uri ->
                    result.add(uri.toString())
                }
            }
            Intent.ACTION_SEND -> {
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (stream != null) result.add(stream.toString())
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                    if (text.startsWith("content://") || text.startsWith("file://")) result.add(text)
                }
            }
        }
        return result.filter { it.isNotEmpty() }
    }

    /**
     * 暂存待播放项
     * @param uris - 音频地址列表
     */
    fun push(uris: List<String>) {
        synchronized(queue) { queue.addAll(uris) }
    }

    /**
     * 取走全部待播放项并清空
     * @returns JSON 数组形式的地址列表
     */
    fun drain(): JSONArray {
        synchronized(queue) {
            val snapshot = JSONArray()
            while (queue.isNotEmpty()) snapshot.put(queue.removeFirst())
            return snapshot
        }
    }
}
