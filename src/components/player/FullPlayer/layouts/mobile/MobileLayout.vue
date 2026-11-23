<script setup lang="ts">
/**
 * 手机竖屏双页布局
 *
 * 第 1 页：封面 + 曲目信息 + 进度 + 控制排 + 工具排
 * 第 2 页：整屏歌词（上游 Lyrics 引擎原样渲染）
 * 两页之间是跟手的整页横移，不是覆盖展开
 */

import { useI18n } from "vue-i18n";
import { useStatusStore } from "@/stores/status";
import { useSettingsStore } from "@/stores/settings";
import { useMediaStore } from "@/stores/media";
import { useLibraryStore } from "@/stores/library";
import { useTimeFormat } from "@/composables/useTimeFormat";
import { useProgressLyric } from "@/composables/useProgressLyric";
import { useFavorite } from "@/composables/useFavorite";
import { usePlaylistPicker } from "@/composables/usePlaylistPicker";
import { useSettingsDialog } from "@/settings/useSettingsDialog";
import { useDownload } from "@/composables/useDownload";
import { setPlayerExpanded } from "@/services/playerTransition";

const { t } = useI18n();
import * as player from "@/core/player";
import IconLucideTextQuote from "~icons/lucide/text-quote";
import IconLucideSettings2 from "~icons/lucide/settings-2";
import IconLucideLayoutTemplate from "~icons/lucide/layout-template";
import IconLucideChevronDown from "~icons/lucide/chevron-down";
import IconMaterialSymbolsFavoriteRounded from "~icons/material-symbols/favorite-rounded";
import IconMaterialSymbolsFavoriteOutlineRounded from "~icons/material-symbols/favorite-outline-rounded";

const status = useStatusStore();
const media = useMediaStore();
const settingsStore = useSettingsStore();
const { isPlaying, isLoading, position, duration, shuffleMode, repeatMode } = storeToRefs(status);
const { timeDisplay } = useTimeFormat();
const { snapToNearestLyric } = useProgressLyric();
const { open: pickerOpen, tracks: pickerTracks, mode: pickerMode, openPicker } = usePlaylistPicker();
const settingsDialog = useSettingsDialog();
const fav = useFavorite();
const download = useDownload();

/** 下载当前歌曲（本地曲目直接跳过） */
const downloadCurrent = (): void => {
  const track = displayTrack.value;
  if (track) void download.enqueue(track);
};
const library = useLibraryStore();

/** 加载中的歌曲使用队列当前项兜底 */
const displayTrack = computed(() => media.track ?? status.currentTrack);
const title = computed(() => displayTrack.value?.title ?? "");
const artists = computed(() =>
  (displayTrack.value?.artists ?? []).map((artist) => artist.name).join(" / "),
);
const liked = computed(() => fav.isLiked(displayTrack.value));
const canLike = computed(() => fav.isSupported(displayTrack.value));
const hasLyric = computed(() => media.parsedLyric.length > 0);
/** 顶部标题显示当前播放来源（歌单名），无来源时兜底 */
const contextName = computed(() => status.currentPlaybackContext?.originName ?? "");


/** 歌词来源切换弹窗 */
const matchDialogOpen = ref(false);

/** 页索引：0 封面页，1 歌词页 */
const page = ref(0);
const swipeArea = ref<HTMLElement>();
/** 歌词页容器，用于触摸结束后复位悬停态 */
const lyricSectionRef = ref<HTMLElement>();
/** 歌词引擎重建标识：触摸触发模糊消失后 3 秒重挂，恢复模糊 */
const lyricKey = ref(0);
/** 模糊恢复计时器 */
let lyricRestoreTimer: ReturnType<typeof setTimeout> | null = null;

/**
 * 触摸歌词后安排 3 秒重建歌词引擎，让渲染器回到初始的模糊状态
 */
const scheduleLyricBlurRestore = (): void => {
  if (lyricRestoreTimer) clearTimeout(lyricRestoreTimer);
  lyricRestoreTimer = setTimeout(() => {
    lyricRestoreTimer = null;
    lyricKey.value += 1;
  }, 3000);
};

/**
 * 触摸结束后清掉歌词的悬停与焦点状态
 * 渲染器按 hover 取消模糊，触摸设备的指针会停在上次位置，导致模糊一直不恢复
 */
