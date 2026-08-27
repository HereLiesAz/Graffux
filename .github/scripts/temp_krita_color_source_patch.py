from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1))


# Brush model: Source and base Mix are explicit brush properties.
path = "core/common/src/main/java/com/hereliesaz/graffitixr/common/azphalt/AzphaltBrush.kt"
replace_once(path,
'''@Serializable
enum class MaskedBrushBlendMode {''',
'''@Serializable
enum class BrushColorSource {
    @SerialName("plain") PLAIN,
    @SerialName("gradient") GRADIENT,
    @SerialName("uniformRandom") UNIFORM_RANDOM,
}

@Serializable
enum class MaskedBrushBlendMode {''')
replace_once(path,
'''    val followStroke: Boolean = false,
    val maskedBrush: MaskedBrushConfig? = null,''',
'''    val followStroke: Boolean = false,
    /** Krita-style colour source. PLAIN is the historical single foreground colour. */
    val colorSource: BrushColorSource = BrushColorSource.PLAIN,
    /** Base foreground→background gradient coordinate. A MIX sensor route may override per dab. */
    val colorMix: Float = 0f,
    val maskedBrush: MaskedBrushConfig? = null,''')
replace_once(path,
'''        grainStrength = grainStrength.coerceIn(0f, 1f),
        maskedBrush = maskedBrush?.sanitized(),''',
'''        grainStrength = grainStrength.coerceIn(0f, 1f),
        colorMix = colorMix.coerceIn(0f, 1f),
        maskedBrush = maskedBrush?.sanitized(),''')
replace_once(path,
'''            val maskedBrush = params?.get("maskedBrush")?.let { element ->
                runCatching { AzphaltJson.decodeFromJsonElement<MaskedBrushConfig>(element) }.getOrNull()
            }?.sanitized()

            return AzphaltBrush(''',
'''            val maskedBrush = params?.get("maskedBrush")?.let { element ->
                runCatching { AzphaltJson.decodeFromJsonElement<MaskedBrushConfig>(element) }.getOrNull()
            }?.sanitized()
            val colorSource = params?.get("colorSource")?.let { element ->
                runCatching { AzphaltJson.decodeFromJsonElement<BrushColorSource>(element) }.getOrNull()
            } ?: BrushColorSource.PLAIN

            return AzphaltBrush(''')
replace_once(path,
'''                followStroke = b("followStroke") ?: false,
                maskedBrush = maskedBrush,''',
'''                followStroke = b("followStroke") ?: false,
                colorSource = colorSource,
                colorMix = (f("colorMix") ?: f("mix") ?: 0f).coerceIn(0f, 1f),
                maskedBrush = maskedBrush,''')

# Sensor engine: Mix is an absolute [0,1] selector, not an HSV multiplier.
path = "core/common/src/main/java/com/hereliesaz/graffitixr/common/azphalt/BrushSensorDynamics.kt"
replace_once(path,
'''    @SerialName("value") VALUE,
    @SerialName("smudgeRate") SMUDGE_RATE,''',
'''    @SerialName("value") VALUE,
    @SerialName("mix") MIX,
    @SerialName("smudgeRate") SMUDGE_RATE,''')
replace_once(path,
'''    val valueMultiplier: Float = 1f,
    val smudgeRateMultiplier: Float = 1f,''',
'''    val valueMultiplier: Float = 1f,
    /** Absolute gradient coordinate supplied by the last MIX route; null means use brush.colorMix. */
    val mixValue: Float? = null,
    val smudgeRateMultiplier: Float = 1f,''')
replace_once(path,
'''        var value = 1f
        var smudgeRate = 1f''',
'''        var value = 1f
        var mix: Float? = null
        var smudgeRate = 1f''')
replace_once(path,
'''                BrushParameter.VALUE -> value *= mapped
                BrushParameter.SMUDGE_RATE -> smudgeRate *= mapped''',
'''                BrushParameter.VALUE -> value *= mapped
                BrushParameter.MIX -> mix = mapped
                BrushParameter.SMUDGE_RATE -> smudgeRate *= mapped''')
replace_once(path,
'''            valueMultiplier = value.coerceAtLeast(0f),
            smudgeRateMultiplier = smudgeRate.coerceAtLeast(0f),''',
'''            valueMultiplier = value.coerceAtLeast(0f),
            mixValue = mix?.coerceIn(0f, 1f),
            smudgeRateMultiplier = smudgeRate.coerceAtLeast(0f),''')

