/**
 * AFP 指纹库（afp.mjs）的 TypeScript 声明
 *
 * afp.mjs 是 Emscripten 打包产出的纯浏览器 ESM，只依赖 atob / btoa / WebAssembly / console，
 * 这里只声明实际使用的 GenerateFP，避免把 1700 行生成代码当 TS 逐一类型检查
 */

/**
 * 计算 AFP 音频指纹
 * @param pcm - 8 kHz 单声道样本
 * @returns base64 编码的指纹字符串
 */
export declare function GenerateFP(pcm: Float32Array): Promise<string>;
