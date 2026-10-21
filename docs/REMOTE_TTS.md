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
它的子命令：`bt`（绑蓝牙 PAN）、`hotspot`（绑手机热点，首次会记住热点 SSID）、`local`（只绑 127.0.0.1）、
`webui`（WebUI 模式注册音色）、`stop`（停服）、`restart`（停掉再起，换线后地址变了就用它）、
`status`（在跑吗？监听在哪个地址？该地址还有效吗）、`diag`（网卡/口令/防火墙/监听一次性报告）。
其它参数原样转给 `run-server.ps1`（例：`... -Api -Port 9000 -Backend cpu`）。

**访问口令（必须）**：口令放在 `tools\tts-token.txt`（已在 `.gitignore`，不会提交），启动时自动读取，
服务端要求 `Authorization: Bearer <口令>`，手机端「访问口令」必须填同一个值。临时关掉用 `-NoToken`
（仅本机调试）。日志只打印口令长度，不打印口令本身。

脚本做的事：自动定位 `bin/`（exe + `ggml*.dll`）、`models/`、`voices/`；缺 GGML 库直接报错；
`-Api` 模式下把 `voices/*.gguf` **每个文件按文件名映射成一个同名音色**；**起服后自检实际监听地址**
并打印 `PHONE -> http://<地址>:8080`（模型加载要 30–90 秒，这行会在加载完之后出现）。

**日志落盘**：启动器把服务端自己的输出捕获到脚本同目录的 `server.log`（stdout：启动横幅 + `[REQ]`
请求日志，含每次请求的 `elapsed_ms=` / `bytes=`）和 `server.log.err`（stderr：错误）。每次启动会先把
上一份挪成 `*.prev`，所以上一次的日志也留着。控制台不再是唯一去处——从快捷方式起的启动器往往没有
可回看的控制台，出问题要查的就是这个文件；`-LogFile ''` 可退回只在控制台输出。

**绑哪个地址（`-Bind auto`，默认）**：只绑**手机链路**的那一个具体地址，按「记忆的链路 → USB 共享 →
蓝牙 PAN → SSID 匹配到的手机热点」的顺序挑；挑不出来就**一直等**（`-WaitLinkSeconds`，默认 `-1`；
每 10 秒报一次活地址，按 Ctrl+C 放弃；设 `0` 则立即放弃）。**绝不静默绑 `0.0.0.0`**——
绑具体地址时别的网络连端口开着都看不出来，详见第 6 节。链路记忆写在 `tools\tts-link.txt`
（同样 gitignored、不含密钥），首次成功启动后自动写入（`preferred=usb|bt|hotspot`，热点另存
`hotspot-ssid`），所以**先启动、后插线/开共享**也行：脚本会等到链路出现再绑它的当前地址。

**运行中链路断了**（拔线、换口、关共享、IP 变了）：看门狗发现绑的地址失效后，先给
**`-GraceSeconds`（默认 20 秒）宽限期**——地址在宽限期内回到**同一个地址**且服务仍在监听，就**继续用原进程**
（0 秒重载，瞬断无感）；否则才停服、等链路回来、用新地址重启（模型重载约 90 秒）。不想自动重绑用
`-NoWatchdog`，不想等宽限期用 `-GraceSeconds 0`。等价的手工命令是：

```powershell
cosyvoice-server --model models\CosyVoice3-2512_Q4_K_M.gguf `
  --voice-prompt builtin_1_v2=voices\builtin_1_v2.gguf `
  --served-model-name cosyvoice-3 `
  --host 10.84.116.224 --port 8080 --api --concurrency 2 `
  --max-llm-len 2048 --dit-kv-fixed-slots 2 --dit-kv-cache-length 8192 `
  --inference-buffer-policy dedicated
