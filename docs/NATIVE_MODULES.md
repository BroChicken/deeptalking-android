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
| `:domain:agent` | domain | `AgentTool/ToolRegistry`、`ChatOrchestrator`、`AgentLoop`、提示词装配（`prompts/`）、结构化解析（`ResponseParser`）、工具实现（`tools/`）、后台任务（`background/BackgroundTasks`：文风校对、快速回应换位生成、记忆抽取/分析、场景概要、世界书整理、空字段补全、字段/世界书迁移） |
| `:domain:memory` | domain | `MemoryService` + `MemoryServiceImpl`、检索/世界书匹配/记忆策略/时间工具、`MemoryEvidence`（证据与静态修改意图校验、自学习重要度）、`LorebookStore`（世界书写入/合并/来源校验/去重） |
| `:engine:ondevice` | engine | 端侧推理接口：`LlmBackend/EmbeddingBackend/AsrBackend/TtsBackend` + `InferenceRegistry` |
| `:feature:chat` | feature | 聊天界面：头像/气泡（尾角圆角）/流式打字/工具活动/快速回应/图片/表情包（含标签编辑）/消息操作（复制、编辑重发、重新生成）/⚡📖 状态改动提示/图片灯箱。**性能**：消息仅在有公式时才用 `RichTextWebView`，其余走纯 Compose `RichText`（`renderMarkdownAnnotated`），配合稳定 `key` 保证滚动顺滑 |
| `:feature:characters` | feature | 角色/群组列表（群组展开成员子行，可直接编辑成员）、创建弹窗（单角色/群组 + 一句话生成）、角色卡编辑器（基础/当前状态/世界书 **三** 标签，不再有记忆可视化页；世界书每条含 **启用** + **常驻** 两勾选、条目名、命中次数/最近命中时间、来源标签）、AI emoji 头像、成员一句话补全、升级为群组、导出/导入、补全头像（含群组成员） |
| `:feature:settings` | feature | 平台下拉（显示名；切换时按平台独立保存 Base URL/模型/API Key）、Base URL、模型（可输入 + 预设建议）、Temperature、流式、思考强度、API Key、测试连接、测试提醒、调试信息（最近回应/用量/缓存）+ 复制 |
| `:feature:richtext` | feature | `MarkdownRenderer`（纯 Kotlin）+ `RichTextWebView`（KaTeX 早渲染） |

依赖方向：`app → feature → domain → core/engine`。core/engine 不反依赖上层。

## 关键接口

- 端侧推理（`:engine:ondevice`）：调用方只依赖接口，新增模型 = 新增实现 + 在 `NativeCore` 注册。当前仅 `LlmBackend` 有实现；`EmbeddingBackend/AsrBackend/TtsBackend` 暂为 `null`（TTS 已解禁，但只保留接口，实现另行评审）。
- Agent 工具（`:domain:agent`）：实现 `AgentTool` 接口并在 `defaultTools()` 注册即可，`AgentLoop` 不变。
- 记忆（`:domain:memory`）：上层依赖 `MemoryService` 接口。

## 界面与交互（对齐旧版 WebView）

- 配色**逐值对齐** `src/styles/app.css`：`core:designsystem` 暴露 `LegacyColors`（`--bg/--text/--bubble-*/--sidebar/--input-*` 等全部 CSS 变量，四主题各一套），UI 直接用原值，不再依赖 Material 语义色派生，从根上避免"文字与气泡颜色相近看不清"。
- 应用骨架对齐旧版 `shell.html`：顶部 header（hamburger 42dp + 头像 32 + 名称/身份 + 主题菜单 + 版本徽标，高 64dp，随状态栏 inset）+ 左侧抽屉侧栏 **258dp**（**角色 / 设置** 两标签）+ 主聊天区。缓存条 / 活动状态为 header 右下角小胶囊（对齐 `#cacheStatsBar` / `#activityStatusBar`）。
- 消息区：`max-width 56rem` 居中；气泡 `max-width min(84%,560)`、内距 `12px 14px`、圆角 12 + 尾角 4、无阴影、字号 15、行高 1.68；AI 头像 30dp 圆角方块（用户消息无头像）；typing 三点；消息 meta 行（时间 + 30dp 圆形操作按钮）。
- 交互回退回旧版：角色**主动开口**为面板空闲 **60s** 自动触发（随应用可见性暂停/重置），非手动按钮；编辑重发/重新生成会**丢弃后续分支及相关记忆**。
- 世界书条目受 `enabled` 控制：禁用的条目不参与注入（`LorebookMatcher.select` 过滤），与「常驻」互不影响。

## 数据与迁移

