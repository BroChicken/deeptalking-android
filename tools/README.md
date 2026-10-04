# tools/

Native Android build/test tooling. All app development lives in `android-lite/`;
these scripts are thin wrappers around Gradle that resolve a JDK 17 and the
Android SDK automatically (see `native/env.ps1`).

## Scripts

| Script | Purpose |
|---|---|
| `native/env.ps1` | Dot-sourced by the others; resolves JAVA_HOME / ANDROID_SDK_ROOT / gradle. |
| `native-verify.ps1` | Compile all modules + run the full unit-test suite. Pre-commit gate. |
| `native-test.ps1` | Run just the unit-test tasks and print a pass/fail summary. |
| `native-build.ps1` | `assembleDebug` (default) or `-Release`. |
| `native-version.ps1` | Print / bump `versionName` + `versionCode` in `app/build.gradle.kts`. |
| `pull-apk.ps1` | Download the latest released APK via the GitHub CLI. |

## Environment overrides

`env.ps1` looks for, in order:

- JDK 17: `$env:JAVA_HOME`, `$env:DEVTOOLS_JDK17`, `D:\devtools\temurin17\*`, `C:\Program Files\Eclipse Adoptium`, `C:\Program Files\Java`.
- Android SDK: `$env:ANDROID_SDK_ROOT`, `$env:ANDROID_HOME`, `android-lite/local.properties` `sdk.dir`, `$env:DEVTOOLS_SDK`, `D:\Android`, `%LOCALAPPDATA%\Android\Sdk`.
- Gradle: `gradle` on PATH, `$env:DEVTOOLS_GRADLE`, or `D:\devtools\gradle-*`.

## Test layout

Unit tests live in each module under `src/test/`, run with `native-test.ps1`:

- `:engine:ondevice` — pluggable inference backends (LLM/Embedding/ASR/TTS).
- `:domain:agent` — tool registry, orchestrator turn (streaming + memory fold), style guard.
- `:domain:memory` — retrieval ranking, lorebook selection, memory policy.
- `:core:data` — legacy import / mapper.
- `:feature:richtext` — markdown renderer.

CI runs `native-verify.ps1` equivalent tasks before building the release APK.
