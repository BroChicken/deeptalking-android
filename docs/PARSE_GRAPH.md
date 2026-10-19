# 解析机制关系图谱（模型友好版）

> 目的：任何"改字段 / 改提示词 / 改解析"的改动，先按本图谱定位会连带影响的机制链，避免引入新问题。
> 机器可读镜像见文末 `GRAPH` 代码块（YAML）。代码锚点用**模块:函数名**而非行号（行号会漂移，用 grep 函数名定位）。

## 0. 设计铁律（改动的硬约束）

- **INV-1 单点解析**：所有"模型输出 → 结构化对象"都经 `ResponseParser.parseJsonLenient`（`:domain:agent`）。改它=改全系统。
- **INV-2 顶层类型契约**：主回复解析必须返回**对象**（`parseSubmitResponse` / `parseTurnFromText` → `buildTurn` 只接受 `JsonObject`）。返回数组=快速回应/记忆全丢（静默失败）。
- **INV-3 不改字段名集合**（`Prompts.DYNAMIC_STATE_FIELDS` / `STATIC_PROFILE_FIELDS`）而不检查下游：工具 schema、清洗、迁移、UI、提示词全部由这两个常量驱动。
- **INV-4 前缀缓存稳定**：system 固定在前、历史原样递增、volatile 只挂当前用户消息；任何"每轮变化的文本塞进 system 前缀"都会击穿缓存。
- **INV-5 阶段二只用于兜底**：阶段一（含 `submit_response` 工具）拿到正文就直接收尾；只有"连正文都没有"才进阶段二锁定 `submit_response`。
- **INV-6 待办与长期记忆独立，准入须「明确日期 + 双方同意」**（`TodoPolicy` / `add_todo`）；`promises` 是叙事记忆、仍需用户原话证据（`isValidAutomaticMemory`）。
- **INV-7 正文优先，不为 quickReplies 重发**：合法 JSON（即使缺 quickReplies）或散文都直接采用；快速回应由后台补齐，绝不再为它多发一轮。
- **INV-8 快速回应必须是用户视角**：`StyleGuard.detectQuickReplyIssues` 校验，失败由 `BackgroundTasks.repairQuickReplies`（换位生成）重写；不得覆盖已显示的正文。

## 1. 核心机制链（主链）

```
模型原始输出 fullText / function_call.arguments
        │
        ▼
  ResponseParser.parseJsonLenient(text)        ★中枢★  (返回值类型不定：对象 / 数组 / null)
        ├─ L1 stripFences → JSON.parse 严格
        ├─ L2 removeTrailingCommas → JSON.parse
        ├─ L3 repairFreetextFieldsByScan         ← 自由文本引号/换行修复（引号配对感知扫描）
        ├─ L4 repairFreetextFieldsLegacy         ← 旧策略兜底（不劣于修复前）
        ├─ L5 safeJson(切片 {..} / [..])          ← 可能把数组当合法 JSON 返回（INV-2 违例源）
        └─ L6 safeJson(全文)
        │
   ┌────┴───────────────────────┬──────────────────────────┐
   ▼                            ▼                          ▼
parseSubmitResponse        StreamText.extractReplyFromJson   parseQuickReplyList /
（主回复：必须对象）         （流式气泡 + salvage）            parseQuickRepliesFromText
   │                            │                          │
   ▼                            ▼                          ▼
ChatOrchestrator.applyMemory ─► applyDynamicStateFields   detectQuickReplyIssues（视角校验）
   ├ shortTerm（不由主对话写）        │                          │
   ├ longTerm / promises              ▼                          ▼
   ├ dynamicState（6 字段）      memberDynamicState       通过 → quickRepliesState
   └ memberDynamicState                                 不通过 → repairQuickReplies
        │                                                  （后台换位生成，不改正文）
        ▼
ResponseParser.parseMemoryFromText（从 reply 文本抓 <MEM_UPDATE>）
        │
        ▼（散文收尾时）
BackgroundTasks.extractProseTurnMemory（后台补 shortTerm/longTerm/dynamicState，不阻塞正文）
```

## 2. 全部调用方（谁受 `parseJsonLenient` 影响）

