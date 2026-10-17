# 各调用发送内容一览

本文记录当前代码里**每一次外发调用**（LLM / 语音合成 / 联网抓取）的传输格式与发送内容。纯快照：只描述当前状态（历史看 git）。

**阅读约定**

- **传输格式**逐字给出：URL 规则、Header、请求体 JSON 键名与取值来源。
- **短字符串**（system 提示词、user 拼装里的固定语句、返回 schema）在代码块内**逐字照抄**——代码块里就是请求里实际发出去的文本；Kotlin 源码里的 `\n` 在请求里是真实换行（正文按真实换行排版）。
- **长指令块**（`RULE_*`、抽取/分析类长提示）不整段复制，给「常量名 + 源码行 + 逐条要求」；**以源码为唯一权威**，本文只保证结构、顺序、上限与短句准确。
- 改动任一调用的**参数 / 提示词 / 工具清单 / 拼装顺序**时必须同步本文（与 `AGENT_ARCHITECTURE.md`、`NATIVE_MODULES.md` 同级要求）。

**代码位置速查**：传输层 `core/network/ResponsesLlmBackend.kt`；请求体 DTO `core/network/ResponsesDto.kt`；主对话提示词 `domain/agent/prompts/Prompts.kt`；主循环 `domain/agent/AgentLoop.kt`；后台调用 `domain/agent/background/BackgroundTasks.kt`；建卡/补全 `app/.../NativeCore.kt` + `feature/characters/CharacterParity.kt`；远程朗读 `engine/remote-tts/`；联网抓取 `core/network/HttpWebContentProvider.kt`。

---

## 0. 调用索引

| # | 调用 | 入口（触发时机） | taskType | system 来源 | 期望输出 |
|---|---|---|---|---|---|
| 1 | 主对话 · AUTO | 每轮用户发言 / 主动开场 | — | `buildSystemPrompt` | JSON 正文或 `submit_response` |
| 2 | 主对话 · SUBMIT | AUTO 没拿到正文时的兜底 | — | 同上（同一 `instructions`） | 强制 `submit_response` |
| 3 | 即时对话 → 短期事件 | 每轮；`instant` 到 60 整窗折叠 | `extraction` | `EXTRACTION_SYSTEM` | JSON 数组 |
| 4 | 短期事件 → 长期记忆 | 每 N 轮 / 清理 | `analysis` | `ANALYSIS_SYSTEM` | JSON 对象 |
| 5 | 场景概要 | 场景切换 / 跨 SPAN 且有 ≥6 条 | `scene` | `SCENE_SYSTEM` | 纯文本 |
| 6 | 世界书整理 | 每轮（折叠出新摘要后） | `lorebook` | `LOREBOOK_SYSTEM` | JSON 对象 |
| 7 | 世界书超长退回重写 | 上一步有条目 >400 字 | `lorebook` | `LOREBOOK_SYSTEM` | JSON 对象 |
| 8 | 世界书超长条目重建 | 用户手动一次性迁移 | `lorebook` | `LOREBOOK_REBUILD_SYSTEM` | JSON 对象 |
| 9 | 散文回复 → 结构化记忆 | 本轮回复是散文 | `auxiliary` | `PROSE_SYSTEM` + 角色设定 | 标准 JSON |
| 10 | 补全空的基础设定字段 | 启动维护 / 手动 | `auxiliary` | `FILL_SYSTEM` | JSON 对象 |
| 11 | 补全群成员字段 | 启动维护 / 手动 | `auxiliary` | `FILL_SYSTEM` | JSON 对象 |
| 12 | 世界观迁移（背景 → 世界书） | 一次性迁移 | `auxiliary` | `LOREBOOK_SYSTEM` | JSON 对象 |
| 13 | 字段结构重排 | 一次性迁移 | `auxiliary` | `FILL_SYSTEM` | JSON 对象 |
| 14 | 文风校对 | 检测到文风违规时 | `auxiliary` | `CRITIQUE_SYSTEM` | `{"reply":…}` |
| 15 | 快速回应修补 | 每轮（开关开且不足两条/不过校验） | `auxiliary` | 动态（用户视角） | `{"quickReplies":[…]}` |
| 16 | 建卡 / 群组升级 `quickGenerate` | 用户触发 | `auxiliary` | `QUICK_GEN_INSTRUCTIONS` | 角色/群组 JSON |
| 17 | 头像 emoji `requestEmojiAvatar` | 建卡后 / 手动 | `auxiliary` | 单行 | 一个 emoji |
| 18 | 表情包打标签 `tagSticker` | 添加表情包时 | `auxiliary` | 单段 | 一个短词 |
| 19 | 群成员字段补全 `fillMemberFields` | 手动 | `auxiliary` | `FILL_MEMBER_INSTRUCTIONS` | 成员字段 JSON |
| 20 | 远程语音合成 | 点朗读 / 自动朗读 | — | — | PCM 音频流 |
| 21 | 联网抓取（搜索/热榜/社区/网页） | 工具调用 | — | — | JSON 或 `input_text`+`input_image` |

---

## 1. LLM 传输层（所有 LLM 调用共用）

### 1.1 端点

`POST {responsesEndpoint}/responses`

`responsesEndpoint(baseUrl, platform)`（`ResponsesLlmBackend.kt:486-492`）：去掉结尾 `/chat/completions` → 去掉结尾 `/v1`（平台 `keepV1InResponses=true`，如 `opencode`，保留 `/v1`）→ 追加 `/responses`。

默认 `apiBaseUrl = "https://api.deepseek.com/v1"` → `https://api.deepseek.com/responses`。

### 1.2 Header

| Header | 值 |
|---|---|
| `Content-Type` | `application/json` |
| `User-Agent` | `DeepTalking-Lite/<BuildConfig.VERSION_NAME>`（app 注入；默认 `DeepTalking-Lite`） |
| `Authorization` | `Bearer <apiKey>`；key 为空**直接抛错不发请求**（`未配置API Key，请在设置中填写`） |
| `x-opencode-session` | **仅** `apiPlatform == "opencode"`：`sessionId ?: "deeptalking-general"`（缺这个 header 网关返回 400 MissingSessionID） |
| `Accept` | **仅流式**：`text/event-stream` |

### 1.3 请求体（`ResponsesRequest`，字段名即 JSON 键）

```json
{
  "model": "<AppConfig.modelName，默认 deepseek-flash>",
  "instructions": "<system prompt；无则省略>",
  "input": [ /* 见 1.4 */ ],
  "tools": [
    { "type": "function", "name": "…", "description": "…", "parameters": { }, "strict": false }
  ],
  "tool_choice": "auto",
  "temperature": 0.8,
  "max_output_tokens": 8192,
  "stream": true,
  "reasoning": { "effort": "medium" }
}
```

- `tool_choice` 三种形态：`"auto"` / `"none"` / `{"type":"function","name":"…"}`。
- `reasoning.effort` 白名单 `none|low|medium|high|max`，越界回落 `medium`。
- `signature`：`temperature` 由 `normalizeTemperature(config.temperature)` 夹到 `[0,2]`；`encodeDefaults = true` 意味着即使是默认值也会出现在体里（`stream`/`strict` 等）。

### 1.4 `input` 数组的元素形态（异构）

| 形态 | 结构 |
|---|---|
| 普通消息 | `{"role":"user|assistant|system","content":"文本"}` |
| 含图消息 | `{"role":"user","content":[{"type":"input_text","text":"…"},{"type":"input_image","image_url":"data:…","detail":"auto"}]}` |
| 工具调用 | `{"type":"function_call","call_id":"…","name":"…","arguments":"<JSON 字符串>"}` |
| 工具结果 | `{"type":"function_call_output","call_id":"…","output":"字符串 或 [input_text/input_image 部件]"}` |
| 思考项重放 | `{"type":"reasoning","id":"…","status":"…","content":[{"type":"reasoning_text","text":"…"}],"summary":[…]}` |

规则：`Role.Tool` **绝不**作为 `{role:"tool"}` 发出；assistant 带 `toolName` 的消息会先把该消息的 reasoning 项逐个展开、再发 `function_call`；工具结果若是 `[{type:"input_text"|"input_image"|"input_file"}, …]` 数组则原样下发（模型能直接看图），否则按字符串下发。

### 1.5 超时

