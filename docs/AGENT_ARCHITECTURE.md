# Agent 架构文档（DeepTalking / 模块化前端）

> 本文档记录 `src/js/` 中 Agent 功能的定义与构造（只描述**当前最新状态**，历史变更看 git）。源码按职责拆分，网页与 APK 的 `hub.html` 均为构建产物。行号随源码调整会偏移，**以模块路径与函数名检索为准**；改动 Agent 循环、工具、提示词、记忆、字段或常量时，必须同步更新本文档。

## 源码与运行边界

后续开发以拆开的 `src/` 模块为唯一源码，单文件不再维护。模块顺序由 `src/manifest.json` 声明，`node tools/build-hub.mjs` 将 `src/shell.html`、样式和 JavaScript 构建为两份字节级一致的 `hub.html`；两份文件仅为生成产物。脚本保留原有经典脚本全局作用域和执行顺序，跨模块函数调用、DOM 事件、提示词、Agent 循环和存储格式均维持原有契约。Android 仍从固定 `file:///android_asset/hub.html` 加载，localStorage 主键仍为 `deeptalking_data_v1`。

| 模块 | 关键锚点 |
|---|---|
| `src/js/chat/conversation.js` | `sendMessage`、`performChatRequest`、主动开口与流式主链路 |
| `src/js/agent/tool-definitions.js` | `MAX_TOOL_ROUNDS`、`buildAgentTools` |
| `src/js/agent/tool-execution.js` | `executeToolCall` |
| `src/js/api/responses.js` | `buildSubmitResponseTool`、`extractSubmitResponse`、`buildResponsesRequestBody` |
| `src/js/prompts/context.js` | `buildRoleContext`、人格、承诺与话题上下文 |
| `src/js/prompts/volatile.js` | `buildVolatileContext`、本轮动态上下文的预算与取舍 |
| `src/js/prompts/style.js` | 文风检查、快速回应视角、`queueBackgroundTask` |
| `src/js/prompts/request.js` | `buildRequestPayload`、`callAPI`、`extractProseTurnMemory`、后台记忆补写 |
| `src/js/memory/updates.js` | `retrieveRelevantMemories`、`applyMemoryUpdate` |
| `src/js/memory/lorebook.js` | 世界书检索、写入、证据与保护规则 |
| `src/js/memory/tasks.js` | `checkMemoryTriggers`、整理与退避 |
| `src/js/core/config.js` | 字段与记忆/世界书/提示词预算常量 |
| `src/js/ui/status-settings.js` | `toolActivityHint`、`chatStageDecision`、设置 |

完整模块职责和开发/构建流程见 [FRONTEND_MODULES.md](FRONTEND_MODULES.md)。`APP_VERSION` 在源码中使用占位符，构建器从 Android 的 `versionName` 注入；`sync-version.mjs` 也调用同一构建器。`build-hub.mjs --check` 和 `verify-hub.mjs` 验证源码与网页、APK 资产三方一致，并检查独立模块语法边界。

Android 打包的 `preBuild` 自动执行 `buildHubAssets`，直接从 `src/` 生成运行资源，再由 Gradle 打包 APK。`hub.html` 是这一流程的中间产物，不是打包时需要手工维护的输入源码。

## 一、Agent 是什么

本项目的"agent"不是一个类或对象，而是一套 **LLM + 人格 + 记忆 + 工具 + 运行循环** 的组合。核心思想：

- 模型不再只输出一段文字，而是可以在一次回复中**主动调用工具 → 拿到执行结果 → 继续推理**，循环往复，直到给出最终答案。
- 这种"思考（reasoning）→ 行动（tool call）→ 观察（tool output）→ 收尾（structured reply）"的循环，就是 agent 的本质。

一句话概括：

> **人格 + 记忆 + 世界书** 提供身份与状态 → **`while` 循环**让模型反复"想→调工具→看结果" → **`submit_response`** 保证最终以结构化 JSON 收尾并落盘记忆。

## 二、总体数据流

```
用户输入
   │
   ▼
buildRequestPayload(src/js/prompts/request.js:1, requestContext)
   ├─ 人格注入 buildRoleContext(src/js/prompts/context.js:15)（staticOnly=true → 进可缓存前缀）
   ├─ 世界书匹配 buildLorebookContext(src/js/memory/lorebook.js:237)（常驻条目必进 + 关键词命中，模型维护）
   ├─ 记忆注入 buildVolatileContext(src/js/prompts/volatile.js:1) / retrieveRelevantMemories(src/js/memory/updates.js:1)
   └─ 消息历史（逐字稳定，volatile 追加为独立的末尾 user 项，src/js/prompts/request.js:53 起）
   │
   ▼
while(true) 循环 (sendMessage 内 src/js/chat/conversation.js:163)
   │
   ├─ performChatRequest(src/js/chat/conversation.js:450)  → 流式 SSE，解析 function_call / reasoning 事件
   ├─ 有工具调用？→ executeToolCall(src/js/agent/tool-execution.js:1) 执行一个 → 结果回传 toolState.items → continue
   ├─ 只调 ask_user？→ 直接把问题当回复（特判）
   ├─ 有合法 submit_response 工具调用？→ break
   ├─ 有正文（合法 JSON 或散文）？→ break（散文即终稿）
   └─ 否则（连正文都没有）→ 切换阶段二，强制 submit_response（兜底，必要时重试一次）
   │
   ▼
applyMemoryUpdate(src/js/memory/updates.js:642) 落盘 shortTerm / longTerm / dynamicState / promiseUpdates
   │
   ▼
checkMemoryTriggers(src/js/memory/tasks.js:199) 异步整理短期记忆 / 长期记忆 / 场景概要 / 世界书
   │
   ▼
渲染用户可见回复 + quickReplies（缺失/视角不对时后台换位生成补齐）
```

## 三、五大部件

### 1. API 载体（Responses API）

- `buildResponsesRequestBody()` — src/js/api/responses.js:56
- 请求体字段：`model`、`input`（首条 system 提升为 `instructions`）、`stream`、`temperature`、`max_output_tokens=8192`、`tools`、`tool_choice`、`reasoning.effort`。
- 端点：`getResponsesEndpoint()` — src/js/prompts/request.js:180（把 base URL 归一化为 `/responses`）。
- 阶段一（auto）的工具数组里**包含 `submit_response`**（`buildAgentTools()` src/js/agent/tool-definitions.js:9 末尾 push），模型可以在同一轮里"调完信息工具 → 调 `submit_response` 提交"，一次请求收尾。
- 阶段二（submit）为**兜底**：只有阶段一连正文都没拿到时才进入，此时强制 `tool_choice = { type: 'function', name: 'submit_response' }`，且 `reasoning = { effort: 'none' }`（DeepSeek 仅支持 effort=none 时锁定工具）。

### 2. 工具清单（agent 的能力）

`buildAgentTools()` — src/js/agent/tool-definitions.js:9，通过 `isAgentToolsEnabled()`（src/js/agent/tool-definitions.js:5）控制开关（当前即启用）。