```

两个要点：

- `--host` 默认是 `127.0.0.1`，必须显式指定。**脚本只绑手机链路的那个具体地址，永不默认绑
  `0.0.0.0`**：绑具体地址时，服务只在那条链路上可达，同一台电脑的 Wi-Fi/回环/VPN/虚拟网卡一律不通，
  **别的网络里的机器扫 8080 只会得到「连不上」，连端口开着都看不出来**（见第 6 节的核对）。
  只有显式 `-Bind any` 才会绑 `0.0.0.0`（那时口令是唯一防线）；`-Bind local` 只绑 `127.0.0.1`。
- **口令默认必填**：脚本从 `tools\tts-token.txt` 读口令并总是传 `--api-key`，服务端要求
  `Authorization: Bearer <口令>`，App 的「访问口令」填同一个值；只有 `-NoToken` 才无鉴权
  （仅本机调试）。

## 2. 网络：三种链路（USB 共享 / 蓝牙网络共享 / 手机热点）

三条链路都能用，`-Bind auto` 会自己认（认不出来就等，等不到就拒绝启动，见第 1 节）。三种都是
**手机建网、电脑当客户端**，所以手机天然知道该扫哪个网段，App 的「查找并测试电脑」不需要你填 IP。

| 链路 | 速度 | 开启难度 | 适合 |
|---|---|---|---|
| **USB 网络共享** | 最快（等效数十~数百 Mbps） | 最低：插线 + 手机点一下 | 日常首选 |
| **手机热点** | 20–100 Mbps | 低：PC 连一次 SSID 即可（首次要告诉脚本热点名） | 不想插线 |
| **蓝牙网络共享** | 名义 **3 Mbps**（PC 侧网卡 LinkSpeed 实测 3 Mbps） | 最高：手机开共享 + PC 端手动「接入点」 | 不想插线又不想开热点，或 USB 口/线有问题时 |

### 2.1 USB 网络共享

手机插 USB、下拉通知栏选「USB 网络共享」（不是「仅充电」）。**手机是这张网的网关**，电脑多出一张
RNDIS 网卡：

```powershell
# 找到 RNDIS 网卡及其地址（实测这台机器是 10.84.116.224/24，各厂商网段不同）
Get-NetAdapter | Where-Object { $_.InterfaceDescription -like '*NDIS*' } |
  Select-Object Name, InterfaceIndex, Status
Get-NetIPAddress -AddressFamily IPv4 | Select-Object InterfaceAlias, IPAddress, PrefixLength
```

### 2.2 蓝牙网络共享（蓝牙 PAN）

**关键坑：蓝牙「连着」≠ PAN 通了。** 配对后连上的是 A2DP/HFP 那类服务，网络共享要的是 **PAN 配置文件**，
必须由 PC 主动发起一次：

1. 手机：打开「蓝牙网络共享」（需要手机与 PC 已配对）；
2. PC：运行 `control printers`（设备和打印机）→ **右键那台手机 → 连接方式 → 接入点**；
   Windows 11 的**设置 App 里没有这个入口**，只能走控制面板；
3. 成功后 `蓝牙网络连接` 网卡变为 `Up`，拿到手机分配的地址（实测本机 `192.168.44.175/24`，网关
   `192.168.44.1` 就是手机）。

连过一次之后 Windows 一般会记住并自动重连。也可以直接用 `tools\run-cosyvoice-server.bat bt` 启动。

### 2.3 手机热点

手机开热点 → PC 连到该 SSID。PC 拿到 `192.168.43.x` 之类（各厂商不同），网关就是手机。

热点**必须显式告诉脚本一次**，因为「校园网/家里 Wi-Fi」和「手机热点」在系统里长得一模一样（都是
「带默认网关的 Wi-Fi 网卡」），只有 SSID 能区分：

```powershell
tools\run-cosyvoice-server.bat hotspot    # 首次：绑定热点并记住它的 SSID
tools\run-cosyvoice-server.bat            # 之后 auto 就会自动认它
```

也可以手工把 `hotspot-ssid=<热点名>` 写进 `tools\tts-link.txt`。

### 2.4 防火墙与路由

Windows 常把这些网卡判成「公用网络」，防火墙会默认拦住 8080 且不一定弹窗，加一条不依赖网络分类的
显式规则（`-OpenFirewall` 会自动做，需管理员）：

```powershell
New-NetFirewallRule -DisplayName "cosyvoice-server" -Direction Inbound -Protocol TCP `
  -LocalPort 8080 -Action Allow -Profile Any
```

