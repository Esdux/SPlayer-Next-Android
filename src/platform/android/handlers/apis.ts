/**
 * 音源 API handler
 *
 * 把 apis:* 四个 channel 接到浏览器版实现上：
 * 网络走 net:fetch（OkHttp）绕开 CORS，加密与签名全部在 JS 侧完成。
 */

import { registerHandler } from "../electron-shim";
import { clearNeteaseSession, neteaseCall, setNeteaseCookie } from "../apis/netease";
import { clearKugouSession, kugouCall, setKugouCookie } from "../apis/kugou";
import { clearQQMusicSession, qqmusicCall, setQQMusicCookie } from "../apis/qqmusic";

/** 支持的平台 */
type Platform = "netease" | "qqmusic" | "kugou";

/**
 * 按平台分发接口调用
 * @param platform - 平台标识
 * @param name - 接口名
 * @param params - 业务参数
 * @returns 统一响应
 */
const dispatch = (platform: Platform, name: string, params: Record<string, unknown>) => {
  if (platform === "netease") return neteaseCall(name, params);
  if (platform === "kugou") return kugouCall(name, params);
  if (platform === "qqmusic") return qqmusicCall(name, params);
  return Promise.resolve({ ok: false, error: `未知平台: ${platform}` });
};

/**
 * 注册音源相关 channel
 */
export const registerApisHandlers = (): void => {
  registerHandler("apis:call", async (platform: unknown, name: unknown, params: unknown) =>
    dispatch(
      String(platform) as Platform,
      String(name),
      (params ?? {}) as Record<string, unknown>,
    ),
  );

  registerHandler("apis:clearSession", async (platform: unknown) => {
    const key = String(platform);
    if (key === "netease") clearNeteaseSession();
    else if (key === "kugou") clearKugouSession();
    else if (key === "qqmusic") clearQQMusicSession();
    return undefined;
  });

  registerHandler("apis:setCookie", async (platform: unknown, cookie: unknown) => {
    const key = String(platform);
    try {
      if (key === "netease") setNeteaseCookie(String(cookie ?? ""));
      else if (key === "kugou") setKugouCookie(String(cookie ?? ""));
      else if (key === "qqmusic") setQQMusicCookie(String(cookie ?? ""));
      else return { ok: false, error: `未知平台: ${key}` };
      return { ok: true };
    } catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : String(error) };
    }
  });

  // 安卓没有多窗口网页登录，统一引导到扫码或 Cookie
  registerHandler("apis:openLoginWeb", async () => ({
    ok: false,
    error: "安卓版请使用扫码登录或手动填入 Cookie",
  }));
};
