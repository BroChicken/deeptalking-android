# Agent 架构文档（原生 Android）

> 本文档描述 **当前实现**：原生 Android 应用的 Agent 机制（`:domain:agent` / `:domain:memory`，宿主在 `:app`）。只写最新状态，历史看 git。
> 模块职责、依赖方向与构建流程见 [NATIVE_MODULES.md](NATIVE_MODULES.md)；解析机制链见 [PARSE_GRAPH.md](PARSE_GRAPH.md)；朗读链路见 [REMOTE_TTS.md](REMOTE_TTS.md)；**每一次外发调用（LLM/语音/联网）的传输格式与发送内容**见 [OUTBOUND_CALLS.md](OUTBOUND_CALLS.md)。
> 代码锚点用**模块:函数名**（行号会漂移，用 grep 函数名定位）；改动 agent 循环、工具清单、提示词、记忆系统、字段或常量时必须同步更新本文档。

## 模块锚点

| 关注点 | 原生锚点 |
|---|---|
| 宿主接线（回合、状态、指标、主动开口） | `:app` 的 `AppViewModel`（`submitTurn` / `proactiveLoop` / `recordMetrics` / `armHotTopicTurn`）、`NativeCore`（装配 `ChatOrchestrator`、后台任务、辅助调用） |
| Agent 循环 | `:domain:agent` 的 `AgentLoop`（`run` / `collectStream` / `collectResultWithRetry` / `salvageText` / `toolActivityHint`），阶段枚举 `prompts/RequestPhase` |
| 回合编排与落盘 | `:domain:agent` 的 `ChatOrchestrator.run`（`runTurnWorker` / `applyMemberDynamicState` / `applyWorkerTodo` / `applyMemory` / `applyDynamicStateFields` / `applyPromiseUpdate`）、`:core:model` 的 `FieldEditRegistry`（聚焦字段保护）；静态字段不进聊天回合，只能从角色卡编辑器修改 |
| 工具注册 | `:domain:agent` 的 `Tools.defaultTools`（+ `tools/*Tool.kt`） |
| 回合 worker | `:domain:agent` 的 `TurnWorker`（强制 `submit_state`：动态状态 / 快速回应 / 待办 / 约定） |
| 工具实现 | `:domain:agent` 的 `tools/*`（`SearchMemoryTool` / `MemoryWriteTool` 等） |
| 提示词装配 | `:domain:agent` 的 `prompts/Prompts.kt`（`buildSystemPrompt` / `buildVolatileContext` / `buildShortTermBlock` / `TimeContext`）、`RequestBuilder` |
| 结构化解析 | `:domain:agent` 的 `ResponseParser`、`StreamText`、`ToneText` |
| 后台任务与文风守卫 | `:domain:agent` 的 `background/BackgroundTasks`、`background/StyleGuard`、`background/AnalysisSalvage` |
| 记忆系统 | `:domain:memory` 的 `MemoryServiceImpl` / `MemoryPolicy` / `Retrieval` / `MemoryEvidence` / `LorebookMatcher` / `LorebookStore` / `TimeUtils` / `RelativeTimeMigration` |
| 传输与联网 | `:core:network` 的 `ResponsesLlmBackend`（`buildBody` / `newRequest` / `ResponsesSseInterpreter`）、`HttpWebContentProvider` |
| 模型与常量 | `:core:model`（`Character` / `ChatMessage` / `SpeechSegment` / `parseGroupSpeechSegments` / `AppConfig` / `RequestMetric` / `FieldEditRegistry`）、`:core:common` 的 `AppLimits` |
| 数据与迁移 | `:core:data` 的仓库层（`CharacterRepository.mutate` 是唯一读-改-写入口）、`SettingsStore`、`MetricsRepository`（独立 DataStore 文件）、`legacy/LegacyImportService`、`legacy/BackupService` |

## 一、Agent 是什么

本项目的"agent"不是某个类，而是 **LLM + 人格 + 记忆 + 工具 + 运行循环** 的组合：

- 模型不再只输出一段文字，而是可以在一次回复中**主动调用工具 → 拿到执行结果 → 继续推理**，循环往复，直到给出最终答案。
- 这种"思考（reasoning）→ 行动（tool call）→ 观察（tool output）→ 收尾"的循环就是 agent 的本质。

> **人格 + 记忆 + 世界书** 提供身份与状态 → **`while` 循环**让模型反复"想→调工具→看结果" → **主调用只输出纯文本正文 + 末尾一行 `【语气】`**；动态状态/快速回应/待办/约定由**回合 worker**（`submit_state`）在正文之后整理落盘。

## 二、总体数据流

```
用户输入（或主动开口 / 热榜开场）
   │
   ▼
ChatOrchestrator.run
   ├─ buildSystemPrompt            人格 + 交互规则 + 输出通道（纯静态 → 可缓存前缀）
   ├─ buildShortTermBlock          短期窗口整块，注入到历史之前
   ├─ RequestBuilder.mapHistory    历史（append-only，assistant 纯文本）+ 本轮用户消息
   └─ buildVolatileContext         11 段上下文（末尾 user 项）
   │
   ▼
AgentLoop.run  loop
   ├─ AUTO 阶段                    工具可用：只执行工具并回传，正文在后续轮返回
   ├─ FINAL 阶段                   无工具（toolChoice=Auto）；空回合最多重试 2 次
   └─ 拿到正文 → 纯文本 + 【语气】行
   │
   ▼
ResponseParser.parseTurnFromText   正文 / 语气拆分（历史 JSON 仅在整段是单个对象时兼容）
   │
   ├─ 群组 → groupSpeechSegments   → ChatMessage.segments（逐成员分段，只供渲染/朗读；content 仍是全文）
   │
   ▼
TurnWorker.run（runTurnWorker）    强制 submit_state：dynamicState / memberDynamicState（成员含 currentTone）/ quickReplies / todos / promiseUpdates
   │                              静态字段不在聊天回合写入（只在角色卡编辑器手动修改/重新生成）
   │
   ▼
BackgroundTasks.scheduleTurn       后台：文风校对、快速回应校验/换位（仅 worker 未成功时）、记忆抽取/分析、场景概要、世界书整理、空字段补全
   │
   ▼
按 segments 逐成员渲染正文 + 语气（朗读）+ quickReplies
```

## 三、核心部件

### 1. API 载体（Responses API）

- `ResponsesLlmBackend.buildBody()`（`:core:network`）+ `ResponsesDto`：请求体字段 `model`、`input`（首条 system 提升为 `instructions`）、`stream`、`temperature`、`max_output_tokens=8192`、`tools`、`tool_choice`、`reasoning.effort`。
- 端点：`responsesEndpoint()`（把 base URL 归一化为 `/responses`；DeepSeek 去尾 `/v1`，OpenCode Go 保留）。
- 请求头：`Content-Type`、`User-Agent`、`Authorization: Bearer`（缺 Key 抛清晰错误）、流式加 `Accept: text/event-stream`、`opencode` 平台加 `x-opencode-session`（`deeptalking-<charId>` / `deeptalking-general`）。
- `tools`：主调用只带**信息/写入类工具**（下表 9 个）；`tool_choice` 恒为 `auto`。终稿那一轮（`RequestPhase.FINAL`）`tools` 为空、`reasoning.effort='none'`，正文以纯文本返回。
- 传输超时：chat 180s（call + read），SSE 空闲 60s；**辅助/后台调用 90s**（`LlmRequest.auxiliary`，见"健壮性"）。

### 2. 工具清单（agent 的能力）

`Tools.defaultTools(memory, web, stickersEnabled, bilibiliVideo, stickerFetcher)`——**9 个工具**（顺序即注册顺序；`bilibili_video` 由 `:app` 注入，未接线时保持 8 个）：

| 工具 | 作用 | 实现 |
|---|---|---|
| `search_memory` | 检索长期记忆（关键词 / 按日期 `date`/`timeRef`/`slot` / 给 `category` 即盘点分类，`limit` 可调）；群组可指定成员私人记忆 | `SearchMemoryTool` |
| `web_search` | 通用联网搜索（必应 RSS，`limit` 1–20） | `WebSearchTool` |
| `web_fetch` | 读取链接（网页正文 / 图片 / 站内专门解析）；虎扑帖子、网易云歌曲、B站视频链接各有专门解析。**只读链接、不做关键词搜索** | `WebFetchTool` → `HttpWebContentProvider` |
| `search` | 按关键词搜一个社区平台：`platform` ∈ `netease` / `hupu`（B 站视频改走 `bilibili_video`） | `SearchTool` |
| `hot_board` | 指定平台热榜：`platform` ∈ `baidu` / `toutiao` / `douyin` / `zhihu` | `HotBoardTool` |
| `bilibili_video` | B 站视频的唯一入口：给 `query` 搜视频（BV 号/标题/UP/时长/播放/简介/封面），给 `url` 或 BV 号总结这个视频（`focus` 可选）。总结逐句字幕走分块、官方 AI 总结存在时直接采用，都没有就用简介/章节/抽帧画面说个大概并标明"没字幕"；结果带 `mode`（summary 摘要 / shallow 没字幕）、`covered_until`、`unread_sec`、`can_continue`，没看完就提示模型带 `from` 续读；`frame_at` 可把某一刻的帧作为附图贴进回复；7 天缓存（带 `focus`/`from`/`refresh` 时不读缓存） | `BilibiliVideoTool` → `HttpBiliVideoSource` + `WebContentProvider`（搜索） + `VideoSummaryService` + `BiliFrameExtractor`/`BiliVideoCache`（见 `docs/BILIBILI_VIDEO_SUMMARY.md`） |
| `memory_write` | 按 ID 修正（`op=update`，须 evidence + sourceMessageIds）或删除（`op=delete`）记忆 | `MemoryWriteTool` |
| `upsert_lorebook_entry` | 写入/更新世界书条目（世界层设定唯一入口，保护人工维护条目） | `UpsertLorebookEntryTool` |
| `send_sticker` | 发表情包（有表情能力时**恒启用**，空库也能靠 `query` 起家）。库里有关键词就给 `keyword`（schema 内联枚举，按大类分组写进 description）；库里没有合适的就给 `query`，按情境联网找一张、视觉只起关键词+归类后入库（**模型不承担审核**）、直接发出；失败原因如实回给模型，成功带入库后总张数。**不设张数上限** | `SendStickerTool` + `StickerFetcher`（`:app` 的 `HttpStickerFetcher`） |

每个工具就是一段 JSON Schema 描述（name + description + parameters），模型据此决定何时调用。
**工具描述是"何时用哪个工具"的权威依据**，system 提示词的规则 A 只留一句导引 + 工具名单。
新增工具 = 新增 `AgentTool` 实现 + 在 `defaultTools` 注册，`AgentLoop` 不变；`ToolRegistry` 构造时**重名直接抛异常**（同名工具会让 `execute` 静默取第一个）。

**平台搜索/热榜各用一个工具 + 显式 `platform` 枚举**：`search` 与 `hot_board` 在 schema 里把平台列成 enum，而不是每个平台一个工具名——工具条数少、`toolsHash` 更稳，模型也不必记隐式映射（"netease 才是音乐"）。

### 3. 工具实现（agent 的手）

`ToolRegistry.execute()`（`:domain:agent`）：

- 模型只"说"要调用，真正干活的是工具类：检索/修正记忆、读世界书、联网检索、发表情等。
- 统一返回 JSON 字符串，异常安全（`try/catch` 兜底，失败返回 `{ ok: false, reason }`），**异常不中断整轮**；`ToolRegistry` 对未知工具/被禁用工具同样回 `ok:false`。

### 4. Agent 循环（大脑）

`AgentLoop.run()`（`:domain:agent`）：

