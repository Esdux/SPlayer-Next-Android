/**
 * QQ 音乐扫码登录实现（浏览器版）
 *
 * 扫码链路本身不加密：ptqrshow 直接下发二维码图片与 qrsig，
 * ptqrlogin 用 hash33 算出的 ptqrtoken 轮询。HTTP 走 net:fetch（OkHttp）。
 */

import { bytesToBase64, getCookies, httpRequest, mergeSetCookie, setCookies } from "../net/http";

/** 网页 UA，QQ 登录态对 UA 敏感 */
const WEB_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
/** 移动端接口入口 */
const QM_API_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg";
/** 移动端请求头 */
const QM_HEADERS: Record<string, string> = {
  "Content-Type": "application/json",
  "User-Agent": "QQMusic 14090008(android 15)",
  Referer: "https://y.qq.com",
};
/** 请求体 comm 字段，伪装安卓客户端 */
const commonParams = (): Record<string, string | number> => ({
  ct: 11,
  cv: 14090008,
  v: 14090008,
  chid: "10003505",
  os_ver: "15",
  phonetype: "24122RKC7C",
  tmeAppID: "qqmusic",
  nettype: "NETWORK_WIFI",
  udid: "0",
  OpenUDID: "0",
  QIMEI36: "0",
  uin: "0",
});

/**
 * QQ 系哈希，用于 ptqrtoken
 * @param str - 待哈希串
 * @param seed - 初始值
 * @returns 结果
 */
const hash33 = (str: string, seed = 0): number => {
  let h = seed;
  for (let i = 0; i < str.length; i++) {
    h = (((h << 5) + h + str.charCodeAt(i)) & 0xffffffff) >>> 0;
  }
  return h & 2147483647;
};

/**
 * 从响应头取出 Cookie
 * @param setCookie - Set-Cookie 头
 * @returns Cookie 键值对
 */
const parseCookies = (setCookie: string | undefined): Record<string, string> => mergeSetCookie({}, setCookie);

/**
 * 获取二维码（QQ 或微信）
 * @param type - qq 或 wx
 * @returns key 与 data URL
 */
