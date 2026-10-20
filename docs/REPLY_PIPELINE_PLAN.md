# 回复管线改造：实施计划

> 对应设计稿 [REPLY_PIPELINE.md](REPLY_PIPELINE.md)。本文件只记录**已定决策与落地清单**，不含工具迁移（见文末“暂缓”）。

## 一、已定决策

| # | 决策 |
|---|---|
| 1 | 主调用只输出纯文本；不再有 `submit_response`，不再要求 JSON |
| 2 | 结构化字段由后台 turn worker 产出：`dynamicState` + `quickReplies`（可选） |
| 3 | `longTerm` 不做第二套：继续走现有 `shortTerm → longTerm` 分析链路 |
| 4 | `staticFields` 本轮不由 worker 写；`memberDynamicState` 至少补 `currentTone`；`promiseUpdates` 与待办由 worker 维护 |
| 5 | 语气由**主调用**返回（不再由 worker 或 TTS 开关决定），客户端解析后写入 `currentTone` |
| 6 | 群组不拆成多条消息：一条 assistant 消息 + `segments: [{speaker, text}]`，按 segment 渲染多个气泡 |
| 7 | 快速回复不进记忆；是否生成做成勾选项，用户选了就接受延迟 |
| 8 | 每类调用独立通道、独立重试；写角色的通道共享 `CharacterRepository.mutate(id)` 的 per-id 锁 |
| 9 | 用户编辑的字段不被后台覆盖：字段级补丁合并为主，焦点 + 更新时间戳为辅 |
| 10 | 一次性缓存失效可接受 |
| 11 | 指标按调用类型分开统计，右上角按钮展开查看 |
| 12 | worker 输出走**强制 function call**（`submit_state`，`tool_choice` 锁定、`reasoning.effort=none`），需实测长输入 |
| 13 | `quickReplies` 默认开启 |
| 14 | 空回合（既无正文也无工具调用）沿用现有重试：最多 2 次 |
| 15 | 每条请求都记一条指标，右上角按 `taskType` 分行分组 |
| 16 | `submit_response` 删除（它不算能力工具）；信息工具保留，是否改行动轮另行决定 |
| 17 | 系统上下文（volatile）按 11 段重写；删除待跟进承诺、快速回应提醒；保留消息时间元数据 |
| 18 | 待办是“未完成事项”的唯一载体；周期性约定保留；`promises` 只作信息字段，有期限时同步建 todo |
| 19 | 记忆召回最多 3 条；单条 ≤1200 字、总预算 3600 字，超长才按句边界截断 |
| 20 | 场景概要只注入 1 条、≤300 字；有则注入，生成仍为换场景或满 12 条 |
| 21 | 历史窗口（`instant`）与短期记忆（近期摘要）本轮不改 |
| 22 | 主调用保留现有消息时间元数据（`id \| role \| timestamp`），供写工具引用 |
| 23 | 历史 assistant 在请求中改纯文本（只改序列化，不改 `instant` 存储） |
| 24 | `AgentLoop` 增加 FINAL 相：无工具、独立空回合计数（2 次）与硬上限 |
| 25 | `submit_state.todos` 增加 `proposer`，并补 create 应用路径（走 `validateTodoCreation`） |
| 26 | 群组按成员输出/写入 `currentTone`（第二期；先单角色） |
| 27 | 写入改补丁式，锁下沉到 `CharacterRepository.mutate(id)` |
| 28 | 场景概要有则注入最近 1 条；世界书 1400 预算已存在，装配层不重复截断 |
| 29 | 指标独立存储、保留 per-round、`taskType` 用 `startsWith("chat")` |
| 30 | 静态字段修订调用落地后才删除 `update_character_field` |

## 二、主调用契约

```text
<正文>

【语气】<朗读指导，≤60 字>
```

- 语气行固定为最后一行，客户端取**最后一次**出现的标记。
- 解析后从 `reply` 与落盘内容中剥掉；缺失或格式不合法 → 保留上一轮 `currentTone`，不写空值。
- 语气指令放 **system（静态、可缓存）**，不放 volatile：语气是每轮都给的，放 system 不会因“用不用 TTS”破缓存。
- input 保留现有【消息时间元数据】（最近 14 条 `id | role | timestamp`），供 `memory_write` / `upsert_lorebook_entry` 引用真实消息。
- 写入：`dynamicState.currentTone` + `dynamicStateMeta.currentTone.updatedAt`，免证据（与现在一致）。
- TTS 设置里 `ttsEnabled` / `ttsAutoRead` 仍控制**是否朗读**，不再控制**是否生成语气**。

