@file:Suppress("MaxLineLength", "LongParameterList", "ReturnCount")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.setFrom
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerNode
import com.hereliesaz.graffitixr.common.scene.RestGate
import com.hereliesaz.graffitixr.common.scene.SplitCache
import com.hereliesaz.graffitixr.common.scene.StackSplit
import kotlin.math.roundToInt

/**
 * The 2D inactive-layer cache (spec/3d-space.md, "Inactive-layer cache"): while a stroke is in
 * progress, the page and the top-level subtrees under the active one are drawn from one offscreen
 * [GraphicsLayer] (an HWUI hardware layer: a GPU render target), and the subtrees over it from a
 * second. Each frame then draws behind + the live active layer + front; HWUI re-renders a render
 * only when something inside it is damaged.
 *
 * The render is recorded through the canvas view's transform ([ViewTransform]) and drawn back
 * through its inverse, so its texels are screen pixels: zoom and rotation sample the layer bitmaps
 * once, exactly as the uncached stack does. A change of view therefore bypasses the cache until
 * the view rests ([RestGate]); a canvas resize keeps the render and draws it scaled.
 */
internal class StackLayerCache(private val graphics: GraphicsContext) {
    private class Render(val layer: GraphicsLayer, val size: IntSize)

    private val slots = SplitCache<Render> { graphics.releaseGraphicsLayer(it.layer) }
    private val gates = SplitCache.Slot.entries.associateWith { RestGate() }

    /** The canvas view's transform (the viewport `graphicsLayer`, origin at the top left). */
    data class ViewTransform(val zoom: Float, val rotation: Float, val offsetX: Float, val offsetY: Float) {
        fun matrix(): android.graphics.Matrix = android.graphics.Matrix().apply {
            setScale(zoom, zoom)
            postRotate(rotation)
            postTranslate(offsetX, offsetY)
        }
    }

    /**
     * Draws the content from [slot]'s render while [key] and [view] hold; directly when [enabled]
     * is false (this side cannot be cached) or the view is moving. Only a stroke in progress
     * ([drawing]) builds a render.
     */
    fun Modifier.cachedSide(
        slot: SplitCache.Slot,
        activeId: String,
        enabled: Boolean,
        key: Any,
        view: ViewTransform,
        drawing: () -> Boolean,
    ): Modifier = drawWithContent {
        slots.select(activeId)
        if (!enabled || !gates.getValue(slot).settled(view)) {
            slots.dump(slot)
            drawContent()
            return@drawWithContent
        }
        val toScreen = view.matrix()
        val render = slots.get(slot, listOf(key, view)) {
            if (!drawing()) return@get null
            val size = IntSize(size.width.roundToInt(), size.height.roundToInt())
            if (size.width <= 0 || size.height <= 0) return@get null
            val layer = graphics.createGraphicsLayer()
            layer.compositingStrategy = CompositingStrategy.Offscreen
            val m = Matrix().apply { setFrom(toScreen) }
            layer.record(size = size) {
                withTransform({ transform(m) }) { this@drawWithContent.drawContent() }
            }
            Render(layer, size)
        }
        if (render == null) {
            drawContent()
            return@drawWithContent
        }
        val toLocal = android.graphics.Matrix().also { toScreen.invert(it) }
        val back = Matrix().apply { setFrom(toLocal) }
        withTransform({ transform(back) }) {
            // A resize keeps the render and scales it into the new box.
            scale(size.width / render.size.width, size.height / render.size.height, Offset.Zero) {
                drawLayer(render.layer)
            }
        }
    }

    fun dumpAll() = slots.dumpAll()

    companion object {
        /** A flat, single-tile, uncropped, non-animated stack: the only one the 2D cache serves. */
        fun applies(state: com.hereliesaz.graffitixr.common.model.EditorUiState, scene: SceneContext?): Boolean =
            !state.wrapAroundMode && !state.isAnimationMode && state.documentCrop.isIdentity &&
                scene != null && scene.sceneIds.isEmpty() && scene.depth.isEmpty

        /** True when every layer of the subtree composites as plain source-over. */
        fun plainSourceOver(subtree: List<Layer>): Boolean = subtree.all {
            it.blendMode == BlendMode.SrcOver && !it.clipToLayerBelow && it.adjustment == null
        }

        /** The split of a top-level list around the subtree holding [activeId]; null when it has none. */
        fun split(tree: List<LayerNode>, activeId: String?): StackSplit? {
            if (activeId == null) return null
            val top = tree.firstOrNull { node -> flatten(node).any { it.id == activeId } } ?: return null
            val byId = tree.associateBy { it.layer.id }
            return StackSplit.plan(tree.map { it.layer.id }, top.layer.id) { id -> byId[id]?.let { plainSourceOver(flatten(it)) } == true }
        }

        fun flatten(node: LayerNode): List<Layer> = buildList {
            fun walk(n: LayerNode) {
                add(n.layer)
                n.children.forEach(::walk)
            }
            walk(node)
        }
    }
}
