/**
 * 网易云评论取数
 *
 * 本地曲目没有网易 id，先用标题与歌手搜索候选，再按同名 / 包含 / 歌手 / 时长打分挑最像的一条，
 * 拿到 id 后按 hot 与 new 分别走热门评论与全部评论接口
 */

import type {
  MusicCommentItem,
  MusicCommentPage,
  MusicCommentQuery,
  MusicCommentResponse,
} from "@shared/types/comment";
import type { Track } from "@shared/types/player";
import { neteaseCall } from "../apis/netease";

/** 搜索候选条数 */
const SEARCH_LIMIT = 20;
/** 缓存有效期 */
const CACHE_TTL_MS = 5 * 60 * 1000;
/** 缓存条目上限，避免长会话堆积整页评论 */
const CACHE_MAX = 20;
/** 时长容差（毫秒） */
const DURATION_TOLERANCE = 5000;

/** 打分权重：同名 > 包含 > 歌手 > 时长，同名单项要压过其余三项之和 */
const SCORE_TITLE_EXACT = 200;
const SCORE_TITLE_CONTAINS = 100;
const SCORE_ARTIST_EXACT = 40;
const SCORE_ARTIST_CONTAINS = 20;
const SCORE_DURATION = 25;
/** 歌手完全无交集的惩罚，避免翻唱顶掉原唱 */
const PENALTY_ARTIST_MISS = 60;

/** 缓存项 */
interface CacheEntry {
  /** 过期时间戳 */
  expiresAt: number;
  /** 已转换的评论分页 */
  page: MusicCommentPage;
}

/** 评论分页缓存，key 为 id|type|page */
const cache = new Map<string, CacheEntry>();

/** 网易评论原始项 */
interface NeteaseComment {
  commentId?: string | number;
  beRepliedCommentId?: string | number;
  content?: string;
  time?: number;
  likedCount?: number;
  ipLocation?: { location?: string };
  replyCount?: number;
  user?: { userId?: string | number; nickname?: string; avatarUrl?: string };
  beReplied?: NeteaseComment[];
}

/** 网易评论响应体，热门与全部评论字段分开 */
interface NeteaseCommentBody {
  code?: number;
  total?: number;
  hotComments?: NeteaseComment[];
  comments?: NeteaseComment[];
  data?: { totalCount?: number; comments?: NeteaseComment[] };
}

/** 搜索结果里的歌曲 */
interface NeteaseSearchSong {
  id?: string | number;
  name?: string;
  artists?: { name?: string }[];
  /** 时长（毫秒） */
  duration?: number;
}

/**
 * 字符串归一化，去掉大小写、标点与空白
 * @param text - 原始文本
 * @returns 归一化文本
 */
