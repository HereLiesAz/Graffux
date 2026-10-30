@file:Suppress("MagicNumber", "MaxLineLength", "ReturnCount", "TooManyFunctions", "LongParameterList", "CyclomaticComplexMethod")

package com.hereliesaz.graffitixr.feature.editor.export

import com.hereliesaz.graffitixr.common.model.FrameOps
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerNode
import com.hereliesaz.graffitixr.common.model.PathEditing
import com.hereliesaz.graffitixr.common.model.ShapeKind
import com.hereliesaz.graffitixr.common.model.VectorShape
import com.hereliesaz.graffitixr.common.model.buildLayerTree
import com.hereliesaz.graffitixr.common.model.frame
import com.hereliesaz.graffitixr.common.model.isContainer
import com.hereliesaz.graffitixr.common.model.isFrame
import com.hereliesaz.graffitixr.common.photo.CropGeometry
import com.hereliesaz.graffitixr.common.photo.DocumentCrop
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The vector export's scene: one walk of the layer tree, in the same order and with the same
 * transforms the exporter's raster composite uses (ExportManager.drawNode), turned into a small
 * display list that two sinks write out: SVG (SvgWriter, pure text) and PDF (PdfExport, Android's
 * PdfDocument, which keeps paths as vectors). Shapes stay vectors; raster and text layers are
 * embedded as images of their pixels. What vectors can't express is embedded as the raster
 * composite's own render of it (through [VectorRasterizer]), so SVG/PDF match the PNG export:
 * a layer with colour filters (brightness, contrast, saturation, colour balance, invert) is its
 * filtered render, and a photo adjustment or clip-to-below layer, which both act on what is
 * beneath them, flattens itself and everything under it in its parent into one image.
 */

/**
 * Renders, exactly as the PNG export (ExportManager) does, what the vector scene can't express.
 * Each returns a [Layer] carrying the rendered bitmap, or null when it can't be rendered.
 */
interface VectorRasterizer {
    /**
     * [nodes] composited as in a full canvas, in canvas pixels: over [backgroundArgb] (only the
     * document's top level has one), and inside [container]'s frame box (fill and clip) when given.
     * Only [region] of that canvas is rendered (one pixel per unit): the returned bitmap is
     * [RasterRegion.width] x [RasterRegion.height], its pixel (0, 0) at ([RasterRegion.left],
     * [RasterRegion.top]) — which may lie outside the canvas, for a frame bigger than it.
     */
    fun composite(nodes: List<LayerNode>, backgroundArgb: Int, container: Layer?, region: RasterRegion): Layer?

    /** The whole document page as the PNG export renders it, in page pixels: for a perspective crop. */
    fun document(): Layer?
}

/** The part of a parent's canvas space a raster fallback renders, in whole units. */
data class RasterRegion(val left: Int, val top: Int, val width: Int, val height: Int) {
    companion object {
        /** No side over this many pixels: a runaway transform must not ask for a gigapixel bitmap. */
        const val MAX_SIDE = 8192

        /** The whole-unit rectangle covering [l], [t], [r], [b], each side capped at [MAX_SIDE]. */
        fun covering(l: Float, t: Float, r: Float, b: Float): RasterRegion {
            val left = kotlin.math.floor(l).toInt()
            val top = kotlin.math.floor(t).toInt()
            val w = (kotlin.math.ceil(r).toInt() - left).coerceIn(1, MAX_SIDE)
            val h = (kotlin.math.ceil(b).toInt() - top).coerceIn(1, MAX_SIDE)
            return RasterRegion(left, top, w, h)
        }
    }
}

