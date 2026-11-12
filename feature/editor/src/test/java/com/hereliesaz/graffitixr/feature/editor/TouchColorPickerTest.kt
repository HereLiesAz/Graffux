package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchColorPickerTest {
    @Test fun exactCurrentColourIsAtTheCentre() {
        val colour = Color(0xFF739AB2)
        assertEquals(colour, relativePickerColour(colour, 0f, 0f))
    }

    @Test fun changingSaturationDoesNotMoveTheCentre() {
        val colour = Color(0xFF739AB2)
        val less = relativePickerColour(colour, -1f, 0f)
        val more = relativePickerColour(colour, 1f, 0f)
        val a = FloatArray(3)
        val b = FloatArray(3)
        android.graphics.Color.colorToHSV(less.value.ushr(32).toInt(), a)
        android.graphics.Color.colorToHSV(more.value.ushr(32).toInt(), b)
        assertTrue(a[1] < b[1])
        assertEquals(colour, relativePickerColour(colour, 0f, 0f))
    }

    @Test fun verticalAdjustmentIsRelativeToTheOriginalBrightness() {
        val colour = Color(0xFF739AB2)
        val lighter = relativePickerColour(colour, 0f, -1f)
        val darker = relativePickerColour(colour, 0f, 1f)
        val light = FloatArray(3)
        val dark = FloatArray(3)
        android.graphics.Color.colorToHSV(lighter.value.ushr(32).toInt(), light)
        android.graphics.Color.colorToHSV(darker.value.ushr(32).toInt(), dark)
        assertTrue(light[2] > dark[2])
    }
}