## 三、turn worker 契约

- 输入：当前 dynamicState（空值标 `(未设置)`）、本轮用户消息、角色回复、时间基准、最近 3–6 条对话。
- 输出：强制调用 `submit_state`（`tool_choice` 锁定、`reasoning.effort=none`），参数为
  `dynamicState`（除 currentTone）、`quickReplies`（默认开启）、`todos`（带 `proposer`，create 走 `validateTodoCreation`）、`promiseUpdates`。
- 输入额外包含 `【当前待办】` 与 `【当前约定】`（带 ID），用于完成/取消/改期的指认。
- 强制调用失败（模型未发调用 / 参数不是合法 JSON）时退化为解析文本 JSON；仍失败则保留旧状态，只记失败指标，不重试整轮。
- 主动开场（无用户消息）：走显式分支「本轮无用户消息（角色主动开场）」，不伪造占位消息，允许来源仅 `assistantMessage.id`。
- 证据：除 currentTone 外必须有 `sourceMessageIds` + `evidence`，解析不了就丢该字段。
- 应用：以字段补丁写回（`CharacterRepository.mutate(id)`），不做整对象覆盖；`dynamicStateMeta` 同步写 `updatedAt`。

## 四、用户编辑保护

- **主保护**：写入一律走字段补丁 + `CharacterRepository.mutate(id)`，合并层只接受改动过的字段，不再整块 `pick`。
- **时间戳**：所有写入方（worker、主调用语气、编辑器保存）对实际改动的 key 写 `updatedAt = now`；`null` 视为未设置，按 fail-closed 处理。
- **焦点**：编辑器给每个输入框加 `onFocusChanged`，写入 `:core:model` 的 `FieldEditRegistry`（key = 角色 id/成员 id + static/dynamic + 字段名）；对话框关闭、失焦超时、保存完成时清除；worker 应用时跳过聚焦字段。
- **列表**：待办按“整表占用”或脏 id 集合保护；编辑器保存走 `mergeCharacterChanges`，不整表写回。

## 五、群组 segments

- `ChatMessage` 新增 `segments: List<SpeechSegment>`（speaker + text），只在 assistant 群组消息上有值。
- 渲染按 segment 出气泡；历史、`instant` 窗口、折叠节奏仍按**一条消息**计。
- worker / TTS 直接读 segment 的 speaker，不再从文本猜归属。
- 归属失败：整段归默认发言人并标记，不猜测交替发言。

## 六、本地处理与流式

- 主调用纯文本后，流式只剩四件事：语气行 hold-back、偶发 JSON 回退检测、旧 `<MEM_UPDATE>` 剥离（兼容一个版本）、群组成员名前缀。
- 未闭合括号、引用、URL 原样显示，不在流式阶段改写。
- 清洗在现有 `StreamText` / `extractSpeechText` / `extractSpeechSegments` 上扩展，不新开平行实现；`FormatGuard` 并入 `detectStyleViolations` 的 advisory 键。
- 旧 `<MEM_UPDATE>`：仅保留 `StreamText.getDisplayText` 的剥离一个版本；`ResponseParser.parseMemoryFromText` 的应用路径与 `BackgroundTasks.parseMemoryFromText`（已无生产调用方）删除。

## 七、通道与重试

- 通道：`turnWorker` / `quickReply` / `critique` 并行；`extraction` / `analysis` / `lorebook` / `scene` 按角色串行。
- 所有写角色的通道共享 per-character 锁。
- 重试：worker 失败一次即放弃（下一轮补状态）；记忆类沿用现有指数退避（`MemoryTaskKind`）。

## 八、指标

- `RequestMetric.taskType` 细分：`chat.main` / `chat.worker` / `memory.*` / `lorebook.*`。
- **每条请求都写一条记录**（含工具轮、重试、失败），不再只按“一轮一条”聚合。
- 右上角缓存条加一个小按钮，点开显示：按 `taskType` 分行分组，每行给出命中 tokens、输入 tokens、命中率与最近 N 次。
- 现有 `AppRoot.kt` 缓存条与 `SettingsScreen` 明细保持，新增分组视图。

## 九、验收点

