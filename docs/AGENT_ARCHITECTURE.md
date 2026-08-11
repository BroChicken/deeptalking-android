# Agent 架构文档（DeepTalking / hub_1.html）

> 本文档记录 `hub_1.html` 中 agent 功能的定义与构造，所有代码锚点均指向当前文件行号，重构时请同步更新。

## 一、Agent 是什么

本项目的"agent"不是一个类或对象，而是一套 **LLM + 人格 + 记忆 + 工具 + 运行循环** 的组合。核心思想：

- 模型不再只输出一段文字，而是可以在一次回复中**主动调用工具 → 拿到执行结果 → 继续推理**，循环往复，直到给出最终答案。
- 这种"思考（reasoning）→ 行动（tool call）→ 观察（tool output）→ 收尾（structured reply）"的循环，就是 agent 的本质。

一句话概括：

> **人格 + 记忆** 提供身份与状态 → **`while` 循环**让模型反复"想→调工具→看结果" → **`submit_response`** 保证最终以结构化 JSON 收尾并落盘记忆。

## 二、总体数据流

```
用户输入
   │
   ▼
buildRequestPayload(hub_1.html:3452)
   ├─ 人格注入 buildRoleContext(:3280)
   ├─ 相关记忆注入 buildVolatileContext(:3398) / retrieveRelevantMemories(:2825)
   └─ 消息历史（DeepSeek 前缀缓存友好截断 :3473）
   │
   ▼
while(true) 循环 (:2159)
   │
   ├─ performChatRequest(:2374)  → 流式 SSE，解析 function_call / reasoning 事件
   ├─ 有工具调用？→ executeToolCall(:3631) 执行一个 → 结果回传 toolState.items → continue
   ├─ 只调 ask_user？→ 直接把问题当回复（特判 :2173）
   ├─ 有合法 JSON 正文？→ break
   └─ 否则 → 切换阶段二，强制 submit_response (:3857-3865)
   │
   ▼
applyMemoryUpdate(:3242) 落盘 shortTerm / longTerm / dynamicState / promiseUpdates
   │
   ▼
渲染用户可见回复 + quickReplies
```

## 三、五大部件

### 1. API 载体（Responses API）

- `buildResponsesRequestBody()` — hub_1.html:3838
- 请求体字段：`model`、`input`（首条 system 提升为 `instructions`）、`stream`、`temperature`、`max_output_tokens=8192`、`tools`、`tool_choice`、`reasoning.effort`。
- 端点：`getResponsesEndpoint()` — hub_1.html:3573（把 base URL 归一化为 `/responses`）。
- 阶段二（submit）时强制 `tool_choice = { type: 'function', name: 'submit_response' }`，且 `reasoning = { effort: 'none' }`（DeepSeek 仅支持 effort=none 时锁定工具）。

### 2. 工具清单（agent 的能力）

`buildAgentTools()` — hub_1.html:3587，通过 `isAgentToolsEnabled()`（:3583）控制开关（当前即启用）。

| 工具 | 作用 | 关键实现 |
|---|---|---|
| `get_current_time` | 获取当前时间/时区 | executeToolCall(:3642) |
| `search_memory` | 按关键词检索长期记忆 | retrieveRelevantMemories(:3651) |
| `list_memories` | 按分类盘点记忆清单（不含详情） | executeToolCall(:3671) |
| `delete_memory` | 按 ID 删除记忆 | executeToolCall(:3699) |
| `set_reminder` | 记录待办/约定（promises） | executeToolCall(:3716) |
| `ask_user` | 向用户提出澄清问题 | executeToolCall(:3756) |
| `web_search` | 联网搜索（平台内置类型） | buildAgentTools(:3627) |

每个工具就是一段 JSON Schema 描述（name + description + parameters），模型据此决定何时调用。

### 3. 工具实现（agent 的手）

`executeToolCall(call, char)` — hub_1.html:3631
- 模型只"说"要调用，真正干活的是这段代码：读写 `char.memory.longTerm`、返回时间、记约定等。
- 统一返回 JSON 字符串，异常安全（`try/catch` 兜底，失败返回 `{ ok: false, reason }`）。

### 4. Agent 循环（大脑）

`sendMessage` 内的 `while(true)` — hub_1.html:2159-2247：