| 场景 | callTimeout | readTimeout |
|---|---|---|
| 主对话（非流式） | 180 s | 180 s |
| 主对话（流式） | 180 s | 60 s（空闲即断；每来一个事件重置） |
| 辅助调用（`auxiliary=true`） | 90 s（`AppLimits.Api.AUXILIARY_TIMEOUT_MS`） | 90 s |

### 1.6 响应解析

- 正文 = `output[]` 中 `type=="message"` 的 `content[]` 里 `type=="output_text"` 的 `text` 拼接。
- 工具调用 = `output[]` 中 `type=="function_call"` → `{call_id,name,arguments}`。
- 用量缓存：`prompt_cache_hit_tokens` 存在时优先，否则 `input_tokens_details.cached_tokens`。
- `error` 非空 → 抛 `API 返回错误: …`；HTTP 非 2xx → 抛 `Responses API HTTP <code>: <body 前 500 字>`。
- 前缀指纹：`instructionsHash`（system prompt）与 `toolsHash`（启用工具的 `name|description|parametersJson|strict` 拼接）用 `fnv1a` 计算，用于缓存提示；**改提示词或工具清单都会让每个角色破一次前缀缓存**。

---

## 2. 主对话请求 · AUTO 阶段

**调用点**：`ChatOrchestrator.run` 组装 → `AgentLoop.run` 内 `builder.build(...)`（`AgentLoop.kt:92`）。**触发**：每次用户发言、主动开场轮、热榜开场轮。

```json
{
  "model": "<config.modelName>",
  "instructions": "<buildSystemPrompt(...)>",
  "input": [ /* 见 2.3 */ ],
  "tools": [ /* 见 2.5，全部启用的工具 */ ],
  "tool_choice": "auto",
  "temperature": 0.8,
  "max_output_tokens": 8192,
  "stream": true,
  "reasoning": { "effort": "<config.reasoningEffort，默认 medium>" }
}
```

（`stream`/`temperature`/`reasoningEffort` 取自 `AppConfig`，默认 `stream=true`、`temperature=0.8`、`reasoningEffort="medium"`、`apiPlatform="deepseek"`、`modelName="deepseek-flash"`。）

### 2.1 `instructions`（system prompt）分块顺序

| # | 标题/内容 | 来源 | 截断 |
|---|---|---|---|
| 1 | `【交互规则】` | 字面 | — |
| 2 | 规则优先级阶梯（0/0.5 与【输出格式】> 2.5 语气锁定与 2.6 > 人设 > 风格偏好） | `PRIORITY_LADDER` | — |
| 3 | 规则 0、1、2、2.6、3、3.5、3.6、3.7、3.8、3.9、4、5、5.5、6、7、8、9、9.5、A | `RULE_*` 常量 | — |
| 4 | 规则 10、11、12（**仅群组**） | `GROUP_RULE_*` | — |
| 5 | `【角色设定（固定，不随对话变化）】` | 字面 + `buildRoleContext(character, staticOnly=true)` | 单角色 `trimText 8000`（`Prompt.ROLE_CHARS`）；群成员每人 `900`（`MEMBER_CHARS`） |
| 6 | `【硬性约束与语气锁定（最高优先级，冲突时以这里为准）】` | 字面 | — |
| 7 | `HARD_0_5` + `HARD_2_5` + `OUTPUT_FORMAT` | 常量 | — |

规则清单（顺序即发送顺序，标签 + 一条要点；正文见 `Prompts.kt`）：

| 标签 | 要点 |
|---|---|
| （阶梯） | 冲突时按优先级，0.5 与输出格式不可违反 |
| `0.` | 收尾必须结构化：`submit_response` 或单个 JSON 对象（`reply` 为首字段），正文之外无文字 |
| `1.` | 保持角色身份连续；先用已提供记忆；「系统提供的本轮上下文」不是用户陈述 |
| `2.` | reply 写法：≤250 字无下限、可分段、禁标题/列表/引用/代码块、动作必须全角括号（≤¼）、**自己的话不加引号、引用他人/歌词/书名才用「」《》**、去重 |
| `2.6` | 表演质量：show-don't-tell、句长起伏、对白优先、避陈词、删不推进句 |
| `3.` | 人设/背景锁定 + 可更新的 `dynamicState` 字段清单（`key（label）`，见下）+ 溯源；`currentTone` 每轮必填 |
| `3.5` | 谨慎改基础设定：用户要求 → `update_character_field`；仅决定性不可逆转折才自动改；世界层 → 世界书；value 写简短陈述句、禁「不是…而是…」 |
| `3.6` | `dynamicState`/`staticFields` 的 value 只写内容本身，不加主语、不写理由 |
| `3.7` | 记忆 key/value 必须写明主体，禁「我/你/TA」 |
| `3.8` | 时间一律绝对日期，相对说法进 `timeRef` |
| `3.9` | 用户要求改说话方式时本轮直接演出，禁元话术 |
| `4.` | 只记未来有价值的信息；模型不写 `shortTerm`，只写 `longTerm` + `promiseUpdates` |
| `5.` | `subject` ∈ user/relationship/world 的取值约束 |
| `5.5` | 承诺必须写 `promisor`/`promisee`；时间绝对化 |
| `6.` | 新建承诺只能 `active`；完成/取消走 `promiseUpdates` 并引用原话 |
| `7.` | `quickReplies` 恰好两条、用户视角、三类禁止 |
| `8.` | JSON 字段结构示例 + 转义规则 |
| `9.` | `longTerm.category` 五选一；`importance` 1–10 |
| `9.5` | 相对时间 `timeRef`（`anchor/offsetDays/weekday/slot/explicit`）；时段只有深夜/凌晨/清晨/早晨/上午/中午/下午/傍晚/晚上/夜里 |
| `A.` | 本会话可用工具清单（枚举全部注册工具）+ 使用原则（一工具一平台、断言前先 `search_memory`） |
| `10.` | 群组发言格式：`成员名：内容`（台词直接写）、动作全角括号、`memberDynamicState` |
| `11.` | 群组记忆归属：共有才共享，私人事实写 `memberName` |
| `12.` | 群组三层设定：前提+共同场景 / 世界书 / 每成员独立 |
| `0.5` | 只扮演角色本人，禁替用户说话行动 |
| `2.5` | 语气锁定：腔调只来自「说话风格」与「对用户的称呼」，签名标记须真的出现（≥2 处） |
| `【输出格式】` | 只能 `submit_response` 或单个 JSON 对象；`"`→`\"`、换行→`\n`（优先用「」可免转义）；`quickReplies` 恒两条 |

`3.` 里注入的 `dynamicState` 字段清单（`DYNAMIC_STATE_FIELDS` 顺序）：

```
currentSituation（当前处境）、currentLocation（当前位置/场景）、currentMood（当前情绪）、currentOccupation（当前职业/身份）、currentGoal（当前目标）、currentTone（当前语气）
```

### 2.2 `input` 组装顺序

```
[0] 近期摘要记忆块（User 消息，可省略）      ← buildShortTermBlock(character)
[1..n] mapHistory(history).takeLast(60)      ← 历史，最多 60 条
[n+1] 本轮用户消息（若历史最后一条不是 User）  ← 原文 userText
[last] 本轮系统上下文（User 消息，volatile 尾部）
```

- **历史映射**（`mapHistory`）：过滤 `rejected`；用户消息 `trimText 2000` 且贴图附件 `Kind.Sticker → Kind.Image`；助手消息 `trimText 1200` 后包成 `{"reply":"…"}`（含 JSON 转义），空则丢弃；其它角色原样。
- **近期摘要块**：`【近期摘要记忆（刚发生的事，按时间从早到晚，共 N 条；更早的事用 search_memory 按日期或关键词召回）】` + 每行 `[日期] 内容`。
- **系统上下文消息**前缀逐字：`【系统提供的本轮上下文，仅供角色理解，不代表用户陈述】\n`。

### 2.3 volatile 尾部（系统上下文消息内）分块顺序

