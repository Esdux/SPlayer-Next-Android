<script setup lang="ts">
import { useStatusStore } from "@/stores/status";
import { useMediaStore } from "@/stores/media";
import { useSettingsStore } from "@/stores/settings";
import { useFullPlayer } from "../../context";
import { setPlayerExpanded } from "@/services/playerTransition";

const status = useStatusStore();
const media = useMediaStore();
const settings = useSettingsStore();
const { isPlaying, showLyric } = storeToRefs(status);

/** 加载中的歌曲使用队列当前项兜底 */
const displayTrack = computed(() => media.track ?? status.currentTrack);
const hasLyric = computed(() => media.parsedLyric.length > 0 || media.lyricLoading);
const coverWidth = computed(() => `${settings.player.coverLyricRatio * 100}%`);
const { immersive } = useFullPlayer();
/** 横屏下滑收起的起点 */
let touchStartY = 0;

const onTouchStart = (event: TouchEvent): void => {
  touchStartY = event.touches[0]?.clientY ?? 0;
};

/** 空白区域下滑超过 80px 收起全屏播放页 */
const onTouchEnd = (event: TouchEvent): void => {
  const endY = event.changedTouches[0]?.clientY ?? 0;
  if (endY - touchStartY > 80) setPlayerExpanded(false);
};

/** 不看队列时封面是否居中 */
const centeredWithoutQueue = computed(
  () => !showLyric.value || (settings.player.autoCenterCover && !hasLyric.value),
);

const coverCentered = computed(() => !status.fullQueueOpen && centeredWithoutQueue.value);

const lyricVisible = computed(() => showLyric.value && hasLyric.value && !status.fullQueueOpen);

const toggleLyric = (): void => {
  if (status.fullQueueOpen) {
    status.fullQueueOpen = false;
    showLyric.value = true;
  } else {
    showLyric.value = !showLyric.value;
  }
};
</script>

<template>
  <PlayerTopBar>
    <template #left>
      <SButton
        type="cover"
        variant="ghost"
        circle
        :size="40"
        :disabled="!hasLyric"
        :class="lyricVisible ? 'opacity-100' : 'opacity-40'"
        @click="toggleLyric"
      >
        <template #icon><IconLucideTextQuote /></template>
      </SButton>
    </template>
  </PlayerTopBar>
  <!-- 歌词区固定到屏幕底部，底栏覆盖其上，不再随底栏显隐上下移动 -->
  <div class="absolute top-8 inset-x-0 bottom-0" @touchstart.passive="onTouchStart" @touchend.passive="onTouchEnd">
    <!-- 左侧封面与信息：固定在底栏抬高后的位置 -->
    <div
      class="absolute top-6 bottom-[calc(5rem+var(--android-safe-bottom,0px))] left-0 flex items-center justify-center px-9 transition-transform duration-600 ease-[cubic-bezier(0.4,0,0.2,1)]"
      :style="{
        width: coverWidth,
        transform: coverCentered ? 'translateX(calc(50vw - 50%))' : undefined,
      }"
    >
      <div class="relative w-[clamp(200px,85%,50vh)] -translate-y-[11vh]">
        <Transition name="scale-switch" mode="out-in">
          <div :key="displayTrack?.id">
            <PlayerCover
              class="w-full aspect-square rounded-[32px] shadow-[0_0_20px_10px_rgba(0,0,0,0.1)] [transition:transform_500ms_cubic-bezier(0.34,1.56,0.64,1),box-shadow_500ms_ease-out]"
              :class="isPlaying ? 'scale-100' : 'scale-90'"
            />
            <div class="absolute top-full left-0 w-full pt-3">
              <PlayerData align="left" title-only />
            </div>
          </div>
        </Transition>
      </div>
    </div>
    <!-- 右侧歌词 -->
    <div
      class="group absolute inset-y-0 right-0 pr-1.5 -translate-x-6 flex flex-col transition-opacity duration-600 ease-[cubic-bezier(0.4,0,0.2,1)]"
      :class="
        coverCentered || status.fullQueueOpen ? 'opacity-0 pointer-events-none' : 'opacity-100'
      "
      :style="{ width: `calc(100% - ${coverWidth})` }"
    >
      <PlayerLyric :visible="lyricVisible" />
      <LyricActions />
    </div>
    <!-- 播放队列 -->
    <div
      class="absolute inset-y-0 right-0 pl-4 py-6 flex items-center"
      :class="status.fullQueueOpen ? '' : 'pointer-events-none'"
      :style="{ width: `calc(100% - ${coverWidth})` }"
    >
      <Transition
        :enter-active-class="`transition-opacity duration-600 ease-[cubic-bezier(0.4,0,0.2,1)] ${centeredWithoutQueue ? 'delay-300' : ''}`"
        enter-from-class="opacity-0"
        leave-active-class="transition-opacity duration-600 ease-[cubic-bezier(0.4,0,0.2,1)]"
        leave-to-class="opacity-0"
      >
        <div v-if="status.fullQueueOpen" class="w-full h-full">
          <QueuePanel @close="status.fullQueueOpen = false" />
        </div>
      </Transition>
    </div>
  </div>
  <PlayerBottomBar />
</template>