const releaseLyricHover = (): void => {
  const active = document.activeElement as HTMLElement | null;
  if (active && active !== document.body) active.blur();
  const section = lyricSectionRef.value;
  if (section) {
    const target = section.querySelector("canvas, .lp-line, [class*='lp-']") ?? section;
    for (const type of ["pointerleave", "pointerout", "pointercancel"] as const) {
      target.dispatchEvent(new PointerEvent(type, { bubbles: true, pointerType: "touch" }));
      window.dispatchEvent(new PointerEvent(type, { bubbles: true, pointerType: "touch" }));
    }
  }
  // 渲染器的 hover 态可能被触摸粘住：重放一次模糊配置强制复位
  if (settingsStore.lyric.enableBlur) {
    settingsStore.lyric.enableBlur = false;
    requestAnimationFrame(() => {
      settingsStore.lyric.enableBlur = true;
    });
  }
};

/** 跟手拖拽位移（像素），松手后归零 */
const dragOffset = ref(0);
const dragging = ref(false);
const areaWidth = ref(0);
const touchStart = { x: 0, y: 0 };
/** 本次手势是否因起点落在进度条扩区而被整页横移忽略 */
let ignoreSwipe = false;

/**
 * 判断触点是否落在进度条的扩展命中区
 * @param y - 触点纵坐标
 * @returns 落在扩区内为真
 */
const isInsideProgress = (y: number): boolean => {
  const rect = progressAreaRef.value?.getBoundingClientRect();
  if (!rect) return false;
  return y >= rect.top - 24 && y <= rect.bottom + 24;
};

/**
 * 记录手指按下位置与容器宽度
 * @param event - 触摸事件
 */
const onTouchStart = (event: TouchEvent): void => {
  const touch = event.touches[0];
  if (!touch) return;
  touchStart.x = touch.clientX;
  touchStart.y = touch.clientY;
  areaWidth.value = swipeArea.value?.clientWidth ?? window.innerWidth;
  dragging.value = false;
  dragOffset.value = 0;
  ignoreSwipe = isInsideProgress(touch.clientY);
};

/**
 * 实时跟随手指，纵向滑动让给歌词滚动
 * @param event - 触摸事件
 */
const onTouchMove = (event: TouchEvent): void => {
  if (ignoreSwipe) return;
  const touch = event.touches[0];
  if (!touch) return;
  const deltaX = touch.clientX - touchStart.x;
  const deltaY = touch.clientY - touchStart.y;
  if (!dragging.value) {
    if (Math.abs(deltaX) < 8 || Math.abs(deltaX) < Math.abs(deltaY)) return;
    dragging.value = true;
  }
  const width = areaWidth.value || 1;
  const base = -page.value * width;
  const next = Math.min(0, Math.max(-width, base + deltaX));
  dragOffset.value = next - base;
};

/**
 * 松手判定翻页，距离不足则回弹
 * @param event - 触摸事件
 */
const onTouchEnd = (event: TouchEvent): void => {
  if (ignoreSwipe) {
    ignoreSwipe = false;
    dragging.value = false;
    dragOffset.value = 0;
    return;
  }
  const touch = event.changedTouches[0];
  if (!touch) return;
  const deltaX = touch.clientX - touchStart.x;
  const deltaY = touch.clientY - touchStart.y;
  const width = areaWidth.value || 1;
  // 封面页纵向下滑收起播放页
  if (!dragging.value && page.value === 0 && deltaY > 110 && Math.abs(deltaY) > Math.abs(deltaX)) {
    setPlayerExpanded(false);
    return;
  }
  if (dragging.value && Math.abs(deltaX) > width * 0.2) {
    // 无歌词也允许停在歌词页，避免滑过去又被弹回封面
    if (deltaX < 0) page.value = 1;
    else if (deltaX > 0) page.value = 0;
  }
  dragOffset.value = 0;
  dragging.value = false;
};

/**
 * 拖动进度条后跳转
 * @param value - 毫秒位置
 */
const onSeek = (value: number): void => {
  player.seek(snapToNearestLyric(value));
};

/** 切换收藏 */
const onToggleLike = (): void => {
  void fav.toggle(displayTrack.value);
};

/** 打开评论面板 */
const onShowComments = (): void => {
  const track = displayTrack.value;
  if (track) status.showComments(track);
};

/** 两页互换 */
const flipPage = (): void => {
  if (page.value === 0 && !hasLyric.value) return;
  page.value = page.value === 0 ? 1 : 0;
};

/** 是否沉浸全屏 */
const fullscreen = ref(false);

/** 切换沉浸全屏（隐藏状态栏与导航栏） */
const toggleFullscreen = (): void => {
  window.api.window.toggleFullscreen();
  fullscreen.value = !fullscreen.value;
};

/** 更多菜单项 */
const moreItems = [
  { key: "lyric", label: "歌词设置", icon: IconLucideTextQuote },
  { key: "player", label: "播放设置", icon: IconLucideSettings2 },
  { key: "appearance", label: "外观设置", icon: IconLucideLayoutTemplate },
  { key: "collapse", label: "收起播放页", icon: IconLucideChevronDown },
];

