/**
 * 平台判定
 *
 * 安卓壳通过 `AndroidHost` 注入识别，渲染层所有"按平台裁剪"的地方都走这里，避免各写一份判定而漂移
 */

/**
 * 是否运行在安卓壳内
 * @returns 安卓壳内为 true
 */
export const isAndroidRuntime = (): boolean =>
  typeof window !== "undefined" && Boolean((window as any).AndroidHost);
