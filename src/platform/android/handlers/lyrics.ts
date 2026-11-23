/**
 * 歌词匹配通道
 *
 * 上游实现在主进程，走 lyric_new（/api/song/lyric/v1）取 yrc 逐字歌词、
 * tlyric 翻译与 romalrc 罗马音；这里把同一条链路搬到渲染端。
 */

import { neteaseCall } from "../apis/netease";
import { registerHandler } from "../electron-shim";
import { detectFormat } from "lyric-kit";
import { httpRequest } from "../net/http";
import { kugouCall } from "../apis/kugou";
import { fetchKugouLyric } from "../apis/kugou-lyric";
import { qqmusicCall } from "../apis/qqmusic";
import { decryptQrc } from "../apis/qqmusic-qrc";

/** 主歌词：yrc 优先，其次 lrc */
const pickMain = (
  yrc?: string,
  lrc?: string,
): { content: string; format: "yrc" | "lrc" } | undefined => {
  const yrcContent = yrc?.trim();
  if (yrcContent) return { content: yrcContent, format: "yrc" };
  const lrcContent = lrc?.trim();
  if (lrcContent) return { content: lrcContent, format: "lrc" };
  return undefined;
};

/** 翻译 / 罗马音：ytlrc 时间戳更贴 yrc 行边界，优先选用 */
const pickSub = (
  paired?: string,
  plain?: string,
): { content: string; format: "lrc" } | undefined => {
  const preferred = paired?.trim();
  if (preferred) return { content: preferred, format: "lrc" };
  const fallback = plain?.trim();
  if (fallback) return { content: fallback, format: "lrc" };
  return undefined;
};

/**
 * 按网易歌曲 id 取歌词
 * @param id - 歌曲 id
 * @returns 匹配结果，取不到时为 null
 */
const resolveNeteaseLyric = async (id: string): Promise<Record<string, unknown> | null> => {
  if (!id) return null;
  const res = (await neteaseCall("lyric_new", { id })) as {
    ok?: boolean;
    body?: {
      code?: number;
      yrc?: { lyric?: string };
      lrc?: { lyric?: string };
      ytlrc?: { lyric?: string };
      tlyric?: { lyric?: string };
      yromalrc?: { lyric?: string };
      romalrc?: { lyric?: string };
    };
  };
  const body = res?.body;
  if (!res?.ok || body?.code !== 200) return null;
  const main = pickMain(body.yrc?.lyric, body.lrc?.lyric);
  if (!main) return null;
  const trans = pickSub(body.ytlrc?.lyric, body.tlyric?.lyric);
  const roma = pickSub(body.yromalrc?.lyric, body.romalrc?.lyric);
  return {
    platform: "netease",
    format: main.format,
    content: main.content,
    translation: trans?.content,
    translationFormat: trans?.format,
    romaji: roma?.content,
    romajiFormat: roma?.format,
  };
};

/**
 * 按元数据模糊搜索网易歌词
 * @param track - 曲目
 * @returns 最佳候选 id
 */
const searchNeteaseByTrack = async (track: {
  title?: string;
  artists?: { name?: string }[];
}): Promise<string | null> => {
  const artist = track?.artists?.[0]?.name ?? "";
  const keyword = `${track?.title ?? ""} ${artist}`.trim();
  if (!keyword) return null;
  const res = (await neteaseCall("search", { keywords: keyword, type: 1, limit: 5 })) as {
    body?: { result?: { songs?: { id?: number; name?: string }[] } };
  };
  const songs = res?.body?.result?.songs;
  if (!Array.isArray(songs) || songs.length === 0) return null;
  return String(songs[0]?.id ?? "");
};

/**
 * 注册歌词通道
 */