| # | 标题 | 条件 | 上限 |
|---|---|---|---|
| 1 | `【可选话题库（仅在需要时自然引出，不要生硬报菜名）】` | 有话题候选 | 最多 5 条；来源 `trimText 200` |
| 2 | `【角色当前状态，可随对话变化】` | 恒有 | 每字段 `trimText 200`；群成员 `trimText 220` |
| 3 | `【待跟进承诺】` | 有承诺 | 最多 3 条；key 80 / value 260 |
| 4 | `【世界书·常驻前提…】` / `【世界书·相关条目…】` | 有条目 | 每条 `trimText 400`；最多 6 条 |
| 5 | `【<成员名>的私人记忆，仅该成员知道】` | 群组且有 | 每条 `trimText 1200`；每成员 ≤2 条 |
| 6 | `【用户最新纠正（优先于旧记忆…）】` | 有纠正 | `takeLast(2)`；`trimText 200` |
| 7 | `【互动节奏】距上次回复已过 …` | 间隔 ≥6h | — |
| 8 | `【场景概要（较早情节的压缩记录…）】` | 有场景 | `takeLast(2)` |
| 9 | `【本轮主动召回的相关长期记忆】` + `【记忆取用说明】` + `【记忆主体约定】` | 恒有 | 召回 5 条；key 120 / value 1200 |
| 10 | `【时间基准】`（本地时间/时区/ISO/逻辑日） | 恒有 | — |
| 11 | `【消息时间元数据，仅供内部推理】` | 恒有 | 最近 14 条：`id \| role \| timestamp` |
| 12 | 风格尾块 `tail` | 各子块各自条件 | 见下 |
| 13 | `【角色主动开场（系统触发，不是用户陈述）】` | 主动轮 | — |
| 14 | `【本轮热榜话题（系统触发，不是用户陈述）】` | 热榜轮 | — |

`tail` 内部顺序：`【结尾多样性】`（有近期收尾时）→ `【本轮节奏骨架…】`（恒有，随机一条模板）→ `【本轮冷场】` **或** `【本轮话题切换】` → `【上一轮快速回应（内部提醒）】`（有视角问题时）→ `【上一轮需要纠正（内部提醒）】`（有违规时）→ `【语气回顾…】`（每 3 轮）→ `【本轮声音契约…】`（单角色有说话风格）/ `【群聊声音锁定…】`+`【成员声音·<名>】`（群组）。声音契约：摘要 `trimText 300`、示例 ≤3 条各 `trimText 80`。

**volatile 无区块预算**，只有上表每条目的的兜底上限。

### 2.4 多轮工具循环

每执行一个信息类工具后追加两条消息（下轮请求里序列化成 3 个 item）：

```
assistant 消息 { content:"", toolCallId, toolName, toolArguments, reasoningJson }  →  reasoning item(s) + function_call item
Role.Tool 消息 { content: 工具结果 JSON, toolCallId }                              →  function_call_output item
```

上限：`MAX_TOOL_ROUNDS = 5`、`MAX_TOOL_CALLS = 12`（`AppLimits.Agent`）。达到上限时：有累计正文就直接采用；没有就转 SUBMIT。

### 2.5 `tools`（注册顺序即发送顺序）

`get_current_time`、`search_memory`、`list_memories`、`delete_memory`、`set_reminder`、`ask_user`、`update_character_field`、`upsert_lorebook_entry`、`web_fetch`、`update_memory`、`send_sticker`（**仅当角色有贴图**，schema 内联该角色标签枚举）、`web_search`、`search_video`、`search_music`、`search_posts`、`baidu_hot`、`toutiao_hot`、`douyin_hot`、`zhihu_hot`、`submit_response`。

`strict` 只有 `submit_response` 为 `true`，其余全部 `false`。

### 2.6 期望输出与解析

`submit_response` 工具调用、或合法 JSON、或**散文**都直接采用（散文即终稿）。

---

## 3. 主对话请求 · SUBMIT 阶段

同一 `instructions`，只改三项：

| 字段 | AUTO | SUBMIT |
|---|---|---|
| `tools` | 全部启用工具 | 只有 `submit_response` |
| `tool_choice` | `"auto"` | `{"type":"function","name":"submit_response"}` |
| `reasoning.effort` | `config.reasoningEffort` | `"none"` |

**触发**：AUTO 没产出可用正文时（先补一个 reasoning 消息再转）；SUBMIT 给一次重试，仍空则最多再试两次空回合，最后返回空结果。

---

## 4. 后台 / 辅助调用（共性）

所有后台任务（第 5–18 节）走同一个模板（`BackgroundTasks.kt:1929-1940`）：

```json
{
  "model": "<BackgroundTasks.model，默认 deepseek-flash>",
  "instructions": "<各调用自己的 system>",
  "input": [ { "role": "user", "content": "<各调用拼装的 user>" } ],
  "tool_choice": "auto",
  "temperature": 0.8,
  "max_output_tokens": 8192,
  "stream": false,
  "reasoning": { "effort": "none" }
}
```

- `temperature = normalizeTemperature(config.temperature)`；`max_output_tokens = AppLimits.Api.AUXILIARY_MAX_OUTPUT_TOKENS = 8192`；`stream=false`；`auxiliary=true`（因此用 90 s 预算）；`sessionId = deeptalking-<charId>`（无 id 时 `deeptalking-general`）；`tools` 空。
- **失败策略**：瞬时错误（网络/超时/429/5xx）按 `ApiRetry` 重试；永久失败记录原因并返回空文本（各调用按空结果回退）。**没有 `tools`**，所以辅助调用只发一次、不进入工具循环。

---

## 5. 即时对话 → 短期事件（`extraction`）

- **触发**：每轮；`instant` 达高水位 60 时整窗折叠；或超长窗迁移（`migrateOversizedWindow`）。
- **taskType**：`extraction`。
- **system（逐字）**：

```
你是一个信息提取助手。只返回JSON数组，不要其他文字。
```

- **user 拼装顺序**：`EXTRACTION_PROMPT_HEAD` → 模式子句 → `EXTRACTION_PROMPT_TAIL` → 已有短期事件 JSON → `\n对话内容:\n` → 对话 JSON。

  1. 模式子句：普通 `返回JSON数组，最多十五个元素。`；迁移 `不限事件数量，尽可能完整覆盖本批全部消息。`
  2. 已有短期事件 JSON：`[{eventTime,participants,location,content}]`，来自 `shortTerm` 按 `eventTime` 倒序 `take 20`，每条 `content = trimText 240`。
  3. 对话 JSON：`[{id,role,content,timestamp}]`，`role` 小写、`content = trimText 600`；普通模式=当前 `instant` 全窗，迁移模式=`instant.take(60)`。
- **期望输出**：`[{"content":"…","eventTime":"ISO","participants":["用户","角色名"],"location":"…"}]`；解析用宽松 JSON，普通的 `take(15)`，空即失败。

`EXTRACTION_PROMPT_HEAD`（逐字）：

```
把下面这段即时对话整体压缩为事件流程摘要，覆盖整段对话、不要遗漏内容。事件按“日期+时段”划分：时段只有深夜、凌晨、清晨、早晨、上午、中午、下午、傍晚、晚上、夜里十种，且一天从02:00起算（00:00-02:00算前一天的深夜）；同一天同一时段的多个事情视为同一事件，按时间线集中叙述（如“2026-08-01 下午在家先…晚上…”）。同一时段也可能有不同事件，按事实或持续话题区分，不得把无关事件强行合并；同一事件内用时间线连接各阶段，保留仍然有效的关键细节、结果与未决事项。**只保留会影响后续剧情的内容**：谁做了什么、学到或决定了什么，地点/物品/伤势/关系的变化，新的发现或线索，以及仍未解决的目标、承诺、威胁与期限；一次性的寒暄、当下的情绪起伏、闲聊过程一律不记。**每条content不超过120字**，用平实的叙述句写成，禁止文学化描写、比喻、形容词铺陈与对白原文。eventTime写该事件的真实ISO时间，participants为参与人列表，location写地点（没有明确地点写“未说明”）。若本次某事件是“已有短期事件”中某事件的延续或更新，不要另起一条：把它并进对应事件（沿用同一事件的时间与地点），并在输出里直接给出合并后的完整摘要，旧摘要中**仍然有效**的信息（未过期的约定、未解决的事、仍然成立的关系与状态）必须原样保留，只丢弃已经过时或已被推翻的部分。content按时间和因果顺序书写，不要逐字摘录。content必须写明是谁做的：涉及用户写“用户”，涉及角色写角色名（群组写具体成员名），双方共同的事写清各自做了什么，禁止使用“我、你、TA、他、她”这类指代不清的代词。content中禁止使用“今天、昨天、明天、今晚、刚刚、刚才、现在、最近、这几天”等相对时间词，一律使用具体日期（如“8月1日下午”）或事件本身描述。不要输出任何消息ID。
```

`EXTRACTION_PROMPT_TAIL`（逐字，末尾换行后接已有事件 JSON）：

