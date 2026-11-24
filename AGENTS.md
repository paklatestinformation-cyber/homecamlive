# HomeCamLive — project memory

## What
One Android APK, two modes: Camera (CCTV broadcaster) and Viewer (live remote
feed over the internet). **Display name (v3): "Personal CCTV"** (was HomeCamLive;
package/id/repo stay `com.kamran.homecamlive` / `homecamlive`). Developer credit:
Muhammad Kamran Malik, +923135275771.

## v3 request (23 Nov 2026) — IN PROGRESS
User report/asks:
- RECORD start hota hai par MP4 device par save nahi hota — fix the save path.
- Camera rotation galat side dikhata hai — fix orientation for broadcast + recording.
- New option: **front + back dono camera ek hi screen par** (split/dual view).
- **Full-screen view** option in Viewer.
- Existing manual camera flip option **remove nahi karna** — dono options rahen.
- App name change karke `Personal CCTV` karna.
- Video ke upar **live time/date overlay** — aur ye overlay recording mein bhi save ho.

## Architecture
- Native Java UI, Material3 Dark "Command Center" (navy #0B1220, card #131E33,
  amber #F5A623, line #23314C, text #EAF0FA / #8FA2C0).
- Free public MQTT-over-WebSocket relay (no own server, no account):
  primary `wss://broker.emqx.io:8084/mqtt`, fallback
  `wss://broker.hivemq.com:8884/mqtt`. Topics `homecamlive/<roomid>/frame`,
  `/audio`, `/control`, `/status`.
- Camera1 API -> NV21 -> YuvImage JPEG (~45 quality), ~5 fps, QoS 0.
- Audio: PCM16 16 kHz mono, 40 ms chunks, published to `/audio`; viewer plays
  via AudioTrack. Control + status on `/control` and `/status`.
- Remote flip + recording triggered from viewer over the control topic.
- Camera foreground Service with SurfaceTexture + partial wake lock keeps
  streaming when backgrounded; local preview = same frames via broadcast.
- Viewer subscribes wildcard `homecamlive/<room>/#`; camera subscribes **only**
  `homecamlive/<room>/control` so it does not receive its own frames/audio.
- Room ID stored in SharedPreferences, random `HOME-XXXX`.
- v2 (23 Nov): versionCode 2 / versionName 1.1; FGS type `camera|microphone`;
  permissions RECORD_AUDIO + WRITE_EXTERNAL_STORAGE(maxSdkVersion 28).

## Key files
- `app/src/main/java/com/kamran/homecamlive/RelayClient.java` — MQTT multi-topic
- `app/src/main/java/com/kamran/homecamlive/AudioCapture.java` — mic -> PCM packets
- `app/src/main/java/com/kamran/homecamlive/AudioPlayer.java` — PCM -> AudioTrack
- `app/src/main/java/com/kamran/homecamlive/Mp4Recorder.java` — H264+AAC MP4 muxer
- `app/src/main/java/com/kamran/homecamlive/Control.java` — command constants
- `app/src/main/java/com/kamran/homecamlive/CameraService.java` — foreground capture + publish
- `app/src/main/java/com/kamran/homecamlive/MainActivity.java` — Home / mode select
- `app/src/main/java/com/kamran/homecamlive/CameraActivity.java` — broadcaster UI
- `app/src/main/java/com/kamran/homecamlive/ViewerActivity.java` — remote feed UI
- `.github/workflows/build.yml` — builds APK, attaches to GitHub release

## Commands
- Build is cloud-only: edit -> `git add -A && git commit && git push` ->
  GitHub Actions produces APK in Releases.
- Local preview of UI mockup: `python -m http.server 8485 --bind 127.0.0.1`
  then open `http://127.0.0.1:8485/ui-preview.html`.

## Dated decisions
- 22 Nov 2026: user picked relay option 1 and UI design 2.
- 23 Nov 2026: v2 shipped — audio, receiver-side flip, MP4 recording with audio.
  versionCode 2 / versionName 1.1; run 45081217616 green; release apk-4
  (5,720,540 bytes). Workflow has `permissions: contents: write` from v1 fix.
- 24 Nov 2026: v3 shipped as **Personal CCTV** (display name only; package/repo
  unchanged). versionCode 3 / versionName 1.2; run 45275319298 green; release apk-6
  (5,730,648 bytes). Adds robust MP4 save (temp file -> MediaStore copy),
  sensor/device rotation fix + front mirror, dual front/back split view,
  fullscreen overlay, and baked-in live date/time overlay.
  Dual mode needs two cameras open at once; unsupported phones get DUAL_FAIL status.

## v4 request (24 Nov 2026) — IN PROGRESS
User report/asks:
- **Recording abhi bhi device par save nahi ho raha** — dobara root-cause karo.
- Add a **fixed web address** (browser me kholo → CCTV view turant chal jaye).
  Har baar URL change nahi hona chahiye.
- Us web page par **saare controls** ho: live view, audio, flip, dual/split,
  fullscreen, aur **recording**.
- Decision: static web viewer GitHub Pages par fixed URL
  `https://paklatestinformation-cyber.github.io/homecamlive/` — repo ka `docs/` folder.
  Browser page MQTT(WebSocket) se same `homecamlive/<room>/#` subscribe karega.
- Implementation SHIPPED 24 Nov 2026 (awaiting device test):
  - `Mp4Recorder.java` rewritten: Q+ par MediaStore `openFileDescriptor` se seedha
    final MP4, pre-Q par public `Movies/HomeCamLive` File; temp copy/delete hata diya.
    Dono encoder tracks ke ready hone tak 1.5 s wait; na ho to start fail with error.
    Stop par IS_PENDING clear + `OpenableColumns.SIZE` verify.
  - `versionCode 4`, `versionName '1.3'`.
  - `docs/` me static viewer: `index.html`, `styles.css`, `app.js`, vendored
    `mqtt.min.js` (329 KB), `.nojekyll`. Controls: MIC, CAM MIC, FLIP,
    SPLIT/DUAL, FULLSCREEN, RECORD/STOP (WebM download), SNAPSHOT, EVENT LOG.
  - `PROJECT_PLAN.md` + `PRD.md` web code se pehle likhe gaye.
  - Build: commit `ddf1133`, run `45357117257` attempt 2 green (pehla attempt
    Maven Central 429 se fail tha), release `apk-7`, APK 5,731,232 bytes verified.
  - GitHub Pages enabled via API from `main:/docs`; fixed URL returns 200.
  - 24 Nov clarification: user wants PC access from ANY location when the second
    phone is unavailable. The GitHub Pages URL IS that fixed address — a permanent
    URL, not a device IP. `127.0.0.1` local preview is phone-only; never give it
    as the PC address. Camera phone must stay online with broadcast ON; PC only
    needs a browser + internet + the same Room ID.
  - 24 Nov LATER REVERSAL: user checked the web link and says **remove it** —
    only mobile-to-mobile view is required. Do not ship/present the web viewer.
    Recording **still not saving** after `apk-7` direct-MediaStore attempt —
    open defect (v5), needs deeper fix; see below.

## v5 request (24 Nov 2026) — IN PROGRESS
- Remove web link / web viewer; app is mobile-to-mobile only.
- Fix recording once and for all: user reports no MP4 on mobile after apk-7.
- Chosen approach: temp MP4 first (reliable app-private write), then publish to
  `DCIM/Camera` via MediaStore Video (Gallery always sees it), with fallbacks
  to Downloads and Movies root; keep temp if all fail; verify SIZE > 0; show
  exact saved path in status Toast on camera phone and viewer phone.