| 工具 | 作用 | 定义位置 |
|---|---|---|
| `get_current_time` | 获取当前时间/时区 | src/js/agent/tool-definitions.js:14 |
| `search_memory` | 按关键词检索长期记忆（可指定成员私人记忆） | src/js/agent/tool-definitions.js:20 |
| `list_memories` | 按分类盘点记忆清单（不含详情） | src/js/agent/tool-definitions.js:26 |
| `delete_memory` | 按 ID 删除记忆 | src/js/agent/tool-definitions.js:32 |
| `set_reminder` | 记录待办/约定（promises，须用户发起 + 原话证据） | src/js/agent/tool-definitions.js:38 |
| `ask_user` | 向用户提出澄清问题 | src/js/agent/tool-definitions.js:44 |
| `update_character_field` | 精准修改基础设定字段（说话风格、称呼等） | src/js/agent/tool-definitions.js:50 |
| `upsert_lorebook_entry` | 写入/更新世界书条目（世界层设定唯一入口，保护用户手写条目） | src/js/agent/tool-definitions.js:56 |
| `web_fetch` | 抓网页正文 / 图片 / B站视频（url 或 keyword） | src/js/agent/tool-definitions.js:71 |
| `update_memory` | 按 ID 修正记忆（用户纠正时用） | src/js/agent/tool-definitions.js:77 |
| `send_sticker` | 发表情包（仅当角色有贴图时注册） | src/js/agent/tool-definitions.js:102 |
| `web_search` | 联网搜索（RSS 抓取，本地 function 工具） | src/js/agent/tool-definitions.js:108 / src/js/api/web-content.js |

每个工具就是一段 JSON Schema 描述（name + description + parameters），模型据此决定何时调用。
**工具描述是"何时用哪个工具"的权威依据**，system 提示词的规则 A 只留一句导引。

### 3. 工具实现（agent 的手）

`executeToolCall(call, char, toolState)` — src/js/agent/tool-execution.js:1
- 模型只"说"要调用，真正干活的是这段代码：读写 `char.memory.longTerm`、返回时间、记约定等。
- 统一返回 JSON 字符串，异常安全（`try/catch` 兜底，失败返回 `{ ok: false, reason }`）。

### 4. Agent 循环（大脑）

`sendMessage(options)` 内的 `while(true)` — src/js/chat/conversation.js:163：

- **工具调用（每轮一个）**：DeepSeek thinking 模式**不支持并行 `function_call` 回传（会 400）**，因此模型一次返回多个 `function_call` 时，`conversation.js` 只执行并回传**第一个信息类工具**，其余调用丢弃、由模型在下一轮基于结果重发；`reasoning` 必须先于 `function_call`、再跟配对的 `function_call_output`。`toolState.executedCalls` 与 `MAX_TOOL_CALLS` 作为总量安全上限。若同一轮里既有信息类工具又有 `submit_response`，先执行该工具；只有工具失败时才丢弃这一轮的 `submit_response`，继续让模型修正。**`web_search` 是本地 function 工具**：DeepSeek Responses 会静默忽略内置 `web_search`，故本地注册同名工具，经 `searchWebContent()` 实际检索 RSS 后回传结果。
- **上限**：`MAX_TOOL_ROUNDS = 5`（src/js/agent/tool-definitions.js:2）与 `MAX_TOOL_CALLS = 12`（:3）；达到任一上限后，未执行的调用回传 `ok:false` 的说明，并按 `chatStageDecision('auto-tool-limit', …)` 收尾。
- **reasoning 回传**：thinking 模式的 `reasoning_text` 必须先回传，否则上下文断裂。
- **ask_user 特判**：本轮只调 `ask_user` 且没有 `submit_response` 时，直接把问题包装成结构化回复输出，不进入工具循环。若同一轮既有改动工具又有 `submit_response`，先执行工具、再采用提交（避免"回复说设置已改、实际没改"）。
- **工具改变可缓存 system 后重建请求**：`update_character_field` / `upsert_lorebook_entry` 修改的是静态设定或世界书，会影响 system 前缀；`sendMessage` 在工具调用后比较 `toolState.staticChanges` / `lorebookChanges`，若发生变化则用 `buildRequestPayload` 重建 `messages` 再进入下一轮，保证后续请求与最新设定一致。
- **一次提交 + 兜底**（不再每轮重发）：阶段一 `phase='auto'`（工具自由，含 `submit_response`）。只要拿到正文——`submit_response` 工具调用、合法 JSON 正文、或**散文**——就直接采用，**散文即终稿**，不再为了补 `quickReplies`/记忆而重发一轮（旧行为是几乎每轮都出现「正在整理回复…」甚至「正在重新整理回复…」）。阶段二 `phase='submit'` 只作为"阶段一连正文都没有"时的兜底，`quickReplies` 也**不再阻塞收尾**（缺失/非法直接接受，由后台换位生成补齐）。
- **散文轮的记忆/状态后台补**：散文收尾时正文立即显示，`extractProseTurnMemory()` 在后台把这一轮整理成 shortTerm / longTerm / dynamicState 落盘（`queueBackgroundTask` 串行，不阻塞也不改写正文）。
- **状态显示**：循环内所有右上角文案都来自纯函数 `chatStageDecision(stage, ctx)`（src/js/ui/status-settings.js:19），工具提示来自 `toolActivityHint(name)`（src/js/ui/status-settings.js:1），`setActivity()` 只负责写入 DOM。阶段事件计数由 `noteStageEvent(key)` 记入 `state.config.stageStats`，可在调试面板里核对「整理回复」是否真的不再出现。

### 5. 结构化收尾协议（agent 的手续）

`buildSubmitResponseTool()` — src/js/api/responses.js:1；`extractSubmitResponse()` — src/js/api/responses.js:31。

推荐每轮调用一次 `submit_response` 收尾（阶段一工具里就有它），用 JSON Schema（`strict: true`）强制校验，一次性输出：

| 字段 | 说明 |
|---|---|
| `reply` | 用户可见回复正文（Markdown，动作/表情/心理活动用括号） |
| `quickReplies` | 恰好两条**用户视角**短句（用户下一句可直接发送；不得是角色的台词、表态或角色对用户的提问） |
| `shortTerm` | 本轮事件流程摘要 |
| `longTerm` | 未来仍有价值的稳定事实（category∈userProfile/relationship/events/promises/habits） |
| `dynamicState` | 角色动态状态（7 字段） |
| `memberDynamicState` | 群组成员动态状态（仅群组） |
| `promiseUpdates` | 用户明确完成/取消的承诺（resolved/cancelled） |
| `recall` | 主动召回记忆的请求 |

这是"回复 + 写记忆"合一的契约，保证每轮回复结构合法。

### 6. 右上角状态显示（全分支）

`chatStageDecision` 是状态文案的唯一来源（verify-hub 第 2b 节断言主循环内不再出现裸 `setActivity('…')`）：

