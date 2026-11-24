<script setup lang="ts">
import { useStatusStore } from "@/stores/status";
import { useMediaStore } from "@/stores/media";
import { useSettingsStore } from "@/stores/settings";
import { useOrpheusProtocol } from "@/composables/useOrpheusProtocol";
import { useExternalFileHandler } from "@/composables/useExternalFileHandler";
import { useResponsiveLayout } from "@/composables/useResponsiveLayout";
import { coverTransitionActive, installCoverTransitions } from "@/services/coverTransition";
import { consumeBack } from "@/services/backStack";
import {
  fullPlayerReady,
  playerTransitionActive,
  cancelPlayerTransition,
} from "@/services/playerTransition";

const route = useRoute();
const router = useRouter();
const status = useStatusStore();
const settings = useSettingsStore();
const { sidebarCollapsed, isPhone, drawerOpen } = useResponsiveLayout();

/** 抽屉内是否展开为完整侧栏（折叠样式里点按钮切换） */
const drawerExpanded = ref(false);

/** 左缘右滑打开抽屉的手势起点 */
const edgeSwipe = { x: 0, y: 0, active: false };

/**
 * 记录右滑起点：只有从屏幕左缘 24px 内开始才算
 * @param event - 触摸事件
 */
const onEdgeTouchStart = (event: TouchEvent): void => {
  if (!isPhone.value || drawerOpen.value) return;
  // 全屏播放页内不触发（那里的手势留给下滑收起）
  if (status.isPlayerExpanded) return;
  const touch = event.touches[0];
  if (!touch) return;
  edgeSwipe.x = touch.clientX;
  edgeSwipe.y = touch.clientY;
  edgeSwipe.active = true;
};

/**
 * 右滑超过 60px 且以水平方向为主时打开抽屉
 * @param event - 触摸结束事件
 */
const onEdgeTouchEnd = (event: TouchEvent): void => {
  if (!edgeSwipe.active) return;
  edgeSwipe.active = false;
  const touch = event.changedTouches[0];
  if (!touch) return;
  const dx = touch.clientX - edgeSwipe.x;
  const dy = Math.abs(touch.clientY - edgeSwipe.y);
  if (dx > 60 && dx > dy * 1.5) drawerOpen.value = true;
};

// 手势挂在 document 捕获阶段：页面内部滚动容器会吞掉根元素上的 touch 事件
onMounted(() => {
  document.addEventListener("touchstart", onEdgeTouchStart, { passive: true, capture: true });
  document.addEventListener("touchend", onEdgeTouchEnd, { passive: true, capture: true });
});

onBeforeUnmount(() => {
  document.removeEventListener("touchstart", onEdgeTouchStart, { capture: true } as EventListenerOptions);
  document.removeEventListener("touchend", onEdgeTouchEnd, { capture: true } as EventListenerOptions);
});

/** 返回键解绑 */
let offBack: (() => void) | undefined;

/** 通知栏/锁屏的上一首、下一首命令转到网页端的播放队列 */
let offMediaNext: (() => void) | undefined;
let offMediaPrevious: (() => void) | undefined;

onMounted(async () => {
  const host = (window as unknown as { AndroidHost?: unknown }).AndroidHost;
  if (!host) return;
  const { onNativeEvent } = await import("@/platform/android/electron-shim");
  const player = await import("@/core/player");
  // 返回键统一协调：弹层/播放页各自先行消费，都没消费时回退路由
  offBack = onNativeEvent("system:back", () => {
    if (consumeBack()) return;
    if (window.history.length > 1) {
      router.back();
      return;
    }
    // 已经在根路由，按安卓习惯退到后台，而不是退出进程
    (window as unknown as { AndroidHost?: { moveTaskToBack?: () => void } }).AndroidHost
      ?.moveTaskToBack?.();
  });
  offMediaNext = onNativeEvent("system:mediaNext", () => void player.nextTrack(true));
  offMediaPrevious = onNativeEvent("system:mediaPrevious", () => void player.prevTrack());
});

onBeforeUnmount(() => {
  offMediaNext?.();
  offMediaPrevious?.();
  offBack?.();
});

// 接入 orpheus 协议唤起与外部音频文件播放
useOrpheusProtocol();
useExternalFileHandler();

/** 有歌曲信息时显示播放栏 */
const showPlayerBar = computed(() => !!useMediaStore().track);
const { isPlayerExpanded } = storeToRefs(status);

