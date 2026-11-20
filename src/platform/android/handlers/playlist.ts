import { registerHandler } from "../electron-shim";

/** 歌单存储键 */
const STORE_KEY = "splayer.android.playlists";

/** 单条歌单的持久化结构 */
interface StoredPlaylist {
  summary: {
    id: string;
    type: "local";
    title: string;
    description?: string;
    cover?: string;
    trackCount: number;
    createTime: number;
    updateTime: number;
  };
  trackIds: string[];
}

/** 读取全部歌单 */
const readAll = (): StoredPlaylist[] => {
  try {
    const raw = localStorage.getItem(STORE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw) as StoredPlaylist[];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
};

/** 写回全部歌单 */
const writeAll = (list: StoredPlaylist[]): void => {
  localStorage.setItem(STORE_KEY, JSON.stringify(list));
};

/** 生成歌单 id */
const makeId = (): string => `pl_${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;

/**
 * 注册本地歌单 channel，数据落在 localStorage，与桌面端 sqlite 行为对齐
 */
export const registerPlaylistHandlers = (): void => {
  registerHandler("playlist:list", () => readAll().map((item) => item.summary));

  registerHandler("playlist:get", async (id: string) => {
    const entry = readAll().find((item) => item.summary.id === id);
    if (!entry) return null;
    const res = (await (
      window as unknown as {
        api: { library: { getTracksByIds: (ids: string[]) => Promise<unknown> } };
      }
    ).api.library.getTracksByIds(entry.trackIds)) as { success?: boolean; data?: unknown[] };
    const tracks = res?.success && Array.isArray(res.data) ? res.data : [];
    return { ...entry.summary, tracks };
  });

  registerHandler("playlist:create", (input: { title: string; description?: string }) => {
    const now = Date.now();
    const summary = {
      id: makeId(),
      type: "local" as const,
      title: input?.title ?? "未命名歌单",
      description: input?.description,
      trackCount: 0,
      createTime: now,
      updateTime: now,
    };
    const list = readAll();
    list.unshift({ summary, trackIds: [] });
    writeAll(list);
    return summary;
  });

  registerHandler(
    "playlist:update",
    (id: string, input: { title?: string; description?: string; cover?: string }) => {
      const list = readAll();
      const entry = list.find((item) => item.summary.id === id);
      if (!entry) return null;
      if (input?.title !== undefined) entry.summary.title = input.title;
      if (input?.description !== undefined) entry.summary.description = input.description;
      if (input?.cover !== undefined) entry.summary.cover = input.cover;
      entry.summary.updateTime = Date.now();
      writeAll(list);
      return entry.summary;
    },
  );

  registerHandler("playlist:remove", (id: string) => {
    writeAll(readAll().filter((item) => item.summary.id !== id));
  });

  registerHandler("playlist:addTracks", (id: string, trackIds: string[]) => {
    const list = readAll();
    const entry = list.find((item) => item.summary.id === id);
    if (!entry || !Array.isArray(trackIds)) return 0;
    const existing = new Set(entry.trackIds);
    const fresh = trackIds.filter((tid) => tid && !existing.has(tid));
    if (fresh.length === 0) return 0;
    entry.trackIds.push(...fresh);
    entry.summary.trackCount = entry.trackIds.length;
    entry.summary.updateTime = Date.now();
    writeAll(list);
    return fresh.length;
  });

  registerHandler("playlist:removeTracks", (id: string, trackIds: string[]) => {
    const list = readAll();
    const entry = list.find((item) => item.summary.id === id);
    if (!entry || !Array.isArray(trackIds)) return 0;
    const drop = new Set(trackIds);
    const before = entry.trackIds.length;
    entry.trackIds = entry.trackIds.filter((tid) => !drop.has(tid));
    const removed = before - entry.trackIds.length;
    if (removed === 0) return 0;
    entry.summary.trackCount = entry.trackIds.length;
    entry.summary.updateTime = Date.now();
    writeAll(list);
    return removed;
  });

  registerHandler("playlist:clear", () => {
    writeAll([]);
  });

  registerHandler(
    "playlist:importLegacy",
    (
      records: {
        id: string;
        title: string;
        description?: string;
        cover?: string;
        trackIds: string[];
        createTime?: number;
        updateTime?: number;
      }[],
    ) => {
      if (!Array.isArray(records) || records.length === 0) return;
      const list = readAll();
      const existing = new Set(list.map((item) => item.summary.id));
      for (const record of records) {
        if (!record?.id || existing.has(record.id)) continue;
        list.push({
          summary: {
            id: record.id,
            type: "local",
            title: record.title ?? "未命名歌单",
            description: record.description,
            cover: record.cover,
            trackCount: record.trackIds?.length ?? 0,
            createTime: record.createTime ?? Date.now(),
            updateTime: record.updateTime ?? Date.now(),
          },
          trackIds: record.trackIds ?? [],
        });
      }
      writeAll(list);
    },
  );
};
