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
- [x] R1 — MP4 save fix: muxer temp file par likhta hai, phir MediaStore me copy + IS_PENDING clear
- [x] R2 — Rotation fix: sensor + device orientation se frame rotate, front mirror; broadcast + recording dono
- [x] R3 — Dual camera option: front + back ek hi screen par (frame_back / frame_front topics)
- [x] R4 — Fullscreen overlay Viewer + Camera preview
- [x] R5 — Manual FLIP option bana rahega (dono options)
- [x] R6 — App display name: `Personal CCTV` (package/repo same)
- [x] R7 — Live time/date overlay frames par bake — recording me bhi save hota hai
- [x] R8 — Push, build green, release APK verify

## DONE (v3)
Build green (run 45275319298, commit e69d55f), release `apk-6`,
APK 5,730,648 bytes verified (`classes.dex`, `AndroidManifest.xml`, `resources.arsc`).
Download: https://github.com/paklatestinformation-cyber/homecamlive/releases/download/apk-6/app-debug.apk
Compile fixes: `RUNNING.get()/set()` -> plain boolean, `lastPublish2` forward ref,
`(byte) 16/128` casts in Mp4Recorder.

## v4 request (24 Nov 2026) — SHIPPED, LATER REVERSED
User report: recording abhi bhi save nahi hota. Naya: fixed web address (browser
me kholo → CCTV turant), us page par saare controls + recording.
User ne web link check karke baad REVERSE kar diya — mobile-to-mobile only.

- [x] V1 — Plan + PRD likhe (`PROJECT_PLAN.md`, `PRD.md`) before web code.
- [x] V2 — Mp4Recorder rewrite: direct MediaStore FD (Q+) / public Movies (pre-Q),
      no temp copy; muxer tracks ready hone tak wait; failure par clear error.
- [x] V3 — versionCode 4 / versionName 1.3.
- [x] V4 — Static web viewer in `docs/`: index.html, styles.css, app.js,
      vendored mqtt.min.js, .nojekyll. Fixed URL GitHub Pages.
- [x] V5 — Push + GitHub Actions build green (run 45357117257, attempt 2 after
      Maven Central 429), release `apk-7` (5,731,232 bytes) verified with unzip.
- [x] V6 — GitHub Pages enabled from `docs/`; fixed URL returns 200 with title
      and scripts. Browser preview opened. Device test ab user ke haath me hai.

## Decisions (v4)
- Fixed URL: https://paklatestinformation-cyber.github.io/homecamlive/ (repo docs/).
- Web page MQTT over WebSocket par same `homecamlive/<room>/#` subscribe karti hai.
- Web controls: MIC (local audio), CAM MIC, FLIP, SPLIT/DUAL, FULLSCREEN,
  RECORD/STOP (WebM download), SNAPSHOT, EVENT LOG.
- Browser recording = canvas.captureStream + PCM audio -> MediaRecorder -> WebM
  file Downloads folder me (browser direct Gallery me nahi likh sakta).
- Mp4Recorder ab final MediaStore FD par seedha MP4 likhta hai — temp delete/copy
  race khatam. Is_PENDING clear + size verify stop par.

## v5 request (24 Nov 2026) — SHIPPED
- [x] V1 — Web viewer hata diya: `docs/` repo se delete, GitHub Pages disable (204).
- [x] V2 — Mp4Recorder: temp MP4 phir multi-destination publish —
      `DCIM/Camera` (primary), `Download`, `Movies` fallbacks; SIZE > 0 verify;
      temp tabhi delete jab publish prove ho; warna temp keep.
- [x] V3 — Exact saved path Toast/status: camera phone (`SAVED:` msg) + viewer
      phone (`SAVED:` control command) dono par dikhta hai.
- [x] V4 — versionCode 5 / versionName 1.4.
- [x] V5 — Push + Actions green (run `45370108525`), release `apk-8`
      (5,732,924 bytes) verified with `unzip -l`.