```
返回JSON数组：[{"content":"一段完整的事件流程摘要","eventTime":"ISO时间","participants":["用户","角色名"],"location":"地点或未说明"}]。
已有短期事件（不要重复，只在需要延续或更新时合并进去）:
```

---

## 6. 短期事件 → 长期记忆（`analysis`）

- **触发**：累计够一批（`SHORT_TERM - TRIM_FLOOR = 20`）时每 N 轮；清理时可手动排空。
- **taskType**：`analysis`。
- **system（逐字）**：

```
你是一个记忆分析助手。只返回JSON，不要其他文字。
```

- **user 拼装顺序**：`ANALYSIS_PROMPT_HEAD` → 条目 JSON → `ANALYSIS_PROMPT_TAIL`。
  1. 条目 JSON：`[{id,content,sourceMessageIds,eventTime,participants,location,userEvidence,analyzed}]`。`batch` = 未分析的前 40 条（`analyzed=false`），`context` = 最近 20 条已分析条目（`analyzed=true`，只用于连贯）。
- **期望输出**：`{"status":"ok","analyzedShortTermIds":[…],"longTerm":[{category,subject,key,value,tags,importance,sourceShortTermIds,sourceMessageIds,evidence,eventTime,participants,location,status,dueAt,arcOf,arcStage}]}`；解析失败则走 `AnalysisSalvage` 的确定性抢救。

`ANALYSIS_PROMPT_HEAD`（逐字）：

```
分析以下带短期ID、来源消息ID、userEvidence及时间/人物/地点三要素的事件流程摘要，提取未来仍有价值的稳定事实。输入中每条条目带 analyzed 标记：**只为 analyzed=false 的条目产出长期记忆**；analyzed=true 的是前一轮已整理过的上下文，只用于保持情节连贯（可据此合并或修正），不得为它们重复产出条目，也不要在 analyzedShortTermIds 中返回它们。**价值判据**：只有能跨轮复用、会影响后续对话或关系、或用户明确表达过的信息才记（长期偏好、重要人物与关系、承诺与约定、反复出现的习惯、持续的情节线）；一次性的寒暄客套、当下的情绪起伏、可以随口重说的闲聊、纯场景描写一律不记。**证据不足宁可不记**，不要为了产出而脑补或推演。必须检查全部 analyzed=false 的条目，并在analyzedShortTermIds中原样返回它们的短期ID；不得遗漏、增加或重复。**每条 value 不超过200字，arc 叙事摘要不超过300字**，写成平实叙述，不堆砌形容词。合并重复或冲突条目；事件、承诺保留必要日期；普通寒暄不要进入长期记忆。每条长期记忆必须在sourceShortTermIds中列出它实际使用的短期条目id；每个列出的短期条目都必须至少贡献一个sourceMessageIds中的消息ID，否则不要列出该短期ID。sourceMessageIds只选择直接支持该事实的消息，不要复制短期条目的全部来源；userProfile和habits只能选择用户消息。evidence只能逐字引用被选择消息对应的userEvidence.text，不能引用摘要或改写。没有可引用的userEvidence时不要输出该条。events按“日期+时段+事实主题”区分：同一天同一时段（深夜/凌晨/清晨/早晨/上午/中午/下午/傍晚/晚上/夜里，一天从02:00起算）的短期事件合并为一条长期事件，同一时段的独立事实不得互相覆盖，key须标明稳定事实主题；合并时eventTime取最早的ISO时间，participants取并集，location取最新（无明确地点写“未说明”）。剧情弧线：若若干短期事件属于同一持续情节或话题线（同一人物线、同一持续事件、同一反复出现的话题），除按时间合并外，还应在其中一条事件条目上标注arcOf（该情节的持续话题或人物线名称，稳定可复用）与arcStage（只能是起始/发展/转折/现状之一，按情节推进阶段标注），arcOf命名一旦确定就保持稳定——同一情节线不得每轮改名或另起新名，续写时沿用已有名称；并将value整合成按时间顺序、带情绪起伏的叙事摘要；同一arcOf只允许一条带弧线标注的条目，其余同线条目按普通事件输出。无论是否提取出长期记忆，成功完成分析都必须返回status:"ok"；没有长期价值时仍需返回完整analyzedShortTermIds和空longTerm。
分类规则：userProfile/habits仅限用户事实，subject=user；relationship仅限双方关系，subject=relationship；events只记录用户陈述或共同事件；promises只记录用户明确承诺或双方明确约定。promises在此处只能新建为active；完成或取消由主对话的promiseUpdates按承诺ID处理。存在明确期限才填写dueAt。
条目:
```

`ANALYSIS_PROMPT_TAIL`（逐字）：

```

返回JSON格式:
{"status":"ok","analyzedShortTermIds":["全部 analyzed=false 的 short_id"],"longTerm": [{"category": "...", "subject": "user|relationship|world", "key": "稳定且可复用的标识", "value": "...", "tags": [...], "importance": 1-10, "sourceShortTermIds":["short_id"], "sourceMessageIds": ["msg_id"], "evidence": "被选择用户消息中的逐字原话", "eventTime": "ISO时间", "participants":["参与者"],"location":"地点或未说明","status": "active", "dueAt": "ISO时间", "arcOf": "可选，持续情节线名称", "arcStage": "可选，起始/发展/转折/现状"}]}
category可选: userProfile, relationship, events, promises, habits。value同样必须写明主体：涉及用户写“用户”，涉及角色写角色名（群组写具体成员名），禁止“我/你/TA”这类指代不清的代词。key与value中的时间一律写绝对日期（YYYY-MM-DD 或 YYYY-MM-DD 时段），禁止“明天/明晚/上周/上个月/三天后”这类相对时间词。这里只提取用户的约定（promises 的 promisor=user、promisor与promisee只能是 user 或 character）；角色单方承诺由主对话记录，不在此处提取。
```

---

## 7. 场景概要（`scene`）

- **触发**：场景键（地点）变化、或场景跨度超过 `SPAN=12` 且消息 ≥ `MIN_MESSAGES=6`。
- **taskType**：`scene`。**system（逐字）**：

```
你是对话记录整理助手。只输出场景摘要正文，不要任何额外文字。
```

- **user**：`SCENE_PROMPT` + 转录文本。转录 = 场景起点之后的 `instant` 取 `takeLast(20)`，每行 `[序号] 用户|角色名: trimText(content,500)`。
- **期望输出**：纯文本摘要（无 JSON）；长度 <8 视为失败，落盘 `trimText 2000`。

`SCENE_PROMPT`（逐字）：

```
把下面这段已经告一段落的情节压缩成 1-2 段、不超过 300 字的「场景记忆」，供之后长期参考。
只保留会影响后续剧情的要点：谁做了什么、学到或决定了什么；地点、物品、伤势、关系的变化；新的发现或线索；仍未解决的目标、承诺、威胁或期限。
要求：①写明主体（涉及用户写“用户”，涉及角色写角色名），禁止“我/你/TA”这类指代不清的代词；②时间写绝对日期（YYYY-MM-DD 或 YYYY-MM-DD 时段），禁止“今天/昨天/刚才”这类相对时间词；③不要引用对白原句、不要加标题或 markdown、不要文学化描写；④不得编造原文没有的事实；⑤直接输出摘要正文，不要任何前后缀或解释。

对话内容：
```

---

## 8. 世界书整理（`lorebook`）

- **触发**：每轮（折叠出新摘要后）/ 清理时排空。
- **taskType**：`lorebook`。**system（逐字）**：

```
你是世界书整理助手。只返回 JSON，不要任何额外文字。
```

- **user 拼装顺序**：`LOREBOOK_PROMPT_HEAD` → 现有条目清单（或字面 `（空）`）→ `LOREBOOK_PROMPT_TAIL` → `（角色名：<名>）：` → 事件摘要行。

  1. 现有条目清单每行：`- 名称｜关键词: a、b` + `｜常驻` + `｜内容: …` 或 `｜内容（略，本轮不要更新此条）`。完整内容只给**最近更新的 12 条**且总字符 ≤ 6000（`LOREBOOK_EXISTING_FULL_ENTRIES` / `LOREBOOK_EXISTING_FULL_CHARS`）。
  2. 事件摘要行：`[<shortTermId>] trimText(content,600)`。
- **期望输出**：`{"entries":[{"name","keywords","content","alwaysActive","sourceShortTermIds"}]}`；`take(3)`，`sourceShortTermIds` 必须都在本批摘要 id 内；超 400 字的条目转入第 9 节重写。

