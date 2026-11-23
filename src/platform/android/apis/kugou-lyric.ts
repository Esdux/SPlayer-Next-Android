/**
 * 酷狗歌词：公开的 krcs 搜索 + download 链路
 *
 * 这条链路由桌面端验证有效，无签名、无 salt，不要套用 Android gateway 的 MD5 规则
 */
import { base64ToBytes, httpJson } from "../net/http";

/** 酷狗歌词接口要求的请求头 */
const KG_LYRIC_HEADERS: Record<string, string> = {
  "KG-RC": "1",
  "KG-THash": "expand_search_manager.cpp:852736169:451",
  "User-Agent": "KuGou2012-9020-ExpandSearchManager",
};

/** KRC 固定异或密钥，16 字节 */
const KRC_KEY = Uint8Array.from([
  0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47, 0x51, 0x36, 0x31, 0x2d, 0xce, 0xd2, 0x6e, 0x69,
]);

/** 歌词检索需要的曲目信息 */
export interface KugouLyricQuery {
  /** 曲名 */
  name: string;
  /** 文件 hash */
  hash: string;
  /** 时长毫秒 */
  durationMs?: number;
}

/** 搜索结果候选 */
interface KrcsCandidate {
  id: string;
  accesskey: string;
  krctype?: number;
  contenttype?: number;
}

/**
 * 解压 deflate
 * @param input - 压缩字节
 * @returns 解压结果
 */
const inflate = async (input: Uint8Array): Promise<Uint8Array> => {
  if (typeof DecompressionStream !== "function") throw new Error("当前环境不支持 DecompressionStream");
  const bytes = input.buffer.slice(input.byteOffset, input.byteOffset + input.byteLength);
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("deflate"));
  return new Uint8Array(await new Response(stream).arrayBuffer());
};

/**
 * 解 KRC
 * @param content - 服务端返回的 base64 内容
 * @returns KRC 文本
 */
const decryptKrc = async (content: string): Promise<string> => {
  const packed = base64ToBytes(content);
  if (packed.length < 5) throw new Error("KRC 内容过短");
  const xored = packed.slice(4);
  for (let i = 0; i < xored.length; i += 1) xored[i] ^= KRC_KEY[i % KRC_KEY.length];
  return new TextDecoder("utf-8", { fatal: true }).decode(await inflate(xored));
};

/**
 * 按曲目信息取酷狗歌词
 * @param query - 曲名、hash 与时长
 * @returns 歌词文本与格式，未命中为 null
 */
export const fetchKugouLyric = async (
  query: KugouLyricQuery,
): Promise<{ format: "krc" | "lrc"; content: string } | null> => {
  const keyword = String(query.name ?? "").trim();
  if (!keyword) return null;
  const search = new URL("https://krcs.kugou.com/search");
  search.search = new URLSearchParams({
    ver: "1",
    man: "yes",
    client: "pc",
    lrctxt: "1",
    keyword,
    hash: String(query.hash ?? ""),
    timelength: String(Math.round((query.durationMs ?? 0) / 1000)),
  }).toString();
  const found = await httpJson<{ candidates?: KrcsCandidate[] }>({
    url: search.href,
    headers: KG_LYRIC_HEADERS,
    timeoutMs: 8000,
  });
  const item = (found.candidates ?? [])[0];
  if (!item) return null;
  const format: "krc" | "lrc" = item.krctype === 1 && item.contenttype !== 1 ? "krc" : "lrc";

  const download = new URL("https://lyrics.kugou.com/download");
  download.search = new URLSearchParams({
    ver: "1",
    client: "pc",
    charset: "utf8",
    id: item.id,
    accesskey: item.accesskey,
    fmt: format,
  }).toString();
  const payload = await httpJson<{ fmt?: "krc" | "lrc"; content?: string }>({
    url: download.href,
    headers: KG_LYRIC_HEADERS,
    timeoutMs: 8000,
  });
  const content = payload.content;
  if (!content) return null;
  if (format === "lrc") {
    return { format, content: new TextDecoder().decode(base64ToBytes(content)) };
  }
  return { format, content: await decryptKrc(content) };
};
