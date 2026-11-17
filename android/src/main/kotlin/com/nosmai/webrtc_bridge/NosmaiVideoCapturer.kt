package com.nosmai.webrtc_bridge

import android.content.Context
import android.graphics.Matrix
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.SystemClock
import android.util.Log
import com.nosmai.effect.api.NosmaiSDK
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.TextureBufferImpl
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.YuvConverter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * WebRTC [VideoCapturer] fed by Nosmai's processed GPU texture.
 *
 * Producer:  Nosmai GL worker -> [NosmaiSDK.TextureFrameCallback] (texture id in the shared EGL group).
 * Consumer:  one glBlitFramebuffer into a helper-owned 3-slot ring on WebRTC's GL thread,
 *            wrapped in a [TextureBufferImpl] and handed to [CapturerObserver.onFrameCaptured].
 *
 * Why blit instead of handing Nosmai's texture straight to the encoder: the encoder may hold a
 * frame across several vsyncs while Nosmai wants its producer slot back immediately.
 *
 * INVARIANT: every producer slot is released exactly once (success, drop, error, stopped) via
 * [NosmaiSDK.releaseStreamSlot], otherwise the producer ring starves and the stream freezes.
 */
class NosmaiVideoCapturer : VideoCapturer {

    private var helper: SurfaceTextureHelper? = null
    private var observer: CapturerObserver? = null
    private var yuvConverter: YuvConverter? = null

    @Volatile private var capturing = false
    @Volatile private var released = false

    // Single-flight: if the previous blit is still running we DROP instead of queueing, so
    // Nosmai's GL worker is never back-pressured.
    private val inFlight = AtomicBoolean(false)

    private val tex = IntArray(RING)
    private val fbo = IntArray(RING)
    private val busy = Array(RING) { AtomicBoolean(false) }
    private val busySince = LongArray(RING)
    private var ringW = 0
    private var ringH = 0
    private var srcFbo = 0

    /**
     * Horizontal flip of the STREAM. Nosmai's mirror setting only affects its on-screen preview; the
     * streaming texture is always non-mirrored. The app sets this to follow the preview so that
     * what the viewer sees is exactly what the sender sees on screen.
     */
    @Volatile var mirrorX = false

    /** Rotation metadata stamped on frames. 0 is correct: Nosmai's streaming pass is already upright portrait. */
    @Volatile var rotationDegrees = 0

    // ---- Counters read by the metrics collector (monotonic) -----------------------------
    /** Frames Nosmai delivered to us = Nosmai processed OUTPUT frame count. */
    val nosmaiFrames = AtomicLong(0)
    /** Frames successfully handed to WebRTC. */
    val pushedFrames = AtomicLong(0)
    /** Frames dropped by the bridge (single-flight busy, ring busy, stopped). */
    val droppedFrames = AtomicLong(0)
    /** Sum of blit+handoff time on the GL thread, nanoseconds. */
    val blitNanos = AtomicLong(0)
    val firstPushElapsedMs = AtomicLong(0)
    @Volatile var lastWidth = 0
        private set
    @Volatile var lastHeight = 0
        private set

