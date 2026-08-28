package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushColorSource
import com.hereliesaz.graffitixr.common.azphalt.MaskedBrushConfig
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [gpuCompatibleStampBrush] decides whether the azphalt stamp-brush live-preview path can hand a
 * stroke to a GPU shader instead of the CPU renderer (item 15 of the roadmap doc). Every stamp
 * brush configuration is GPU-compatible now: a masked/dual (second) tip is composited by
 * `stamp_masked.comp`'s secondary-dab sampling path (see stampMaskedDabs()'s secondaryDabs
 * arguments), same as the shaped-tip, non-round-tipRatio, and grain cases. [gpuPipelineUsesMaskedShader]
 * still decides which of the two shaders a stroke uses: `stamp.comp` (round-only, no optional
 * bindings) vs `stamp_masked.comp` (shape/tipRatio/grain/dual-brush). Color source is resolved to
 * a final per-dab RGB on the CPU before a dab ever reaches either shader, so it was never actually
 * a shader limitation and must NOT gate this.
 */
class GpuCompatibleStampBrushTest {

    private val bitmap = mockk<Bitmap>(relaxed = true)

    @Test
    fun `a plain round brush with no shape, grain, or mask is GPU-compatible via the round pipeline`() {
        val brush = AzphaltBrush(name = "Round")

        assertTrue(gpuCompatibleStampBrush(brush, shape = null, grain = null, maskShape = null))
        assertFalse(gpuPipelineUsesMaskedShader(brush, shape = null))
    }

    @Test
    fun `gradient and uniform-random color sources are also GPU-compatible`() {
        val gradient = AzphaltBrush(name = "Gradient", colorSource = BrushColorSource.GRADIENT)
        val random = AzphaltBrush(name = "Random", colorSource = BrushColorSource.UNIFORM_RANDOM)

        assertTrue(gpuCompatibleStampBrush(gradient, shape = null, grain = null, maskShape = null))
        assertTrue(gpuCompatibleStampBrush(random, shape = null, grain = null, maskShape = null))
    }

    @Test
    fun `a shaped tip is GPU-compatible via the masked pipeline`() {
        val brush = AzphaltBrush(name = "Shaped")

        assertTrue(gpuCompatibleStampBrush(brush, shape = bitmap, grain = null, maskShape = null))
        assertTrue(gpuPipelineUsesMaskedShader(brush, shape = bitmap))
    }

    @Test
    fun `a non-round tip ratio is GPU-compatible via the masked pipeline`() {
        val brush = AzphaltBrush(name = "Flat", tipRatio = 0.5f)

        assertTrue(gpuCompatibleStampBrush(brush, shape = null, grain = null, maskShape = null))
        assertTrue(gpuPipelineUsesMaskedShader(brush, shape = null))
    }

    @Test
    fun `grain texture on a round tip is GPU-compatible via the masked pipeline`() {
        val brush = AzphaltBrush(name = "Textured")

        assertTrue(gpuCompatibleStampBrush(brush, shape = null, grain = bitmap, maskShape = null))
        // Only the masked shader has a grain sampler binding -- stamp.comp has none at all, so a
        // round tip with grain still needs stamp_masked.comp even though shape/tipRatio don't ask for it.
        assertFalse(gpuPipelineUsesMaskedShader(brush, shape = null))
        assertTrue(gpuPipelineUsesMaskedShader(brush, shape = null, grain = bitmap))
    }

    @Test
    fun `a masked second tip is GPU-compatible via the masked pipeline's secondary-dab sampling`() {
        val brush = AzphaltBrush(name = "Dual", maskedBrush = MaskedBrushConfig())

        assertTrue(gpuCompatibleStampBrush(brush, shape = null, grain = null, maskShape = bitmap))
        // Even without an actual mask bitmap loaded yet, the config itself commits to the masked pipeline.
        assertTrue(gpuCompatibleStampBrush(brush, shape = null, grain = null, maskShape = null))
        // A dual-brush config alone routes to stamp_masked.comp even with no shape/grain/tipRatio ask.
        assertTrue(gpuPipelineUsesMaskedShader(brush, shape = null))
    }
}
