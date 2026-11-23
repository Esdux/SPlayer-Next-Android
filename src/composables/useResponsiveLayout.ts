import { useSettingsStore } from "@/stores/settings";

/** 内容区进入紧凑布局的视口宽度 */
const COMPACT_QUERY = "(max-width: 1023.98px)";
/** 侧栏自动折叠的视口宽度 */
const SIDEBAR_QUERY = "(max-width: 1199.98px)";
/** 竖屏手机视口宽度 */
const PHONE_QUERY = "(max-width: 640px)";

/** 竖屏导航抽屉开关（模块级：布局与顶栏共享同一实例） */
const drawerOpen = ref(false);

/**
 * 应用级响应式布局状态
 * @returns 紧凑布局标记、侧栏折叠状态与竖屏抽屉开关
 */
export const useResponsiveLayout = () => {
  const settings = useSettingsStore();
  const isCompact = useMediaQuery(COMPACT_QUERY);
  const isNarrow = useMediaQuery(SIDEBAR_QUERY);
  const isPhone = useMediaQuery(PHONE_QUERY);

  /** 窄窗口强制折叠侧栏，避免主内容区被持续挤压 */
  const sidebarCollapsed = computed(() => settings.appearance.sidebarCollapsed || isNarrow.value);

  // 退出竖屏时收起抽屉，避免转屏后残留展开状态
  watch(isPhone, (value) => {
    if (!value) drawerOpen.value = false;
  });

  /** 切换竖屏导航抽屉 */
  const toggleDrawer = () => {
    drawerOpen.value = !drawerOpen.value;
  };

  return { isCompact, isNarrow, isPhone, drawerOpen, toggleDrawer, sidebarCollapsed };
};
