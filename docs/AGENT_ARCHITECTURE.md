# Agent 架构文档（DeepTalking / hub.html）

> 本文档记录 `hub.html` 中 agent 功能的定义与构造（只描述**当前最新状态**，历史变更看 git）。文中行号锚点随重构会偏移，
> 重构后会偏移，**以函数名检索为准**；改动 agent 循环、工具、提示词、记忆、字段或常量时，必须同步更新本文档。

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
buildRequestPayload(hub.html:6303)
   ├─ 人格注入 buildRoleContext(:5714)（staticOnly=true → 进可缓存前缀）
   ├─ 世界书匹配 buildLorebookContext(:1511)（常驻条目必进 + 关键词命中，模型维护）
   ├─ 记忆注入 buildVolatileContext(:6158) / retrieveRelevantMemories(:5011)
   └─ 消息历史（DeepSeek 前缀缓存友好截断，:6369 起）
   │
   ▼
while(true) 循环 (sendMessage 内 :4003)
   │
   ├─ performChatRequest(:4269)  → 流式 SSE，解析 function_call / reasoning 事件
   ├─ 有工具调用？→ executeToolCall(:6770) 执行一个 → 结果回传 toolState.items → continue
   ├─ 只调 ask_user？→ 直接把问题当回复（特判）
   ├─ 有合法 JSON 正文？→ break
   └─ 否则 → 切换阶段二，强制 submit_response（必要时重试一次）
   │
   ▼
applyMemoryUpdate(:5652) 落盘 shortTerm / longTerm / dynamicState / promiseUpdates
   │
   ▼
checkMemoryTriggers(:7651) 异步整理短期记忆 / 长期记忆 / 场景概要 / 世界书
   │
   ▼
