package com.hereliesaz.graffitixr.common.azphalt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MaskDab.keepInside] resolves Krita's `MaskedBrushBlendMode` + `invert` pair into the single
 * Porter-Duff-style flag both the CPU (`StampBrushRenderer.paintMaskedDabs`) and GPU
 * (`stamp_masked.comp`'s `secondaryDabs[i].paint.w`) dual-brush compositors consume: `true` means
 * DST_IN (keep only where the second tip covers), `false` means DST_OUT (cut where it covers).
 */
class MaskDabKeepInsideTest {

    private fun dab(blendMode: MaskedBrushBlendMode, invert: Boolean) = MaskDab(
        x = 0f, y = 0f, radius = 1f, tipRatio = 1f, alpha = 1f, angleDeg = 0f,
        blendMode = blendMode, invert = invert,
    )

    @Test
    fun `multiply without invert keeps inside`() {
        assertTrue(dab(MaskedBrushBlendMode.MULTIPLY, invert = false).keepInside)
    }

    @Test
    fun `multiply with invert cuts inside`() {
        assertFalse(dab(MaskedBrushBlendMode.MULTIPLY, invert = true).keepInside)
    }

    @Test
    fun `subtract without invert cuts inside`() {
        assertFalse(dab(MaskedBrushBlendMode.SUBTRACT, invert = false).keepInside)
    }

    @Test
    fun `subtract with invert keeps inside`() {
        assertTrue(dab(MaskedBrushBlendMode.SUBTRACT, invert = true).keepInside)
    }
}
