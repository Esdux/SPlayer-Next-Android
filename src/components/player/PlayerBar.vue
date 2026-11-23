<script setup lang="ts">
import { useStatusStore } from "@/stores/status";
import { useSettingsStore } from "@/stores/settings";
import { useMediaStore } from "@/stores/media";
import { useMediaQuery } from "@vueuse/core";
import { useFavorite } from "@/composables/useFavorite";
import { usePlaylistPicker } from "@/composables/usePlaylistPicker";
import { useTrackMenu } from "@/composables/useTrackMenu";
import { useDownload } from "@/composables/useDownload";
import { useProgressLyric } from "@/composables/useProgressLyric";
import { useResponsiveLayout } from "@/composables/useResponsiveLayout";
import * as player from "@/core/player";
import { setPlayerExpanded } from "@/services/playerTransition";
import IconLucidePlay from "~icons/lucide/play";
import IconLucidePause from "~icons/lucide/pause";
import IconLucideListMusic from "~icons/lucide/list-music";

const status = useStatusStore();
const settings = useSettingsStore();
const media = useMediaStore();
const fav = useFavorite();
const { position, duration } = storeToRefs(status);
const { formatTooltip, snapToNearestLyric } = useProgressLyric();

const { isCompact } = useResponsiveLayout();
/** 手机宽度：播放条进入极简布局 */
const isPhoneViewport = useMediaQuery("(max-width: 640px)");
/** 浮动模式或窄窗口统一使用紧凑布局 */
const useCompactLayout = computed(
  () => settings.appearance.layoutMode === "floating" || isCompact.value,
);
/** 是否显示进度条提示 */
const showTooltip = computed(() => settings.player.showProgressTooltip);

/** 胶囊滑动起点 */
const barTouch = { x: 0, y: 0, active: false };

/** 胶囊内容跟手位移（像素） */
const barDragX = ref(0);
/**
 * 滑动手势过程中让标题/歌手跟手平移
 * @param event - 触摸事件
 */
const onBarTouchMove = (event: TouchEvent): void => {
  if (!isPhoneViewport.value || !barTouch.active) return;
  const touch = event.touches[0];
  if (!touch) return;
  const dx = touch.clientX - barTouch.x;
  barDragX.value = Math.max(-140, Math.min(140, dx));
};

/** 胶囊内容的位移/透明度样式：拖拽时无过渡，松手回弹带过渡；切歌入场由 TrackInfo 的 slide-left 负责 */
const barContentStyle = computed(() => ({
  transform: `translateX(${barDragX.value * 0.5}px)`,
  opacity: String(1 - Math.min(0.45, Math.abs(barDragX.value) / 280)),
  transition: barDragX.value === 0 ? "transform 240ms cubic-bezier(0.22,1,0.36,1), opacity 240ms ease" : "none",
}));

/**
 * 点击胶囊空白处展开全屏播放页
 * @param event - 点击事件
 */
const onBarClick = (event: MouseEvent): void => {
  // 控件区域只触发控件自身功能，任何按钮（含 s-button 包装）都拦下
  const target = event.target as HTMLElement | null;
  if (target?.closest("button,a,input,[role='button'],.s-button")) return;
  setPlayerExpanded(true);
};

/**
 * 记录胶囊滑动起点
 * @param event - 触摸事件
 */
const onBarTouchStart = (event: TouchEvent): void => {
  const touch = event.touches[0];
  if (!touch) return;
  barTouch.x = touch.clientX;
  barTouch.y = touch.clientY;
  barTouch.active = true;
};

/**
 * 左右滑切歌
 * @param event - 触摸事件
 */
const onBarTouchEnd = (event: TouchEvent): void => {
  if (!barTouch.active) return;
  barTouch.active = false;
  const touch = event.changedTouches[0];
  if (!touch) return;
  const dx = touch.clientX - barTouch.x;
  const dy = touch.clientY - barTouch.y;
  // 无论是否达到切歌阈值，松手都要先复位，否则内容会停在拖拽位置
  barDragX.value = 0;
  if (Math.abs(dx) < 60 || Math.abs(dx) < Math.abs(dy)) return;
  if (dx < 0) player.nextTrack();
  else player.prevTrack();
};

const onSeekDragEnd = (value: number): void => {
  const snappedValue = snapToNearestLyric(value);
  player.seek(snappedValue);
};

/** 添加到歌单 */
const {
  open: pickerOpen,
  tracks: pickerTracks,
  mode: pickerMode,
  openPicker,
} = usePlaylistPicker();

/** 歌曲菜单 */
const { enqueue: enqueueDownload } = useDownload();
const { items: menuItems, handleSelect: onMenuSelect } = useTrackMenu(toRef(media, "track"), {
  hidePlayActions: true,
  onAddToPlaylist: (track) => openPicker([track]),
  onDownload: (track, quality) => void enqueueDownload(track, { quality }),
});
</script>

