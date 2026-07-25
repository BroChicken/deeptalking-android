# DeepTalking Android prototype

This APK hosts `hub.html` in a local-only WebView and exposes a native offline TTS bridge.

- Every character has a `voicePack` field: `xiaoya`, `huayan`, or `matcha`.
- `VoiceManager` owns exactly one active model. Changing to a different pack stops playback, releases the old model, and initializes the requested pack.
- The source model files are copied into `app/src/main/assets/voices/` before the cloud build.

The initial build is intentionally unsigned and is published as a GitHub Actions artifact.
