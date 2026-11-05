@file:Suppress("MaxLineLength")

package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Groups on the layers rail: a host tile, open unless the user collapsed it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class LayersRailGroupsUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun group(id: String, parent: String? = null) = Layer(id, id, type = LayerType.GROUP, parentId = parent)
    private fun leaf(id: String, parent: String? = null) = Layer(id, id, parentId = parent)

    private fun show(layers: List<Layer>, expansion: Map<String, Boolean> = emptyMap(), onExpand: (String, Boolean) -> Unit = { _, _ -> }) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LayersRail(
                    layers = layers,
                    activeLayerId = layers.firstOrNull { it.type != LayerType.GROUP }?.id,
                    secondary = emptySet(),
                    expansion = expansion,
                    callbacks = LayersRailCallbacks({}, {}, onExpand, { _, _ -> }, {}),
                )
            }
        }
    }

    private val one = listOf(leaf("a"), group("g1"), leaf("b", "g1"), leaf("c", "g1"))

    @Test
    fun `a new group, or one in a project that never toggled it, is open and shows its layers`() {
        assertTrue(groupExpanded(emptyMap(), "layer.g1"))
        assertEquals(listOf("g1", "c", "b", "a"), visibleLayerRows(one, emptyMap()).map { it.layer.id })
        show(one)
        listOf("a", "g1", "b", "c").forEach { rule.onNodeWithTag("rail.layer.$it").assertExists() }
    }

    @Test
    fun `a collapse the user made sticks, and a layer under it is hidden`() {
        assertFalse(groupExpanded(mapOf("layer.g1" to false), "layer.g1"))
        assertEquals(listOf("g1", "a"), visibleLayerRows(one, mapOf("layer.g1" to false)).map { it.layer.id })
        show(one, mapOf("layer.g1" to false))
        rule.onAllNodes(hasTestTag("rail.layer.b")).assertCountEquals(0)
        rule.onNodeWithTag("rail.layer.g1").assertExists()
    }

    @Test
    fun `an explicit open stays open, and nested groups follow their own state`() {
        val nested = listOf(group("g1"), group("h", "g1"), leaf("a", "h"))
        assertEquals(listOf("g1", "h", "a"), visibleLayerRows(nested, emptyMap()).map { it.layer.id })
        assertEquals(listOf("g1", "h"), visibleLayerRows(nested, mapOf("layer.h" to false)).map { it.layer.id })
        assertEquals(listOf("g1"), visibleLayerRows(nested, mapOf("layer.g1" to false)).map { it.layer.id })
        assertEquals(listOf("g1", "h", "a"), visibleLayerRows(nested, mapOf("layer.g1" to true, "layer.h" to true)).map { it.layer.id })
    }

    @Test
    fun `tapping an open group's tile collapses it`() {
        var asked: Pair<String, Boolean>? = null
        show(one) { id, open -> asked = id to open }
        rule.onNodeWithTag("rail.layer.g1").performTouchInputClick()
        assertEquals("layer.g1" to false, asked)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performTouchInputClick() =
        performTouchInput { click() }
}
