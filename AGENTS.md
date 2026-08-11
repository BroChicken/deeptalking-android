# 开发规则（必须遵守）

## 1. 试验失败品（不予理睬，不再开发/使用/扩展）
| 功能 | 位置 | 结论 |
|---|---|---|
| **TTS 语音合成** | `archive/experimental_failures/tts/`（含 kokoro/matcha 等候选模型） | 试验失败品：模型体积大、合成不稳定、体验差；**不集成、不维护** |
| **像素头像**（16×16 网格） | `archive/experimental_failures/pixel_test/` | 试验失败品：模型输出网格常缺行/缺色/尺寸错误；**不再渲染**，头像统一走 emoji，代码中仅保留 `normalizePixelAvatar` 做历史数据兼容 |

## 2. 每次开发只改两个文件，且内容必须一致
- 开发主文件：`hub_1.html`（Web 版/开发版）
- APK 内文件：`android-lite/app/src/main/assets/hub.html`
- **任何对 hub_1.html 的改动，必须同步复制到 hub.html，两份文件字节级一致，否则禁止提交。**

## 3. 版本号规则
- `android-lite/app/build.gradle.kts` 中 `versionName` 为 `X.Y.Z`，`versionCode` 单调递增。
- 每次 APK 更新：**版本号最后一位 Z 必须 +1**（如 1.0.0 → 1.0.1）。
- 前两位 X.Y 由用户决定是否升级；**若我认为需要升 X 或 Y，必须先询问用户**，不得擅自修改。
- `versionCode` 必须同步 +1。

## 4. 每次更新后要支持直接安装包覆盖升级
- 签名必须稳定一致，否则用户手机会报"签名冲突/与现有应用签名不一致"无法覆盖安装。
- 当前构建在 GitHub Actions 上跑 `assembleDebug`，使用 CI 环境临时生成的 debug keystore，签名每次不同 → 必须改为固定的签名配置（详见 android-lite/README 或 workflow）。
