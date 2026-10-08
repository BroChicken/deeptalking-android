# 原生模块与构建

## 形态

DeepTalking 是原生 Android 应用（Kotlin + Jetpack Compose 多模块），入口在 `android-lite/`。WebView 仅作为富文本/公式渲染子系统（`:feature:richtext` 内嵌 KaTeX）。旧 WebView 单文件前端（`src/`、`tools/`、`hub.html`）为历史遗留，只作行为/数据参照，不再是运行入口。

## 模块清单

| 模块 | 层 | 职责 |
|---|---|---|
| `:app` | application | `MainActivity`（Compose）、`AppRoot`、`AppViewModel`、`NativeCore`（手动 DI）、`DeepTalkingApp` |
| `:core:common` | core | 结果类型、文本工具、`AppLimits`（从旧前端迁移的常量/上限） |
| `:core:model` | core | 领域模型：`Character/GroupMember`、`ChatMessage`、`LongTermMemory/ShortTermMemory`、`LorebookEntry`、`Sticker`、`AppConfig`、`TtsPhase`（朗读相位枚举）、`extractSpeechText`（回复去括号动作/`【】`/Markdown 标记，仅留台词，供朗读） |
| `:core:network` | core | `ResponsesLlmBackend`（OkHttp + SSE，实现 `LlmBackend`）、`WebContentProvider`/`HttpWebContentProvider`（web_search/web_fetch/B站） |
| `:core:database` | core | Room 实体/DAO/数据库（角色、消息、配置的 JSON 聚合存储） |
| `:core:data` | core | 仓库层（`CharacterRepository/ChatRepository/ConfigRepository`）、`SettingsStore`（DataStore）、`CoreDataContainer`、`legacy`（旧数据迁移与备份） |
| `:core:security` | core | `SecretStore`：API Key 存 Keystore/EncryptedSharedPreferences |
| `:core:notifications` | core | 通知渠道、`ReminderWorker`、`Reminders`（WorkManager 调度） |
| `:core:designsystem` | core | `DeepTalkingTheme` + `AppTheme`（清浅/夜色/深海/旧灯 四套固定配色） |
| `:domain:agent` | domain | `AgentTool/ToolRegistry`、`ChatOrchestrator`、`AgentLoop`、提示词装配（`prompts/`）、结构化解析（`ResponseParser`）、工具实现（`tools/`）、后台任务（`background/BackgroundTasks`：文风校对、快速回应换位生成、记忆抽取/分析、场景概要、世界书整理、空字段补全、字段/世界书迁移） |
| `:domain:memory` | domain | `MemoryService` + `MemoryServiceImpl`、检索/世界书匹配/记忆策略/时间工具、`MemoryEvidence`（证据与意图校验、自学习重要度、自动写入合法性判定、promise/dynamic 来源解析）、`MemoryPolicy`（记忆衰减/淘汰、短/长期裁剪、冲突保留与裁决）、`LorebookStore`（世界书写入/合并/来源校验/去重/生成条目规范化）、`RelativeTimeMigration`（旧相对时间一次性转绝对日期） |
| `:engine:ondevice` | engine | 端侧推理接口：`LlmBackend/EmbeddingBackend/AsrBackend/TtsBackend` + `InferenceRegistry` |
| `:engine:cosyvoice` | engine | 端侧 TTS：社区 `cosyvoice.cpp`（GGML）+ `Fun-CosyVoice3-0.5B` GGUF + ONNX 音色前端，经 JNA 绑定 `TtsBackend`。`CosyVoiceModelManager`（ModelScope 按需下载）、`CosyVoiceEngine`（加载模型/编码音色/合成）、`CosyVoiceController`（导入音色、朗读播放）、`PcmPlayer`（AudioTrack 流式播放）。**导入音频**：`AudioLoader` 用 `MediaExtractor + MediaCodec` 流式解码任意格式（mp3/m4a/aac/ogg/opus/flac/webm/wav，含 24-bit/float），只保留滑动窗口、不整文件进内存；`VoiceSegment`/`SegmentPicker` 用能量 VAD 自动挑最响的连续人声窗（目标 ~3s、最短 2s，掐头尾静音并峰值归一化）；无 `MediaCodec` 时回退 `Wav`（支持 8/16/24/32-bit int 与 32-bit float）。**音色命名**：`VoiceStore` 以 `voices/index.json` 维护「文件名 → 显示名/builtin」，可重命名、删除用户音色；内置 `assets/voices/sample_1.wav`/`sample_2.wav`（CosyVoice 官方示例，Apache-2.0）在模型就绪后编码为「内置音色 1/2」。**注意** `prompt_speech` GGUF 必须含 `text` 张量，故参考文本留空时用占位符编码，且写完立即回读校验（`canLoadPromptSpeech`），失败即删文件，避免产生无法加载的音色。原生 `.so`（arm64）随包：`libcosyvoice/libggml/libggml-cpu/libggml-base/libonnxruntime/libomp/libc++_shared`（`libggml*` 依赖 `libomp.so`，缺一会 `dlopen` 失败），模型运行时下载。**性能**：`libggml*` 必须以 `-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` 交叉编译（见 `tools/build-cosyvoice-android.ps1`），否则 ggml 回退到 armv8-a 基线，无 `sdot`/`fmla.8h`，Q4_K 与 F16（Flow/HiFiGAN）算子跑慢速路径，合成慢数倍且耗时抖动。**线程数**：`CosyVoiceEngine.loadModel` 经 `cosyvoice_load_from_file_ext(..., n_threads, ...)` 显式设定线程数（`CpuTopology.performanceCoreCount` 取最高频簇核数、且不低于总核数一半），避免 big.LITTLE 上把负载均摊到小核而拖慢（上游 `cosyvoice_load_from_file` 默认用满所有核）；无法读取频率或加载失败时回退默认（实测同机 4 性能核 RTF 4.8、6 核 5.6、8 核 8.2——把小核也拉进来反而显著变慢）。**朗读**：`speak(text, style, onPhase)` 为整段合成后一次性播放（曾试 `cosyvoice_tts_instruct_stream` 流式，因端侧合成慢、逐块出声体验差而回退），经 `onPhase` 上报 `TtsPhase.Synthesizing/Playing` 供气泡显示进度；`cancel()` 经 JNA 调原生 `cosyvoice_request_stop` 抢占正在进行的合成（须在非合成线程调用，会阻塞至作业退出；检查点见上游 LLM/flow/TTS 循环），配合 `PcmPlayer.stop()` 实现「同一时刻仅一个朗读、新请求抢占旧的」。每次合成打 `Log.i("CosyVoiceEngine", "synth: …RTF=…")` 便于实测。**构建期性能默认**（`tools/build-cosyvoice-android.ps1` 对上游源码打补丁；构建目录在仓库内 `tools/.work/cosyvoice-android-build`，git 忽略）：**`diffusion_steps` 10→3**（flow 是耗时大头、DiT 每扩散步跑一次前向，故与步数近似线性；同时把 `t_span` 噪声表改为按 `diffusion_steps` 覆盖 t∈[0,1]，否则末尾步只覆盖一小段轨迹；实测 RTF 6.7→4.8）、`inference_buffer_policy` 改为 `DEDICATED`、v1 加载分支 `dit_kv_fixed_slots` 设为 `2`（作者的流式 DiT KV 缓存方案，**仅流式生效**，当前非流式下不启用，保留以备提速后再开流式）。上游需 CMake ≥3.28 且 `vendor/pcre2`/`vendor/ggml` 子模块就位（`git` 直连 GitHub 不稳时可用 `codeload.github.com` 下 tarball 铺入）。 |
| `:feature:chat` | feature | 聊天界面：头像/气泡（尾角圆角）/流式打字/工具活动/快速回应/图片/表情包（含标签编辑）/消息操作（复制、编辑重发、重新生成）/⚡📖 状态改动提示/图片灯箱。**性能**：消息仅在有公式时才用 `RichTextWebView`，其余走纯 Compose `RichText`（`renderMarkdownAnnotated`），配合稳定 `key` 保证滚动顺滑 |
| `:feature:characters` | feature | 角色/群组列表（群组展开成员子行，可直接编辑成员）、创建弹窗（单角色/群组 + 一句话生成）、角色卡编辑器（基础/当前状态/世界书 **三** 标签，不再有记忆可视化页；世界书每条含 **启用** + **常驻** 两勾选、条目名、命中次数/最近命中时间、来源标签）、AI emoji 头像、成员一句话补全、升级为群组、导出/导入、补全头像（含群组成员）。纯解析/清洗助手集中在本模块 `CharacterParity` / `FieldCleaning`（供 `:app` 复用） |
| `:feature:settings` | feature | 平台下拉（显示名；切换时按平台独立保存 Base URL/模型/API Key）、Base URL、模型（可输入 + 预设建议）、Temperature、流式、思考强度、API Key、测试连接、测试提醒（各字段改动即时持久化，仍保留「保存设置」按钮）、**语音朗读**（开关/自动朗读、模型下载进度、导入任意音频（mp3/m4a/wav…）+音色命名、音色选择/重命名/删除、可选「高级：参考文本」、试听/停止）、调试信息（最近回应/用量/缓存）+ 复制 |
| `:feature:richtext` | feature | `MarkdownRenderer`（纯 Kotlin）+ `RichTextWebView`（KaTeX 早渲染）；行内 `![alt](url)` 图片点击经 `DeepTalking.openImage` 交回宿主展示应用内灯箱，普通外链仍走系统浏览器 |