/**
 * 更多菜单选择
 * @param key - 菜单项键
 */
const onMoreSelect = (key: string): void => {
  if (key === "collapse") {
    setPlayerExpanded(false);
    return;
  }
  settingsDialog.show(key);
};

/** 加入歌单 */
const onAddToPlaylist = (): void => {
  if (displayTrack.value) openPicker([displayTrack.value]);
};

/** 每次展开播放页都从封面页开始 */
watch(
  () => status.isPlayerExpanded,
  (expanded) => {
    if (expanded) page.value = 0;
  },
);

/**
 * 播放模式三态切换：列表循环 → 单曲循环 → 随机 → 列表循环
 * 随机与循环在状态里本是正交的两维，这里收敛成三态换取单手可用的单一入口
 */
const cyclePlayMode = (): void => {
  if (shuffleMode.value === "on") {
    player.setShuffleMode("off");
    player.setRepeatMode("list");
    return;
  }
  if (repeatMode.value === "one") {
    player.setRepeatMode("list");
    player.setShuffleMode("on");
    return;
  }
  player.setRepeatMode("one");
};

/** 进度条区域，用于把矩形上报给安卓侧做系统手势排除 */
const progressAreaRef = ref<HTMLElement>();

/**
 * 让系统手势在进度条那段让路
 * 底部手势导航会在水平拖动几像素后取消指针事件，表现为进度条拖不动
 */
const syncGestureExclusion = (): void => {
  const api = (
    window as unknown as {
      api?: {
        system?: {
          setGestureExclusion?: (rect: { top: number; bottom: number } | null) => Promise<unknown>;
        };
      };
    }
  ).api;
  const setter = api?.system?.setGestureExclusion;
  if (!setter) return;
  const el = progressAreaRef.value;
  if (!el) {
    void setter(null);
    return;
  }
  const rect = el.getBoundingClientRect();
  if (rect.height <= 0) {
    void setter(null);
    return;
  }
  void setter({ top: rect.top - 8, bottom: rect.bottom + 8 });
};

/** 通知栏红心按钮的订阅解绑函数 */
let offToggleLike: (() => void) | undefined;

/** 原生返回事件的解绑函数 */
let offBack: (() => void) | undefined;

onMounted(async () => {
  // 红心状态只读本地缓存，读到位前按钮按未就绪处理
  void library.loadLiked();
  const nativeApi = (
    window as unknown as {
      api?: {
        system?: { subscribe?: (channel: string, cb: (data: unknown) => void) => () => void };
      };
    }
  ).api;
  // 通知卡片点红心时原生只发事件，收藏切换仍在渲染层做
  offToggleLike = nativeApi?.system?.subscribe?.("system:toggleLike", () => {
    void fav.toggle(displayTrack.value);
  });
  syncGestureExclusion();
  // 挂载首帧 layout 可能还没定尺寸，下一拍再测一次
  void nextTick(syncGestureExclusion);
  const host = (window as unknown as { AndroidHost?: { setBackHandler?: (enabled: boolean) => void } })
    .AndroidHost;
  if (!host) return;
  host.setBackHandler?.(true);
  const { registerBackInterceptor } = await import("@/services/backStack");
  // 播放页优先消费返回：先回封面页，再收起播放页
  offBack = registerBackInterceptor(() => {
    // 播放页收起时不再消费返回，交还给路由/下层
    if (!status.isPlayerExpanded) return false;
    if (page.value === 1) {
      page.value = 0;
      return true;
    }
    setPlayerExpanded(false);
    return true;
  });
});

onUnmounted(() => {
  if (lyricRestoreTimer) clearTimeout(lyricRestoreTimer);
  offToggleLike?.();
  offToggleLike = undefined;
  offBack?.();
  offBack = undefined;
  (
    window as unknown as {
      api?: { system?: { setGestureExclusion?: (rect: null) => Promise<unknown> } };
    }
  ).api?.system?.setGestureExclusion?.(null);
  (window as unknown as { AndroidHost?: { setBackHandler?: (enabled: boolean) => void } }).AndroidHost
    ?.setBackHandler?.(false);
});
</script>

