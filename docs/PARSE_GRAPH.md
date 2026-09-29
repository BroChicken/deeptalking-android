# 解析机制关系图谱（模型友好版）

> 目的：任何"改字段 / 改提示词 / 改解析"的改动，先按本图谱定位会连带影响的机制链，避免引入新问题。
> 机器可读镜像见文末 `GRAPH` 代码块（YAML）。代码锚点用**函数名**而非行号（行号会漂移，用 grep 函数名定位）。

## 0. 设计铁律（改动的硬约束）

- **INV-1 单点解析**：所有"模型输出 → 结构化对象"都经 `parseJsonPayload`。改它=改全系统。
- **INV-2 顶层类型契约**：主回复解析必须返回**对象**（`isPlainObject`）。返回数组=快速回应/记忆全丢（静默失败）。
- **INV-3 不改字段名集合**（`DYNAMIC_STATE_FIELDS` / `STATIC_PROFILE_FIELDS`）而不检查下游：schema、清洗、迁移、UI、提示词全部由这两个常量驱动。
- **INV-4 两文件字节一致**：`hub.html` 与 `android-lite/.../hub.html` 必须同步（`tools/check-sync.ps1`）。
- **INV-5 前缀缓存稳定**：system 固定在前、历史原样递增、volatile 只挂当前用户消息；任何"每轮变化的文本塞进 system 前缀"都会击穿缓存。
- **INV-6 阶段二只用于兜底**：阶段一（含 `submit_response` 工具）拿到正文就直接收尾；只有"连正文都没有"才进阶段二锁定 `submit_response`。
- **INV-7 待办必须用户原话证据**（promises 准入）。
- **INV-8 正文优先，不为 quickReplies 重发**：合法 JSON（即使缺 quickReplies）或散文都直接采用；快速回应由后台补齐，绝不再为它多发一轮。
- **INV-9 快速回应必须是用户视角**：`detectQuickReplyIssues` 校验，失败由 `generateQuickRepliesAsUser` 换位重写；不得覆盖已显示的正文。

## 1. 核心机制链（主链）

```
模型原始输出 fullText / function_call.arguments
        │
        ▼
  parseJsonPayload(text)                      ★中枢★  (返回值类型不定：对象 / 数组 / 抛错)
        ├─ L1 JSON.parse(cleaned)
        ├─ L2 JSON.parse(去尾逗号)
        ├─ L3 repairFreetextFields(cleaned)      ← 自由文本引号/换行修复（当前缺陷源）
        ├─ L4 repairFreetextFields(切片 {..})
        ├─ L5 safeJson(切片 {..} / [..])         ← 可能把数组当合法 JSON 返回（INV-2 违例源）
        └─ L6 safeJson(全文)
        │
   ┌────┴───────────────────────┬──────────────────────────┐
   ▼                            ▼                          ▼
parseStructuredResponse   extractReplyFromJson      parseQuickReplyList /
 (主回复：必须对象)         (流式气泡 + salvage)        parseQuickRepliesFromText
   │                            │                          │
   ▼                            ▼                          ▼
applyMemoryUpdate ──► applyDynamicStateUpdates    detectQuickReplyIssues（视角校验）
   ├ shortTerm                    │                          │
   ├ longTerm / promises          ▼                          ▼
   ├ dynamicState（新 7 字段）  memberDynamicState     通过 → state.quickReplies
   └ memberDynamicState                                 不通过 → generateQuickRepliesAsUser
        │                                                  （后台换位生成，不改正文）
        ▼
parseMemoryFromText（从 reply 文本抓 <MEM_UPDATE>）
        │
        ▼（散文收尾时）
extractProseTurnMemory（后台补 shortTerm/longTerm/dynamicState，不阻塞正文）
```

## 2. 全部调用方（谁受 `parseJsonPayload` 影响）

| 调用点 | 期望返回 | 若返回数组/抛错的后果 |
|---|---|---|
| `parseStructuredResponse`（主回复） | 对象 | 判为"无合法 JSON"→ 走阶段二强制 submit；快速回应丢 |
| `parseQuickReplyList` / `parseQuickRepliesFromText` | 数组 | 快速回应 fallback 到 `['嗯','继续']` |
| `extractReplyFromJson`（流式气泡/salvage） | 字符串 | 气泡为空 → salvage |
| `applyMemoryUpdate` ← `parseMemoryFromText` | 对象 | 记忆不落盘 |
| `submit_response` 工具参数解析 | 对象 | 记忆/回复字段缺失 |
| `ask_user` 参数解析 | 对象 | 澄清问题丢失 |
| 角色/群组生成、成员补全、群组升级、头像/话题 | 对象 | 生成失败 |
| ★v1.2.0 新增★ AI 字段迁移（`aiRemapFieldsForEntity` 等） | 对象 | 迁移失败回退本地合并 |