- 数据唯一入口：`:core:data` 仓库层（Room + DataStore）。禁止绕过仓库访问数据库。
- 旧版 WebView 的 localStorage 数据通过 `legacy/LegacyImportService` 一次性导入；`legacy/BackupService` 保留旧 JSON 根结构（`{config, characters, activeCharacterId, activeTheme, version}`）的导入导出，导出时每个角色的会话从聊天表（`ChatRepository`）读入 `memory.instant`，不再依赖 `character.instant`。UI 通过系统文件选择器（SAF）导出/导入备份。
- 每平台配置槽 `AppConfig.platformSettings` 保存非密的 Base URL/模型；API Key 走 `SecretStore` 的按平台键（切换平台互不覆盖）。
- 媒体/表情以文件（`filesDir`）存放（`app/MediaStore`：图片压到 ≤1280px/2MB，表情 ≤384px/120KB），不再以 base64 进库。
- API Key 存 `:core:security` 的 `SecretStore`（Keystore），UI 只见脱敏状态；导出时剔除。

## 记忆维护任务（对齐旧版 `src/js/memory/tasks.js`）

每轮后台串行执行（`BackgroundTasks.scheduleTurn` → `runMemoryMaintenance`），均可用假后端单测：

- **自适应重要度** `selfLearnMemoryImportance`（`:domain:memory`）：30 天内被使用过的记忆按 `usageCount/2` 升权（封顶 +3）；userProfile/habits 45/90/180 天未召回逐档降到 -3（只降权不删除）。召回时累计 `usageCount`/`lastUsageAt`。
- **场景概要** `summarizeScene`：场景切换（`currentLocation` 变化）或超过 `Scene.SPAN`(24) 条消息且 ≥6 条时，压缩为一条 `scenes`（保留最近 `Scene.SUMMARIES`(2) 条）。游标存 `character.sceneState`。
- **世界书整理** `consolidateLorebook`：`shortTerm` 有未整理项（`lorebookScannedAt == null`）且积压 ≥ `Lorebook.CONSOLIDATE_SPAN`(8) 或有已分析项时触发；从已沉淀的短期记忆里抽取世界层设定（≤`AUTO_ENTRIES_PER_PASS`(3) 条，`sourceShortTermIds` 必须可回溯），写前整批校验、失败不改标记；写后本地 `dedupeLorebook` 兜底并执行 `evictStaleLorebookEntries` 淘汰（AI 近重复合并、常驻/用户条目豁免）。
- **空字段补全** `autoFillStaticFields`：启动时静默补齐为空的静态字段（最多 3 个），只补空、绝不覆盖；退避用 `character.staticFillMeta`（成功清零，失败 5→30 分钟指数退避，无失败冷却 24h）。
- **一次性迁移** `migrateWorldLore`（旧 `description`/`background` 里的世界观拆成世界书条目，可选精简原文，标记 `lorebookMigratedAt`）与 `remapFields`（旧字段结构重排，标记 `fieldsMigrationVersion`）。启动时（有 API Key 且未勾选"以后不再询问"）由 `AppViewModel.checkMigrationPrompts` 弹`AlertDialog`询问——先"字段结构升级"，后"世界书整理"（含"同时精简原文"勾选）；确认走 `NativeCore.runWorldLoreMigration` / `remapCharacterFields`，"以后不再询问"写入 `SettingsStore`（键 `deeptalking_field_migration_skip_v1` / `deeptalking_lorebook_migration_skip_v1`，对应旧版 localStorage 跳过键）。

**证据与意图校验**（`MemoryEvidence`）：`hasValidUserEvidence` 要求 `sourceMessageIds` 解析为真实用户消息且 `evidence` 逐字出现；`hasStaticEditIntent` 要求用户原话明确在要求修改该字段。`update_memory` 与 `update_character_field` 工具据此拒绝无据写入；自动写入冲突时 `memoriesSemanticallyDiffer`（token 重叠 <0.35）阻止静默覆盖。

## 构建与自检

统一走 `tools/` 下的原生脚本（自动解析 JDK17/Android SDK/Gradle，见 `tools/native/env.ps1`）：

```powershell
powershell -File tools/native-verify.ps1    # 全量自检：静态检查 + 编译 + 全部单测
powershell -File tools/native-test.ps1      # 仅单测
powershell -File tools/native-build.ps1     # debug
powershell -File tools/native-build.ps1 -Release   # 需 keystore 环境变量
powershell -File tools/native-version.ps1 [-BumpPatch]
```

等价于在 `android-lite/` 下用 JDK17 / Android SDK35 运行：
`gradle :app:assembleDebug` + `gradle :engine:ondevice:test :domain:agent:test :core:network:test :domain:memory:test :core:data:testDebugUnitTest :feature:richtext:testDebugUnitTest`。

工具链本地路径（开发机）：JDK17 `D:\devtools\temurin17\jdk-17.0.20.1+1`、SDK `D:\Android`（`local.properties` 的 `sdk.dir`）、Gradle `D:\devtools\gradle\gradle-8.10.2`。可用 `JAVA_HOME` / `DEVTOOLS_JDK17` / `ANDROID_SDK_ROOT` / `DEVTOOLS_GRADLE` 覆盖。

## CI

CI（`.github/workflows/build-lite-apk.yml`）用固定 release keystore 构建签名的 release APK 并发布。包名、WebView 渲染入口、存储键与签名的稳定性是覆盖升级的兼容要求。
