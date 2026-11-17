# nosmai_webrtc_bridge

Publish a **Nosmai-processed camera feed** over **raw WebRTC** (`flutter_webrtc`) from Flutter.
Nosmai's processed GPU texture becomes a normal `flutter_webrtc` video track. Your app owns the
`RTCPeerConnection`, signalling, ICE/TURN, audio and reconnects.

Android only for now (minSdk 24, arm64-v8a). Tested against Nosmai SDK **3.0.7**.

```
Camera -> Nosmai (filters + AR) --+--> on-screen preview   (NosmaiCameraPreview, nosmai_camera_sdk)
                                  +--> this bridge --> flutter_webrtc track --> your RTCPeerConnection
```

## The model: Nosmai owns the camera, WebRTC only transports

- Mount `NosmaiCameraPreview` and do **not** call `getUserMedia` for video. A second camera means a
  second Nosmai pipeline on the one GL worker and a large fps loss.
- `registerShareContext()` **must be awaited before** `NosmaiFlutter.initialize()`. The EGL share
  handle is consumed when Nosmai's GL context is created; a later call is ignored and the remote
  sees black video with no error.

## API

```dart
await NosmaiWebRtcBridge.registerShareContext();      // before Nosmai init
await NosmaiFlutter.initialize(licenseKey);

await NosmaiWebRtcBridge.setMirror(false);            // default; see Mirror below
final v = await NosmaiWebRtcBridge.createVideoTrack();    // optional: width/height/fps downscale
await pc.addTrack(v.track, v.stream);                 // your own peer connection + signalling

NosmaiWebRtcBridge.frameStats().listen((s) => ...);   // Nosmai output fps, pushed fps, drops, blit ms
await NosmaiWebRtcBridge.stopStreaming();             // BEFORE pc.close()
```

| Method | Purpose |
|---|---|
| `registerShareContext()` | Join flutter_webrtc's EGL group. Before Nosmai init. |
| `createVideoTrack({width,height,fps})` | Returns `NosmaiVideoTrack(stream, track)`. 0 = no downscale. |
| `stopStreaming()` / `isStreaming()` | Teardown / state. |
| `setMirror(bool)` | Horizontal flip of the **stream**. See below. |
| `setRotation(deg)` | Rotation metadata. Default 0 is correct on Android. |
| `getCounters()` / `frameStats()` | Raw counters / per-second rates (Nosmai output fps etc.). |

### Mirror

`setMirror(bool)` flips the **stream** horizontally (default false). Nosmai's own mirror override only
affects its on-screen preview; the streamed texture is always non-mirrored. With `nosmai_camera_sdk`
the preview matches the raw stream (verified on a OnePlus CPH2515 with a second phone as viewer), so the
default needs no flip. Call `setMirror(true)` only if your host mirrors the preview (e.g. native
`NosmaiSDK.MIRROR_ON`). Always confirm with a real viewer using an asymmetric scene. The example has a
"Stream mirror" button for this comparison. Back camera was not tested.

## Install

```yaml
dependencies:
  nosmai_webrtc_bridge:
    path: ../nosmai_webrtc_bridge
  flutter_webrtc: 1.6.0
  nosmai_camera_sdk: ^3.0.6
```

The host app bundles the Nosmai AAR (the plugin only declares it `compileOnly`). Put it at
`android/app/libs/nosmai-release.aar` (the file name `nosmai_camera_sdk` expects) and add:

```kotlin
// android/app/build.gradle.kts
dependencies { implementation(files("libs/nosmai-release.aar")) }
```

Set `applicationId` to the id your Nosmai licence key is bound to. Permissions CAMERA, RECORD_AUDIO and
INTERNET are the host's responsibility.

## Example

`example/` is a sender: Nosmai preview, a plain WebSocket signalling client, an `RTCPeerConnection`, a
top banner `SEND x fps | VIEWER GETS y fps`, and Nosmai output fps. It talks to the signalling server and
viewer in `web_rtc_testapp/server` (`node server.js`); a viewer reports its fps back over signalling.

## Notes

- `flutter_webrtc` is pinned to 1.6.0 because the bridge wraps the native track with
  `MediaStreamTrackNative` (an implementation import). Re-verify when upgrading.
- The Android `compileOnly` WebRTC dependency matches flutter_webrtc 1.6.0 (`webrtc-sdk 144.7559.09`).