依赖方向：`app → feature → domain → core/engine`。core/engine 不反依赖上层。

## 关键接口

- 端侧推理（`:engine:ondevice`）：调用方只依赖接口，新增模型 = 新增实现 + 在 `NativeCore` 注册。`LlmBackend` 由 `:core:network` 的 `ResponsesLlmBackend` 实现（远程）；`TtsBackend` 由 `:engine:cosyvoice` 的 `CosyVoiceController.backend` 实现（端侧 CosyVoice3）；`EmbeddingBackend/AsrBackend` 暂为 `null`。
- Agent 工具（`:domain:agent`）：实现 `AgentTool` 接口并在 `defaultTools()` 注册即可，`AgentLoop` 不变。
- 记忆（`:domain:memory`）：上层依赖 `MemoryService` 接口。

## 界面与交互（对齐旧版 WebView）

- 配色**逐值对齐** `src/styles/app.css`：`core:designsystem` 暴露 `LegacyColors`（`--bg/--text/--bubble-*/--sidebar/--input-*` 等全部 CSS 变量，四主题各一套），UI 直接用原值，不再依赖 Material 语义色派生，从根上避免"文字与气泡颜色相近看不清"。
- 应用骨架对齐旧版 `shell.html`：顶部 header（hamburger 42dp + 头像 32 + 名称/身份 + 主题菜单 + 版本徽标，高 64dp，随状态栏 inset）+ 左侧抽屉侧栏 **258dp**（**角色 / 设置** 两标签）+ 主聊天区。缓存条 / 活动状态为 header 右下角小胶囊（对齐 `#cacheStatsBar` / `#activityStatusBar`）。
- 消息区：`max-width 56rem` 居中；气泡 `max-width min(84%,560)`、内距 `12px 14px`、圆角 12 + 尾角 4、无阴影、字号 15、行高 1.68；AI 头像 30dp 圆角方块（用户消息无头像）；typing 三点；消息 meta 行（时间 + 30dp 圆形操作按钮）。
- 交互回退回旧版：角色**主动开口**为面板空闲 **60s** 自动触发（随应用可见性暂停/重置），非手动按钮；编辑重发/重新生成会**丢弃后续分支及相关记忆**。
- 世界书条目受 `enabled` 控制：禁用的条目不参与注入（`LorebookMatcher.select` 过滤），与「常驻」互不影响。
- 多模态：用户图片在请求装配时由 `LlmRequest.imageResolver` 解析为 `data:` URL，`buildResponsesInput` 生成 `input_text` + `input_image(detail:auto)` 内容数组（对齐 `buildUserMessageContent`）。
- 表情包：新增后由视觉模型从 `STICKER_TAGS` 词表自动打标签（`NativeCore.tagSticker`；都不贴切时允许 1~6 字中文短词，未分类时才写）；`send_sticker` 按标签精确匹配，失败再按子串双向兜底，schema 内联该角色的标签枚举。
- 对话循环（`AgentLoop`/`ResponsesLlmBackend`）：工具上限、提交重试、空回复的 `extractResponsesText`/内嵌 `reply` JSON（含 reasoning 项）/`extractAnyResponseText` 抢救顺序对齐旧版；带 180s 硬超时 + 60s 流式空闲中断；`web_search_call.*` 事件经保留状态 chunk 上报为活动提示；启动/导入时静默修损坏头像（`autoRepairAvatars`）。
- 注入顺序：世界书按 `order`（默认 100）→ 常驻 → 名称排序后按字符预算截断；命中记 `mentions` 并重置 `misses`。
- 语音朗读（端侧 CosyVoice3，`:engine:cosyvoice`）：设置页可下载模型（ModelScope，Q4_K_M GGUF ~630MB + ONNX 前端 ~253MB）；导入**任意音频文件**（mp3/m4a/wav…，不限时长，`AudioLoader` 流式解码并自动截取 ~3s 最清晰人声片段）生成音色，可选填「参考文本」（仅零样本用，instruct 模式会忽略，一般留空）；音色可命名/重命名/删除并保存到列表（`VoiceStore`，内置「内置音色 1/2」，来自 `assets/voices/sample_1/2.wav`）。**朗读气泡**：AI 气泡的「朗读」按钮有状态反馈——合成中显示进度圈 + 实时秒数、播放中变停止键、完成短暂 ✓ 后回到喇叭；点击正在朗读的气泡即停止；**同一时刻仅一个合成，新请求会抢占（停止）旧的**（原生 `cosyvoice_request_stop`）。合成前先 `extractSpeechText` 去掉 `（）/()` 动作与 `【】`/Markdown 标记只留台词，再由 `NativeCore.generateToneInstruction` 把「角色设定（性格/说话风格/称呼/语言/当前情绪/当前处境）+ 台词」交给 LLM 生成一句自由语气指令（`instruction`，非固定枚举；无 key/失败则中性），以 `instruct` 模式合成；自动朗读新回复同样走此流程。同一音色的 prompt-speech GGUF 与派生的 prompt/TTS 上下文会缓存复用（换音色或释放引擎时失效），避免每次朗读重复加载。设备需 arm64（APK 含 `libcosyvoice/libggml*/libonnxruntime/libomp` `.so`）。
- 角色功能对齐旧版（`feature:characters`，纯助手无 Android 依赖、可单测）：
  - 一句话建卡/群组升级的 prompt 覆盖完整静态 12 字段 + 7 项 `dynamicState` + 2-4 条 `lorebook`（`CharacterParity.buildQuickGeneratePrompt`），模型 JSON 解析保留全部字段（`parseGeneratedDraft` / `parseGroupMembersJson`）。
  - 头像描述含群内定位（`buildAvatarDescription`，成员版追加 `roleInGroup`）。
  - 编辑器保存前对基础/动态字段做清洗与相对时间→绝对日期换算、`userAddress` 归一化（`normalizeEditedDraft` / `FieldCleaning.cleanFieldValue` / `parseRelativeText`）。
  - 成员一句话补全只填空字段并覆盖静态 + `roleInGroup` + 7 项动态状态（`applyMemberFillPayload`）。
  - 世界书新增条目默认 `alwaysActive=true`（`newUserLorebookEntry`），名称/内容受 `AppLimits.Lorebook` 上限约束，关键词清空即转常驻（`keywordsToAlwaysActive`）。

