/**
 * 安卓侧统一 HTTP 出口
 *
 * 渲染进程跑在 https://appassets.androidplatform.net 下，直接 fetch 第三方域名会被 CORS 拦，
 * 所以一律经原生 net:fetch（OkHttp）发出，这里只负责编解码与 Cookie 罐。
 */

import { ipcRenderer } from "../electron-shim";

/** 请求参数 */
export interface HttpOptions {
  url: string;
  method?: string;
  headers?: Record<string, string>;
  body?: string | Uint8Array;
  contentType?: string;
  /** 是否自动跟随 302，QQ check_sig 需要关掉以取得跳转中的 Set-Cookie */
  followRedirects?: boolean;
}

/** 响应结构 */
export interface HttpResponse {
  status: number;
  headers: Record<string, string>;
  bytes: Uint8Array;
  /** 按 UTF-8 解码的文本 */
  text: string;
}

/**
 * 字节转 base64，分块避免一次性展开过多参数爆栈
 * @param bytes - 原始字节
 * @returns base64 文本
 */
export const bytesToBase64 = (bytes: Uint8Array): string => {
  let out = "";
  const chunk = 0x1000;
  for (let i = 0; i < bytes.length; i += chunk) {
    const slice = bytes.subarray(i, i + chunk);
    out += String.fromCharCode.apply(null, slice as unknown as number[]);
  }
  return btoa(out);
};

/**
 * base64 转字节
 * @param text - base64 文本
 * @returns 原始字节
 */
export const base64ToBytes = (text: string): Uint8Array => {
  const raw = atob(text);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes;
};

/**
 * 发起一次请求
 * @param options - 请求参数
 * @returns 响应
 */
export const httpRequest = async (options: HttpOptions): Promise<HttpResponse> => {
  const {
    url,
    method = "GET",
    headers = {},
    body,
    contentType = "application/octet-stream",
    followRedirects = true,
  } = options;
  const payload: Record<string, unknown> = { url, method, headers, followRedirects };
  if (body !== undefined) {
    const bytes = typeof body === "string" ? new TextEncoder().encode(body) : body;
    payload.bodyBase64 = bytesToBase64(bytes);
    payload.contentType = contentType;
  }
  const raw = (await ipcRenderer.invoke("net:fetch", payload)) as {
    status: number;
    headers: Record<string, string>;
    bodyBase64: string;
  };
  const bytes = raw.bodyBase64 ? base64ToBytes(raw.bodyBase64) : new Uint8Array(0);
  const lower: Record<string, string> = {};
  for (const [key, value] of Object.entries(raw.headers ?? {})) lower[key.toLowerCase()] = value;
  return {
    status: raw.status,
    headers: lower,
    bytes,
    text: new TextDecoder().decode(bytes),
  };
};

/**
 * 发起请求并解析 JSON
 * @param options - 请求参数
 * @returns 解析后的对象
 */
export const httpJson = async <T = any>(options: HttpOptions): Promise<T> => {
  const res = await httpRequest(options);
  if (res.status >= 400) throw new Error(`HTTP ${res.status}`);
  return JSON.parse(res.text) as T;
};

/** Cookie 罐：平台 → 键值对 */
type CookieJar = Record<string, Record<string, string>>;

const JAR_KEY = "splayer.android.cookies";

/**
 * 读取全部 Cookie
 * @returns 平台到 Cookie 的映射
 */
export const readJar = (): CookieJar => {
  try {
    return JSON.parse(localStorage.getItem(JAR_KEY) ?? "{}") as CookieJar;
  } catch {
    return {};
  }
};

/**
 * 持久化 Cookie 罐
 * @param jar - 全量映射
 */
export const writeJar = (jar: CookieJar): void => {
  localStorage.setItem(JAR_KEY, JSON.stringify(jar));
};

/**
 * 取某平台的 Cookie
 * @param platform - 平台标识
 * @returns Cookie 键值对
 */
export const getCookies = (platform: string): Record<string, string> => readJar()[platform] ?? {};

/**
 * 写入某平台的 Cookie
 * @param platform - 平台标识
 * @param cookies - Cookie 键值对
 */
export const setCookies = (platform: string, cookies: Record<string, string>): void => {
  const jar = readJar();
  jar[platform] = cookies;
  writeJar(jar);
};

/**
 * 清空某平台的 Cookie
 * @param platform - 平台标识
 */
export const clearCookies = (platform: string): void => {
  const jar = readJar();
  delete jar[platform];
  writeJar(jar);
};

/**
 * 合并响应里的 Set-Cookie 到已有 Cookie
 * @param existing - 现有 Cookie
 * @param setCookie - 响应头中的 Set-Cookie，可能是逗号合并的多条
 * @returns 合并结果
 */
export const mergeSetCookie = (
  existing: Record<string, string>,
  setCookie: string | undefined,
): Record<string, string> => {
  const merged = { ...existing };
  if (!setCookie) return merged;
  // OkHttp 把多条 Set-Cookie 用逗号拼接；按「逗号 + 形如 name=」切分，避免切坏 Expires 里的日期
  for (const part of setCookie.split(/,\s*(?=[A-Za-z0-9_.-]+=)/)) {
    const first = part.split(";")[0];
    const eq = first.indexOf("=");
    if (eq <= 0) continue;
    const name = first.slice(0, eq).trim();
    const value = first.slice(eq + 1).trim();
    if (name) merged[name] = value;
  }
  return merged;
};

/**
 * 把 Cookie 对象序列化为请求头
 * @param cookies - Cookie 键值对
 * @returns Cookie 头内容
 */
export const cookieHeader = (cookies: Record<string, string>): string =>
  Object.entries(cookies)
    .filter(([, value]) => value !== undefined && value !== "")
    .map(([key, value]) => `${key}=${value}`)
    .join("; ");