可选：这些链路会把 PC 的默认路由抢到手机（PC 上网绕道手机流量）。直连访问不受影响，介意的话把该
网卡 metric 调高：

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

- **查找并测试电脑**：一个动作完成"扫本机网段找到电脑 + 验证连通性"。它先试已配置的地址，再按
  **手机自己所有接口的网段**并发扫 `:8080`——USB 共享（`rndis0`）、蓝牙 PAN（`bnep0`）、热点 AP
  （`wlan0`/`ap0`）都在内，所以三条链路都不需要填 IP；多网段**交错**扫描（一个慢网段不会拖住命中的
  那个），扫描预算随在线网卡数放宽（每个网段一个 /24，总数上限 1024）。蓝牙链路往返慢，App 对
  `bnep*` 网段用更宽的超时档（1.5s / 2.5s；普通链路仍是 0.4s / 0.9s）。命中即写入地址并回填采样率/
  音色列表（API 模式读 `/healthz` 的 `voices`，WebUI 模式读 `/speaker`）。
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
| `max_token_text_ratio` | 20.0 | 长度上限（**硬截断**：调小会让句子念不完；想更快请用 `speed`，见 9.2） |
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

### 暴露面核对（只有一个套接字，且只绑手机链路的地址）

`-Bind auto` 绑的是手机链路的**具体地址**，不是 `0.0.0.0`，所以自查应该是：