| stage | 触发条件 | action | 右上角文案 |
|---|---|---|---|
| `auto-tool` | 阶段一模型发起工具调用 | run-tool | `工具结果已返回，正在继续推理…` + 对应工具提示 |
| `auto-tool-limit` | 工具轮次达 `MAX_TOOL_ROUNDS` | accept-text / force-submit | 有正文 → `工具调用已达上限，直接收尾…`；无正文 → `正在整理回复…` |
| `auto-final` | 阶段一未调用工具、拿到正文 | accept-text / force-submit | 合法 JSON **或散文** → 直接采用（无提示）；完全没有正文 → `正在整理回复…` |
| `quick-replies-missing` | `submit_response` 有 `reply` 但缺/非法 `quickReplies` | accept-reply | 直接接受正文（`obj` 里其余记忆字段照常落盘），`quickReplies` 交后台补齐（无提示） |
| `submit-retry` | 阶段二兜底仍未拿到合法 `submit_response` | retry-submit | `正在重新整理回复…` |
| `empty` | 无可用正文 | regenerate / fail | 有额度 → `正在重新生成（第2次）…`；否则抛错收口 |

工具提示（`toolActivityHint`）：`get_current_time`→`正在确认时间…`、`search_memory`→`正在回忆…`、`list_memories`→`正在整理记忆…`、`delete_memory`→`正在清理记忆…`、`set_reminder`→`已记下，正在回应…`、`update_character_field`→`已按你的要求调整设定…`、`web_search`→`正在搜索…`、`web_fetch`→`正在读取网页…`、`send_sticker`→`正在挑表情…`，未知工具回退 `正在处理…`。

> 主提示词的 0/7/8 与【输出格式】都要求"以结构化方式收尾"（调用 `submit_response` 或输出单个 JSON 对象）且 `quickReplies` 恒为两条用户视角短句；但**客户端不再把 `quickReplies` 当收尾门槛**——散文/缺 quickReplies 的收尾一律直接采用，从而消除"几乎每轮都多跑一轮「正在整理回复…」"的历史问题。

## 四、记忆系统

### 三层结构 + 场景层

`MEMORY_LIMITS` — src/js/core/config.js:104

| 层 | 内容 | 上限 |
|---|---|---|
| `instant` | 最近原始消息 | 160 条（截断保底 40） |
| `shortTerm` | 事件流程摘要 | 80 条（截断保底 20） |
| `longTerm` | 分类长期记忆 | 每类 40 条 |
| `scenes` | 场景概要 | 8 条，注入最近 2 条 |

### longTerm 五类

`userProfile`（用户信息）、`relationship`（关系）、`events`（共同事件）、`promises`（约定）、`habits`（习惯）。

- subject 仅限 `user` / `relationship` / `world` / `character`（`MEMORY_SUBJECTS` src/js/core/config.js:117；`legacy` 仅作历史兼容，不再写入）。
- `isValidAutomaticMemory()` 校验来源引用与证据逐字摘录，防止模型编造记忆。

### 相关度检索

`retrieveRelevantMemories(char, query, memoryStore)` — src/js/memory/updates.js:1
- 评分 = 有效重要度×0.45 + 关键词命中加分 + 时效加分（90 天内递增）+ 剧情弧线 `arcOf` +3。
- 取 top 12，配合 `pendingRecall`（待召回记忆，强制置顶）。
- 可传入 `memoryStore` 以检索群组成员的私人记忆。

### 注入与衰减

- `buildVolatileContext(char, query, metadata)` — src/js/prompts/volatile.js:1：把最相关的记忆注入本轮 prompt（agent 模式只注入 `AGENT_TOOL_MEMORY_INJECT_LIMIT = 5` 条，其余提示模型用 `search_memory` 检索）。总长受 `CONTEXT_BUDGET.volatileChars`（6000）约束，按低→高优先级顺序 `add()`，超出时先丢低优先级块；`metadata` 回传本轮真正注入的召回 ID 与世界书条目，供主线在回复落盘后精确清除 `pendingRecall` 与累计 `mentions`（不依赖会被后台任务改写的全局字段）。检索查询经 `buildMemoryQuery()` 扩展：用户明显指代前文时附加最近对话，避免短指代召回不到正确记忆。
- `applyMemoryDecay()` — src/js/memory/policy.js:143：按时间衰减 events/promises 的重要性与状态。
- `computeEffectiveImportance()` — src/js/memory/policy.js:2：`(importance + learnedBonus) × decay`（0-10 封顶）。

### 记忆子任务

| 任务 | 触发 | 计数器（`memoryTaskKeys`） | 说明 |
|---|---|---|---|
| `extraction` | `instant` 满 160 | `extractionRetryAt` / `extractionFailures` | 即时消息 → 短期摘要 |
| `analysis` | `shortTerm` 满 80 且积压 ≥ 60 | `analysisRetryAt` / `analysisFailures` | 短期摘要 → 长期记忆 |
| `scene` | 场景切换或跨度 ≥ 24 条（按消息序号） | `sceneRetryAt` / `sceneFailures` | 场景概要 |
| `lorebook` | 有已分析摘要尚未整理（或积压 ≥ 8，`consolidateSpan`） | `lorebookRetryAt` / `lorebookFailures` | 沉淀世界书条目 + 自动淘汰 |

`scheduleMemoryRetry()` 统一指数退避（5 分钟 → 30 分钟封顶）；`resetMemoryRetry()` 成功后清零。`checkMemoryTriggers()` — src/js/memory/tasks.js:199 依次调度这四个任务。

**摘要生命周期（`revision` 标记）**：每条短期摘要带 `revision` / `analyzedRevision` / `lorebookScannedRevision`。长期分析只标记 `analyzedRevision`、世界书整理只标记 `lorebookScannedRevision`；`trimShortTermList()` 只有在**两个消费者都处理过同一 revision**、且超出 `shortTermTrimFloor` 时才允许裁剪，避免"世界书还没读到摘要就被删掉"（历史 bug）。摘要被改写时 `revision + 1`，两个标记同时失效、重新排队。`summarizeScene()` 跳过 `sequence ≤ sceneState.startSequence` 的旧消息，裁剪后仍能稳定统计"距上次场景已过多少条"。

**证据与时间精度**：`evidenceMatchesSummary()` 要求证据与实际回复有 ≥2 个双字片段重叠（≥35%），不再"满 8 字即采信"，防止无关文本改写情绪/静态设定；`applyStaticFieldUpdates` 与 `update_character_field` 都额外要求 `hasStaticEditIntent()`（用户原话提到该字段且是修改意图）。`resolveTimeRef` 对带时分秒的 explicit ISO 原样保留（含时区偏移），只给"仅日期有理"的结果补时段起点，不再把明确时刻改写成"当天 12:00"。

### 动态状态（7 字段）

`DYNAMIC_STATE_FIELDS` — src/js/core/config.js:119：`currentSituation` / `currentLocation` / `currentMood` / `currentOccupation` / `currentGoal` / `currentRelationship` / `currentImportantOthers`。
每个字段的 value 必须带 `sourceMessageIds` + `evidence` 溯源；群组整体只维护 `currentSituation` + `currentLocation`（`GROUP_SHARED_DYNAMIC_FIELDS` src/js/core/config.js:129）。

### 世界书（lorebook）