- **两阶段（`RequestPhase { AUTO, FINAL }`）**：`AUTO` 工具可用，**只执行工具并继续**——同一轮里模型若既调工具又写正文，正文丢弃（契约是"工具或正文，二选一"），终稿在后续轮返回；`FINAL` **无工具**（`tools` 为空、`tool_choice=Auto`、`reasoningEffort=none`），是唯一能结束回合的阶段。**工具轮上限只按"轮"（模型响应）计**：`MAX_TOOL_ROUNDS`(5) 到了直接转 `FINAL`；`MAX_TOOL_CALLS`(12) 目前只是保留常量，没有任何判定点。
- **工具回传**：`reasoning` 必须先于 `function_call`、再跟配对的 `function_call_output`。DeepSeek thinking 模式**不支持并行 `function_call` 回传（会 400）**，因此**同一条响应里的全部 `tool_calls` 都在本轮执行**（`executedCalls` 只作统计），随后一次性按序补上各自的 `function_call_output`——漏执行任何一条都会让未配对的 `function_call` 留在下一轮请求里。`reasoningJson` 只挂在轮内第一条调用上，保证上下文不断裂又不会重复回放。**`web_search` 是本地 function 工具**（DeepSeek Responses 会静默忽略内置 `web_search`）：本地注册同名工具，经 `HttpWebContentProvider.searchWeb()` 实际检索 RSS 后回传结果。
- **空回合重试**：循环只有 `AUTO` 与 `FINAL` 两相；`FINAL` 阶段无正文时最多重试 2 次（`emptyAttempts < 2`），之后返回空结果，由外层 `AppViewModel` 的回合重试/失败提示接管。
- **状态显示**：循环内所有右上角文案经 `onToolActivity` 回调驱动（`AppViewModel.statusState`）；工具提示来自纯函数 `AgentLoop.toolActivityHint(name)`。
- **正文之后由 worker 补结构化字段**：收尾阶段结束后，`ChatOrchestrator` 先落历史与正文，再跑 turn worker（见下）；后台任务只做记忆维护与文风/快速回应校验，不改写已显示的正文。静态角色卡字段不参与这条自动链路。

### 5. 输出协议（纯文本 + 语气行）

主调用**没有收尾工具**：assistant 文本就是回复正文；正文之后另起一行、只写一行 `【语气】<朗读指导：语气、情绪、语速、节奏、停顿、重音，≤60 字>`（`Prompts.OUTPUT_CHANNEL`）。朗读时 `ToneText.split` 把这一行拆出来，作为 `TtsTurn.style` 交给朗读引擎；正文里绝不出现字段名/JSON/工具说明。

- 历史 assistant 消息按**纯文本**重新序列化（`RequestBuilder.mapHistory`，`trimText(1200)`），不再包 `{"reply": …}`——否则模型会把历史当成"输出格式"继续写 JSON。
- `ResponseParser.parseTurnFromText` 只在"整段文本就是一个 JSON 对象"时兼容旧格式（缓存/旧提示词遗留），其余一律 `ToneText.split`。群组回复随后由 `groupSpeechSegments` 切成 `ChatMessage.segments`（只影响渲染与朗读，见 §三 7）。
- 动态状态、成员状态、快速回应、待办、约定变更都由下面的 worker 生产，**不占主调用的输出通道**。

### 6. turn worker（结构化整理）

`TurnWorker`（`:domain:agent`）+ `ChatOrchestrator.runTurnWorker`：正文已定稿后，用一次**强制 function call** 拿到本轮的结构化增量。

| 项 | 取值 / 行为 |
|---|---|
| 工具 | `tools = [submit_state]`，`tool_choice = Function("submit_state")`，`reasoningEffort = "none"`，`stream = false`，`auxiliary = true` |
| 输入 | 【当前状态】/【当前待办】/【当前约定】/【本轮用户消息（id）】/【角色回复（id）】/【时间基准】/【最近对话】；主动开场时换成【本轮无用户消息（角色主动开场）】 |
| 输出 | `dynamicState`（**不含 `currentTone`**）、`memberDynamicState`（仅群组，`[{memberName, dynamicState}]`，**成员可带自己的 `currentTone`**）、`quickReplies`（恰好 2 条：多于 2 只留前两条、少于 2 整组弃用并记 note）、`todos`（create/done/cancel/reschedule，`detail` 可空；空详情不复制标题）、`promiseUpdates`（resolved/cancelled） |
| 落盘 | 动态字段仍走 `applyDynamicStateFields` + `dynamicStateMeta`（证据闸门 `resolveDynamicStateSources` / `hasValidUserEvidence`；`currentTone` 免证据门）；群组共享状态走 `groupSharedDynamic`，成员状态走 `applyMemberDynamicState`（`currentTone` 一并写入）；新建待办走 `TodoPolicy.validateTodoCreation`（`applyWorkerTodo`），其余变更复用 `applyTodoUpdate`；记忆整理另把用户确认过、期限可定位的 promise 经 `TodoPolicy.upsertPromiseTodo` 补成待办（角色单方承诺不补）；约定走 `applyPromiseUpdate` |
| 失败/违规 | **整轮失败重试 2 次**（1s/2s）：请求失败、无结果、解析失败；仍失败则保留旧状态，聊天页快捷回应位置显示红色“整理失败·原因，点击重试”，并弹一次“再试一次 / 先这样”。红字只重跑 worker，不重生成正文；手动重试带按角色的回合代次和回复行归属校验，切角色/新回合返回的旧重试不会写回快捷回应或新的失败态。**局部规则拒绝不重试**（待办/约定/动态字段证据或期限不合格等），只记一条短标签（如 `待办：期限不明`）写入 `Character.workerIssues`，下一轮 worker 提示词最多带 8 条；成功一轮后清空。完整长原因汇总进 `workerNotes` 与 `chat.worker.phase`（`worker-note: …` / `worker-failed: …`），不吞原因 |

群组只把共享的 `currentSituation`/`currentLocation` 写进 `groupSharedDynamic`（共享状态**永远不带语气**），成员各自的处境/位置/情绪/职业/目标以及**各自的 `currentTone`** 写进对应 `member.dynamicState`；朗读时优先用该成员的 `currentTone`，成员缺失或为空才回退群组语气（`AppViewModel.toneFor`；群组语气仍由主调用 `【语气】` 写进 `dynamicState.currentTone`）。

**worker 成功后旧修复轮不再运行**：`quickReplyRepairEnabled = config.quickReplyRepair && (!worker.ok || quickReplyCount < 2)`——worker 已交回两条时，后台既不做换位重写、也不把校验标签写回消息（避免与 worker 抢结果、每轮都触发修复）。只有 worker 没跑、没成功或不足两条时，才回到 `repairQuickReplies` 的兜底路径。

### 7. 群组回复分段（`segments`）

- **一条消息，多个气泡**：群组的一轮回复仍然只有**一条** assistant 消息——历史、`instant` 窗口、折叠节奏都按一条算——`ChatMessage.segments` 只是附带的逐成员分段，供界面画多个气泡、供朗读拿真实发言人。`content` 始终是**全文**（请求历史、编辑重发、复制都用它）。
- **解析**：`groupSpeechSegments` → `parseGroupSpeechSegments(reply, 成员名列表)`。只认**行首的成员名 + 全角 `：`/半角 `:`**（最长名字优先，`张三丰` 不会被读成 `张三`）；发言人之后直到下一个发言人行的内容归该段，引号、`（动作）`、Markdown 原样保留。**归属绝不猜**：没有一行以已知成员名开头时，整条回复变成**一个空 speaker 的段**；成员名列表为空时同样整段成一个无归属段；只写了成员名前缀的回复回退成整段原文，不静默丢字。
- **渲染**：`:feature:chat` 对 assistant 消息优先按 `segments` 逐个画气泡；群组旧消息没有 `segments` 时，按正文现场解析（只接受已列出的成员名，解析到至少一个成员才启用多气泡），所以旧记录也会用各成员自己的头像和名字；单角色仍画单气泡。
- **朗读**：`extractSpeechSegments` 是**另一条**清洗路径（去括号动作/引用记号，只留台词），按同一套成员名切段；每段的语气取说话成员的 `currentTone`，为空/成员缺失才回退群组语气。
- **持久化/备份**：新增 assistant 消息落库时同时写入 `segments`（界面读的是落库行，不写就会退回单气泡）；`segments` 随备份导出/导入往返（导入时最多 60 段、丢弃空文本）；没有该字段的旧备份/旧消息由渲染层按正文回退解析。
- **单角色**：不生成 segments（`groupSpeechSegments` 对非群组直接返回空）。

### 8. 右上角状态显示（全分支）

| stage | 触发条件 | 动作 | 右上角文案 |
|---|---|---|---|
| `auto-tool` | AUTO 阶段模型发起工具调用 | 执行工具 | 对应工具提示 + `工具结果已返回，正在继续推理…` |
| `auto-tool-limit` | 工具轮次达 `MAX_TOOL_ROUNDS` | 转 FINAL | `正在整理回复…` |
| `auto-final` | AUTO 阶段没有工具调用、也拿不到正文 | 转 FINAL | `正在整理回复…` |
| `final-empty` | FINAL 阶段没有正文 | 重试（≤2 次） | `正在重新生成…` |
| `empty` | 重试后仍无正文 | 失败 | 外层提示"收到空回复，请重试。你的消息已保留。" |

工具提示（`AgentLoop.toolActivityHint`）：`search_memory`→`正在回忆…`、`web_search`→`正在搜索…`、`web_fetch`→`正在读取网页…`、`search`→`正在搜索…`、`hot_board`→`正在看热榜…`、`bilibili_video`→`正在看视频…`、`memory_write`→`正在更新记忆…`、`upsert_lorebook_entry`→`正在更新世界书…`、`send_sticker`→`正在挑表情…`，未知工具回退 `正在处理…`。

> `quickReplies` 不是收尾门槛：worker 失败或只给出不足两条时没有按钮，正文照常显示；后台换位生成在**开关开启且 worker 未成功、或成功但不足两条**时运行，结果通过 `detectQuickReplyIssues` 才补上。worker 已给出两条时不再跑，避免抢写。

## 四、记忆系统

### 三层结构 + 场景层 + 存档

常量集中在 `:core:common` 的 `AppLimits`。

| 层 | 内容 | 上限 |
|---|---|---|
| `instant` | 最近原始消息（发给模型的历史窗口） | 高水位 60 / 低水位 20（追加式，整窗折叠后压回 20） |
| `shortTerm` | 事件流程摘要 | 高水位 40 / 低水位 20（整窗注入，靠整窗折叠消化而非截断） |
| `longTerm` | 分类长期记忆 | 无数量上限（仅 `applyMemoryDecay` 时效遗忘） |
| `scenes` | 场景概要 | 保留最近 8 条（`Scene.RETAIN`），注入最近 1 条 |
| `history`（存档） | 完整聊天记录队列 | 上限 `History.ARCHIVE_MESSAGES`(400)，纯 FIFO，与窗口互不影响 |

**上下文窗口（高/低水位 + 整窗折叠）**：`INSTANT=60` / `INSTANT_TRIM_FLOOR=20`、`SHORT_TERM=40` / `SHORT_TERM_TRIM_FLOOR=20`。两个窗口都是同一形态：

