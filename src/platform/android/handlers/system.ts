import { registerHandler, ipcRenderer } from "../electron-shim";

/**
 * 原生能力调用封装，未实现时抛出可捕获错误
 * @param channel - 原生 channel
 * @param args - 参数
 * @returns 原生返回值
 */
const native = (channel: string, ...args: unknown[]): Promise<unknown> => ipcRenderer.invoke(channel, ...args);

/**
 * 注册系统与窗口相关 channel，桌面专属能力降级为空实现
 */
export const registerSystemHandlers = (): void => {
  registerHandler("window:ready", () => true);
  registerHandler("window:startupComplete", () => undefined);
  registerHandler("window:startupFailed", (error: unknown) => {
    console.error("[android] 启动失败", error);
    return undefined;
  });
  registerHandler("window:minimize", () => native("window:minimize"));
  registerHandler("window:quit", () => native("window:quit"));
  registerHandler("window:hide", () => native("window:minimize"));
  registerHandler("window:toggleMaximize", () => false);
  registerHandler("window:isMaximized", () => false);
  registerHandler("window:toggleFullscreen", () => native("window:toggleFullscreen"));
  registerHandler("window:isFullscreen", () => native("window:isFullscreen"));
  registerHandler("window:toggleDesktopLyric", () => false);
  registerHandler("window:closeDesktopLyric", () => undefined);
  registerHandler("window:isDesktopLyricOpen", () => false);
  registerHandler("window:toggleDynamicIsland", () => false);
  registerHandler("window:closeDynamicIsland", () => undefined);
  registerHandler("window:isDynamicIslandOpen", () => false);

  registerHandler("system:consumePendingAudioFiles", () => native("system:consumePendingAudioFiles"));
  registerHandler("system:consumePendingProtocolUrl", () => native("system:consumePendingProtocolUrl"));
  registerHandler("system:setLocale", () => undefined);
  registerHandler("system:toggleDevTools", () => false);
  registerHandler("system:showInExplorer", () => native("system:showInExplorer"));
  registerHandler("system:copyTrackFile", () => false);
  registerHandler("system:openLogsDir", () => native("system:openLogsDir"));
  registerHandler("system:focusMainWindow", () => true);
  registerHandler("system:openSettings", () => undefined);
  registerHandler("system:listFonts", () => []);
  registerHandler("system:saveFile", (...args) => native("system:saveFile", ...args));
  registerHandler("system:relaunch", () => native("system:relaunch"));
  registerHandler("system:testNetworkProxy", () => ({ ok: true, latency: 0 }));
  registerHandler("system:fetchRemoteBytes", (url: unknown) => native("net:fetchBytes", url));

  registerHandler("theme:pickBackgroundImage", () => native("theme:pickBackgroundImage"));
  registerHandler("theme:clearBackgroundImages", () => native("theme:clearBackgroundImages"));
};

export { native };