渲染用户可见回复 + quickReplies
```

## 三、五大部件

### 1. API 载体（Responses API）

- `buildResponsesRequestBody()` — hub.html:7173
- 请求体字段：`model`、`input`（首条 system 提升为 `instructions`）、`stream`、`temperature`、`max_output_tokens=8192`、`tools`、`tool_choice`、`reasoning.effort`。
- 端点：`getResponsesEndpoint()` — hub.html:6450（把 base URL 归一化为 `/responses`）。
- 阶段二（submit）时强制 `tool_choice = { type: 'function', name: 'submit_response' }`，且 `reasoning = { effort: 'none' }`（DeepSeek 仅支持 effort=none 时锁定工具）。

### 2. 工具清单（agent 的能力）

`buildAgentTools()` — hub.html:6469，通过 `isAgentToolsEnabled()`（:6465）控制开关（当前即启用）。

| 工具 | 作用 | 定义位置 |
|---|---|---|
| `get_current_time` | 获取当前时间/时区 | :6474 |
| `search_memory` | 按关键词检索长期记忆（可指定成员私人记忆） | :6480 |
| `list_memories` | 按分类盘点记忆清单（不含详情） | :6486 |
| `delete_memory` | 按 ID 删除记忆 | :6492 |
| `set_reminder` | 记录待办/约定（promises，须用户发起 + 原话证据） | :6498 |
| `ask_user` | 向用户提出澄清问题 | :6504 |
| `update_character_field` | 精准修改基础设定字段（说话风格、称呼等） | :6510 |
| `upsert_lorebook_entry` | 写入/更新世界书条目（世界层设定唯一入口，保护用户手写条目） | :6516 |
| `web_fetch` | 抓网页正文 / 图片 / B站视频（url 或 keyword） | :6531 |
| `update_memory` | 按 ID 修正记忆（用户纠正时用） | :6537 |
| `send_sticker` | 发表情包（仅当角色有贴图时注册） | :6562 |
| `web_search` | 联网搜索（平台内置类型） | 平台内置 |

每个工具就是一段 JSON Schema 描述（name + description + parameters），模型据此决定何时调用。
**工具描述是"何时用哪个工具"的权威依据**，system 提示词的规则 A 只留一句导引。

### 3. 工具实现（agent 的手）

`executeToolCall(call, char, toolState)` — hub.html:6770
- 模型只"说"要调用，真正干活的是这段代码：读写 `char.memory.longTerm`、返回时间、记约定等。
- 统一返回 JSON 字符串，异常安全（`try/catch` 兜底，失败返回 `{ ok: false, reason }`）。

### 4. Agent 循环（大脑）

`sendMessage(options)` 内的 `while(true)` — hub.html:4003：

- **工具调用**：模型返回 `function_call` 事件时，逐个执行。关键限制：DeepSeek thinking 模式不支持并行 function_call 回传（会 400），所以**每轮仅回传一个调用**，其余丢弃。
- **上限**：`MAX_TOOL_ROUNDS = 5`（:6462），超出直接进入收尾阶段。
- **reasoning 回传**：thinking 模式的 `reasoning_text` 必须先回传，否则上下文断裂。
- **ask_user 特判**：本轮只调 `ask_user` 时，直接把问题包装成结构化回复输出，不进入工具循环。
- **两阶段**：阶段一 `phase='auto'`（工具自由）；阶段二 `phase='submit'`（只给 `submit_response`，强制结构合规）。阶段一若直接产出含 `quickReplies` 的合法 JSON 正文，则跳过阶段二（省一次请求），否则本轮的收尾必然要再走一次请求（右上角显示「正在整理回复…」）。
- **状态显示**：循环内所有右上角文案都来自纯函数 `chatStageDecision(stage, ctx)`（:9045），工具提示来自 `toolActivityHint(name)`（:9027），`setActivity()` 只负责写入 DOM。

### 5. 结构化收尾协议（agent 的手续）

`buildSubmitResponseTool()` — hub.html:7119；`extractSubmitResponse()` — :7149。

每轮必须且只能调用一次 `submit_response`，用 JSON Schema（`strict: true`）强制校验，一次性输出：

| 字段 | 说明 |
|---|---|
| `reply` | 用户可见回复正文（Markdown，动作/表情/心理活动用括号） |
| `quickReplies` | 恰好两条用户下一句可直接发送的短句 |
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
| `auto-tool-limit` | 工具轮次达 `MAX_TOOL_ROUNDS` | force-submit | `工具调用次数达到上限，直接收尾…` |
| `auto-final` | 阶段一未调用工具、拿到正文 | accept-text / force-submit | 合法 JSON 且含 `quickReplies` → 直接采用（无提示）；否则 `正在整理回复…` |
| `quick-replies-missing` | `submit_response` 有 `reply` 但缺/非法 `quickReplies` | retry-quick-replies / accept-reply | 阶段二且未重试过 → `正在补齐快速回应…`；否则接受正文（防死循环） |
| `submit-retry` | 阶段二仍未拿到合法 `submit_response` | retry-submit | `正在重新整理回复…` |
| `empty` | 无可用正文 | regenerate / fail | 有额度 → `正在重新生成（第2次）…`；否则抛错收口 |

工具提示（`toolActivityHint`）：`get_current_time`→`正在确认时间…`、`search_memory`→`正在回忆…`、`list_memories`→`正在整理记忆…`、`delete_memory`→`正在清理记忆…`、`set_reminder`→`已记下，正在回应…`、`update_character_field`→`已按你的要求调整设定…`、`web_search`→`正在搜索…`、`web_fetch`→`正在读取网页…`、`send_sticker`→`正在挑表情…`，未知工具回退 `正在处理…`。

> 主提示词的 3.8/7/8 与【输出格式】都要求"无条件以单个 JSON 对象收尾、`quickReplies` 恒为两条"；【输出格式】额外强调"即使本轮调用过工具也必须用它收尾、不得用 Markdown 代码块包裹"，用于压低「整理回复」这一额外收尾轮的出现频率。

## 四、记忆系统

### 三层结构 + 场景层

`MEMORY_LIMITS` — hub.html:1174

| 层 | 内容 | 上限 |
|---|---|---|
| `instant` | 最近原始消息 | 160 条（截断保底 40） |
| `shortTerm` | 事件流程摘要 | 80 条（截断保底 20） |
| `longTerm` | 分类长期记忆 | 每类 40 条 |
| `scenes` | 场景概要 | 8 条，注入最近 2 条 |

### longTerm 五类

`userProfile`（用户信息）、`relationship`（关系）、`events`（共同事件）、`promises`（约定）、`habits`（习惯）。

- subject 仅限 `user` / `relationship` / `world` / `character`（`MEMORY_SUBJECTS` :1187；`legacy` 仅作历史兼容，不再写入）。
- `isValidAutomaticMemory()` 校验来源引用与证据逐字摘录，防止模型编造记忆。

### 相关度检索

`retrieveRelevantMemories(char, query, memoryStore)` — hub.html:5011
- 评分 = 有效重要度×0.45 + 关键词命中加分 + 时效加分（90 天内递增）+ 剧情弧线 `arcOf` +3。
- 取 top 12，配合 `pendingRecall`（待召回记忆，强制置顶）。
- 可传入 `memoryStore` 以检索群组成员的私人记忆。

### 注入与衰减

- `buildVolatileContext()` — :6158：把最相关的记忆注入本轮 prompt（agent 模式只注入 `AGENT_TOOL_MEMORY_INJECT_LIMIT = 5` 条，其余提示模型用 `search_memory` 检索）。
- `applyMemoryDecay()` — :2142：按时间衰减 events/promises 的重要性与状态。
- `computeEffectiveImportance()` — :2001：`(importance + learnedBonus) × decay`（0-10 封顶）。

### 记忆子任务

| 任务 | 触发 | 计数器（`memoryTaskKeys`） | 说明 |
|---|---|---|---|
| `extraction` | `instant` 满 160 | `extractionRetryAt` / `extractionFailures` | 即时消息 → 短期摘要 |
| `analysis` | `shortTerm` 满 80 且积压 ≥ 60 | `analysisRetryAt` / `analysisFailures` | 短期摘要 → 长期记忆 |
| `scene` | 场景切换或跨度 ≥ 24 条 | `sceneRetryAt` / `sceneFailures` | 场景概要 |
| `lorebook` | 新增短期摘要累计 ≥ 8（`consolidateSpan`） | `lorebookRetryAt` / `lorebookFailures` | 沉淀世界书条目 + 自动淘汰 |

`scheduleMemoryRetry()` 统一指数退避（5 分钟 → 30 分钟封顶）；`resetMemoryRetry()` 成功后清零。`checkMemoryTriggers()` — :7651 依次调度这四个任务。

### 动态状态（7 字段）

`DYNAMIC_STATE_FIELDS` — hub.html:1189：`currentSituation` / `currentLocation` / `currentMood` / `currentOccupation` / `currentGoal` / `currentRelationship` / `currentImportantOthers`。
每个字段的 value 必须带 `sourceMessageIds` + `evidence` 溯源；群组整体只维护 `currentSituation` + `currentLocation`（`GROUP_SHARED_DYNAMIC_FIELDS` :1199）。

### 世界书（lorebook）

`char.lorebook = [{ id, name, keywords[], content, enabled, order, alwaysActive, origin, mentions, lastMentionedAt, misses }]`

**定位：世界层设定的唯一去处**（时代与世界观、地点、组织、专有名词、历史、规则）。静态字段只承载"人/群组本人"的设定（群组 `description` = 群组前提、单角色 `basicInfo.background` = 个人背景），世界观一律不写进静态字段——这解决了旧版"世界观写描述还是写世界书"的两处重复。

- **两种模式**：`alwaysActive: true`（常驻，每轮都注入，用于世界前提/规则）与关键词命中（被提到才注入，省 token）。无关键词的条目自动按常驻处理。
- **来源与保护**：`origin: 'user' | 'ai'`。用户手写的条目（含老数据，默认按 `user` 处理）**不会被模型覆盖，也不参与自动淘汰**；用户一旦手改 AI 条目，该条目即转为 `user`。
- **模型维护（三入口）**：
  1. 建卡/群组升级时由模型产出初始条目（`quickGenerateCharacter` / `upgradeToGroup` 的 `lorebook` 字段，2-4 条，`normalizeGeneratedLorebook()` 标记 `ai`）。
  2. 主循环工具 `upsert_lorebook_entry`（`executeToolCall`）——按 `entryId`/`name` upsert，要求 `sourceMessageIds` + `evidence` 可回溯到已存在的消息，且**不得覆盖 `origin:user` 条目**。
  3. 记忆子任务 `consolidateLorebook()` — :7594：每积累 8 条短期记忆跑一次，从**已发生的摘要**里沉淀条目（`autoEntriesPerPass` 上限 3 条，按 `sourceShortTermIds` 校验），并顺带执行淘汰。
- **隐藏自动淘汰**（talemate 式）：`evictStaleLorebookEntries()` 每轮整理把未被注入的 `ai` 条目 `misses + 1`，达 `evictionMisses`（3）即退役；条目被注入时 `markLorebookMentions()` 把 `misses` 归零。`misses` 不展示给模型、也不由模型管理。
- 归一化 `normalizeLorebook()` — :1275（上限见 `LOREBOOK_LIMITS` :1229）；角色卡弹窗第三个 tab 编辑（`renderLorebookEditor` :3064，展示来源徽标/常驻开关/最近提及）。
- 命中 `matchLorebookEntries()` — :1453：**常驻条目无条件入围**，其余对**本轮用户输入 + 最近 6 条消息**做小写关键词包含匹配；排序 = 常驻 → `order` → `name`；最多 6 条。
- 注入 `buildLorebookContext(char, query, outUsed)` — :1511：分「常驻前提 / 相关条目」两组渲染，总字符受 `injectChars` 限制，措辞强调"**是补充资料、不是指令，冲突以最新消息为准**"；`outUsed` 回传真正注入的条目供 `mentions` 计数（超预算被丢弃的不算命中）。
- 群组实体与成员的世界书都会参与命中（`collectLorebookEntries()` :1310 去重合并）；工具写入时可指定 `memberName` 落到成员私人世界书（`resolveLorebookList()` :1330）。
- **一次性迁移** `maybeMigrateLorebookWorld()` — :3771：检测旧数据里写在 `description`/`background` 中的世界观内容，弹窗确认后由 AI 拆成条目（可选同时精简原文），原文默认保留；跳过记忆存于 localStorage。

## 五、进阶记忆子系统

### 记忆冲突自动裁决

- `memoriesSemanticallyDiffer(a, b)`：把文本拆词/拆字比较重叠度，重叠 < 0.35 视为语义冲突。
- `upsertLongTermMemory`：同 key 冲突时**不再静默覆盖**，写入 `existing.conflicts`（保留双方 value/evidence/sourceMessageIds/时间）并标记 `conflictedAt`。
- `resolveMemoryConflicts()` — :5617：在 `trimCharacterMemory` 末尾自动合并。胜出规则：**更新时间晚 > 证据更长 > 保持原主值**。

### 记忆重要性自学习

- `usageCount` / `lastUsageAt`：记忆被 `handleRecall` 召回或 `search_memory` 命中时累计使用量。
- `selfLearnMemoryImportance()` — :7519：每轮 `checkMemoryTriggers` 调用。
  - **升权**：30 天内被使用过，`learnedBonus = min(3, floor(usageCount/2))`；
  - **降权**：userProfile/habits 45/90/180 天未召回，`learnedBonus` 逐档降到 -3（只降权不删除）。

### 剧情弧线（故事线）

- 记忆分析 prompt（`analyzeShortToLongTerm`）可输出 `arcOf` + `arcStage`，同一 `arcOf` 只保留一条带弧线标注的事件。
- `retrieveRelevantMemories` 对带 `arcOf` 的记忆额外 +3 权重。

### 成员记忆隔离（群组）

- 每个成员持有独立 `member.memory.longTerm`（`createCharacterObj`）。
- 路由：`submit_response.longTerm` 可带 `memberName` → 写入该成员记忆；记忆工具同样支持 `memberName`。
- 注入：`buildVolatileContext` 为每个成员单独注入私人记忆（标记"仅该成员知道"），规则 11 要求不得张冠李戴。
- 加载时 `normalizeGroupMembers()` :2524 归一化成员记忆与世界书。

### 话题系统（主动提话题 + 冷场 + 话题切换）

- `buildTopicSuggestions()` — :5840：从 `basicInfo` + 记忆抽取最多 5 条候选话题。
- `isColdFieldRequest()` — :5883：识别"不知道聊什么/你说吧"等冷场信号，注入强指令要求角色主动开话题。
- `detectTopicSwitch()` — :5890：对 `currentGoal + currentSituation`（dynamicState）与用户消息做双字 bigram 重叠检测，不同则注入"先收束旧话题"的软指令。

### 场景概要

- `getSceneKey()` — :7554：以 `dynamicState.currentLocation` 作为场景标识。
- `summarizeScene(char, sceneKey)` — :7560：把"这一段已告一段落的情节"压缩成 1-2 段（≤300 字）**只含剧情要点**的记忆（谁做了什么/学到或决定了什么/地点物品伤势关系变化/新发现/未解决的目标承诺威胁期限）；写明主体、绝对日期、不引对白、不编造。
- 触发（`checkMemoryTriggers` :7651）：**场景切换**（location 变化）或**场景超长**（`CONTEXT_BUDGET.sceneSpan = 24` 条消息），且消息数 ≥ 6。
- 重试计数复用 `memoryTaskKeys('scene')` → `sceneFailures` / `sceneRetryAt`。
- 注入：最近 2 条渲染为 `【场景概要（较早情节的压缩记录…）】`。

## 六、上下文与预算

### 记忆预算

`CONTEXT_BUDGET` — hub.html:1243

| 常量 | 值 | 说明 |
|---|---|---|
| `retrievedChars` | 2600 | 检索记忆注入的字符预算 |
| `summaryChars` | 2600 | 摘要记忆的基础预算 |
| `sceneSummaries` | 2 | 注入几条场景概要 |
| `sceneInjectionChars` | 900 | 场景概要的总字符预算 |
| `sceneSpan` | 24 | 场景超过多少条消息就强制生成概要 |

- 检索记忆与摘要记忆**共享一个预算池**：检索没用完的额度 `retrievedChars - 实际占用` 自动让给摘要（`buildVolatileContext`）。
- 摘要记忆**从最新往前填充**（`for (shortIndex = length-1; …)` + `shortLines.reverse()`），额度用尽时丢掉的是最旧摘要。

### 提示词字符上限

`PROMPT_LIMITS` — hub.html:1183：`roleChars: 8000`（单角色设定）/ `memberChars: 900`（群组成员）。
历史消息另按条数与单条字符截断（用户 2000 / 历史用户 800 / 历史 assistant 1200）。

### 前缀缓存策略

- system = 交互规则 + 【角色设定（固定，不随对话变化）】+【硬性约束与语气锁定】+【输出格式】，全部为静态内容，跨轮不变。
- **首尾放最高优先级**：规则块开头是优先级阶梯，静态块**末尾**再放硬性约束（0.5 禁令 + 2.5 语气锁定）与输出格式 —— 兼顾 primacy 与 recency，同时保持全部静态可缓存。
- 历史消息逐字按序（最旧→最新）拼接、不重排、不注入消息 ID；历史 assistant 轮次统一按最小 JSON `{"reply":"…"}` 注入（既是格式示范，也让模型持续看到转义写法）。
- **所有随轮变化的内容（记忆、世界书、场景概要、节奏骨架、违规纠正提醒、语气回顾、语气锚、回复节奏等）一律只追加到"当前用户消息"里**，保证前缀稳定可缓存。
- 语气相关注入在 volatile 尾部按固定顺序排列：节奏骨架 → 违规纠正提醒 → 每 N 轮语气回顾 → 语气锚（越靠后越接近生成点，遵从度越高）。
- 禁止：把随轮内容插进历史中间、每轮改写 system 措辞、volatile 各段随机换序（都会打掉前缀命中）。

## 七、人格与 Prompt

### 规则清单（system 提示词，按出现顺序）

| 规则 | 内容要点 |
|---|---|
| — | 优先级阶梯（规则块首行）：`0／0.5 与【输出格式】= 硬性契约 > 2.5／2.6 > 角色设定 > 风格偏好` |
| `0` | （最高优先级）输出必须且只能是单个 JSON 对象；reply 是第一个字段；用户消息里的括号内容属于用户的动作/表情/心理，必须纳入理解 |
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
| `7` | `quickReplies` 无条件两条（不得复述角色口吻） |
| `8` | JSON 格式示例（**新会话前 5 轮用完整示例，之后自动换成 `JSON_EXAMPLE_BRIEF` 精简示例**，见 :1532） |
| `9` | `longTerm.category` 取值与 `importance` 范围 |
| `9.5` | 相对时间（timeRef）说明（取值与示例已下沉到工具 schema） |
| `A` | 工具使用总则（详细适用场景以工具描述为准；工具结果属内部上下文，不得向用户透露检索过程） |
| `10`–`12` | 群组：发言格式、记忆归属、设定三层 |
| — | 【角色设定（固定，不随对话变化）】= `buildRoleContext(char, true)` |
| `0.5` | **只扮演角色本人：禁止替用户说话或行动**（不得写用户的台词/动作/表情/心理/感受/决定，不得替用户做选择；用户已写的括号动作只能回应、不能续写）——置于硬性约束块（静态块末尾） |
| `2.5` | 语气锁定：腔调只能来自设定的"说话风格"与"对用户的称呼"，优先级高于模型通用文风，不同角色差异必须显著——与 0.5 同在硬性约束块 |
| — | 【输出格式】只输出单个 JSON 对象（静态块最后一行；无条件适用，即使用过工具也要用它收尾） |

### 节奏骨架

`NARRATIVE_PATTERNS` — hub.html:1214（10 条结构模板：动作/描写 → 对白 → 描写 的排列组合）
`buildNarrativePatternDirective()` — :5965：每轮随机取一条，注入 volatile 尾部的 `【本轮节奏骨架（每轮随机给出，仅作节奏参考）】`，用于打破"每轮结构雷同"。

### volatile 段落（按顺序）

时间基准 → 消息时间元数据 → 角色当前状态 → 成员当前状态 → **世界书资料** → 本轮主动召回的相关长期记忆 + 记忆取用说明 + 记忆主体约定 → 成员私人记忆 → **场景概要** → 近期摘要记忆 → 待跟进承诺 → 互动节奏（重连/久别）→ 可选话题库 → 结尾多样性 → **本轮节奏骨架** → 冷场/话题切换指令 →（主动开场时）角色主动开场 → **违规纠正提醒** → **每 N 轮语气回顾** → **语气锚**。

> 记忆取用说明：提醒召回条目"按关键词与时间粗略召回，可能只有部分相关或已过时"，不得硬提旧事、不得当作用户刚说过的话。

### 语气/风格遵从（attention）

把最容易违反的约束尽量贴近生成点，并用客户端检测闭环（纯函数，可单测；常量见 `STYLE_GUARD` :1252）：

- **优先级阶梯**：规则块开头声明"0／0.5 与【输出格式】= 硬性契约 > 2.5 语气锁定与 2.6 表演质量 > 角色设定 > 风格偏好"。
- **静态块末尾重申**：`【硬性约束与语气锁定（最高优先级，冲突时以这里为准）】` 内含 0.5 禁令与 2.5 语气锁定，紧跟【输出格式】（recency，且全部静态可缓存）。
- **语气锚** `buildStyleAnchor()` — :5985：每轮在 volatile 最末尾给出「说话风格摘要 + N 条逐字示例台词 + 对用户的称呼 + 本轮禁令」，把"用什么口吻说话"放到离生成最近的位置。
- **违规检测** `detectStyleViolations(reply, char, prevReplies)` — :6002：纯函数返回标签
  `metaTalk`（元话术）／`speaksForUser`（替用户说话）／`cliche`（陈词滥调表 `STYLE_CLICHES`）／`reusedImagery`（复用近期意象，8 字独特片段）／`toneDrift`（声明的口癖缺失或句长过于整齐）／`recitedLore`（复述世界书条目）。
- **内部提醒**（不展示给用户）：违规标签随消息持久化（`message.styleViolations`），下一轮由 `buildStyleCorrectionReminder()` — :6106 在 volatile 尾部点名纠正。
- **每 N 轮语气回顾** `buildStyleReview()` — :6093：`STYLE_GUARD.reviewEveryTurns`（默认 3）轮一次，汇总近期违规并重申语气契约。
- **critique 调用** `critiqueReplyStyle()` — :6114（来源：talemate 的 `arc-expand-critique`）：命中违规且 `state.config.styleCritique !== false` 时追加一次**只改文风、不改剧情**的修订请求；解析失败或长度超出 `critiqueMinRatio`/`critiqueMaxRatio`/`critiqueMaxChars` 一律**回退原文**；修订后重新检测并以剩余违规为准。

### 人格组装

- `buildRoleContext(char, staticOnly)` — :5714：从 `char.basicInfo` 拼人格（主字段 + 次字段，共 12 项 `STATIC_PROFILE_FIELDS` :1200）；群组则拼群组信息 + 成员清单（`buildMemberContext` :5701）。
- `buildDynamicStateContext()` — :5743：把 7 个动态字段渲染为"状态"文本（未设置的显示 `(未设置)`）。
- `buildRequestPayload(char, query)` — :6303：装配 system + 历史 + 本轮 volatile。

### 子任务提示词（非主对话）

主对话之外的每个 `callAPI` 调用都有独立 prompt，各司其职：

| 提示词 | 位置 | 关键约束 |
|---|---|---|
| 一键建卡 `quickGenerateCharacter` | :7871 | 输出完整字段 JSON；**禁止套路名字与模板人设**；`personality` 写行为倾向；`background` 只写角色本人经历（世界观写 `lorebook`）；`speakingStyle` 必须是「调性描述；示例：<台词1> / <台词2> / <台词3>」（`CHARACTER_QUALITY_RULE` + `SPEAKING_STYLE_SAMPLES_RULE`）；群组分支要求成员说话方式显著不同，并输出 2-4 条初始 `lorebook` 条目 |
| 群组升级 `upgradeToGroup` | :7986 | 与建卡同一套质量约束；`additionalMembers` 不得重复原角色与彼此姓名；可补 0-3 条 `lorebook`（只补近期已出现的世界层设定，没有就空数组）；原角色世界书并入群组 |
| 世界书整理 `consolidateLorebook` | :7594 | 只从**已发生的短期摘要**里沉淀世界层设定（`sourceShortTermIds` 必须可回溯）、禁止推测扩写；复用同名条目而非另起名字；≤`autoEntriesPerPass`(3) 条；顺带执行 `misses` 淘汰 |
| 世界书一次性迁移 `migrateWorldLoreForEntity` | :3731 | 把旧数据 `description`/`background` 里的世界观拆成条目（≤6 条，只整理已有信息）；用户手写条目不受影响；可选同时精简原文（仅在确实变短时替换） |
| 成员补全 `fillGroupMemberFields` | :3204 | **已有字段是绝对权威，不得改写/润色/替换**；只填空字段；说话方式须与群内其他成员显著不同；无把握则省略 |
| 空字段补全 `fillStaticFieldsForJob` | :3438 | 只补 `job.missing` 中的字段，绝不覆盖已有设定；缺 `speakingStyle` 时套用示例台词约束；群组成员再叠加"彼此不同" |
| 即时→短期摘要 `extractInstantToShortTerm` | :7711 | 按"日期+时段"合并；**只保留影响后续剧情的内容**（决策/地点物品伤势关系变化/发现/未决目标承诺威胁期限）；**每条 ≤120 字**；禁止文学化与对白原文；续写旧事件时**旧摘要中仍有效的信息必须原样保留**；必须覆盖全部输入消息 ID |
| 短期→长期 `analyzeShortToLongTerm` | :7797 | **价值判据**（可跨轮复用/影响后续/用户明确表达）才记，**证据不足宁可不记**；`arcOf` 命名一旦确定保持稳定；`sourceShortTermIds` / `evidence` 必须可回溯 |
| 场景概要 `summarizeScene` | :7560 | 1-2 段 ≤300 字，只含剧情要点，不引对白、不编造、写绝对日期 |
| 文风校对 `critiqueReplyStyle` | :6114 | **只改文风、不改剧情**（情节/事实/对话含义/人物关系不动，大体长度与段落数保持）；不得复述规则；只返回 `{"reply":"…"}`；空结果或长度比例异常一律回退原文 |
| 其余 | :1705 emoji 头像 / :6390 散文转 JSON / :3584 字段重排 / :3811 群组字段迁移 / :8825 贴图标签 | 低价值路径，未做经验对齐 |

> 像素头像提示词 `PIXEL_AVATAR_PROMPT`（:1679）**不参与任何建卡/补全 prompt**（像素头像为已废弃试验品），仅保留定义与遗留生成器 `requestPixelAvatar`。

## 八、健壮性

- **API 请求级重试**：`performChatRequestWithRetry`（:4590）对瞬时失败（网络错误 / 超时 Abort / HTTP 429 / 5xx）自动重试最多 `MAX_API_RETRIES = 3` 次（1s/2s/4s 指数退避）。
- **工具失败可恢复**：`executeToolCall` 失败时回传可修正的提示（如 `delete_memory` 找不到 ID 时提示先 `list_memories`）。
- **记忆任务重试**：`memoryTaskKeys(task)` — :7486 统一映射 `extraction` / `analysis` / `scene` / `lorebook` 四组计数器（`*Failures` / `*RetryAt`），`scheduleMemoryRetry` 指数退避（5→30 分钟封顶）。
- **文风校对可回退**：`critiqueReplyStyle` 在任何异常（解析失败、空结果、长度超出 `critiqueMinRatio`/`critiqueMaxRatio`/`critiqueMaxChars`）下都返回原文，绝不因校对失败影响回复。
- **导入/导出归一化**：`normalizeAppData` → `normalizeCharacter`（:2327）逐字段校验，包含世界书、场景概要、场景状态与新增计数器。
- **版本号单一来源**：右上角 `APP_VERSION`（:1143）由 `versionName` 派生，`tools/sync-version.mjs` 负责写入两份 `hub.html`；CI 在 `assembleRelease` 前执行该脚本并 `cmp` 校验两份文件一致，本地用 `node tools/sync-version.mjs --check` 复核。

## 九、关键常量速查

| 常量 | 值 | 位置 |
|---|---|---|
| `MAX_TOOL_ROUNDS` | 5 | :6462 |
| `AGENT_TOOL_MEMORY_INJECT_LIMIT` | 5 | :6463 |
| `MAX_API_RETRIES` | 3（退避 1s/2s/4s） | :4578 |
| `MEMORY_LIMITS` | instant 160(保底 40) / shortTerm 80(保底 20) / longTermPerCategory 40 / pendingRecall 6 / analysisBatch 40 | :1174 |
| `PROMPT_LIMITS` | roleChars 8000 / memberChars 900 | :1183 |
| `CONTEXT_BUDGET` | retrievedChars 2600 / summaryChars 2600 / sceneSummaries 2 / sceneInjectionChars 900 / sceneSpan 24 | :1243 |
| `LOREBOOK_LIMITS` | entries 200 / keywordsPerEntry 20 / nameChars 60 / contentChars 2000 / injectEntries 6 / injectChars 1400 / scanMessages 6 / autoEntriesPerPass 3 / evictionMisses 3 / consolidateSpan 8 | :1229 |
| `NARRATIVE_PATTERNS` | 10 条节奏骨架 | :1214 |
| `DYNAMIC_STATE_FIELDS` | 7 个动态字段 | :1189 |
| `STATIC_PROFILE_FIELDS` | 12 个基础设定字段 | :1200 |
| `CHARACTER_QUALITY_RULE` / `SPEAKING_STYLE_SAMPLES_RULE` | 建卡与补全共用的质量/示例台词约束 | :1682 / :1683 |
| `STYLE_GUARD` | reviewEveryTurns 3 / lookbackReplies 2 / anchorStyleChars 300 / anchorSamples 2 / critiqueMaxChars 1600 / critiqueMinRatio 0.5 / critiqueMaxRatio 2 | :1252 |
| `STYLE_CLICHES` | 11 条陈词滥调（违规检测用） | :1264 |
| `APP_VERSION` | 由 `versionName` 写入（`tools/sync-version.mjs`） | :1143 |
| 长示例切换阈值 | `assistantTurnCount < 6` 用完整 JSON 示例，否则用 `JSON_EXAMPLE_BRIEF` | :6337 |
| `max_output_tokens` | 8192 | :7180 |