const normalize = (text: string | undefined | null): string =>
  (text ?? "").toLowerCase().replace(/[\s\-_'"`~!?？！.。、&;，,/|()·・（）[\]【】]+/g, "");

/**
 * 转成字符串 id，兼容接口的 number 与 string
 * @param value - 原始 id
 * @returns 字符串 id，缺失时为空串
 */
const toId = (value: unknown): string => {
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "bigint") return String(value);
  return "";
};

/**
 * 取错误文本，网络与服务端异常统一转成字符串
 * @param error - 捕获到的异常
 * @returns 错误文本
 */
const toMessage = (error: unknown): string =>
  error instanceof Error ? error.message : String(error);

/**
 * 候选歌曲与曲目的相似度打分
 * @param song - 搜索候选
 * @param track - 目标曲目
 * @returns 分数，标题既不同名也不包含时返回 -1 表示淘汰
 */
const scoreCandidate = (song: NeteaseSearchSong, track: Track): number => {
  const title = normalize(track.title);
  const name = normalize(song.name);
  if (!name || !title) return -1;

  let score = 0;
  if (name === title) score += SCORE_TITLE_EXACT;
  else if (name.includes(title) || title.includes(name)) score += SCORE_TITLE_CONTAINS;
  else return -1;

  const wanted = track.artists.map((artist) => normalize(artist.name)).filter(Boolean);
  const offered = (song.artists ?? []).map((artist) => normalize(artist.name)).filter(Boolean);
  const artistExact = wanted.some((item) => offered.includes(item));
  const artistContains = wanted.some((item) =>
    offered.some(
      (candidate) => item.length >= 2 && (candidate.includes(item) || item.includes(candidate)),
    ),
  );
  if (artistExact) score += SCORE_ARTIST_EXACT;
  else if (artistContains) score += SCORE_ARTIST_CONTAINS;
  else if (wanted.length > 0 && offered.length > 0) score -= PENALTY_ARTIST_MISS;

  if (
    typeof song.duration === "number" &&
    typeof track.duration === "number" &&
    Math.abs(song.duration - track.duration) < DURATION_TOLERANCE
  ) {
    score += SCORE_DURATION;
  }
  return score;
};

/**
 * 解析网易歌曲 id
 *
 * 平台曲目直接用自身 id；本地等其它来源按元数据搜索后打分取最高
 * @param track - 目标曲目
 * @returns 网易歌曲 id，搜索无匹配时为 null
 */
export const resolveNeteaseId = async (track: Track): Promise<string | null> => {
  const id = String(track?.id ?? "");
  if (track?.source === "netease" && /^\d+$/.test(id)) return id;

  const keyword = [track?.title ?? "", track.artists.map((artist) => artist.name).join(" ")]
    .map((part) => part.trim())
    .filter(Boolean)
    .join(" ");
  if (!keyword) return null;

  const res = await neteaseCall("search", { keywords: keyword, type: 1, limit: SEARCH_LIMIT });
  const body = res.body as { code?: number; result?: { songs?: NeteaseSearchSong[] } } | undefined;
  if (!res.ok || body?.code !== 200) return null;

  let bestId: string | null = null;
  let bestScore = 0;
  for (const song of body.result?.songs ?? []) {
    const score = scoreCandidate(song, track);
    const candidateId = toId(song.id);
    if (score > bestScore && /^\d+$/.test(candidateId)) {
      bestScore = score;
      bestId = candidateId;
    }
  }
  return bestId;
};

/**
 * 转换单条评论，楼中楼递归处理
 * @param raw - 原始评论
 * @returns 应用层评论项，缺 id 或正文时返回 null
 */
const normalizeItem = (raw: NeteaseComment): MusicCommentItem | null => {
  const id = toId(raw.commentId ?? raw.beRepliedCommentId);
  const text = (raw.content ?? "").trim();
  if (!id || !text) return null;

  const item: MusicCommentItem = {
    id,
    userName: (raw.user?.nickname ?? "").trim(),
    text,
  };
  const userId = toId(raw.user?.userId);
  if (userId) item.userId = userId;
  const avatar = raw.user?.avatarUrl?.trim();
  if (avatar) item.avatar = avatar;
  if (typeof raw.time === "number") item.time = raw.time;
  const location = raw.ipLocation?.location?.trim();
  if (location) item.location = location;
  if (typeof raw.likedCount === "number") item.likedCount = raw.likedCount;
  if (typeof raw.replyCount === "number") item.replyTotal = raw.replyCount;

  const reply = (raw.beReplied ?? [])
    .map((child) => normalizeItem(child))
    .filter((child): child is MusicCommentItem => child !== null);
  if (reply.length > 0) item.reply = reply;
  return item;
};

/**
 * 转换评论分页
 * @param body - 接口响应体
 * @param type - 评论标签
 * @param page - 页码
 * @param limit - 每页条数
 * @returns 应用层评论分页
 */
const normalizePage = (
  body: NeteaseCommentBody,
  type: "hot" | "new",
  page: number,
  limit: number,
): MusicCommentPage => {
  const rawList =
    type === "hot"
      ? (body.hotComments ?? body.data?.comments ?? [])
      : (body.comments ?? body.data?.comments ?? []);
  const list = rawList
    .map((item) => normalizeItem(item))
    .filter((item): item is MusicCommentItem => item !== null);
  return {
    list,
    total: body.total ?? body.data?.totalCount ?? list.length,
    page,
    limit,
  };
};

/**
 * 读缓存，过期即删
 * @param key - 缓存 key
 * @returns 命中的分页，未命中为 null
 */
const readCache = (key: string): MusicCommentPage | null => {
  const entry = cache.get(key);
  if (!entry) return null;
  if (entry.expiresAt <= Date.now()) {
    cache.delete(key);
    return null;
  }
  return entry.page;
};

/**
 * 写缓存，超出上限从最早写入的开始淘汰
 * @param key - 缓存 key
 * @param page - 评论分页
 */
const writeCache = (key: string, page: MusicCommentPage): void => {
  cache.delete(key);
  cache.set(key, { expiresAt: Date.now() + CACHE_TTL_MS, page });
  while (cache.size > CACHE_MAX) {
    const oldest = cache.keys().next().value;
    if (oldest === undefined) break;
    cache.delete(oldest);
  }
};

/**
 * 取网易云评论分页
 * @param query - 评论查询参数
 * @returns 统一响应，搜索不到 id 时返回空分页且不报错
 */
export const getMusicComments = async (query: MusicCommentQuery): Promise<MusicCommentResponse> => {
  const page = Number(query?.page) > 0 ? Number(query.page) : 1;
  const limit = Number(query?.limit) > 0 ? Number(query.limit) : 20;
  const type = query?.type === "hot" ? "hot" : "new";

  let id: string | null = null;
  try {
    id = await resolveNeteaseId(query.track);
  } catch (error) {
    return { ok: false, error: toMessage(error) };
  }
  if (!id) return { ok: true, data: { list: [], total: 0, page, limit } };

  const cacheKey = `${id}|${type}|${page}`;
  const cached = readCache(cacheKey);
  if (cached) return { ok: true, data: cached };

  const cursor = Number(query?.cursor ?? 0);
  try {
    const res = await neteaseCall(type === "hot" ? "comment_hot" : "comment_music", {
      id,
      limit,
      offset: (page - 1) * limit,
      ...(type === "hot" ? { beforeTime: Number.isFinite(cursor) ? cursor : 0 } : {}),
      ...(type === "new" && query?.cursor ? { before: cursor } : {}),
    });
    const body = res.body as NeteaseCommentBody | undefined;
    if (!res.ok || !body) return { ok: false, error: res.error ?? "评论加载失败" };
    if (body.code !== undefined && body.code !== 200) {
      return { ok: false, error: `网易云返回 code ${body.code}` };
    }
    const data = normalizePage(body, type, page, limit);
    writeCache(cacheKey, data);
    return { ok: true, data };
  } catch (error) {
    return { ok: false, error: toMessage(error) };
  }
};
