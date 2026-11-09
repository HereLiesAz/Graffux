package com.hereliesaz.graffitixr.common.azphalt

import com.hereliesaz.graffitixr.common.azphalt.PaintTransaction.CH
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The physical palette (spec/painterly.md 6.3): a board of paint blobs you squeeze, dip a brush into,
 * mix with a brush or knife, and clean the brush on. A board texel stores a **recipe** (16 concentrations:
 * the 12 board tubes then the 4 basis channels, [PigmentConcentration.BOARD_CHANNELS]) and a paint
 * amount, so two recipes with the same colour stay distinguishable. Paint is finite: a blob is a region
 * of the board's paint field and every lift consumes it ([PaintTransaction]).
 *
 * This class is the Kotlin reference runtime for the board. The editor drives it on the CPU at a small
 * board size today; running the same transaction on the resident GPU surface is the P5 wiring still to do.
 */
class PaletteBoard(
    val size: Int,
    /** Board tube slot -> pigment (at most [PigmentConcentration.BOARD_TUBES]). */
    val tubes: List<Pigment>,
) {
    init {
        require(size in 8..MAX_SIZE) { "board size $size" }
        require(tubes.size <= PigmentConcentration.BOARD_TUBES) { "at most ${PigmentConcentration.BOARD_TUBES} tubes" }
    }

    val surface = PaintTransaction.Surface(size, size)

    /** Two brushes, foreground and background (the editor's two colours); [hand] is the one in use. */
    val brushes = List(2) { PaintTransaction.Load(lanes = 1, capacity = BRUSH_CAPACITY) }
    var hand: Int = 0
        set(value) { field = value.coerceIn(0, 1) }

    /** The brush in hand: one load column. */
    val brush: PaintTransaction.Load get() = brushes[hand]

    /** Pigments by channel: the board tubes, padded to 12, then the 4 basis pigments. */
    private val channelPigments: List<Pigment> = run {
        val set = PlaceholderPigments.set
        val filler = tubes.firstOrNull() ?: set.pigments.first()
        val padded = tubes + List(PigmentConcentration.BOARD_TUBES - tubes.size) { filler }
        padded + PigmentLookup.BASIS_IDS.map { id -> set.pigments.first { it.id == id } }
    }

    /** Squeezes [amount] of tube [slot] onto the board in a blob of [radius] texels at ([x], [y]). */
    fun squeeze(slot: Int, x: Float, y: Float, radius: Float, amount: Float) {
        require(slot in tubes.indices)
        val minX = max(0, floor(x - radius).toInt())
        val maxX = min(size - 1, ceil(x + radius).toInt())
        val minY = max(0, floor(y - radius).toInt())
        val maxY = min(size - 1, ceil(y + radius).toInt())
        for (py in minY..maxY) for (px in minX..maxX) {
            val dx = px + 0.5f - x
            val dy = py + 0.5f - y
            val d = sqrt(dx * dx + dy * dy)
            if (d >= radius) continue
            val dep = amount * (1f - d / radius)
            val i = py * size + px
            val present = surface.amount[i]
            val raw = FloatArray(CH) { c -> surface.conc[i * CH + c] * present + if (c == slot) dep else 0f }
            val q = PigmentConcentration.quantize(raw)
            for (c in 0 until CH) surface.conc[i * CH + c] = q[c] / PigmentConcentration.SUM.toFloat()
            surface.amount[i] = present + dep
        }
    }

    /** Dips the brush into the board at a point: lifts paint into the brush. */
    fun dip(x: Float, y: Float, radius: Float = DEFAULT_TOUCH_RADIUS, lift: Float = 0.2f) {
        PaintTransaction.run(surface, brush, listOf(PaintTransaction.Dab(x, y, radius, 0.5f, 0, lift = lift)))
    }

    /** Dabs the brush onto the board, mixing with what is there (`mixWet`) by [mix]. */
    fun dab(x: Float, y: Float, radius: Float = DEFAULT_TOUCH_RADIUS, draw: Float = 0.2f, mix: Float = 0.7f) {
        PaintTransaction.run(surface, brush, listOf(PaintTransaction.Dab(x, y, radius, 0.5f, 0, draw = draw,
            mixWet = mix)))
    }

    /**
     * Drags the brush or knife along [path] (x, y pairs): at each point it lifts, then lays what it holds,
     * so paint is pulled along and blended. One pass is a stroke of the transaction; more passes mix more.
     */
    fun drag(path: FloatArray, radius: Float = DEFAULT_TOUCH_RADIUS, lift: Float = DRAG_LIFT_SHARE,
        draw: Float = DRAG_DRAW_SHARE, mix: Float = 0.8f) {
        // [lift] and [draw] are shares of the brush's capacity per dab, spread over the dab's footprint, so an
        // ordinary gesture leaves usable paint on the brush at any radius (a per-texel amount over a footprint of
        // tens of texels asked for several times the whole capacity and emptied the brush on the first dab).
        val perTexel = brush.capacity / footprintWeight(radius)
        val liftPerTexel = lift * perTexel
        val drawPerTexel = draw * perTexel
        val dabs = ArrayList<PaintTransaction.Dab>()
        fun at(x: Float, y: Float) {
            dabs += PaintTransaction.Dab(x, y, radius, 0.5f, 0, lift = liftPerTexel)
            dabs += PaintTransaction.Dab(x, y, radius, 0.5f, 0, draw = drawPerTexel, mixWet = mix)
        }
        // Resample every segment at a spacing of at most half a radius, so a fast swipe (a long step between
        // two touch events) paints a continuous line instead of two separate dabs.
        val spacing = (radius * MAX_DRAG_SPACING_RADII).coerceAtLeast(0.5f)
        var i = 0
        if (path.size >= 2) at(path[0], path[1])
        while (i + 3 < path.size && dabs.size < MAX_DRAG_DABS) {
            val x0 = path[i]; val y0 = path[i + 1]; val x1 = path[i + 2]; val y1 = path[i + 3]
            val length = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
            val steps = ceil(length / spacing).toInt().coerceAtLeast(1)
            for (k in 1..steps) {
                val t = k / steps.toFloat()
                at(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
            }
            i += 2
        }
        PaintTransaction.run(surface, brush, dabs)
    }

    /** The sum of a dab's per-texel weights (hardness 0.5), so amounts can be set as a share of capacity. */
    private fun footprintWeight(radius: Float): Float {
        val r = ceil(radius).toInt()
        var sum = 0f
        for (y in -r..r) for (x in -r..r) {
            val d = sqrt((x + 0.5f) * (x + 0.5f) + (y + 0.5f) * (y + 0.5f)) / radius
            if (d < 1f) sum += if (d <= 0.5f) 1f else (1f - d) / 0.5f
        }
        return sum.coerceAtLeast(1f)
    }

    /** Exchanges the paint on the foreground and background brushes (with the editor's colour swap). */
    fun swapBrushes() {
        val a = brushes[0]
        val b = brushes[1]
        val amount = a.amount[0]
        val conc = a.conc.copyOf()
        a.amount[0] = b.amount[0]
        b.conc.copyInto(a.conc)
        b.amount[0] = amount
        conc.copyInto(b.conc)
    }

    /** What cleaning the brush does. */
    enum class Clean { WIPE, RINSE }

    /** Wipe on a rag removes part of the load; rinse removes all of it. */
    fun clean(how: Clean) {
        val keep = if (how == Clean.WIPE) WIPE_KEEP else 0f
        brush.amount[0] *= keep
        if (brush.amount[0] <= 0f) for (c in 0 until CH) brush.conc[c] = 0f
    }

    /** The recipe the brush holds: tube concentrations (named) and the canvas-origin remainder. */
    class Recipe(val tubes: List<Pair<Pigment, Float>>, val basis: Float, val amount: Float)

    fun brushRecipe(): Recipe = recipeOf(brush.conc, 0, brush.amount[0])

    /** The recipe at a board texel. */
    fun recipeAt(x: Int, y: Int): Recipe {
        val i = y * size + x
        return recipeOf(surface.conc, i * CH, surface.amount[i])
    }

    private fun recipeOf(conc: FloatArray, base: Int, amount: Float): Recipe {
        var total = 0f
        for (c in 0 until CH) total += conc[base + c]
        if (!(total > 0f)) return Recipe(emptyList(), 0f, amount)
        val named = ArrayList<Pair<Pigment, Float>>()
        for (c in tubes.indices) if (conc[base + c] > 0.004f) named += tubes[c] to conc[base + c] / total
        var basis = 0f
        for (c in PigmentConcentration.BOARD_TUBES until CH) basis += conc[base + c] / total
        return Recipe(named.sortedByDescending { it.second }, basis, amount)
    }

    /** The colour a recipe makes: the K-M mix of its 16 channels, as sRGB-encoded ARGB. */
    fun colourOf(conc: FloatArray, base: Int): Int {
        val c = FloatArray(CH) { conc[base + it] }
        val rgb = PigmentMixer.mixSrgb8(c, channelPigments)
        return (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
    }

    fun brushColour(): Int = colourOf(brush.conc, 0)

    fun colourAt(x: Int, y: Int): Int = colourOf(surface.conc, (y * size + x) * CH)

    /** Every texel's colour (ARGB; transparent where there is no paint), row-major. */
    fun render(out: IntArray = IntArray(size * size)): IntArray {
        for (i in 0 until size * size) out[i] = if (surface.amount[i] > 0.01f) colourOf(surface.conc, i * CH) else 0
        return out
    }

    companion object {
        const val MAX_SIZE = 1024

        /** A drag lays a dab at least this often, in brush radii. */
        const val MAX_DRAG_SPACING_RADII = 0.5f
        private const val MAX_DRAG_DABS = 4000
        const val DEFAULT_SIZE = 512
        const val BRUSH_CAPACITY = 1.0f

        /** Share of the brush's capacity one drag dab lifts / lays (a dab's footprint shares it out). */
        const val DRAG_LIFT_SHARE = 0.12f
        const val DRAG_DRAW_SHARE = 0.06f
        const val DEFAULT_TOUCH_RADIUS = 6f
        private const val WIPE_KEEP = 0.4f
    }
}

/**
 * Finds the tube recipe closest to a target colour (the "Digital" eyedropper and hex entry, spec 6.5):
 * a deterministic pattern search on the simplex over at most 12 tubes, so an arbitrary colour becomes a
 * recipe the physical palette can actually make, with the error stated rather than hidden.
 */
object RecipeSolver {
    class Result(val concentration: FloatArray, val achievedRgb8: IntArray, val error: Float)

    fun solve(targetRgb8: IntArray, tubes: List<Pigment>): Result {
        val n = tubes.size
        require(n in 1..PigmentConcentration.BOARD_TUBES)
        val target = FloatArray(3) { c ->
            val e = targetRgb8[c].coerceIn(0, 255) / 255f
            if (e <= 0.04045f) e / 12.92f else Math.pow(((e + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        var best = FloatArray(n) { 1f / n }
        var bestErr = Float.MAX_VALUE
        // a few deterministic starts: uniform, then each tube alone, so a pure tube is found exactly
        val starts = ArrayList<FloatArray>().apply {
            add(FloatArray(n) { 1f / n })
            for (i in 0 until n) add(FloatArray(n).also { it[i] = 1f })
        }
        for (start in starts) {
            val (c, e) = descend(start.copyOf(), target, tubes)
            if (e < bestErr) { best = c; bestErr = e }
        }
        val rgb = PigmentMixer.mixSrgb8(best, tubes)
        return Result(best, rgb, sqrt(bestErr))
    }

    private fun error(c: FloatArray, target: FloatArray, tubes: List<Pigment>): Float {
        val m = PigmentMixer.mixLinearRgb(c, tubes)
        var e = 0f
        for (k in 0 until 3) e += (m[k] - target[k]) * (m[k] - target[k])
        return e
    }

    private fun descend(c: FloatArray, target: FloatArray, tubes: List<Pigment>): Pair<FloatArray, Float> {
        val n = c.size
        var best = error(c, target, tubes)
        var step = 0.25f
        repeat(120) {
            var improved = false
            var bi = -1
            var bj = -1
            var bestE = best
            for (a in 0 until n) for (b in 0 until n) {
                if (a == b || c[b] < step) continue
                c[a] += step; c[b] -= step
                val e = error(c, target, tubes)
                c[a] -= step; c[b] += step
                if (e < bestE) { bestE = e; bi = a; bj = b; improved = true }
            }
            if (improved) { c[bi] += step; c[bj] -= step; best = bestE } else step *= 0.5f
            if (abs(step) < 1e-3f) return c to best
        }
        return c to best
    }
}
