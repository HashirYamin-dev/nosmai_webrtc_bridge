# nosmai_webrtc_bridge

Stream a **Nosmai-processed camera feed** (filters, beauty, AR effects) over **WebRTC** from Flutter,
on **Android and iOS**, with one Dart API.

```
Camera -> Nosmai (effects) --+--> NosmaiCameraPreview (on screen)
                             +--> this bridge --> flutter_webrtc video track --> your RTCPeerConnection
```

The bridge only turns Nosmai's output into a `flutter_webrtc` track. **Your app** owns the peer
connection, signalling, STUN/TURN, audio and reconnects.

## Requirements

| | Android | iOS |
|---|---|---|
| OS | minSdk 24, arm64-v8a | iOS 15+ |
| Nosmai | AAR 3.0.7 in `android/app/libs/` | `NosmaiCameraSDK` pod 3.0.x (pulled automatically) |
| WebRTC | `flutter_webrtc: 1.6.0` (pinned) | same |
| Licence | bound to `applicationId` | bound to bundle id |

## 1. Install

```yaml
dependencies:
  nosmai_webrtc_bridge:
    git:
      url: https://github.com/HashirYamin-dev/nosmai_webrtc_bridge.git
  nosmai_camera_sdk: ^3.0.6
  flutter_webrtc: 1.6.0        # keep exactly this version
  permission_handler: ^11.3.1
```

## 2. Android setup

1. Copy the Nosmai AAR to `android/app/libs/nosmai-release.aar`.
2. `android/app/build.gradle.kts`:
   ```kotlin
   android { defaultConfig { minSdk = 24; ndk { abiFilters += listOf("arm64-v8a") } } }
   dependencies { implementation(files("libs/nosmai-release.aar")) }
   ```
3. `applicationId` = the id your Nosmai licence is issued for.
4. `AndroidManifest.xml`: `CAMERA`, `RECORD_AUDIO`, `INTERNET`, `MODIFY_AUDIO_SETTINGS`.

## 3. iOS setup

1. `ios/Podfile`: `platform :ios, '15.0'`, and in `post_install` enable the permissions
   `permission_handler` needs (without this it reports "denied" and never prompts):
   ```ruby
   target.build_configurations.each do |config|
     config.build_settings['GCC_PREPROCESSOR_DEFINITIONS'] ||= ['$(inherited)']
     config.build_settings['GCC_PREPROCESSOR_DEFINITIONS'] += ['PERMISSION_CAMERA=1', 'PERMISSION_MICROPHONE=1']
   end
   ```
2. `Info.plist`: `NSCameraUsageDescription`, `NSMicrophoneUsageDescription`,
   `NSLocalNetworkUsageDescription` (for a LAN signalling server).
3. Bundle id = the id your Nosmai licence is issued for. Set your own signing team in Xcode.

## 4. Go live (sender)

```dart
// a) Once, at startup. ORDER MATTERS: share context BEFORE Nosmai init (no-op on iOS).
await NosmaiWebRtcBridge.registerShareContext();
await NosmaiFlutter.initialize(licenseKey);

// b) Show the camera: put NosmaiCameraPreview() in your widget tree.
//    Do NOT call getUserMedia for video; Nosmai owns the camera.

// c) Go live
final v = await NosmaiWebRtcBridge.createVideoTrack();       // 720x1280 by default
final pc = await createPeerConnection({'iceServers': [/* STUN + TURN */]});
await pc.addTrack(v.track, v.stream);
final mic = await navigator.mediaDevices.getUserMedia({'audio': true, 'video': false});
for (final t in mic.getAudioTracks()) { await pc.addTrack(t, mic); }
// ...create the offer and exchange SDP/ICE over your own signalling...

// d) Stop: bridge FIRST, then the peer connection
await NosmaiWebRtcBridge.stopStreaming();
await pc.close();
```

**Viewer:** a normal `flutter_webrtc` receiver (`onTrack` → `RTCVideoView`), or any browser. It needs
no Nosmai SDK and no licence. See `example/lib/viewer_page.dart`.

**Effects while live:** call Nosmai as usual (`applySkinSmoothing`, `applyEffect`, `removeAllFilters`,
…). The stream carries whatever Nosmai renders, so viewers see the change on the next frame.
See `example/lib/effects_sheet.dart`.

## API

| Method | What it does |
|---|---|
| `registerShareContext()` | Android: joins WebRTC's EGL group. **Await before `NosmaiFlutter.initialize`.** iOS: no-op. |
| `createVideoTrack({width, height, fps})` | Returns `(stream, track)`. Optional downscale; 0 = Nosmai's size. |
| `stopStreaming()` / `isStreaming()` | Teardown / state. Call stop before `pc.close()`. |
| `setMirror(bool)` | Flips the **stream** only (default off). |
| `setRotation(deg)` | Rotation metadata. Keep 0 (frames are already upright). |
| `frameStats()` / `getCounters()` | Nosmai fps, pushed fps, dropped frames. |

## Rules that save debugging time

- Android: `registerShareContext()` after Nosmai init → remote video is **black, no error**.
- Never open a second camera (`getUserMedia` video) → large fps loss.
- Keep `flutter_webrtc` at **1.6.0**; the bridge uses one of its internal classes. Re-test before upgrading.
- Production needs a **TURN** server. STUN alone fails on many mobile networks.
- Never commit the licence key. Pass it with `--dart-define=NOSMAI_LICENSE=...`.

## Run the example

```bash
# 1. Signalling server + browser viewer (Mac and phones on the same Wi-Fi)
cd example/server && npm install && node server.js      # prints ws://<mac-ip>:8080

# 2. App (Android or iOS)
cd example
flutter run --release \
  --dart-define=NOSMAI_LICENSE=<your-key> \
  --dart-define=SIGNAL_URL=ws://<mac-ip>:8080 \
  --dart-define=ROOM=qa
```

In the app: one phone taps **Open sender** → **Go Live** (**Effects** button for filters); another phone
taps **Open viewer**, or open `http://localhost:8080/viewer.html?room=qa` on the Mac.
Before building, change the example's `applicationId` / bundle id and iOS signing team to your own.
`AUTO_LIVE=true` skips the taps (for scripted tests).

## Status

- **iOS:** verified on iPhone X (sender → Mac viewer): 720x1280 at 30 fps, hardware H.264, 0 dropped
  frames, 0 packet loss over a 3-minute run.
- **Android:** builds and runs; streaming verified earlier on OnePlus CPH2515 with a second phone as viewer.
- **Not yet verified:** effects as seen by the viewer, mirror on iOS, back camera, repeated stop/go-live,
  long runs, TURN/mobile networks.
