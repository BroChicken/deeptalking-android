# Agent 架构文档（原生 Android）

> 本文档描述 **当前实现**：原生 Android 应用的 Agent 机制（`:domain:agent` / `:domain:memory`，宿主在 `:app`）。只写最新状态，历史看 git。
> 模块职责、依赖方向与构建流程见 [NATIVE_MODULES.md](NATIVE_MODULES.md)；解析机制链见 [PARSE_GRAPH.md](PARSE_GRAPH.md)；朗读链路见 [REMOTE_TTS.md](REMOTE_TTS.md)；**每一次外发调用（LLM/语音/联网）的传输格式与发送内容**见 [OUTBOUND_CALLS.md](OUTBOUND_CALLS.md)。
> 代码锚点用**模块:函数名**（行号会漂移，用 grep 函数名定位）；改动 agent 循环、工具清单、提示词、记忆系统、字段或常量时必须同步更新本文档。

## 模块锚点

| 关注点 | 原生锚点 |
|---|---|
| 宿主接线（回合、状态、指标、主动开口） | `:app` 的 `AppViewModel`（`submitTurn` / `proactiveLoop` / `recordMetrics` / `armHotTopicTurn`）、`NativeCore`（装配 `ChatOrchestrator`、后台任务、辅助调用） |
| Agent 循环 | `:domain:agent` 的 `AgentLoop`（`run` / `collectStream` / `attempt` / `salvageText` / `toolActivityHint`） |
| 回合编排与落盘 | `:domain:agent` 的 `ChatOrchestrator.run`（`applyMemory` / `applyLongTerm` / `applyDynamicStateFields` / `applyPromiseUpdate`） |
| 工具注册 | `:domain:agent` 的 `Tools.defaultTools`（+ `tools/*Tool.kt`） |
| 工具实现 | `:domain:agent` 的 `tools/*`（`SearchMemoryTool` 等） |
| 提示词装配 | `:domain:agent` 的 `prompts/Prompts.kt`（`buildSystemPrompt` / `buildVolatileContext` / `buildShortTermBlock` / `TimeContext`）、`RequestBuilder` |
| 结构化解析 | `:domain:agent` 的 `ResponseParser`、`StreamText` |
| 后台任务与文风守卫 | `:domain:agent` 的 `background/BackgroundTasks`、`background/StyleGuard`、`background/AnalysisSalvage` |
| 记忆系统 | `:domain:memory` 的 `MemoryServiceImpl` / `MemoryPolicy` / `Retrieval` / `MemoryEvidence` / `LorebookMatcher` / `LorebookStore` / `TimeUtils` / `RelativeTimeMigration` |
| 传输与联网 | `:core:network` 的 `ResponsesLlmBackend`（`buildBody` / `newRequest` / `ResponsesSseInterpreter`）、`HttpWebContentProvider` |
| 模型与常量 | `:core:model`（`Character` / `ChatMessage` / `AppConfig` / `RequestMetric`）、`:core:common` 的 `AppLimits` |
| 数据与迁移 | `:core:data` 的仓库层、`SettingsStore`、`legacy/LegacyImportService`、`legacy/BackupService` |

## 一、Agent 是什么

本项目的"agent"不是某个类，而是 **LLM + 人格 + 记忆 + 工具 + 运行循环** 的组合：

- 模型不再只输出一段文字，而是可以在一次回复中**主动调用工具 → 拿到执行结果 → 继续推理**，循环往复，直到给出最终答案。
- 这种"思考（reasoning）→ 行动（tool call）→ 观察（tool output）→ 收尾（结构化 reply）"的循环就是 agent 的本质。

> **人格 + 记忆 + 世界书** 提供身份与状态 → **`while` 循环**让模型反复"想→调工具→看结果" → **`submit_response`** 保证最终以结构化 JSON 收尾并落盘记忆。

## 二、总体数据流

```
用户输入（或主动开口 / 热榜开场）
   │
   ▼
ChatOrchestrator.run
   ├─ buildSystemPrompt            人格 + 交互规则（纯静态 → 可缓存前缀）
   ├─ buildShortTermBlock          短期窗口整块，注入到历史之前
   ├─ RequestBuilder.mapHistory    历史（append-only）+ 本轮用户消息
   └─ buildVolatileContext         记忆召回 / 世界书 / 状态 / 时间基准 / 本轮指令（末尾 user 项）
   │
   ▼
AgentLoop.run  loop
   ├─ ResponsesLlmBackend.stream   SSE：text / reasoning / function_call / web_search_call
   ├─ 有工具调用？→ ToolRegistry.execute 执行一个 → 结果回传 → continue
   ├─ 只调 ask_user？→ syntheticSubmitReply（把问题当回复）
   ├─ 拿到合法正文（submit_response / 结构化 JSON / 散文）？→ break
   └─ 否则 → 阶段二强制 submit_response（兜底，必要时重试）
   │
   ▼
ChatOrchestrator.applyMemory  落盘 longTerm / dynamicState / promiseUpdates（短期窗口不由主对话写）
   │
   ▼
BackgroundTasks.scheduleTurn  后台串行维护：文风校对、快速回应换位、记忆抽取/分析、场景概要、世界书整理、空字段补全
   │
   ▼
渲染回复 + quickReplies（缺失或视角不对时后台换位生成补齐）
```

## 三、五大部件

### 1. API 载体（Responses API）

- `ResponsesLlmBackend.buildBody()`（`:core:network`）+ `ResponsesDto`：请求体字段 `model`、`input`（首条 system 提升为 `instructions`）、`stream`、`temperature`、`max_output_tokens=8192`、`tools`、`tool_choice`、`reasoning.effort`。
- 端点：`responsesEndpoint()`（把 base URL 归一化为 `/responses`；DeepSeek 去尾 `/v1`，OpenCode Go 保留）。
- 请求头：`Content-Type`、`User-Agent`、`Authorization: Bearer`（缺 Key 抛清晰错误）、流式加 `Accept: text/event-stream`、`opencode` 平台加 `x-opencode-session`（`deeptalking-<charId>` / `deeptalking-general`）。
- 阶段一（auto）的工具数组里**包含 `submit_response`**（`Tools.defaultTools` 末尾），模型可以在同一轮里"调完信息工具 → 调 `submit_response` 提交"，一次请求收尾。
- 阶段二（submit）为**兜底**：只有阶段一连正文都没拿到时才进入，此时 `tools` 只有 `submit_response`、`tool_choice` 锁定，且 `reasoning.effort='none'`（DeepSeek 仅在该 effort 下支持锁定工具）。
- 传输超时：chat 180s（call + read），SSE 空闲 60s；**辅助/后台调用 90s**（`LlmRequest.auxiliary`，见"健壮性"）。

### 2. 工具清单（agent 的能力）

`Tools.defaultTools(memory, web, stickersEnabled)`——**13 个信息工具 + 条件 `send_sticker` + `submit_response`**（顺序即注册顺序）：

| 工具 | 作用 | 实现 |
|---|---|---|
| `get_current_time` | 获取当前时间/时区 | `GetCurrentTimeTool` |
| `search_memory` | 按关键词检索长期记忆（可指定成员私人记忆）；**支持按日期召回**（`date`/`timeRef`/`slot`，返回那天的原始对话+摘要+长期记忆） | `SearchMemoryTool` |
| `list_memories` | 按分类盘点记忆清单（不含详情） | `ListMemoriesTool` |
| `delete_memory` | 按 ID 删除记忆（`short_` 前缀可删短期条目） | `DeleteMemoryTool` |
| `set_reminder` | 记录待办/约定（promises，须用户发起 + 原话证据） | `SetReminderTool` |
| `ask_user` | 向用户提出澄清问题 | `AskUserTool` |
| `update_character_field` | 精准修改基础设定字段（说话风格、称呼等） | `UpdateCharacterFieldTool` |
| `upsert_lorebook_entry` | 写入/更新世界书条目（世界层设定唯一入口，保护人工维护条目） | `UpsertLorebookEntryTool` |
| `web_fetch` | 读取链接（网页正文 / 图片 / 站内专门解析）；虎扑帖子、网易云歌曲、B站视频链接各有专门解析。**只读链接、不做关键词搜索** | `WebFetchTool` → `HttpWebContentProvider` |
| `update_memory` | 按 ID 修正记忆（用户纠正时用） | `UpdateMemoryTool` |
| `send_sticker` | 发表情包（**仅当角色有贴图时注册**，schema 内联该角色的标签枚举） | `SendStickerTool` |
| `web_search` | 通用联网搜索（必应 RSS，`limit` 可调 1–20） | `WebSearchTool` |
| `search_video` | 按关键词搜 **B站** 视频（免账号） | `SocialSearchTool(platform=bilibili)` |
| `search_music` | 按关键词搜 **网易云音乐** 歌曲（免账号，返回歌曲 id 可用 `web_fetch` 看热评） | `SocialSearchTool(platform=netease)` |
| `search_posts` | 按关键词搜 **虎扑** 帖子（免账号） | `SocialSearchTool(platform=hupu)` |
| `baidu_hot` | **百度**热搜（免账号） | `HotBoardTool(platform=baidu)` |
| `toutiao_hot` | **今日头条**热榜（免账号） | `HotBoardTool(platform=toutiao)` |
| `douyin_hot` | **抖音**热榜（免账号） | `HotBoardTool(platform=douyin)` |
| `zhihu_hot` | **知乎**热榜（免账号） | `HotBoardTool(platform=zhihu)` |
| `submit_response` | 结构化收尾（见下） | `SubmitResponseTool` |

