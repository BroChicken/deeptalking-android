# 解析机制关系图谱（模型友好版）

> 目的：任何"改字段 / 改提示词 / 改解析"的改动，先按本图谱定位会连带影响的机制链，避免引入新问题。
> 机器可读镜像见文末 `GRAPH` 代码块（YAML）。代码锚点用**模块:函数名**而非行号（行号会漂移，用 grep 函数名定位）。

## 0. 设计铁律（改动的硬约束）

- **INV-1 单点解析**：所有"模型输出 → 结构化对象"都经 `ResponseParser.parseJsonLenient`（`:domain:agent`）。改它=改全系统。
- **INV-2 顶层类型契约**：主回复解析必须返回对象（`parseTurnFromText` → `buildTurn` 只接受 `JsonObject`）。返回数组=旧 JSON 兼容路径失效（静默失败）。
- **INV-3 不改字段名集合**（`Prompts.DYNAMIC_STATE_FIELDS` / `STATIC_PROFILE_FIELDS`）而不检查下游：工具 schema、清洗、迁移、UI、提示词全部由这两个常量驱动。
- **INV-4 前缀缓存稳定**：system 固定在前、历史原样递增、volatile 只挂当前用户消息；任何"每轮变化的文本塞进 system 前缀"都会击穿缓存。
- **INV-5 FINAL 相无工具**：`AUTO` 拿不到正文就转 `FINAL`（`tools` 为空、`toolChoice=Auto`、`reasoning.effort=none`），FINAL 空回合最多重试 2 次；回合只在这两相之间流转。
- **INV-6 待办与长期记忆独立，准入须「明确日期 + 双方同意」**（`TodoPolicy.validateTodoCreation` / worker 的 `submit_state.todos`）；`promises` 是叙事记忆、仍需用户原话证据（`isValidAutomaticMemory`）。
- **INV-7 正文优先，不为 quickReplies 重发**：主调用只产出纯文本 + `【语气】` 行；快速回应由 turn worker 生产（**恰好 2 条**，多则留前两条、少则整组弃用），失败不影响正文；后台换位生成**只在 worker 未成功时**兜底。
- **INV-8 快速回应必须是用户视角**：`StyleGuard.detectQuickReplyIssues` 校验，worker 未成功时由 `BackgroundTasks.repairQuickReplies`（换位生成）重写；不得覆盖已显示的正文。
- **INV-9 群组分段不改历史**：`ChatMessage.segments` 只服务渲染/朗读，`content` 始终是全文；历史与 `instant` 窗口把一轮群聊当**一条**消息。

## 1. 核心机制链（主链）

```
模型原始输出 fullText
        │
        ▼
  ResponseParser.parseTurnFromText(text)       ★主链★
        ├─ StreamText.stripHiddenText            剥离 <MEM_UPDATE> 残留（仅显示用途）
        ├─ 整段是单个 JSON 对象？→ parseJsonLenient → buildTurn（旧格式兼容）
        └─ 否则 → ToneText.split：正文 + 末尾【语气】行
        │
        ▼
  ParsedTurn { reply, tone }                    正文立即显示
        │
        ├─ groupSpeechSegments（群组）→ ChatMessage.segments（逐成员气泡；content 仍是全文）
        ├─ AgentLoop.salvageText（空文本时）→ extractResponsesText / 内嵌 reply JSON / extractAnyResponseText
        │
        ▼
  TurnWorker.parse(result)                      强制 function call：submit_state
        ├─ dynamicState（本体不含 currentTone） → applyDynamicStateFields
        ├─ memberDynamicState（群组）        → 对应成员 dynamicState（含成员 currentTone）
        ├─ quickReplies（恰好 2 条）          → 快速回应按钮（不足 2 条则弃用、无按钮）
        ├─ todos                             → TodoPolicy.validateTodoCreation / applyTodoUpdate（detail 可空）
        └─ promiseUpdates                    → applyPromiseUpdate
        │
        ▼
  FieldRevision.detect → 单字段修订（用户显式改字段时）
```

## 2. 全部调用方（谁受 `parseJsonLenient` 影响）

