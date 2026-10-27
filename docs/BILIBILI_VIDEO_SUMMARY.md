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
- **无字幕时另外回 3~4 张给主模型**：这时候画面本身就是证据，直接把帧塞进工具结果（`input_text` + `input_image(detail:low)`），让能识图的模型自己看、自己说；有字幕时不塞（文字信息足够，图片纯占上下文）。回给主模型的帧是**按位置均匀挑**的（`spreadPick`）——以前是"时间最靠前的两张"，等于只给开头，模型自然只会说开头。缓存里只存纯文字，所以命中缓存永远不带 base64。
- **按需取单帧（`frame_at`）**：工具参数 `frame_at="3:20"` 时用 `nearestStoryboardFrame` 找到时间轴上最近的一帧，裁出来存盘，作为 `AgentToolResult.imageFileRef` 回给上层，由 `AppViewModel` 追加成一条**助手图片消息**（并 `persistImageFromRef` 从缓存目录复制到 `filesDir/images`，免得清缓存后聊天里留破图）。

## 3. 本地缓存（7 天）

`BiliVideoCache`（`:app`）按"视频 + 分P"存一份工具结果 JSON，默认 `DEFAULT_TTL_MS` = 7 天。第 1 P 的键是裸 BV 号（历史缓存继续有效），第 N>1 P 是 `<bvid>#p<N>`——同一视频的两个分P 内容不同，不能互相命中：

- 命中 → 直接把上次的结果返回，`cached=true`；不抓字幕、不抽帧、不调模型。
- 过期 → 读的时候删掉（连同它的截图），下次重新抓。
- 带 `focus` 的提问**不读也不写缓存**：问题不同，答案不能复用。带 `from` 的续读同样不读不写（它只覆盖后段，覆盖写会把泛总结弄残）；`refresh=true` 强制重看一遍、不看缓存。**带 `extras` 的调用同样不读不写**：评论/弹幕/相关推荐是时效内容，缓存 7 天等于骗人。
- 工具结果里带 `cached=true` 时说明这是旧结果。
- 为什么用文件而不是 Room 表：内容既有 JSON 又有截图，文件天然匹配；而当前 Room 库还是 version 1、没有迁移历史，为一张缓存表升库要手写 Migration（列类型/可空性对不上会让老用户装完直接崩），风险远大于收益——缓存本来就允许丢。目录用 `cacheDir`，系统空间紧张时可以清掉。

**字幕原文与截图只留本机缓存**：不进数据库、不进备份导出、不进聊天历史（历史是追加式的，塞进去会跟着后面每一轮重新计费）。

## 4. 总结策略（`VideoSummaryService`）

有字幕：

- 产出结构固定为**「一句话 + 整体脉络 + 关键节点」**：一句话说清讲了什么（≤40 字），整体脉络 3~5 句按时间顺序说清怎么展开、最后得出什么结论（这是"领会整体内容"的关键），关键节点 2~8 条带 `mm:ss`。只有要点、没有脉络时模型只能念几条点，看起来就像"只看了开头"。
- ≤ `CHUNK_CHARS`(14000 字) → 一次补全。实测 28 分钟视频（637 句 / 7730 字，加时间戳前缀约 12.8k 字符）落在一次调用内。
- 更长 → 按行切成最多 `MAX_CHUNKS`(8) 段，逐段提要点（每段第一行先概括"本段在讲什么"）后合并成整体脉络 + 3~8 条。
- **截断不再静默**：`VideoDigest.coveredUntilSec` 报出真正读到的最后一条字幕，工具据此在结果里写 `covered_until` / `unread_sec` / `can_continue`，并在 `note` 里要求模型"没看完就说清看到哪，用户要继续就带 `from=<covered_until>`"。`from` 参数只把该时间点之后的字幕喂给总结，不会从头再讲一遍。
- 补全走 `:app` 的辅助通道（`trackedComplete`，`auxiliary=true` 90s 超时），并在 `requestMetrics` 里记一条 `video_summary`（任务名保留旧值，便于和历史指标对比），失败带原因。

