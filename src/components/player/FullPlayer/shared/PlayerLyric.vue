<script setup lang="ts">
import { useStatusStore } from "@/stores/status";
import { useMediaStore } from "@/stores/media";
import { useSettingsStore } from "@/stores/settings";
import { usePlaybackTime } from "@/composables/usePlaybackTime";
import { getCurrentTime } from "@/services/playback";
import Lyrics from "@/components/player/Lyrics/index.vue";
import * as player from "@/core/player";
import { useFullPlayer } from "../context";

const props = defineProps<{
  /** 当前布局下歌词区域是否可见 */
  visible: boolean;
}>();

const status = useStatusStore();
const media = useMediaStore();
const settings = useSettingsStore();
const { lyricMounted, contentActive, onLyricReady } = useFullPlayer();

const lyricRef = ref<InstanceType<typeof Lyrics>>();
const initialLyricTimeMs = ref(0);
const hasLyric = computed(() => media.parsedLyric.length > 0 || media.lyricLoading);

/**
 * 读取叠加单曲偏移后的歌词时间
 * @returns 歌词时间（毫秒）
 */
const lyricTimeMs = (): number => getCurrentTime() + status.lyricOffsetMs;

const { start: startTick, stop: stopTick } = usePlaybackTime((currentMs) => {
  if (!status.trackLoading && !media.lyricLoading) {
    lyricRef.value?.setCurrentTime(currentMs + status.lyricOffsetMs, player.isSeeking());
  }
});

/** 仅在歌词实际可见时推送时间并运行渲染器 */
const syncActivity = (): void => {
  if (lyricMounted.value && contentActive.value && props.visible) {
    lyricRef.value?.setCurrentTime(lyricTimeMs());
    lyricRef.value?.resume();
    startTick();
  } else {
    stopTick();
    lyricRef.value?.freeze();
  }
};

watch([lyricMounted, contentActive, () => props.visible], syncActivity, { flush: "post" });

/** 引擎完成首次布局后同步运行状态并通知壳层 */
const onReady = (): void => {
  syncActivity();
  onLyricReady();
};

// 挂载前记录初始时间；布局切换重建时同样生效。无歌词时引擎不会挂载，直接按就绪处理
watch(
  lyricMounted,
  (mounted) => {
    if (!mounted) return;
    initialLyricTimeMs.value = lyricTimeMs();
    if (!hasLyric.value) onLyricReady();
  },
  { immediate: true },
);

watch(hasLyric, (value) => {
  if (!lyricMounted.value) return;
  if (value) {
    initialLyricTimeMs.value = lyricTimeMs();
    return;
  }
  onLyricReady();
});

// 歌词变化时先推送精确时间
watch(
  () => media.parsedLyric,
  () => lyricRef.value?.setCurrentTime(lyricTimeMs()),
);

// 切换歌词引擎时，重新计算初始并推送时间
watch(
  () => settings.lyric.engine,
  () => {
    initialLyricTimeMs.value = lyricTimeMs();
    nextTick(syncActivity);
  },
);

const handleLyricSeek = async (timeMs: number): Promise<void> => {
  await player.seek(timeMs);
  if (!status.isPlaying) await player.play();
};

/** 手机竖屏：按高度缩放会把字号放大到屏宽装不下一行，改为按屏宽收敛 */
const isPhoneViewport = useMediaQuery("(max-width: 640px)");

/** 横屏优先使用独立的字号与字重，窄屏与横屏不再共用一套数值 */
const lyricFontWeight = computed(() =>
  isPhoneViewport.value
    ? String(settings.lyric.fontWeight)
    : String(settings.lyric.landscapeFontWeight),
);

const lyricFontSize = computed(() => {
  if (isPhoneViewport.value) {
    // 手机竖屏：按用户配置的字号等比映射，兼顾一行可容纳字数
    const base = Number(settings.lyric.fontSize) || 48;
    const size = Math.max(20, Math.min(44, Math.round(base * 0.52)));
    return `${size}px`;
  }
  const landscape = Number(settings.lyric.landscapeFontSize) || 0;
  if (landscape > 0) return `${landscape}px`;
  return settings.lyric.adaptiveFontSize
    ? `calc(${settings.lyric.fontSize} / 1080 * 100vh)`
    : `${settings.lyric.fontSize}px`;
});

const lyricSubFontSize = computed(() => {
  if (settings.lyric.translationFontSizeFollowMain) return "max(0.5em, 12px)";
  return settings.lyric.adaptiveFontSize
    ? `max(12px, calc(${settings.lyric.translationFontSize} / 1080 * 100vh))`
    : `${settings.lyric.translationFontSize}px`;
});
</script>

<template>
  <!-- 字号设在外层，让布局插入的头部信息与歌词同比缩放 -->
  <div class="relative flex-1 min-h-0 flex flex-col" :style="{ fontSize: lyricFontSize }">
    <slot name="header" />
    <div
      data-player-layout="lyric"
      class="lyric-area relative flex-1 min-h-0"
      :class="settings.lyric.lyricBlendMode === 'normal' ? 'lyric-normal' : 'lyric-blend'"
      :style="{
        '--lp-credit-opacity': '1',
        '--lp-sub-font-size': lyricSubFontSize,
        fontWeight: lyricFontWeight,
        fontFamily: settings.lyric.fontFamily || undefined,
        '--lyric-font-zh': settings.lyric.fontFamilyChinese || undefined,
        '--lyric-font-ja': settings.lyric.fontFamilyJapanese || undefined,
        '--lyric-font-ko': settings.lyric.fontFamilyKorean || undefined,
        '--lyric-font-latin': settings.lyric.fontFamilyLatin || undefined,
        mixBlendMode: settings.lyric.lyricBlendMode,
      }"
    >
      <Lyrics
        v-if="lyricMounted && hasLyric"
        ref="lyricRef"
        :lyric-lines="media.parsedLyric"
        :initial-time="initialLyricTimeMs"
        :playing="status.isPlaying"
        @seek="handleLyricSeek"
        @ready="onReady"
      />
      <div
        v-else-if="lyricMounted"
        class="w-full h-full flex items-center justify-center text-cover/30"
      >
        暂无歌词
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 手机竖屏：放宽行内左右留白，让歌词更接近满宽 */
.lyric-area :deep(.amll-lyric-player) {
  --lyric-line-padding-x: 10px !important;
}

/* normal 混色：去掉 filter 生成的隔离组，改用文字投影保证任意背景上的可读性 */
.lyric-area.lyric-normal {
  filter: none;
}

.lyric-area.lyric-normal :deep(.lp-line),
.lyric-area.lyric-normal :deep(.lp-content),
.lyric-area.lyric-normal :deep([class*="lyricLineWrapper"]),
.lyric-area.lyric-normal :deep([class*="lyricMainLine"]),
.lyric-area.lyric-normal :deep([class*="lyricSubLine"]) {
  mix-blend-mode: normal;
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.55);
}

.lyric-area.lyric-normal :deep([class*="lyric-player"]) {
  mix-blend-mode: normal !important;
  isolation: auto !important;
}

.lyric-area {
  filter: drop-shadow(0px 4px 6px rgba(0, 0, 0, 0.2));
  mask: linear-gradient(
    180deg,
    hsla(0, 0%, 100%, 0) 0,
    hsla(0, 0%, 100%, 0.6) 5%,
    #fff 10%,
    #fff 75%,
    hsla(0, 0%, 100%, 0.6) 85%,
    hsla(0, 0%, 100%, 0)
  );
}
</style>
