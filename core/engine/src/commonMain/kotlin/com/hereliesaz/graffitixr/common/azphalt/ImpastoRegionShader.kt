package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.pow

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
        wetness: PersistentWetnessField? = null,
        wetGlossStrength: Float = 0f,
        baseRoughness: Float = 0.72f,
    ): IntArray {
        require(rawRegion.size >= regionWidth * regionHeight)
        val out = rawRegion.copyOf()
        if (regionWidth <= 0 || regionHeight <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
            return out
        }
        val reliefStrength = strength.coerceAtLeast(0f)
        val glossStrength = wetGlossStrength.coerceIn(0f, 1f)
        if (reliefStrength <= 0f && (wetness == null || glossStrength <= 0f)) return out
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
                val nx = -dHdx
                val ny = -dHdy
                val invLen = 1f / sqrt(nx * nx + ny * ny + 1f)
                val nz = invLen
                val nnx = nx * invLen
                val nny = ny * invLen
                val diffuse = nnx * lx + nny * ly + nz * lz
                val multiplier = (1f + reliefStrength * (diffuse - lz)).coerceIn(0f, 3f)
                val index = localY * regionWidth + localX
                var shaded = if (multiplier != 1f) scaleRgb(rawRegion[index], multiplier) else rawRegion[index]

                // Phase 5 optics: wetness changes only this derived display sample, never the
                // caller-owned pigment colour. A flat wet surface can therefore become glossier
                // while the stored/raw ARGB remains byte-identical.
                val localWet = wetness?.wetnessAt(x, y)?.coerceIn(0f, 1f) ?: 0f
                if (localWet > 0f && glossStrength > 0f) {
                    val hx = lx
                    val hy = ly
                    val hz = lz + 1f
                    val hInv = 1f / sqrt(hx * hx + hy * hy + hz * hz)
                    val nDotH = (nnx * hx * hInv + nny * hy * hInv + nz * hz * hInv)
                        .coerceIn(0f, 1f)
                    val roughness = (
                        baseRoughness.coerceIn(0.05f, 1f) - 0.55f * localWet * glossStrength
                        ).coerceIn(0.05f, 1f)
                    val exponent = 2f + (1f - roughness) * 30f
                    val specular = nDotH.toDouble().pow(exponent.toDouble()).toFloat() *
                        localWet * glossStrength
                    if (specular > 0f) shaded = addWhiteSpecular(shaded, specular)
                }
                out[index] = shaded
            }
        }
        return out
    }

    private fun at(height: FloatArray, width: Int, canvasHeight: Int, x: Int, y: Int): Float {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, canvasHeight - 1)
        return height[cy * width + cx]
    }

    private fun addWhiteSpecular(argb: Int, amount: Float): Int {
        val a = argb ushr 24 and 0xFF
        val t = amount.coerceIn(0f, 1f)
        val r0 = argb shr 16 and 0xFF
        val g0 = argb shr 8 and 0xFF
        val b0 = argb and 0xFF
        val r = (r0 + (255 - r0) * t).toInt().coerceIn(0, 255)
        val g = (g0 + (255 - g0) * t).toInt().coerceIn(0, 255)
        val b = (b0 + (255 - b0) * t).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
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
