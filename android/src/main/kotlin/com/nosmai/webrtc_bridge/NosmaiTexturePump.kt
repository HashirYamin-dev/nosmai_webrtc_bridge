package com.nosmai.webrtc_bridge

import android.content.Context
import android.util.Log
import com.cloudwebrtc.webrtc.FlutterWebRTCPlugin
import com.cloudwebrtc.webrtc.StateProvider
import com.cloudwebrtc.webrtc.utils.EglUtils
import com.cloudwebrtc.webrtc.video.LocalVideoTrack
import com.nosmai.effect.api.NosmaiSDK
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID

/**
 * Publishes Nosmai's processed GPU texture as a flutter_webrtc local video track.
 *
 * Model: Nosmai owns the camera AND the on-screen preview (via nosmai_camera_sdk); WebRTC only
 * encodes and transports. WebRTC must NEVER open a camera: a second capture path means a second
 * Nosmai pipeline contending for the one GL worker.
 */
object NosmaiTexturePump {
    private const val TAG = "NosmaiTexturePump"

    // Share-context registration is process-wide and one-shot: the handle is consumed when
    // Nosmai's GL context is constructed, and a later call is rejected.
    private var shareCtxRegistered = false
    private var cachedHandle: Long = 0

    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var helper: SurfaceTextureHelper? = null
    private var capturer: NosmaiVideoCapturer? = null

    /** Latched so the value survives between streams. */
    @Volatile var mirrorX = false
        set(v) { field = v; capturer?.mirrorX = v }

    @Volatile var rotationDegrees = 0
        set(v) { field = v; capturer?.rotationDegrees = v }

    @Volatile var streaming = false
        private set

    /**
     * Joins flutter_webrtc's EGL share group. MUST run BEFORE Nosmai initializes, otherwise Nosmai
     * gets a standalone GL context, its texture ids are meaningless to the encoder, and the remote
     * shows black with no error anywhere.
     */
    fun registerShareContext(): Long? {
        if (shareCtxRegistered) return cachedHandle
        return try {
            // setAgoraShareContext is native; force the library up first.
            System.loadLibrary("nosmai")
            val ctx = EglUtils.getRootEglBaseContext()
                ?: run { Log.e(TAG, "root EglBase context is null"); return null }
            val handle = ctx.nativeEglContext
            // The name is historical; the native side just uses it as the eglCreateContext share arg.
            NosmaiSDK.setAgoraShareContext(handle)
            shareCtxRegistered = true
            cachedHandle = handle
            Log.i(TAG, "EGL share context registered handle=$handle")
            handle
        } catch (t: Throwable) {
            Log.e(TAG, "registerShareContext failed: ${t.message}", t)
            null
        }
    }

    /**
     * @param width/height/fps optional downscale via VideoSource.adaptOutputFormat (0 = no adaptation).
     * @return the native track id, or null on failure (see logcat).
     */
    fun startStreaming(appContext: Context, width: Int, height: Int, fps: Int): String? {
        if (streaming) {
            Log.w(TAG, "startStreaming while streaming; stopping first")
            stopStreaming()
        }
        try {
            val plugin = FlutterWebRTCPlugin.sharedSingleton
                ?: run { Log.e(TAG, "FlutterWebRTCPlugin.sharedSingleton is null"); return null }
            // Created lazily by flutter_webrtc's first "initialize" call, so Dart must have made
            // some WebRTC call before this (the Dart side does a throwaway createLocalMediaStream).
            val factory = plugin.peerConnectionFactory
                ?: run { Log.e(TAG, "peerConnectionFactory is null (WebRTC not initialized)"); return null }
            val rootCtx = EglUtils.getRootEglBaseContext()
                ?: run { Log.e(TAG, "root EglBase context is null"); return null }

            val src = factory.createVideoSource(false)
            if (width > 0 && height > 0 && fps > 0) src.adaptOutputFormat(width, height, fps)
            val id = "nosmai-" + UUID.randomUUID().toString()
            val vt = factory.createVideoTrack(id, src)

            // flutter_webrtc resolves trackId against its internal registry but exposes no getter
            // for the handler (field is private), so reflect the FIELD only and call the public
            // StateProvider.putLocalTrack.
            val f = FlutterWebRTCPlugin::class.java.getDeclaredField("methodCallHandler")
            f.isAccessible = true
            val handler = f.get(plugin) ?: run { Log.e(TAG, "methodCallHandler is null"); return null }
            (handler as StateProvider).putLocalTrack(id, LocalVideoTrack(vt))

            // PER-STREAM helper, never kept across stop/start: a lingering helper's GL thread keeps
            // contending for the single Nosmai worker (the "second go-live freezes" bug).
            val sth = SurfaceTextureHelper.create("NosmaiWebRtc", rootCtx)
                ?: run { Log.e(TAG, "SurfaceTextureHelper.create returned null"); return null }

            val cap = NosmaiVideoCapturer()
            cap.mirrorX = mirrorX
            cap.rotationDegrees = rotationDegrees
            cap.initialize(sth, appContext, src.capturerObserver)

            source = src; track = vt; helper = sth; capturer = cap
            streaming = true
            cap.startCapture(if (width > 0) width else 720, if (height > 0) height else 1280, if (fps > 0) fps else 30)
            Log.i(TAG, "streaming: track $id armed")
            return id
        } catch (t: Throwable) {
            Log.e(TAG, "startStreaming failed: ${t.javaClass.simpleName}: ${t.message}", t)
            stopStreaming()
            return null
        }
    }

    /** Raw monotonic counters; Dart turns them into rates. Empty when not streaming. */
    fun counters(): Map<String, Any> {
        val c = capturer ?: return emptyMap()
        return mapOf(
            "nosmaiFrames" to c.nosmaiFrames.get(),
            "pushedFrames" to c.pushedFrames.get(),
            "droppedFrames" to c.droppedFrames.get(),
            "blitNanos" to c.blitNanos.get(),
            "width" to c.lastWidth,
            "height" to c.lastHeight,
        )
    }

    /** Teardown order: stop PRODUCING (capturer), then the consumer (helper, track, source). */
    fun stopStreaming() {
        streaming = false
        try { capturer?.stopCapture() } catch (_: Throwable) {}
        try { capturer?.dispose() } catch (_: Throwable) {}
        try { helper?.dispose() } catch (_: Throwable) {}
        try { track?.dispose() } catch (_: Throwable) {}
        try { source?.dispose() } catch (_: Throwable) {}
        capturer = null; helper = null; track = null; source = null
        Log.i(TAG, "stopped")
    }
}