- **工具调用**：模型返回 `function_call` 事件时，逐个执行。关键限制：DeepSeek thinking 模式不支持并行 function_call 回传（会 400），所以**每轮仅回传一个调用**，其余丢弃，模型基于结果下一轮再决定（:2192-2195）。
- **上限**：`MAX_TOOL_ROUNDS = 5`（:3580），超出直接进入收尾阶段。
- **reasoning 回传**：thinking 模式的 `reasoning_text` 必须先回传（:2197），否则上下文断裂。
- **ask_user 特判**：本轮只调 `ask_user` 时，直接把问题包装成结构化回复输出，不进入工具循环（:2173-2182）。
- **两阶段**：阶段一 `phase='auto'`（工具自由）；拿不到合法 JSON 时切阶段二 `phase='submit'`（强制 `submit_response`，:2207-2221），必要时重试一次（`attemptsLeft=2`）。

### 5. 结构化收尾协议（agent 的手续）

`buildSubmitResponseTool()` — hub_1.html:3796；`extractSubmitResponse()` — :3821。

每轮必须且只能调用一次 `submit_response`，用 JSON Schema（`strict: true`）强制校验，一次性输出：

| 字段 | 说明 |
|---|---|
| `reply` | 用户可见回复正文（Markdown，动作/表情/心理活动用括号，不超过一半） |
| `quickReplies` | 恰好两条用户下一句可直接发送的短句 |
| `shortTerm` | 本轮事件流程摘要 |
| `longTerm` | 未来仍有价值的稳定事实（category∈userProfile/relationship/events/promises/habits） |
| `dynamicState` | 角色动态状态（5 字段，见下） |
| `memberDynamicState` | 群组成员动态状态（仅群组） |
| `promiseUpdates` | 用户明确完成/取消的承诺（resolved/cancelled） |
| `recall` | 主动召回记忆的请求 |

这是"回复 + 写记忆"合一的契约，保证每轮回复结构合法。

## 四、记忆系统

### 三层结构

`MEMORY_LIMITS` — hub_1.html:979

| 层 | 内容 | 上限 |
|---|---|---|
| `instant` | 最近原始消息 | 80 条（截断保底 20） |
| `shortTerm` | 事件流程摘要 | 40 条（截断保底 10） |
| `longTerm` | 分类长期记忆 | 每类 40 条 |

### longTerm 五类

`userProfile`（用户信息）、`relationship`（关系）、`events`（共同事件）、`promises`（约定）、`habits`（习惯）。

- subject 仅限 `user` / `relationship` / `world`（`MEMORY_SUBJECTS` :994）。
- `isValidAutomaticMemory()` — :2885 校验来源引用与证据逐字摘录，防止模型编造记忆。

### 相关度检索

`retrieveRelevantMemories(char, query)` — hub_1.html:2825
- 评分 = 有效重要度×0.45 + 关键词命中加分 + 时效加分（90 天内递增）。
- 取 top 8，配合 `pendingRecall`（待召回记忆，强制置顶）。

### 注入与衰减

- `buildVolatileContext()` — :3398：把最相关的记忆注入本轮 prompt（agent 模式仅注入 3 条，`AGENT_TOOL_MEMORY_INJECT_LIMIT=3`，其余提示模型用 `search_memory` 检索）。
- `applyMemoryDecay()` — :1282：按时间衰减 events/promises 的重要性与状态。

### 动态状态（动态字段）

`DYNAMIC_STATE_FIELDS` — hub_1.html:996：`currentSituation`（处境）/ `currentLocation`（位置）/ `currentMood`（情绪）/ `currentFocus`（关注）/ `recentDevelopment`（进展）。每个字段的 value 必须带 `sourceMessageIds` + `evidence` 溯源。

## 五、进阶记忆子系统（2026-08 新增）

### 记忆冲突自动裁决

- `memoriesSemanticallyDiffer(a, b)`：把文本拆词/拆字比较重叠度，重叠 < 0.35 视为语义冲突。
- `upsertLongTermMemory`：同 key 冲突时**不再静默覆盖**，写入 `existing.conflicts`（保留双方 value/evidence/sourceMessageIds/时间）并标记 `conflictedAt`；语义一致才正常覆盖并清除冲突标记。
- `resolveMemoryConflicts()`：在 `trimCharacterMemory` 末尾自动合并。胜出规则：**更新时间晚 > 证据更长 > 保持原主值**，合并后清空冲突标记。用户全程无感。

### 记忆重要性自学习