/** A 2D affine map: x' = a·x + c·y + e, y' = b·x + d·y + f (SVG's `matrix(a b c d e f)`). */
data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    /** This map, then [next]. */
    fun then(next: Affine): Affine = Affine(
        next.a * a + next.c * b, next.b * a + next.d * b,
        next.a * c + next.c * d, next.b * c + next.d * d,
        next.a * e + next.c * f + next.e, next.b * e + next.d * f + next.f,
    )

    fun map(x: Float, y: Float): Pair<Float, Float> = (a * x + c * y + e) to (b * x + d * y + f)

    /** The inverse map, or null when this one collapses the plane (a zero scale). */
    fun inverse(): Affine? {
        val det = a * d - b * c
        if (det == 0f || !det.isFinite()) return null
        return Affine(d / det, -b / det, -c / det, a / det, (c * f - d * e) / det, (b * e - a * f) / det)
    }

    /** The axis-aligned bounds of the rectangle [l], [t], [w], [h] under this map, as l, t, r, b. */
    fun bounds(l: Float, t: Float, w: Float, h: Float): FloatArray {
        val pts = listOf(map(l, t), map(l + w, t), map(l, t + h), map(l + w, t + h))
        return floatArrayOf(pts.minOf { it.first }, pts.minOf { it.second }, pts.maxOf { it.first }, pts.maxOf { it.second })
    }

    val isIdentity: Boolean get() = this == IDENTITY

    companion object {
        val IDENTITY = Affine(1f, 0f, 0f, 1f, 0f, 0f)
        fun translate(x: Float, y: Float) = Affine(1f, 0f, 0f, 1f, x, y)
        fun scale(s: Float) = Affine(s, 0f, 0f, s, 0f, 0f)
        fun scale(sx: Float, sy: Float) = Affine(sx, 0f, 0f, sy, 0f, 0f)
        fun rotate(degrees: Float): Affine {
            val r = Math.toRadians(degrees.toDouble())
            val c = cos(r).toFloat()
            val s = sin(r).toFloat()
            return Affine(c, s, -s, c, 0f, 0f)
        }
    }
}

/** One path command, in the space the shape is drawn in. */
sealed interface PathCmd {
    data class Move(val x: Float, val y: Float) : PathCmd
    data class Line(val x: Float, val y: Float) : PathCmd
    data class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : PathCmd
    data object Close : PathCmd
}

/** The display list. Every node's [transform] maps its own space into its parent's. */
sealed interface VectorNode {
    val transform: Affine
    val opacity: Float
    val blendMode: androidx.compose.ui.graphics.BlendMode

    /** A layer group or frame: its children, and for a frame its fill and clip box. */
    data class Group(
        override val transform: Affine,
        override val opacity: Float,
        override val blendMode: androidx.compose.ui.graphics.BlendMode,
        val name: String,
        /** A frame's box in the group's own space: left, top, width, height, radius. Null for a group. */
        val box: FloatArray?,
        val fillArgb: Long?,
        val clips: Boolean,
        val children: List<VectorNode>,
    ) : VectorNode

    /** One vector shape of a layer: its outline, fill and stroke. */
    data class Shape(
        override val transform: Affine,
        override val opacity: Float,
        override val blendMode: androidx.compose.ui.graphics.BlendMode,
        val name: String,
        val path: List<PathCmd>,
        val fillArgb: Long?,
        val strokeArgb: Long?,
        val strokeWidth: Float,
    ) : VectorNode

    /** A layer's pixels, [width] x [height], placed by [transform] (bitmap pixels to parent space). */
    data class Image(
        override val transform: Affine,
        override val opacity: Float,
        override val blendMode: androidx.compose.ui.graphics.BlendMode,
        val name: String,
        val layer: Layer,
        val width: Int,
        val height: Int,
    ) : VectorNode
}

/** A page of the export: [width] x [height] units, [root] mapping canvas space onto it. */
data class VectorPage(val name: String, val width: Float, val height: Float, val root: Affine, val nodes: List<VectorNode>)

object VectorScene {

    /**
     * The page for frame [frameId]: exactly its box, with only the frame and what is inside it.
     * Layers live in a [canvasW] x [canvasH] canvas (their offsets measured from its centre).
     */
    fun framePage(layers: List<Layer>, frameId: String, canvasW: Float, canvasH: Float, raster: VectorRasterizer? = null): VectorPage? {
        val frame = layers.firstOrNull { it.id == frameId && it.isFrame } ?: return null
        val box = FrameOps.boxOf(layers, frame)
        val node = findNode(buildLayerTree(layers), frameId) ?: return null
        // The frame's ancestors' transforms, so a nested frame exports where it is.
        val parentT = frame.parentId?.let { id -> layers.firstOrNull { it.id == id } }
            ?.let { FrameOps.worldTransform(layers, it) }
        val parentMap = parentT?.let { containerTransform(it.offset.x, it.offset.y, it.scale, it.rotation, canvasW, canvasH) } ?: Affine.IDENTITY
        val root = parentMap.then(Affine.translate(-(canvasW / 2f + box.x), -(canvasH / 2f + box.y)))
        val region = pageRegion(root, box.width, box.height, canvasW, canvasH)
        return VectorPage(frame.name, box.width, box.height, root, listOfNotNull(build(node, canvasW, canvasH, raster, region)))
    }

