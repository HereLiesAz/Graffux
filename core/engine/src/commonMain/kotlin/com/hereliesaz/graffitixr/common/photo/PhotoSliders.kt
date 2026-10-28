package com.hereliesaz.graffitixr.common.photo

import kotlin.math.abs
import kotlin.math.roundToInt

/** How a slider's value is written beside it. */
enum class SliderFormat {
    /** Signed whole number: +12, −38, 0. */
    SIGNED,
    /** Unsigned whole number (a slider that starts at 0): 30. */
    PLAIN,
    /** Exposure: +0.35. */
    EV,
    /** Temperature: 5900 K. */
    KELVIN,
    /** Degrees with one decimal: −3.0°. */
    DEGREES,
    /** Levels' midpoint: 1.10. */
    GAMMA,
}

/**
 * One slider row of the photo sheet: its [id] in [PhotoAdjustment.values], what it is called, its
 * range and resting value. [bipolar] sliders rest in the middle; the others start at their left end.
 */
data class PhotoSlider(
    val id: String,
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float = 0f,
    val format: SliderFormat = SliderFormat.SIGNED,
) {
    val bipolar: Boolean get() = min < 0f && max > 0f

    /** Where [value] sits along the track, 0..1. */
    fun fraction(value: Float): Float = ((value - min) / (max - min)).coerceIn(0f, 1f)

    /** The value at [fraction] along the track, snapped to the slider's step. */
    fun valueAt(fraction: Float): Float {
        val raw = min + (max - min) * fraction.coerceIn(0f, 1f)
        return snap(raw)
    }

    /** [value] on this slider's step: a hundredth of an EV, ten kelvin, a tenth of a degree, else one. */
    fun snap(value: Float): Float = when (format) {
        SliderFormat.EV, SliderFormat.GAMMA -> (value * 100f).roundToInt() / 100f
        SliderFormat.KELVIN -> (value / 10f).roundToInt() * 10f
        SliderFormat.DEGREES -> (value * 10f).roundToInt() / 10f
        else -> value.roundToInt().toFloat()
    }.coerceIn(min, max)

    /** The value as the row shows it. Minus is U+2212, as the mockup sets it. */
    fun display(value: Float): String {
        fun sign(v: Float) = when {
            v > 0f -> "+"
            v < 0f -> "−"
            else -> ""
        }
        return when (format) {
            SliderFormat.SIGNED -> value.roundToInt().let { "${sign(it.toFloat())}${abs(it)}" }
            SliderFormat.PLAIN -> "${value.roundToInt()}"
            SliderFormat.EV -> {
                val hundredths = (value * 100f).roundToInt()
                val a = abs(hundredths)
                "${sign(hundredths.toFloat())}${a / 100}.${(a % 100).toString().padStart(2, '0')}"
            }
            SliderFormat.KELVIN -> "${value.roundToInt()} K"
            SliderFormat.DEGREES -> {
                val tenths = (value * 10f).roundToInt()
                val a = abs(tenths)
                "${sign(tenths.toFloat())}${a / 10}.${a % 10}°"
            }
            SliderFormat.GAMMA -> {
                val hundredths = (value * 100f).roundToInt()
                "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
            }
        }
    }
}

/** The eight HSL ranges, in the order the swatches are drawn, with the hue each one centres on. */
enum class HueRange(val label: String, val centerDegrees: Float, val swatchArgb: Long) {
    RED("Red", 0f, 0xFFE5484D),
    ORANGE("Orange", 30f, 0xFFF0892A),
    YELLOW("Yellow", 60f, 0xFFE8C21E),
    GREEN("Green", 120f, 0xFF45A35A),
    AQUA("Aqua", 180f, 0xFF12A5A5),
    BLUE("Blue", 240f, 0xFF4166E0),
    PURPLE("Purple", 270f, 0xFF8E4FCC),
    MAGENTA("Magenta", 300f, 0xFFD8459B),
}

/** The sliders of every photo tab, in the order the mockup lists them. */
object PhotoSliders {
    val EXPOSURE = PhotoSlider("exposure", "Exposure", -5f, 5f, format = SliderFormat.EV)
    val CONTRAST = PhotoSlider("contrast", "Contrast", -100f, 100f)
    val HIGHLIGHTS = PhotoSlider("highlights", "Highlights", -100f, 100f)
    val SHADOWS = PhotoSlider("shadows", "Shadows", -100f, 100f)
    val WHITES = PhotoSlider("whites", "Whites", -100f, 100f)
    val BLACKS = PhotoSlider("blacks", "Blacks", -100f, 100f)

