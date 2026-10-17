package com.hereliesaz.graffitixr.nativebridge

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kotlin face of the native Liquify warper (`ImageWarper.cpp`, GLES3, no OpenCV).
 *
 * Every call is marshalled onto a dedicated headless GL thread ([LiquifyGlContext]) because
 * `ImageWarper` issues raw GLES3 calls (shader compilation, an FBO, `glReadPixels`) that are only
 * legal with a context current on the calling thread, and Graffux has no other GL surface Liquify
 * could piggyback on.
 */
@Singleton
class LiquifyEngine @Inject constructor() {
    init {
        NativeLibLoader.loadAll()
    }

    // Lazily created on first Liquify use and torn down in destroy(). A named Lazy (not a bare
    // `by lazy` property) so destroy() can check liquifyGlLazy.isInitialized() without forcing
    // creation just to tear down something that was never touched.
    private val liquifyGlLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LiquifyGlContext(onContextReady = {
            nativeInitImageWarper()
            nativeInitGl()
        })
    }
    private val liquifyGl by liquifyGlLazy

    fun prepareLiquify(bitmap: Bitmap) = liquifyGl.runOnGlThread { nativePrepareLiquify(bitmap) }

    fun applyLiquify(stroke: FloatArray, brushSize: Float, intensity: Float) =
        liquifyGl.runOnGlThread { nativeApplyLiquify(stroke, brushSize, intensity) }

    fun bakeLiquify(outBitmap: Bitmap) = liquifyGl.runOnGlThread { nativeBakeLiquify(outBitmap) }

    /**
     * Frees the native warper and stops the GL thread. This engine is a singleton that outlives the
     * editor ViewModel whose onCleared() calls this, so a later Liquify session starts a fresh
     * thread and context. nativeDestroy() null-checks before deleting, so it is safe to call
     * whether or not Liquify was ever used.
     */
    fun destroy() {
        nativeDestroy()
        if (liquifyGlLazy.isInitialized()) liquifyGl.release()
    }

    private external fun nativeInitImageWarper()
    private external fun nativeInitGl()
    private external fun nativeDestroy()
    private external fun nativePrepareLiquify(bitmap: Bitmap)
    private external fun nativeApplyLiquify(stroke: FloatArray, brushSize: Float, intensity: Float)
    private external fun nativeBakeLiquify(outBitmap: Bitmap)
}
