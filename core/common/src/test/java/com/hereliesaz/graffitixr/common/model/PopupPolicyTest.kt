package com.hereliesaz.graffitixr.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupPolicyTest {
    @Test
    fun `guidance and decisions are important, bare reports are transient`() {
        assertEquals(PopupImportance.IMPORTANT, PopupPolicy.importance(guidesNextStep = true, offersAction = false))
        assertEquals(PopupImportance.IMPORTANT, PopupPolicy.importance(guidesNextStep = false, offersAction = true))
        assertEquals(PopupImportance.TRANSIENT, PopupPolicy.importance(guidesNextStep = false, offersAction = false))
    }

    @Test
    fun `an actionable popup never auto-dismisses`() {
        assertNull(PopupPolicy.autoDismissMillis(textLength = 5, actionable = true))
        assertNull(PopupPolicy.autoDismissMillis(textLength = 500, actionable = true))
    }

    @Test
    fun `a transient popup stays in proportion to its length within bounds`() {
        assertEquals(PopupPolicy.MIN_MS, PopupPolicy.autoDismissMillis(0, actionable = false))
        assertEquals(PopupPolicy.MIN_MS, PopupPolicy.autoDismissMillis(20, actionable = false))
        val medium = PopupPolicy.autoDismissMillis(80, actionable = false)!!
        val longer = PopupPolicy.autoDismissMillis(120, actionable = false)!!
        assertTrue(medium > PopupPolicy.MIN_MS && longer > medium)
        assertEquals(PopupPolicy.MAX_MS, PopupPolicy.autoDismissMillis(10_000, actionable = false))
    }

    @Test
    fun `the surface confirmations carry a decision and stay, the refusals are transient`() {
        assertEquals(PopupImportance.IMPORTANT, SurfaceDrawNotice.DONE.importance)
        assertEquals(PopupImportance.IMPORTANT, SurfaceDrawNotice.VIEW_DONE.importance)
        val refusals = listOf(
            SurfaceDrawNotice.NO_LAYER, SurfaceDrawNotice.LOCKED,
            SurfaceDrawNotice.UNAVAILABLE, SurfaceDrawNotice.TOO_SHORT,
        )
        for (n in refusals) {
            assertEquals(PopupImportance.TRANSIENT, n.importance)
        }
    }

    @Test
    fun `the armed surface prompt is important whatever it carries, nothing at all is transient`() {
        assertEquals(PopupImportance.IMPORTANT, surfacePromptImportance(true, null))
        assertEquals(PopupImportance.IMPORTANT, surfacePromptImportance(true, SurfaceDrawNotice.TOO_SHORT))
        assertEquals(PopupImportance.IMPORTANT, surfacePromptImportance(false, SurfaceDrawNotice.DONE))
        assertEquals(PopupImportance.TRANSIENT, surfacePromptImportance(false, SurfaceDrawNotice.LOCKED))
        assertEquals(PopupImportance.TRANSIENT, surfacePromptImportance(false, null))
    }
}
