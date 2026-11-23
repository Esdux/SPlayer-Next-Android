import { ErrorCode } from "@shared/types/errors";
import { invokeNative, registerHandler } from "../electron-shim";

/** picker 内部通道，和 MainActivity 的 launcher 一一对应 */
type PickerChannel =
  | "picker:openFile"
  | "picker:openFiles"
  | "picker:openTree"
  | "picker:createFile"
  | "picker:release";

/** 文档基础信息，对应 files:stat */
export interface DocumentStat {
  /** content:// uri */
  path: string;
  /** 显示名 */
  name: string;
  /** 字节数 */
  size: number;
  /** MIME 类型 */
  mimeType: string;
  /** 是否为目录 */
  isDirectory: boolean;
}

/** 缩略图结果，base64 为 JPEG 编码后的内容 */
export interface DocumentThumbnail {
  /** 固定为 image/jpeg */
  mime: string;
  /** 无换行 base64 */
  base64: string;
  /** 缩略图宽度 */
  width: number;
  /** 缩略图高度 */
  height: number;
}

/** 背景图在 WebView 里的虚拟地址前缀，MainActivity 的 /bg/ 处理器指向 cache/backgrounds */
const BACKGROUND_URL_PREFIX = "https://appassets.androidplatform.net/bg/";

/** 背景图缓存桶名 */
const BACKGROUND_BUCKET = "backgrounds";

/** 封面预览缩略图长边 */
const COVER_THUMB_SIZE = 300;

/** 保存对话框按扩展名挑 MIME */
const MIME_BY_EXT: Record<string, string> = {
  mp3: "audio/mpeg",
  flac: "audio/flac",
  wav: "audio/wav",
  m4a: "audio/mp4",
  aac: "audio/aac",
  ogg: "audio/ogg",
  jpg: "image/jpeg",
  jpeg: "image/jpeg",
  png: "image/png",
  webp: "image/webp",
  gif: "image/gif",
  bmp: "image/bmp",
  json: "application/json",
  txt: "text/plain",
  lrc: "text/plain",
  ttml: "application/ttml+xml",
};

/**
 * 调用 picker 内部通道
 * @param channel - picker 内部通道
 * @param payload - 选择器配置
 * @returns 原生回投的数据
 */
const callPicker = async <T>(
  channel: PickerChannel,
  payload: Record<string, unknown>,
): Promise<T> => (await invokeNative(channel, payload)) as T;

/**
 * 调用文件读写通道
 * @param channel - files 通道
 * @param args - 位置参数
 * @returns 原生回投的数据
 */
const callFiles = async <T>(channel: string, ...args: unknown[]): Promise<T> =>
  (await invokeNative(channel, ...args)) as T;

/**
 * 去掉文件名里的路径分隔符与非法字符
 * @param name - 原始文件名
 * @returns 可交给 SAF 的安全文件名
 */