每个工具就是一段 JSON Schema 描述（name + description + parameters），模型据此决定何时调用。
**工具描述是"何时用哪个工具"的权威依据**，system 提示词的规则 A 只留一句导引。
新增工具 = 新增 `AgentTool` 实现 + 在 `defaultTools` 注册，`AgentLoop` 不变。

**一个工具只连一个平台**（搜索类）：平台是工具身份的一部分，不再通过 `platform` 参数让模型去挑——
`search_video`/`search_music`/`search_posts` 与四个 `*_hot` 都是同一个类按固定平台常量实例化
（`SocialSearchTool` / `HotBoardTool`），因此不存在"选错平台"这一步，也不必让模型记住
`netease` 才是音乐这类隐式映射。代价是工具条数变多（工具 schema 属于请求前缀，一次性的
`toolsHash` 变化 = 每个角色一次破缓存）。`SocialToolsTest` 有回归护栏：这些工具的 schema
里不得再出现 `platform` 参数。

### 3. 工具实现（agent 的手）

`ToolRegistry.execute()`（`:domain:agent`）：

- 模型只"说"要调用，真正干活的是工具类：读写长期记忆、返回时间、记约定等。
- 统一返回 JSON 字符串，异常安全（`try/catch` 兜底，失败返回 `{ ok: false, reason }`），**异常不中断整轮**。

### 4. Agent 循环（大脑）

`AgentLoop.run()`（`:domain:agent`）：

- **工具调用（每轮一个）**：DeepSeek thinking 模式**不支持并行 `function_call` 回传（会 400）**，因此模型一次返回多个 `function_call` 时只执行并回传**第一个信息类工具**，其余丢弃、由模型下一轮基于结果重发；`reasoning` 必须先于 `function_call`、再跟配对的 `function_call_output`。`AppLimits.Agent.MAX_TOOL_CALLS`(12) 是整轮总量上限。若同一轮里既有信息类工具又有 `submit_response`，先执行该工具；只有工具失败（`ok:false`）时才丢弃这一轮的提交，继续让模型修正。**`web_search` 是本地 function 工具**（DeepSeek Responses 会静默忽略内置 `web_search`）：本地注册同名工具，经 `HttpWebContentProvider.searchWeb()` 实际检索 RSS 后回传结果。
- **上限**：`MAX_TOOL_ROUNDS = 5`（`AppLimits.Agent`）与 `MAX_TOOL_CALLS = 12`；达到任一上限后未执行的调用回传 `ok:false` 说明，并按"有正文就收尾、没有才转阶段二"处理。
- **reasoning 回传**：thinking 模式的 reasoning 项必须先回传，否则上下文断裂。
- **ask_user 特判**：本轮只调 `ask_user`（且无 `submit_response`）时，直接把问题包成结构化回复（`AgentLoop.syntheticSubmitReply`），不进入工具循环。
- **一次提交 + 兜底**（不再每轮重发）：阶段一 `RequestPhase.AUTO`（工具自由，含 `submit_response`）。只要拿到正文——`submit_response` 调用、合法 JSON 正文、或**散文**——就直接采用，**散文即终稿**，不再为了补 `quickReplies`/记忆而重发一轮。阶段二 `RequestPhase.SUBMIT` 只作为"阶段一连正文都没有"时的兜底，`quickReplies` **不阻塞收尾**（缺失/非法直接接受，由后台换位生成补齐）。
- **空回复重试**：同一循环内最多重试 2 次（共 3 次请求），保留已累计的工具往返。
- **散文轮的记忆/状态后台补**：散文收尾时正文立即显示，`BackgroundTasks.extractProseTurnMemory()` 在后台把这一轮整理成 shortTerm / longTerm / dynamicState 落盘（串行队列，不阻塞也不改写正文）。
- **状态显示**：循环内所有右上角文案经 `onStatus` 回调驱动（`AppViewModel.statusState`）；工具提示来自纯函数 `AgentLoop.toolActivityHint(name)`；阶段分支（`auto-tool` / `auto-tool-limit` / `auto-final` / `submit-retry` / `empty`）在 `AgentLoop.run` 内一一对应下表。

### 5. 结构化收尾协议（agent 的手续）

`SubmitResponseTool`（`:domain:agent` 的 `tools/`）+ `ResponseParser.parseSubmitResponse()`。

推荐每轮调用一次 `submit_response` 收尾（阶段一工具里就有它），用 JSON Schema（`strict: true`）强制校验，一次性输出：

| 字段 | 说明 |
|---|---|
| `reply` | 用户可见回复正文（Markdown，动作/表情/心理活动用全角括号） |
| `quickReplies` | 恰好两条**用户视角**短句（用户下一句可直接发送；不得是角色的台词、表态或角色对用户的提问） |
| `longTerm` | 未来仍有价值的稳定事实（category∈userProfile/relationship/events/promises/habits） |
| `dynamicState` | 角色动态状态（6 字段） |
| `memberDynamicState` | 群组成员动态状态（仅群组） |
| `promiseUpdates` | 用户明确完成/取消的承诺（resolved/cancelled） |
| `recall` | 主动召回记忆的请求 |

这是"回复 + 写记忆"合一的契约，保证每轮回复结构合法。**没有 `shortTerm` 字段**：短期窗口只由整窗抽取写入（见"记忆子任务"）。

### 6. 右上角状态显示（全分支）

| stage | 触发条件 | 动作 | 右上角文案 |
|---|---|---|---|
| `auto-tool` | 阶段一模型发起工具调用 | run-tool | `工具结果已返回，正在继续推理…` + 对应工具提示 |
| `auto-tool-limit` | 工具轮次达 `MAX_TOOL_ROUNDS` | accept-text / force-submit | 有正文 → `工具调用已达上限，直接收尾…`；无正文 → 正在整理 |
| `auto-final` | 阶段一未调用工具、拿到正文 | accept-text / force-submit | 合法 JSON **或散文** → 直接采用（无提示）；完全没有正文 → 正在整理 |
| `quick-replies-missing` | `submit_response` 有 `reply` 但缺/非法 `quickReplies` | accept-reply | 直接接受正文，`quickReplies` 交后台补齐（无提示） |
| `submit-retry` | 阶段二兜底仍未拿到合法 `submit_response` | retry-submit | 正在重新整理 |
| `empty` | 无可用正文 | regenerate / fail | 有额度 → `正在重新生成（第2次）…`；否则提示失败并保留用户消息 |

工具提示（`AgentLoop.toolActivityHint`）：`get_current_time`→`正在确认时间…`、`search_memory`→`正在回忆…`、`list_memories`→`正在整理记忆…`、`delete_memory`→`正在清理记忆…`、`set_reminder`→`已记下，正在回应…`、`update_character_field`→`已按你的要求调整设定…`、`upsert_lorebook_entry`→`已更新世界书…`、`web_search`→`正在搜索…`、`web_fetch`→`正在读取网页…`、`search_video`→`正在搜B站视频…`、`search_music`→`正在搜歌…`、`search_posts`→`正在搜虎扑帖子…`、`baidu_hot`/`toutiao_hot`/`douyin_hot`/`zhihu_hot`→`正在看热榜…`、`send_sticker`→`正在挑表情…`，未知工具回退 `正在处理…`。

