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
- [ ] Step 7 — Push repo, watch build to green, fix errors if any
- [ ] Step 8 — Deliver APK download link + usage instructions

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

## NOW
Step 7: repo ban kar push kar rahe hain, phir GitHub Actions build ka intezaar.