| 调用点 | 期望返回 | 若返回数组/null 的后果 |
|---|---|---|
| `parseSubmitResponse`（主回复工具参数） | 对象 | 判为"无合法提交"→ 走阶段二强制 submit；快速回应丢 |
| `parseTurnFromText`（正文即终稿） | 对象 | 同上 |
| `parseQuickReplyList` / `parseQuickRepliesFromText` | 数组 | 快速回应 fallback，交给后台换位生成 |
| `StreamText.extractReplyFromJson`（流式气泡/salvage） | 字符串 | 气泡为空 → `AgentLoop.salvageText` 抢救 |
| `parseMemoryFromText` ← `<MEM_UPDATE>` | 对象 | 记忆不落盘 |
| `submit_response` 工具参数解析 | 对象 | 记忆/回复字段缺失 |
| `ask_user` 参数解析 | 对象 | 澄清问题丢失（`AgentLoop.syntheticSubmitReply` 兜底） |
| 角色/群组生成、成员补全、群组升级、头像描述、话题 | 对象 | 生成失败（`CharacterParity.parseGeneratedDraft` 等） |
| AI 字段迁移 / 世界书迁移（`NativeCore.runWorldLoreMigration` / `remapCharacterFields`） | 对象 | 迁移失败回退本地合并 |

## 3. 字段集合驱动链（改字段时）

字段常量在 `:domain:agent` 的 `prompts/Prompts.kt`。

```
DYNAMIC_STATE_FIELDS ──┬─► submit_response schema (SubmitResponseTool.buildSchema)
                       ├─► ChatOrchestrator.applyDynamicStateFields（落盘校验）
                       ├─► FieldCleaning.cleanFieldValue / CharacterParity（清洗）
                       ├─► buildSystemPrompt / buildVolatileContext（提示词注入与状态块）
                       ├─► LegacyMapper.mapDynamicState（导入旧数据）
                       ├─► 角色编辑器「当前状态」页签（CharactersScreen）
                       └─► 生成/补全/升级提示词里的字段清单（CharacterParity）

STATIC_PROFILE_FIELDS ─┬─► submit_response schema (staticFieldProperties)
                       ├─► applyStaticFields / update_character_field 工具 enum
                       ├─► buildSystemPrompt（角色卡注入与成员块）
                       ├─► buildVolatileContext 的话题库 / BackgroundTasks.autoFillStaticFields
                       ├─► 角色编辑器「基础」页签
                       └─► LegacyMapper 的旧字段迁移（worldView→background 等）
```

> 原生 `DYNAMIC_STATE_FIELDS` 为 **6 项**：`currentSituation` / `currentLocation` / `currentMood` / `currentOccupation` / `currentGoal` / `currentTone`（`currentTone` 是朗读投递属性，每轮必填、免证据门）。

## 4. 已知脆弱点与对应加固

| ID | 脆弱点 | 触发 | 后果 | 加固 |
|---|---|---|---|---|
| F1 | 自由文本字段修复不感知引号配对/嵌套 | 字段值内含未转义 `"` 且后接 `}`/`]`/`"key":` | 字段被提前截断 → JSON 非法 → L5 把数组当合法 → `quickReplies` 丢（ARRAY 返回） | `repairFreetextFieldsByScan`（引号配对感知扫描 + `hasLaterCandidate` 消歧）＋ `repairStringArrayField` 逐项修数组；旧策略保留为下限兜底 |
| F2 | 解析可能"成功"返回非预期顶层类型 | 同上 | 静默失败，调用方按对象用则读到空 | `parseSubmitResponse` / `parseTurnFromText` 的顶层类型守卫（只接受 `JsonObject`） |
| F3 | 快速回应仅在 `memUpdate` 为对象时走文本兜底 | memUpdate 为数组/null | 快速回应不兜底 | 由 F1+F2 覆盖（返回对象后正常） |

### F1 修复算法
1. 对每个自由文本字段，从其开引号起扫描，收集"其后是合法 JSON 后继"的引号候选。
2. 选结束引号时加消歧：`}`/`]` 型后继若其**后面仍存在候选**，则跳过后面的更优候选（真结束引号通常是本字段最靠后、结构自洽者）。
3. 数组型字段（`quickReplies`）用 `repairStringArrayField` 单独修每个字符串项。
4. `repairFreetextFields` 汇总多个候选修复串，返回**第一个能整段解析成功**的；全失败则退回旧策略（不劣于修复前）。

## 4.5 请求体布局与阶段一/二路径

