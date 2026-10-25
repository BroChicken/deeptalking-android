# B 站视频总结

角色能把一个 B 站视频"讲明白"（这个视频讲了什么、结论在第几分钟），**没字幕也能说个大概**。这条链路服务 `bilibili_video` 工具（B 站视频的唯一入口：`query` 搜视频、`url` 总结视频）。搜索不再走 `search`，两者共用 B 站接口但**不共用登录态以外的任何东西**。

## 1. 三种输入，没有一种是"失败"

| 输入 | 条件 | 用法 |
|---|---|---|
| 逐句字幕 | 需要登录态（`SESSDATA`） | 主力路径：分块总结出带 `mm:ss` 的要点 |
| 官方 AI 总结 | 需要登录态 | 有就直接采用，**零 LLM 调用** |
| 元信息 + 章节 + 画面 | **免登录** | `source=metadata`：只说"没字幕、看画面像是…"，禁止编造 |

2026-10 实测（免登录、正常 UA/Referer）：

| 探测 | 结果 |
|---|---|
| `x/web-interface/view` | 正常，给标题/时长/简介/cid/UP主 |
| `x/player/wbi/v2` 的 `subtitle.subtitles` | 24/24 视频为空（无登录态） |
| `x/player/wbi/v2` 的 `view_points` | **免登录可取**，4~18 字的章节标签，部分视频有 |
| `x/player/videoshot` | **免登录可取**：2~4 张 10×10 拼图，每帧 480×270，`index` 是每帧秒数 |
| `x/v2/subtitle/web/view` | `-400` |
| `x/web-interface/view/conclusion/get` | `-403 访问权限不足` |

**带登录态实测**（28 分钟视频）：`tracks=1 / ai-zh`、字幕 637 句 7730 字、`conclusion` 返回 `code=0` 但摘要为空（说明"官方总结"只是少数视频才有的省事路径）。

## 2. 抽帧（免登录）

`x/player/videoshot` 是 B 站自己的"截图墙"：整段视频被切成 10×10 的拼图，每帧 480×270。下载拼图后用 `BitmapRegionDecoder` 只解出目标区域（内存里只有一小块 Bitmap），比"下载视频流再解码"便宜两个数量级，也不需要 ffmpeg。

- 默认抽 `DEFAULT_FRAME_COUNT`(6) 帧，按帧号等距覆盖全片（`pickStoryboardFrames`），同一张拼图只下一次。
- 截图存 `<bvid>-f<帧号>.jpg`（JPEG 82，宽度上限 640），与总结 JSON 放在同一个缓存目录。
- **失败一律降级为空表**：没有截图墙、下载超时、内存不足都不会让总结失败，只是变成纯文字。
- 抽到的帧作为 `input_image` 喂给辅助调用；模型不支持看图（400）时自动退回纯文字重试一次。
- **无字幕时另外回 1~2 张给主模型**：这时候画面本身就是证据，直接把帧塞进工具结果（`input_text` + `input_image(detail:low)`），让能识图的模型自己看、自己说；有字幕时不塞（文字信息足够，图片纯占上下文）。缓存里只存纯文字，所以命中缓存永远不带 base64。

## 3. 本地缓存（7 天）

`BiliVideoCache`（`:app`）按 BV 号存一份工具结果 JSON，默认 `DEFAULT_TTL_MS` = 7 天：

- 命中 → 直接把上次的结果返回，`cached=true`；不抓字幕、不抽帧、不调模型。
- 过期 → 读的时候删掉（连同它的截图），下次重新抓。
- 带 `focus` 的提问**不读也不写缓存**：问题不同，答案不能复用。
- 为什么用文件而不是 Room 表：内容既有 JSON 又有截图，文件天然匹配；而当前 Room 库还是 version 1、没有迁移历史，为一张缓存表升库要手写 Migration（列类型/可空性对不上会让老用户装完直接崩），风险远大于收益——缓存本来就允许丢。目录用 `cacheDir`，系统空间紧张时可以清掉。

**字幕原文与截图只留本机缓存**：不进数据库、不进备份导出、不进聊天历史（历史是追加式的，塞进去会跟着后面每一轮重新计费）。

