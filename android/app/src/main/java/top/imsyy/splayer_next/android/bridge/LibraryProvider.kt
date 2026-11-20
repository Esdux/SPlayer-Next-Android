package top.imsyy.splayer_next.android.bridge

import android.content.Context
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import top.imsyy.splayer_next.android.AppPaths
import java.io.File

/**
 * 本地曲库，基于 MediaStore 扫描，替代桌面端 better-sqlite3 + scanner
 */
object LibraryProvider {

    private val tracks = mutableListOf<JSONObject>()
    private var lastScanAt = 0L

    /**
     * 扫描设备音频
     * @param context - 应用上下文
     * @returns 扫描到的曲目数量
     */
    fun scan(context: Context): Int {
        tracks.clear()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            MediaStore.Audio.Media.TITLE + " ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mtimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: cursor.getString(nameCol) ?: "未知曲目"
                val artist = cursor.getString(artistCol) ?: "未知歌手"
                val album = cursor.getString(albumCol) ?: "未知专辑"
                val albumId = cursor.getLong(albumIdCol)
                tracks.add(
                    JSONObject()
                        .put("id", "ms:" + id)
                        .put("source", "local")
                        .put("path", "content://media/external/audio/media/" + id)
                        .put("title", title)
                        .put("artists", JSONArray().put(JSONObject().put("name", artist)))
                        .put("album", JSONObject().put("name", album).put("cover", coverUrl(albumId)))
                        .put("duration", cursor.getLong(durationCol))
                        .put("fileSize", cursor.getLong(sizeCol))
                        .put("mtime", cursor.getLong(mtimeCol) * 1000L)
                        .put("track", cursor.getInt(trackCol))
                        .put("cover", coverUrl(albumId))
                        .put(
                            "quality",
                            JSONObject()
                                .put("sampleRate", 44100)
                                .put("channels", 2)
                                .put("bitsPerSample", 16)
                                .put("bitRate", 0)
                                .put("codec", ""),
                        ),
                )
            }
        }
        lastScanAt = System.currentTimeMillis()
        return tracks.size
    }

    /**
     * 相册封面地址，经自定义 path handler 输出
     * @param albumId - MediaStore 专辑 ID
     * @returns 前端可直接使用的 URL
     */
    fun coverUrl(albumId: Long): String =
        "https://appassets.androidplatform.net/cover/" + albumId

    /**
     * 全部曲目
     * @returns JSON 数组
     */
    fun all(): JSONArray = JSONArray(tracks)

    /**
     * 按专辑聚合
     * @returns 专辑数组
     */
    fun albums(): JSONArray {
        val map = LinkedHashMap<String, JSONObject>()
        for (track in tracks) {
            val album = track.optJSONObject("album") ?: continue
            val name = album.optString("name")
            if (name.isEmpty()) continue
            val entry = map.getOrPut(name) {
                JSONObject()
                    .put("name", name)
                    .put("cover", album.optString("cover"))
                    .put("trackCount", 0)
            }
            entry.put("trackCount", entry.optInt("trackCount") + 1)
        }
        return JSONArray(map.values.toList())
    }

    /**
     * 按歌手聚合
     * @returns 歌手数组
     */
    fun artists(): JSONArray {
        val map = LinkedHashMap<String, JSONObject>()
        for (track in tracks) {
            val list = track.optJSONArray("artists") ?: continue
            for (i in 0 until list.length()) {
                val name = list.optJSONObject(i)?.optString("name") ?: continue
                if (name.isEmpty()) continue
                val entry = map.getOrPut(name) {
                    JSONObject().put("name", name).put("albumCount", 0)
                }
                entry.put("albumCount", entry.optInt("albumCount") + 1)
            }
        }
        return JSONArray(map.values.toList())
    }

    /**
     * 单张专辑内的曲目
     * @param albumName - 专辑名
     * @returns JSON 数组
     */
    fun albumTracks(albumName: String): JSONArray {
        val result = JSONArray()
        for (track in tracks) {
            if (track.optJSONObject("album")?.optString("name") == albumName) result.put(track)
        }
        return result
    }

    /**
     * 单个歌手的曲目
     * @param artistName - 歌手名
     * @returns JSON 数组
     */
    fun artistTracks(artistName: String): JSONArray {
        val result = JSONArray()
        for (track in tracks) {
            val list = track.optJSONArray("artists") ?: continue
            var hit = false
            for (i in 0 until list.length()) {
                if (list.optJSONObject(i)?.optString("name") == artistName) {
                    hit = true
                    break
                }
            }
            if (hit) result.put(track)
        }
        return result
    }

    /**
     * 按 id 批量取曲目
     * @param ids - 曲目 id 数组
     * @returns JSON 数组
     */
    fun byIds(ids: List<String>): JSONArray {
        val wanted = ids.toSet()
        val result = JSONArray()
        for (track in tracks) {
            if (wanted.contains(track.optString("id"))) result.put(track)
        }
        return result
    }

    /**
     * 关键词搜索，命中标题、歌手、专辑任一字段
     * @param query - 搜索关键词
     * @param limit - 返回上限
     * @returns JSON 数组
     */
    fun search(query: String, limit: Int = 200): JSONArray {
        val keyword = query.trim().lowercase()
        val result = JSONArray()
        if (keyword.isEmpty()) return result
        for (track in tracks) {
            val haystack = buildString {
                append(track.optString("title").lowercase())
                append(" ")
                append(track.optJSONObject("album")?.optString("name")?.lowercase() ?: "")
                val list = track.optJSONArray("artists")
                if (list != null) {
                    for (i in 0 until list.length()) {
                        append(" ")
                        append(list.optJSONObject(i)?.optString("name")?.lowercase() ?: "")
                    }
                }
            }
            if (haystack.contains(keyword)) {
                result.put(track)
                if (result.length() >= limit) break
            }
        }
        return result
    }

    /**
     * 曲目总数
     * @returns 数量
     */
    fun count(): Int = tracks.size

    /**
     * 随机取一首
     * @returns 曲目 JSON，库为空时为 null
     */
    fun randomOne(): JSONObject? = if (tracks.isEmpty()) null else tracks.random()

    /**
     * 随机取若干首
     * @param limit - 数量上限
     * @returns JSON 数组
     */
    fun randomMany(limit: Int): JSONArray {
        val result = JSONArray()
        if (tracks.isEmpty() || limit <= 0) return result
        tracks.shuffled().take(limit).forEach { result.put(it) }
        return result
    }

    /**
     * 读取单曲标签
     * @param path - 音频地址
     * @returns 标签 JSON，读取失败时为 null
     */
    fun readTags(path: String): JSONObject? {
        return runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            if (path.startsWith("content://")) {
                retriever.setDataSource(top.imsyy.splayer_next.android.SPlayerContext.get(), android.net.Uri.parse(path))
            } else {
                retriever.setDataSource(path)
            }
            val tags = JSONObject()
                .put("title", retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE) ?: "")
                .put("artist", retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "")
                .put("album", retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "")
                .put("duration", retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
            retriever.release()
            tags
        }.getOrNull()
    }

    /**
     * 扫描根目录，安卓侧以 MediaStore 全量为准，暴露公共音乐目录便于文件夹树聚合
     * @returns 目录数组
     */
    fun scanDirs(): JSONArray = JSONArray().put("/storage/emulated/0/Music")

    /**
     * 扫描时间
     * @returns Unix ms
     */
    fun scannedAt(): Long = lastScanAt
}
