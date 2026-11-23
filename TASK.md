# GOAL
HomeCamLive: ek hi Android APK — ek phone CCTV camera ban kar broadcast kare,
doosra phone kahin bhi (mobile data / doosri WiFi / location change ke sath)
uski live feed dekhe. Developer credit: Muhammad Kamran Malik, +923135275771.

Plan/UI reference: `ui-preview.html` (design 2 "Command Center" selected).

## Steps
- [x] Step 1 — Requirements + credentials confirm (GitHub token present)
- [x] Step 2 — Architecture pick: free public MQTT/WebSocket relay (option 1)
- [x] Step 3 — UI pick: design 2 Command Center
- [x] Step 4 — Gradle scaffold + GitHub Actions workflow
- [x] Step 5 — Resources: colors/theme/strings/icon/3 layouts
- [x] Step 6 — Java: RelayClient, CameraService, MainActivity, CameraActivity, ViewerActivity
- [x] Step 7 — Push repo, watch build to green, fix errors if any
- [x] Step 8 — Deliver APK download link + usage instructions

## Key decisions (22 Nov 2026)
- Broker: EMQX public `wss://broker.emqx.io:8084/mqtt`, fallback HiveMQ
  `wss://broker.hivemq.com:8884/mqtt`. Topic: `homecamlive/<roomid>/frame`.
- Frames: Camera1 NV21 -> YuvImage.compressToJpeg(quality ~45), ~5 fps, QoS 0.
- Camera runs in a foreground Service (SurfaceTexture, no UI dependency) so
  broadcasting survives app background/screen-off. Partial wake lock.
- Local preview in CameraActivity = same JPEG frames delivered by the service
  (no second camera open).
- Room ID: random `HOME-XXXX`, saved in SharedPreferences, editable on Home.
- Package: `com.kamran.homecamlive`, minSdk 26, targetSdk/compileSdk 34, Java 17.
- Theme: Material3 Dark, navy #0B1220 / card #131E33 / amber #F5A623.

## v2 request (23 Nov 2026) — IN PROGRESS
- [x] V1 — RelayClient: multi-topic publish + topic-aware callback + selectable subscribe filter
- [x] V2 — AudioCapture (camera mic -> PCM over MQTT) + AudioPlayer (viewer AudioTrack)
- [x] V3 — Remote camera flip via `control` topic; status CAM_FRONT/CAM_BACK
- [x] V4 — Mp4Recorder (MediaCodec H264 + AAC -> MediaMuxer), usable on both phones
- [x] V5 — UI buttons: camera MIC/FLIP/REC, viewer SOUND/FLIP/REC; RECORD_AUDIO perm
- [x] V6 — Push, watch build green, verify release APK

## Decisions (v2)
- Topics: `homecamlive/<room>/frame`, `/audio` (raw PCM 16 kHz mono 40 ms),
  `/control` (FLIP, REC_START, REC_STOP), `/status`.
- Viewer subscribes `homecamlive/<room>/#` (wildcard) on one connection.
- Camera subscribes **sirf** `homecamlive/<room>/control` (apne frame/audio wapas nahi leta).
- Recording = local MP4 on each phone via MediaCodec + MediaMuxer fed from the
  same NV21/PCM stream; viewer REC also sends REC_START so camera phone records.
- Audio packets: raw PCM16 16 kHz mono, 1280 bytes / 40 ms (~25 msg/s).
- versionCode 2 / versionName 1.1; FGS type `camera|microphone` (Android 14).
- Mp4Recorder start par 40 ms silence feed karta hai taake mic band hone par bhi
  audio track create ho aur MP4 valid rahe.

## DONE (v1)
Build green (run apk-3), APK verified 5.5 MB.
Download: https://github.com/paklatestinformation-cyber/homecamlive/releases/download/apk-3/app-debug.apk
Fix log: (1) XML scaleType `fitCenter` lowercase; (2) workflow needs
`permissions: contents: write` for release upload.

## DONE (v2)
Build green (run 45081217616, commit 40aa121), release `apk-4`,
APK 5,720,540 bytes verified (`classes.dex`, `AndroidManifest.xml`, `resources.arsc`).
Download: https://github.com/paklatestinformation-cyber/homecamlive/releases/download/apk-4/app-debug.apk

## v3 request (23 Nov 2026) — IN PROGRESS
- [ ] R1 — MP4 save fix: muxer ko temp file par likho, phir MediaStore me copy karo; valid file verify karo
- [ ] R2 — Rotation fix: sensor + device orientation se frame rotate, front mirror; broadcast + recording dono
- [ ] R3 — Dual camera option: front + back ek hi screen par (service publishes frame_front/frame_back)
- [ ] R4 — Fullscreen view option in Viewer (aur camera preview par bhi)
- [ ] R5 — Manual FLIP option bana rahega (dono options)
- [ ] R6 — App display name: `Personal CCTV` (package/repo same)
- [ ] R7 — Live time/date overlay frames par bake karo — recording me bhi save ho
- [ ] R8 — Push, build green, release APK verify

## NOW
R1 se shuru: Mp4Recorder ko robust save path dena hai.
