@file:Suppress(
    "MagicNumber",
    "ReturnCount",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "LoopWithTooManyJumpStatements",
    "ComplexCondition",
    "MaxLineLength",
    "TooManyFunctions",
)

package com.hereliesaz.graffitixr.common.scene

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.SelectionOp
import com.hereliesaz.graffitixr.common.model.SelectionRing
import com.hereliesaz.graffitixr.common.model.SurfaceSelection
import com.hereliesaz.graffitixr.common.model.ViewSelection
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

/**
 * A selection on a multi-view surface (spec/3d-space.md, "Paint per view"): the lasso is drawn in
 * world (canvas) space over the picture, but each view's paint image has its own flat space, so a
 * region that crosses a crease is several regions, one per image. Split here, exactly: the lasso
 * ring is clipped to each projected triangle of the view's patches and caps (the same triangles that
 * are drawn), and each piece is carried into the image through the triangle's own mapping, so a point
 * is selected in an image precisely when it is under the lasso on the surface. A face the lasso covers
 * partly shows only the covered part; one it covers whole is whole. Faces facing away are selected
 * too (the lasso selects what is under it, seen or not), which only matters to a stroke that could
 * not reach them.
 */
object SurfaceViewSelection {
    /**
     * [selection] (rings in world space) as a selection in [view]'s flat space: the same rings in the
     * same order and sense, each cut to the view's faces. Null when it reaches none of them.
     *
     * An **inverted** selection is everything outside its recipe, so it is never a miss: it is returned as the
     * view's facing faces with the recipe taken out, a plain (not inverted) selection that is bounded to what
     * faces the camera like any other. Complementing a recipe ring by ring is exact: an Add ring becomes a
     * cut and a cut becomes an Add, starting from the whole facing area.
     */
    fun forView(
        selection: Selection,
        view: SurfaceView,
        owner: SurfaceOwner,
        hull: SurfaceHull,
        camera: SceneCamera,
        width: Float,
        height: Float,
        projection: SurfaceProjection? = null,
    ): Selection? {
        if (width <= 0f || height <= 0f) return null
        val tris = (projection ?: SurfaceProjection.of(hull, owner, camera, width, height)).facing(view)
        if (tris.isEmpty()) return null
        val rings = ArrayList<SelectionRing>()
        var reached = false
        for (ring in selection.rings) {
            if (!ring.isUsable) continue
            for (t in tris) {
                val clipped = clip(ring.path, t.sx, t.sy)
                if (clipped.size < 3) continue
                val flat = clipped.mapNotNull { t.paintAt(it) }
                    .mapNotNull { (px, py) -> SurfaceMath.unrolledToFlat(px, py, owner.innerChain, width, height) }
                    .map { Offset(it.first, it.second) }
                if (flat.size < 3 || area(flat) < MIN_AREA) continue
                rings += SelectionRing(flat, ring.additive)
                if (ring.additive) reached = true
            }
        }
        if (selection.inverted) return complemented(tris, rings, selection, owner, width, height)
        if (!reached) return null
        return Selection(rings, selection.canvasSize, selection.inverted, selection.featherPx)
    }

    /** The facing triangles [tris] of a view as rings in its flat space (the area a selection of that view may reach). */
    private fun facingRings(tris: List<SurfaceProjection.Tri>, owner: SurfaceOwner, width: Float, height: Float): List<SelectionRing> =
        tris.mapNotNull { t ->
            val flat = (0..2).mapNotNull { SurfaceMath.unrolledToFlat(t.px[it], t.py[it], owner.innerChain, width, height) }
                .map { Offset(it.first, it.second) }
            flat.takeIf { it.size == 3 && area(it) >= MIN_AREA }?.let { SelectionRing(it, true) }
        }

    /**
     * The complement of the recipe [rings] (cut to [tris], the facing triangles of one view) within those
     * triangles, as a plain selection: the whole facing area, then each ring with its sense turned over.
     * Null when nothing of the view faces the camera.
     */
    private fun complemented(
        tris: List<SurfaceProjection.Tri>,
        rings: List<SelectionRing>,
        selection: Selection,
        owner: SurfaceOwner,
        width: Float,
        height: Float,
    ): Selection? {
        val whole = facingRings(tris, owner, width, height)
        if (whole.isEmpty()) return null
        return Selection(whole + rings.map { it.copy(additive = !it.additive) }, selection.canvasSize, false, selection.featherPx)
    }

