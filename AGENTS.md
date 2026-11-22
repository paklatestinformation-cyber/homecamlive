# HomeCamLive — project memory

## What
One Android APK, two modes: Camera (CCTV broadcaster) and Viewer (live remote
feed over the internet). Developer credit: Muhammad Kamran Malik, +923135275771.

## Architecture
- Native Java UI, Material3 Dark "Command Center" (navy #0B1220, card #131E33,
  amber #F5A623, line #23314C, text #EAF0FA / #8FA2C0).
- Free public MQTT-over-WebSocket relay (no own server, no account):
  primary `wss://broker.emqx.io:8084/mqtt`, fallback
  `wss://broker.hivemq.com:8884/mqtt`. Topic `homecamlive/<roomid>/frame`.
- Camera1 API -> NV21 -> YuvImage JPEG (~45 quality), ~5 fps, QoS 0.
- Camera foreground Service with SurfaceTexture + partial wake lock keeps
  streaming when backgrounded; local preview = same frames via broadcast.
- Room ID stored in SharedPreferences, random `HOME-XXXX`.

## Key files
- `app/src/main/java/com/kamran/homecamlive/RelayClient.java` — MQTT connect/publish/subscribe
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