1. 纯聊天轮：首字时间不变；正文无 JSON/字段名；`currentTone` 每轮更新。
2. 工具相关轮次：行为与现在一致。
3. 群组：多气泡、按成员取语气、发言归属正确（第二期）。
4. 用户编辑中/刚保存：对应字段不被 worker 覆盖。
5. 主动开场：状态与语气照常更新。
6. worker 失败：正文不受影响，状态保留旧值，指标留痕。
7. 旧数据：历史 `<MEM_UPDATE>` 不显示；旧 JSON 回复可读。
8. **强制 function call 实测**：worker 短输入 / 长输入（含 40k 级上下文）各跑若干次，记录是否返回调用、参数是否合法、耗时；结论写回本节。
9. **系统上下文**：典型轮 ≤5.5k 字符、满态 ≤8k；不再出现【待跟进承诺】；召回无半句截断（细则见第十一节）。
10. **消息 ID**：`memory_write` / `upsert_lorebook_entry` 能用真实或合成 ID 通过证据校验。
11. **群组朗读**：每段都有成员语气，缺失时回退到群组语气（第二期）。
12. **并发**：用户保存角色卡与 worker 结果互不覆盖（字段级），待办不整批丢失。
13. **指标**：主调用改名后右上角仍显示主轮数据，per-round 记录可见。

## 十、工具

最终保留 **8 个**，写操作按“写的是什么”分类。

### 读写能力

| 工具 | 作用 | 参数 |
|---|---|---|
| `search_memory` | 检索记忆（含盘点模式） | `query` / `date` / `timeRef` / `category` / `limit` / `memberName` |
| `web_search` | 通用联网搜索 | `query`、`limit` |
| `web_fetch` | 读链接（网页/图片/虎扑帖/网易云歌/B站视频） | `url` |
| `search` | 平台搜索 | `platform`（bilibili/netease/hupu）、`query`、`limit` |
| `hot_board` | 热榜 | `platform`（baidu/toutiao/douyin/zhihu）、`limit` |

### 写能力（按写入对象分类）

| 工具 | 写入对象 | 参数 |
|---|---|---|
| `memory_write` | 记忆（长期/短期） | `op`（update/delete）、`id`；update 时给 `subject`/`key`/`value`/`importance`/`promisor`/`promisee`/`timeRef`/`evidence`/`memberName` |
| `upsert_lorebook_entry` | 世界书 | `entryId`/`name`/`keywords`/`content`/`alwaysActive`/`sourceMessageIds`/`evidence`/`memberName` |

### 表达

| 工具 | 作用 | 参数 |
|---|---|---|
| `send_sticker` | 发表情包（有贴图才注册） | `tag` |

### 变更

- 删除：`submit_response`（输出协议）、`ask_user`（正文提问即可）、`get_current_time`（volatile 已给）、`update_character_field`（改由角色卡的字段修订调用）、`add_todo`（改由 worker 维护）。
- 合并：`list_memories` → `search_memory`；三个平台搜索 → `search`；四个热榜 → `hot_board`；`update_memory` + `delete_memory` → `memory_write`。
- 待办与约定与 worker 维护：新建/完成/取消/改期在 `submit_state.todos`，约定完成/取消在 `submit_state.promiseUpdates`；语义上与世界书一样属于后台维护的长期结构，而不是“模型主动调用的能力”。
- `memory_write` 保留退路：评测若显示 `op=update` 的参数错误率高，拆回 `update_memory` / `delete_memory`（总数 9）。
- 平台搜索与 `memory_write` 合并前需过评测：选择准确率 ≥90%、参数正确率 ≥85%。
- 连带改动：`SocialToolsTest` 的“不得出现 platform 参数”护栏、`AGENT_ARCHITECTURE.md` 的“一工具一平台”、热榜轮提示词里的 `zhihu_hot` 字样。
- 体积：单角色 tools 24.3k → 约 7k。

## 十一、系统上下文压缩

目标：单角色典型轮 ≤5.5k 字符、硬上限 8k（现状约 12k，最坏 19k）。超出时按「可选话题 → 场景概要 → 世界书 → 记忆召回」顺序丢弃；【时间】【当前状态】【未完成事项】【本轮写法】不丢。

### 11.1 结构

