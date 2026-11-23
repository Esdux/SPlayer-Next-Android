import { defaultSystemConfig } from "@shared/defaults/settings";
import { deepMerge } from "@main/store/utils";
import { getByPath, setByPath } from "@shared/utils/path";
import { invokeNative, registerHandler } from "../electron-shim";
import { exportConfigToFile, importConfigFromFile } from "./pickers";

/** 配置在 WebView localStorage 中的键名 */
const STORAGE_KEY = "splayer.android.settings";

/** 写盘防抖窗口 */
const FLUSH_DEBOUNCE_MS = 200;

let flushTimer: number | null = null;

/**
 * 从 localStorage 读取并合并默认配置
 * @returns 完整配置对象
 */
const loadConfig = (): Record<string, unknown> => {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    const merged = deepMerge(
      defaultSystemConfig as unknown as Record<string, unknown>,
      raw ? JSON.parse(raw) : {},
    );
    // 安卓端一次性打开歌名下的播放来源标签（格式/音质），之后尊重用户设置
    const playerOpts0 = (merged.player ?? {}) as Record<string, unknown>;
    const tagFlag = "splayer.android.sourceTagDefaulted";
    if (!window.localStorage.getItem(tagFlag)) {
      playerOpts0.showPlaybackSource = true;
      window.localStorage.setItem(tagFlag, "1");
    }
    merged.player = playerOpts0;

    // 安卓端默认开放在线 TTML：逐字歌词是主要卖点，桌面默认关闭不适合手机
    const lyricOpts = (merged.lyric ?? {}) as Record<string, unknown>;
    if (lyricOpts.enableOnlineTTMLLyric === undefined) lyricOpts.enableOnlineTTMLLyric = true;
    merged.lyric = lyricOpts;

    // 安卓端默认开启动态流体背景：桌面的 blur 默认在手机上几乎看不出效果
    const player = (merged.player ?? {}) as Record<string, unknown>;
    if (!player.playerBgType) player.playerBgType = "animation";
    merged.player = player;
    return merged;
  } catch {
    return deepMerge(defaultSystemConfig as unknown as Record<string, unknown>, {});
  }
};

let data = loadConfig();

/**
 * 把界面缩放到根元素上，等价于桌面端的 setZoomFactor
 * @param value - 百分比（50-200）
 */
const applyUiZoom = (value: unknown): void => {
  const percent = Math.max(50, Math.min(200, Number(value) || 100));
  document.documentElement.style.zoom = String(percent / 100);
};

/**
 * 立即落盘
 */
const writeNow = (): void => {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
  } catch {
    /* 存储超限时保持内存态 */
  }
};

/**
 * 防抖落盘
 */
const flush = (): void => {
  if (flushTimer !== null) window.clearTimeout(flushTimer);
  flushTimer = window.setTimeout(() => {
    flushTimer = null;
    writeNow();
  }, FLUSH_DEBOUNCE_MS);
};

/**
 * 注册配置相关 channel，语义对齐主进程 config.ts
 */
export const registerConfigHandlers = (): void => {
  registerHandler("config:get", (keyPath: unknown) => getByPath(data, String(keyPath)));
  registerHandler("config:set", (keyPath: unknown, value: unknown) => {
    setByPath(data, String(keyPath), value);
    if (String(keyPath) === "system.uiZoom") applyUiZoom(value);
    // 同步系统媒体控制开关到原生，关闭时停止媒体前台服务
    if (String(keyPath) === "media.systemMediaControls") {
      void invokeNative("system:setMediaControlEnabled", value === true);
    }
    flush();
    return undefined;
  });
  registerHandler("config:getAll", () => data);
  // 启动时回放已保存的界面缩放
  applyUiZoom(getByPath(data, "system.uiZoom"));
  registerHandler("config:reset", () => {
    data = deepMerge(defaultSystemConfig as unknown as Record<string, unknown>, {});
    writeNow();
    return undefined;
  });
  registerHandler("config:replaceAll", (payload: unknown) => {
    data = deepMerge(
      defaultSystemConfig as unknown as Record<string, unknown>,
      (payload && typeof payload === "object" ? payload : {}) as Record<string, unknown>,
    );
    writeNow();
    return undefined;
  });
  // 导出/导入走 SAF 文档，JSON 解析在 pickers 里完成
  registerHandler("config:exportToFile", (payload: unknown) => exportConfigToFile(payload));
  registerHandler("config:importFromFile", () => importConfigFromFile());
  registerHandler("config:flush", () => {
    writeNow();
    return undefined;
  });
};

export { data as configData };
