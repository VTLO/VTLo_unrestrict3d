# VTLo_unrestrict3d

Android app (Kotlin + Jetpack Compose) that runs a **LiteRT-LM** model fully on-device, with a
crash-proof memory layer and a *plan once, run as code, recall the AI only on a verified stop* pipeline.

The header carries an animated banner (cross-fading reference pictures, slow pan/zoom, scan-lines, light sweep).

## Build
```
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # 15 JVM tests for the pipeline
```
JDK 17+ and Android SDK (platform 35). `.github/workflows/build-apk.yml` builds the APK on GitHub.

## Model
- **Built-in model**: Gemma-4-E2B abliterated (`.litertlm`, ~5.07 GB, INT8, 8 GB+ RAM recommended).
  A 5 GB file cannot live inside an APK, so its URL is hardcoded (`data/DefaultModel.kt`): one tap
  downloads it (resumable, Wi-Fi advised) into private storage and it auto-loads afterwards.
  INTERNET is used for that download only.
- Or import any other `.litertlm` file.

## Stability (no more silent crashes)
- Crash log + event log + previous exit reason (`ApplicationExitInfo`: low memory, native crash, ANR…) shown in a banner.
- Foreground service + wake lock while working; low-memory watchdog during generation; idle release of the model (lazy reload).
- Context window defaults to 4096 (the model's 32k default is a likely crash cause).
- **Context compression**: at 70% of the window the conversation is summarised into a compact memory, then the old turns are dropped.

## Root (optional, opt-in)
Raise the app's `oom_score_adj`, renice, drop caches, compact memory, kill background apps, list top memory users,
copy a model with correct SELinux context. Everything works without root.

## Auto pipeline (REcod3r-style "coding economy")
1. The model understands the request and plans **once**, as JSON steps (`write_file`, `sh`, `root_sh`, `ask_ai`).
2. The model is **released** (memory cut-out) and code runs the steps in a sandboxed run folder, retrying failures itself.
3. The AI is recalled only after a verified stop (failed step, `needs_ai`, decision, blocked), within a budget; its fix is re-validated.
4. A guard deny-list blocks dangerous commands; root steps need approval (once / all in run / skip).
5. State is checkpointed after every step, so a killed run can be resumed. The final report is written by code.

## Layout
Home screen = the studio robot pointing at you; chat = plain darkest grey with high-contrast text.
VTLO logo = splash + launcher icon.

## Notes
- `arm64-v8a` only. LiteRT-LM pinned to 0.16.1 (newer needs Kotlin 2.4 / newer D8).
- Not yet validated on a physical device; whether the abliterated export loads on LiteRT-LM 0.16.1 is unverified.
- Lighter alternative: the official litert-community Gemma-4-E2B (~2.6 GB).