```powershell
# 1) 只有一条监听记录，LocalAddress 是手机链路地址（不是 0.0.0.0，也没有 IPv6）
$pids = @(Get-Process cosyvoice-server).Id
Get-NetTCPConnection -State Listen | Where-Object { $pids -contains $_.OwningProcess } |
  Select-Object LocalAddress, LocalPort

# 2) 只有手机链路地址通；同一台电脑的 Wi-Fi / 回环 / VPN / 虚拟网卡一律不通
foreach ($ip in '<手机链路地址>','<本机 Wi-Fi 地址>','127.0.0.1','192.168.75.1') {
  curl.exe -sS --max-time 4 -o NUL -w "$ip -> %{http_code}`n" "http://$ip`:8080/healthz"
}
```

实测（走蓝牙 PAN，绑 `192.168.44.175`）：蓝牙地址 → 200（带口令）/ 401（不带口令）；校园网 Wi-Fi
地址 `10.127.8.138` → **连不上**；`127.0.0.1` → 连不上（绑的是具体地址，本机自测请用 `-Bind local`）。
**别的网络里的机器扫 8080 只会得到「连不上」，连端口是否开着都看不出来**——这就是"绑具体地址"而不是
"绑 `0.0.0.0` 再靠防火墙"的关键区别。

防火墙里只应有一条由本方案添加的规则 `Inbound / Allow / TCP 8080`（首次运行 `-OpenFirewall` 时创建；
若发现 `Action=Block` 见第 7 节的说明）。除 8080 外的监听端口都属系统或其他软件，与本方案无关。

只有显式 `-Bind any`（或直接给服务传 `--host 0.0.0.0`）才会真的对 Wi-Fi 开放——那种情况下口令是唯一
防线，务必确认 `tools\tts-token.txt` 里是强口令。

### 链路带宽与首音延迟

音频流固定是 24 kHz / 16-bit 单声道 = **384 kbps**（约 48 KB/s），而 App 是**整段收完再播**
（`stream=false`），所以首音延迟 ≈ 合成时间 + 传输时间：

| 链路 | PC 侧速率 | 一句 5 秒音频（约 240 KB）的传输 | 体验 |
|---|---|---|---|
| USB 共享 | 数十~数百 Mbps | 忽略不计 | 与端侧几乎无差别 |
| 手机热点 | 20–100 Mbps | < 0.2 秒 | 很好 |
| 蓝牙 PAN | 名义 **3 Mbps**（网卡 LinkSpeed 实测 3 Mbps） | 数秒 | **实测朗读速度没问题**，可作为常规链路（比 USB/热点慢，但不是瓶颈） |

实测一次（蓝牙 PAN，App 朗读）：2.16 秒音频（101 KB）——PC 合成 1.43 秒，蓝牙传输约 1 秒量级，整体 RTF ≈ 1.3，按下到出声没有明显等待。

### 运行时不要留长驻

`cosyvoice-server` 常驻约 4–5GB 内存（外加 3GB 显存），不用时显式停掉：

```powershell
powershell -File tools/.work/cosyvoice-server/run-server.ps1 -Stop
```

## 7. 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| 测试连接报「无法连接」 | 先跑 `tools\run-cosyvoice-server.bat diag`：看有没有活链路、`auto` 会绑哪个地址、防火墙是不是 Block。服务绑在 `127.0.0.1` 时手机也连不上（那是 `-Bind local`，只给本机自测） |
| 换线、重插或换 USB 口后连不上（同一根线之前可以） | 服务绑的地址过期了：Windows 会给**断开**的适配器保留 IP，旧实例可能一直绑在死地址上。`run-cosyvoice-server.bat status` 里该地址会标 `STALE` → `restart` 重起（运行中拔线时看门狗会自动重绑）；手机侧也可以点「自动发现」 |
| 手机浏览器打不开 WebUI | 同上；另外确认手机开的是「USB 网络共享」而不是「USB 文件传输」 |
| 蓝牙连着，但「蓝牙网络连接」还是 Disconnected / 手机连不上 | 蓝牙**经典链路**（A2DP/HFP）连着 ≠ PAN 通了。手机开「蓝牙网络共享」，PC 端 `control printers` → 右键手机 → 连接方式 → 接入点（Win11 设置 App 没有这个入口）；成功后该网卡变 Up 并拿到 `192.168.44.x` |
| 蓝牙下「自动发现」扫不到电脑 | 蓝牙往返慢（名义 3 Mbps），App 对 `bnep*` 网段已用更宽的超时档；仍扫不到就手填 `diag` 打印的地址 |
| 热点下连不上 | 确认 PC 连的是**手机热点的 SSID**（不是校园网）。热点必须显式认一次：`run-cosyvoice-server.bat hotspot`（首次，会记住 SSID），或把 `hotspot-ssid=<热点名>` 写进 `tools\tts-link.txt` |
| 启动报「Missing ...cosyvoice-server.exe」或「并行配置不正确」 | 杀软（360 等）会把无签名的监听型 exe 隔离掉：`diag` 会直接提示 exe 缺失。到杀软隔离区恢复，并把 `tools\.work\cosyvoice-server\bin` 加入信任区，否则下次还会被吃 |
| 先启动、后插线 | 脚本会**一直等**链路出现（`-WaitLinkSeconds`，默认 `-1` = 一直等，按 Ctrl+C 放弃；设 `0` 则立即放弃），出现后自动绑它的当前地址；运行中拔线/换口由看门狗自动重绑（**同一地址 20 秒内回来就不重载**，只有地址真的变了才重载约 90 秒；`-NoWatchdog` 可关、`-GraceSeconds 0` 关宽限） |
| 「服务器要求访问口令（HTTP 401/403）」 | `tools\tts-token.txt` 里的值、App 的「访问口令」必须完全一致（一边填一边没填也算）；改完口令要重启服务 |
| `{"code":"model_not_found"}` | App 里「模型名」必须等于服务端的对外模型名（`--served-model-name`，缺省时是架构名如 `cosyvoice3-2512`）。`--api` 模式下 `run-server.ps1` 固定传 `cosyvoice-3`，与 App 默认值一致 |
| 「端点不存在」/「服务器已响应但端点不匹配」 | 该端口不是 `cosyvoice-server`，或路径写错（地址只写到 `http://IP:8080`，不要带 `/v1`） |
| 朗读没有声音但没报错 | 音色名与服务器注册名不一致；服务端日志会显示未找到 speaker |
| 首音很久才响 | 服务端用 CPU；改用 GPU 后端（`-Backend cuda0`），或确认已启用 DiT KV cache（`-DitKvFixedSlots 2`） |
| 朗读失败：socket closed 之类 | 链路被拔线/共享关闭；切回端侧引擎即可（远程失败**不会**自动降级） |
| **同一句话越念越长**（第二遍比第一遍久、第三遍更久），最后服务端进程崩掉 | 服务端的跨请求状态累积（第 10.1 节）。判据：`server.log` 里同一段文本的 `bytes=` 一次比一次大；换个音色立刻恢复正常。本地 checkout 已修好，见 10.1 的验证与回滚 |
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

