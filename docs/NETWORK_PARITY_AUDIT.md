# JS → 原生 网络/请求链路差异审计

对照 `src/js/`（权威）与 `android-lite/`（原生）的请求/响应链路，逐项记录差异。
只描述**当前状态**。

## 已修复（本轮）

| # | 差异 | 旧版 | 原生修复位置 |
|---|---|---|---|
| 1 | OpenCode `x-opencode-session` 请求头缺失 | `src/js/core/normalization.js:68-71`（含 `testApiConnection` 经 `buildApiHeaders` 带头，`src/js/ui/status-settings.js:204`） | `core/network/ResponsesLlmBackend.kt`（`opencodeSessionHeader`，`newRequest` 与 `:app` 的 `NativeCore.testApiConnection` 探针均调用）+ `engine/ondevice/Inference.kt`（`LlmRequest.apiPlatform/sessionId`）+ `domain/agent/SessionId.kt` |
| 2 | 流式请求缺 `Accept: text/event-stream` | `normalization.js:65-67` | `ResponsesLlmBackend.newRequest(stream)` |
| 3 | 端点未按平台处理 `/v1` | `src/js/prompts/request.js:207-216` `getResponsesEndpoint` | `ResponsesLlmBackend.responsesEndpoint`（deepseek 去 `/v1`，opencode 保留） |
| 4 | 无请求级重试 | `src/js/api/retry.js:1-31` | `domain/agent/ApiRetry.kt` + `AgentLoop.collectStreamWithRetry`（3 次 / 1s,2s,4s；瞬时错误 network/abort/429/5xx） |
| 5 | 每平台独立配置槽 `platformSettings`（baseUrl/apiKey/modelName 互不覆盖） | `src/js/storage/schema.js:353-368`、`status-settings.js:151-174` | `core/model/Config.kt`（`AppConfig.platformSettings` + `PlatformSlot`）+ `feature/settings/SettingsScreen.kt`（`switchPlatform`）+ `SecretStore` 按平台键 + `NativeCore.apiKeyFor(platform)` |
| 6 | 独立 `cacheStats` 字段（缓存条显示用） | `responses.js:262-301`、`stickers.js:266-288` | `AppViewModel.recordMetrics` 每次成功 chat 写 `CacheStats`，`AppRoot.CachePill` 读 `config.cacheStats`（空态 `缓存 --`，近 10 次均值） |
| 7 | 缓存字段回退与用量来源 | `responses.js:271-279` | `ResponsesLlmBackend`/`ResponsesDto` 同时解析 `prompt_cache_hit/miss_tokens` 与 `input_tokens_details.cached_tokens` |
| 8 | 缺 Key 抛错 / `reasoningEffort` 校验 / Chat-Completions 兜底 | `normalization.js:31-33,63`、`responses.js:221-226` | `ResponsesLlmBackend.authorizationHeader`（缺 Key 抛错）、`normalizeReasoningEffort`（白名单兜底）、SSE `else` 分支读 `choices[0].delta.content` |
| 9 | 抓取图片作为多模态输入 | `web-content.js:139/170/184` | `buildResponsesInput.toolOutputElement` 识别 `[{input_text},{input_image}]` 数组并原样作为 `function_call_output` 下发 |
| 10 | 设备信任库缺锚导致 `opencode.ai` TLS 校验失败（`CertPathValidatorException: Trust anchor ... not found`） | 旧版无此问题：`fetch()` 由 WebView/Chromium 代管 TLS（自带根库 + AIA 补链）；原生 OkHttp/Conscrypt 只信系统库且不补链 | `:app` 的 `res/xml/network_security_config.xml`（对 `opencode.ai` 保留 `system` 锚并附加 `res/raw/opencode_we1.pem`/`opencode_gts_root_r4.pem`/`opencode_globalsign_root_ca.pem`） |

## 待补（已确认缺失）

_无。_

> SSE `response.function_call_arguments.done`（`conversation.js:620-623`）、`response.output_text.done`（`conversation.js:653-657`）、`web_search_call.*`（`conversation.js:552-562`）均已实现：`ResponsesLlmBackend.kt` 处理 `function_call_arguments.done` / `output_text.done` / `web_search_call.*`（`in_progress` 发「正在联网搜索…」，`completed` 按旧版不设状态；`reasoning_summary_text.delta` 按旧版忽略），由 `ResponsesSseInterpreterTest` 覆盖。

## 已核对一致

- Agent 工具集（11 + `send_sticker` 条件 + `submit_response`）：`src/js/agent/tool-definitions.js:10-116` ↔ `domain/agent/tools/*`。
- 结构化解析（`submit_response` / 散文即终稿 / quickReplies 不阻塞收尾）：见 `docs/PARSE_GRAPH.md` 与差分测试。
- 端点路径（`/responses`）与请求体字段（model/input/instructions/tools/tool_choice/temperature/max_output_tokens/reasoning.effort）。
- 用量解析：`input_tokens_details.cached_tokens` 与 `prompt_cache_hit_tokens` 回退。
