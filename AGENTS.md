# 开发规则（必须遵守）

## 0. 项目形态（已迁移为原生 Android）
- 项目是**原生 Android 应用（Kotlin + Compose 多模块）**，位于 `android-lite/`。WebView 只保留为富文本/公式渲染子系统（`:feature:richtext`，内嵌 KaTeX）。
- **唯一开发入口是 `android-lite/` 下的 Kotlin 模块源码**（见 `android-lite/settings.gradle.kts` 的模块清单）。
- **`src/`（旧 WebView 单文件前端）、`tools/build-hub.mjs`、`hub.html`、`assets/hub.html`、`assets/katex/` 为历史遗留**：仅作行为/数据参照，不再是运行入口，不再作为开发/修复目标。旧构建脚本与旧 JS 不再维护。

## 1. 试验失败品
| 功能 | 位置 | 结论 |
|---|---|---|
| **像素头像**（16×16 网格） | `archive/experimental_failures/pixel_test/` | 失败品：网格常缺行/缺色；不再渲染，头像走 emoji |
| **TTS 语音合成** | 曾归档于 `archive/experimental_failures/tts/` | **已解禁**：不再是禁区。目标为端侧本地 TTS，但**当前只保留接口**（`:engine:ondevice` 的 `TtsBackend`），具体实现需单独立项评审后再做 |

## 2. 模块架构与接口（必须遵守）
- 端侧推理能力统一走 `:engine:ondevice` 的接口：`LlmBackend / EmbeddingBackend / AsrBackend / TtsBackend`，由 `InferenceRegistry` 聚合。**调用方只依赖接口**；新增端侧模型 = 新增实现 + 在 `:app` 的 `NativeCore` 注册，不改上层。当前仅 `LlmBackend` 有实现（`:core:network` 的 `ResponsesLlmBackend`），其余为 `null`。
- Agent 工具走 `:domain:agent` 的 `AgentTool` + `ToolRegistry`。**新增工具 = 新增类 + 注册**，不改 `AgentLoop`。
- 数据唯一入口是 `:core:data` 的仓库层（Room + DataStore）。**禁止绕过仓库直接访问数据库**。
- API Key 存 `:core:security` 的 `SecretStore`（Keystore）。**禁止把密钥写入数据库、日志或导出文件**。
- 分层依赖方向：`app → feature → domain → core/engine`；core/engine 不反依赖上层。
- **文档：`docs/` 必须同步更新**（凡触及 agent 循环、工具清单、提示词、记忆系统、字段、常量、模块结构、构建/测试流程的改动）。
  文档只描述"当前最新状态"，**禁止写变更记录/版本历史**（历史看 git）。

## 3. 版本号规则
- `android-lite/app/build.gradle.kts` 中 `versionName` 为 `X.Y.Z`，`versionCode` 单调递增。
- 每次 APK 更新：**Z 必须 +1**；前两位 X.Y 由用户决定，**若我认为需要升 X 或 Y，必须先询问用户**。
- `versionCode` 必须同步 +1。应用内版本号由 BuildConfig/`versionName` 提供，不要另设手写常量。

## 4. 覆盖升级
- `applicationId`、签名 keystore（`android-lite/keystore/deeptalking-release.jks`，CI secret `ANDROID_KEYSTORE_BASE64`）必须稳定，否则无法覆盖安装。
- **数据兼容**：旧版 WebView 的 localStorage 数据通过 `:core:data` 的 `LegacyImportService`（`legacy/`）一次性导入；`BackupService` 保留旧 JSON 结构的导入导出。改动仓库/模型时必须保持这条迁移链可用。

## 5. 改完必跑的自检（全部通过才算完成）
统一入口：`powershell -File tools/native-verify.ps1`（自动解析 JDK17/Android SDK/Gradle，见 `tools/native/env.ps1`）。
它等价于：
0. `gradle :app:assembleDebug` —— 全模块编译通过。
1. `gradle :engine:ondevice:test :domain:agent:test :domain:memory:test :core:data:testDebugUnitTest :feature:richtext:testDebugUnitTest` —— 单元测试全绿。
   - 新增工具/记忆/解析/迁移逻辑时必须同步补测试。
2. 端侧接口新增实现时，在 `:engine:ondevice` 或对应模块补一个"假后端可插拔"测试。
3. `docs/`（尤其 `docs/AGENT_ARCHITECTURE.md`、`docs/NATIVE_MODULES.md`）已同步更新。
4. 发布前跑 `powershell -File tools/native-build.ps1 -Release`（需 keystore 环境变量）确认签名打包成功。

`tools/` 只保留原生侧脚本：`native-verify.ps1`（全量自检）、`native-test.ps1`（单测）、`native-build.ps1`（打包）、`native-version.ps1`（版本号）、`pull-apk.ps1`（拉取发布包）。旧 WebView 构建/测试脚本已删除。
