/**
 * 安卓听歌识曲：麦克风采集 → AFP 指纹 → 网易云 audio_match
 *
 * 桌面走 Node 原生采集加 Worker 线程算指纹，安卓没有 Node，这里全部使用浏览器 API；
 * WebView 页面本身已经处理麦克风授权弹窗（MainActivity 的 onPermissionRequest），
 * 本文件只负责把「被拒」映射成可区分的事件
 */

import type {
  RecognitionCandidate,
  RecognitionConfig,
  RecognitionErrorCode,
  RecognitionEvent,
} from "@shared/types/recognition";
import {
  captureMicrophone,
  waitCapture,
  type MicrophoneCaptureHandle,
} from "@/services/recognition/microphoneCapture";
import { neteaseCall } from "../apis/netease";

/** 指纹输入采样率，worklet 已把任意输入抽稀到该值 */
const SAMPLE_RATE = 8000;
/** 静音判定阈值（8 kHz 单声道 RMS），低于该值视为没录到声音 */
const SILENCE_RMS_THRESHOLD = 0.005;
/** 匹配窗口 3 秒、滑窗步长 1 秒，与桌面 session.ts 保持一致 */
const WINDOW_SAMPLES = 3 * SAMPLE_RATE;
const STEP_SAMPLES = 1 * SAMPLE_RATE;
/** 指纹输入的期望 RMS 与增益上限，弱信号放大后 AFP 才能拾取到峰值特征 */
const TARGET_RMS = 0.1;
const MAX_GAIN = 50;
/** 采集窗口默认 8 秒，至少 3 秒才能凑出一个匹配窗口 */
const DEFAULT_DURATION_MS = 8000;
const MIN_DURATION_MS = 3000;
const MAX_DURATION_MS = 15000;
/** Emscripten 运行时初始化失败时轮询不会结束，指纹阶段必须有上限 */
const FINGERPRINT_TIMEOUT_MS = 15000;

/** 匹配接口返回的歌曲信息 */
interface MatchedSong {
  id: number;
  name: string;
  artists?: { name: string }[];
  album?: { name?: string; picUrl?: string };
}

/** audio_match 响应体 */
interface MatchBody {
  code?: number;
  data?: { result?: { startTime?: number; song?: MatchedSong }[] };
}

/** 指纹计算结果 */
type FingerprintResult =
  | { ok: true; fingerprint: string }
  | { ok: false; message: string };

/** 匹配结果 */
type MatchResult =
  | { ok: true; songs: { song: MatchedSong; startTime?: number }[] }
  | { ok: false; message: string };

/** AFP 指纹库导出面 */
interface AfpModule {
  GenerateFP: (pcm: Float32Array) => Promise<string>;
}

/** 事件订阅者；preload 与 renderer 在安卓属于同一 bundle，模块级订阅即可跨组件派发 */
const listeners = new Set<(event: RecognitionEvent) => void>();

/** 当前采集的取消信号 */
let activeAbort: AbortController | null = null;
/** 当前采集句柄，取消时直接释放 */
let activeCapture: MicrophoneCaptureHandle | null = null;
/** 会话令牌：start / submit / cancel 时递增，异步步骤据此识别已取消的会话 */
let sessionToken = 0;

/**
 * 推送识别事件
 * @param event - 阶段 / 音量 / 候选 / 错误
 */
const emit = (event: RecognitionEvent): void => {
  for (const listener of [...listeners]) {
    try {
      listener(event);
    } catch (error) {
      console.error("[recognition] 事件处理异常:", error);
    }
  }
};

/**
 * 推送失败事件
 * @param code - 共享错误码
 * @param message - 面向排查的说明
 */
const emitError = (code: RecognitionErrorCode, message: string): void => {
  console.warn(`[recognition] 识别失败 [${code}]: ${message}`);
  emit({ phase: "error", error: { code, message } });
};

/**
 * 订阅识别事件
 * @param callback - 进度 / 结果 / 错误
 * @returns 取消订阅函数
 */
export const onRecognitionEvent = (
  callback: (event: RecognitionEvent) => void,
): (() => void) => {
  listeners.add(callback);
  return () => {
    listeners.delete(callback);
  };
};

/**
 * 清空本地事件订阅；热重载会重复挂载，preload 侧按 IPC 的同口径处理
 */
export const clearRecognitionListeners = (): void => {
  listeners.clear();
};

/**
 * 当前环境是否能做麦克风识别
 * @returns getUserMedia 与 AudioWorklet 都可用时为真
 */
export const isRecognitionSupported = (): boolean => {
  if (typeof navigator === "undefined" || typeof AudioContext === "undefined") return false;
  if (typeof AudioWorkletNode === "undefined") return false;
  return typeof navigator.mediaDevices?.getUserMedia === "function";
};

