package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [resolveGrainTileAndPhase] is the single source of truth the CPU masked-tip path
 * ([StampBrushRenderer.paintMaskedDabs]) and the GPU masked pipeline's live-preview setup
 * (`EditorViewModel.onStrokeStart`) both call for item 15's texture/grain follow-up -- extracted
 * specifically so the two never resolve `grainRandomOffsetPerStroke`'s seeded draw differently.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ResolveGrainTileAndPhaseTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    @After
    fun clearCache() = BrushTipMaskCache.clear()

    private fun grainBitmap(): Bitmap = RenderTestBase.filled(8, 8, Color.WHITE)

    @Test
    fun `no grain source resolves to a null tile and zero phase`() {
        val brush = AzphaltBrush(name = "plain")
        val r = resolveGrainTileAndPhase(null, brush, seed = 1L)
        assertNull(r.tile)
        assertEquals(0f, r.phaseX, 0f)
        assertEquals(0f, r.phaseY, 0f)
    }

    @Test
    fun `a fixed offset with no random component resolves the phase directly`() {
        val brush = AzphaltBrush(name = "grained", grainOffsetX = 3f, grainOffsetY = -2f)
        val r = resolveGrainTileAndPhase(grainBitmap(), brush, seed = 1L)
        assertEquals(3f, r.phaseX, 0f)
        assertEquals(-2f, r.phaseY, 0f)
    }

    @Test
    fun `grainRandomOffsetPerStroke adds a deterministic extra offset on top of the fixed one`() {
        val brush = AzphaltBrush(
            name = "grained", grainOffsetX = 1f, grainOffsetY = 1f, grainRandomOffsetPerStroke = true,
        )
        val a = resolveGrainTileAndPhase(grainBitmap(), brush, seed = 42L)
        val b = resolveGrainTileAndPhase(grainBitmap(), brush, seed = 42L)

        assertEquals("same seed must reproduce the same phase", a.phaseX, b.phaseX, 0f)
        assertEquals(a.phaseY, b.phaseY, 0f)
        assertTrue("random component should shift the phase away from the fixed offset alone", a.phaseX != 1f || a.phaseY != 1f)
    }

    @Test
    fun `different seeds draw different random offsets`() {
        val brush = AzphaltBrush(name = "grained", grainRandomOffsetPerStroke = true)
        val a = resolveGrainTileAndPhase(grainBitmap(), brush, seed = 1L)
        val b = resolveGrainTileAndPhase(grainBitmap(), brush, seed = 2L)
        assertNotEquals(a.phaseX to a.phaseY, b.phaseX to b.phaseY)
    }
}