    /**
     * The inverse of [forView]: rings in [view]'s flat space (a wand region traced in its image, with the
     * holes marked) as rings in world space. Each ring is cut to every triangle of the view's patches and
     * caps and carried to the canvas through the triangle's own perspective mapping, so the result is the
     * footprint the region has on screen. Only triangles that face the viewer count, so the far side of the
     * solid is never selected through the near one; the triangle under [tappedAt] (the paint point the wand was
     * tapped at) counts whichever way it faces. Same order and sense as the rings given.
     */
    fun fromView(
        rings: List<SelectionRing>,
        view: SurfaceView,
        owner: SurfaceOwner,
        projection: SurfaceProjection,
        width: Float,
        height: Float,
        tappedAt: Offset? = null,
        region: Int = ViewSelection.ANY_REGION,
    ): List<SelectionRing> {
        val tris = projection.triangles(view).filter {
            (region == ViewSelection.ANY_REGION || it.region == region) &&
                (it.facing || (tappedAt != null && it.holdsPaint(tappedAt.x, tappedAt.y)))
        }
        val out = ArrayList<SelectionRing>()
        for (ring in rings) {
            if (!ring.isUsable) continue
            val paint = ring.path.map {
                val (u, t) = SurfaceMath.flatToUnrolled(it.x, it.y, owner.innerChain, width, height)
                Offset(u, t)
            }
            val loX = paint.minOf { it.x }; val hiX = paint.maxOf { it.x }
            val loY = paint.minOf { it.y }; val hiY = paint.maxOf { it.y }
            for (t in tris) {
                if (t.maxPx < loX || t.minPx > hiX || t.maxPy < loY || t.minPy > hiY) continue
                val clipped = clip(paint, t.px, t.py)
                if (clipped.size < 3) continue
                val world = clipped.mapNotNull { t.screenAt(it.x, it.y) }
                if (world.size < 3 || area(world) < MIN_AREA) continue
                val mid = Offset(world.sumOf { it.x.toDouble() }.toFloat() / world.size, world.sumOf { it.y.toDouble() }.toFloat() / world.size)
                if (t.facing && projection.sharedWithEarlier(t, mid)) continue
                out += SelectionRing(world, ring.additive)
            }
        }
        return out
    }

    /**
     * The on-screen footprint of [surface] for the camera [projection] was built for: each stored region
     * carried to the canvas through the facing triangles of its view (and cap region). Recomputed when the
     * camera or surface changes; [checkpoint] is called between regions so a caller can abandon a stale pass.
     */
    fun footprint(
        surface: SurfaceSelection,
        owner: SurfaceOwner,
        projection: SurfaceProjection,
        width: Float,
        height: Float,
        tappedAt: Offset? = null,
        checkpoint: () -> Unit = {},
    ): List<SelectionRing> {
        val out = ArrayList<SelectionRing>()
        for (v in surface.views) {
            checkpoint()
            out += fromView(v.rings, v.view, owner, projection, width, height, tappedAt, v.region)
        }
        return out
    }

    /** A wand's flooded [regions] as a surface selection; faces turned away from the viewer contribute nothing. Null if nothing is left. */
    fun fromWand(
        surfaceId: String,
        regions: List<ViewSelection>,
        owner: SurfaceOwner,
        projection: SurfaceProjection,
        width: Float,
        height: Float,
        canvasSize: IntSize,
        tappedAt: Offset?,
    ): Selection? {
        val kept = ArrayList<ViewSelection>()
        val rings = ArrayList<SelectionRing>()
        for (r in regions) {
            val fp = fromView(r.rings, r.view, owner, projection, width, height, tappedAt, r.region)
            if (fp.isEmpty()) continue
            kept += r
            rings += fp
        }
        val sel = Selection(rings, canvasSize, surface = SurfaceSelection(surfaceId, kept))
        return sel.takeIf { it.isUsable }
    }