export const registerLyricsHandlers = (): void => {
  registerHandler("lyrics:matchById", async (platform: unknown, id: unknown, track: unknown) => {
    const name = String(platform ?? "");
    console.warn("[lyric] matchById platform=" + name + " id=" + String(id ?? ""));
    if (name === "netease") {
      const text = await resolveNeteaseLyric(String(id ?? ""));
      return { ok: true, data: toMatchResult("netease", text) };
    }
    if (name === "qqmusic") {
      const row = (track ?? {}) as QqRow;
      const lyric = await resolveQqLyric(String(row.extId ?? id ?? ""), row);
      return {
        ok: true,
        data: toMatchResult("qqmusic", lyric?.content ?? null, {
          translation: lyric?.translation,
          romaji: lyric?.romaji,
          format: lyric?.format,
        }),
      };
    }
    if (name === "kugou") {
      const row = (track ?? {}) as QqRow;
      // 专辑/歌手/歌单来源的 id 是 audio_id，酷狗歌词接口要的是文件 hash
      const hash = String((row as { hash?: string }).hash || id || "");
      const lyric = await resolveKugouLyric(hash, row);
      return {
        ok: true,
        data: toMatchResult("kugou", lyric?.content ?? null, {
          translation: lyric?.translation,
          romaji: lyric?.romaji,
          format: lyric?.format,
        }),
      };
    }
    return { ok: true, data: null };
  });

  registerHandler("lyrics:matchByQuery", async (platform: unknown, track: unknown) => {
    const name = String(platform ?? "");
    const row = (track ?? {}) as LyricTrackRow;
    console.warn("[lyric] matchByQuery platform=" + name + " title=" + String(row.title ?? ""));
    if (name === "netease") {
      const id = await searchNeteaseByTrack(row);
      const text = id ? await resolveNeteaseLyric(id) : null;
      return { ok: true, data: toMatchResult("netease", text) };
    }
    if (name === "qqmusic") {
      const hit = await searchQqSong(row);
      const lyric = hit ? await resolveQqLyric(hit.numericId, row) : null;
      return {
        ok: true,
        data: toMatchResult("qqmusic", lyric?.content ?? null, {
          translation: lyric?.translation,
          romaji: lyric?.romaji,
          format: lyric?.format,
        }),
      };
    }
    if (name === "kugou") {
      // 没有 hash 时先按「歌名 + 歌手」搜一次拿文件 hash，酷狗歌词接口必须有它
      let hash = String(row.hash ?? "");
      if (!hash) hash = await searchKugouHash(row);
      const lyric = hash ? await resolveKugouLyric(hash, row) : null;
      return {
        ok: true,
        data: toMatchResult("kugou", lyric?.content ?? null, {
          translation: lyric?.translation,
          romaji: lyric?.romaji,
          format: lyric?.format,
        }),
      };
    }
    return { ok: true, data: null };
  });

  // 前端只传 AMLL 条目 id，走 amll 的 id 参数
  registerHandler("lyrics:fetchTTMLById", async (id: unknown) => {
    const lyric = await fetchTtmlByPlatformId("amll", String(id ?? ""));
    return { ok: true, data: lyric };
  });

  registerHandler("lyrics:fetchTTMLOverlay", async (track: unknown, platform: unknown) => {
    // 叠加层与主歌词同源：优先按平台 id 取，取不到再按歌名搜索
    const row = (track ?? {}) as { id?: string; title?: string };
    const byId = await fetchTtmlByPlatformId(String(platform ?? ""), String(row.id ?? ""));
    if (byId) return { ok: true, data: byId };
    return { ok: true, data: await searchTtmlByTrack(row) };
  });

  registerHandler("lyrics:searchTTML", async (track: unknown) => {
    const row = (track ?? {}) as { title?: string; artists?: { name?: string }[] };
    const title = String(row.title ?? "").trim();
    if (!title) return { ok: true, data: [] };
    const data = await amllRequest<{ items?: AmllSong[] }>(
      `search?${new URLSearchParams({ musicName: title, pageSize: "50" }).toString()}`,
    );
    const items = (data?.items ?? []).map((item) => ({
      id: String(item.id),
      title: item.musicNames?.[0] ?? title,
      artists: item.artistNames?.[0] ?? "",
      platform: "amll",
    }));
    return { ok: true, data: items };
  });
};
/**
 * 把歌词文本包装成前端约定的匹配结果
 * @param platform - 来源平台
 * @param content - 歌词原文
 * @param extra - 翻译与罗马音
 * @returns 匹配结果，无内容时为 null
 */
