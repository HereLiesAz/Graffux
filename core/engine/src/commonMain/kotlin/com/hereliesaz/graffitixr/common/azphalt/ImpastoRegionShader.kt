package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Region-packed impasto shader for live preview.
 *
 * [rawRegion] and the returned array contain only the requested rectangle, while [height] remains
 * the authoritative full-canvas height map. This keeps live shading allocation proportional to the
 * dirty brush footprint instead of width*height on every rendered batch.
 */
object ImpastoRegionShader {
    fun shade(
        rawRegion: IntArray,
        height: FloatArray,
        canvasWidth: Int,
        canvasHeight: Int,
        left: Int,
        top: Int,
        regionWidth: Int,
        regionHeight: Int,
        lightAzimuthDeg: Float,
        lightElevationDeg: Float,
        strength: Float,
    ): IntArray {
        require(rawRegion.size >= regionWidth * regionHeight)
        val out = rawRegion.copyOf()
        if (regionWidth <= 0 || regionHeight <= 0 || canvasWidth <= 0 || canvasHeight <= 0 || strength <= 0f) {
            return out
        }
        val azimuth = lightAzimuthDeg * DEG_TO_RAD
        val elevation = lightElevationDeg * DEG_TO_RAD
        val lx = cos(azimuth) * cos(elevation)
        val ly = sin(azimuth) * cos(elevation)
        val lz = sin(elevation)

        for (localY in 0 until regionHeight) {
            val y = top + localY
            if (y !in 0 until canvasHeight) continue
            for (localX in 0 until regionWidth) {
                val x = left + localX
                if (x !in 0 until canvasWidth) continue
                val dHdx = (at(height, canvasWidth, canvasHeight, x + 1, y) -
                    at(height, canvasWidth, canvasHeight, x - 1, y)) / 2f
                val dHdy = (at(height, canvasWidth, canvasHeight, x, y + 1) -
                    at(height, canvasWidth, canvasHeight, x, y - 1)) / 2f
                if (dHdx == 0f && dHdy == 0f) continue
                val nx = -dHdx
                val ny = -dHdy
                val invLen = 1f / sqrt(nx * nx + ny * ny + 1f)
                val diffuse = nx * invLen * lx + ny * invLen * ly + invLen * lz
                val multiplier = (1f + strength * (diffuse - lz)).coerceIn(0f, 3f)
                if (multiplier != 1f) {
                    val index = localY * regionWidth + localX
                    out[index] = scaleRgb(rawRegion[index], multiplier)
                }
            }
        }
        return out
    }

    private fun at(height: FloatArray, width: Int, canvasHeight: Int, x: Int, y: Int): Float {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, canvasHeight - 1)
        return height[cy * width + cx]
    }

    private fun scaleRgb(argb: Int, factor: Float): Int {
        val a = argb ushr 24 and 0xFF
        val r = ((argb shr 16 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = ((argb shr 8 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((argb and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private const val DEG_TO_RAD = 0.017453292f
}
