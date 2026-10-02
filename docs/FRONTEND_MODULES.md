# 前端模块与构建工作流

## 运行与开发入口

DeepTalking 的手机应用由原生 Android 外壳和 WebView 前端组成。`MainActivity.kt` 提供文件选择、备份写入、键盘布局与生命周期支持；前端负责角色、群组、对话、Agent、记忆和设置。

唯一开发入口是 `src/`，后续功能、修复、样式与架构调整全部基于拆开的模块源码。单文件不再维护。根目录 `hub.html` 与 `android-lite/app/src/main/assets/hub.html` 仅是提交到仓库的生成产物，不得手改，也不得将产物反向覆盖回源码。它们仍可直接离线打开，APK 仍加载 `file:///android_asset/hub.html`。生成时保留原有 HTML、CSS、JavaScript 顺序与经典脚本作用域，确保 DOM 内联处理器和历史数据兼容。

## 模块职责

| 路径（相对于 `src/`） | 职责与主要函数 |
|---|---|
| `shell.html` | 页面结构、弹窗、输入和资源引用；只保留样式与脚本构建占位符 |
| `styles/mobile.css` | 移动端溢出约束 |
| `styles/utilities.css` | 已编译的 Tailwind 工具样式，不需要网络或运行时编译 |
| `styles/app.css` | 主题变量、聊天样式和响应式布局 |
| `js/core/config.js` | 状态、字段定义、平台预设、常量和内容格式化 |
| `js/core/normalization.js` | 基础归一化、API 请求头、会话 ID |
| `js/characters/avatar.js` | emoji 头像与历史像素数据兼容，不启用像素渲染 |
| `js/characters/entities.js` | 角色与群组创建、选择、删除 |
| `js/characters/profile-completion.js` | 空设定字段补全 |
| `js/characters/generation.js` | 一句话建卡与群组升级 |
| `js/storage/persistence.js` | localStorage 读写、防抖落盘 |
| `js/storage/schema.js` | 数据归一化、旧字段确定性兼容 |
| `js/storage/profile-migration.js` | 旧设定字段 AI 迁移 |
| `js/storage/lorebook-migration.js` | 世界书一次性迁移 |
| `js/storage/backup.js` | 导入、导出、AndroidBridge 备份 |
| `js/memory/lorebook.js` | 世界书匹配、来源校验、写入、淘汰 |
| `js/memory/time.js` | 逻辑日、相对时间、字段清洗 |
| `js/memory/policy.js` | 重要度衰减、去重、裁剪、私人记忆访问 |
| `js/memory/updates.js` | 记忆检索、证据校验、状态与承诺更新 |
| `js/memory/tasks.js` | 召回、整理任务、场景摘要、退避调度 |
| `js/chat/conversation.js` | 主动开口、发送、工具循环、流式主请求 |
| `js/prompts/context.js` | 角色、群组、承诺与话题上下文 |
| `js/prompts/volatile.js` | 本轮动态上下文装配与字符预算 |
| `js/prompts/style.js` | 文风检查、快速回应视角、后台任务队列 |
| `js/prompts/request.js` | 请求消息装配、`callAPI`、散文记忆补写 |
| `js/agent/tool-definitions.js` | Agent 工具清单及 Schema |
| `js/agent/tool-execution.js` | 工具分发、执行与回传 |
| `js/api/retry.js` | 请求级瞬时失败重试 |
| `js/api/response-parsing.js` | 输出清洗、JSON 修复、结构化提取 |
| `js/api/web-content.js` | 网页、图片、B站内容获取 |
| `js/api/responses.js` | submit_response 协议、Responses 请求与事件解析、缓存统计 |
| `js/media/images.js` | 识图上传、压缩与附加图片 |
| `js/media/stickers.js` | 角色独立表情包库与发送 |
| `js/ui/theme.js` | 主题与主题菜单 |
| `js/ui/chat-rendering.js` | 角色列表、聊天 DOM、快速回应、滚动 |
| `js/ui/character-editor.js` | 角色和群组成员编辑、世界书编辑、头像补全 |
| `js/ui/helpers.js` | 侧边栏与设置弹窗入口 |
| `js/ui/status-settings.js` | 状态文案、调试、建卡弹窗、平台配置与连接诊断 |
| `js/bootstrap.js` | 启动顺序、事件委托、视口变化、页面退出落盘 |

