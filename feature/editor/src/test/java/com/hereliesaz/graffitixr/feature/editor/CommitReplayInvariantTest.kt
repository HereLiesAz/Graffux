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
 * The invariant three separate bugs violated: **what a stroke commits must equal what replaying it
 * produces.**
 *
 * Every one of those bugs was a commit path maintaining its own composite alongside
 * [DrawingEngine]'s, drifting from it, and only showing up as "the layer changed the first time I
 * undid". Nothing could catch that before, because catching it means compositing real pixels twice
 * and comparing them.
 *
 * This tests the engine's own two entry points against each other — `applySingleStroke` (the commit)
 * and `composite` (the replay). It does not reach the ViewModel, so it cannot prove a *caller* went
 * through them; what it can prove is that the pair agrees, which is what makes routing every caller
 * through them worth doing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CommitReplayInvariantTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    private val engine = DrawingEngine(mockk<SlamManager>(relaxed = true))
    private val canvas = IntSize(48, 48)

    private fun base() = RenderTestBase.filled(48, 48, Color.WHITE)

    private fun selection(featherPx: Float = 0f) = Selection(
        rings = listOf(SelectionRing(SelectionGeometry.rectangle(Offset(12f, 12f), Offset(36f, 36f)))),
        canvasSize = canvas,
        featherPx = featherPx,
    )

    private fun brushStroke(sel: Selection?) = StrokeCommand(
        path = listOf(Offset(6f, 24f), Offset(42f, 24f)),
        canvasSize = canvas,
        tool = Tool.BRUSH,
        brushSize = 8f,
        brushColor = Color.RED,
        intensity = 0.5f,
        selection = sel,
    )

    /** Every pixel identical. */
    private fun assertSamePixels(a: Bitmap, b: Bitmap, what: String) {
        assertEquals("$what: width", a.width, b.width)
        var differing = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (a.getPixel(x, y) != b.getPixel(x, y)) differing++
        }
        assertEquals("$what: $differing pixels differ between commit and replay", 0, differing)
    }

    @Test
    fun `a plain stroke commits what it replays`() = runTest {
        val committed = engine.applySingleStroke(base(), brushStroke(null))
        val replayed = engine.composite(base(), listOf(brushStroke(null)))
        assertSamePixels(committed, replayed, "unclipped brush")
    }

    @Test
    fun `a stroke inside a hard selection commits what it replays`() = runTest {
        val cmd = brushStroke(selection())
        assertSamePixels(
            engine.applySingleStroke(base(), cmd),
            engine.composite(base(), listOf(cmd)),
            "hard-clipped brush",
        )
    }

    @Test
    fun `a stroke inside a FEATHERED selection commits what it replays`() = runTest {
        // The case that broke, three times over.
        val cmd = brushStroke(selection(featherPx = 8f))
        assertSamePixels(
            engine.applySingleStroke(base(), cmd),
            engine.composite(base(), listOf(cmd)),
            "feathered brush",
        )
    }

    @Test
    fun `a feathered selection actually softens the committed edge`() = runTest {
        // Guards the guard: the agreement test above would also pass if feathering did nothing at
        // all. This asserts the two differ from each other, so "they agree" means something.
        val hard = engine.applySingleStroke(base(), brushStroke(selection()))
        val soft = engine.applySingleStroke(base(), brushStroke(selection(featherPx = 8f)))
        var differing = 0
        for (y in 0 until 48) for (x in 0 until 48) {
            if (hard.getPixel(x, y) != soft.getPixel(x, y)) differing++
        }
        assertTrue("feathering should change the result; $differing pixels differed", differing > 0)
    }

    @Test
    fun `colour fill commits what it replays, feathered`() = runTest {
        val cmd = StrokeCommand(
            path = emptyList(),
            canvasSize = canvas,
            tool = Tool.FILL,
            brushSize = 0f,
            brushColor = Color.BLUE,
            intensity = 1f,
            selection = selection(featherPx = 6f),
            fillSelection = true,
        )
        assertSamePixels(
            engine.applySingleStroke(base(), cmd),
            engine.composite(base(), listOf(cmd)),
            "feathered colour fill",
        )
    }

    @Test
    fun `clear commits what it replays, feathered`() = runTest {
        val cmd = StrokeCommand(
            path = emptyList(),
            canvasSize = canvas,
            tool = Tool.ERASER,
            brushSize = 0f,
            brushColor = 0,
            intensity = 0f,
            selection = selection(featherPx = 6f),
            clearAll = true,
        )
        assertSamePixels(
            engine.applySingleStroke(base(), cmd),
            engine.composite(base(), listOf(cmd)),
            "feathered clear",
        )
    }

    @Test
    fun `a clone stroke paints something, and the same thing on replay`() = runTest {
        // Clone committed *nothing* until the audit: its live paint is blank by design, and no
        // commit path ran the composite that fills it in. A test that only compared commit to
        // replay would have passed — both were empty. So assert it paints at all, first.
        val art = RenderTestBase.filled(48, 48, Color.WHITE).also { b ->
            for (y in 0 until 16) for (x in 0 until 16) b.setPixel(x, y, Color.RED)
        }
        val cmd = StrokeCommand(
            path = listOf(Offset(30f, 30f), Offset(38f, 30f)),
            canvasSize = canvas,
            tool = Tool.CLONE,
            brushSize = 10f,
            brushColor = 0,
            intensity = 0.5f,
            // Sample from the red quadrant, up and to the left of the stroke.
            cloneOffset = Offset(-24f, -24f),
        )
        val committed = engine.applySingleStroke(art, cmd)
        assertNotEquals(
            "clone must actually copy pixels, not commit an empty layer",
            Color.WHITE,
            committed.getPixel(32, 30),
        )
        assertSamePixels(committed, engine.composite(art, listOf(cmd)), "clone")
    }

    @Test
    fun `a sharpen stroke changes something, and the same thing on replay`() = runTest {
        // Same trap as clone: sharpen has no live paint, so a commit path that forgot to run the
        // composite would leave the layer untouched and a pure commit-equals-replay test would pass
        // on two identical *unchanged* bitmaps. Assert it does something, first.
        //
        // Feathered, because that is the case that has broken every other tool here: the commit used
        // to build its own hard clip while the replay built a soft one, so the layer changed the
        // first time it was undone.
        val art = RenderTestBase.filled(48, 48, Color.WHITE).also { b ->
            // An edge for it to bite on — sharpen has nothing to do to a flat colour.
            for (y in 0 until 48) for (x in 24 until 48) b.setPixel(x, y, Color.rgb(140, 140, 140))
        }
        val cmd = StrokeCommand(
            path = listOf(Offset(6f, 24f), Offset(42f, 24f)),
            canvasSize = canvas,
            tool = Tool.SHARPEN,
            brushSize = 10f,
            brushColor = 0,
            intensity = 1f,
            selection = selection(featherPx = 8f),
        )
        val committed = engine.applySingleStroke(art, cmd)
        assertNotEquals(
            "sharpen must actually change pixels, not commit an untouched layer",
            art.getPixel(25, 24),
            committed.getPixel(25, 24),
        )
        assertSamePixels(committed, engine.composite(art, listOf(cmd)), "feathered sharpen")
    }

    /**
     * The selection is a hard boundary for it, not a suggestion. Sharpen writes its result back with
     * PorterDuff.SRC — a *replacing* mode — so if it ever stopped going through the clipped canvas
     * it would overwrite the whole layer with its own copy rather than sharpening inside the lasso.
     */
    @Test
    fun `sharpen does not touch pixels outside the selection`() = runTest {
        val art = RenderTestBase.filled(48, 48, Color.WHITE).also { b ->
            for (y in 0 until 48) for (x in 24 until 48) b.setPixel(x, y, Color.rgb(140, 140, 140))
        }
        val cmd = StrokeCommand(
            path = listOf(Offset(0f, 24f), Offset(47f, 24f)),   // right across the layer
            canvasSize = canvas,
            tool = Tool.SHARPEN,
            brushSize = 10f,
            brushColor = 0,
            intensity = 1f,
            selection = selection(),                            // the 12..36 box, hard-edged
        )
        val committed = engine.applySingleStroke(art, cmd)

        // The stroke crosses these, the selection does not contain them.
        for (x in intArrayOf(2, 6, 44)) {
            assertEquals(
                "x=$x is outside the selection and must be untouched",
                art.getPixel(x, 24), committed.getPixel(x, 24),
            )
        }
    }

    /**
     * Smudge is the one tool here whose result depends on the *order* it visits pixels in — the
     * carrier accumulates along the stroke — so "commit equals replay" is a stronger claim for it
     * than for anything else in this file. Any non-determinism at all, and the layer changes the
     * first time it is undone.
     */
    @Test
    fun `a smudge stroke changes something, and the same thing on replay`() = runTest {
        val art = RenderTestBase.filled(48, 48, Color.WHITE).also { b ->
            for (y in 0 until 48) for (x in 0 until 20) b.setPixel(x, y, Color.RED)
        }
        val cmd = StrokeCommand(
            path = listOf(Offset(14f, 24f), Offset(40f, 24f)),
            canvasSize = canvas,
            tool = Tool.SMUDGE,
            brushSize = 10f,
            brushColor = 0,
            intensity = 1f,
            selection = selection(featherPx = 8f),
        )
        val committed = engine.applySingleStroke(art, cmd)
        assertNotEquals(
            "smudge must actually carry colour, not commit an untouched layer",
            art.getPixel(28, 24),
            committed.getPixel(28, 24),
        )
        assertSamePixels(committed, engine.composite(art, listOf(cmd)), "feathered smudge")
    }

    @Test
    fun `a warp commits what it replays`() = runTest {
        val cmd = StrokeCommand(
            path = emptyList(),
            canvasSize = canvas,
            tool = Tool.NONE,
            brushSize = 0f,
            brushColor = 0,
            intensity = 0f,
            warpHandles = listOf(
                Offset(0f, 0f), Offset(24f, 0f),
                Offset(0f, 48f), Offset(24f, 48f),
            ),
        )
        val art = RenderTestBase.filled(48, 48, Color.WHITE).also { b ->
            for (y in 0 until 24) for (x in 0 until 24) b.setPixel(x, y, Color.RED)
        }
        assertSamePixels(
            engine.applySingleStroke(art, cmd),
            engine.composite(art, listOf(cmd)),
            "warp",
        )
    }
}