没字幕（`source=metadata`）：只给标题/简介/章节标签/画面截图，系统提示词要求"第一句就带保留说法、严禁编造具体观点/数据/人名、不要假装看过"，要点 2~4 条。工具结果里 `has_subtitle=false`、`mode=shallow`，提示词要求角色转述时说清"没字幕"。

## 5. 分P / 合集（系列）/ 分段

三者都来自**同一个** `x/web-interface/view` 响应，不额外发请求：

| 数据 | 字段 | 用途 |
|---|---|---|
| 分P | `pages[]`（`page` / `cid` / `part` / `duration`） | 多 P 视频选第几 P：显式 `p` 参数或链接里的 `?p=3`；**`cid` 决定字幕与截图墙取哪一段**，选错就等于讲了另一段 |
| 合集 | `ugc_season`（`title` + `sections[].episodes[].{bvid,title,arc.duration}`） | 回答"这个系列还有哪些""下一集是什么"；不在合集里的普通视频没有这个字段 |
| 分段 | `x/player/wbi/v2` 的 `view_points`（免登录） | 视频自己的章节标签，回答"分几段、每段讲什么" |

- 工具结果里分别是 `page` / `page_count` + `pages`、`season`（`title` / `count` / `current` / `episodes`）、`chapters`（`from` / `title`），列表各自封顶 50 条；`note` 会写明怎么用（换第几 P 带 `p`、看某一集给那集 BV 号、分段按 `chapters` 回答），否则模型容易把列表当正文念出来。
- **总结只针对选中的那一 P**：总结提示词的头部会写"第 X/Y P"，避免把第 2 P 的摘要说成整个视频。
- 缓存的键是"视频 + 分P"：第 1 P 保持裸 BV 号（历史缓存继续有效），第 N>1 P 用 `<bvid>#p<N>`，同一视频不同 P 不会互相命中。
- 番剧（`pgc`）没做：走 `pgc/view/web/season`，参数与风控都是另一套，暂不接。

## 6. 周边信息（`extras`）

字幕/总结回答"视频讲了什么"，这里回答"别人怎么评价、还有什么可看、观众在刷什么"。四项都由 `bilibili_video` 的 `extras` 参数**按需申请**，默认一个额外请求都不发——不改变原有结果、不增加成本。

| extra | 接口 | 登录 | 上限 | 说明 |
|---|---|---|---|---|
| `comments` | `x/v2/reply/wbi/main`（`mode=3` 热门） | **要** | 8 条 / 单条 80 字 | `oid` 用 `aid`（不是 `cid`）；置顶在 `top_replies[]`、其余在 `replies[]`，按「用户+正文」去重 |
| `related` | `archive/related?bvid=` | 免 | 8 条 | `data` 是裸数组（个别版本包成 `data.list[]`） |
| `series` | `seasons_series_list?mid=` + `seasons_archives_list?mid=&season_id=` | 免 | 20 个合集 / 30 条分集 | `items_lists.seasons_list[]`（合集，`meta.season_id`）与 `series_list[]`（系列，`meta.series_id`）；`archives[]` 只是最近几条预览，**不等于全集** |
| `danmaku` | `comment.bilibili.com/<cid>.xml` | 免 | 高频 12 / 时间线 8 | XML `<d p="秒,模式,…">文本</d>`；只回聚合视图，不回原始弹幕流 |

- **评论是唯一有风控风险的一项**：匿名请求实测直接 `-352`，所以走三级降级——WBI 签名的主接口 → 旧 `x/v2/reply` → 放弃并写进 `extras_failed`。
- **弹幕只给「高频 + 时间线」**：相同文本计数后取前 12（纯数字/纯符号过滤掉，它们在弹幕里占比极高但零信息量）；按分钟分桶、每桶取代表句，桶数超过 8 时**均匀挑**（不是取前 8），否则一小时视频的时间线会全挤在开头十分钟。
- **单项失败不拖垮整次调用**：任何一项抓不到只进 `extras_failed`（`{name, reason}`），摘要照常返回，`note` 会要求模型"如实说抓不到，不要编造"。
- `series` 带 `season_id` 时多取一次该合集的分集；不带就从第一个合集取，让模型先看到"合集里有什么"。

