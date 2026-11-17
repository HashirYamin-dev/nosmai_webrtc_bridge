import 'dart:async';
import 'dart:developer' as developer;

import 'package:flutter/services.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart' as rtc;
// MediaStreamTrackNative is not exported from flutter_webrtc.dart: wrapping a natively-minted
// track id has no supported public constructor, so the implementation path is imported on
// purpose. Kept HERE (plugin plumbing) so no consumer has to know about it.
// ignore: implementation_imports
import 'package:flutter_webrtc/src/native/media_stream_track_impl.dart' show MediaStreamTrackNative;

/// A Nosmai-backed local video track plus the stream it belongs to. Use with a normal
/// flutter_webrtc peer connection: `pc.addTrack(v.track, v.stream)`.
class NosmaiVideoTrack {
  const NosmaiVideoTrack(this.stream, this.track);
  final rtc.MediaStream stream;
  final rtc.MediaStreamTrack track;
}

/// Raw monotonic counters from the native capturer.
class NosmaiFrameCounters {
  const NosmaiFrameCounters({
    required this.nosmaiFrames,
    required this.pushedFrames,
    required this.droppedFrames,
    required this.blitNanos,
    required this.width,
    required this.height,
  });

  /// Frames Nosmai delivered = Nosmai processed OUTPUT frames.
  final int nosmaiFrames;

  /// Frames handed to WebRTC.
  final int pushedFrames;

  /// Frames dropped by the bridge before WebRTC (single-flight busy, ring busy, stopped).
  final int droppedFrames;

  /// Total GL-thread blit+handoff time, nanoseconds.
  final int blitNanos;
  final int width;
  final int height;
}

/// Per-interval rates derived from two [NosmaiFrameCounters] samples.
class NosmaiFrameStats {
  const NosmaiFrameStats({
    required this.nosmaiOutputFps,
    required this.pushedFps,
    required this.droppedInInterval,
    required this.blitMsPerFrame,
    required this.width,
    required this.height,
  });

  /// Nosmai processed output FPS (rate of the native texture callback).
  final double nosmaiOutputFps;

  /// FPS actually handed to WebRTC.
  final double pushedFps;
  final int droppedInInterval;

  /// Average GL-thread cost per pushed frame, or null if nothing was pushed.
  final double? blitMsPerFrame;
  final int width;
  final int height;
}

/// Nosmai <-> flutter_webrtc bridge (Android).
///
/// Exposes Nosmai's processed GPU texture as a normal flutter_webrtc video track. Your app owns the
/// `RTCPeerConnection`, signalling, ICE/TURN, audio and reconnects.
///
/// ## The model
///
/// **Nosmai owns the camera and the preview. WebRTC only encodes and transports.**
/// Mount `NosmaiCameraPreview` (nosmai_camera_sdk) and do NOT call `getUserMedia` for video: a
/// second camera means a second Nosmai pipeline on the one GL worker and a large fps loss.
///
/// ```dart
/// // 1. BEFORE Nosmai initializes (joins flutter_webrtc's EGL share group)
/// await NosmaiWebRtcBridge.registerShareContext();
/// await NosmaiFlutter.initialize(licenseKey);
///
/// // 2. Mount NosmaiCameraPreview. 3. Get the track and use your own peer connection:
/// final v = await NosmaiWebRtcBridge.createVideoTrack();
/// await pc.addTrack(v.track, v.stream);
/// // ...your own offer/answer/ICE over your own signalling...
///
/// // 4. Teardown (before closing the peer connection)
/// await NosmaiWebRtcBridge.stopStreaming();
/// ```
class NosmaiWebRtcBridge {
  NosmaiWebRtcBridge._();

  static const MethodChannel _channel = MethodChannel('nosmai_webrtc_bridge');

  /// Joins flutter_webrtc's EGL share group so Nosmai's output textures are readable by the
  /// encoder. **Must be awaited before `NosmaiFlutter.initialize()`**: the share handle is consumed
  /// when Nosmai's GL context is created, and a later call is ignored (remote shows black, no
  /// error). Idempotent. Returns the native EGL handle, or null on failure.
  static Future<int?> registerShareContext() async {
    try {
      return await _channel.invokeMethod<int>('registerShareContext');
    } catch (e, s) {
      developer.log('[NosmaiWebRtcBridge] registerShareContext failed', error: e, stackTrace: s);
      return null;
    }
  }