<template>
  <div
    class="relative h-full w-full flex flex-col"
    :style="{ paddingTop: 'var(--android-safe-top, 0px)' }"
  >
    <!-- 顶部：收起 / 标题 / 换一首 -->
    <div class="shrink-0 flex items-center gap-2 px-3 pt-2 pb-1 [filter:drop-shadow(0_1px_2px_rgba(0,0,0,0.35))]">
      <SButton type="cover" variant="ghost" circle :size="44" class="opacity-70" @click="setPlayerExpanded(false)">
        <template #icon><IconLucideChevronDown /></template>
      </SButton>
      <span class="flex-1 min-w-0 text-center text-[13px] text-cover/70 truncate">
        {{ page === 1 ? title || "歌词" : contextName || "正在播放" }}
      </span>
      <!-- 163key 尚未解析完时提示，避免本地曲收藏状态看起来"失灵" -->
      <span v-if="library.musicIdResolving" class="shrink-0 text-[11px] text-cover/50">解析中</span>
      <SButton type="cover" variant="ghost" circle :size="44" class="opacity-70" @click="toggleFullscreen">
        <template #icon>
          <IconLucideMinimize v-if="fullscreen" />
          <IconLucideMaximize v-else />
        </template>
      </SButton>
    </div>

    <!-- 横向双页容器：跟手平移 -->
    <div
      ref="swipeArea"
      class="flex-1 min-h-0 relative overflow-hidden"
      @touchstart.passive="onTouchStart"
      @touchmove.passive="onTouchMove"
      @touchend.passive="onTouchEnd"
      @touchcancel.passive="onTouchEnd"
    >
      <div
        class="h-full w-full flex"
        :class="dragging ? 'will-change-transform' : ''"
        :style="{
          transform: `translateX(calc(${-page * 100}% + ${dragOffset}px))`,
          transition: dragging ? 'none' : 'transform 320ms cubic-bezier(0.32, 0.72, 0, 1)',
        }"
      >
        <!-- 第 1 页：封面页 -->
        <section class="w-full h-full shrink-0 flex flex-col px-5 pt-1">
          <div class="flex-1 min-h-0 flex items-center justify-center">
            <PlayerCover
              class="w-full max-w-[min(80vw,40vh)] aspect-square rounded-lg"
            />
          </div>

          <!-- 曲目信息：单列排布，红心与评论按钮挂在标签行尾部 -->
          <div class="shrink-0 mt-3 text-[13px] [filter:drop-shadow(0_1px_2px_rgba(0,0,0,0.35))]">
            <PlayerData
              align="left"
              hide-album
              class="-mx-2"
              :marquee-iterations="2"
              marquee-comment
            >
              <template #meta-trailing>
                <!-- 负右边距抵消 PlayerData 的 px-2，让按钮右边缘与左侧标签的 20px 屏边距对齐 -->
                <div class="-my-[9px] -mr-4 flex items-center gap-1">
                  <SButton
                    type="cover"
                    variant="ghost"
                    circle
                    :size="44"
                    :disabled="!canLike"
                    :class="library.likedReady ? (liked ? undefined : 'opacity-45') : undefined"
                    @click="onToggleLike"
                  >
                    <template #icon>
                      <SIconSwap :active="liked">
                        <template #on>
                          <IconMaterialSymbolsFavoriteRounded class="size-5" />
                        </template>
                        <template #off>
                          <IconMaterialSymbolsFavoriteOutlineRounded class="size-5" />
                        </template>
                      </SIconSwap>
                    </template>
                  </SButton>
                  <SButton
                    type="cover"
                    variant="ghost"
                    circle
                    :size="44"
                    class="opacity-45"
                    @click="onShowComments"
                  >
                    <template #icon><IconLucideMessageCircle class="size-5" /></template>
                  </SButton>
                </div>
              </template>
            </PlayerData>
          </div>

          <!-- 进度 -->
          <div ref="progressAreaRef" class="shrink-0 mt-2 [filter:drop-shadow(0_1px_2px_rgba(0,0,0,0.35))]">
            <div class="flex items-center gap-2">
              <span class="text-[11px] text-cover/50 tabular-nums min-w-9 text-center">
                {{ timeDisplay[0] }}
              </span>
              <SSlider
                :model-value="position"
                :glow="status.transitioning"
                :min="0"
                :max="duration"
                :step="100"
                :always-show-thumb="false"
                :hitbox-height="36"
                cover
                class="flex-1"
                @change="onSeek"
                @drag-end="onSeek"
              />
              <span class="text-[11px] text-cover/50 tabular-nums min-w-9 text-center">
                {{ timeDisplay[1] }}
              </span>
            </div>
          </div>

          <!-- 控制排：播放模式 / 上一首 / 播放 / 下一首 / 队列 -->
          <div class="shrink-0 flex items-center justify-between px-1 pt-1 [filter:drop-shadow(0_1px_2px_rgba(0,0,0,0.35))]">
            <SButton type="cover" variant="ghost" circle :size="44" @click="cyclePlayMode()">
              <template #icon>
                <IconLucideShuffle v-if="shuffleMode === 'on'" class="size-6" />
                <IconLucideRepeat1 v-else-if="repeatMode === 'one'" class="size-6" />
                <IconLucideRepeat v-else class="size-6" />
              </template>
            </SButton>
            <SButton type="cover" variant="ghost" circle :size="48" @click="player.prevTrack()">
              <template #icon><IconLucideSkipBack class="size-7 fill-current" /></template>
            </SButton>
            <SButton type="cover" variant="ghost" circle :size="64" @click="player.togglePlay()">
              <template #icon>
                <IconLucideLoaderCircle v-if="isLoading" class="size-11 animate-spin" />
                <IconLucidePause v-else-if="isPlaying" class="size-11 fill-current" />
                <IconLucidePlay v-else class="size-11 fill-current" />
              </template>
            </SButton>
            <SButton type="cover" variant="ghost" circle :size="48" @click="player.nextTrack()">
              <template #icon><IconLucideSkipForward class="size-7 fill-current" /></template>
            </SButton>
            <SButton
              type="cover"
              variant="ghost"
              circle
              :size="44"
              class="opacity-45"
              @click="status.fullQueueOpen = true"
            >
              <template #icon><IconLucideListMusic class="size-6" /></template>
            </SButton>
          </div>

          <!-- 工具排：歌词 / 加歌单 / 收藏 / 下载 / 更多 -->
          <div
            class="shrink-0 flex items-center justify-around px-2 pt-1 [filter:drop-shadow(0_1px_2px_rgba(0,0,0,0.35))]"
            :style="{ paddingBottom: 'calc(var(--android-safe-bottom, 0px) + 4px)' }"
          >
            <SButton
              type="cover"
              variant="ghost"
              circle
              :size="46"
              :class="page === 1 ? undefined : 'opacity-45'"
              :disabled="!hasLyric"
              @click="flipPage"
            >
              <template #icon><IconLucideCaptions class="size-5" /></template>
            </SButton>
            <SButton type="cover" variant="ghost" circle :size="46" class="opacity-45" @click="onAddToPlaylist">
              <template #icon><IconLucideListPlus class="size-5" /></template>
            </SButton>
            <SButton
              type="cover"
              variant="ghost"
              circle
              :size="46"
              :class="page === 1 ? undefined : 'opacity-45'"
              @click="downloadCurrent"
            >
              <template #icon><IconLucideDownload class="size-5" /></template>
            </SButton>
            <SDropdownMenu :items="moreItems" cover side="top" align="end" @select="onMoreSelect">
              <template #trigger>
                <SButton type="cover" variant="ghost" circle :size="46" class="opacity-45">
                  <template #icon><IconLucideEllipsis class="size-5" /></template>
                </SButton>
              </template>
            </SDropdownMenu>
          </div>
        </section>

        <!-- 第 2 页：整屏歌词 -->
        <section
          ref="lyricSectionRef"
          class="w-full h-full shrink-0 flex flex-col relative"
          @click.stop
          @pointerdown.stop
          @touchend.passive="releaseLyricHover"
        >
          <!-- 触摸设备上歌词不接收指针，避免点击把渲染器的悬停态粘住导致模糊一直取消 -->
          <PlayerLyric :visible="page === 1" class="pointer-events-none" />
          <!-- 歌词来源切换 -->
          <SButton
            type="cover"
            variant="ghost"
            circle
            :size="44"
            class="absolute right-3 bottom-[calc(var(--android-safe-bottom,0px)+12px)] z-10 pointer-events-auto bg-cover/15 backdrop-blur-sm [filter:drop-shadow(0_1px_3px_rgba(0,0,0,0.5))]"
            @click="matchDialogOpen = true"
          >
            <template #icon><IconLucideTextSearch class="size-5" /></template>
          </SButton>
        </section>
      </div>
    </div>

    <!-- 播放队列：使用通用弹层，动画与遮罩和设置页一致 -->
    <SDialog
      :open="status.fullQueueOpen"
      mobile-sheet
      :closable="false"
      width="720px"
      @update:open="(value: boolean) => { if (!value) status.fullQueueOpen = false; }"
    >
      <div class="h-[78vh] w-full flex flex-col">
        <QueuePanel @close="status.fullQueueOpen = false" />
      </div>
    </SDialog>

    <PlaylistPickerDialog v-model:open="pickerOpen" :mode="pickerMode" :tracks="pickerTracks" />
    <LyricMatchDialog v-model:open="matchDialogOpen" />
  </div>
</template>