- **`history` 与 `instant` 是两个独立状态**：`history` 是 Room `messages` 的完整聊天记录（UI 可上划查看），折叠永远不删除它；`instant` 是请求携带的逐字尾段，会随折叠变化。
- **窗口内容就是状态**，且是**追加式**的：每轮只把窗口没见过的消息追加进 `instant`（`AppViewModel.growInstantWindow`），**绝不从聊天存档重建**（否则折叠裁掉的消息会被捞回来，窗口只涨不缩）。只有折叠那一轮才把窗口压回低水位；`shortTerm` 同理——只有整窗抽取会追加、只有同一轮折叠会压缩，所以窗口在两次折叠之间**逐字不变**。
- **到高水位折叠（绝不按位置截断）**：`instant` 满 60 → **发送整个窗口**（60、61、62 条都发，绝不先砍成正好 60）→ **请求发出后立即把 instant 替换为本次批次的最后 20 条**（不等短期提取返回）→ 提取成功后只追加 `shortTerm`；提取失败保留这次请求，重试直接重发，不依赖已经折叠的 `instant`。`shortTerm` 满 40 → 一次整窗分析（输入=整个 40 条窗口）→ 产出 `longTerm` → 落回最新 20 条。低水位留下的 20 条会作为下一次折叠的上下文被**再读一遍**（连贯性）；重复靠**提示词注入已有短期条目**压，不靠标记。**窗口超过 `INSTANT_MIGRATION_THRESHOLD`(120) 视为旧档遗留**，走**补课通道**（`BackgroundTasks.migrateOversizedWindow`：每批最旧的 60 条，折完即丢，**一次跑完**）；启动时检测到会**先弹确认框**（含「以后不再询问」），确认后弹**阻塞式**进度框，与正常通道严格分开。世界书条目内容超过 `Lorebook.CONTENT_CHARS`(400) 时同样由一次性重写处理（见"世界书"）。
- **窗口错误处理**：连续失败达到 3 次后，右上角显示红色可点击的「短期记忆生成失败 / 长期记忆生成失败」；短期提取的重试直接重发保留的请求，长期分析仍等成功后再消费 `shortTerm`。
- **抽取不点名消息 id**：模型把整窗压成按"日期+时段"划分的事件摘要，**原话证据由客户端按同一时段从窗口里采**（挂成 `userEvidence`/`sourceRoles`）。因此没有"id 必须存在""必须连续覆盖"这类硬约束，单条出错不再作废整批。
- **注入**：`instant` 整窗进历史；`shortTerm` 整窗进 **历史之前**的一条 user 块（`buildShortTermBlock`），因此它落在缓存前缀里。
- **紧急上限**：`INSTANT_HARD_CAP=120` / `SHORT_TERM_HARD_CAP=80`，仅在折叠持续失败时生效（丢最旧的并在 `counters.analysisLastError` 写明原因，绝不静默）。
- 更早内容由 `scenes` + 长期记忆 + `search_memory` 承载。`Scene.SPAN=12`。

### longTerm 五类

`userProfile`（用户信息）、`relationship`（关系）、`events`（共同事件）、`promises`（约定）、`habits`（习惯）。

- subject 仅限 `user` / `relationship` / `world` / `character`（`legacy` 仅作历史兼容，不再写入）。
- `MemoryEvidence.isValidAutomaticMemory()` 校验来源引用与证据逐字摘录，防止模型编造记忆。
- 短期记忆在捕获时写入 `sourceRoles` 与 `userEvidence`（用户消息逐字摘录，`[{sourceMessageId,text}]`），短期→长期分析据此在源消息滚出 `instant` 后仍能校验 `evidence` 逐字来自声明的用户消息。**来源以折叠开始时读到的整批对话为准**：请求返回后可能已有新消息把窗口推进，不能拿“实时窗口”去否定这批摘要的来源；实时窗口只用于合并已有条目。**`sourceRoles` 与 `sourceMessageIds` 是逐位对齐的平行数组**：合并来源必须逐位重建；旧条目缺 role 时保留来源 id（role 可空），不能因为 role 缺失把 id 一起丢掉。
- 主对话（纯文本协议）**不再自动写长期记忆**：正常轮次里 `longTerm` 由后台抽取/分析产生；记忆的修正与删除只走 `memory_write` 工具（`MemoryPolicy` / `MemoryEvidence.hasValidUserEvidence` 校验）。旧结构本身是完整 JSON 的兼容路径仍会经 `ChatOrchestrator.applyMemory` → `MemoryPolicy.upsertLongTermMemory` 落盘。
- **短期记忆不由主对话写**：主调用没有任何记忆字段，窗口唯一的写入者是整窗抽取（`BackgroundTasks.analyzeShortTermBatch`）。这样窗口在两次折叠之间不会被写在中部或提前压缩，注入块才能保持逐字稳定。
- **短期条目可被修正/删除**：`memory_write` 支持 `short_` 前缀 ID（`op=update` 支持 `value`/`eventTime`；改正后 `revision+1` 并移到队尾，等下一轮折叠写进长期记忆）；`search_memory` 的关键词召回也返回 `shortTerm` 命中。

### 相关度检索

`Retrieval.rankMemories` / `topK`（`:domain:memory`）：

- 每个命中词按「归一化特异性（IDF 式：含该词的记忆越少、权重越高）× 命中字段（`tags`×2 / `key`×1.5 / `value`×1）× 词长」加权，再乘覆盖率加成，压掉"晚上/事情"这类泛词。
- `topK` 先过相关性下限 `MIN_MATCH_SCORE`(1.5)，再按「同一事件（日期+时段）只留一条 + 每类最多 `MAX_PER_CATEGORY`(3) 条」去重；若下限会清空结果则回退到完整排序（不会召回为空）。
- 取 top 12，配合 `pendingRecall`（待召回记忆，强制置顶，上限 `PENDING_RECALL`(6)）。
- 自动注入与 `search_memory` 工具共用同一评分；群组可指定 `memberName` 检索成员私人记忆。

### 注入与衰减

- `buildVolatileContext(character, query, memory, now, proactiveTurn, hotTopicTurn, recentAssistantReplies)` — 把本轮动态上下文拼成 **11 段固定顺序**（见"volatile 段落"与 §六 预算）。**有硬预算**：整段 ≤ `VOLATILE_HARD_CHARS`(8000)，超限按 可选话题 → 场景概要 → 世界书 → 记忆召回 的顺序整段丢弃（时间 / 当前状态 / 未完成事项 / 本轮写法 永不丢）。装配函数 `buildVolatileContextForTurn` 除文本外还返回**每段字符数**（`sections: name/chars/dropped`，键见 `VOLATILE_SECTION_KEYS`）与本轮实际渲染的召回 ID；**召回段被整段丢弃时 `recallIds` 返回空**——模型没看到的记忆绝不消费 `pendingRecall`（没挤进 3 条上限的仍保留）。检索查询经 `buildMemoryQuery()` 扩展：用户明显指代前文时附加最近对话。**热榜开场**（`HotTopicPolicy`：每角色每逻辑日掷一次、10%、每天 1 次/每周 2 次）命中时 `10 本轮指令` 段要求先调 `hot_board(platform=zhihu)` 再挑一条话题；两条指令都只加在 volatile 尾部，**不进 system prompt**，所以不会打乱缓存前缀。本轮实际注入的世界书条目直接取自 `selectLorebook` 的结果并累计 `mentions`（`bumpLorebookMentions`）。
- `MemoryPolicy.applyMemoryDecay()`：按时间衰减 events/promises 的重要性与状态。
- `MemoryPolicy.computeEffectiveImportance()`：`(importance + learnedBonus) × decay`（0-10 封顶）。

### 记忆子任务

由 `BackgroundTasks.scheduleTurn()` → `runMemoryMaintenance()` 每轮后台调度（各任务互不排队、可用假后端单测）：

| 任务 | 触发 | 计数器 | 说明 |
|---|---|---|---|
| 抽取（instant→shortTerm） | `instant` 满 `INSTANT`(60) | `extractionRetryAt` / `extractionFailures` | 整窗折叠成"日期+时段"事件摘要；请求发出后立即把窗口提交为最后 20 条，失败保留请求重发 |
| 分析（shortTerm→longTerm） | `shortTerm` 低水位之上攒够 20 条 | `analysisRetryAt` / `analysisFailures` | **逐条容错**（单条校验不过只丢该条）＋**ack 宽容**（只承认批次内 id，缺的下轮再送）＋**截断抢救** `AnalysisSalvage`＋显式 `max_output_tokens` |
| 场景概要 | 场景切换或跨度 ≥ `Scene.SPAN`(12) 条 | `sceneRetryAt` / `sceneFailures` | `summarizeScene` |
| 世界书整理 | 跟随**抽取**：处理本次折叠刚产出的那批短期（排在分析之前） | `lorebookRetryAt` / `lorebookFailures` | `consolidateLorebook` + 容量驱动归档；失败的那批留在内存待重试 |

`scheduleMemoryRetry()` 统一指数退避（5 分钟 → 30 分钟封顶）；成功后清零。

**短期摘要按位置消费（没有逐条标记）**：短期窗口是高低水位对（40 → 20），**位置就是状态**——比最新 20 条更老的那一段（每批最多 `ANALYSIS_BATCH`(40) 条）就是本批要消化的，分析成功即**原样丢掉这一批**，最新 20 条留作下一批（以及注入块）的上下文。因此不需要 `analyzedAt`/`analyzedRevision` 这类"两个消费者都确认才允许裁剪"的记账（一旦和位置错位就会死锁：标记说"都处理过" → 每轮都判"没有待办" → 窗口只涨不降）。`revision` 只剩一个用途：条目被 `memory_write` 改写时自增，用来把**请求飞行期间刚改过**的条目扣在窗口里、下一轮再读。

**证据与时间精度**：`MemoryEvidence.evidenceMatchesSummary()` 要求证据与实际回复有 ≥2 个双字片段重叠（≥35%），不"满 8 字即采信"；动态状态写入仍要求 `hasValidUserEvidence()`（用户原话逐字支撑）。静态字段不参与聊天回合写入。`TimeUtils.resolveTimeRef` 对带时分秒的 explicit ISO 原样保留（含时区偏移），只给"仅日期有理"的结果补时段起点。

### 基础设定字段（10 字段）

`Prompts.STATIC_PROFILE_FIELDS`（`:domain:agent`）：`gender` / `age` / `race` / `appearance` / `personality` / `fears` / `background` / `speakingStyle` / `language` / `userAddress`（群成员另有 `roleInGroup`）。

> `speakingStyle` 的界面标签已改为**「声音底色」**（可选）：只写 1~2 句语速/用词层次/句尾习惯/称呼，**不写示例台词、不列口癖清单**（示例会被逐字照抄成每轮固定语句）。与角色无关的「像真人说话」规则全部在 `RULE_7`；建卡与补全的生成侧也用 `VOICE_BASE_RULE` 代替原来的"必须写三条示例"。

**只留"人本身"的设定**：原本还有「价值观」与「关键过往」两项，它们分别与「性格特征」「背景故事」重叠（后者只是前者加时间线），**已删除**——设定越少、越不易互相矛盾，注入也更省。旧页面的这两个字段由 `:core:data` 的 `RetiredStaticFields` 在**读取时并入幸存字段**（性格 ← 价值观、背景 ← 关键过往）并摘掉退休键；旧备份走 `LegacyMapper` 的同一条合并规则，所以升级不丢人设。

**写作规则**（`core:model` 的 `StaticFieldRules`，角色卡编辑器的保存/重新生成与建卡共用）：

- **只写明确的简短陈述句**：禁止「不是 A 而是 B」这类对比句（`contrastReason` 命中即**拒收**，工具会回一条明确错误让模型当场改写；它不静默截断也不偷改语义）。
- **按字段收敛**（`AppLimits.StaticProfile`）：短字段（性别/年龄/种族/语言/称呼）**40 字**、叙述字段（外貌/性格/恐惧弱点/声音底色）**120 字**、背景故事**250 字**；超长按句末标点回退截断（`clamp`）。动态状态等非基础设定字段不受这些上限约束。
- **两条通道语义不同**（`:feature:characters` 的 `FieldCleaning`）：**模型 / 建卡通道**（`MemoryToolSupport.cleanFieldValue`、`FieldCleaning.cleanFieldValue`）保留上面两条严格规则；**用户手写通道**（编辑器保存，`FieldCleaning.cleanUserWrittenField`）**对比句原样保留、不截断**，只去字段名前缀 / 括号说明，并把每一条处理作为可见提示回报给用户。注入侧另有单字段 600（背景 800）、整段 8000 字上限兜底，所以卡片存更长的文本不会撑大提示词。

