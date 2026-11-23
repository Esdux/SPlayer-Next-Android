import { api, electronAPI } from "./preload-api";
import "./android-mobile.css";
import { installReplyChannel, isAndroidHost } from "./electron-shim";
import { registerAndroidHandlers } from "./handlers";

/**
 * 全局错误捕获：WebView 只报 "Maximum call stack size exceeded" 时拿不到栈，
 * 这里把 error / unhandledrejection 的 stack 一并打到 logcat，便于定位。
 */
const installErrorTracing = (): void => {
  window.addEventListener("error", (event) => {
    console.error("[android] window.error:", event.error?.stack ?? event.message);
  });
  window.addEventListener("unhandledrejection", (event) => {
    const reason = (event as PromiseRejectionEvent).reason;
    console.error("[android] unhandledrejection:", reason?.stack ?? String(reason));
  });
};

/**
 * 安卓触屏没有 hover，浏览器默认的点击高亮会留下一层蓝/灰遮罩，
 * 同时禁用长按选中与橡皮筋回弹，让交互更接近原生应用。
 */
/**
 * 页面跑在 https 的 asset 域下，各平台封面常是 http，会被 mixed content 拦掉
 * 这里把图片地址统一升级成 https（网易、QQ、酷狗的 CDN 都支持）
 */
const installSecureImages = (): void => {
  const fix = (root: ParentNode): void => {
    root.querySelectorAll?.("img").forEach((img) => {
      const src = img.getAttribute("src") ?? "";
      if (src.startsWith("http://")) img.setAttribute("src", "https://" + src.slice(7));
    });
  };
  fix(document);
  new MutationObserver((records) => {
    for (const record of records) {
      if (record.type === "attributes" && record.target instanceof HTMLImageElement) {
        fix(record.target.parentNode ?? document);
      }
      record.addedNodes.forEach((node) => {
        if (node instanceof HTMLElement) fix(node);
      });
    }
  }).observe(document.documentElement, {
    subtree: true,
    childList: true,
    attributes: true,
    attributeFilter: ["src"],
  });
};

const installTouchStyles = (): void => {
  const style = document.createElement("style");
  style.textContent = [
    "*{-webkit-tap-highlight-color:transparent !important;}",
    "html,body{overscroll-behavior:none;}",
    "button,a,input,label{-webkit-user-select:none;user-select:none;}",
  ].join("");
  document.head.appendChild(style);
};

/**
 * 安卓壳内安装 window.api，桌面环境保持原样
 */
export const installAndroidApi = (): void => {
  if (!isAndroidHost()) return;
  installReplyChannel();
  registerAndroidHandlers();
  const target = window as unknown as Record<string, unknown>;
  target.electron = electronAPI;
  target.api = api;
  installErrorTracing();
  installSecureImages();
  installTouchStyles();
};

installAndroidApi();