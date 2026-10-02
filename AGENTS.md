# 开发规则（必须遵守）

## 1. 试验失败品（不予理睬，不再开发/使用/扩展）
| 功能 | 位置 | 结论 |
|---|---|---|
| **TTS 语音合成** | `archive/experimental_failures/tts/`（含 kokoro/matcha 等候选模型） | 试验失败品：模型体积大、合成不稳定、体验差；**不集成、不维护** |
| **像素头像**（16×16 网格） | `archive/experimental_failures/pixel_test/` | 试验失败品：模型输出网格常缺行/缺色/尺寸错误；**不再渲染**，头像统一走 emoji，代码中仅保留 `normalizePixelAvatar` 做历史数据兼容 |

## 2. 开发入口与生成产物（必须遵守）
- 开发源码：`src/shell.html`、`src/styles/` 与 `src/js/`；模块拼接顺序由 `src/manifest.json` 声明。修改对应模块后运行 `node tools/build-hub.mjs`。
- **后续开发只能基于拆开的模块源码。单文件不再作为开发入口维护；禁止在 `hub.html` 中实现功能、修复问题或改样式，也禁止修改产物后反向覆盖模块源码。**
- 网页入口：`hub.html`（仓库根目录，由模块源码生成，禁止直接手改，避免下次构建覆盖）。
- APK 资产：`android-lite/app/src/main/assets/hub.html`（同一构建器生成，禁止手改）。
- **模块改动后必须通过构建器更新两份 `hub.html`，两份文件字节级一致，否则禁止提交。**
  `node tools/build-hub.mjs --check` 检查源码、网页与 APK 资产三方一致。发现差异时修正源码并重建，不使用手工复制作为修复方式。
  提交前跑一次校验：`powershell -File tools/check-sync.ps1`（SHA256 不一致会退出码 1）。
- **文档：`docs/AGENT_ARCHITECTURE.md` 必须同步更新**（凡触及 agent 循环、工具清单、提示词与交互规则、记忆系统、角色/成员字段、常量与上限的改动），并同步修正其中的函数锚点、常量表与新增小节。
  **文档只描述"当前最新状态"，禁止写变更记录/版本历史**（历史看 git）。
- `stable_version/` 已废弃、不再保留；需要回退旧版本用 git 历史与标签。模块化版本应回退对应源码与构建配置后重建产物，不单独回退 `hub.html` 造成源码与产物漂移。

## 3. 版本号规则
- `android-lite/app/build.gradle.kts` 中 `versionName` 为 `X.Y.Z`，`versionCode` 单调递增。
- 每次 APK 更新：**版本号最后一位 Z 必须 +1**（如 1.0.0 → 1.0.1）。
- 前两位 X.Y 由用户决定是否升级；**若我认为需要升 X 或 Y，必须先询问用户**，不得擅自修改。
- `versionCode` 必须同步 +1。
- 右上角显示的版本号来自 `hub.html` 里的 `const APP_VERSION`，**唯一来源是 `versionName`，不要手改**：改完 `versionName` 后跑 `node tools/sync-version.mjs`（会把两份 `hub.html` 同步成同一版本）；CI 在打包前也会自动跑一次，保证 APK 显示与 `versionName` 一致。

## 4. 每次更新后要支持直接安装包覆盖升级
- 签名必须稳定一致，否则用户手机会报"签名冲突/与现有应用签名不一致"无法覆盖安装。
- 构建在 GitHub Actions 上跑 `assembleRelease`，签名用固定 keystore（`android-lite/keystore/deeptalking-release.jks`，由 CI secret `ANDROID_KEYSTORE_BASE64` 还原），不得改回临时 debug 签名。

## 5. 改完必跑的自检（全部通过才算完成）
0. `node tools/build-hub.mjs --check` —— 模块源码与两份产物一致（先用不带 `--check` 的命令构建）。
1. `powershell -File tools/check-sync.ps1` —— 两份 `hub.html` 字节级一致。
2. `node tools/sync-version.mjs --check` —— `APP_VERSION` 与 `versionName` 一致。
3. `node tools/verify-hub.mjs` —— 脚本块语法检查 + 纯函数单测（含右上角状态显示全分支）+ 提示词静态断言 + tab/版本一致性（改提示词/记忆/世界书/弹窗 tab/状态文案后尤其必跑；新增功能应同步补断言）。
   `node --test tools/test-agent-regressions.mjs` —— 模块源码级记忆/证据/时间/工具/缓存统计与最新存档只读回归（改 agent 循环、记忆整理、证据校验、请求前缀或缓存统计后必跑）。
   `node tools/test-build-hub.mjs` —— 构建器生成/幂等/漂移/异常回归。
   改前端交互后建议跑一次 `python tools/test-hub-browser.py --smoke`（需 Playwright）；发布前跑一次 `python tools/test-hub-browser.py --save` 用真实存档只读启动校验（不修改存档）。
4. `docs/AGENT_ARCHITECTURE.md` 已同步更新（见第 2 条）。
