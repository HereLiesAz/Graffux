@file:Suppress("MagicNumber", "ReturnCount", "LongParameterList", "TooManyFunctions", "MaxLineLength", "CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements", "ComplexCondition")

package com.hereliesaz.graffitixr.common.scene

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.Base64
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How a depth map becomes a relief grid ([ReliefSurface.build]). */
data class ReliefOptions(
    /** The most grid vertices; a bigger photo gets a coarser grid so the mesh stays interactive. */
    val maxVertices: Int = ReliefSurface.MAX_VERTICES,
    /** Bilateral smoothing radius in grid cells; 0 leaves the depth as the model gave it. */
    val smoothingRadius: Int = 2,
    /** The depth difference (0..1) beyond which two neighbours are not averaged: edges survive smoothing. */
    val edgeSigma: Float = 0.1f,
    /** Depth quantiles clamped to 0 and 1, so a few wild samples do not squash the rest. */
    val clipLow: Float = 0.02f,
    val clipHigh: Float = 0.98f,
)

/**
 * A photo's depth as a surface (spec/3d-space.md, "Relief surfaces"): a grid of heights over the
 * photo's plane. It lives in [SurfaceProfile.relief], so a relief group is an ordinary group surface:
 * ownership, undo, selection, the wire mesh and the paint mapping all go through the group-surface path.
 *
 * The base plane is the group's unrolled plane itself: a point of the surface at unrolled `(u, t)` is
 * `(u, t, z)` in the group's box space, with z the displacement from the grid. The photo covers the
 * rectangle [left], [top], [spanU] x [spanT] of that plane, and the photo's own pixels are the paint
 * space ([photoPixel]), so painting needs no unrolling. Heights are 0..1, 1 nearest; scene z points
 * away from the viewer, so z = -(height - 0.5) * [displacement] (a flat depth is the plane z = 0).
 *
 * The heights are stored as 16-bit samples in base64 ([heightData]), so a 64k-vertex grid is about 170 KB
 * of project JSON; [heights] is the decoded copy. [inverted] and [displacement] apply on read, so the
 * strength slider and the invert toggle never touch the data.
 */
