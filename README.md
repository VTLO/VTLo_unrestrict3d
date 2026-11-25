<p align="center"><img src="docs/banner.gif" alt="VTLo_unrestrict3d" width="100%"></p>

<p align="center">
<a href="https://github.com/VTLO/VTLo_unrestrict3d/raw/main/releases/VTLo_unrestrict3d-arm64.apk"><b>⬇ Download APK</b></a> (arm64 · ~90 MB) ·
<a href="https://github.com/VTLO/VTLo_unrestrict3d/releases/latest/download/VTLo_unrestrict3d-arm64.apk">Latest release APK</a> ·
<a href="https://vtlo.github.io/VTLo_unrestrict3d/"><b>🌐 Web page</b></a>
</p>

# VTLo_unrestrict3d

Minimal, crash-free Android chat app that runs a **LiteRT-LM** model on-device. Inference is offline; the internet is only used by skills and MCP tool calls, and only when you turn them on.
GPU (with MTP speculative decoding when the model ships a drafter) and automatic CPU fallback.
Animated header banner, plain dark readable chat.

## Install
Download the APK above, open it on your phone (allow unknown apps), then tap **IMPORT MODEL** and pick your `.litertlm`.
SHA-256 of the committed APK: `59788b32212a80e5a9d533bcab22533bdcc9bbc55e46174663bd2ac65e3d0eb0`

## Build
`flake.nix` pins the whole toolchain (JDK 21, Gradle 9, Android SDK 37, AGP 9.2.1):
```
nix develop --command bash -c 'export GRADLE_USER_HOME=$PWD/.gradle-home && gradle :app:assembleDebug'
# -> app/build/outputs/apk/debug/app-debug.apk
```
GitHub Actions builds it too (`.github/workflows/build-apk.yml`).

## Model
Install the app, open it, tap **IMPORT MODEL** and pick your `.litertlm` file (it is copied into the app's private
files dir). Or copy it yourself to `/sdcard/Android/data/com.vtlo.unrestrict3d/files/model.litertlm`
and reopen. Loading happens once, on GPU first, CPU if the driver refuses.

## Skills & tools (same idea as AI Edge Gallery's Agent Skills)
Tap **SKILLS** in the header. Off by default, so the plain chat stays exactly the tested path.
- **Skills** use the Gallery format: a kebab-case folder with `SKILL.md` (+ `scripts/index.html` for JS skills that define `window.ai_edge_gallery_get_result(data, secret)`).
  Add one from the URL of its folder, or import a folder from the phone. Per-skill on/off switch and optional secret field.
- The model gets three tools: `load_skill`, `run_js` (hidden WebView) and `run_intent` (`send_email`, `send_sms`, `open_url`, `dial`).
- **MCP servers** (Streamable HTTP, optional bearer token): every tool of the server is exposed to the model, so it can run scripts on your own PC or a cloud endpoint.
- Tool calls are shown in the chat as ⚙ lines. Applying changes starts a fresh model context.

## Notes
- LiteRT-LM 0.20.0, `maxNumTokens = 4096`, sampler topK 64 / topP 0.95 / temperature 1.0 (edit in `MainActivity.kt`).
- minSdk 31 (Android 12+), arm64 + x86_64.