## 数据与迁移

- 数据唯一入口：`:core:data` 仓库层（Room + DataStore）。禁止绕过仓库访问数据库。
- 旧版 WebView 的 localStorage 数据通过 `legacy/LegacyImportService` 一次性导入；`legacy/BackupService` 保留旧 JSON 根结构（`{config, characters, activeCharacterId, activeTheme, version}`）的导入导出，导出时每个角色的会话从聊天表（`ChatRepository`）读入 `memory.instant`，不再依赖 `character.instant`。UI 通过系统文件选择器（SAF）导出/导入备份；**导入为整体替换（先清空再写入，带覆盖确认），按 `decodeImportBytes`（UTF-8→GBK→UTF-8 兜底）解码，并迁移同一天重复事件记忆、恢复 `activeCharacterId`、导入后静默补全并询问迁移**。
- 每平台配置槽 `AppConfig.platformSettings` 保存非密的 Base URL/模型；API Key 走 `SecretStore` 的按平台键（切换平台互不覆盖）。
- 媒体/表情以文件（`filesDir`）存放（`app/MediaStore`：图片压到 ≤1280px/2MB，表情 ≤384px/120KB），不再以 base64 进库。
- API Key 存 `:core:security` 的 `SecretStore`（Keystore），UI 只见脱敏状态；导出时剔除。

