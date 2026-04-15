# Watchdog (Scaffold)

Secure home CCTV starter scaffold with two Android apps:
- `app-camera`: runs on old phone and publishes camera/mic stream.
- `app-viewer`: runs on your main phone and connects to trusted camera.

## Security model in this scaffold
- One-time pairing concept (`deviceId + pairingCode`) exists in `:core`.
- Trusted viewer list is modeled in `TrustedViewerRegistry`.
- No per-session "accept" prompt is needed after trust is established.
- You should persist trust state with encrypted storage before production use.

## Current status
Phase 2 scaffold is now included:
- Camera app stores `cameraId`, pairing code, and trusted viewers in encrypted local storage.
- Viewer app shows its device fingerprint and stores last Tailscale host securely.
- One-time setup is modeled as: trust viewer fingerprint once, then reconnect without prompts.

Phase 3 and Phase 4 structure is now added:
- `CameraStreamService` foreground service lifecycle for long-running monitoring.
- `CameraStreamEngine` and `ViewerStreamEngine` classes to hold streaming pipeline logic.
- `SignalingApiClient` and signaling DTO models in `:core` for camera registration and offer/answer.
- Camera app start/stop service UI and viewer app connect trigger UI.

Phase 5/6 implementation now adds:
- Runtime camera/mic permission flow before starting capture service.
 - Camera capture and publish via WebRTC camera capturer in `WebRtcCameraClient`.
- Camera-side WebRTC answer generation with local media tracks.
- Viewer-side WebRTC offer creation, remote-answer apply, and remote video renderer hook.
- Direct-IP signaling hosted inside camera app on port `8080`.
- Bi-directional ICE relay APIs and polling loops.
- Pairing code is validated on every signaling offer.
- STUN fallback (`stun.l.google.com:19302`) is enabled for better ICE setup.

## Project modules
- `:core` shared pairing and trust models
- `:app-camera` camera role app
- `:app-viewer` viewer role app

## Build APKs
1. Open `watchdog/` in Android Studio.
2. Let Android Studio sync Gradle and generate Gradle wrapper if prompted.
3. Build:
   - Camera debug APK: `.\gradlew :app-camera:assembleDebug`
   - Viewer debug APK: `.\gradlew :app-viewer:assembleDebug`
4. APK output paths:
   - `app-camera/build/outputs/apk/debug/app-camera-debug.apk`
   - `app-viewer/build/outputs/apk/debug/app-viewer-debug.apk`

## Direct-IP flow (Tailscale)
1. Install Tailscale on both phones and login with same account.
2. Start `Watchdog Camera` service on old phone.
3. In `Watchdog Viewer`, enter old phone Tailscale IP (example: `100.x.x.x`).
4. Viewer automatically calls `http://<camera-tailscale-ip>:8080`.

### Camera app local signaling endpoints
- `POST /api/register-camera`
- `POST /api/offer`
- `POST /api/answer`
- `GET /api/answer`
- `POST /api/ice`
- `GET /api/pull-ice`
- `GET /health`

## Next implementation steps
1. Replace HTTP polling signaling with WebSocket signaling for lower latency.
2. Add STUN/TURN servers in peer connection config for NAT traversal stability.
3. Wire CameraX frame source directly into WebRTC custom capturer path (optional optimization).
4. Harden auth by validating Tailscale identity/JWT in camera local signaling layer.
5. Add end-to-end reconnection and heartbeat handling.
6. Add motion snapshots and push notifications.