    /** 6500 K is neutral: the photo as it is. Lower cools it, higher warms it. */
    const val NEUTRAL_KELVIN = 6500f
    val TEMPERATURE = PhotoSlider("temperature", "Temperature", 2000f, 12000f, NEUTRAL_KELVIN, SliderFormat.KELVIN)
    val TINT = PhotoSlider("tint", "Tint", -100f, 100f)
    val VIBRANCE = PhotoSlider("vibrance", "Vibrance", -100f, 100f)
    val SATURATION = PhotoSlider("saturation", "Saturation", -100f, 100f)

    val CLARITY = PhotoSlider("clarity", "Clarity", -100f, 100f)
    val TEXTURE = PhotoSlider("texture", "Texture", -100f, 100f)
    val DEHAZE = PhotoSlider("dehaze", "Dehaze", -100f, 100f)
    val NOISE_REDUCTION = PhotoSlider("noise", "Noise reduction", 0f, 100f, format = SliderFormat.PLAIN)
    val SHARPEN = PhotoSlider("sharpen", "Sharpen", 0f, 100f, format = SliderFormat.PLAIN)

    val VIGNETTE = PhotoSlider("vignette", "Vignette amount", -100f, 100f)
    val VIGNETTE_MIDPOINT = PhotoSlider("vignette.mid", "Midpoint", 0f, 100f, 50f, SliderFormat.PLAIN)
    val VIGNETTE_FEATHER = PhotoSlider("vignette.feather", "Feather", 0f, 100f, 50f, SliderFormat.PLAIN)
    val GRAIN = PhotoSlider("grain", "Grain amount", 0f, 100f, format = SliderFormat.PLAIN)
    val GRAIN_SIZE = PhotoSlider("grain.size", "Grain size", 0f, 100f, 25f, SliderFormat.PLAIN)

    val LEVELS_BLACK = PhotoSlider("levels.black", "Black point", 0f, 254f, 0f, SliderFormat.PLAIN)
    val LEVELS_MID = PhotoSlider("levels.mid", "Midpoint", 0.1f, 9.99f, 1f, SliderFormat.GAMMA)
    val LEVELS_WHITE = PhotoSlider("levels.white", "White point", 1f, 255f, 255f, SliderFormat.PLAIN)

    /** HSL: hue, saturation and luminance of one [range]. */
    fun hsl(range: HueRange, component: Int): PhotoSlider = PhotoSlider(
        "hsl.${range.name.lowercase()}.${HSL_COMPONENTS[component].lowercase()}",
        HSL_COMPONENTS[component],
        -100f,
        100f,
    )

    val HSL_COMPONENTS = listOf("Hue", "Saturation", "Luminance")

    /** The slider rows of [tool]'s tab ([PhotoTool.HSL]'s depend on the range: see [hsl]). */
    fun of(tool: PhotoTool): List<PhotoSlider> = when (tool) {
        PhotoTool.LIGHT -> listOf(EXPOSURE, CONTRAST, HIGHLIGHTS, SHADOWS, WHITES, BLACKS)
        PhotoTool.COLOR -> listOf(TEMPERATURE, TINT, VIBRANCE, SATURATION)
        PhotoTool.HSL -> HueRange.entries.flatMap { r -> (0..2).map { hsl(r, it) } }
        PhotoTool.CURVES -> listOf(LEVELS_BLACK, LEVELS_MID, LEVELS_WHITE)
        PhotoTool.DETAIL -> listOf(CLARITY, TEXTURE, DEHAZE, NOISE_REDUCTION, SHARPEN)
        PhotoTool.EFFECTS -> listOf(VIGNETTE, VIGNETTE_MIDPOINT, VIGNETTE_FEATHER, GRAIN, GRAIN_SIZE)
        PhotoTool.HEAL, PhotoTool.RED_EYE -> emptyList()
    }

    /** Every slider, for looking one up by id. */
    val all: Map<String, PhotoSlider> by lazy { PhotoTool.entries.flatMap(::of).associateBy { it.id } }
}