## 依赖与执行规则

`src/manifest.json` 是唯一的模块顺序清单。构建器按清单原样拼接，不包装、不压缩、不转换为 ES Module。所有模块当前仍共享原有经典脚本作用域；拆分的是维护边界，尚未实现独立依赖注入或模块隔离。跨模块调用保留原函数名与参数。

状态和常量先声明，初始化事件在 `bootstrap.js` 中最后注册。函数声明的前向引用仍然有效。新增顶层执行代码必须考虑常量初始化时机；新增模块必须显式加入清单。不能直接将这些文件作为多个外部脚本载入页面，否则会改变初始化和作用域行为。

开发时在职责对应的文件中改动，禁止同时维护产物中的另一份实现。`APP_VERSION` 在源码中为 `__APP_VERSION__` 占位符，构建时从 Android 的 `versionName` 注入。工具清单、提示词、字段或常量有变化时同步维护 `AGENT_ARCHITECTURE.md`。

## 构建与验证

APK 的入口是 Gradle 构建：`android-lite/app/build.gradle.kts` 注册 `buildHubAssets`，并由 `preBuild` 依赖它。执行 `gradle :app:assembleDebug` 或 `gradle :app:assembleRelease` 会直接从 `src/` 生成资产再打包；输入包括源码目录、构建器和版本配置，两份生成文件为输出。网页预览则单独调用下面的构建器。

要求 Node.js 22，无 npm 依赖，无打包器安装步骤。从仓库根目录运行：

```powershell
node tools/build-hub.mjs
node tools/build-hub.mjs --check
powershell -File tools/check-sync.ps1
node tools/sync-version.mjs --check
node tools/verify-hub.mjs
node tools/test-build-hub.mjs
node --test tools/test-agent-regressions.mjs
```

`build-hub.mjs` 检查版本、重复模块、模板占位符和拼接后语法，生成两份完全相同的文件。`--check` 只比较源码构建结果和提交产物，不写文件。`sync-version.mjs` 调用同一构建器，避免直接修改生成产物导致源码漂移。`verify-hub.mjs` 还逐个检查模块语法边界，再执行应用契约验证。`test-build-hub.mjs` 在隔离目录中覆盖生成、幂等、源码/资产漂移、无写入检查、非法路径、重复模块、模板错误、语法错误和版本注入。

`test-agent-regressions.mjs`（`node --test`，无网络、无存档依赖，但若存在 `save/deeptalking_backup_2026-10-02.json` 会只读校验）用 `vm` 加载模块源码，覆盖：无关证据不得改写字段、静态字段必须由用户明确要求、显式时间保留时区、同段独立事实与全部来源保留、摘要两个消费者完成前不清理、跨轮世界书交接、坏输出不改计数、裁剪后场景统计、强制召回优先与停用词过滤、过期后台任务不覆盖新状态、组合工具先执行后提交、当前消息变历史后编码一致、群组静态上下文不含地点、未知缓存值保持 `null`、最新存档的游标/文风/纠正检索。存档只读且断言不输出对话正文。

浏览器回归有三种模式（需要 Python Playwright 和 Chromium）：`python tools/test-hub-browser.py --smoke` 对当前构建跑桌面与手机全流程冒烟；`--baseline-ref <git-ref>` 额外与指定提交做逐字节/截图差分（仅适用于纯重构、要求运行期不变时）；`--save [path]` 用真实存档（默认 `save/` 下最新备份）只读启动，校验角色数、逐个切换渲染、持久化与 `APP_VERSION`，并断言存档 SHA256 未被改写。脚本覆盖旧数据加载、编辑、备份、设置、JSON/散文/工具/流式回复与持久化，网络请求使用固定模拟响应，产物写入系统临时目录，不触及用户数据。

CI 先校验提交产物与源码一致，再同步版本、检查两个产物和应用契约，通过后使用固定 release keystore 构建 APK。Gradle 也会保证打包资产来自当前源码。包名、WebView 入口、存储键、资源路径及签名的稳定性是覆盖升级兼容要求。