> 主提示词的 0/7/8 与【输出格式】都要求"以结构化方式收尾"（调用 `submit_response` 或输出单个 JSON 对象）且 `quickReplies` 恒为两条用户视角短句；但**客户端不把 `quickReplies` 当收尾门槛**——散文/缺 quickReplies 的收尾一律直接采用。

## 四、记忆系统

### 三层结构 + 场景层 + 存档

常量集中在 `:core:common` 的 `AppLimits`。

| 层 | 内容 | 上限 |
|---|---|---|
| `instant` | 最近原始消息（发给模型的历史窗口） | 高水位 60 / 低水位 20（追加式，整窗折叠后压回 20） |
| `shortTerm` | 事件流程摘要 | 高水位 40 / 低水位 20（整窗注入，靠整窗折叠消化而非截断） |
| `longTerm` | 分类长期记忆 | 无数量上限（仅 `applyMemoryDecay` 时效遗忘） |
| `scenes` | 场景概要 | 保留最近 8 条（`Scene.RETAIN`），注入最近 2 条（`Scene.SUMMARIES`） |
| `history`（存档） | 完整聊天记录队列 | 上限 `History.ARCHIVE_MESSAGES`(400)，纯 FIFO，与窗口互不影响 |

**上下文窗口（高/低水位 + 整窗折叠）**：`INSTANT=60` / `INSTANT_TRIM_FLOOR=20`、`SHORT_TERM=40` / `SHORT_TERM_TRIM_FLOOR=20`。两个窗口都是同一形态：

- **窗口内容就是状态**，且是**追加式**的：每轮只把窗口没见过的消息追加进 `instant`（`AppViewModel.growInstantWindow`），**绝不从聊天存档重建**（否则折叠裁掉的消息会被捞回来，窗口只涨不缩）。只有折叠那一轮才把窗口压回低水位；`shortTerm` 同理——只有整窗抽取会追加、只有同一轮折叠会压缩，所以窗口在两次折叠之间**逐字不变**。
- **到高水位折叠（绝不按位置截断）**：`instant` 满 60 → **发送整个窗口**（60、61、62 条都发，绝不先砍成正好 60）→ `shortTerm` 追加条目 → **只消费刚读过的那一批**，保留最新 20 条作原文上下文；`shortTerm` 满 40 → 一次整窗分析（输入=整个 40 条窗口）→ 产出 `longTerm` → 落回最新 20 条。低水位留下的 20 条会作为下一次折叠的上下文被**再读一遍**（连贯性）；重复靠**提示词注入已有短期条目**压，不靠标记。**窗口超过 `INSTANT_MIGRATION_THRESHOLD`(120) 视为旧档遗留**，走**补课通道**（`BackgroundTasks.migrateOversizedWindow`：每批最旧的 60 条，折完即丢，**一次跑完**）；启动时检测到会**先弹确认框**（含「以后不再询问」），确认后弹**阻塞式**进度框，与正常通道严格分开。世界书条目内容超过 `Lorebook.CONTENT_CHARS`(400) 时同样由一次性重写处理（见"世界书"）。
- **抽取不点名消息 id**：模型把整窗压成按"日期+时段"划分的事件摘要，**原话证据由客户端按同一时段从窗口里采**（挂成 `userEvidence`/`sourceRoles`）。因此没有"id 必须存在""必须连续覆盖"这类硬约束，单条出错不再作废整批。
- **注入**：`instant` 整窗进历史；`shortTerm` 整窗进 **历史之前**的一条 user 块（`buildShortTermBlock`），因此它落在缓存前缀里。
- **紧急上限**：`INSTANT_HARD_CAP=120` / `SHORT_TERM_HARD_CAP=80`，仅在折叠持续失败时生效（丢最旧的并在 `counters.analysisLastError` 写明原因，绝不静默）。
- 更早内容由 `scenes` + 长期记忆 + `search_memory` 承载。`Scene.SPAN=12`。

### longTerm 五类

`userProfile`（用户信息）、`relationship`（关系）、`events`（共同事件）、`promises`（约定）、`habits`（习惯）。

- subject 仅限 `user` / `relationship` / `world` / `character`（`legacy` 仅作历史兼容，不再写入）。
- `MemoryEvidence.isValidAutomaticMemory()` 校验来源引用与证据逐字摘录，防止模型编造记忆。
- 短期记忆在捕获时写入 `sourceRoles` 与 `userEvidence`（用户消息逐字摘录，`[{sourceMessageId,text}]`），短期→长期分析据此在源消息滚出 `instant` 后仍能校验 `evidence` 逐字来自声明的用户消息。**`sourceRoles` 与 `sourceMessageIds` 是逐位对齐的平行数组**：合并来源必须走 `:core:model` 的 `alignSources`/`mergeSources` 逐位重建——把它当集合去重会让来源 role 塌缩，之后所有来源都变无 role、校验直接失败。
- 主对话的结构化写入（`submit_response` 的 `longTerm`/`staticFields`/`dynamicState`/`promiseUpdates`）同样过证据/意图校验（`ChatOrchestrator.applyMemory` → `MemoryPolicy.upsertLongTermMemory` / `MemoryEvidence.hasValidUserEvidence` / `hasStaticEditIntent` / `resolveDynamicStateSources`）。
- **短期记忆不由主对话写**：`submit_response` 没有 `shortTerm` 字段，窗口唯一的写入者是整窗抽取（`BackgroundTasks.analyzeShortTermBatch`）。这样窗口在两次折叠之间不会被写在中部或提前压缩，注入块才能保持逐字稳定。
- **短期条目可被修正/删除**：`update_memory`（`short_` 前缀 ID，支持 `value`/`eventTime`；改正后 `revision+1` 并移到队尾，等下一轮折叠写进长期记忆）与 `delete_memory`（同前缀）都能作用于短期；`search_memory` 的关键词召回也返回 `shortTerm` 命中。

### 相关度检索

`Retrieval.rankMemories` / `topK`（`:domain:memory`）：

- 每个命中词按「归一化特异性（IDF 式：含该词的记忆越少、权重越高）× 命中字段（`tags`×2 / `key`×1.5 / `value`×1）× 词长」加权，再乘覆盖率加成，压掉"晚上/事情"这类泛词。
- `topK` 先过相关性下限 `MIN_MATCH_SCORE`(1.5)，再按「同一事件（日期+时段）只留一条 + 每类最多 `MAX_PER_CATEGORY`(3) 条」去重；若下限会清空结果则回退到完整排序（不会召回为空）。
- 取 top 12，配合 `pendingRecall`（待召回记忆，强制置顶，上限 `PENDING_RECALL`(6)）。
- 自动注入与 `search_memory` 工具共用同一评分；群组可指定 `memberName` 检索成员私人记忆。

### 注入与衰减

- `buildVolatileContext(character, query, memory, now, proactiveTurn, hotTopicTurn, recentAssistantReplies)` — 把最相关的记忆与本轮动态上下文注入本轮 prompt（只注入 `AppLimits.Agent.TOOL_MEMORY_INJECT_LIMIT = 5` 条长期记忆，其余提示模型用 `search_memory` 检索）。**没有任何区块预算**：这是主对话请求体，所有块整块注入（只保留"每条条目"级别的兜底上限，靠提示词的软限制让它尽量不触发）。区块顺序按"越靠后越近"排列：可选话题库 → 角色当前状态 → 待跟进承诺 → 世界书 → 群成员私人记忆 → 用户最新纠正 → 互动节奏 → 场景概要 → 长期召回 + 取用说明/主体约定 → 时间基准 → 消息时间元数据 → 风格尾块 + 本轮指令。**最末尾两条系统触发的本轮指令**：`proactiveTurn`（主动开场）与 `hotTopicTurn`（热榜开场，见 `HotTopicPolicy`：每角色每逻辑日掷一次、10%、每天 1 次/每周 2 次；命中时要求先调一个热榜工具——`zhihu_hot` 优先——再挑一条话题带入）。两条都只加在 volatile 上下文尾部，**不进 system prompt**，所以不会打乱缓存前缀。`metadata` 回传本轮真正注入的召回 ID 与世界书条目，供主线在回复落盘后精确清除 `pendingRecall` 与累计 `mentions`。检索查询经 `buildMemoryQuery()` 扩展：用户明显指代前文时附加最近对话，避免短指代召回不到正确记忆。
- `MemoryPolicy.applyMemoryDecay()`：按时间衰减 events/promises 的重要性与状态。
- `MemoryPolicy.computeEffectiveImportance()`：`(importance + learnedBonus) × decay`（0-10 封顶）。

