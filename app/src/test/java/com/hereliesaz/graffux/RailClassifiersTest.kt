package com.hereliesaz.graffux

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.SelectionRing
import com.hereliesaz.graffitixr.common.model.SymmetryMode
import com.hereliesaz.graffitixr.common.model.Tool
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [activeRailClassifiers] — which rail items are lit.
 *
 * The first tests in `:app`, which had none at all despite holding the entire rail. This function is
 * where every "you are here" in the UI is decided, and it is exactly the kind of logic that fails
 * silently: a classifier that is wrong, missing or misspelled breaks nothing and throws nothing, the
 * item simply never lights up. That is how five of the six group hosts once ended up defaulting to
 * an empty classifier set and an armed Select tool showed nothing at all once its group was closed.
 */
class RailClassifiersTest {

    private fun classifiers(state: EditorUiState, modelOpen: Boolean = false, optionsOpen: Boolean = false) =
        activeRailClassifiers(
            uiState = state,
            brushes = emptyList(),
            customBrushes = emptyList(),
            modelWindowOpen = modelOpen,
            toolOptionsOpen = optionsOpen,
        )

    @Test
    fun `the armed tool lights its own item`() {
        val lit = classifiers(EditorUiState(activeTool = Tool.ERASER))
        assertTrue("tool.eraser" in lit)
        assertFalse("tool.brush" in lit)
    }

    @Test
    fun `a tool inside a nested rail also lights the host that holds it`() {
        // The member's own item is out of sight whenever its group is closed, so without the host
        // carrying the light too, arming a tool and closing the rail shows nothing armed at all.
        assertTrue("grp.focus" in classifiers(EditorUiState(activeTool = Tool.LIQUIFY)))
        assertTrue("grp.retouch" in classifiers(EditorUiState(activeTool = Tool.CLONE)))
        assertTrue("grp.vector" in classifiers(EditorUiState(activeTool = Tool.PEN)))
        assertTrue("grp.select" in classifiers(EditorUiState(activeTool = Tool.SELECT)))
    }

    @Test
    fun `Tool NONE lights no tool item, because it is the absence of one`() {
        val lit = classifiers(EditorUiState(activeTool = Tool.NONE))
        assertTrue(lit.none { it.startsWith("tool.") && it != "tool.color" })
    }

    @Test
    fun `node editing lights the Vector host and its own item`() {
        val lit = classifiers(EditorUiState(pathEditLayerId = "p1"))
        assertTrue("grp.vector" in lit)
        assertTrue("tool.nodeEdit" in lit)
    }

    @Test
    fun `Clone lights its source item only once the tool is actually aimed`() {
        val armed = EditorUiState(activeTool = Tool.CLONE)
        assertFalse("tool.cloneSource" in classifiers(armed))
        assertTrue("tool.cloneSource" in classifiers(armed.copy(cloneSource = Offset(4f, 4f))))
    }

    @Test
    fun `symmetry lights the toggle and the picker's current member, and the picker always names one`() {
        val off = classifiers(EditorUiState(symmetryMode = SymmetryMode.NONE))
        assertFalse("tool.symmetry" in off)
        // The picker is a choice among all options including "off", so it always lights exactly one.
        assertTrue("symmetryMode.NONE" in off)

        val on = classifiers(EditorUiState(symmetryMode = SymmetryMode.RADIAL_6))
        assertTrue("tool.symmetry" in on)
        assertTrue("symmetryMode.RADIAL_6" in on)
        assertFalse("symmetryMode.NONE" in on)
    }

    @Test
    fun `only the panels that still have a rail item are lit`() {
        // Adjust, Balance and Layer Options open from a layer's hidden menu now, and a menu row has
        // no persistent state to light — the panel on screen is its own indication.
        assertTrue("adj.transform" in classifiers(EditorUiState(activePanel = EditorPanel.TRANSFORM)))
        assertTrue("adj.extensions" in classifiers(EditorUiState(activePanel = EditorPanel.EXTENSIONS)))
        val adjust = classifiers(EditorUiState(activePanel = EditorPanel.ADJUST))
        assertFalse("adj.adjust" in adjust)
        assertFalse("adj.transform" in adjust)
    }

    @Test
    fun `the built-in round brush lights the host itself, since it has no member of its own`() {
        assertTrue("grp.brushes" in classifiers(EditorUiState(activeBrushName = null)))
        assertFalse("grp.brushes" in classifiers(EditorUiState(activeBrushName = "Rough Ink")))
    }