- `usageCount` / `lastUsageAt`：记忆被 `handleRecall` 召回或 `search_memory` 命中时累计使用量。
- `selfLearnMemoryImportance()`：每轮 `checkMemoryTriggers` 调用——
  - **升权**：30 天内被使用过，按累计使用量折算 `learnedBonus = min(3, floor(usageCount/2))`；
  - **降权**：userProfile/habits 45/90/180 天未召回，`learnedBonus` 逐档降到 -3（只降权不删除）。
- `computeEffectiveImportance` = `(importance + learnedBonus) × decay`（0-10 封顶）。

### 剧情弧线（故事线）

- 记忆分析 prompt（`analyzeShortToLongTerm`）可输出 `arcOf`（持续情节线名称）+ `arcStage`（起始/发展/转折/现状），同一 arcOf 只保留一条带弧线标注的事件。
- `retrieveRelevantMemories` 对带 `arcOf` 的记忆额外 +3 权重，角色更容易自然提起旧事。

### 成员记忆隔离（群组）

- 每个成员持有独立 `member.memory.longTerm`（创建时自带，`createCharacterObj`）。
- 路由：`submit_response.longTerm` 可带 `memberName` → 写入该成员记忆；记忆工具（`search_memory`/`list_memories`/`delete_memory`/`set_reminder`）可带 `memberName` → 读写该成员记忆；不带则操作群组共享记忆。
- 注入：`buildVolatileContext` 为每个成员单独注入其私人记忆（标记"仅该成员知道"），规则 11 要求模型不得张冠李戴。
- `consumePromiseUpdates` 同时检索共享与所有成员的 promises。
- 加载时 `normalizeGroupMembers` 归一化成员记忆，导出/导入不丢失。

### 话题系统（主动提话题 + 冷场 + 话题切换）

- `buildTopicSuggestions(char)`：从 `basicInfo`（人格/目标/背景/世界观等 12 个字段）+ 记忆（userProfile/habits/events）抽取最多 5 条候选话题，注入 volatile context。
- `isColdFieldRequest(text)`：正则识别"不知道聊什么/你说吧/好无聊"等冷场信号；命中时注入强指令——**角色必须主动开话题，不得反问"你想聊什么"**。
- `detectTopicSwitch(char, query)`：对 `currentFocus`+`currentSituation` 与用户消息做**双字 bigram 重叠**检测；不同则注入"先温和收束旧话题再进入新话题"的软指令。

## 六、健壮性

- **API 请求级重试**：`performChatRequestWithRetry` 对瞬时失败（网络错误 / 超时 Abort / HTTP 429 / 5xx）自动重试最多 3 次（1s/2s/4s 指数退避），气泡显示"连接中断，正在重试…"；业务 4xx 不重试，仍失败走原 `alert('回复失败：…')`。`isTransientApiError` 判定，`performChatRequest` 抛错时附带 `status`/`network`/`aborted` 标记。
- **工具失败可恢复**：`executeToolCall` 失败信息回传可修正的提示（如 `delete_memory` 找不到 ID 时提示先 `list_memories`）；`web_search` 失败事件（`response.web_search_call.failed` / `output_item.done` + `status:'failed'`）已在 SSE 处理器捕获，角色自行应对。

## 七、人格与 Prompt

- `buildRoleContext(char)` — hub_1.html:3280：从 `char.basicInfo` 拼人格（主字段 + 次字段），群组则拼成员清单。
- `buildRequestPayload(char, query)` — :3452：system prompt = 角色设定 + 交互规则（JSON 格式铁律、dynamicState 溯源规则、记忆写入规则、工具使用说明、群组格式）。
- DeepSeek 前缀缓存优化 — :3473：固定 system 在前，历史消息原样按序（最旧→最新）拼接，volatile context 只追加到当前用户消息，保证缓存前缀每轮不变。

## 八、关键常量速查

| 常量 | 值 | 位置 |
|---|---|---|
| `MAX_TOOL_ROUNDS` | 5 | :3580 |
| `MAX_API_RETRIES` | 3（退避 1s/2s/4s） | 重试包装器 |
| `AGENT_TOOL_MEMORY_INJECT_LIMIT` | 3 | :3581 |
| `MEMORY_LIMITS` | instant:80 / shortTerm:40 / longTermPerCategory:40 | :979 |
| `PROMPT_LIMITS` | roleChars:3600 / memoryChars:2400 / shortTermChars:1400 / recentChars:5600 | :988 |
| `DYNAMIC_STATE_FIELDS` | 5 个动态字段 | :996 |
| `max_output_tokens` | 8192 | :3845 |

> 注：文中行号随 `hub_1.html` 演进会偏移，仅作参考；以函数名检索为准。
