@file:Suppress("CyclomaticComplexMethod", "LongMethod", "LoopWithTooManyJumpStatements", "MagicNumber")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.isAdjustment
import com.hereliesaz.graffitixr.common.model.isContainer
import com.hereliesaz.graffitixr.common.scene.SceneGltfExporter
import com.hereliesaz.graffitixr.common.scene.SceneMath
import com.hereliesaz.graffitixr.common.scene.planeChain

/**
 * The whole 3D space as glTF input (spec/3d-space.md, "Export"): every visible paint, text and
 * vector layer as a textured quad at its placement, every 3D strokes layer as its tube mesh, every
 * 3D model layer as its mesh with UVs and painted texture, and
 * the camera when [includeCamera]. Vector layers are rasterized ([VectorRaster]).
 */
internal fun buildSceneGltf(
    state: EditorUiState,
    w: Float,
    h: Float,
    includeCamera: Boolean,
): SceneGltfExporter.Scene {
    val byId = state.layers.associateBy { it.id }
    fun chainVisible(layer: Layer): Boolean {
        var current: Layer? = layer
        val seen = HashSet<String>()
        while (current != null && seen.add(current.id)) {
            if (!current.isVisible) return false
            current = current.parentId?.let(byId::get)
        }
        return true
    }
    fun chainOpacity(layer: Layer): Float {
        var current: Layer? = layer
        var alpha = 1f
        val seen = HashSet<String>()
        while (current != null && seen.add(current.id)) {
            alpha *= current.opacity
            current = current.parentId?.let(byId::get)
        }
        return alpha
    }
    val quads = ArrayList<SceneGltfExporter.Quad>()
    val strokes = ArrayList<SceneGltfExporter.Strokes>()
    val models = ArrayList<SceneGltfExporter.Model>()
    for (layer in state.layers) {
        if (layer.isContainer || !chainVisible(layer)) continue
        val model = SceneMath.chainModel(
            planeChain(state.layers, layer.id), w, h,
        )
        val content = layer.model
        if (content != null) {
            val mesh = content.mesh ?: continue
            models += SceneGltfExporter.Model(
                layer.name, mesh.mesh,
                model * com.hereliesaz.graffitixr.common.scene.ModelSpace.meshToLocal(mesh, w, h),
                content.texture?.pixels()?.let(::modelTexturePng),
                chainOpacity(layer),
            )
            continue
        }
        val spatial = layer.spatial
        if (spatial != null) {
            if (spatial.strokes.isNotEmpty()) {
                strokes += SceneGltfExporter.Strokes(
                    layer.name, spatial.strokes,
                    model * SceneMath.strokeToLocal(w, h),
                )
            }
            continue
        }
        if (layer.isAdjustment) continue
        // A vector layer goes out as its shapes rasterized by the canvas's own drawing code, on a
        // quad covering them (spec/3d-space.md, "Export", for why not tessellated).
        val vector = if (layer.shapes.isNotEmpty()) VectorRaster.rasterize(layer, w, h) else null
        val bitmap = vector?.bitmap ?: layer.bitmap?.takeUnless { it.isRecycled } ?: continue
        val rect = vector?.rect
            ?: com.hereliesaz.graffitixr.common.scene.DepthComposite.imageRect(layer, bitmap.width, bitmap.height, w, h)
            ?: continue
        val left = rect[0]
        val top = rect[1]
        val right = rect[2]
        val bottom = rect[3]
        val png = java.io.ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        vector?.bitmap?.recycle()
        quads += SceneGltfExporter.Quad(
            layer.name, png,
            listOf(
                model.apply(left, top, 0f), model.apply(right, top, 0f),
                model.apply(right, bottom, 0f), model.apply(left, bottom, 0f),
            ),
            chainOpacity(layer),
        )
    }
    return SceneGltfExporter.Scene(
        w, h, quads, strokes, state.sceneCamera.takeIf { includeCamera }, models,
    )
}

/** A model's paint over its base colour, as the canvas shows it, as an opaque PNG. */
internal fun modelTexturePng(paint: Bitmap): ByteArray {
    val c = SceneGltfExporter.MODEL_BASE_COLOR
    val out = Bitmap.createBitmap(paint.width, paint.height, Bitmap.Config.ARGB_8888)
    out.eraseColor(android.graphics.Color.rgb((c[0] * 255).toInt(), (c[1] * 255).toInt(), (c[2] * 255).toInt()))
    android.graphics.Canvas(out).drawBitmap(paint, 0f, 0f, null)
    return java.io.ByteArrayOutputStream().also {
        out.compress(Bitmap.CompressFormat.PNG, 100, it)
        out.recycle()
    }.toByteArray()
}