`char.lorebook = [{ id, name, keywords[], content, enabled, order, alwaysActive, origin, mentions, lastMentionedAt, misses }]`

**定位：世界层设定的唯一去处**（时代与世界观、地点、组织、专有名词、历史、规则）。静态字段只承载"人/群组本人"的设定（群组 `description` = 群组前提、单角色 `basicInfo.background` = 个人背景），世界观一律不写进静态字段——这解决了旧版"世界观写描述还是写世界书"的两处重复。

- **两种模式**：`alwaysActive: true`（常驻，每轮都注入，用于世界前提/规则）与关键词命中（被提到才注入，省 token）。无关键词的条目**不再自动转常驻**：写入时若无 `keywords` 又未显式 `alwaysActive:true` 会被拒收，避免堆出永不淘汰的无效条目。
- **来源与保护**：`origin: 'user' | 'ai'`。用户手写的条目（含老数据，默认按 `user` 处理）**不会被模型覆盖，也不参与自动淘汰**；用户一旦手改 AI 条目，该条目即转为 `user`。
- **模型维护（三入口）**：
  1. 建卡/群组升级时由模型产出初始条目（`quickGenerateCharacter` / `upgradeToGroup` 的 `lorebook` 字段，2-4 条，`normalizeGeneratedLorebook()` 标记 `ai`）。
  2. 主循环工具 `upsert_lorebook_entry`（`executeToolCall`）——先按 `entryId`/`name` 精确匹配，未命中再用 `findSimilarLorebookEntry()` 按名称 bigram 相似度（≥`nameSimilarity`）、共享关键词、内容 bigram 相似度（≥`contentSimilarity`）找**同一事物的近似条目**并**合并**（`mergeLorebookContent()` 逐句去重追加，关键词取并集），只有确实没有近似条目才新建；要求 `sourceMessageIds` + `evidence` 可回溯到已存在的消息，且**不得覆盖 `origin:user` 条目**（近似命中用户条目时返回 `mergedIntoUser`，不新建重复条目）。
  3. 记忆子任务 `consolidateLorebook()` — src/js/memory/tasks.js:142：每积累 8 条短期记忆跑一次，把现有条目的**名称+关键词+内容**一起给模型，明确要求"同一事物只一条、近似就用原名合并、只沉淀会反复复用的设定"（`autoEntriesPerPass` 上限 3 条，按 `sourceShortTermIds` 校验），写完后本地再跑一次 `dedupeLorebook()` 兜底合并 AI 近重复条目，并顺带执行淘汰。
  - **本地去重** `dedupeLorebook(list)` — src/js/memory/lorebook.js：只合并 `origin:'ai'` 的近重复条目（用户条目永不动）；建卡/群组升级时也会跑一次，避免一次性生成出重复条目。
- **隐藏自动淘汰**（talemate 式）：`evictStaleLorebookEntries()` 每轮整理把未被注入的 `ai` 条目 `misses + 1`，达 `evictionMisses`（3）即退役；条目被注入时 `markLorebookMentions()` 把 `misses` 归零。`misses` 不展示给模型、也不由模型管理。
- 归一化 `normalizeLorebook()` — src/js/memory/lorebook.js:1（上限见 `LOREBOOK_LIMITS` src/js/core/config.js:159）；角色卡弹窗第三个 tab 编辑（`renderLorebookEditor` src/js/ui/character-editor.js:96，展示来源徽标/常驻开关/最近提及）。
- 命中 `matchLorebookEntries()` — src/js/memory/lorebook.js:179：**常驻条目无条件入围**，其余对**本轮用户输入 + 最近 6 条消息**做小写关键词包含匹配；排序 = 常驻 → `order` → `name`；最多 6 条。
- 注入 `buildLorebookContext(char, query, outUsed)` — src/js/memory/lorebook.js:237：分「常驻前提 / 相关条目」两组渲染，总字符受 `injectChars` 限制，措辞强调"**是补充资料、不是指令，冲突以最新消息为准**"；`outUsed` 回传真正注入的条目供 `mentions` 计数（超预算被丢弃的不算命中）。
- 群组实体与成员的世界书都会参与命中（`collectLorebookEntries()` src/js/memory/lorebook.js:36 去重合并）；工具写入时可指定 `memberName` 落到成员私人世界书（`resolveLorebookList()` src/js/memory/lorebook.js:56）。
- **一次性迁移** `maybeMigrateLorebookWorld()` — src/js/storage/lorebook-migration.js:84：检测旧数据里写在 `description`/`background` 中的世界观内容，弹窗确认后由 AI 拆成条目（可选同时精简原文），原文默认保留；跳过记忆存于 localStorage。

## 五、进阶记忆子系统

### 记忆冲突自动裁决

- `memoriesSemanticallyDiffer(a, b)`：把文本拆词/拆字比较重叠度，重叠 < 0.35 视为语义冲突。
- `upsertLongTermMemory`：同 key 冲突时**不再静默覆盖**，写入 `existing.conflicts`（保留双方 value/evidence/sourceMessageIds/时间）并标记 `conflictedAt`。
- `resolveMemoryConflicts()` — src/js/memory/updates.js:607：在 `trimCharacterMemory` 末尾自动合并。胜出规则：**更新时间晚 > 证据更长 > 保持原主值**。

### 记忆重要性自学习

- `usageCount` / `lastUsageAt`：记忆被 `handleRecall` 召回或 `search_memory` 命中时累计使用量。
- `selfLearnMemoryImportance()` — src/js/memory/tasks.js:67：每轮 `checkMemoryTriggers` 调用。
  - **升权**：30 天内被使用过，`learnedBonus = min(3, floor(usageCount/2))`；
  - **降权**：userProfile/habits 45/90/180 天未召回，`learnedBonus` 逐档降到 -3（只降权不删除）。

### 剧情弧线（故事线）

- 记忆分析 prompt（`analyzeShortToLongTerm`）可输出 `arcOf` + `arcStage`，同一 `arcOf` 只保留一条带弧线标注的事件。
- `retrieveRelevantMemories` 对带 `arcOf` 的记忆额外 +3 权重。

### 成员记忆隔离（群组）

- 每个成员持有独立 `member.memory.longTerm`（`createCharacterObj`）。
- 路由：`submit_response.longTerm` 可带 `memberName` → 写入该成员记忆；记忆工具同样支持 `memberName`。
- 注入：`buildVolatileContext` 为每个成员单独注入私人记忆（标记"仅该成员知道"），规则 11 要求不得张冠李戴。
- 加载时 `normalizeGroupMembers()` src/js/storage/schema.js:237 归一化成员记忆与世界书。

### 话题系统（主动提话题 + 冷场 + 话题切换）

- `buildTopicSuggestions()` — src/js/prompts/context.js:141：从 `basicInfo` + 记忆抽取最多 5 条候选话题。
- `isColdFieldRequest()` — src/js/prompts/context.js:184：识别"不知道聊什么/你说吧"等冷场信号，注入强指令要求角色主动开话题。
- `detectTopicSwitch()` — src/js/prompts/context.js:191：对 `currentGoal + currentSituation`（dynamicState）与用户消息做双字 bigram 重叠检测，不同则注入"先收束旧话题"的软指令。

