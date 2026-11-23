import { defaultHotkeyConfig } from "@shared/defaults/hotkeys";
import type { CommentSource, MusicCommentQuery } from "@shared/types/comment";
import type { RecognitionConfig } from "@shared/types/recognition";
import { invokeNative, registerHandler } from "../electron-shim";
import { getMusicComments } from "../services/comments";
import {
  cancelRecognition,
  isRecognitionSupported,
  startRecognition,
  submitPcm,
} from "../services/recognition";

/**
 * 注册快捷键、缓存、更新、统计等桌面外设相关 channel
 */
export const registerMiscHandlers = (): void => {
  registerHandler("hotkey:getAll", () => defaultHotkeyConfig);
  registerHandler("hotkey:set", () => undefined);
  registerHandler("hotkey:reset", () => undefined);
  registerHandler("hotkey:setGlobalEnabled", () => undefined);
  registerHandler("hotkey:probe", () => ({ ok: false, reason: "unsupported" }));
  registerHandler("hotkey:setRecording", () => undefined);
  registerHandler("hotkey:getConflicts", () => []);

  registerHandler("cache:getStats", () => ({ covers: 0, artists: 0, backgrounds: 0, songs: 0, total: 0 }));
  registerHandler("cache:clear", () => undefined);
  registerHandler("cache:clearAllByKind", () => undefined);
  registerHandler("cache:getDir", () => "");
  registerHandler("cache:pickDir", () => null);
  registerHandler("cache:resetDir", () => undefined);

  registerHandler("update:getState", () => ({ phase: "idle" }));
  registerHandler("update:check", () => ({ phase: "idle" }));
  registerHandler("update:download", () => undefined);
  registerHandler("update:install", () => undefined);
  registerHandler("update:openDownloadPage", () => undefined);

  registerHandler("nowPlaying:update", () => undefined);
  registerHandler("nowPlaying:setLyricOffset", () => undefined);
  registerHandler("nowPlaying:requestSnapshot", () => ({
    track: null,
    lyric: null,
    lyricOffsetMs: 0,
    playing: false,
    position: 0,
    duration: 0,
  }));

  registerHandler("stats:recordPlay", () => undefined);
  registerHandler("stats:recordFavorite", () => undefined);
  // 首页统计会直接参与算数（weekListenedMs / 3600000），缺字段会渲染成 NaN
  registerHandler("stats:getStatsSummary", () => ({
    todayListenedMs: 0,
    weekListenedMs: 0,
    lastWeekListenedMs: 0,
    totalListenedMs: 0,
    weekPlayCount: 0,
    totalPlayCount: 0,
    weekFavoriteAdds: 0,
    streakDays: 0,
  }));
  registerHandler("stats:getTopTracks", () => []);
  registerHandler("stats:getRecentTracks", () => []);
  registerHandler("stats:getLibraryStats", async () => {
    const res: any = await invokeNative("library:getTracks");
    const list: any[] = Array.isArray(res?.data) ? res.data : [];
    const albums = new Set<string>();
    const artists = new Set<string>();
    const codecs = new Map<string, number>();
    let durationMs = 0;
    let fileSize = 0;
    for (const track of list) {
      if (track?.album?.name) albums.add(String(track.album.name));
      for (const artist of track?.artists ?? []) {
        if (artist?.name) artists.add(String(artist.name));
      }
      durationMs += Number(track?.duration) || 0;
      fileSize += Number(track?.fileSize) || 0;
      const ext = String(track?.path ?? "").split(".").pop()?.toLowerCase();
      if (ext && ext !== String(track?.path ?? "")) {
        codecs.set(ext, (codecs.get(ext) ?? 0) + 1);
      }
    }
    return {
      trackCount: list.length,
      albumCount: albums.size,
      artistCount: artists.size,
      totalDurationMs: durationMs,
      totalFileSize: fileSize,
      codecs: [...codecs.entries()]
        .map(([codec, count]) => ({ codec, count }))
        .sort((a, b) => b.count - a.count),
    };
  });
  registerHandler("stats:getPlayHistoryDaily", () => []);
  registerHandler("stats:getPlayHistoryHourly", () => []);
  registerHandler("stats:getTopAlbums", () => []);
  registerHandler("stats:getTopArtists", () => []);

  registerHandler("externalApi:getStatus", () => ({ enabled: false, port: 0 }));
  registerHandler("externalApi:restart", () => ({ enabled: false, port: 0 }));
  registerHandler("mcp:getStatus", () => ({ enabled: false, port: 0 }));
  registerHandler("mcp:restart", () => ({ enabled: false, port: 0 }));
  registerHandler("mcp:getClientConfigParams", () => ({}));
  registerHandler("mcp:detectAgents", () => []);
  registerHandler("mcp:injectAgentConfig", () => ({ ok: false }));
  registerHandler("aiModel:list", () => []);
  registerHandler("aiModel:save", () => null);
  registerHandler("aiModel:remove", () => undefined);
  registerHandler("aiModel:setActive", () => undefined);

  registerHandler("recognition:isSupported", () => isRecognitionSupported());
  // 采集与识别在后台跑，进度与结果全部经 recognition:event 推送，返回值只表示已受理
  registerHandler("recognition:start", (args: unknown) => {
    void startRecognition((args ?? {}) as RecognitionConfig);
    return { success: true };
  });
  registerHandler("recognition:cancel", () => cancelRecognition());
  // 与桌面一致：渲染进程自行采完 PCM 后提交识别；stubs.ts 的同名占位注册在后，会覆盖本行
  registerHandler("recognition:submitPcm", (pcm: unknown) => submitPcm(pcm));
  registerHandler("lastfm:getStatus", () => ({ connected: false }));
  registerHandler("lastfm:connect", () => ({ ok: false }));
  registerHandler("lastfm:cancelConnect", () => undefined);
  registerHandler("lastfm:disconnect", () => undefined);
  registerHandler("lastfm:love", () => ({ ok: false }));

  registerHandler("automix:getStatus", () => ({ components: [] }));
  registerHandler("automix:download", () => ({ components: [] }));
  registerHandler("automix:cancelDownload", () => undefined);

  registerHandler("opencc:convert", (_text: unknown) => _text);
  registerHandler("opencc:convertBatch", (list: unknown) => list);
  registerHandler("motionCover:resolve", () => null);
  // 本地曲目没有网易 id，评论服务会按标题与歌手搜索兜底
  registerHandler("comments:sources", (): CommentSource[] => [
    {
      id: "netease",
      name: "网易云音乐",
      kind: "builtin",
      tabs: ["hot", "new"],
    },
  ]);
  registerHandler("comments:get", (args: unknown) => {
    const query = (args ?? {}) as MusicCommentQuery;
    return getMusicComments(query);
  });
  registerHandler("cloud:pickSongs", () => []);
  registerHandler("cloud:uploadSong", () => ({ ok: false }));
}
