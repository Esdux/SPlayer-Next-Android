package top.imsyy.splayer_next.android.bridge

import android.content.Context
import android.util.Log
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lyricon（词幕）桥
 *
 * 把当前歌曲与结构化歌词推给词幕，由其渲染桌面/悬浮歌词；未安装词幕时所有调用静默降级
 */
object LyriconBridge {

    private const val TAG = "SPlayerLyricon"

    /** 词幕 Provider 实例 */
    private var provider: LyriconProvider? = null

    /** 是否已注册成功 */
    private var registered = false

    /**
     * 确保 Provider 已创建并注册
     * @param context - 应用上下文
     */
    fun ensure(context: Context) {
        if (provider != null) return
        runCatching {
            val created = LyriconFactory.createProvider(context.applicationContext)
            provider = created
            created.register()
            registered = true
            Log.i(TAG, "provider registered")
        }.onFailure { Log.w(TAG, "createProvider failed: " + it.message) }
    }

    /**
     * 推送歌曲与歌词
     * @param payload - { id, name, artist, duration, lyrics: [{ begin, end, text, translation, words: [{ text, begin, end }] }] }
     * @returns 是否成功推送
     */
    fun setSong(payload: JSONObject): Boolean {
        val service = provider ?: return false
        return runCatching {
            val list = payload.optJSONArray("lyrics") ?: JSONArray()
            val lines = ArrayList<RichLyricLine>(list.length())
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                lines.add(
                    RichLyricLine(
                        begin = item.optLong("begin"),
                        end = item.optLong("end"),
                        text = item.optString("text"),
                        words = wordsOf(item.optJSONArray("words")),
                        secondary = item.optString("secondary"),
                        translation = item.optString("translation"),
                    ),
                )
            }
            val song = Song(
                id = payload.optString("id"),
                name = payload.optString("name"),
                artist = payload.optString("artist"),
                duration = payload.optLong("duration"),
                lyrics = lines,
            )
            service.player.setSong(song)
            service.player.setDisplayTranslation(payload.optBoolean("showTranslation", true))
            service.player.setDisplayRoma(payload.optBoolean("showRoma", false))
            true
        }.onFailure { Log.w(TAG, "setSong failed: " + it.message) }.getOrDefault(false)
    }

    /**
     * 同步播放位置
     * @param positionMs - 毫秒
     */
    fun setPosition(positionMs: Long) {
        provider?.let { runCatching { it.player.setPosition(positionMs) } }
    }

    /**
     * 同步播放状态
     * @param playing - 是否播放中
     */
    fun setPlaybackState(playing: Boolean) {
        provider?.let { runCatching { it.player.setPlaybackState(playing) } }
    }

    /** 断开并释放 */
    fun release() {
        runCatching {
            if (registered) provider?.unregister()
            provider?.destroy()
        }
        provider = null
        registered = false
    }

    /**
     * 解析逐字歌词
     * @param array - 前端给的 words 数组
     * @returns 词幕的 LyricWord 列表
     */
    private fun wordsOf(array: JSONArray?): List<LyricWord> {
        if (array == null || array.length() == 0) return emptyList()
        val words = ArrayList<LyricWord>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            words.add(
                LyricWord(
                    text = item.optString("text"),
                    begin = item.optLong("begin"),
                    end = item.optLong("end"),
                ),
            )
        }
        return words
    }
}
