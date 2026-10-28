@file:Suppress("MagicNumber", "MaxLineLength", "CyclomaticComplexMethod")

package com.hereliesaz.graffitixr.feature.editor.export

import androidx.compose.ui.graphics.BlendMode
import java.util.Base64
import java.util.Locale

/**
 * Writes a [VectorPage] as an SVG document: groups and frames as `<g>` (a frame's fill as a
 * `<rect>`, its clip as a `<clipPath>`), shapes as `<path>`, and pixel layers as embedded PNG
 * `<image>`s ([encodePng] gives a layer's PNG bytes, null to leave it out). Pure text, so it is
 * tested on the JVM. Opacity and the blend modes CSS has are kept; the rest draw normally.
 */
object SvgWriter {

    fun write(page: VectorPage, encodePng: (VectorNode.Image) -> ByteArray?): String = buildString {
        val w = num(page.width)
        val h = num(page.height)
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="$w" height="$h" viewBox="0 0 $w $h">""").append('\n')
        append("  <title>").append(escape(page.name)).append("</title>\n")
        val clips = StringBuilder()
        val body = StringBuilder()
        var clipCount = 0
        fun node(n: VectorNode, depth: Int) {
            val pad = "  ".repeat(depth)
            when (n) {
                is VectorNode.Group -> {
                    val box = n.box
                    var clipRef = ""
                    if (box != null && n.clips) {
                        val id = "clip${clipCount++}"
                        clips.append("    <clipPath id=\"$id\">").append(rect(box)).append("</clipPath>\n")
                        clipRef = " clip-path=\"url(#$id)\""
                    }
                    body.append(pad).append("<g").append(attrs(n, n.name))
                    body.append(">\n")
                    // The clip is in the group's own space, so it sits on an inner group.
                    if (clipRef.isNotEmpty()) body.append(pad).append("  <g").append(clipRef).append(">\n")
                    val inner = if (clipRef.isNotEmpty()) depth + 2 else depth + 1
                    if (box != null && n.fillArgb != null) {
                        body.append("  ".repeat(inner)).append(rect(box, n.fillArgb)).append('\n')
                    }
                    n.children.forEach { node(it, inner) }
                    if (clipRef.isNotEmpty()) body.append(pad).append("  </g>\n")
                    body.append(pad).append("</g>\n")
                }
                is VectorNode.Shape -> {
                    val d = pathData(n.path)
                    if (d.isEmpty()) return
                    body.append(pad).append("<path").append(attrs(n, null)).append(" d=\"").append(d).append('"')
                    body.append(paint("fill", n.fillArgb))
                    if (n.strokeArgb != null && n.strokeWidth > 0f) {
                        body.append(paint("stroke", n.strokeArgb)).append(" stroke-width=\"").append(num(n.strokeWidth)).append('"')
                    }
                    body.append("/>\n")
                }
                is VectorNode.Image -> {
                    val png = encodePng(n) ?: return
                    body.append(pad).append("<image").append(attrs(n, n.name))
                    body.append(" width=\"").append(n.width).append("\" height=\"").append(n.height).append('"')
                    body.append(" preserveAspectRatio=\"none\" xlink:href=\"data:image/png;base64,")
                    body.append(Base64.getEncoder().encodeToString(png)).append("\"/>\n")
                }
            }
        }
        page.nodes.forEach { node(it, 2) }
        if (clips.isNotEmpty()) append("  <defs>\n").append(clips).append("  </defs>\n")
        append("  <g").append(if (page.root.isIdentity) "" else " transform=\"${matrix(page.root)}\"").append(">\n")
        append(body)
        append("  </g>\n</svg>\n")
    }

    private fun attrs(n: VectorNode, name: String?): String = buildString {
        if (name != null) append(" data-name=\"").append(escape(name)).append('"')
        if (!n.transform.isIdentity) append(" transform=\"").append(matrix(n.transform)).append('"')
        if (n.opacity < 1f) append(" opacity=\"").append(num(n.opacity.coerceIn(0f, 1f))).append('"')
        cssBlend(n.blendMode)?.let { append(" style=\"mix-blend-mode:").append(it).append('"') }
    }

    private fun rect(box: FloatArray, fill: Long? = null): String = buildString {
        append("<rect x=\"").append(num(box[0])).append("\" y=\"").append(num(box[1]))
        append("\" width=\"").append(num(box[2])).append("\" height=\"").append(num(box[3])).append('"')
        if (box[4] > 0f) append(" rx=\"").append(num(box[4])).append('"')
        if (fill != null) append(paint("fill", fill))
        append("/>")
    }

    /** `fill="#rrggbb"` plus an opacity attribute when the colour is translucent; `none` for null. */
    private fun paint(attr: String, argb: Long?): String {
        if (argb == null) return " $attr=\"none\""
        val v = argb.toInt()
        val alpha = (v ushr 24 and 0xFF) / 255f
        val hex = "#%06x".format(Locale.US, v and 0xFFFFFF)
        return " $attr=\"$hex\"" + if (alpha < 1f) " $attr-opacity=\"${num(alpha)}\"" else ""
    }

    fun pathData(path: List<PathCmd>): String = path.joinToString(" ") {
        when (it) {
            is PathCmd.Move -> "M ${num(it.x)} ${num(it.y)}"
            is PathCmd.Line -> "L ${num(it.x)} ${num(it.y)}"
            is PathCmd.Cubic -> "C ${num(it.x1)} ${num(it.y1)} ${num(it.x2)} ${num(it.y2)} ${num(it.x)} ${num(it.y)}"
            PathCmd.Close -> "Z"
        }
    }

    private fun matrix(m: Affine) = "matrix(${num(m.a)} ${num(m.b)} ${num(m.c)} ${num(m.d)} ${num(m.e)} ${num(m.f)})"

    /** The blend modes CSS (and so SVG) can express; null for normal and for the rest. */
    private fun cssBlend(mode: BlendMode): String? = when (mode) {
        BlendMode.Multiply -> "multiply"
        BlendMode.Screen -> "screen"
        BlendMode.Overlay -> "overlay"
        BlendMode.Darken -> "darken"
        BlendMode.Lighten -> "lighten"
        BlendMode.ColorDodge -> "color-dodge"
        BlendMode.ColorBurn -> "color-burn"
        BlendMode.Hardlight -> "hard-light"
        BlendMode.Softlight -> "soft-light"
        BlendMode.Difference -> "difference"
        BlendMode.Exclusion -> "exclusion"
        BlendMode.Hue -> "hue"
        BlendMode.Saturation -> "saturation"
        BlendMode.Color -> "color"
        BlendMode.Luminosity -> "luminosity"
        else -> null
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Up to three decimals, trailing zeros trimmed, always a '.' whatever the locale. */
    private fun num(v: Float): String =
        String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }
}
