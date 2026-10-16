package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The product-facing canvas surfaces (Phase 3 product activation). A project picks one; every stamp
 * brush with a non-zero [AzphaltBrush.substrateResponse] then deposits against its tooth.
 *
 * [id] is the stable persistence key written into the project file and must never change once
 * shipped; [displayName] is UI text only. Tiles are generated procedurally from fixed seeds, so the
 * same preset yields byte-identical tooth on every device, build and replay — nothing about a
 * stroke's appearance depends on a bundled asset. [SMOOTH] carries no tile and is the exact historical
 * (pre-substrate) path.
 */
enum class SubstratePreset(
    val id: String,
    val displayName: String,
    val profile: SubstrateProfile,
) {
    /** No tooth: every stroke renders exactly as it did before substrates existed. */
    SMOOTH("smooth", "Smooth", SubstrateProfile.SMOOTH),

    /** Medium random tooth: smoothed value noise, like a cold-press watercolour sheet. */
    COLD_PRESS(
        "cold_press", "Cold-press paper",
        SubstrateProfile(baseHeight = 0.05f, heightScale = 0.75f, textureScale = 1f),
    ),

    /** Woven directional tooth: alternating over/under warp and weft threads. */
    CANVAS_WEAVE(
        "canvas_weave", "Canvas weave",
        SubstrateProfile(baseHeight = 0.05f, heightScale = 0.8f, textureScale = 1f),
    ),

    /** Coarse wall tooth: large pitted aggregate over fine grit, for brick and concrete. */
    BRICK_CONCRETE(
        "brick_concrete", "Brick / concrete",
        SubstrateProfile(baseHeight = 0.1f, heightScale = 0.9f, textureScale = 2f),
    ),
    ;

    /** True for [SMOOTH]: no tile, no substrate gating. */
    val isSmooth: Boolean get() = this == SMOOTH

    /**
     * This preset's repeating [TILE_SIZE]² R8 height tile, or null for [SMOOTH]. Generated once per
     * preset and cached; [SubstrateField] copies its input, so callers can't disturb the cache.
     */
    fun field(): SubstrateField? = cachedField

    /** A copy of this preset's [TILE_SIZE]² R8 height tile for GPU upload; null for [SMOOTH]. */
    fun heightR8(): ByteArray? = cachedBytes?.copyOf()

    // lazy's default mode is synchronized, so concurrent first strokes share one generation.
    private val cachedBytes: ByteArray? by lazy { if (isSmooth) null else generateHeightR8(this) }
    private val cachedField: SubstrateField? by lazy { cachedBytes?.let { SubstrateField(TILE_SIZE, TILE_SIZE, it) } }

    companion object {
        /** Side length of every generated tile. Power of two so the tile wraps without a seam. */
        const val TILE_SIZE = 128

        /** The project default, and what a missing field in an older project file means. */
        val DEFAULT = SMOOTH

        /** The preset persisted as [id]; anything unknown (or null) is [SMOOTH]. */
        fun fromId(id: String?): SubstratePreset = entries.firstOrNull { it.id == id } ?: SMOOTH

        /** The raw height bytes for [preset], freshly generated (the uncached form of [field]). */
        fun generateHeightR8(preset: SubstratePreset): ByteArray = when (preset) {
            SMOOTH -> ByteArray(TILE_SIZE * TILE_SIZE)
            COLD_PRESS -> coldPress()
            CANVAS_WEAVE -> canvasWeave()
            BRICK_CONCRETE -> brickConcrete()
        }

        private fun coldPress(): ByteArray {
            val n = TILE_SIZE
            val coarse = ValueNoise(seed = 0x5EED_C01DL, cells = 16)
            val fine = ValueNoise(seed = 0x5EED_C02DL, cells = 32)
            return tile { x, y -> mix(coarse.at(x, y, n), fine.at(x, y, n), COLD_PRESS_COARSE_WEIGHT) }
        }

        private fun canvasWeave(): ByteArray {
            val n = TILE_SIZE
            // WEAVE_THREADS threads per tile each way; each cell is one thread crossing, raised where
            // the thread on top runs along it (checkerboard over/under), with a little fibre noise.
            val cell = n / WEAVE_THREADS
            val fibre = ValueNoise(seed = 0x5EED_CA7AL, cells = 64)
            return tile { x, y ->
                val u = (x % cell + HALF) / cell
                val v = (y % cell + HALF) / cell
                val warpOnTop = (x / cell + y / cell) % 2 == 0
                // Thread profile across its width: a rounded ridge, low at the gaps between threads.
                val across = sin((if (warpOnTop) u else v) * PI.toFloat())
                val along = mix(1f, sin((if (warpOnTop) v else u) * PI.toFloat()), WEAVE_ALONG_FLOOR)
                mix(across * along, fibre.at(x, y, n), WEAVE_THREAD_WEIGHT)
            }
        }

        private fun brickConcrete(): ByteArray {
            val n = TILE_SIZE
            val aggregate = ValueNoise(seed = 0x5EED_B41CL, cells = 8)
            val grit = ValueNoise(seed = 0x5EED_B42CL, cells = 64)
            val pits = Lcg(seed = 0x5EED_B43CL)
            val relief = FloatArray(n * n) { i ->
                mix(aggregate.at(i % n, i / n, n), grit.at(i % n, i / n, n), BRICK_AGGREGATE_WEIGHT)
            }
            // Pits: deep round dents scattered over the relief (paint skips them under light contact).
            repeat(PIT_COUNT) {
                val px = pits.nextInt(n)
                val py = pits.nextInt(n)
                val r = PIT_MIN_RADIUS + pits.nextInt(PIT_RADIUS_RANGE)
                for (dy in -r..r) for (dx in -r..r) {
                    val d2 = dx * dx + dy * dy
                    if (d2 > r * r) continue
                    val i = wrap(py + dy, n) * n + wrap(px + dx, n)
                    relief[i] *= mix(1f, PIT_FLOOR, d2 / (r * r).toFloat())
                }
            }
            return ByteArray(n * n) { i -> toTooth(relief[i]) }
        }

        /** A tile from a physical surface relief function (1 = crest), stored as tooth height. */
        private inline fun tile(surface: (x: Int, y: Int) -> Float): ByteArray =
            ByteArray(TILE_SIZE * TILE_SIZE) { i -> toTooth(surface(i % TILE_SIZE, i / TILE_SIZE)) }

        /**
         * Surface relief (1 = crest) to the stored tooth height: [SubstrateField] heights are
         * penetration barriers (0 = easiest to reach) that existing paint fills, i.e. valley depth.
         * Crests catch a light, dry stroke and valleys need pressure — the dry-brush look.
         */
        private fun toTooth(surface: Float): Byte =
            ((1f - surface.coerceIn(0f, 1f)) * BYTE_MAX).roundToInt().toByte()

        /** [a] weighted by [weightA], the rest [b]. */
        private fun mix(a: Float, b: Float, weightA: Float): Float = a * weightA + b * (1f - weightA)

        private fun wrap(v: Int, n: Int): Int = ((v % n) + n) % n

        private const val BYTE_MAX = 255f
        private const val HALF = 0.5f
        private const val COLD_PRESS_COARSE_WEIGHT = 0.65f
        private const val WEAVE_THREADS = 16
        private const val WEAVE_ALONG_FLOOR = 0.75f
        private const val WEAVE_THREAD_WEIGHT = 0.85f
        private const val BRICK_AGGREGATE_WEIGHT = 0.6f
        private const val PIT_COUNT = 90
        private const val PIT_MIN_RADIUS = 2
        private const val PIT_RADIUS_RANGE = 5
        private const val PIT_FLOOR = 0.15f
    }
}