    @Test
    fun `the active layer lights its own row in the layers rail`() {
        val state = EditorUiState(
            layers = listOf(Layer(id = "a", name = "a"), Layer(id = "b", name = "b")),
            activeLayerId = "b",
        )
        val lit = classifiers(state)
        assertTrue("layer.b" in lit)
        assertFalse("layer.a" in lit)
    }

    @Test
    fun `an inverted selection lights Invert, and a plain one does not`() {
        val ring = SelectionRing(listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 10f)), additive = true)
        val plain = EditorUiState(selection = Selection(listOf(ring), IntSize(100, 100)))
        assertFalse("tool.selectInvert" in classifiers(plain))
        assertTrue(
            "tool.selectInvert" in
                classifiers(plain.copy(selection = plain.selection?.copy(inverted = true))),
        )
    }

    @Test
    fun `windows whose state lives outside UiState still light their openers`() {
        // Both are composable state in GraffuxApp rather than editor state, which is precisely why
        // they are parameters — an item that opens a window has to light up while it is open.
        assertTrue("tool.model" in classifiers(EditorUiState(), modelOpen = true))
        assertTrue("tool.options" in classifiers(EditorUiState(), optionsOpen = true))
        val none = classifiers(EditorUiState())
        assertFalse("tool.model" in none)
        assertFalse("tool.options" in none)
    }

    @Test
    fun `time-lapse recording lights the Animation item even outside animation mode`() {
        // Time-lapse lives inside the Animation window, so this is the only place a recording in
        // progress can show at all.
        assertTrue("tool.animation" in classifiers(EditorUiState(isTimeLapseRecording = true)))
        assertTrue("tool.animation" in classifiers(EditorUiState(isAnimationMode = true)))
        assertFalse("tool.animation" in classifiers(EditorUiState()))
    }

    @Test
    fun `every mode picker lights exactly one of its members`() {
        val lit = classifiers(EditorUiState())
        assertTrue(lit.count { it.startsWith("symmetryMode.") } == 1)
        assertTrue(lit.count { it.startsWith("selectShape.") } == 1)
        assertTrue(lit.count { it.startsWith("selectOp.") } == 1)
        assertTrue(lit.count { it.startsWith("transform.") } == 1)
    }
}

/**
 * [secondaryRailClassifiers] — the third highlight, which is a *condition* rather than a place.
 *
 * Both of these were editor state the rail had no way to show before 11.9 gave it a second colour,
 * and both are the kind that bites silently: a selection quietly clipping every stroke, and layers
 * that come along when you drag one without ever having said they would.
 */
class SecondaryRailClassifiersTest {

    private fun layer(id: String, linked: Boolean = false) =
        Layer(id = id, name = id, isLinked = linked)

    @Test
    fun `an unlinked layer marks nothing`() {
        val state = EditorUiState(
            layers = listOf(layer("a"), layer("b"), layer("c")),
            activeLayerId = "b",
        )
        assertTrue(secondaryRailClassifiers(state).none { it.startsWith("layer.") })
    }

    @Test
    fun `a link run marks its other members and never the active layer itself`() {
        // isLinked means "linked to the layer below me", so b and c form a run with a.
        val state = EditorUiState(
            layers = listOf(layer("a"), layer("b", linked = true), layer("c", linked = true), layer("d")),
            activeLayerId = "b",
        )
        val lit = secondaryRailClassifiers(state)
        assertTrue("layer.a" in lit)
        assertTrue("layer.c" in lit)
        // The one you are on is ACTIVE, not secondary — it would be lit twice otherwise, and the
        // rail would say the same thing about the layer you are editing and the ones tagging along.
        assertFalse("layer.b" in lit)
        // d is outside the run.
        assertFalse("layer.d" in lit)
    }

    @Test
    fun `a live selection marks the Selection group, because it clips every raster tool`() {
        val ring = SelectionRing(listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 10f)), additive = true)
        val none = EditorUiState()
        assertFalse("grp.select" in secondaryRailClassifiers(none))
        val selected = EditorUiState(selection = Selection(listOf(ring), IntSize(100, 100)))
        assertTrue("grp.select" in secondaryRailClassifiers(selected))
    }

    @Test
    fun `nothing is secondary in an empty document`() {
        assertTrue(secondaryRailClassifiers(EditorUiState()).isEmpty())
    }
}