## 7. 登录态（SESSDATA）

- 用户在设置页「B 站视频总结（可选）」粘贴**整段 Cookie 或只粘 SESSDATA 的值**（`normalizeBiliCookie` 归一化）；「测试登录」打 `nav` 接口回报"已登录：昵称 / -101 已过期"，不回显 Cookie。
- 存储：`SecretStore`（`EncryptedSharedPreferences` + Keystore）。**刻意不进 `snapshot()`**，所以不会随加密备份导出；不写数据库、不写日志、不进导出 JSON。设置页只显示"已保存/未保存"。
- 取用：`HttpBiliVideoSource` 的 cookie 回调每次调用现取，用户改完设置下一次工具调用即生效，不用重启。
- 没配 Cookie 也能用：只是没有逐句字幕，退化成"元信息 + 章节 + 画面"。

## 8. 时效与风控

- `wbi_img`（签名 key）**每天轮换**：按天缓存，跨天自动重取；签名算法在 `BiliWbi`，用公开向量单测锁住（未登录时服务端不校验 `w_rid`，写错只会在登录后暴露）。
- 字幕 CDN 地址的 `auth_key` **分钟级过期**、URL 不能截断：必须现取现用、不可缓存分发。
- 高频请求会吃 `-412`（风控）/`-799`（频控）：一次用户请求最多一次总结；7 天缓存本身也压住了重复请求。
- **评论区最容易被风控**（匿名实测 `-352`）：所以它只在显式 `extras=comments` 时才请求，且失败就降级、绝不重试轰炸。
- 站内内容只给摘要 + 原视频链接 + 时间戳，不整段展示、不跨用户共享。

## 9. 已知限制（未做）

- **无字幕视频的逐句内容**：需要端侧 ASR（`playurl` 的 DASH 音频流免登录可取 → Media3 解 PCM → sherpa-onnx Paraformer，模型约 227MB 按需下载）。工作量与体积都大，暂缓；当前用"画面 + 简介/章节"兜底。
- **时间戳跳转**：摘要里带 `mm:ss`，模型可拼 `https://www.bilibili.com/video/<bvid>?t=<秒>`，但界面还没做成可点按钮。
- **追问复用**：带 `focus` 的问题每次重新总结（不读泛总结缓存）；续读靠 `from` 参数一段段往前推，没有"摘要 + 相关片段"的二次检索，也不会把整条字幕留在历史里（那样每轮都要重新计费）。
- **`frame_at` 只有一个时间点**：要贴多张图就多调几次（每轮最多 `MAX_RESULT_FRAMES`(4) 张，工具结果里另外塞给主模型的截图也按这个上限）。

## 10. 代码锚点

- `:core:network` 的 `BiliWbi`（签名）、`BiliVideoSource.kt`（`HttpBiliVideoSource` / 字幕·章节·截图墙解析 / `pickStoryboardFrames` / `nearestStoryboardFrame` / `spreadPick` / `BiliLinks` / `formatBiliClock` / `parseBiliDurationClock`）、`BiliExtras.kt`（评论/相关/合集/弹幕的解析 + `digestBiliDanmaku` 聚合）
- `:domain:agent` 的 `video/VideoSummaryService`（两条总结路径 + `coveredUntilSec`）、`video/VideoPieces.kt`（`VideoSummaryCache` / `VideoFrameProvider.frames|frameAt` 接口）、`tools/BilibiliVideoTool`（编排 + 缓存短路 + `from`/`refresh`/`frame_at` + `extras` 按需抓取与降级）
- `:app` 的 `BiliVideoCache`（7 天文件缓存）、`BiliFrameExtractor`（截图墙裁帧 + 单帧）、`NativeCore`（`biliVideoSource` / `videoSummarizer` / `probeBiliLogin`）、`AppViewModel`（`saveBiliCookie` / `testBiliLogin` / 助手附图消息）、`feature:settings` 的「B 站视频总结」分区
