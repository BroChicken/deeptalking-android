# 远程语音朗读（PC 合成 / 手机播放）

手机把朗读交给电脑上的 CosyVoice3 服务，自己只收 PCM 边收边播。适用于手机端侧合成太慢、
或想用更大/更高精度的量化模型（PC 上可换 Q8/F16）的场景。**这是可选功能**：设置页「朗读引擎」
默认仍是「端侧 CosyVoice3（本机）」，切到「远程服务器（电脑）」才启用。

- 手机侧实现：`:engine:remote-tts`（`RemoteTtsClient` / `RemoteTtsSpeaker` / `LanDiscovery`）
- 电脑侧服务：上游同源项目 [Lourdle/cosyvoice.cpp](https://github.com/Lourdle/cosyvoice.cpp) 的
  `cosyvoice-server`（`POST /v1/audio/speech`，OpenAI Speech 兼容）
- 播放门面：`:engine:ondevice` 的 `TtsSpeaker`，本机与远程两个实现由 `NativeCore.ttsSpeaker` 分派

## 1. 电脑侧准备（Windows x64）

1. 从 `cosyvoice.cpp` 的 Releases 下载 `cosyvoice-server`（建议 `miniaudio` 或 `no_audio` 变体，
   不需要 FFmpeg——手机端固定用 `response_format=pcm`）。
2. 下载同版本 `llama.cpp` Release，把其中的 GGML 运行库（`ggml*.dll`，**包括 `ggml-cpu-<isa>.dll`
   这种 CPU 变体模块**）全部拷到 `cosyvoice-server.exe` 同目录。缺了就没有 CPU 后端：
   用 `cosyvoice-cli --list-backends` 自检。
3. 模型用手机端同一套下载源（ModelScope `Lourdle/Fun-CosyVoice3-0.5B-2512-GGUF`）：
   `CosyVoice3-2512_*.gguf` + `frontend-onnx/speech_tokenizer_v3.int8.onnx` + `frontend-onnx/campplus.int8.onnx`。
4. 启动 API 模式。**推荐直接用仓库里的启动脚本**，它把下面这些坑都包好了：

```powershell
powershell -File tools/.work/cosyvoice-server/run-server.ps1 -Api -Backend cuda0
```

Windows 上也可以直接双击 `tools\run-cosyvoice-server.bat`（默认就是上面这条 API+CUDA）；
它的子命令：`webui`（WebUI 模式注册音色）、`stop`（停服）、`restart`（停掉再起，换线后地址变了就用它）、
`status`（在跑吗？监听在哪个地址？该地址还有效吗）、`diag`（网卡/口令/防火墙/监听一次性报告）。
其它参数原样转给 `run-server.ps1`（例：`... -Api -Port 9000 -Backend cpu`）。

**访问口令（必须）**：口令放在 `tools\tts-token.txt`（已在 `.gitignore`，不会提交），启动时自动读取，
服务端要求 `Authorization: Bearer <口令>`，手机端「访问口令」必须填同一个值。临时关掉用 `-NoToken`
（仅本机调试）。日志只打印口令长度，不打印口令本身。

脚本做的事：自动定位 `bin/`（exe + `ggml*.dll`）、`models/`、`voices/`；缺 GGML 库直接报错；
`-Api` 模式下把 `voices/*.gguf` **每个文件按文件名映射成一个同名音色**；监听地址由 `-Bind auto`
决定——**只有恰好一张"活着"的 USB 共享网卡（Status=Up）时**才绑它，否则（没有 / 多于一张 / 无法确定）
一律绑 `0.0.0.0`，因为口令已经把入口守住了；**起服后自检实际监听地址**并打印
`PHONE -> http://<地址>:8080`（模型加载要 30–90 秒，这行会在加载完之后出现）。等价的手工命令是：

```powershell
cosyvoice-server --model models\CosyVoice3-2512_Q4_K_M.gguf `
  --voice-prompt builtin_1_v2=voices\builtin_1_v2.gguf `
  --served-model-name cosyvoice-3 `
  --host 10.84.116.224 --port 8080 --api --concurrency 2 `
  --max-llm-len 2048 --dit-kv-fixed-slots 2 --dit-kv-cache-length 8192 `
  --inference-buffer-policy dedicated
```

两个要点：

- `--host` 默认是 `127.0.0.1`，必须显式指定。**指定成 USB 共享网卡的具体地址**（而不是
  `0.0.0.0`）时，服务只在那条点对点链路上可达：Wi-Fi/回环/VPN/虚拟网卡全部不通，
  因此**不需要口令**（见第 6 节的安全核对）。要对外网段开放才用 `-Bind any` 并配上 `-Token`。
- 传 `--api-key` 才会要求 `Authorization: Bearer`；不传则无鉴权，App 里口令留空。

## 2. 网络：手机开 USB 网络共享

手机插 USB、下拉通知栏选「USB 网络共享」（不是「仅充电」）。此时**手机是这张网的网关**，
电脑是它的一个 DHCP 客户端，电脑会多出一张 RNDIS 网卡：

```powershell
# 找到 RNDIS 网卡及其地址（实测这台机器是 10.84.116.224/24，各厂商网段不同）
Get-NetAdapter | Where-Object { $_.InterfaceDescription -like '*NDIS*' } |
  Select-Object Name, InterfaceIndex, Status
Get-NetIPAddress -AddressFamily IPv4 | Select-Object InterfaceAlias, IPAddress, PrefixLength
```

Windows 常把这张网卡判成「公用网络」，防火墙会默认拦住 8080 且不一定弹窗，直接加一条不依赖
网络分类的显式规则：

```powershell
New-NetFirewallRule -DisplayName "cosyvoice-tether" -Direction Inbound -Protocol TCP `
  -LocalPort 8080 -Action Allow -Profile Any
```

可选：USB 共享会把 PC 的默认路由抢到手机（PC 上网绕道手机流量）。`192.168.x/10.x` 网段内的
直连访问不受影响，介意的话把该网卡 metric 调高：

```powershell
Set-NetIPInterface -InterfaceIndex <上面的 InterfaceIndex> -InterfaceMetric 9000
```

## 3. 零代码验证（先做这一步）

`cosyvoice-server` 默认还有 WebUI 模式。**先不加 `--api`** 启动，用**手机浏览器**打开
`http://<电脑在共享网段里的IP>:8080/`，在 WebUI 里注册音色并生成一段语音，用手机扬声器听到
声音即说明「PC 模型能跑 + 链路通 + 手机能播」三件事全部成立。这一步不写任何 App 代码就能
验完传输层。

再用 API 模式验证接口面（把 `<IP>` 换成本机地址）：

```powershell
# 站点/接口存活
curl.exe -sS http://<IP>:8080/healthz
# 合成一段 PCM（pcm 为 24kHz 单声道 16-bit LE 原始字节）
curl.exe -sS -o out.pcm -w "%{http_code}`n" -X POST http://<IP>:8080/v1/audio/speech `
  -H "Authorization: Bearer sk-local" -H "Content-Type: application/json" `
  -d '{\"model\":\"cosyvoice-3\",\"voice\":\"v1\",\"input\":\"你好\",\"response_format\":\"pcm\",\"stream\":false}'
```

## 4. 手机侧设置

设置 → 语音朗读 → 朗读引擎切到「远程服务器（电脑）」：

| 字段 | 说明 |
|---|---|
| 服务器地址 | **可留空**。点「查找并测试电脑」即可——USB 网络共享下不需要知道电脑 IP |
| 模型名 | 与 `--served-model-name` 一致（默认 `cosyvoice-3`，脚本已对齐） |
| 音色 | **下拉选择，与端侧同一套音色**（显示端侧音色名，括号里是服务端用的文件名）。默认项「跟随端侧音色」= 用端侧当前音色向服务端要同名音色；服务端没有的会标注「服务端未注册」 |
| 高级选项 | **两端共用**的 8 个合成旋钮（见 4.1），访问口令也在里面（仅远程时显示） |
| 上传本机音色到电脑 | 把端侧每个音色的 gguf 按同名传到服务端并注册，见第 8 节 |

- **查找并测试电脑**：一个动作完成"扫本机网段找到电脑 + 验证连通性"。它先试已配置的地址，
  再按**手机自己接口的网段**（USB 共享是手机建的网，各厂商网段不同；实测本机 `10.84.116.0/24`）
  并发扫 `:8080`，多网段交错扫描，命中即写入地址并回填采样率/音色列表（API 模式读 `/healthz` 的
  `voices`，WebUI 模式读 `/speaker`）。
- **无 `-api-key` 时**：探针走 `/v1/models`、`/healthz`；有口令时自动带上 `Authorization: Bearer`。
- 手机蜂窝口可能是 `10.104.37.169/**8**` 这种大网段，发现逻辑会把短于 /24 的前缀**收敛到所在 /24**，
  不会去扫 6 万个地址。

### 4.1 高级选项（两端共用）

折叠在「高级选项（两端共用）」里，**两个引擎都能看到**：同一个值既写给端侧引擎
（`cosyvoice_set_generation_config` / `cosyvoice_set_chunk_tokens`），又随请求体发给 PC，
所以**调一次两端一起变**。**每个框都带模型元数据里的默认值**，不动就等于现状；改乱了点
「恢复默认」。完整对照见第 9 节。

| 选项 | 默认 | 作用 |
|---|---|---|
| `chunk_tokens` | 28 | 每块多少 speech token：小=省内存、大=吞吐高 |
| `temperature` | 1.0 | 采样温度：越低越稳、越高越有表现力 |
| `top_k` | 25 | 采样候选数 |
| `top_p` | 0.8 | 采样范围（0-1） |
| `tau_r` | 0.1 | 重复抑制：出现复读就调大 |
| `win_size` | 10 | 重复检查窗口，与 `tau_r` 配对 |
| `min_token_text_ratio` | 2.0 | 最少生成量：句子被截断就调大 |
| `max_token_text_ratio` | 20.0 | 最多生成量：拖沓/复读就调小 |
| 访问口令 | 空 | 对应服务端 `--api-key`（仅远程引擎显示）；服务端未设口令就留空 |

`flow`（DiT 扩散步数）**不是** App 开关，是编译期常量：当前**手机 5、PC 10**（唯一保留的差异，
见 9.1）。改它要改各自 checkout 的 `src/cosyvoice-modules.h` 后重编（PC：`cmake --build D:\cosyvoice-server\build-cuda`；
手机：重编 `libcosyvoice.so`）。服务端脚本还提供
`-DitKvFixedSlots / -DitKvCacheLength / -DitKvCacheType / -LlmKvCacheType / -MaxLlmLen / -TextNormalization`
等启动级开关，默认值已按第 9 节钉好。

## 5. 请求映射

App 概念 → `POST /v1/audio/speech`（`cosyvoice-server` 的扩展字段见其 `docs/TOOLS.md`）：

| App | 请求字段 |
|---|---|
| 台词（`extractSpeechText` 去动作/Markdown 后） | `input` |
| 服务器注册音色名 | `voice` |
| 该发言人的 `currentTone` | `instructions`（非空 → instruct 模式；这与端侧语义一致） |
| `AppConfig.ttsSpeed` | `speed`（0.5–2.0，服务端要求 > 0） |
| `--served-model-name` | `model` |
| 高级选项（两端共用，8 项） | `chunk_tokens`、`temperature`、`top_k`、`top_p`、`win_size`、`tau_r`、`min_token_text_ratio`、`max_token_text_ratio`（见 9.2） |
| 固定 | `response_format=pcm`、`stream=false`（非流式：服务端一次性返回整段，和端侧同一入口 `cosyvoice_tts_instruct`） |

群聊逐成员朗读 = 每个成员一次请求，音频连续写入同一个播放轨（成员之间可能有约百毫秒级间隙，
端侧是合成后拼接、无间隙）。

## 6. 性能与内存

手机端的 `cosyvoice` 原生库在构建时打过 3 个性能补丁：`COSYVOICE_INFERENCE_BUFFER_POLICY_DEDICATED`、
`params_v3.dit_kv_fixed_slots = 2`、`diffusion_steps` 10 → 5（并同步重写 `t_span` 噪声调度）。
PC 端同样打前两个补丁，但 **`diffusion_steps` 保持上游的 10**（有意保留的唯一差异，见 9.1）。
`diffusion_steps` 是编译期常量，两边的 checkout 都没有运行时开关。

下表是**旧参数组合**（PC：5 步 / `max_llm_len 2048` / DiT 缓存 8192 / 流式）在无 GPU 争用时的实测：

| 用例 | 首字节 | 总耗时 | 音频时长 | RTF |
|---|---|---|---|---|
| 短句（19 字） | 0.5–1.1s | 1.6–2.2s | 5.5s | **0.29–0.40** |
| 长句（114 字） | 0.5–0.6s | 10.7–11.6s | 32.5–34.1s | **0.33–0.34** |

对照手机端（`docs/NATIVE_MODULES.md` 记录的端侧实测 RTF 4.8–8.2，且那是 3 步的旧数据、5 步更慢）：
**同参数下 PC 快约一个数量级**，且 RTF < 1 意味着边合成边播不会中途断流。

**新参数组合（10 步 / `max_llm_len 4096` / DiT 缓存 8192 / 非流式）的长句耗时待重测**：10 步大约比 5 步
多一倍计算量，且必须**在没有游戏/其他 CUDA 任务占用显卡时**测（见 9.4 的实测反例：游戏占卡时
一句 3.5 秒的话要 326 秒）。短句单点实测：19 字 → 1.24 s 音频 / 1.46 s 用时，RTF ≈ 1.2（无 GPU 争用）。

### 内存从哪来（600MB 的模型为什么能占到 7GB）

权重只有约 600MB，其余都是流式合成用的缓冲与缓存：

| 项 | 量级 | 说明 |
|---|---|---|
| DiT KV cache | 数 GB | `slots × cache_length × ~71.5KiB/位置`；`cache_length` 默认 `max_llm_len × 10` |
| `dedicated` 推理缓冲 | 数 GB | LLM KV / flow / HiFT 各自独立缓冲（上游为"流式复用"选的内存换速度） |
| ONNX 音色前端 + ICU | ~50MB | 只在"从音频提取音色"时真正用到 |

实测：`max_llm_len 4096` + 默认缓存长度时服务私有内存 **7.3–8.3GB**、显存 **6.3–6.9GB**；
把 `--max-llm-len 2048`（对齐手机）与 `--dit-kv-cache-length 8192` 配上后降到
**3.9–4.9GB / 2.9–3.5GB**，**且速度不变甚至略快**（缓存短了，显存带宽搬运更少）。`tools/.work/cosyvoice-server/run-server.ps1`
已把这套作为默认值。

两个必须知道的坑：

- **`--dit-kv-fixed-slots` 不是越大越好**：每个 slot 的缓存长度默认 `max_llm_len × 10`，
  在 8GB 卡上给 10 个 slot 会尝试分配 **28.6GB** 直接失败（`cudaMalloc failed`）。
  手机端选 2 是恰好合适的值。
- **单条朗读过长会超出缓存长度**：超出的位置会被丢弃，推理继续但质量可能下降。
  默认 8192 个位置约覆盖 800+ LLM token 的回复；需要更长回复时上调 `-DitKvCacheLength`（内存随之上升）。

### 暴露面核对（只会有一个套接字）

绑到共享网卡的具体地址后，可以这样自查（这是实测过的预期输出）：

```powershell
# 1) 只有一条监听记录，且 LocalAddress 是共享网卡地址（不是 0.0.0.0，也没有 IPv6）
$pids = @(Get-Process cosyvoice-server).Id
Get-NetTCPConnection -State Listen | Where-Object { $pids -contains $_.OwningProcess } |
  Select-Object LocalAddress, LocalPort

# 2) 只有共享网卡地址通，其余网卡（Wi-Fi / VPN / 回环 / 虚拟网卡）都不通
foreach ($ip in '10.84.116.224','10.127.8.138','127.0.0.1','192.168.75.1') {
  curl.exe -sS --max-time 4 -o NUL -w "$ip -> %{http_code}`n" "http://$ip`:8080/healthz"
}
```

实测：`10.84.116.224:8080` → HTTP 200；Wi-Fi 与 VPN 地址 → 超时；回环与 VMware 虚拟网卡 → 拒绝连接。
防火墙里只应有一条由本方案添加的规则 `Inbound / Allow / TCP 8080`（首次运行 `-OpenFirewall` 时创建；
若发现 `Action=Block` 见第 7 节的说明）。除 8080 外的监听端口都属系统或其他软件，与本方案无关。

因为"不暴露"依赖的是**绑定地址**这一层，所以：只要用 `-Bind any`（或直接给服务传 `--host 0.0.0.0`）
起服务，就会真的对 Wi-Fi 开放——那种情况下请同时设置 `-Token`。

### 运行时不要留长驻

`cosyvoice-server` 常驻约 4–5GB 内存（外加 3GB 显存），不用时显式停掉：

```powershell
powershell -File tools/.work/cosyvoice-server/run-server.ps1 -Stop
```

## 7. 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| 测试连接报「无法连接」 | PC 服务没起、绑在 `127.0.0.1`（改 `--host 0.0.0.0`）、或防火墙没放行（见第 2 节的规则）。先跑 `tools\run-cosyvoice-server.bat diag` 一次性看网卡/绑定/防火墙 |
| 换线、重插或换 USB 口后连不上（同一根线之前可以） | 服务绑的地址过期了：Windows 会给**断开**的适配器保留 IP，旧实例可能一直绑在死地址上。`run-cosyvoice-server.bat status` 里该地址会标 `STALE` → 用 `restart` 重起（或 `-Bind any`）；手机侧地址变了也可以点 App 的「自动发现」 |
| 手机浏览器打不开 WebUI | 同上；另外确认手机是「USB 网络共享」而不是「USB 文件传输」 |
| 「服务器要求访问口令（HTTP 401/403）」 | `tools\tts-token.txt` 里的值、App 的「访问口令」必须完全一致（一边填一边没填也算）；改完口令要重启服务 |
| `{"code":"model_not_found"}` | App 里「模型名」必须等于服务端的对外模型名（`--served-model-name`，缺省时是架构名如 `cosyvoice3-2512`）。`--api` 模式下 `run-server.ps1` 固定传 `cosyvoice-3`，与 App 默认值一致 |
| 「端点不存在」/「服务器已响应但端点不匹配」 | 该端口不是 `cosyvoice-server`，或路径写错（地址只写到 `http://IP:8080`，不要带 `/v1`） |
| 朗读没有声音但没报错 | 音色名与服务器注册名不一致；服务端日志会显示未找到 speaker |
| 首音很久才响 | 服务端用 CPU；改用 GPU 后端（`-Backend cuda0`），或确认已启用 DiT KV cache（`-DitKvFixedSlots 2`） |
| 朗读失败：socket closed 之类 | 链路被拔线/共享关闭；切回端侧引擎即可（远程失败**不会**自动降级） |
| PC 一锁屏/休眠就断 | 把 `cosyvoice-server` 注册成服务或关掉休眠 |
| 服务占用好几 GB 内存 | 见第 6 节的内存构成；不用时用 `-Stop` 停掉，或调小 `-DitKvCacheLength` |

