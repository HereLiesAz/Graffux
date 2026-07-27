package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Animation Assist's frame model: a frame IS a top-level layer, so none of this needs a separate
 * frame list — it's all derived from the layer stack.
 */
class AnimationFramesTest {

    private fun layer(id: String, parentId: String? = null, type: LayerType = LayerType.RASTER) =
        Layer(id = id, name = id, parentId = parentId, type = type)

    @Test
    fun `frames are the top-level layers, in order`() {
        val layers = listOf(
            layer("f1"),
            layer("g", type = LayerType.GROUP),
            layer("g-child", parentId = "g"),
            layer("f3"),
        )
        assertEquals(listOf("f1", "g", "f3"), AnimationFrames.topLevelFrames(layers).map { it.id })
    }

    @Test
    fun `a group frame's subtree includes its children`() {
        val layers = listOf(
            layer("g", type = LayerType.GROUP),
            layer("a", parentId = "g"),
            layer("b", parentId = "g"),
            layer("other"),
        )
        assertEquals(setOf("g", "a", "b"), AnimationFrames.frameSubtreeIds(layers, "g"))
        assertEquals(setOf("other"), AnimationFrames.frameSubtreeIds(layers, "other"))
    }

    @Test
    fun `frameIndexForLayer walks a nested layer up to its frame`() {
        val layers = listOf(
            layer("f0"),
            layer("g", type = LayerType.GROUP),
            layer("deep", parentId = "g"),
        )
        assertEquals(0, AnimationFrames.frameIndexForLayer(layers, "f0"))
        assertEquals(1, AnimationFrames.frameIndexForLayer(layers, "g"))
        // The point of this: selecting a layer inside a group frame must land on THAT frame, not 0.
        assertEquals(1, AnimationFrames.frameIndexForLayer(layers, "deep"))
        assertEquals(0, AnimationFrames.frameIndexForLayer(layers, null))
    }

    @Test
    fun `a group frame delegates drawing to its topmost child`() {
        val layers = listOf(
            layer("plain"),
            layer("g", type = LayerType.GROUP),
            layer("under", parentId = "g"),
            layer("over", parentId = "g"),
        )
        assertEquals("plain", AnimationFrames.drawableLayerForFrame(layers, 0))
        // "over" is last in the list, i.e. topmost — a group itself can't receive paint.
        assertEquals("over", AnimationFrames.drawableLayerForFrame(layers, 1))
        assertNull(AnimationFrames.drawableLayerForFrame(layers, 99))
    }

    @Test
    fun `an empty group frame has nothing to draw on`() {
        val layers = listOf(layer("g", type = LayerType.GROUP))
        assertNull(AnimationFrames.drawableLayerForFrame(layers, 0))
    }

    @Test
    fun `without onion skin only the active frame is visible`() {
        val layers = listOf(layer("a"), layer("b"), layer("c"))
        val alphas = AnimationFrames.effectiveOpacities(layers, activeFrameIndex = 1, onionSkinEnabled = false, onionSkinFrameCount = 2)
        assertEquals(0f, alphas["a"]!!, 0f)
        assertEquals(1f, alphas["b"]!!, 0f)
        assertEquals(0f, alphas["c"]!!, 0f)
    }

    @Test
    fun `onion skin fades neighbours and hides frames beyond the count`() {
        val layers = listOf(layer("a"), layer("b"), layer("c"), layer("d"))
        val alphas = AnimationFrames.effectiveOpacities(layers, activeFrameIndex = 2, onionSkinEnabled = true, onionSkinFrameCount = 1)
        assertEquals(1f, alphas["c"]!!, 0f)
        // One frame away on each side: faded but visible.
        assertTrue(alphas["b"]!! > 0f && alphas["b"]!! < 1f)
        assertTrue(alphas["d"]!! > 0f && alphas["d"]!! < 1f)
        // Two away, with a count of 1: hidden.
        assertEquals(0f, alphas["a"]!!, 0f)
    }

    @Test
    fun `onion skin applies a frame's alpha to its whole subtree`() {
        val layers = listOf(
            layer("f0"),
            layer("g", type = LayerType.GROUP),
            layer("child", parentId = "g"),
        )
        val alphas = AnimationFrames.effectiveOpacities(layers, activeFrameIndex = 1, onionSkinEnabled = true, onionSkinFrameCount = 1)
        assertEquals(alphas["g"], alphas["child"])
        assertEquals(1f, alphas["g"]!!, 0f)
    }

    // ── Reducer transitions ──────────────────────────────────────────────────────────────────

    @Test
    fun `entering animation mode syncs the cursor to the active layer's frame`() {
        val state = EditorUiState(
            layers = listOf(layer("f0"), layer("g", type = LayerType.GROUP), layer("deep", parentId = "g")),
            activeLayerId = "deep",
        )
        val on = EditorReducer.reduce(state, EditorIntent.ToggleAnimationMode)
        assertTrue(on.isAnimationMode)
        assertEquals(1, on.activeFrameIndex)
        assertFalse(EditorReducer.reduce(on, EditorIntent.ToggleAnimationMode).isAnimationMode)
    }

    @Test
    fun `SetActiveFrameIndex only retargets the active layer when asked`() {
        val state = EditorUiState(layers = listOf(layer("a"), layer("b")), activeLayerId = "a")

        val playback = EditorReducer.reduce(state, EditorIntent.SetActiveFrameIndex(1))
        assertEquals(1, playback.activeFrameIndex)
        // Playback moves the cursor only — it must not steal the user's layer selection.
        assertEquals("a", playback.activeLayerId)

        val stepped = EditorReducer.reduce(state, EditorIntent.SetActiveFrameIndex(1, followActiveLayer = true))
        assertEquals("b", stepped.activeLayerId)
    }

    @Test
    fun `stepping frames does not put the brush down`() {
        val state = EditorUiState(
            layers = listOf(layer("a"), layer("b")),
            activeLayerId = "a",
            activeTool = com.hereliesaz.graffitixr.common.model.Tool.BRUSH,
        )
        val stepped = EditorReducer.reduce(state, EditorIntent.SetActiveFrameIndex(1, followActiveLayer = true))
        assertEquals(com.hereliesaz.graffitixr.common.model.Tool.BRUSH, stepped.activeTool)
    }

    @Test
    fun `onion skin count is clamped to a usable range`() {
        val s = EditorUiState()
        assertEquals(1, EditorReducer.reduce(s, EditorIntent.SetOnionSkinFrameCount(0)).onionSkinFrameCount)
        assertEquals(5, EditorReducer.reduce(s, EditorIntent.SetOnionSkinFrameCount(99)).onionSkinFrameCount)
    }
}
