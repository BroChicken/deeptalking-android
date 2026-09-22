# 解析机制关系图谱（模型友好版）

> 目的：任何"改字段 / 改提示词 / 改解析"的改动，先按本图谱定位会连带影响的机制链，避免引入新问题。
> 机器可读镜像见文末 `GRAPH` 代码块（YAML）。代码锚点用**函数名**而非行号（行号会漂移，用 grep 函数名定位）。

## 0. 设计铁律（改动的硬约束）

- **INV-1 单点解析**：所有"模型输出 → 结构化对象"都经 `parseJsonPayload`。改它=改全系统。
- **INV-2 顶层类型契约**：主回复解析必须返回**对象**（`isPlainObject`）。返回数组=快速回应/记忆全丢（静默失败）。
- **INV-3 不改字段名集合**（`DYNAMIC_STATE_FIELDS` / `STATIC_PROFILE_FIELDS`）而不检查下游：schema、清洗、迁移、UI、提示词全部由这两个常量驱动。
- **INV-4 两文件字节一致**：`hub.html` 与 `android-lite/.../hub.html` 必须同步（`tools/check-sync.ps1`）。
- **INV-5 前缀缓存稳定**：system 固定在前、历史原样递增、volatile 只挂当前用户消息；任何"每轮变化的文本塞进 system 前缀"都会击穿缓存。

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
applyMemoryUpdate ──► applyDynamicStateUpdates    state.quickReplies（快速回应按钮）
   ├ shortTerm                    │                          │
   ├ longTerm / promises          ▼                          ▼
   ├ dynamicState（新 7 字段）  memberDynamicState     快速回应渲染 renderQuickReplies
   └ memberDynamicState
        │
        ▼
parseMemoryFromText（从 reply 文本抓 <MEM_UPDATE>）
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

## 5. 改动检查清单（每次改前必读）

- [ ] 是否碰到 `parseJsonPayload` / `repairFreetextFields`？→ 跑"自测样例"（含未转义引号、真实换行、嵌套对象）。
- [ ] 是否改 `DYNAMIC_STATE_FIELDS` / `STATIC_PROFILE_FIELDS`？→ 逐一核对第 3 节所有下游 + 迁移 + 提示词。
- [ ] 是否改 system prompt 文本？→ 检查前缀缓存（INV-5）。
- [ ] 是否改 `hub.html`？→ 同步 APK 副本 + `check-sync.ps1`。
- [ ] 是否改 `versionName`？→ 同步 `APP_VERSION` + `versionCode`，跑 workflow。
- [ ] 是否新增 `parseJsonPayload` 调用？→ 检查返回类型守卫。

## 6. GRAPH（YAML 镜像）

```yaml
invariants:
  INV-1: {rule: single-parse-entry, at: parseJsonPayload}
  INV-2: {rule: main-reply-must-be-object, guard: parseStructuredResponse}
  INV-3: {rule: field-set-change-requires-downstream-audit, keys: [DYNAMIC_STATE_FIELDS, STATIC_PROFILE_FIELDS]}
  INV-4: {rule: two-hub-files-identical, check: tools/check-sync.ps1}
  INV-5: {rule: prefix-cache-stability}

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
