import { api, electronAPI } from "./preload-api";
import { installReplyChannel, isAndroidHost } from "./electron-shim";
import { registerAndroidHandlers } from "./handlers";

/**
 * 安卓壳内安装 window.api，桌面环境保持原样
 */
export const installAndroidApi = (): void => {
  if (!isAndroidHost()) return;
  installReplyChannel();
  registerAndroidHandlers();
  const target = window as unknown as Record<string, unknown>;
  target.electron = electronAPI;
  target.api = api;
};

installAndroidApi();
