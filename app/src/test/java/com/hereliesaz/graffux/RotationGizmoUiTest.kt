@file:Suppress("MaxLineLength")

package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.hereliesaz.graffitixr.common.model.RotationAxis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rotation triangle in a real Compose tree (xhdpi, so 1dp = 2px): only its three 48dp touch bands take
 * pointer input, a drag turns by its length, and each edge is an accessibility node with +/- 15 degree actions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class RotationGizmoUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var canvasDowns = 0
    private val turns = mutableListOf<Pair<RotationAxis, Float>>()
    private var ends = 0
    private val steps = mutableListOf<Pair<RotationAxis, Float>>()

    private fun show() {
        rule.setContent {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); canvasDowns++ } },
                contentAlignment = Alignment.Center,
            ) {
                RotationGizmo({ a, d -> turns += a to d }, { ends++ }, { a, d -> steps += a to d })
            }
        }
    }

    // Triangle coordinates (px) to the gizmo box's: the box has a half band (48px) all round.
    private fun at(x: Float, y: Float) = Offset(x + 48f, y + 48f)
    private val w = 352f
    private val h = 240f

    @Test
    fun `a touch in the interior or outside the bands reaches the canvas, not the gizmo`() {
        show()
        rule.onNodeWithTag("rotationGizmo").performTouchInput {
            val inner = at(w - 80f, h - 80f) // the incentre of a 352 x 240 triangle is 80 from each leg
            down(inner); up()
        }
        rule.onNodeWithTag("rotationGizmo").performTouchInput { down(Offset(4f, 4f)); up() } // the box's corner
        rule.waitForIdle()
        assertEquals(2, canvasDowns)
        assertTrue(turns.isEmpty())
        assertEquals(0, ends)
    }

    @Test
    fun `a touch on a band is the gizmo's, and does not reach the canvas`() {
        show()
        rule.onNodeWithTag("rotationGizmo").performTouchInput {
            down(at(w / 2f, h)); moveBy(Offset(50f, 0f)); up()
        }
        rule.waitForIdle()
        assertEquals(0, canvasDowns)
        assertEquals(1, ends)
    }

    @Test
    fun `dragging the length of the bottom leg turns X by 180, the side Y, the diagonal Z`() {
        show()
        rule.onNodeWithTag("rotationGizmo").performTouchInput {
            down(at(0f, h)); moveTo(at(w, h)); up()
        }
        rule.waitForIdle()
        assertTrue(turns.all { it.first == RotationAxis.X })
        assertEquals(180f, turns.sumOf { it.second.toDouble() }.toFloat(), 0.5f)
        turns.clear()
        rule.onNodeWithTag("rotationGizmo").performTouchInput {
            // Not exactly on the corner (a tie there goes to the bottom leg): 30px up the side.
            down(at(w, h - 30f)); moveTo(at(w, 0f)); up()
        }
        rule.waitForIdle()
        assertTrue(turns.isNotEmpty() && turns.all { it.first == RotationAxis.Y })
        assertEquals(157.5f, turns.sumOf { it.second.toDouble() }.toFloat(), 0.5f)
        turns.clear()
        rule.onNodeWithTag("rotationGizmo").performTouchInput {
            down(at(w / 2f, h / 2f)); moveTo(at(0f, h)); up() // down-left along the diagonal: negative Z
        }
        rule.waitForIdle()
        assertTrue(turns.isNotEmpty() && turns.all { it.first == RotationAxis.Z })
        assertEquals(-90f, turns.sumOf { it.second.toDouble() }.toFloat(), 0.5f)
        assertEquals(3, ends)
    }

    @Test
    fun `a drag stays locked to its edge and a second swipe adds on`() {
        show()
        repeat(2) {
            rule.onNodeWithTag("rotationGizmo").performTouchInput {
                down(at(0f, h)); moveTo(at(w / 2f, h - 30f)); moveTo(at(w, h - 90f)); up()
            }
        }
        rule.waitForIdle()
        assertTrue(turns.all { it.first == RotationAxis.X })
        assertEquals(360f, turns.sumOf { it.second.toDouble() }.toFloat(), 1f)
        assertEquals(2, ends)
    }

    @Test
    fun `each edge is a named node with plus and minus 15 degree actions`() {
        show()
        for (axis in RotationAxis.entries) {
            val node = rule.onNodeWithContentDescription("Rotate ${axis.name}").fetchSemanticsNode()
            val actions = node.config[SemanticsActions.CustomActions]
            assertEquals(2, actions.size)
            rule.runOnIdle { actions[0].action(); actions[1].action() }
        }
        assertEquals(
            listOf(
                RotationAxis.X to 15f, RotationAxis.X to -15f,
                RotationAxis.Y to 15f, RotationAxis.Y to -15f,
                RotationAxis.Z to 15f, RotationAxis.Z to -15f,
            ),
            steps,
        )
    }

    @Test
    fun `the triangle rides the sheet's top edge, and sits on the safe area with no sheet`() {
        // 2000px tall, safe area 100px, gap 16px.
        assertEquals(1900f, gizmoBottomY(2000f, 100f, Float.NaN, 16f), 0f)
        assertEquals(1184f, gizmoBottomY(2000f, 100f, 1200f, 16f), 0f)
        assertEquals(584f, gizmoBottomY(2000f, 100f, 600f, 16f), 0f)
        // A sheet shut below the safe line never pushes it under it.
        assertEquals(1900f, gizmoBottomY(2000f, 100f, 1990f, 16f), 0f)
    }
}
