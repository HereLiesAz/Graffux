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
 * stroke to `stamp.comp` instead of the CPU renderer (item 15 of the roadmap doc). `stamp.comp`
 * only draws a generated round dab, so a custom tip/grain/mask or a non-round [AzphaltBrush]
 * still forces CPU -- but color source is resolved to a final per-dab RGB on the CPU before a dab
 * ever reaches the GPU, so it was never actually a shader limitation and must NOT gate this.
 */
class GpuCompatibleStampBrushTest {

    private val bitmap = mockk<Bitmap>(relaxed = true)

    @Test
    fun `a plain round brush with no shape, grain, or mask is GPU-compatible`() {
        val brush = AzphaltBrush(name = "Round")

        assertTrue(gpuCompatibleStampBrush(brush, shape = null, grain = null, maskShape = null))
    }

    @Test
    fun `gradient and uniform-random color sources are also GPU-compatible`() {
        val gradient = AzphaltBrush(name = "Gradient", colorSource = BrushColorSource.GRADIENT)
        val random = AzphaltBrush(name = "Random", colorSource = BrushColorSource.UNIFORM_RANDOM)

        assertTrue(gpuCompatibleStampBrush(gradient, shape = null, grain = null, maskShape = null))
        assertTrue(gpuCompatibleStampBrush(random, shape = null, grain = null, maskShape = null))
    }

    @Test
    fun `a shaped tip forces the CPU path`() {
        val brush = AzphaltBrush(name = "Shaped")

        assertFalse(gpuCompatibleStampBrush(brush, shape = bitmap, grain = null, maskShape = null))
    }

    @Test
    fun `grain texture forces the CPU path`() {
        val brush = AzphaltBrush(name = "Textured")

        assertFalse(gpuCompatibleStampBrush(brush, shape = null, grain = bitmap, maskShape = null))
    }

    @Test
    fun `a masked second tip forces the CPU path`() {
        val brush = AzphaltBrush(name = "Dual", maskedBrush = MaskedBrushConfig())

        assertFalse(gpuCompatibleStampBrush(brush, shape = null, grain = null, maskShape = bitmap))
        // Even without an actual mask bitmap loaded yet, the config itself commits to the masked pipeline.
        assertFalse(gpuCompatibleStampBrush(brush, shape = null, grain = null, maskShape = null))
    }

    @Test
    fun `a non-round tip ratio forces the CPU path`() {
        val brush = AzphaltBrush(name = "Flat", tipRatio = 0.5f)

        assertFalse(gpuCompatibleStampBrush(brush, shape = null, grain = null, maskShape = null))
    }
}
