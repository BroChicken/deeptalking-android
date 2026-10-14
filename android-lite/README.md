# DeepTalking（android-lite）

DeepTalking 的原生 Android 应用：Kotlin + Jetpack Compose 多模块，入口模块 `:app`。

- 模块清单、依赖方向与关键接口：`docs/NATIVE_MODULES.md`
- Agent（agent 循环、工具、提示词、记忆、后台任务）：`docs/AGENT_ARCHITECTURE.md`
- 语音朗读链路（端侧 / 远程）：`docs/REMOTE_TTS.md`
- 包名 `com.deeptalking.lite`，显示名 DeepTalking

## 形态

- 界面全部是 Compose；WebView 只作为富文本/公式渲染子系统（`:feature:richtext` 内嵌 KaTeX，资产随该模块打包）。
- 数据存 Room + DataStore（`:core:data`），API Key 存 Keystore（`:core:security`），媒体（图片/表情）以文件存放。
- 端侧/远程推理统一走 `:engine:ondevice` 的接口族：`LlmBackend`（远程 Responses API）、`TtsBackend`（端侧 CosyVoice3）、`TtsSpeaker`（朗读播放，端侧或远程按 `AppConfig.ttsBackend` 分派）。

## 构建与自检

需要 JDK 17、Android SDK 与 Gradle（仓库根目录的脚本会自动解析路径）：

```powershell
powershell -File ..\tools\native-verify.ps1              # 静态检查 + 全模块编译 + 全部单测（提交前必跑）
powershell -File ..\tools\native-test.ps1                # 仅单测
powershell -File ..\tools\native-build.ps1               # debug 打包
powershell -File ..\tools\native-build.ps1 -Release      # 签名 release（需 keystore 环境变量）
```

等价于在本目录运行 `gradle :app:assembleDebug` 加各模块单测（完整任务清单见 `tools/native-verify.ps1`）。debug 产物：`app/build/outputs/apk/debug/app-debug.apk`。

## 版本

`app/build.gradle.kts` 的 `versionName`（`X.Y.Z`）与 `versionCode` 是版本号的唯一来源；每次出包补丁位 +1、`versionCode` +1，用 `powershell -File ..\tools\native-version.ps1 -BumpPatch` 修改。

## CI 与覆盖升级

`.github/workflows/build-lite-apk.yml` 在 `android-lite/**` 变化时编译、跑全部单测，并用固定 release keystore 构建签名 APK 发布。`applicationId`、签名与旧数据导入链的稳定是覆盖安装的前提（旧版数据需先在旧版导出 JSON，再在原生「导入备份」导入）。