const sanitizeName = (name: string): string =>
  name
    .replace(/[\\/:*?"<>|]/g, " ")
    .replace(/\s+/g, " ")
    .trim();

/**
 * 按扩展名推断 MIME
 * @param name - 文件名
 * @returns MIME 类型，未知时按二进制处理
 */
const mimeOfName = (name: string): string => {
  const ext = name.split(".").pop()?.toLowerCase() ?? "";
  return MIME_BY_EXT[ext] ?? "application/octet-stream";
};

/**
 * 打开系统文件选择器，单选
 * @param mimeTypes - 允许的 MIME 类型
 * @param title - 选择器标题，SAF 不展示，仅用于日志排查
 * @returns 选中文件的 content:// uri，取消为 null
 */
export const pickFile = (mimeTypes: string[], title?: string): Promise<string | null> =>
  callPicker<string | null>("picker:openFile", { mimeTypes, title });

/**
 * 打开系统文件选择器，多选
 * @param mimeTypes - 允许的 MIME 类型
 * @param title - 选择器标题，SAF 不展示
 * @returns 选中文件的 uri 列表，取消为 null
 */
export const pickFiles = (mimeTypes: string[], title?: string): Promise<string[] | null> =>
  callPicker<string[] | null>("picker:openFiles", { mimeTypes, title });

/**
 * 打开目录选择器
 * @param persistable - 是否申请持久授权
 * @returns 目录 tree uri，取消为 null
 */
export const pickTree = (persistable = true): Promise<string | null> =>
  callPicker<string | null>("picker:openTree", { persistable });

/**
 * 打开保存对话框新建文档
 * @param mimeType - 文档 MIME
 * @param fileName - 默认文件名
 * @param title - 保存对话框标题，SAF 不展示
 * @returns 新建文档的 uri，取消为 null
 */
export const createDocument = (
  mimeType: string,
  fileName: string,
  title?: string,
): Promise<string | null> =>
  callPicker<string | null>("picker:createFile", { mimeType, fileName, title });

/**
 * 释放目录或文档的持久授权
 * @param uri - 已授权的 uri
 */
export const releaseDocument = (uri: string): Promise<void> =>
  callPicker<void>("picker:release", { uri });

/**
 * 读取文档基础信息
 * @param uri - 文档 uri
 * @returns 显示名、大小与目录标记
 */
export const fileStat = (uri: string): Promise<DocumentStat> =>
  callFiles<DocumentStat>("files:stat", uri);

/**
 * 读取文本
 * @param uri - 文档 uri
 * @returns UTF-8 文本
 */
export const readText = (uri: string): Promise<string> => callFiles<string>("files:readText", uri);

/**
 * 读取二进制并取 base64
 * @param uri - 文档 uri
 * @returns 无换行 base64
 */
export const readBase64 = (uri: string): Promise<string> =>
  callFiles<string>("files:readBase64", uri);

/**
 * 覆盖写入文本
 * @param uri - 文档 uri
 * @param text - 文本内容
 */
export const writeText = (uri: string, text: string): Promise<void> =>
  callFiles<void>("files:writeText", uri, text);

/**
 * 覆盖写入二进制
 * @param uri - 文档 uri
 * @param data - base64 文本，或 electron-shim 编出的 {__t:"ab", b64}，由原生侧解包
 */
export const writeBase64 = (uri: string, data: unknown): Promise<void> =>
  callFiles<void>("files:writeBase64", uri, data);

/**
 * 生成 JPEG 缩略图
 * @param uri - 图片 uri
 * @param size - 目标最长边像素
 * @returns 缩略图 base64 与尺寸
 */
export const thumbnailBase64 = (uri: string, size = COVER_THUMB_SIZE): Promise<DocumentThumbnail> =>
  callFiles<DocumentThumbnail>("files:thumbnailBase64", uri, size);

/**
 * 把文档复制进应用缓存桶
 * @param uri - 源文档 uri
 * @param bucket - 桶名
 * @returns 落盘文件名
 */
export const copyToBucket = (uri: string, bucket: string): Promise<string> =>
  callFiles<string>("files:copyToBucket", uri, bucket);

/**
 * 清空应用缓存桶
 * @param bucket - 桶名
 */
export const clearBucket = (bucket: string): Promise<void> =>
  callFiles<void>("files:clearBucket", bucket);

/**
 * 选择封面图并生成预览，形状对齐桌面 library:pickCoverImage
 * @returns 成功带 path 与 dataUrl，取消带 FILE_NOT_SELECTED
 */
export const pickCoverImage = async (): Promise<{
  success: boolean;
  data?: { path: string; dataUrl: string };
  error?: string;
}> => {
  const uri = await pickFile(["image/jpeg", "image/png"], "选择封面图片");
  if (!uri) return { success: false, error: ErrorCode.FILE_NOT_SELECTED };
  try {
    const thumb = await thumbnailBase64(uri, COVER_THUMB_SIZE);
    if (!thumb.base64) return { success: false, error: ErrorCode.TAG_WRITE_FAILED };
    return {
      success: true,
      data: { path: uri, dataUrl: `data:${thumb.mime};base64,${thumb.base64}` },
    };
  } catch {
    return { success: false, error: ErrorCode.TAG_WRITE_FAILED };
  }
};

/**
 * 保存任意字节到用户选择的位置，形状对齐桌面 system:saveFile
 * @param data - 二进制内容，ArrayBuffer 会被 electron-shim 编成 base64 信封
 * @param defaultName - 默认文件名
 * @param title - 保存对话框标题，SAF 不展示
 * @returns 保存结果，取消时 canceled 为真
 */
export const saveFileData = async (
  data: unknown,
  defaultName: string,
  title: string,
): Promise<{ success: boolean; path?: string; canceled?: boolean; error?: string }> => {
  const name = sanitizeName(defaultName) || "splayer-file";
  const uri = await createDocument(mimeOfName(name), name, title);
  if (!uri) return { success: false, canceled: true };
  try {
    await writeBase64(uri, data);
    return { success: true, path: uri };
  } catch (error) {
    return { success: false, error: error instanceof Error ? error.message : String(error) };
  }
};

/**
 * 导出配置到用户选择的位置
 * @param payload - 配置对象
 * @returns 形状对齐桌面 config:exportToFile
 */
export const exportConfigToFile = async (
  payload: unknown,
): Promise<{ ok: boolean; reason?: "canceled" | "writeFailed" }> => {
  const uri = await createDocument("application/json", "splayer-config.json", "导出配置");
  if (!uri) return { ok: false, reason: "canceled" };
  try {
    await writeText(uri, JSON.stringify(payload ?? null, null, 2));
    return { ok: true };
  } catch {
    return { ok: false, reason: "writeFailed" };
  }
};

/**
 * 从用户选择的文件导入配置
 * @returns 解析后的配置，或带原因标记的失败
 */
export const importConfigFromFile = async (): Promise<
  { ok: true; data: unknown } | { ok: false; reason: "canceled" | "readFailed" | "parseFailed" }
> => {
  const uri = await pickFile(["application/json", "text/plain"], "导入配置");
  if (!uri) return { ok: false, reason: "canceled" };
  let text: string;
  try {
    text = await readText(uri);
  } catch {
    return { ok: false, reason: "readFailed" };
  }
  void releaseDocument(uri).catch(() => undefined);
  try {
    return { ok: true, data: JSON.parse(text) as unknown };
  } catch {
    return { ok: false, reason: "parseFailed" };
  }
};

/**
 * 选择待上传的歌曲
 * @returns 形状对齐桌面 cloud:pickSongs，取消为空数组
 */
export const pickSongsForUpload = async (): Promise<
  { path: string; name: string; size: number }[]
> => {
  const uris = await pickFiles(["audio/*"], "选择要上传的歌曲");
  if (!uris?.length) return [];
  const songs: { path: string; name: string; size: number }[] = [];
  for (const uri of uris) {
    try {
      const info = await fileStat(uri);
      songs.push({ path: uri, name: info.name, size: info.size });
    } catch {
      // 单个文件信息读不到就跳过，不影响其余选择
    }
  }
  return songs;
};

/**
 * 选择本地歌词库目录
 * @returns 目录 tree uri，取消为 null
 */
export const pickLyricRepoDir = (): Promise<string | null> => pickTree(true);

/** 受管目录类型 */
type ManagedDirKind = "download" | "cache";

/** 受管目录在配置里的存点 */
const DIR_URI_PATH: Record<ManagedDirKind, string> = {
  download: "system.download.dirUri",
  cache: "system.cache.dirUri",
};

/** 未选择时的默认展示文案 */
const DEFAULT_DIR_LABEL: Record<ManagedDirKind, string> = {
  download: "应用私有目录",
  cache: "应用私有缓存",
};

/**
 * 把 SAF tree uri 转成可读目录名
 * @param uri - tree uri
 * @returns 目录末段展示名
 */
const treeDisplayName = (uri: string): string => {
  const match = /\/tree\/([^/]+)$/.exec(uri);
  const raw = match ? decodeURIComponent(match[1]) : uri;
  return raw.startsWith("primary:") ? "/" + raw.slice(8) : raw;
};

/**
 * 读取当前生效的受管目录
 * @param kind - download 或 cache
 * @returns 目录展示名
 */
export const getManagedDir = async (kind: ManagedDirKind): Promise<string> => {
  const saved = (await window.api.config.get(DIR_URI_PATH[kind])) as string | null;
  return saved ? treeDisplayName(saved) : DEFAULT_DIR_LABEL[kind];
};

/**
 * 选择目录并把持久授权写进配置
 * @param kind - download 或 cache
 * @returns 与桌面同形的结果，选中后 ok 为真且带目录名
 */
export const pickManagedDir = async (
  kind: ManagedDirKind,
): Promise<{ ok: boolean; dir: string; reason?: "canceled" }> => {
  const uri = await pickTree(true);
  if (!uri) return { ok: false, dir: await getManagedDir(kind), reason: "canceled" };
  await window.api.config.set(DIR_URI_PATH[kind], uri);
  return { ok: true, dir: treeDisplayName(uri) };
};

/**
 * 清除已选目录并回落到默认目录
 * @param kind - download 或 cache
 * @returns 回落后的目录展示名
 */
export const resetManagedDir = async (kind: ManagedDirKind): Promise<string> => {
  await window.api.config.set(DIR_URI_PATH[kind], null);
  return getManagedDir(kind);
};

/**
 * 选择背景图并复制进缓存桶，返回 WebView 可加载的地址
 * @returns 背景图 URL，取消或失败为 null
 */
export const pickBackgroundImage = async (): Promise<string | null> => {
  const uri = await pickFile(["image/*"], "选择背景图片");
  if (!uri) return null;
  try {
    await clearBucket(BACKGROUND_BUCKET);
    const name = await copyToBucket(uri, BACKGROUND_BUCKET);
    return name ? BACKGROUND_URL_PREFIX + name : null;
  } catch {
    return null;
  }
};

/**
 * 清空已缓存的背景图
 */
export const clearBackgroundImages = (): Promise<void> => clearBucket(BACKGROUND_BUCKET);

/** 原生下载引擎通道前缀；与本地 handler 同名会形成递归调用，因此统一加 engine 段 */
const DOWNLOAD_ENGINE_PREFIX = "download:engine:";

/**
 * 调用原生下载引擎
 * @param action - engine 段动作名，如 start / list
 * @param payload - 原生侧按位置参数读取的对象
 * @returns 原生返回的业务数据
 */
const callDownloadEngine = <T>(action: string, payload: Record<string, unknown>): Promise<T> =>
  invokeNative(DOWNLOAD_ENGINE_PREFIX + action, payload) as Promise<T>;

/**
 * 读取已选下载目录的 tree uri
 * @returns SAF tree uri，未选择时为 null
 */
const downloadTreeUri = async (): Promise<string | null> => {
  const saved = (await window.api.config.get("system.download.dirUri")) as string | null;
  return saved?.startsWith("content://") ? saved : null;
};

/**
 * 注册 picker 与文件通道，必须排在 stubs 之后以覆盖同名占位实现
 */
export const registerPickersHandlers = (): void => {
  registerHandler("library:pickCoverImage", () => pickCoverImage());
  registerHandler("cloud:pickSongs", () => pickSongsForUpload());
  registerHandler("lyrics:pickLyricRepoDir", () => pickLyricRepoDir());
  registerHandler("download:pickDir", () => pickManagedDir("download"));
  registerHandler("download:getDir", () => getManagedDir("download"));
  registerHandler("download:resetDir", () => resetManagedDir("download"));
  // 下载任务交给原生引擎，目录授权随请求一起下发，解析地址由原生经 download:resolve 索要
  registerHandler("download:start", async (req) =>
    callDownloadEngine("start", { req, dirUri: await downloadTreeUri() }),
  );
  registerHandler("download:startMany", async (reqs) =>
    callDownloadEngine("startMany", {
      reqs: Array.isArray(reqs) ? reqs : [],
      dirUri: await downloadTreeUri(),
    }),
  );
  // 重试与桌面一致：复用 taskId 重新入队
  registerHandler("download:retry", async (req) =>
    callDownloadEngine("start", { req, dirUri: await downloadTreeUri() }),
  );
  registerHandler("download:list", () => callDownloadEngine("list", {}));
  registerHandler("download:cancel", async (taskId) => {
    await callDownloadEngine("cancel", { taskId });
  });
  registerHandler("download:remove", async (taskId) => {
    await callDownloadEngine("remove", { taskId });
  });
  registerHandler("download:clearFinished", async () => {
    await callDownloadEngine("clearFinished", {});
  });
  registerHandler("download:lookup", async (track) => {
    const path = await callDownloadEngine<string | null>("lookup", { track });
    return path ?? null;
  });
  registerHandler("download:resolution", (taskId, res) =>
    callDownloadEngine("resolution", { taskId, res }),
  );
  registerHandler("download:resolveFailed", async (taskId) => {
    await callDownloadEngine("resolveFailed", { taskId });
  });
  registerHandler("cache:pickDir", () => pickManagedDir("cache"));
  registerHandler("cache:getDir", () => getManagedDir("cache"));
  registerHandler("cache:resetDir", () => resetManagedDir("cache"));
};