@Serializable
data class ReliefSurface(
    val cols: Int,
    val rows: Int,
    val heightData: String,
    val left: Float,
    val top: Float,
    val spanU: Float,
    val spanT: Float,
    val photoWidth: Int,
    val photoHeight: Int,
    val displacement: Float,
    val inverted: Boolean = false,
) {
    /** The stored heights (not inverted), row-major; empty when [heightData] is not a grid. */
    @Transient
    val heights: FloatArray = decode(heightData)

    val isValid: Boolean
        get() = cols >= 2 && rows >= 2 && heights.size == cols * rows &&
            left.isFinite() && top.isFinite() && spanU.isFinite() && spanU > 0f && spanT.isFinite() && spanT > 0f &&
            photoWidth > 0 && photoHeight > 0 && displacement.isFinite() && displacement >= 0f

    /** The height (0..1, 1 nearest) of grid vertex ([i], [j]), as displayed. */
    fun height(i: Int, j: Int): Float {
        val h = heights[j * cols + i]
        return if (inverted) 1f - h else h
    }

    /** The displaced z of grid vertex ([i], [j]). */
    fun z(i: Int, j: Int): Float = -(height(i, j) - 0.5f) * displacement

    fun vertexU(i: Int): Float = left + spanU * i / (cols - 1)

    fun vertexT(j: Int): Float = top + spanT * j / (rows - 1)

    /**
     * Whether cell ([i], [j]) is cut along its b-c diagonal (top-right to bottom-left) rather than a-d: the
     * diagonal across the smaller height difference, so a step is never sliced across a cell.
     */
    fun cutsAntiDiagonal(i: Int, j: Int): Boolean {
        val a = heights[j * cols + i]
        val b = heights[j * cols + i + 1]
        val c = heights[(j + 1) * cols + i]
        val d = heights[(j + 1) * cols + i + 1]
        return abs(b - c) < abs(a - d)
    }

    /** The displayed height at unrolled ([u], [t]), exact on the mesh's triangles; clamped to the extent. */
    fun heightAt(u: Float, t: Float): Float {
        val fu = ((u - left) / spanU * (cols - 1)).coerceIn(0f, (cols - 1).toFloat())
        val ft = ((t - top) / spanT * (rows - 1)).coerceIn(0f, (rows - 1).toFloat())
        val i = min(floor(fu).toInt(), cols - 2)
        val j = min(floor(ft).toInt(), rows - 2)
        val x = fu - i
        val y = ft - j
        val ha = height(i, j)
        val hb = height(i + 1, j)
        val hc = height(i, j + 1)
        val hd = height(i + 1, j + 1)
        return if (cutsAntiDiagonal(i, j)) {
            if (x + y <= 1f) ha + (hb - ha) * x + (hc - ha) * y else hd + (hc - hd) * (1f - x) + (hb - hd) * (1f - y)
        } else {
            if (y <= x) ha + (hb - ha) * x + (hd - hb) * y else ha + (hd - hc) * x + (hc - ha) * y
        }
    }

    /** The surface point (group box space) over unrolled ([u], [t]). */
    fun at(u: Float, t: Float): FloatArray = floatArrayOf(u, t, -(heightAt(u, t) - 0.5f) * displacement)

    /** The photo pixel under unrolled ([u], [t]): the paint space. */
    fun photoPixel(u: Float, t: Float): Pair<Float, Float> =
        (u - left) / spanU * photoWidth to (t - top) / spanT * photoHeight

    /** The inverse of [photoPixel]. */
    fun unrolled(px: Float, py: Float): Pair<Float, Float> =
        left + px / photoWidth * spanU to top + py / photoHeight * spanT

    fun withDisplacement(value: Float): ReliefSurface = copy(displacement = if (value.isFinite()) max(value, 0f) else 0f)

    fun withInverted(value: Boolean): ReliefSurface = copy(inverted = value)

    /** The group surface this relief is: a base line along box x, swept along box y, carrying the grid. */
    fun toProfile(): SurfaceProfile =
        SurfaceProfile(listOf(0f, 0f, 0f, left + spanU, 0f, 0f), 0f, 1f, 0f, relief = this)

    companion object {
        /** The most grid vertices by default: a 64k-vertex mesh is 128k triangles and stays interactive. */
        const val MAX_VERTICES = 65_536

        /** The ~quarter of the photo's longer side a new relief stands out by. */
        fun defaultDisplacement(spanU: Float, spanT: Float): Float = 0.25f * max(spanU, spanT)

        /**
         * The grid for a photo of [photoWidth] x [photoHeight] pixels: its aspect, at most [maxVertices]
         * vertices, never more samples than the photo has, and at least 2 x 2.
         */
        fun gridSize(photoWidth: Int, photoHeight: Int, maxVertices: Int = MAX_VERTICES): Pair<Int, Int> {
            val pw = max(photoWidth, 1)
            val ph = max(photoHeight, 1)
            var c = pw
            var r = ph
            if (pw.toLong() * ph > maxVertices) {
                val s = sqrt(maxVertices.toDouble() / (pw.toDouble() * ph))
                c = (pw * s).roundToInt()
                r = (ph * s).roundToInt()
            }
            c = max(c, 2)
            r = max(r, 2)
            while (c.toLong() * r > maxVertices && (c > 2 || r > 2)) {
                if (c > 2 && (r <= 2 || c.toFloat() / pw >= r.toFloat() / ph)) c-- else r--
            }
            return c to r
        }

        /**
         * Builds the relief of a photo from its depth: [depth] is [depthWidth] x [depthHeight] row-major
         * inverse depth (larger nearer, any scale; non-finite samples count as the far plane). The photo of
         * [photoWidth] x [photoHeight] pixels covers the rectangle [left], [top], [right], [bottom] of the
         * group's unrolled plane. Null for an empty or mismatched map, an empty photo or an empty rectangle.
         */
        fun build(
            depth: FloatArray,
            depthWidth: Int,
            depthHeight: Int,
            photoWidth: Int,
            photoHeight: Int,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            options: ReliefOptions = ReliefOptions(),
        ): ReliefSurface? {
            if (depthWidth <= 0 || depthHeight <= 0 || depth.size != depthWidth * depthHeight) return null
            if (photoWidth <= 0 || photoHeight <= 0) return null
            if (!(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) || right <= left || bottom <= top) return null
            val (gc, gr) = gridSize(photoWidth, photoHeight, options.maxVertices)
            val cols = min(gc, max(depthWidth, 2))
            val rows = min(gr, max(depthHeight, 2))

            var lo = Float.POSITIVE_INFINITY
            for (v in depth) if (v.isFinite()) lo = min(lo, v)
            val grid = FloatArray(cols * rows)
            if (lo.isFinite()) {
                fun sample(x: Int, y: Int): Float = depth[y * depthWidth + x].let { if (it.isFinite()) it else lo }
                for (j in 0 until rows) {
                    val fy = if (depthHeight == 1) 0f else j.toFloat() / (rows - 1) * (depthHeight - 1)
                    val y0 = min(floor(fy).toInt(), depthHeight - 1)
                    val y1 = min(y0 + 1, depthHeight - 1)
                    val ty = fy - y0
                    for (i in 0 until cols) {
                        val fx = if (depthWidth == 1) 0f else i.toFloat() / (cols - 1) * (depthWidth - 1)
                        val x0 = min(floor(fx).toInt(), depthWidth - 1)
                        val x1 = min(x0 + 1, depthWidth - 1)
                        val tx = fx - x0
                        grid[j * cols + i] = (sample(x0, y0) * (1 - tx) + sample(x1, y0) * tx) * (1 - ty) +
                            (sample(x0, y1) * (1 - tx) + sample(x1, y1) * tx) * ty
                    }
                }
                normalise(grid, options.clipLow, options.clipHigh)
                if (options.smoothingRadius > 0) smooth(grid, cols, rows, options.smoothingRadius, options.edgeSigma)
            } else {
                grid.fill(0.5f)
            }
            return ReliefSurface(
                cols, rows, encode(grid), left, top, right - left, bottom - top, photoWidth, photoHeight,
                defaultDisplacement(right - left, bottom - top),
            )
        }

        /** Min-max to 0..1 between the [lowQ] and [highQ] quantiles; a grid with no range becomes flat (0.5). */
        private fun normalise(grid: FloatArray, lowQ: Float, highQ: Float) {
            val sorted = grid.copyOf().also { it.sort() }
            fun q(f: Float) = sorted[(f.coerceIn(0f, 1f) * (sorted.size - 1)).roundToInt()]
            val lo = q(lowQ)
            val hi = q(max(highQ, lowQ))
            val range = hi - lo
            if (!(range > 1e-9f) || !range.isFinite()) {
                grid.fill(0.5f)
                return
            }
            for (k in grid.indices) grid[k] = ((grid[k] - lo) / range).coerceIn(0f, 1f)
        }

        /** Bilateral filter: spatial Gaussian times a range Gaussian, so neighbours across an edge do not mix. */
        private fun smooth(grid: FloatArray, cols: Int, rows: Int, radius: Int, edgeSigma: Float) {
            val src = grid.copyOf()
            val sigmaS = max(radius / 2f, 0.5f)
            val sigmaR = max(edgeSigma, 1e-4f)
            val spatial = FloatArray((2 * radius + 1) * (2 * radius + 1)) { k ->
                val dx = k % (2 * radius + 1) - radius
                val dy = k / (2 * radius + 1) - radius
                exp(-(dx * dx + dy * dy) / (2f * sigmaS * sigmaS))
            }
            for (j in 0 until rows) {
                for (i in 0 until cols) {
                    val centre = src[j * cols + i]
                    var sum = 0f
                    var weight = 0f
                    for (dy in -radius..radius) {
                        val y = j + dy
                        if (y < 0 || y >= rows) continue
                        for (dx in -radius..radius) {
                            val x = i + dx
                            if (x < 0 || x >= cols) continue
                            val v = src[y * cols + x]
                            val d = v - centre
                            val wgt = spatial[(dy + radius) * (2 * radius + 1) + dx + radius] * exp(-(d * d) / (2f * sigmaR * sigmaR))
                            sum += v * wgt
                            weight += wgt
                        }
                    }
                    grid[j * cols + i] = if (weight > 0f) sum / weight else centre
                }
            }
        }

        /** 16-bit big-endian samples as base64. */
        fun encode(heights: FloatArray): String {
            val bytes = ByteArray(heights.size * 2)
            for (k in heights.indices) {
                val q = (heights[k].coerceIn(0f, 1f) * 65535f).roundToInt()
                bytes[k * 2] = (q shr 8).toByte()
                bytes[k * 2 + 1] = q.toByte()
            }
            return Base64.getEncoder().encodeToString(bytes)
        }

        /** The inverse of [encode]; empty when [data] is not valid base64 of whole samples. */
        fun decode(data: String): FloatArray {
            val bytes = try {
                Base64.getDecoder().decode(data)
            } catch (_: IllegalArgumentException) {
                return FloatArray(0)
            }
            if (bytes.size % 2 != 0) return FloatArray(0)
            return FloatArray(bytes.size / 2) { k ->
                (((bytes[k * 2].toInt() and 0xFF) shl 8) or (bytes[k * 2 + 1].toInt() and 0xFF)) / 65535f
            }
        }
    }
}