## 3. 字段集合驱动链（改字段时）

```
DYNAMIC_STATE_FIELDS ──┬─► submit_response schema (dynFieldProperties)
                       ├─► applyDynamicStateUpdates（落盘校验）
                       ├─► cleanFieldValue / SHORT_FACT_FIELDS（清洗）
                       ├─► buildDynamicStateContext（提示词注入）
                       ├─► normalizeCharacter / normalizeGroupMembers（加载/迁移）
                       ├─► 编辑弹窗渲染 + collectEditDraftFromDom
                       └─► 生成/补全/升级提示词里的字段清单

STATIC_PROFILE_FIELDS ─┬─► submit_response schema (staticFieldProperties)
                       ├─► applyStaticFieldUpdates / update_character_field 工具 enum
                       ├─► buildRoleContext（角色卡注入） / buildMemberContext
                       ├─► buildTopicSuggestions / collectStaticFillJobs / fillStaticFieldsForJob
                       ├─► 编辑弹窗 basicFields
                       └─► normalizeCharacter（worldView→background 迁移）
```

## 4. 已知脆弱点与对应加固（F1/F2 已于 v1.2.1 修复）

| ID | 脆弱点 | 触发 | 后果 | 加固 | 状态 |
|---|---|---|---|---|---|
| F1 | `repairFreetextFields` 非贪婪 + 不感知嵌套 | 自由文本值内含未转义 `"` 且后接 `}`/`]`/`"key":` | 字段被提前截断 → JSON 非法 → L5 把数组当合法 → `quickReplies` 丢（ARRAY 返回） | 重写为**引号配对感知扫描**（`repairFreetextFieldsByScan`，含 `hasLaterCandidate` 消歧）＋数组项修复 `repairStringArrayField`；旧策略保留为下限兜底 | ✅ 已修 |
| F2 | `parseJsonPayload` 可能"成功"返回非预期顶层类型 | 同上 | 静默失败，调用方按对象用则读到 undefined | `parseStructuredResponse` 顶层类型守卫（要求对象） | ✅ 已修 |
| F3 | 快速回应仅当 `memUpdate` 为对象时走文本兜底 | memUpdate 为数组/null | 快速回应不兜底 | 由 F1+F2 覆盖（返回对象后正常） | ✅ 已覆盖 |

### F1 修复算法（v1.2.1）
1. 对每个自由文本字段，从其开引号起扫描，收集"其后是合法 JSON 后继"的引号候选。
2. 选结束引号时加消歧：`}`/`]` 型后继若其**后面仍存在候选**，则跳过后面的更优候选（真结束引号通常是本字段最靠后、结构自洽者）。
3. 数组型字段（`quickReplies`）用 `repairStringArrayField` 单独修每个字符串项。
4. `repairFreetextFields` 汇总多个候选修复串，返回**第一个能整段 JSON.parse 成功**的；全失败则退回旧策略（不劣于修复前）。

### 自测矩阵（v1.2.1 实测）
- 165/165 组用例 `parseJsonPayload` 均返回**对象**且 `quickReplies` 完整（含 `reply`/`value`/`evidence` 内出现 `"`、`}`、`]`、`,`、真实换行、中文引号、数组项含引号）。
- 真实群组回复格式（`成员名："台词"` + 真实换行、未转义引号）解析为对象且 reply 文本字节一致。

## 4.5 请求体布局与阶段一/二路径（v1.3.4 关键认知）

### 请求体（Responses API，`buildResponsesRequestBody`）
```
{
  model, instructions(=system提示词，单独提顶层), input(=对话+工具回传),
  stream, temperature, max_output_tokens, reasoning,
  tools: [...],        // ★工具定义在顶层，与 input/instructions 平级
  tool_choice: 'auto' | {type:'function', name:'submit_response'}
}
```
- **阶段一 `auto`（正常路径，一次请求收尾）**：`tools = buildAgentTools()`，`tool_choice:'auto'`；工具数组**末尾含 `submit_response`**，所以"调完信息工具 → 调 `submit_response` 提交"在同一轮完成。工具调用只发生在这里。
- **阶段二 `submit`（兜底，仅当阶段一连正文都没拿到）**：`tools = [submit_response]`（**只有 1 个**），`tool_choice` 锁定，`reasoning.effort='none'`。