/** 播放页展开状态同步与懒加载 */
const FullPlayer = defineAsyncComponent(() => import("@/components/player/FullPlayer/index.vue"));
const fullPlayerLoaded = ref(isPlayerExpanded.value);
watch(
  isPlayerExpanded,
  (expanded) => {
    if (expanded) fullPlayerLoaded.value = true;
    window.api.window.setPlayerExpanded(expanded);
  },
  { immediate: true },
);

const { appearance } = settings;

/** 路由切换动效 */
const routeTransitionName = computed(() => {
  const transition = appearance.routeTransition;
  return transition === "none" ? "" : `route-${transition}`;
});

/** 详情按路径和服务器隔离实例，列表沿用父路由缓存 */
const routeKey = computed(() => {
  const hasParam = route.matched.some((m) => m.path.includes(":"));
  return hasParam
    ? `${route.path}:${route.query.serverId ?? ""}`
    : (route.matched[1]?.path ?? route.path);
});

/** 需要受控缓存的页面组件白名单 */
const cachedViews = [
  "Home",
  "Library",
  "Liked",
  "History",
  "Download",
  "Daily",
  "Favorites",
  "Cloud",
  "LocalList",
  "Folders",
  "SearchPage",
  "Stats",
  "StreamingIndex",
];

const mainContainerRef = shallowRef<HTMLElement | null>(null);
const mainScrollMap = new Map<string, number>();

// 路由离开前记录滚动位置
watch(
  () => route.fullPath,
  (_newPath, oldPath) => {
    if (oldPath && mainContainerRef.value) {
      mainScrollMap.set(oldPath, mainContainerRef.value.scrollTop);
      if (mainScrollMap.size > 10) {
        mainScrollMap.delete(mainScrollMap.keys().next().value!);
      }
    }
  },
);

/** 路由切换完成后恢复主内容区滚动位置 */
const handleAfterEnter = (): void => {
  if (!mainContainerRef.value) return;
  const saved = mainScrollMap.get(route.fullPath) ?? 0;
  mainContainerRef.value.scrollTop = saved;
};

let disposeCoverTransitions: (() => void) | undefined;
watch(
  () => appearance.seamlessTransition && !settings.reducedMotion,
  (enabled) => {
    if (!enabled) cancelPlayerTransition();
    disposeCoverTransitions?.();
    disposeCoverTransitions = enabled
      ? installCoverTransitions(router, () => mainContainerRef.value, handleAfterEnter)
      : undefined;
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  window.api.window.setPlayerExpanded(false);
  disposeCoverTransitions?.();
  cancelPlayerTransition();
  fullPlayerReady.value = false;
});

/** 侧边栏样式 */
const sidebarClass = computed(() => {
  const classes: string[] = [];
  if (appearance.layoutMode === "floating") {
    classes.push("ml-3 mt-3 mb-3 rounded-xl border border-solid border-primary/10");
  } else {
    classes.push("border-r border-r-solid border-r-primary/10");
    if (showPlayerBar.value && appearance.layoutMode === "default") classes.push("mb-20");
  }
  return classes.join(" ");
});

/** 主界面底部边距 */
const mainMarginClass = computed(() =>
  showPlayerBar.value && appearance.layoutMode !== "floating" ? "mb-20" : "",
);

/** 切换「折叠侧边栏」设置，抽屉宽度随之变化 */
const toggleSidebarCollapsed = (): void => {
  appearance.sidebarCollapsed = !appearance.sidebarCollapsed;
};

/** 外层播放条样式 */
const playerBarWrapperClass = computed(() => {
  const base = "fixed bottom-0 z-50 transition-[left] duration-300 pointer-events-none";
  const collapsed = sidebarCollapsed.value;
  // 播放条统一避让底部安全区：手势条与三键导航都会压到最底部
  const phoneSafe = "pb-[max(var(--android-safe-bottom,0px),16px)]";
  const floatingSafe = "pb-[max(var(--android-safe-bottom,0px),24px)]";
  const plainSafe = "pb-[var(--android-safe-bottom,0px)]";
  // 竖屏导航改由抽屉承载，播放条不再为侧栏让位
  if (isPhone.value) {
    // 竖屏胶囊自己带圆角，外层只负责避让手势条
    return `${base} left-0 right-0${appearance.layoutMode === "floating" ? " px-3" : ""} ${phoneSafe}`;
  }
  switch (appearance.layoutMode) {
    case "sidebar-full":
      return `${base} ${collapsed ? "left-16" : "left-60"} right-0 ${plainSafe}`;
    case "floating":
      return `${base} ${collapsed ? "left-[76px]" : "left-[252px]"} right-0 px-4 ${floatingSafe}`;
    default:
      return `${base} left-0 right-0 ${plainSafe}`;
  }
});

