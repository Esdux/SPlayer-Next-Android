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
  /** 参数改写：上游多数接口用 keywords，接口层要求 s */
  mapParams?: (params: Record<string, unknown>) => Record<string, unknown>;
  /** 动态路径：路径里带资源 id 的接口用它拼 */
  pathOf?: (params: Record<string, unknown>) => string;
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
  // 登录状态必须包一层 data，前端读的是 body.data.code / body.data.profile
  login_status: {
    path: "/api/w/nuser/account/get",
    wrap: (json) => (json?.code === 200 ? { data: json } : json),
  },
  logout: { path: "/api/logout" },
  search: {
    path: "/api/search/get",
    mapParams: (params) => {
      const { keywords, ...rest } = params;
      return { s: keywords ?? params.s ?? params.keyword ?? "", ...rest };
    },
  },
  song_detail: {
    path: "/api/v3/song/detail",
    // 服务端要的是 c=[{"id":1},{"id":2}]，前端传的是逗号分隔 ids
    mapParams: (params) => {
      const ids = String(params.ids ?? params.id ?? "")
        .replace(/[[\]]/g, "")
        .split(",")
        .map((item) => item.trim())
        .filter(Boolean);
      return { c: `[${ids.map((id) => `{"id":${Number(id)}}`).join(",")}]` };
    },
  },
  lyric: {
    path: "/api/song/lyric",
    // 缺 tv/lv/rv/kv 时服务端不返回 lrc 字段
    mapParams: (params) => ({
      id: params.id,
      tv: -1,
      lv: -1,
      rv: -1,
      kv: -1,
      _nmclfl: 1,
    }),
  },
  lyric_new: {
    path: "/api/song/lyric/v1",
    // 新版歌词：yrc 逐字 + tlyric 翻译 + romalrc 罗马音
    mapParams: (params) => ({
      id: params.id,
      cp: false,
      tv: 0,
      lv: 0,
      rv: 0,
      kv: 0,
      yv: 0,
      ytv: 0,
      yrv: 0,
    }),
  },
  playlist_detail: {
    path: "/api/v6/playlist/detail",
    // 不带 n 时服务端只给前 10 首
    mapParams: (params) => ({ id: params.id, n: 100000, s: params.s ?? 8 }),
  },
  album: {
    path: "/api/v1/album",
    pathOf: (p) => `/api/v1/album/${p.id ?? ""}`,
  },
  album_new: {
    path: "/api/album/new",
    mapParams: (p) => ({
      area: "ALL",
      offset: 0,
      total: true,
      limit: p.limit ?? 30,
    }),
  },
  artist_songs: { path: "/api/v1/artist/songs" },
  artist_detail: { path: "/api/v1/artist" },
  album_detail: {
    path: "/api/v1/album",
    pathOf: (p) => `/api/v1/album/${p.id ?? ""}`,
  },
  user_playlist: { path: "/api/user/playlist" },
  recommend_resource: { path: "/api/v1/discovery/recommend/resource" },
  recommend_songs: { path: "/api/v3/discovery/recommend/songs" },
  personal_fm: { path: "/api/v1/radio/get" },
  toplist: { path: "/api/toplist" },
  playlist_catlist: { path: "/api/playlist/catlist" },
  playlist_hot: { path: "/api/playlist/hottags" },
  dj_detail: { path: "/api/v1/djradio/detail" },
  // 歌单增删改
  playlist_create: { path: "/api/playlist/create" },
  playlist_delete: { path: "/api/playlist/remove" },
  playlist_desc_update: { path: "/api/playlist/desc/update" },
  playlist_name_update: { path: "/api/playlist/update/name" },
  playlist_order_update: { path: "/api/playlist/order/update" },
  playlist_subscribe: { path: "/api/playlist/subscribe" },
  playlist_tracks: {
    path: "/api/playlist/manipulate/tracks",
    mapParams: (params) => ({
      op: params.op,
      pid: params.pid,
      trackIds: JSON.stringify(String(params.tracks ?? "").split(",")),
      imme: "true",
    }),
  },
  // 喜欢 / 收藏
  likelist: { path: "/api/song/like/get" },
  // 与桌面一致：旧版走 /api/radio/like，参数必须是 trackId/alg/time，否则服务端拒绝
  song_like: {
    path: "/api/radio/like",
    mapParams: (p) => ({
      alg: "itembased",
      trackId: String(p.id ?? ""),
      like: p.like !== false && p.like !== "false",
      time: "3",
    }),
  },
  fm_trash: { path: "/api/radio/trash/add" },
  artist_sub: { path: "/api/artist/sub" },
  artist_sublist: { path: "/api/artist/sublist" },
  album_sub: { path: "/api/album/sub" },
  album_sublist: { path: "/api/album/sublist" },
  artists: { pathOf: (params) => `/api/v1/artist/${params.id ?? ""}` },
  artist_album: { pathOf: (params) => `/api/artist/albums/${params.id ?? ""}` },
  // 用户数据
  user_level: { path: "/api/user/level" },
  top_artists: { path: "/api/artist/top" },
  personalized: { path: "/api/personalized/playlist" },
  playmode_intelligence: { path: "/api/playmode/intelligence/list" },
  login_refresh: { path: "/api/login/token/refresh" },
  user_subcount: { path: "/api/subcount" },
  user_cloud: { path: "/api/v1/cloud/get" },
  user_cloud_del: { path: "/api/cloud/del" },
  // 播放地址
  comment_music: {
    path: "/api/v1/resource/comments/R_SO_4_0",
    // pathOf 收到的是 mapParams 的返回值，id 要一起带出去才能拼进路径
    pathOf: (p) => `/api/v1/resource/comments/R_SO_4_${p.id ?? ""}`,
    mapParams: (p) => ({
      id: p.id,
      limit: p.limit ?? 20,
      offset: p.offset ?? 0,
      ...(p.before ? { before: p.before } : {}),
    }),
  },
  comment_hot: {
    path: "/api/v1/resource/hotcomments/R_SO_4_0",
    // 同上：id 留在 mapParams 里供 pathOf 拼路径，rid 是热门评论接口的业务字段
    pathOf: (p) => `/api/v1/resource/hotcomments/R_SO_4_${p.id ?? ""}`,
    mapParams: (p) => ({
      id: p.id,
      rid: p.id ?? "",
      limit: p.limit ?? 20,
      offset: p.offset ?? 0,
      beforeTime: p.beforeTime ?? 0,
    }),
  },
  like: {
    path: "/api/radio/like",
    mapParams: (p) => ({
      alg: "itembased",
      trackId: String(p.id ?? ""),
      like: p.like !== false && p.like !== "false",
      time: "3",
    }),
  },
  song_url: {
    path: "/api/song/enhance/player/url/v1",
    mapParams: (params) => {
      const raw = String(params.id ?? params.ids ?? "");
      return {
        ids: `[${raw.split(",").join(",")}]`,
        level: String(params.level ?? "exhigh"),
        encodeType: "flac",
      };
    },
  },
  song_download_url: {
    path: "/api/song/enhance/download/url/v1",
    mapParams: (params) => {
      const raw = String(params.id ?? params.ids ?? "");
      return {
        id: raw,
        level: String(params.level ?? "exhigh"),
      };
    },
  },
  // 搜索
  cloudsearch: {
    path: "/api/cloudsearch/pc",
    mapParams: (params) => {
      const { keywords, ...rest } = params;
      return { s: keywords ?? params.s ?? "", ...rest };
    },
  },
  search_suggest: {
    path: "/api/search/suggest/web",
    mapParams: (params) => ({ s: params.keywords ?? "" }),
  },
  deviceinfo_center_upload: { path: "/api/deviceinfo/center/upload" },
};

