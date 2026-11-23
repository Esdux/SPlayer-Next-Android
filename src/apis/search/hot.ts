import { neteaseCall } from "@/apis/netease";

export interface HotSearchItem {
  /** 关键词 */
  keyword: string;
  /** 描述/补充 */
  content?: string;
  /** 图标 url */
  iconUrl?: string;
  /** 热度 */
  score?: number;
}

interface NeteaseHotDetailResp {
  data?: Array<{
    searchWord: string;
    score?: number;
    content?: string;
    iconUrl?: string;
    iconType?: number;
  }>;
}

const CACHE_TTL = 10 * 60 * 1000;

let cache: { items: HotSearchItem[]; at: number } | null = null;

/**
 * 取内置在线源热搜（按 TTL 缓存）
 */
export const getHotSearches = async (): Promise<HotSearchItem[]> => {
  const now = Date.now();
  if (cache && now - cache.at < CACHE_TTL) return cache.items;

  const res = await neteaseCall<
    NeteaseHotDetailResp & { result?: { hots?: Array<{ first?: string; iconType?: number; second?: number }> } }
  >("search_hot_detail");
  // 直连 /api/search/hot 返回 result.hots，其它实现返回 data
  const rows =
    res.data ??
    (res.result?.hots ?? []).map((row) => ({
      searchWord: row.first ?? "",
      iconType: row.iconType,
      // second 是排序位不是热度，避免面板显示无意义的「1」
      score: undefined,
    }));
  const items: HotSearchItem[] = rows
    .filter((row) => row.searchWord)
    .map((row) => ({
      keyword: row.searchWord,
      content: (row as { content?: string }).content,
      iconUrl: (row as { iconUrl?: string }).iconUrl,
      score: row.score,
    }));

  cache = { items, at: now };
  return items;
};