    /** Every top-level frame as a page, in stack order (the PDF's "All frames"). */
    fun allFramePages(layers: List<Layer>, canvasW: Float, canvasH: Float, raster: VectorRasterizer? = null): List<VectorPage> =
        allFrameIds(layers).mapNotNull { framePage(layers, it, canvasW, canvasH, raster) }

    /** The frames [allFramePages] makes pages of, in order: for building those pages one at a time. */
    fun allFrameIds(layers: List<Layer>): List<String> =
        layers.filter { it.isFrame && it.parentId == null && it.isVisible }.map { it.id }

    /**
     * The page for the document's artboard: [docW] x [docH], the artboard cut from the canvas the
     * way ExportManager.compositeToDocument cuts it, with every layer over [backgroundArgb] (the
     * canvas background the PNG export composites over; transparent adds nothing). A [crop] is
     * applied as the PNG export applies it: its rotation, flips and rectangle as the page's
     * transform and size; a perspective crop, which no affine map can express, makes the page one
     * image of the PNG render (from [raster]).
     */
    fun documentPage(
        name: String,
        layers: List<Layer>,
        canvasW: Float,
        canvasH: Float,
        docW: Int,
        docH: Int,
        crop: DocumentCrop? = null,
        backgroundArgb: Int = 0,
        raster: VectorRasterizer? = null,
    ): VectorPage {
        val rect = artboardRect(canvasW.toInt(), canvasH.toInt(), docW, docH)
        val activeCrop = crop?.takeIf { !it.isIdentity && docW > 0 && docH > 0 }
        if (activeCrop != null && (activeCrop.vertical != 0f || activeCrop.horizontal != 0f)) {
            raster?.document()?.let { img ->
                val b = img.bitmap ?: return@let null
                return VectorPage(name, b.width.toFloat(), b.height.toFloat(), Affine.IDENTITY, listOf(image(img, b.width, b.height)))
            }
        }
        if (activeCrop == null) {
            val s = if (rect[2] > 0f) docW / rect[2] else 1f
            val root = Affine.translate(-rect[0], -rect[1]).then(Affine.scale(s))
            return VectorPage(name, docW.toFloat(), docH.toFloat(), root, documentNodes(layers, canvasW, canvasH, raster, backgroundArgb))
        }
        // DocumentCropRender.render: through the crop's matrix, cut to its rectangle, scaled to its
        // share of the document's resolution.
        val left = rect[0] + activeCrop.left * rect[2]
        val top = rect[1] + activeCrop.top * rect[3]
        val cropW = (activeCrop.right - activeCrop.left) * rect[2]
        val cropH = (activeCrop.bottom - activeCrop.top) * rect[3]
        val outW = (docW * (activeCrop.right - activeCrop.left)).roundToInt().coerceAtLeast(1)
        val outH = (docH * (activeCrop.bottom - activeCrop.top)).roundToInt().coerceAtLeast(1)
        val m = CropGeometry.matrix(activeCrop, rect[0] + rect[2] / 2f, rect[1] + rect[3] / 2f, rect[2], rect[3])
        val root = Affine(m[0], m[3], m[1], m[4], m[2], m[5])
            .then(Affine.translate(-left, -top))
            .then(Affine.scale(if (cropW > 0f) outW / cropW else 1f, if (cropH > 0f) outH / cropH else 1f))
        return VectorPage(name, outW.toFloat(), outH.toFloat(), root, documentNodes(layers, canvasW, canvasH, raster, backgroundArgb))
    }

    /**
     * The document's top level, over its background. The artboard is cut from the canvas
     * (ExportManager.compositeToDocument renders the canvas, then crops), so a raster fallback here
     * renders the canvas, like the PNG.
     */
    private fun documentNodes(layers: List<Layer>, canvasW: Float, canvasH: Float, raster: VectorRasterizer?, backgroundArgb: Int): List<VectorNode> {
        val region = RasterRegion.covering(0f, 0f, canvasW, canvasH)
        val nodes = buildSiblings(buildLayerTree(layers), canvasW, canvasH, raster, backgroundArgb, null, region)
        val background = backgroundArgb.takeIf { it != 0 && !nodes.flattenedBackground }
            ?.let { listOf(backgroundRect(it, canvasW, canvasH)) }.orEmpty()
        return background + nodes.nodes
    }