| 调用点 | 期望返回 | 若返回数组/null 的后果 |
|---|---|---|
| `parseTurnFromText`（正文即终稿） | 对象（仅旧格式兼容路径） | 退回纯文本 + 语气行，正文照常显示 |
| `TurnWorker.parse` | 对象（`submit_state` 参数） | 本轮结构化增量全部丢弃（正文不受影响） |
| `StreamText.extractReplyFromJson`（流式气泡/salvage） | 字符串 | 气泡为空 → `AgentLoop.salvageText` 抢救 |
| 角色/群组生成、成员补全、群组升级、头像描述、话题 | 对象 | 生成失败（`CharacterParity.parseGeneratedDraft` 等） |
| AI 字段迁移 / 世界书迁移（`NativeCore.runWorldLoreMigration` / `remapCharacterFields`） | 对象 | 迁移失败回退本地合并 |
| 世界书整理 / 抽取 / 分析 / 场景 / 快速回应修补（辅助调用） | 对象或数组（按任务约定） | 任务失败，按各自退避重试；不改已显示正文 |

## 3. 字段集合驱动链（改字段时）

字段常量在 `:domain:agent` 的 `prompts/Prompts.kt`。

```
DYNAMIC_STATE_FIELDS ──┬─► TurnWorker SCHEMA（submit_state 的 dynamicState/memberDynamicState）
                       ├─► ChatOrchestrator.applyDynamicStateFields（落盘校验）
                       ├─► FieldCleaning.cleanFieldValue / CharacterParity（清洗）
                       ├─► buildSystemPrompt / buildVolatileContext（提示词注入与状态块）
                       ├─► LegacyMapper.mapDynamicState（导入旧数据）
                       ├─► 角色编辑器「当前状态」页签（CharactersScreen）
                       └─► 生成/补全/升级提示词里的字段清单（CharacterParity）

STATIC_PROFILE_FIELDS ─┬─► TurnWorker 不含静态字段（成员/本体字段都不由 worker 写）
                       ├─► FieldRevision.detect 的别名表 / FieldRevision.revise（单字段修订）
                       ├─► buildSystemPrompt（角色卡注入与成员块）
                       ├─► buildVolatileContext 的话题库 / BackgroundTasks.autoFillStaticFields
                       ├─► 角色编辑器「基础」页签
                       └─► LegacyMapper 的旧字段迁移（worldView→background 等）
```

> 原生 `DYNAMIC_STATE_FIELDS` 为 **6 项**：`currentSituation` / `currentLocation` / `currentMood` / `currentOccupation` / `currentGoal` / `currentTone`。本体：前 5 项由 turn worker 写，`currentTone` 来自主调用末尾的 `【语气】` 行；群组：共享状态只有 `currentSituation` / `currentLocation`，成员的完整状态（**含各自 `currentTone`**）由 `memberDynamicState` 写入。所有 `currentTone` 都免证据门。
>
> 群组分段不走 `parseJsonLenient`：`parseGroupSpeechSegments`（`:core:model` 的 `GroupSpeech.kt`）是纯文本行解析，只切气泡/发言人，失败也只退化成单个无语气段，不影响正文、历史与窗口。

## 4. 已知脆弱点与对应加固

| ID | 脆弱点 | 触发 | 后果 | 加固 |
|---|---|---|---|---|
| F1 | 自由文本字段修复不感知引号配对/嵌套 | 字段值内含未转义 `"` 且后接 `}`/`]`/`"key":` | 字段被提前截断 → JSON 非法 → L5 把数组当合法 → `quickReplies` 丢（ARRAY 返回） | `repairFreetextFieldsByScan`（引号配对感知扫描 + `hasLaterCandidate` 消歧）＋ `repairStringArrayField` 逐项修数组；旧策略保留为下限兜底 |
| F2 | 解析可能"成功"返回非预期顶层类型 | 同上 | 静默失败，调用方按对象用则读到空 | `parseTurnFromText` / `TurnWorker.parse` 的顶层类型守卫（只接受 `JsonObject`；后者解析不出就当本轮无增量） |
| F3 | 快速回应仅在 `memUpdate` 为对象时走文本兜底 | memUpdate 为数组/null | 快速回应不兜底 | 由 F1+F2 覆盖（返回对象后正常） |