/**
 * 8 kHz 单声道样本的 RMS 音量
 * @param pcm - 8 kHz 单声道样本
 * @returns 均方根音量
 */
const rms = (pcm: Float32Array): number => {
  let energy = 0;
  for (let i = 0; i < pcm.length; i++) {
    energy += pcm[i] * pcm[i];
  }
  return Math.sqrt(energy / Math.max(1, pcm.length));
};

/**
 * 弱信号增益归一化：手机外放距离远时信号偏弱，AFP 峰值拾取会失效
 * @param pcm - 8 kHz 单声道样本
 * @returns 归一化后的样本
 */
const normalizeLevel = (pcm: Float32Array): Float32Array => {
  const current = rms(pcm);
  if (current <= 0) return pcm;
  const gain = Math.min(MAX_GAIN, TARGET_RMS / current);
  if (gain <= 1) return pcm;
  const out = new Float32Array(pcm.length);
  for (let i = 0; i < pcm.length; i++) {
    out[i] = pcm[i] * gain;
  }
  return out;
};

/**
 * 采集时长收敛到可用区间
 * @param value - 调用方传入的毫秒数
 * @returns 裁剪后的毫秒数
 */
const clampDuration = (value: unknown): number => {
  const ms = Number(value);
  if (!Number.isFinite(ms) || ms <= 0) return DEFAULT_DURATION_MS;
  return Math.min(MAX_DURATION_MS, Math.max(MIN_DURATION_MS, Math.round(ms)));
};

/**
 * 把采集异常映射为共享错误码
 * @param error - getUserMedia 或 AudioWorklet 抛出的异常
 * @returns 共享错误码
 */
const mapCaptureError = (error: unknown): RecognitionErrorCode => {
  const name =
    typeof error === "object" && error !== null && "name" in error
      ? String((error as { name: unknown }).name)
      : "";
  if (
    name === "NotAllowedError" ||
    name === "PermissionDeniedError" ||
    name === "SecurityError"
  ) {
    return "permission-denied";
  }
  if (name === "NotFoundError" || name === "DevicesNotFoundError") return "no-device";
  return "capture-failed";
};

/**
 * 异常的可读描述
 * @param error - 任意异常
 * @returns 文案
 */
const describeError = (error: unknown): string =>
  error instanceof Error ? error.message : String(error);

/** 指纹库模块缓存，359 KB 的 Emscripten 产物只在识别时才进内存 */
let afpModulePromise: Promise<AfpModule | null> | null = null;

/**
 * 惰性加载 AFP 指纹库
 * @returns 可用的指纹模块，加载失败时为 null
 */
const loadAfp = (): Promise<AfpModule | null> => {
  if (!afpModulePromise) {
    afpModulePromise = import("../../../../resources/afp/afp.mjs")
      .then((mod) => (typeof mod.GenerateFP === "function" ? (mod as AfpModule) : null))
      .catch((error: unknown) => {
        console.error("[recognition] AFP 指纹库加载失败:", error);
        return null;
      });
  }
  return afpModulePromise;
};

/**
 * 给可能永不 settle 的任务加超时
 * @param task - 被等待的任务
 * @param ms - 超时毫秒
 * @param message - 超时错误文案
 * @returns 任务结果
 */
const withTimeout = <T>(task: Promise<T>, ms: number, message: string): Promise<T> =>
  new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(message)), ms);
    task.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error: unknown) => {
        clearTimeout(timer);
        reject(error instanceof Error ? error : new Error(String(error)));
      },
    );
  });

/**
 * 计算 AFP 指纹
 * @param pcm - 8 kHz 单声道样本
 * @returns base64 指纹或失败说明
 */
const fingerprintPcm = async (pcm: Float32Array): Promise<FingerprintResult> => {
  const mod = await loadAfp();
  if (!mod) return { ok: false, message: "音频指纹库不可用" };
  try {
    const fingerprint = await withTimeout(
      mod.GenerateFP(pcm),
      FINGERPRINT_TIMEOUT_MS,
      "指纹计算超时",
    );
    if (!fingerprint) return { ok: false, message: "指纹结果为空" };
    return { ok: true, fingerprint };
  } catch (error) {
    return { ok: false, message: describeError(error) };
  }
};

/**
 * 把指纹交给网易云 audio_match
 * @param fingerprint - AFP 指纹
 * @param durationSec - 片段时长（秒）
 * @returns 最多三个候选或失败说明
 */
const matchAudio = async (fingerprint: string, durationSec: number): Promise<MatchResult> => {
  try {
    const response = await neteaseCall("audio_match", {
      audioFP: fingerprint,
      duration: durationSec,
    });
    if (!response.ok) return { ok: false, message: response.error ?? "音频匹配请求失败" };
    const body = response.body as MatchBody | null;
    if (body?.code !== 200) {
      return { ok: false, message: `音频匹配接口错误: code=${body?.code}` };
    }
    const songs = (body.data?.result ?? [])
      .filter((item): item is { startTime?: number; song: MatchedSong } => !!item.song)
      .slice(0, 3)
      .map((item) => ({ song: item.song, startTime: item.startTime }));
    return { ok: true, songs };
  } catch (error) {
    return { ok: false, message: describeError(error) };
  }
};