**静态字段只有手动写入路径，没有工具，也不在聊天回合自动改写**。角色卡编辑器提供：
1. **手动编辑**：保存时只提交真正改过的字段，并给这些字段盖 `staticFieldMeta[key].updatedAt`；
2. **重新生成字段**（手动点按钮）：`NativeCore.regenerateCharacterField` 发一次 `auxiliary` 小调用，只重写用户点名的一个字段；结果先回到编辑框草稿，点保存才落盘。

聊天回合即使出现“以后叫我…/把性格改成…”也不会再自动修改角色卡；旧 JSON 回复里的 `staticFields` 同样被忽略。群组不维护本体基础设定字段；成员字段仍只在该成员卡片上编辑。

**空字段补全**是另一条独立路径：只填空白字段，绝不覆盖已有内容（见 `BackgroundTasks.autoFillStaticFields`）。

### 动态状态（6 字段）

`Prompts.DYNAMIC_STATE_FIELDS`（`:domain:agent`）：`currentSituation` / `currentLocation` / `currentMood` / `currentOccupation` / `currentGoal` / `currentTone`。

`currentTone`（当前语气）由主调用正文末尾的 `【语气】` 行给出（`ResponseParser` 的 `tone` → `ChatOrchestrator.applyTone` 写进 `dynamicState.currentTone` + `dynamicStateMeta`），是面向朗读的 TTS 播报指令（语气/情绪/语速/节奏/停顿/重音，软上限约 60 字），朗读时直接作为 `TtsTurn.style` 传给当前朗读引擎（端侧 CosyVoice 的 instruct 指令，或远程服务器的 `instructions` 字段）。旧的 `currentRelationship`（当前关系）与 `currentImportantOthers`（当前重要他人）已不再使用，其旧值不迁移。

除 `currentTone` 外，每个字段的 value 必须带 `sourceMessageIds` + `evidence` 溯源；这些字段由 turn worker 的 `submit_state` 写入。群组整体只维护 `currentSituation` + `currentLocation`（`GROUP_SHARED_DYNAMIC_FIELDS`，共享状态不含语气），成员各自的处境/位置/情绪/职业/目标写进 `memberDynamicState`；**成员的 `currentTone` 也由 worker 的 `memberDynamicState` 写入**（每句话的朗读投递各不相同），群组整体语气仍来自主调用 `【语气】` 行。朗读时取该成员的 `currentTone`，为空或成员缺失则回退群组语气（`AppViewModel.toneFor`）。`currentTone` 是朗读投递属性（自指、非事实状态），**免证据门**（`applyDynamicStateFields` 对 `currentTone` 跳过来源解析）。

### 世界书（lorebook）

`char.lorebook = [{ id, name, keywords[], content, enabled, order, alwaysActive, origin, mentions, lastMentionedAt, misses }]`；`char.lorebookArchive = [...]`（归档，`GroupMember.lorebookArchive` 同理）。`misses` 仅为存档兼容保留，**已不参与任何判定**。

**定位：世界层设定的唯一去处**（时代与世界观、地点、组织、专有名词、历史、规则）。静态字段只承载"人/群组本人"的设定（群组 `description` = 群组前提、单角色 `basicInfo.background` = 个人背景），世界观一律不写进静态字段。

- **两种模式**：`alwaysActive: true`（常驻，每轮都注入，用于世界前提/规则）与关键词命中（被提到才注入，省 token）。**没关键词的条目不被拒收**：改用**条目名当触发词**。常驻数量受 `MAX_ALWAYS_ACTIVE`(6) 约束（超限只是降级、绝不整条丢弃）。
- **来源与保护**：`origin: 'user' | 'ai'`（界面显示为「人工维护」/「AI 维护」，点一下即可切换）。`user` 的条目**不会被模型覆盖、不计入 200 名额、也不参与自动淘汰**；`ai` 的条目模型可维护（更新=整条替换）。**归属只由这个开关决定**：编辑名字/关键词/内容、勾「启用」「常驻」都不改变归属；界面新建条目默认 `ai`。
- **模型维护（三入口）**：
  1. 建卡/群组升级时由模型产出初始条目（`CharacterParity` 的 `lorebook` 字段，2-4 条，标记 `ai`）。
  2. 主循环工具 `upsert_lorebook_entry`：先按 `entryId`/`name` 精确匹配——**精确命中时用新 `content` 替换该条目原有内容**，未命中再用近似匹配（名称 bigram / 共享关键词 / 内容相似度）找**同一事物的近似条目**并**整条替换**（允许变短、允许把 `alwaysActive` 改回 false），确实没有近似条目才新建；要求 `sourceMessageIds` + `evidence` 可回溯，且**不得覆盖 `origin:user` 条目**。
  3. 记忆子任务 `consolidateLorebook()`：跟随**短期抽取**（排在长期分析之前，读的正是本次折叠刚产出的那批短期；清理流程则读它即将消费的那批），把现有条目的**完整内容**（最近更新的 12 条 / 累计 ≤6000 字；其余只给名字+关键词并注明"本轮不要更新此条"）交给模型，要求"同一事物只一条：沿用原名，把完整原文里仍有效的部分 + 新事实**重新总结成一条去整条替换**（允许变短、一条只讲一件事、目标 300 字 / 硬上限 400 字）"（`AUTO_ENTRIES_PER_PASS`(3) 条上限，按 `sourceShortTermIds` 校验）；超 400 字的条目**退回模型重写一次**，仍超才放弃；写完后本地 `dedupeLorebookWithArchive()` 兜底，**被替换的旧条目写进 `lorebookArchive`**，再按容量规则分包（见下）。**写回只动世界书**（逐条并进最新列表，另有 `dedupeLorebookWithArchive`），不再因为"运行期间角色版本变了"整批作废；**失败的那批留在内存**（`LorebookRetryStore`），下一轮维护时与新的批次一起重试，连续多次失败后弹窗询问「重试 / 忽略」（忽略即按用户意愿丢弃并写进请求日志）。每趟都会以 `status=note` 记录"写入 N 条 / 跳过 M 条（原因）/ 归档了哪些"。另有一次性重写 `rebuildOversizedLorebook`：内容 >400 字的旧 AI 条目交给模型按主题拆成 1~3 条并原地替换。
- **容量驱动淘汰（200 上限，全局计数）**：一个单角色、或一个群组（本体 + 全体成员的世界书）**合计** 200 条——只统计 **`ai` 条目**；**人工维护条目不计入、永不淘汰、永不被自动改写**；**归档不计名额、不注入、不参与命中统计**。活跃时间 = `max(lastMentionedAt, createdAt)`（单一排序键，二者同级；都缺失时排最后，绝不因时间缺失而误删）。**平时不淘汰**：只有"本批新增放不下"时才腾位置，腾的数量 = `当前启用总数 + 本批真正新增数 − 200`（<0 记 0）；单次最多 `EVICT_MAX_PER_PASS`(4) 条（`Character.archiveOldestLorebookEntries` + `LorebookStore.archiveToFit`），需要更多时本次只写放得下的部分、其余下次再写并在 note 里说明。排序确定性：活跃时间 ↑ → 累计命中次数 ↑（少的先淘汰）→ 条目 id ↑，同一输入两次结果一致；重试按"当前还缺几个位置"重算，**幂等**（够了就是 0 条）。淘汰 + 新增/更新在**同一次角色更新**里提交（沿用 `consolidateLorebook` 返回同一 `Character` 的结构），失败什么都不变。**可淘汰池为空**（全是人工/常驻）+ 放不下 → **拒写并可见提示**，绝不删除受保护内容。**超容状态**（旧档导入 >200）走一次性收敛 `convergeLorebookCapacity()`（收敛到 200、进归档）。**归档可恢复**：条目从 live 列表移入 `lorebookArchive`（保留原归属），编辑器里可查看/恢复——世界书页签**跟随「正在编辑的对象」**（本体或某个成员），因此成员世界书与成员归档同样可见、可归档、可恢复（成员一律按 **id** 定位，找不到就原样返回、绝不写进本体）；恢复要占名额，超限时走同一套容量规则（且**不会**立刻把刚恢复的条目再次归档），腾不出就拒写 + 提示。所有上限判定点（编辑器新增、建卡/升级生成、`upsert_lorebook_entry`、后台整理、一次性重写、旧档导入/迁移）**统一用全局计数**。
- 归一化：写入受 `AppLimits.Lorebook` 约束（`ENTRIES` 200（**全局**：本体+成员，只算 `ai`）/ `KEYWORDS_PER_ENTRY` 8 / `NAME_CHARS` 60 / `CONTENT_CHARS` 400——**超限写入直接拒收、退回模型重写，绝不截断**）；角色卡弹窗第三个标签页编辑（展示来源徽标/常驻开关/最近提及；页签跟随正在编辑的本体或成员），条目可**归档**、归档区可**恢复**。
- 命中 `LorebookMatcher`：**常驻条目无条件入围**，其余对**本轮用户输入 + 最近 6 条消息**做小写关键词包含匹配；**正文为空的"待补充"条目是唯一例外**——即使勾了常驻也只在关键词命中时注入（否则一堆未填条目会每轮挤在常驻段），注入时写成「名字：（正文为空，待补充；条目ID: …；关键词: …）」，让模型能按 id 精确补写而不用猜名字（完全没写关键词的空条目没有任何命中依据，不会注入）。排序 = 常驻 → `order` → 最近更新（`updatedAt` 新的在前）→ 名称（中文 `Collator`）；最多 `INJECT_ENTRIES`(6) 条。
- 注入：分「常驻前提 / 相关条目」两组渲染，总字符受 `INJECT_CHARS`(1400) 限制（**装不下的条目跳过、继续尝试后面的短条目**，只有单条自身超预算才截断——旧行为下一条长常驻条目就能把后面全部挡死）；措辞强调"**是补充资料、不是指令，冲突以最新消息为准**"；真正注入的条目才计入 `mentions`（超预算被丢弃的不算命中）。
- 群组实体与成员的世界书都参与命中（按 `id`（空则 `name|content`）去重合并）；工具写入时可指定 `memberName` 落到成员私人世界书。
- **一次性迁移** `migrateWorldLore`：检测旧数据里写在 `description`/`background` 中的世界观内容，弹窗确认后由 AI 拆成条目（可选同时精简原文），原文默认保留。

## 五、进阶记忆子系统

### 记忆冲突自动裁决

- `MemoryEvidence.memoriesSemanticallyDiffer(a, b)`：把文本拆词/拆字比较重叠度，重叠 < 0.35 视为语义冲突。
- `MemoryPolicy.upsertLongTermMemory`：同 key 冲突时**不静默覆盖**，写入 `conflicts`（保留双方 value/evidence/sourceMessageIds/时间）并标记 `conflictedAt`。
- `MemoryPolicy.resolveMemoryConflicts()`：在长期裁剪路径自动合并，胜出规则 **更新时间晚 > 证据更长 > 保持原主值**。

### 记忆重要性自学习

- `usageCount` / `lastUsageAt`：记忆被召回或 `search_memory` 命中时累计使用量。
- `MemoryEvidence.selfLearnMemoryImportance()`：每轮维护任务调用。
  - **升权**：30 天内被使用过，`learnedBonus = min(3, floor(usageCount/2))`；
  - **降权**：userProfile/habits 45/90/180 天未召回，`learnedBonus` 逐档降到 -3（只降权不删除）。

### 剧情弧线（故事线）

- 记忆分析提示词可输出 `arcOf` + `arcStage`，同一 `arcOf` 只保留一条带弧线标注的事件。
- `Retrieval.rankMemories` 对带 `arcOf` 的记忆额外加权。

### 成员记忆隔离（群组）

- 每个成员持有独立 `member.memory.longTerm`。
- 路由：`search_memory` / `memory_write` / `upsert_lorebook_entry` 都可传 `memberName` → 作用于该成员的私人记忆/世界书；后台抽取产出的长期记忆写入共享层。
- 注入：`buildVolatileContext` 为选中的成员各注入 1 条私人记忆（标记"仅该成员知道"），群组规则要求不得张冠李戴；成员动态状态由 turn worker 的 `memberDynamicState` 维护。