### 记忆子任务

由 `BackgroundTasks.scheduleTurn()` → `runMemoryMaintenance()` 每轮后台调度（各任务互不排队、可用假后端单测）：

| 任务 | 触发 | 计数器 | 说明 |
|---|---|---|---|
| 抽取（instant→shortTerm） | `instant` 满 `INSTANT`(60) | `extractionRetryAt` / `extractionFailures` | 整窗折叠成"日期+时段"事件摘要 |
| 分析（shortTerm→longTerm） | `shortTerm` 低水位之上攒够 20 条 | `analysisRetryAt` / `analysisFailures` | **逐条容错**（单条校验不过只丢该条）＋**ack 宽容**（只承认批次内 id，缺的下轮再送）＋**截断抢救** `AnalysisSalvage`＋显式 `max_output_tokens` |
| 场景概要 | 场景切换或跨度 ≥ `Scene.SPAN`(12) 条 | `sceneRetryAt` / `sceneFailures` | `summarizeScene` |
| 世界书整理 | 跟随**抽取**：处理本次折叠刚产出的那批短期（排在分析之前） | `lorebookRetryAt` / `lorebookFailures` | `consolidateLorebook` + 淘汰；失败的那批留在内存待重试 |

`scheduleMemoryRetry()` 统一指数退避（5 分钟 → 30 分钟封顶）；成功后清零。

**短期摘要按位置消费（没有逐条标记）**：短期窗口是高低水位对（40 → 20），**位置就是状态**——比最新 20 条更老的那一段（每批最多 `ANALYSIS_BATCH`(40) 条）就是本批要消化的，分析成功即**原样丢掉这一批**，最新 20 条留作下一批（以及注入块）的上下文。因此不需要 `analyzedAt`/`analyzedRevision` 这类"两个消费者都确认才允许裁剪"的记账（一旦和位置错位就会死锁：标记说"都处理过" → 每轮都判"没有待办" → 窗口只涨不降）。`revision` 只剩一个用途：条目被 `update_memory` 改写时自增，用来把**请求飞行期间刚改过**的条目扣在窗口里、下一轮再读。

**证据与时间精度**：`MemoryEvidence.evidenceMatchesSummary()` 要求证据与实际回复有 ≥2 个双字片段重叠（≥35%），不"满 8 字即采信"；`applyStaticFields` 与 `update_character_field` 都额外要求 `hasStaticEditIntent()`（用户原话提到该字段且是修改意图）。`TimeUtils.resolveTimeRef` 对带时分秒的 explicit ISO 原样保留（含时区偏移），只给"仅日期有理"的结果补时段起点。

### 基础设定字段（10 字段）

`Prompts.STATIC_PROFILE_FIELDS`（`:domain:agent`）：`gender` / `age` / `race` / `appearance` / `personality` / `fears` / `background` / `speakingStyle` / `language` / `userAddress`（群成员另有 `roleInGroup`）。

**只留"人本身"的设定**：原本还有「价值观」与「关键过往」两项，它们分别与「性格特征」「背景故事」重叠（后者只是前者加时间线），**已删除**——设定越少、越不易互相矛盾，注入也更省。旧页面的这两个字段由 `:core:data` 的 `RetiredStaticFields` 在**读取时并入幸存字段**（性格 ← 价值观、背景 ← 关键过往）并摘掉退休键；旧备份走 `LegacyMapper` 的同一条合并规则，所以升级不丢人设。

**写作规则**（`core:model` 的 `StaticFieldRules`，两条写入路径共用——工具/`submit_response.staticFields` 与界面/建卡）：

- **只写明确的简短陈述句**：禁止「不是 A 而是 B」这类对比句（`contrastReason` 命中即**拒收**，工具会回一条明确错误让模型当场改写；它不静默截断也不偷改语义）。
- **按字段收敛**（`AppLimits.StaticProfile`）：短字段（性别/年龄/种族/语言/称呼）**40 字**、叙述字段（外貌/性格/恐惧弱点/说话风格）**120 字**、背景故事**250 字**；超长按句末标点回退截断（`clamp`）。动态状态等非基础设定字段不受这些上限约束。

### 动态状态（6 字段）

`Prompts.DYNAMIC_STATE_FIELDS`（`:domain:agent`）：`currentSituation` / `currentLocation` / `currentMood` / `currentOccupation` / `currentGoal` / `currentTone`。

`currentTone`（当前语气）由模型每轮更新，写成面向朗读的 TTS 播报指令（按需描述语气/情绪/语速/节奏/停顿/重音，可短可长、软上限约 60 字），朗读时直接作为 `TtsTurn.style` 传给当前朗读引擎（端侧 CosyVoice 的 instruct 指令，或远程服务器的 `instructions` 字段），省去"朗读前再调一次 API 生成语气指令"的开销。旧的 `currentRelationship`（当前关系）与 `currentImportantOthers`（当前重要他人）已不再使用，其旧值不迁移。

除 `currentTone` 外，每个字段的 value 必须带 `sourceMessageIds` + `evidence` 溯源；群组整体只维护 `currentSituation` + `currentLocation`（`GROUP_SHARED_DYNAMIC_FIELDS`），成员各自的完整状态（含 `currentTone`）写入 `memberDynamicState`。`currentTone` 是朗读投递属性（自指、非事实状态），**每轮必填且免证据门**（`applyDynamicStateFields` 对 `currentTone` 跳过来源解析），是唯一不受"仅更新有依据字段"约束的动态字段。

### 世界书（lorebook）

`char.lorebook = [{ id, name, keywords[], content, enabled, order, alwaysActive, origin, mentions, lastMentionedAt, misses }]`

**定位：世界层设定的唯一去处**（时代与世界观、地点、组织、专有名词、历史、规则）。静态字段只承载"人/群组本人"的设定（群组 `description` = 群组前提、单角色 `basicInfo.background` = 个人背景），世界观一律不写进静态字段。

- **两种模式**：`alwaysActive: true`（常驻，每轮都注入，用于世界前提/规则）与关键词命中（被提到才注入，省 token）。**没关键词的条目不被拒收**：改用**条目名当触发词**。常驻数量受 `MAX_ALWAYS_ACTIVE`(6) 约束（超限只是降级、绝不整条丢弃）。
- **来源与保护**：`origin: 'user' | 'ai'`（界面显示为「人工维护」/「AI 维护」，点一下即可切换）。`user` 的条目**不会被模型覆盖，也不参与自动淘汰**；`ai` 的条目模型可维护（更新=整条替换）。**归属只由这个开关决定**：编辑名字/关键词/内容、勾「启用」「常驻」都不改变归属；界面新建条目默认 `ai`。
- **模型维护（三入口）**：
  1. 建卡/群组升级时由模型产出初始条目（`CharacterParity` 的 `lorebook` 字段，2-4 条，标记 `ai`）。
  2. 主循环工具 `upsert_lorebook_entry`：先按 `entryId`/`name` 精确匹配——**精确命中时用新 `content` 替换该条目原有内容**，未命中再用近似匹配（名称 bigram / 共享关键词 / 内容相似度）找**同一事物的近似条目**并**整条替换**（允许变短、允许把 `alwaysActive` 改回 false），确实没有近似条目才新建；要求 `sourceMessageIds` + `evidence` 可回溯，且**不得覆盖 `origin:user` 条目**。
  3. 记忆子任务 `consolidateLorebook()`：跟随**短期抽取**（排在长期分析之前，读的正是本次折叠刚产出的那批短期；清理流程则读它即将消费的那批），把现有条目的**完整内容**（最近更新的 12 条 / 累计 ≤6000 字；其余只给名字+关键词并注明"本轮不要更新此条"）交给模型，要求"同一事物只一条：沿用原名，把完整原文里仍有效的部分 + 新事实**重新总结成一条去整条替换**（允许变短、一条只讲一件事、目标 300 字 / 硬上限 400 字）"（`AUTO_ENTRIES_PER_PASS`(3) 条上限，按 `sourceShortTermIds` 校验）；超 400 字的条目**退回模型重写一次**，仍超才放弃；写完后本地 `dedupeLorebook()` 兜底并执行淘汰。**写回只动世界书**（逐条并进最新列表，另有 `dedupeLorebook`），不再因为"运行期间角色版本变了"整批作废；**失败的那批留在内存**（`LorebookRetryStore`），下一轮维护时与新的批次一起重试，连续多次失败后弹窗询问「重试 / 忽略」（忽略即按用户意愿丢弃并写进请求日志）。每趟都会以 `status=note` 记录"写入 N 条 / 跳过 M 条（原因）"。另有一次性重写 `rebuildOversizedLorebook`：内容 >400 字的旧 AI 条目交给模型按主题拆成 1~3 条并原地替换。