`LOREBOOK_PROMPT_HEAD`（逐字）：

```
下面是一段角色扮演对话里已经发生的事件摘要，以及现有的世界书条目清单。
请找出其中**已经出现、值得日后复用**的世界层设定（时代/世界观、地点、组织、专有名词、历史、规则、背景事实），整理成世界书条目。
要求：①只写已经出现或已被明确说出的内容，禁止推测、扩写或发明新设定；②**同一件事物只能有一条**：对照现有条目的名称、关键词与内容，凡与已有条目讲的是同一件事（名称相近、关键词相同、内容重叠），必须复用它的名字、用你这次总结的内容**整条替换**它，绝不新建近似条目；同理，本批不同摘要里指向同一件事的也只输出一条；③只沉淀会反复复用的世界层设定，一次性的小事、可从上下文直接看出的细节不要单独立条；④每条 content 只写该条目本身的信息，不写理由、解释或出处，**一条只讲一件事**；**长度目标 300 字以内**、**硬上限 400 字**（超过 400 字的条目会被退回重写，宁短勿长）；⑤更新一条已有条目时，把它下面给出的**完整原有内容**与你掌握的新事实**重新总结成一条**：仍然有效的信息必须原样保留，重复的、过时的、与本条主题无关的内容删掉，允许比原来短——**只写增量会丢掉原有信息**；⑥清单里标注「内容（略）」的条目本轮不要更新（你看不到它的完整内容）；⑦keywords 写剧情里可能出现的称呼，不超过 8 个；若这条是世界前提/规则这类需要每轮生效的设定，把 alwaysActive 设为 true，否则一律 false；⑧最多 3 条；没有值得沉淀的就返回空数组。
只返回 JSON：{"entries":[{"name":"条目名","keywords":["触发词"],"content":"设定内容","alwaysActive":false,"sourceShortTermIds":["上面的摘要ID"]}]}。sourceShortTermIds 必须是上面出现过的摘要ID，至少要有一个；没有可引用ID的条目不要返回。

现有条目清单：
```

`LOREBOOK_PROMPT_TAIL`（逐字）：

```

事件摘要
```

---

## 9. 世界书超长条目的退回重写（`lorebook`）

- **触发**：第 8 节有条目 >400 字时**同一函数内**追加一轮。
- **system**：同 `LOREBOOK_SYSTEM`。
- **user 拼装**：`LOREBOOK_FIX_PROMPT` → 被退回条目逐行 `- name: X｜keywords: a、b｜sourceShortTermIds: s1、s2｜content: …`（原文回显，不截断）。
- **期望输出**：同 `{"entries":[…]}`；只接受原名单里、content 非空且 ≤400 字、来源 id 合法的条目，其余丢弃（原超长条目不落盘）。

`LOREBOOK_FIX_PROMPT`（逐字，末尾换行后接退回条目）：

```
下面这些世界书条目**超过了 400 字的硬上限**被退回，请把每一条**重新写一遍**：
要求：①压缩到 **300 字以内**，仍然有效的关键信息（专有名词、人名、地点、日期、已确定的事实）必须保留，只删重复、修饰和可从上下文推出的细节；②**一条只讲一件事**，名称保持不变；③sourceShortTermIds 原样照抄，不要新增或改动；④不得编造原文没有的内容。
只返回 JSON：{"entries":[{"name":"条目名","keywords":["触发词"],"content":"压缩后的设定内容","alwaysActive":false,"sourceShortTermIds":["摘要ID"]}]}

被退回的条目：
```

---

## 10. 世界书超长条目重建（一次性迁移）

- **触发**：用户手动对某角色执行"重建超长世界书条目"，每条一次调用。
- **taskType**：`lorebook`。**system（逐字）**：

```
你是世界书整理助手。只返回 JSON，不要任何额外文字。
```

- **user 拼装**：`LOREBOOK_REBUILD_PROMPT` → `\n\n（角色名：<名>）\n原条目：\n名称：…\n关键词：…|（无）\n常驻：true|false\n内容：\n<原条目全文>`
- **期望输出**：`{"entries":[…]}`；`take(3)`，名称 `take 60`，content 必须 ≤400 字（否则整次失败、保留原条目），keywords `take 8`；首条沿用原 id。

`LOREBOOK_REBUILD_PROMPT`（逐字）：

```
下面是一条已经积累得过长、把好几件不同的事混在一起的世界书条目。请把它**重新整理**成 1~3 条各自只讲一件事的条目。
要求：①只使用原文里已有的信息，**禁止新增、推测或扩写任何设定**；②按主题拆分：读起来是不同话题的内容必须拆成不同条目，同一话题的合并成一条；③删掉重复的说法、已经过时或彼此矛盾的旧信息和与条目主题无关的内容；④每条的 content 都要写成完整、精炼的句子，**目标 300 字以内、硬性不超过 400 字**（超了这次重写会被丢弃、原条目原样保留），只写该条目本身的信息，不写理由或出处；⑤沿用原文的专有名词写法，条目名简洁（不超过 20 字）；⑥keywords 写剧情里可能出现的称呼，不超过 8 个，不要写泛词；⑦只有确实是世界前提/规则这类需要每轮生效的才把 alwaysActive 设为 true，其余一律 false；⑧原文里**仍然有效的信息要尽量保留**，不要因为压缩而丢事实。
只返回 JSON：{"entries":[{"name":"条目名","keywords":["触发词"],"content":"设定内容","alwaysActive":false}]}
```

---

## 11. 散文回复 → 结构化记忆（`auxiliary`）

- **触发**：本轮回复是散文（非结构化 JSON），后台补成标准 JSON 并落记忆。
- **taskType**：默认 `auxiliary`（未显式传）。
- **system**：`PROSE_SYSTEM` + `\n\n【角色设定】\n` + `buildRoleContext(character, false)`。
- **user 拼装**（逐字骨架）：

```
【系统提供的本轮上下文，仅供理解，不是用户陈述】
<volatileContext（同 2.3）>

用户消息: <用户原文> （消息id: <id>）

角色回复（散文，整理到reply字段）：
<散文原文>
```

- **期望输出**：与主对话一致的标准 JSON（`reply` 一字不改、`quickReplies` 两条、`shortTerm`/`longTerm`/`dynamicState`/`staticFields`/`memberDynamicState`/`promiseUpdates`）；来源 id 只接受本轮用户消息 id 与助手消息 id。

`PROSE_SYSTEM`（逐字）：

```
你是「对话记录整理助手」。下面是一次角色扮演交互（用户消息 + 角色回复），请整理成与主对话一致的标准 JSON，包含全部字段：{"reply":"角色回复原文（一字不改）","quickReplies":["用户下一句1","用户下一句2"],"shortTerm":[{"content":"从交互提取的本轮事件摘要，写明谁做了什么","sourceMessageIds":["<用户消息id>"]}],"longTerm":[{"category":"userProfile|relationship|events|promises|habits","subject":"user|relationship|world","key":"稳定标识","value":"用户的具体事实（写明主体）","tags":[],"importance":1-10,"sourceMessageIds":["<用户消息id>"],"evidence":"用户原话","promisor":"可选，约定的承诺方 user|character","promisee":"可选，约定的受约方 user|character"}],"dynamicState":{"currentSituation":{"value":"当前处境","sourceMessageIds":["current_response"],"evidence":"回复中引原句"},"currentMood":{"value":"当前情绪","sourceMessageIds":["current_response"],"evidence":"概括回复原句"},"currentGoal":{"value":"当前目标","sourceMessageIds":["current_response"],"evidence":"回复中引原句"}},"staticFields":{},"memberDynamicState":[],"promiseUpdates":[]}。规则：reply必须一字不改保留角色回复原文；quickReplies必须恰好两条用户可直接发送给角色的短句；所有记忆只能从这次交互中明确提取，不得凭空编造；dynamicState来源用current_response、evidence引用回复原句；shortTerm和longTerm的sourceMessageIds引用用户消息id；用户未明确陈述或表现的内容不要写入；无合格记忆返回空数组。记忆字段（shortTerm的content、longTerm的key与value）必须写明主体：涉及用户写“用户”，涉及角色写角色名，禁止“我/你/TA”这类指代不清的代词；时间一律写绝对日期（YYYY-MM-DD 或 YYYY-MM-DD 时段），禁止“明天/明晚/上周/上个月/三天后”这类相对时间词，用户用相对时间说法时改用timeRef（anchor/offsetDays/weekday/slot/explicit）。dynamicState与staticFields的值以段落式的陈述句书写（自然完整陈述句，简短段落），禁止括号注释或理由。只返回JSON对象，不要其他任何文字。
```

