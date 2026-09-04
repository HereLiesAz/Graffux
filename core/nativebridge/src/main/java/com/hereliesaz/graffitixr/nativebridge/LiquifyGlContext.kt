// FILE: core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/LiquifyGlContext.kt
package com.hereliesaz.graffitixr.nativebridge

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch

/**
 * A dedicated, headless (no visible surface) GLES3 context for Liquify's native `ImageWarper`.
 *
 * `ImageWarper` (core/nativebridge/src/main/cpp/ImageWarper.cpp) issues raw GLES3 calls --
 * shader compilation, an FBO, `glReadPixels` -- and those are only legal with a GL context current
 * on the calling thread. Graffux has no other GL surface Liquify could piggyback on (the only
 * `GLSurfaceView` in the app is the unrelated 3D model viewer, and it's never current while the 2D
 * editor is painting), so this owns one dedicated thread with a 1x1 pbuffer surface kept current
 * for the thread's entire lifetime, and every native Liquify call is marshalled onto it. Without
 * this, `SlamManager.initGl()` — which every other GLES call in this file's Liquify path
 * ultimately depends on — had no thread with a context to ever be called from, so `ImageWarper`
 * stayed permanently uninitialized and every Liquify stroke silently baked nothing.
 *
 * [onContextReady] runs exactly once, on the GL thread, right after the context is first made
 * current — this is where the caller should perform its own one-time GL setup (e.g.
 * `nativeInitGl()`).
 */
internal class LiquifyGlContext(private val onContextReady: () -> Unit) {
    private val thread = HandlerThread("LiquifyGL").apply { start() }
    private val handler = Handler(thread.looper)

    // Touched only on the GL thread -- no lock needed as long as every access happens via
    // runOnGlThread/release, both of which post onto the same single-threaded Handler.
    private var contextReady = false
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    /**
     * Runs [block] on the dedicated GL thread with the pbuffer context current, blocking the
     * caller until it completes. Every native Liquify call this wraps was already synchronous and
     * already invoked from a background dispatcher by its Kotlin callers (`DrawingEngine`,
     * `EditorViewModel`), so this preserves their existing calling contract exactly -- callers
     * don't need their own thread affinity or to know a dedicated GL thread exists.
     */
    fun <T> runOnGlThread(block: () -> T): T {
        val latch = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null
        handler.post {
            try {
                ensureContext()
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun ensureContext() {
        if (contextReady) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "LiquifyGlContext: eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) {
            "LiquifyGlContext: eglInitialize failed"
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0,
        ) { "LiquifyGlContext: eglChooseConfig found no matching ES3 config" }
        val config = configs[0]!!

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "LiquifyGlContext: eglCreateContext failed" }

        // 1x1: the real render target is ImageWarper's own FBO/offscreen texture, sized off the
        // source bitmap. This pbuffer only needs to exist so EGL has a surface to make current.
        val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        surface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttribs, 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "LiquifyGlContext: eglCreatePbufferSurface failed" }

        check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
            "LiquifyGlContext: eglMakeCurrent failed"
        }
        contextReady = true
        onContextReady()
    }

    /** Tears down the EGL context and stops the dedicated thread. Safe to call more than once. */
    fun release() {
        handler.post {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            surface = EGL14.EGL_NO_SURFACE
            contextReady = false
        }
        thread.quitSafely()
    }
}