/** The relief's meshes, ray hit and wire mesh. Pure Kotlin. */
object ReliefMath {
    /**
     * The mesh over the unrolled window [uMin]..[uMax] x [tMin]..[tMax]: every cell the window overlaps, as
     * triangles of x, y, z (group box space) then the unrolled u, t (what [SurfaceMath.mesh] returns for a strip).
     * Empty when the window misses the photo.
     */
    fun mesh(relief: ReliefSurface, uMin: Float, uMax: Float, tMin: Float, tMax: Float): FloatArray =
        build(relief, uMin, uMax, tMin, tMax, photo = false)

    /** The whole mesh with the photo's own pixel coordinates as the last two floats of each vertex. */
    fun photoMesh(relief: ReliefSurface): FloatArray =
        build(relief, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, photo = true)

    private fun build(relief: ReliefSurface, uMin: Float, uMax: Float, tMin: Float, tMax: Float, photo: Boolean): FloatArray {
        if (!relief.isValid || !(uMax > uMin) || !(tMax > tMin)) return FloatArray(0)
        val right = relief.left + relief.spanU
        val bottom = relief.top + relief.spanT
        if (uMax <= relief.left || uMin >= right || tMax <= relief.top || tMin >= bottom) return FloatArray(0)
        val cw = relief.spanU / (relief.cols - 1)
        val ch = relief.spanT / (relief.rows - 1)
        val i0 = floor((max(uMin, relief.left) - relief.left) / cw).toInt().coerceIn(0, relief.cols - 2)
        val i1 = (ceil((min(uMax, right) - relief.left) / cw).toInt() - 1).coerceIn(i0, relief.cols - 2)
        val j0 = floor((max(tMin, relief.top) - relief.top) / ch).toInt().coerceIn(0, relief.rows - 2)
        val j1 = (ceil((min(tMax, bottom) - relief.top) / ch).toInt() - 1).coerceIn(j0, relief.rows - 2)
        val out = FloatArray((i1 - i0 + 1) * (j1 - j0 + 1) * 6 * 5)
        var k = 0
        fun put(i: Int, j: Int) {
            val u = relief.vertexU(i)
            val t = relief.vertexT(j)
            out[k++] = u; out[k++] = t; out[k++] = relief.z(i, j)
            if (photo) {
                val p = relief.photoPixel(u, t)
                out[k++] = p.first; out[k++] = p.second
            } else {
                out[k++] = u; out[k++] = t
            }
        }
        for (j in j0..j1) {
            for (i in i0..i1) {
                if (relief.cutsAntiDiagonal(i, j)) {
                    put(i, j); put(i + 1, j); put(i, j + 1)
                    put(i + 1, j); put(i + 1, j + 1); put(i, j + 1)
                } else {
                    put(i, j); put(i + 1, j); put(i + 1, j + 1)
                    put(i, j); put(i + 1, j + 1); put(i, j + 1)
                }
            }
        }
        return out
    }