### 场景概要

- `getSceneKey()` — src/js/memory/tasks.js:102：以 `dynamicState.currentLocation` 作为场景标识。
- `summarizeScene(char, sceneKey)` — src/js/memory/tasks.js:108：把"这一段已告一段落的情节"压缩成 1-2 段（≤300 字）**只含剧情要点**的记忆（谁做了什么/学到或决定了什么/地点物品伤势关系变化/新发现/未解决的目标承诺威胁期限）；写明主体、绝对日期、不引对白、不编造。
- 触发（`checkMemoryTriggers` src/js/memory/tasks.js:199）：**场景切换**（location 变化）或**场景超长**（`CONTEXT_BUDGET.sceneSpan = 24` 条消息），且消息数 ≥ 6。
- 重试计数复用 `memoryTaskKeys('scene')` → `sceneFailures` / `sceneRetryAt`。
- 注入：最近 2 条渲染为 `【场景概要（较早情节的压缩记录…）】`。

## 六、上下文与预算

### 记忆预算

`CONTEXT_BUDGET` — src/js/core/config.js:173

| 常量 | 值 | 说明 |
|---|---|---|
| `retrievedChars` | 1200 | 检索记忆注入的字符预算 |
| `summaryChars` | 1600 | 摘要记忆的基础预算 |
| `sceneSummaries` | 2 | 注入几条场景概要 |
| `sceneInjectionChars` | 600 | 场景概要的总字符预算 |
| `sceneSpan` | 24 | 场景超过多少条消息就强制生成概要 |
| `volatileChars` | 6000 | 本轮动态上下文（volatile）总字符上限 |

- 检索记忆与摘要记忆**共享一个预算池**：检索没用完的额度 `retrievedChars - 实际占用` 自动让给摘要（`buildVolatileContext`）。
- 摘要记忆**从最新往前填充**（`for (shortIndex = length-1; …)` + `shortLines.reverse()`），额度用尽时丢掉的是最旧摘要。

### 提示词字符上限

`PROMPT_LIMITS` — src/js/core/config.js:113：`roleChars: 8000`（单角色设定）/ `memberChars: 900`（群组成员）。
历史消息按条数与单条字符截断，且**同一消息无论是否为本轮都使用同一上限（用户 2000 / assistant 1200）**——保证同一段文字在"当前轮"与"下一轮变历史"时编码逐字一致，避免前缀缓存整段失效。`API_LIMITS`（src/js/core/config.js:98）另有 `auxiliaryTimeoutMs: 90000`（后台小请求超时）、`requestMetrics: 60`（保留记录条数）、`prefixSnapshots: 12`（前缀快照数）。

### 前缀缓存策略

- system = 交互规则 + 【角色设定（固定，不随对话变化）】+【硬性约束与语气锁定】+【输出格式】，全部为静态内容，跨轮不变。
- **首尾放最高优先级**：规则块开头是优先级阶梯，静态块**末尾**再放硬性约束（0.5 禁令 + 2.5 语气锁定）与输出格式 —— 兼顾 primacy 与 recency，同时保持全部静态可缓存。
- 历史消息逐字按序（最旧→最新）拼接、不重排、不注入消息 ID；**同一条消息在任何一轮都用同一编码**（含 `trimText` 上限），因此上一轮的"当前用户消息"在下一轮变成历史时字节不变。
- **所有随轮变化的内容（记忆、世界书、场景概要、节奏骨架、违规纠正提醒、语气回顾、语气锚、回复节奏等）汇总进 `buildVolatileContext`，作为独立的末尾 user 项追加**，不写进任何历史消息体内；`buildResponsesRequestBody` 只用 `instructions` + `input` 数组原样发送，不改变内容顺序。
- 生成示例固定使用 `JSON_EXAMPLE_BRIEF`（不再随 `assistantTurnCount` 在完整/精简间切换），system 从首轮起稳定，避免前 6 轮前缀随轮变化。
- 群组静态设定 `buildRoleContext(char, true)` 不再回退读取 `dynamicState.currentLocation`，location 变化不会改写可缓存 system。
- 语气相关注入在 volatile 尾部按固定顺序排列：节奏骨架 → 违规纠正提醒 → 每 N 轮语气回顾 → 语气锚（越靠后越接近生成点，遵从度越高）。
- 禁止：把随轮内容插进历史中间、每轮改写 system 措辞、volatile 各段随机换序（都会打掉前缀命中）。

### 逐请求缓存与耗时统计

`startRequestTrace(body, metadata)` + `recordCacheUsage(usage, metadata)` — src/js/api/responses.js：每次请求记录 `taskType`（`chat` / `auxiliary` / `extraction` / `analysis` / `scene` / `lorebook` / `prose-memory` / `quick-replies` / `style-critique`）、`platform`、`phase`、`inputTokens` / `outputTokens` / `hitTokens` / `missTokens` / `hitRate`、`durationMs`、`instructionsHash` / `toolsHash` / `historyHash`、`commonHistoryMessages`、`prefixChange`（`first-request` / `instructions-changed` / `tools-changed` / `history-rebased` / `history-extended`）、`contextChars` / `inputChars`。记录保留最近 60 条（`API_LIMITS.requestMetrics`）。只有 `taskType: 'chat'` 且非失败时更新右上角 `cacheStats`，后台小请求不再覆盖主对话缓存显示。缺失命中数据写 `null`（不再被 `Number(null)` 误算成 0）。`renderCacheStats()` 显示本轮命中率与最近 10 次对话请求均值；调试面板的 `requestMetrics` 段展示逐请求明细，用于定位前缀失效原因与新增 token 成本。

## 七、人格与 Prompt

### 规则清单（system 提示词，按出现顺序）