# Dab resolution: colour randomness has its own RNG stream.
path = "core/common/src/main/java/com/hereliesaz/graffitixr/common/azphalt/BrushStamps.kt"
replace_once(path,
'''private const val MASK_SEED_SALT = 0x4D41534B5F544950L''',
'''private const val MASK_SEED_SALT = 0x4D41534B5F544950L
private const val COLOR_SEED_SALT = 0x434F4C4F525F4D58L''')
replace_once(path,
'''    val valueMultiplier: Float = 1f,
    val mask: MaskDab? = null,''',
'''    val valueMultiplier: Float = 1f,
    /** Foreground→background selector used by GRADIENT source. */
    val colorMix: Float = 0f,
    /** Independent deterministic per-dab sample used by UNIFORM_RANDOM source. */
    val sourceRandom: Float = 0f,
    val mask: MaskDab? = null,''')
replace_once(path,
'''        val rng = Random(seed)
        val maskRng = Random(seed xor MASK_SEED_SALT)
        val out = ArrayList<Dab>(count)''',
'''        val rng = Random(seed)
        val maskRng = Random(seed xor MASK_SEED_SALT)
        val colorRng = Random(seed xor COLOR_SEED_SALT)
        val out = ArrayList<Dab>(count)''')
replace_once(path,
'''                    angleDeg = angle,
                    tipRatio = brush.tipRatio,
                    mask = mask,''',
'''                    angleDeg = angle,
                    tipRatio = brush.tipRatio,
                    colorMix = brush.colorMix.coerceIn(0f, 1f),
                    sourceRandom = colorRng.nextFloat(),
                    mask = mask,''')
replace_once(path,
'''        val rng = Random(seed)
        val maskRng = Random(seed xor MASK_SEED_SALT)
        val out = ArrayList<Dab>()''',
'''        val rng = Random(seed)
        val maskRng = Random(seed xor MASK_SEED_SALT)
        val colorRng = Random(seed xor COLOR_SEED_SALT)
        val out = ArrayList<Dab>()''')
replace_once(path,
'''                    valueMultiplier = dynamic.valueMultiplier,
                    mask = mask,''',
'''                    valueMultiplier = dynamic.valueMultiplier,
                    colorMix = (dynamic.mixValue ?: brush.colorMix).coerceIn(0f, 1f),
                    sourceRandom = colorRng.nextFloat(),
                    mask = mask,''')

# Renderer: Source resolves first, existing HSV dynamics resolve second.
path = "feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/StampBrushRenderer.kt"
replace_once(path,
'''import com.hereliesaz.graffitixr.common.azphalt.BrushSample''',
'''import com.hereliesaz.graffitixr.common.azphalt.BrushColorSource
import com.hereliesaz.graffitixr.common.azphalt.BrushSample''')
replace_once(path,
'''        maskStamp: Bitmap? = null,
    ) {
        val curved = CatmullRom.densify(points)''',
'''        maskStamp: Bitmap? = null,
        secondaryColorArgb: Int = colorArgb,
    ) {
        val curved = CatmullRom.densify(points)''')
replace_once(path,
'''            maskStamp,
            seed,
        )''',
'''            maskStamp,
            seed,
            secondaryColorArgb,
        )''')
replace_once(path,
'''        maskStamp: Bitmap? = null,
    ) {
        paintDabs(
            canvas,
            BrushStamps.dynamicDabs''',
'''        maskStamp: Bitmap? = null,
        secondaryColorArgb: Int = colorArgb,
    ) {
        paintDabs(
            canvas,
            BrushStamps.dynamicDabs''')
replace_once(path,
'''            maskStamp,
            seed,
        )''',
'''            maskStamp,
            seed,
            secondaryColorArgb,
        )''')
replace_once(path,
'''        maskStamp: Bitmap? = null,
        seed: Long = 0L,
    ) {
        if (dabs.isEmpty()) return''',
'''        maskStamp: Bitmap? = null,
        seed: Long = 0L,
        secondaryColorArgb: Int = colorArgb,
    ) {
        if (dabs.isEmpty()) return''')
