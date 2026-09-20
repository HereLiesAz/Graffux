package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow
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

    /**
     * Regional Phase-5 material presentation. Keeps the same packed-region allocation contract as
     * [shade] while adding wetness-driven roughness/specular response. Canonical color, height and
     * wetness are read-only.
     */
    fun shadeMaterial(
        rawRegion: IntArray,
        height: FloatArray,
        wetness: PersistentWetnessField?,
        canvasWidth: Int,
        canvasHeight: Int,
        left: Int,
        top: Int,
        regionWidth: Int,
        regionHeight: Int,
        lightAzimuthDeg: Float,
        lightElevationDeg: Float,
        reliefStrength: Float,
        medium: PaintMedium,
        mediumAt: ((x: Int, y: Int) -> PaintMedium)? = null,
    ): IntArray {
        require(rawRegion.size >= regionWidth * regionHeight)
        val out = rawRegion.copyOf()
        if (regionWidth <= 0 || regionHeight <= 0 || canvasWidth <= 0 || canvasHeight <= 0) return out
        val material = medium.sanitized()
        if (mediumAt == null && reliefStrength <= 0f && material.wetSpecularStrength <= 0f) return out

        val azimuth = lightAzimuthDeg * DEG_TO_RAD
        val elevation = lightElevationDeg * DEG_TO_RAD
        val lx = cos(azimuth) * cos(elevation)
        val ly = sin(azimuth) * cos(elevation)
        val lz = sin(elevation)
        val hx0 = lx
        val hy0 = ly
        val hz0 = lz + 1f
        val hInv = 1f / sqrt(hx0 * hx0 + hy0 * hy0 + hz0 * hz0)
        val hx = hx0 * hInv
        val hy = hy0 * hInv
        val hz = hz0 * hInv

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
                val nx0 = -dHdx
                val ny0 = -dHdy
                val invLen = 1f / sqrt(nx0 * nx0 + ny0 * ny0 + 1f)
                val nx = nx0 * invLen
                val ny = ny0 * invLen
                val nz = invLen
                val diffuse = nx * lx + ny * ly + nz * lz
                val multiplier = (
                    1f + reliefStrength.coerceAtLeast(0f) * (diffuse - lz)
                    ).coerceIn(0f, 3f)

                val localMaterial = mediumAt?.invoke(x, y)?.sanitized() ?: material
                val wet = wetness?.wetnessAt(x, y)?.coerceIn(0f, 1f) ?: 0f
                val roughness = (
                    localMaterial.baseRoughness * (1f - wet) + MIN_WET_ROUGHNESS * wet
                    ).coerceIn(MIN_WET_ROUGHNESS, 1f)
                val shininess = 4f + (1f - roughness) * 60f
                val nDotH = (nx * hx + ny * hy + nz * hz).coerceIn(0f, 1f)
                val specular = localMaterial.wetSpecularStrength * wet *
                    nDotH.toDouble().pow(shininess.toDouble()).toFloat()
                val index = localY * regionWidth + localX
                out[index] = shadeRgb(rawRegion[index], multiplier, specular)
            }
        }
        return out
    }

    private fun at(height: FloatArray, width: Int, canvasHeight: Int, x: Int, y: Int): Float {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, canvasHeight - 1)
        return height[cy * width + cx]
    }

    private fun shadeRgb(argb: Int, factor: Float, specular: Float): Int {
        val a = argb ushr 24 and 0xFF
        val add = 255f * specular.coerceIn(0f, 1f)
        val r = ((argb shr 16 and 0xFF) * factor + add).toInt().coerceIn(0, 255)
        val g = ((argb shr 8 and 0xFF) * factor + add).toInt().coerceIn(0, 255)
        val b = ((argb and 0xFF) * factor + add).toInt().coerceIn(0, 255)
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
    private const val MIN_WET_ROUGHNESS = 0.08f
}