const toMatchResult = (
  platform: string,
  content: string | null,
  extra: {
    translation?: string;
    romaji?: string;
    /** 已知格式（QQ 的 QRC/LRC、酷狗的 KRC/LRC），给了就不再靠内容猜 */
    format?: string;
  } = {},
): LyricMatchResult | null => {
  // 已经包装过的结果直接透传，避免二次包装
  if (content && typeof content === "object") {
    const maybe = content as { content?: unknown; format?: unknown };
    if (typeof maybe.content === "string" && typeof maybe.format === "string") {
      return content as LyricMatchResult;
    }
  }
  // 平台可能直接返回对象形态的歌词（如 {"t":0,"c":[...]}），统一序列化成文本
  const text = typeof content === "string" ? content : content ? JSON.stringify(content) : "";
  if (!text.trim()) {
    // 主歌词为空时前端会升级到 TTML，打日志才能分辨是"没取到"还是"解析失败"
    console.warn("[lyric] empty " + platform + "（主歌词为空，前端会回退 TTML）");
    return null;
  }
  // 下游会直接对 content/translation/romaji 调 trim，必须保证是字符串
  const asText = (value: unknown): string | undefined =>
    typeof value === "string" && value.trim() ? value : undefined;
  // 各平台可能返回 JSON 形态的逐字歌词（{"t":0,"c":[…] }），detectFormat 认不出会误判成 lrc
  const looksJsonLyric = /^\s*\{\s*"t"\s*:/.test(text);
  // 不伪造时间轴：JSON 形态直接按来源格式交给 lyric-kit，解析不了的交给前端判定

  // detectFormat 也分不清 QRC 与 YRC（都是 [start,dur](…)），已知来源格式时以来源为准
  // JSON 形态是 QQ/网易的 QRC，桌面端也是直接标 qrc 交给 lyric-kit 解析
  const fmt = looksJsonLyric
    ? ("qrc" as LyricMatchResult["format"])
    : ((extra.format as LyricMatchResult["format"] | undefined) ?? detectFormat(text));

  const translation = asText(extra.translation);
  const romaji = asText(extra.romaji);
  console.warn(
    "[lyric] wrap " + platform + " fmt=" + String(fmt) + " detected=" + detectFormat(text) +
      " len=" + text.length + " trans=" + (translation ? translation.length : 0) +
      " roma=" + (romaji ? romaji.length : 0) +
      " head=" + text.slice(0, 36).replace(/\n/g, " "),
  );
  return {
    platform: platform as LyricMatchResult["platform"],
    format: fmt,
    content: text,
    translation,
    romaji,
    ...(translation ? { translationFormat: "lrc" as LyricMatchResult["format"] } : {}),
    ...(romaji ? { romajiFormat: "lrc" as LyricMatchResult["format"] } : {}),
  };
};

/** 歌词解析需要的曲目字段 */
interface LyricTrackRow {
  id?: string;
  extId?: string;
  /** 酷狗等平台的文件 hash，歌词接口的主键 */
  hash?: string;
  title?: string;
  duration?: number;
  artists?: { name?: string }[];
  album?: { name?: string };
}

/**
 * UTF-8 安全的 base64，QQ 参数要求
 * @param value - 原文
 * @returns base64
 */
const b64Utf8 = (value: string): string => {
  const bytes = new TextEncoder().encode(value);
  let raw = "";
  for (const byte of bytes) raw += String.fromCharCode(byte);
  return btoa(raw);
};

/**
 * 取 QQ 歌词：QRC 优先，LRC 兜底
 * @param songId - QQ 歌曲 mid
 * @param row - 曲目信息
 * @returns 歌词文本，未命中为 null
 */
/**
 * 取第一个能转成正整数的候选（QQ 的 songID 必须是数字，字母 mid 转出来是 NaN）
 * @param values - 候选值，按优先级排列
 * @returns 数字 id，都没有时返回 0
 */
const pickNumericSongId = (...values: unknown[]): number => {
  for (const value of values) {
    const n = Number(value);
    if (Number.isFinite(n) && n > 0) return n;
  }
  return 0;
};

const resolveQqLyric = async (
  songId: string,
  row: LyricTrackRow,
): Promise<{ content: string; format?: string; translation?: string; romaji?: string } | null> => {
  if (!songId) return null;
  // 字段名与取值必须对齐桌面实现：songID 全大写、不带 songmid、补 trans/type
  const param = {
    albumName: b64Utf8(row.album?.name ?? ""),
    crypt: 1,
    ct: 19,
    cv: 2111,
    interval: Math.floor((row.duration ?? 0) / 1000),
    lrc_t: 0,
    qrc: 1,
    qrc_t: 0,
    roma: 1,
    roma_t: 0,
    singerName: b64Utf8((row.artists ?? []).map((a) => a.name ?? "").join(" / ") || ""),
    songID: pickNumericSongId(songId, row.extId, row.id),
    songName: b64Utf8(row.title ?? ""),
    trans: 1,
    trans_t: 0,
    type: 0,
  };
  /** 一次取词的结果：主词 + 翻译 + 罗马音，都带格式 */
  type TqPayload = {
    content: string;
    format: "qrc" | "lrc";
    translation?: string;
    romaji?: string;
  };
  const pick = async (payload: Record<string, unknown>): Promise<TqPayload | null> => {
    const res = (await qqmusicCall("musicu", {
      module: "music.musichallSong.PlayLyricInfo",
      method: "GetPlayLyricInfo",
      param: payload,
    })) as { ok?: boolean; body?: { req_1?: { data?: Record<string, string> } } };
    // musicu 的响应键会回显请求键：通用分支发的是 request，读 req_1 恒为 undefined
    const envelope = res?.body as { request?: { data?: unknown }; req_1?: { data?: unknown } } | undefined;
    const node = (envelope?.request ?? envelope?.req_1) as { data?: Record<string, string> } | undefined;
    // musicu 的 data 可能是被字符串化的 JSON
    const data = (typeof node?.data === "string" ? JSON.parse(node.data) : node?.data) as Record<string, string> | undefined;
    if (!data) {
      console.warn("[qq] no data");
      return null;
    }
    console.warn("[qq] fields=" + Object.keys(data).join(",").slice(0, 100) + " qrc_t=" + String(data.qrc_t));
    /** 单个字段解密，失败返回 undefined */
    const tryDecrypt = async (hex: unknown): Promise<string | undefined> => {
      if (!hex || typeof hex !== "string") return undefined;
      try {
        const text = await decryptQrc(hex);
        return text.trim() ? text : undefined;
      } catch (err) {
        console.warn("[qq] decrypt fail " + String(err).slice(0, 60) + " hexlen=" + hex.length);
        return undefined;
      }
    };
    // qrc_t === 0 表示主词是 LRC，否则是逐字 QRC（桌面同判据）
    const format: "qrc" | "lrc" = Number(data.qrc_t ?? 0) === 0 ? "lrc" : "qrc";
    // 桌面实现只解 lyric：qrc_t 决定它进 lrc 还是 qrc；qrc 字段是同内容的不同形态，
    // 优先它会把 JSON 形态当逐字歌词交给 lyric-kit（只认 <QrcInfos>），表现为选中优先 QM 却无词
    const lyricText = await tryDecrypt(data.lyric);
    const qrcText = await tryDecrypt(data.qrc);
    const main = lyricText ?? qrcText;
    if (!main) {
      const plain = data.lrc ?? data.lyric_lrc;
      return plain && plain.trim() ? { content: plain, format } : null;
    }
    const translation = await tryDecrypt(data.trans);
    const romaji = await tryDecrypt(data.roma);
    console.warn(
      "[qq] decrypt ok fmt=" + format + " len=" + main.length + " head=" + main.slice(0, 30),
    );
    return { content: main, format, translation, romaji };
  };
  const main = await pick(param);
  if (main) return main;
  // 再要一次纯 LRC（qrc:0 时服务端回 LRC）
  const lrcOnly = await pick({ ...param, qrc: 0, qrc_t: 0 });
  if (lrcOnly) return lrcOnly;
  // 服务端加密已变更、解不出时按歌名回退：酷狗要先有文件 hash，在线曲拿不到，
  // 所以走网易的按歌名搜索作为兜底，至少保证画面里有词
  const kg = await fetchKugouLyric({
    name: row.title ?? "",
    hash: "",
    durationMs: row.duration ?? 0,
  });
  if (kg?.content) return { content: kg.content, format: kg.format };
  const ncmId = await searchNeteaseByTrack(row);
  return ncmId ? await resolveNeteaseLyric(ncmId) : null;
};

/**
 * 按歌名与歌手在 QQ 音乐里搜出 songmid
 * @param row - 曲目信息
 * @returns songmid，未命中为 null
 */
const searchQqSong = async (
  row: LyricTrackRow,
): Promise<{ numericId: string; mid: string } | null> => {
  const title = String(row.title ?? "").trim();
  const artist = String((row.artists ?? [])[0]?.name ?? "").trim();
  if (!title) return null;
  /**
   * 单次搜索，返回命中列表
   *
   * 这里复用已修好的 search 通道（Lite + zzc 签名），不再自己发 Desktop：
   * Desktop + search_type=0 实测恒被判 2001
   */
  /** QQ 搜索结果里用到的字段 */
  type QQCandidate = {
    mid?: string;
    extId?: string;
    id?: string | number;
    name?: string;
    title?: string;
    artist?: string;
    album?: string;
    duration?: number;
  };
  const search = async (keyword: string): Promise<QQCandidate[]> => {
    const res = (await qqmusicCall("search", {
      keywords: keyword,
      type: 0,
      limit: 10,
      page: 1,
    })) as {
      songs?: QQCandidate[];
      ok?: boolean;
      data?: { songs?: QQCandidate[] };
      body?: { songs?: QQCandidate[] };
    };
    // qqmusicCall("search") 直接返回 { songs, total }；经 dispatch 包装时才多一层 data
    const list = res?.songs ?? res?.data?.songs ?? res?.body?.songs ?? [];
    console.warn("[qq] search kw=" + keyword + " n=" + list.length);
    return list;
  };

  /** 去掉空白、标点与符号后的小写文本，用于歌名/歌手归一化比对 */
  const normalize = (value: unknown): string =>
    String(value ?? "")
      .toLowerCase()
      .replace(/[\s\p{P}\p{S}]/gu, "");

  /** 双向包含 */
  const bothContains = (a: string, b: string): boolean =>
    a.length > 0 && b.length > 0 && (a.includes(b) || b.includes(a));

  /**
   * 从候选里挑最匹配的一首（与桌面 pickBestCandidate 同规则）
   * 硬性条件：歌名全等或双向包含且短串占比不低于 0.34、时长差不超过 20s、有歌手时必须命中歌手
   * 打分：歌名全等 10 / 包含 4，歌手全等 5 / 包含 2，专辑 2，时长 ±5s 3
   */
  const pickBestCandidate = (candidates: QQCandidate[], track: LyricTrackRow): QQCandidate | null => {
    const trackName = normalize(track.title);
    const trackArtists = (track.artists ?? []).map((item) => normalize(item?.name)).filter(Boolean);
    const trackAlbum = normalize(track.album?.name);
    const trackDuration = Number(track.duration ?? 0);
    let best: QQCandidate | null = null;
    let bestScore = 0;
    for (const candidate of candidates) {
      const candName = normalize(candidate.name ?? candidate.title);
      const candAlbum = normalize(candidate.album);
      const nameExact = candName.length > 0 && candName === trackName;
      if (!nameExact) {
        if (!bothContains(candName, trackName)) continue;
        const longer = Math.max(candName.length, trackName.length);
        if (Math.min(candName.length, trackName.length) / longer < 0.34) continue;
      }
      const candDuration = Number(candidate.duration ?? 0);
      const hasDuration = candDuration > 0 && trackDuration > 0;
      if (hasDuration && Math.abs(candDuration - trackDuration) > 20_000) continue;
      const artistExact = trackArtists.includes(normalize(candidate.artist));
      const artistContains =
        !artistExact && trackArtists.some((item) => bothContains(normalize(candidate.artist), item));
      if (trackArtists.length > 0 && !artistExact && !artistContains) continue;
      const durationClose = hasDuration && Math.abs(candDuration - trackDuration) <= 5_000;
      if (!nameExact && !artistExact && !artistContains && !durationClose) continue;
      let score = nameExact ? 10 : 4;
      if (artistExact) score += 5;
      else if (artistContains) score += 2;
      if (trackAlbum && candAlbum === trackAlbum) score += 2;
      if (durationClose) score += 3;
      if (score > bestScore) {
        bestScore = score;
        best = candidate;
      }
    }
    return best;
  };

  // 先按歌名搜，没命中再退到歌手、歌名+歌手，与桌面 searchCandidates 的回退一致
  const rounds = [title, artist, artist && title ? `${title} ${artist}` : ""].filter(Boolean);
  let hit: QQCandidate | null = null;
  let searched = 0;
  for (const keyword of rounds) {
    const list = await search(keyword);
    searched += list.length;
    if (list.length > 0) {
      hit = pickBestCandidate(list, row);
      if (hit) break;
    }
  }
  console.warn(
    "[qq] search title=" + title + " artist=" + artist + " hits=" + searched +
      " pick=" + JSON.stringify(hit ?? null),
  );
  // 桌面取词用的是搜索命中的数字 id（song.id），mid 只进 TTML；这里把数字 id 带回去
  const numericId = String(
    hit?.extId ?? (typeof hit?.id === "number" ? hit.id : ""),
  ).trim();
  if (!numericId || Number(numericId) <= 0) {
    console.warn("[qq] search hit without numeric id: " + JSON.stringify(hit ?? null).slice(0, 120));
    return null;
  }
  return { numericId, mid: String(hit?.mid ?? "") };
};

/**
 * 取酷狗歌词
 * @param hash - 文件 hash
 * @param row - 曲目信息
 * @returns 歌词文本，未命中为 null
 */
const resolveKugouLyric = async (
  hash: string,
  row: LyricTrackRow,
): Promise<{ content: string; format?: string; translation?: string; romaji?: string } | null> => {
  const res = (await kugouCall("lyric", {
    name: row.title ?? "",
    hash,
    durationMs: row.duration ?? 0,
  })) as {
    ok?: boolean;
    body?: { format?: string; content?: string; translation?: string; romaji?: string } | null;
  };
  console.warn(
    "[kg] lyric req hash=" + hash.slice(0, 8) + " ok=" + String(res?.ok) + " body=" +
      (res?.body ? Object.keys(res.body).join(",").slice(0, 60) : "nil") +
      " err=" + String((res as { error?: string })?.error ?? "").slice(0, 40),
  );
  const content = res?.body?.content;
  if (!content || !content.trim()) return null;
  console.warn("[kg] lyric fmt=" + String(res?.body?.format) + " len=" + content.length);
  return {
    content,
    format: res?.body?.format,
    translation: res?.body?.translation,
    romaji: res?.body?.romaji,
  };
};

/**
 * 按曲目信息搜一次酷狗，取文件 hash
 * @param row - 曲目信息
 * @returns 命中的 hash，未命中为空串
 */
const searchKugouHash = async (row: LyricTrackRow): Promise<string> => {
  const keyword = [row.title ?? "", (row.artists ?? [])[0]?.name ?? ""].join(" ").trim();
  if (!keyword) return "";
  const res = (await kugouCall("search", { keywords: keyword, type: 0, limit: 5, page: 1 })) as {
    songs?: { hash?: string }[];
    ok?: boolean;
    data?: { songs?: { hash?: string }[] };
    body?: { songs?: { hash?: string }[] };
  };
  const list = res?.songs ?? res?.data?.songs ?? res?.body?.songs ?? [];
  const hash = String(list[0]?.hash ?? "");
  console.warn("[kg] fuzzy search kw=" + keyword + " n=" + list.length + " hash=" + hash.slice(0, 8));
  return hash;
};

/** AMLL 官方 TTML 库 */
const AMLL_API = "https://api.amll.dev/v1/lyrics";
/** 上游同款 UA，AMLL 对空 UA 会限流 */
const AMLL_UA = "SPlayer-Next/1.2.1 (https://github.com/SPlayer-Dev/SPlayer-Next)";

/**
 * 请求 AMLL 接口
 * @param path - 相对路径与查询串
 * @returns 解析后的 data，失败为 null
 */
const amllRequest = async <T>(path: string): Promise<T | null> => {
  try {
    const res = await httpRequest({
      url: `${AMLL_API}/${path}`,
      method: "GET",
      headers: { "User-Agent": AMLL_UA },
      timeoutMs: 8000,
    });
    if (res.status !== 200) return null;
    const json = JSON.parse(res.text) as { data?: T };
    return (json?.data ?? null) as T | null;
  } catch {
    return null;
  }
};

/**
 * 按平台 id 取 TTML
 * @param platform - netease / qqmusic
 * @param id - 平台歌曲 id
 * @returns TTML 文本
 */
const fetchTtmlByPlatformId = async (platform: string, id: string): Promise<string | null> => {
  if (!id) return null;
  // amll 库自身用 id 参数，netease / qqmusic 用各自的平台 id
  const query =
    platform === "netease" ? "ncmMusicId" : platform === "qqmusic" ? "qqMusicId" : "id";
  const data = await amllRequest<{ lyrics?: unknown }>(
    `get?${query}=${encodeURIComponent(id)}`,
  );
  // 上游可能返回字符串，也可能是数组或对象，只接受字符串
  const lyrics = typeof data?.lyrics === "string" ? data.lyrics.trim() : "";
  return lyrics ? lyrics : null;
};

/** AMLL 搜索结果条目 */
interface AmllSong {
  id: number;
  musicNames: string[];
  artistNames: string[];
}

/**
 * 按歌曲元数据在 AMLL 搜索 TTML
 * @param track - 曲目
 * @returns TTML 文本
 */
const searchTtmlByTrack = async (track: {
  title?: string;
  artists?: { name?: string }[];
}): Promise<string | null> => {
  const title = String(track?.title ?? "").trim();
  if (!title) return null;
  const data = await amllRequest<{ items?: AmllSong[] }>(
    `search?${new URLSearchParams({ musicName: title, pageSize: "50" }).toString()}`,
  );
  const items = data?.items ?? [];
  if (items.length === 0) return null;
  const norm = (value: string): string => value.toLowerCase().replace(/\s+/g, "");
  const wanted = norm(title);
  const artist = norm(String(track?.artists?.[0]?.name ?? ""));
  const exact = items.find((item) => item.musicNames.some((name) => norm(name) === wanted));
  const withArtist = items.find((item) =>
    item.artistNames.some((name) => artist && norm(name).includes(artist)),
  );
  const picked = exact ?? withArtist ?? items[0];
  return fetchTtmlByPlatformId("amll", String(picked.id));
};