/** 内层播放条样式 */
const playerBarInnerClass = computed(() => {
  // 禁用底部播放栏交互
  const base = isPlayerExpanded.value ? "pointer-events-none" : "pointer-events-auto";
  // 手机竖屏由 PlayerBar 自己画悬浮胶囊，外层不再铺底色与分隔线
  if (isPhone.value) return `${base} bg-transparent`;
  switch (appearance.layoutMode) {
    case "floating":
      return `${base} mx-auto max-w-4xl glass-panel rounded-full shadow-xl border border-solid border-primary/10`;
    default:
      return `${base} h-20 bg-surface-panel border-t border-t-solid border-t-primary/10`;
  }
});
</script>

<template>
  <!-- 主界面 -->
  <div
    class="player-shell h-screen flex overflow-hidden bg-app text-on-surface transition-[transform,opacity] duration-360 ease-[cubic-bezier(0.25,1,0.35,1)] origin-center"
    :style="playerTransitionActive ? { transition: 'none' } : undefined"
    :class="isPlayerExpanded && fullPlayerReady ? 'scale-95 opacity-0 pointer-events-none' : ''"
  >
    <!-- 侧边栏（桌面与横屏） -->
    <aside
      v-if="!isPhone"
      class="shrink-0 bg-surface-panel overflow-y-auto z-10 transition-[width,margin] duration-300"
      :class="[sidebarCollapsed ? 'w-16 self-stretch' : 'w-60', sidebarClass]"
    >
      <SideBar />
    </aside>

    <!-- 竖屏导航抽屉：灵动悬浮卡片，底部背景透出 -->
    <SDrawer
      v-if="isPhone"
      v-model:open="drawerOpen"
      side="left"
      floating
      :width="drawerExpanded ? '244px' : '76px'"
      :closable="false"
    >
      <div class="flex h-full flex-col min-h-0 bg-surface-panel">
        <SideBar
          :force-expanded="drawerExpanded"
          toggleable
          @toggle-expanded="drawerExpanded = !drawerExpanded"
        />
      </div>

    </SDrawer>

    <!-- 右侧主区域 -->
    <div class="flex-1 flex flex-col min-w-0 min-h-0" :class="mainMarginClass">
      <!-- 顶部导航 -->
      <header class="h-16 shrink-0 flex items-center px-3">
        <NavHeader />
      </header>

      <!-- 主内容区 -->
      <main
        ref="mainContainerRef"
        class="flex-1 overflow-y-auto overflow-x-hidden"
        :style="status.isPlayerExpanded ? { visibility: 'hidden' } : undefined"
      >
        <RouterView v-slot="{ Component }">
          <Transition
            :name="routeTransitionName"
            :css="!coverTransitionActive"
            :mode="
              coverTransitionActive || routeTransitionName === 'route-fade' ? undefined : 'out-in'
            "
            @after-enter="handleAfterEnter"
          >
            <KeepAlive :max="10" :include="cachedViews">
              <component :is="Component" :key="routeKey" />
            </KeepAlive>
          </Transition>
        </RouterView>
      </main>
    </div>
  </div>

  <!-- 底部播放栏 -->
  <Transition
    enter-active-class="transition-transform duration-300 ease-out"
    leave-active-class="transition-transform duration-300 ease-in"
    enter-from-class="translate-y-full"
    leave-to-class="translate-y-full"
  >
    <div v-if="showPlayerBar" :class="playerBarWrapperClass">
      <footer :class="playerBarInnerClass">
        <PlayerBar />
      </footer>
    </div>
  </Transition>

  <!-- Toast -->
  <SToast :max="1" />
  <!-- 性能监视器 -->
  <SPerformanceMonitor v-if="appearance.showPerformanceMonitor" />
  <!-- Dialog -->
  <SDialogProvider />
  <!-- 全屏播放器 -->
  <FullPlayer v-if="fullPlayerLoaded" @vue:mounted="fullPlayerReady = true" />
  <!-- 全局设置 -->
  <SettingsDialog />
  <!-- 更新弹窗 -->
  <UpdateDialog />
  <!-- 评论弹窗 -->
  <MusicCommentsDialog />
  <!-- 输出信息弹窗 -->
  <AudioInfoDialog />
</template>
