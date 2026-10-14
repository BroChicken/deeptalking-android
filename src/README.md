# src/（已冻结的历史前端快照）

这里存放旧 WebView 版前端的源码：`js/`（按职责拆分的经典脚本）、`styles/`、`shell.html`、`manifest.json`。

- **已冻结**：不再是运行入口，也不作为开发或修复目标。应用是原生 Android（`android-lite/`，见 `docs/NATIVE_MODULES.md`）；所有改动都发生在原生侧。本目录可用于查阅旧行为与旧数据格式。
- **唯一的活依赖**：`tools/diff/run-js.mjs` 会在 Node `vm` 沙箱里加载 `src/js`，复现 `:domain:agent` 差分测试用的 oracle（`android-lite/domain/agent/src/test/resources/js-oracle.json`）。因此 `src/js/` 不能删除；除此之外没有任何构建、CI 或打包步骤读取本目录。
- 曾在此的 `vendor/katex` 已迁到 `android-lite/feature/richtext/src/main/assets/katex`，现在随 `:feature:richtext` 打包。
- 旧数据的导入/导出契约由 `:core:data` 的 `legacy/`（`LegacyImportService` / `BackupService`）维护，与本目录无关。
