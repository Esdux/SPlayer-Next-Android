import type { SettingCategory } from "@/types/settings-schema";
import generalCategory from "./categories/general";
import appearanceCategory from "./categories/appearance";
import playerCategory from "./categories/player";
import lyricCategory from "./categories/lyric";
import externalLyricCategory from "./categories/externalLyric";
import hotkeysCategory from "./categories/hotkeys";
import servicesCategory from "./categories/services";
import aiIntegrationCategory from "./categories/aiIntegration";
import mediaSourceCategory from "./categories/streaming";
import downloadCategory from "./categories/download";
import localCacheCategory from "./categories/localCache";
import pluginsCategory from "./categories/plugins";
import otherCategory from "./categories/other";
import { updateSection } from "./sections/update";
import AboutSettings from "@/components/settings/custom/AboutSettings.vue";
import IconLucideInfo from "~icons/lucide/info";

import { isAndroidRuntime } from "@/utils/platform";

/** 是否运行在安卓壳内 */
const android = isAndroidRuntime();

/** 安卓上没有对应实现、或纯桌面形态的设置分类，直接不出现在设置页 */
const DESKTOP_ONLY_CATEGORY_IDS = new Set([
  "externalLyric",
  "hotkeys",
  "plugins",
  "mediaSource",
  "localCache",
]);

export const settingsSchema: SettingCategory[] = [
  generalCategory,
  appearanceCategory,
  playerCategory,
  lyricCategory,
  externalLyricCategory,
  hotkeysCategory,
  servicesCategory,
  aiIntegrationCategory,
  mediaSourceCategory,
  downloadCategory,
  localCacheCategory,
  pluginsCategory,
  otherCategory,
  { id: "about", icon: IconLucideInfo, component: AboutSettings, sections: [updateSection] },
].filter((category) => !(android && DESKTOP_ONLY_CATEGORY_IDS.has(category.id)));
