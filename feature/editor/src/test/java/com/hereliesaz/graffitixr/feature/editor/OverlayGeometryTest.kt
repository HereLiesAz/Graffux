package com.hereliesaz.graffitixr.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class OverlayGeometryTest {
    @Test
    fun inverseUndoesARotatedScaledTranslatedMap() {
        val angle = 0.7f
        val scale = 1.8f
        val forward = floatArrayOf(
            scale * cos(angle), -scale * sin(angle), 120f,
            scale * sin(angle), scale * cos(angle), -45f,
        )
        val inverse = invertAffine(forward)!!
        val onScreen = applyAffine(forward, 33f, 71f)
        val back = applyAffine(inverse, onScreen.x, onScreen.y)
        assertEquals(33f, back.x, 1e-3f)
        assertEquals(71f, back.y, 1e-3f)
    }

    @Test
    fun singularMapHasNoInverse() {
        assertNull(invertAffine(floatArrayOf(1f, 2f, 0f, 2f, 4f, 0f)))
    }
}