- **隐藏自动淘汰**：`LorebookStore.evictStaleLorebookEntries()` 每轮整理把未被注入的 `ai` 条目 `misses + 1`，达 3 即退役；条目被注入时 `markLorebookMentions()` 把 `misses` 归零。`misses` 不展示给模型、也不由模型管理。**退役不默默发生**：每趟退役的条目名称以 `status=note` 写进请求日志（与"真失败"的 `failed` 分开）。
- 归一化：写入受 `AppLimits.Lorebook` 约束（`ENTRIES` 200 / `KEYWORDS_PER_ENTRY` 8 / `NAME_CHARS` 60 / `CONTENT_CHARS` 400——**超限写入直接拒收、退回模型重写，绝不截断**）；角色卡弹窗第三个标签页编辑（展示来源徽标/常驻开关/最近提及）。
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
- 路由：`submit_response.longTerm` 可带 `memberName` → 写入该成员记忆；记忆工具同样支持 `memberName`。
- 注入：`buildVolatileContext` 为每个成员单独注入私人记忆（标记"仅该成员知道"），群组规则要求不得张冠李戴。

### 话题系统（主动提话题 + 冷场 + 话题切换）

- `Prompts.buildTopicSuggestions()`：从 `basicInfo` + 记忆抽取最多 5 条候选话题。
- `Prompts.isColdFieldRequest()`：识别"不知道聊什么/你说吧"等冷场信号，注入强指令要求角色主动开话题。
- `Prompts.detectTopicSwitch()`：对 `currentGoal + currentSituation` 与用户消息做双字 bigram 重叠检测，不同则注入"先收束旧话题"的软指令。

### 场景概要

- `BackgroundTasks.checkScene()`：以 `dynamicState.currentLocation` 作为场景标识。
- `summarizeScene()`：把"这一段已告一段落的情节"压缩成 1-2 段（≤300 字）**只含剧情要点**的记忆（谁做了什么/学到或决定了什么/地点物品伤势关系变化/新发现/未解决的目标承诺威胁期限）；写明主体、绝对日期、不引对白、不编造。
- 触发：**场景切换**（location 变化）或**场景超长**（`Scene.SPAN`(12) 条消息），且消息数 ≥ `Scene.MIN_MESSAGES`(6)。
- 游标存 `character.sceneState`（`key`/`startCount`/`startSequence`/`messageCount`，按消息 `sequence` 推进）。
- 注入：最近 2 条渲染为 `【场景概要（较早情节的压缩记录…）】`。

## 六、上下文与预算

### 记忆预算

**volatile 没有区块预算**：该段属于主对话请求体，所有块整块注入，取舍只剩"条数上限 × 每条兜底上限"。

| 项 | 值 | 说明 |
|---|---|---|
| 短期窗口注入 | 整窗（20~40 条） | 注入到**历史之前**的独立 user 块，不进 volatile |
| `TOOL_MEMORY_INJECT_LIMIT` | 5 | 每轮注入的长期记忆条数 |
| 世界书注入 | `Lorebook.INJECT_ENTRIES`(6) 条 | 预算 `INJECT_CHARS`(1400) 装不下的条目**跳过、继续装后面的** |
| 场景概要 | `Scene.SUMMARIES`(2) 条 | 整条注入（写入时已受 300/400 字约束） |
| 每条兜底上限 | 短期正文/长期 value/群私人 1200、长期 key 120 | 仅为兜底；提示词软限制让它尽量不触发 |

### 请求装配与缓存前缀

一轮请求的形状（`ChatOrchestrator.run` + `RequestBuilder`）：

```
instructions（system，纯静态角色设定）
input:
  [0]     短期窗口块（user）    ← 【近期摘要记忆…】整窗
  [1..n]  历史 = instant 窗口（20~60 条原文）
  [n+1]   本轮用户消息
  [n+2]   volatile 上下文（user，最后一条）
tools: AUTO 阶段全部工具 / SUBMIT 阶段只有 submit_response
```

- **短期块放在历史之前**是有意的：它只在折叠那一轮变化，而折叠同时把历史 rebase 掉——即"它变的时候前缀本来就要断"。中间那段它逐字不变，于是和"追加式历史"一起待在缓存前缀里。
- 前缀缓存的失配只应来自三处：`instructions-changed`（角色静态资料被改）、`tools-changed`、以及两个窗口各自的 `history-rebased`（折叠，约每 20 轮一次）。`AppViewModel.requestTrace` 把短期块作为首个伪条目纳入前缀快照，否则它的变化会被误报成 `history-extended`。
- 历史**不逐轮滑动**：`AppViewModel.growInstantWindow` 只追加窗口没见过的消息，`ChatOrchestrator` 只取窗口末尾 `INSTANT`(60) 条作尺寸兜底。
- 图片保留窗口 `AppLimits.Media.IMAGE_RETENTION_MESSAGES`(60) 必须 ≥ `INSTANT`(60)：否则还在窗口内的消息会被剪掉图片，等于在稳定期中间改写前缀，还让模型"忘掉"刚看过的图。

### 提示词字符上限

`AppLimits.Prompt`：`ROLE_CHARS = 8000`（单角色设定）/ `MEMBER_CHARS = 900`（群组成员）。
历史消息按条数与单条字符截断，且**同一消息无论是否为本轮都使用同一上限（用户 2000 / assistant 1200）**——保证同一段文字在"当前轮"与"下一轮变历史"时编码逐字一致，避免前缀缓存整段失效。

### 前缀缓存策略

- system = 交互规则 + 【角色设定（固定，不随对话变化）】+【硬性约束与语气锁定】+【输出格式】，全部为静态内容，跨轮不变。
- **首尾放最高优先级**：规则块开头是优先级阶梯，静态块**末尾**再放硬性约束（0.5 禁令 + 2.5 语气锁定）与输出格式 —— 兼顾 primacy 与 recency，同时保持全部静态可缓存。
- 历史消息逐字按序（最旧→最新）拼接、不重排、不注入消息 ID；**同一条消息在任何一轮都用同一编码**（含 `trimTo` 上限），因此上一轮的"当前用户消息"在下一轮变成历史时字节不变。
- **所有随轮变化的内容（记忆、世界书、场景概要、节奏骨架、违规纠正提醒、语气回顾、声音契约/群聊声音锁定、本轮指令）汇总进 `buildVolatileContext`，作为独立的末尾 user 项追加**，不写进任何历史消息体内。
- 生成示例固定使用同一份精简 JSON 示例，system 从首轮起稳定。
- 语气相关注入在 volatile 尾部按固定顺序排列：节奏骨架 → 违规纠正提醒 → 每 N 轮语气回顾 → 声音契约（单角色）/群聊声音锁定（群聊，每位有说话风格的成员各一块、块数随成员数、不封顶）（越靠后越接近生成点，遵从度越高）。
- 禁止：把随轮内容插进历史中间、每轮改写 system 措辞、volatile 各段随机换序（都会打掉前缀命中）。

### 逐请求缓存与耗时统计

`AppViewModel.recordMetrics`（chat）/ `recordAuxiliaryUsage`（辅助调用）写 `RequestMetric`：`taskType`（`chat` / `auxiliary` 等）、`platform`、`phase`、`status`（`completed` / `failed` / `note`）、`inputTokens` / `outputTokens` / `hitTokens` / `missTokens` / `hitRate`、`durationMs`、`instructionsHash` / `toolsHash` / `historyHash`、`commonHistoryMessages`、`prefixChange`、`contextChars` / `inputChars`。记录保留最近 60 条（`AppLimits.Api.REQUEST_METRICS`）。**一轮对话只写一条 chat 记录，数值是本轮所有主对话请求（工具轮 + 强制收尾轮）的合计**——只有第一轮会带破缓存的前缀，只记最后一轮（那一轮通常已被本轮自己写热）会让命中率永远看着很高；后台/辅助调用（抽取、分析、场景、世界书、快捷回复）一律不计入右上角。没有拿到用量的请求记"无数据"（`hitRate=null`，不按 0% 计入均值），右上角 `cacheStats` **每轮都刷新**（无数据时显示「缓存未返回命中数据」），**失败的 chat 轮也会留一条 `status='failed'`**（`phase` 存原因），失败的后台调用同样留痕。缓存条显示本轮（含工具轮）命中率与最近 10 次对话轮均值；设置页「调试信息」展示逐请求明细（含 `prefixChange`，用于判断"这次破缓存是我改了设定还是历史自然增长"）。

