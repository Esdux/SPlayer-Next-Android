import { registerApisHandlers } from "./apis";
import { registerConfigHandlers } from "./config";
import { registerMiscHandlers } from "./misc";
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
  registerStubHandlers();
};