  /// Creates the video track carrying Nosmai's processed output.
  ///
  /// [width]/[height]/[fps] optionally downscale (Nosmai produces 720x1280 portrait); leave 0 for
  /// no adaptation. Requires the Nosmai camera to be running (a mounted `NosmaiCameraPreview`).
  /// Throws [StateError] if the native side cannot start.
  static Future<NosmaiVideoTrack> createVideoTrack({int width = 0, int height = 0, int fps = 0}) async {
    // flutter_webrtc builds its PeerConnectionFactory lazily on its first call. On a cold start it
    // is null and the native mint fails, so provoke it with a throwaway stream.
    final warmup = await rtc.createLocalMediaStream('nosmai-warmup');
    await warmup.dispose();

    final r = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'startStreaming', {'width': width, 'height': height, 'fps': fps});
    if (r == null) {
      throw StateError('Nosmai WebRTC bridge failed to start. Is NosmaiCameraPreview mounted? See native log.');
    }
    final trackId = r['trackId'].toString();
    final label = r['label'].toString();
    final track = MediaStreamTrackNative(trackId, label, 'video', true, '');
    final stream = await rtc.createLocalMediaStream(label);
    try {
      await stream.addTrack(track);
    } catch (e) {
      // Not fatal: pc.addTrack(track, stream) only needs the stream id.
      developer.log('[NosmaiWebRtcBridge] stream.addTrack skipped', error: e);
    }
    return NosmaiVideoTrack(stream, track);
  }

  /// Stops streaming and releases the track and its GL resources. Safe when not streaming.
  /// Call BEFORE closing the peer connection.
  static Future<void> stopStreaming() async {
    try {
      await _channel.invokeMethod('stopStreaming');
    } catch (e) {
      developer.log('[NosmaiWebRtcBridge] stopStreaming failed', error: e);
    }
  }

  static Future<bool> isStreaming() async {
    try {
      return await _channel.invokeMethod<bool>('isStreaming') ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Horizontally flips the STREAM (not the preview). Default: false.
  ///
  /// Nosmai's own mirror override (`NosmaiSDK.setMirrorOverride(MIRROR_ON)`) only affects its
  /// on-screen preview; the streamed texture is always non-mirrored. With `nosmai_camera_sdk` the
  /// preview matches the raw stream (verified on a device), so the default needs no flip. If your
  /// host mirrors the preview (e.g. native `MIRROR_ON`), call `setMirror(true)` so the remote viewer
  /// sees exactly what the sender sees on screen. Takes effect on the next frame; latched across
  /// streams. Always confirm with a viewer: compare an asymmetric scene on both screens.
  static Future<void> setMirror(bool mirror) async {
    try {
      await _channel.invokeMethod('setMirror', {'mirror': mirror});
    } catch (e) {
      developer.log('[NosmaiWebRtcBridge] setMirror failed', error: e);
    }
  }

  /// Rotation metadata (0/90/180/270) stamped on frames; the receiver applies it. Default 0 is
  /// correct on Android: Nosmai's streaming pass already delivers upright portrait frames.
  static Future<void> setRotation(int degrees) async {
    try {
      await _channel.invokeMethod('setRotation', {'degrees': degrees});
    } catch (e) {
      developer.log('[NosmaiWebRtcBridge] setRotation failed', error: e);
    }
  }

  /// Raw counters, or null when not streaming.
  static Future<NosmaiFrameCounters?> getCounters() async {
    try {
      final m = await _channel.invokeMethod<Map<dynamic, dynamic>>('getCounters');
      if (m == null || m.isEmpty) return null;
      int i(String k) => (m[k] as num).toInt();
      return NosmaiFrameCounters(
        nosmaiFrames: i('nosmaiFrames'),
        pushedFrames: i('pushedFrames'),
        droppedFrames: i('droppedFrames'),
        blitNanos: i('blitNanos'),
        width: i('width'),
        height: i('height'),
      );
    } catch (_) {
      return null;
    }
  }

  /// Emits per-interval Nosmai output fps, pushed fps, drops and blit cost while streaming.
  static Stream<NosmaiFrameStats> frameStats({Duration interval = const Duration(seconds: 1)}) async* {
    NosmaiFrameCounters? prev;
    DateTime? prevAt;
    while (true) {
      await Future<void>.delayed(interval);
      final now = DateTime.now();
      final c = await getCounters();
      if (c == null) {
        prev = null;
        prevAt = null;
        continue;
      }
      if (prev != null && prevAt != null) {
        final dt = now.difference(prevAt).inMicroseconds / 1e6;
        if (dt > 0) {
          final pushed = c.pushedFrames - prev.pushedFrames;
          yield NosmaiFrameStats(
            nosmaiOutputFps: (c.nosmaiFrames - prev.nosmaiFrames) / dt,
            pushedFps: pushed / dt,
            droppedInInterval: c.droppedFrames - prev.droppedFrames,
            blitMsPerFrame: pushed > 0 ? (c.blitNanos - prev.blitNanos) / 1e6 / pushed : null,
            width: c.width,
            height: c.height,
          );
        }
      }
      prev = c;
      prevAt = now;
    }
  }

  static Future<String?> getPlatformVersion() async {
    try {
      return await _channel.invokeMethod<String>('getPlatformVersion');
    } catch (_) {
      return null;
    }
  }
}