<template>
  <!-- 浮动模式与窄窗口紧凑布局 -->
  <!-- 紧凑布局：手机竖屏时容器变成悬浮胶囊，结构不动 -->
  <div
    v-if="useCompactLayout"
    class="flex items-center min-w-0"
    :class="
      isPhoneViewport
        ? [
            'mx-3 mb-2 h-[56px] rounded-full overflow-hidden bg-surface-panel/70 text-on-surface border border-on-surface/10 shadow-xl pl-[15px] pr-1.5 gap-3',
            // 播放页展开时胶囊被覆盖层完全遮住，实时模糊仍在合成；展开态去掉该层
            status.isPlayerExpanded ? '' : 'backdrop-blur-xl',
          ]
        : 'relative h-full px-4 gap-4'
    "
    @click="onBarClick"
    @touchstart.passive="onBarTouchStart"
    @touchmove.passive="onBarTouchMove"
    @touchend.passive="onBarTouchEnd"
  >
    <PlayerControls
      compact
      :minimal="isPhoneViewport"
      :play-only="isPhoneViewport"
      :class="isPhoneViewport ? 'order-last shrink-0' : ''"
    />
    <div
      class="flex flex-col flex-1 min-w-0"
      :class="isPhoneViewport ? 'justify-center order-first' : 'gap-1 pt-2 pb-1'"
      :style="isPhoneViewport ? barContentStyle : undefined"
    >
      <div class="flex items-center gap-2 min-w-0">
        <TrackInfo compact class="flex-1">
          <template v-if="!isPhoneViewport" #title-trailing>
            <div class="flex items-center shrink-0">
              <SButton
                v-if="media.track?.source !== 'plugin'"
                class="-my-1"
                type="primary"
                variant="text"
                circle
                :size="24"
                :icon-size="16"
                :disabled="!fav.isSupported(media.track)"
                @click="fav.toggle(media.track)"
              >
                <template #icon>
                  <SIconSwap :active="fav.isLiked(media.track)">
                    <template #on><IconMaterialSymbolsFavoriteRounded /></template>
                    <template #off><IconMaterialSymbolsFavoriteOutlineRounded /></template>
                  </SIconSwap>
                </template>
              </SButton>
              <SDropdownMenu
                v-if="media.track"
                :items="menuItems"
                side="top"
                align="start"
                @select="onMenuSelect"
              >
                <template #trigger>
                  <SButton
                    class="-my-1"
                    type="primary"
                    variant="text"
                    circle
                    :size="24"
                    :icon-size="16"
                  >
                    <template #icon><IconLucideMoreHorizontal /></template>
                  </SButton>
                </template>
              </SDropdownMenu>
            </div>
          </template>
        </TrackInfo>
        <div class="contents lt-md:hidden">
          <PlayerTimeInfo compact />
        </div>
      </div>
      <SSlider
        v-if="!isPhoneViewport"
        :model-value="position"
        :glow="status.transitioning"
        :min="0"
        :max="duration"
        :step="100"
        :track-height="3"
        :thumb-size="10"
        :always-show-thumb="false"
        :show-popover="showTooltip"
        @drag-end="onSeekDragEnd"
      >
        <template #popover="{ value }">{{ formatTooltip(value) }}</template>
      </SSlider>
    </div>
    <div v-if="!isPhoneViewport" class="shrink-0">
      <Toolbar />
    </div>
  </div>
  <!-- 默认模式 -->
  <div v-else class="relative h-full">
    <div class="absolute left-0 right-0 top-0 -translate-y-1/2 z-10">
      <SSlider
        :model-value="position"
        :glow="status.transitioning"
        :min="0"
        :max="duration"
        :step="100"
        :track-height="3"
        :thumb-size="12"
        :always-show-thumb="false"
        :show-popover="showTooltip"
        @drag-end="onSeekDragEnd"
      >
        <template #popover="{ value }">{{ formatTooltip(value) }}</template>
      </SSlider>
    </div>
    <div class="grid grid-cols-[1fr_auto_1fr] items-center h-full px-3 gap-3">
      <TrackInfo>
        <template #title-trailing>
          <div class="flex items-center shrink-0">
            <SButton
              v-if="media.track?.source !== 'plugin'"
              class="-my-1"
              type="primary"
              variant="text"
              circle
              :size="28"
              :icon-size="18"
              :disabled="!fav.isSupported(media.track)"
              @click="fav.toggle(media.track)"
            >
              <template #icon>
                <SIconSwap :active="fav.isLiked(media.track)">
                  <template #on><IconMaterialSymbolsFavoriteRounded /></template>
                  <template #off><IconMaterialSymbolsFavoriteOutlineRounded /></template>
                </SIconSwap>
              </template>
            </SButton>
            <SDropdownMenu
              v-if="media.track"
              :items="menuItems"
              side="top"
              align="start"
              @select="onMenuSelect"
            >
              <template #trigger>
                <SButton
                  class="-my-1"
                  type="primary"
                  variant="text"
                  circle
                  :size="28"
                  :icon-size="18"
                >
                  <template #icon><IconLucideMoreHorizontal /></template>
                </SButton>
              </template>
            </SDropdownMenu>
          </div>
        </template>
      </TrackInfo>
      <PlayerControls class="mx-15" />
      <div class="flex items-center justify-end gap-2 min-w-0">
        <PlayerTimeInfo />
        <Toolbar />
      </div>
    </div>
  </div>
  <PlaylistPickerDialog v-model:open="pickerOpen" :mode="pickerMode" :tracks="pickerTracks" />
</template>