| 段 | 内容 | 来源 | 预算 | 出现条件 |
|---|---|---|---|---|
| 时间 | 本地时间、星期、时段、逻辑日；间隔 ≥6h 加一句重连提示 | 系统时钟；`instant` 时间戳 | 2 行 | 每轮 |
| 消息元数据 | 最近 14 条非 loading 消息：`id \| role \| timestamp` | `instant` | 600 | 每轮 |
| 当前状态 | 处境/位置/情绪/职业身份/目标；群组另加共享状态与成员状态 | `dynamicState`、`groupSharedDynamic`、`member.dynamicState` | 每项 ≤60 | 每轮（非空字段） |
| 未完成事项 | 待办 + 周期性约定，按 id 去重 | `todos`；`longTerm` 中 promises/habits 命中周期词 | 400 | 有 |
| 世界书 | 条目 `名称：正文`，不再分常驻/相关 | `lorebook` | ≤6 条 / 1400 | 有命中或常驻 |
| 场景概要 | 最近 1 条摘要 | `scenes` | 300 | 有则注入（生成仍为换场景或满 12 条） |
| 记忆召回 | 用户最新纠正 + 最多 3 条记忆 + 一句说明 | `instant`；`longTerm` 检索 | 3600 | 每轮（可空） |
| 成员私人记忆 | 按点名集合，每人 1 条 | 成员 `longTerm` | 300/人 | 群组（第二期） |
| 本轮写法 | 口吻摘要 + 2 示例 + 称呼；节奏骨架；冷场/切换；上轮违规；结尾去重 | `staticProfile`；常量；`styleViolations`；最近回复 | 500 | 有内容才出 |
| 可选话题 | 3 条短话题 | 设定 + 状态 + 记忆现算 | 180 | 冷场 / 主动开场 |
| 本轮指令 | 主动开场 / 热榜 | 调用参数 | 250 | 对应轮次 |

### 11.2 删除

- 【待跟进承诺】：`promises` 已改为信息类字段；有期限、要执行的约定由 `todos` 承载。
- 【上一轮快速回应】：quickReplies 归 worker，主调用不再做视角修复。
- 单独的语气回顾：并入【本轮写法】的“上一轮”，无违规不出现。
- 空字段的「(未设置)」占位。

### 11.3 合并

- 时间基准 + 互动节奏 → 【时间】。
- 待办 + 周期性约定 → 【未完成事项】；同一条长期记忆不得同时以两种身份出现。
- 世界书两个标题 → 【世界书】；常驻只影响是否每轮注入，不在文本里标注。
- 声音契约 + 节奏骨架 + 结尾多样性 + 文风纠正 + 冷场/切换 → 【本轮写法】。
- 用户最新纠正 + 记忆召回 + 两段说明 → 【记忆召回】。

### 11.4 取法

- **召回**：最多 3 条；单条 ≤1200 字、总预算 3600 字（等于不额外限总量）。只有单条超过 1200 字时才按 `。！？；` 边界截断。`pendingRecall` 按**实际渲染出的 id** 消费，不按前 N 条。
- **场景**：最近 1 条、≤300 字；有则注入，不用 `key` 过滤。生成触发保持现状：换场景或满 12 条消息。
- **世界书**：≤6 条、合计 ≤1400 字；该预算已由 `LorebookMatcher.select` 生效，装配层不得重复截断。常驻条目优先保留，不能被普通命中挤出；**空正文条目只在关键词命中时注入**（占位符 + 补写说明保留），需要移除 `MemoryServiceImpl.selectLorebook` 里 `content.isBlank()` 的过滤。
- **当前状态**：只写非空字段、每项 ≤60、不写 `currentTone`。
- **成员私人记忆**：成员集合 = 本轮原文出现的名字 → 上一轮 segment 的 speaker → 都没有时全员各 1 条；每人 ≤300 字。群组第二期实现。
- **声音**：风格摘要 ≤60 + 2 条示例 + 称呼；“带出 2 处签名、禁 AI 腔”的规则留在 system，不重复。

### 11.5 与 worker / 记忆链路的分工

- 待办是“未完成事项”的唯一载体；新建/完成/取消/改期都在 `submit_state.todos`。
- 周期性约定仍由记忆链路写 `promises`/`habits`；worker 只用 `promiseUpdates` 改已有承诺的状态，不新建、不转成待办。
- `promises` 作为信息字段仍可被召回，但不再单独占一段。
- quickReplies 由 worker 生成与校验，主调用不再接收视角提醒。

### 11.6 不改

- `instant` 历史窗口与折叠节奏（存什么、怎么折）。
- 短期记忆块：仍是整窗注入，不截条数、不加预算。
- 请求内的历史序列化按 12.2 改为纯文本（只改 `mapHistory`，不动存储）。

