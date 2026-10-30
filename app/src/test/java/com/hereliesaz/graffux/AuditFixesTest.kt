@file:Suppress("MagicNumber", "MaxLineLength")

package com.hereliesaz.graffux

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.GestureAction
import com.hereliesaz.graffitixr.common.model.GestureSlot
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.photo.PhotoSliders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure pieces behind the UI/UX audit fixes. */
class AuditFixesTest {

    @Test
    fun `back closes the topmost open layer, and nothing when all are shut`() {
        val closed = mutableListOf<String>()
        val layers = listOf(
            BackLayer(false) { closed += "menu" },
            BackLayer(true) { closed += "store" },
            BackLayer(true) { closed += "sheet" },
        )
        layers.topmost()?.close?.invoke()
        assertEquals(listOf("store"), closed)
        assertNull(listOf(BackLayer(false) {}).topmost())
    }

    @Test
    fun `move up and down step one place and stop at the ends`() {
        val order = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), steppedSiblingOrder(order, "b", -1))
        assertEquals(listOf("a", "c", "b"), steppedSiblingOrder(order, "b", 1))
        assertEquals(order, steppedSiblingOrder(order, "a", -1))
        assertEquals(order, steppedSiblingOrder(order, "c", 1))
        assertEquals(order, steppedSiblingOrder(order, "zz", 1))
    }

    @Test
    fun `layer siblings come top first`() {
        val layers = listOf(Layer(id = "bottom", name = "B"), Layer(id = "top", name = "T"))
        assertEquals(listOf(layerRailId("top"), layerRailId("bottom")), layerSiblingsTopFirst(layers, layers[0]))
    }

    @Test
    fun `the rail autoscrolls only near its edges, faster closer in`() {
        assertEquals(0f, railAutoscrollStep(500f, 100f, 900f, 40f, 12f))
        assertTrue(railAutoscrollStep(110f, 100f, 900f, 40f, 12f) < 0f)
        assertTrue(railAutoscrollStep(895f, 100f, 900f, 40f, 12f) > 0f)
        assertEquals(-12f, railAutoscrollStep(50f, 100f, 900f, 40f, 12f))
        assertTrue(railAutoscrollStep(890f, 100f, 900f, 40f, 12f) > railAutoscrollStep(870f, 100f, 900f, 40f, 12f))
        assertEquals(0f, railAutoscrollStep(10f, 0f, 0f, 40f, 12f))
    }

    @Test
    fun `a locked layer says so to TalkBack`() {
        val label = layerThumbLabel(Layer(id = "l", name = "Ink", alphaLock = true, isVisible = false), masked = false, expanded = false)
        assertEquals("Ink, alpha locked, hidden", label)
    }

    @Test
    fun `a brush chip drag locks to the axis it moved along most`() {
        assertEquals(DragAxis.VERTICAL, dominantAxis(Offset(2f, -9f)))
        assertEquals(DragAxis.HORIZONTAL, dominantAxis(Offset(-9f, 2f)))
    }

    @Test
    fun `levels set-progress keeps black below white`() {
        assertEquals(PhotoSliders.LEVELS_BLACK to 199f, levelsTargetValue("black", 250f, 10f, 200f))
        assertEquals(PhotoSliders.LEVELS_WHITE to 11f, levelsTargetValue("white", 0f, 10f, 200f))
        assertEquals(PhotoSliders.LEVELS_MID, levelsTargetValue("mid", 1.2f, 0f, 255f).first)
    }

    @Test
    fun `gesture help lists every slot with its current action`() {
        val lines = gestureHelpLines(mapOf(GestureSlot.THREE_FINGER_SCRUB to GestureAction.NONE))
        assertEquals(GestureSlot.entries.size, lines.size)
        assertTrue(lines.contains("Three-finger scrub: Off"))
        assertTrue(lines.contains("Two-finger tap: Undo"))
    }

    @Test
    fun `a check row and a section are what the layer menu builds from`() {
        val rows = buildMenu {
            section("Layer")
            check("Lock transparency", true) {}
            section("Remove")
            destructiveItem("Delete") {}
        }
        assertEquals(MenuRow.Header("Layer"), rows[0])
        assertTrue(rows[1] is MenuRow.Check && (rows[1] as MenuRow.Check).checked)
        assertEquals(MenuRow.Divider, rows[2])
        assertTrue((rows.last() as MenuRow.Action).destructive)
    }

    @Test
    fun `back closes floating windows latest-opened first`() {
        val order = WindowOpenOrder()
        val closed = mutableListOf<String>()
        fun windows(figma: Boolean, open: Boolean) = listOf(
            "open" to BackLayer(open) { closed += "open" },
            "figma" to BackLayer(figma) { closed += "figma" },
        )
        // Figma opened first, then Open on top of it.
        order.topFirst(windows(figma = true, open = false))
        order.topFirst(windows(figma = true, open = true)).topmost()?.close?.invoke()
        assertEquals(listOf("open"), closed)
        // Open shut; reopening it later puts it on top again.
        order.topFirst(windows(figma = true, open = false)).topmost()?.close?.invoke()
        assertEquals(listOf("open", "figma"), closed)
    }
}