    /**
     * What a page of [pageW] x [pageH] shows, in the space [root] maps onto it: where a raster
     * fallback must render, so content outside the canvas (a frame bigger than it) isn't cut off.
     */
    private fun pageRegion(root: Affine, pageW: Float, pageH: Float, canvasW: Float, canvasH: Float): RasterRegion {
        val b = root.inverse()?.bounds(0f, 0f, pageW, pageH) ?: return RasterRegion.covering(0f, 0f, canvasW, canvasH)
        return RasterRegion.covering(b[0], b[1], b[2], b[3])
    }

    /** [region] (in a container's parent space) in the container's own space, through [t] (own to parent). */
    private fun regionInside(region: RasterRegion, t: Affine): RasterRegion {
        val b = t.inverse()?.bounds(region.left.toFloat(), region.top.toFloat(), region.width.toFloat(), region.height.toFloat())
            ?: return region
        return RasterRegion.covering(b[0], b[1], b[2], b[3])
    }

    private fun backgroundRect(argb: Int, w: Float, h: Float) = VectorNode.Shape(
        Affine.IDENTITY, 1f, androidx.compose.ui.graphics.BlendMode.SrcOver, "Background",
        listOf(PathCmd.Move(0f, 0f), PathCmd.Line(w, 0f), PathCmd.Line(w, h), PathCmd.Line(0f, h), PathCmd.Close),
        argb.toLong() and 0xFFFFFFFFL, null, 0f,
    )

    private fun findNode(nodes: List<LayerNode>, id: String): LayerNode? {
        for (n in nodes) {
            if (n.layer.id == id) return n
            findNode(n.children, id)?.let { return it }
        }
        return null
    }

    /** A container's transform: ExportManager's group matrix (about the canvas centre, then offset). */
    private fun containerTransform(ox: Float, oy: Float, s: Float, rot: Float, w: Float, h: Float): Affine =
        Affine.translate(-w / 2f, -h / 2f).then(Affine.scale(s)).then(Affine.rotate(rot)).then(Affine.translate(w / 2f + ox, h / 2f + oy))

    /** The vector nodes for one parent's children; [flattenedBackground] when [backgroundArgb] went into an image. */
    private class Siblings(val nodes: List<VectorNode>, val flattenedBackground: Boolean)

    /**
     * One parent's [children] in stack order. A photo adjustment or clip-to-below layer acts on the
     * composite beneath it in its parent (ExportManager.drawNode hands it that canvas), so with a
     * [raster] the children up to the last such layer become one image of their composite, drawn
     * over the parent's [backgroundArgb] and inside its frame box ([container]) exactly as the PNG
     * export draws them; the rest stay vectors.
     */
    private fun buildSiblings(
        children: List<LayerNode>,
        w: Float,
        h: Float,
        raster: VectorRasterizer?,
        backgroundArgb: Int,
        container: Layer?,
        region: RasterRegion,
    ): Siblings {
        val visible = children.filter { it.layer.isVisible }
        val cut = if (raster == null) -1 else visible.indexOfLast { it.layer.actsOnBelow }
        val out = ArrayList<VectorNode>()
        var flattened = false
        if (cut >= 0) {
            raster?.composite(visible.subList(0, cut + 1), backgroundArgb, container, region)?.let { img ->
                img.bitmap?.let { b ->
                    out += image(img, b.width, b.height, region)
                    flattened = true
                }
            }
        }
        visible.drop(if (flattened) cut + 1 else 0).mapNotNullTo(out) { build(it, w, h, raster, region) }
        return Siblings(out, flattened)
    }

    /** An image in its parent's (canvas) pixels, its pixel (0, 0) at [region]'s top-left (identity without one). */
    private fun image(img: Layer, bw: Int, bh: Int, region: RasterRegion? = null) = VectorNode.Image(
        region?.let { Affine.translate(it.left.toFloat(), it.top.toFloat()) } ?: Affine.IDENTITY,
        1f, androidx.compose.ui.graphics.BlendMode.SrcOver, img.name, img, bw, bh,
    )