---

## 12. 补全空的基础设定字段（`auxiliary`）

- **触发**：启动维护 / 手动；有缺失字段且未在冷却期（成功后 24h，失败按 5→30 分钟指数退避）。
- **taskType**：默认 `auxiliary`。**system（逐字）**：

```
你是角色卡补全助手。只返回JSON。
```

- **user 拼装**（逐字骨架，`labels` = `中文标签(key)`、`existing` = 已有字段 `"key":"value"` 且 value `trimText 300`）：

```
请只补全下列【当前为空】的字段：<labels>。
要求：只补空字段，绝不修改或覆盖已有设定（已有设定是绝对权威，不得改写、润色或替换）；只根据已知信息合理补写，没有把握的字段直接省略；不要编造与已有设定冲突的内容。<speakingStyle 示例规则><userAddress 规则>
已有设定:
{<existing>}
只返回JSON对象，键为字段英文名，值为补写内容。
```

  - 补 `speakingStyle` 时插入逐字规则：`speakingStyle 必须写成“整体调性描述；示例：<台词1> / <台词2> / <台词3>”：三条示例台词必须是该角色真的会说的口语短句，要体现口头禅、句尾助词、标点习惯与对用户的称呼，三条之间差异明显（能看出是同一个人、但场景不同）；**禁止换行，三条之间只能用 " / " 分隔**，整个字段不超过 200 字。`
  - 补 `userAddress` 时插入逐字规则：`“对用户的称呼”(userAddress) 只填一个简短称呼词（如“明明”“老公”），不带任何解释。`
- **期望输出**：`{"<字段英文名>":"内容"}`；每个字段再走 `cleanFieldValue`（长度/句式收敛，见 `AppLimits.StaticProfile`：短字段 40 / 叙述字段 120 / 背景 250 字）与 `normalizeUserAddress`。

---

## 13. 补全群成员字段（`auxiliary`）

- **触发**：启动维护 / 手动；每次最多 3 名成员。**sessionId 用群组 id**。
- **system**：同 `FILL_SYSTEM`。
- **user 拼装**（逐字骨架）：

```
所属群组：<群组名>（<description 前 200 字>）
群内其他成员：<名：personality 前 60 字；…|（无）>
请只补全该成员下列【当前为空】的字段：<labels>。
要求：只补空字段，绝不修改或覆盖已有设定（已有设定是绝对权威，不得改写、润色或替换）；只根据已知信息合理补写，没有把握的字段直接省略；不要编造与已有设定冲突的内容。<speakingStyle 示例规则><该成员的说话方式必须与群内其他成员显著不同（看台词就能分辨是谁）。><userAddress 规则>
已有设定:
{<existing>}
只返回JSON对象，键为字段英文名，值为补写内容。
```

- **期望输出 / 解析**：同第 12 节。

---

## 14. 世界观迁移：背景 → 世界书（一次性）

- **触发**：用户手动迁移（已有 `lorebookMigratedAt` 或源文本 <40 字则跳过）。
- **system**：`LOREBOOK_SYSTEM`。
- **user 拼装**（逐字骨架，`trimmed` = 群组 `description` / 单角色 `background`）：

```
下面是一个<群组前提|角色的个人背景>文本，其中可能混有世界观类内容（时代/世界观、地点、组织、专有名词、历史、规则）。
请把其中的**世界层设定**整理成世界书条目；只整理原文已有的信息，禁止编造或扩写；角色/群组本人的性格、经历、关系不要抽成条目。
每条给出 name（条目名）、keywords（剧情里可能出现的称呼）、content（该条目本身的信息，不要理由与解释）、alwaysActive（世界前提/规则这类需要每轮生效的设 true，其余 false）。最多 6 条；没有世界层内容就返回空数组。
<可选：另外请给出精简后的原文：删掉已经抽成条目的世界观内容，只保留角色/群组本人相关的部分，其他内容一字不改；没有可精简的就原样返回。>
只返回 JSON：{"entries":[{"name":"","keywords":[],"content":"","alwaysActive":false}]<可选 ,"trimmedSource":"">}。

现有世界书条目：<已有条目 JSON|（空）>

<群组前提|个人背景>：
<trimText(trimmed, 2000)>
```

- **期望输出**：`{"entries":[…6 条…]}`，可选 `trimmedSource`（只在更短时采纳，落盘 `trimText 1200`（群组）/`800`（单角色））。

---

## 15. 字段结构重排（一次性）

- **触发**：用户手动迁移（已在 `fieldsMigrationVersion == "1.2.0"` 则跳过）。
- **system**：`FILL_SYSTEM`。
- **user 拼装**（逐字骨架 + 现有内容 JSON）：

```
下面是一个角色/成员的当前字段文本。请把它整理成新版字段结构：
动态状态字段只能是：currentSituation、currentLocation、currentMood、currentOccupation、currentGoal、currentTone；静态设定只保留 background（仅单角色）。
要求：①按语义归入最贴切字段，消除重复；②时间写绝对日期；③只整理已有信息，不编造；④写成自然完整的陈述句。
只返回JSON对象：{"dynamicState":{...},"background":"..."}。
现有内容（JSON）：
{"name":…,"entityType":"group|character","dynamicState":{六个字段},"background":…}
```

- **期望输出**：`{"dynamicState":{…},"background":"…"}`；动态字段 `trimText 700`、单角色 background `800`、群组 description `1200`。

> 注：`「价值观」「关键过往」两个字段已删除`，旧数据由 `core:data/RetiredStaticFields` 在**读取时**并入性格/背景（旧备份由 `LegacyMapper` 合并），不经过本调用。

---

## 16. 文风校对（`auxiliary`）

- **触发**：`detectStyleViolations` 命中且 `AppConfig.styleCritique` 开启。
- **taskType**：默认 `auxiliary`。**system（逐字）**：

```
你是文风校对助手。只返回JSON。
```

- **user 拼装**（逐字骨架，`rules` = 每条 `- <违规中文标签>`）：

```
下面是一段角色扮演回复，以及它违反的文风规则清单。请修订文风。
硬性要求：①只改文风，绝不改动情节、事实、对话含义与人物关系；②保持大体长度与段落数；③不要新增情节要素、不要加解释或旁白；④不得复述规则本身；⑤**被「」『』《》〈〉或引号包裹的内容是引用（别人的话、歌词、诗句、书名/作品名），以及世界书条目名、角色/成员名、对用户的称呼，都必须原样保留、逐字不变**——改写、删减、换序任何一处都会导致整次修订被丢弃。
违反的规则：
<rules>

只返回 JSON：{"reply":"修订后的正文"}，换行写 \n，双引号写 \"。

原文：
<原文>
```

- **期望输出**：`{"reply":"…"}`。
- **确定性回退**（任何一条不满足就保留原文）：非空、有变化、长度 ≤ `critiqueMaxChars`(1600)、长度比在 `[0.5, 2.0]`、且 `keepsProtectedContent`（引用段落逐字未变、世界书条目名/角色名/成员名/称呼未丢失）。

---

## 17. 快速回应修补（`auxiliary`）

- **触发**：每轮（开关开；当前 `quickReplies` 不足两条或不过视角校验）。
- **taskType**：默认 `auxiliary`。**system**（动态，逐字）：

```
你就是这位用户本人，正在手机上和<charName>聊天。只输出用户此刻最可能打出的两句话，不要扮演<charName>，不要写旁白或动作，不要解释。
```

- **user 拼装**（逐字骨架）：

```
【用户最近说过的话，仅用于参考语气，不要照抄】   ← 仅当最近有 >1 条用户消息
<最近 2 条用户消息，各 trimText 80>

<charName>刚对你说：
<trimText(reply, 800)>

请写出你（用户<，对方平时叫你“<称呼>”>）此刻最可能发给他的两句话：
①每句都是用户可以原样发送的消息，是"我"（用户自己）的立场、感受、提问或要求；
②不得是<charName>会说的话，不得是把<charName>刚说的话换个人称复述一遍；
③每条不超过 40 字，不用括号动作、不用 Markdown。
只返回 JSON：{"quickReplies":["句子一","句子二"]}
```

