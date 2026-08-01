package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.util.SafeBitmap

/**
 * Procreate's Distort and Warp: pushing a layer's pixels around by dragging a grid of handles.
 *
 * Both are one idea at two resolutions. The handles are an **n × n grid**, initially sitting on the
 * layer's corners and evenly spaced between them; moving one says "the pixels that were here should
 * now be there". Distort is that grid at n = 2 — four corners, so the only deformation expressible
 * is a perspective one — and Warp is the same grid subdivided, where each cell bends independently.
 * Keeping them one representation rather than two means the handle UI, the drag maths, the undo
 * record and the bake are each written once.
 *
 * The transform is **baked into the bitmap** rather than applied at render time, which is how
 * Liquify already works here. A live mesh transform would mean replacing the layer's `Image` with
 * mesh drawing and re-implementing colour filters, blend modes and letterboxing on top of it; baking
 * costs one pass over the pixels and leaves export, compositing and history untouched.
 */
internal object ImageWarp {

    /** Handles for an [n] × [n] grid over a [width] × [height] rect, row-major from the top-left. */
    fun identityGrid(n: Int, width: Float, height: Float, origin: Offset = Offset.Zero): List<Offset> {
        if (n < 2) return emptyList()
        val out = ArrayList<Offset>(n * n)
        for (row in 0 until n) {
            for (col in 0 until n) {
                out.add(
                    Offset(
                        origin.x + width * col / (n - 1),
                        origin.y + height * row / (n - 1),
                    )
                )
            }
        }
        return out
    }

    /** The grid size a flat handle list represents, or 0 if it isn't square. */
    fun gridSizeOf(handles: List<Offset>): Int {
        if (handles.size < 4) return 0
        val n = Math.round(Math.sqrt(handles.size.toDouble())).toInt()
        return if (n * n == handles.size) n else 0
    }

    /**
     * Warps [source] so that its identity grid lands on [destination], both in **bitmap pixels**.
     *
     * Returns a new bitmap of the same size; [source] is untouched. Null when the handles don't
     * describe a square grid, which is the caller having got its own state wrong rather than
     * anything the user did.
     *
     * n = 2 goes through [Matrix.setPolyToPoly], which for four points is the exact perspective
     * transform — a true Distort, with straight lines staying straight and converging properly.
     * Doing that case with the mesh would split the quad into two triangles and shade each
     * affinely, putting a visible crease along the diagonal wherever the perspective is strong.
     */
    fun warp(source: Bitmap, destination: List<Offset>): Bitmap? {
        val n = gridSizeOf(destination)
        if (n < 2) return null
        val w = source.width.toFloat()
        val h = source.height.toFloat()
        val out = SafeBitmap.create(source.width, source.height) ?: return null
        val canvas = Canvas(out)
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }

        if (n == 2) {
            val src = floatArrayOf(0f, 0f, w, 0f, 0f, h, w, h)
            val dst = FloatArray(8)
            // The grid is row-major, and setPolyToPoly wants the same corner order it was given:
            // top-left, top-right, bottom-left, bottom-right.
            listOf(0, 1, 2, 3).forEachIndexed { i, idx ->
                dst[i * 2] = destination[idx].x
                dst[i * 2 + 1] = destination[idx].y
            }
            val matrix = Matrix()
            // False when the quad is degenerate — dragged inside-out, or three handles collinear.
            // There is no sensible transform then, so the untouched copy is the honest answer.
            if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) {
                canvas.drawBitmap(source, 0f, 0f, paint)
                return out
            }
            canvas.drawBitmap(source, matrix, paint)
            return out
        }

        // drawBitmapMesh counts *quads*, not vertices, and wants them row-major as (x, y) pairs —
        // which is the order identityGrid already produces.
        val verts = FloatArray(destination.size * 2)
        destination.forEachIndexed { i, p ->
            verts[i * 2] = p.x
            verts[i * 2 + 1] = p.y
        }
        canvas.drawBitmapMesh(source, n - 1, n - 1, verts, 0, null, 0, paint)
        return out
    }
}
