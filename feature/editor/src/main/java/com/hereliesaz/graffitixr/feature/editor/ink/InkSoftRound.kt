package com.hereliesaz.graffitixr.feature.editor.ink

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The soft-edged round on the Jetpack Ink path, as pure maths (no Ink, no Android) so it is
 * JVM-testable.
 *
 * **What it copies.** The legacy round brush softens its edge with
 * `BlurMaskFilter(brushSize * feathering * 0.5)` over one continuous path (`ImageProcessor`): a blur of
 * radius `r = size/2 · feathering` centred on the path's edge. So the paint reaches `r` past the
 * nominal edge, fades from full to nothing across `2r`, and a single stroke never builds up.
 *
 * **How Ink gets there.** Ink 1.0 has no tip-softness control. Its only per-pixel falloff is a
 * texture, and the only texture mapping that follows the tip is STAMPING, which needs a particle
 * brush: a disc stamped every [PARTICLE_GAP] brush sizes, each carrying the texture. Particles
 * accumulate (`1 - Π(1 - a)`), and Ink's other overlap mode (DISCARD) keeps the *first* fragment,
 * which along a stroke is a dab's faint leading edge — far worse. So the texture is not the target
 * profile itself but one solved ([solveStampProfile]) so that a straight stroke of accumulated
 * stamps lands on the target's cross-section. The tip is widened to `1 + feathering` so the
 * texture has room for the part of the fade that lies outside the nominal edge.
 *
 * **What it can't copy** (see also docs/Native Rendering Engine Design.md, "Jetpack Ink brush"):
 * - the fade is solved for a straight line; on tight curves the inside of the bend gets more
 *   stamps than a straight line would and runs a little denser;
 * - a stroke crossing itself accumulates where the legacy path paints once, so crossings darken;
 * - opacity is baked into the solved texture per 5% step, not continuous.
 */
internal object InkSoftRound {

    /** Particle spacing in multiples of brush size — dense enough that no bead is visible. */
    const val PARTICLE_GAP = 0.1f

    /** Radial samples in a solved profile (and texels from centre to edge in a texture). */
    const val BINS = 64

    private const val STEPS = 20
    private const val SOLVER_ITERATIONS = 40
    private const val EPS = 1e-6

    /** Feathering and opacity quantised to 1/[STEPS]: one texture per step, not per slider tick. */
    fun quantize(value: Float): Float = (value.coerceIn(0f, 1f) * STEPS).let { Math.round(it) / STEPS.toFloat() }

    /** Whether [feathering] needs the soft path at all; zero draws Ink's hard stock pen. */
    fun isSoft(feathering: Float): Boolean = quantize(feathering) > 0f

    /** How much wider the tip is than the nominal brush size, to hold the outer half of the fade. */
    fun tipScale(feathering: Float): Float = 1f + quantize(feathering)

    /** Texture id for one (feathering, opacity) step. [parseTextureId] reads it back. */
    fun textureId(feathering: Float, opacity: Float): String =
        String.format(Locale.US, "$ID_PREFIX%.2f/%.2f", quantize(feathering), quantize(opacity))

    /** (feathering, opacity) from a [textureId], or null for any id this class didn't make. */
    fun parseTextureId(id: String): Pair<Float, Float>? {
        val parts = id.takeIf { it.startsWith(ID_PREFIX) }?.removePrefix(ID_PREFIX)?.split('/')
        val f = parts?.getOrNull(0)?.toFloatOrNull()
        val o = parts?.getOrNull(1)?.toFloatOrNull()
        return if (parts?.size == 2 && f != null && o != null) f to o else null
    }

    /**
     * The legacy round's cross-section at normalised distance [d] from the stroke's centre line,
     * where 1 is the widened tip's rim: 1 inside the fade, a smoothstep down across the blur band
     * (`(1-f)/(1+f)` .. `1`), 0 beyond.
     */
    fun targetProfile(d: Float, feathering: Float): Float {
        val f = quantize(feathering)
        if (f <= 0f) return if (d <= 1f) 1f else 0f
        val inner = (1f - f) / (1f + f)
        return when {
            d <= inner -> 1f
            d >= 1f -> 0f
            else -> {
                val t = (d - inner) / (1f - inner)
                1f - smoothstep(t)
            }
        }
    }

    /** Hermite smoothstep on [t] in 0..1: `3t² - 2t³`. */
    private fun smoothstep(t: Float): Float = t * t * (SMOOTH_A - SMOOTH_B * t)

    /**
     * Per-stamp alpha by radius (index `i` = radius `i / (BINS - 1)`), solved so that stamps every
     * [gap] (in tip diameters) along a straight line accumulate to `opacity × targetProfile` across
     * it. Fixed-point: scale each bin by target/achieved until they agree.
     */
    fun solveStampProfile(feathering: Float, opacity: Float, gap: Float = PARTICLE_GAP): FloatArray {
        val o = quantize(opacity)
        val target = FloatArray(BINS) { i -> o * targetProfile(i / (BINS - 1f), feathering) }
        val stamp = target.copyOf()
        repeat(SOLVER_ITERATIONS) {
            val achieved = accumulatedCrossSection(stamp, gap)
            for (i in 0 until BINS) {
                val want = target[i].toDouble()
                val got = achieved[i].toDouble()
                stamp[i] = when {
                    want <= EPS -> 0f
                    got <= EPS -> min(1f, stamp[i] + SEED_STEP)
                    else -> (stamp[i] * want / got).toFloat().coerceIn(0f, 1f)
                }
            }
        }
        return stamp
    }

    /**
     * What stamps of radial alpha [stamp] placed every [gap] tip diameters along the x axis add up
     * to at each lateral distance `y = i / (BINS - 1)` tip radii — the straight-stroke
     * cross-section. Exposed for tests; [solveStampProfile] inverts it.
     */
    fun accumulatedCrossSection(stamp: FloatArray, gap: Float): FloatArray {
        val spacing = max(gap * 2f, MIN_SPACING) // diameters -> radii
        return FloatArray(BINS) { i ->
            val y = i / (BINS - 1f)
            // A pixel can sit anywhere between two stamp centres, so average over the phases.
            var sum = 0.0
            for (phase in 0 until PHASES) {
                var transmit = 1.0
                var x = -1f + spacing * phase / PHASES
                while (x <= 1f) {
                    transmit *= 1.0 - sample(stamp, sqrt(x * x + y * y))
                    x += spacing
                }
                sum += 1.0 - transmit
            }
            (sum / PHASES).toFloat()
        }
    }

    /** Linear lookup of [profile] at radius [r] (0 centre, 1 rim); 0 outside. */
    fun sample(profile: FloatArray, r: Float): Float {
        if (r >= 1f || r < 0f) return 0f
        val pos = r * (profile.size - 1)
        val i = pos.toInt()
        val t = pos - i
        val next = if (i + 1 < profile.size) profile[i + 1] else 0f
        return profile[i] * (1f - t) + next * t
    }

    private const val ID_PREFIX = "graffux.ink.softround/"
    private const val PHASES = 4
    private const val SMOOTH_A = 3f
    private const val SMOOTH_B = 2f
    /** Nudge for a bin that achieved nothing yet, so the multiplicative update can take hold. */
    private const val SEED_STEP = 0.1f
    private const val MIN_SPACING = 1e-3f
}