## 七、人格与 Prompt

### 规则清单（system 提示词，按出现顺序）

| 规则 | 内容要点 |
|---|---|
| — | 优先级阶梯（规则块首行）：`0／0.5 与【输出格式】= 硬性契约 > 2.5／2.6 > 角色设定 > 风格偏好` |
| `0` | （最高优先级）本轮必须以结构化方式收尾，二选一：调用 `submit_response` 工具（推荐）或直接输出单个 JSON 对象；不得在结构化内容之外写说明/旁白 |
| `1` | 保持角色身份连续；先用已提供的记忆；"系统提供的本轮上下文"不是用户的话 |
| `2` | reply 正文写法（像日常说话）：**不设下限**、只设 **≤250 字上限**防大段空话；分段/行内样式限制；**动作/表情/心理/旁白一律写全角括号、单拍短、不堆叠、总量 ≤ ¼、不复述对白情绪**；**自己说的话直接写、不加引号包裹；只有引用他人话语/歌词/诗句/书名或专有名词原文时才用符号（引号用「」、书名用《》、逐字照抄），并避免英文直引号（JSON 需转义）**；去重（禁重复近期整句/句式/意象/固定动作/收尾） |
| `2.6` | **表演质量（真人感）**：show-don't-tell、句长节奏起伏、对白优先、回避陈词滥调与复用比喻、删掉不推进内容的句子、人物性格稳定 |
| `3` | 人格/背景锁定 + `dynamicState` 更新与溯源规则（时间写法见 `3.8`） |
| `3.5` | 谨慎修改基础设定：用户直接要求 → `update_character_field`；无要求时仅"决定性不可逆转折"才改；value 写成明确的简短陈述句（禁「不是…而是…」对比句） |
| `3.6` | 状态/设定字段的 value 写法（只写内容本身，不加主语，解释进 evidence）；**基础设定字段有硬长度上限并只写陈述句**——见「基础设定字段」一节 |
| `3.7` | 记忆字段必须写明主体（禁止"我/你/TA"这类代词） |
| `3.8` | 时间一律写绝对日期，相对说法填 `timeRef` |
| `3.9` | 用户提"说话方式类"要求时，本轮 reply 直接演出、禁止元话术 |
| `4`–`6` | 只记录未来有价值的信息；`subject` 取值约束；promises 的承诺方/受约方与状态流转 |
| `7` | `quickReplies` 恰好两条且必须是**用户视角**（禁止角色口吻、**禁止角色对用户的提问**、禁止复述角色台词） |
| `8` | JSON 字段结构精简示例（字段完整定义见 `submit_response` 工具 schema） |
| `9` | `longTerm.category` 取值与 `importance` 范围 |
| `9.5` | 相对时间（timeRef）说明（取值与示例已下沉到工具 schema） |
| `A` | 工具使用总则（详细适用场景以工具描述为准；工具结果属内部上下文，不得向用户透露检索过程） |
| `10`–`12` | 群组：发言格式、记忆归属、设定三层（群组 quickReplies 同样要求用户视角） |
| — | 【角色设定（固定，不随对话变化）】= `buildSystemPrompt` 的静态角色块 |
| `0.5` | **只扮演角色本人：禁止替用户说话或行动**——置于硬性约束块（静态块末尾） |
| `2.5` | 语气锁定：腔调只能来自设定的"说话风格"与"对用户的称呼"，并硬性要求口癖/句尾助词/称呼等签名标记真的出现（≥2 处），禁止中和成通用书面语或默认 AI 助手腔——与 0.5 同在硬性约束块 |
| — | 【输出格式】工具调用与单个 JSON 对象二选一收尾（静态块最后一行） |

### 节奏骨架

`Prompts.buildNarrativePatternDirective()`：从 10 条结构模板（动作/描写 → 对白 → 描写的排列组合）里每轮随机取一条，注入 volatile 尾部的 `【本轮节奏骨架（每轮随机给出，仅作节奏参考）】`，用于打破"每轮结构雷同"。

### volatile 段落（按顺序）

可选话题库 → 角色当前状态（+群成员状态）→ **待跟进承诺** → **世界书资料** → 成员私人记忆 → 用户最新纠正 → 互动节奏（重连/久别）→ **场景概要** → 本轮主动召回的相关长期记忆 + 记忆取用说明 + 记忆主体约定 → 时间基准 → 消息时间元数据 → 结尾多样性 → **本轮节奏骨架** → 冷场/话题切换指令 →（主动开场时）角色主动开场 →（热榜开场时）热榜指令 → **快速回应视角提醒** → **违规纠正提醒** → **每 N 轮语气回顾** → **声音契约（单角色）/群聊声音锁定（群聊）**。

> 顺序原则是"越靠后越近"：更早的背景（话题库/状态/承诺/世界书）在前，较早情节（场景）→ 较早记忆（长期召回）→ 现在（时间基准/时间线）→ 本轮指令（尾块）在后。**短期窗口不在这里**：它整窗注入到历史之前（见"请求装配与缓存前缀"）。
>
> 记忆取用说明：提醒召回条目"按关键词与时间粗略召回，可能只有部分相关或已过时"，不得硬提旧事、不得当作用户刚说过的话。

### 语气/风格遵从（attention）

把最容易违反的约束尽量贴近生成点，并用客户端检测闭环（纯函数，可单测；在 `StyleGuard`）：

- **优先级阶梯**：规则块开头声明"0／0.5 与【输出格式】= 硬性契约 > 2.5 语气锁定与 2.6 表演质量 > 角色设定 > 风格偏好"。
- **静态块末尾重申**：`【硬性约束与语气锁定（最高优先级，冲突时以这里为准）】` 内含 0.5 禁令与 2.5 语气锁定，紧跟【输出格式】（recency，且全部静态可缓存）。
- **声音契约** `StyleGuard.buildStyleAnchor()`：每轮在 volatile 最末尾给出「说话风格摘要 + 3 条逐字示例台词 + 对用户的称呼 + 本轮硬约束」，硬约束要求回复**至少自然带出设定的口头禅/句尾助词/称呼共 2 处**并保持标点习惯，**禁止通用书面语与默认 AI 助手腔**；示例从完整 `speakingStyle` 抽取后再截断摘要，避免长风格丢示例。`speakingStyle` 为空时不出契约（依赖补全字段功能填充）。
- **群聊声音锁定** `StyleGuard.buildGroupVoiceContract()`：群聊时为**每位有 `speakingStyle` 的成员**各出一块（成员名 + 说话风格摘要 + 3 条示例 + 称呼 + 该成员专属要求），块数随成员数、不封顶；总纲要求成员之间语气显著不同。无说话风格的成员跳过。
- **违规检测** `StyleGuard.detectStyleViolations(reply, char, prevReplies)`：纯函数返回标签
  `metaTalk`（元话术）／`speaksForUser`（替用户说话）／`cliche`（陈词滥调表）／`reusedImagery`（复用近期意象）／`toneDrift`（声明的口癖缺失或句长过于整齐）／`recitedLore`（复述世界书条目）。
- **内部提醒**（不展示给用户）：违规标签随消息持久化（`message.styleViolations`），下一轮由 `StyleGuard.buildStyleCorrectionReminder()` 在 volatile 尾部点名纠正。
- **每 N 轮语气回顾** `StyleGuard.buildStyleReview()`：`reviewEveryTurns`（3）轮一次，汇总近期违规并重申语气契约。
- **critique 调用** `BackgroundTasks`（只改文风、不改剧情）：命中违规且 `AppConfig.styleCritique` 开启时追加一次修订请求；解析失败或长度比例异常一律**回退原文**；**引用保护**（确定性兜底，`StyleGuard.keepsProtectedContent`）：把 `「」『』《》〈〉` 与英文/弯引号包起来的引用段落、以及世界书条目名/角色名/成员名/对用户的称呼逐一比对，**只要有一处被改写、增删或换序就整体回退原文**（不做局部修补——那会留下半截被改写的引用）；修订后重新检测并以剩余违规为准。

### 快速回应视角（quickReplies）

