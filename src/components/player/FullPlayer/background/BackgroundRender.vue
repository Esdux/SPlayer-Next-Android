<script setup lang="ts">
import {
  type AbstractBaseRenderer,
  type BaseRenderer,
  BackgroundRender as CoreBackgroundRender,
  MeshGradientRenderer,
  IsolationRenderer,
  PixiRenderer,
} from "@applemusic-like-lyrics/core";
import type { PlayerBgRenderer } from "@/types/settings";
import { getFftFrame } from "@/services/playback";
import { acquireFft, releaseFft } from "@/services/fftCapture";
import { getBassPulse, toAmllLowFreqVolume } from "@/services/audioFeatures";

const RENDERER_MAP: Record<PlayerBgRenderer, new (canvas: HTMLCanvasElement) => BaseRenderer> = {
  mesh: MeshGradientRenderer,
  isolation: IsolationRenderer,
  pixi: PixiRenderer,
};

export interface BackgroundRenderProps {
  /** 专辑封面资源 URL */
  album?: string;
  /** 是否处于播放状态，默认为 true */
  playing?: boolean;
  /** 动画流动速度，默认为 2 */
  flowSpeed?: number;
  /** 是否有歌词，默认为 true */
  hasLyric?: boolean;
  /** 帧率，默认为 30 */
  fps?: number;
  /** 渲染缩放比例，默认为 0.5 */
  renderScale?: number;
  /** 是否随低频节拍脉动（默认 false，关闭则不采集 FFT） */
  enableBeat?: boolean;
  /** 渲染引擎标识，默认为 'mesh' */
  renderEngine?: PlayerBgRenderer;
  /** 自定义渲染器类（若指定则优先于 renderEngine） */
  renderer?: new (...args: ConstructorParameters<typeof BaseRenderer>) => BaseRenderer;
}

const props = withDefaults(defineProps<BackgroundRenderProps>(), {
  playing: true,
  flowSpeed: 2,
  hasLyric: true,
  fps: 30,
  renderScale: 0.5,
  enableBeat: false,
  renderEngine: "mesh",
});

const emit = defineEmits<{ ready: [] }>();

const wrapperRef = ref<HTMLDivElement | null>(null);
const visibility = useDocumentVisibility();

// 外部渲染器实例引用
const bgRenderRef = shallowRef<AbstractBaseRenderer>();

/**
 * 统一同步更新属性状态到底层渲染器
 */
