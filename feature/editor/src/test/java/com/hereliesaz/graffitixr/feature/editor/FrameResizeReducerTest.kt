package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.AutoLayout
import com.hereliesaz.graffitixr.common.model.ConstraintAnchor
import com.hereliesaz.graffitixr.common.model.Constraints
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.LayoutDirection
import com.hereliesaz.graffitixr.common.model.ShapeKind
import com.hereliesaz.graffitixr.common.model.VectorShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** [EditorIntent.ResizeFrame]: a frame's box changes and its children follow in the same transition. */
class FrameResizeReducerTest {

    private fun frame(layout: AutoLayout = AutoLayout(), w: Float = 400f, h: Float = 200f) = Layer(
        id = "F", name = "F", type = LayerType.GROUP, autoLayout = layout, layoutWidth = w, layoutHeight = h,
    )

    // In the frame's local space the 400x200 box runs -200..200 x -100..100.
    private fun shape(id: String, x: Float, y: Float, size: Pair<Float, Float>, c: Constraints) = Layer(
        id = id, name = id, parentId = "F", offset = Offset(x, y), constraints = c,
        shapes = listOf(VectorShape(kind = ShapeKind.RECTANGLE, width = size.first, height = size.second)),
    )

    private fun reduce(vararg layers: Layer, w: Float = 800f, h: Float = 400f) = EditorReducer.reduce(
        EditorUiState(layers = layers.toList(), activeLayerId = "F"),
        EditorIntent.ResizeFrame("F", w, h),
    ).layers

    @Test
    fun `resizing a frame sets its layout size and runs its children's constraints`() {
        // Right-pinned child: right edge 20px in from the frame's right edge (200 - 20 - 50 = 130).
        val out = reduce(
            frame(),
            shape("end", 130f, 0f, 100f to 50f, Constraints(horizontal = ConstraintAnchor.END)),
            shape("fill", 0f, 0f, 360f to 180f, Constraints(ConstraintAnchor.STRETCH, ConstraintAnchor.STRETCH)),
        )
        val f = out.first { it.id == "F" }
        assertEquals(800f, f.layoutWidth, 0.01f)
        assertEquals(400f, f.layoutHeight, 0.01f)
        // New box -400..400: still 20px from the right edge.
        assertEquals(330f, out.first { it.id == "end" }.offset.x, 0.01f)
        val fill = out.first { it.id == "fill" }.shapes.first()
        assertEquals(760f, fill.width, 0.01f)
        assertEquals(380f, fill.height, 0.01f)
    }

    @Test
    fun `an auto-layout frame is laid out instead of constrained`() {
        val row = AutoLayout(direction = LayoutDirection.HORIZONTAL)
        val out = reduce(
            frame(row).copy(offset = Offset(5f, 7f)),
            shape("a", 999f, 999f, 100f to 50f, Constraints(horizontal = ConstraintAnchor.END)),
        )
        // Laid out from the frame's origin (auto-layout's convention), ignoring the END constraint.
        assertEquals(5f, out.first { it.id == "a" }.offset.x, 0.01f)
        assertEquals(7f, out.first { it.id == "a" }.offset.y, 0.01f)
    }

    @Test
    fun `an undeclared frame is taken to have been document-sized`() {
        val s = EditorUiState(
            layers = listOf(
                frame(w = 0f, h = 0f),
                shape("a", 0f, 0f, 100f to 50f, Constraints(horizontal = ConstraintAnchor.SCALE)),
            ),
        )
        val intent = EditorIntent.ResizeFrame("F", s.documentWidth * 2f, s.documentHeight.toFloat())
        val out = EditorReducer.reduce(s, intent)
        assertEquals(200f, out.layers.first { it.id == "a" }.shapes.first().width, 0.01f)
    }

    @Test
    fun `a non-positive size is ignored`() {
        val s = EditorUiState(layers = listOf(frame()))
        assertSame(s, EditorReducer.reduce(s, EditorIntent.ResizeFrame("F", 0f, 10f)))
    }

    @Test
    fun `SetLayerGeometry applies a peer's shapes and layout size`() {
        val shapes = listOf(VectorShape(kind = ShapeKind.ELLIPSE, width = 12f, height = 34f))
        val out = EditorReducer.reduce(
            EditorUiState(layers = listOf(frame())),
            EditorIntent.SetLayerGeometry("F", shapes, 10f, 20f),
        ).layers.first()
        assertEquals(shapes, out.shapes)
        assertEquals(10f, out.layoutWidth, 0f)
        assertEquals(20f, out.layoutHeight, 0f)
    }
}
