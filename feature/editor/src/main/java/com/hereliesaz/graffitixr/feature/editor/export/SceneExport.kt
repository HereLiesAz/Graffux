@file:Suppress("LongParameterList", "CyclomaticComplexMethod", "ReturnCount", "MaxLineLength", "LoopWithTooManyJumpStatements", "MagicNumber", "TooManyFunctions")

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
import com.hereliesaz.graffitixr.common.scene.SurfaceMath
import com.hereliesaz.graffitixr.common.scene.planeChain
import com.hereliesaz.graffitixr.feature.editor.DepthScene
import com.hereliesaz.graffitixr.feature.editor.VectorRaster
import com.hereliesaz.graffitixr.feature.editor.threed.DepthCompositor

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

        /** Layers on a flat surface draw on it, as on the canvas (SceneContext.flatSurfaceModels). */
        val flatSurfaceModels = SurfaceMath.flatSurfaceModels(layers, w.toFloat(), h.toFloat())

        val flatSurfaceCrops = SurfaceMath.flatSurfaceCrops(layers, w.toFloat(), h.toFloat())

        /** Box-local to scene, through the layer's whole chain of planes (or its flat surface). */
        fun model(layer: Layer) = flatSurfaceModels[layer.id]
            ?: SceneMath.chainModel(planeChain(layers, layer.id), w.toFloat(), h.toFloat())
    }

    private fun vector(layer: Layer, ctx: Ctx): VectorRaster.Image? =
        vectorImages.getOrPut(layer.id) { VectorRaster.rasterize(layer, ctx.w.toFloat(), ctx.h.toFloat()) }

    /** Box-local to canvas, through the layer's whole chain of planes. */
    private fun homography(layer: Layer, ctx: Ctx): Matrix? {
        val hm = SceneMath.homography(ctx.model(layer), ctx.frame)
        // A plane crossing the eye cannot be one perspective draw; clip to the part in front.
        return Matrix().apply { setValues(hm) }
    }

    private fun clipInFront(canvas: Canvas, layer: Layer, ctx: Ctx) {
        val hm = SceneMath.homography(ctx.model(layer), ctx.frame)
        val poly = frontClip(hm, ctx.frame, ctx.w, ctx.h, ctx.flatSurfaceCrops[layer.id]) ?: return
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
        drawDepthFallback(canvas, scene, ctx)
    }

    /**
     * No GLES (tests): the planes farthest-first through their homographies. Crossing planes
     * then overlap by centre depth rather than per pixel, and 3D ink, which only the GPU pass
     * draws, is absent. A translucent surface group is one item: its layers are drawn into a
     * bitmap of their own, in stack order, which is then drawn at the group's opacity (as the
     * GPU pass composites its target), so its layers merge before they fade.
     */
    private fun drawDepthFallback(canvas: Canvas, scene: DepthCompositor.Scene, ctx: Ctx) {
        val units = DepthCompositor.isolatedUnits(scene)
        // Stack order, so a tie in depth (coincident groups) goes to the one above.
        val items = ArrayList<Pair<Any, Float>>()
        val emitted = HashSet<String>()
        for (p in scene.planes) {
            val unit = units.firstOrNull { u -> u.planes.any { it.id == p.id } }
            if (unit == null) {
                items += p to scene.camera.toCamera(p.model.apply((p.rect[0] + p.rect[2]) / 2f, (p.rect[1] + p.rect[3]) / 2f, 0f))[2]
            } else if (emitted.add(unit.group.id)) {
                items += unit to DepthCompositor.unitDepth(unit, scene.camera)
            }
        }
        val byId = ctx.layers.associateBy { it.id }
        for ((item, _) in items.sortedByDescending { it.second }) {
            when (item) {
                is DepthCompositor.Plane -> byId[item.id]?.let { drawPlaneFallback(canvas, item, it, scene, ctx) }
                is DepthCompositor.Isolated -> drawMergedFallback(canvas, item, byId, scene, ctx)
            }
        }
    }

    /** An isolated group's layers into a bitmap of their own, then that at the group's opacity. */
    private fun drawMergedFallback(
        canvas: Canvas,
        unit: DepthCompositor.Isolated,
        byId: Map<String, Layer>,
        scene: DepthCompositor.Scene,
        ctx: Ctx,
    ) {
        val merged = Bitmap.createBitmap(ctx.w, ctx.h, Bitmap.Config.ARGB_8888)
        val mergedCanvas = Canvas(merged)
        unit.planes.forEach { p -> byId[p.id]?.let { drawPlaneFallback(mergedCanvas, p, it, scene, ctx) } }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (unit.group.opacity * 255).toInt().coerceIn(0, 255)
        }
        canvas.drawBitmap(merged, 0f, 0f, paint)
        merged.recycle()
    }

    private fun drawPlaneFallback(
        canvas: Canvas,
        plane: DepthCompositor.Plane,
        layer: Layer,
        scene: DepthCompositor.Scene,
        ctx: Ctx,
    ) {
        // Paint on a group surface goes on the surface's strips, not on a plane of its own.
        plane.surface?.let { surface ->
            drawSurfaceFallback(canvas, plane, surface, scene.camera, manager.layerPaint(layer, plane.alpha))
            return
        }
        val m = homography(layer, ctx) ?: return
        val place = Matrix().apply {
            setScale((plane.rect[2] - plane.rect[0]) / plane.bitmap.width, (plane.rect[3] - plane.rect[1]) / plane.bitmap.height)
            postTranslate(plane.rect[0], plane.rect[1])
            postConcat(m)
        }
        canvas.drawBitmap(plane.bitmap, place, manager.layerPaint(layer, plane.alpha))
    }

    /**
     * The no-GLES fallback for paint on a surface: each strip of [surface] (a planar quad, six
     * vertices x, y, z, u, v in the owner's box) maps the image onto its projection exactly, so the
     * image follows the profile. A strip reaching behind the eye is skipped.
     */
    private fun drawSurfaceFallback(
        canvas: Canvas,
        plane: com.hereliesaz.graffitixr.feature.editor.threed.DepthCompositor.Plane,
        surface: FloatArray,
        cam: com.hereliesaz.graffitixr.common.scene.CameraFrame,
        paint: android.graphics.Paint,
    ) {
        val bw = plane.bitmap.width.toFloat()
        val bh = plane.bitmap.height.toFloat()
        if (plane.surfaceTriangles) {
            drawTriangleFallback(canvas, plane, surface, cam, paint)
            return
        }
        var i = 0
        while (i + STRIP_FLOATS <= surface.size) {
            val src = FloatArray(8)
            val dst = FloatArray(8)
            var inFront = true
            QUAD_CORNERS.forEachIndexed { k, v ->
                val o = i + v * VERTEX_FLOATS
                val c = cam.toCamera(plane.model.apply(surface[o], surface[o + 1], surface[o + 2]))
                if (c[2] <= NEAR) inFront = false
                src[k * 2] = surface[o + 3] * bw
                src[k * 2 + 1] = surface[o + 4] * bh
                dst[k * 2] = cam.width / 2f + cam.focal * c[0] / c[2]
                dst[k * 2 + 1] = cam.height / 2f + cam.focal * c[1] / c[2]
            }
            i += STRIP_FLOATS
            val m = Matrix()
            if (!inFront || !m.setPolyToPoly(src, 0, dst, 0, 4)) continue
            val clip = android.graphics.Path().apply {
                moveTo(dst[0], dst[1]); lineTo(dst[2], dst[3]); lineTo(dst[4], dst[5]); lineTo(dst[6], dst[7]); close()
            }
            val save = canvas.save()
            canvas.clipPath(clip)
            canvas.drawBitmap(plane.bitmap, m, paint)
            canvas.restoreToCount(save)
        }
    }

    /** [drawSurfaceFallback] for a multi-view patch: each triangle maps its part of the image onto its projection. */
    private fun drawTriangleFallback(
        canvas: Canvas,
        plane: com.hereliesaz.graffitixr.feature.editor.threed.DepthCompositor.Plane,
        surface: FloatArray,
        cam: com.hereliesaz.graffitixr.common.scene.CameraFrame,
        paint: android.graphics.Paint,
    ) {
        val bw = plane.bitmap.width.toFloat()
        val bh = plane.bitmap.height.toFloat()
        var i = 0
        while (i + TRIANGLE_FLOATS <= surface.size) {
            val src = FloatArray(6)
            val dst = FloatArray(6)
            var inFront = true
            for (k in 0..2) {
                val o = i + k * VERTEX_FLOATS
                val c = cam.toCamera(plane.model.apply(surface[o], surface[o + 1], surface[o + 2]))
                if (c[2] <= NEAR) inFront = false
                src[k * 2] = surface[o + 3] * bw
                src[k * 2 + 1] = surface[o + 4] * bh
                dst[k * 2] = cam.width / 2f + cam.focal * c[0] / c[2]
                dst[k * 2 + 1] = cam.height / 2f + cam.focal * c[1] / c[2]
            }
            i += TRIANGLE_FLOATS
            val m = Matrix()
            if (!inFront || !m.setPolyToPoly(src, 0, dst, 0, 3)) continue
            val clip = android.graphics.Path().apply {
                moveTo(dst[0], dst[1]); lineTo(dst[2], dst[3]); lineTo(dst[4], dst[5]); close()
            }
            val save = canvas.save()
            canvas.clipPath(clip)
            canvas.drawBitmap(plane.bitmap, m, paint)
            canvas.restoreToCount(save)
        }
    }

    companion object {
        /** The clip a layer draws under: the part in front of the eye, cut to its flat sheet ([crop]) when it has one. */
        internal fun frontClip(
            hm: FloatArray,
            frame: com.hereliesaz.graffitixr.common.scene.CameraFrame,
            w: Int,
            h: Int,
            crop: List<Pair<Float, Float>>?,
        ): List<Pair<Float, Float>>? =
            SceneMath.croppedClip(SceneMath.visiblePolygon(hm, frame, CLIP_EXTENT * maxOf(w, h)), crop)

        private const val TRIANGLE_FLOATS = 3 * 5
        private const val CLIP_EXTENT = 8f
        private const val VERTEX_FLOATS = 5
        private const val STRIP_FLOATS = 6 * VERTEX_FLOATS

        /** A strip's two triangles are (a,t0) (b,t0) (b,t1) and (a,t0) (b,t1) (a,t1): its corners. */
        private val QUAD_CORNERS = intArrayOf(0, 1, 2, 5)
        private const val NEAR = 1e-3f

        /** Anything to do beyond the flat composite: a layer placed in 3D, a moved camera, 3D ink. */
        fun needed(layers: List<Layer>, camera: SceneCamera): Boolean =
            !DepthComposite.plan(layers, camera).isEmpty || SceneRenderPath.sceneLayerIds(layers, camera).isNotEmpty()
    }
}
