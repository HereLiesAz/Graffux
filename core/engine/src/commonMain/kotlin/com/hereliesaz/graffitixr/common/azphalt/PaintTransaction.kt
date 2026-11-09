package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The paint transaction (spec/painterly.md 3.4): per dab, **lift** paint from a surface into a brush
 * load column (or discard it), then **draw** the column's paint onto the surface. Kotlin reference and
 * test oracle for `core/wgpu-engine/src/shaders/transaction.wgsl`; the arithmetic order, including
 * the 64-lane reduction tree, mirrors the shader so the two agree.
 *
 * Surface fields (per texel): [Surface.conc] 16 concentrations as `q/255` (12 tubes + 4 basis,
 * [PigmentConcentration.BOARD_CHANNELS]), [Surface.amount] paint amount. [run] takes a snapshot of the
 * concentrations (`snapConc`, for **colour sampling only**) and starts a stroke-local remaining
 * liftable amount `avail = amount` (for **quantities**). Lifts consume `avail` and remove the matching
 * amount from the surface; draws add to the surface but **not** to `avail` or the snapshot, so a stroke
 * never lifts its own paint (spec 3.4, owner decision 5).
 */
object PaintTransaction {
    const val CH = PigmentConcentration.BOARD_CHANNELS
    private const val LANES_THREADS = 64

    class Surface(val width: Int, val height: Int) {
        val conc = FloatArray(width * height * CH)
        val amount = FloatArray(width * height)

        fun copy(): Surface = Surface(width, height).also {
            conc.copyInto(it.conc)
            amount.copyInto(it.amount)
        }
    }

    /** A brush load: [lanes] columns, each an [amount] and a normalised recipe of [CH] concentrations. */
    class Load(val lanes: Int, val capacity: Float) {
        val amount = FloatArray(lanes)
        val conc = FloatArray(lanes * CH)

        fun copy(): Load = Load(lanes, capacity).also {
            amount.copyInto(it.amount)
            conc.copyInto(it.conc)
        }
    }

    /** One dab's intent. [lane] < 0 names no column (only [dispose] lifts then make sense). */
    data class Dab(
        val x: Float,
        val y: Float,
        val radius: Float,
        val hardness: Float,
        val lane: Int,
        val draw: Float = 0f,
        val lift: Float = 0f,
        val mixWet: Float = 0f,
        val dispose: Boolean = false,
    )

    /** Reduce 64 per-thread partials by the shader's fixed tree (stride 32, 16, ... 1). */
    private fun reduce(partial: FloatArray): Float {
        var stride = LANES_THREADS / 2
        while (stride > 0) {
            for (i in 0 until stride) partial[i] = partial[i] + partial[i + stride]
            stride /= 2
        }
        return partial[0]
    }

    private fun coverage(rNorm: Float, hardness: Float): Float {
        if (rNorm <= 0f) return 1f
        if (rNorm >= 1f) return 0f
        val h = hardness.coerceIn(0f, 1f)
        if (rNorm <= h) return 1f
        val denom = 1f - h
        if (denom <= 1e-4f) return 1f
        return ((1f - rNorm) / denom).coerceIn(0f, 1f)
    }

