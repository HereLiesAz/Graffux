package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.abs

/**
 * RGB to basis-latent lookup (spec/painterly.md 6.2 item 4): for a canvas pixel that has no stored
 * concentrations, the concentrations of four fixed basis pigments whose K-M mix is closest to the
 * pixel's colour, plus the signed residual that makes an unmixed pixel round-trip exactly.
 * Generated deterministically by a fixed pattern search (no randomness), so the table is reproducible.
 * Reference implementation; the GPU samples the same table as a 3-D texture.
 */
class PigmentLookup private constructor(
    val basis: List<Pigment>,
    val grid: Int,
    /** Per grid point, [BASIS] bytes summing to 255. */
    private val concentration: IntArray,
    /** Per grid point, 3 signed bytes (sRGB-encoded residual / [RESIDUAL_RANGE] * 127). */
    private val residual: IntArray,
) {
    class Latent(val concentration: FloatArray, val residual: FloatArray)

    private fun index(r: Int, g: Int, b: Int) = (r * grid + g) * grid + b

    /** The latent at exact grid point (r, g, b), each 0 until [grid]. */
    fun atGrid(r: Int, g: Int, b: Int): Latent {
        val i = index(r, g, b)
        return Latent(
            FloatArray(BASIS) { concentration[i * BASIS + it] / PigmentConcentration.SUM.toFloat() },
            FloatArray(3) { residual[i * 3 + it] / 127f * RESIDUAL_RANGE },
        )
    }

    /** Trilinear latent for an sRGB-encoded 8-bit colour. */
    fun latentOf(r8: Int, g8: Int, b8: Int): Latent {
        val f = (grid - 1) / 255f
        val coords = floatArrayOf(r8 * f, g8 * f, b8 * f)
        val lo = IntArray(3) { coords[it].toInt().coerceIn(0, grid - 2) }
        val t = FloatArray(3) { coords[it] - lo[it] }
        val conc = FloatArray(BASIS)
        val res = FloatArray(3)
        for (corner in 0 until 8) {
            val dr = corner and 1
            val dg = (corner shr 1) and 1
            val db = (corner shr 2) and 1
            val w = (if (dr == 1) t[0] else 1f - t[0]) * (if (dg == 1) t[1] else 1f - t[1]) *
                (if (db == 1) t[2] else 1f - t[2])
            val l = atGrid(lo[0] + dr, lo[1] + dg, lo[2] + db)
            for (k in 0 until BASIS) conc[k] += w * l.concentration[k]
            for (k in 0 until 3) res[k] += w * l.residual[k]
        }
        return Latent(conc, res)
    }

    /** The colour a latent stands for: the K-M mix plus the residual, as sRGB-encoded 8-bit. */
    fun toRgb8(latent: Latent): IntArray {
        val encoded = PigmentMixer.encodeSrgb(PigmentMixer.mixLinearRgb(latent.concentration, basis))
        return IntArray(3) { c -> ((encoded[c] + latent.residual[c]).coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255) }
    }

    companion object {
        const val BASIS = 4
        const val RESIDUAL_RANGE = 1f
        val BASIS_IDS = listOf("titanium-white", "phthalo-blue", "quinacridone-magenta", "cadmium-yellow")

        fun build(set: PigmentSet = PlaceholderPigments.set, grid: Int = 16): PigmentLookup {
            val basis = BASIS_IDS.map { id -> set.pigments.first { it.id == id } }
            val conc = IntArray(grid * grid * grid * BASIS)
            val res = IntArray(grid * grid * grid * 3)
            var i = 0
            for (r in 0 until grid) for (g in 0 until grid) for (b in 0 until grid) {
                val target = floatArrayOf(decode(r, grid), decode(g, grid), decode(b, grid))
                val c = solve(target, basis)
                val q = PigmentConcentration.quantize(c)
                val mixed = PigmentMixer.encodeSrgb(PigmentMixer.mixLinearRgb(PigmentConcentration.dequantize(q), basis))
                val wanted = PigmentMixer.encodeSrgb(target)
                for (k in 0 until BASIS) conc[i * BASIS + k] = q[k]
                for (k in 0 until 3) {
                    val d = (wanted[k] - mixed[k]).coerceIn(-RESIDUAL_RANGE, RESIDUAL_RANGE)
                    res[i * 3 + k] = Math.round(d / RESIDUAL_RANGE * 127f)
                }
                i++
            }
            return PigmentLookup(basis, grid, conc, res)
        }

        /** Grid index to linear 0..1 (the grid is uniform in sRGB-encoded space). */
        private fun decode(i: Int, grid: Int): Float {
            val e = i / (grid - 1).toFloat()
            return if (e <= 0.04045f) e / 12.92f else Math.pow(((e + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }

        /** Fixed pattern search on the simplex: move a step of mass between two basis pigments. */
        private fun solve(target: FloatArray, basis: List<Pigment>): FloatArray {
            val c = FloatArray(BASIS) { 1f / BASIS }
            fun error(x: FloatArray): Float {
                val m = PigmentMixer.mixLinearRgb(x, basis)
                var e = 0f
                for (k in 0 until 3) e += (m[k] - target[k]) * (m[k] - target[k])
                return e
            }
            var best = error(c)
            var step = 0.25f
            repeat(80) {
                var improved = false
                var bi = -1
                var bj = -1
                var bestE = best
                for (a in 0 until BASIS) for (b in 0 until BASIS) {
                    if (a == b || c[b] < step) continue
                    c[a] += step; c[b] -= step
                    val e = error(c)
                    c[a] -= step; c[b] += step
                    if (e < bestE) { bestE = e; bi = a; bj = b; improved = true }
                }
                if (improved) { c[bi] += step; c[bj] -= step; best = bestE } else step *= 0.5f
                if (abs(step) < 1e-4f) return c
            }
            return c
        }
    }
}