- **期望输出**：`{"quickReplies":[…]}`；解析不足 2 条或仍不过校验 → 回退 `["嗯","继续"]`。

---

## 18. App 层建卡 / 补全调用

### 18.1 建卡与群组升级（`quickGenerate`）

**system（逐字）**：

```
你是角色设计助手。严格按用户在消息里给出的 JSON 结构返回，只输出 JSON，不要任何解释、旁白或代码块标记。
不得使用陈词滥调的人设模板；世界层设定一律写进 lorebook，不要写进角色个人背景。
```

**参数**：`temperature = 1.0`、`max_output_tokens = 1200`、`reasoning.effort = "none"`、`sessionId = "deeptalking-general"`、`auxiliary = true`、无工具。**input** 只有一条用户消息 = 调用方给的 prompt：

- **建角色卡**（`CharacterParity.buildQuickGeneratePrompt(desc, isGroup=false)`）：
  `CHARACTER_PROMPT_PREFIX` + `CHARACTER_QUALITY_RULE` + `SPEAKING_STYLE_SAMPLES_RULE` + `LOREBOOK_CONTENT_RULE` + `\n描述: ` + 用户描述。
  前缀（逐字）：
  ```
  根据以下描述，生成一个角色设定。返回JSON格式，包含这些字段: entityType, name, avatar(emoji), gender, age, race, appearance, personality, fears, background(角色个人经历，不要写世界观), speakingStyle, language, userAddress(角色对用户的称呼，只填一个短称呼词), dynamicState(对象，包含 currentSituation、currentLocation、currentMood、currentOccupation、currentGoal、currentTone), lorebook(数组，2-4 条世界层设定条目，每条 {"name":"条目名","keywords":["触发词"],"content":"命中后注入的世界层设定","alwaysActive":false})。**世界层设定（时代/世界观、地点、组织、专有名词、历史、规则）一律写进 lorebook，不要写进 background。**
  ```
- **建群组卡**（`isGroup=true`）：`GROUP_PROMPT_PREFIX` + 同样三条规则 + `\n描述: ` + 描述。前缀（逐字）：
  ```
  根据以下描述生成一个角色群组。只返回JSON：{"entityType":"group","groupInfo":{"name":"群组名","avatar":"emoji","description":"群组前提（这群人是谁、为什么在一起，1-2 句；不要写世界观/时代/地点/组织等世界层设定）","scene":"场景","interactionRules":"成员互动规则"},"lorebook":[{"name":"条目名（地点/组织/专有名词/规则等）","keywords":["触发词"],"content":"命中后注入的世界层设定","alwaysActive":false}],"members":[{"name":"成员名","avatar":"emoji","gender":"性别","age":"年龄","race":"种族","appearance":"外貌","personality":"性格","fears":"恐惧或弱点","background":"背景","speakingStyle":"说话风格","language":"语言","userAddress":"该成员对用户的称呼（一个短称呼词）","roleInGroup":"群内定位","dynamicState":{"currentSituation":"当前处境","currentLocation":"当前位置","currentMood":"当前情绪","currentOccupation":"当前职业/身份","currentGoal":"当前目标","currentTone":"当前语气（供朗读的播报指令，可短可长，通常约60字内）"}}]}。群组的 description/scene 是全体成员共用的前提，必须填写；**世界层设定（时代/世界观、地点、组织、专有名词、历史、规则）一律写进 lorebook（2-4 条），绝不能塞进 description 或成员的 background**；lorebook 条目要能被日后复用，keywords 写剧情里可能出现的称呼（常驻内容把 alwaysActive 设为 true），content 只写该条目本身的信息。至少生成两名成员；每名成员必须尽可能填满所有字段，不能只返回名称和性格；成员之间的说话方式必须显著不同（看台词就能分辨是谁）。
  ```
- 三条共用规则（逐字）：
  - `CHARACTER_QUALITY_RULE`：
    ```
    人设质量要求：①不得使用陈词滥调的名字或模板化人设（如“艾尔德里亚”式的套路奇幻名、“高冷大小姐”“温柔邻家女孩”这类通用模板）；②personality 写具体的行为倾向（遇到事情会怎么做、在意什么），不要堆砌形容词；③background 只写 3-5 条会影响当下互动的要点，不要写编年史。
    ```
  - `SPEAKING_STYLE_SAMPLES_RULE`：同第 12 节的同名规则文本。
  - `LOREBOOK_CONTENT_RULE`：
    ```
    lorebook 每条 content 精炼、一条只讲一件事，**目标 300 字以内**（硬上限 400 字，超过会被丢掉）。
    ```
- **群组升级**（`AppViewModel.upgradeToGroup`，同一 `quickGenerate`）：prompt 顺序为「升级说明 + 返回 JSON 骨架（`groupInfo` / `lorebook` / `additionalMembers` 全字段空串）+ lorebook 与成员补充要求 + `CHARACTER_QUALITY_RULE` + `SPEAKING_STYLE_SAMPLES_RULE` + `\n原角色（禁止重复输出）:\n<原角色 JSON>` + `\n近期对话:\n<最近 20 条，各 take 1000>`」。原角色 JSON 键顺序：`name, avatar, gender, age, race, appearance, personality, fears, background, speakingStyle, language, userAddress`。
- **解析**：`CharacterParity.parseGeneratedDraft`（提取第一个 `{…}`）；lorebook 条目超 400 字**丢弃不截断**，条数 ≤200、keywords ≤8、名称 `take 60`。

### 18.2 头像 emoji（`requestEmojiAvatar`）

**system（逐字）**：

```
你是角色头像助手。只输出一个最能代表该角色的 emoji 字符，不要任何文字、标点或解释。
```

**参数**：`temperature = 1.2`、`max_output_tokens = 16`、`effort = "none"`、`sessionId = "deeptalking-general"`、`auxiliary = true`。**input** = 一条用户消息，内容 = 描述（`[name, appearance, personality]` 非空项用 `，` 连接；空则 `一个神秘角色`）。**输出**：第一个 emoji 码点（扫描 emoji 码区）。

### 18.3 表情包打标签（`tagSticker`）

**system（逐字，`STICKER_TAGS` 内联）**：

```
你是表情包分类助手。看这张表情包或图片，从下列标签中选一个最贴切的：开心、大笑、难过、委屈、生气、无语、惊讶、疑惑、害羞、得意、爱意、亲亲、抱抱、加油、点赞、拒绝、睡觉、干杯。若都不贴切，就给出一个 1 到 6 字的中文短词。只输出这一个词，不要标点、解释或其它文字。
```

**参数**：`temperature = 0.2`、`max_output_tokens = 8`、`effort = "none"`、`auxiliary = true`。**input** = 一条用户消息：文本 `给这张图打一个短词标签。` + 图片附件（经 `imageResolver` 变成 `input_image`）。**输出**：去标点后 `take 6`。

### 18.4 群成员字段补全（`fillMemberFields`，NativeCore 版）

**system（逐字）**：

```
你是角色卡补全助手。只返回 JSON。
只能根据群组设定、现有角色卡和用户的一句话补全空字段，不能覆盖或编造与已有字段冲突的信息。
已有字段是绝对权威，任何情况下不得改写、润色或替换。
返回字段：name, avatar, gender, age, race, appearance, personality, fears,
background, speakingStyle, language, userAddress, roleInGroup,
dynamicState(对象: currentSituation, currentLocation, currentMood, currentOccupation, currentGoal, currentTone)。
只给出有把握的字段，没有把握就省略。成员的说话方式必须与群内其他成员显著不同。
每个字段都要精炼：短字段几十字内（性别/年龄/种族/语言/称呼），外貌/性格/恐惧弱点/说话风格一两句、不超过 120 字，背景故事不超过 250 字；
一律写成明确的简短陈述句，禁止“不是…而是…”这类对比句，禁止括号注释、理由与解释性文字。
```

**参数**：`temperature = 0.8`、`max_output_tokens = 800`、`effort = "none"`、`auxiliary = true`。**input** = 一条用户消息，顺序拼装 `群组信息:`(JSON `name, avatar, description, scene, interactionRules`) + `群组其他成员:`(JSON `name, personality, speakingStyle, roleInGroup`) + `待补全成员:`(JSON `name, avatar, gender, age, race, appearance, personality, fears, background, speakingStyle, language, userAddress, roleInGroup, dynamicState{六字段}`) + `用户补充:` + hint + `SPEAKING_STYLE_SAMPLES_RULE`。**输出**：只填**空**字段的 JSON。