### 话题系统（主动提话题 + 冷场 + 话题切换）

- `Prompts.buildTopicSuggestions()`：从 `basicInfo` + 记忆抽取最多 5 条候选话题。
- `Prompts.isColdFieldRequest()`：识别"不知道聊什么/你说吧"等冷场信号，注入强指令要求角色主动开话题。
- `Prompts.detectTopicSwitch()`：对 `currentGoal + currentSituation` 与用户消息做双字 bigram 重叠检测，不同则注入"先收束旧话题"的软指令。

### 场景概要

- `BackgroundTasks.checkScene()`：场景标识 = 当前地点（`sceneLocation`：**群组读 `groupSharedDynamic.currentLocation`，单角色读 `dynamicState.currentLocation`**），空则记「未说明」。读错字段会让群组的场景 key 永远停在「未说明」，地点变了也不触发概要。
- `summarizeScene()`：把"这一段已告一段落的情节"压缩成 1-2 段（≤300 字）**只含剧情要点**的记忆（谁做了什么/学到或决定了什么/地点物品伤势关系变化/新发现/未解决的目标承诺威胁期限）；写明主体、绝对日期、不引对白、不编造。**输入带时间**：在规则与转录之间拼一个 `【时间基准】`（当前本地时间/时区/ISO/逻辑日，与 volatile 逐字同款），转录每行前面标该条消息自身的时间 `yyyy-MM-dd HH:mm（逻辑日 YYYY-MM-DD 时段）`，没有可用时间戳的消息标 `（本条时间未知）`；提示词据此要求"日期只能依据给到的时间信息写、无时间标注的消息不得编日期"。存储结构与检索未变。
- 触发：**场景切换**（location 变化）或**场景超长**（`Scene.SPAN`(12) 条消息），且消息数 ≥ `Scene.MIN_MESSAGES`(6)。
- 游标存 `character.sceneState`（`key`/`startCount`/`startSequence`/`messageCount`，按消息 `sequence` 推进）。
- 注入：最近 1 条渲染为 `【场景概要】`（不再按 `key` 过滤，≤300 字）。

### 用户约定（promises，纯叙事记忆）与独立待办（todo）

**长期记忆与待办互相独立**：`promises` 是被角色记住的叙事记忆（角色记得你们之间有过什么约定/承诺），
待办是单独的实体 `Character.todos`。角色单方面承诺只留在记忆里，**不生成待办**；用户本人提出/答应的、
带明确期限的约定，除了写入记忆，还会在记忆整理时补成待办（当轮 worker 已经建过则不重复）。

- **约定确认** `MemoryEvidence.promisePushDecision()`：判定一条 `promisor=user/relationship` 的约定是否是**用户本人同意**过的。`promisor=character`（角色单方承诺）照常存进记忆，但不计入"已确认"；`promisor=user` 要求 `sourceMessageIds` 解析得出且全是用户原话（`evidence` 必须逐字对上）；`promisor=relationship`（双方约定）还必须有另一方原话佐证；其余（群组成员名 / 空 / 旧数据）一律不计入。**结论会落盘**：严格闸门通过的那一刻把 `pushConfirmedAt`（时间）与 `pushConfirmedDueAt`（当时被确认的 `dueAt`）写进记忆（`AppViewModel.recordPromiseConfirmations` 只扫刚回过一轮的角色，幂等；无期限的已确认约定也打标），标记按 `dueAt` 失效。它不再投递通知（无通知权限、无 WorkManager），但 `promisePushDecision` 的结论同时用于下面待办的补齐。
- **期限定位**：`dueAtBackedByEvidence(sources, dueAt)`（原 `dueAtBackedByUserEvidence`，泛化后按传入来源判断）要求期限能在给定原话里定位（相对词按消息时间换算后核对日期；绝对日期直接比对；只有粗粒度时间词时"出现过时间词"即可）。`promisePushDecision` 用它判定 `dueAtNeedsConfirmation`。
- **周期性约定注入**：`Prompts.selectStandingCommitments()` 确定性挑周期性 promises/habits（见"上下文与预算"表），与待办一起并入 `3 未完成事项`，每条约一行；这是叙事记忆的补丁，与待办无关。
- **独立待办（todo）**：模型 `Character.todos: List<Todo>`（`id/title/detail/dueAt/status/proposer/sourceMessageIds/evidence/createdAt/updatedAt/closedAt`，`TodoStatus` = active/done/cancelled，`TodoProposer` = user/character）。**新增只有一条闸门** `TodoPolicy.validateTodoCreation()`：①期限必填且能在对话原话里定位；②`proposer=user` 时用户原话含期限与提议；③`proposer=character` 时必须附**用户应答的原话**，且该原话不得是明确拒绝（`TodoPolicy.isUserRefusal`）；**同意与否以模型的判断为主，关键词只做其中一环**（`isUserAgreement` 只作正向确认信号，覆盖不到的说法一律不否决），期限落在双方任一方的原话里即可。**写入有两条通道**：① turn worker 的 `submit_state.todos`（`ChatOrchestrator.applyWorkerTodo`）：`create` 走上面的闸门，`done`/`cancel`/`reschedule` 走 `applyTodoUpdate`（须引用户原话，改期也要能定位）；② 记忆整理对**用户确认过、有用户原话可定位期限**的 promise 调用 `TodoPolicy.upsertPromiseTodo()` 补建/更新，角色单方承诺会被 `promisePushDecision` 直接拒绝。两条路径按标题（或来源消息 + 期限）去重，不重复建。**关闭条目保留 `AppLimits.Todo.CLOSED_RETENTION_DAYS`(30) 天**：`TodoPolicy.pruneClosedTodos()` 在启动维护时真删更老的，列表（`TodoBuckets`）再防御性过滤一次。未结束的待办与周期性约定合并成 `【未完成事项】`（`Prompts.buildUnfinishedBlock()`，固定顺序，无命中不出段），角色可自然提起。
- **待办 UI**：角色编辑弹窗的「待办」页签（`:feature:characters`，仅角色本体；成员页签不显示），分 `进行中 / 已超时（由 dueAt < now 派生）/ 已完成 / 已取消`，后两组默认折叠；进行中/已超时行「完成 / 取消」，关闭行「删除」——都只是**手动兜底**，正常生命周期由模型处理。

## 六、上下文与预算

### 记忆预算

volatile 有**整段硬上限**（`VOLATILE_HARD_CHARS`=8000）；超限时按固定顺序整段丢弃，取舍仍优先"条数上限 × 每条上限"。

| 项 | 值 | 说明 |
|---|---|---|
| 短期窗口注入 | 整窗（20~40 条） | 注入到**历史之前**的独立 user 块，不进 volatile |
| 记忆召回 | `RECALL_INJECT_LIMIT`(3) 条 / 单条 `RECALL_LINE_CHARS`(1200) / 总预算 3600 | 只截断超长单条（按 `。！？；` 句末边界），3×1200 即总量上限；`pendingRecall` 按**实际渲染**的 id 消费，召回段被整段丢弃时一个都不消费 |
| 世界书注入 | `Lorebook.INJECT_ENTRIES`(6) 条 / `INJECT_CHARS`(1400) | 装不下的条目**跳过、继续装后面的** |
| 场景概要 | 1 条 / ≤300 字 | 只取最近一条（不再按 `key` 过滤） |
| 未完成事项 | 待办 `Todo.INJECT_LIMIT`(4) + 周期约定 `STANDING_COMMITMENT_LIMIT`(4)；每行 100 / 整段 `UNFINISHED_BLOCK_CHARS`(400) | 待办按期限升序；同一件事不会同时以 `[待办]`/`[周期]` 出现；**无命中整段不出现** |
| 本轮写法 | `WRITING_BLOCK_CHARS`(500) | 口吻（声音底色摘要 ≤40 字 + 称呼）+ 骨架 + 话题 + 上一轮问题 + 收尾去重 + 本轮别用（`REPEATED_PHRASE_LIMIT`=3） |
| 消息时间元数据 | 14 条 | `id \| role \| timestamp`，供 `sourceMessageIds` 引用 |
| 超限丢弃顺序 | 可选话题 → 场景概要 → 世界书 → 记忆召回 | 时间 / 当前状态 / 未完成事项 / 本轮写法**永不丢** |
| 每条兜底上限 | 长期 value / 群私人 1200、召回 key 120 | 仅为兜底；提示词软限制让它尽量不触发 |

### 请求装配与缓存前缀

一轮请求的形状（`ChatOrchestrator.run` + `RequestBuilder`）：

```
instructions（system：交互规则 + 角色设定 + 输出通道，纯静态）
input:
  [0]     短期窗口块（user）    ← 【近期摘要记忆…】整窗
  [1..n]  历史 = instant 窗口（20~60 条原文，assistant 纯文本）
  [n+1]   本轮用户消息
  [n+2]   volatile 上下文（user，最后一条）
tools: AUTO 阶段 8 个工具 / FINAL 阶段无 tools（tool_choice 恒为 auto）
```

- **短期块放在历史之前**是有意的：它只在折叠那一轮变化，而折叠同时把历史 rebase 掉——即"它变的时候前缀本来就要断"。中间那段它逐字不变，于是和"追加式历史"一起待在缓存前缀里。
- 前缀缓存的失配只应来自三处：`instructions-changed`（角色静态资料被改）、`tools-changed`、以及两个窗口各自的 `history-rebased`（折叠，约每 20 轮一次）。`AppViewModel.requestTrace` 把短期块作为首个伪条目纳入前缀快照，否则它的变化会被误报成 `history-extended`。
- 历史**不逐轮滑动**：`AppViewModel.growInstantWindow` 只追加窗口没见过的消息；`ChatOrchestrator` 直接使用 append-only 窗口，不再在请求侧 `takeLast(INSTANT)` 造成逐轮滑窗。
- 图片保留窗口 `AppLimits.Media.IMAGE_RETENTION_MESSAGES`(60) 必须 ≥ `INSTANT`(60)：否则还在窗口内的消息会被剪掉图片，等于在稳定期中间改写前缀，还让模型"忘掉"刚看过的图。

### 提示词字符上限

`AppLimits.Prompt`：`ROLE_CHARS = 8000`（单角色设定）/ `MEMBER_CHARS = 900`（群组成员）。
历史消息按条数与单条字符截断，且**同一消息无论是否为本轮都使用同一上限（用户 2000 / assistant 1200）**——保证同一段文字在"当前轮"与"下一轮变历史"时编码逐字一致，避免前缀缓存整段失效。

### 前缀缓存策略

- system = 【交互规则】(`RULE_0`–`RULE_7` + `RULE_A` 工具名单) + 【群组附加规则】（仅群组）+【角色设定（固定，不随对话变化）】+【输出通道】，全部静态，跨轮不变。
- 历史消息逐字按序（最旧→最新）拼接、不重排、不注入消息 ID；**同一条消息在任何一轮都用同一编码**（含 `trimText` 上限），因此上一轮的"当前用户消息"在下一轮变成历史时字节不变。assistant 历史按纯文本原样回放，不再包 JSON。
- **所有随轮变化的内容汇总进 `buildVolatileContext`**，作为独立的末尾 user 项追加，不写进任何历史消息体内：时间 / 当前状态 / 未完成事项 / 世界书 / 场景概要 / 记忆召回 / 成员私人记忆 / 本轮写法（声音底色摘要、骨架、话题、上一轮问题、收尾去重、本轮别用）/ 可选话题 / 本轮指令 / 消息时间元数据。
- 语气相关注入都收在 `8 本轮写法` 的固定顺序里：口吻（**声音底色摘要 ≤40 字 + 称呼，不发示例台词**）→ 骨架 → 话题 → 上一轮问题（每 3 轮的语气回顾也走这一行）→ 收尾去重 → 本轮别用；群组按成员各一行口吻。越靠后越接近生成点，遵从度越高。
- **分层**：与角色无关的「像真人说话」放 `RULE_7`（静态 system，所有角色共享）；角色专属的只剩一句可选的「声音底色」与记忆数据。示例台词**只留在 system 的角色设定里**，不进尾部——逐字示例是最容易被照抄成每轮固定语句的东西。
- 禁止：把随轮内容插进历史中间、每轮改写 system 措辞、volatile 各段随机换序（都会打掉前缀命中）。

