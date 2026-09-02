package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.Dab
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [StampBrushRenderer] against real `android.graphics` — no test at all existed for this file before,
 * in a codebase that otherwise specifically invested in this render tier
 * ([SelectionMaskRenderTest], [ImageWarpRenderTest]) to catch exactly this class of bug: a swapped
 * argument order or a sign error that a pure-logic test, or no test, would never see.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StampBrushRendererTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    private val brush = AzphaltBrush(name = "test", hardness = 1f)

    private fun isOpaque(b: Bitmap, x: Int, y: Int) = Color.alpha(b.getPixel(x, y)) > 200

    @Test
    fun `a generated round tip paints an opaque disc at the dab centre and stays transparent past its radius`() {
        val canvas = RenderTestBase.filled(40, 40, Color.TRANSPARENT)
        val red = 0xFFFF0000.toInt()
        StampBrushRenderer.paintDabs(
            Canvas(canvas),
            dabs = listOf(Dab(x = 20f, y = 20f, radius = 8f, alpha = 1f, angleDeg = 0f)),
            brush = brush,
            colorArgb = red,
            flow = 1f,
        )
        assertTrue("dab centre should be opaque", isOpaque(canvas, 20, 20))
        // Well outside the radius on every side.
        assertTrue("corner should stay transparent", !isOpaque(canvas, 2, 2))
        assertTrue("corner should stay transparent", !isOpaque(canvas, 38, 38))
        val center = canvas.getPixel(20, 20)
        assertTrue(
            "centre should be tinted red, was ${Integer.toHexString(center)}",
            Color.red(center) > 200 && Color.green(center) < 60 && Color.blue(center) < 60,
        )
    }

    @Test
    fun `a shape stamp is positioned and rotated correctly, not mirrored or offset`() {
        // A stamp that is opaque only on its right half (x >= half-width), transparent on its left.
        // If the translate-scale-rotate-translate chain in paintDabs is composed in the wrong order,
        // or a sign is wrong, this asymmetric shape lands mirrored, offset, or unrotated — a mistake
        // a symmetric round-tip test could never catch.
        val stampSize = 20
        val stamp = Bitmap.createBitmap(stampSize, stampSize, Bitmap.Config.ARGB_8888)
        for (y in 0 until stampSize) {
            for (x in 0 until stampSize) {
                stamp.setPixel(x, y, if (x >= stampSize / 2) Color.WHITE else Color.TRANSPARENT)
            }
        }
        val blue = 0xFF0000FF.toInt()

        fun paint(angleDeg: Float): Bitmap {
            val canvasBmp = RenderTestBase.filled(40, 40, Color.TRANSPARENT)
            StampBrushRenderer.paintDabs(
                Canvas(canvasBmp),
                dabs = listOf(Dab(x = 20f, y = 20f, radius = 8f, alpha = 1f, angleDeg = angleDeg)),
                brush = brush,
                colorArgb = blue,
                flow = 1f,
                stamp = stamp,
            )
            return canvasBmp
        }

        // Unrotated: the opaque half of the stamp is on the right, so the right side of the dab
        // (relative to its centre at x=20) should be tinted and the left side transparent.
        val unrotated = paint(0f)
        assertTrue("right of centre should be opaque at angle 0", isOpaque(unrotated, 26, 20))
        assertTrue("left of centre should stay transparent at angle 0", !isOpaque(unrotated, 14, 20))

        // Rotated 180 degrees: the same opaque half now faces left instead of right.
        val rotated = paint(180f)
        assertTrue("left of centre should be opaque at angle 180", isOpaque(rotated, 14, 20))
        assertTrue("right of centre should stay transparent at angle 180", !isOpaque(rotated, 26, 20))
    }

    @Test
    fun `flow scales dab opacity`() {
        val faint = RenderTestBase.filled(40, 40, Color.TRANSPARENT)
        StampBrushRenderer.paintDabs(
            Canvas(faint),
            dabs = listOf(Dab(x = 20f, y = 20f, radius = 8f, alpha = 1f, angleDeg = 0f)),
            brush = brush,
            colorArgb = Color.BLACK or (0xFF shl 24),
            flow = 0.2f,
        )
        val full = RenderTestBase.filled(40, 40, Color.TRANSPARENT)
        StampBrushRenderer.paintDabs(
            Canvas(full),
            dabs = listOf(Dab(x = 20f, y = 20f, radius = 8f, alpha = 1f, angleDeg = 0f)),
            brush = brush,
            colorArgb = Color.BLACK or (0xFF shl 24),
            flow = 1f,
        )
        val faintAlpha = Color.alpha(faint.getPixel(20, 20))
        val fullAlpha = Color.alpha(full.getPixel(20, 20))
        assertTrue(
            "low flow ($faintAlpha) should be visibly less opaque than full flow ($fullAlpha)",
            faintAlpha < fullAlpha,
        )
    }

    /**
     * The bug this guards against: a dragged stroke's soft edge reading as hard, even though a
     * single tap at the same spot would show it clearly. Sequential `SRC_OVER` of many overlapping
     * dabs at default (tight) spacing compounds via `1 - Π(1 - αᵢ)`, converging to ~full opacity
     * well inside a single dab's own falloff band — see [paintRoundDabsMaxCombined]'s doc comment
     * for the numbers. A point 70% of the way out from a hardness-0.3 dab's centre reads ~43% alpha
     * from one dab alone; the old per-dab-compositing implementation put a dense drag's alpha there
     * at ~91%.
     */
    @Test
    fun `a dragged stroke's soft edge matches a single dab's own falloff, not a hardened build-up`() {
        val soft = brush.copy(hardness = 0.3f, spacing = 0.1f)
        val radius = 40f
        val diameter = radius * 2f
        // Dabs spaced at 10% of diameter along a straight horizontal line -- the tight, overlap-heavy
        // spacing a real drag samples at, and exactly what regressed before this fix.
        val interval = soft.spacing * diameter
        val dabs = (-40..40).map { i ->
            Dab(x = 100f + i * interval, y = 100f, radius = radius, alpha = 1f, angleDeg = 0f, hardness = soft.hardness)
        }
        val canvas = RenderTestBase.filled(200, 200, Color.TRANSPARENT)
        StampBrushRenderer.paintDabs(
            Canvas(canvas),
            dabs = dabs,
            brush = soft,
            colorArgb = Color.BLACK or (0xFF shl 24),
            flow = 1f,
        )

        // Sample perpendicular to the stroke, 70% of the way out to the radius, well clear of the
        // start/end caps (x=100, the middle of the dab run).
        val dragAlpha = Color.alpha(canvas.getPixel(100, (100 + radius * 0.7f).toInt()))

        val singleTap = RenderTestBase.filled(200, 200, Color.TRANSPARENT)
        StampBrushRenderer.paintDabs(
            Canvas(singleTap),
            dabs = listOf(Dab(x = 100f, y = 100f, radius = radius, alpha = 1f, angleDeg = 0f, hardness = soft.hardness)),
            brush = soft,
            colorArgb = Color.BLACK or (0xFF shl 24),
            flow = 1f,
        )
        val tapAlpha = Color.alpha(singleTap.getPixel(100, (100 + radius * 0.7f).toInt()))

        assertTrue(
            "a dragged stroke's edge alpha ($dragAlpha) should be close to a single tap's own " +
                "falloff at the same offset ($tapAlpha), not built up towards full opacity",
            kotlin.math.abs(dragAlpha - tapAlpha) <= 15,
        )
        assertTrue("the edge should still read as visibly translucent, not hardened", dragAlpha < 200)
    }

    /**
     * The bug this guards against: a LIVE stroke (painted incrementally, frame by frame, as more
     * dabs arrive) reading as hard-edged even though the exact same dabs painted all at once (what
     * committing the stroke does) read as soft -- i.e. the live preview visibly not matching what
     * lifting the finger produces. [EditorViewModel.onStrokePoint]'s own doc comment always assumed
     * "re-drawing dabs beyond the count already stamped matches a full re-render"; that stopped
     * being true the moment overlapping dabs started compositing via
     * [StampBrushRenderer]'s own max-combine (each frame's own small new-dab batch got its own
     * separate max-combined composite, `SRC_OVER`'d on top of the previous frame's -- exactly the
     * hardening bug max-combine exists to prevent, just recurring at frame granularity).
     * [StampBrushRenderer.repaintRoundStrokeFromBase] is the fix: this test is the actual
     * "incremental repaint equals one full repaint" property it exists to restore.
     */
    @Test
    fun `repainting a growing stroke frame by frame from its own base matches painting it all at once`() {
        val soft = brush.copy(hardness = 0.3f, spacing = 0.1f)
        val radius = 30f
        val diameter = radius * 2f
        val interval = soft.spacing * diameter
        val allDabs = (0..30).map { i ->
            Dab(x = 50f + i * interval, y = 80f, radius = radius, alpha = 1f, angleDeg = 0f, hardness = soft.hardness)
        }
        val colorArgb = Color.BLACK or (0xFF shl 24)

        // "Lifting the finger": every dab painted in one call, straight onto a transparent canvas.
        val committed = RenderTestBase.filled(400, 200, Color.TRANSPARENT)
        StampBrushRenderer.paintDabs(Canvas(committed), allDabs, soft, colorArgb, flow = 1f)

        // "Dragging": the same dabs arrive a handful at a time, each frame re-painting the WHOLE
        // stroke so far from a pristine (never-drawn-on) base -- exactly what repaintRoundStrokeFromBase
        // is for.
        val base = RenderTestBase.filled(400, 200, Color.TRANSPARENT)
        val live = RenderTestBase.filled(400, 200, Color.TRANSPARENT)
        val liveCanvas = Canvas(live)
        var stamped = 0
        val frameSize = 4
        while (stamped < allDabs.size) {
            stamped = (stamped + frameSize).coerceAtMost(allDabs.size)
            StampBrushRenderer.repaintRoundStrokeFromBase(
                liveCanvas, base, allDabs.subList(0, stamped), soft, colorArgb, flow = 1f,
            )
        }

        // Sample along the dragged path's soft edge, well clear of the start/end caps, where the
        // old per-frame-batch bug showed hardening.
        for (y in intArrayOf(80 - (radius * 0.7f).toInt(), 80 + (radius * 0.7f).toInt())) {
            for (x in intArrayOf(150, 200, 250)) {
                val committedAlpha = Color.alpha(committed.getPixel(x, y))
                val liveAlpha = Color.alpha(live.getPixel(x, y))
                assertTrue(
                    "at ($x,$y): live-drag alpha ($liveAlpha) should match the committed alpha " +
                        "($committedAlpha), not read as hardened from per-frame batching",
                    kotlin.math.abs(liveAlpha - committedAlpha) <= 2,
                )
            }
        }
    }
}
