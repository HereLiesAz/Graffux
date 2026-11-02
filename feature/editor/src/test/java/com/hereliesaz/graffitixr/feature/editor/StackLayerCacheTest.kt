@file:Suppress("MagicNumber", "MaxLineLength", "CyclomaticComplexMethod")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.ui.graphics.BlendMode
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.buildLayerTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.random.Random

/**
 * The 2D inactive-layer cache: the split, and that composing from the two renders gives the same
 * pixels as the uncached stack, with the real (Skia) raster pipeline and a blend mode.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StackLayerCacheTest {
    @Test
    fun `the split is by top-level subtree and skips sides that need a backdrop`() {
        val layers = listOf(
            Layer("a", "a"),
            Layer("g", "group", type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP),
            Layer("active", "active", parentId = "g", blendMode = BlendMode.Multiply),
            Layer("b", "b"),
            Layer("c", "c", clipToLayerBelow = true),
        )
        val split = StackLayerCache.split(buildLayerTree(layers), "active")!!
        assertEquals("g", split.activeTopLevelId)
        assertEquals(listOf("a"), split.behind)
        assertEquals(listOf("b", "c"), split.front)
        assertTrue(split.cacheBehind)
        assertFalse("a clipping mask in front needs the layers under it", split.cacheFront)
        val plain = StackLayerCache.split(buildLayerTree(layers.dropLast(1)), "active")!!
        assertTrue(plain.cacheFront)
    }

    private val size = 64

    private fun layerBitmap(rnd: Random): Bitmap {
        val px = IntArray(size * size) {
            val a = listOf(0, 255, rnd.nextInt(256)).random(rnd)
            android.graphics.Color.argb(a, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private class L(val bitmap: Bitmap, val alpha: Int, val mode: android.graphics.BlendMode?)

    /** The view transform the renders are recorded through: zoom and rotation, as on the canvas. */
    private val view = Matrix().apply { setScale(1.5f, 1.5f); postRotate(10f); postTranslate(-4f, 3f) }

    private fun Canvas.drawLayer(l: L) {
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            alpha = l.alpha
            l.mode?.let { blendMode = it }
        }
        save()
        concat(view)
        drawBitmap(l.bitmap, 0f, 0f, p)
        restore()
    }

    private fun Canvas.page() {
        save()
        concat(view)
        drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply { color = 0xFFEEEEE0.toInt() })
        restore()
    }

    @Test
    fun `cached and uncached composites are pixel-equal, including a blend mode`() {
        val rnd = Random(3)
        var worst = 0
        var exactBelowFront = true
        repeat(20) {
            val behind = List(3) { L(layerBitmap(rnd), listOf(255, 128).random(rnd), null) }
            val active = L(layerBitmap(rnd), 255, android.graphics.BlendMode.MULTIPLY) // Compose's Multiply
            val front = List(2) { L(layerBitmap(rnd), listOf(255, 200).random(rnd), null) }
            val out = size * 2

            val uncached = Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888)
            Canvas(uncached).apply {
                drawColor(0xFF202024.toInt())
                page()
                behind.forEach { drawLayer(it) }
                drawLayer(active)
            }
            val uncachedBelowFront = uncached.copy(Bitmap.Config.ARGB_8888, true)
            Canvas(uncached).apply { front.forEach { drawLayer(it) } }

            // The renders: page and behind in one, front in the other, each from transparent.
            val behindRender = Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888)
            Canvas(behindRender).apply { page(); behind.forEach { drawLayer(it) } }
            val frontRender = Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888)
            Canvas(frontRender).apply { front.forEach { drawLayer(it) } }
            val cached = Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888)
            Canvas(cached).apply {
                drawColor(0xFF202024.toInt())
                drawBitmap(behindRender, 0f, 0f, null)
                drawLayer(active)
            }
            // Under the page (where the artboard covers), behind + the blended active layer are exact.
            val pageMask = Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888)
            Canvas(pageMask).page()
            for (y in 0 until out) for (x in 0 until out) {
                if (android.graphics.Color.alpha(pageMask.getPixel(x, y)) == 255 &&
                    cached.getPixel(x, y) != uncachedBelowFront.getPixel(x, y)
                ) {
                    exactBelowFront = false
                }
            }
            Canvas(cached).drawBitmap(frontRender, 0f, 0f, null)
            for (y in 0 until out) for (x in 0 until out) {
                val a = cached.getPixel(x, y)
                val b = uncached.getPixel(x, y)
                for (shift in intArrayOf(0, 8, 16, 24)) worst = maxOf(worst, abs((a ushr shift and 0xFF) - (b ushr shift and 0xFF)))
            }
        }
        assertTrue("behind + active must be bit-exact over the page", exactBelowFront)
        // Source-over is associative, so the front render is the same image; it differs only where
        // Skia's 8-bit pipeline rounds an intermediate write (two translucent front layers: at most
        // 3/255 on a channel, measured). Everything under the front render is bit-exact (above).
        assertTrue("front render differs by $worst", worst <= 3)
    }
}