### 逐请求缓存与耗时统计

`AppViewModel.recordMetrics`（主对话 + worker）/ `recordAuxiliaryUsage`（后台辅助调用）写 `RequestMetric`：`taskType`、`platform`、`phase`、`status`（`completed` / `failed` / `note`）、`round`、`inputTokens` / `outputTokens` / `hitTokens` / `missTokens` / `hitRate`、`durationMs`、`instructionsHash` / `toolsHash` / `historyHash`、`commonHistoryMessages`、`prefixChange`、`contextChars` / `inputChars`、`sectionChars`。记录**按 `taskType` 各保留最近 `AppLimits.Api.REQUEST_METRICS`(60) 条**（一种请求不会挤掉另一种）。

- **`round`**：主对话请求在本轮内的 1 起序号（工具轮、FINAL 轮、外层重试的每一轮各占一号），用来把一个回合的多条 `chat.main` 归并成一组；其他 taskType 为 null。
- **`sectionChars`**：本请求**实际带上的 volatile 各段字符数**（键 = `VOLATILE_SECTION_KEYS`：`time`/`state`/`unfinished`/`lorebook`/`scene`/`recall`/`privateMemory`/`writing`/`topics`/`instruction`/`timeline`）。为省预算被整段丢弃的段不出现（而不是记 0）；全为空时该字段是 null，旧行也是 null。设置页「调试信息」导出的最近 12 条明细里带 `round`、`sectionChars` 与 `prefixChange`，用来判断"这次破缓存是我改了设定、历史增长，还是某一段在膨胀"。
- **存储**：`:core:data` 的 `MetricsRepository` 把整份 `List<RequestMetric>` 存在**独立的 DataStore 文件**（`deeptalking_metrics`）里，每次追加是一次原子读-改-写（`MetricsStore.updateRaw`），`AppViewModel.metrics` 直接从它读（`observe()`），面板不再读 `AppConfig`。`AppConfig.requestMetrics` 只剩**旧备份/WebView 兼容**用途：不在运行时写入，导入旧备份时由 `migrateLegacy` 一次性采纳（仓库已有数据则 no-op）。备份把指标写成**根级 `requestMetrics` 数组**（所有字段，`round`/`sectionChars` 在内），导入时按数组整体替换（`merge=false`）。

- **每条请求一行**：主对话用 `chat.main`——工具轮、FINAL 轮、外层重试的每一轮都是独立一行；turn worker 用 `chat.worker`（有用量、失败、或留下任何 note 才写；`phase` 为 `worker-failed: <原因>` 或 `worker-note: <原因>`）。后台（抽取/分析/场景/世界书/字段补全/快速回应）用自己的 taskType，不计入面板的主聊天行。
- **失败也留痕**：空回复写一条 `chat.main` + `status=failed`、`phase=empty-reply`；异常轮把错误摘要写进 `phase`；辅助调用失败同样 `status=failed`。
- **`cacheStats` 快照**在每个完成的回合写一次（取本轮**第一条** `chat.main` 的用量），只作为兼容/诊断数据保留；它描述上一轮，不驱动右上角状态，也不显示“正在拼接上下文”。
- **缓存面板（▾）**固定两行中文：**主聊天 / 后台处理**（`CacheMenu`）。主聊天按 `round` 回溯到本回合的第一条 `chat.main` 并把各工具轮加总（没有 `round` 的旧行不显示，绝不拿单次请求冒充整轮）；后台处理取 `chat.worker` 最新的成功行。短期/长期/世界书等后台任务不放进缓存面板，避免用单次一次性请求的 0% 干扰判断。失败请求仍留在调试明细，但**不参与缓存面板/命中率**，避免一条没有用量的失败行把上一次正常数据顶成「缓存 --」。没有用量时该行写「缓存 --」。
- **逐请求明细**在设置页「调试信息」：中文分字段展示最近 12 条 `RequestMetric`（类型、状态、耗时、输入/输出、命中/未命中、命中率、`contextChars`、`inputChars`、`prefixChange`、`commonHistoryMessages`、逐段 `sectionChars` 及合计），用于判断"这次破缓存是我改了设定、历史自然增长，还是某一段在膨胀"。回复原文不再放进调试信息。

## 七、人格与 Prompt

### 规则清单（system 提示词，按出现顺序）

| 规则 | 内容要点 |
|---|---|
| `0` | 只输出角色这一轮的回复正文；不要 JSON、字段名、工具说明、前后缀或代码块围栏 |
| `1` | 保持角色身份，先用手上已给的记忆；"系统提供的本轮上下文"不是用户的话 |
| `2` | 像日常说话：可只一句/几个字/一个动作；整段 ≤250 字；自然分段；不总结、不升华、不硬抛开放问题 |
| `3` | 只写角色自身的言行，不替用户说话或行动；用户括号里的动作/心理只作语境 |
| `4` | 动作/表情/心理/环境用全角括号、单拍短、总量 ≤ ¼；台词直接写不加引号；引用用「」《》；不用标题/列表/代码块 |
| `5` | 需要工具时**只调工具、不写正文**；工具结果回来后再写正文；正文不提工具名或检索过程 |
| `6` | 文风以"性格特征"、可选"声音底色"和"对用户的称呼"为准；不向默认助手腔靠拢；不复用最近几轮的整句/句式/比喻/意象/收尾 |
| `7` | 通用真人感（与角色无关，所有角色共享）：句长要乱（多数 3~15 字、长句 ≤40 字）、允许不完整句/省略主语/停住改口/语气词；情绪不直说、不必每轮表态；可以不接话/只回半句；近三轮用过的词句不再复用；禁总结升华排比与结构词；禁 AI 腔固定句 |
| `A` | 本会话可用工具清单（9 个，逐一点名）+ 何时用哪个工具的导引；日期用 `search_memory` 的 `date`/`timeRef`；说"第一次/从来没/总是"前先检索；B 站视频用 `bilibili_video`（给 query 搜、给 url 总结；依据字幕/站方总结/画面，`has_subtitle=false` 时说清"没字幕"；`mode=summary` 是摘要不是全文，有 `unread_sec` 就如实说看到哪、带 `from` 续读，想看画面就带 `frame_at`）；表情包用 `send_sticker`（`keyword` 发已有、`query` 现找一张，失败原因如实转述） |
| `10` | 群组：只能由已列出的成员发言，每人独立一行"成员名：内容"；成员之间语气必须显著不同 |
| `11` | 群组：成员私人记忆互不相通，只有全体都知道的事才写共享记忆；不得张冠李戴 |
| — | 【角色设定（固定，不随对话变化）】= `buildSystemPrompt` 的静态角色块（群组含群组前提与成员清单） |
| — | 【输出通道】正文之后另起一行，只写一行 `【语气】<朗读指导，≤60 字>`；语气只来自性格、声音底色与称呼；**签名标记是底色不是每轮配额**（同一标记不要连着两轮重复） |

### 节奏骨架

`NARRATIVE_PATTERNS`：从 10 条结构模板（动作/描写 → 对白 → 描写的排列组合）里每轮随机取一条，写成 `8 本轮写法` 里的 `骨架: <骨架名>：<说明>`，用于打破"每轮结构雷同"。

### volatile 段落（按顺序）

固定 11 段，空段整段不出现：

1. **【时间】** — 本地时钟 + 周几 + 时段 + 逻辑日；间隔 ≥6 小时追加"可温和重连"。
2. **【当前状态】** — 处境/位置/情绪/职业/目标（每项 ≤60 字，不含 `currentTone`）；群组为"群组: …" + 每位成员一行。
3. **【未完成事项】** — `[待办]`（未结束，按期限升序，过期标注）+ `[周期]`（周期性 promises/habits）；同一件事不重复出现；整段 ≤400 字。
4. **【世界书】** — ≤6 条 / ≤1400 字；正文为空的条目写成"（正文为空，待补充；条目ID: …；关键词: …）"并提示按 ID 补写。
5. **【场景概要】** — 最近 1 条，≤300 字。
6. **【记忆召回】** — 用户最新纠正行 + 最多 3 条召回（每条 ≤1200 字）+ 一句免责与主体说明；无召回时写"无相关长期记忆"。
7. **【成员私人记忆】**（仅群组）— 选中成员各 1 条，每人 ≤300 字；成员集合 = 本轮提到的成员名 → 上一轮发言人 → 全员。
8. **【本轮写法】**（≤500 字）— 口吻（**声音底色摘要 ≤40 字 + 称呼**，群组按成员分行；**不再发示例台词**，示例在 system 的角色设定里）→ 骨架（10 条模板随机一条）→ 话题（冷场/切换二选一）→ 上一轮问题（违规标签，或每 3 轮语气回顾）→ 收尾不要重复 → **本轮别用**（`repeatedPhrasesIn` 从近三轮挑 3~6 字重复片段，排除角色名与称呼，最多 3 个）。
9. **【可选话题】** — 仅冷场或主动开场；3 条、每条 ≤60 字。
10. **【本轮指令】** — 主动开场 / 热榜开场（`hot_board(platform=zhihu)`），可同时出现。
11. **【消息时间元数据】** — 最近 14 条非 loading 消息的 `id | role | timestamp`，供 `sourceMessageIds` 引用。

> 顺序原则是"越靠后越近"：身份状态在前，较早情节（场景）→ 较早记忆（召回）→ 现在（时间/时间线）→ 本轮指令在后。**短期窗口不在这里**：它整窗注入到历史之前（见"请求装配与缓存前缀"）。整段 >8000 字时按 9 → 5 → 4 → 6 的顺序丢弃（见 §六 预算）。
>
> **周期性约定**：`Prompts.selectStandingCommitments()` 用确定性判据（`category` ∈ promises/habits 且 `status=active` 且 key/value 命中周期性词表——`每(个)?(天|日|晚|早|晨|周|月|年|次|回|逢|一)` / `一直|永远|始终|照例|如常` / `every( day|night|week|month|time)` / `always` / `forever`，裸「每」不算）挑出"每晚…/一直…"这类每轮生效的约定，排序 importance ↓ → 最近更新 ↓ → id，取前 4 条，与待办合并进 `3 未完成事项`；**无命中时整段不出现**。它是"固定仪式被静默遗忘"的补丁，不是第二份记忆注入。

### 语气/风格遵从（attention）

把最容易违反的约束尽量贴近生成点，并用客户端检测闭环（纯函数，可单测；在 `StyleGuard`）：

- **静态声明**：`OUTPUT_CHANNEL` 要求语气只来自性格、"声音底色"与"对用户的称呼"，不得向默认助手腔靠拢；**签名标记是底色不是每轮配额**——不再要求"至少带出 2 处"，否则模型会为凑数反复用同一批词（这是"每轮固定语句"的根因）。`RULE_6` 复述同一条文风约束，`RULE_7` 给通用真人感。
- **贴生成点的口吻行** `voiceSpec()`：`8 本轮写法` 第一行给出「口吻: 声音底色摘要（≤`VOICE_SUMMARY_CHARS`=40 字）；称呼: …」；群组为每位有底色的成员各一行。**不再发示例台词**（示例已在 system 的角色设定里；尾部再发会被逐字照抄成模板）；底色为空时不出口吻行。
- **反模板闭环** `StyleGuard.repeatedPhrasesIn(replies, limit, exclude)`：从近三轮回复里找 3~6 个连续汉字、出现在 ≥2 条回复里的片段（排除角色名与称呼，被更长片段覆盖的不重复报），取最多 3 个写进 `本轮别用`。它是"反重复"的确定性度量，也是效果验证指标。
- **违规检测** `StyleGuard.detectStyleViolations(reply, char, prevReplies)`：纯函数返回标签
  `metaTalk`（元话术）／`speaksForUser`（替用户说话）／`cliche`（陈词滥调表）／`reusedImagery`（复用近期意象）／`toneDrift`（**句长过于整齐、缺少长短起伏**，即"像模板"）／`recitedLore`（复述世界书条目）。
