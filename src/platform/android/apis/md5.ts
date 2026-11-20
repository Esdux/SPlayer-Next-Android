/**
 * 纯 JS MD5
 *
 * WebView 里没有 node:crypto，Web Crypto 又不提供 MD5，
 * 而网易/酷狗/QQ 三家的签名都要用它，所以自带一份实现。
 */

/** 每轮左移位数 */
const SHIFT = [
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
];

/** 正弦常量表 */
const TABLE = new Uint32Array(64);
for (let i = 0; i < 64; i++) {
  TABLE[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 0x100000000) >>> 0;
}

/**
 * 计算 MD5，输出小写 32 位十六进制
 * @param input - 文本（按 UTF-8 编码）或原始字节
 * @returns 32 位十六进制摘要
 */
export const md5 = (input: string | Uint8Array): string => {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : input;
  const bitLength = bytes.length * 8;
  // 补位到 64 字节整数倍，末尾 8 字节写长度
  const total = (((bytes.length + 8) >> 6) + 1) << 6;
  const buffer = new Uint8Array(total);
  buffer.set(bytes);
  buffer[bytes.length] = 0x80;
  const view = new DataView(buffer.buffer);
  view.setUint32(total - 8, bitLength >>> 0, true);
  view.setUint32(total - 4, Math.floor(bitLength / 0x100000000), true);

  let a0 = 0x67452301;
  let b0 = 0xefcdab89;
  let c0 = 0x98badcfe;
  let d0 = 0x10325476;
  const words = new Uint32Array(16);

  for (let offset = 0; offset < total; offset += 64) {
    for (let i = 0; i < 16; i++) words[i] = view.getUint32(offset + i * 4, true);
    let a = a0;
    let b = b0;
    let c = c0;
    let d = d0;
    for (let i = 0; i < 64; i++) {
      let f: number;
      let g: number;
      if (i < 16) {
        f = (b & c) | (~b & d);
        g = i;
      } else if (i < 32) {
        f = (d & b) | (~d & c);
        g = (5 * i + 1) % 16;
      } else if (i < 48) {
        f = b ^ c ^ d;
        g = (3 * i + 5) % 16;
      } else {
        f = c ^ (b | ~d);
        g = (7 * i) % 16;
      }
      const sum = (a + f + TABLE[i] + words[g]) >>> 0;
      a = d;
      d = c;
      c = b;
      b = (b + ((sum << SHIFT[i]) | (sum >>> (32 - SHIFT[i])))) >>> 0;
    }
    a0 = (a0 + a) >>> 0;
    b0 = (b0 + b) >>> 0;
    c0 = (c0 + c) >>> 0;
    d0 = (d0 + d) >>> 0;
  }

  const out = new Uint8Array(16);
  const outView = new DataView(out.buffer);
  outView.setUint32(0, a0, true);
  outView.setUint32(4, b0, true);
  outView.setUint32(8, c0, true);
  outView.setUint32(12, d0, true);
  let hex = "";
  for (const byte of out) hex += byte.toString(16).padStart(2, "0");
  return hex;
};
