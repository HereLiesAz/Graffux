package com.hereliesaz.graffitixr.common.mesh

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Whether two surface hits are one stretch of surface, or two parts of it that merely overlap on
 * screen.
 *
 * [UvIslands] says two triangles are in the same chart, which is necessary to join a stroke between
 * them but not sufficient: a strip folded over itself is one island, and when the front panel ends
 * the camera ray drops from it onto the back panel with no miss in between. Both hits are on the
 * island, and a line between their UVs runs through the fold the finger never touched.
 *
 * Within one continuous patch the UV distance between two points cannot be much more than the
 * distance between them in space times the patch's UV-per-length (it can be a little more because
 * the surface may curve between them). Across an occlusion edge the points are close in space and
 * far in UV, which breaks that bound. The check needs no camera and no neighbour walk, so it is
 * cheap enough to run for every sample of a fast drag.
 *
 * The bound first uses the two end triangles' UV-per-length. Where that rejects, it is retried with
 * the densest triangle of the island near the chord between the hits, because a narrow, densely
 * mapped strip between two coarse triangles spans far more UV than either end suggests (a seamless
 * move would otherwise be split and leave a gap). An occlusion drop stays rejected: the triangles
 * near its chord are the two panels, no denser than the ends. So the check errs toward joining
 * wherever the surface between the hits is continuous, and toward breaking across a fold only when
 * nothing dense lies near the chord.
 *
 * It cannot tell two panels apart that are so far apart in space that the straight line between them
 * is as long as the way round the fold; there the stroke is joined, as it was before.
 */
object SurfaceContinuity {
    /** How much longer than the straight line the way across the surface may be, allowing for curvature. */
    const val CURVATURE_ALLOWANCE = 2.0

    /** UV slack, so two hits a hair apart in one triangle's rounding are never split. */
    private const val UV_SLACK = 1e-4

    /**
     * Whether hits [a] and [b] can be joined by a straight line in UV space. Hits without a position
     * (built outside [MeshPicker.raycast]) are given the benefit of the doubt.
     */
    fun continuous(mesh: Mesh, islands: UvIslands, a: MeshPicker.Hit, b: MeshPicker.Hit): Boolean {
        val unknown = a.x.isNaN() || b.x.isNaN()
        return unknown || a.triangleIndex == b.triangleIndex || run {
            val space = sqrt(
                sq((a.x - b.x).toDouble()) + sq((a.y - b.y).toDouble()) + sq((a.z - b.z).toDouble()),
            )
            val uv = hypot((a.u - b.u).toDouble(), (a.v - b.v).toDouble())
            val ends = maxOf(uvPerLength(mesh, a.triangleIndex), uvPerLength(mesh, b.triangleIndex))
            // The end triangles' own density is the cheap bound. Only when it fails is the surface
            // between them looked at: the densest triangle of the island near the chord, which also
            // bounds a narrow strip the chord crosses. An occlusion drop stays rejected because the
            // triangles near its chord are the two panels, no denser than the ends.
            uv <= bound(space, ends) || uv <= bound(space, surfaceDensity(islands, a, b, space))
        }
    }

    private fun bound(space: Double, density: Double) = CURVATURE_ALLOWANCE * space * density + UV_SLACK

    /** The densest triangle of the island within one chord length of the chord from [a] to [b]. */
    private fun surfaceDensity(islands: UvIslands, a: MeshPicker.Hit, b: MeshPicker.Hit, space: Double): Double {
        val pad = space.toFloat()
        val lo = floatArrayOf(minOf(a.x, b.x) - pad, minOf(a.y, b.y) - pad, minOf(a.z, b.z) - pad)
        val hi = floatArrayOf(maxOf(a.x, b.x) + pad, maxOf(a.y, b.y) + pad, maxOf(a.z, b.z) + pad)
        return islands.maxDensityNear(islands.islandOf(a.triangleIndex), lo, hi)
    }

    private fun sq(x: Double) = x * x

    /**
     * The most UV distance a unit of length on triangle [triangle] can span: the largest singular
     * value of the map from the triangle's plane to UV space. Direction-independent, so a UV map
     * stretched along one axis is bounded by its stretched axis rather than its average. Zero for a
     * degenerate triangle.
     */
    internal fun uvPerLength(mesh: Mesh, triangle: Int): Double {
        val stride = Mesh.FLOATS_PER_VERTEX
        val v = mesh.vertices
        val o = IntArray(CORNERS) { mesh.indices.getOrElse(triangle * CORNERS + it) { 0 } * stride }
        fun p(c: Int, axis: Int) = v.getOrElse(o[c] + axis) { 0f }.toDouble()
        fun q(c: Int, axis: Int) = v.getOrElse(o[c] + Mesh.UV_OFFSET + axis) { 0f }.toDouble()
        val e1 = DoubleArray(CORNERS) { p(1, it) - p(0, it) }
        val e2 = DoubleArray(CORNERS) { p(2, it) - p(0, it) }
        val l1 = sqrt(e1.sumOf { it * it })
        val nx = e1[1] * e2[2] - e1[2] * e2[1]
        val ny = e1[2] * e2[0] - e1[0] * e2[2]
        val nz = e1[0] * e2[1] - e1[1] * e2[0]
        val area2 = sqrt(nx * nx + ny * ny + nz * nz)
        return if (l1 <= 0.0 || area2 <= 0.0) {
            0.0
        } else {
            // In the triangle's own plane: e1 along x, so e2 = (c, d) with d = |n| / |e1|.
            val c = (0 until CORNERS).sumOf { e1[it] / l1 * e2[it] }
            val d = area2 / l1
            val d1u = q(1, 0) - q(0, 0)
            val d1v = q(1, 1) - q(0, 1)
            val d2u = q(2, 0) - q(0, 0)
            val d2v = q(2, 1) - q(0, 1)
            // J = [d1 d2] * [[l1, c], [0, d]]^-1, columns j1 = d1 / l1 and j2 = (d2 - c * j1) / d.
            val j1u = d1u / l1
            val j1v = d1v / l1
            val j2u = (d2u - c * j1u) / d
            val j2v = (d2v - c * j1v) / d
            largestSingularValue(j1u * j1u + j1v * j1v, j1u * j2u + j1v * j2v, j2u * j2u + j2v * j2v)
        }
    }

    /** The largest singular value of J given the entries of J^T J: `[[a, b], [b, c]]`. */
    private fun largestSingularValue(a: Double, b: Double, c: Double): Double {
        val mean = (a + c) / 2.0
        val spread = sqrt(((a - c) / 2.0) * ((a - c) / 2.0) + b * b)
        return sqrt(mean + spread)
    }

    private const val CORNERS = 3
}
