package com.hereliesaz.graffitixr.feature.editor

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.hereliesaz.graffitixr.common.util.NamedPalette
import com.hereliesaz.graffitixr.common.util.PaletteEdit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class NamedPaletteSlotsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun emptyFilledAndHeldSlotsDispatchTheirOwnActions() {
        val events = mutableListOf<PaletteEdit>()
        val picked = mutableListOf<Color>()
        rule.setContent {
            MaterialTheme {
                NamedPaletteSlots(
                    NamedPalette("ink", "Ink", listOf(0xFF123456.toInt()) + List<Int?>(29) { null }),
                    Color(0xFFABCDEF), { picked += it }, { events += it }, cards = false,
                )
            }
        }
        rule.onNodeWithTag("touchColor.palette.ink.slot.1").performClick()
        rule.onNodeWithTag("touchColor.palette.ink.slot.0").performClick()
        rule.onNodeWithTag("touchColor.palette.ink.slot.0").performTouchInput { longClick() }
        assertEquals(listOf(Color(0xFF123456)), picked)
        assertEquals(listOf(PaletteEdit.SetSlot("ink", 1, 0xFFABCDEF.toInt()), PaletteEdit.SetSlot("ink", 0, null)), events)
    }
}
