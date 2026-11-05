@file:Suppress("MagicNumber", "ReturnCount", "LongParameterList", "LoopWithTooManyJumpStatements")

package com.hereliesaz.graffitixr.common.scene

import kotlin.math.sqrt

/**
 * Painting and erasing by footprint on a group surface (spec/3d-space.md, "Painting by footprint").
 *
 * Where a stroke lands does not depend on which part of the surface the touch hits, only on the
 * brush footprint under it. A touch whose ray meets the surface maps exactly as [SurfaceMath.hit]
 * does, on any strip, across any bend. A touch whose ray misses it but whose footprint (a circle
 * of [radius] canvas pixels around the touch) reaches the surface maps to the surface point
 * nearest the touch on the canvas, so a stroke that grazes or leaves the surface keeps painting
 * what the brush covers instead of being dropped sample by sample. Only a footprint that touches
 * the surface nowhere is a true miss.
 */
object SurfaceFootprint {
    /**
     * The surface point [radius] canvas pixels or less from ([x], [y]) on screen: the ray hit when
     * there is one, else the nearest point of the surface's projected outline; null when the
     * footprint touches no part of the surface.
     */
    fun hit(x: Float, y: Float, radius: Float, profile: SurfaceProfile, model: Affine3, cam: CameraFrame): SurfaceHit? {
        SurfaceMath.hit(x, y, profile, model, cam)?.let { return it }
        if (!profile.isValid || radius <= 0f || !radius.isFinite()) return null
        return nearest(x, y, radius, profile, model, cam)
    }

    /** [SurfaceMath.worldToFlat] by footprint: the flat point for the touch, or null for a true miss. */
    fun worldToFlat(
        x: Float,
        y: Float,
        radius: Float,
        owner: SurfaceOwner,
        camera: SceneCamera,
        width: Float,
        height: Float,
    ): Pair<Float, Float>? {
        if (width <= 0f || height <= 0f) return null
        val cam = SceneMath.frame(camera, width, height)
        val model = SceneMath.chainModel(owner.ownerChain, width, height)
        val h = hit(x, y, radius, owner.profile, model, cam) ?: return null
        return SurfaceMath.unrolledToFlat(h.u, h.t, owner.innerChain, width, height)
    }

    /**
     * A ray that misses every strip passes outside the surface's projected area, so the nearest
     * surface point on screen is on its outline: the profile at sweep 0 and the sweep line at each
     * profile vertex. Each is a straight 3D edge, clipped to the near plane and measured in pixels.
     */
    private fun nearest(
        x: Float,
        y: Float,
        radius: Float,
        profile: SurfaceProfile,
        model: Affine3,
        cam: CameraFrame,
    ): SurfaceHit? {
        val scale = sqrt(SceneMath.dot(model.column(0), model.column(0))).coerceAtLeast(1e-6f)
        val reach = SceneMath.farPlane(cam.focal) * 0.5f / scale
        fun camAt(u: Float, t: Float): FloatArray {
            val p = profile.at(u, t)
            return cam.toCamera(model.apply(p[0], p[1], p[2]))
        }
        var best: SurfaceHit? = null
        var bestDistance = radius
        fun consider(a: FloatArray, b: FloatArray, at: (Float) -> Pair<Float, Float>) {
            val e = edge(x, y, a, b, cam) ?: return
            if (e.first <= bestDistance) {
                bestDistance = e.first
                val (u, t) = at(e.second)
                best = SurfaceHit(u, t, e.third)
            }
        }
        for (i in 0 until profile.count - 1) {
            val u0 = profile.arcLengths[i]
            val u1 = profile.arcLengths[i + 1]
            consider(camAt(u0, 0f), camAt(u1, 0f)) { f -> (u0 + (u1 - u0) * f) to 0f }
        }
        for (i in 0 until profile.count) {
            val u = profile.arcLengths[i]
            consider(camAt(u, 0f), camAt(u, reach)) { f -> u to reach * f }
        }
        return best
    }

    /**
     * Closest approach of canvas point ([x], [y]) to the camera-space segment [a]-[b] after the
     * near-plane clip: (pixel distance, fraction along the unclipped segment, camera depth there),
     * null when the segment is wholly behind the near plane.
     */
    private fun edge(x: Float, y: Float, a: FloatArray, b: FloatArray, cam: CameraFrame): Triple<Float, Float, Float>? {
        val near = SceneMath.nearPlane(cam.focal) * 2f
        if (a[2] < near && b[2] < near) return null
        var fa = 0f
        var fb = 1f
        if (a[2] < near) fa = (near - a[2]) / (b[2] - a[2])
        if (b[2] < near) fb = (a[2] - near) / (a[2] - b[2])
        fun at(f: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * f }
        val ca = at(fa)
        val cb = at(fb)
        val ax = cam.width / 2f + cam.focal * ca[0] / ca[2]
        val ay = cam.height / 2f + cam.focal * ca[1] / ca[2]
        val bx = cam.width / 2f + cam.focal * cb[0] / cb[2]
        val by = cam.height / 2f + cam.focal * cb[1] / cb[2]
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val s = if (len2 < 1e-12f) 0f else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0f, 1f)
        val px = ax + dx * s
        val py = ay + dy * s
        val dist = sqrt((x - px) * (x - px) + (y - py) * (y - py))
        // 1/z is linear in screen space, so the fraction along the 3D edge is perspective-corrected.
        val invZ = (1f - s) / ca[2] + s / cb[2]
        val g = if (invZ <= 0f) s else (s / cb[2]) / invZ
        return Triple(dist, fa + (fb - fa) * g, 1f / invZ)
    }
}
