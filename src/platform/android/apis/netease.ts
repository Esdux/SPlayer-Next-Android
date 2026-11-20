/**
 * 网易云音乐 weapi 实现（浏览器版）
 *
 * 上游在 Electron 主进程用 node:crypto 做 AES-128-CBC 与裸 RSA-1024；
 * 这里用 Web Crypto 做 AES（自带 PKCS7），用 BigInt 手写模幂做裸 RSA，
 * 不引入任何第三方密码学依赖。HTTP 走 net:fetch 以绕开 CORS。
 */

import {
  base64ToBytes,
  bytesToBase64,
  clearCookies,
  cookieHeader,
  getCookies,
  httpRequest,
  mergeSetCookie,
  setCookies,
} from "../net/http";

/** 网页版域名 */
const DOMAIN = "https://music.163.com";
/** AES-CBC 初始向量 */
const IV = "0102030405060708";
/** 第一层 AES 预设密钥 */
const PRESET_KEY = "0CoJUm6Qyw8W8jud";
/** 随机 secretKey 字符集 */
const BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
/** weapi RSA 公钥（1024bit） */
const PUBLIC_KEY =
  "-----BEGIN PUBLIC KEY-----\n" +
  "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB\n" +
  "-----END PUBLIC KEY-----";
/** weapi 请求 UA */
const WEB_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

/** 解析出的模数缓存 */
let cachedModulus: bigint | null = null;

/**
 * 从 SPKI 公钥里取出模数
 * @returns RSA 模数
 */
const modulus = (): bigint => {
  if (cachedModulus) return cachedModulus;
  const body = PUBLIC_KEY.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = base64ToBytes(body);
  // SPKI 末尾是 INTEGER n 与 INTEGER e(010001)；e 的 TLV 固定为 02 03 01 00 01
  for (let i = der.length - 5; i >= 0; i--) {
    if (der[i] === 0x02 && der[i + 1] === 0x03 && der[i + 2] === 0x01 && der[i + 3] === 0x00 && der[i + 4] === 0x01) {
      let hex = "";
      for (let j = i - 128; j < i; j++) hex += der[j].toString(16).padStart(2, "0");
      cachedModulus = BigInt("0x" + hex);
      return cachedModulus;
    }
  }
  throw new Error("netease public key parse failed");
};

/**
 * 模幂运算
 * @param base - 底数
 * @param exp - 指数
 * @param mod - 模
 * @returns base^exp mod mod
 */
const modPow = (base: bigint, exp: bigint, mod: bigint): bigint => {
  let result = 1n;
  let b = base % mod;
  let e = exp;
  while (e > 0n) {
    if ((e & 1n) === 1n) result = (result * b) % mod;
    b = (b * b) % mod;
    e >>= 1n;
  }
  return result;
};

/**
 * 裸 RSA 加密：明文左补零到 128 字节后直接做模幂，输出 256 位 hex
 * @param text - 明文
 * @returns 十六进制密文
 */
const rsaEncryptRaw = (text: string): string => {
  const data = new TextEncoder().encode(text);
  const block = new Uint8Array(128);
  block.set(data, 128 - data.length);
  let hex = "";
  for (const byte of block) hex += byte.toString(16).padStart(2, "0");
  const value = modPow(BigInt("0x" + hex), 65537n, modulus());
  return value.toString(16).padStart(256, "0");
};

/**
 * AES-128-CBC 加密，输出 base64
 * @param key - 16 字节密钥
 * @param text - 明文
 * @returns base64 密文
 */
const aesEncrypt = async (key: string, text: string): Promise<string> => {
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(key),
    { name: "AES-CBC" },
    false,
    ["encrypt"],
  );
  const encrypted = await crypto.subtle.encrypt(
    { name: "AES-CBC", iv: new TextEncoder().encode(IV) },
    cryptoKey,
    new TextEncoder().encode(text),
  );
  return bytesToBase64(new Uint8Array(encrypted));
};

/**
 * weapi 两层加密
 * @param payload - 业务参数
 * @returns 请求体字段
 */
const weapi = async (payload: Record<string, unknown>): Promise<{ params: string; encSecKey: string }> => {
  const text = JSON.stringify(payload);
  let secretKey = "";
  const random = new Uint8Array(16);
  crypto.getRandomValues(random);
  for (let i = 0; i < 16; i++) secretKey += BASE62.charAt(random[i] % 62);
  const first = await aesEncrypt(PRESET_KEY, text);
  const params = await aesEncrypt(secretKey, first);
  const encSecKey = rsaEncryptRaw(secretKey.split("").reverse().join(""));
  return { params, encSecKey };
};

