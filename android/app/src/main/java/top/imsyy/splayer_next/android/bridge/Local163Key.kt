package top.imsyy.splayer_next.android.bridge

import android.content.Context
import android.net.Uri
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 本地曲库的网易 163 key 解析与对照表
 *
 * 网易下载的音频在 ID3v2 里带 `163 key(Don't modify):<base64>`，
 * base64 解开后是 AES-128-ECB 加密的 JSON，里面含 musicId，
 * 用它就能把在线曲目对回本地文件
 */
object Local163Key {

    /** 163 key 的固定前缀 */
    private const val TAG = "163 key(Don't modify):"

    /** 网易使用的固定 AES 密钥 */
    private val AES_KEY = "#14ljk_!\\]&0U<('".toByteArray(Charsets.UTF_8)

    /** 对照表持久化文件 */
    private const val PREF_NAME = "splayer_163key"

    /**
     * 从音频文件里取出 musicId
     * @param context - 应用上下文
     * @param uri - 音频地址
     * @returns 网易歌曲 id，解析失败为 null
     */
    fun musicIdOf(context: Context, uri: Uri): Long? {
        val token = readToken(context, uri) ?: return null
        return decodeMusicId(token)
    }

    /**
     * 读取 ID3v2 里的 163 key 文本
     * @param context - 应用上下文
     * @param uri - 音频地址
     * @returns base64 串，未找到为 null
     */
    private fun readToken(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                // 嵌入封面常占 200KB 以上，163 key 往往在其后，这里放宽到 512KB
                val buffer = ByteArray(512 * 1024)
                val read = stream.read(buffer)
                if (read <= 0) return@use null
                val text = String(buffer, 0, read, Charsets.ISO_8859_1)
                val index = text.indexOf(TAG)
                if (index < 0) return@use null
                text.substring(index + TAG.length)
                    .takeWhile { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
                    .takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    /**
     * 解 base64 再 AES-128-ECB 解出 musicId
     * @param token - 163 key 的 base64 串
     * @returns 网易歌曲 id
     */
    private fun decodeMusicId(token: String): Long? {
        return runCatching {
            val raw = android.util.Base64.decode(token, android.util.Base64.DEFAULT)
            if (raw.isEmpty() || raw.size % 16 != 0) return@runCatching null
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY, "AES"))
            // 标准格式明文为 music: 加 JSON；第三方工具写入的私有串解不出来，直接放弃
            val plain = String(cipher.doFinal(raw), Charsets.UTF_8)
            if (!plain.startsWith("music:")) return@runCatching null
            Regex("\"musicId\"\\s*:\\s*(\\d+)").find(plain)?.groupValues?.get(1)?.toLongOrNull()
        }.getOrNull()
    }

    /**
     * 刷新曲库时重建对照表：musicId -> 音频地址，另存歌名+歌手+时长指纹
     * @param context - 应用上下文
     * @param items - 曲库条目（uri、标题、歌手、时长毫秒）
     * @returns 写入的映射数量
     */
    fun rebuild(context: Context, items: List<Fingerprint>): Int {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.clear()
        for (item in items) {
            // 指纹索引：没有 163key 的曲目也能靠标题+歌手+时长对上
            editor.putString(fingerprintKey(item.title, item.artist, item.durationMs), item.uri.toString())
        }
        var count = 0
        for (item in items) {
            val id = musicIdOf(context, item.uri) ?: continue
            editor.putString(id.toString(), item.uri.toString())
            count += 1
        }
        editor.apply()
        android.util.Log.i("SPlayer163", "本地 163key 对照表写入 " + count + " 条")
        return count
    }

    /**
     * 用在线曲目 id 查本地音频地址
     * @param context - 应用上下文
     * @param musicId - 网易歌曲 id
     * @returns 本地可播放地址（content uri），未命中为 null
     */
    fun findLocal(context: Context, musicId: String): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(musicId, null)
    }

    /**
     * 指纹匹配：163key 缺失时用标题+歌手+时长兜底
     * @param context - 应用上下文
     * @param title - 曲名
     * @param artist - 首位歌手
     * @param durationMs - 时长毫秒
     * @returns 本地可播放地址，未命中为 null
     */
    fun findByFingerprint(
        context: Context,
        title: String,
        artist: String,
        durationMs: Long,
    ): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // 时长按秒级容差尝试，避免编码差异导致对不上
        for (delta in 0L..3L) {
            for (sign in listOf(1L, -1L)) {
                val key = fingerprintKey(title, artist, durationMs + sign * delta * 1000L)
                prefs.getString(key, null)?.let { return it }
            }
        }
        return null
    }

    /**
     * 指纹键：小写并去掉空白与括号等噪声
     * @param title - 曲名
     * @param artist - 歌手
     * @param durationMs - 时长毫秒
     * @returns 对照表键
     */
    private fun fingerprintKey(title: String, artist: String, durationMs: Long): String {
        return "fp:" + normalize(title) + "|" + normalize(artist) + "|" + (durationMs / 1000L)
    }

    /**
     * 归一化：小写、去掉括号内容（feat./Cover/版本号）、去掉空白与标点
     * @param value - 原始文本
     * @returns 归一化结果
     */
    private fun normalize(value: String): String {
        // 括号里的通常是版本、feat、翻唱等修饰，两侧往往不一致，先去掉
        val withoutBrackets = value
            .replace(Regex("[(（\\[【].*?[)）\\]】]"), "")
            .replace(Regex("(?i)\\bfeat\\.?\\b.*$"), "")
            .replace(Regex("(?i)\\bft\\.?\\b.*$"), "")
        return withoutBrackets.lowercase().replace(Regex("[\\s\\p{Punct}]"), "")
    }

    /** 曲库指纹条目 */
    data class Fingerprint(
        val uri: Uri,
        val title: String,
        val artist: String,
        val durationMs: Long,
    )

    /**
     * 对照表条目数量
     * @param context - 应用上下文
     * @returns 已有映射条数
     */
    fun size(context: Context): Int =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).all.size
}