**Windows 防火墙的隐形陷阱**：程序首次监听端口时系统会弹「安全警报」，**如果你忽略或点了取消，Windows 会记住并创建一条 `Inbound / Block` 规则**（作用域为"公用网络"），以后不再弹窗，手机就一直连不上。确认与修复（需要管理员 PowerShell）：

```powershell
Get-NetFirewallRule -DisplayName 'cosyvoice-server' | ForEach-Object {
  $r = $_; $pf = $r | Get-NetFirewallPortFilter
  [pscustomobject]@{ Action=$r.Action; Direction=$r.Direction; Profile=$r.Profile; LocalPort=$pf.LocalPort }
}
# 若看到 Action=Block：删掉阻止规则，换成显式放行
Get-NetFirewallRule -DisplayName 'cosyvoice-server' | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName 'cosyvoice-server' -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Any
```

## 8. 音色互通

`prompt_speech.gguf` 是端侧与 PC 端同一格式，两端因此可以共用**同一个音色文件**：

- **手机 → PC（一键）**：设置 → 语音朗读 → 远程 → 「**上传本机音色到电脑**」。App 把每个端侧音色
  的 gguf 按**同名**（`voices/<文件名去扩展名>.gguf`）POST 到 `POST /v1/voices`，服务端落盘并立即注册；
  端侧导入时已经做过裁剪/编码，所以两端是**逐字节同一个音色**，不需要在 PC 上重做一遍。
