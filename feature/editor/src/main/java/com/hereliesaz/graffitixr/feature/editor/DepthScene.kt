@file:Suppress("LoopWithTooManyJumpStatements")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.mesh.SpatialStroke
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.scene.DepthComposite
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SceneMath
import com.hereliesaz.graffitixr.common.scene.planeChain
import com.hereliesaz.graffitixr.feature.editor.threed.DepthCompositor

/**
 * The GPU depth pass's input for a layer list (spec/3d-space.md, "Per-pixel depth"), built the same
 * way for the canvas and for the PNG/PDF export so both show the same scene.
 */
internal object DepthScene {
    /** A layer's current pixels and a token that changes with them (a live stroke's version). */
    fun interface Pixels {
        fun of(layer: Layer): Pair<Bitmap, Long>?
    }

    @Suppress("LongParameterList")
    fun build(
        layers: List<Layer>,
        plan: DepthComposite,
        camera: SceneCamera,
        width: Float,
        height: Float,
        pixels: Pixels,
        vectors: (Layer) -> VectorRaster.Image?,
        draft: Pair<String, SpatialStroke>? = null,
    ): DepthCompositor.Scene {
        val byId = layers.associateBy { it.id }
        val planes = ArrayList<DepthCompositor.Plane>()
        val inks = ArrayList<DepthCompositor.Ink>()
        for (id in plan.memberIds) {
            val layer = byId[id] ?: continue
            val model = SceneMath.chainModel(planeChain(layers, id), width, height)
            val alpha = DepthComposite.effectiveOpacity(layers, id)
            val spatial = layer.spatial
            if (spatial != null) {
                val live = draft?.takeIf { it.first == id }?.second
                inks += DepthCompositor.Ink(
                    id, if (live == null) spatial.strokes else spatial.strokes + live,
                    model * SceneMath.strokeToLocal(width, height), alpha,
                )
                continue
            }
            val (bitmap, token, rect) = if (layer.shapes.isNotEmpty()) {
                val image = vectors(layer) ?: continue
                Triple(image.bitmap, layer.shapes.hashCode().toLong(), image.rect)
            } else {
                val (bmp, tok) = pixels.of(layer) ?: continue
                val r = DepthComposite.imageRect(layer, bmp.width, bmp.height, width, height) ?: continue
                Triple(bmp, tok, r)
            }
            planes += DepthCompositor.Plane(id, bitmap, token, model, rect, colorMatrixOf(layer), alpha)
        }
        return DepthCompositor.Scene(SceneMath.frame(camera, width, height), planes, inks)
    }

    /** The canvas's colour adjustment for [layer]; null when it changes nothing. */
    fun colorMatrixOf(layer: Layer): FloatArray? {
        val neutral = layer.saturation == 1f && layer.contrast == 1f && layer.brightness == 0f &&
            layer.colorBalanceR == 1f && layer.colorBalanceG == 1f && layer.colorBalanceB == 1f && !layer.isInverted
        if (neutral) return null
        return createColorMatrix(
            saturation = layer.saturation,
            contrast = layer.contrast,
            brightness = layer.brightness,
            colorBalanceR = layer.colorBalanceR,
            colorBalanceG = layer.colorBalanceG,
            colorBalanceB = layer.colorBalanceB,
            isInverted = layer.isInverted,
        ).values
    }
}