    /** [region]: what of this node's parent space reaches the page, where a raster fallback renders. */
    private fun build(node: LayerNode, w: Float, h: Float, raster: VectorRasterizer?, region: RasterRegion): VectorNode? {
        val l = node.layer
        if (!l.isVisible) return null
        val cx = w / 2f
        val cy = h / 2f
        if (raster != null && !l.isContainer && l.hasColorFilter) {
            // Colour filters have no vector form: embed the layer as the PNG export renders it,
            // unblended at full strength, and keep its opacity and blend on the image.
            val solo = node.copy(layer = l.copy(opacity = 1f, blendMode = androidx.compose.ui.graphics.BlendMode.SrcOver))
            raster.composite(listOf(solo), 0, null, region)?.let { img ->
                val b = img.bitmap ?: return@let null
                return image(img, b.width, b.height, region).copy(opacity = l.opacity, blendMode = l.blendMode, name = l.name)
            }
        }
        return when {
            l.isContainer -> {
                val t = containerTransform(l.offset.x, l.offset.y, l.scale, l.rotationZ, w, h)
                val kids = buildSiblings(node.children, w, h, raster, 0, l.takeIf { it.isFrame }, regionInside(region, t))
                VectorNode.Group(
                    transform = t,
                    opacity = l.opacity,
                    blendMode = l.blendMode,
                    name = l.name,
                    box = if (l.isFrame && l.layoutWidth > 0f && l.layoutHeight > 0f) {
                        floatArrayOf(cx - l.layoutWidth / 2f, cy - l.layoutHeight / 2f, l.layoutWidth, l.layoutHeight, l.frameRadius)
                    } else {
                        null
                    },
                    // A flattened prefix already holds the frame's fill (it was drawn inside the box).
                    fillArgb = l.frameFillArgb.takeIf { l.isFrame && !kids.flattenedBackground },
                    clips = l.isFrame && l.clipsContent,
                    children = kids.nodes,
                )
            }
            l.shapes.isNotEmpty() -> {
                // drawVectorLayer: scale and rotate about the centre, then the offset.
                val t = Affine.translate(-cx, -cy).then(Affine.scale(l.scale)).then(Affine.rotate(l.rotationZ))
                    .then(Affine.translate(cx + l.offset.x, cy + l.offset.y))
                val shapes = l.shapes.map { shape ->
                    VectorNode.Shape(
                        transform = Affine.IDENTITY,
                        opacity = 1f,
                        blendMode = androidx.compose.ui.graphics.BlendMode.SrcOver,
                        name = l.name,
                        path = outline(shape, cx, cy),
                        fillArgb = shape.fillArgb.takeIf { shape.hasFill && shape.kind != ShapeKind.LINE },
                        strokeArgb = shape.strokeArgb.takeIf { shape.hasStroke },
                        strokeWidth = shape.strokeWidth,
                    )
                }
                VectorNode.Group(t, l.opacity, l.blendMode, l.name, null, null, false, shapes)
            }
            else -> {
                val b = l.bitmap ?: return null
                VectorNode.Image(rasterTransform(l, b.width, b.height, w, h), l.opacity, l.blendMode, l.name, l, b.width, b.height)
            }
        }
    }

    /** A photo adjustment or clipping mask: it renders from what is beneath it in its parent. */
    private val Layer.actsOnBelow: Boolean get() = adjustment != null || clipToLayerBelow

    /** ExportManager's colour matrix is not the identity for this layer. */
    internal val Layer.hasColorFilter: Boolean
        get() = brightness != 0f || contrast != 1f || saturation != 1f ||
            colorBalanceR != 1f || colorBalanceG != 1f || colorBalanceB != 1f || isInverted

    /** ExportManager.getLayerScreenMatrix: the bitmap at its frame origin, fitted to the canvas, then the layer's transform. */
    internal fun rasterTransform(layer: Layer, bw: Int, bh: Int, w: Float, h: Float): Affine {
        val f = layer.frame(bw, bh)
        val imageAspect = f.width.toFloat() / f.height.toFloat()
        var rw = w
        var rh = h
        if (imageAspect > w / h) rh = rw / imageAspect else rw = rh * imageAspect
        return Affine.translate(f.originX.toFloat(), f.originY.toFloat())
            .then(Affine.scale(rw / f.width, rh / f.height))
            .then(Affine.translate(-rw / 2f, -rh / 2f))
            .then(Affine.scale(layer.scale))
            .then(Affine.rotate(layer.rotationZ))
            .then(Affine.translate(w / 2f + layer.offset.x, h / 2f + layer.offset.y))
    }

