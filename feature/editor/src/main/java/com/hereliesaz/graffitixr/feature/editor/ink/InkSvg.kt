package com.hereliesaz.graffitixr.feature.editor.ink

import java.util.Locale

/**
 * Builds an SVG document out of Jetpack Ink stroke outlines — written next to the bundle by
 * "Export for Figma" (EditorViewModel.exportForFigma) whenever the visible layers hold Ink strokes,
 * so it can be dropped straight into Figma over the PNGs to see what a future vector layer built
 * from these strokes would hold.
 *
 * Pure Kotlin on plain point lists: the Ink-specific part (walking a `PartitionedMesh`'s outlines)
 * lives in [InkStrokes.outlines], so this half is JVM-testable without Ink's native library.
 */
internal object InkSvg {

    /** One stroke: its outline loops (each a closed polygon of x,y pairs) and its ARGB colour. */
    data class StrokeOutlines(val outlines: List<FloatArray>, val argb: Int)

    /**
     * An SVG sized [width]x[height] with one `<path>` per stroke. Every outline of a stroke goes into
     * that stroke's single path as its own closed subpath, filled `nonzero` — Ink's outlines trace
     * the mesh boundary and can overlap themselves, and nonzero is what keeps a self-crossing
     * stroke solid rather than punching evenodd holes where it loops.
     */
    fun document(width: Int, height: Int, strokes: List<StrokeOutlines>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" """)
        append("""viewBox="0 0 $width $height">""").append('\n')
        strokes.forEachIndexed { index, stroke ->
            val d = pathData(stroke.outlines)
            if (d.isEmpty()) return@forEachIndexed
            val alpha = (stroke.argb ushr ALPHA_SHIFT and BYTE) / BYTE_F
            append("""  <path id="ink-stroke-$index" d="$d" fill="${rgbHex(stroke.argb)}" """)
            append("""fill-opacity="${num(alpha)}" fill-rule="nonzero"/>""").append('\n')
        }
        append("</svg>\n")
    }

    /** `M x y L x y … Z` per outline; outlines with fewer than three points enclose nothing and are skipped. */
    fun pathData(outlines: List<FloatArray>): String = buildString {
        for (outline in outlines) {
            val points = outline.size / 2
            if (points < MIN_POLYGON_POINTS) continue
            if (isNotEmpty()) append(' ')
            append("M ").append(num(outline[0])).append(' ').append(num(outline[1]))
            for (i in 1 until points) {
                append(" L ").append(num(outline[i * 2])).append(' ').append(num(outline[i * 2 + 1]))
            }
            append(" Z")
        }
    }

    /** `#rrggbb` — SVG colours carry alpha separately, as `fill-opacity`. */
    fun rgbHex(argb: Int): String = "#%06x".format(Locale.US, argb and RGB_MASK)

    /** Two decimals, trailing zeros trimmed, always a '.' decimal separator whatever the locale. */
    private fun num(v: Float): String =
        String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }

    private const val MIN_POLYGON_POINTS = 3
    private const val ALPHA_SHIFT = 24
    private const val BYTE = 0xFF
    private const val BYTE_F = 255f
    private const val RGB_MASK = 0xFFFFFF
}
