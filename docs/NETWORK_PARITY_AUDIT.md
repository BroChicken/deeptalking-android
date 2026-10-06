# JS → 原生 网络/请求链路差异审计

对照 `src/js/`（权威）与 `android-lite/`（原生）的请求/响应链路，逐项记录差异。
只描述**当前状态**。

## 已修复（本轮）

| # | 差异 | 旧版 | 原生修复位置 |
|---|---|---|---|
| 1 | OpenCode `x-opencode-session` 请求头缺失 | `src/js/core/normalization.js:68-71` | `core/network/ResponsesLlmBackend.kt`（`opencodeSessionHeader`）+ `engine/ondevice/Inference.kt`（`LlmRequest.apiPlatform/sessionId`）+ `domain/agent/SessionId.kt` |
| 2 | 流式请求缺 `Accept: text/event-stream` | `normalization.js:65-67` | `ResponsesLlmBackend.newRequest(stream)` |
| 3 | 端点未按平台处理 `/v1` | `src/js/prompts/request.js:207-216` `getResponsesEndpoint` | `ResponsesLlmBackend.responsesEndpoint`（deepseek 去 `/v1`，opencode 保留） |
| 4 | 无请求级重试 | `src/js/api/retry.js:1-31` | `domain/agent/ApiRetry.kt` + `AgentLoop.collectStreamWithRetry`（3 次 / 1s,2s,4s；瞬时错误 network/abort/429/5xx） |
| 5 | 每平台独立配置槽 `platformSettings`（baseUrl/apiKey/modelName 互不覆盖） | `src/js/storage/schema.js:353-368`、`status-settings.js:151-174` | `core/model/Config.kt`（`AppConfig.platformSettings` + `PlatformSlot`）+ `feature/settings/SettingsScreen.kt`（`switchPlatform`）+ `SecretStore` 按平台键 + `NativeCore.apiKeyFor(platform)` |

## 待补（已确认缺失）

| # | 差异 | 旧版 | 原生现状 | 影响 |
|---|---|---|---|---|
| 6 | 独立 `cacheStats` 字段（缓存条显示用） | `responses.js:262-301`、`stickers.js:266-288` | 原生用 `requestMetrics` 推算，缺 `cacheStats.hitTokens/missTokens/promptTokens` | 中 |

> SSE `response.function_call_arguments.done`（`conversation.js:620-623`）、`response.output_text.done`（`conversation.js:653-657`）、`web_search_call.*`（`conversation.js:552-562`）均已实现：`ResponsesLlmBackend.kt` 处理 `function_call_arguments.done` / `output_text.done` / `web_search_call.*`（含 `web_search_call.completed` → 状态提示），由 `ResponsesSseInterpreterTest` 覆盖。

## 已核对一致

- Agent 工具集（11 + `send_sticker` 条件 + `submit_response`）：`src/js/agent/tool-definitions.js:10-116` ↔ `domain/agent/tools/*`。
- 结构化解析（`submit_response` / 散文即终稿 / quickReplies 不阻塞收尾）：见 `docs/PARSE_GRAPH.md` 与差分测试。
- 端点路径（`/responses`）与请求体字段（model/input/instructions/tools/tool_choice/temperature/max_output_tokens/reasoning.effort）。
- 用量解析：`input_tokens_details.cached_tokens` 与 `prompt_cache_hit_tokens` 回退。