- **双向（一键）**：设置 → 语音朗读 → 「**同步音色和名称**」。App 先拉取 `GET /v1/voices` 的
  稳定 id / 显示名 / 大小 / FNV 指纹，再按 id 对齐：手机有电脑没有 → 上传；电脑有手机没有 → 下载；
  同名但不同文件 → 手机保留原名，另一端依次加 `_1`、`_2`；同一 id 内容不同 → 只报告，不覆盖。
  显示名不一致时以**端侧（手机）名字优先**，用 `PUT /v1/voices/<id>` 修改电脑端显示名。改名只改显示名，
  朗读请求仍用稳定 speaker id，不重新编码音频。
- **单向上传**：仍保留「只上传本机音色到电脑」；旧版服务端没有清单接口时，同步按钮会自动降级为这种方式，
  并明确提示“电脑端服务端版本过旧，只能上传，不能同步名称/下载”。
- **服务端持久化**：显示名保存在 `--voice-dir/names.json`，重启后仍保留；音色文件仍是
  `<--voice-dir>/<id>.gguf`。
- PC → 手机的手工方式仍可用：WebUI 的「Save speaker」导出 `.gguf`，放进手机 `voices/` 目录后重启 App
  即出现在音色列表。

音色接口（`cosyvoice-server`，API 与 WebUI 模式都有）：

```powershell
# 清单：id、显示名、大小、FNV 指纹
curl.exe -sS "http://10.84.116.224:8080/v1/voices"
# 下载某个音色
curl.exe -sS -o builtin_1_v2.gguf "http://10.84.116.224:8080/v1/voices/builtin_1_v2"
# 原始 gguf 作为请求体；name 是稳定 id，display_name 是用户显示名
curl.exe -sS -X POST "http://10.84.116.224:8080/v1/voices?name=builtin_1_v2&display_name=%E5%86%85%E7%BD%AE%E9%9F%B3%E8%89%B2%201" `
  --data-binary "@voices\builtin_1_v2.gguf" -H "Content-Type: application/octet-stream"
# 只改显示名（JSON body，UTF-8）
curl.exe -sS -X PUT "http://10.84.116.224:8080/v1/voices/builtin_1_v2" `
  -H "Content-Type: application/json" --data-binary "{\"displayName\":\"内置音色 1\"}"
# 也支持 multipart：-F "name=..." -F "display_name=..." -F "file=@...gguf"
```

- 同名已注册时返回 200 + `already_present`；如果同时带了 `display_name`，会更新显示名，所以"同步"可以反复点。
- 稳定 id 只允许 `A-Za-z0-9._-`（它会被当作文件名），大小上限 16 MiB（实际音色约 40 KB）；显示名可以是中文/空格，保存在 `names.json`，不参与文件名。
- 落盘目录由 `--voice-dir` 指定（`run-server.ps1` 传 `voices/`），因此**重启后依然在**：
  脚本启动时会把 `voices/*.gguf` 全部映射成 `--voice-prompt`。