### F1 修复算法
1. 对每个自由文本字段，从其开引号起扫描，收集"其后是合法 JSON 后继"的引号候选。
2. 选结束引号时加消歧：`}`/`]` 型后继若其**后面仍存在候选**，则跳过后面的更优候选（真结束引号通常是本字段最靠后、结构自洽者）。
3. 数组型字段（`quickReplies`）用 `repairStringArrayField` 单独修每个字符串项。
4. `repairFreetextFields` 汇总多个候选修复串，返回**第一个能整段解析成功**的；全失败则退回旧策略（不劣于修复前）。

## 4.5 请求体布局与 AUTO / FINAL 路径

### 请求体（Responses API，`ResponsesLlmBackend.buildBody`；由 `RequestBuilder.build(phase, …)` 装配）
```
{
  model, instructions(=system提示词，单独提顶层), input(=对话+工具回传),
  stream, temperature, max_output_tokens(8192), reasoning.effort,
  tools: [...],        // ★工具定义在顶层，与 input/instructions 平级
  tool_choice: 'auto'
}
```
- **AUTO 相（正常路径）**：`tools = Tools.defaultTools()`（8 个），`tool_choice: Auto`。模型若发起工具调用，**这一轮的正文丢弃**，只执行工具、回传结果、`continue`。**同一条响应里的全部 `tool_calls` 都在本轮执行**（每一条都必须配一个 `function_call_output`，否则下一轮会带着孤立的 `function_call`）；`MAX_TOOL_ROUNDS`(5) 按**响应数**计，`MAX_TOOL_CALLS`(12) 目前不参与判定。
- **FINAL 相（唯一收尾相）**：`tools` 为空（传输层省略 `tools`），`tool_choice: Auto`，`reasoning.effort='none'`；正文以纯文本返回。AUTO 拿不到正文、或工具轮达上限时转入。
- turn worker 是**另一条请求**：`tools=[submit_state]`、`tool_choice={type:'function', name:'submit_state'}`、`stream=false`、`reasoning.effort='none'`、`auxiliary=true`。

### 产出路径（正文优先、worker 不阻塞）
```
主路径一：FINAL 返回纯文本 → ToneText.split → 正文 + 【语气】
        └─ 正文立即采用；语气写 dynamicState.currentTone
主路径二：整段文本是单个 JSON 对象（旧格式/缓存遗留）→ buildTurn 兼容解析
        └─ 只取 reply 与 tone，其余字段照旧落盘
空回合：FINAL 无正文 → 重试（≤2 次）→ 仍空则本轮失败提示
```
- quickReplies 由 turn worker 的 `submit_state` 提供（恰好两条、用户视角；多则留前两条、少则整组弃用并记 note）；**worker 未成功时**才由 `BackgroundTasks.repairQuickReplies` 换位重写（`quickReplyRepairEnabled = config.quickReplyRepair && !worker.ok`），失败就不出按钮。
- 群组回复在同一条消息上附带 `segments`（`parseGroupSpeechSegments`）：只用于逐成员气泡与朗读，`content` 与历史序列化保持不变。
- 主调用不写 `longTerm` / `shortTerm`：记忆只由后台抽取/分析与 `memory_write` 工具产生。

## 4.6 待办（todo）准入原则

- **定义**：待办 = 用户与角色之间、**有明确日期**且**双方同意**的一件待办；是**独立实体**（`Character.todos`），与长期记忆互相独立。
- **准入**（`TodoPolicy.validateTodoCreation`，唯一入口是 turn worker 的 `submit_state.todos` 里 `op=create`）：①期限必填且能在对话原话里定位；②用户发起→用户原话含期限与提议；③角色发起→必须附**用户应答的原话**，且该原话不得是明确拒绝（`isUserRefusal` 否决，`isUserAgreement` 只作正向信号；**同意与否以模型的判断为主，关键词只做一环**）。`title` 是唯一必填文本，`detail` 可空（空时用 `title` 补位，整条不会因此被丢）；`sourceMessageIds`+`evidence` 经 `MemoryEvidence.hasValidUserEvidence` 校验。
- **禁止**：没有明确日期、期限定位不到、或角色单方叮嘱而用户没答应的，一律拒绝（`applyWorkerTodo` 跳过该条）。
- **生命周期**：改期/完成/取消由同一 `submit_state.todos` 的 `reschedule`/`done`/`cancel` 处理（须引用户原话，改期还要能定位，走 `applyTodoUpdate`）；角色编辑页待办 tab 提供「完成 / 取消 / 删除」手动兜底。
- **与 promises 区分**：`promises` 是叙事记忆，走 `isValidAutomaticMemory`（来源须为用户消息），**不产生待办**。

