import "./platform/android/install";

/** 显示启动层并在首屏就绪后移交播放控制，避免静态导入 Vue 或业务模块 */
export const bootstrap = async (): Promise<void> => {
  const splash = document.getElementById("app-loading")!;
  const root = document.getElementById("app")!;
  const logo = splash.querySelector<HTMLImageElement>(".splash-logo")!;
  /** 跟随页面可见性暂停启动动画 */
  const syncVisibility = (): void => {
    splash.classList.toggle("paused", document.hidden);
  };
  document.addEventListener("visibilitychange", syncVisibility);
  syncVisibility();

  try {
    await Promise.allSettled([document.fonts.load('36px "logo"'), logo.decode()]);
    await window.api.window.ready();
    splash.classList.add("started");
    // 只等待有限入场动画，循环圆点不参与就绪判定。
    const entrance = Promise.allSettled(
      Array.from(splash.querySelectorAll(".entrance")).flatMap((element) =>
        element.getAnimations().map((animation) => animation.finished),
      ),
    );
    // 先提交动画首帧，再解析业务模块，避免热缓存下模块求值抢占入场。
    await new Promise<void>((resolve) =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
    );
    const application = await import("./main");
    await Promise.all([application.mountApplication(), entrance]);
    // Vue 更新完成不代表浏览器已布局，保留一帧让底层界面完成绘制。
    await new Promise<void>((resolve) =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
    );
    const fade = splash.animate([{ opacity: 1 }, { opacity: 0 }], {
      duration: matchMedia("(prefers-reduced-motion: reduce)").matches ? 0 : 200,
      easing: "ease-out",
      fill: "forwards",
    });
    await fade.finished;
    splash.remove();
    fade.cancel();
    root.inert = false;
    root.removeAttribute("aria-hidden");
    await window.api.window.startupComplete();
    application.startApplication();
  } finally {
    document.removeEventListener("visibilitychange", syncVisibility);
  }
};

void bootstrap().catch((error) => {
  console.error("[app] 初始化失败", error);
  const stack = error instanceof Error ? error.stack || String(error) : String(error);
  window.api?.window?.startupFailed?.(stack);
  // 兜底：初始化失败时必须收掉启动层，否则界面会永远停在转圈动画上
  const splash = document.getElementById("app-loading");
  if (splash) splash.remove();
  const host = document.getElementById("app");
  if (host) {
    host.inert = false;
    host.removeAttribute("aria-hidden");
    if (host.childElementCount === 0) {
      const panel = document.createElement("pre");
      panel.style.cssText =
        "margin:0;padding:24px;color:#ffb4b4;font:12px/1.6 ui-monospace,monospace;white-space:pre-wrap;word-break:break-all;background:#141418;min-height:100vh;box-sizing:border-box";
      panel.textContent = "[启动失败]\n\n" + stack;
      host.appendChild(panel);
    }
  }
});
