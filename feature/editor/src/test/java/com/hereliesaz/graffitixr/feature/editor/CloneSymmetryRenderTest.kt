package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.SymmetryMode
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A glee audit found Tool.CLONE's source sampling ignored Symmetry entirely: only the destination
 * MASK was mirrored (via `drawStroke`'s own recursion), while the SOURCE stayed one single bitmap
 * shifted by the raw, un-mirrored clone offset and composited against every mirrored copy at once.
 * A mirrored copy's source read, still offset in the un-mirrored direction, usually landed off
 * canvas -- painting nothing there instead of the correctly mirrored source content.
 *
 * This pins the fix with two markers placed so the primary copy and the (VERTICAL-)mirrored copy
 * read from two different, known locations: the primary destination must show the primary marker's
 * colour, and the mirrored destination must show the *other* marker's colour -- which only happens
 * if the mirrored copy's own offset was itself mirrored, not reused unmirrored from the primary.
 * Under the bug, the mirrored destination's computed sample point falls off the 48px-wide canvas
 * entirely and stays the untouched background colour instead.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CloneSymmetryRenderTest {

    companion object {
        @JvmStatic
        @org.junit.BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    /**
     * White 48x48 canvas (mirror axis at x=24) with a BLUE marker centred at (40,24) -- the primary
     * copy's source, given the offset chosen below -- and a distinct GREEN marker centred at (8,24)
     * -- reachable only by the mirrored copy's own, separately-mirrored offset.
     */
    private fun markedCanvas(): Bitmap = RenderTestBase.filled(48, 48, Color.WHITE).also { b ->
        for (y in 20 until 28) for (x in 36 until 44) b.setPixel(x, y, Color.BLUE)
        for (y in 20 until 28) for (x in 4 until 12) b.setPixel(x, y, Color.GREEN)
    }

    @Test
    fun `clone under vertical symmetry mirrors the source offset, not just the mask`() = runBlocking {
        val before = markedCanvas()
        // Destination at (4,24); offset (36,0) so the primary copy samples (40,24) -- the BLUE
        // marker. VERTICAL mirrors x -> 48-x: the mirrored destination is (44,24), and mirroring
        // (36,0)'s own linear part (about the canvas centre) gives (-36,0), so the mirrored copy's
        // correct sample point is (44-36,24) = (8,24) -- the GREEN marker. Under the bug, the
        // mirrored copy would instead reuse the raw (36,0) offset unmirrored: (44+36,24) = (80,24),
        // off the 48px-wide canvas, so it stays the background white instead of GREEN.
        val stroke = listOf(Offset(4f, 24f), Offset(5f, 24f))
        val committed = ImageProcessor.applyToolToBitmap(
            before, stroke, Tool.CLONE,
            brushSize = 6f,
            cloneOffset = Offset(36f, 0f),
            symmetryMode = SymmetryMode.VERTICAL,
        )

        assertEquals(
            "the primary copy must sample its own (unmirrored) source",
            Color.BLUE,
            committed.getPixel(4, 24),
        )
        assertEquals(
            "the mirrored copy must sample from the mirrored offset, not the raw one -- " +
                "reusing the unmirrored offset lands off-canvas and leaves this pixel background white",
            Color.GREEN,
            committed.getPixel(44, 24),
        )
    }
}
