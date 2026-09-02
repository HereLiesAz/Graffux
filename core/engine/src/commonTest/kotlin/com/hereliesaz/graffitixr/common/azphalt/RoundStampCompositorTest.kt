package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Runs on every target (androidTarget + jvm("desktop")) from one source set, so a regression here
 *  is caught identically whichever platform compiled it — this is the whole point of extracting
 *  [RoundStampCompositor] out of the Android-only renderer. */
class RoundStampCompositorTest {

    private val black = ArgbColor.argb(255, 0, 0, 0)

    @Test
    fun `a dragged stroke's soft edge matches a single dab's own falloff, not a hardened build-up`() {
        val hardness = 0.3f
        val radius = 40f
        val diameter = radius * 2f
        val spacing = 0.1f
        val interval = spacing * diameter
        val dabs = (-40..40).map { i ->
            Dab(x = 100f + i * interval, y = 100f, radius = radius, alpha = 1f, angleDeg = 0f, hardness = hardness)
        }
        val drag = RoundStampCompositor.compositeMaxCombined(dabs, black, black, BrushColorSource.PLAIN, flow = 1f)
        assertNotNull(drag)

        val singleTap = RoundStampCompositor.compositeMaxCombined(
            listOf(Dab(x = 100f, y = 100f, radius = radius, alpha = 1f, angleDeg = 0f, hardness = hardness)),
            black, black, BrushColorSource.PLAIN, flow = 1f,
        )
        assertNotNull(singleTap)

        val sampleY = (100 + radius * 0.7f).toInt()
        val dragAlpha = ArgbColor.alpha(drag.pixels[(sampleY - drag.top) * drag.width + (100 - drag.left)])
        val tapAlpha = ArgbColor.alpha(singleTap.pixels[(sampleY - singleTap.top) * singleTap.width + (100 - singleTap.left)])

        assertTrue(
            abs(dragAlpha - tapAlpha) <= 15,
            "a dragged stroke's edge alpha ($dragAlpha) should be close to a single tap's own falloff " +
                "at the same offset ($tapAlpha)",
        )
        assertTrue(dragAlpha < 200, "the edge should still read as visibly translucent, not hardened")
    }

    @Test
    fun `fully transparent dabs composite to nothing`() {
        val dabs = listOf(Dab(x = 10f, y = 10f, radius = 5f, alpha = 0f, angleDeg = 0f))
        val result = RoundStampCompositor.compositeMaxCombined(dabs, black, black, BrushColorSource.PLAIN, flow = 1f)
        assertTrue(result == null)
    }

    @Test
    fun `hue shift rotates the dab color without touching alpha`() {
        val red = ArgbColor.argb(255, 255, 0, 0)
        val dab = Dab(x = 0f, y = 0f, radius = 1f, alpha = 1f, angleDeg = 0f, hueShiftDeg = 120f)
        val shifted = ArgbColor.resolveDabColor(red, red, BrushColorSource.PLAIN, dab)
        assertTrue(ArgbColor.alpha(shifted) == 255)
        // +120 degrees from red (hue 0) lands on green.
        assertTrue(ArgbColor.green(shifted) > ArgbColor.red(shifted))
        assertTrue(ArgbColor.green(shifted) > ArgbColor.blue(shifted))
    }
}
