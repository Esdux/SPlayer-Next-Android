import { registerHandler } from "../electron-shim";

/**
 * 注册安卓侧暂不具备能力的外设/在线通道，按前端消费形状返回安全空值
 */
export const registerStubHandlers = (): void => {
  // 在线平台代理：桌面端在 Node 里跑平台加密协议，安卓侧暂无对等实现

  // 歌曲缓存
  registerHandler("cache:song:lookup", () => null);
  registerHandler("cache:song:fetch", () => ({ success: false }));
  registerHandler("cache:song:cancel", () => undefined);

  // 桌面歌词与灵动岛属于桌面窗口能力
  registerHandler("desktopLyric:setHeight", () => undefined);
  registerHandler("dynamicIsland:getMode", () => "off");
  registerHandler("window:toggleTaskbarLyric", () => ({ success: false }));
  registerHandler("window:isTaskbarLyricOpen", () => false);
  registerHandler("window:closeTaskbarLyric", () => undefined);

  // 下载任务：安卓侧暂以系统下载器为准
  registerHandler("download:list", () => []);
  registerHandler("download:start", () => ({ success: false }));
  registerHandler("download:startMany", () => ({ success: false }));
  registerHandler("download:cancel", () => undefined);
  registerHandler("download:remove", () => undefined);
  registerHandler("download:clearFinished", () => undefined);
  registerHandler("download:retry", () => ({ success: false }));
  registerHandler("download:lookup", () => null);
  registerHandler("download:resolveFailed", () => undefined);
  registerHandler("download:resolution", () => ({ success: false }));
  registerHandler("download:getDir", () => "");
  registerHandler("download:pickDir", () => null);
  registerHandler("download:resetDir", () => undefined);

  // 插件系统依赖 Node 运行时
  registerHandler("plugin:list", () => []);
  registerHandler("plugin:install", () => ({ success: false }));
  registerHandler("plugin:installFromUrl", () => ({ success: false }));
  registerHandler("plugin:pickAndInstall", () => ({ success: false }));
  registerHandler("plugin:uninstall", () => ({ success: false }));
  registerHandler("plugin:setEnabled", () => ({ success: false }));
  registerHandler("plugin:setPermission", () => ({ success: false }));
  registerHandler("plugin:setSetting", () => ({ success: false }));
  registerHandler("plugin:applyUpdate", () => ({ success: false }));
  registerHandler("plugin:checkUpdate", () => ({ success: false }));
  registerHandler("plugin:market", () => []);
  registerHandler("plugin:search", () => ({ success: false }));
  registerHandler("plugin:resolveUrl", () => ({ success: false }));
  registerHandler("plugin:matchCover", () => null);
  registerHandler("plugin:matchLyric", () => null);
  registerHandler("plugin:invokeMenu", () => ({ success: false }));

  // 流媒体服务器（Navidrome 等）依赖长连接与本地索引
  registerHandler("streaming:loadServers", () => ({ servers: [], activeServerId: null }));
  registerHandler("streaming:getSnapshot", () => ({
    songs: [],
    albums: [],
    artists: [],
    playlists: [],
    favorites: [],
  }));
  registerHandler("streaming:addServer", () => ({ success: false }));
  registerHandler("streaming:updateServer", () => ({ success: false }));
  registerHandler("streaming:removeServer", () => ({ success: false }));
  registerHandler("streaming:setActiveServer", () => ({ success: false }));
  registerHandler("streaming:testConnection", () => ({ success: false, error: "暂不支持" }));
  registerHandler("streaming:connect", () => ({ success: false }));
  registerHandler("streaming:disconnect", () => undefined);
  registerHandler("streaming:sync", () => ({ success: false }));
  registerHandler("streaming:search", () => ({ songs: [], albums: [], artists: [] }));
  registerHandler("streaming:getAlbumSongs", () => []);
  registerHandler("streaming:getArtistAlbums", () => []);
  registerHandler("streaming:getArtistSongs", () => []);
  registerHandler("streaming:getPlaylistSongs", () => []);
  registerHandler("streaming:getLyrics", () => null);
  registerHandler("streaming:getFavorite", () => ({ success: false }));
  registerHandler("streaming:setFavorite", () => ({ success: false }));

  // 歌词仓库与手动匹配：安卓侧从曲目内嵌与同名文件读取
  registerHandler("lyrics:searchCandidates", () => []);
  registerHandler("lyrics:searchTTML", () => []);
  registerHandler("lyrics:fetchTTMLById", () => null);
  registerHandler("lyrics:fetchTTMLOverlay", () => null);
  registerHandler("lyrics:matchById", () => ({ success: false }));
  registerHandler("lyrics:matchByQuery", () => ({ success: false }));
  registerHandler("lyrics:matchLocalTTML", () => null);
  registerHandler("lyrics:getManualMatch", () => null);
  registerHandler("lyrics:setManualMatch", () => ({ success: false }));
  registerHandler("lyrics:clearManualMatch", () => undefined);
  registerHandler("lyrics:rememberMatch", () => undefined);
  registerHandler("lyrics:forgetMatch", () => undefined);
  registerHandler("lyrics:pickLyricRepoDir", () => null);

  // 音频识别依赖 PCM 采集管线
  registerHandler("recognition:submitPcm", () => ({ success: false }));

  // 播放器细项
  registerHandler("player:getFadeDuration", () => 0);
  registerHandler("player:reinit", () => undefined);
};