    /** [shape]'s outline centred on ([cx], [cy]), exactly the figure ExportManager.drawShape draws. */
    fun outline(shape: VectorShape, cx: Float, cy: Float): List<PathCmd> {
        val w = shape.width
        val h = shape.height
        val l = cx - w / 2f
        val t = cy - h / 2f
        return when (shape.kind) {
            ShapeKind.RECTANGLE -> roundRect(l, t, w, h, shape.cornerRadius)
            ShapeKind.ELLIPSE -> ellipse(cx, cy, w / 2f, h / 2f)
            ShapeKind.LINE -> listOf(PathCmd.Move(cx - w / 2f, cy), PathCmd.Line(cx + w / 2f, cy))
            ShapeKind.POLYGON -> {
                val n = shape.sides.coerceAtLeast(3)
                (0 until n).map { i ->
                    val a = -Math.PI / 2 + i * 2 * Math.PI / n
                    val x = cx + w / 2f * cos(a).toFloat()
                    val y = cy + h / 2f * sin(a).toFloat()
                    if (i == 0) PathCmd.Move(x, y) else PathCmd.Line(x, y)
                } + PathCmd.Close
            }
            ShapeKind.PATH -> pathOutline(shape, cx, cy)
        }
    }

    private fun pathOutline(shape: VectorShape, cx: Float, cy: Float): List<PathCmd> {
        val pts = shape.points
        if (pts.size < 4) return emptyList()
        val out = ArrayList<PathCmd>()
        if (shape.hasCurves) {
            val nodes = PathEditing.nodes(shape)
            out += PathCmd.Move(cx + nodes[0].x, cy + nodes[0].y)
            for (s in 0 until PathEditing.segmentCount(nodes.size, shape.closed)) {
                val a = nodes[s]
                val b = nodes[(s + 1) % nodes.size]
                out += if (a.isCorner && b.isCorner) {
                    PathCmd.Line(cx + b.x, cy + b.y)
                } else {
                    PathCmd.Cubic(cx + a.x + a.outDx, cy + a.y + a.outDy, cx + b.x + b.inDx, cy + b.y + b.inDy, cx + b.x, cy + b.y)
                }
            }
        } else {
            out += PathCmd.Move(cx + pts[0], cy + pts[1])
            var i = 2
            while (i + 1 < pts.size) {
                out += PathCmd.Line(cx + pts[i], cy + pts[i + 1])
                i += 2
            }
        }
        if (shape.closed) out += PathCmd.Close
        return out
    }

    /** The circle-to-cubic constant: control points at 0.5523 of the radius. */
    private const val KAPPA = 0.5522848f

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): List<PathCmd> {
        val kx = rx * KAPPA
        val ky = ry * KAPPA
        return listOf(
            PathCmd.Move(cx + rx, cy),
            PathCmd.Cubic(cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry),
            PathCmd.Cubic(cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy),
            PathCmd.Cubic(cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry),
            PathCmd.Cubic(cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy),
            PathCmd.Close,
        )
    }

    /** A rectangle with corner radius [radius] (clamped to half the shorter side), clockwise from top-left. */
    fun roundRect(l: Float, t: Float, w: Float, h: Float, radius: Float): List<PathCmd> {
        val r = radius.coerceIn(0f, minOf(w, h) / 2f)
        val rr = l + w
        val b = t + h
        if (r <= 0f) return listOf(PathCmd.Move(l, t), PathCmd.Line(rr, t), PathCmd.Line(rr, b), PathCmd.Line(l, b), PathCmd.Close)
        val k = r * KAPPA
        return listOf(
            PathCmd.Move(l + r, t),
            PathCmd.Line(rr - r, t), PathCmd.Cubic(rr - r + k, t, rr, t + r - k, rr, t + r),
            PathCmd.Line(rr, b - r), PathCmd.Cubic(rr, b - r + k, rr - r + k, b, rr - r, b),
            PathCmd.Line(l + r, b), PathCmd.Cubic(l + r - k, b, l, b - r + k, l, b - r),
            PathCmd.Line(l, t + r), PathCmd.Cubic(l, t + r - k, l + r - k, t, l + r, t),
            PathCmd.Close,
        )
    }
}
