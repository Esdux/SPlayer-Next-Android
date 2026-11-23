import { registerApisHandlers } from "./apis";
import { registerConfigHandlers } from "./config";
import { registerLyricsHandlers } from "./lyrics";
import { registerMiscHandlers } from "./misc";
import { registerPickersHandlers } from "./pickers";
import { registerPlaylistHandlers } from "./playlist";
import { registerStubHandlers } from "./stubs";
import { registerSystemHandlers } from "./system";

/**
 * 注册全部 JS 侧 channel 实现
 */
export const registerAndroidHandlers = (): void => {
  registerApisHandlers();
  registerConfigHandlers();
  registerSystemHandlers();
  registerMiscHandlers();
  registerPlaylistHandlers();
  registerLyricsHandlers();
  registerStubHandlers();
  // picker 走系统选择器，必须排在 stubs 之后才能覆盖同名占位通道
  registerPickersHandlers();
};