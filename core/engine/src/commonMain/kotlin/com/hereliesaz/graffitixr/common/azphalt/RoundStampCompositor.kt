package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** A rectangle of composited ARGB_8888 pixels ([pixels], row-major, straight alpha) positioned at
 *  ([left], [top]) in the destination's coordinate space. Zero-alpha where nothing was painted. */
class CompositedTile(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val pixels: IntArray,
)

/**
 * Platform-agnostic core of the round-brush stroke compositor: every dab in [dabs] is rasterized
 * into one shared alpha/color buffer using `max`, not sequential `SRC_OVER`, so a dense drag's
 * edge alpha matches a single dab's own falloff rather than compounding via `1 - Π(1 - αᵢ)` — see
 * [BrushStamps.stampCoverage]. This is the same algorithm the Android renderer
 * (`feature.editor.StampBrushRenderer.paintRoundDabsMaxCombined`) uses; extracted here, with zero
 * `android.graphics` dependency, so the desktop (Compose Multiplatform) renderer produces pixel-
 * identical strokes from the same dab list instead of re-deriving the falloff curve.
 *
 * Returns `null` when [dabs] is empty or every dab is fully transparent (nothing to composite).
 */
object RoundStampCompositor {

    fun compositeMaxCombined(
        dabs: List<Dab>,
        colorArgb: Int,
        secondaryColorArgb: Int,
        colorSource: BrushColorSource,
        flow: Float,
    ): CompositedTile? {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (d in dabs) {
            val r = max(d.radius, 0.5f)
            if (d.x - r < minX) minX = d.x - r
            if (d.x + r > maxX) maxX = d.x + r
            if (d.y - r < minY) minY = d.y - r
            if (d.y + r > maxY) maxY = d.y + r
        }
        if (minX > maxX || minY > maxY) return null

        val left = floor(minX).toInt()
        val top = floor(minY).toInt()
        val width = (ceil(maxX).toInt() - left).coerceAtLeast(1)
        val height = (ceil(maxY).toInt() - top).coerceAtLeast(1)
        return compositeMaxCombinedForRegion(
            dabs, DirtyRegion(left, top, left + width, top + height), colorArgb, secondaryColorArgb, colorSource, flow,
        )
    }

    /**
     * Same max-combine algorithm as [compositeMaxCombined], restricted to a caller-supplied
     * [region] instead of each dab list's own bounding box. This is what lets a stroke's dirty
     * area be split into [TileGrid] tiles and rasterized concurrently (one call per tile, off the
     * main/UI thread) on the multi-core CPUs Surface Pro devices ship with, since each call only
     * touches its own tile's pixel buffer — see the desktop app's tile-parallel renderer. Dabs
     * outside [region] are skipped cheaply (their own bbox is checked before the per-pixel loop),
     * so passing the full, unfiltered dab list per tile is fine.
     */
    fun compositeMaxCombinedForRegion(
        dabs: List<Dab>,
        region: DirtyRegion,
        colorArgb: Int,
        secondaryColorArgb: Int,
        colorSource: BrushColorSource,
        flow: Float,
    ): CompositedTile? {
        if (region.isEmpty) return null
        val baseFlow = flow.coerceIn(0f, 1f)
        val left = region.left
        val top = region.top
        val width = region.width
        val height = region.height
        val alphaBuf = FloatArray(width * height)
        val colorBuf = IntArray(width * height)

        for (d in dabs) {
            val dabColor = ArgbColor.resolveDabColor(colorArgb, secondaryColorArgb, colorSource, d)
            val rgb = dabColor and 0x00FFFFFF
            val radius = max(d.radius, 0.5f)
            val strength = (
                ArgbColor.alpha(dabColor) / 255f * d.alpha * baseFlow * d.flowMultiplier.coerceAtLeast(0f)
                ).coerceIn(0f, 1f)
            if (strength <= 0f) continue

            val dLeft = floor(d.x - radius).toInt().coerceAtLeast(left)
            val dTop = floor(d.y - radius).toInt().coerceAtLeast(top)
            val dRight = ceil(d.x + radius).toInt().coerceAtMost(left + width - 1)
            val dBottom = ceil(d.y + radius).toInt().coerceAtMost(top + height - 1)

            var py = dTop
            while (py <= dBottom) {
                val rowBase = (py - top) * width
                val dy = py + 0.5f - d.y
                var px = dLeft
                while (px <= dRight) {
                    val dx = px + 0.5f - d.x
                    val rNorm = hypot(dx, dy) / radius
                    if (rNorm < 1f) {
                        val localAlpha = BrushStamps.stampCoverage(rNorm, d.hardness) * strength
                        val idx = rowBase + (px - left)
                        if (localAlpha > alphaBuf[idx]) {
                            alphaBuf[idx] = localAlpha
                            colorBuf[idx] = rgb
                        }
                    }
                    px++
                }
                py++
            }
        }

        val out = IntArray(width * height)
        var any = false
        for (i in out.indices) {
            val a = alphaBuf[i]
            if (a > 0f) {
                any = true
                out[i] = (a.coerceIn(0f, 1f) * 255f).roundToInt().shl(24) or colorBuf[i]
            }
        }
        if (!any) return null
        return CompositedTile(left, top, width, height, out)
    }
}