## 记忆维护任务（对齐旧版 `src/js/memory/tasks.js`）

每轮后台串行执行（`BackgroundTasks.scheduleTurn` → `runMemoryMaintenance`），均可用假后端单测：

- **自适应重要度** `selfLearnMemoryImportance`（`:domain:memory`）：30 天内被使用过的记忆按 `usageCount/2` 升权（封顶 +3）；userProfile/habits 45/90/180 天未召回逐档降到 -3（只降权不删除）。召回时累计 `usageCount`/`lastUsageAt`。**记忆衰减/淘汰** `applyMemoryDecay` 同样在维护任务里执行。
- **自动写入校验**（`MemoryEvidence`）：`isValidAutomaticMemory` 校验模型自动写入的 `longTerm` 的 category/subject/证据/角色规则；promise/dynamic 的来源按 `resolvePromiseSources`/`resolveDynamicStateSources`（含 `current_response`）解析；语义冲突写入 `LongTermMemory.conflicts`/`conflictedAt` 并在维护时由 `resolveMemoryConflicts` 裁决（手动 `update_memory` 会把旧值记入 `corrections` 并清空冲突）。
- **序号与修订**：每条消息落库带单调 `sequence`（`ensureMessageSequences`）；`Character.revision` 每轮与丢弃分支时自增（`discardConversationBranch` 对齐）。
- **散文兜底**：本轮若模型返回散文（非结构化 JSON），后台用 `convertProseToJson` + `extractProseTurnMemory` 补齐记忆，再退到 `shouldCaptureUserTurn` 的最小事件捕获。
- **失败退避**：抽取/分析任务带 `scheduleMemoryRetry`/`canRunMemoryTask` 指数退避，避免失败热循环。
- **旧会话迁移**：`runInitialMemoryMigration`/`performMigrationExtraction` 对超长旧对话做有界抽取。
- **场景概要** `summarizeScene`：场景切换（`currentLocation` 变化）或超过 `Scene.SPAN`(24) 条消息且 ≥6 条时，压缩为一条 `scenes`（保留最近 `Scene.RETAIN`(8) 条，注入最近 `Scene.SUMMARIES`(2) 条）。游标存 `character.sceneState`（`key`/`startCount`/`startSequence`/`messageCount`，按消息 `sequence` 推进）。
- **世界书整理** `consolidateLorebook`：`shortTerm` 有未整理项（`lorebookScannedAt == null`）且积压 ≥ `Lorebook.CONSOLIDATE_SPAN`(8) 或有已分析项时触发；从已沉淀的短期记忆里抽取世界层设定（≤`AUTO_ENTRIES_PER_PASS`(3) 条，`sourceShortTermIds` 必须可回溯），写前整批校验、失败不改标记；写后本地 `dedupeLorebook` 兜底并执行 `evictStaleLorebookEntries` 淘汰（AI 近重复合并、常驻/用户条目豁免）。
- **空字段补全** `autoFillStaticFields`：启动时静默补齐为空的静态字段（最多 3 个），只补空、绝不覆盖；单角色与**群组成员**都补（成员补全带群组上下文）；退避用 `staticFillMeta`（成功清零，失败 5→30 分钟指数退避，无失败冷却 24h）。
- **一次性迁移** `migrateWorldLore`（旧 `description`/`background` 里的世界观拆成世界书条目，可选精简原文，标记 `lorebookMigratedAt`）与 `remapFields`（旧字段结构重排，标记 `fieldsMigrationVersion`）；另有 `RelativeTimeMigration`（相对时间转绝对日期，标记 `timeParseVersion`）。启动时（有 API Key 且未勾选"以后不再询问"）由 `AppViewModel.checkMigrationPrompts` 弹`AlertDialog`询问——先"字段结构升级"，后"世界书整理"（含"同时精简原文"勾选）；确认走 `NativeCore.runWorldLoreMigration` / `remapCharacterFields`，"以后不再询问"写入 `SettingsStore`（键 `deeptalking_field_migration_skip_v1` / `deeptalking_lorebook_migration_skip_v1`）。**世界书整理只在检测到目标（`lorebookMigrationTargetCount>0`）时才询问/执行，处理完标记 `lorebookMigratedAt` 后自然不再提醒；`runWorldLoreMigration` 逐角色 `try/catch` 隔离，单个失败不会清零整批，返回 `LorebookMigrationOutcome(success, failed)`。** 注意 `LorebookProposal` 依赖 `@Serializable`（`migrateWorldLore` 把它序列化进 prompt）。**这些写整行角色的任务（补全/迁移/相对时间）在 `NativeCore` 里用同一把 `Mutex` 串行，且 upsert 前按 id 重读最新角色、只覆盖自己负责的字段**，避免互相覆盖标记。新建/导入的实体直接写入 `fieldsMigrationVersion="1.2.0"`、`lorebookMigratedAt=now`，不再反复弹迁移框。

