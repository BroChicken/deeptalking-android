# DeepTalking Lite (android-lite)

DeepTalking 的轻量 Android 封装：单个 WebView 直接加载 `assets/hub.html`（最新稳定版，由 `hub_1.html` 同步而来）。

- 包名：`com.deeptalking.lite`
- 显示名：DeepTalking
- 无 TTS、无第三方依赖（纯原生 WebView）
- 开启 `allowUniversalAccessFromFileURLs` + `allowFileAccessFromFileURLs`，**关闭 CORS 限制**：
  - DeepSeek：`https://api.deepseek.com/v1` 直连
  - OpenCode Go：`https://opencode.ai/zen/go/v1` 直连（无需本地/Cloudflare 代理）
  - 两者均需在应用内设置中填入对应 API Key

## 构建

```bash
cd android-lite
gradle :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

CI：推送 `android-lite/**` 到 `main`/`cloud-main` 自动构建，产物上传到 GitHub Actions Artifacts（无 TTS 下载步骤）。

## 更新资产（每次出包前）

把最新的开发版 `hub_1.html` 同步为 APK 内稳定版：

```bash
Copy-Item hub_1.html android-lite/app/src/main/assets/hub.html
# katex/ 同步（引用了 katex/katex.min.css）
Copy-Item katex android-lite/app/src/main/assets/katex -Recurse
```

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