### 11.7 落地

- 重写 `Prompts.buildVolatileContext`，标题、顺序、预算按 11.1；各段独立拼接，空段整段不出现。
- 新增按段字符统计，随 `RequestMetric` 落盘（`contextChars` 之外再加分块计数），用于回归对比。
- 单测：固定角色 fixture 断言段结构、空段不出现、总长 ≤8k、召回不出现半句截断、同地点场景概要仍注入。
- 文档：`OUTBOUND_CALLS_V2.md` 的 input 段已按本结构更新；`AGENT_ARCHITECTURE.md` 同步 volatile 段说明。

## 十二、审计修正与实施前必改

五个方向（上下文保真、协议解析、工具合并、写入并发、群组兼容）审计后，以下问题必须先修，否则会出现“实现完比现在更差”。

### 12.1 P0

| # | 问题 | 修正 | 证据 |
|---|---|---|---|
| 1 | 主调用看不到消息 ID，`memory_write` / `upsert_lorebook_entry` 的证据校验必然失败 | 保留现有【消息时间元数据】块（最近 14 条 `id \| role \| timestamp`），不做删减；两条写入工具必须有测试覆盖 | `Prompts.kt:919-923`、`MemoryEvidence.kt:79-106`、`UpdateMemoryTool.kt:57` |
| 2 | 删除 `submit_response` 后 `AgentLoop` 没有终稿相，空回合重试与工具轮兜底一起消失 | 新增 FINAL 相：`tools` 为空、`toolChoice=Auto`、独立 `emptyAttempts`（2 次）+ 硬上限；删除 `submitTool`、`syntheticSubmitReply`、`ask_user` 特判 | `AgentLoop.kt:91,178-208` |
| 3 | 纯文本下 `proseFallback` 恒为 true，每轮多一次全量调用，并绕过编辑保护双写 `dynamicState` | 显式关闭；删除 `PROSE_SYSTEM` / `convertProseToJson` 的生产路径，只在 reply 解析为空时兜底一次 | `ChatOrchestrator.kt:300`、`BackgroundTasks.kt:1874-1880` |
| 4 | 合并层是对象级 `pick`，时间戳保护没有数据源，编辑器保存还会清空 meta | 写入改补丁式（字段 → 值）；锁下沉到 `CharacterRepository.mutate(id)`；补齐 `updatedAt` 写入点；编辑器只提交实际改动 | `CharacterMerge.kt:23-60`、`CharacterParity.kt:270-285`、`AppViewModel.kt:716-729` |
| 5 | 群组成员语气/状态没有写入者，TTS 按成员取语气会拿到空值 | 主调用按成员输出语气或 worker 写 `memberDynamicState.currentTone`；`toneFor` 增加“成员缺失 → 群组语气”回退 | `AppViewModel.kt:2416-2422`、`ChatOrchestrator.kt:434-450` |
| 6 | 场景概要用 `key != currentLocation` 过滤，同地点长聊会永远不注入 | 改为有则注入最近 1 条，不用 `key` 过滤；生成触发保持“换场景或满 12 条”；补“同地点连续 3 条摘要仍能进上下文”测试 | `BackgroundTasks.kt:850-858`、`Prompts.kt:1023` |
| 7 | 静态字段的模型写入口全部消失（工具删、submit 删、worker 不写） | 字段修订调用先落地（同时 patch draft 与 live），再删 `update_character_field`；否则保留工具 | `UpdateCharacterFieldTool.kt:92`、`Prompts.kt:337,349,392` |
| 8 | `submit_state.todos` 缺 `proposer` 与 create 路径，角色提议的待办会被静默丢弃 | schema 加 `proposer`（user/character）；补 create 应用路径并走 `validateTodoCreation`；期限允许锚定角色上一轮原话；失败写指标不静默 | `OUTBOUND_CALLS_V2.md:1056-1084`、`TodoPolicy.kt:70-86` |
| 9 | 群组状态有两个对象（`groupSharedDynamic` / `character.dynamicState`），写入与读取各认一个，新【当前状态】会读不到值 | 定唯一来源（建议 `groupSharedDynamic`）；`applyDynamicStateUpdate` 按 `isGroup` 分流；迁移旧数据；删除 volatile 里对群组 `character.dynamicState` 的读取。**群组第二期，首版单角色沿用旧 volatile** | `Prompts.kt:288-294,962-965`、`CharactersScreen.kt:871-885`、`BackgroundTasks.kt:1296-1314` |