    /** [selection] with its footprint re-derived for [projection]'s camera; any other selection, untouched. */
    fun refresh(
        selection: Selection,
        owner: SurfaceOwner,
        projection: SurfaceProjection,
        width: Float,
        height: Float,
        checkpoint: () -> Unit = {},
    ): Selection {
        val s = selection.surface ?: return selection
        val fp = footprint(s, owner, projection, width, height, null, checkpoint)
        return if (fp == selection.rings) selection else selection.copy(rings = fp)
    }

    /**
     * The part of [selection]'s stored regions a stroke on [view]'s image may paint, in that image's flat
     * space: cut to the triangles facing the camera. Null when none of it is reachable (turned away, or the
     * selection never reached the view); the caller decides whether that blocks. An inverted selection is the facing
     * area with the stored recipe taken out ([forView]), so it is null only when nothing of the view faces the camera.
     */
    fun viewCut(
        selection: Selection,
        view: SurfaceView,
        owner: SurfaceOwner,
        projection: SurfaceProjection,
        width: Float,
        height: Float,
    ): Selection? {
        val s = selection.surface ?: return null
        val out = ArrayList<SelectionRing>()
        for (v in s.views) {
            if (v.view != view) continue
            val tris = projection.facing(view).filter { v.region == ViewSelection.ANY_REGION || it.region == v.region }
            for (ring in v.rings) {
                if (!ring.isUsable) continue
                val paint = ring.path.map {
                    val (u, t) = SurfaceMath.flatToUnrolled(it.x, it.y, owner.innerChain, width, height)
                    Offset(u, t)
                }
                val loX = paint.minOf { it.x }; val hiX = paint.maxOf { it.x }
                val loY = paint.minOf { it.y }; val hiY = paint.maxOf { it.y }
                for (t in tris) {
                    if (t.maxPx < loX || t.minPx > hiX || t.maxPy < loY || t.minPy > hiY) continue
                    val clipped = clip(paint, t.px, t.py)
                    if (clipped.size < 3) continue
                    val flat = clipped.mapNotNull { SurfaceMath.unrolledToFlat(it.x, it.y, owner.innerChain, width, height) }.map { Offset(it.first, it.second) }
                    if (flat.size < 3 || area(flat) < MIN_AREA) continue
                    out += SelectionRing(flat, ring.additive)
                }
            }
        }
        // Inverted: everything outside the stored recipe, bounded to the facing triangles like any other cut.
        if (selection.inverted) return complemented(projection.facing(view), out, selection, owner, width, height)
        if (out.isEmpty() || out.none { it.additive }) return null
        return Selection(out, selection.canvasSize, false, selection.featherPx)
    }

    /** A world-space [selection]'s rings as per-image regions (the lasso-to-view cut, [forView], for every facing view). */
    private fun bind(
        selection: Selection,
        owner: SurfaceOwner,
        hull: SurfaceHull,
        camera: SceneCamera,
        projection: SurfaceProjection,
        width: Float,
        height: Float,
    ): List<ViewSelection> {
        val plain = Selection(selection.rings, selection.canvasSize)
        return SurfaceView.entries.filter { it in projection.facingViews }.mapNotNull { v ->
            forView(plain, v, owner, hull, camera, width, height, projection)?.let { ViewSelection(v, ViewSelection.ANY_REGION, it.rings) }
        }
    }

    /**
     * Folds [incoming] (a wand's surface selection, or a lasso in world space) into [current] under [op].
     * Defined result for mixes: whichever side is a world-space lasso is converted to per-image regions with
     * the lasso-to-view cut ([forView]; parts on faces turned away are dropped), then the regions are
     * concatenated in recipe order with the incoming rings' sense set as the flat Add/Remove would
     * (a hole stays a hole; under an inverted base the sense flips). Inversion and feather are the base's.
     * Null: Remove from nothing.
     */
    fun compose(
        current: Selection?,
        incoming: Selection,
        op: SelectionOp,
        surfaceId: String,
        owner: SurfaceOwner,
        hull: SurfaceHull,
        camera: SceneCamera,
        projection: SurfaceProjection,
        width: Float,
        height: Float,
    ): Selection? {
        val canvas = incoming.canvasSize
        val inc = incoming.surface?.takeIf { it.surfaceId == surfaceId }?.views
            ?: bind(incoming, owner, hull, camera, projection, width, height)
        fun result(views: List<ViewSelection>, inverted: Boolean, feather: Float): Selection {
            val surface = SurfaceSelection(surfaceId, views)
            return Selection(footprint(surface, owner, projection, width, height), canvas, inverted, feather, surface)
        }
        if (op == SelectionOp.NEW) return result(inc, false, 0f)
        val base = current?.takeIf { (it.isUsable || it.surface?.isUsable == true) && it.canvasSize == canvas }
        if (base == null) {
            return if (op == SelectionOp.ADD) result(inc, current?.inverted ?: false, current?.featherPx ?: 0f) else null
        }
        val have = base.surface?.takeIf { it.surfaceId == surfaceId }?.views
            ?: bind(base, owner, hull, camera, projection, width, height)
        val added = inc.map { v ->
            v.copy(rings = v.rings.map { r ->
                val eff = if (r.additive) op else SelectionOp.REMOVE
                r.copy(additive = (eff == SelectionOp.ADD) != base.inverted)
            })
        }
        return result(have + added, base.inverted, base.featherPx)
    }

