<script setup lang="ts">
import { useStatusStore } from "@/stores/status";
import { useMediaStore } from "@/stores/media";
import { useFullPlayer } from "../../context";
import { setPlayerExpanded } from "@/services/playerTransition";

const status = useStatusStore();
const media = useMediaStore();

const hasLyric = computed(() => media.parsedLyric.length > 0 || media.lyricLoading);

/** 全屏歌词没有隐藏歌词的入口，歌词仅让位给播放队列 */
const lyricVisible = computed(() => hasLyric.value && !status.fullQueueOpen);
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
</script>

<template>
  <PlayerTopBar />
  <!-- 歌词区固定到屏幕底部，底栏覆盖其上 -->
  <div class="absolute top-8 inset-x-0 bottom-0" @touchstart.passive="onTouchStart" @touchend.passive="onTouchEnd">
    <div
      class="group absolute inset-0 px-2 flex flex-col transition-opacity duration-600 ease-[cubic-bezier(0.4,0,0.2,1)]"
      :class="status.fullQueueOpen ? 'opacity-0 pointer-events-none' : 'opacity-100'"
    >
      <PlayerLyric :visible="lyricVisible">
        <template #header>
          <div class="shrink-0 pt-2 pb-6">
            <PlayerData simple />
          </div>
        </template>
      </PlayerLyric>
      <LyricActions />
    </div>
    <!-- 播放队列 -->
    <div
      class="absolute inset-y-0 right-0 w-1/2 pl-4 py-6 flex items-center"
      :class="status.fullQueueOpen ? '' : 'pointer-events-none'"
    >
      <Transition
        enter-active-class="transition-opacity duration-600 ease-[cubic-bezier(0.4,0,0.2,1)]"
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