private const val LCG_MULTIPLIER = 6364136223846793005L
private const val LCG_INCREMENT = 1442695040888963407L
private const val LCG_SHIFT = 33
private const val FLOAT_BITS_MASK = 0xFFFFFFL
private const val FLOAT_BITS_RANGE = 16_777_216f

/** Deterministic linear congruential generator (MMIX constants): stable across platforms/releases. */
private class Lcg(seed: Long) {
    private var state = seed

    fun next(): Long {
        state = state * LCG_MULTIPLIER + LCG_INCREMENT
        return state ushr LCG_SHIFT
    }

    fun nextInt(bound: Int): Int = (next() % bound).toInt()

    fun nextFloat(): Float = (next() and FLOAT_BITS_MASK).toFloat() / FLOAT_BITS_RANGE
}

/** Periodic, smoothstep-interpolated value noise on a [cells]² lattice that wraps with the tile. */
private class ValueNoise(seed: Long, private val cells: Int) {
    private val lattice = Lcg(seed).let { rng -> FloatArray(cells * cells) { rng.nextFloat() } }

    fun at(x: Int, y: Int, tileSize: Int): Float {
        val fx = (x + CELL_CENTRE) * cells / tileSize
        val fy = (y + CELL_CENTRE) * cells / tileSize
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = smoothstep(fx - x0)
        val ty = smoothstep(fy - y0)
        val top = lerp(node(x0, y0), node(x0 + 1, y0), tx)
        val bottom = lerp(node(x0, y0 + 1), node(x0 + 1, y0 + 1), tx)
        return lerp(top, bottom, ty).coerceIn(0f, 1f)
    }

    private fun node(ix: Int, iy: Int): Float = lattice[wrap(iy) * cells + wrap(ix)]

    private fun wrap(i: Int): Int = (i % cells + cells) % cells

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    @Suppress("MagicNumber") // The smoothstep polynomial, 3t² - 2t³.
    private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

    private companion object {
        const val CELL_CENTRE = 0.5f
    }
}
