package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.SelectionRing
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.SelectionGeometry
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **Liquify obeys the selection.**
 *
 * It did not. Lasso a region, pick Liquify, drag, and the whole layer warped — while the rail said,
 * and `Tool.SELECT`'s own doc still says, that a region "confines every raster tool until it is
 * cleared". The exception was recorded in a comment inside `ImageProcessor` and nowhere a user could
 * see it, and no test covered it because the warp goes through the native pass rather than the tool
 * switch every other tool shares.
 *
 * The native pass is faked here: `bakeLiquify` is what writes the warped pixels back, so a fake that
 * paints the whole bitmap a flat colour stands in for "the warp touched everything". What is under
 * test is not the warp — it is whether the result is confined afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LiquifySelectionTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    private val canvas = IntSize(48, 48)

    /** A SlamManager whose bake repaints the entire layer — the loudest possible "it warped". */
    private fun repaintingSlam(): SlamManager {
        val slam = mockk<SlamManager>(relaxed = true)
        every { slam.bakeLiquify(any()) } answers {
            android.graphics.Canvas(firstArg<Bitmap>()).drawColor(Color.MAGENTA)
        }
        return slam
    }

    private fun liquifyCommand(selection: Selection?) = StrokeCommand(
        path = listOf(Offset(6f, 24f), Offset(42f, 24f)),
        canvasSize = canvas,
        tool = Tool.LIQUIFY,
        brushSize = 12f,
        brushColor = 0,
        intensity = 0.5f,
        selection = selection,
    )

    private fun boxSelection(featherPx: Float = 0f) = Selection(
        rings = listOf(SelectionRing(SelectionGeometry.rectangle(Offset(12f, 12f), Offset(36f, 36f)))),
        canvasSize = canvas,
        featherPx = featherPx,
    )

    @Test
    fun `a liquify stroke does not touch pixels outside the selection`() = runTest {
        val engine = DrawingEngine(repaintingSlam())
        val base = RenderTestBase.filled(48, 48, Color.WHITE)

        val out = engine.applySingleStroke(base, liquifyCommand(boxSelection()))

        // Inside the 12..36 box the warp lands.
        assertEquals("inside the selection must be warped", Color.MAGENTA, out.getPixel(24, 24))
        // Outside it the layer is exactly as it was. This is the whole bug: every one of these was
        // MAGENTA before, because the bake wrote the entire bitmap and nothing masked it back.
        for (p in listOf(2 to 2, 44 to 44, 24 to 4, 4 to 24, 24 to 44)) {
            assertEquals(
                "(${p.first}, ${p.second}) is outside the selection and must be untouched",
                Color.WHITE, out.getPixel(p.first, p.second),
            )
        }
    }

    /** With no selection there is nothing to confine it to, and the whole layer is fair game. */
    @Test
    fun `with no selection the whole layer still warps`() = runTest {
        val engine = DrawingEngine(repaintingSlam())
        val base = RenderTestBase.filled(48, 48, Color.WHITE)

        val out = engine.applySingleStroke(base, liquifyCommand(null))

        for (p in listOf(2 to 2, 24 to 24, 44 to 44)) {
            assertEquals(
                "(${p.first}, ${p.second}) must warp when nothing is selected",
                Color.MAGENTA, out.getPixel(p.first, p.second),
            )
        }
    }

    /** A feathered selection ramps the warp in, rather than cutting it off at a hard boundary. */
    @Test
    fun `a feathered selection softens the edge of the warp`() = runTest {
        val engine = DrawingEngine(repaintingSlam())
        val base = RenderTestBase.filled(48, 48, Color.WHITE)

        val hard = engine.applySingleStroke(base, liquifyCommand(boxSelection(featherPx = 0f)))
        val soft = engine.applySingleStroke(base, liquifyCommand(boxSelection(featherPx = 10f)))

        var differing = 0
        for (y in 0 until 48) for (x in 0 until 48) {
            if (hard.getPixel(x, y) != soft.getPixel(x, y)) differing++
        }
        assertNotEquals("feathering must change the result; nothing differed", 0, differing)
        // And the middle is still overwhelmingly warped — the feather softens the boundary, not the
        // core. Not exactly MAGENTA: a 10 px blur on a 24 px box reaches the centre, so the ramp
        // leaves a little of the white base showing through. Asserting the green channel is what
        // makes this a statement about the warp rather than about the blur kernel's exact shape —
        // magenta has none, the white base is full of it.
        assertTrue(
            "the centre must be warped, not merely tinted; green was ${Color.green(soft.getPixel(24, 24))}",
            Color.green(soft.getPixel(24, 24)) < 64,
        )
    }

    /** Commit and replay must agree, or the layer changes the first time it is undone. */
    @Test
    fun `a liquify stroke commits what it replays`() = runTest {
        val engine = DrawingEngine(repaintingSlam())
        val base = RenderTestBase.filled(48, 48, Color.WHITE)
        val cmd = liquifyCommand(boxSelection(featherPx = 8f))

        val committed = engine.applySingleStroke(base, cmd)
        val replayed = engine.composite(base, listOf(cmd))

        var differing = 0
        for (y in 0 until 48) for (x in 0 until 48) {
            if (committed.getPixel(x, y) != replayed.getPixel(x, y)) differing++
        }
        assertEquals("$differing px differ between commit and replay", 0, differing)
    }
}