    /**
     * The nearest hit of the ray `eye + s * dir` (group box space) on the relief with `s > near`, or null. The
     * ray is walked cell by cell through the grid (a 2D DDA over the photo's extent, clipped to the slab the
     * displacement spans), so the cost follows the ray's length across the photo, not the vertex count. The
     * hit's `(u, t)` is the base plane position, which is the photo's paint space ([ReliefSurface.photoPixel]).
     */
    fun hit(relief: ReliefSurface, eye: FloatArray, dir: FloatArray, near: Float): SurfaceHit? {
        if (!relief.isValid) return null
        val cols = relief.cols
        val rows = relief.rows
        val cw = relief.spanU / (cols - 1)
        val ch = relief.spanT / (rows - 1)
        val padX = cw * 1e-3f
        val padY = ch * 1e-3f
        val half = relief.displacement / 2f + 1e-2f + relief.displacement * 1e-3f
        var s0 = near
        var s1 = Float.POSITIVE_INFINITY
        fun slab(o: Float, d: Float, lo: Float, hi: Float): Boolean {
            if (abs(d) < 1e-12f) return o in lo..hi
            val a = (lo - o) / d
            val b = (hi - o) / d
            s0 = max(s0, min(a, b))
            s1 = min(s1, max(a, b))
            return true
        }
        if (!slab(eye[0], dir[0], relief.left - padX, relief.left + relief.spanU + padX)) return null
        if (!slab(eye[1], dir[1], relief.top - padY, relief.top + relief.spanT + padY)) return null
        if (!slab(eye[2], dir[2], -half, half)) return null
        if (!(s0 <= s1) || !s0.isFinite() || !s1.isFinite()) return null

        val px = eye[0] + dir[0] * s0
        val py = eye[1] + dir[1] * s0
        var ci = floor((px - relief.left) / cw).toInt().coerceIn(0, cols - 2)
        var cj = floor((py - relief.top) / ch).toInt().coerceIn(0, rows - 2)
        val stepI = if (dir[0] > 0f) 1 else -1
        val stepJ = if (dir[1] > 0f) 1 else -1
        fun boundary(index: Int, step: Int, origin: Float, size: Float) = origin + (if (step > 0) index + 1 else index) * size
        var tMaxX = if (abs(dir[0]) < 1e-12f) Float.POSITIVE_INFINITY else (boundary(ci, stepI, relief.left, cw) - eye[0]) / dir[0]
        var tMaxY = if (abs(dir[1]) < 1e-12f) Float.POSITIVE_INFINITY else (boundary(cj, stepJ, relief.top, ch) - eye[1]) / dir[1]
        val tDeltaX = if (abs(dir[0]) < 1e-12f) Float.POSITIVE_INFINITY else cw / abs(dir[0])
        val tDeltaY = if (abs(dir[1]) < 1e-12f) Float.POSITIVE_INFINITY else ch / abs(dir[1])
        var guard = cols + rows + 8
        while (guard-- > 0) {
            val s = hitCell(relief, ci, cj, eye, dir, near)
            if (s != null) {
                val u = (eye[0] + dir[0] * s).coerceIn(relief.left, relief.left + relief.spanU)
                val t = (eye[1] + dir[1] * s).coerceIn(relief.top, relief.top + relief.spanT)
                return SurfaceHit(u, t, s)
            }
            if (min(tMaxX, tMaxY) > s1) break
            if (tMaxX < tMaxY) {
                ci += stepI
                tMaxX += tDeltaX
            } else {
                cj += stepJ
                tMaxY += tDeltaY
            }
            if (ci < 0 || ci > cols - 2 || cj < 0 || cj > rows - 2) break
        }
        return null
    }

