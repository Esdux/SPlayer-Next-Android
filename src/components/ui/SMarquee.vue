<script setup lang="ts">
export interface SMarqueeProps {
  /** 滚动速度（px/s） */
  speed?: number;
  /** 开始滚动前的延迟（ms） */
  delay?: number;
  /** 两段文本之间的间距（px） */
  gap?: number;
  /** 收缩适应内容 */
  fit?: boolean;
  /** 是否在超出滚动时启用边缘渐变遮罩 */
  mask?: boolean;
  /** 渐变遮罩宽度（px） */
  maskWidth?: number;
  /** 滚动次数，0 表示无限循环 */
  iterations?: number;
}

const props = withDefaults(defineProps<SMarqueeProps>(), {
  speed: 30,
  delay: 2000,
  gap: 50,
  fit: false,
  mask: true,
  maskWidth: 8,
  iterations: 0,
});

const containerRef = ref<HTMLElement>();
const textRef = ref<HTMLElement>();
const isOverflowing = ref(false);
const animDuration = ref("0s");

/** 滚动动画的迭代次数，0 保持无限循环 */
const marqueeIterations = computed(() =>
  props.iterations > 0 ? String(props.iterations) : "infinite",
);

/** 有限次数播放结束后停在第二份文本开头，无限循环不需要填充 */
const marqueeFill = computed(() => (props.iterations > 0 ? "forwards" : "none"));

let resizeObserver: ResizeObserver | null = null;

const check = () => {
  const container = containerRef.value;
  const text = textRef.value;
  if (!container || !text) return;
  const overflow = text.scrollWidth > container.clientWidth;
  isOverflowing.value = overflow;
  if (overflow) {
    animDuration.value = `${text.scrollWidth / props.speed}s`;
  }
};

onMounted(() => {
  resizeObserver = new ResizeObserver(check);
  if (containerRef.value) resizeObserver.observe(containerRef.value);
  if (textRef.value) resizeObserver.observe(textRef.value);
  nextTick(check);
});

onUpdated(check);

onUnmounted(() => {
  resizeObserver?.disconnect();
});
</script>

<template>
  <div
    ref="containerRef"
    class="overflow-hidden"
    :class="[fit ? 'max-w-full' : 'w-full', isOverflowing && mask && 's-marquee-mask']"
    :style="{
      '--marquee-duration': animDuration,
      '--marquee-delay': `${delay}ms`,
      '--marquee-gap': `${gap}px`,
      '--marquee-mask-width': `${maskWidth}px`,
      '--marquee-iterations': marqueeIterations,
      '--marquee-fill': marqueeFill,
    }"
  >
    <div
      class="inline-flex whitespace-nowrap min-w-full"
      :class="isOverflowing && 's-marquee-scrolling'"
    >
      <span ref="textRef" class="inline-flex items-center whitespace-nowrap shrink-0">
        <slot />
      </span>
      <span
        v-if="isOverflowing"
        class="inline-flex items-center whitespace-nowrap shrink-0 pl-[var(--marquee-gap,50px)]"
        aria-hidden="true"
      >
        <slot />
      </span>
    </div>
  </div>
</template>

<style scoped>
.s-marquee-scrolling {
  will-change: transform;
  animation-name: s-marquee-scroll;
  animation-duration: var(--marquee-duration, 10s);
  animation-timing-function: linear;
  animation-delay: var(--marquee-delay, 2s);
  animation-iteration-count: var(--marquee-iterations, infinite);
  animation-fill-mode: var(--marquee-fill, none);
}
@keyframes s-marquee-scroll {
  0% {
    transform: translateX(0);
  }
  100% {
    transform: translateX(calc(-50% - var(--marquee-gap, 50px) / 2));
  }
}

.s-marquee-mask {
  mask-image: linear-gradient(
    to right,
    black 0%,
    black calc(100% - var(--marquee-mask-width, 8px)),
    transparent 100%
  );
  -webkit-mask-image: linear-gradient(
    to right,
    black 0%,
    black calc(100% - var(--marquee-mask-width, 8px)),
    transparent 100%
  );
  animation-name: s-marquee-mask-fade;
  animation-duration: var(--marquee-duration, 10s);
  animation-timing-function: linear;
  animation-delay: var(--marquee-delay, 2s);
  animation-iteration-count: var(--marquee-iterations, infinite);
  animation-fill-mode: var(--marquee-fill, none);
}

@keyframes s-marquee-mask-fade {
  0%,
  100% {
    mask-image: linear-gradient(
      to right,
      transparent 0%,
      black var(--marquee-mask-width, 8px),
      black calc(100% - var(--marquee-mask-width, 8px)),
      transparent 100%
    );
    -webkit-mask-image: linear-gradient(
      to right,
      transparent 0%,
      black var(--marquee-mask-width, 8px),
      black calc(100% - var(--marquee-mask-width, 8px)),
      transparent 100%
    );
  }
}

@media (prefers-reduced-motion: reduce) {
  .s-marquee-scrolling,
  .s-marquee-mask {
    animation: none;
  }
}
</style>