replace_once(path,
'''            paintMaskedDabs(canvas, dabs, brush, colorArgb, flow, stamp, grain, maskStamp, seed)''',
'''            paintMaskedDabs(canvas, dabs, brush, colorArgb, secondaryColorArgb, flow, stamp, grain, maskStamp, seed)''')
replace_once(path,
'''                val dabColor = resolvedColor(colorArgb, d)''',
'''                val dabColor = resolvedColor(colorArgb, secondaryColorArgb, brush, d)''')
replace_once(path,
'''            val dabColor = resolvedColor(colorArgb, d)''',
'''            val dabColor = resolvedColor(colorArgb, secondaryColorArgb, brush, d)''')
replace_once(path,
'''        baseColor: Int,
        flow: Float,''',
'''        baseColor: Int,
        secondaryColor: Int,
        flow: Float,''')
replace_once(path,
'''                val dabColor = resolvedColor(baseColor, dab)''',
'''                val dabColor = resolvedColor(baseColor, secondaryColor, brush, dab)''')
replace_once(path,
'''    internal fun resolvedColor(baseArgb: Int, dab: Dab): Int {
        if (dab.hueShiftDeg == 0f &&
            dab.saturationMultiplier == 1f &&
            dab.valueMultiplier == 1f
        ) return baseArgb

        val hsv = FloatArray(3)
        Color.colorToHSV(baseArgb, hsv)
        hsv[0] = ((hsv[0] + dab.hueShiftDeg) % 360f + 360f) % 360f
        hsv[1] = (hsv[1] * dab.saturationMultiplier).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * dab.valueMultiplier).coerceIn(0f, 1f)
        return Color.HSVToColor(Color.alpha(baseArgb), hsv)
    }''',
'''    internal fun resolvedColor(baseArgb: Int, secondaryArgb: Int, brush: AzphaltBrush, dab: Dab): Int {
        val sourced = when (brush.colorSource) {
            BrushColorSource.PLAIN -> baseArgb
            BrushColorSource.GRADIENT -> lerpArgb(baseArgb, secondaryArgb, dab.colorMix)
            BrushColorSource.UNIFORM_RANDOM -> lerpArgb(baseArgb, secondaryArgb, dab.sourceRandom)
        }
        if (dab.hueShiftDeg == 0f &&
            dab.saturationMultiplier == 1f &&
            dab.valueMultiplier == 1f
        ) return sourced

        val hsv = FloatArray(3)
        Color.colorToHSV(sourced, hsv)
        hsv[0] = ((hsv[0] + dab.hueShiftDeg) % 360f + 360f) % 360f
        hsv[1] = (hsv[1] * dab.saturationMultiplier).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * dab.valueMultiplier).coerceIn(0f, 1f)
        return Color.HSVToColor(Color.alpha(sourced), hsv)
    }

    private fun lerpArgb(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun channel(ca: Int, cb: Int): Int = (ca + (cb - ca) * t).roundToInt().coerceIn(0, 255)
        return Color.argb(
            channel(Color.alpha(a), Color.alpha(b)),
            channel(Color.red(a), Color.red(b)),
            channel(Color.green(a), Color.green(b)),
            channel(Color.blue(a), Color.blue(b)),
        )
    }''')

# Replay command owns both authored colours.
path = "feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt"
replace_once(path,
'''    val brushColor: Int,
    val intensity: Float,''',
'''    val brushColor: Int,
    /** Background/secondary brush colour snapshotted for Krita Source/Mix replay. */
    val secondaryBrushColor: Int = android.graphics.Color.BLACK,
    val intensity: Float,''')
replace_once(path,
'''                val gpuCompatibleBrush = stampShapeForStroke == null &&
                    stampGrainForStroke == null &&''',
'''                val gpuCompatibleBrush = stampShapeForStroke == null &&
                    stampBrush.colorSource == com.hereliesaz.graffitixr.common.azphalt.BrushColorSource.PLAIN &&
                    stampGrainForStroke == null &&''')
replace_once(path,
'''                        stampShapeForStroke, stampGrainForStroke, stampMaskShapeForStroke, stampSeed,
                    )''',
'''                        stampShapeForStroke, stampGrainForStroke, stampMaskShapeForStroke, stampSeed,
                        _uiState.value.secondaryColor.toArgb(),
                    )''')
