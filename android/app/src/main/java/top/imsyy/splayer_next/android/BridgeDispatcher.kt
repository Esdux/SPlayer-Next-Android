package top.imsyy.splayer_next.android

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import top.imsyy.splayer_next.android.bridge.LibraryProvider
import top.imsyy.splayer_next.android.playback.PlaybackEngine
import java.util.concurrent.Executors

/**
 * channel 分发器，未实现的 channel 返回失败占位，保证前端不因缺能力而崩溃
 */
class BridgeDispatcher(private val activity: MainActivity) {

    private val pool = Executors.newFixedThreadPool(4)

    /**
     * 同步分发
     * @param channel - IPC channel 名
     * @param payload - JSON 数组字符串
     * @returns JSON 字符串结果
     */
    fun invokeSync(channel: String, payload: String): String {
        return runCatching { JSONObject().put("ok", true).put("data", dispatch(channel, parse(payload))).toString() }
            .getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "dispatch failed").toString() }
    }

    /**
     * 异步分发，耗时操作在线程池执行
     * @param callId - 调用标识
     * @param channel - IPC channel 名
     * @param payload - JSON 数组字符串
     */
    fun invokeAsync(callId: String, channel: String, payload: String) {
        val args = parse(payload)
        pool.execute {
            val envelope = runCatching {
                JSONObject().put("ok", true).put("data", dispatch(channel, args))
            }.getOrElse {
                Log.w("SPlayerBridge", "channel failed: " + channel, it)
                JSONObject().put("ok", false).put("error", it.message ?: "dispatch failed")
            }
            reply(callId, envelope.toString())
        }
    }

    /**
     * 解析参数
     * @param payload - JSON 数组字符串
     * @returns JSON 数组，非法时为单元素空数组
     */
    private fun parse(payload: String): JSONArray = runCatching { JSONArray(payload) }.getOrElse { JSONArray() }

    /**
     * 回投结果给 JS
     * @param callId - 调用标识
     * @param json - 结果 JSON
     */
    fun reply(callId: String, json: String) {
        val escapedCallId = JSONObject.quote(callId)
        val escapedJson = JSONObject.quote(json)
        activity.runOnUiThread {
            activity.webView.evaluateJavascript(
                "window.__androidReply && window.__androidReply(" + escapedCallId + ", " + escapedJson + ")",
                null,
            )
        }
    }

    /**
     * 按 channel 路由
     * @param channel - IPC channel 名
     * @param args - 参数数组
     * @returns 前端可直接消费的结果对象
     */
    private fun dispatch(channel: String, args: JSONArray): Any {
        return when (channel) {
            "player:getStatus" -> ok(PlaybackEngine.status())
            "player:load" -> {
                val source = args.optString(0)
                val options = args.optJSONObject(1)
                val autoPlay = options?.optBoolean("autoPlay", true) ?: true
                ok(PlaybackEngine.load(source, autoPlay, options?.optJSONObject("meta")))
            }
            "player:play" -> { PlaybackEngine.play(); ok(null) }
            "player:pause" -> { PlaybackEngine.pause(); ok(null) }
            "player:stop" -> { PlaybackEngine.stop(); ok(null) }
            "player:seek" -> { PlaybackEngine.seek(args.optLong(0)); ok(null) }
            "player:setVolume" -> { PlaybackEngine.setVolume(args.optDouble(0, 1.0).toFloat()); ok(null) }
            "player:getVolume" -> ok(PlaybackEngine.volume().toDouble())
            "player:setSpeed" -> { PlaybackEngine.setSpeed(args.optDouble(0, 1.0).toFloat()); ok(null) }
            "player:setPitch", "player:setPitchSync", "player:setFadeDuration",
            "player:setNormalizationEnabled", "player:setEqualizerBands",
            "player:setPreampGain", "player:setEqualizerEnabled",
            "player:setPauseOnDeviceSwitch", "player:setFftEnabled",
            "player:syncPlayMode", "player:syncQueueIndex", "player:syncLikeState",
            "player:respondQueue", "player:dispatch",
            -> ok(null)
            "player:getStreamInfo" -> ok(PlaybackEngine.streamInfo())
            "player:getFftData" -> ok(JSONObject().put("ldata", JSONArray()).put("rdata", JSONArray()))
            "player:getOutputDevices" -> ok(
                JSONArray().put(
                    JSONObject().put("id", "builtin").put("name", "本机扬声器").put("isDefault", true),
                ),
            )
            "player:getDefaultDeviceName", "player:getSelectedDeviceName" -> ok("本机扬声器")
            "player:setOutputDevice" -> ok(null)
            "player:getCoverRaw" -> ok(null)
            "player:prepareNext" -> ok(JSONObject().put("ready", false))
            "player:cancelPrepared" -> ok(null)
            "player:transitionPrepared" -> JSONObject().put("success", false).put("error", "NOT_SUPPORTED")
            "player:readLyricFile" -> ok(null)
            "library:scan" -> {
                val count = LibraryProvider.scan(activity)
                EventBus.emit(
                    "library:scanProgress",
                    JSONObject().put("phase", "done").put("total", count).put("scanned", count),
                )
                ok(JSONObject().put("success", true).put("count", count))
            }
            "library:cancelScan" -> ok(JSONObject().put("success", true))
            "library:getScanDirs" -> ok(
                JSONObject().put("success", true).put("data", LibraryProvider.scanDirs()),
            )
            "library:addScanDir" -> ok(
                JSONObject().put("success", false).put("error", "安卓侧曲库由系统媒体库托管"),
            )
            "library:removeScanDir" -> ok(JSONObject().put("success", true))
            "library:getAlbumTracks" -> ok(
                JSONObject().put("success", true)
                    .put("data", LibraryProvider.albumTracks(args.optString(0))),
            )
            "library:getArtistTracks" -> ok(
                JSONObject().put("success", true)
                    .put("data", LibraryProvider.artistTracks(args.optString(0))),
            )
            "library:getTracksByIds" -> {
                val ids = args.optJSONArray(0) ?: JSONArray()
                val list = ArrayList<String>(ids.length())
                for (i in 0 until ids.length()) list.add(ids.optString(i))
                ok(JSONObject().put("success", true).put("data", LibraryProvider.byIds(list)))
            }
            "library:searchTracks" -> ok(
                JSONObject().put("success", true).put("data", LibraryProvider.search(args.optString(0))),
            )
            "library:quickSearch" -> ok(
                JSONObject().put("success", true).put("data", LibraryProvider.search(args.optString(0), 20)),
            )
            "library:getTrackCount" -> ok(LibraryProvider.count())
            "library:isScanning" -> ok(false)
            "library:getRandomTrack" -> ok(LibraryProvider.randomOne())
            "library:getRandomTracks" -> ok(LibraryProvider.randomMany(args.optInt(0, 20)))
            "library:readTags" -> ok(LibraryProvider.readTags(args.optString(0)))
            "library:writeTags" -> ok(
                JSONObject().put("success", false).put("error", "安卓侧暂不支持写入媒体标签"),
            )
            "library:fetchArtistAvatar" -> ok(
                JSONObject().put("success", true).put("data", JSONObject.NULL),
            )
            "library:prefetchArtistAvatars" -> ok(JSONObject().put("success", true).put("data", JSONObject()))
            "library:pickCoverImage" -> ok(
                JSONObject().put("success", false).put("error", "暂不支持"),
            )
            "library:deleteTracks" -> ok(
                JSONObject().put("success", false).put("error", "暂不支持删除系统媒体库文件"),
            )
            "library:getTracks" -> ok(JSONObject().put("success", true).put("data", LibraryProvider.all()))
            "library:getAlbums" -> ok(JSONObject().put("success", true).put("data", LibraryProvider.albums()))
            "library:getArtists" -> ok(JSONObject().put("success", true).put("data", LibraryProvider.artists()))
            "net:fetchBytes", "net:fetch" -> ok(NetClient.fetch(args.optJSONObject(0) ?: JSONObject()))
            "system:consumePendingAudioFiles" -> ok(PendingIntake.drain())
            "system:consumePendingProtocolUrl" -> ok(null)
            "system:openLogsDir", "system:showInExplorer", "system:relaunch" -> ok(null)
            "window:minimize" -> { activity.runOnUiThread { activity.moveTaskToBack(true) }; ok(null) }
            "window:quit" -> { activity.runOnUiThread { activity.finish() }; ok(null) }
            "window:toggleFullscreen" -> ok(null)
            "window:isFullscreen" -> ok(false)
            // 未实现的 channel 以 IpcResponse 形状失败返回，避免前端 Promise 链整体 reject
            else -> ok(
                JSONObject().put("success", false).put("error", "channel not implemented: " + channel),
            )
        }
    }

    /**
     * 包装为前端 IpcResponse 形状
     * @param data - 业务数据
     * @returns { success, data }
     */
    private fun ok(data: Any?): JSONObject {
        val payload = JSONObject().put("success", true)
        if (data != null) payload.put("data", data)
        return payload
    }
}