/** 随机十六进制串 */
const randomHex = (bytes: number): string => {
  const buf = new Uint8Array(bytes);
  crypto.getRandomValues(buf);
  return [...buf].map((byte) => byte.toString(16).padStart(2, "0")).join("");
};

/** 直连接口的单次请求超时 */
const DIRECT_TIMEOUT_MS = 8000;

/** 不走 weapi 的直连接口描述 */
interface DirectRouteSpec {
  /** 完整接口地址 */
  url: string;
  /** 业务参数转 query 字符串 */
  buildQuery: (params: Record<string, unknown>) => string;
  /** 固定请求头 */
  headers?: Record<string, string>;
}

/**
 * 直连接口路由
 *
 * audio_match 只接受 AFP 指纹，不校验登录态也不需要 weapi 签名，
 * 与桌面 modules/audio_match.ts 保持一致
 */
const DIRECT_ROUTES: Record<string, DirectRouteSpec> = {
  audio_match: {
    url: "https://interface.music.163.com/api/music/audio/match",
    buildQuery: (params) => {
      const fingerprint = typeof params.audioFP === "string" ? params.audioFP : "";
      const duration = Number(params.duration);
      if (!fingerprint || !Number.isFinite(duration) || duration <= 0) {
        throw new Error("无效的听歌识曲参数");
      }
      return new URLSearchParams({
        sessionId: randomHex(8),
        algorithmCode: "shazam_v2",
        duration: String(duration),
        rawdata: fingerprint,
        times: "1",
        decrypt: "1",
      }).toString();
    },
    headers: {
      Accept: "application/json",
      Referer: "https://music.163.com/",
      "User-Agent": "Mozilla/5.0",
    },
  },
  search_defaultkeyword_get: {
    url: "https://music.163.com/api/search/defaultkeyword/get",
    buildQuery: () => "",
    headers: {
      Accept: "application/json",
      Referer: "https://music.163.com/",
      "User-Agent": WEB_UA,
    },
  },
  search_hot_detail: {
    url: "https://music.163.com/api/search/hot",
    // 热搜榜：type=1 返回 { result: { hots: [{ first, second, iconType }] } }
    buildQuery: (params) => new URLSearchParams({ type: String(params.type ?? 1) }).toString(),
    headers: {
      Accept: "application/json",
      Referer: "https://music.163.com/",
      "User-Agent": WEB_UA,
    },
  },
};