**证据与意图校验**（`MemoryEvidence`）：`hasValidUserEvidence` 要求 `sourceMessageIds` 解析为真实用户消息且 `evidence` 逐字出现；`hasStaticEditIntent` 要求用户原话明确在要求修改该字段。`submit_response` 的 `longTerm`（经 `upsertLongTermMemory`）、`staticFields`、`dynamicState`/`memberDynamicState`、`promiseUpdates`，以及 `update_memory` / `update_character_field` / `set_reminder` 工具，均据此拒绝无据写入；自动写入冲突时 `memoriesSemanticallyDiffer`（token 重叠 <0.35）阻止静默覆盖。短期记忆在捕获时写入 `sourceRoles` 与 `userEvidence`（用户消息逐字摘录），供短期→长期分析在源消息已滚出 `instant` 后仍能校验证据。

**导入数据安全**（`LegacyImportService`/`LegacyMapper`）：旧版备份里 `shortTerm/longTerm.userEvidence` 恒为 `[{sourceMessageId,text}]` 数组、`conflicts` 为 `[{value,evidence,sourceMessageIds,at}]` 对象数组；原生用容错编解码（`SourceEvidenceListSerializer`/`MemoryConflictListSerializer`/`MemoryCorrectionListSerializer`，同时接受字符串/数组/对象）映射到 `SourceEvidence`/`MemoryConflict`/`MemoryCorrection`，避免整角色解码失败被静默丢弃。导入会套用旧版长度/数量上限（`sourceMessageIds`/`evidence`/`participants`/`scenes`/世界书名与内容等），**保留原生专有配置**（`tts*`、`lastReplyDebug`、`requestMetrics`、空 `platformSettings` 时沿用现值），并在 `ImportSummary.skipped` 里上报无法解析而被跳过的角色数（不再静默）。

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
