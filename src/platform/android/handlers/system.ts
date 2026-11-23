import { base64ToBytes } from "../net/http";
import { invokeNative, registerHandler } from "../electron-shim";
import { clearBackgroundImages, pickBackgroundImage, saveFileData } from "./pickers";

/**
 * 原生能力调用封装，未实现时抛出可捕获错误
 * @param channel - 原生 channel
 * @param args - 参数
 * @returns 原生返回值
 */
const native = (channel: string, ...args: unknown[]): Promise<unknown> => invokeNative(channel, ...args);

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
  registerHandler("system:setGestureExclusion", (rect: unknown) =>
    native("system:setGestureExclusion", rect),
  );
  registerHandler("system:focusMainWindow", () => true);
  registerHandler("system:openSettings", () => undefined);
  registerHandler("system:listFonts", () => []);
  // 保存走 SAF 新建文档，字节由 pickers 写入
  registerHandler("system:saveFile", (data: unknown, defaultName: unknown, title: unknown) =>
    saveFileData(data, String(defaultName ?? ""), String(title ?? "")),
  );
  // 安卓没有进程重启：重载 WebView 让渲染层重新读取已恢复的配置
  registerHandler("system:relaunch", () => {
    setTimeout(() => window.location.reload(), 120);
    return null;
  });
  // 真发一次请求再回报，避免"填错代理也提示成功"
  registerHandler("system:testNetworkProxy", async () => {
    const started = Date.now();
    try {
      await invokeNative("net:fetch", { url: "https://music.163.com/", method: "GET" });
      return { ok: true, latency: Date.now() - started };
    } catch {
      return { ok: false, latency: 0 };
    }
  });
  // 前端把 data 当作字节数组交给 Uint8Array，这里把原生的 base64 还原成字节
  registerHandler("system:fetchRemoteBytes", async (url: unknown) => {
    const reply = (await invokeNative("net:fetchBytes", url)) as { bodyBase64?: string } | null;
    console.log("[fetchRemoteBytes]", typeof reply, reply ? Object.keys(reply).join(",") : "null", (reply as { bodyBase64?: string })?.bodyBase64?.length ?? -1);
    if (!reply?.bodyBase64) return { success: false, data: null };
    return { success: true, data: base64ToBytes(reply.bodyBase64) };
  });

  registerHandler("theme:pickBackgroundImage", () => pickBackgroundImage());
  registerHandler("theme:clearBackgroundImages", () => clearBackgroundImages());
};

export { native };