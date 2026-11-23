/**
 * 词幕（Lyricon）推送
 *
 * 把当前歌曲与结构化歌词交给原生桥，由词幕渲染桌面/悬浮歌词；未安装词幕时静默失败
 */

import { useMediaStore } from "@/stores/media";
import { useSettingsStore } from "@/stores/settings";

/** 词幕桥的可用方法 */
interface LyriconApi {
  setSong?: (payload: unknown) => Promise<unknown>;
  setPlaying?: (playing: boolean) => Promise<unknown>;
}

/**
 * 取词幕桥
 * @returns 桥对象；不可用时为 undefined
 */
const lyriconApi = (): LyriconApi | undefined =>
  (window as unknown as { api?: { lyricon?: LyriconApi } }).api?.lyricon;

/**
 * 把当前歌曲与歌词推给词幕
 */
export const syncLyricon = (): void => {
  const api = lyriconApi();
  if (!api?.setSong) return;
  const media = useMediaStore();
  const settings = useSettingsStore();
  const track = media.track;
  if (!track) {
    void api.setSong(null);
    return;
  }
  const lines = (media.parsedLyric ?? [])
    .filter((line) => !line.isBG)
    .map((line) => ({
      begin: line.startTime ?? 0,
      end: line.endTime ?? 0,
      text: (line.words ?? []).map((word) => word.word).join(""),
      translation: line.translatedLyric ?? "",
      secondary: "",
      words: (line.words ?? []).map((word) => ({
        text: word.word,
        begin: word.startTime,
        end: word.endTime,
      })),
    }));
  void api.setSong({
    id: String(track.id ?? ""),
    name: track.title ?? "",
    artist: (track.artists ?? []).map((artist) => artist.name).filter(Boolean).join(" / "),
    duration: track.duration ?? 0,
    lyrics: lines,
    showTranslation: settings.lyric.showTranslation,
    showRoma: settings.lyric.showRoma,
  });
};

/**
 * 订阅曲目与歌词变化，自动同步给词幕
 * @returns 取消订阅函数
 */
export const watchLyricon = (): (() => void) => {
  const media = useMediaStore();
  return watch(
    [() => media.track?.id, () => media.parsedLyric],
    () => syncLyricon(),
    { flush: "post" },
  );
};
