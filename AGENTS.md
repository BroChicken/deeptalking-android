# 开发规则（必须遵守）

## 0. 项目形态（已迁移为原生 Android）
- 项目是**原生 Android 应用（Kotlin + Compose 多模块）**，位于 `android-lite/`。WebView 只保留为富文本/公式渲染子系统（`:feature:richtext`，内嵌 KaTeX）。
- **唯一开发入口是 `android-lite/` 下的 Kotlin 模块源码**（见 `android-lite/settings.gradle.kts` 的模块清单）。
- **`src/` 是已冻结的历史前端快照**（`src/js/`、`src/styles/`、`src/shell.html`、`src/manifest.json`）：不再是运行入口，不再作为开发/修复目标，也不再作为行为参照——**与旧 WebView 的差分对照已下线**（原 `tools/diff/run-js.mjs` 及对应测试已删除），当前实现以原生版本为唯一基准。该目录下没有任何构建/CI/打包步骤会读取它，保留只为翻阅历史。曾在此的 `src/vendor/katex` 已迁到 `android-lite/feature/richtext/src/main/assets/katex`。

## 1. 试验失败品
| 功能 | 位置 | 结论 |
|---|---|---|
| **像素头像**（16×16 网格） | 已从代码库移除（`core:model` 的 `Character` 无该字段，导入时直接丢弃） | 失败品：网格常缺行/缺色；不再渲染，头像统一 emoji |
| **TTS 语音合成** | 曾归档于 `archive/experimental_failures/tts/` | **已解禁并落地**。端侧 `:engine:cosyvoice`（CosyVoice3 GGUF）已完整实现；另有可选的远程引擎 `:engine:remote-tts`（PC 合成 / 手机播放，链路见 `docs/REMOTE_TTS.md`）。朗读统一经 `:engine:ondevice` 的 `TtsSpeaker` 接口，引擎由 `AppConfig.ttsBackend` 选择 |

## 2. 模块架构与接口（必须遵守）
- 端侧推理能力统一走 `:engine:ondevice` 的接口：`LlmBackend / EmbeddingBackend / AsrBackend / TtsBackend`，由 `InferenceRegistry` 聚合；朗读播放另走同模块的 `TtsSpeaker`。**调用方只依赖接口**；新增端侧模型 = 新增实现 + 在 `:app` 的 `NativeCore` 注册，不改上层。`LlmBackend` 由 `:core:network` 的 `ResponsesLlmBackend` 实现，`TtsSpeaker` 由 `:engine:cosyvoice`（端侧）与 `:engine:remote-tts`（远程）两个实现按配置分派；`EmbeddingBackend/AsrBackend` 为 `null`。
- Agent 工具走 `:domain:agent` 的 `AgentTool` + `ToolRegistry`。**新增工具 = 新增类 + 注册**，不改 `AgentLoop`。
- 数据唯一入口是 `:core:data` 的仓库层（Room + DataStore）。**禁止绕过仓库直接访问数据库**。
- API Key 存 `:core:security` 的 `SecretStore`（Keystore）。**禁止把密钥写入数据库、日志或导出文件**。
- 分层依赖方向：`app → feature → domain → core/engine`；core/engine 不反依赖上层。
- **文档：`docs/` 必须同步更新**（凡触及 agent 循环、工具清单、提示词、记忆系统、字段、常量、模块结构、构建/测试流程的改动）。
  文档只描述"当前最新状态"，**禁止写变更记录/版本历史**（历史看 git）。

