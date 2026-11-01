@file:Suppress("MagicNumber", "FunctionNaming", "TooGenericExceptionCaught", "MaxLineLength", "ReturnCount")

package com.hereliesaz.graffitixr.feature.editor.threed

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The 3D space's depth pass in the canvas stack (spec/3d-space.md, "Per-pixel depth"): the
 * [DepthCompositor] drawing into a transparent [TextureView]. A TextureView composites in view
 * order, unlike a SurfaceView, so the pass sits at its place among the HWUI-drawn layers. A new
 * [scene] redraws; nothing is uploaded that has not changed.
 */
@Composable
fun DepthCompositeLayer(scene: DepthCompositor.Scene, modifier: Modifier = Modifier) {
    val host = remember { GlTextureHost(DepthCompositor()) }
    DisposableEffect(host) { onDispose { host.release() } }
    LaunchedEffect(scene) {
        host.renderer.scene = scene
        host.requestRender()
    }
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                isOpaque = false
                surfaceTextureListener = host
            }
        },
        modifier = modifier,
    )
}

/**
 * The depth pass rendered off screen into a bitmap, for the PNG/PDF export: the same
 * [DepthCompositor] on a pbuffer, read back once. Null where GLES 3 cannot start (tests, a broken
 * driver); the export then draws the members farthest-first instead.
 */
object OffscreenDepthRender {
    fun render(scene: DepthCompositor.Scene, width: Int, height: Int): android.graphics.Bitmap? {
        if (width <= 0 || height <= 0) return null
        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 24, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_NONE,
            )
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            surface = EGL14.eglCreatePbufferSurface(
                display, configs[0], intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0,
            )
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null
            val renderer = DepthCompositor()
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, width, height)
            renderer.scene = scene
            renderer.onDrawFrame(null)
            val pixels = java.nio.ByteBuffer.allocateDirect(width * height * 4).order(java.nio.ByteOrder.nativeOrder())
            android.opengl.GLES30.glReadPixels(0, 0, width, height, android.opengl.GLES30.GL_RGBA, android.opengl.GLES30.GL_UNSIGNED_BYTE, pixels)
            // GL rows run bottom-up; a bitmap's top-down. Both are premultiplied RGBA.
            val row = width * 4
            val flipped = java.nio.ByteBuffer.allocateDirect(width * height * 4).order(java.nio.ByteOrder.nativeOrder())
            val line = ByteArray(row)
            for (y in height - 1 downTo 0) {
                pixels.position(y * row)
                pixels.get(line)
                flipped.put(line)
            }
            flipped.rewind()
            android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(flipped)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Off-screen depth pass failed", t)
            null
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
    }

    private const val TAG = "OffscreenDepthRender"
}

/**
 * Minimal EGL host for a [GLSurfaceView.Renderer][android.opengl.GLSurfaceView.Renderer] on a
 * TextureView: one GL thread, an ES 3 context with a depth buffer, render on request.
 */
internal class GlTextureHost<R : android.opengl.GLSurfaceView.Renderer>(val renderer: R) : TextureView.SurfaceTextureListener {
    private val thread = HandlerThread("Scene3DGl").also { it.start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    @Volatile private var released = false

    fun requestRender() {
        if (released) return
        handler.removeCallbacks(draw)
        handler.post(draw)
    }

    private val draw = Runnable {
        if (surface == EGL14.EGL_NO_SURFACE) return@Runnable
        try {
            renderer.onDrawFrame(null)
            EGL14.eglSwapBuffers(display, surface)
        } catch (t: Throwable) {
            Log.e(TAG, "3D frame failed", t)
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        handler.post {
            if (!setUp(texture)) return@post
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, width, height)
            draw.run()
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        handler.post {
            if (surface == EGL14.EGL_NO_SURFACE) return@post
            renderer.onSurfaceChanged(null, width, height)
            draw.run()
        }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        handler.post { tearDown() }
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    fun release() {
        released = true
        handler.post {
            tearDown()
            thread.quitSafely()
        }
    }

    private fun setUp(texture: SurfaceTexture): Boolean = try {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 24, EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        surface = EGL14.eglCreateWindowSurface(display, configs[0], texture, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }
        true
    } catch (t: Throwable) {
        Log.e(TAG, "3D layer could not start GLES 3", t)
        tearDown()
        false
    }

    private fun tearDown() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        surface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    private companion object {
        const val TAG = "GlTextureHost"
    }
}