- [x] V6 — Web viewer removed: `docs/` deleted, Pages DELETE 204, URL ab 404.
- [x] V7 — Device test: user ne apk-8 test kiya — RECORD abhi bhi stuck,
      video jerky/bursty; v6 open defect.

## v6 request (24 Nov 2026) — SHIPPED
- [x] V1 — `RelayClient`: latest-wins frame publish (purane frames drop, burst khatam).
- [x] V2 — `CameraService`: frame rate ~12 fps (dual ~8 fps/pane), 480x360-
      targeted frame, JPEG cost kam, callback thread non-blocking publish.
- [x] V3 — `CameraService`: MIC_ON/MIC_OFF remote handle; toggleMic refactor;
      har start failure par `REC_FAIL` status.
- [x] V4 — `Mp4Recorder`: `feedVideo`/`feedAudio` non-blocking (buffer na ho to frame drop).
- [x] V5 — `CameraActivity` / `ViewerActivity`: RECORD button optimistic state + failure reset.
- [x] V6 — Logo/icon design: `brand/logo-final.svg`, adaptive icon updated (Beacon Shield).
- [x] V7 — versionCode 6 / versionName 1.5; commit `9f24fec`; Actions run
      `45499098534` green; release `apk-9` (5,734,540 bytes) verified in all 3 dex files.

## Decisions
- Mobile-to-mobile only; web link/web viewer removed from scope.
- Gallery visibility ke liye primary destination `DCIM/Camera` — yahan har gallery
  scan karti hai. Purana `Movies/HomeCamLive` nested folder kaam nahi kar raha tha.
- Publish fail par file zinda rakhi jati hai (temp), taake data na jaye.
- v6 root cause: ~5 fps + unbounded MQTT publish backlog + heavy JPEG/stamp work
  camera callback thread par + encoder backpressure + silent RECORD start failures.
  Fix: latest-wins frames, ~12 fps, non-blocking encoder feed, explicit failure
  status, optimistic UI state.

## v7 request (25 Nov 2026) — IN PROGRESS
User ne `apk-9` test kiya: RECORD button stuck, recording on nahi hoti, video
save nahi hoti, live video jerky/ruk-ruk kar chalti hai. v6 fixes kaafi nahi the.

- [x] V1 — **Root cause #1:** `Mp4Recorder.stop()` filename return karta tha,
      `CameraService` usko save-path samajh kar `SAVE FAIL` dikhata tha. Ab
      `r.stop()` ke baad `r.getSavedPath()` read hota hai; `SAVED:` sahi publish hota hai.
- [x] V2 — Encoder-safe preview: 480x320 target, 16-pixel alignment priority.
- [x] V3 — `Mp4Recorder.createVideoEncoder()`: AVC encoder list scan, SemiPlanar
      ya Planar color format pick, `toEncoderInput()` NV12/I420; audio track na
      bane to video-only MP4 fallback.
- [x] V4 — Recording start/stop dedicated `recHandler` thread par; camera thread
      block nahi hota.
- [x] V5 — `isRecording()` non-blocking volatile; recording NV21 conversion
      dedicated worker par; stale recording frames drop.
- [x] V6 — Viewer: MQTT callback sirf latest frame rakhta hai; decode worker +
      RGB_565 + stale drop; viewer ka local recorder hata diya (camera phone
      hi record karta hai); remote RECORD state/timeout.
- [x] V7 — `REC_FAIL:<reason>` viewer tak; versionCode 7 / versionName 1.6.
- [ ] V8 — Push + Actions green; release `apk-10` verify.
- [ ] V9 — User device test: RECORD, MP4 save, smooth live view, MIC.

## Decisions (v7)
- Recording authority camera phone par; viewer sirf remote control.
- Save path v5 jaisa: temp MP4 -> `DCIM/Camera` -> `Download` -> `Movies`.
- Viewer decode MQTT thread par nahi; latest-frame only.
- Camera thread se recording conversion hata kar worker par; UI/live feed priority.

## NOW
v7 code done; build pending. Next: commit/push, Actions green, release `apk-10`,
then user device test.