## 5. 改动检查清单（每次改前必读）

- [ ] 是否碰到 `parseJsonLenient` / `repairFreetextFields*`？→ 跑下面的"自测样例"（含未转义引号、真实换行、嵌套对象）。
- [ ] 是否改 `DYNAMIC_STATE_FIELDS` / `STATIC_PROFILE_FIELDS`？→ 逐一核对第 3 节所有下游 + 迁移 + 提示词 + 编辑器。
- [ ] 是否改 system prompt 文本？→ 检查前缀缓存（INV-4）；改 `RULE_A` 之类文本会让每个角色一次性破缓存。
- [ ] 是否改 `versionName`？→ 同步 `versionCode`，跑 `tools/native-verify.ps1`。
- [ ] 是否新增 `parseJsonLenient` 调用？→ 检查返回类型守卫。
- [ ] 是否改请求体阶段（`RequestBuilder`/`ResponsesLlmBackend`）？→ 核对 FINAL 相 tools 为空、`tool_choice=auto`（INV-5）。
- [ ] 是否改待办（todo）准入？→ 必须保留「明确日期 + 双方同意」约束（INV-6）；改 promises 自动写入则保留"用户原话证据"约束。
- [ ] 是否改 `submit_state` 字段？→ 同步 `TurnWorker.SCHEMA`、`TurnWorker.parse`、`ChatOrchestrator.runTurnWorker` 与 `OUTBOUND_CALLS_V2.md` 附录 B-1。
- [ ] 是否改群组回复格式（`成员名：内容`）？→ 同步 `parseGroupSpeechSegments`（气泡）与 `extractSpeechSegments`（朗读），并确认历史/窗口仍按**一条**消息计数（INV-9）。

## 6. GRAPH（YAML 镜像）

