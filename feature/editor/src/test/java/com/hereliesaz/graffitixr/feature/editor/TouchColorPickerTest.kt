package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchColorPickerTest {
    @Test fun squareCornersLandOnDiscDiagonals() {
        val p = pickerSquareToDisc(1f, -1f)
        assertEquals(0.70710678f, p.x, 0.00001f)
        assertEquals(-0.70710678f, p.y, 0.00001f)
    }

    @Test fun discCardinalPointsReachSquareEdges() {
        val right = pickerDiscToSquare(2f, 0f)
        val top = pickerDiscToSquare(0f, -1f)
        val center = pickerDiscToSquare(0f, 0f)
        assertEquals(1f, right.x, 0.00001f)
        assertEquals(0f, right.y, 0.00001f)
        assertEquals(0f, top.x, 0.00001f)
        assertEquals(-1f, top.y, 0.00001f)
        assertEquals(0f, center.x, 0.00001f)
        assertEquals(0f, center.y, 0.00001f)
    }

    @Test fun ringHueUsesCounterclockwiseCardinals() {
        assertEquals(0f, pickerHueAt(1f, 0f), 0.001f)
        assertEquals(90f, pickerHueAt(0f, -1f), 0.001f)
        assertEquals(180f, pickerHueAt(-1f, 0f), 0.001f)
        assertEquals(270f, pickerHueAt(0f, 1f), 0.001f)
    }

    @Test fun inverseRetainsCoordinatesAcrossWholeSquare() {
        for (x in -10..10) for (y in -10..10) {
            val original = PickerPoint(x / 10f, y / 10f)
            val p = pickerSquareToDisc(original.x, original.y)
            val restored = pickerDiscToSquare(p.x, p.y)
            assertTrue(kotlin.math.abs(original.x - restored.x) < 0.001f)
            assertTrue(kotlin.math.abs(original.y - restored.y) < 0.001f)
        }
    }

    @Test fun heldBlueHueAndSaturationSurviveBlack() {
        val blue = PickerHsv(240f, 0.8f, 0.6f)
        val black = PickerHsv.from(Color.Black, blue)
        assertEquals(240f, black.hue, 0f)
        assertEquals(0.8f, black.saturation, 0f)
        assertEquals(0f, black.brightness, 0f)
        assertEquals(0x803333FF.toInt(), black.copy(brightness = 1f).color(0.5f).toArgb())
    }

    @Test fun chromaticExternalColorReplacesHeldHueAndKeepsAlphaOnOutput() {
        val green = PickerHsv.from(Color(0x4000FF00), PickerHsv(240f, 1f, 1f))
        assertEquals(120f, green.hue, 0.001f)
        assertEquals(0x4000FF00, green.color(64f / 255f).toArgb())
    }
}