    // ---- VideoCapturer ------------------------------------------------------------------
    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        applicationContext: Context,
        capturerObserver: CapturerObserver,
    ) {
        helper = surfaceTextureHelper
        observer = capturerObserver
        // YuvConverter allocates GL objects, so it must be built on the helper's GL thread.
        // It is only needed if something downstream calls toI420() (software encoder / fallback).
        val latch = CountDownLatch(1)
        surfaceTextureHelper.handler.post {
            try { yuvConverter = YuvConverter() } catch (t: Throwable) {
                Log.e(TAG, "YuvConverter init failed", t)
            } finally { latch.countDown() }
        }
        latch.await(2, TimeUnit.SECONDS)
        if (yuvConverter == null) throw IllegalStateException("YuvConverter unavailable")
    }

    /**
     * width/height/fps are advisory: Nosmai decides the produced texture size (720x1280 portrait).
     * Use VideoSource.adaptOutputFormat() to scale down for lower-resolution test runs.
     */
    override fun startCapture(width: Int, height: Int, framerate: Int) {
        val obs = observer ?: throw IllegalStateException("initialize() not called")
        capturing = true
        obs.onCapturerStarted(true)
        // TWO independent native gates. Arming only one yields ZERO frames and no error:
        //  1) render mode must permit a streaming pass, 2) texture callback must be registered.
        // DUAL_OUTPUT because Nosmai also owns the visible on-screen preview.
        NosmaiSDK.setRenderMode(NosmaiSDK.RenderMode.DUAL_OUTPUT)
        NosmaiSDK.setTextureFrameCallback(NosmaiSDK.TextureFrameCallback { texId, w, h, tsNs, _ ->
            // Runs on the Nosmai GL worker: hand off and return, never block.
            nosmaiFrames.incrementAndGet()
            if (!capturing || released) {
                droppedFrames.incrementAndGet()
                NosmaiSDK.releaseStreamSlot(texId)
            } else {
                push(texId, w, h, tsNs)
            }
        })
        Log.i(TAG, "startCapture advisory ${width}x$height@$framerate")
    }

    override fun stopCapture() {
        capturing = false
        try { NosmaiSDK.setTextureFrameCallback(null) } catch (_: Throwable) {}
        try { NosmaiSDK.setRenderMode(NosmaiSDK.RenderMode.PREVIEW_ONLY) } catch (_: Throwable) {}
        try { observer?.onCapturerStopped() } catch (_: Throwable) {}
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        // Output size is owned by Nosmai; nothing to do.
    }

    override fun dispose() {
        if (capturing) stopCapture()
        released = true
        val h = helper ?: return
        val latch = CountDownLatch(1)
        try {
            h.handler.post {
                try {
                    if (tex[0] != 0) {
                        GLES20.glDeleteTextures(RING, tex, 0)
                        GLES20.glDeleteFramebuffers(RING, fbo, 0)
                        for (i in 0 until RING) { tex[i] = 0; fbo[i] = 0; busy[i].set(false) }
                    }
                    if (srcFbo != 0) {
                        GLES20.glDeleteFramebuffers(1, intArrayOf(srcFbo), 0)
                        srcFbo = 0
                    }
                    ringW = 0; ringH = 0
                    yuvConverter?.release()
                    yuvConverter = null
                } catch (t: Throwable) {
                    Log.w(TAG, "dispose GL cleanup", t)
                } finally { latch.countDown() }
            }
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: Throwable) {}
        helper = null
        observer = null
        Log.i(TAG, "disposed (nosmai=${nosmaiFrames.get()} pushed=${pushedFrames.get()} dropped=${droppedFrames.get()})")
    }

    override fun isScreencast(): Boolean = false

    // ---- Texture hand-off ---------------------------------------------------------------
    private fun push(srcTexId: Int, width: Int, height: Int, timestampNs: Long) {
        val h = helper
        val obs = observer
        if (h == null || obs == null || !inFlight.compareAndSet(false, true)) {
            droppedFrames.incrementAndGet()
            NosmaiSDK.releaseStreamSlot(srcTexId)
            return
        }
        val posted = h.handler.post {
            val t0 = System.nanoTime()
            var pushed = false
            try {
                if (released) return@post
                ensureRing(width, height)
                val slot = acquireSlot()
                if (slot < 0) return@post

                if (srcFbo == 0) {
                    val f = IntArray(1)
                    GLES20.glGenFramebuffers(1, f, 0)
                    srcFbo = f[0]
                }
                GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, srcFbo)
                GLES30.glFramebufferTexture2D(
                    GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, srcTexId, 0)
                GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, fbo[slot])
                // Y-flipped blit: GL textures are bottom-up, WebRTC expects top-down. The optional X
                // flip (mirrorX) is applied here by reversing the destination x range.
                val dx0 = if (mirrorX) width else 0
                val dx1 = if (mirrorX) 0 else width
                GLES30.glBlitFramebuffer(0, 0, width, height, dx0, height, dx1, 0,
                    GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_NEAREST)
                GLES30.glFramebufferTexture2D(
                    GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, 0, 0)
                GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
                GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
                GLES20.glFlush()

                val s = slot
                val buffer = TextureBufferImpl(
                    width, height, VideoFrame.TextureBuffer.Type.RGB, tex[s], Matrix(),
                    h.handler, yuvConverter,
                ) { busySince[s] = 0; busy[s].set(false) }
                val frame = VideoFrame(buffer, rotationDegrees, timestampNs)
                try {
                    obs.onFrameCaptured(frame)
                    pushed = true
                } finally {
                    frame.release() // the encoder retains its own reference if it needs one
                }
            } catch (t: Throwable) {
                Log.e(TAG, "push failed", t)
            } finally {
                if (pushed) {
                    pushedFrames.incrementAndGet()
                    firstPushElapsedMs.compareAndSet(0L, SystemClock.elapsedRealtime())
                    lastWidth = width; lastHeight = height
                    blitNanos.addAndGet(System.nanoTime() - t0)
                } else {
                    droppedFrames.incrementAndGet()
                }
                inFlight.set(false)
                NosmaiSDK.releaseStreamSlot(srcTexId) // exactly once, on every path
            }
        }
        if (!posted) {
            // Helper thread is gone: the posted block will never run, so release here.
            droppedFrames.incrementAndGet()
            inFlight.set(false)
            NosmaiSDK.releaseStreamSlot(srcTexId)
        }
    }

    private fun ensureRing(width: Int, height: Int) {
        if (ringW == width && ringH == height && tex[0] != 0) return
        if (tex[0] != 0) {
            GLES20.glDeleteTextures(RING, tex, 0)
            GLES20.glDeleteFramebuffers(RING, fbo, 0)
        }
        GLES20.glGenTextures(RING, tex, 0)
        GLES20.glGenFramebuffers(RING, fbo, 0)
        for (i in 0 until RING) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height,
                0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, tex[i], 0)
            busy[i].set(false)
            busySince[i] = 0
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        ringW = width; ringH = height
        Log.i(TAG, "ring created ${width}x$height")
    }

    /** Reclaims a slot the encoder never released, so one lost release can't shrink the ring forever. */
    private fun acquireSlot(): Int {
        val now = SystemClock.uptimeMillis()
        for (i in 0 until RING) {
            if (busy[i].compareAndSet(false, true)) { busySince[i] = now; return i }
        }
        var oldest = -1
        var oldestSince = Long.MAX_VALUE
        for (i in 0 until RING) {
            val since = busySince[i]
            if (since != 0L && now - since > SLOT_STALL_MS && since < oldestSince) {
                oldestSince = since; oldest = i
            }
        }
        if (oldest >= 0) {
            Log.w(TAG, "reclaiming stalled slot $oldest (${now - oldestSince}ms)")
            busySince[oldest] = now
        }
        return oldest
    }

    private companion object {
        const val TAG = "NosmaiVideoCapturer"
        const val RING = 3
        const val SLOT_STALL_MS = 500L
    }
}
