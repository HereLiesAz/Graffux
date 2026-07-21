// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/importer/PsdVectorMask.kt
package com.hereliesaz.graffitixr.common.importer

/**
 * Parses a Photoshop **vector mask** (`vmsk` / `vsms` additional-layer-info block) into editable
 * [ImportedPath]s — how a shape layer's real vector outline is preserved on import.
 *
 * The block is an 8-byte header (version + flags) followed by fixed 26-byte path records. Each record
 * starts with a 2-byte selector:
 *  - **0 / 3** — closed / open subpath *length* record (begins a new subpath).
 *  - **1 / 2** — closed subpath Bézier knot (linked / unlinked).
 *  - **4 / 5** — open subpath Bézier knot.
 *  - 6 / 7 / 8 — fill rule / clipboard / initial fill (no geometry; skipped).
 *
 * A knot record holds three points — preceding control, **anchor**, leaving control — each a pair of
 * signed 8.24 fixed-point numbers `(vertical, horizontal)`. Coordinates are relative to the document:
 * vertical to the height, horizontal to the width. We keep the anchor points (a poly-line
 * approximation of the Béziers), converted to document pixels.
 */
object PsdVectorMask {

    private const val FIXED = 16777216.0 // 2^24
    private const val RECORD = 26

    /** Parses [block] (the `vmsk`/`vsms` data) into subpaths, in [docW]×[docH] pixel space. */
    fun parse(block: ByteArray, docW: Int, docH: Int): List<ImportedPath> {
        if (block.size < 8 + RECORD || docW <= 0 || docH <= 0) return emptyList()
        val paths = ArrayList<ImportedPath>()
        var points: ArrayList<Float>? = null
        var closed = false
        var pos = 8 // skip version(4) + flags(4)
        while (pos + RECORD <= block.size) {
            when (u16(block, pos)) {
                0, 3 -> {
                    flush(paths, points, closed)
                    points = ArrayList()
                    closed = u16(block, pos) == 0
                }
                1, 2, 4, 5 -> {
                    val p = points ?: ArrayList<Float>().also { points = it }
                    val vert = i32(block, pos + 10)  // anchor = the middle of the three points
                    val horiz = i32(block, pos + 14)
                    p.add((horiz / FIXED * docW).toFloat())
                    p.add((vert / FIXED * docH).toFloat())
                }
                // 6/7/8 and anything else: no geometry.
            }
            pos += RECORD
        }
        flush(paths, points, closed)
        return paths
    }

    private fun flush(into: MutableList<ImportedPath>, points: List<Float>?, closed: Boolean) {
        if (points != null && points.size >= 4) into.add(ImportedPath(points, closed))
    }

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun i32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
}