    /**
     * A surface selection that was moved ([Selection.translated] drops its image regions): the moved rings
     * are the marquee as they are, and are cut to the images again so the result follows the surface.
     */
    fun rebind(
        moved: Selection,
        surfaceId: String,
        owner: SurfaceOwner,
        hull: SurfaceHull,
        camera: SceneCamera,
        projection: SurfaceProjection,
        width: Float,
        height: Float,
    ): Selection = moved.copy(surface = SurfaceSelection(surfaceId, bind(moved, owner, hull, camera, projection, width, height)))

    /**
     * The rectangle of the face a touch is on ([SurfaceMath.ViewFlat.region]) as a one-ring selection
     * in the view's flat space: what a flood fill from that touch may reach, since the image's other
     * faces share it.
     */
    fun faceRegion(
        flat: SurfaceMath.ViewFlat,
        owner: SurfaceOwner,
        hull: SurfaceHull,
        canvasSize: androidx.compose.ui.unit.IntSize,
        width: Float,
        height: Float,
    ): Selection? {
        val r = hull.regionRect(flat.view, flat.region) ?: return null
        val corners = listOf(r[0] to r[1], r[2] to r[1], r[2] to r[3], r[0] to r[3])
            .map { (x, y) -> SurfaceMath.unrolledToFlat(x, y, owner.innerChain, width, height) ?: return null }
            .map { Offset(it.first, it.second) }
        return Selection.ofPolygon(corners, canvasSize)
    }

    /** Sutherland-Hodgman: [subject] cut to the (convex) triangle [t], in world space. */
    private fun clip(subject: List<Offset>, xs: FloatArray, ys: FloatArray): List<Offset> {
        val ccw = (xs[1] - xs[0]) * (ys[2] - ys[0]) - (xs[2] - xs[0]) * (ys[1] - ys[0]) > 0f
        var poly = subject
        for (e in 0..2) {
            val a = e
            val b = (e + 1) % 3
            val ax = xs[a]; val ay = ys[a]; val bx = xs[b]; val by = ys[b]
            fun side(p: Offset): Float {
                val s = (bx - ax) * (p.y - ay) - (by - ay) * (p.x - ax)
                return if (ccw) s else -s
            }
            val next = ArrayList<Offset>()
            for (i in poly.indices) {
                val cur = poly[i]
                val prev = poly[(i + poly.size - 1) % poly.size]
                val sc = side(cur)
                val sp = side(prev)
                if (sc >= 0f) {
                    if (sp < 0f) next += cross(prev, cur, sp, sc)
                    next += cur
                } else if (sp >= 0f) {
                    next += cross(prev, cur, sp, sc)
                }
            }
            poly = next
            if (poly.size < 3) return emptyList()
        }
        return poly
    }

    private fun cross(p: Offset, q: Offset, sp: Float, sq: Float): Offset {
        val f = sp / (sp - sq)
        return Offset(p.x + (q.x - p.x) * f, p.y + (q.y - p.y) * f)
    }

    private fun area(p: List<Offset>): Float {
        var a = 0f
        for (i in p.indices) {
            val q = p[(i + 1) % p.size]
            a += p[i].x * q.y - q.x * p[i].y
        }
        return abs(a) / 2f
    }

    private const val MIN_AREA = 1e-3f
}