replace_once(path,
'''            brushSize = brushSize,
            brushColor = color,
            intensity = 0.5f,''',
'''            brushSize = brushSize,
            brushColor = color,
            secondaryBrushColor = state.secondaryColor.toArgb(),
            intensity = 0.5f,''')
replace_once(path,
'''    override fun setActiveColor(color: Color) {''',
'''    override fun setSecondaryColor(color: Color) = dispatch(EditorIntent.SetSecondaryColor(color))

    override fun swapBrushColors() = dispatch(EditorIntent.SwapBrushColors)

    override fun setActiveColor(color: Color) {''')

# Replay renderer passes the snapshotted secondary colour.
path = "feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/DrawingEngine.kt"
replace_once(path,
'''                    stroke.stampShape, stroke.stampGrain, stroke.stampMaskShape,
                )''',
'''                    stroke.stampShape, stroke.stampGrain, stroke.stampMaskShape,
                    stroke.secondaryBrushColor,
                )''')
replace_once(path,
'''                    stroke.stampShape, stroke.stampGrain, stroke.stampMaskShape,
                )''',
'''                    stroke.stampShape, stroke.stampGrain, stroke.stampMaskShape,
                    stroke.secondaryBrushColor,
                )''')

# Editor state/intents/actions: foreground and background are first-class.
path = "core/common/src/main/java/com/hereliesaz/graffitixr/common/model/EditorModels.kt"
replace_once(path,
'''    val activeColor: Color = Color.White,
    val showColorPicker: Boolean = false,''',
'''    val activeColor: Color = Color.White,
    /** Krita-style background/secondary paint colour used by gradient Source/Mix brushes. */
    val secondaryColor: Color = Color.Black,
    val showColorPicker: Boolean = false,''')

path = "feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorIntent.kt"
replace_once(path,
'''    /** Sets the active brush color and closes the color picker. */
    data class SetActiveColor(val color: Color) : EditorIntent''',
'''    /** Sets the active foreground brush color. */
    data class SetActiveColor(val color: Color) : EditorIntent
    /** Sets the background/secondary brush color used by gradient Source/Mix brushes. */
    data class SetSecondaryColor(val color: Color) : EditorIntent
    data object SwapBrushColors : EditorIntent''')

path = "feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorReducer.kt"
replace_once(path,
'''        is EditorIntent.SetActiveColor -> state.copy(activeColor = intent.color)
        else -> null''',
'''        is EditorIntent.SetActiveColor -> state.copy(activeColor = intent.color)
        is EditorIntent.SetSecondaryColor -> state.copy(secondaryColor = intent.color)
        EditorIntent.SwapBrushColors -> state.copy(
            activeColor = state.secondaryColor,
            secondaryColor = state.activeColor,
        )
        else -> null''')

path = "feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorActions.kt"
replace_once(path,
'''    fun setBrushSize(size: Float)
    fun setActiveColor(color: Color)
    fun adjustColorLightness(delta: Float)''',
'''    fun setBrushSize(size: Float)
    fun setActiveColor(color: Color)
    fun setSecondaryColor(color: Color)
    fun swapBrushColors()
    fun adjustColorLightness(delta: Float)''')

# Color picker gets compact FG/BG target swatches and swap.
path = "feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorUi.kt"
replace_once(path,
'''                currentColor = uiState.activeColor,
                history = emptyList(),''',
'''                currentColor = uiState.activeColor,
                secondaryColor = uiState.secondaryColor,
                history = emptyList(),''')
replace_once(path,
'''                onSelectColor = { color ->
                    actions.setActiveColor(color)
                    actions.onColorPickerDismissed()
                },
                onDismiss''',
'''                onSelectColor = { color ->
                    actions.setActiveColor(color)
                    actions.onColorPickerDismissed()
                },
                onSelectSecondaryColor = { color ->
                    actions.setSecondaryColor(color)
                    actions.onColorPickerDismissed()
                },
                onSwapColors = actions::swapBrushColors,
                onDismiss''')

path = "feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/SketchToolsDialog.kt"
replace_once(path,
'''fun ColorPickerDialog(
    currentColor: Color,
    history: List<Color>,
    onSelectColor: (Color) -> Unit,
    onDismiss: () -> Unit,''',
'''fun ColorPickerDialog(
    currentColor: Color,
    secondaryColor: Color = Color.Black,
    history: List<Color>,
    onSelectColor: (Color) -> Unit,
    onSelectSecondaryColor: (Color) -> Unit = {},
    onSwapColors: () -> Unit = {},
    onDismiss: () -> Unit,''')
