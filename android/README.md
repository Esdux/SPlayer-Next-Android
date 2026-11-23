# SPlayer-Next-Android

[SPlayer-Next](https://github.com/SPlayer-Dev/SPlayer-Next) 的安卓移植版。沿用其 Vue 3 渲染层与 `window.api` 契约，用 Kotlin 壳替换 Electron 主进程与 Rust 音频引擎。

## 架构

```
Vue SPA（原样复用 renderer）
   │  window.api（与桌面端同一份 preload 契约）
   ▼
src/platform/android/  ← 事件订阅 / channel 转发 / 纯 JS 能力实现
   │  AndroidHost.invokeAsync(callId, channel, payloadJson)
   ▼
HostBridge → BridgeDispatcher → 曲库 / 播放引擎 / 网络 / 系统
   │  window.__androidReply(callId, json) / window.__androidEvent(channel, json)
   ▼
WebView（https://appassets.androidplatform.net/assets/web/）
```

- **页面外壳**：`WebView` + `WebViewAssetLoader`，前端以 `https://appassets.androidplatform.net/assets/web/index.html` 加载，因此具备安全上下文与同源 `fetch`，无需 `file://` hack。
- **契约复用**：`electron/preload/index.ts` 整文件复制为 `src/platform/android/preload-api.ts`，仅替换 `os` / `electron` / `@electron-toolkit/preload` 三个 import，168 个 channel 的方法签名一个没改。
- **播放引擎**：Media3 `ExoPlayer` 单例替代 Rust `audio-engine`，`MediaSessionCompat + NotificationCompat.MediaStyle` 提供后台播放与系统媒体控制。
- **曲库**：`MediaStore` 扫描替代 better-sqlite3 + 文件扫描；封面经 `/cover/<albumId>` 自定义 path handler 输出。
- **歌单**：`localStorage` 持久化，行为与桌面端 sqlite 版对齐（含去重、计数、封面继承）。

## 构建

前置：Node 20+、JDK 21、Android SDK（platform 35、build-tools 35.0.0）、Gradle 9.x。

```bash
pnpm install --ignore-scripts
# 1. 构建前端到 android/app/src/main/assets/web（该目录不入库）
./node_modules/.bin/vite build --config vite.android.config.ts
# 2. 打包
cd android && gradle assembleRelease
# 产物：android/app/build/outputs/apk/release/app-release.apk
```

签名从 `android/local.properties` 读取（`ks.path` / `ks.pass` / `ks.alias`），不写入构建脚本，该文件已 gitignore。

## 能力对照

已实现：

| 能力 | 说明 |
| --- | --- |
| 播放控制 | play/pause/stop/seek/音量/倍速、status 与 position 事件推送 |
| 本地曲库 | MediaStore 扫描、曲目/专辑/歌手聚合、关键词搜索、随机取歌 |
| 歌单 | 列表、创建、改名、删除、增删曲目、旧版迁移 |
| 配置 | settings 全量读写（localStorage + deepMerge 默认值） |
| 系统打开方式 | `audio/*` 的 VIEW / SEND / SEND_MULTIPLE，冷启动入队、热启动推事件 |
| 窗口与主题 | ready/startupComplete、全屏、最小化、退出、主题同步 |
| 网络 | OkHttp 代理 fetch，供渲染层复用在线接口 |

尚未移植（返回结构化失败，不会中断前端）：

- `plugin:*` 插件系统（依赖 Node 运行时与动态加载）
- `streaming:*` Navidrome 等流媒体服务器同步
- `download:*` 下载队列（当前交由系统下载器）
- `lyrics:*` 歌词仓库搜索与手动匹配（内嵌歌词仍可读）
- `desktopLyric` / `dynamicIsland` / `taskbarLyric` 等桌面窗口能力
- 均衡器、响度均衡、FFT 频谱（Rust DSP 部分）

## 许可证

继承上游 AGPL-3.0，见 [LICENSE](LICENSE)。