### 产出路径（v1.3.4 起：正文优先、quickReplies 不阻塞）
```
主路径：阶段一 submit_response 工具调用
        └─ extractSubmitResponse → ok ? break : （缺 quickReplies）直接接受 reply + obj
主路径二：阶段一直接返回纯文本 JSON {reply,...}
        └─ parseStructuredResponse → ok ? break（不再要求 hasQuickReplies）
主路径三：阶段一直接返回散文
        └─ 直接采用（散文即终稿）；记忆/状态由后台 extractProseTurnMemory 补写
兜底：阶段一完全没有正文
        └─ 阶段二锁定 submit_response → extractSubmitResponse
```
- `parseStructuredResponse` 仍返回 `hasQuickReplies`，但**只作为"后台是否需要重写快速回应"的信号**，不再是收尾放行条件（v1.2.3 曾把它当门槛，导致几乎每轮都要多发一轮「正在整理回复…」）。
- `extractSubmitResponse` 缺 quickReplies 时返回 `ok:false, reason:'missing_quick_replies'`，**同时带上已解析的 `obj`**，调用方据此照常落盘记忆、直接接受正文。
- quickReplies 由 `detectQuickReplyIssues`（客户端视角校验）+ `generateQuickRepliesAsUser`（后台换位生成）补齐；兜底 `['嗯','继续']` 只在换位生成也失败时出现。

### 历史回归（务必牢记）
| 版本 | 阶段二 tools | 结果 |
|---|---|---|
| ≤v1.1.1 | `[submit_response]` | quickReplies 正常 |
| v1.1.2 (bd3dc24) | 全量工具 + 锁定（为命中缓存） | **strict 被稀释，模型只吐 {reply}，quickReplies 长期退化** |
| v1.2.2+ | 回退为 `[submit_response]` | 恢复；并加协议守卫覆盖主/次两条路径 |
| v1.2.3 (a713e77) | 同上 | 主路径放行**加严**为"必须含 ≥2 条 quickReplies"，「整理回复」几乎每轮出现 |
| v1.3.4 | 同上（降级为兜底） | 阶段一含 `submit_response` + 正文优先放行 → 正常轮只有一次请求 |

## 4.6 待办（promises）准入原则（v1.2.3）

- **定义**：待办 = 需要用户参与的事。
- **准入**：只有**用户自己明确提出/同意**才可入库（`set_reminder` 必须带 `sourceMessageIds`+`evidence`，经 `hasValidUserEvidence` 校验）。
- **禁止**：角色要求用户去做的事、角色的建议/叮嘱，不得记为待办（缺用户原话 → `set_reminder` 返回 `ok:false`，引导模型先征询用户）。
- **既有保护**：`longTerm` 的 promises 走 `isValidAutomaticMemory`，要求来源全为用户消息。

## 5. 改动检查清单（每次改前必读）

- [ ] 是否碰到 `parseJsonPayload` / `repairFreetextFields`？→ 跑"自测样例"（含未转义引号、真实换行、嵌套对象）。
- [ ] 是否改 `DYNAMIC_STATE_FIELDS` / `STATIC_PROFILE_FIELDS`？→ 逐一核对第 3 节所有下游 + 迁移 + 提示词。
- [ ] 是否改 system prompt 文本？→ 检查前缀缓存（INV-5）。
- [ ] 是否改 `hub.html`？→ 同步 APK 副本 + `check-sync.ps1`。
- [ ] 是否改 `versionName`？→ 同步 `APP_VERSION` + `versionCode`，跑 workflow。
- [ ] 是否新增 `parseJsonPayload` 调用？→ 检查返回类型守卫。
- [ ] 是否改请求体阶段（`buildResponsesRequestBody`）？→ 核对阶段二 tools/tool_choice（INV-6）。
- [ ] 是否改待办（promises）准入？→ 必须保留"用户原话证据"约束（INV-7）。

## 6. GRAPH（YAML 镜像）

