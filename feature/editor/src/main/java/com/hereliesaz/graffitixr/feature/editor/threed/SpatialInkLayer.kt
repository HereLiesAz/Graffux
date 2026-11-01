@file:Suppress("MagicNumber", "FunctionNaming", "TooGenericExceptionCaught")

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
import com.hereliesaz.graffitixr.common.mesh.SpatialStroke

/**
 * A 3D strokes layer in the canvas stack (spec/3d-space.md, "Render path"): the depth-tested GLES
 * tube renderer drawing into a transparent [TextureView]. A TextureView composites in view order,
 * unlike a SurfaceView, so the ink sits between the paint layers above and below it. [clip] is the
 * layer's full clip matrix (`SceneMath.clipMatrix`); a new one redraws without re-tessellating.
 */
@Composable
fun SpatialInkLayer(strokes: List<SpatialStroke>, clip: FloatArray, modifier: Modifier = Modifier) {
    val host = remember { GlTextureHost(SpatialStrokeRenderer(clearColor = floatArrayOf(0f, 0f, 0f, 0f))) }
    DisposableEffect(host) { onDispose { host.release() } }
    LaunchedEffect(strokes, clip) {
        host.renderer.setStrokes(strokes)
        host.renderer.mvp = clip
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
 * Minimal EGL host for a [GLSurfaceView.Renderer][android.opengl.GLSurfaceView.Renderer] on a
 * TextureView: one GL thread, an ES 3 context with a depth buffer, render on request.
 */
internal class GlTextureHost(val renderer: SpatialStrokeRenderer) : TextureView.SurfaceTextureListener {
    private val thread = HandlerThread("SpatialInkGl").also { it.start() }
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
            Log.e(TAG, "3D ink frame failed", t)
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
        Log.e(TAG, "3D ink layer could not start GLES 3", t)
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
        const val TAG = "SpatialInkLayer"
    }
}