「快速回应」必须是**用户**下一句会发的话，而模型容易写成角色口吻或角色的提问。这里同样用"校验 + 后台修正"闭环：

- **提示词层**：规则 `7` 明确三类禁止写法（角色口吻 / **角色视角的提问** / 复述角色台词）并给出对比例句；`submit_response` 的 `quickReplies` 描述与群组规则 `10` 同步收紧。
- **校验层** `StyleGuard.detectQuickReplyIssues(replies, char, replyText)`：纯函数返回标签 `missing` / `placeholder`（照抄占位文字）/ `action`（带括号动作）/ `mirrored`（与角色本轮台词逐字或镜像重复）/ `characterName`（出现角色名）/ `tooLong`。
- **修正层** `BackgroundTasks.repairQuickReplies()`：**换位生成**——用一个身份为"用户本人"的后台小调用重写（system 明写"你就是这位用户本人…不要扮演<角色名>"），成功后只更新快速回应按钮，**不触碰已显示的正文**；失败回退兜底短句。开关 `AppConfig.quickReplyRepair`（默认开）。
- **不阻塞**：quickReplies 不参与"是否重发一轮"的判定；缺失/非法一律先接受正文，由后台补上。
- **内部提醒**：问题随消息持久化（`message.quickReplyIssues`），下一轮由 `StyleGuard.buildQuickReplyPerspectiveReminder()` 在 volatile 尾部点名。

### 人格组装

- `buildSystemPrompt()`（`:domain:agent` 的 `prompts/Prompts.kt`）：从 `char.basicInfo` 拼人格（主字段 + 次字段，共 12 项 `STATIC_PROFILE_FIELDS`）；群组则拼群组信息 + 成员清单。
- 动态状态渲染为"状态"文本（未设置的显示 `(未设置)`；volatile 里的角色/成员状态块同样用 `(未设置)` 并脱敏"用户"词）。
- `RequestBuilder.build()` / `mapHistory()`：装配 system + 历史 + 本轮 volatile；`mapHistory` 对空 assistant 消息整条丢弃、非空包成 `{"reply": …}` 作格式示范、贴图转图片。

### 子任务提示词（非主对话）

主对话之外的每次辅助调用都有独立 prompt，各司其职（`BackgroundTasks` / `NativeCore` / `CharacterParity`）：

| 提示词 | 位置 | 关键约束 |
|---|---|---|
| 一键建卡 `buildQuickGeneratePrompt` | `feature:characters` 的 `CharacterParity` | 输出完整字段 JSON；**禁止套路名字与模板人设**；`personality` 写行为倾向；`background` 只写角色本人经历（世界观写 `lorebook`）；`speakingStyle` 必须是「调性描述；示例：<台词1> / <台词2> / <台词3>」（`CHARACTER_QUALITY_RULE` + `SPEAKING_STYLE_SAMPLES_RULE`）；群组分支要求成员说话方式显著不同，并输出 2-4 条初始 `lorebook` 条目 |
| 群组升级 `upgradeToGroup` | `:app` 的 `AppViewModel` | 与建卡同一套质量约束；`additionalMembers` 不得重复原角色与彼此姓名；可补 0-3 条 `lorebook`（只补近期已出现的世界层设定）；原角色世界书并入群组 |
| 世界书整理 `consolidateLorebook` | `BackgroundTasks` | **跟随短期抽取**（排在长期分析之前）；只从**已发生的短期摘要**里沉淀世界层设定（`sourceShortTermIds` 必须可回溯）、禁止推测扩写；给出已有条目的完整内容；"同一事物只一条：整条替换、允许变短、≤400 字"；≤`AUTO_ENTRIES_PER_PASS`(3) 条；写后本地去重并执行 `misses` 淘汰；**只写世界书**，失败留在内存重试/交给用户决定 |
| 世界书一次性迁移 `migrateWorldLore` | `BackgroundTasks` / `NativeCore.runWorldLoreMigration` | 把旧数据 `description`/`background` 里的世界观拆成条目（只整理已有信息）；人工维护条目不受影响；可选同时精简原文 |
| 成员补全 `fillMemberFields` | `NativeCore` / `CharacterParity.applyMemberFillPayload` | **已有字段是绝对权威，不得改写/润色/替换**；只填空字段；说话方式须与群内其他成员显著不同；无把握则省略 |
| 空字段补全 `autoFillStaticFields` | `BackgroundTasks` | 只补缺失字段，绝不覆盖已有设定；缺 `speakingStyle` 时套用示例台词约束；群组成员再叠加"彼此不同" |
| 即时→短期摘要 `analyzeShortTermBatch`（抽取） | `BackgroundTasks` | **整窗折叠**：`instant` 满 60 触发，输入是**整个窗口**；按"日期+时段"合并；**只保留影响后续剧情的内容**；**每条 ≤120 字**；禁止文学化与对白原文。**不要求模型点名消息 id**：原话证据由客户端按同一"日期+时段"从窗口里采；重复靠提示词注入**已有短期事件**来压 |
| 短期→长期 `analyzeShortTermBatch`（分析） | `BackgroundTasks` | **整窗折叠**：`shortTerm` 满 40 触发，输入是**整个窗口**（低水位留下的 20 条只作上下文）；**价值判据**（可跨轮复用/影响后续/用户明确表达）才记，**证据不足宁可不记**；`arcOf` 命名一旦确定保持稳定；**取证链锚在短期条目上**；**每条 value ≤200 字、arc 叙事 ≤300 字** |
| 场景概要 `summarizeScene` | `BackgroundTasks` | 1-2 段 ≤300 字，只含剧情要点，不引对白、不编造、写绝对日期 |
| 文风校对（critique） | `BackgroundTasks` | **只改文风、不改剧情**；不得复述规则；只返回 `{"reply":"…"}`；空结果或长度比例异常一律回退原文；**引用段落（「」『』《》〈〉/引号）与世界书条目名/角色名/成员名/称呼被改动也整体回退** |
| 快速回应换位生成 `repairQuickReplies` | `BackgroundTasks` | 身份是**用户本人**；只输出 `{"quickReplies":[...]}`；结果再过一遍 `detectQuickReplyIssues`，不通过就放弃（回退兜底） |
| 散文轮记忆整理 `extractProseTurnMemory` | `BackgroundTasks` | 复用"对话记录整理助手"把散文轮补成结构化记忆；**后台执行**、不改写已显示正文、还原已注入召回 |
| 其他 | emoji 头像（`NativeCore.requestEmojiAvatar`）、散文转 JSON（`convertProseToJson`）、字段重排（`remapFields`）、贴图标签（`NativeCore.tagSticker`） | 低价值路径，未做经验对齐 |

## 八、健壮性