- 这套接口是本仓库对上游 checkout 的**本地补丁**（`D:\cosyvoice-server\src\tools\server\`：
  清单/下载/改名 + `POST /v1/voices` + `--voice-dir`）；改完需重新 `cmake --build`，并把
  `build\bin\cosyvoice-server.exe`/`cosyvoice.dll` 复制到 `tools/.work/cosyvoice-server/bin`。
  旧版服务端会返回 404，App 会降级为“只上传”并明确提示。

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
| `max_token_text_ratio` | 20.0 | 长度上限（**硬截断**，不是压缩） | 只在模型复读循环时调小；想让朗读更快请用 `speed`（见下） |

**`max_token_text_ratio` 实测（PC 端，91 字同一段原文，`min_token_text_ratio` 固定 2）**：它是**硬截断**，
不是"把话压缩得更快"——上限一旦低于模型自己想跑的长度，句子就**念不完**（不只是变短）：

| `max_token_text_ratio` | 产出音频 | 相对默认长度 | 听感 |
|---|---|---|---|
| **20（默认）** | 24–29 s | 100% | 句子完整 |
| 8 | 28.2 s | 100% | 上限还没生效（模型自己就停在这里） |
| 5 | 17.7 s | **62%** | **念不完（实测）** |
| 3 | 10.6 s | 37% | 明显截断 |

模型**会自己停**，默认值下产出约 7 倍文本 token 是**正常长度**——因为这条台词的 `instructions`
就是"**语速慢**"。同一段 91 字实测：指令"语速慢"= 28.4 s（3.2 字/秒）、不加指令 = 19.3 s
（4.7 字/秒）、指令"语速快" = 17.7 s（5.1 字/秒）。**所以别用这个上限去"压短"**：想快就改台词里的
语速指令，或调 App 的朗读速度 `speed`（同一段：1.0 → 28.4 s、1.25 → 22.7 s、1.5 → 18.9 s，**不丢字**）。
`max_token_text_ratio` 只在模型**复读循环**（同一句反复念）时才有用，那种情况才调小、并接受截断风险。

（顺带一条调试经验：请求里的 `seed` 只能让**长度**复现，**波形**复现不了——同一 seed 连发三次都是
28.36 s，音频却各不相同（flow 的扩散噪声没吃请求 seed）。所以这类 A/B 只能比长度 + 用耳朵听，
不能靠字节比对。）

### 9.3 不能做成 App 开关的

`diffusion_steps`（编译期常量，改它要重编两端）、DiT/LLM KV 缓存与 `n_max_seq`
（加载期，改了要重载模型/重启服务）、后端与线程数（启动参数）。

### 9.4 性能提醒：别和游戏抢 GPU

PC 端合成吃满 CUDA。实测（RTX 4060 Laptop 8GB）：**开着《天国拯救2》时**（游戏占
7651/8188 MiB 显存、GPU 100%），一句 3.5 秒的话要 **326 秒**，第二次请求 900 秒超时；
没有游戏占用时同一句话在十几秒量级。用远程朗读前请先退出游戏（或把 `-MaxLlmLen`/步数
调低，并保留流式，慢速下至少能边出边听）。

## 10. 电脑侧 checkout 上的本地补丁

服务端源码在 `D:\cosyvoice-server`（上游 [Lourdle/cosyvoice.cpp](https://github.com/Lourdle/cosyvoice.cpp)
的 checkout，**不是本仓库的一部分、也不带 git**）。下面两处本地修改是本仓库功能所依赖的，
**重装或更新上游后必须重新打上**，否则对应功能会退化或直接坏掉：

| 补丁 | 文件 | 作用 |
|---|---|---|
| 音色上传接口 | `tools/server/cosyvoice-server.cpp`（`--voice-dir`）、`tools/server/cosyvoice-server.h`（`voice_dir`）、`tools/server/cosyvoice-server-backend.cpp`（`POST /v1/voices`） | 见第 8 节；没有它 App 的「上传本机音色到电脑」会返回 404 |
| **每请求重置生成状态** | `tools/server/cosyvoice-server-backend.cpp`、`src/cosyvoice-tts.cpp` | 见 10.1；没有它朗读会越念越长，最后把服务端进程打崩 |

改完用 VS 开发者环境重编，然后把产物同步到启动器的 `bin/`（**`cosyvoice.dll` 也要同步**，
改动在库里时 exe 不会重新链接）：

```powershell
cmd /c "call `"C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat`" >nul && cmake --build D:\cosyvoice-server\build-cuda"
Copy-Item D:\cosyvoice-server\build-cuda\bin\cosyvoice-server.exe,D:\cosyvoice-server\build-cuda\bin\cosyvoice.dll `
  tools\.work\cosyvoice-server\bin\ -Force     # 先停服，否则文件被占用