const updateRendererState = () => {
  const renderer = bgRenderRef.value;
  if (!renderer) return;

  if (props.album) {
    renderer.setAlbum(props.album, false).then(
      async () => {
        if (bgRenderRef.value !== renderer) return;
        syncRendererMotion();
        // 缓存纹理也要留出首帧，保证淡入起始状态已经绘制
        await new Promise<void>((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
        );
        if (bgRenderRef.value === renderer) emit("ready");
      },
      (error) => console.warn("[background] 背景封面加载失败", error),
    );
  }
  renderer.setFPS(props.fps);
  renderer.setRenderScale(props.renderScale);
  renderer.setHasLyric(props.hasLyric);
  syncRendererMotion();
};

/**
 * 同步流体背景运动状态
 */
const syncRendererMotion = () => {
  const renderer = bgRenderRef.value;
  if (!renderer) return;

  if (visibility.value !== "visible") {
    renderer.pause();
    return;
  }

  renderer.setStaticMode(!props.playing);
  if (props.playing) {
    renderer.setFlowSpeed(props.flowSpeed);
    renderer.resume();
  } else {
    renderer.setFlowSpeed(0);
    renderer.resume();
  }
};

const BASS_ATTACK = 0.45;
const BASS_DECAY = 0.14;

// 低频平滑后脉冲
let smoothedPulse = 0;
let lastFftFrame: readonly [number[], number[]] = [[], []];

/**
 * 从最新 FFT 帧数据计算低频音量能量值 [0.0 - 1.0]
 */
const updateLowFreqVolume = () => {
  const data = getFftFrame();
  if (!data || data[0].length === 0) return;
  if (data === lastFftFrame) return;
  lastFftFrame = data;

  const pulse = getBassPulse(data);
  const smoothFactor = pulse > smoothedPulse ? BASS_ATTACK : BASS_DECAY;
  smoothedPulse = smoothedPulse + smoothFactor * (pulse - smoothedPulse);

  bgRenderRef.value?.setLowFreqVolume(toAmllLowFreqVolume(smoothedPulse));
};

const { resume: resumeFftLoop, pause: pauseFftLoop } = useRafFn(updateLowFreqVolume, {
  immediate: false,
});

// 本地持有标记，保证 acquire / release 严格配对
let fftAcquired = false;

/**
 * 开始捕获 FFT 频谱数据
 */
const startFftCapture = () => {
  if (!fftAcquired) {
    acquireFft();
    fftAcquired = true;
  }
  resumeFftLoop();
};

/**
 * 停止捕获 FFT 频谱数据
 */
const stopFftCapture = () => {
  pauseFftLoop();
  if (fftAcquired) {
    releaseFft();
    fftAcquired = false;
  }
};

/**
 * 按播放状态与跳动开关同步 FFT 采集
 */
const syncFftCapture = () => {
  if (visibility.value === "visible" && props.playing && props.enableBeat) {
    startFftCapture();
  } else {
    stopFftCapture();
    if (!props.enableBeat) {
      smoothedPulse = 0;
      lastFftFrame = [[], []];
      bgRenderRef.value?.setLowFreqVolume(1.0);
    }
  }
};

/** 获取当前设置对应的背景渲染器类 */
const getRendererClass = () => {
  if (props.renderer) return props.renderer;
  return RENDERER_MAP[props.renderEngine ?? "mesh"] ?? MeshGradientRenderer;
};

/** WebGL 上下文丢失后的重建计时器 */
let contextLostTimer: number | undefined;

/**
 * 上下文丢失时兜底重建，避免背景整块变黑
 * @param event - WebGL 上下文事件
 */
const onContextLost = (event: Event): void => {
  event.preventDefault();
  console.warn("[background] WebGL 上下文丢失，1.2s 后重建渲染器");
  if (contextLostTimer !== undefined) window.clearTimeout(contextLostTimer);
  contextLostTimer = window.setTimeout(() => {
    contextLostTimer = undefined;
    destroyRenderer();
    initRenderer();
  }, 1200);
};

/**
 * 初始化底层渲染器并挂载至 DOM
 */
const initRenderer = () => {
  if (!wrapperRef.value) return;

  const RendererClass = getRendererClass();
  // WebGL 不可用或精度不足时直接放弃，保持流体层透明、露出底层背景
  const capability = RendererClass as unknown as { isSupported?: () => boolean };
  if (typeof capability.isSupported === "function" && !capability.isSupported()) {
    console.warn("[background] 当前环境不支持该渲染器，回落到静态背景");
    return;
  }

  try {
    bgRenderRef.value = CoreBackgroundRender.new(RendererClass);
  } catch (error) {
    console.warn("[background] 渲染器初始化失败，回落到静态背景", error);
    return;
  }

  const el = bgRenderRef.value.getElement();
  el.style.width = "100%";
  el.style.height = "100%";
  el.style.display = "block";
  el.addEventListener("webglcontextlost", onContextLost, false);
  wrapperRef.value.appendChild(el);

  // 重建后需要重新注入封面，否则网格取色没有素材
  if (props.album) {
    void toSameOriginUrl(props.album).then((sameOrigin) =>
      bgRenderRef.value?.setAlbum(sameOrigin, false).catch((error: unknown) => {
        console.warn("[background] 重建后封面加载失败", error);
      }),
    );
  }

  updateRendererState();
  syncFftCapture();
};

/**
 * 释放渲染器资源并清空容器
 */
const destroyRenderer = () => {
  stopFftCapture();
  if (contextLostTimer !== undefined) {
    window.clearTimeout(contextLostTimer);
    contextLostTimer = undefined;
  }

  const renderer = bgRenderRef.value;
  if (renderer) {
    renderer.pause();
    const el = renderer.getElement();
    el?.removeEventListener("webglcontextlost", onContextLost, false);
    el?.remove();
    renderer.dispose();
    bgRenderRef.value = undefined;
  }
};

onMounted(() => {
  initRenderer();
});

onBeforeUnmount(() => {
  destroyRenderer();
});

// 监听渲染器引擎切换，平滑重建实例
watch(
  () => [props.renderEngine, props.renderer],
  () => {
    destroyRenderer();
    initRenderer();
  },
);

// 属性变化监听
/**
 * 远程封面先经原生取字节转成同源 blob，否则渲染器 fetch 会被 CORS 拦下、
 * 网格取色没有素材，背景就只剩纯色
 * @param url - 封面地址
 * @returns 可被渲染器读取的地址
 */
const toSameOriginUrl = async (url: string): Promise<string> => {
  if (!/^https?:/i.test(url)) return url;
  try {
    const result = await window.api.system.fetchRemoteBytes(url);
    if (!result.success || !result.data) return url;
    return URL.createObjectURL(new Blob([new Uint8Array(result.data)]));
  } catch {
    return url;
  }
};

watch(
  () => props.album,
  async (val) => {
    const renderer = bgRenderRef.value;
    if (val && renderer) {
      const sameOrigin = await toSameOriginUrl(val);
      renderer.setAlbum(sameOrigin, false).then(
        () => {
          if (bgRenderRef.value === renderer) syncRendererMotion();
        },
        (error) => console.warn("[background] 背景封面加载失败", error),
      );
    }
  },
);

watch([() => props.playing, visibility], () => {
  syncRendererMotion();
  syncFftCapture();
});

watch(
  () => props.enableBeat,
  () => syncFftCapture(),
);

watch(
  () => props.fps,
  (val) => {
    bgRenderRef.value?.setFPS(val);
  },
);

watch(
  () => props.flowSpeed,
  (val) => {
    if (props.playing) bgRenderRef.value?.setFlowSpeed(val);
  },
);

watch(
  () => props.renderScale,
  (val) => {
    bgRenderRef.value?.setRenderScale(val);
  },
);

watch(
  () => props.hasLyric,
  (val) => {
    bgRenderRef.value?.setHasLyric(val);
  },
);

defineExpose({
  bgRender: bgRenderRef,
  wrapperEl: wrapperRef,
});
</script>

<template>
  <div ref="wrapperRef" class="background-render-wrapper" aria-hidden="true" />
</template>

<style scoped>
.background-render-wrapper {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  overflow: hidden;
  z-index: 0;
  pointer-events: none;
}
</style>