/**
 * 识别一段 8 kHz 单声道 PCM：按 3 秒窗口 / 1 秒步长顺序匹配，首个有候选的窗口即停止
 * @param pcm - 8 kHz 单声道样本
 * @param token - 会话令牌，与当前值不一致时放弃后续步骤
 */
const recognizePcm = async (pcm: Float32Array, token: number): Promise<void> => {
  const normalized = normalizeLevel(pcm);
  if (rms(normalized) < SILENCE_RMS_THRESHOLD) {
    emitError("silent-input", "没有采集到声音，请靠近声源并确认媒体音量");
    return;
  }
  emit({ phase: "fingerprinting" });
  const windows: Array<{ start: number; pcm: Float32Array }> = [];
  for (let start = 0; start + WINDOW_SAMPLES <= normalized.length; start += STEP_SAMPLES) {
    windows.push({ start, pcm: normalized.subarray(start, start + WINDOW_SAMPLES) });
  }
  if (windows.length === 0) windows.push({ start: 0, pcm: normalized });

  let candidates: RecognitionCandidate[] = [];
  for (const window of windows) {
    if (token !== sessionToken) return;
    const fingerprint = await fingerprintPcm(window.pcm);
    if (token !== sessionToken) return;
    if (!fingerprint.ok) {
      emitError("afp-unavailable", fingerprint.message);
      return;
    }
    emit({ phase: "matching" });
    const match = await matchAudio(fingerprint.fingerprint, WINDOW_SAMPLES / SAMPLE_RATE);
    if (token !== sessionToken) return;
    if (!match.ok) {
      emitError("network", match.message);
      return;
    }
    if (match.songs.length === 0) continue;
    candidates = match.songs.map((item) => ({
      songId: String(item.song.id),
      title: item.song.name,
      artists: (item.song.artists ?? []).map((artist) => artist.name),
      album: item.song.album?.name,
      cover: item.song.album?.picUrl,
      startTime: (item.startTime ?? 0) + window.start / SAMPLE_RATE,
    }));
    break;
  }
  console.info(`[recognition] 识别完成，候选 ${candidates.length} 个`);
  // 空候选即 noMatch，沿用桌面的 done + 空列表表达，事件字段不变
  emit({ phase: "done", candidates });
};

/**
 * 取消当前识别
 */
export const cancelRecognition = (): void => {
  sessionToken++;
  activeAbort?.abort();
  activeAbort = null;
  activeCapture?.close();
  activeCapture = null;
};

/**
 * 启动一次识别
 *
 * 安卓没有系统回采，config.source 是 system 还是 microphone 都走麦克风
 * @param config - 采集来源与时长
 * @returns 采集与识别会话是否成功建立
 */
export const startRecognition = async (config: RecognitionConfig): Promise<boolean> => {
  cancelRecognition();
  if (!isRecognitionSupported()) {
    emitError("unsupported", "当前 WebView 不支持麦克风采集");
    return false;
  }
  const token = ++sessionToken;
  const controller = new AbortController();
  activeAbort = controller;
  emit({ phase: "capturing", level: 0 });
  try {
    const handle = await captureMicrophone((level) => {
      if (token === sessionToken) emit({ phase: "capturing", level });
    }, controller.signal);
    if (token !== sessionToken) {
      handle.close();
      return false;
    }
    activeCapture = handle;
    await waitCapture(clampDuration(config.durationMs), controller.signal);
    if (token !== sessionToken) return false;
    const pcm = await handle.stop();
    handle.close();
    activeCapture = null;
    activeAbort = null;
    if (pcm.length === 0) {
      emitError("capture-failed", "麦克风没有返回音频数据");
      return false;
    }
    void recognizePcm(pcm, token);
    return true;
  } catch (error) {
    activeAbort = null;
    activeCapture?.close();
    activeCapture = null;
    if (token !== sessionToken) return false;
    emitError(mapCaptureError(error), describeError(error));
    return false;
  }
};

/**
 * 识别渲染进程自行采集后提交的 PCM
 * @param pcm - 8 kHz 单声道样本
 * @returns 是否受理
 */
export const submitPcm = (pcm: unknown): boolean => {
  if (!(pcm instanceof Float32Array) || pcm.length === 0) {
    emitError("capture-failed", "渲染进程提交了无效的 PCM");
    return false;
  }
  cancelRecognition();
  const token = sessionToken;
  emit({ phase: "capturing", level: 0 });
  void recognizePcm(pcm, token);
  return true;
};
