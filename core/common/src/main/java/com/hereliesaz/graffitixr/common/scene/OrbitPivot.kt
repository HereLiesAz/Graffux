@file:Suppress("MagicNumber", "ReturnCount", "LongParameterList", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.isAdjustment
import com.hereliesaz.graffitixr.common.model.isContainer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The one point everything that revolves revolves about (spec/3d-space.md, "The orbit point"): the
 * rotation slider on every axis, the axis cycler's two-finger turn, camera orbit, and the point a
 * newly adopted view plane passes through.
 *
 * It is the centre of the box that holds the content. Layer 1 (the first paint layer, the base) is
 * flat, so until other layers give the content extent in the other planes the box is assumed from
 * layer 1's own size: its width W, its height H, and a depth [assumedDepth]. The box's centre is
 * W/2 across, H/2 down, and half that depth BEHIND layer 1's plane, away from the viewer; never on
 * the plane and never far off.
 */
object OrbitPivot {
    /**
     * The assumed depth D of layer 1's box, for a layer [width] by [height]: its longer side, so the
     * assumed box is a cube on that side. The owner did not state D; this is the choice, kept in
     * one place so it can be changed in one place.
     */
    fun assumedDepth(width: Float, height: Float): Float = max(width, height)

    /** Layers that hold content: not groups and not adjustments. Layer 1 is the first of them. */
    private fun contentLayers(layers: List<Layer>): List<Layer> =
        layers.filter { !it.isContainer && !it.isAdjustment }

    /**
     * The pivot to use: the user's [manual] point when they have placed one, else the automatic
     * [of]. A manual point that is not three finite numbers is ignored.
     */
    fun resolve(layers: List<Layer>, width: Float, height: Float, manual: List<Float>?): FloatArray =
        if (manual != null && manual.size == 3 && manual.all { it.isFinite() }) {
            floatArrayOf(manual[0], manual[1], manual[2])
        } else {
            of(layers, width, height)
        }

    /**
     * The pivot for [layers] on a [width] by [height] canvas, in scene pixels (x, y, z): the centre
     * of the box that holds every surface defined now (see [ofSurfaces]) when there is one, else
     * the plane-layer box described on the object.
     */
    fun of(layers: List<Layer>, width: Float, height: Float): FloatArray {
        ofSurfaces(layers, width, height)?.let { return it }
        val d = assumedDepth(width, height)
        val content = contentLayers(layers)
        val base = content.firstOrNull() ?: return floatArrayOf(width / 2f, height / 2f, d / 2f)
        val baseModel = SceneMath.chainModel(planeChain(layers, base.id), width, height)
        // Layer 1 alone: its own box, carried with it. Because the box rides the layer's pose, the
        // point does not move when that pose turns about it, so a drag cannot make it jump.
        if (content.size == 1) return baseModel.apply(width / 2f, height / 2f, d / 2f)

        val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (layer in content) {
            val m = SceneMath.chainModel(planeChain(layers, layer.id), width, height)
            for ((u, v) in listOf(0f to 0f, width to 0f, 0f to height, width to height)) {
                val p = m.apply(u, v, 0f)
                for (i in 0..2) { lo[i] = min(lo[i], p[i]); hi[i] = max(hi[i], p[i]) }
            }
        }
        // Along an axis the content is still flat on, fall back to layer 1's assumed box.
        val flat = d * 1e-3f
        val box = FloatArray(3) { Float.MAX_VALUE } to FloatArray(3) { -Float.MAX_VALUE }
        for (z in listOf(0f, d)) for ((u, v) in listOf(0f to 0f, width to 0f, 0f to height, width to height)) {
            val p = baseModel.apply(u, v, z)
            for (i in 0..2) { box.first[i] = min(box.first[i], p[i]); box.second[i] = max(box.second[i], p[i]) }
        }
        return FloatArray(3) { i ->
            if (abs(hi[i] - lo[i]) <= flat) (box.first[i] + box.second[i]) / 2f else (lo[i] + hi[i]) / 2f
        }
    }

    /**
     * The pivot of the surfaces: the centre of the axis-aligned box that holds, in world space, the
     * geometry of EVERY surface defined at this moment (each profiled group's [SurfaceMath.localBounds]
     * through its group chain) together with the planes of any content layer that is not paint on a
     * surface. Null when no group has a surface, which leaves the plane-layer rule above.
     *
     * The box's centre is used rather than a vertex or area centroid on purpose: it depends only on
     * the extent of the geometry, so it does not shift when a stroke is simplified, a relief's grid is
     * rebuilt or one surface is tessellated more finely than another, and it moves only when a surface
     * is added, removed, changed or moved. Paint layers inside a surface group are skipped, because
     * their own plane is the flat paint space, not where the paint is drawn. An axis the box is flat
     * on keeps that flat position.
     */
    fun ofSurfaces(layers: List<Layer>, width: Float, height: Float): FloatArray? {
        val surfaces = layers.filter { it.isContainer && it.surface?.isValid == true }
        if (surfaces.isEmpty()) return null
        val byId = layers.associateBy { it.id }
        val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        fun grow(p: FloatArray) {
            for (i in 0..2) { lo[i] = min(lo[i], p[i]); hi[i] = max(hi[i], p[i]) }
        }
        for (group in surfaces) {
            val (a, b) = SurfaceMath.localBounds(group.surface!!) ?: continue
            val m = SceneMath.chainModel(planeChain(layers, group.id), width, height)
            for (mask in 0 until 8) {
                grow(m.apply(if (mask and 1 == 0) a[0] else b[0], if (mask and 2 == 0) a[1] else b[1], if (mask and 4 == 0) a[2] else b[2]))
            }
        }
        // Planes that are not paint on a surface (a layer beside the surfaces) are part of the content too.
        for (layer in contentLayers(layers)) {
            if (SurfaceMath.ownerOf(byId, layer.id) != null) continue
            val m = SceneMath.chainModel(planeChain(layers, layer.id), width, height)
            for ((u, v) in listOf(0f to 0f, width to 0f, 0f to height, width to height)) grow(m.apply(u, v, 0f))
        }
        if (lo[0] > hi[0]) return null
        return FloatArray(3) { (lo[it] + hi[it]) / 2f }
    }

    /**
     * The scene point a tap at canvas ([x], [y]) names, for "Relocate rotation point": where the ray
     * from the eye first meets a surface (a hull by its solid, a relief or sheet by its strips), else
     * the point on that ray at the depth of the [current] pivot, so the point sits under the finger
     * at the depth the scene already revolves about. Never null.
     */
    fun pick(
        layers: List<Layer>,
        camera: SceneCamera,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        current: FloatArray,
    ): FloatArray {
        val cam = SceneMath.frame(camera, width, height)
        val dir = cam.rayDirection(x, y)
        var best: FloatArray? = null
        var bestDistance = Float.MAX_VALUE
        val byId = layers.associateBy { it.id }
        // What the renderer draws: not hidden itself or under a hidden group, and not at zero opacity
        // (its own times every enclosing group's), as DepthComposite.plan and the layer stack skip them.
        fun drawn(layer: Layer): Boolean {
            var opacity = 1f
            var current: Layer? = layer
            val seen = HashSet<String>()
            while (current != null && seen.add(current.id)) {
                if (!current.isVisible) return false
                opacity *= current.opacity
                current = current.parentId?.let(byId::get)
            }
            return opacity > 0f
        }
        for (group in layers.filter { it.isContainer && it.surface?.isValid == true && drawn(it) }) {
            val profile = group.surface!!
            val model = SceneMath.chainModel(planeChain(layers, group.id), width, height)
            val inv = model.inverse() ?: continue
            val near = SceneMath.nearPlane(cam.focal)
            val hull = if (profile.hasHull) SurfaceHull.of(profile) else null
            val s: Float
            if (hull != null) {
                val eye = inv.apply(cam.eye[0], cam.eye[1], cam.eye[2])
                val d = inv.applyVector(dir[0], dir[1], dir[2])
                s = (hull.hit(eye, d, near) ?: continue).distance
            } else {
                val hit = SurfaceMath.hit(x, y, profile, model, cam) ?: continue
                // The sheet is swept without bound, but is drawn and boxed only as far as the guide grid reaches.
                if (profile.relief == null && hit.t > SurfaceMath.gridExtent(profile)) continue
                s = hit.distance
            }
            // The hit is read straight off the ray: both hits report the ray parameter, which an affine
            // model leaves unchanged, so no (u, t) is rebuilt (that clamped a relief's negative coordinates).
            if (s < bestDistance) { bestDistance = s; best = FloatArray(3) { cam.eye[it] + dir[it] * s } }
        }
        best?.let { return it }
        val depth = cam.toCamera(current)[2].coerceAtLeast(SceneMath.nearPlane(cam.focal) * 2f)
        // The ray's depth along the view axis is s * (dir . forward); solve for that depth.
        val s = depth / SceneMath.dot(dir, cam.forward).coerceAtLeast(1e-6f)
        return FloatArray(3) { cam.eye[it] + dir[it] * s }
    }
}
