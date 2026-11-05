@file:Suppress("MaxLineLength")

package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.design.theme.Amber
import com.hereliesaz.graffitixr.design.theme.Cyan
import com.hereliesaz.graffitixr.design.theme.HotPink
import com.hereliesaz.graffitixr.design.theme.NeonGreen
import com.hereliesaz.graffitixr.design.theme.White
import com.hereliesaz.graffitixr.design.theme.rememberAppStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Groups have no tile on the layers rail: every layer shows, flat, in stack order, whatever the
 * saved expansion says; membership is told by a colour band, only once there are two groups.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class LayersRailGroupsUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun group(id: String, parent: String? = null) = Layer(id, id, type = LayerType.GROUP, parentId = parent)
    private fun leaf(id: String, parent: String? = null) = Layer(id, id, parentId = parent)

    private fun show(layers: List<Layer>, expansion: Map<String, Boolean> = emptyMap()) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LayersRail(
                    layers = layers,
                    activeLayerId = layers.firstOrNull { !it.isGroup() }?.id,
                    secondary = emptySet(),
                    expansion = expansion,
                    callbacks = LayersRailCallbacks({}, {}, { _, _ -> }, { _, _ -> }, {}),
                )
            }
        }
    }

    private fun Layer.isGroup() = type == LayerType.GROUP

    private fun tiles(layers: List<Layer>) =
        layers.count { rule.onAllNodes(hasTestTag("rail.${layerRailId(it.id)}")).fetchSemanticsNodes().isNotEmpty() }

    private val none = listOf(leaf("a"), leaf("b"))
    private val one = listOf(leaf("a"), group("g1"), leaf("b", "g1"), leaf("c", "g1"))
    private val two = listOf(group("g1"), leaf("a", "g1"), group("g2"), leaf("b", "g2"), leaf("c", "g2"), leaf("d"))
    private val nested = listOf(group("g1"), group("h", "g1"), leaf("a", "h"), leaf("b", "g1"), group("g2"), leaf("c", "g2"))

    @Test
    fun `the tile count equals the layer count for zero, one, two and nested groups`() {
        listOf(none, one, two, nested).forEach { layers ->
            val leaves = layers.filter { !it.isGroup() }
            assertEquals(leaves.size, visibleLayerRows(layers, emptyMap()).size)
            assertTrue(visibleLayerRows(layers, emptyMap()).none { it.layer.isGroup() })
        }
        show(two)
        assertEquals(4, tiles(two))
        two.filter { it.isGroup() }.forEach { rule.onAllNodes(hasTestTag("rail.${layerRailId(it.id)}")).assertCountEquals(0) }
    }

    @Test
    fun `no group tile appears whether the group is saved collapsed or expanded`() {
        listOf(emptyMap(), mapOf("layer.g1" to false), mapOf("layer.g1" to true, "layer.g2" to false)).forEach { exp ->
            assertEquals(
                two.filter { !it.isGroup() }.size,
                visibleLayerRows(two, exp).size,
            )
            assertTrue(visibleLayerRows(two, exp).none { it.layer.isGroup() })
        }
        show(one, mapOf("layer.g1" to false))
        assertEquals(3, tiles(one))
    }

    @Test
    fun `one group shows nothing, two groups show two distinct bands`() {
        assertTrue(layerGroupBands(none).isEmpty())
        assertTrue(layerGroupBands(one).isEmpty())
        val bands = layerGroupBands(two)
        assertEquals(setOf("a", "b", "c"), bands.keys)
        assertEquals(bands.getValue("b"), bands.getValue("c"))
        assertNotEquals(bands.getValue("a").color, bands.getValue("b").color)
        assertEquals(1, bands.getValue("a").number)
        assertEquals(2, bands.getValue("b").number)
        show(two)
        listOf("a", "b", "c").forEach { rule.onNodeWithTag("rail.group.band.$it").assertExists() }
        rule.onAllNodes(hasTestTag("rail.group.band.d")).assertCountEquals(0)
        rule.onNodeWithTag("rail.${layerRailId("b")}").assertContentDescriptionContains("Group 2", substring = true)
    }

    @Test
    fun `one group draws no band on screen`() {
        show(one)
        rule.onAllNodes(hasTestTag("rail.group.band.b")).assertCountEquals(0)
    }

    @Test
    fun `nested groups take the colour of their top-level group`() {
        val bands = layerGroupBands(nested)
        assertEquals(bands.getValue("a"), bands.getValue("b"))
        assertNotEquals(bands.getValue("a").color, bands.getValue("c").color)
        assertEquals(setOf("a", "b", "c"), bands.keys)
    }

    @Test
    fun `colours are stable for the same document and apart from each other and from the outlines`() {
        assertEquals(layerGroupBands(two), layerGroupBands(two.toList()))
        assertEquals(layerGroupBands(two), layerGroupBands(two.reversed().reversed()))
        val reserved = listOf(HotPink, Cyan, White, Amber, NeonGreen)
        fun luma(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
        fun dist(a: Color, b: Color) = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)
        GROUP_PALETTE.forEach { c -> reserved.forEach { assertTrue("$c clashes with $it", dist(c, it) > 0.6f) } }
        GROUP_PALETTE.forEachIndexed { i, a ->
            GROUP_PALETTE.drop(i + 1).forEach { b -> assertTrue("$a vs $b", dist(a, b) > 0.45f || abs(luma(a) - luma(b)) > 0.12f) }
        }
        assertEquals(GROUP_PALETTE.size, GROUP_PALETTE.distinct().size)
    }

    @Test
    fun `a member's menu carries the group's actions`() {
        val profile = com.hereliesaz.graffitixr.common.scene.SurfaceProfile(listOf(0f, 0f, 0f, 100f, 0f, 0f))
        val layers = listOf(group("g").copy(surface = profile), leaf("a", "g"))
        val ui = EditorUiState(layers = layers, activeLayerId = "a")
        var rows: List<MenuRow> = emptyList()
        rule.setContent {
            val strings = rememberAppStrings()
            val vm = io.mockk.mockk<com.hereliesaz.graffitixr.feature.editor.EditorViewModel>(relaxed = true)
            rows = MenuBuilder().apply { layerMenu(layers[1], ui, LayerMenuEnv(vm, strings, {}, {}, {})) }.rows
        }
        rule.waitForIdle()
        val labels = rows.filterIsInstance<MenuRow.Action>().map { it.text } + rows.filterIsInstance<MenuRow.Check>().map { it.label }
        listOf("Draw surface", "Flatten surface", "Ungroup").forEach { want ->
            assertTrue("$want in $labels", labels.any { it.contains(want, ignoreCase = true) })
        }
        assertTrue(labels.any { it.contains("grid", ignoreCase = true) })
    }
}