replace_once(path,
'''    var hue by remember { mutableFloatStateOf(initHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initHsv[1]) }
    var brightness by remember { mutableFloatStateOf(initHsv[2]) }

    val selectedColor = remember(hue, saturation, brightness) {
        val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness))
        Color(argb).copy(alpha = currentColor.alpha)
    }''',
'''    var hue by remember { mutableFloatStateOf(initHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initHsv[1]) }
    var brightness by remember { mutableFloatStateOf(initHsv[2]) }
    var target by remember { mutableStateOf(BrushColorTarget.FOREGROUND) }

    fun loadWorking(color: Color) {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt(),
            hsv,
        )
        hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
    }

    val workingAlpha = if (target == BrushColorTarget.FOREGROUND) currentColor.alpha else secondaryColor.alpha
    val selectedColor = remember(hue, saturation, brightness, workingAlpha) {
        val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness))
        Color(argb).copy(alpha = workingAlpha)
    }''')
replace_once(path,
'''            // Color preview
            Box(
                Modifier
                    .size(48.dp)
                    .background(selectedColor, CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape)
            )

            Spacer(Modifier.height(12.dp))''',
'''            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BrushColorTarget.entries.forEach { candidate ->
                    val swatch = if (candidate == BrushColorTarget.FOREGROUND) currentColor else secondaryColor
                    Box(
                        Modifier
                            .size(42.dp)
                            .background(swatch, CircleShape)
                            .border(
                                if (candidate == target) 3.dp else 1.dp,
                                if (candidate == target) Color.White else Color.White.copy(alpha = 0.4f),
                                CircleShape,
                            )
                            .clickable {
                                target = candidate
                                loadWorking(swatch)
                            }
                    )
                }
                AzButton(
                    text = "Swap",
                    onClick = {
                        val next = if (target == BrushColorTarget.FOREGROUND) secondaryColor else currentColor
                        onSwapColors()
                        loadWorking(next)
                    },
                    shape = AzButtonShape.RECTANGLE,
                )
            }

            Spacer(Modifier.height(12.dp))''')
replace_once(path,
'''                onClick = { onSelectColor(selectedColor) },''',
'''                onClick = {
                    if (target == BrushColorTarget.FOREGROUND) onSelectColor(selectedColor)
                    else onSelectSecondaryColor(selectedColor)
                },''')
replace_once(path,
'''/** A small selectable label — the picker's mode and harmony choosers. */''',
'''private enum class BrushColorTarget { FOREGROUND, BACKGROUND }

/** A small selectable label — the picker's mode and harmony choosers. */''')

# Brush Studio: progressively disclosed Source/Mix controls and preview.
path = "feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/BrushStudioWindow.kt"
replace_once(path,
'''import com.hereliesaz.graffitixr.common.azphalt.BrushParameter''',
'''import com.hereliesaz.graffitixr.common.azphalt.BrushColorSource
import com.hereliesaz.graffitixr.common.azphalt.BrushParameter''')
replace_once(path,
'''    draft: AzphaltBrush,
    brushColor: Color,
    isSaved: Boolean,''',
'''    draft: AzphaltBrush,
    brushColor: Color,
    secondaryColor: Color = Color.Black,
    isSaved: Boolean,''')
replace_once(path,
'''    var showDynamics by remember { mutableStateOf(false) }
    var showTexture by remember { mutableStateOf(false) }''',
'''    var showDynamics by remember { mutableStateOf(false) }
    var showColorSource by remember { mutableStateOf(false) }
    var showTexture by remember { mutableStateOf(false) }''')
replace_once(path,
'''            BrushPreview(draft, brushColor)

            ParamSlider("Spacing"''',
'''            BrushPreview(draft, brushColor, secondaryColor)

            ParamSlider("Spacing"''')