const loginQrKey = async (type: string): Promise<any> => {
  if (type === "wx") {
    const searchParams = new URLSearchParams({
      appid: "wx48db31d50e334801",
      redirect_uri: "https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=https://y.qq.com/",
      response_type: "code",
      scope: "snsapi_login",
      state: "STATE",
      href: "https://y.qq.com/mediastyle/music_v17/src/css/popup_wechat.css#wechat_redirect",
    });
    const page = await httpRequest({
      url: `https://open.weixin.qq.com/connect/qrconnect?${searchParams}`,
      headers: { "User-Agent": WEB_UA },
    });
    const match = /uuid=([^"]+)"|uuid=([a-zA-Z0-9_-]+)/.exec(page.text);
    const uuid = match?.[1] ?? match?.[2];
    if (!uuid) throw new Error("获取微信登录二维码 uuid 失败");
    const qr = await httpRequest({
      url: `https://open.weixin.qq.com/connect/qrcode/${uuid}`,
      headers: {
        Referer: "https://open.weixin.qq.com/connect/qrconnect",
        "User-Agent": WEB_UA,
      },
    });
    return {
      code: 200,
      key: uuid,
      content: `data:image/jpeg;base64,${bytesToBase64(qr.bytes)}`,
      type: "wx",
    };
  }

  const url = `https://ssl.ptlogin2.qq.com/ptqrshow?appid=716027609&e=2&l=M&s=3&d=72&v=4&t=${Math.random()}&daid=383&pt_3rd_aid=100497308`;
  const res = await httpRequest({
    url,
    headers: { Referer: "https://xui.ptlogin2.qq.com/", "User-Agent": WEB_UA },
  });
  const cookies = parseCookies(res.headers["set-cookie"]);
  const qrsig = cookies.qrsig;
  if (!qrsig) throw new Error("未能获取到 QQ 登录 qrsig");
  return {
    code: 200,
    key: qrsig,
    content: `data:image/png;base64,${bytesToBase64(res.bytes)}`,
    type: "qq",
  };
};

/**
 * 轮询扫码状态
 * @param key - qrsig
 * @returns 状态：0 过期 / 2 已扫待确认 / 4 成功
 */
const loginQrCheck = async (key: string): Promise<any> => {
  const query = new URLSearchParams({
    u1: "https://graph.qq.com/oauth2.0/login_jump",
    ptqrtoken: String(hash33(key, 0)),
    ptredirect: "0",
    h: "1",
    t: "1",
    g: "1",
    from_ui: "1",
    ptlang: "2052",
    action: `0-0-${Date.now()}`,
    js_ver: "20102616",
    js_type: "1",
    pt_uistyle: "40",
    aid: "716027609",
    daid: "383",
    pt_3rd_aid: "100497308",
    has_onekey: "1",
  });
  const res = await httpRequest({
    url: `https://ssl.ptlogin2.qq.com/ptqrlogin?${query}`,
    headers: {
      Referer: "https://xui.ptlogin2.qq.com/",
      Cookie: `qrsig=${key};`,
      "User-Agent": WEB_UA,
    },
  });
  const match = /ptuiCB\((.*?)\)/.exec(res.text);
  if (!match) return { code: 200, status: 1 };
  const args = [...match[1].matchAll(/'((?:\\.|[^'])*)'/g)].map((m) => m[1]);
  const statusCode = args[0];
  const nickname = args[5] || "";
  if (statusCode === "65") return { code: 200, status: 0 };
  if (statusCode === "67") return { code: 200, status: 2, nickname };
  if (statusCode === "0") {
    const jumpUrl = args[2] ?? "";
    if (jumpUrl.startsWith("http")) {
      // 手动跟随一次，取得 Set-Cookie 中的 p_skey，后续接口才有登录态
      const initial = mergeSetCookie({ qrsig: key }, res.headers["set-cookie"]);
      const check = await httpRequest({
        url: jumpUrl,
        headers: {
          Referer: "https://xui.ptlogin2.qq.com/",
          Cookie: Object.entries(initial)
            .map(([k, v]) => `${k}=${v}`)
            .join("; "),
          "User-Agent": WEB_UA,
        },
        followRedirects: false,
      });
      const session = mergeSetCookie(initial, check.headers["set-cookie"]);
      const pSkey = session.p_skey ?? session.p_skey ?? session.skey ?? session.pskey;
      if (pSkey) {
        setCookies("qqmusic", {
          ...getCookies("qqmusic"),
          ...session,
          p_skey: pSkey,
        });
      }
    }
    return { code: 200, status: 4, nickname };
  }
  return { code: 200, status: 1 };
};

/**
 * 调用 QQ 音乐接口
 * @param name - 接口名
 * @param params - 业务参数
 * @returns 统一响应
 */
export const qqmusicCall = async (
  name: string,
  params: Record<string, unknown> = {},
): Promise<{ ok: boolean; status?: number; body?: unknown; error?: string }> => {
  try {
    if (name === "login_qr_key") {
      return { ok: true, status: 200, body: await loginQrKey(String(params.type ?? "qq").toLowerCase()) };
    }
    if (name === "login_qr_check") {
      return { ok: true, status: 200, body: await loginQrCheck(String(params.key ?? "")) };
    }
    if (name === "user_detail" || name === "login_status") {
      const cookies = getCookies("qqmusic");
      const uin = cookies.uin ?? cookies.p_uin ?? "";
      if (!uin) return { ok: true, status: 200, body: { code: 200, loggedIn: false } };
      return {
        ok: true,
        status: 200,
        body: { code: 200, loggedIn: true, profile: { userId: uin, nickname: "" } },
      };
    }
    // 其余接口统一走 musicu 网关：一个 POST 就能覆盖搜索、详情、歌词等
    const body = {
      comm: commonParams(),
      request: {
        module: String(params.module ?? ""),
        method: String(params.method ?? ""),
        param: params.param ?? {},
      },
    };
    const cookies = getCookies("qqmusic");
    const cookieStr = Object.entries(cookies)
      .map(([key, value]) => `${key}=${value}`)
      .join("; ");
    const res = await httpRequest({
      url: QM_API_URL,
      method: "POST",
      headers: {
        ...QM_HEADERS,
        Cookie: cookieStr || "tmeLoginType=-1;",
      },
      body: JSON.stringify(body),
      contentType: "application/json",
    });
    if (res.status >= 400) return { ok: false, error: `HTTP ${res.status}`, status: res.status };
    return { ok: true, status: res.status, body: JSON.parse(res.text) };
  } catch (error) {
    return { ok: false, error: error instanceof Error ? error.message : String(error) };
  }
};

/**
 * 清空 QQ 音乐登录态
 */
export const clearQQMusicSession = (): void => {
  setCookies("qqmusic", {});
};

/**
 * 手动写入 QQ 音乐 Cookie
 * @param cookie - Cookie 字符串
 */
export const setQQMusicCookie = (cookie: string): void => {
  const current = getCookies("qqmusic");
  for (const part of cookie.split(";")) {
    const eq = part.indexOf("=");
    if (eq <= 0) continue;
    current[part.slice(0, eq).trim()] = part.slice(eq + 1).trim();
  }
  setCookies("qqmusic", current);
};
