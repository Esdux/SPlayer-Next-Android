import type { IpcRendererEvent } from "electron";

/** 原生调用超时，避免前端永久挂起 */
const NATIVE_TIMEOUT_MS = 30_000;

/** 需要用户手动操作的原生调用（如目录选择）超时更宽松 */
const INTERACTIVE_TIMEOUT_MS = 180_000;

/** 需要等用户操作完成的 channel */
const INTERACTIVE_CHANNELS = new Set([
  "library:addScanDir",
  "library:pickCoverImage",
  "cloud:pickSongs",
  "download:pickDir",
  "cache:pickDir",
  "lyrics:pickLyricRepoDir",
  "theme:pickBackgroundImage",
  "system:saveFile",
  "config:exportToFile",
  "config:importFromFile",
]);

/**
 * 判断 channel 是否按交互超时等待
 * @param channel - IPC channel 名
 * @returns 需要更宽超时为真
 */
const isInteractiveChannel = (channel: string): boolean =>
  INTERACTIVE_CHANNELS.has(channel) || channel.startsWith("picker:");

type Pending = {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timer: number;
};

const pending = new Map<string, Pending>();
const listeners = new Map<string, Set<(event: IpcRendererEvent, data: unknown) => void>>();
let callSeq = 0;

/**
 * 判断当前是否运行在安卓壳内
 * @returns 存在原生桥时为真
 */
export const isAndroidHost = (): boolean =>
  typeof window !== "undefined" && typeof (window as unknown as { AndroidHost?: unknown }).AndroidHost === "object";

/**
 * 二进制与普通对象的安全编码，ArrayBuffer 走 base64 以免 JSON 丢字节
 * @param value - 任意参数值
 * @returns 可 JSON 序列化的结构
 */
const encode = (value: unknown, seen = new WeakSet<object>(), depth = 0): unknown => {
  if (value instanceof ArrayBuffer) return { __t: "ab", b64: bytesToBase64(new Uint8Array(value)) };
  if (ArrayBuffer.isView(value)) {
    const view = value as ArrayBufferView;
    return { __t: "ab", b64: bytesToBase64(new Uint8Array(view.buffer, view.byteOffset, view.byteLength)) };
  }
  if (typeof value === "function" || typeof value === "symbol") return undefined;
  if (value === null || typeof value !== "object") return value;
  // 循环引用与过深结构直接截断，否则递归展开会爆栈
  if (depth > 32 || seen.has(value as object)) return undefined;
  seen.add(value as object);
  if (Array.isArray(value)) return value.map((item) => encode(item, seen, depth + 1));
  if ((value as object).constructor === Object) {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      const encoded = encode(v, seen, depth + 1);
      if (encoded !== undefined) out[k] = encoded;
    }
    return out;
  }
  return value;
};

/**
 * 字节转 base64
 * @param bytes - 原始字节
 * @returns base64 文本
 */
const bytesToBase64 = (bytes: Uint8Array): string => {
  let out = "";
  // 分块必须足够小：一次性 spread 三万个参数会直接触发 Maximum call stack size exceeded
  const chunk = 0x1000;
  for (let i = 0; i < bytes.length; i += chunk) {
    const slice = bytes.subarray(i, i + chunk);
    out += String.fromCharCode.apply(null, slice as unknown as number[]);
  }
  return btoa(out);
};

/**
 * 原生结果统一信封解码
 * @param raw - 原生返回的 JSON 文本
 * @returns 解包后的业务数据
 */
const decodeEnvelope = (raw: string): unknown => {
  const envelope = JSON.parse(raw) as { ok: boolean; data?: unknown; error?: string };
  if (!envelope.ok) throw new Error(envelope.error ?? "native call failed");
  return envelope.data;
};

/**
 * 调用原生 channel
 * @param channel - IPC channel 名
 * @param args - 位置参数
 * @returns 原生返回的业务数据
 */
const callNative = (channel: string, args: unknown[]): Promise<unknown> => {
  const host = (window as unknown as { AndroidHost: { invokeAsync: (id: string, channel: string, payload: string) => void } }).AndroidHost;
  const callId = `c${Date.now().toString(36)}${(++callSeq).toString(36)}`;
  return new Promise((resolve, reject) => {
    const timer = window.setTimeout(() => {
      pending.delete(callId);
      reject(new Error(`native channel timeout: ${channel}`));
    }, isInteractiveChannel(channel) ? INTERACTIVE_TIMEOUT_MS : NATIVE_TIMEOUT_MS);
    pending.set(callId, { resolve, reject, timer });
    try {
      host.invokeAsync(callId, channel, JSON.stringify(encode(args)));
    } catch (error) {
      window.clearTimeout(timer);
      pending.delete(callId);
      reject(error instanceof Error ? error : new Error(String(error)));
    }
  });
};

/**
 * 直接调用原生，跳过本地 handler 表
 *
 * 转发型的 handler（如 window:minimize -> 原生 window:minimize）必须走这里，
 * 否则 ipcRenderer.invoke 会再次命中同名 handler，形成无限递归。
 * @param channel - IPC channel 名
 * @param args - 位置参数
 * @returns 原生返回的业务数据
 */
export const invokeNative = (channel: string, ...args: unknown[]): Promise<unknown> =>
  callNative(channel, args);

/**
 * 原生异步结果回投入口，供 Kotlin 侧 evaluateJavascript 调用
 * @param callId - 调用标识
 * @param raw - 结果 JSON
 */
/** 是否已经绑定过 postWebMessage 的 message 监听 */
let messageBound = false;

/**
 * 安装原生回调通道：同步 reply 与高频事件
 */