replace_once(path,
'''            AzButton(
                text = if (showTexture) "Texture ▴" else "Texture ▾",''',
'''            AzButton(
                text = if (showColorSource) "Color Source ▴" else "Color Source ▾",
                onClick = { showColorSource = !showColorSource },
                shape = AzButtonShape.RECTANGLE,
            )
            if (showColorSource) {
                EnumButtons("Source", BrushColorSource.entries, draft.colorSource) { value ->
                    onEdit { it.copy(colorSource = value) }
                }
                if (draft.colorSource == BrushColorSource.GRADIENT) {
                    ParamSlider("Mix", draft.colorMix, 0f..1f) { value -> onEdit { it.copy(colorMix = value) } }
                    DynamicsPreset("Pressure → Mix", draft.hasRoute(BrushSensor.PRESSURE, BrushParameter.MIX)) {
                        onEdit { it.toggleRoute(BrushSensorBinding(BrushSensor.PRESSURE, BrushParameter.MIX, outputMin = 0f, outputMax = 1f)) }
                    }
                } else if (draft.colorSource == BrushColorSource.UNIFORM_RANDOM) {
                    Text("Each dab samples the foreground→background ramp from its own deterministic random stream.", style = MaterialTheme.typography.labelSmall)
                } else {
                    Text("Plain preserves the historical foreground colour exactly.", style = MaterialTheme.typography.labelSmall)
                }
            }

            AzButton(
                text = if (showTexture) "Texture ▴" else "Texture ▾",''')
replace_once(path,
'''private fun BrushPreview(brush: AzphaltBrush, color: Color) {''',
'''private fun BrushPreview(brush: AzphaltBrush, color: Color, secondaryColor: Color) {''')
replace_once(path,
'''        dabs.forEach { dab ->
            val width = dab.radius * 2f
            val height = width * dab.tipRatio
            drawOval(
                color = color.copy(alpha = color.alpha * dab.alpha),''',
'''        dabs.forEach { dab ->
            val width = dab.radius * 2f
            val height = width * dab.tipRatio
            val sourced = when (brush.colorSource) {
                BrushColorSource.PLAIN -> color
                BrushColorSource.GRADIENT -> mixPreviewColor(color, secondaryColor, dab.colorMix)
                BrushColorSource.UNIFORM_RANDOM -> mixPreviewColor(color, secondaryColor, dab.sourceRandom)
            }
            drawOval(
                color = sourced.copy(alpha = sourced.alpha * dab.alpha),''')
replace_once(path,
'''private const val PREVIEW_SEED = 12345L''',
'''private fun mixPreviewColor(a: Color, b: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = a.alpha + (b.alpha - a.alpha) * t,
    )
}

private const val PREVIEW_SEED = 12345L''')

# Main host supplies the actual global secondary colour to Brush Studio.
path = "app/src/main/java/com/hereliesaz/graffux/MainActivity.kt"
replace_once(path,
'''                        brushColor = uiState.activeColor,
                        isSaved = uiState.brushStudioEditingId != null,''',
'''                        brushColor = uiState.activeColor,
                        secondaryColor = uiState.secondaryColor,
                        isSaved = uiState.brushStudioEditingId != null,''')

