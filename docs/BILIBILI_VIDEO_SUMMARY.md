# B 站视频总结

角色能把一个 B 站视频"讲明白"（这个视频讲了什么、结论在第几分钟）。这条链路只服务 `video_summary` 工具，与 `search`（关键词搜视频）共用 B 站接口但**不共用登录态以外的任何东西**。

## 1. 为什么必须登录

2026-10 实测（免登录、带 `buvid3` Cookie、正常 UA/Referer）：

| 探测 | 结果 |
|---|---|
| `x/web-interface/view` | 正常，给标题/时长/简介/cid/UP主 |
| `x/player/wbi/v2` 的 `subtitle.subtitles` | 24/24 视频为空；部分视频 `need_login_subtitle=true` |
| `x/v2/subtitle/web/view` | `-400` |
| `x/web-interface/view/conclusion/get`（官方 AI 总结） | `-403 访问权限不足` |

结论：**B 站的 AI 字幕、UP 上传的 CC 字幕、官方 AI 总结都只对登录态开放**。所以本功能是应用里唯一需要账号的能力，其余联网工具仍然免登录。

## 2. 数据流

`VideoSummaryTool`（`:domain:agent`）→ `HttpBiliVideoSource`（`:core:network`）→ 三种结果之一：

1. **逐句字幕**：`view` 取 `cid` → `x/player/wbi/v2` 取字幕轨（优先 `ai-zh`，其次任意中文，最后第一条）→ 下载字幕 CDN 的 JSON（`body[].from/content`）。
2. **官方 AI 总结**：字幕取不到时调 `conclusion/get`（`data.result.summary` + `outline[].part_outline[]` 的时间戳），**零 LLM 调用**，直接采用。
3. **失败**：`{ok:false, reason}`，`reason` 里写明"需要登录"或"这个视频没有可用文本"。**工具永远不会让模型凭标题/封面编内容。**

拿到字幕后的总结（`VideoSummaryService`）：

- 字幕 ≤ `CHUNK_CHARS`(6000 字) → 一次补全出「一句话 + 3~6 条带 `mm:ss` 的要点」。
- 更长 → 按行切成最多 `MAX_CHUNKS`(4) 段，逐段提要点后合并成 4~8 条（超出 4 段的部分丢弃，摘要里保留视频总时长，用户追问后段时模型可如实说只看了一部分）。
- 补全走 `:app` 的辅助通道（`trackedComplete`，`auxiliary=true` 90s 超时），并在 `requestMetrics` 里记一条 `video_summary`，失败带原因。
- 结果只有几百字进上下文，**字幕原文既不落库也不进聊天历史**（它是追加式历史的一部分，进来就会跟着后面每一轮重新计费）。

## 3. 登录态（SESSDATA）

- 用户在设置页「B 站视频总结（可选）」粘贴**整段 Cookie 或只粘 SESSDATA 的值**（`normalizeBiliCookie` 归一化）；「测试登录」打 `nav` 接口回报"已登录：昵称 / -101 已过期"，不回显 Cookie。
- 存储：`SecretStore`（`EncryptedSharedPreferences` + Keystore）。**刻意不进 `snapshot()`**，所以不会随加密备份导出；不写数据库、不写日志、不进导出 JSON。设置页只显示"已保存/未保存"。
- 取用：`HttpBiliVideoSource` 的 cookie 回调每次调用现取，用户改完设置下一次工具调用即生效，不用重启。

## 4. 时效与风控

- `wbi_img`（签名 key）**每天轮换**：`HttpBiliVideoSource` 按天缓存，跨天自动重取；签名算法在 `BiliWbi`，用公开向量单测锁住（未登录时服务端不校验 `w_rid`，写错只会在登录后暴露）。
- 字幕 CDN 地址的 `auth_key` **分钟级过期**、URL 不能截断：必须现取现用、不可缓存分发。
- 高频请求会吃 `-412`（风控）/`-799`（频控）：不要在循环里批量抓视频，一次用户请求最多一次总结。
- 字幕原文与官方总结都是站内内容：只给摘要 + 原视频链接 + 时间戳，不整段展示、不跨用户共享、不落库。

## 5. 已知限制（未做）

- **无字幕视频**：需要端侧 ASR（`playurl` 的 DASH 音频流免登录可取 → Media3 解 PCM → sherpa-onnx Paraformer，模型约 227MB 按需下载）。工作量与体积都大，暂缓；当前如实报"没有可用文本"。
- **总结缓存**：目前只有进程内复用，没有 Room 表；同一视频重复总结会重新花一次 LLM 调用。
- **时间戳跳转**：摘要里带 `mm:ss`，模型可拼 `https://www.bilibili.com/video/<bvid>?t=<秒>`，但界面还没做成可点按钮。

## 6. 代码锚点

- `:core:network` 的 `BiliWbi`（签名）、`BiliVideoSource.kt`（`HttpBiliVideoSource` / 解析纯函数 / `BiliLinks` / `formatBiliClock` / `parseBiliDurationClock`）
- `:domain:agent` 的 `video/VideoSummaryService`、`tools/VideoSummaryTool`
- `:app` 的 `NativeCore`（`biliVideoSource`、`videoSummarizer`、`probeBiliLogin`）、`AppViewModel`（`saveBiliCookie` / `testBiliLogin`）、`feature:settings` 的「B 站视频总结」分区
