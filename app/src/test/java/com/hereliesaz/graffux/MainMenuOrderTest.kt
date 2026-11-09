package com.hereliesaz.graffux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainMenuOrderTest {
    @Test
    fun newIsTheFirstEntryAndViewFollowsIt() {
        val rows = buildMenu {
            mainMenuLead("New", "View…", {}, {})
            listItem("Open") {}
        }
        assertTrue(rows[0] is MenuRow.Action)
        assertEquals("New", (rows[0] as MenuRow.Action).text)
        assertEquals("View…", (rows[1] as MenuRow.Page).text)
        assertEquals("Open", (rows[2] as MenuRow.Action).text)
    }

    @Test
    fun leadCallbacksAreWiredToTheirRows() {
        var made = 0
        var viewed = 0
        val rows = buildMenu { mainMenuLead("New", "View…", { made++ }, { viewed++ }) }
        (rows[0] as MenuRow.Action).onClick()
        (rows[1] as MenuRow.Page).onClick()
        assertEquals(1, made)
        assertEquals(1, viewed)
    }
}
