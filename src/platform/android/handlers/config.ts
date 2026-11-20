import { defaultSystemConfig } from "@shared/defaults/settings";
import { deepMerge } from "@main/store/utils";
import { getByPath, setByPath } from "@shared/utils/path";
import { registerHandler } from "../electron-shim";

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
    return deepMerge(defaultSystemConfig as unknown as Record<string, unknown>, raw ? JSON.parse(raw) : {});
  } catch {
    return deepMerge(defaultSystemConfig as unknown as Record<string, unknown>, {});
  }
};

let data = loadConfig();

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
    flush();
    return undefined;
  });
  registerHandler("config:getAll", () => data);
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
  registerHandler("config:exportToFile", () => ({ ok: false, reason: "canceled" }));
  registerHandler("config:importFromFile", () => ({ ok: false, reason: "canceled" }));
  registerHandler("config:flush", () => {
    writeNow();
    return undefined;
  });
};

export { data as configData };