    /** The nearest ray parameter (> [near]) on either triangle of cell ([i], [j]), or null. */
    private fun hitCell(relief: ReliefSurface, i: Int, j: Int, eye: FloatArray, dir: FloatArray, near: Float): Float? {
        val a = vertex(relief, i, j)
        val b = vertex(relief, i + 1, j)
        val c = vertex(relief, i, j + 1)
        val d = vertex(relief, i + 1, j + 1)
        val first: Float?
        val second: Float?
        if (relief.cutsAntiDiagonal(i, j)) {
            first = triangle(a, b, c, eye, dir, near)
            second = triangle(b, d, c, eye, dir, near)
        } else {
            first = triangle(a, b, d, eye, dir, near)
            second = triangle(a, d, c, eye, dir, near)
        }
        return when {
            first == null -> second
            second == null -> first
            else -> min(first, second)
        }
    }

    private fun vertex(relief: ReliefSurface, i: Int, j: Int) =
        floatArrayOf(relief.vertexU(i), relief.vertexT(j), relief.z(i, j))

    /** Moller-Trumbore, two-sided, a hair of slack at the edges so a ray never slips between triangles. */
    private fun triangle(a: FloatArray, b: FloatArray, c: FloatArray, eye: FloatArray, dir: FloatArray, near: Float): Float? {
        val e1 = FloatArray(3) { b[it] - a[it] }
        val e2 = FloatArray(3) { c[it] - a[it] }
        val p = SceneMath.cross(dir, e2)
        val det = SceneMath.dot(e1, p)
        if (abs(det) < 1e-12f) return null
        val inv = 1f / det
        val tv = FloatArray(3) { eye[it] - a[it] }
        val u = SceneMath.dot(tv, p) * inv
        if (u < -EDGE || u > 1f + EDGE) return null
        val q = SceneMath.cross(tv, e1)
        val v = SceneMath.dot(dir, q) * inv
        if (v < -EDGE || u + v > 1f + EDGE) return null
        val s = SceneMath.dot(e2, q) * inv
        return if (s.isFinite() && s > near) s else null
    }