## 4. 总结策略（`VideoSummaryService`）

有字幕：

- ≤ `CHUNK_CHARS`(14000 字) → 一次补全出「一句话 + 3~6 条带 `mm:ss` 的要点」。实测 28 分钟视频（637 句 / 7730 字，加时间戳前缀约 12.8k 字符）落在一次调用内。
- 更长 → 按行切成最多 `MAX_CHUNKS`(4) 段，逐段提要点后合并成 4~8 条（超出部分丢弃，摘要里保留总时长，追问后段时可如实说只看了一部分）。
- 补全走 `:app` 的辅助通道（`trackedComplete`，`auxiliary=true` 90s 超时），并在 `requestMetrics` 里记一条 `video_summary`（任务名保留旧值，便于和历史指标对比），失败带原因。

没字幕（`source=metadata`）：只给标题/简介/章节标签/画面截图，系统提示词要求"第一句就带保留说法、严禁编造具体观点/数据/人名、不要假装看过"，要点 2~4 条。工具结果里 `has_subtitle=false`，提示词要求角色转述时说清"没字幕"。

## 5. 登录态（SESSDATA）

- 用户在设置页「B 站视频总结（可选）」粘贴**整段 Cookie 或只粘 SESSDATA 的值**（`normalizeBiliCookie` 归一化）；「测试登录」打 `nav` 接口回报"已登录：昵称 / -101 已过期"，不回显 Cookie。
- 存储：`SecretStore`（`EncryptedSharedPreferences` + Keystore）。**刻意不进 `snapshot()`**，所以不会随加密备份导出；不写数据库、不写日志、不进导出 JSON。设置页只显示"已保存/未保存"。
- 取用：`HttpBiliVideoSource` 的 cookie 回调每次调用现取，用户改完设置下一次工具调用即生效，不用重启。
- 没配 Cookie 也能用：只是没有逐句字幕，退化成"元信息 + 章节 + 画面"。

## 6. 时效与风控

- `wbi_img`（签名 key）**每天轮换**：按天缓存，跨天自动重取；签名算法在 `BiliWbi`，用公开向量单测锁住（未登录时服务端不校验 `w_rid`，写错只会在登录后暴露）。
- 字幕 CDN 地址的 `auth_key` **分钟级过期**、URL 不能截断：必须现取现用、不可缓存分发。
- 高频请求会吃 `-412`（风控）/`-799`（频控）：一次用户请求最多一次总结；7 天缓存本身也压住了重复请求。
- 站内内容只给摘要 + 原视频链接 + 时间戳，不整段展示、不跨用户共享。

## 7. 已知限制（未做）

- **无字幕视频的逐句内容**：需要端侧 ASR（`playurl` 的 DASH 音频流免登录可取 → Media3 解 PCM → sherpa-onnx Paraformer，模型约 227MB 按需下载）。工作量与体积都大，暂缓；当前用"画面 + 简介/章节"兜底。
- **时间戳跳转**：摘要里带 `mm:ss`，模型可拼 `https://www.bilibili.com/video/<bvid>?t=<秒>`，但界面还没做成可点按钮。
- **追问复用**：带 `focus` 的问题每次重新总结（不读泛总结缓存），没有"摘要 + 相关片段"的二次检索。

## 8. 代码锚点

- `:core:network` 的 `BiliWbi`（签名）、`BiliVideoSource.kt`（`HttpBiliVideoSource` / 字幕·章节·截图墙解析 / `pickStoryboardFrames` / `BiliLinks` / `formatBiliClock`）
- `:domain:agent` 的 `video/VideoSummaryService`（两条总结路径）、`video/VideoPieces.kt`（`VideoSummaryCache` / `VideoFrameProvider` 接口）、`tools/VideoSummaryTool`（编排 + 缓存短路）
- `:app` 的 `BiliVideoCache`（7 天文件缓存）、`BiliFrameExtractor`（截图墙裁帧）、`NativeCore`（`biliVideoSource` / `videoSummarizer` / `probeBiliLogin`）、`AppViewModel`（`saveBiliCookie` / `testBiliLogin`）、`feature:settings` 的「B 站视频总结」分区
