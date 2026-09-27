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
buildRequestPayload(hub.html:5710)
   ├─ 人格注入 buildRoleContext(:5318)（staticOnly=true → 进可缓存前缀）
   ├─ 世界书匹配 buildLorebookContext(:1307)（关键词命中才注入）
   ├─ 记忆注入 buildVolatileContext(:5574) / retrieveRelevantMemories(:4615)
   └─ 消息历史（DeepSeek 前缀缓存友好截断，:5860 起）
   │
   ▼
while(true) 循环 (sendMessage 内 :3626)
   │
   ├─ performChatRequest(:3873)  → 流式 SSE，解析 function_call / reasoning 事件
   ├─ 有工具调用？→ executeToolCall(:6157) 执行一个 → 结果回传 toolState.items → continue
   ├─ 只调 ask_user？→ 直接把问题当回复（特判）
   ├─ 有合法 JSON 正文？→ break
   └─ 否则 → 切换阶段二，强制 submit_response（必要时重试一次）
   │
   ▼
applyMemoryUpdate(:5256) 落盘 shortTerm / longTerm / dynamicState / promiseUpdates
   │
   ▼
checkMemoryTriggers(:6962) 异步整理短期记忆 / 长期记忆 / 场景概要
   │
   ▼
渲染用户可见回复 + quickReplies
```

## 三、五大部件

### 1. API 载体（Responses API）

- `buildResponsesRequestBody()` — hub.html:6544
- 请求体字段：`model`、`input`（首条 system 提升为 `instructions`）、`stream`、`temperature`、`max_output_tokens=8192`、`tools`、`tool_choice`、`reasoning.effort`。
- 端点：`getResponsesEndpoint()` — hub.html:5852（把 base URL 归一化为 `/responses`）。
- 阶段二（submit）时强制 `tool_choice = { type: 'function', name: 'submit_response' }`，且 `reasoning = { effort: 'none' }`（DeepSeek 仅支持 effort=none 时锁定工具）。

### 2. 工具清单（agent 的能力）

`buildAgentTools()` — hub.html:5871，通过 `isAgentToolsEnabled()`（:5867）控制开关（当前即启用）。

| 工具 | 作用 | 定义位置 |
|---|---|---|
| `get_current_time` | 获取当前时间/时区 | :5876 |
| `search_memory` | 按关键词检索长期记忆（可指定成员私人记忆） | :5882 |
| `list_memories` | 按分类盘点记忆清单（不含详情） | :5888 |
| `delete_memory` | 按 ID 删除记忆 | :5894 |
| `set_reminder` | 记录待办/约定（promises，须用户发起 + 原话证据） | :5900 |
| `ask_user` | 向用户提出澄清问题 | :5906 |
| `update_character_field` | 精准修改基础设定字段（说话风格、称呼等） | :5912 |
| `web_fetch` | 抓网页正文 / 图片 / B站视频（url 或 keyword） | :5918 |
| `update_memory` | 按 ID 修正记忆（用户纠正时用） | :5924 |
| `send_sticker` | 发表情包（仅当角色有贴图时注册） | :5949 |
| `web_search` | 联网搜索（平台内置类型） | 平台内置 |

每个工具就是一段 JSON Schema 描述（name + description + parameters），模型据此决定何时调用。
**工具描述是"何时用哪个工具"的权威依据**，system 提示词的规则 A 只留一句导引。

### 3. 工具实现（agent 的手）

`executeToolCall(call, char)` — hub.html:6157
- 模型只"说"要调用，真正干活的是这段代码：读写 `char.memory.longTerm`、返回时间、记约定等。
- 统一返回 JSON 字符串，异常安全（`try/catch` 兜底，失败返回 `{ ok: false, reason }`）。

### 4. Agent 循环（大脑）

`sendMessage(options)` 内的 `while(true)` — hub.html:3626：

- **工具调用**：模型返回 `function_call` 事件时，逐个执行。关键限制：DeepSeek thinking 模式不支持并行 function_call 回传（会 400），所以**每轮仅回传一个调用**，其余丢弃。
- **上限**：`MAX_TOOL_ROUNDS = 5`（:5864），超出直接进入收尾阶段。
- **reasoning 回传**：thinking 模式的 `reasoning_text` 必须先回传，否则上下文断裂。
- **ask_user 特判**：本轮只调 `ask_user` 时，直接把问题包装成结构化回复输出，不进入工具循环。
- **两阶段**：阶段一 `phase='auto'`（工具自由）；拿不到合法 JSON 时切阶段二 `phase='submit'`（强制 `submit_response`），必要时重试一次。

### 5. 结构化收尾协议（agent 的手续）

`buildSubmitResponseTool()` — hub.html:6490；`extractSubmitResponse()` — :6520。

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

## 四、记忆系统

### 三层结构 + 场景层

`MEMORY_LIMITS` — hub.html:1172

| 层 | 内容 | 上限 |
|---|---|---|
| `instant` | 最近原始消息 | 160 条（截断保底 40） |
| `shortTerm` | 事件流程摘要 | 80 条（截断保底 20） |
| `longTerm` | 分类长期记忆 | 每类 40 条 |
| `scenes` | 场景概要 | 8 条，注入最近 2 条 |

### longTerm 五类

`userProfile`（用户信息）、`relationship`（关系）、`events`（共同事件）、`promises`（约定）、`habits`（习惯）。

- subject 仅限 `user` / `relationship` / `world` / `character`（`MEMORY_SUBJECTS` :1185；`legacy` 仅作历史兼容，不再写入）。
- `isValidAutomaticMemory()` 校验来源引用与证据逐字摘录，防止模型编造记忆。

### 相关度检索

`retrieveRelevantMemories(char, query, memoryStore)` — hub.html:4615
- 评分 = 有效重要度×0.45 + 关键词命中加分 + 时效加分（90 天内递增）+ 剧情弧线 `arcOf` +3。
- 取 top 12，配合 `pendingRecall`（待召回记忆，强制置顶）。
- 可传入 `memoryStore` 以检索群组成员的私人记忆。

### 注入与衰减

- `buildVolatileContext()` — :5574：把最相关的记忆注入本轮 prompt（agent 模式只注入 `AGENT_TOOL_MEMORY_INJECT_LIMIT = 5` 条，其余提示模型用 `search_memory` 检索）。
- `applyMemoryDecay()` — :1932：按时间衰减 events/promises 的重要性与状态。
- `computeEffectiveImportance()` — :1791：`(importance + learnedBonus) × decay`（0-10 封顶）。

### 动态状态（7 字段）

`DYNAMIC_STATE_FIELDS` — hub.html:1187：`currentSituation` / `currentLocation` / `currentMood` / `currentOccupation` / `currentGoal` / `currentRelationship` / `currentImportantOthers`。
每个字段的 value 必须带 `sourceMessageIds` + `evidence` 溯源；群组整体只维护 `currentSituation` + `currentLocation`（`GROUP_SHARED_DYNAMIC_FIELDS` :1197）。

### 世界书（lorebook）

`char.lorebook = [{ id, name, keywords[], content, enabled, order }]`

- 与"角色设定/长期记忆"的区别：**用户手写、关键词命中才注入、不占常驻预算**。
- 归一化 `normalizeLorebook()` — :1244（上限见 `LOREBOOK_LIMITS` :1225）；角色卡弹窗第三个 tab 编辑（`renderLorebookEditor` :2827）。
- 命中 `matchLorebookEntries()` — :1281：对**本轮用户输入 + 最近 6 条消息**做小写关键词包含匹配；按 `order` → `name` 排序；最多 6 条。
- 注入 `buildLorebookContext()` — :1307：渲染为 `【世界书资料…】`，总字符受 `injectChars` 限制，插入 volatile 尾部（不破坏前缀缓存）。
- 群组实体与成员的世界书都会参与命中（`collectLorebookEntries()` :1262 去重合并）。

## 五、进阶记忆子系统

### 记忆冲突自动裁决

- `memoriesSemanticallyDiffer(a, b)`：把文本拆词/拆字比较重叠度，重叠 < 0.35 视为语义冲突。
- `upsertLongTermMemory`：同 key 冲突时**不再静默覆盖**，写入 `existing.conflicts`（保留双方 value/evidence/sourceMessageIds/时间）并标记 `conflictedAt`。
- `resolveMemoryConflicts()` — :5221：在 `trimCharacterMemory` 末尾自动合并。胜出规则：**更新时间晚 > 证据更长 > 保持原主值**。

### 记忆重要性自学习

- `usageCount` / `lastUsageAt`：记忆被 `handleRecall` 召回或 `search_memory` 命中时累计使用量。
- `selfLearnMemoryImportance()` — :6889：每轮 `checkMemoryTriggers` 调用。
  - **升权**：30 天内被使用过，`learnedBonus = min(3, floor(usageCount/2))`；
  - **降权**：userProfile/habits 45/90/180 天未召回，`learnedBonus` 逐档降到 -3（只降权不删除）。

### 剧情弧线（故事线）

- 记忆分析 prompt（`analyzeShortToLongTerm`）可输出 `arcOf` + `arcStage`，同一 `arcOf` 只保留一条带弧线标注的事件。
- `retrieveRelevantMemories` 对带 `arcOf` 的记忆额外 +3 权重。

### 成员记忆隔离（群组）

- 每个成员持有独立 `member.memory.longTerm`（`createCharacterObj`）。
- 路由：`submit_response.longTerm` 可带 `memberName` → 写入该成员记忆；记忆工具同样支持 `memberName`。
- 注入：`buildVolatileContext` 为每个成员单独注入私人记忆（标记"仅该成员知道"），规则 11 要求不得张冠李戴。
- 加载时 `normalizeGroupMembers()` :2310 归一化成员记忆与世界书。

### 话题系统（主动提话题 + 冷场 + 话题切换）

- `buildTopicSuggestions()` — :5444：从 `basicInfo` + 记忆抽取最多 5 条候选话题。
- `isColdFieldRequest()` — :5487：识别"不知道聊什么/你说吧"等冷场信号，注入强指令要求角色主动开话题。
- `detectTopicSwitch()` — :5494：对 `currentGoal + currentSituation`（dynamicState）与用户消息做双字 bigram 重叠检测，不同则注入"先收束旧话题"的软指令。

### 场景概要

- `getSceneKey()` — :6924：以 `dynamicState.currentLocation` 作为场景标识。
- `summarizeScene(char, sceneKey)` — :6930：把"这一段已告一段落的情节"压缩成 1-2 段（≤300 字）**只含剧情要点**的记忆（谁做了什么/学到或决定了什么/地点物品伤势关系变化/新发现/未解决的目标承诺威胁期限）；写明主体、绝对日期、不引对白、不编造。
- 触发（`checkMemoryTriggers` :6962）：**场景切换**（location 变化）或**场景超长**（`CONTEXT_BUDGET.sceneSpan = 24` 条消息），且消息数 ≥ 6。
- 重试计数复用 `memoryTaskKeys('scene')` → `sceneFailures` / `sceneRetryAt`。
- 注入：最近 2 条渲染为 `【场景概要（较早情节的压缩记录…）】`。

## 六、上下文与预算

### 记忆预算

`CONTEXT_BUDGET` — hub.html:1236

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

`PROMPT_LIMITS` — hub.html:1181：`roleChars: 8000`（单角色设定）/ `memberChars: 900`（群组成员）。
历史消息另按条数与单条字符截断（用户 2000 / 历史用户 800 / 历史 assistant 1200）。

### 前缀缓存策略

- system = 交互规则 + 【角色设定（固定，不随对话变化）】+【输出格式】，全部为静态内容，跨轮不变。
- 历史消息逐字按序（最旧→最新）拼接、不重排、不注入消息 ID；历史 assistant 轮次统一按最小 JSON `{"reply":"…"}` 注入（既是格式示范，也让模型持续看到转义写法）。
- **所有随轮变化的内容（记忆、世界书、场景概要、节奏骨架、回复节奏等）一律只追加到"当前用户消息"里**，保证前缀稳定可缓存。

## 七、人格与 Prompt

### 规则清单（system 提示词，按出现顺序）

| 规则 | 内容要点 |
|---|---|
| `0` | （最高优先级）输出必须且只能是单个 JSON 对象；reply 是第一个字段；用户消息里的括号内容属于用户的动作/表情/心理，必须纳入理解 |
| `0.5` | **只扮演角色本人：禁止替用户说话或行动**（不得写用户的台词/动作/表情/心理/感受/决定，不得替用户做选择；用户已写的括号动作只能回应、不能续写） |
| `1` | 保持角色身份连续；先用已提供的记忆；"系统提供的本轮上下文"不是用户的话 |
| `2` | reply 的字数（120–250 字手感锚点）、分段、动作穿插（每 1–2 句一次，不超过正文 1/3）、去重与结尾多样性 |
| `2.5` | 语气锁定：腔调只能来自设定的"说话风格"与"对用户的称呼"，优先级高于模型通用文风，不同角色差异必须显著 |
| `2.6` | **表演质量（真人感）**：show-don't-tell、句长节奏起伏、对白优先、回避陈词滥调与复用比喻、每轮只推进一件事、不强行升华、人物性格稳定 |
| `3` | 人格/背景锁定 + `dynamicState` 更新与溯源规则 + 时间写法（绝对日期 + 十个时段） |
| `3.5` | 谨慎修改基础设定：用户直接要求 → `update_character_field`；无要求时仅"决定性不可逆转折"才改 |
| `3.6` | 状态/设定字段的 value 写法（只写内容本身，不加主语，解释进 evidence） |
| `3.7` | 记忆字段必须写明主体（禁止"我/你/TA"这类代词） |
| `3.8` | 时间一律写绝对日期，相对说法填 `timeRef` |
| `3.9` | 用户提"说话方式类"要求时，本轮 reply 直接演出、禁止元话术 |
| `4`–`6` | 只记录未来有价值的信息；`subject` 取值约束；promises 的承诺方/受约方与状态流转 |
| `7` | `quickReplies` 无条件两条（不得复述角色口吻） |
| `8` | JSON 格式示例（**新会话前 5 轮用完整示例，之后自动换成 `JSON_EXAMPLE_BRIEF` 精简示例**，见 :1326） |
| `9` | `longTerm.category` 取值与 `importance` 范围 |
| `9.5` | 相对时间（timeRef）说明（取值与示例已下沉到工具 schema） |
| `A` | 工具使用总则（详细适用场景以工具描述为准；工具结果属内部上下文，不得向用户透露检索过程） |
| `10`–`12` | 群组：发言格式、记忆归属、设定三层 |
| — | 【角色设定（固定，不随对话变化）】= `buildRoleContext(char, true)` |
| — | 【输出格式】只输出单个 JSON 对象 |

### 节奏骨架

`NARRATIVE_PATTERNS` — hub.html:1212（10 条结构模板：动作/描写 → 对白 → 描写 的排列组合）
`buildNarrativePatternDirective()` — :5569：每轮随机取一条，注入 volatile 尾部的 `【本轮节奏骨架（每轮随机给出，仅作节奏参考）】`，用于打破"每轮结构雷同"。

### volatile 段落（按顺序）

时间基准 → 消息时间元数据 → 角色当前状态 → 成员当前状态 → **世界书资料** → 本轮主动召回的相关长期记忆 + 记忆取用说明 + 记忆主体约定 → 成员私人记忆 → **场景概要** → 近期摘要记忆 → 待跟进承诺 → 互动节奏（重连/久别）→ 可选话题库 → 结尾多样性 → **本轮节奏骨架** → 冷场/话题切换指令 →（主动开场时）角色主动开场。

> 记忆取用说明：提醒召回条目"按关键词与时间粗略召回，可能只有部分相关或已过时"，不得硬提旧事、不得当作用户刚说过的话。

### 人格组装

- `buildRoleContext(char, staticOnly)` — :5318：从 `char.basicInfo` 拼人格（主字段 + 次字段，共 12 项 `STATIC_PROFILE_FIELDS` :1198）；群组则拼群组信息 + 成员清单（`buildMemberContext` :5305）。
- `buildDynamicStateContext()` — :5347：把 7 个动态字段渲染为"状态"文本（未设置的显示 `(未设置)`）。
- `buildRequestPayload(char, query)` — :5710：装配 system + 历史 + 本轮 volatile。

### 子任务提示词（非主对话）

主对话之外的每个 `callAPI` 调用都有独立 prompt，各司其职：

| 提示词 | 位置 | 关键约束 |
|---|---|---|
| 一键建卡 `quickGenerateCharacter` | :7176 | 输出完整字段 JSON；**禁止套路名字与模板人设**；`personality` 写行为倾向；`background` 只写 3-5 条会影响互动的要点；`speakingStyle` 必须是「调性描述；示例：<台词1> / <台词2> / <台词3>」（`CHARACTER_QUALITY_RULE` + `SPEAKING_STYLE_SAMPLES_RULE` :1476-1477）；群组分支要求成员说话方式显著不同 |
| 群组升级 `upgradeToGroup` | :7283 | 与建卡同一套质量约束；`additionalMembers` 不得重复原角色与彼此姓名 |
| 成员补全 `fillGroupMemberFields` | :2965 | **已有字段是绝对权威，不得改写/润色/替换**；只填空字段；说话方式须与群内其他成员显著不同；无把握则省略 |
| 空字段补全 `fillStaticFieldsForJob` | :3199 | 只补 `job.missing` 中的字段，绝不覆盖已有设定；缺 `speakingStyle` 时套用示例台词约束；群组成员再叠加"彼此不同" |
| 即时→短期摘要 `extractInstantToShortTerm` | :7016 | 按"日期+时段"合并；**只保留影响后续剧情的内容**（决策/地点物品伤势关系变化/发现/未决目标承诺威胁期限）；**每条 ≤120 字**；禁止文学化与对白原文；续写旧事件时**旧摘要中仍有效的信息必须原样保留**；必须覆盖全部输入消息 ID |
| 短期→长期 `analyzeShortToLongTerm` | :7102 | **价值判据**（可跨轮复用/影响后续/用户明确表达）才记，**证据不足宁可不记**；`arcOf` 命名一旦确定保持稳定；`sourceShortTermIds` / `evidence` 必须可回溯 |
| 场景概要 `summarizeScene` | :6936 | 1-2 段 ≤300 字，只含剧情要点，不引对白、不编造、写绝对日期 |
| 其余 | :1499 emoji 头像 / :5798 散文转 JSON / :3336 字段重排 / :3440 群组字段迁移 / :8111 贴图标签 | 低价值路径，未做经验对齐 |

> 像素头像提示词 `PIXEL_AVATAR_PROMPT`（:1473）**不参与任何建卡/补全 prompt**（像素头像为已废弃试验品），仅保留定义与遗留生成器 `requestPixelAvatar`。

## 八、健壮性

- **API 请求级重试**：`performChatRequestWithRetry`（:4194）对瞬时失败（网络错误 / 超时 Abort / HTTP 429 / 5xx）自动重试最多 `MAX_API_RETRIES = 3` 次（1s/2s/4s 指数退避）。
- **工具失败可恢复**：`executeToolCall` 失败时回传可修正的提示（如 `delete_memory` 找不到 ID 时提示先 `list_memories`）。
- **记忆任务重试**：`memoryTaskKeys(task)` — :6857 统一映射 `extraction` / `analysis` / `scene` 三组计数器（`*Failures` / `*RetryAt`），`scheduleMemoryRetry` 指数退避（5→30 分钟封顶）。
- **导入/导出归一化**：`normalizeAppData` → `normalizeCharacter`（:2117）逐字段校验，包含世界书、场景概要、场景状态与新增计数器。

## 九、关键常量速查

| 常量 | 值 | 位置 |
|---|---|---|
| `MAX_TOOL_ROUNDS` | 5 | :5864 |
| `AGENT_TOOL_MEMORY_INJECT_LIMIT` | 5 | :5865 |
| `MAX_API_RETRIES` | 3（退避 1s/2s/4s） | :4182 |
| `MEMORY_LIMITS` | instant 160(保底 40) / shortTerm 80(保底 20) / longTermPerCategory 40 / pendingRecall 6 / analysisBatch 40 | :1172 |
| `PROMPT_LIMITS` | roleChars 8000 / memberChars 900 | :1181 |
| `CONTEXT_BUDGET` | retrievedChars 2600 / summaryChars 2600 / sceneSummaries 2 / sceneInjectionChars 900 / sceneSpan 24 | :1236 |
| `LOREBOOK_LIMITS` | entries 200 / keywordsPerEntry 20 / nameChars 60 / contentChars 2000 / injectEntries 6 / injectChars 1400 / scanMessages 6 | :1225 |
| `NARRATIVE_PATTERNS` | 10 条节奏骨架 | :1212 |
| `DYNAMIC_STATE_FIELDS` | 7 个动态字段 | :1187 |
| `STATIC_PROFILE_FIELDS` | 12 个基础设定字段 | :1198 |
| 长示例切换阈值 | `assistantTurnCount < 6` 用完整 JSON 示例，否则用 `JSON_EXAMPLE_BRIEF` | :5742 |
| `max_output_tokens` | 8192 | :6544 附近 |