export const installReplyChannel = (): void => {
  (window as unknown as Record<string, unknown>).__androidReply = (callId: string, raw: string): void => {
    const entry = pending.get(callId);
    if (!entry) return;
    pending.delete(callId);
    window.clearTimeout(entry.timer);
    try {
      entry.resolve(decodeEnvelope(raw));
    } catch (error) {
      entry.reject(error instanceof Error ? error : new Error(String(error)));
    }
  };
  /**
   * 把一条原生事件分发给订阅者
   * @param channel - 事件 channel
   * @param raw - 事件数据 JSON 文本
   */
  const dispatchNativeEvent = (channel: string, raw: string): void => {
    let data: unknown = null;
    try {
      data = JSON.parse(raw);
    } catch {
      data = raw;
    }
    const set = listeners.get(channel);
    if (!set) return;
    for (const handler of [...set]) handler({} as IpcRendererEvent, data);
  };

  (window as unknown as Record<string, unknown>).__androidEvent = dispatchNativeEvent;

  // 原生高频推送走 postWebMessage，避免 evaluateJavascript 的脚本拼接与转义
  if (!messageBound) {
    messageBound = true;
    window.addEventListener("message", (event) => {
      if (event.origin !== "https://appassets.androidplatform.net") return;
      if (typeof event.data !== "string") return;
      try {
        const parsed = JSON.parse(event.data) as { channel?: string; data?: unknown };
        if (!parsed.channel) return;
        dispatchNativeEvent(String(parsed.channel), String(parsed.data ?? ""));
      } catch {
        // 非 JSON 消息直接忽略
      }
    });
  }
};

/**
 * 本地 JS handler 表，移植后的业务逻辑注册在此，未命中的 channel 转原生
 */
const localHandlers = new Map<string, (...args: unknown[]) => unknown>();

/**
 * 注册 JS 侧 channel 实现
 * @param channel - IPC channel 名
 * @param handler - 处理函数
 */
export const registerHandler = (channel: string, handler: (...args: unknown[]) => unknown): void => {
  localHandlers.set(channel, handler);
};

/**
 * 订阅原生推送
 * @param channel - 事件 channel 名
 * @param handler - 回调
 * @returns 解绑函数
 */
/**
 * 订阅原生推送事件（不经过 ipcRenderer 封装）
 * @param channel - 事件通道
 * @param handler - 回调
 * @returns 解绑函数
 */
export const onNativeEvent = (
  channel: string,
  handler: (data: unknown) => void,
): (() => void) => addListener(channel, (_event, data) => handler(data));

const addListener = (channel: string, handler: (event: IpcRendererEvent, data: unknown) => void): (() => void) => {
  const set = listeners.get(channel) ?? new Set();
  set.add(handler);
  listeners.set(channel, set);
  return () => set.delete(handler);
};

/**
 * ipcRenderer 兼容实现，行为与 Electron 对齐
 */
export const ipcRenderer = {
  /**
   * 请求-响应调用，先查本地实现再转原生
   * @param channel - IPC channel 名
   * @param args - 位置参数
   * @returns 处理结果
   */
  invoke: async (channel: string, ...args: unknown[]): Promise<unknown> => {
    const local = localHandlers.get(channel);
    if (local) return await local(...args);
    return await callNative(channel, args);
  },
  /**
   * 单向消息，JS 侧只记录不阻塞
   * @param channel - IPC channel 名
   * @param args - 位置参数
   */
  send: (channel: string, ...args: unknown[]): void => {
    const local = localHandlers.get(channel);
    if (local) {
      void local(...args);
      return;
    }
    void callNative(channel, args).catch(() => undefined);
  },
  on: (channel: string, handler: (event: IpcRendererEvent, data: unknown) => void): void => {
    addListener(channel, handler);
  },
  once: (channel: string, handler: (event: IpcRendererEvent, data: unknown) => void): void => {
    const off = addListener(channel, (event, data) => {
      off();
      handler(event, data);
    });
  },
  removeListener: (channel: string, handler: (event: IpcRendererEvent, data: unknown) => void): void => {
    listeners.get(channel)?.delete(handler);
  },
  removeAllListeners: (channel: string): void => {
    listeners.delete(channel);
  },
};

/**
 * webUtils 兼容实现，安卓没有本地绝对路径，交给 SAF 流程处理
 */
export const webUtils = {
  /**
   * 取得文件路径
   * @param file - 浏览器 File 对象
   * @returns 安卓侧固定返回空串
   */
  getPathForFile: (_file: File): string => "",
};

/**
 * os 兼容实现，用于 system.osInfo
 */
export const os = {
  type: (): string => "Android",
  arch: (): string => (navigator.userAgent.includes("arm64") ? "arm64" : "arm"),
  release: (): string => String((window as unknown as { __androidApiLevel?: number }).__androidApiLevel ?? ""),
};

/**
 * contextBridge 兼容实现，安卓侧直接挂到 window
 */
export const contextBridge = {
  /**
   * 挂载对象到全局
   * @param key - 全局键名
   * @param value - 挂载对象
   */
  exposeInMainWorld: (key: string, value: unknown): void => {
    (window as unknown as Record<string, unknown>)[key] = value;
  },
};

/**
 * electronAPI 兼容实现，安卓侧仅保留最小可用面
 */
export const electronAPI = {
  ipcRenderer,
  process: { platform: "android", versions: {} },
};

/**
 * 浏览器侧的 process 垫片，preload 代码会读取 platform / execPath
 */
export const processShim = {
  platform: "android",
  env: {} as Record<string, string>,
  execPath: "",
  contextIsolated: false,
  versions: {} as Record<string, string>,
};

if (typeof globalThis !== "undefined" && !(globalThis as { process?: unknown }).process) {
  (globalThis as Record<string, unknown>).process = processShim;
}