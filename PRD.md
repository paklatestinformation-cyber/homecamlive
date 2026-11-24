# PRD — Personal CCTV Web Viewer

## Problem
Recording starts but no MP4 appears on the device after apk-6. Separately, the user must install the APK to watch CCTV; they want a single fixed browser address that always works and has every control including recording.

## Users
- Owner on camera phone: starts broadcast, optionally starts/stops recording.
- Viewer on any browser/phone/PC: opens fixed URL, enters Room ID, watches live feed, uses controls.

## Requirements
### Must
- R1: MP4 must be visible in Gallery / Files under `Movies/HomeCamLive` after STOP REC.
- R2: Fixed URL `https://paklatestinformation-cyber.github.io/homecamlive/` must never change.
- R3: Opening URL + entering same Room ID must show live frames automatically.
- R4: Web page must show single feed and dual front/back split when available.
- R5: Web page must provide MIC, FLIP, DUAL/SPLIT, FULLSCREEN, RECORD/STOP.
- R6: Web RECORD must produce a downloadable video file (WebM) with audio when available.
- R7: Same Command Center visual style as the app.

### Should
- S1: Show connection status and last status message from `/status`.
- S2: Remember Room ID in localStorage.
- S3: Handle broker failover automatically.

### Won't
- W1: No server-side account or paid relay.
- W2: No direct write to phone Gallery from browser (browser downloads to Downloads folder).
- W3: No changes to package name or repo name.

## Acceptance tests
- A1: Install apk-7 on camera phone; start broadcast; RECORD -> STOP; MP4 exists in Movies/HomeCamLive and size > 0.
- A2: Open fixed URL in Chrome; enter Room ID; live frames appear within 10 s.
- A3: Press FLIP / DUAL / FULLSCREEN / RECORD on web page; camera status reflects commands; RECORD produces WebM download.
- A4: `unzip -l app-debug.apk` contains classes.dex, AndroidManifest.xml, resources.arsc.
- A5: GitHub Actions run green; release apk-7 attached.

## Open questions
- None blocking. Design already chosen in prior sessions (Command Center).