    /** Applies [dabs] to [surface] and [load] in place. Returns the stroke's `avail` field. */
    fun run(surface: Surface, load: Load, dabs: List<Dab>): FloatArray {
        val w = surface.width
        val h = surface.height
        val snap = surface.conc.copyOf()
        val avail = surface.amount.copyOf()
        val partial = FloatArray(LANES_THREADS)
        for (d in dabs) {
            if (d.radius <= 0f) continue
            val minX = max(0, floor(d.x - d.radius).toInt())
            val maxX = min(w - 1, ceil(d.x + d.radius).toInt())
            val minY = max(0, floor(d.y - d.radius).toInt())
            val maxY = min(h - 1, ceil(d.y + d.radius).toInt())
            if (minX > maxX || minY > maxY) continue
            val bw = maxX - minX + 1
            val n = bw * (maxY - minY + 1)
            fun weightAt(k: Int): Float {
                val x = minX + k % bw
                val y = minY + k / bw
                val dx = x + 0.5f - d.x
                val dy = y + 0.5f - d.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist >= d.radius) return 0f
                return coverage((dist / d.radius).coerceIn(0f, 1f), d.hardness)
            }
            fun idxAt(k: Int) = (minY + k / bw) * w + (minX + k % bw)
            val lane = d.lane
            val hasLane = lane in 0 until load.lanes

            // ---- lift ----
            if (d.lift > 0f && (hasLane || d.dispose)) {
                // 1. what each texel could give (scratch, like the shader's storage buffer)
                val take = FloatArray(n)
                partial.fill(0f)
                for (t in 0 until LANES_THREADS) {
                    var k = t
                    while (k < n) {
                        val wgt = weightAt(k)
                        if (wgt > 0f) {
                            take[k] = min(d.lift * wgt, avail[idxAt(k)])
                            partial[t] = partial[t] + take[k]
                        }
                        k += LANES_THREADS
                    }
                }
                val wanted = reduce(partial)
                // 2. a column can only take what fits; a disposal has no limit
                val scale = if (d.dispose || !hasLane) 1f else {
                    val free = (load.capacity - load.amount[lane]).coerceAtLeast(0f)
                    if (wanted > free) free / wanted else 1f
                }
                // 3. consume the stroke-local source and remove the same from the committed surface
                for (k in 0 until n) {
                    if (take[k] <= 0f) continue
                    take[k] = take[k] * scale
                    val i = idxAt(k)
                    avail[i] -= take[k]
                    surface.amount[i] = max(surface.amount[i] - take[k], 0f)
                }
                // 4. the column's new recipe: amount-weighted mix of its old recipe and the SNAPSHOT
                //    recipes of what was lifted (colour sampling only)
                if (!d.dispose && hasLane) {
                    val before = load.amount[lane]
                    val sums = FloatArray(CH)
                    for (c in 0 until CH) {
                        partial.fill(0f)
                        for (t in 0 until LANES_THREADS) {
                            var k = t
                            while (k < n) {
                                if (take[k] > 0f) partial[t] = partial[t] + take[k] * snap[idxAt(k) * CH + c]
                                k += LANES_THREADS
                            }
                        }
                        sums[c] = reduce(partial)
                    }
                    partial.fill(0f)
                    for (t in 0 until LANES_THREADS) {
                        var k = t
                        while (k < n) {
                            partial[t] = partial[t] + take[k]
                            k += LANES_THREADS
                        }
                    }
                    val total = reduce(partial)
                    val after = before + total
                    if (after > 0f) {
                        for (c in 0 until CH) load.conc[lane * CH + c] =
                            (load.conc[lane * CH + c] * before + sums[c]) / after
                        load.amount[lane] = after
                    }
                }
            }

            // ---- draw ----
            if (d.draw > 0f && hasLane && load.amount[lane] > 0f) {
                partial.fill(0f)
                for (t in 0 until LANES_THREADS) {
                    var k = t
                    while (k < n) {
                        val wgt = weightAt(k)
                        if (wgt > 0f) partial[t] = partial[t] + d.draw * wgt
                        k += LANES_THREADS
                    }
                }
                val wanted = reduce(partial)
                val scale = if (wanted > load.amount[lane]) load.amount[lane] / wanted else 1f
                val raw = FloatArray(CH)
                for (k in 0 until n) {
                    val wgt = weightAt(k)
                    if (wgt <= 0f) continue
                    val i = idxAt(k)
                    val dep = d.draw * wgt * scale
                    val present = surface.amount[i]
                    val m = d.mixWet.coerceIn(0f, 1f) * min(1f, present)
                    val newAmount = present + dep
                    for (c in 0 until CH) {
                        val surf = surface.conc[i * CH + c]
                        val recipe = load.conc[lane * CH + c] * (1f - m) + surf * m
                        raw[c] = (surf * present + recipe * dep) / newAmount
                    }
                    val q = quantise(raw)
                    for (c in 0 until CH) surface.conc[i * CH + c] = q[c] / PigmentConcentration.SUM.toFloat()
                    surface.amount[i] = newAmount
                }
                partial.fill(0f)
                for (t in 0 until LANES_THREADS) {
                    var k = t
                    while (k < n) {
                        val wgt = weightAt(k)
                        if (wgt > 0f) partial[t] = partial[t] + d.draw * wgt * scale
                        k += LANES_THREADS
                    }
                }
                load.amount[lane] = max(load.amount[lane] - reduce(partial), 0f)
            }
        }
        return avail
    }

    /** Largest-remainder quantisation to [PigmentConcentration.SUM] over [CH] channels (shader-identical). */
    fun quantise(conc: FloatArray): IntArray = PigmentConcentration.quantize(conc)
}