---

## 19. 远程语音合成（`POST {base}/v1/audio/speech`）

**端点**：`speechUrl(base) = base.trimEnd('/') + "/v1/audio/speech"`，`base = config.normalizedBaseUrl`。

**Header**：

| Header | 值 |
|---|---|
| `Accept` | `audio/L16` |
| `User-Agent` | `DeepTalking-Lite/<version>` |
| `Authorization` | `Bearer <token>`，**仅当**配置了访问口令（自动去掉用户粘贴的 `Bearer ` 前缀） |
| `Content-Type` | `application/json; charset=utf-8` |

**请求体**（字段顺序即下）：

```json
{
  "model": "cosyvoice-3",
  "voice": "alloy",
  "input": "<朗读文本（extractSpeechText 后的纯台词）>",
  "response_format": "pcm",
  "stream": false,
  "speed": 1.0,
  "instructions": "<本轮 currentTone，空则省略>",
  "chunk_tokens": 28,
  "temperature": 1.0,
  "top_k": 25,
  "top_p": 0.8,
  "win_size": 10,
  "tau_r": 0.1,
  "min_token_text_ratio": 2.0,
  "max_token_text_ratio": 20.0
}
```

- `speed` 夹到 `[0.5, 2.0]`；`instructions` 即角色当前语气（群聊按发言人分段，每段用该成员的 `currentTone`）。响应体是 PCM16 裸流，按 8192 字节读取切块。
- 超时：连接 4 s、读空闲 60 s、**无总超时**。
- 同模块其他端点：探测 `GET {base}/v1/models` → `/healthz` → `/ping`；状态 `GET {base}/status`（取 `sample_rate`）；音色列表 API 模式 `GET {base}/healthz` 的 `voices`、WebUI 模式 `GET {base}/speaker`；上传参考音色 `POST {base}/v1/voices?name=<URL编码>`（body 为 gguf 原始字节，`Content-Type: application/octet-stream`）。

---

## 20. 联网抓取（工具触发的 HTTP）

所有请求走 `HttpWebContentProvider`（读超时 20 s；DoH DNS 可开）。`BROWSER_UA` = `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36`，仅在下表标注处发送。

| 工具 | 方法 + URL | 特殊 Header | 超时 | 返回 |
|---|---|---|---|---|
| `web_search` | GET `https://www.bing.com/search?format=rss&q=<query 截断 200>` | — | 15 s | `{"ok":true,"query","results":[{"title","url","description"}]}`（title ≤160、desc ≤400） |
| `search_video` | GET `https://api.bilibili.com/x/web-interface/search/all/v2?keyword=<q>&page=1` | `Referer: https://www.bilibili.com/` | 15 s | `{"ok":true,"platform":"bilibili","keyword","count","results":[{"bvid","title","author","play","duration","url","cover"}]}` |
| `web_fetch`（B站链接） | GET `…/x/web-interface/view?bvid=<id>`（`b23.tv` 先解析短链） | Referer bilibili | 15 s | 视频详情（+ 有封面时包成 `input_text`+`input_image`） |
| `baidu_hot` | GET `https://top.baidu.com/api/board?platform=wise&tab=realtime` | `User-Agent: BROWSER_UA` | 15 s | `{"ok":true,"platform":"baidu","count","items":[{"title","url","hot"}]}` |
| `toutiao_hot` | GET `https://www.toutiao.com/hot-event/hot-board/?origin=toutiao_pc` | BROWSER_UA + `Referer: https://www.toutiao.com/` | 15 s | 同上 items |
| `douyin_hot` | GET `https://www.iesdouyin.com/web/api/v2/hotsearch/billboard/word/` | BROWSER_UA | 15 s | 同上 items |
| `zhihu_hot` | GET `https://api.zhihu.com/topstory/hot-lists/total?limit=50` | BROWSER_UA + `Referer: https://www.zhihu.com/hot` | 15 s | 同上 items（问题链接改写为 `https://www.zhihu.com/question/<id>`） |
| `search_music` | GET `https://music.163.com/api/search/get?s=<q>&type=1&limit=<n>` | BROWSER_UA + `Referer: https://music.163.com/` | 15 s | `{"ok":true,"platform":"netease","keyword","count","results":[{"id","name","artists","album"?,"url"}],"note":"要听热评可用 web_fetch 打开该歌曲链接"}` |
| `search_posts` | GET `https://bbs.hupu.com/search?q=<q>` | BROWSER_UA | 20 s | `{"ok":true,"platform":"hupu","keyword","count","results":[{"title","url","meta"?}]}` |
| `web_fetch`（虎扑帖子） | GET `https://bbs.hupu.com/<tid>.html` | BROWSER_UA | 20 s | 帖子正文（≤3000）+ 回帖（每条 ≤400）+ 图片 |
| `web_fetch`（网易云歌曲） | GET `https://music.163.com/api/song/detail/?ids=[<id>]` + `…/api/v1/resource/comments/R_SO_4_<id>?limit=20&offset=0` | BROWSER_UA + Referer netease | 15 s | 歌曲信息 + 热评（每条 ≤300） |
| `web_fetch`（通用网页） | GET 目标 URL | — | 20 s | 正文 ≥80 字 → `{"ok":true,"url","content"(≤6000)}`；图片响应 → `input_text`+`input_image` |
| `web_fetch`（摘要兜底） | GET `https://api.microlink.io/?url=<目标>` | — | 12 s | `{"ok":true,"via":"microlink","url","title","description"(≤600),"publisher","author","date","image"}`（有图则包成部件数组） |

**上限**：`MAX_SEARCH_RESULTS=20`、`MAX_HOT_TOPICS=30`、`MAX_SOCIAL_RESULTS=20`、`NETEASE_COMMENT_LIMIT=20`。所有失败都降级为 `{"ok":false,…,"reason":…}`，不抛异常。

---

## 21. 其他外发调用

| 用途 | 方法 + URL | 说明 |
|---|---|---|
| DoH DNS | POST `https://dns.alidns.com/dns-query` 或 `https://doh.pub/dns-query` | body 为 RFC 8484 线格式查询，`Accept: application/dns-message`；DoH 主机名用固定引导 IP 解析，避免被劫持；结果缓存 5 分钟，失败回落系统 DNS |
| CosyVoice3 模型下载 | GET `https://modelscope.cn/models/Lourdle/Fun-CosyVoice3-0.5B-2512-GGUF/resolve/master/…` | 三个文件（主 gguf 629 MB、speech_tokenizer、campplus）；`User-Agent: DeepTalking-Lite/1.5`（硬编码）；连接 30 s / 读 60 s |
| API 连接自检 | GET `{base}/` + POST `{responsesEndpoint}` | 探测请求体固定为 `{"model":…,"input":[{"role":"user","content":"hi"}],"max_output_tokens":50,"stream":false,"reasoning":{"effort":"none"}}` |

> 没有更新检查、也没有第三方头像/表情包服务：头像由第 18.2 的 LLM 调用产生，表情包标签由第 18.3 的视觉调用产生。

---

## 22. 关键常量索引

| 常量 | 值 | 位置 |
|---|---|---|
| `AppLimits.Api.AUXILIARY_TIMEOUT_MS` | 90000 | `core/common/AppLimits.kt` |
| `AppLimits.Api.AUXILIARY_MAX_OUTPUT_TOKENS` | 8192 | 同上 |
| `AppLimits.Agent.MAX_TOOL_ROUNDS` / `MAX_TOOL_CALLS` / `TOOL_MEMORY_INJECT_LIMIT` | 5 / 12 / 5 | 同上 |
| `AppLimits.Memory.INSTANT` / `SHORT_TERM` / `INSTANT_TRIM_FLOOR` | 60 / 40 / 20 | 同上 |
| `AppLimits.StaticProfile.SHORT_CHARS` / `PROSE_CHARS` / `BACKGROUND_CHARS` | 40 / 120 / 250 | 同上 |
| `AppLimits.Lorebook.CONTENT_CHARS` / `ENTRIES` / `INJECT_ENTRIES` | 400 / 200 / 6 | 同上 |
| 主对话超时 / 流式空闲 / 辅助超时 | 180 s / 60 s / 90 s | `core/network/ResponsesLlmBackend.kt` |
| `STYLE_GUARD.critiqueMaxChars` / 长度比 | 1600 / 0.5–2.0 | `domain/agent/background/StyleGuard.kt` |
