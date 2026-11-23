import "virtual:uno.css";
import "@/styles/global.css";

import piniaPersistedstate from "pinia-plugin-persistedstate";
import App from "./App.vue";
import router from "./router";
import i18n from "./i18n";

import { useThemeStore } from "./stores/theme";
import { useSettingsStore } from "./stores/settings";
import { useHotkeyStore } from "./stores/hotkey";
import { useDownloadStore } from "./stores/download";
import { initPlayer, playFiles, restoreLastTrack } from "./core/player";
import { handleOrpheus } from "./services/orpheus";
import { installHotkeyManager } from "./core/hotkey/manager";
import { vRipple } from "./directives/ripple";
import { watchLyricon } from "@/platform/android/services/lyricon";

const pinia = createPinia();
pinia.use(piniaPersistedstate);

const app = createApp(App);
app.directive("ripple", vRipple);
app.use(pinia);
app.use(router);
app.use(i18n);

// 初始化主题
useThemeStore().init();

// 同步语言设置
watch(
  () => useSettingsStore().locale,
  (v) => {
    i18n.global.locale.value = v;
    window.api.system.setLocale(v);
  },
  { immediate: true },
);

/**
 * 启动播放服务并分发冷启动任务
 */
const bootstrapPlayback = async (): Promise<void> => {
  const pendingAudioFiles = await window.api.system.consumePendingAudioFiles();
  const pendingProtocolUrl = await window.api.system.consumePendingProtocolUrl();

  // 安卓桥回的是信封对象，桌面契约是数组/字符串：这里按类型收敛，避免把信封当 URL 传给 orpheus
  const audioFiles = Array.isArray(pendingAudioFiles) ? pendingAudioFiles : [];
  const protocolUrl = typeof pendingProtocolUrl === "string" ? pendingProtocolUrl : "";

  if (audioFiles.length > 0) {
    await playFiles(audioFiles);
  } else if (protocolUrl) {
    await handleOrpheus(protocolUrl);
  } else {
    await restoreLastTrack();
  }
};

/** 挂载首屏并准备播放操作 */
export const mountApplication = async (): Promise<void> => {
  await router.isReady();
  app.mount("#app");
  await nextTick();
  await initPlayer();
};

/** 启动层退出后恢复播放和快捷键 */
export const startApplication = (): void => {
  void bootstrapPlayback().catch(console.error);
  // pinia 就绪后再订阅词幕推送，避免启动期访问 store
  if (typeof window !== "undefined" && (window as any).AndroidHost) watchLyricon();
  // 下载队列的订阅与手机布局无关，启动即初始化，避免下载页/角标永远为空
  void useDownloadStore()
    .init()
    .catch((err) => console.error("[download] init failed", err));
  useHotkeyStore()
    .init()
    .then(installHotkeyManager)
    .catch((err) => console.error("[hotkey] init failed", err));
};
