# Agent 后续方向（Roadmap）

> 记录已评估的优化方向，标注实施状态。已实施内容见 `hub_1.html` 与 `docs/AGENT_ARCHITECTURE.md`。
> 本文件是根目录旧 `AGENT_OPTIMIZATIONS.md` 的迁移版，按"架构层 / 体验层 / 记忆 / 性能安全"重组，并补充了原理对照。

## 已弃用功能（试验失败品，请勿再使用/扩展）

| 功能 | 状态 | 结论 |
|---|---|---|
| **像素头像**（`avatarPixel` 16×16 网格 + `requestPixelAvatar` / "生成像素头像"按钮） | 已弃用，2026-08 起移除展示与自动修复入口 | 试验失败品：模型输出网格常缺行/缺色/尺寸错误，不稳定；代码仅保留 `normalizePixelAvatar` 做历史数据兼容，不再渲染；头像统一走 emoji |
| **TTS 语音合成**（`tts/` 目录、`android-app` 的 sherpa-onnx 方案） | 已弃用 | 试验失败品：模型体积大、合成不稳定、体验差；轻量 APK（android-lite）不再包含 TTS |

**当前头像方案**：emoji（角色/群组各一个字符）。损坏/缺失的 emoji 头像由 AI 自动生成贴切 emoji（`requestEmojiAvatar` / `autoRepairAvatars`），编辑器提供"AI 生成 emoji 头像"按钮。

## 已实施（2026-08）

| 项 | 说明 | 代码锚点 |
|---|---|---|
| 记忆冲突自动裁决 | 同 key 新值不再静默覆盖：标记 `conflictedAt` + `conflicts` 保留双方，`resolveMemoryConflicts` 按"时间晚 > 证据长 > 保持主值"自动合并，用户全程无感 | `upsertLongTermMemory` / `resolveMemoryConflicts` / `memoriesSemanticallyDiffer` |
| 话题切换显式跟踪 | `detectTopicSwitch` 用双字 bigram 判断新话题，向上下文注入"先收束旧话题再进入新话题"的软指令 | `detectTopicSwitch` / `buildVolatileContext` |
| 记忆重要性自学习 | `usageCount` 累计使用量，30 天内被使用自动升权（每 2 次 +1 封顶 +3）；userProfile/habits 45/90/180 天未召回逐档降权（只降不删） | `selfLearnMemoryImportance` / `handleRecall` / `search_memory` 工具 |
| 剧情弧线摘要 | 记忆分析可输出 `arcOf`（持续情节线）+ `arcStage`（起始/发展/转折/现状），叙事性 events 检索时加权，角色可自然提起旧事 | `analyzeShortToLongTerm` prompt / `retrieveRelevantMemories` |
| 成员记忆隔离 | 群组每个成员独立 `member.memory.longTerm`；工具与 `submit_response.longTerm` 支持 `memberName` 路由；上下文分注入各成员私人记忆 | `getMemberMemory` / `buildVolatileContext` / `applyMemoryUpdate` |
| API 请求级自动重试 | 瞬时失败（网络/超时/429/5xx）自动重试 3 次（1s/2s/4s 退避），业务 4xx 不重试，仍失败走原错误提示 | `performChatRequestWithRetry` / `isTransientApiError` |
| 工具失败可恢复 | 记忆工具失败回传"可修正参数"提示，模型下一轮自行修正；`web_search` 失败事件已捕获，角色自行应对 | `executeToolCall` / SSE handler |
| 主动话题避免冷场 | `buildTopicSuggestions` 从角色背景+记忆抽取候选话题库；冷场识别（`isColdFieldRequest`）时强制角色主动开话题，不反问"你想聊什么" | `buildTopicSuggestions` / `isColdFieldRequest` / `buildVolatileContext` |

## 一、架构层改进（待实施）

### 1. 成员主动插话触发
- **价值**：中
- **成本**：中高
- **原理**：当前群组 reply 是"每人独立一行"格式。可为群组增加"某成员希望发言"的内部信号，模型在 reply 中按 `成员名:"内容"` 自然切换发言者，支持中途插话节奏，增强真实感。

## 二、体验层改进（待实施）

### 2. 记忆可视化面板
- **价值**：中（偏体验）
- **成本**：低
- **原理**：侧边栏新增"记忆"标签页，按 longTerm 分类展示条目数、重要性热力图（1-10 分色阶）、最近召回时间。用户直观看到 agent 记住了什么，也可手动删除/置顶。注意：默认 UI 不应出现，需放入开发者/调试模式以免破坏沉浸感。

### 3. 工具执行步骤可视化
- **价值**：中
- **成本**：低
- **原理**：气泡内显示工具步骤徽章（"回忆→整理→回复"），当前只有文字提示。注意：此功能与"加载反馈即进度提示"的现有体验叠加时需评估，避免暴露工具机制。

### 4. 搜索结果来源展示
- **价值**：中（信任感）
- **成本**：低
- **原理**：`web_search` 工具结果解析后，把来源 URL 存进记忆 attributes 供角色"有依据地说话"，而非渲染成可见链接（不破坏沉浸）。

### 5. 多模态输出
- **价值**：中（沉浸感）
- **成本**：高
- **原理**：`reply` 中支持模型生成的图片/emoji 增强表达；需接图片生成 API 或在 `submit_response` schema 增加 `imagePrompt` 字段。

## 三、记忆智能（待实施）

### 6. 长对话压缩摘要增强（剧情弧线进阶）
- **价值**：中
- **成本**：中
- **原理**：已实施单条弧线标注（`arcOf`/`arcStage`）。可进一步：把连续多轮相关事件压缩成一条带完整情绪弧线的叙事摘要，供长期记忆引用；同一 arcOf 多条时合并成更长的叙事。

### 7. 成员记忆隔离进阶（群组共享记忆的显式管理）
- **价值**：中高
- **成本**：中高
- **原理**：当前已按 `memberName` 隔离。可进一步：群组共享记忆与成员私人记忆的归属规则由模型按"公开 vs 私下"判断，必要时增加 `ask_user` 之外的自动置信度判断，避免过度写入共享记忆。

## 四、性能与健壮性（待实施）

### 8. 搜索结果缓存
- **价值**：中（省 token）
- **成本**：中
- **原理**：同一查询的 `web_search` 结果按角色缓存（如 1 小时），避免重复搜索。

### 9. 工具调用频率限制
- **价值**：中（安全）
- **成本**：低
- **原理**：对 `web_search` 增加每轮次数限制（当前已有 `MAX_TOOL_ROUNDS=5` 总量上限），防止模型刷工具；`delete_memory` 已通过失败提示引导先查再删，无需额外确认 UI。

## 附录：与现有实现的关系

- 所有优化都在现有五大部件（API 载体 / 工具清单 / 工具实现 / 运行循环 / 结构化收尾）内扩展，不改整体架构。
- 涉及记忆写入的项需同步约束 `isValidAutomaticMemory`（hub_1.html）与 `applyMemoryUpdate` 的校验逻辑，防止"记录了什么"与"校验规则"脱节。
