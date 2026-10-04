# tools/diff — JS ↔ Kotlin differential oracle

Guards that the native Kotlin port of the agent/prompt layer stays behaviorally
equivalent to the legacy WebView implementation.

## How it works

1. `scenarios.mjs` defines fixed scenarios (character, config, user turn). One
   source produces both the legacy-shaped object the JS reads and the
   native-shaped object the Kotlin model reads (`toNativeCharacter`).
2. `run-js.mjs` loads the legacy `src/js` modules in a `vm` sandbox, builds the
   system prompt, volatile context and request body for each scenario, and
   writes:
   - `js-oracle.json` — the legacy output (the expectation).
   - `scenarios.json` — the native-shaped scenarios the Kotlin test consumes.
   Both are written into `android-lite/domain/agent/src/test/resources/`.
3. `DifferentialOracleTest` (Kotlin) rebuilds the same output natively and
   compares byte-for-byte:
   - tool contract (names, order, required, property keys, strict): exact.
   - system prompt: exact (the role block is 100% byte-identical).
   - volatile context: exact except the narrative-rhythm skeleton, which is
     randomly chosen in both implementations and is normalized out.
   - submit phase: locks `submit_response`, `reasoning.effort = none`.

## Regenerating the oracle

Run this whenever the legacy `src/js` prompts or the native prompt builders
change; it rewrites the two test resources, then the Kotlin test re-verifies.

```bash
node tools/diff/run-js.mjs \
  android-lite/domain/agent/src/test/resources/js-oracle.json \
  android-lite/domain/agent/src/test/resources/scenarios.json
```

Then run the native test:

```bash
powershell -File tools/native-test.ps1
```
