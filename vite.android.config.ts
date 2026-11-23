import { resolve } from "path";
import { defineConfig } from "vite";
import UnoCSS from "unocss/vite";
import vue from "@vitejs/plugin-vue";
import AutoImport from "unplugin-auto-import/vite";
import Icons from "unplugin-icons/vite";
import IconsResolver from "unplugin-icons/resolver";
import { FileSystemIconLoader } from "unplugin-icons/loaders";
import RekaResolver from "reka-ui/resolver";
import Components from "unplugin-vue-components/vite";
import pkg from "./package.json" with { type: "json" };

/**
 * 安卓版构建配置：只产出 renderer 静态资源，产物直接落入 APK assets
 */
export default defineConfig({
  root: ".",
  base: "/assets/web/",
  // 安卓只需要 favicon，桌面托盘/磁贴等资源不进 APK
  publicDir: resolve(__dirname, "public-android"),
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
    __APP_REPO_URL__: JSON.stringify(pkg.repository.url),
    __APP_REPO_NAME__: JSON.stringify(pkg.productName),
    __APP_AUTHOR__: JSON.stringify(pkg.author.name),
    __APP_HOMEPAGE__: JSON.stringify(pkg.homepage),
    __APP_AUTHOR_URL__: JSON.stringify(pkg.author.url),
    __COMMIT_HASH__: JSON.stringify("android"),
    __COMMIT_DATE__: JSON.stringify("android"),
    __ANDROID__: JSON.stringify(true),
  },
  optimizeDeps: {
    entries: ["index.html", "src/components/**/*.vue"],
    include: ["reka-ui"],
  },
  build: {
    outDir: resolve(__dirname, "android/app/src/main/assets/web"),
    emptyOutDir: true,
    target: "es2022",
    sourcemap: false,
    chunkSizeWarningLimit: 4096,
    rollupOptions: {
      input: { index: resolve(__dirname, "index.html") },
    },
  },
  resolve: {
    alias: {
      "@": resolve("src"),
      "@shared": resolve(__dirname, "shared"),
      "@main": resolve(__dirname, "electron/main"),
      "@windows": resolve(__dirname, "windows"),
      "@root": resolve(__dirname),
    },
  },
  plugins: [
    vue(),
    UnoCSS(),
    AutoImport({ imports: ["vue", "pinia", "vue-router", "@vueuse/core", "vue-i18n"] }),
    Icons({
      compiler: "vue3",
      scale: 1,
      customCollections: { sp: FileSystemIconLoader("./src/assets/icons") },
    }),
    Components({
      dirs: ["src/components"],
      dts: false,
      resolvers: [RekaResolver(), IconsResolver({ prefix: "icon", customCollections: ["sp"] })],
    }),
  ],
});
