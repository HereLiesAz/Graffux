@file:Suppress("MagicNumber")

package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The trash sits at the foot of the layers rail; a layer thumbnail dragged onto it is deleted. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class LayersRailTrashUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val deleted = mutableListOf<String>()
    private val relocated = mutableListOf<List<String>>()

    private fun show() {
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                LayersRail(
                    layers = listOf(Layer(id = "a", name = "A"), Layer(id = "b", name = "B")),
                    activeLayerId = "a", secondary = emptySet(), expansion = emptyMap(),
                    callbacks = LayersRailCallbacks(
                        onActivate = {}, onOpenMenu = {}, onExpand = { _, _ -> },
                        onRelocate = { order, _ -> relocated += order }, onAdd = {},
                        onDelete = { deleted += it },
                    ),
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 8.dp),
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `dragging a thumbnail onto the trash deletes that layer`() {
        show()
        val thumb = rule.onNodeWithTag("rail.layer.b")
        val thumbOrigin = thumb.fetchSemanticsNode().positionOnScreen
        thumb.performTouchInput {
            down(center)
            advanceEventTime(800)
            moveBy(Offset(-40f, 40f))
        }
        rule.waitForIdle()
        val trashNode = rule.onNodeWithTag("rail.layer.trash", useUnmergedTree = true).fetchSemanticsNode()
        val trash = androidx.compose.ui.geometry.Rect(
            trashNode.positionOnScreen,
            androidx.compose.ui.geometry.Size(trashNode.size.width.toFloat(), trashNode.size.height.toFloat()),
        )
        thumb.performTouchInput {
            moveTo(trash.center - thumbOrigin)
            moveTo(trash.center - thumbOrigin + Offset(1f, 1f))
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf("b"), deleted)
        assertTrue(relocated.isEmpty())
        // Still there for the next one.
        assertEquals(1, rule.onAllNodesWithTagCount("rail.layer.trash"))
    }

    @Test
    fun `the trash is always at the bottom of the layers rail`() {
        show()
        // No drag in progress: it is there anyway.
        assertEquals(1, rule.onAllNodesWithTagCount("rail.layer.trash"))
        val rail = rule.onNodeWithTag("rail.layers").fetchSemanticsNode().boundsInRoot
        val trash = rule.onNodeWithTag("rail.layer.trash", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val lastLayer = rule.onNodeWithTag("rail.layer.a").fetchSemanticsNode().boundsInRoot
        assertTrue("inside the rail", rail.contains(trash.center))
        assertTrue("below every layer", trash.top >= lastLayer.bottom)
    }

    @Test
    fun `a drag that ends away from the trash deletes nothing`() {
        show()
        rule.onNodeWithTag("rail.layer.b").performTouchInput {
            down(center)
            advanceEventTime(800)
            moveBy(Offset(0f, -60f))
            moveBy(Offset(0f, -60f))
            up()
        }
        rule.waitForIdle()
        assertTrue(deleted.isEmpty())
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
}