/**
 * 调用直连接口
 * @param spec - 路由描述
 * @param params - 业务参数
 * @returns 统一响应
 */
const directCall = async (
  spec: DirectRouteSpec,
  params: Record<string, unknown>,
): Promise<{ ok: boolean; status?: number; body?: unknown; error?: string }> => {
  const url = `${spec.url}?${spec.buildQuery(params)}`;
  const res = await httpRequest({
    url,
    method: "GET",
    headers: spec.headers ?? {},
    timeoutMs: DIRECT_TIMEOUT_MS,
  });
  if (res.status >= 400) {
    return { ok: false, error: `HTTP ${res.status}`, status: res.status };
  }
  try {
    return { ok: true, status: res.status, body: JSON.parse(res.text) };
  } catch {
    return { ok: false, error: `响应不是 JSON（HTTP ${res.status}）`, status: res.status };
  }
};

/**
 * 补齐客户端指纹 cookie
 *
 * 网易把 os / appver / deviceId / _ntes_nuid 这批字段的缺失判成异常客户端，
 * 扫码确认后直接回 8821 风控，此处照上游 processCookieObject 注入。
 * @param cookies - 现有 cookie
 * @returns 补全后的 cookie
 */
const ensureBaseCookies = (cookies: Record<string, string>): Record<string, string> => {
  const next = { ...cookies };
  if (!next._ntes_nuid) next._ntes_nuid = randomHex(16);
  if (!next._ntes_nnid) next._ntes_nnid = `${next._ntes_nuid},${Date.now()}`;
  if (!next.WNMCID) next.WNMCID = randomHex(16);
  if (!next.WEVNSM) next.WEVNSM = "1.0.0";
  if (!next.os) next.os = "pc";
  if (!next.osver) next.osver = "10";
  if (!next.appver) next.appver = "2.9.7";
  if (!next.deviceId) next.deviceId = randomHex(16).toUpperCase();
  if (!next.__remember_me) next.__remember_me = "true";
  if (!next.ntes_kaola_ad) next.ntes_kaola_ad = "1";
  return next;
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
  const direct = DIRECT_ROUTES[name];
  if (direct) return await directCall(direct, params);
  const route = ROUTES[name];
  let cookies = getCookies("netease");
  const baseCookies = ensureBaseCookies(cookies);
  if (Object.keys(baseCookies).length !== Object.keys(cookies).length) {
    cookies = baseCookies;
    setCookies("netease", cookies);
  }
  const mapped = route?.mapParams ? route.mapParams(params) : params;
  const payload: Record<string, unknown> = {
    ...(route?.fixed ?? {}),
    ...mapped,
    csrf_token: cookies.__csrf ?? "",
    e_r: false,
  };
  delete payload.timestamp;
  const path = route?.pathOf ? route.pathOf(mapped) : (route?.path ?? `/api/${name}`);
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
  if (!route && !name.startsWith("login")) {
    console.log("[netease] route-missing", name);
  }
  if (name.startsWith("login")) {
    console.log(
      "[netease]",
      name,
      res.status,
      "cookie=" + Object.keys(cookies).join("|"),
      "setCookieKeys=" + Object.keys(nextCookies).length,
      String(res.text ?? "").slice(0, 180),
    );
  }
  if (Object.keys(nextCookies).length !== Object.keys(cookies).length) {
    setCookies("netease", nextCookies);
  }
  let json: any;
  try {
    json = JSON.parse(res.text);
  } catch {
    const snippet = res.text.slice(0, 200).replace(/\s+/g, " ");
    return {
      ok: false,
      error: `响应不是 JSON（HTTP ${res.status}，长度 ${res.bytes.length}）：${snippet}`,
      status: res.status,
    };
  }
  const wrapped = route?.wrap ? route.wrap(json) : json;
  // 扫码登录 803 时把 cookie 一并回传，前端据此判定登录成功
  if (name === "login_qr_check" && (wrapped as any)?.code === 803) {
    (wrapped as any).cookie = cookieHeader(nextCookies);
    setCookies("netease", nextCookies);
  }
  const code = (wrapped as { code?: number } | null)?.code;
  if (code !== undefined && code !== 200) {
    console.log("[netease] code", name, code, String(res.text ?? "").slice(0, 140));
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