| 规则 | 内容要点 |
|---|---|
| — | 优先级阶梯（规则块首行）：`0／0.5 与【输出格式】= 硬性契约 > 2.5／2.6 > 角色设定 > 风格偏好` |
| `0` | （最高优先级）本轮必须以结构化方式收尾，二选一：调用 `submit_response` 工具（推荐）或直接输出单个 JSON 对象；不得在结构化内容之外写说明/旁白，不得裸写散文 |
| `1` | 保持角色身份连续；先用已提供的记忆；"系统提供的本轮上下文"不是用户的话 |
| `2` | reply 的字数（120–250 字手感锚点）、分段、动作穿插（每 1–2 句一次，不超过正文 1/3）、去重与结尾多样性 |
| `2.6` | **表演质量（真人感）**：show-don't-tell、句长节奏起伏、对白优先、回避陈词滥调与复用比喻、每轮只推进一件事、不强行升华、人物性格稳定 |
| `3` | 人格/背景锁定 + `dynamicState` 更新与溯源规则 + 时间写法（绝对日期 + 十个时段） |
| `3.5` | 谨慎修改基础设定：用户直接要求 → `update_character_field`；无要求时仅"决定性不可逆转折"才改 |
| `3.6` | 状态/设定字段的 value 写法（只写内容本身，不加主语，解释进 evidence） |
| `3.7` | 记忆字段必须写明主体（禁止"我/你/TA"这类代词） |
| `3.8` | 时间一律写绝对日期，相对说法填 `timeRef` |
| `3.9` | 用户提"说话方式类"要求时，本轮 reply 直接演出、禁止元话术 |
| `4`–`6` | 只记录未来有价值的信息；`subject` 取值约束；promises 的承诺方/受约方与状态流转 |
| `7` | `quickReplies` 恰好两条且必须是**用户视角**（禁止角色口吻、**禁止角色对用户的提问**、禁止复述角色台词） |
| `8` | JSON 格式示例（**新会话前 5 轮用完整示例，之后自动换成 `JSON_EXAMPLE_BRIEF` 精简示例**，见 src/js/core/normalization.js:2） |
| `9` | `longTerm.category` 取值与 `importance` 范围 |
| `9.5` | 相对时间（timeRef）说明（取值与示例已下沉到工具 schema） |
| `A` | 工具使用总则（详细适用场景以工具描述为准；工具结果属内部上下文，不得向用户透露检索过程） |
| `10`–`12` | 群组：发言格式、记忆归属、设定三层（群组 quickReplies 同样要求用户视角） |
| — | 【角色设定（固定，不随对话变化）】= `buildRoleContext(char, true)` |
| `0.5` | **只扮演角色本人：禁止替用户说话或行动**（不得写用户的台词/动作/表情/心理/感受/决定，不得替用户做选择；用户已写的括号动作只能回应、不能续写）——置于硬性约束块（静态块末尾） |
| `2.5` | 语气锁定：腔调只能来自设定的"说话风格"与"对用户的称呼"，优先级高于模型通用文风，不同角色差异必须显著——与 0.5 同在硬性约束块 |
| — | 【输出格式】工具调用与单个 JSON 对象二选一收尾（静态块最后一行；即使本轮调用过其他工具也必须用它收尾） |

### 节奏骨架

`NARRATIVE_PATTERNS` — src/js/core/config.js:144（10 条结构模板：动作/描写 → 对白 → 描写 的排列组合）
`buildNarrativePatternDirective()` — src/js/prompts/context.js:266：每轮随机取一条，注入 volatile 尾部的 `【本轮节奏骨架（每轮随机给出，仅作节奏参考）】`，用于打破"每轮结构雷同"。

### volatile 段落（按顺序）

时间基准 → 消息时间元数据 → 角色当前状态 → 成员当前状态 → **世界书资料** → 本轮主动召回的相关长期记忆 + 记忆取用说明 + 记忆主体约定 → 成员私人记忆 → **场景概要** → 近期摘要记忆 → 待跟进承诺 → 互动节奏（重连/久别）→ 可选话题库 → 结尾多样性 → **本轮节奏骨架** → 冷场/话题切换指令 →（主动开场时）角色主动开场 → **快速回应视角提醒** → **违规纠正提醒** → **每 N 轮语气回顾** → **语气锚**。

> 记忆取用说明：提醒召回条目"按关键词与时间粗略召回，可能只有部分相关或已过时"，不得硬提旧事、不得当作用户刚说过的话。

### 语气/风格遵从（attention）

把最容易违反的约束尽量贴近生成点，并用客户端检测闭环（纯函数，可单测；常量见 `STYLE_GUARD` src/js/core/config.js:182）：

- **优先级阶梯**：规则块开头声明"0／0.5 与【输出格式】= 硬性契约 > 2.5 语气锁定与 2.6 表演质量 > 角色设定 > 风格偏好"。
- **静态块末尾重申**：`【硬性约束与语气锁定（最高优先级，冲突时以这里为准）】` 内含 0.5 禁令与 2.5 语气锁定，紧跟【输出格式】（recency，且全部静态可缓存）。
- **语气锚** `buildStyleAnchor()` — src/js/prompts/style.js:15：每轮在 volatile 最末尾给出「说话风格摘要 + N 条逐字示例台词 + 对用户的称呼 + 本轮禁令」，把"用什么口吻说话"放到离生成最近的位置。
- **违规检测** `detectStyleViolations(reply, char, prevReplies)` — src/js/prompts/style.js:32：纯函数返回标签
  `metaTalk`（元话术）／`speaksForUser`（替用户说话）／`cliche`（陈词滥调表 `STYLE_CLICHES`）／`reusedImagery`（复用近期意象，8 字独特片段）／`toneDrift`（声明的口癖缺失或句长过于整齐）／`recitedLore`（复述世界书条目）。
- **内部提醒**（不展示给用户）：违规标签随消息持久化（`message.styleViolations`），下一轮由 `buildStyleCorrectionReminder()` — src/js/prompts/style.js:239 在 volatile 尾部点名纠正。
- **每 N 轮语气回顾** `buildStyleReview()` — src/js/prompts/style.js:226：`STYLE_GUARD.reviewEveryTurns`（默认 3）轮一次，汇总近期违规并重申语气契约。
- **critique 调用** `critiqueReplyStyle()` — src/js/prompts/style.js:247（来源：talemate 的 `arc-expand-critique`）：命中违规且 `state.config.styleCritique !== false` 时追加一次**只改文风、不改剧情**的修订请求；解析失败或长度超出 `critiqueMinRatio`/`critiqueMaxRatio`/`critiqueMaxChars` 一律**回退原文**；修订后重新检测并以剩余违规为准。

### 快速回应视角（quickReplies）

「快速回应」必须是**用户**下一句会发的话，而模型容易写成角色口吻或角色的提问。这里同样用"校验 + 后台修正"闭环（常量 `QUICK_REPLY_GUARD` src/js/core/config.js:208）：

- **提示词层**：规则 `7`（src/js/prompts/request.js:32）明确三类禁止写法（角色口吻 / **角色视角的提问** / 复述角色台词）并给出对比例句；`submit_response` 的 `quickReplies` 描述与群组规则 `10` 同步收紧；规则 `8` 的示例把 quickReplies 换成真实用户视角短句（只示范视角，措辞不得照抄）。
- **校验层** `detectQuickReplyIssues(replies, char, replyText)` — src/js/prompts/style.js:124：纯函数返回标签 `missing` / `placeholder`（照抄"短句一/短句二"占位文字）/ `action`（带括号动作）/ `mirrored`（与角色本轮台词逐字或镜像重复）/ `characterName`（出现角色名）/ `tooLong`。
- **修正层** `generateQuickRepliesAsUser()` — src/js/prompts/style.js:181 + `repairQuickRepliesAsUser()` — src/js/prompts/request.js:114：**换位生成**——用一个身份为"用户本人"的后台小调用重写（system 明写"你就是这位用户本人…不要扮演<角色名>"），成功后只更新快速回应按钮，**不触碰已显示的正文**；失败回退 `['嗯','继续']`。开关 `state.config.quickReplyRepair`（默认开，设置面板 `quickReplyRepairToggle`）。
- **不阻塞**：quickReplies 不再参与"是否重发一轮"的判定，也不再触发「正在补齐快速回应…」；缺失/非法一律先接受正文，由后台补上。
- **内部提醒**：问题随消息持久化（`message.quickReplyIssues`），下一轮由 `buildQuickReplyPerspectiveReminder()` 在 volatile 尾部点名（与语气纠正同一机制）。