```

### 10.1 每请求重置生成状态（朗读越念越长 / 服务端崩溃）

**症状**：同一个音色、同一段文本，念第二遍比第一遍久、第三遍更久，服务端重启后第一遍又正常；
累积到一定程度进程直接崩，stderr 里是
`ggml.c: GGML_ASSERT(cgraph->n_nodes < cgraph->size) failed`。

**判据**：`server.log` 里同一段文本的 `bytes=` / `elapsed_ms=` 一次比一次大。正常应该是
**稳定**的（同一段 91 字约 1.1–1.3 MB / 24–28 s，只有随机波动）。另一个一秒钟就能确认的判据：
**换一个音色立刻恢复正常**（session 的键里有音色）。

**原因**：服务端在启动时为每个 (并发槽, 音色) 建**一个** `cosyvoice_tts_context`（TTS session）
并跨请求复用，而 `src/cosyvoice-tts.cpp` 的 `tts_job()` 会**原地改写这个 session**：

- `cosyvoice_prompt_set(ctx, this, ...)` 默认 `inplace=true`：instruct 模式下截断 `prompt_text`、
  把 `llm_prompt_speech_tokens`（音色的参考语音 token）`reset()` 掉，而且从不恢复；
- session 继承的 `cosyvoice_tokenization_result_impl::tokens` 是**追加**写入的
  （`cosyvoice_vocab::tokenize` 不先清空），而合成真正用的就是 `get_tokens()/get_n_tokens()`；
- session 里还有 `prompt_speech_feat` / `llm_embedding` / `flow_embedding` 等生成态。

于是每次朗读都在上一次的残骸上继续生成：文本越堆越多、模型越跑越长，直到计算图溢出。

**修法（三处，缺一不可）**：

| 位置 | 改动 |
|---|---|
| `tools/server/cosyvoice-server-backend.cpp`，`/v1/audio/speech` 处理器 | 取到并发槽后调 `cosyvoice_empty_buffer_cache(model_ctx)`：清 offload 的 KV、重建计算图、重置 prompt crc（放在请求**开始**而不是结束，被中断的请求也不会留状态） |
| 同上，**非流式**分支 | **每个请求新建一个 session**：`cosyvoice_tts_context_new(model_ctx, voice.prompt.get())`，用完即弃。注册音色那份 prompt 是母版（session 只是它的拷贝，从不被改写），所以新建即干净 |
| `src/cosyvoice-tts.cpp`，`tts_job()` | 进入时 `tokens.clear()`；退出时用 guard 把 `prompt_text` / `llm_prompt_speech_tokens` / `prompt_crc32` 还原 |

App 固定走**非流式**（`stream=false`），所以每请求新建 session 就够；**流式路径仍然复用缓存的
session**，只有 `tts_job()` 的两条守卫在兜底（流式要用到时请照上面的思路一起改）。

**验证**：服务端在跑时，同一段文本、同一音色连发 3 次，看日志里的 `bytes=` 是否稳定：

```powershell
$token = (Get-Content tools\tts-token.txt | Where-Object { $_ -and -not $_.TrimStart().StartsWith('#') } |
  Select-Object -First 1) -replace '^\s*token\s*[:=]\s*', ''
$body = '{"model":"cosyvoice-3","voice":"<音色名>","input":"<一段 80~90 字的台词>","response_format":"pcm","stream":false}'
1..3 | ForEach-Object {
  curl.exe -sS -o NUL -w "%{http_code} %{size_download}`n" -X POST "http://<IP>:8080/v1/audio/speech" `
    -H "Authorization: Bearer $token" -H "Content-Type: application/json" -d $body
}
Get-Content tools\.work\cosyvoice-server\server.log -Tail 8
```

若 `size_download` 递增（例如 1.32MB → 2.46MB → 3.75MB），说明补丁没打上或没同步到 `bin/`。

**回滚**：改动前的二进制备份在 `tools\.work\cosyvoice-server\bin\`：
`cosyvoice-server.exe.bak-before-emptycache-fix`、`cosyvoice-server.exe.bak-before-promptguard-fix`、
`cosyvoice.dll.bak-before-promptguard-fix`（都不进 git）。


