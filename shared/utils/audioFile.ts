/** 本地播放支持的音频文件后缀（含点，小写） */
export const SUPPORTED_AUDIO_EXTENSIONS = new Set([
  ".mp3",
  ".flac",
  ".wav",
  ".ogg",
  ".oga",
  ".m4a",
  ".aac",
  ".opus",
  ".wma",
  ".ape",
  ".alac",
  ".dsf",
  ".dff",
  ".m4b",
]);

/**
 * 判断文件路径是否为支持的音频格式
 * @param filePath - 文件路径
 */
export const isAudioFile = (filePath: string): boolean => {
  // 安卓侧由系统按 audio/* 过滤后下发 content:// 或 file:// 地址，本身不带后缀
  if (filePath.startsWith("content://") || filePath.startsWith("file://")) return true;
  const dotIndex = filePath.lastIndexOf(".");
  if (dotIndex === -1) return false;
  return SUPPORTED_AUDIO_EXTENSIONS.has(filePath.slice(dotIndex).toLowerCase());
};
