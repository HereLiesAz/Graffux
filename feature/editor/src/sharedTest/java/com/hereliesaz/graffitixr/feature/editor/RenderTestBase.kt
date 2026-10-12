package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import io.mockk.every
import io.mockk.mockkObject

/**
 * Shared setup for the tests that run real Android graphics under Robolectric.
 *
 * [ImageProcessor] loads OpenCV and the app's own C++ engine in its object initialiser and throws
 * when it cannot, so merely *touching* it off-device kills the test. Stubbing the loader is what
 * makes this whole tier of tests possible — and it is honest, because nothing exercised here goes
 * near a native symbol: the selection mask, the warp and the flood fill are all pure
 * android.graphics.
 */
internal object RenderTestBase {
    fun stubNativeLibs() {
        mockkObject(NativeLibLoader)
        every { NativeLibLoader.loadAll() } returns Unit
    }

    /** A bitmap filled with [color]. */
    fun filled(w: Int, h: Int, color: Int = Color.WHITE): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    /** Alpha of the pixel at ([x], [y]). */
    fun alphaAt(b: Bitmap, x: Int, y: Int): Int = Color.alpha(b.getPixel(x, y))
}