```yaml
invariants:
  INV-1: {rule: single-parse-entry, at: ResponseParser.parseJsonLenient}
  INV-2: {rule: main-reply-must-be-object, guard: parseTurnFromText|TurnWorker.parse}
  INV-3: {rule: field-set-change-requires-downstream-audit, keys: [DYNAMIC_STATE_FIELDS, STATIC_PROFILE_FIELDS]}
  INV-4: {rule: prefix-cache-stability}
  INV-5: {rule: final-phase-has-no-tools, why: 'FINAL terminates the turn with plain text; an empty FINAL round is retried at most twice'}
  INV-6: {rule: todo-requires-date-and-mutual-agreement, why: 'todo is independent of long-term memory; a character instruction with no user agreement must not become a to-do'}
  INV-7: {rule: body-first-never-refetch-for-quickReplies, why: 'quick replies come from the turn worker (exactly 2), never from an extra main-call round; the legacy repair round only runs when the worker did not succeed'}
  INV-8: {rule: quickReplies-must-be-user-viewpoint, guard: StyleGuard.detectQuickReplyIssues, repair: BackgroundTasks.repairQuickReplies}
  INV-9: {rule: group-segments-never-change-history, why: 'ChatMessage.segments only drives bubbles/read-aloud; content stays the full text and the window counts one reply as one message'}

nodes:
  parseJsonLenient:        {kind: function, role: hub, risk: high}
  repairFreetextFieldsByScan:{kind: function, role: repair, risk: high, defect: F1}
  repairFreetextFieldsLegacy:{kind: function, role: fallback, risk: medium, defect: F1}
  safeJson:                {kind: function, role: fallback, risk: medium, defect: F2}
  decodeJsonEscapes:       {kind: function, role: helper, risk: low}
  parseTurnFromText:       {kind: function, role: main-reply, risk: high}
  buildTurn:               {kind: function, role: legacy-json-compat, risk: medium}
  ToneText.split:          {kind: function, role: reply-tone-split, risk: high}
  parseGroupSpeechSegments:{kind: function, role: group-bubble-split, risk: medium}
  extractSpeechSegments:   {kind: function, role: group-tts-split, risk: medium}
  TurnWorker.parse:        {kind: function, role: worker-output, risk: high}
  StreamText.extractReplyFromJson:{kind: function, role: stream-bubble, risk: medium}
  ChatOrchestrator.applyMemory:{kind: function, role: memory-write, risk: high}
  ChatOrchestrator.runTurnWorker:{kind: function, role: worker-apply, risk: high}
  applyDynamicStateFields: {kind: function, role: dynamic-write, risk: medium}
  applyMemberDynamicState: {kind: function, role: member-dynamic-write, risk: medium}
  FieldRevision.detect:    {kind: function, role: static-field-revise, risk: medium}
  FieldEditRegistry:       {kind: object, role: focused-field-guard, risk: medium}
  DYNAMIC_STATE_FIELDS:    {kind: const, role: field-set, risk: high}
  STATIC_PROFILE_FIELDS:   {kind: const, role: field-set, risk: high}

edges:
  - {from: model_output,             to: parseTurnFromText}
  - {from: parseTurnFromText,        to: ToneText.split}
  - {from: parseTurnFromText,        to: buildTurn, note: 'only when the whole text is a single JSON object'}
  - {from: buildTurn,                to: parseJsonLenient}
  - {from: TurnWorker.parse,         to: parseJsonLenient}
  - {from: ChatOrchestrator.runTurnWorker, to: TurnWorker.parse}
  - {from: ChatOrchestrator.runTurnWorker, to: applyDynamicStateFields}
  - {from: ChatOrchestrator.runTurnWorker, to: applyMemberDynamicState, note: 'group members, currentTone included'}
  - {from: parseTurnFromText,        to: parseGroupSpeechSegments, note: 'groups only; display bubbles'}
  - {from: parseGroupSpeechSegments, to: 'ChatMessage.segments'}
  - {from: parseGroupSpeechSegments, to: extractSpeechSegments, note: 'read-aloud re-splits and cleans the text'}
  - {from: FieldEditRegistry,        to: [applyDynamicStateFields, applyMemberDynamicState, FieldRevision.detect, autoFillStaticFields], note: 'focused fields are skipped'}
  - {from: parseJsonLenient,         to: StreamText.extractReplyFromJson}
  - {from: parseJsonLenient,         to: 'LegacyMapper.field-migration'}
  - {from: parseJsonLenient,         to: 'CharacterParity.generate/fill/upgrade'}
  - {from: repairFreetextFieldsByScan, to: parseJsonLenient, note: 'used by L3 and stream salvage'}
  - {from: parseTurnFromText,        to: ChatOrchestrator.applyMemory}
  - {from: ChatOrchestrator.applyMemory, to: applyDynamicStateFields}
  - {from: DYNAMIC_STATE_FIELDS,     to: [TurnWorker.SCHEMA, applyDynamicStateFields, FieldCleaning.cleanFieldValue, buildVolatileContext, LegacyMapper.mapDynamicState, editor-state-tab, prompts]}
  - {from: STATIC_PROFILE_FIELDS,    to: [FieldRevision.detect, buildSystemPrompt, buildVolatileContext, autoFillStaticFields, editor-basic-tab, LegacyMapper]}

failure_modes:
  F1: {cause: unescaped-quote-then-brace, effect: premature-field-cut, cascade: 'json-invalid -> array-returned -> quickReplies-lost'}
  F2: {cause: fallback-picks-array-shape, effect: wrong-toplevel-type, cascade: 'silent-undefined'}
```

## 7. 自测样例（改解析后必跑）

输入（ASCII，未转义引号）：
```
{"reply":"x","quickReplies":["a","b"],"dynamicState":{"currentMood":{"value":"ok","evidence":"quote "here""},"currentGoal":{"value":"g"}}}
```
期望：`parseJsonLenient` 能修复为**对象**且 `quickReplies==["a","b"]`（修复前会返回数组，`quickReplies` 为空）。对应的回归用例在 `:domain:agent` 的 `ResponseParserTest` / `ResponseParserSalvageTest`。

群组分段样例（改 `parseGroupSpeechSegments` 后必跑）：`"张三：走吧。\n（雨更大了）\n李四：再等等。"` → 两个段（`张三` / `李四`），`（雨更大了）` 留在第一段的文本里；把成员名换成一个不存在的名字 → 只有一个空 speaker 的段，且 `content` 不受影响。回归用例在 `:core:model` 的 `GroupSpeechTest` / `SpeechTextTest`。
