@file:Suppress("LongParameterList", "CyclomaticComplexMethod", "ReturnCount", "MaxLineLength", "LoopWithTooManyJumpStatements", "MagicNumber")

package com.hereliesaz.graffitixr.feature.editor.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerNode
import com.hereliesaz.graffitixr.common.model.buildLayerTree
import com.hereliesaz.graffitixr.common.model.isContainer
import com.hereliesaz.graffitixr.common.scene.DepthComposite
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SceneMath
import com.hereliesaz.graffitixr.common.scene.SceneRenderPath
import com.hereliesaz.graffitixr.common.scene.planeChain
import com.hereliesaz.graffitixr.feature.editor.DepthScene
import com.hereliesaz.graffitixr.feature.editor.VectorRaster

/**
 * The PNG/PDF composite of a document in 3D (spec/3d-space.md, "Export"): what the canvas shows
 * from the current camera, at the canvas size, before the artboard crop.
 *
 * The depth-pass members are rendered by the same GPU [DepthScene] pass as on screen, off screen,
 * and drawn at the pass's stack position. Every other layer is drawn as the canvas draws it on the
 * scene path: its bitmap through its plane-to-canvas homography (Skia renders perspective bitmaps),
 * groups composited with their opacity and blend, a frame's fill and clip through the frame's own
 * homography. A flat document never comes here.
 */
internal class SceneExport(private val manager: ExportManager) {
    private val vectorImages = HashMap<String, VectorRaster.Image?>()

    fun draw(canvas: Canvas, layers: List<Layer>, w: Int, h: Int, camera: SceneCamera, target: Bitmap?) {
        val plan = DepthComposite.plan(layers, camera)
        val sceneIds = SceneRenderPath.sceneLayerIds(layers, camera)
        val ctx = Ctx(layers, w, h, camera, plan, sceneIds, target)
        for (node in buildLayerTree(layers)) {
            if (node.layer.id == plan.anchorTopLevelId) drawDepthPass(canvas, ctx)
            drawNode(canvas, node, ctx)
        }
        vectorImages.values.forEach { it?.bitmap?.recycle() }
        vectorImages.clear()
    }

    private class Ctx(
        val layers: List<Layer>,
        val w: Int,
        val h: Int,
        val camera: SceneCamera,
        val plan: DepthComposite,
        val sceneIds: Set<String>,
        val target: Bitmap?,
    ) {
        val frame = SceneMath.frame(camera, w.toFloat(), h.toFloat())
    }

    private fun vector(layer: Layer, ctx: Ctx): VectorRaster.Image? =
        vectorImages.getOrPut(layer.id) { VectorRaster.rasterize(layer, ctx.w.toFloat(), ctx.h.toFloat()) }

    /** Box-local to canvas, through the layer's whole chain of planes. */
    private fun homography(layer: Layer, ctx: Ctx): Matrix? {
        val hm = SceneMath.homography(SceneMath.chainModel(planeChain(ctx.layers, layer.id), ctx.w.toFloat(), ctx.h.toFloat()), ctx.frame)
        // A plane crossing the eye cannot be one perspective draw; clip to the part in front.
        return Matrix().apply { setValues(hm) }
    }

    private fun clipInFront(canvas: Canvas, layer: Layer, ctx: Ctx) {
        val hm = SceneMath.homography(SceneMath.chainModel(planeChain(ctx.layers, layer.id), ctx.w.toFloat(), ctx.h.toFloat()), ctx.frame)
        val poly = SceneMath.visiblePolygon(hm, ctx.frame, CLIP_EXTENT * maxOf(ctx.w, ctx.h)) ?: return
        val path = android.graphics.Path()
        poly.forEachIndexed { i, (u, v) -> if (i == 0) path.moveTo(u, v) else path.lineTo(u, v) }
        path.close()
        canvas.clipPath(path)
    }

