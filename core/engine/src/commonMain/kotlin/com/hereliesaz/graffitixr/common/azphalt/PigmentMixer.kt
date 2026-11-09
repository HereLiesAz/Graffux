package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Kubelka-Munk pigment mixing (spec/painterly.md 6.2, phase P3). The Kotlin reference and test oracle:
 * `core/wgpu-engine/src/shaders/pigment.wgsl` is a statement-for-statement port, pinned to this file
 * by `PigmentGoldenFixtureTest` and `core/wgpu-engine/tests/pigment.rs`.
 *
 * A [Pigment] carries absorption K and scattering S at [PigmentModel.BANDS] wavelengths. A mixture's
 * K and S are the concentration-weighted sums (K-M is linear in concentration); the film's reflectance
 * is `R = 1 + K/S - sqrt((K/S)^2 + 2 K/S)`, then a fixed band-to-sRGB matrix gives colour. Tinting
 * strength is not a parameter: it falls out of K and S magnitudes. Opacity is separate (alpha), not
 * modelled here.
 */
object PigmentModel {
    const val BANDS = 7

    /** Band centres, nm. */
    val WAVELENGTHS: FloatArray = floatArrayOf(440f, 470f, 500f, 540f, 580f, 620f, 660f)

    /**
     * Band reflectance -> linear sRGB, row per channel. PLACEHOLDER: built from a multi-lobe Gaussian fit
     * of the CIE 1931 observer sampled at [WAVELENGTHS], through the XYZ-to-sRGB matrix, each row
     * normalised so a flat reflectance of 1 gives (1, 1, 1). Frozen as constants so Kotlin and WGSL
     * share the exact numbers. Replace with a calibrated matrix when calibrated data arrives
     * (bump [PigmentSet.DATA_FORMAT] consumers' dataVersion).
     */
    internal val BAND_TO_LINEAR_RGB: Array<FloatArray> = arrayOf(
        floatArrayOf(0.0846821f, -0.0513403f, -0.1992169f, -0.1765789f, 0.5181762f, 0.6919792f, 0.1322987f),
        floatArrayOf(-0.0952128f, 0.0143237f, 0.2472030f, 0.6014336f, 0.2953821f, -0.0499800f, -0.0131496f),
        floatArrayOf(0.5937924f, 0.4371635f, 0.0707287f, -0.0505347f, -0.0405309f, -0.0092208f, -0.0013983f),
    )
}

@Serializable
data class Pigment(
    val id: String,
    val name: String,
    /** Colour Index generic name, e.g. PB29. */
    @SerialName("colourIndex") val colourIndex: String = "",
    /** "placeholder" = hand-tuned, believable, NOT measured. "calibrated" = fitted to measured data. */
    val dataQuality: String = QUALITY_PLACEHOLDER,
    /** Bumped whenever this pigment's numbers change, so recorded strokes keep resolving what they used. */
    val dataVersion: Int = 1,
    /** 0..1 opacity: how much of the surface below is hidden at working thickness. Drives alpha. */
    val hiding: Float = 1f,
    /** 0..1 tinting strength relative to the strongest tinter. Informational; K and S already encode it. */
    val tinting: Float = 1f,
    /** 0..1 tendency to settle into paper tooth (used by the watercolour passes). */
    val granulation: Float = 0f,
    /** Absorption per band. */
    val K: List<Float>,
    /** Scattering per band. */
    val S: List<Float>,
) {
    val isPlaceholder: Boolean get() = dataQuality != QUALITY_CALIBRATED

    companion object {
        const val QUALITY_PLACEHOLDER = "placeholder"
        const val QUALITY_CALIBRATED = "calibrated"
    }
}

@Serializable
data class PigmentSet(
    val format: String = DATA_FORMAT,
    val wavelengths: List<Float> = PigmentModel.WAVELENGTHS.toList(),
    val pigments: List<Pigment>,
) {
    /** Null when valid, else why not. */
    fun validate(): String? {
        if (format != DATA_FORMAT) return "unknown format $format"
        if (wavelengths.size != PigmentModel.BANDS) return "expected ${PigmentModel.BANDS} wavelengths"
        val ids = HashSet<String>()
        for (p in pigments) {
            if (p.id.isBlank() || !ids.add(p.id)) return "duplicate or blank pigment id '${p.id}'"
            if (p.K.size != PigmentModel.BANDS || p.S.size != PigmentModel.BANDS) return "${p.id}: K and S need ${PigmentModel.BANDS} values"
            if (p.K.any { !it.isFinite() || it < 0f }) return "${p.id}: K must be finite and non-negative"
            if (p.S.any { !it.isFinite() || it <= 0f }) return "${p.id}: S must be finite and positive"
        }
        return null
    }

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val DATA_FORMAT = "azphalt-pigments/1"
        private val JSON = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

        /** Parses and validates; null on any problem (never a partial set). */
        fun fromJson(text: String): PigmentSet? =
            runCatching { JSON.decodeFromString(serializer(), text) }.getOrNull()?.takeIf { it.validate() == null }
    }
}

object PigmentMixer {
    /**
     * Linear sRGB (0..1) of the mixture of [pigments] in proportions [concentration] (any non-negative
     * scale; normalised here). All zero or non-finite gives black.
     */
    fun mixLinearRgb(concentration: FloatArray, pigments: List<Pigment>): FloatArray {
        val n = pigments.size
        var total = 0f
        for (i in 0 until n) total += concentration[i]
        val out = FloatArray(3)
        if (!(total > 0f) || !total.isFinite()) return out
        val reflectance = FloatArray(PigmentModel.BANDS)
        for (b in 0 until PigmentModel.BANDS) {
            var k = 0f
            var s = 0f
            for (i in 0 until n) {
                val w = concentration[i] / total
                k += w * pigments[i].K[b]
                s += w * pigments[i].S[b]
            }
            val q = k / s
            reflectance[b] = 1f + q - sqrt(q * q + 2f * q)
        }
        for (c in 0 until 3) {
            var v = 0f
            for (b in 0 until PigmentModel.BANDS) v += PigmentModel.BAND_TO_LINEAR_RGB[c][b] * reflectance[b]
            out[c] = v.coerceIn(0f, 1f)
        }
        return out
    }