- **内部提醒**（不展示给用户）：违规标签随消息持久化（`message.styleViolations`），下一轮由 `buildWritingSection` 收进 `8 本轮写法` 的"上一轮: …，本轮不得再出现"一行。
- **每 N 轮语气回顾** `styleReviewDue()`：`reviewEveryTurns`（3）轮一次，在没有具体违规时把语气要点写进同一行。
- **critique 调用** `BackgroundTasks`（只改文风、不改剧情）：命中违规且 `AppConfig.styleCritique` 开启时追加一次修订请求；解析失败或长度比例异常一律**回退原文**；**引用保护**（确定性兜底，`StyleGuard.keepsProtectedContent`）：把 `「」『』《》〈〉` 与英文/弯引号包起来的引用段落、以及世界书条目名/角色名/成员名/对用户的称呼逐一比对，**只要有一处被改写、增删或换序就整体回退原文**（不做局部修补——那会留下半截被改写的引用）；修订后重新检测并以剩余违规为准。

### 快速回应视角（quickReplies）

「快速回应」必须是**用户**下一句会发的话，而模型容易写成角色口吻或角色的提问。链路是"worker 生产 + 客户端校验 + 后台换位"：

- **生产层**：turn worker 的 `TurnWorker.SYSTEM` 明确要求"恰好两条、用户此刻可能原样发给角色的话；不得是角色口吻、角色提问或复述角色刚说过的话；不写 Markdown、括号动作、消息 ID"，`submit_state` 的 `quickReplies` 也是 `minItems/maxItems = 2`。解析侧按 `QUICK_REPLY_COUNT`(2) 严格计数：**多于两条只留前两条**、**少于两条整组弃用**（留给修复/兜底），两种情况都在 `warnings`/`workerNotes` 里留一句原因，不静默截断。主调用提示词不再提快速回应。
- **校验层** `StyleGuard.detectQuickReplyIssues(replies, char, replyText)`：纯函数返回标签 `missing` / `placeholder`（照抄占位文字）/ `action`（带括号动作）/ `mirrored`（与角色本轮台词逐字或镜像重复）/ `characterName`（出现角色名）/ `tooLong`。
- **修正层** `BackgroundTasks.repairQuickReplies()`：**只在 worker 没跑、没成功或不足两条时**（`quickReplyRepairEnabled = config.quickReplyRepair && (!worker.ok || quickReplyCount < 2)`）做**换位生成**——用一个身份为"用户本人"的后台小调用重写（system 明写"你就是这位用户本人…不要扮演<角色名>"），成功后只更新快速回应按钮，**不触碰已显示的正文**；失败保留 worker 已给的两条（没有则不出按钮）。开关 `AppConfig.quickReplyRepair`（默认开）。worker 成功时既不重写、也不把校验标签写回消息，避免和 worker 抢结果、每轮重复触发修复。
- **不阻塞**：quickReplies 不参与"是否重发一轮"的判定；worker 失败也只是没有按钮，正文照常显示。
- **内部记录**：真正跑修复时才把问题写进 `message.quickReplyIssues` 留档；volatile 不再单独出一段"快速回应视角"提醒，视角问题在 worker 提示词与校验层处理。

### 人格组装

- `buildSystemPrompt()`（`:domain:agent` 的 `prompts/Prompts.kt`）：从 `char.staticProfile` 拼人格（10 项 `STATIC_PROFILE_FIELDS`）；群组则拼群组信息 + 成员清单。
- 动态状态渲染为"状态"文本（未设置的显示 `(未设置)`；volatile 里的角色/成员状态块同样用 `(未设置)` 并脱敏"用户"词）。
- `RequestBuilder.build()` / `mapHistory()`：装配 system + 历史 + 本轮 volatile；`mapHistory` 丢弃被拒绝的消息、对空 assistant 消息整条丢弃、非空 assistant **按纯文本原样回放**（`trimText(1200)`）、贴图转图片。

### 子任务提示词（非主对话）

主对话之外的每次辅助调用都有独立 prompt，各司其职（`BackgroundTasks` / `NativeCore` / `CharacterParity`）：

| 提示词 | 位置 | 关键约束 |
|---|---|---|
| 一键建卡 `buildQuickGeneratePrompt` | `feature:characters` 的 `CharacterParity` | 输出完整字段 JSON；**禁止套路名字与模板人设**；`personality` 写行为倾向；`background` 只写角色本人经历（世界观写 `lorebook`）；`speakingStyle` 只写「声音底色」1~2 句（语速/用词层次/句尾习惯/称呼，≤60 字，**不写示例台词与口癖清单**，`CHARACTER_QUALITY_RULE` + `VOICE_BASE_RULE`）；群组分支要求成员声音底色显著不同，并输出 2-4 条初始 `lorebook` 条目 |
| 群组升级 `upgradeToGroup` | `:app` 的 `AppViewModel` | 与建卡同一套质量约束；`additionalMembers` 不得重复原角色与彼此姓名；可补 0-3 条 `lorebook`（只补近期已出现的世界层设定）；原角色世界书并入群组 |
| 世界书整理 `consolidateLorebook` | `BackgroundTasks` | **跟随短期抽取**（排在长期分析之前）；只从**已发生的短期摘要**里沉淀世界层设定（`sourceShortTermIds` 必须可回溯）、禁止推测扩写；给出已有条目的完整内容；"同一事物只一条：整条替换、允许变短、≤400 字"；≤`AUTO_ENTRIES_PER_PASS`(3) 条；写后本地去重并按容量规则归档（全局 200、单次 ≤`EVICT_MAX_PER_PASS`(4)、人工/常驻豁免）；**只写世界书**，失败留在内存重试/交给用户决定 |
| 世界书一次性迁移 `migrateWorldLore` | `BackgroundTasks` / `NativeCore.runWorldLoreMigration` | 把旧数据 `description`/`background` 里的世界观拆成条目（只整理已有信息）；人工维护条目不受影响；可选同时精简原文 |
| 成员补全 `fillMemberFields` | `NativeCore` / `CharacterParity.applyMemberFillPayload` | **已有字段是绝对权威，不得改写/润色/替换**；只填空字段；说话方式须与群内其他成员显著不同；无把握则省略 |
| 空字段补全 `autoFillStaticFields` | `BackgroundTasks` | 只补缺失字段，绝不覆盖已有设定；缺 `speakingStyle` 时套用示例台词约束；群组成员再叠加"彼此不同" |
| 即时→短期摘要 `analyzeShortTermBatch`（抽取） | `BackgroundTasks` | **整窗折叠**：`instant` 满 60 触发，输入是**整个窗口**；按"日期+时段"合并；**只保留影响后续剧情的内容**；**每条 ≤120 字**；禁止文学化与对白原文。**不要求模型点名消息 id**：原话证据由客户端按同一"日期+时段"从窗口里采；重复靠提示词注入**已有短期事件**来压 |
| 短期→长期 `analyzeShortTermBatch`（分析） | `BackgroundTasks` | **整窗折叠**：`shortTerm` 满 40 触发，输入是**整个窗口**（低水位留下的 20 条只作上下文）；**价值判据**（可跨轮复用/影响后续/用户明确表达）才记，**证据不足宁可不记**；`arcOf` 命名一旦确定保持稳定；**取证链锚在短期条目上**；**每条 value ≤200 字、arc 叙事 ≤300 字** |
| 场景概要 `summarizeScene` | `BackgroundTasks` | 1-2 段 ≤300 字，只含剧情要点，不引对白、不编造、写绝对日期 |
| 文风校对（critique） | `BackgroundTasks` | **只改文风、不改剧情**；不得复述规则；只返回 `{"reply":"…"}`；空结果或长度比例异常一律回退原文；**引用段落（「」『』《》〈〉/引号）与世界书条目名/角色名/成员名/称呼被改动也整体回退** |
| 快速回应换位生成 `repairQuickReplies` | `BackgroundTasks` | 身份是**用户本人**；只输出 `{"quickReplies":[...]}`；结果再过一遍 `detectQuickReplyIssues`，不通过就放弃 |
| 回合整理 `submit_state`（turn worker） | `TurnWorker` | 强制 function call、`stream=false`、`reasoningEffort=none`；只写本轮有依据的增量；动态字段须带 `sourceMessageIds` + `evidence`（成员 `currentTone` 除外，免证据门）；快速回应恰好两条；待办新建须可定位期限 + 双方同意（`detail` 可空）；约定只在用户明确完成/取消时更新 |
| 字段重生成（手动） | `NativeCore.regenerateCharacterField` | 只在角色卡编辑器点“重新生成”时运行，只重写用户点名的一个静态字段；结果先回编辑框草稿，保存才落盘；聊天回合不触发 |
| 其他 | emoji 头像（`NativeCore.requestEmojiAvatar`）、字段重排（`remapFields`）、贴图标签（`NativeCore.tagSticker`） | 低价值路径，未做经验对齐 |

## 八、健壮性