### 人格组装

- `buildRoleContext(char, staticOnly)` — src/js/prompts/context.js:15：从 `char.basicInfo` 拼人格（主字段 + 次字段，共 12 项 `STATIC_PROFILE_FIELDS` src/js/core/config.js:130）；群组则拼群组信息 + 成员清单（`buildMemberContext` src/js/prompts/context.js:2）。
- `buildDynamicStateContext()` — src/js/prompts/context.js:44：把 7 个动态字段渲染为"状态"文本（未设置的显示 `(未设置)`）。
- `buildRequestPayload(char, query)` — src/js/prompts/request.js:1：装配 system + 历史 + 本轮 volatile。

### 子任务提示词（非主对话）

主对话之外的每个 `callAPI` 调用都有独立 prompt，各司其职：

| 提示词 | 位置 | 关键约束 |
|---|---|---|
| 一键建卡 `quickGenerateCharacter` | src/js/characters/generation.js:2 | 输出完整字段 JSON；**禁止套路名字与模板人设**；`personality` 写行为倾向；`background` 只写角色本人经历（世界观写 `lorebook`）；`speakingStyle` 必须是「调性描述；示例：<台词1> / <台词2> / <台词3>」（`CHARACTER_QUALITY_RULE` + `SPEAKING_STYLE_SAMPLES_RULE`）；群组分支要求成员说话方式显著不同，并输出 2-4 条初始 `lorebook` 条目 |
| 群组升级 `upgradeToGroup` | src/js/characters/generation.js:117 | 与建卡同一套质量约束；`additionalMembers` 不得重复原角色与彼此姓名；可补 0-3 条 `lorebook`（只补近期已出现的世界层设定，没有就空数组）；原角色世界书并入群组 |
| 世界书整理 `consolidateLorebook` | src/js/memory/tasks.js:142 | 只从**已发生的短期摘要**里沉淀世界层设定（`sourceShortTermIds` 必须可回溯）、禁止推测扩写；把现有条目内容一并给模型并要求"同一事物只一条、近似就用原名合并、只写会复用的设定"；≤`autoEntriesPerPass`(3) 条；写后本地 `dedupeLorebook()` 兜底，并执行 `misses` 淘汰 |
| 世界书一次性迁移 `migrateWorldLoreForEntity` | src/js/storage/lorebook-migration.js:44 | 把旧数据 `description`/`background` 里的世界观拆成条目（≤6 条，只整理已有信息）；用户手写条目不受影响；可选同时精简原文（仅在确实变短时替换） |
| 成员补全 `fillGroupMemberFields` | src/js/ui/character-editor.js:236 | **已有字段是绝对权威，不得改写/润色/替换**；只填空字段；说话方式须与群内其他成员显著不同；无把握则省略 |
| 空字段补全 `fillStaticFieldsForJob` | src/js/characters/profile-completion.js:33 | 只补 `job.missing` 中的字段，绝不覆盖已有设定；缺 `speakingStyle` 时套用示例台词约束；群组成员再叠加"彼此不同" |
| 即时→短期摘要 `extractInstantToShortTerm` | src/js/memory/tasks.js:259 | 按"日期+时段"合并；**只保留影响后续剧情的内容**（决策/地点物品伤势关系变化/发现/未决目标承诺威胁期限）；**每条 ≤120 字**；禁止文学化与对白原文；续写旧事件时**旧摘要中仍有效的信息必须原样保留**；必须覆盖全部输入消息 ID |
| 短期→长期 `analyzeShortToLongTerm` | src/js/memory/tasks.js:345 | **价值判据**（可跨轮复用/影响后续/用户明确表达）才记，**证据不足宁可不记**；`arcOf` 命名一旦确定保持稳定；`sourceShortTermIds` / `evidence` 必须可回溯 |
| 场景概要 `summarizeScene` | src/js/memory/tasks.js:108 | 1-2 段 ≤300 字，只含剧情要点，不引对白、不编造、写绝对日期 |
| 文风校对 `critiqueReplyStyle` | src/js/prompts/style.js:247 | **只改文风、不改剧情**（情节/事实/对话含义/人物关系不动，大体长度与段落数保持）；不得复述规则；只返回 `{"reply":"…"}`；空结果或长度比例异常一律回退原文 |
| 快速回应换位生成 `generateQuickRepliesAsUser` | src/js/prompts/style.js:181 | 身份是**用户本人**（system：你就是这位用户本人…不要扮演角色）；只输出 `{"quickReplies":[...]}`；结果再过一遍 `detectQuickReplyIssues`，不通过就放弃（回退兜底） |
| 散文轮记忆整理 `extractProseTurnMemory` | src/js/prompts/request.js:126 | 复用"对话记录整理助手"把散文轮补成结构化记忆；**后台执行**（`queueBackgroundTask` 串行）、不改写已显示正文、还原 `lastInjectedRecallIds` |
| 其余 | src/js/characters/avatar.js:98 emoji 头像 / src/js/prompts/request.js:88 散文转 JSON / src/js/storage/profile-migration.js:68 字段重排 / src/js/storage/lorebook-migration.js:124 群组字段迁移 / src/js/media/stickers.js:92 贴图标签 | 低价值路径，未做经验对齐 |

> 像素头像提示词 `PIXEL_AVATAR_PROMPT`（src/js/characters/avatar.js:72）**不参与任何建卡/补全 prompt**（像素头像为已废弃试验品），仅保留定义与遗留生成器 `requestPixelAvatar`。

## 八、健壮性