### 请求体（Responses API，`ResponsesLlmBackend.buildBody`；由 `RequestBuilder.build` 装配）
```
{
  model, instructions(=system提示词，单独提顶层), input(=对话+工具回传),
  stream, temperature, max_output_tokens(8192), reasoning.effort,
  tools: [...],        // ★工具定义在顶层，与 input/instructions 平级
  tool_choice: 'auto' | {type:'function', name:'submit_response'}
}
```
- **阶段一 `auto`（正常路径，一次请求收尾）**：`tools = Tools.defaultTools()`，`tool_choice: Auto`；工具数组**含 `submit_response`**，所以"调完信息工具 → 调 `submit_response` 提交"在同一轮完成。工具调用只发生在这里。
- **阶段二 `submit`（兜底，仅当阶段一连正文都没拿到）**：`tools = [submit_response]`（**只有 1 个**），`tool_choice` 锁定，`reasoning.effort='none'`。

### 产出路径（正文优先、quickReplies 不阻塞）
```
主路径一：阶段一 submit_response 工具调用 → parseSubmitResponse
        └─ 有 obj ? 直接采用 reply（缺 quickReplies 也接受）+ 落盘记忆
主路径二：阶段一直接返回纯文本 JSON {reply,...} → parseTurnFromText
主路径三：阶段一直接返回散文 → 直接采用（散文即终稿）；记忆/状态由
        BackgroundTasks.extractProseTurnMemory 后台补写
兜底：阶段一完全没有正文 → 阶段二锁定 submit_response → parseSubmitResponse
```
- `parseSubmitResponse` 缺 quickReplies 时仍带上已解析的对象，调用方照常落盘记忆、直接接受正文。
- quickReplies 由 `StyleGuard.detectQuickReplyIssues`（视角校验）+ `BackgroundTasks.repairQuickReplies`（后台换位生成）补齐；换位生成也失败才回退兜底短句。
- 空回复在 `AgentLoop` 同一循环内最多重试 2 次，保留已累计的工具往返。

## 4.6 待办（todo）准入原则

- **定义**：待办 = 用户与角色之间、**有明确日期**且**双方同意**的一件待办；是**独立实体**（`Character.todos`），与长期记忆互相独立。
- **准入**（`TodoPolicy.validateTodoCreation`，唯一入口 `add_todo` 工具）：①期限必填且能在对话原话里定位；②用户发起→用户原话含期限与提议；③角色发起→必须附**用户应答的原话**，且该原话不得是明确拒绝（`isUserRefusal` 否决，`isUserAgreement` 只作正向信号；**同意与否以模型的判断为主，关键词只做一环**）。`sourceMessageIds`+`evidence` 经 `MemoryEvidence.hasValidUserEvidence` 校验。
- **禁止**：没有明确日期、期限定位不到、或角色单方叮嘱而用户没答应的，一律拒绝（工具返回 `ok:false` 并说明原因）。
- **生命周期**：改期/完成/取消由 `submit_response.todoUpdates` 处理（须引用户原话，改期还要能定位）；角色编辑页待办 tab 提供「完成 / 取消 / 删除」手动兜底。
- **与 promises 区分**：`longTerm` 的 `promises` 是叙事记忆，走 `isValidAutomaticMemory`（来源须为用户消息），**不再产生待办**。

## 5. 改动检查清单（每次改前必读）

- [ ] 是否碰到 `parseJsonLenient` / `repairFreetextFields*`？→ 跑下面的"自测样例"（含未转义引号、真实换行、嵌套对象）。
- [ ] 是否改 `DYNAMIC_STATE_FIELDS` / `STATIC_PROFILE_FIELDS`？→ 逐一核对第 3 节所有下游 + 迁移 + 提示词 + 编辑器。
- [ ] 是否改 system prompt 文本？→ 检查前缀缓存（INV-4）；改 `RULE_A` 之类文本会让每个角色一次性破缓存。
- [ ] 是否改 `versionName`？→ 同步 `versionCode`，跑 `tools/native-verify.ps1`。
- [ ] 是否新增 `parseJsonLenient` 调用？→ 检查返回类型守卫。
- [ ] 是否改请求体阶段（`RequestBuilder`/`ResponsesLlmBackend`）？→ 核对阶段二 tools/tool_choice（INV-5）。
- [ ] 是否改待办（todo）准入？→ 必须保留「明确日期 + 双方同意」约束（INV-6）；改 promises 自动写入则保留"用户原话证据"约束。