/** 接口路由描述 */
interface RouteSpec {
  /** 接口路径（/api/ 开头） */
  path: string;
  /** 固定附加参数 */
  fixed?: Record<string, unknown>;
  /** 结果包装 */
  wrap?: (json: any) => unknown;
}

/** 已确认走 weapi 的接口路由，未列出时按 /api/<name> 直通 */
const ROUTES: Record<string, RouteSpec> = {
  login_qr_key: {
    path: "/api/login/qrcode/unikey",
    fixed: { type: 3 },
    wrap: (json) => ({ code: 200, data: json }),
  },
  login_qr_check: {
    path: "/api/login/qrcode/client/login",
    fixed: { type: 3 },
    wrap: (json) => json,
  },
  login_status: { path: "/api/w/nuser/account/get" },
  logout: { path: "/api/logout" },
  search: { path: "/api/cloudsearch/pc" },
  song_detail: { path: "/api/v3/song/detail" },
  lyric: { path: "/api/song/lyric" },
  playlist_detail: { path: "/api/v6/playlist/detail" },
  album: { path: "/api/v1/album" },
  artist_songs: { path: "/api/v1/artist/songs" },
  artist_detail: { path: "/api/v1/artist" },
  album_detail: { path: "/api/v1/album" },
  user_playlist: { path: "/api/user/playlist" },
  recommend_resource: { path: "/api/v1/discovery/recommend/resource" },
  recommend_songs: { path: "/api/v3/discovery/recommend/songs" },
  personal_fm: { path: "/api/v1/personal/fm" },
  comment_music: { path: "/api/v1/resource/comments/R_SO_4" },
  toplist: { path: "/api/toplist" },
  playlist_catlist: { path: "/api/playlist/catlist" },
  playlist_hot: { path: "/api/playlist/hottags" },
  dj_detail: { path: "/api/v1/djradio/detail" },
};

/**
 * 调用网易接口
 * @param name - 接口名
 * @param params - 业务参数
 * @returns 统一响应
 */
export const neteaseCall = async (
  name: string,
  params: Record<string, unknown> = {},
): Promise<{ ok: boolean; status?: number; body?: unknown; error?: string }> => {
  const route = ROUTES[name];
  const path = route?.path ?? `/api/${name}`;
  const cookies = getCookies("netease");
  const payload: Record<string, unknown> = {
    ...(route?.fixed ?? {}),
    ...params,
    csrf_token: cookies.__csrf ?? "",
    e_r: false,
  };
  delete payload.timestamp;
  const { params: encParams, encSecKey } = await weapi(payload);
  const url = `${DOMAIN}/weapi/${path.slice(5)}`;
  const res = await httpRequest({
    url,
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      Referer: DOMAIN,
      "User-Agent": WEB_UA,
      Cookie: cookieHeader(cookies),
    },
    body: `params=${encodeURIComponent(encParams)}&encSecKey=${encSecKey}`,
    contentType: "application/x-www-form-urlencoded",
  });
  if (res.status >= 400) {
    return { ok: false, error: `HTTP ${res.status}`, status: res.status };
  }
  const nextCookies = mergeSetCookie(cookies, res.headers["set-cookie"]);
  if (Object.keys(nextCookies).length !== Object.keys(cookies).length) {
    setCookies("netease", nextCookies);
  }
  let json: any;
  try {
    json = JSON.parse(res.text);
  } catch {
    return { ok: false, error: "响应不是 JSON", status: res.status };
  }
  const wrapped = route?.wrap ? route.wrap(json) : json;
  // 扫码登录 803 时把 cookie 一并回传，前端据此判定登录成功
  if (name === "login_qr_check" && (wrapped as any)?.code === 803) {
    (wrapped as any).cookie = cookieHeader(nextCookies);
    setCookies("netease", nextCookies);
  }
  return { ok: true, status: res.status, body: wrapped };
};

/**
 * 清空网易登录态
 */
export const clearNeteaseSession = (): void => {
  clearCookies("netease");
};

/**
 * 手动写入网易 Cookie
 * @param cookie - 形如 MUSIC_U=xxx; __csrf=yyy
 */
export const setNeteaseCookie = (cookie: string): void => {
  const current = getCookies("netease");
  for (const part of cookie.split(";")) {
    const eq = part.indexOf("=");
    if (eq <= 0) continue;
    current[part.slice(0, eq).trim()] = part.slice(eq + 1).trim();
  }
  setCookies("netease", current);
};