# Deterministic Source/Mix tests, including replay snapshot.
Path("feature/editor/src/test/java/com/hereliesaz/graffitixr/feature/editor/ColorSourceMixTest.kt").write_text(r'''package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushColorSource
import com.hereliesaz.graffitixr.common.azphalt.BrushParameter
import com.hereliesaz.graffitixr.common.azphalt.BrushSampleBuilder
import com.hereliesaz.graffitixr.common.azphalt.BrushSensor
import com.hereliesaz.graffitixr.common.azphalt.BrushSensorBinding
import com.hereliesaz.graffitixr.common.azphalt.BrushStamps
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ColorSourceMixTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    @Test
    fun `plain source preserves historical foreground exactly`() {
        val brush = AzphaltBrush(name = "plain")
        val dab = BrushStamps.dabs(listOf(20f, 20f), 12f, brush, 7L).single()
        val foreground = Color.rgb(23, 91, 177)
        assertEquals(foreground, StampBrushRenderer.resolvedColor(foreground, Color.YELLOW, brush, dab))
    }

    @Test
    fun `gradient source uses mix endpoints`() {
        val foreground = Color.RED
        val background = Color.BLUE
        val fgBrush = AzphaltBrush(name = "fg", colorSource = BrushColorSource.GRADIENT, colorMix = 0f)
        val bgBrush = fgBrush.copy(name = "bg", colorMix = 1f)
        val fgDab = BrushStamps.dabs(listOf(10f, 10f), 8f, fgBrush, 11L).single()
        val bgDab = BrushStamps.dabs(listOf(10f, 10f), 8f, bgBrush, 11L).single()
        assertEquals(foreground, StampBrushRenderer.resolvedColor(foreground, background, fgBrush, fgDab))
        assertEquals(background, StampBrushRenderer.resolvedColor(foreground, background, bgBrush, bgDab))
    }

    @Test
    fun `uniform random source is deterministic without perturbing geometry`() {
        val randomBrush = AzphaltBrush(name = "random", spacing = 0.25f, colorSource = BrushColorSource.UNIFORM_RANDOM)
        val plainBrush = randomBrush.copy(colorSource = BrushColorSource.PLAIN)
        val path = listOf(4f, 10f, 60f, 10f)
        val a = BrushStamps.dabs(path, 12f, randomBrush, 1234L)
        val b = BrushStamps.dabs(path, 12f, randomBrush, 1234L)
        val plain = BrushStamps.dabs(path, 12f, plainBrush, 1234L)
        assertEquals(a.map { it.sourceRandom }, b.map { it.sourceRandom })
        assertTrue(a.map { it.sourceRandom }.distinct().size > 1)
        assertEquals(a.map { Triple(it.x, it.y, it.radius) }, plain.map { Triple(it.x, it.y, it.radius) })
    }

    @Test
    fun `pressure mix route overrides the base gradient coordinate`() {
        val brush = AzphaltBrush(
            name = "pressure mix",
            colorSource = BrushColorSource.GRADIENT,
            colorMix = 0.5f,
            dynamics = listOf(
                BrushSensorBinding(BrushSensor.PRESSURE, BrushParameter.MIX, outputMin = 0f, outputMax = 1f)
            ),
        )
        val builder = BrushSampleBuilder()
        val samples = listOf(
            builder.add(8f, 12f, 0L, pressure = 0f),
            builder.add(40f, 12f, 16L, pressure = 1f),
        )
        val dabs = BrushStamps.dynamicDabs(samples, 10f, brush, 55L)
        assertTrue(dabs.first().colorMix < 0.1f)
        assertTrue(dabs.last().colorMix > 0.8f)
    }

    @Test
    fun `replay uses snapshotted secondary colour rather than later UI colour`() = runTest {
        val engine = DrawingEngine(mockk<SlamManager>(relaxed = true))
        val size = IntSize(48, 32)
        val brush = AzphaltBrush(name = "gradient", hardness = 1f, colorSource = BrushColorSource.GRADIENT, colorMix = 1f)
        val command = StrokeCommand(
            path = listOf(Offset(8f, 16f), Offset(40f, 16f)),
            canvasSize = size,
            tool = Tool.BRUSH,
            brushSize = 10f,
            brushColor = Color.RED,
            secondaryBrushColor = Color.BLUE,
            intensity = 0.5f,
            stampBrush = brush,
            flow = 1f,
            seed = 9L,
        )
        fun base(): Bitmap = RenderTestBase.filled(size.width, size.height, Color.WHITE)
        val committed = engine.applySingleStroke(base(), command)
        val replayed = engine.composite(base(), listOf(command))
        assertEquals(committed.getPixel(24, 16), replayed.getPixel(24, 16))
        val p = replayed.getPixel(24, 16)
        assertTrue(Color.blue(p) > Color.red(p))
        assertNotEquals(Color.WHITE, p)
    }
}
''')

test_path = Path("core/common/src/test/java/com/hereliesaz/graffitixr/common/azphalt/BrushColorSourceParsingTest.kt")
test_path.parent.mkdir(parents=True, exist_ok=True)
test_path.write_text(r'''package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class BrushColorSourceParsingTest {
    @Test
    fun `extension params parse color source and mix`() {
        val brush = AzphaltBrush.fromParams(
            "Gradient",
            buildJsonObject {
                put("colorSource", "gradient")
                put("mix", 0.75f)
            },
        )
        assertEquals(BrushColorSource.GRADIENT, brush.colorSource)
        assertEquals(0.75f, brush.colorMix, 0.0001f)
    }
}
''')

# Temporary scaffolding deletes itself in the same patch commit.
Path(".github/workflows/temp-krita-color-source-mix-patch.yml").unlink(missing_ok=True)
Path(".github/scripts/temp_krita_color_source_patch.py").unlink(missing_ok=True)