- PC → 手机：WebUI 的「Save speaker」导出 `.gguf`，放进手机 `voices/` 目录后重启 App 即出现在音色列表。
- 手工/批量（等价做法）：把手机 `filesDir/cosyvoice/voices/*.gguf` 拷到 PC，用
  `--voice-prompt <名字>=<文件>` 注册；`run-server.ps1` 已经会自动把 `voices/*.gguf` 全部注册。

上传接口（`cosyvoice-server`，API 与 WebUI 模式都有）：

```powershell
# 原始 gguf 作为请求体；名字走查询参数，服务端存成 <--voice-dir>/<name>.gguf
curl.exe -sS -X POST "http://10.84.116.224:8080/v1/voices?name=builtin_1_v2" `
  --data-binary "@voices\builtin_1_v2.gguf" -H "Content-Type: application/octet-stream"
# 也支持 multipart：-F "name=..." -F "file=@...gguf"
```

- 同名已注册时返回 200 + `already_present`，所以"同步"可以反复点。
- 名字只允许 `A-Za-z0-9._-`（它会被当作文件名），大小上限 16 MiB（实际音色约 40 KB）。
- 落盘目录由 `--voice-dir` 指定（`run-server.ps1` 传 `voices/`），因此**重启后依然在**：
  脚本启动时会把 `voices/*.gguf` 全部映射成 `--voice-prompt`。
- 这个接口是本仓库对上游 checkout 的**本地补丁**（`D:\cosyvoice-server\src\tools\server\`：
  `POST /v1/voices` + `--voice-dir`）；旧版服务端会返回 404，App 会提示"电脑端不支持音色上传"。

### 8.1 铁律：参考文本必须是参考音频的转写

零样本克隆靠「参考音频 ↔ 参考文本」对齐。**文本和音频对不上时，模型会退化成重复循环或幻听**
（实测：把 13.75 秒的四句商务叙述配上"大家好，很高兴在这里和你聊天。"，输出会是
`谢谢观看,谢谢观看,…` 重复几百次，或 `嘎嘎嘎嘎…`）。所以两端都用同一条流程：

1. **按手机的规则裁参考音频**（`:engine:cosyvoice` 的 `VoiceSegment`/`SegmentPicker`）：
   解码 → 单声道 → 线性重采样到 **16 kHz** → 取**最响的 3.0 秒**滑窗 → 按能量阈值剪掉静音 →
   不足 2 秒报错 → 峰值归一化到 **0.95**。
2. **参考文本 = 这段裁剪后音频的转写**（不是"你希望它说什么"）。
3. 用同名注册到服务端（名字 = 端侧 `voices/<文件名>.gguf` 去掉扩展名）。

PC 侧的现成工具（都在 `tools/.work/cosyvoice-server/`，不进 git）：

```powershell
# 1) 生成内置音色的参考片段（复刻手机规则，输出 refs/pc_builtin_*.wav）
python tools/.work/cosyvoice-server/prep_voice_assets.py

# 2) 在 WebUI 模式下注册 + 落盘成 voices/<名字>.gguf（文本走 HTTP body，UTF-8 无歧义）
powershell -File tools/.work/cosyvoice-server/run-server.ps1        # 不带 -Api
python tools/.work/cosyvoice-server/build_voices.py
```

`build_voices.py` 里的 `VOICES` 就是「名字 → (参考片段, 参考文本)」对照表；换自己的音色时
把片段换成第 1 步产出的 wav、文本换成它的转写即可（**长度规则与端侧一致：3 秒目标、2 秒下限**）。

**内置的两个音色已经修好并对齐**（`builtin_1_v2` / `builtin_2_v2`，与 App 同名）：

| 参考音频 | 参考文本（音频真实内容） |
|---|---|
| `assets/voices/sample_1.wav`（3.48 s，取最响 3 秒后 2.68 s） | 能够做的比我还好哟 |
| `assets/voices/sample_2.wav`（3.11 s，同一说话人的第一句完整句） | 在那之后完全收购那家公司 |

App 侧的内置音色带一个**来源标记**（`VoiceInfo.source` = 资源内容 + 参考文本的摘要）：只要资源或文本
变了，下次启动会自动重建，**文件名不变**，所以服务端的音色名始终与端侧一致。

### 8.2 自定义音色

用户在手机上导入的音色文件名是 `voice-<hash>.gguf`（导入时按手机规则裁剪 + ONNX 前端编码）。
上传到电脑：

1. **（推荐）直接点「上传本机音色到电脑」**：App 会用这个 hash 文件名作为服务端音色名注册，
   两端名字一致，音色下拉里就会显示为已注册（不再标「服务端未注册」）。
2. 手工在 PC 上重建一份：按 8.1 的规则裁好音频、取转写文本，注册成**手机上的那个文件名**
   （App 的音色下拉里会显示 `名字（文件名）`，照着填即可）。
3. 或者两端各用各的音色，在 App 里从下拉里选服务端已有的那个（会标注「仅服务端」）。

## 9. 两端参数对照

除"硬件环境"（后端 CPU/CUDA、线程数、并发槽）与**有意保留的一处差异**（flow 步数）外，
两端现在完全一致，包括模型/前端文件本身（同一份 ModelScope
`CosyVoice3-2512_Q4_K_M.gguf` 629,141,792 B 与两个 int8 ONNX）。

### 9.1 写死项（两端一致，不可在 App 里改）

| 参数 | 手机端 | PC 端 | 怎么钉住的 |
|---|---|---|---|
| `n_max_seq` | 4096 | 4096 | App 传 **v3 上下文参数**（`params_version=2`）；脚本 `-MaxLlmLen 4096` |
| `dit_kv_cache_length` | 8192 | 8192 | App 显式写入 v3 字段；脚本 `-DitKvCacheLength 8192`（不写就会按 `n_max_seq×10=40960`） |
| DiT KV 槽 | 2 固定 / 0 可卸载 | 同 | v3 字段 / `-DitKvFixedSlots 2` |
| DiT KV 类型 | `k=q8_0,v=q4_0,fallback=q8_0` | 同 | v3 字段（打包值 `0x800008C2`）/ 服务端默认 |
| LLM KV 类型 | `q8_0`（K/V 同精度） | `q8_0` | 库默认 / `-LlmKvCacheType q8_0` |
| 缓冲策略 | `dedicated` | `dedicated` | 库默认 / `-BufferPolicy dedicated` |
| `n_batch` / flash-attn（LLM、Flow） | 256 / 开 / 开 | 同 | 库默认 |
| `seed` / RNG 策略 | 随机 / `RESET_PER_SESSION` | 同 | 库默认（PC 仍可按请求指定 `seed`） |
| **文本规范化** | 关（`.so` 编译时 `COSYVOICE_NO_ICU=ON`） | 关 | 脚本 `--disable-text-normalization`（默认加，`-TextNormalization` 可开回） |
| 流式 | 否（一次性 `cosyvoice_tts_instruct`） | 否 | App 请求体固定 `stream:false`（与端侧同一入口） |
| `n_workers` / `n_threads` / 后端 | 1 / 性能核数 / CPU | 2 / 硬件并发÷2 / CUDA0 | 硬件与并发，非算法差异 |
| **`diffusion_steps`（flow 步数）** | **5** | **10** | **唯一有意保留的差异**（编译期常量：手机 `.so` 改 `src/cosyvoice-modules.h` 后重编，PC 同上） |

**注意（实测）**：DiT KV 缓存**只在流式下参与计算**——非流式（当前两端都是）`dit_sched_config` 把每步 `cache_kv` 置 false，缓存只分配、不读写，所以上表的 `dit_kv_cache_length` / DiT 槽数/类型对**速度和音质没有影响**；在 CPU 后端它甚至不落页（实测 App 加载后 `Heap Alloc` 2.31 GiB、`Private Dirty` 仅 765 MB），只有 CUDA 后端会实占 VRAM（2 槽 × 8192 ≈ 1.12 GB）。保留这些值是为了万一改走流式时两端仍然一致。

### 9.2 高级选项（App 设置页，两端同时生效）

同一个值既通过 `cosyvoice_set_generation_config`/`set_chunk_tokens` 写给端侧引擎，
又随请求体发给 PC，所以**调一次两端一起变**。默认值 = 模型元数据值，不动就等于现状；
「恢复默认」还原下表。存储里 `0`/`0.0` 一律按"未设置"处理并回落到默认值（旧版本存过 0，
而服务端会拒绝 `temperature: 0`）。

| 选项 | 默认 | 影响 | 调它的场景 |
|---|---|---|---|
| `chunk_tokens` | 28 | 速度 + 峰值内存 | 分块小=省内存、分块大=吞吐高 |
| `temperature` | 1.0 | 音质（稳定性/表现力） | 越高越有表现力，过高会跑偏/复读 |
| `top_k` | 25 | 音质（采样候选数） | 调小更稳 |
| `top_p` | 0.8 | 音质（采样范围） | 与 `top_k` 配合 |
| `tau_r` | 0.1 | 音质（**重复抑制**） | 出现"复读"就调大 |
| `win_size` | 10 | 音质（重复检查窗口，与 `tau_r` 配对） | 与 `tau_r` 一起调 |
| `min_token_text_ratio` | 2.0 | 音质（**漏字**） | 句子被截断就调大 |
| `max_token_text_ratio` | 20.0 | 音质（**拖长**） | 拖沓/复读就调小 |

### 9.3 不能做成 App 开关的

`diffusion_steps`（编译期常量，改它要重编两端）、DiT/LLM KV 缓存与 `n_max_seq`
（加载期，改了要重载模型/重启服务）、后端与线程数（启动参数）。

### 9.4 性能提醒：别和游戏抢 GPU

PC 端合成吃满 CUDA。实测（RTX 4060 Laptop 8GB）：**开着《天国拯救2》时**（游戏占
7651/8188 MiB 显存、GPU 100%），一句 3.5 秒的话要 **326 秒**，第二次请求 900 秒超时；
没有游戏占用时同一句话在十几秒量级。用远程朗读前请先退出游戏（或把 `-MaxLlmLen`/步数
调低，并保留流式，慢速下至少能边出边听）。