### 12.2 P1

| # | 问题 | 修正 |
|---|---|---|
| 1 | 历史 assistant 仍包成 `{"reply":...}`，与纯文本协议冲突 | `mapHistory` 改纯文本；旧数据解析兼容，不改 `instant` 存储 |
| 2 | 语气行 hold-back 会让单行回复整段延迟；剥离失败会把朗读指导念出来 | 见到 `【语气】` 才截断，保留 6 字符尾巴；统一 strip 函数；群组先切段再剥 |
| 3 | 旧工具名残留在 RULE_A、热榜、`toolActivityHint`、错误回执 | 全量替换为 8 个新工具；`PromptToolListTest` 增加反向断言：不得出现已删工具名 |
| 4 | 三个平台搜索改同名 `search` 会被 `firstOrNull` 静默打到 bilibili | 新建单一 `PlatformSearchTool` / `HotBoardTool` 读 `platform`；`ToolRegistry` 重名即抛；补平台路由测试 |
| 5 | `memory_write` 把 `evidence` 改为可选、漏 `dueAt`、按 `short_` 前缀判短期 | evidence 一律必填；补 `dueAt`；短期按“是否在 `character.shortTerm`”判定，不按前缀 |
| 6 | `search_memory` 不读 `category/limit`，盘点能力比 `list_memories` 窄 | 实现 category/limit；保留 keyword 与全分类盘点；客户端对“四参全空”返回可操作错误 |
| 7 | 指标 60 条上限、主轮用量被求和、`taskType=="chat"` 过滤会被改名打断 | 指标独立 repository；`LoopOutcome` 暴露 per-round；每个 taskType 各自保留 N 条；过滤改 `startsWith("chat")` |
| 8 | 群组 segments 复用 TTS 文本会丢括号动作；备份不存 segments；“被点名”无判定 | `displayText` / `speechText` 分离；备份三处补 segments；点名规则：本轮原文 → 上一轮 segment speaker → 全员各 1 条；纳入丢弃顺序 |
| 9 | 计划误以为世界书 1400 未生效；空正文条目被服务层过滤 | 11.4 已修正：保留占位符，移除 `MemoryServiceImpl.selectLorebook` 的 `content.isBlank()` 过滤；装配层不重复截断；常驻条目单独保底 |
| 10 | 召回“超 800 字整条跳过”会饿死长弧记忆；`pendingRecall` 按前 N 条消费会误清 | 超长按句边界截断；按实际渲染出的 id 消费 |
| 11 | 删掉承诺注入后，有期限但没转成 todo 的承诺会消失 | analysis 建带 `dueAt` 的 promise 时同步建 todo；或保留最多 2 条到期窗口内的承诺 |
| 12 | worker 强制调用没有生产覆盖：`tools` 与 `tool_choice` 不同给会 400；现有后台调用都不传 tools | 开工前实测对象式 `tool_choice`；`LlmRequest` 在 `toolChoice != Auto && tools.isEmpty()` 时本地断言；worker 复用同一套序列化 |
| 13 | 主调用不再产出 quickReplies 后，旧修复轮会每轮触发并与 worker 抢写 | worker 为唯一生产者；仅 worker 未启用/失败时才走修复；`AppViewModel` 只在非空时写入；新消息时清空 |

### 12.3 实施顺序

1. `CharacterRepository.mutate(id)` + 补丁式合并。
2. `AgentLoop` FINAL 相与空回合计数。
3. `mapHistory` 纯文本 + `proseFallback` 关闭。
4. 字段修订调用落地。
5. `submit_state.todos` 的 `proposer` 与 create。
6. 群组成员语气写入与回退。
7. 指标独立存储与 per-round。
8. 工具合并、提示词清洗、测试迁移。
9. volatile 11 段重写（单角色先行；群组沿用旧 volatile，第二期切换）。

### 12.4 测试迁移

- 编译期必须改：`MemoryToolsTest`、`AddTodoToolTest`、`SocialToolsTest`、`ChatOrchestratorTest`、`SubmitResponseSchemaTest`。
- 新增：FINAL 相空回合、字段级合并、待办 create 闸门、群组语气回退、上下文预算、提示词残留反向断言。
- `tools/native-verify.ps1` 全绿是合并前提。

