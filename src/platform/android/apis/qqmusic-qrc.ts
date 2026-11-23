/**
 * QQ 音乐 QRC 歌词解密
 *
 * 服务端 crypt=1 时歌词字段是 hex 密文：3DES-EDE/ECB 无填充，解出后是 zlib 包装的 deflate
 */
import { qrcDecrypt } from "./qqmusic-tripledes";

/** QQ 歌词固定密钥，24 字节 */
const QRC_KEY = new TextEncoder().encode("!@#)(*$%123ZXC!@!@#)(NHL");

/**
 * hex 字符串转字节
 * @param hex - 十六进制文本
 * @returns 原始字节
 */
const hexToBytes = (hex: string): Uint8Array => {
  const clean = hex.trim();
  if (!clean || (clean.length & 1) || /[^0-9a-f]/i.test(clean)) {
    throw new Error("QQ 歌词不是合法的十六进制密文");
  }
  const output = new Uint8Array(clean.length >>> 1);
  for (let i = 0; i < output.length; i += 1) {
    output[i] = Number.parseInt(clean.slice(i * 2, i * 2 + 2), 16);
  }
  return output;
};

/**
 * 用指定封装解压
 * @param format - DecompressionStream 支持的格式
 * @param input - 压缩字节
 * @returns 解压后的字节
 */
const decompress = async (
  format: "deflate" | "deflate-raw" | "gzip",
  input: Uint8Array,
): Promise<Uint8Array> => {
  if (typeof DecompressionStream !== "function") throw new Error("当前环境不支持 DecompressionStream");
  const bytes = input.buffer.slice(input.byteOffset, input.byteOffset + input.byteLength);
  // WebView 里 new Response(stream).arrayBuffer() 会报 Failed to fetch，必须逐块读流
  const reader = new Blob([bytes]).stream().pipeThrough(new DecompressionStream(format)).getReader();
  const chunks: Uint8Array[] = [];
  let failure: unknown = null;
  for (;;) {
    try {
      const { value, done } = await reader.read();
      if (done) break;
      if (value) chunks.push(value);
    } catch (err) {
      // 服务端会在 zlib 流结束后追加多余字节（Junk found after end），此时数据已解完，
      // 不能因为尾部垃圾丢掉整段结果
      failure = err;
      break;
    }
  }
  if (chunks.length === 0 && failure) throw failure;
  const total = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
  const merged = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    merged.set(chunk, offset);
    offset += chunk.length;
  }
  return merged;
};

/**
 * 解出 QQ 歌词明文
 * @param encryptedHex - 服务端返回的 hex 密文
 * @returns 歌词文本
 */
export const decryptQrc = async (encryptedHex: string): Promise<string> => {
  const cipher = hexToBytes(encryptedHex);
  if (cipher.byteLength % 8 !== 0) throw new Error("QQ 3DES 密文未按 8 字节对齐");
  const compressed = qrcDecrypt(cipher, QRC_KEY);

  // 首选 zlib 包装，raw deflate 与 gzip 作为历史变体回退
  for (const format of ["deflate", "deflate-raw", "gzip"] as const) {
    let inflated: Uint8Array | null = null;
    try {
      inflated = await decompress(format, compressed);
    } catch (err) {
      console.warn("[qrc] inflate " + format + " fail: " + String((err as Error)?.message ?? err));
      continue;
    }
    // 尾部截断可能破坏最后一个多字节字符，先用严格解码，失败退回替换式解码
    try {
      return new TextDecoder("utf-8", { fatal: true }).decode(inflated);
    } catch {
      return new TextDecoder("utf-8").decode(inflated);
    }
  }
  const direct = new TextDecoder().decode(compressed);
  if (direct.includes("[") || direct.includes("<")) return direct;
  throw new Error("QQ 歌词解密成功但无法解压");
};
