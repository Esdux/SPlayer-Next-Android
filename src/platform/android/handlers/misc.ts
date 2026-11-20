import { defaultHotkeyConfig } from "@shared/defaults/hotkeys";
import { registerHandler } from "../electron-shim";

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
  registerHandler("nowPlaying:requestSnapshot", () => null);

  registerHandler("stats:recordPlay", () => undefined);
  registerHandler("stats:recordFavorite", () => undefined);
  registerHandler("stats:getStatsSummary", () => ({}));
  registerHandler("stats:getTopTracks", () => []);
  registerHandler("stats:getRecentTracks", () => []);
  registerHandler("stats:getLibraryStats", () => ({}));
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

  registerHandler("recognition:isSupported", () => false);
  registerHandler("recognition:start", () => ({ ok: false }));
  registerHandler("recognition:cancel", () => undefined);
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
  registerHandler("comments:sources", () => []);
  registerHandler("comments:get", () => ({ comments: [] }));
  registerHandler("cloud:pickSongs", () => []);
  registerHandler("cloud:uploadSong", () => ({ ok: false }));
}