## 6. GRAPH（YAML 镜像）

```yaml
invariants:
  INV-1: {rule: single-parse-entry, at: ResponseParser.parseJsonLenient}
  INV-2: {rule: main-reply-must-be-object, guard: parseSubmitResponse|parseTurnFromText}
  INV-3: {rule: field-set-change-requires-downstream-audit, keys: [DYNAMIC_STATE_FIELDS, STATIC_PROFILE_FIELDS]}
  INV-4: {rule: prefix-cache-stability}
  INV-5: {rule: submit-phase-tools-only-submit_response, why: 'full tools dilute strict schema -> quickReplies dropped; phase 2 is fallback-only'}
  INV-6: {rule: todo-requires-date-and-mutual-agreement, why: 'todo is independent of long-term memory; a character instruction with no user agreement must not become a to-do'}
  INV-7: {rule: body-first-never-refetch-for-quickReplies, why: 'accepting prose/JSON-without-quickReplies avoids the per-turn forced submit round; quick replies are repaired in the background'}
  INV-8: {rule: quickReplies-must-be-user-viewpoint, guard: StyleGuard.detectQuickReplyIssues, repair: BackgroundTasks.repairQuickReplies}

nodes:
  parseJsonLenient:        {kind: function, role: hub, risk: high}
  repairFreetextFieldsByScan:{kind: function, role: repair, risk: high, defect: F1}
  repairFreetextFieldsLegacy:{kind: function, role: fallback, risk: medium, defect: F1}
  safeJson:                {kind: function, role: fallback, risk: medium, defect: F2}
  decodeJsonEscapes:       {kind: function, role: helper, risk: low}
  parseSubmitResponse:     {kind: function, role: main-reply-tool, risk: high}
  parseTurnFromText:       {kind: function, role: prose-json, risk: high}
  StreamText.extractReplyFromJson:{kind: function, role: stream-bubble, risk: medium}
  parseQuickReplyList:     {kind: function, role: quick-replies, risk: medium}
  parseQuickRepliesFromText:{kind: function, role: quick-replies-fallback, risk: low}
  ChatOrchestrator.applyMemory:{kind: function, role: memory-write, risk: high}
  applyDynamicStateFields: {kind: function, role: dynamic-write, risk: medium}
  parseMemoryFromText:     {kind: function, role: mem-update-extract, risk: low}
  DYNAMIC_STATE_FIELDS:    {kind: const, role: field-set, risk: high}
  STATIC_PROFILE_FIELDS:   {kind: const, role: field-set, risk: high}

edges:
  - {from: model_output,             to: parseJsonLenient}
  - {from: parseJsonLenient,         to: parseSubmitResponse}
  - {from: parseJsonLenient,         to: parseTurnFromText}
  - {from: parseJsonLenient,         to: StreamText.extractReplyFromJson}
  - {from: parseJsonLenient,         to: parseQuickReplyList}
  - {from: parseJsonLenient,         to: parseQuickRepliesFromText}
  - {from: parseJsonLenient,         to: parseMemoryFromText}
  - {from: parseJsonLenient,         to: 'submit_response.args'}
  - {from: parseJsonLenient,         to: 'ask_user.args'}
  - {from: parseJsonLenient,         to: 'LegacyMapper.field-migration'}
  - {from: parseJsonLenient,         to: 'CharacterParity.generate/fill/upgrade'}
  - {from: repairFreetextFieldsByScan, to: parseJsonLenient, note: 'used by L3 and stream salvage'}
  - {from: parseSubmitResponse,      to: ChatOrchestrator.applyMemory}
  - {from: ChatOrchestrator.applyMemory, to: applyDynamicStateFields}
  - {from: parseQuickReplyList,      to: quickRepliesState}
  - {from: quickRepliesState,        to: renderQuickReplies}
  - {from: DYNAMIC_STATE_FIELDS,     to: [submit_response.schema, applyDynamicStateFields, FieldCleaning.cleanFieldValue, buildVolatileContext, LegacyMapper.mapDynamicState, editor-state-tab, prompts]}
  - {from: STATIC_PROFILE_FIELDS,    to: [submit_response.schema, applyStaticFields, update_character_field.enum, buildSystemPrompt, buildVolatileContext, autoFillStaticFields, editor-basic-tab, LegacyMapper]}

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