    private const val EDGE = 1e-4f

    /**
     * The relief's guide grid: lines along the photo's rows and columns every [spacing] from its top-left
     * corner and closing on its far edges, each sampled along the grid's vertices so it follows the
     * displacement, as canvas segments `x0, y0, x1, y1` clipped to the near plane (like [SurfaceMath.gridLines]).
     */
    fun gridLines(relief: ReliefSurface, model: Affine3, cam: CameraFrame, spacing: Float): List<FloatArray> {
        if (!relief.isValid || spacing <= 0f) return emptyList()
        val out = ArrayList<FloatArray>()
        val right = relief.left + relief.spanU
        val bottom = relief.top + relief.spanT
        fun scene(u: Float, t: Float): FloatArray {
            val p = relief.at(u, t)
            return model.apply(p[0], p[1], p[2])
        }
        fun line(u0: Float, t0: Float, u1: Float, t1: Float, samples: Int) {
            var prev = scene(u0, t0)
            for (k in 1..samples) {
                val f = k.toFloat() / samples
                val next = scene(u0 + (u1 - u0) * f, t0 + (t1 - t0) * f)
                SurfaceMath.segment(prev, next, cam)?.let(out::add)
                prev = next
            }
        }
        val alongU = min(relief.cols - 1, MAX_SAMPLES)
        val alongT = min(relief.rows - 1, MAX_SAMPLES)
        for (t in ticks(relief.top, bottom, spacing)) line(relief.left, t, right, t, alongU)
        for (u in ticks(relief.left, right, spacing)) line(u, relief.top, u, bottom, alongT)
        return out
    }

    private fun ticks(from: Float, to: Float, spacing: Float): List<Float> {
        val out = ArrayList<Float>()
        var k = 0
        while (from + k * spacing < to - 1e-3f && out.size < SurfaceMath.GRID_MAX_LINES) {
            out += from + k * spacing
            k++
        }
        out += to
        return out
    }

    /**
     * Polylines of unrolled `(u, t)` points that outline the relief as the eye sees it: the photo's border at
     * every vertex and a coarse net across it (a tall relief seen from the side projects outside its border).
     * For [SurfaceFootprint]'s nearest-point search.
     */
    fun outline(relief: ReliefSurface): List<FloatArray> {
        if (!relief.isValid) return emptyList()
        val out = ArrayList<FloatArray>()
        fun poly(vertices: List<Pair<Float, Float>>) {
            out += FloatArray(vertices.size * 2) { if (it % 2 == 0) vertices[it / 2].first else vertices[it / 2].second }
        }
        val topT = relief.top
        val botT = relief.top + relief.spanT
        val leftU = relief.left
        val rightU = relief.left + relief.spanU
        poly((0 until relief.cols).map { relief.vertexU(it) to topT })
        poly((0 until relief.cols).map { relief.vertexU(it) to botT })
        poly((0 until relief.rows).map { leftU to relief.vertexT(it) })
        poly((0 until relief.rows).map { rightU to relief.vertexT(it) })
        for (n in 1 until NET) {
            val t = relief.top + relief.spanT * n / NET
            val u = relief.left + relief.spanU * n / NET
            poly((0..NET_SAMPLES).map { (relief.left + relief.spanU * it / NET_SAMPLES) to t })
            poly((0..NET_SAMPLES).map { u to (relief.top + relief.spanT * it / NET_SAMPLES) })
        }
        return out
    }

    private const val MAX_SAMPLES = 96
    private const val NET = 8
    private const val NET_SAMPLES = 48
}