- **API 请求级重试**：`performChatRequestWithRetry`（src/js/api/retry.js:14）对瞬时失败（网络错误 / 超时 Abort / HTTP 429 / 5xx）自动重试最多 `MAX_API_RETRIES = 3` 次（1s/2s/4s 指数退避）。
- **工具失败可恢复**：`executeToolCall` 失败时回传可修正的提示（如 `delete_memory` 找不到 ID 时提示先 `list_memories`）。本轮工具 `ok:false` 时不采用同一轮的 `submit_response`，继续让模型修正，避免"回复说改了、实际没改"。
- **每轮一个工具 + 上限**：DeepSeek thinking 模式不支持并行 `function_call` 回传，每轮只执行并回传第一个信息类工具；`MAX_TOOL_CALLS` 约束整轮总数；跨轮复用 `call_id` 时自动改用新 `call_id`，保证回传项内 `call_id` 唯一。
- **记忆任务重试**：`memoryTaskKeys(task)` — src/js/memory/tasks.js:34 统一映射 `extraction` / `analysis` / `scene` / `lorebook` 四组计数器（`*Failures` / `*RetryAt`），`scheduleMemoryRetry` 指数退避（5→30 分钟封顶）。
- **后台任务过期保护**：`captureMemoryTask(char)` 记录角色引用、`memory.revision` 与末条消息 ID；`isMemoryTaskCurrent()` 在后台调用返回后校验，过期（角色被删、轮次推进、分支被删）就直接丢弃。`extractProseTurnMemory` 在快照副本上运行 `convertProseToJson`，不修改真实 `lastInjectedRecallIds`；回写前要求 `revision` 未变、原 assistant 消息仍在，并只接受引用本轮用户消息或本轮回复的记忆。快速回应换位生成、空字段补全同样带 guard。
- **摘要不被提前丢弃**：`trimShortTermList` 只在长期分析与世界书整理都消费了同一 `revision` 后才裁剪；世界书条目在写库前整批校验来源与字段，失败不推进 `lorebookScannedRevision`、也不累计 `misses`，避免一次坏输出造成误淘汰。
- **检索相关性下限**：`retrieveRelevantMemories` 只保留至少命中一个非停用词的候选，避免纯高重要性但无关的旧记忆挤占注入；`pendingRecall`（模型主动召回）优先占位，即使候选取满也一定注入；短指代查询经 `buildMemoryQuery` 补上下文。
- **时间精度**：`resolveTimeRef` 保留带时区/时分的 explicit ISO，仅"日期或时段"表达才落整点/时段起点，避免把明确时刻改写成正午。
- **请求失败也留痕**：`performChatRequest` / `callAI` 的 fetch 失败或非 2xx 也会写入 `requestMetrics`（`status: 'failed'`），并取消未读完的流式 reader，便于诊断网络与限流问题。
- **文风校对可回退**：`critiqueReplyStyle` 在任何异常（解析失败、空结果、长度超出 `critiqueMinRatio`/`critiqueMaxRatio`/`critiqueMaxChars`）下都返回原文，绝不因校对失败影响回复。
- **快速回应后台补齐可回退**：`repairQuickRepliesAsUser` 的换位生成失败（或生成结果仍不过校验）时退回 `['嗯','继续']`；回来太晚（`state.quickReplyMessageId` 已被下一轮覆盖）直接丢弃，不会覆盖新一轮的快速回应。
- **后台任务串行**：`queueBackgroundTask`（src/js/prompts/style.js:208）把"散文轮记忆整理""快速回应补写"串成一条 Promise 链，避免交错写同一份角色状态；两者都不阻塞正文显示。
- **散文即终稿不再重写**：阶段一拿到散文就直接采用，旧行为（`convertProseToJson` 阻塞在可见链路里、把用户已看到的正文换成"整理"后的版本）已移除；实机可用调试面板的 `stageStats` / `activityLog` 核对「正在整理回复…」是否真的不再出现。
- **导入/导出归一化**：`normalizeAppData` → `normalizeCharacter`（src/js/storage/schema.js:40）逐字段校验，包含世界书、场景概要、场景状态与新增计数器。
- **版本号单一来源**：右上角 `APP_VERSION`（src/js/core/config.js:73）由 `versionName` 派生，`tools/sync-version.mjs` 负责写入两份 `hub.html`；CI 在 `assembleRelease` 前执行该脚本并 `cmp` 校验两份文件一致，本地用 `node tools/sync-version.mjs --check` 复核。

## 九、关键常量速查

| 常量 | 值 | 位置 |
|---|---|---|
| `MAX_TOOL_ROUNDS` | 5 | src/js/agent/tool-definitions.js:2 |
| `MAX_TOOL_CALLS` | 12 | src/js/agent/tool-definitions.js:3 |
| `AGENT_TOOL_MEMORY_INJECT_LIMIT` | 5 | src/js/agent/tool-definitions.js:4 |
| `MAX_API_RETRIES` | 3（退避 1s/2s/4s） | src/js/api/retry.js:2 |
| `MEMORY_LIMITS` | instant 160(保底 40) / shortTerm 80(保底 20) / longTermPerCategory 40 / pendingRecall 6 / analysisBatch 40 / summarySources 160 | src/js/core/config.js:104 |
| `API_LIMITS` | auxiliaryTimeoutMs 90000 / requestMetrics 60 / prefixSnapshots 12 | src/js/core/config.js:98 |
| `PROMPT_LIMITS` | roleChars 8000 / memberChars 900 | src/js/core/config.js:113 |
| `CONTEXT_BUDGET` | retrievedChars 1200 / summaryChars 1600 / sceneSummaries 2 / sceneInjectionChars 600 / sceneSpan 24 / volatileChars 6000 | src/js/core/config.js:173 |
| `LOREBOOK_LIMITS` | entries 200 / keywordsPerEntry 20 / nameChars 60 / contentChars 2000 / injectEntries 6 / injectChars 1400 / scanMessages 6 / autoEntriesPerPass 3 / evictionMisses 3 / consolidateSpan 8 / maxAlwaysActive 6 / nameSimilarity 0.5 / contentSimilarity 0.45 / mergeSentenceSimilarity 0.6 | src/js/core/config.js:159 |
| `NARRATIVE_PATTERNS` | 10 条节奏骨架 | src/js/core/config.js:144 |
| `DYNAMIC_STATE_FIELDS` | 7 个动态字段 | src/js/core/config.js:119 |
| `STATIC_PROFILE_FIELDS` | 12 个基础设定字段 | src/js/core/config.js:130 |
| `CHARACTER_QUALITY_RULE` / `SPEAKING_STYLE_SAMPLES_RULE` | 建卡与补全共用的质量/示例台词约束 | src/js/characters/avatar.js:76 / src/js/characters/avatar.js:76 |
| `STYLE_GUARD` | reviewEveryTurns 3 / lookbackReplies 2 / anchorStyleChars 300 / anchorSamples 2 / critiqueMaxChars 1600 / critiqueMinRatio 0.5 / critiqueMaxRatio 2 | src/js/core/config.js:182 |
| `STYLE_CLICHES` | 11 条陈词滥调（违规检测用） | src/js/core/config.js:194 |
| `QUICK_REPLY_GUARD` | maxChars 60 / mirrorChars 8 / repairMaxChars 40 | src/js/core/config.js:208 |
| `QUICK_REPLY_ISSUE_LABELS` | 6 类快速回应视角问题说明（内部提醒用） | src/js/core/config.js:214 |
| `QUICK_REPLY_PLACEHOLDERS` | 示例占位串黑名单（短句一/短句二/用户下一句…） | src/js/core/config.js:224 |
| `APP_VERSION` | 由 `versionName` 写入（`tools/sync-version.mjs`） | src/js/core/config.js:73 |
| JSON 示例 | 固定使用 `JSON_EXAMPLE_BRIEF`（不再随轮切换），保证 system 前缀从首轮起稳定 | src/js/core/normalization.js:2 |
| `max_output_tokens` | 8192 | src/js/api/responses.js:63 |
| 摘要生命周期字段 | `revision` / `analyzedRevision` / `lorebookScannedRevision`；消息 `sequence`；场景 `sceneState.startSequence` | src/js/memory/tasks.js / src/js/storage/schema.js |
| 请求追踪字段 | `requestMetrics[]`（taskType/platform/phase/tokens/hitRate/durationMs/prefixChange/commonHistoryMessages/hashes） | src/js/api/responses.js:227 |