    private fun drawNode(canvas: Canvas, node: LayerNode, ctx: Ctx) {
        val layer = node.layer
        if (!layer.isVisible || layer.id in ctx.plan) return
        if (layer.adjustment != null && ctx.target != null && manager.drawAdjustment(canvas, layer, ctx.target)) return
        if (layer.isContainer) {
            val groupBmp = Bitmap.createBitmap(ctx.w, ctx.h, Bitmap.Config.ARGB_8888)
            val groupCanvas = Canvas(groupBmp)
            // The frame's fill and clip through the frame's plane; the clip stays for its children
            // while the matrix is undone (each child draws through its own whole chain).
            val m = homography(layer, ctx)
            val inverse = Matrix()
            if (m != null && m.invert(inverse)) {
                groupCanvas.concat(m)
                manager.frameBox(groupCanvas, layer, ctx.w, ctx.h)
                groupCanvas.concat(inverse)
            }
            node.children.forEach { drawNode(groupCanvas, it, ctx.copyTarget(groupBmp)) }
            canvas.drawBitmap(groupBmp, 0f, 0f, manager.groupPaint(layer))
            groupBmp.recycle()
            return
        }
        val (bitmap, rect) = if (layer.shapes.isNotEmpty()) {
            val image = vector(layer, ctx) ?: return
            image.bitmap to image.rect
        } else {
            val b = layer.bitmap ?: return
            b to (DepthComposite.imageRect(layer, b.width, b.height, ctx.w.toFloat(), ctx.h.toFloat()) ?: return)
        }
        val m = homography(layer, ctx) ?: return
        val place = Matrix().apply {
            setScale((rect[2] - rect[0]) / bitmap.width, (rect[3] - rect[1]) / bitmap.height)
            postTranslate(rect[0], rect[1])
            postConcat(m)
        }
        val save = canvas.save()
        canvas.concat(m)
        clipInFront(canvas, layer, ctx)
        val inverse = Matrix()
        if (m.invert(inverse)) canvas.concat(inverse)
        canvas.drawBitmap(bitmap, place, manager.layerPaint(layer))
        canvas.restoreToCount(save)
    }

    private fun Ctx.copyTarget(target: Bitmap?) = Ctx(layers, w, h, camera, plan, sceneIds, target?.takeIf { this.target != null })

    private fun drawDepthPass(canvas: Canvas, ctx: Ctx) {
        val scene = DepthScene.build(
            ctx.layers, ctx.plan, ctx.camera, ctx.w.toFloat(), ctx.h.toFloat(),
            pixels = { layer -> layer.bitmap?.takeUnless { it.isRecycled }?.let { it to 0L } },
            vectors = { vector(it, ctx) },
        )
        val rendered = manager.depthRenderer(scene, ctx.w, ctx.h)
        if (rendered != null) {
            canvas.drawBitmap(rendered, 0f, 0f, null)
            rendered.recycle()
            return
        }
        // No GLES (tests): the planes farthest-first through their homographies. Crossing planes
        // then overlap by centre depth rather than per pixel, and 3D ink, which only the GPU pass
        // draws, is absent.
        val depths = scene.planes.associate { p ->
            p.id to scene.camera.toCamera(p.model.apply((p.rect[0] + p.rect[2]) / 2f, (p.rect[1] + p.rect[3]) / 2f, 0f))[2]
        }
        val byId = ctx.layers.associateBy { it.id }
        for (id in DepthComposite.backToFront(depths, scene.planes.map { it.id })) {
            val plane = scene.planes.first { it.id == id }
            val layer = byId[id] ?: continue
            val m = homography(layer, ctx) ?: continue
            val place = Matrix().apply {
                setScale((plane.rect[2] - plane.rect[0]) / plane.bitmap.width, (plane.rect[3] - plane.rect[1]) / plane.bitmap.height)
                postTranslate(plane.rect[0], plane.rect[1])
                postConcat(m)
            }
            canvas.drawBitmap(plane.bitmap, place, manager.layerPaint(layer, plane.alpha))
        }
    }

    companion object {
        private const val CLIP_EXTENT = 8f

        /** Anything to do beyond the flat composite: a layer placed in 3D, a moved camera, 3D ink. */
        fun needed(layers: List<Layer>, camera: SceneCamera): Boolean =
            !DepthComposite.plan(layers, camera).isEmpty || SceneRenderPath.sceneLayerIds(layers, camera).isNotEmpty()
    }
}
