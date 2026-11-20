/**
 * 酷狗音乐接口实现（浏览器版）
 *
 * 上游走 Android 网关签名 + 设备注册，这里只保留能直接用的部分：
 * 二维码登录与公开 CDN 搜索。签名算法与上游一致（MD5(salt + 排序参数 + salt)）。
 */

import { getCookies, cookieHeader, httpJson, httpRequest, mergeSetCookie, setCookies } from "../net/http";
import { md5 } from "./md5";

/** 登录服务域名 */
const LOGIN_HOST = "https://login-user.kugou.com";
/** 公开搜索 CDN */
const CDN_HOST = "https://mobilecdn.kugou.com";
/** Web 签名盐 */
const WEB_SIGN_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt";
/** 应用标识 */
const APPID = 1005;
/** 客户端版本 */
const CLIENTVER = 20489;
/** 扫码来源应用 */
const SRC_APPID = 2919;

/**
 * 设备号：上游按固定种子算出并缓存，这里等价处理
 * @returns 32 位十六进制设备号
 */
const deviceMid = (): string => md5("splayer-next-kugou-device-mid");

/**
 * Web 参数签名
 * @param params - 待签名参数
 * @returns 32 位十六进制签名
 */
const signWeb = (params: Record<string, unknown>): string => {
  const body = Object.keys(params)
    .map((key) => `${key}=${params[key] ?? ""}`)
    .sort()
    .join("");
  return md5(`${WEB_SIGN_SALT}${body}${WEB_SIGN_SALT}`);
};

/**
 * 带签名地请求登录服务
 * @param path - 相对路径
 * @param input - 业务参数
 * @returns 响应 JSON
 */
const loginRequest = async (path: string, input: Record<string, unknown>): Promise<any> => {
  const params: Record<string, unknown> = {
    dfid: "-",
    mid: deviceMid(),
    uuid: "-",
    appid: 1001,
    clientver: CLIENTVER,
    clienttime: Math.floor(Date.now() / 1000),
    ...input,
  };
  params.signature = signWeb(params);
  const query = new URLSearchParams(
    Object.entries(params).map(([key, value]) => [key, String(value)]),
  );
  const res = await httpJson<any>({ url: `${LOGIN_HOST}${path}?${query}` });
  return res;
};

/**
 * 获取扫码登录二维码
 * @returns key 与二维码内容
 */
const loginQrKey = async (): Promise<{ code: number; key: string; content: string }> => {
  const body = await loginRequest("/v2/qrcode", {
    type: 1,
    plat: 4,
    qrcode_txt: `https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=${APPID}&`,
    srcappid: SRC_APPID,
  });
  const key = body?.data?.qrcode;
  if (!key) throw new Error("KG QR key missing");
  // 接口本身就带二维码 PNG，直接给前端用，省掉再画一次
  const image = body?.data?.qrcode_img;
  return {
    code: 200,
    key: String(key),
    content: typeof image === "string" && image.startsWith("data:")
      ? image
      : `https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=${key}`,
  };
};

/**
 * 轮询扫码状态
 * @param key - 二维码 key
 * @returns 状态与资料
 */
const loginQrCheck = async (key: string): Promise<any> => {
  if (!key) throw new Error("KG QR key missing");
  const body = await loginRequest("/v2/get_userinfo_qrcode", {
    plat: 4,
    appid: APPID,
    srcappid: SRC_APPID,
    qrcode: key,
  });
  const data = body?.data ?? {};
  const nickname = data.username ?? data.nickname ?? data.nick_name ?? data.user_name;
  const avatarUrl =
    data.userpic ?? data.user_pic ?? data.avatar ?? data.avatar_url ?? data.pic ?? data.user_img;
  if (Number(data.status) === 4) {
    const cookies = getCookies("kugou");
    setCookies("kugou", {
      ...cookies,
      token: String(data.token ?? ""),
      userid: String(data.userid ?? ""),
      vip_token: String(data.vip_token ?? ""),
      vip_type: String(data.vip_type ?? ""),
    });
  }
  return {
    code: 200,
    status: Number(data.status ?? 0),
    nickname,
    avatarUrl,
    token: data.token,
    userid: data.userid,
  };
};

/**
 * 搜索歌曲
 * @param params - 关键词与分页
 * @returns 统一响应
 */
const search = async (params: Record<string, unknown>): Promise<any> => {
  const keyword = String(params.keywords ?? params.keyword ?? params.q ?? "");
  const page = Number(params.page ?? 1);
  const pagesize = Number(params.limit ?? params.pagesize ?? 30);
  const url = `${CDN_HOST}/api/v3/search/song?format=json&keyword=${encodeURIComponent(keyword)}&page=${page}&pagesize=${pagesize}&showtype=1`;
  const body = await httpJson<any>({ url });
  const items: any[] = body?.data?.info ?? [];
  return {
    code: 200,
    data: {
      total: body?.data?.total ?? items.length,
      songs: items.map((item) => ({
        id: String(item.hash ?? ""),
        name: item.songname ?? "",
        artist: item.singername ?? "",
        album: item.album_name ?? "",
        duration: Number(item.duration ?? 0) * 1000,
        hash: item.hash,
        albumId: item.album_id,
      })),
    },
  };
};

/** 平台响应 */
export interface KgResponse {
  ok: boolean;
  status?: number;
  body?: unknown;
  error?: string;
}

/**
 * 调用酷狗接口
 * @param name - 接口名
 * @param params - 业务参数
 * @returns 统一响应
 */
export const kugouCall = async (
  name: string,
  params: Record<string, unknown> = {},
): Promise<KgResponse> => {
  try {
    if (name === "login_qr_key") return { ok: true, status: 200, body: await loginQrKey() };
    if (name === "login_qr_check") {
      return { ok: true, status: 200, body: await loginQrCheck(String(params.key ?? "")) };
    }
    if (name === "search" || name === "search_song") {
      return { ok: true, status: 200, body: await search(params) };
    }
    if (name === "user_detail" || name === "login_status") {
      const cookies = getCookies("kugou");
      if (!cookies.token) return { ok: true, status: 200, body: { code: 200, loggedIn: false } };
      return {
        ok: true,
        status: 200,
        body: { code: 200, loggedIn: true, profile: { userId: cookies.userid, nickname: "" } },
      };
    }
    return { ok: false, error: `酷狗接口未移植: ${name}` };
  } catch (error) {
    return { ok: false, error: error instanceof Error ? error.message : String(error) };
  }
};

/**
 * 清空酷狗登录态
 */
export const clearKugouSession = (): void => {
  setCookies("kugou", {});
};

/**
 * 手动写入酷狗 Cookie
 * @param cookie - Cookie 字符串
 */
export const setKugouCookie = (cookie: string): void => {
  const current = getCookies("kugou");
  for (const part of cookie.split(";")) {
    const eq = part.indexOf("=");
    if (eq <= 0) continue;
    current[part.slice(0, eq).trim()] = part.slice(eq + 1).trim();
  }
  setCookies("kugou", current);
};