- **API 请求级重试**：`ApiRetry`（`:domain:agent`）对瞬时失败（网络错误 / 超时 / HTTP 429 / 5xx）自动重试最多 3 次（1s/2s/4s 指数退避）；**用户主动取消不重试**（`CancellationException` 直接抛出）。后台辅助调用在 `BackgroundTasks.completeDetailed` 用同一策略。
- **工具失败可恢复**：工具失败时回传可修正的提示（如 `delete_memory` 找不到 ID 时提示先 `list_memories`）。本轮工具 `ok:false` 时不采用同一轮的提交，继续让模型修正，避免"回复说改了、实际没改"。
- **每轮一个工具 + 上限**：每轮只执行并回传第一个信息类工具；`MAX_TOOL_CALLS` 约束整轮总数。
- **记忆任务重试**：`extraction` / `analysis` / `scene` / `lorebook` 四组计数器（`*Failures` / `*RetryAt`），`scheduleMemoryRetry` 指数退避（5→30 分钟封顶）。
- **后台任务过期保护**：`captureGuard` / `isGuardCurrent`（`revision` + 末条消息 ID）保护**会覆盖用户可见状态**的任务（场景概要、静态补全、动态状态）；**折叠/分析这类只做增量的任务改为合并写回**——重读最新角色后把新条目追加到最新列表、只移除刚刚折过的那批，所以用户中途又发消息时折叠结果照样落地（不再整批判 `Stale` 丢弃）。
- **摘要不被提前丢弃**：消费只发生在读它的那一趟之后——分析失败的批次原样留在窗口里、由退避门控重试；**世界书整理排在长期分析之前**、读的正是短期抽取刚产出的那批（再加内存里上次失败遗留的），逐条校验来源与字段，坏条目跳过（其余照常入库）并记录原因；这一趟失败时那批**留在内存待重试**（连续失败后弹窗问「重试 / 忽略」），不会因为分析随后消费掉短期条目而永久丢失。**窗口去重只在消费这一刻做**（`BackgroundTasks.settleShortTerm` → `MemoryPolicy.dedupeShortTerm`）：同一逻辑日+时段且相似度 ≥0.6，或全局 ≥`NEAR_DUP_SIMILARITY`(0.72) 合并；稳定期内不碰窗口，否则已发出的请求前缀会变得不可预测。
- **写入只写自己那部分**：所有写路径（主对话收尾、后台维护、编辑器）都用 `mergeCharacterChanges`（`core:model`）把"本次真正改动的字段"合并到最新行上——普通字段按值差异判定、世界书按**条目 id 三方合并**（并发新增的条目不会被抹掉，删除才真正删除）。因此长期的整份写回不再互相回滚：世界书整理只动世界书、聊天收尾不会再把你期间的世界书/记忆改动覆盖掉。
- **检索相关性下限**：`rankMemories` 只保留至少命中一个非停用词的候选，并用特异性下限 `MIN_MATCH_SCORE`(1.5) 过滤泛词命中；`pendingRecall` 优先占位；短指代查询经 `buildMemoryQuery` 补上下文；**用户提到具体日期时由模型用 `search_memory` 的 `date`/`timeRef` 按日期召回那天的原始对话与记忆**。
- **断言前先核对**：系统提示词要求模型在说"第一次/从来没/总是/每次"这类**可被记忆证伪**的话之前先 `search_memory` 确认，查不到依据就不下断言（改说不确定或反问）。
- **短期记忆上限靠"消费即丢"不靠"截断"**：分析一趟读走低水位之上的那一段（每批最多 `ANALYSIS_BATCH`(40) 条），成功后原样丢掉；没读到的最新 20 条永不丢，稳态就是 20~40。图片只保留最近 `IMAGE_RETENTION_MESSAGES`(60) 条消息（更早的删附件与文件、正文保留）。**一次弹窗、之后自动跑完**：`AppViewModel.checkMemoryCleanupPrompt` 只在短期超出高水位或有超窗图片时询问一次；确认后 `NativeCore.pruneOldMessageImages` 把超窗图片**原地压成缩略图**，`drainShortTermBacklog` 把积压消费成长期记忆（每次 1 批、空闲每分钟 1 批），直到降到低水位 20 才清除标记。**去重/修复这类 O(n²) 重活跑在 `Dispatchers.Default`/`IO` 上**（放主线程会 ANR，且修复标记写不进去 → 每次启动重跑）。
- **时间精度**：`TimeUtils.resolveTimeRef` 保留带时区/时分的 explicit ISO，仅"日期或时段"表达才落整点/时段起点。记忆衰减/排序的时间基准逐级回退（`lastRecalled` → `updatedAt` → `createdAt`），不可解析时不短路更新的时间戳、也不被当成 1970 年。
- **联网工具超时**：`HttpWebContentProvider` 给 B站 API、短链解析、Microlink、网页直连统一设超时，避免任意外部请求卡死工具循环。
- **请求失败也留痕**：chat 轮失败、辅助调用失败都会写 `RequestMetric`（`status='failed'`，原因在 `phase`）；`authorizationHeader` 在 API Key 缺失时提前抛清晰错误，不发出 `Bearer undefined` 请求。
- **文风校对可回退**：critique 在任何异常（解析失败、空结果、长度比例异常）下都返回原文；**引用/专有名词被改动也整体回退**（`StyleGuard.keepsProtectedContent`，逐字比对引用段落与条目名/角色名/称呼）。
- **快速回应后台补齐可回退**：换位生成失败（或结果仍不过校验）时退回兜底短句；回来太晚（已被下一轮覆盖）直接丢弃，不会覆盖新一轮。
- **后台任务串行**：后台队列把"散文轮记忆整理""快速回应补写"等串成一条链，避免交错写同一份角色状态；都不阻塞正文显示。
- **散文即终稿不再重写**：阶段一拿到散文就直接采用，`convertProseToJson` 只在后台补记忆，不会把用户已看到的正文换成"整理"后的版本。
- **共享 HTML 工具**：HTML 实体解码/去标签/可读文本抽取是同一份实现，避免多处漂移。
- **导入/导出归一化**：`LegacyImportService` / `BackupService` 逐字段校验（世界书、场景概要、场景状态、计数器、来源数组），坏角色跳过并计入 `ImportSummary.skipped`。
- **版本号单一来源**：`android-lite/app/build.gradle.kts` 的 `versionName` / `versionCode`，应用内版本徽标读 `BuildConfig`；改动用 `tools/native-version.ps1`。

## 九、关键常量速查

常量集中在 `:core:common` 的 `AppLimits`（`:core:model` 的 `BuiltinPlatforms` 定义平台预设）。

| 常量 | 值 | 备注 |
|---|---|---|
| `Agent.MAX_TOOL_ROUNDS` / `MAX_TOOL_CALLS` / `TOOL_MEMORY_INJECT_LIMIT` | 5 / 12 / 5 | 工具循环上限与每轮注入记忆条数 |
| `Api.AUXILIARY_TIMEOUT_MS` | 90 000 | 辅助/后台调用的传输超时（chat 为 180s） |
| `Api.AUXILIARY_MAX_OUTPUT_TOKENS` | 8 192 | 辅助调用显式输出上限 |
| `Api.REQUEST_METRICS` | 60 | 请求指标保留条数 |
| `Memory.INSTANT` / `INSTANT_TRIM_FLOOR` / `INSTANT_HARD_CAP` / `INSTANT_MIGRATION_THRESHOLD` | 60 / 20 / 120 / 120 | 历史窗口高低水位、紧急上限、补课阈值 |
| `Memory.SHORT_TERM` / `SHORT_TERM_TRIM_FLOOR` / `SHORT_TERM_HARD_CAP` / `SHORT_TERM_MERGE_WINDOW` / `SHORT_TERM_LOW_WATER` | 40 / 20 / 80 / 5 / 20 | 短期窗口形态 |
| `Memory.ANALYSIS_BATCH` / `SUMMARY_SOURCES` / `CLEANUP_BATCHES_PER_RUN` / `NEAR_DUP_SIMILARITY` / `PENDING_RECALL` | 40 / 60 / 1 / 0.72 / 6 | 折叠批大小、清理批数、近似去重阈值、待召回上限 |
| `Memory.LONG_TERM_PER_CATEGORY` | 40 | 保留常量，长期记忆当前无数量淘汰 |
| `Prompt.ROLE_CHARS` / `MEMBER_CHARS` | 8 000 / 900 | 单角色设定 / 群组成员注入上限 |
| `Lorebook.ENTRIES` / `KEYWORDS_PER_ENTRY` / `NAME_CHARS` / `CONTENT_CHARS` / `INJECT_ENTRIES` / `INJECT_CHARS` / `MAX_ALWAYS_ACTIVE` / `AUTO_ENTRIES_PER_PASS` | 200 / 8 / 60 / 400 / 6 / 1 400 / 6 / 3 | `CONTENT_CHARS` 是写入约束（超限拒收不截断） |
| `Scene.RETAIN` / `SUMMARIES` / `SPAN` / `MIN_MESSAGES` | 8 / 2 / 12 / 6 | 场景概要保留/注入条数与触发跨度 |
| `History.ARCHIVE_MESSAGES` / `PAGE` | 400 / 50 | 聊天存档上限与分页 |
| `Media.IMAGE_RETENTION_MESSAGES` / `THUMBNAIL_DIMENSION` / `THUMBNAIL_QUALITY` / `IMPORTED_IMAGE_*` | 60 / 384 / 60 / 512 / 70 | 图片保留与缩略/导入压缩 |
| `Sticker.MAX_DIMENSION` / `TARGET_BYTES` | 384 / 120 KB | 贴图压缩目标 |
| `max_output_tokens` | 8 192 | 主对话请求体 |
| `DYNAMIC_STATE_FIELDS` | 6 | `Prompts`（`currentTone` 取代旧关系两字段） |
| `STATIC_PROFILE_FIELDS` | 12 | `Prompts` |
| JSON 示例 | 固定一份精简示例 | system 前缀从首轮起稳定 |
| 请求追踪字段 | `RequestMetric[]` | taskType/platform/phase/status/tokens/hitRate/durationMs/prefixChange/commonHistoryMessages/hashes |
