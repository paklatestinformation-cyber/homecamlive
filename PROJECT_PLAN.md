# PROJECT PLAN — Personal CCTV v4

GOAL: Fix MP4 recording save on device, and ship a fixed GitHub Pages URL that opens a full CCTV viewer in any browser with all controls including recording.

## Deliverables
1. Android fix: MP4 written directly to MediaStore (Q+) / public Movies folder (pre-Q), no fragile temp-copy step; muxer guaranteed to start before recording begins.
2. Static web viewer in `docs/` served at `https://paklatestinformation-cyber.github.io/homecamlive/`.
3. Web viewer controls: room ID + connect, live frame, status, MIC toggle, FLIP, DUAL/SPLIT, FULLSCREEN, RECORD/STOP with WebM download.
4. Version bump to `versionCode 4` / `versionName '1.3'`; new release `apk-7`.

## Stack
- Existing native Java Android app; Gradle build only on GitHub Actions.
- MQTT over WebSocket via vendored `mqtt.js` (no CDN dependency).
- Browser audio: `AudioContext` + `AudioBufferSourceNode` per PCM chunk, fanned to speakers and MediaStreamDestination for recording.
- Browser recording: `canvas.captureStream(15)` video + `MediaStreamDestination` audio -> `MediaRecorder` -> WebM blob -> download.
- Styling: existing Command Center palette — navy `#0B1220`, card `#131E33`, amber `#F5A623`.

## Sequence
1. Write this plan + PRD.md before any web code.
2. Rewrite `Mp4Recorder.java` to use MediaStore FD directly; add format-ready wait; bump version.
3. Build web viewer files in `docs/`: `index.html`, `styles.css`, `app.js`, vendored `mqtt.min.js`.
4. Enable GitHub Pages from repo Settings / or push `.nojekyll`; verify fixed URL.
5. Commit all changes, push, watch Actions to green, verify APK, attach release `apk-7`.
6. Test browser page with same Room ID as camera phone; verify frames, audio, controls, and recording download.

## Decisions already locked
- Relay: EMQX primary, HiveMQ fallback.
- Topics: `homecamlive/<room>/frame`, `/frame_back`, `/frame_front`, `/audio`, `/control`, `/status`.
- Web viewer is read-mostly: subscribes wildcard, publishes only control commands.
- Manual FLIP remains in both app and web page.
- Time/date overlay is already baked into frames by the camera app; web page displays as-is.
- No own server, no account, no paid service.

## NOW
Starting step 2: rewrite Mp4Recorder + version bump. Web files come after.