```yaml
invariants:
  INV-1: {rule: single-parse-entry, at: parseJsonPayload}
  INV-2: {rule: main-reply-must-be-object, guard: parseStructuredResponse}
  INV-3: {rule: field-set-change-requires-downstream-audit, keys: [DYNAMIC_STATE_FIELDS, STATIC_PROFILE_FIELDS]}
  INV-4: {rule: two-hub-files-identical, check: tools/check-sync.ps1}
  INV-5: {rule: prefix-cache-stability}
  INV-6: {rule: submit-phase-tools-only-submit_response, why: 'full tools dilute strict schema -> quickReplies dropped (v1.1.2 regression); phase 2 is fallback-only since v1.3.4'}
  INV-7: {rule: promises-require-user-evidence, why: 'to-do = needs-user; character instructions must not become to-dos'}
  INV-8: {rule: body-first-never-refetch-for-quickReplies, why: 'accepting prose/JSON-without-quickReplies avoids the per-turn forced submit round; quick replies are repaired in the background (v1.3.4)'}
  INV-9: {rule: quickReplies-must-be-user-viewpoint, guard: detectQuickReplyIssues, repair: generateQuickRepliesAsUser}

nodes:
  parseJsonPayload:        {kind: function, role: hub, risk: high}
  repairFreetextFields:    {kind: function, role: repair, risk: high, defect: F1}
  safeJson:                {kind: closure,   role: fallback, risk: medium, defect: F2}
  decodeJsonEscapes:       {kind: function, role: helper, risk: low}
  parseStructuredResponse: {kind: function, role: main-reply, risk: high}
  extractReplyFromJson:    {kind: function, role: stream-bubble, risk: medium}
  parseQuickReplyList:     {kind: function, role: quick-replies, risk: medium}
  parseQuickRepliesFromText:{kind: function, role: quick-replies-fallback, risk: low}
  applyMemoryUpdate:       {kind: function, role: memory-write, risk: high}
  applyDynamicStateUpdates:{kind: function, role: dynamic-write, risk: medium}
  parseMemoryFromText:     {kind: function, role: mem-update-extract, risk: low}
  DYNAMIC_STATE_FIELDS:    {kind: const, role: field-set, risk: high}
  STATIC_PROFILE_FIELDS:   {kind: const, role: field-set, risk: high}

edges:
  - {from: model_output,          to: parseJsonPayload}
  - {from: parseJsonPayload,      to: parseStructuredResponse}
  - {from: parseJsonPayload,      to: extractReplyFromJson}
  - {from: parseJsonPayload,      to: parseQuickReplyList}
  - {from: parseJsonPayload,      to: parseQuickRepliesFromText}
  - {from: parseJsonPayload,      to: parseMemoryFromText}
  - {from: parseJsonPayload,      to: 'submit_response.args'}
  - {from: parseJsonPayload,      to: 'ask_user.args'}
  - {from: parseJsonPayload,      to: 'field-migration(v1.2.0)'}
  - {from: parseJsonPayload,      to: 'generate/fill/upgrade'}
  - {from: repairFreetextFields,  to: parseJsonPayload, note: 'used by L3/L4 and extractReplyFromJson'}
  - {from: parseStructuredResponse, to: applyMemoryUpdate}
  - {from: applyMemoryUpdate,     to: applyDynamicStateUpdates}
  - {from: parseQuickReplyList,   to: state.quickReplies}
  - {from: state.quickReplies,    to: renderQuickReplies}
  - {from: DYNAMIC_STATE_FIELDS,  to: [submit_response.schema, applyDynamicStateUpdates, cleanFieldValue, buildDynamicStateContext, normalizeCharacter, normalizeGroupMembers, edit-modal, prompts]}
  - {from: STATIC_PROFILE_FIELDS, to: [submit_response.schema, applyStaticFieldUpdates, update_character_field.enum, buildRoleContext, buildMemberContext, buildTopicSuggestions, collectStaticFillJobs, fillStaticFieldsForJob, edit-modal, normalizeCharacter]}

failure_modes:
  F1: {cause: unescaped-quote-then-brace, effect: premature-field-cut, cascade: 'json-invalid -> array-returned -> quickReplies-lost'}
  F2: {cause: fallback-picks-array-shape, effect: wrong-toplevel-type, cascade: 'silent-undefined'}
```

## 7. 自测样例（改解析后必跑）

输入（ASCII，未转义引号）：
```
{"reply":"x","quickReplies":["a","b"],"dynamicState":{"currentMood":{"value":"ok","evidence":"quote "here""},"currentGoal":{"value":"g"}}}
```
期望：`parseJsonPayload` 返回**对象**且 `quickReplies==["a","b"]`（修复前返回数组，`quickReplies===undefined`）。