## 3. 版本号与推送规则
- `android-lite/app/build.gradle.kts` 中 `versionName` 为 `X.Y.Z`，`versionCode` 单调递增。
- **默认：改完只做本地提交** —— 不升版本、不推送、不出包。改完跑 `tools/native-verify.ps1`，本地 `git commit` 即止，停下等用户指示。
- **只有用户明确说"推版本"（或"推送 / 出包"）时**才升版本并出包，一次做完：
  1. `powershell -File tools/native-version.ps1 -BumpPatch`（Z+1、`versionCode`+1）；
  2. `powershell -File tools/native-verify.ps1` 全绿；
  3. **本地出包并发布**：`powershell -File tools/native-build.ps1 -Release -Publish`
     —— 本机 keystore 签名构建 release APK，归档 `apk/DeepTalking-lite-v<版本>.apk`，
     再用 `gh` 把**这个本地包**创建/覆盖上传到 GitHub Release（tag `v<版本>`）；
  4. 提交版本改动并 `git push`。
  - **如果 `git push` 因网络不可达失败**：不要反复重试、不要改写历史或强推；保留本地提交，向用户报告提交 SHA 和「推送失败」，等网络恢复后再推即可。
  CI（`.github/workflows/verify-lite.yml`）只跑「编译 + 单测」当门禁，**不出包、不发布**。
- **版本号没变就不要出包**：Release 用 `versionName` 当标签，同名 Release 已存在时 `-Publish` 会 `--clobber` 覆盖已发布的 APK（等于把同版本的包换掉）。
- 升 X 或 Y 由用户决定，**若我认为需要升 X 或 Y，必须先询问用户**。
- 应用内版本号由 BuildConfig/`versionName` 提供，不要另设手写常量。

## 4. 覆盖升级
- `applicationId`、签名 keystore（`android-lite/keystore/deeptalking-release.jks`，CI secret `ANDROID_KEYSTORE_BASE64`）必须稳定，否则无法覆盖安装。
- **数据兼容**：旧 WebView 的数据需**先在旧版导出 JSON、再由原生经系统文件选择器导入**（`:core:data` 的 `legacy/LegacyImportService`；原生不读取 WebView 的 localStorage）；`BackupService` 保留旧 JSON 结构的导入导出。改动仓库/模型时必须保持这条迁移链可用。

## 5. 改完必跑的自检（全部通过才算完成）
统一入口：`powershell -File tools/native-verify.ps1`（自动解析 JDK17/Android SDK/Gradle，见 `tools/native/env.ps1`）。
它等价于：
0. `gradle :app:assembleDebug` —— 全模块编译通过。
1. `gradle :engine:ondevice:test :engine:audio:testDebugUnitTest :engine:cosyvoice:testDebugUnitTest :engine:remote-tts:testDebugUnitTest :domain:agent:test :core:network:test :domain:memory:test :core:data:testDebugUnitTest :core:security:testDebugUnitTest :core:model:test :app:testDebugUnitTest :feature:characters:testDebugUnitTest :feature:chat:testDebugUnitTest :feature:settings:testDebugUnitTest :feature:richtext:testDebugUnitTest` —— 单元测试全绿。
   - 新增工具/记忆/解析/迁移逻辑时必须同步补测试。
2. 端侧接口新增实现时，在 `:engine:ondevice` 或对应模块补一个"假后端可插拔"测试。
3. `docs/`（尤其 `docs/AGENT_ARCHITECTURE.md`、`docs/NATIVE_MODULES.md`）已同步更新。
4. 出包即 `powershell -File tools/native-build.ps1 -Release -Publish`（需 keystore 环境变量 + `gh` 已登录）：本机签名打包、归档到 `apk/`，并把该包上传到 GitHub Release。

`tools/` 只保留原生侧脚本：`native-verify.ps1`（全量自检）、`native-test.ps1`（单测）、`native-build.ps1`（打包；`-Release -Publish` = 本地出包并上传 GitHub Release）、`native-version.ps1`（版本号）、`pull-apk.ps1`（拉取发布包）、`build-cosyvoice-android.ps1`（重建 CosyVoice 原生库），以及 `tools/native/`（环境解析）。另有一个 PC 侧远程朗读启动器 `run-cosyvoice-server.bat`（只是包装 `tools/.work/cosyvoice-server/run-server.ps1`，见 `docs/REMOTE_TTS.md`）。旧 WebView 构建/测试脚本与 JS↔Kotlin 差分 oracle（`tools/diff/`）均已删除。