- **API 请求级重试**：`ApiRetry`（`:domain:agent`）对瞬时失败（网络错误 / 超时 / HTTP 429 / 5xx）自动重试最多 3 次（1s/2s/4s 指数退避）；**用户主动取消不重试**（`CancellationException` 直接抛出）。后台辅助调用在 `BackgroundTasks.completeDetailed` 用同一策略。
- **工具失败可恢复**：工具失败时回传可修正的提示（如 `memory_write` 找不到 ID 时提示先用 `search_memory` 查询）。工具返回 `ok:false` 不中断回合，模型下一轮可修正。
- **工具轮上限**：`MAX_TOOL_ROUNDS`(5) 按"模型响应"（轮）计数，达到上限直接转 FINAL 出正文；同一条响应里的所有 `tool_calls` 都会被执行，不会把整轮耗在工具上。`MAX_TOOL_CALLS`(12) 目前无判定点（保留常量）。
- **记忆任务重试**：`extraction` / `analysis` / `scene` / `lorebook` 四组计数器（`*Failures` / `*RetryAt`），`scheduleMemoryRetry` 指数退避（5→30 分钟封顶）。
- **后台任务过期保护**：`captureGuard` / `isGuardCurrent`（`revision` + 末条消息 ID）保护**会覆盖用户可见状态**的任务（场景概要、静态补全、动态状态）；**折叠/分析这类只做增量的任务改为合并写回**——折叠先在请求发出后提交 `instant` 的最后 20 条，提取结果只追加 `shortTerm`；失败保留原请求，不等待、不回灌旧窗口。
- **摘要不被提前丢弃**：消费只发生在读它的那一趟之后——分析失败的批次原样留在窗口里、由退避门控重试；**世界书整理排在长期分析之前**、读的正是短期抽取刚产出的那批（再加内存里上次失败遗留的），逐条校验来源与字段，坏条目跳过（其余照常入库）并记录原因；这一趟失败时那批**留在内存待重试**（连续失败后弹窗问「重试 / 忽略」），不会因为分析随后消费掉短期条目而永久丢失。**窗口去重只在消费这一刻做**（`BackgroundTasks.settleShortTerm` → `MemoryPolicy.dedupeShortTerm`）：同一逻辑日+时段且相似度 ≥0.6，或全局 ≥`NEAR_DUP_SIMILARITY`(0.72) 合并；稳定期内不碰窗口，否则已发出的请求前缀会变得不可预测。
- **写入只写自己那部分**：所有写路径（主对话收尾、后台维护、编辑器）都用 `mergeCharacterChanges`（`core:model`）把"本次真正改动的字段"合并到最新行上——普通字段按值差异判定、世界书**与长期记忆都按条目 id 三方合并**（并发新增的条目不会被抹掉，删除才真正删除）。因此长期的整份写回不再互相回滚：世界书整理只动世界书、聊天收尾不会再把你期间的世界书/记忆改动覆盖掉（连"只给某条承诺落确认戳"的那点改动也不会整表写回）。
- **检索相关性下限**：`rankMemories` 只保留至少命中一个非停用词的候选，并用特异性下限 `MIN_MATCH_SCORE`(1.5) 过滤泛词命中；`pendingRecall` 优先占位；短指代查询经 `buildMemoryQuery` 补上下文；**用户提到具体日期时由模型用 `search_memory` 的 `date`/`timeRef` 按日期召回那天的原始对话与记忆**。
- **断言前先核对**：系统提示词要求模型在说"第一次/从来没/总是/每次"这类**可被记忆证伪**的话之前先 `search_memory` 确认，查不到依据就不下断言（改说不确定或反问）。
- **短期记忆上限靠"消费即丢"不靠"截断"**：分析一趟读走低水位之上的那一段（每批最多 `ANALYSIS_BATCH`(40) 条），成功后原样丢掉；没读到的最新 20 条永不丢，稳态就是 20~40。图片只保留最近 `IMAGE_RETENTION_MESSAGES`(60) 条消息（更早的压成缩略图、正文保留）。**整理记忆和整理图片是两个独立弹窗**：`AppViewModel.checkMemoryCleanupPrompt` 只在短期**超出高水位 40** 时问「整理记忆」（21~40 的正常窗口不弹、也不算待消化），确认后 `drainShortTermBacklog` 只处理 `size > 40` 的角色，每次 1 批、空闲每分钟 1 批，直到回到高水位以下才清除标记；`checkImageCleanupPrompt` 只在有超窗旧图片时问「整理图片」，确认后只跑 `pruneOldMessageImages`，**不读也不改短期记忆**，因此不会触发分析、不会改变注入前缀。两个弹窗串行出现（记忆 → 图片 → 世界书整理）。**去重/修复这类 O(n²) 重活跑在 `Dispatchers.Default`/`IO` 上**（放主线程会 ANR，且修复标记写不进去 → 每次启动重跑）。
- **时间精度**：`TimeUtils.resolveTimeRef` 保留带时区/时分的 explicit ISO，仅"日期或时段"表达才落整点/时段起点。记忆衰减/排序的时间基准逐级回退（`lastRecalled` → `updatedAt` → `createdAt`），不可解析时不短路更新的时间戳、也不被当成 1970 年。
- **联网工具超时**：`HttpWebContentProvider` 给 B站 API、短链解析、Microlink、网页直连统一设超时，避免任意外部请求卡死工具循环。
- **请求失败也留痕**：主对话每条请求一行 `chat.main`、worker 一条 `chat.worker`；失败都写 `status='failed'`（原因在 `phase`），空回复写 `empty-reply`。`authorizationHeader` 在 API Key 缺失时提前抛清晰错误，不发出 `Bearer undefined` 请求。
- **文风校对可回退**：critique 在任何异常（解析失败、空结果、长度比例异常）下都返回原文；**引用/专有名词被改动也整体回退**（`StyleGuard.keepsProtectedContent`，逐字比对引用段落与条目名/角色名/称呼）。
- **快速回应可回退**：换位生成失败（或结果仍不过校验）时保留 worker 已给的两条；回来太晚（已被下一轮覆盖）直接丢弃，不会覆盖新一轮。
- **后台任务互不排队、写回合并**：`BackgroundTaskQueue` 不设共享锁（一次补课不会把场景/世界书/快速回应堵住），各任务用 `mergeCharacterChanges` 只并自己那份，所以交错执行也不会写坏同一份角色状态；都不阻塞正文显示。
- **正文即终稿**：主调用拿到正文就直接采用，worker 只在正文之后补结构化字段；整轮整理失败会自动重试 2 次，仍失败保留旧状态并给红字/弹窗，局部规则拒绝带短标签到下一轮，绝不改写已显示的正文。
- **后台写回只改自己动过的字段**：`CharacterRepository.mutate(id)` 用 **per-id 锁**做读-改-写，`NativeCore.persistMemory(original, updated)` 与 `AppViewModel.writeCharacterChanges` 都在这个锁里用 `mergeCharacterChanges` 只把自己动过的字段并到**最新行**；编辑器保存（`AppViewModel.saveCharacterEdit`）同样是字段级合并：静态字段只写用户真正改过的 key（并给这些 key 盖 `staticFieldMeta`，后台补全同时写入的其它字段保留实时值）、待办按 id 逐条合并、成员静态字段（`staticProfile`/姓名/emoji/`roleInGroup`）逐个成员按 id 合并，成员世界书与记忆窗口保持实时值。因此并发写入不会互相回滚，也不会用旧快照覆盖 `updatedAt`。
- **焦点保护（`FieldEditRegistry`）**：编辑器把用户正在输入的字段登记为聚焦（键 = 角色 id + 成员 id + 分区 static/dynamic + 字段），登记带 owner，关闭/保存/切换只清自己那份。写入方在落盘前逐字段询问：turn worker 的动态状态、主调用的 `【语气】`（`applyTone`）、成员动态状态、后台 `autoFillStaticFields` / 成员补全、旧格式记忆里的 `dynamicState` 更新——**被用户占用的那个字段跳过，其余字段照常写入**。这样后台拿着"开编辑器之前"的快照算出的值不会覆盖正在输入的文字，编辑框里的草稿也不会被后台写回吞掉。
- **共享 HTML 工具**：HTML 实体解码/去标签/可读文本抽取是同一份实现，避免多处漂移。
- **导入/导出归一化**：`LegacyImportService` / `BackupService` 逐字段校验（世界书、场景概要、场景状态、计数器、来源数组、**待办 `todos`**、**提示 ack / `workerIssues` / `memoryRepairVersion`**），坏角色跳过并计入 `ImportSummary.skipped`。新版备份恢复原生 TTS 设置与数值（旧备份缺字段时沿用当前值，音色路径按文件名匹配本机），API key 与远程朗读口令放在根级 `secrets`，用用户设置的备份密码做 PBKDF2-HMAC-SHA256 + AES-256-GCM 对称加密；密码错误不导入、不修改当前密钥。导入/导出的备份密码输入框为明文（仅用于本次加解密，不保存）。若备份的 `memory.instant` 低于低水位而 `history` 足够，导入时用 `history` 尾部重建工作窗口（修复旧版/失败回合留下的单条合成回复）。**导入归一化用多信号加权判断长期事件是否同一件**：主题/key 0.28、正文 0.38、来源重合 0.24、参与者 0.06、地点 0.04（缺失维度重新归一），相反结果/人物/地点明确冲突一票否决；总分 **严格 >0.70** 才自动合并，合并保留完整正文和双方全部来源 id（缺 role 也保留 id），不再按时段批量合并、不再截断正文。
- **版本号单一来源**：`android-lite/app/build.gradle.kts` 的 `versionName` / `versionCode`，应用内版本徽标读 `BuildConfig`；改动用 `tools/native-version.ps1`。

## 九、关键常量速查

常量集中在 `:core:common` 的 `AppLimits`（`:core:model` 的 `BuiltinPlatforms` 定义平台预设）。

| 常量 | 值 | 备注 |
|---|---|---|
| `Agent.MAX_TOOL_ROUNDS` / `MAX_TOOL_CALLS` | 5 / 12 | `MAX_TOOL_ROUNDS` 是 AUTO 阶段的工具轮上限（按模型响应计）；`MAX_TOOL_CALLS` 目前无判定点（保留） |
| `Api.AUXILIARY_TIMEOUT_MS` | 90 000 | 辅助/后台调用的传输超时（chat 为 180s） |
| `Api.AUXILIARY_MAX_OUTPUT_TOKENS` | 8 192 | 辅助调用显式输出上限 |
| `Api.REQUEST_METRICS` | 60 | 请求指标保留条数（**每个 `taskType`** 各自 60） |
| `Memory.INSTANT` / `INSTANT_TRIM_FLOOR` / `INSTANT_HARD_CAP` / `INSTANT_MIGRATION_THRESHOLD` | 60 / 20 / 120 / 120 | 历史窗口高低水位、紧急上限、补课阈值 |
| `Memory.SHORT_TERM` / `SHORT_TERM_TRIM_FLOOR` / `SHORT_TERM_HARD_CAP` / `SHORT_TERM_MERGE_WINDOW` / `SHORT_TERM_LOW_WATER` | 40 / 20 / 80 / 5 / 20 | 短期窗口形态 |
| `Memory.ANALYSIS_BATCH` / `SUMMARY_SOURCES` / `CLEANUP_BATCHES_PER_RUN` / `NEAR_DUP_SIMILARITY` / `PENDING_RECALL` | 40 / 60 / 1 / 0.72 / 6 | 折叠批大小、清理批数、近似去重阈值、待召回上限 |
| `Memory.LONG_TERM_PER_CATEGORY` | 40 | 保留常量，长期记忆当前无数量淘汰 |
| `Prompt.ROLE_CHARS` / `MEMBER_CHARS` | 8 000 / 900 | 单角色设定 / 群组成员注入上限 |
| `Lorebook.ENTRIES` / `KEYWORDS_PER_ENTRY` / `NAME_CHARS` / `CONTENT_CHARS` / `INJECT_ENTRIES` / `INJECT_CHARS` / `MAX_ALWAYS_ACTIVE` / `AUTO_ENTRIES_PER_PASS` | 200 / 8 / 60 / 400 / 6 / 1 400 / 6 / 3 | `CONTENT_CHARS` 是写入约束（超限拒收不截断） |
| `Scene.RETAIN` / `SUMMARIES` / `SPAN` / `MIN_MESSAGES` | 8 / 2 / 12 / 6 | 场景概要保留条数、触发跨度与最少消息数；`SUMMARIES` 为保留常量，**实际注入最近 1 条** |
| `History.ARCHIVE_MESSAGES` / `PAGE` | 400 / 50 | 聊天存档上限与分页 |
| `Media.IMAGE_RETENTION_MESSAGES` / `THUMBNAIL_DIMENSION` / `THUMBNAIL_QUALITY` / `IMPORTED_IMAGE_*` | 60 / 384 / 60 / 512 / 70 | 图片保留与缩略/导入压缩 |
| `Sticker.MAX_DIMENSION` / `TARGET_BYTES` | 384 / 120 KB | 贴图压缩目标 |
| `max_output_tokens` | 8 192 | 主对话请求体 |
| `DYNAMIC_STATE_FIELDS` | 6 | `Prompts`（`currentTone` 取代旧关系两字段） |
| `STATIC_PROFILE_FIELDS` | 10 | `Prompts` |
| `RECALL_INJECT_LIMIT` / `RECALL_LINE_CHARS` / `VOLATILE_HARD_CHARS` / `WRITING_BLOCK_CHARS` | 3 / 1 200 / 8 000 / 500 | `Prompts`：召回条数与单条上限（总预算 3×1200）、volatile 硬上限、本轮写法整段上限 |
| `UNFINISHED_BLOCK_CHARS` | 400 | `Prompts`：未完成事项整段上限（待办 4 + 周期 4，每行 100） |
| `STANDING_COMMITMENT_LIMIT` | 4 | `Prompts`：周期性约定的条数上限（与待办合并进「未完成事项」） |
| `Todo.CLOSED_RETENTION_DAYS` / `INJECT_LIMIT` / `LINE_CHARS` / `BLOCK_CHARS` | 30 / 4 / 100 / 420 | `AppLimits`：已完成/已取消待办保留天数；待办注入的条数 / 每行 / 整段上限（`BLOCK_CHARS` 为保留常量，实际由 `UNFINISHED_BLOCK_CHARS` 统辖） |
| 请求追踪字段 | `RequestMetric[]` | taskType/round/platform/phase/status/tokens/hitRate/durationMs/prefixChange/commonHistoryMessages/hashes/sectionChars |
