# 原生模块与构建

## 形态

DeepTalking 是原生 Android 应用（Kotlin + Jetpack Compose 多模块），入口在 `android-lite/`。WebView 仅作为富文本/公式渲染子系统（`:feature:richtext` 内嵌 KaTeX）。旧 WebView 单文件前端（`src/`、`tools/`、`hub.html`）为历史遗留，只作行为/数据参照，不再是运行入口。

## 模块清单

| 模块 | 层 | 职责 |
|---|---|---|
| `:app` | application | `MainActivity`（Compose）、`AppRoot`、`AppViewModel`、`NativeCore`（手动 DI）、`DeepTalkingApp` |
| `:core:common` | core | 结果类型、文本工具、`AppLimits`（从旧前端迁移的常量/上限） |
| `:core:model` | core | 领域模型：`Character/GroupMember`、`ChatMessage`、`LongTermMemory/ShortTermMemory`、`LorebookEntry`、`Sticker`、`AppConfig` |
| `:core:network` | core | `ResponsesLlmBackend`（OkHttp + SSE，实现 `LlmBackend`）、`WebContentProvider`/`HttpWebContentProvider`（web_search/web_fetch/B站） |
| `:core:database` | core | Room 实体/DAO/数据库（角色、消息、配置的 JSON 聚合存储） |
| `:core:data` | core | 仓库层（`CharacterRepository/ChatRepository/ConfigRepository`）、`SettingsStore`（DataStore）、`CoreDataContainer`、`legacy`（旧数据迁移与备份） |
| `:core:security` | core | `SecretStore`：API Key 存 Keystore/EncryptedSharedPreferences |
| `:core:notifications` | core | 通知渠道、`ReminderWorker`、`Reminders`（WorkManager 调度） |
| `:core:designsystem` | core | `DeepTalkingTheme` + `AppTheme`（清浅/夜色/深海/旧灯 四套固定配色） |
| `:domain:agent` | domain | `AgentTool/ToolRegistry`、`ChatOrchestrator`、`AgentLoop`、提示词装配（`prompts/`）、结构化解析（`ResponseParser`）、工具实现（`tools/`） |
| `:domain:memory` | domain | `MemoryService` + `MemoryServiceImpl`、检索/世界书匹配/记忆策略/时间工具 |
| `:engine:ondevice` | engine | 端侧推理接口：`LlmBackend/EmbeddingBackend/AsrBackend/TtsBackend` + `InferenceRegistry` |
| `:feature:chat` | feature | 聊天界面：头像/气泡（尾角圆角）/流式打字/工具活动/快速回应/图片/表情包（含标签编辑）/消息操作（复制、编辑重发、重新生成）/⚡📖 状态改动提示/图片灯箱 + `RichText`（渲染 WebView） |
| `:feature:characters` | feature | 角色/群组列表、创建弹窗（单角色/群组 + 一句话生成）、角色卡编辑器（基础/当前状态/世界书/**记忆** 四标签，世界书含 常驻/启用、AI emoji 头像、成员一句话补全）、升级为群组、导出/导入、补全头像 |
| `:feature:memory` | feature | （已并入角色卡「记忆」标签；本模块保留供扩展） |
| `:feature:settings` | feature | 平台下拉、Base URL、模型、Temperature、流式、思考强度、API Key、测试连接、测试提醒、调试信息（最近回应/用量/缓存）+ 复制 |
| `:feature:richtext` | feature | `MarkdownRenderer`（纯 Kotlin）+ `RichTextWebView`（KaTeX 早渲染） |

依赖方向：`app → feature → domain → core/engine`。core/engine 不反依赖上层。

## 关键接口

- 端侧推理（`:engine:ondevice`）：调用方只依赖接口，新增模型 = 新增实现 + 在 `NativeCore` 注册。当前仅 `LlmBackend` 有实现；`EmbeddingBackend/AsrBackend/TtsBackend` 暂为 `null`（TTS 已解禁，但只保留接口，实现另行评审）。
- Agent 工具（`:domain:agent`）：实现 `AgentTool` 接口并在 `defaultTools()` 注册即可，`AgentLoop` 不变。
- 记忆（`:domain:memory`）：上层依赖 `MemoryService` 接口。

## 界面与交互（对齐旧版 WebView）

- 应用骨架对齐旧版 `shell.html`：顶部 header（头像 + 名称 + 身份 + 版本徽标）+ 左侧抽屉侧栏（**角色 / 设置** 两标签）+ 主聊天区。无独立「记忆」页（记忆并入角色卡第 4 标签）。
- 四套主题（清浅/夜色/深海/旧灯）由 `AppConfig.activeTheme` 驱动，顶栏色块菜单切换。
- 气泡：`max-width min(84%,560)`、圆角 12dp + 尾角 4dp、无阴影；头像 30dp 圆角方块；typing 三点；消息操作与 ⚡/📖 提示对齐旧版。
- 交互回退回旧版：角色**主动开口**为面板空闲 **60s** 自动触发（随应用可见性暂停/重置），非手动按钮；编辑重发/重新生成会**丢弃后续分支及相关记忆**。
- 设置区含调试信息面板；头部缓存命中条由 `AppConfig.requestMetrics` 提供（无命中数据显示「缓存未返回命中数据」）。

## 数据与迁移

- 数据唯一入口：`:core:data` 仓库层（Room + DataStore）。禁止绕过仓库访问数据库。
- 旧版 WebView 的 localStorage 数据通过 `legacy/LegacyImportService` 一次性导入；`legacy/BackupService` 保留旧 JSON 根结构（`{config, characters, activeCharacterId, activeTheme, version}`）的导入导出。UI 通过系统文件选择器（SAF）导出/导入备份，`activeTheme` 随备份往返。
- 媒体/表情以文件（`filesDir`）存放（`app/MediaStore`：图片压到 ≤1280px/2MB，表情 ≤384px/120KB），不再以 base64 进库。
- API Key 存 `:core:security` 的 `SecretStore`（Keystore），UI 只见脱敏状态；导出时剔除。

## 构建与自检

统一走 `tools/` 下的原生脚本（自动解析 JDK17/Android SDK/Gradle，见 `tools/native/env.ps1`）：

```powershell
powershell -File tools/native-verify.ps1    # 全量自检：编译 + 全部单测
powershell -File tools/native-test.ps1      # 仅单测
powershell -File tools/native-build.ps1     # debug
powershell -File tools/native-build.ps1 -Release   # 需 keystore 环境变量
powershell -File tools/native-version.ps1 [-BumpPatch]
```

等价于在 `android-lite/` 下用 JDK17 / Android SDK35 运行：
`gradle :app:assembleDebug` + `gradle :engine:ondevice:test :domain:agent:test :domain:memory:test :core:data:testDebugUnitTest :feature:richtext:testDebugUnitTest`。

工具链本地路径（开发机）：JDK17 `D:\devtools\temurin17\jdk-17.0.20.1+1`、SDK `D:\Android`（`local.properties` 的 `sdk.dir`）、Gradle `D:\devtools\gradle\gradle-8.10.2`。可用 `JAVA_HOME` / `DEVTOOLS_JDK17` / `ANDROID_SDK_ROOT` / `DEVTOOLS_GRADLE` 覆盖。

## CI

CI（`.github/workflows/build-lite-apk.yml`）用固定 release keystore 构建签名的 release APK 并发布。包名、WebView 渲染入口、存储键与签名的稳定性是覆盖升级的兼容要求。
