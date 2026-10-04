# DeepTalking Lite (android-lite)

DeepTalking 的轻量 Android 封装：原生 WebView 加载 `assets/hub.html`。开发源码位于根目录 `src/`，构建器将页面、样式与按职责拆分的 JavaScript 模块生成 `assets/hub.html`，并把 `src/vendor/katex/` 复制为 `assets/katex/`。

后续开发只修改 `src/`（含 `src/vendor/`）的模块与静态库；产物不再维护，`assets/hub.html` 与 `assets/katex/` 仅由构建器生成。

- 包名：`com.deeptalking.lite`
- 显示名：DeepTalking
- 无 TTS、无第三方依赖（纯原生 WebView）
- 开启 `allowUniversalAccessFromFileURLs` + `allowFileAccessFromFileURLs`，**关闭 CORS 限制**：
  - DeepSeek：`https://api.deepseek.com/v1` 直连
  - OpenCode Go：`https://opencode.ai/zen/go/v1` 直连（无需本地/Cloudflare 代理）
  - 两者均需在应用内设置中填入对应 API Key

## 构建

需要 Node.js 22、JDK 17、Android SDK 和 Gradle。Gradle 的 `preBuild` 自动调用 `buildHubAssets`，从 `src/`（含 `src/vendor/`）生成 `assets/hub.html` 与 `assets/katex/` 后再打包；不需要手工准备资产。源码、构建器或版本配置变化时会重新生成。

```bash
cd android-lite
gradle :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

CI：推送 `src/**`、`tools/**`、`android-lite/**` 或构建工作流到 `main`/`cloud-main` 自动验证源码与产物、执行应用自检、构建固定签名的 release APK，并上传 Artifacts 和 Release。

## 更新资产（每次出包前）

APK 打包会自动生成资产。提交前验证时，修改 `src/` 中的模块后，从仓库根目录运行：

```bash
node tools/build-hub.mjs
node tools/build-hub.mjs --check
powershell -File tools/check-sync.ps1
node tools/sync-version.mjs --check
node tools/verify-hub.mjs
```

版本号唯一来源仍为 `app/build.gradle.kts` 的 `versionName`。每次 APK 更新将补丁版本与 `versionCode` 各加一，再运行 `node tools/sync-version.mjs`，自动重建生成资产。模块职责与依赖规则见 `docs/FRONTEND_MODULES.md`。

`src/vendor/katex/` 是 KaTeX 的唯一源码，构建时复制到 `assets/katex/` 并以相对路径引用。WebView 的 `file:///android_asset/hub.html`、包名、签名与 localStorage 主键保持稳定，模块拆分不触发数据迁移。

## 数据存储位置

应用内所有数据（角色、记忆、消息、设置）存于 WebView 的 localStorage，落在应用私有目录：

```
/data/data/com.deeptalking.lite/app_webview/Local Storage/leveldb/
```

- 主键：`deeptalking_data_v1`
- 该目录仅应用可访问，**卸载或清除应用数据会全部丢失**
- 备份：应用内“导出”下载 JSON；恢复：应用内“导入”选择 JSON 文件
- 注意：导出走 `blob:` 下载在 WebView 内可能被拦截，请使用导出弹窗中的“复制全文”按钮粘贴保存

## 其他

- `usesCleartextTraffic="true"`：保留局域网/HTTP 代理（如本机 127.0.0.1:8787）连接能力
- 返回键：页面可返回时先回退历史
