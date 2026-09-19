package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.azphalt.PersistentWetnessField
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSmudgePersistentWetnessTest {
    private val width = 48
    private val height = 16
    private val stroke = List(28) { Offset((8 + it).toFloat(), 8f) }

    @Test
    fun `no wetness field preserves historical output byte for byte`() {
        val settings = ColorSmudgeEngine.Settings(
            radiusPx = 4f,
            smudgeRate = 0.72f,
            colorRate = 0.35f,
            pickupRate = 0.4f,
            dilution = 0f,
            paintColor = Color.BLUE,
        )
        val a = IntArray(width * height) { if (it % width < 24) Color.RED else Color.WHITE }
        val b = a.copyOf()

        ColorSmudgeEngine.apply(a, width, height, stroke, settings, strokeSeed = 41L)
        ColorSmudgeEngine.apply(
            b, width, height, stroke, settings, strokeSeed = 41L, wetnessField = null,
        )

        assertArrayEquals(a, b)
    }

    @Test
    fun `positive dilution deposits persistent wetness only under brush coverage`() {
        val settings = ColorSmudgeEngine.Settings(
            radiusPx = 3f,
            smudgeRate = 0f,
            colorRate = 0.5f,
            dilution = 0.75f,
            paintColor = Color.BLUE,
        )
        val pixels = IntArray(width * height) { Color.WHITE }
        val wetness = PersistentWetnessField(width, height, tileSize = 8)

        ColorSmudgeEngine.apply(
            pixels, width, height, stroke, settings, wetnessField = wetness,
        )

        assertFalse(wetness.isIdle)
        assertTrue(wetness.wetnessAt(20, 8) > 0f)
        assertTrue(wetness.wetnessAt(0, 0) == 0f)
    }

    @Test
    fun `canvas wetness changes dry brush mobility once material state exists`() {
        val settings = ColorSmudgeEngine.Settings(
            radiusPx = 4f,
            smudgeRate = 1f,
            colorRate = 0f,
            dilution = 0f,
        )
        val initial = IntArray(width * height) { index ->
            if (index % width < 20) Color.RED else Color.BLUE
        }
        val dryReference = initial.copyOf()
        val dampResult = initial.copyOf()
        val damp = PersistentWetnessField(width, height, tileSize = 8)
        // Keep the field active but only moderately wet along the stroke.
        for (x in 6..38) damp.addWetness(x, 8, 0.25f)

        ColorSmudgeEngine.apply(dryReference, width, height, stroke, settings)
        ColorSmudgeEngine.apply(
            dampResult, width, height, stroke, settings, wetnessField = damp,
        )

        assertFalse(
            "persistent wetness must affect mobility instead of being metadata only",
            dryReference.contentEquals(dampResult),
        )
    }
}