    /** Linear 0..1 to sRGB-encoded 0..1. */
    fun encodeSrgb(linear: FloatArray): FloatArray = FloatArray(3) { c ->
        val x = linear[c].coerceIn(0f, 1f)
        if (x <= 0.0031308f) 12.92f * x else 1.055f * x.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f
    }

    /** Linear 0..1 to sRGB-encoded 0..255. */
    fun encodeSrgb8(linear: FloatArray): IntArray {
        val e = encodeSrgb(linear)
        return IntArray(3) { c -> (e[c] * 255f + 0.5f).toInt().coerceIn(0, 255) }
    }

    fun mixSrgb8(concentration: FloatArray, pigments: List<Pigment>): IntArray =
        encodeSrgb8(mixLinearRgb(concentration, pigments))
}

/**
 * The placeholder tube set: ~12 generic-named pigments whose K and S are derived from a hand-tuned
 * masstone reflectance and a scattering level (`S = s`, `K = s (1-R)^2 / (2R)`, the single-constant
 * K-M relation). **Placeholder data**: believable, not measured. Calibrated numbers replace these
 * through the same [PigmentSet] format with `dataQuality = "calibrated"`.
 */
object PlaceholderPigments {
    internal class Anchor(
        val id: String,
        val name: String,
        val colourIndex: String,
        val reflectance: FloatArray,
        val scatter: Float,
        val hiding: Float,
        val tinting: Float,
        val granulation: Float,
    )

    private val anchors = listOf(
        Anchor("titanium-white", "Titanium White", "PW6", floatArrayOf(0.92f, 0.92f, 0.92f, 0.92f, 0.92f, 0.92f, 0.92f), 3.0000000f, 0.9800000f, 1.0000000f, 0.0500000f),
        Anchor("ivory-black", "Ivory Black", "PBk9", floatArrayOf(0.04f, 0.04f, 0.04f, 0.04f, 0.04f, 0.04f, 0.04f), 0.6000000f, 0.9700000f, 0.9000000f, 0.1000000f),
        Anchor("cadmium-yellow", "Cadmium Yellow", "PY35", floatArrayOf(0.05f, 0.06f, 0.12f, 0.60f, 0.88f, 0.92f, 0.93f), 1.0000000f, 0.9500000f, 0.7500000f, 0.1000000f),
        Anchor("yellow-ochre", "Yellow Ochre", "PY43", floatArrayOf(0.06f, 0.07f, 0.10f, 0.25f, 0.40f, 0.50f, 0.58f), 0.9000000f, 0.8500000f, 0.4500000f, 0.2000000f),
        Anchor("cadmium-red", "Cadmium Red", "PR108", floatArrayOf(0.03f, 0.03f, 0.04f, 0.07f, 0.20f, 0.70f, 0.85f), 1.0000000f, 0.9500000f, 0.8000000f, 0.1000000f),
        Anchor("quinacridone-magenta", "Quinacridone Magenta", "PR122", floatArrayOf(0.35f, 0.20f, 0.05f, 0.04f, 0.08f, 0.35f, 0.60f), 0.8000000f, 0.3500000f, 0.9500000f, 0.0000000f),
        Anchor("ultramarine", "Ultramarine Blue", "PB29", floatArrayOf(0.30f, 0.40f, 0.18f, 0.07f, 0.05f, 0.07f, 0.18f), 0.7000000f, 0.4500000f, 0.5500000f, 0.5500000f),
        Anchor("phthalo-blue", "Phthalo Blue", "PB15:3", floatArrayOf(0.20f, 0.30f, 0.38f, 0.15f, 0.04f, 0.03f, 0.04f), 1.0000000f, 0.3000000f, 1.0000000f, 0.0000000f),
        Anchor("phthalo-green", "Phthalo Green", "PG7", floatArrayOf(0.03f, 0.10f, 0.38f, 0.45f, 0.20f, 0.05f, 0.03f), 1.0000000f, 0.3000000f, 1.0000000f, 0.0000000f),
        Anchor("viridian", "Viridian", "PG18", floatArrayOf(0.05f, 0.12f, 0.30f, 0.30f, 0.12f, 0.05f, 0.04f), 0.8000000f, 0.3500000f, 0.6000000f, 0.3000000f),
        Anchor("cerulean", "Cerulean Blue", "PB35", floatArrayOf(0.30f, 0.45f, 0.45f, 0.25f, 0.10f, 0.06f, 0.06f), 0.8000000f, 0.8000000f, 0.5000000f, 0.3500000f),
        Anchor("burnt-umber", "Burnt Umber", "PBr7", floatArrayOf(0.03f, 0.03f, 0.04f, 0.06f, 0.09f, 0.14f, 0.20f), 0.7000000f, 0.9000000f, 0.5500000f, 0.2500000f),
    )

    val set: PigmentSet = PigmentSet(
        pigments = anchors.map { a ->
            Pigment(
                id = a.id,
                name = a.name,
                colourIndex = a.colourIndex,
                hiding = a.hiding,
                tinting = a.tinting,
                granulation = a.granulation,
                K = a.reflectance.map { r -> a.scatter * (1f - r) * (1f - r) / (2f * r) },
                S = a.reflectance.map { a.scatter },
            )
        },
    )

    fun byId(id: String): Pigment = set.pigments.first { it.id == id }
}
