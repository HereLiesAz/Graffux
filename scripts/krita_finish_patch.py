from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text()


def write(path: str, text: str) -> None:
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text)


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one occurrence, found {count}: {old[:100]!r}")
    write(path, text.replace(old, new, 1))


# ── Shared sensor vocabulary: Color Smudge uses the same resolver as brush dabs. ──────────────
path = "core/common/src/main/java/com/hereliesaz/graffitixr/common/azphalt/BrushSensorDynamics.kt"
replace_once(path,
'''    @SerialName("value") VALUE,\n}''',
'''    @SerialName("value") VALUE,\n    @SerialName("smudgeRate") SMUDGE_RATE,\n    @SerialName("colorRate") COLOR_RATE,\n    @SerialName("smudgeRadius") SMUDGE_RADIUS,\n}''')
replace_once(path,
'''    val valueMultiplier: Float = 1f,\n)''',
'''    val valueMultiplier: Float = 1f,\n    val smudgeRateMultiplier: Float = 1f,\n    val colorRateMultiplier: Float = 1f,\n    val smudgeRadiusMultiplier: Float = 1f,\n)''')
replace_once(path,
'''        var value = 1f\n\n        for (binding in bindings) {''',
'''        var value = 1f\n        var smudgeRate = 1f\n        var colorRate = 1f\n        var smudgeRadius = 1f\n\n        for (binding in bindings) {''')
replace_once(path,
'''                BrushParameter.VALUE -> value *= mapped\n            }''',
'''                BrushParameter.VALUE -> value *= mapped\n                BrushParameter.SMUDGE_RATE -> smudgeRate *= mapped\n                BrushParameter.COLOR_RATE -> colorRate *= mapped\n                BrushParameter.SMUDGE_RADIUS -> smudgeRadius *= mapped\n            }''')
replace_once(path,
'''            valueMultiplier = value.coerceAtLeast(0f),\n        )''',
'''            valueMultiplier = value.coerceAtLeast(0f),\n            smudgeRateMultiplier = smudgeRate.coerceAtLeast(0f),\n            colorRateMultiplier = colorRate.coerceAtLeast(0f),\n            smudgeRadiusMultiplier = smudgeRadius.coerceAtLeast(0.01f),\n        )''')

# ── Color Smudge reference engine: per-dab sensor resolution, while preserving legacy pixels. ──
write("feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/util/ColorSmudgeEngine.kt", r'''package com.hereliesaz.graffitixr.feature.editor.util

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.azphalt.BrushSample
import com.hereliesaz.graffitixr.common.azphalt.BrushSensorBinding
import com.hereliesaz.graffitixr.common.azphalt.BrushSensorEngine
import com.hereliesaz.graffitixr.common.model.SymmetryMode
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * CPU correctness/reference implementation for Graffux's Color Smudge paint-op.
 *
 * Smear compatibility is deliberately pinned: with no [Settings.dynamics], this uses the same
 * resampling, carrier, falloff and channel rounding as the historical Graffux Smudge implementation.
 * Dulling and Color Rate build on that reference instead of replacing it with a blur approximation.
 *
 * Sensor routing follows the same Krita-shaped [BrushSensorEngine] used by stamp brushes. Physical
 * telemetry is supplied separately from bitmap-space positions: callers remap only x/y, while
 * pressure/speed/tilt/time/distance keep the values recorded under the hand. That makes replay
 * deterministic and prevents zoom or layer scale from changing how a sensor curve feels.
 */
object ColorSmudgeEngine {

    enum class Mode { SMEAR, DULLING }

    data class Settings(
        val mode: Mode = Mode.SMEAR,
        /** Base amount of carried/sampled colour, 0..1. */
        val smudgeRate: Float = 0.65f,
        /** Independent foreground-paint deposition, 0 = pure smudge. */
        val colorRate: Float = 0f,
        /** Overall dab coverage multiplier. */
        val opacity: Float = 1f,
        /** Radius of the brush footprint in bitmap pixels; supplied by DrawingEngine at replay. */
        val radiusPx: Float = 1f,
        /** Dulling sample radius relative to [radiusPx]. */
        val smudgeRadius: Float = 1f,
        val feathering: Float = 0f,
        val wrapAround: Boolean = false,
        /** Whether smudging transports alpha along with RGB. */
        val smearAlpha: Boolean = true,
        /** Foreground colour used only when [colorRate] > 0. */
        val paintColor: Int = Color.BLACK,
        val symmetryMode: SymmetryMode = SymmetryMode.NONE,
        /** Optional Krita-style sensor routes. */
        val dynamics: List<BrushSensorBinding> = emptyList(),
    )

    private data class DabPoint(val position: Offset, val sample: BrushSample?)

    private data class Resolved(
        val smudgeRate: Float,
        val colorRate: Float,
        val opacity: Float,
        val smudgeRadius: Float,
    )

    fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        stroke: List<Offset>,
        settings: Settings,
        samples: List<BrushSample> = emptyList(),
        strokeSeed: Long = 0L,
    ) {
        if (stroke.isEmpty() || width <= 0 || height <= 0 || pixels.size < width * height) return
        applyOne(pixels, width, height, stroke, settings, samples, strokeSeed)
        for (transform in symmetryTransforms(settings.symmetryMode, width.toFloat(), height.toFloat())) {
            // Only x/y are mirrored. Sensor values describe the real hand movement and must not be
            // transformed just because a synthetic symmetry twin is being rendered.
            val transformedSamples = if (samples.size == stroke.size) {
                samples.map { sample ->
                    val p = transform(Offset(sample.x, sample.y))
                    sample.copy(x = p.x, y = p.y, predicted = false)
                }
            } else emptyList()
            applyOne(
                pixels,
                width,
                height,
                stroke.map(transform),
                settings.copy(symmetryMode = SymmetryMode.NONE),
                transformedSamples,
                strokeSeed,
            )
        }
    }

    private fun applyOne(
        pixels: IntArray,
        width: Int,
        height: Int,
        stroke: List<Offset>,
        settings: Settings,
        samples: List<BrushSample>,
        strokeSeed: Long,
    ) {
        val radius = settings.radiusPx.coerceAtLeast(1f)
        val path = resampleWithTelemetry(stroke, samples, (radius / 2f).coerceAtLeast(1f))
        if (path.isEmpty()) return
        val kernel = BrushKernel(radius, settings.feathering)
        val startTime = samples.firstOrNull()?.uptimeMillis ?: 0L
        when (settings.mode) {
            Mode.SMEAR -> smear(pixels, width, height, path, kernel, settings, startTime, strokeSeed)
            Mode.DULLING -> dull(pixels, width, height, path, kernel, settings, startTime, strokeSeed)
        }
    }

    private fun resolve(
        settings: Settings,
        sample: BrushSample?,
        strokeStartUptimeMillis: Long,
        strokeSeed: Long,
        dabIndex: Int,
    ): Resolved {
        if (sample == null || settings.dynamics.isEmpty()) {
            return Resolved(
                settings.smudgeRate.coerceIn(0f, 1f),
                settings.colorRate.coerceIn(0f, 1f),
                settings.opacity.coerceIn(0f, 1f),
                settings.smudgeRadius.coerceAtLeast(0.05f),
            )
        }
        val dynamic = BrushSensorEngine.resolve(
            sample,
            settings.dynamics,
            strokeStartUptimeMillis,
            strokeSeed,
            dabIndex,
        )
        return Resolved(
            (settings.smudgeRate * dynamic.smudgeRateMultiplier).coerceIn(0f, 1f),
            (settings.colorRate * dynamic.colorRateMultiplier).coerceIn(0f, 1f),
            (settings.opacity * dynamic.opacityMultiplier).coerceIn(0f, 1f),
            (settings.smudgeRadius * dynamic.smudgeRadiusMultiplier).coerceAtLeast(0.05f),
        )
    }

    /** Spatial carrier. This remains Graffux's original directional smudge when dynamics are empty. */
    private fun smear(
        pixels: IntArray,
        width: Int,
        height: Int,
        path: List<DabPoint>,
        kernel: BrushKernel,
        settings: Settings,
        strokeStartUptimeMillis: Long,
        strokeSeed: Long,
    ) {
        val carrier = IntArray(kernel.size)
        val start = path.first().position

        // Edge extension prevents a stroke started at a layer border from seeding the carrier with
        // transparent black and dragging a translucent band into otherwise opaque artwork.
        forEachKernel(kernel) { dx, dy, k, _ ->
            val sx = (start.x.toInt() + dx).coerceIn(0, width - 1)
            val sy = (start.y.toInt() + dy).coerceIn(0, height - 1)
            carrier[k] = pixels[sy * width + sx]
        }

        for (i in 1 until path.size) {
            val dab = path[i]
            val resolved = resolve(settings, dab.sample, strokeStartUptimeMillis, strokeSeed, i)
            val cx = dab.position.x.toInt()
            val cy = dab.position.y.toInt()
            forEachKernel(kernel) { dx, dy, k, mask ->
                if (mask <= 0f) return@forEachKernel
                val idx = indexOf(cx + dx, cy + dy, width, height, settings.wrapAround)
                if (idx < 0) return@forEachKernel

                val under = pixels[idx]
                carrier[k] = lerpArgb(
                    under, carrier[k], resolved.smudgeRate, includeAlpha = settings.smearAlpha,
                )
                var out = lerpArgb(
                    under,
                    carrier[k],
                    mask * resolved.opacity,
                    includeAlpha = settings.smearAlpha,
                )
                if (resolved.colorRate > 0f) {
                    out = lerpArgb(
                        out,
                        settings.paintColor,
                        mask * resolved.opacity * resolved.colorRate,
                        includeAlpha = true,
                    )
                }
                pixels[idx] = out
            }
        }
    }

    /** Local colour mixing followed by brush-footprint deposition. */
    private fun dull(
        pixels: IntArray,
        width: Int,
        height: Int,
        path: List<DabPoint>,
        kernel: BrushKernel,
        settings: Settings,
        strokeStartUptimeMillis: Long,
        strokeSeed: Long,
    ) {
        for (i in 1 until path.size) {
            val dab = path[i]
            val resolved = resolve(settings, dab.sample, strokeStartUptimeMillis, strokeSeed, i)
            val cx = dab.position.x.toInt()
            val cy = dab.position.y.toInt()
            val sampleRadius = (settings.radiusPx * resolved.smudgeRadius).roundToInt().coerceAtLeast(1)
            val sampled = weightedAverage(
                pixels, width, height, cx, cy, sampleRadius, settings.wrapAround,
            )

            forEachKernel(kernel) { dx, dy, _, mask ->
                if (mask <= 0f) return@forEachKernel
                val idx = indexOf(cx + dx, cy + dy, width, height, settings.wrapAround)
                if (idx < 0) return@forEachKernel
                val under = pixels[idx]
                var out = lerpArgb(
                    under,
                    sampled,
                    mask * resolved.opacity * resolved.smudgeRate,
                    includeAlpha = settings.smearAlpha,
                )
                if (resolved.colorRate > 0f) {
                    out = lerpArgb(
                        out,
                        settings.paintColor,
                        mask * resolved.opacity * resolved.colorRate,
                        includeAlpha = true,
                    )
                }
                pixels[idx] = out
            }
        }
    }

    private class BrushKernel(radius: Float, feathering: Float) {
        val r: Int = radius.toInt().coerceAtLeast(1)
        val diameter: Int = r * 2 + 1
        val mask: FloatArray = FloatArray(diameter * diameter)
        val size: Int get() = mask.size

        init {
            // Keep the historical falloff byte-for-byte in shape: solid core + smoothstep rim.
            val soft = 0.25f + feathering.coerceIn(0f, 1f) * 0.7f
            for (dy in -r..r) {
                for (dx in -r..r) {
                    val t = hypot(dx.toFloat(), dy.toFloat()) / r
                    mask[(dy + r) * diameter + (dx + r)] = when {
                        t >= 1f -> 0f
                        t <= 1f - soft -> 1f
                        else -> {
                            val u = (1f - t) / soft
                            u * u * (3f - 2f * u)
                        }
                    }
                }
            }
        }
    }

    private inline fun forEachKernel(
        kernel: BrushKernel,
        block: (dx: Int, dy: Int, index: Int, mask: Float) -> Unit,
    ) {
        for (dy in -kernel.r..kernel.r) {
            for (dx in -kernel.r..kernel.r) {
                val k = (dy + kernel.r) * kernel.diameter + (dx + kernel.r)
                block(dx, dy, k, kernel.mask[k])
            }
        }
    }

    private fun indexOf(x: Int, y: Int, width: Int, height: Int, wrapAround: Boolean): Int {
        val ix: Int
        val iy: Int
        if (wrapAround) {
            ix = ((x % width) + width) % width
            iy = ((y % height) + height) % height
        } else {
            if (x !in 0 until width || y !in 0 until height) return -1
            ix = x
            iy = y
        }
        return iy * width + ix
    }

    private fun weightedAverage(
        pixels: IntArray,
        width: Int,
        height: Int,
        cx: Int,
        cy: Int,
        radius: Int,
        wrapAround: Boolean,
    ): Int {
        var sumA = 0.0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var sumW = 0.0
        val rr = radius.toFloat()
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val distance = hypot(dx.toFloat(), dy.toFloat())
                if (distance > rr) continue
                val idx = indexOf(cx + dx, cy + dy, width, height, wrapAround)
                if (idx < 0) continue
                val weight = (1f - distance / rr).coerceIn(0f, 1f)
                if (weight <= 0f) continue
                val p = pixels[idx]
                sumA += (p ushr 24 and 0xFF) * weight
                sumR += (p shr 16 and 0xFF) * weight
                sumG += (p shr 8 and 0xFF) * weight
                sumB += (p and 0xFF) * weight
                sumW += weight
            }
        }
        if (sumW <= 0.0) {
            val idx = indexOf(cx, cy, width, height, wrapAround)
            return if (idx >= 0) pixels[idx] else Color.TRANSPARENT
        }
        return Color.argb(
            (sumA / sumW).roundToInt().coerceIn(0, 255),
            (sumR / sumW).roundToInt().coerceIn(0, 255),
            (sumG / sumW).roundToInt().coerceIn(0, 255),
            (sumB / sumW).roundToInt().coerceIn(0, 255),
        )
    }

    /** `a` moved towards `b` by `t`, rounded per channel so repeated pickup cannot bias dark. */
    private fun lerpArgb(a: Int, b: Int, t: Float, includeAlpha: Boolean): Int {
        val f = t.coerceIn(0f, 1f)
        val aa = a ushr 24 and 0xFF
        val ba = b ushr 24 and 0xFF
        val ia = if (includeAlpha) aa + (ba - aa) * f else aa.toFloat()
        val ir = (a shr 16 and 0xFF) + ((b shr 16 and 0xFF) - (a shr 16 and 0xFF)) * f
        val ig = (a shr 8 and 0xFF) + ((b shr 8 and 0xFF) - (a shr 8 and 0xFF)) * f
        val ib = (a and 0xFF) + ((b and 0xFF) - (a and 0xFF)) * f
        return (ia.roundToInt().coerceIn(0, 255) shl 24) or
            (ir.roundToInt().coerceIn(0, 255) shl 16) or
            (ig.roundToInt().coerceIn(0, 255) shl 8) or
            ib.roundToInt().coerceIn(0, 255)
    }

    /**
     * Same positions as the historical resample() loop. When telemetry is aligned 1:1 with the
     * original stroke, interpolate its physical values at those exact generated positions.
     */
    private fun resampleWithTelemetry(
        stroke: List<Offset>,
        samples: List<BrushSample>,
        step: Float,
    ): List<DabPoint> {
        if (stroke.isEmpty()) return emptyList()
        val aligned = samples.size == stroke.size
        if (stroke.size < 2) return listOf(DabPoint(stroke.first(), samples.firstOrNull()))
        val out = ArrayList<DabPoint>(stroke.size * 2)
        out.add(DabPoint(stroke.first(), if (aligned) samples.first().copy(predicted = false) else null))
        for (i in 1 until stroke.size) {
            val a = stroke[i - 1]
            val b = stroke[i]
            val len = hypot(b.x - a.x, b.y - a.y)
            val n = ceil(len / step).toInt().coerceAtLeast(1)
            for (k in 1..n) {
                val t = k.toFloat() / n
                val p = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                val sample = if (aligned) interpolateSample(samples[i - 1], samples[i], p, t) else null
                out.add(DabPoint(p, sample))
            }
        }
        return out
    }

    private fun interpolateSample(a: BrushSample, b: BrushSample, p: Offset, t: Float): BrushSample =
        BrushSample(
            x = p.x,
            y = p.y,
            uptimeMillis = (a.uptimeMillis + (b.uptimeMillis - a.uptimeMillis) * t).toLong(),
            pressure = lerp(a.pressure, b.pressure, t),
            tiltRadians = lerp(a.tiltRadians, b.tiltRadians, t),
            orientationRadians = lerp(a.orientationRadians, b.orientationRadians, t),
            distancePx = lerp(a.distancePx, b.distancePx, t),
            speedPxPerMs = lerp(a.speedPxPerMs, b.speedPxPerMs, t),
            drawingAngleDeg = lerp(a.drawingAngleDeg, b.drawingAngleDeg, t),
            predicted = false,
        )

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun symmetryTransforms(mode: SymmetryMode, w: Float, h: Float): List<(Offset) -> Offset> {
        val cx = w / 2f
        val cy = h / 2f
        return when (mode) {
            SymmetryMode.NONE -> emptyList()
            SymmetryMode.VERTICAL -> listOf({ p: Offset -> Offset(w - p.x, p.y) })
            SymmetryMode.HORIZONTAL -> listOf({ p: Offset -> Offset(p.x, h - p.y) })
            SymmetryMode.QUADRANT -> listOf(
                { p: Offset -> Offset(w - p.x, p.y) },
                { p: Offset -> Offset(p.x, h - p.y) },
                { p: Offset -> Offset(w - p.x, h - p.y) },
            )
            SymmetryMode.RADIAL_6 -> (1..5).map { k ->
                val rad = Math.toRadians(60.0 * k)
                val c = cos(rad).toFloat()
                val s = sin(rad).toFloat()
                val transform: (Offset) -> Offset = { p: Offset ->
                    val dx = p.x - cx
                    val dy = p.y - cy
                    Offset(cx + dx * c - dy * s, cy + dx * s + dy * c)
                }
                transform
            }
        }
    }
}
''')

# ── Stroke snapshot + ViewModel-owned tool settings. ─────────────────────────────────────────
vm = "feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt"
replace_once(vm,
'''import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor\n''',
'''import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor\nimport com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine\n''')
replace_once(vm,
'''    // Canonical per-point brush telemetry. Empty on legacy/remote commands.\n    val brushSamples: List<BrushSample> = emptyList(),\n    val canvasSize: IntSize,''',
'''    // Canonical per-point brush telemetry. Empty on legacy/remote commands. Smudge stores it too:\n    // the same physical pressure/speed/tilt stream can drive Color Smudge sensor routes on replay.\n    val brushSamples: List<BrushSample> = emptyList(),\n    // Tool.SMUDGE only. Null means a legacy command, whose historical intensity->Smear mapping is\n    // retained by DrawingEngine. Snapshotting the settings here makes undo/redo independent of what\n    // the Tool Options window is set to later.\n    val colorSmudgeSettings: ColorSmudgeEngine.Settings? = null,\n    val canvasSize: IntSize,''')
replace_once(vm,
'''    private val _uiState = MutableStateFlow(EditorUiState())\n    val uiState = _uiState.asStateFlow()\n''',
'''    private val _uiState = MutableStateFlow(EditorUiState())\n    val uiState = _uiState.asStateFlow()\n\n    private val _colorSmudgeSettings = MutableStateFlow(ColorSmudgeEngine.Settings())\n    val colorSmudgeSettings = _colorSmudgeSettings.asStateFlow()\n''')
replace_once(vm,
'''    fun setBrushFlow(amount: Float) {\n        dispatch(EditorIntent.SetBrushFlow(amount))\n    }\n''',
'''    fun setBrushFlow(amount: Float) {\n        dispatch(EditorIntent.SetBrushFlow(amount))\n    }\n\n    fun setColorSmudgeMode(mode: ColorSmudgeEngine.Mode) =\n        _colorSmudgeSettings.update { it.copy(mode = mode) }\n\n    fun setColorSmudgeRate(amount: Float) =\n        _colorSmudgeSettings.update { it.copy(smudgeRate = amount.coerceIn(0f, 1f)) }\n\n    fun setColorSmudgeColorRate(amount: Float) =\n        _colorSmudgeSettings.update { it.copy(colorRate = amount.coerceIn(0f, 1f)) }\n\n    fun setColorSmudgeRadius(amount: Float) =\n        _colorSmudgeSettings.update { it.copy(smudgeRadius = amount.coerceIn(0.05f, 3f)) }\n\n    fun setColorSmudgeOpacity(amount: Float) =\n        _colorSmudgeSettings.update { it.copy(opacity = amount.coerceIn(0f, 1f)) }\n\n    fun setColorSmudgeAlphaCarry(enabled: Boolean) =\n        _colorSmudgeSettings.update { it.copy(smearAlpha = enabled) }\n\n    fun setColorSmudgeDynamics(bindings: List<com.hereliesaz.graffitixr.common.azphalt.BrushSensorBinding>) =\n        _colorSmudgeSettings.update { it.copy(dynamics = bindings) }\n''')
replace_once(vm,
'''                path = points,\n                brushSamples = brushSamples,\n                canvasSize = IntSize(canvasW, canvasH),\n                tool = state.activeTool,\n                brushSize = state.brushSize,\n                brushColor = state.activeColor.toArgb(),\n                intensity = 0.5f,''',
'''                path = points,\n                brushSamples = brushSamples,\n                colorSmudgeSettings = _colorSmudgeSettings.value.takeIf { state.activeTool == Tool.SMUDGE },\n                canvasSize = IntSize(canvasW, canvasH),\n                tool = state.activeTool,\n                brushSize = state.brushSize,\n                brushColor = state.activeColor.toArgb(),\n                intensity = 0.5f,\n                seed = if (state.activeTool == Tool.SMUDGE) System.nanoTime() else 0L,''')

# Widen the live dynamic-stamp GPU path: per-dab flow/HSV no longer force CPU fallback.
replace_once(vm,
'''import com.hereliesaz.graffitixr.nativebridge.BrushDab\nimport com.hereliesaz.graffitixr.nativebridge.VulkanStampEngine\n''',
'''import com.hereliesaz.graffitixr.nativebridge.BrushDab\nimport com.hereliesaz.graffitixr.nativebridge.ResolvedBrushDab\nimport com.hereliesaz.graffitixr.nativebridge.VulkanStampEngine\n''')
replace_once(vm,
'''                val needsPerDabPaint = stampBrush.dynamics.any { route ->\n                    route.parameter == BrushParameter.FLOW ||\n                        route.parameter == BrushParameter.HUE ||\n                        route.parameter == BrushParameter.SATURATION ||\n                        route.parameter == BrushParameter.VALUE\n                }\n                val gpuHandled = !needsPerDabPaint && stampGpuActive && stampGpuEngine?.let { engine ->\n                    val gpuDabs = newDabs.map { BrushDab(it.x, it.y, it.radius, it.alpha, it.angleDeg) }\n                    // The shader's baseAlpha (colorArgb's own alpha channel) must fold in `flow` the\n                    // same way StampBrushRenderer.paintDabs's `baseAlpha * d.alpha * f` does — the\n                    // shader only ever multiplies baseAlpha * d.alpha, so flow has to be pre-baked\n                    // into the alpha channel handed across the JNI boundary rather than passed\n                    // separately.\n                    val flow = _uiState.value.brushFlow.coerceIn(0f, 1f)\n                    val flowAlpha = (((colorArgb ushr 24) and 0xFF) * flow).toInt().coerceIn(0, 255)\n                    val flowColorArgb = (flowAlpha shl 24) or (colorArgb and 0x00FFFFFF)\n                    engine.stampDabs(gpuDabs, flowColorArgb, stampBrush.hardness.coerceIn(0f, 1f)) &&\n                        engine.readback(work)\n                } == true''',
'''                val gpuHandled = stampGpuActive && stampGpuEngine?.let { engine ->\n                    val baseFlow = _uiState.value.brushFlow.coerceIn(0f, 1f)\n                    val gpuDabs = newDabs.map { dab ->\n                        ResolvedBrushDab(\n                            x = dab.x,\n                            y = dab.y,\n                            radius = dab.radius,\n                            alpha = dab.alpha,\n                            angleDeg = dab.angleDeg,\n                            colorArgb = StampBrushRenderer.resolvedColor(colorArgb, dab),\n                            flow = (baseFlow * dab.flowMultiplier).coerceAtLeast(0f),\n                        )\n                    }\n                    engine.stampResolvedDabs(gpuDabs, stampBrush.hardness.coerceIn(0f, 1f)) &&\n                        engine.readback(work)\n                } == true''')

# Expose the already-centralized CPU colour resolver to the Vulkan adapter.
replace_once("feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/StampBrushRenderer.kt",
'''    private fun resolvedColor(baseArgb: Int, dab: Dab): Int {''',
'''    internal fun resolvedColor(baseArgb: Int, dab: Dab): Int {''')

# DrawingEngine consumes the snapshotted settings and remapped x/y telemetry.
drawing = "feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/DrawingEngine.kt"
old_smudge = '''        // Color Smudge is a stateful read/modify/write paint-op, not a Canvas blend. Keep its\n        // correctness path separate from ImageProcessor's generic raster-tool switch so Smear,\n        // Dulling and paint deposition can evolve together and later move to Vulkan as one unit.\n        // The current Tool.SMUDGE maps exactly onto the historical Smear preset: no foreground paint,\n        // carry alpha, and the same intensity->retention curve the previous implementation used.\n        if (stroke.tool == Tool.SMUDGE) {\n            val target = SafeBitmap.copy(bitmap) ?: return bitmap\n            val width = target.width\n            val height = target.height\n            val pixels = IntArray(width * height)\n            target.getPixels(pixels, 0, width, 0, 0, width, height)\n            ColorSmudgeEngine.apply(\n                pixels,\n                width,\n                height,\n                mapped,\n                ColorSmudgeEngine.Settings(\n                    mode = ColorSmudgeEngine.Mode.SMEAR,\n                    smudgeRate = 0.35f + stroke.intensity.coerceIn(0f, 1f) * 0.6f,\n                    colorRate = 0f,\n                    opacity = 1f,\n                    radiusPx = (stroke.brushSize * brushScale / 2f).coerceAtLeast(1f),\n                    feathering = stroke.feathering,\n                    smearAlpha = true,\n                    paintColor = stroke.brushColor,\n                    symmetryMode = stroke.symmetryMode,\n                ),\n            )\n            target.setPixels(pixels, 0, width, 0, 0, width, height)\n            // The engine produces a whole bitmap and cannot obey a Canvas clip mid-pass, so this is\n            // the same confinement contract Liquify uses: hard selections are replaced through a\n            // clip, feathered ones through a soft mask.\n            return SelectionMask.confine(bitmap, target, clipPath, featherRadius)\n        }'''
new_smudge = '''        // Color Smudge is stateful read/modify/write, not a Canvas blend. The stroke snapshots its\n        // settings so changing Tool Options later cannot alter undo/redo. A null snapshot is a legacy\n        // command and intentionally receives the exact old Smear/intensity preset.\n        if (stroke.tool == Tool.SMUDGE) {\n            val target = SafeBitmap.copy(bitmap) ?: return bitmap\n            val width = target.width\n            val height = target.height\n            val pixels = IntArray(width * height)\n            target.getPixels(pixels, 0, width, 0, 0, width, height)\n            val baseSettings = stroke.colorSmudgeSettings ?: ColorSmudgeEngine.Settings(\n                mode = ColorSmudgeEngine.Mode.SMEAR,\n                smudgeRate = 0.35f + stroke.intensity.coerceIn(0f, 1f) * 0.6f,\n                colorRate = 0f,\n                opacity = 1f,\n                smearAlpha = true,\n            )\n            val mappedSamples = if (stroke.brushSamples.size == mapped.size) {\n                stroke.brushSamples.mapIndexed { index, sample ->\n                    val point = mapped[index]\n                    sample.copy(x = point.x, y = point.y, predicted = false)\n                }\n            } else emptyList()\n            ColorSmudgeEngine.apply(\n                pixels,\n                width,\n                height,\n                mapped,\n                baseSettings.copy(\n                    radiusPx = (stroke.brushSize * brushScale / 2f).coerceAtLeast(1f),\n                    feathering = stroke.feathering,\n                    wrapAround = false,\n                    paintColor = stroke.brushColor,\n                    symmetryMode = stroke.symmetryMode,\n                ),\n                samples = mappedSamples,\n                strokeSeed = stroke.seed,\n            )\n            target.setPixels(pixels, 0, width, 0, 0, width, height)\n            return SelectionMask.confine(bitmap, target, clipPath, featherRadius)\n        }'''
replace_once(drawing, old_smudge, new_smudge)

# ── Phone UI: Color Smudge controls appear only while Smudge is in hand. ─────────────────────
write("feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/ToolOptionsWindow.kt", r'''package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.common.model.SymmetryMode
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import com.hereliesaz.graffitixr.design.components.FloatingWindow
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import kotlin.math.roundToInt

/** Tool-specific controls; only controls meaningful to the current tool are surfaced. */
@Composable
fun ToolOptionsWindow(
    stabilizerLevel: Int,
    onSetStabilizerLevel: (Int) -> Unit,
    stabilizerAlgorithm: StabilizerAlgorithm,
    onSetStabilizerAlgorithm: (StabilizerAlgorithm) -> Unit,
    magicWandTolerance: Int?,
    onSetMagicWandTolerance: (Int) -> Unit,
    selectionFeatherPx: Float?,
    onSetSelectionFeather: (Float) -> Unit,
    brushFlow: Float?,
    onSetBrushFlow: (Float) -> Unit,
    brushOpacity: Float?,
    onSetBrushOpacity: (Float) -> Unit,
    colorSmudgeSettings: ColorSmudgeEngine.Settings?,
    onSetColorSmudgeMode: (ColorSmudgeEngine.Mode) -> Unit,
    onSetColorSmudgeRate: (Float) -> Unit,
    onSetColorSmudgeColorRate: (Float) -> Unit,
    onSetColorSmudgeRadius: (Float) -> Unit,
    onSetColorSmudgeOpacity: (Float) -> Unit,
    onSetColorSmudgeAlphaCarry: (Boolean) -> Unit,
    symmetryMode: SymmetryMode,
    onSetSymmetryMode: (SymmetryMode) -> Unit,
    onDismiss: () -> Unit,
) {
    FloatingWindow(title = "Tool Options", onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (brushOpacity != null) {
                Text("Opacity  ${(brushOpacity * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                Text("Caps how solid the stroke can get, even where it crosses itself.", style = MaterialTheme.typography.labelSmall)
                Slider(value = brushOpacity, onValueChange = onSetBrushOpacity, valueRange = 0f..1f)
            }

            if (brushFlow != null) {
                Text("Flow  ${(brushFlow * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                Text("How much paint each stamp lays down.", style = MaterialTheme.typography.labelSmall)
                Slider(value = brushFlow, onValueChange = onSetBrushFlow, valueRange = 0f..1f)
            }

            colorSmudgeSettings?.let { smudge ->
                Text("Color Smudge", style = MaterialTheme.typography.bodySmall)
                ColorSmudgeEngine.Mode.entries.forEach { mode ->
                    AzButton(
                        text = if (mode == smudge.mode) "${mode.name.lowercase().replaceFirstChar { it.uppercase() }} ✓"
                        else mode.name.lowercase().replaceFirstChar { it.uppercase() },
                        onClick = { onSetColorSmudgeMode(mode) },
                        shape = AzButtonShape.RECTANGLE,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("Smudge  ${(smudge.smudgeRate * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                Slider(value = smudge.smudgeRate, onValueChange = onSetColorSmudgeRate, valueRange = 0f..1f)
                Text("Color rate  ${(smudge.colorRate * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                Text("Adds the active colour independently of how much existing paint is moved.", style = MaterialTheme.typography.labelSmall)
                Slider(value = smudge.colorRate, onValueChange = onSetColorSmudgeColorRate, valueRange = 0f..1f)
                if (smudge.mode == ColorSmudgeEngine.Mode.DULLING) {
                    Text("Sample radius  ${"%.2f".format(smudge.smudgeRadius)}×", style = MaterialTheme.typography.bodySmall)
                    Slider(value = smudge.smudgeRadius, onValueChange = onSetColorSmudgeRadius, valueRange = 0.25f..3f)
                }
                Text("Smudge opacity  ${(smudge.opacity * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                Slider(value = smudge.opacity, onValueChange = onSetColorSmudgeOpacity, valueRange = 0f..1f)
                AzButton(
                    text = if (smudge.smearAlpha) "Carry alpha ✓" else "Preserve destination alpha",
                    onClick = { onSetColorSmudgeAlphaCarry(!smudge.smearAlpha) },
                    shape = AzButtonShape.RECTANGLE,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text("Stabilize  $stabilizerLevel%", style = MaterialTheme.typography.bodySmall)
            Text("Smooths jitter out of a drag before it becomes a stroke.", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = stabilizerLevel.toFloat(),
                onValueChange = { onSetStabilizerLevel(it.roundToInt()) },
                valueRange = 0f..100f,
            )
            if (stabilizerLevel > 0) {
                StabilizerAlgorithm.entries.forEach { algo ->
                    AzButton(
                        text = if (algo == stabilizerAlgorithm) "${algo.label} ✓" else algo.label,
                        onClick = { onSetStabilizerAlgorithm(algo) },
                        shape = AzButtonShape.RECTANGLE,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (magicWandTolerance != null) {
                Text("Threshold  ${(magicWandTolerance / 255f * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                Text("How far from the tapped colour still counts as the same colour.", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = magicWandTolerance.toFloat(),
                    onValueChange = { onSetMagicWandTolerance(it.roundToInt()) },
                    valueRange = 0f..255f,
                )
            }

            if (selectionFeatherPx != null) {
                Text("Feather  ${selectionFeatherPx.roundToInt()} px", style = MaterialTheme.typography.bodySmall)
                Text("Softens the boundary of the current selection.", style = MaterialTheme.typography.labelSmall)
                Slider(value = selectionFeatherPx, onValueChange = onSetSelectionFeather, valueRange = 0f..64f)
            }

            if (symmetryMode != SymmetryMode.NONE) {
                Text("Symmetry", style = MaterialTheme.typography.bodySmall)
                SymmetryMode.entries.filter { it != SymmetryMode.NONE }.forEach { mode ->
                    AzButton(
                        text = if (mode == symmetryMode) "${mode.label} ✓" else mode.label,
                        onClick = { onSetSymmetryMode(mode) },
                        shape = AzButtonShape.RECTANGLE,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
''')

main = "app/src/main/java/com/hereliesaz/graffux/MainActivity.kt"
replace_once(main,
'''import com.hereliesaz.graffitixr.feature.editor.toModelBlendMode\n''',
'''import com.hereliesaz.graffitixr.feature.editor.toModelBlendMode\nimport com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine\n''')
replace_once(main,
'''    val uiState by vm.uiState.collectAsState()\n    val allInstalledExtensions by vm.allInstalledExtensions.collectAsState()''',
'''    val uiState by vm.uiState.collectAsState()\n    val colorSmudgeSettings by vm.colorSmudgeSettings.collectAsState()\n    val allInstalledExtensions by vm.allInstalledExtensions.collectAsState()''')
replace_once(main,
'''                        brushOpacity = uiState.brushOpacity.takeIf { uiState.activeBrushName == null },\n                        onSetBrushOpacity = { vm.setBrushOpacity(it) },\n                        symmetryMode = uiState.symmetryMode,''',
'''                        brushOpacity = uiState.brushOpacity.takeIf { uiState.activeBrushName == null },\n                        onSetBrushOpacity = { vm.setBrushOpacity(it) },\n                        colorSmudgeSettings = colorSmudgeSettings.takeIf { uiState.activeTool == Tool.SMUDGE },\n                        onSetColorSmudgeMode = { vm.setColorSmudgeMode(it) },\n                        onSetColorSmudgeRate = { vm.setColorSmudgeRate(it) },\n                        onSetColorSmudgeColorRate = { vm.setColorSmudgeColorRate(it) },\n                        onSetColorSmudgeRadius = { vm.setColorSmudgeRadius(it) },\n                        onSetColorSmudgeOpacity = { vm.setColorSmudgeOpacity(it) },\n                        onSetColorSmudgeAlphaCarry = { vm.setColorSmudgeAlphaCarry(it) },\n                        symmetryMode = uiState.symmetryMode,''')

# ── Native dab ABI: old 5-field path stays valid; resolved dabs opt into per-dab paint. ───────
header = "core/nativebridge/src/main/cpp/include/VulkanStampEngine.h"
replace_once(header,
'''// One dab, laid out to match BrushStamps.Dab (x, y, radius, alpha, angleDeg) plus padding to a\n// 32-byte stride — see shaders/stamp.comp's `Dab` struct, which this must stay binary-identical\n// to (std430 layout rules pad a 5-float struct to 8 floats / 32 bytes regardless, so the padding\n// here just makes that explicit instead of relying on the compiler to insert it invisibly).\nstruct GpuDab {\n    float x;\n    float y;\n    float radius;\n    float alpha;\n    float angleDeg;\n    float pad0;\n    float pad1;\n    float pad2;\n};''',
'''// One dab. The first five fields are the historical ABI. The resolved paint fields widen the\n// buffer to 12 floats / 48 bytes; old aggregate initializers that provide only five values leave\n// `resolved` at zero, so the shader falls back to the stroke-level push-constant colour exactly as\n// before. New callers set resolved=1 and provide per-dab RGBA + flow. Keep this binary-identical to\n// shaders/stamp.comp.\nstruct GpuDab {\n    float x;\n    float y;\n    float radius;\n    float alpha;\n    float angleDeg;\n    float colorR = 0.0f;\n    float colorG = 0.0f;\n    float colorB = 0.0f;\n    float colorA = 0.0f;\n    float flow = 0.0f;\n    float resolved = 0.0f;\n    float pad0 = 0.0f;\n};''')

write("core/nativebridge/src/main/cpp/shaders/stamp.comp", r'''#version 450
layout(local_size_x = 16, local_size_y = 16) in;

// First five fields are the original Vulkan dab ABI. `resolved` selects the widened per-dab paint
// values; legacy JNI leaves it zero, so existing callers still use the push-constant colour/alpha.
struct Dab {
    float x;
    float y;
    float radius;
    float alpha;
    float angleDeg;
    float colorR;
    float colorG;
    float colorB;
    float colorA;
    float flow;
    float resolved;
    float _pad0;
};

layout(std430, binding = 0) readonly buffer DabBuffer { Dab dabs[]; };
layout(binding = 1, rgba8) uniform image2D layerImage;

layout(push_constant) uniform PushConstants {
    uint dabCount;
    float hardness;
    float colorR;
    float colorG;
    float colorB;
    float baseAlpha;
    int originX;
    int originY;
} pc;

float stampCoverage(float distFromCenter, float radius, float hardness) {
    if (radius <= 0.0) return 0.0;
    float t = distFromCenter / radius;
    if (t <= hardness) return 1.0;
    if (t >= 1.0) return 0.0;
    if (hardness >= 0.999) return 1.0 - (t - hardness) / 0.001;
    return 1.0 - (t - hardness) / (1.0 - hardness);
}

void main() {
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy) + ivec2(pc.originX, pc.originY);
    ivec2 size = imageSize(layerImage);
    if (pixel.x < 0 || pixel.y < 0 || pixel.x >= size.x || pixel.y >= size.y) return;

    vec4 dst = imageLoad(layerImage, pixel);
    vec2 p = vec2(pixel) + vec2(0.5);

    for (uint i = 0u; i < pc.dabCount; i++) {
        Dab d = dabs[i];
        float radius = max(d.radius, 0.5);
        if (abs(p.x - d.x) > radius || abs(p.y - d.y) > radius) continue;
        float coverage = stampCoverage(distance(p, vec2(d.x, d.y)), radius, pc.hardness);
        if (coverage <= 0.0) continue;

        bool hasResolvedPaint = d.resolved >= 0.5;
        vec3 srcRgb = hasResolvedPaint ? vec3(d.colorR, d.colorG, d.colorB) : vec3(pc.colorR, pc.colorG, pc.colorB);
        float baseAlpha = hasResolvedPaint ? d.colorA : pc.baseAlpha;
        float dabFlow = hasResolvedPaint ? max(d.flow, 0.0) : 1.0;
        float srcA = baseAlpha * d.alpha * dabFlow * coverage;
        if (srcA <= 0.0) continue;

        dst.rgb = srcRgb * srcA + dst.rgb * (1.0 - srcA);
        dst.a = srcA + dst.a * (1.0 - srcA);
        dst = round(dst * 255.0) / 255.0;
    }
    imageStore(layerImage, pixel, dst);
}
''')

# Build the SPIR-V header from the authoritative shader at CMake configure time. NDK ships glslc.
cmake = "core/nativebridge/src/main/cpp/CMakeLists.txt"
replace_once(cmake,
'''# Define the native library\nadd_library(graffitixr SHARED\n    GraffitiJNI.cpp''',
'''# stamp.comp is authoritative. Regenerate the embedded SPIR-V at configure time so the checked-in\n# header can never drift from the shader source. Android NDK distributions include glslc under\n# shader-tools for each desktop host.\nfind_program(GRAFFUX_GLSLC glslc\n    HINTS\n        "${ANDROID_NDK}/shader-tools/linux-x86_64"\n        "${ANDROID_NDK}/shader-tools/windows-x86_64"\n        "${ANDROID_NDK}/shader-tools/darwin-x86_64"\n    NO_DEFAULT_PATH\n)\nif(NOT GRAFFUX_GLSLC)\n    message(FATAL_ERROR "NDK glslc not found; cannot regenerate shaders/StampSpv.h")\nendif()\nset(STAMP_SPV_BODY "${CMAKE_CURRENT_BINARY_DIR}/stamp_spv_body.inc")\nexecute_process(\n    COMMAND "${GRAFFUX_GLSLC}" -O -mfmt=c --target-env=vulkan1.1\n            -o "${STAMP_SPV_BODY}" "${CMAKE_CURRENT_SOURCE_DIR}/shaders/stamp.comp"\n    RESULT_VARIABLE STAMP_GLSLC_RESULT\n)\nif(NOT STAMP_GLSLC_RESULT EQUAL 0)\n    message(FATAL_ERROR "glslc failed for shaders/stamp.comp: ${STAMP_GLSLC_RESULT}")\nendif()\nfile(READ "${STAMP_SPV_BODY}" STAMP_SPV_C_WORDS)\nfile(WRITE "${CMAKE_CURRENT_SOURCE_DIR}/shaders/StampSpv.h"\n"// Generated from stamp.comp at CMake configure time. Do not hand-edit.\\n#pragma once\\n#include <cstdint>\\n#include <cstddef>\\nnamespace graffux {\\ninline constexpr uint32_t kStampCompSpv[] = {${STAMP_SPV_C_WORDS}};\\ninline constexpr size_t kStampCompSpvWords = sizeof(kStampCompSpv) / sizeof(kStampCompSpv[0]);\\n}  // namespace graffux\\n")\n\n# Define the native library\nadd_library(graffitixr SHARED\n    GraffitiJNI.cpp''')
replace_once(cmake,
'''    VulkanStampEngineReuse.cpp\n    InkStrokePredictorJNI.cpp''',
'''    VulkanStampEngineReuse.cpp\n    VulkanStampDynamicsJNI.cpp\n    InkStrokePredictorJNI.cpp''')

write("core/nativebridge/src/main/cpp/VulkanStampDynamicsJNI.cpp", r'''#include <jni.h>
#include <vector>
#include <algorithm>
#include "include/VulkanStampEngine.h"

namespace {
using graffux::GpuDab;
using graffux::VulkanStampEngine;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_VulkanStampEngine_nativeStampResolvedDabs(
        JNIEnv* env, jobject, jlong handle, jfloatArray dabData, jfloat hardness) {
    auto* engine = reinterpret_cast<VulkanStampEngine*>(handle);
    if (!engine || !dabData) return JNI_FALSE;
    const jsize count = env->GetArrayLength(dabData);
    constexpr int kStride = 10;  // x,y,radius,alpha,angle,r,g,b,a,flow
    if (count <= 0 || count % kStride != 0) return JNI_FALSE;

    jfloat* data = env->GetFloatArrayElements(dabData, nullptr);
    if (!data) return JNI_FALSE;
    std::vector<GpuDab> dabs;
    dabs.reserve(static_cast<size_t>(count / kStride));
    for (jsize i = 0; i < count; i += kStride) {
        GpuDab d{};
        d.x = data[i];
        d.y = data[i + 1];
        d.radius = data[i + 2];
        d.alpha = data[i + 3];
        d.angleDeg = data[i + 4];
        d.colorR = std::clamp(data[i + 5], 0.0f, 1.0f);
        d.colorG = std::clamp(data[i + 6], 0.0f, 1.0f);
        d.colorB = std::clamp(data[i + 7], 0.0f, 1.0f);
        d.colorA = std::clamp(data[i + 8], 0.0f, 1.0f);
        d.flow = std::max(data[i + 9], 0.0f);
        d.resolved = 1.0f;
        dabs.push_back(d);
    }
    env->ReleaseFloatArrayElements(dabData, data, JNI_ABORT);
    return engine->stampDabs(dabs, 0xFFFFFFFFu, std::clamp(hardness, 0.0f, 1.0f))
        ? JNI_TRUE : JNI_FALSE;
}
''')

write("core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/VulkanStampEngine.kt", r'''// FILE: core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/VulkanStampEngine.kt
package com.hereliesaz.graffitixr.nativebridge

import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.HardwareBuffer
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/** Kotlin bridge to the persistent Vulkan dab compositor. */
class VulkanStampEngine {
    init { NativeLibLoader.loadAll() }

    private data class PoolKey(val width: Int, val height: Int, val hardwareBufferBacked: Boolean)
    private data class CachedHandle(val key: PoolKey, val handle: Long)

    companion object {
        private const val MAX_POOLED_HANDLES = 2
        private val poolLock = Any()
        private val pooledHandles = ArrayDeque<CachedHandle>()
        private val nativeCreationCount = AtomicInteger(0)

        @JvmStatic
        fun trimPool() {
            val handles = synchronized(poolLock) {
                if (pooledHandles.isEmpty()) return
                buildList { while (pooledHandles.isNotEmpty()) add(pooledHandles.removeFirst().handle) }
            }
            val destroyer = VulkanStampEngine()
            handles.forEach(destroyer::nativeDestroy)
        }

        internal fun nativeCreationCountForTesting(): Int = nativeCreationCount.get()

        private fun takePooled(key: PoolKey): Long = synchronized(poolLock) {
            val iterator = pooledHandles.iterator()
            while (iterator.hasNext()) {
                val cached = iterator.next()
                if (cached.key == key) {
                    iterator.remove()
                    return@synchronized cached.handle
                }
            }
            0L
        }

        private fun putPooled(cached: CachedHandle): Long = synchronized(poolLock) {
            val evicted = if (pooledHandles.size >= MAX_POOLED_HANDLES) pooledHandles.removeFirst().handle else 0L
            pooledHandles.addLast(cached)
            evicted
        }
    }

    private var nativeHandle: Long = 0L
    private var poolKey: PoolKey? = null
    private var healthy = true
    private var hardwareBufferExported = false

    val isInitialized: Boolean get() = nativeHandle != 0L

    fun init(width: Int, height: Int): Boolean = initialize(width, height, false)
    fun initHardwareBufferBacked(width: Int, height: Int): Boolean = initialize(width, height, true)

    private fun initialize(width: Int, height: Int, hardwareBufferBacked: Boolean): Boolean {
        if (width <= 0 || height <= 0) { destroy(); return false }
        destroy()
        val key = PoolKey(width, height, hardwareBufferBacked)
        val cached = takePooled(key)
        if (cached != 0L) {
            nativeHandle = cached
            poolKey = key
            healthy = true
            hardwareBufferExported = false
            if (nativeClear(cached)) return true
            nativeDestroy(cached)
            nativeHandle = 0L
            poolKey = null
            healthy = false
        }
        nativeCreationCount.incrementAndGet()
        val created = if (hardwareBufferBacked) nativeInitHardwareBuffer(width, height) else nativeInit(width, height)
        nativeHandle = created
        poolKey = if (created != 0L) key else null
        healthy = created != 0L
        hardwareBufferExported = false
        return created != 0L
    }

    fun getHardwareBuffer(): HardwareBuffer? {
        if (!isInitialized) return null
        val buffer = nativeGetHardwareBuffer(nativeHandle)
        if (buffer != null) hardwareBufferExported = true
        return buffer
    }

    fun upload(bitmap: Bitmap): Boolean {
        if (!isInitialized) return false
        require(bitmap.config == Bitmap.Config.ARGB_8888) { "VulkanStampEngine.upload requires ARGB_8888, got ${bitmap.config}" }
        return nativeUpload(nativeHandle, bitmap).also { if (!it) healthy = false }
    }

    /** Historical stroke-level paint entry point. */
    fun stampDabs(dabs: List<BrushDab>, colorArgb: Int, hardness: Float): Boolean {
        if (!isInitialized || dabs.isEmpty()) return false
        val flat = FloatArray(dabs.size * 5)
        for (i in dabs.indices) {
            val d = dabs[i]
            val base = i * 5
            flat[base] = d.x; flat[base + 1] = d.y; flat[base + 2] = d.radius
            flat[base + 3] = d.alpha; flat[base + 4] = d.angleDeg
        }
        return nativeStampDabs(nativeHandle, flat, colorArgb, hardness).also { if (!it) healthy = false }
    }

    /** Widened Krita-style entry point: every dab owns its resolved colour and flow. */
    fun stampResolvedDabs(dabs: List<ResolvedBrushDab>, hardness: Float): Boolean {
        if (!isInitialized || dabs.isEmpty()) return false
        val flat = FloatArray(dabs.size * 10)
        for (i in dabs.indices) {
            val d = dabs[i]
            val base = i * 10
            flat[base] = d.x
            flat[base + 1] = d.y
            flat[base + 2] = d.radius
            flat[base + 3] = d.alpha
            flat[base + 4] = d.angleDeg
            flat[base + 5] = Color.red(d.colorArgb) / 255f
            flat[base + 6] = Color.green(d.colorArgb) / 255f
            flat[base + 7] = Color.blue(d.colorArgb) / 255f
            flat[base + 8] = Color.alpha(d.colorArgb) / 255f
            flat[base + 9] = d.flow
        }
        return nativeStampResolvedDabs(nativeHandle, flat, hardness).also { if (!it) healthy = false }
    }

    fun readback(bitmap: Bitmap): Boolean {
        if (!isInitialized) return false
        require(bitmap.config == Bitmap.Config.ARGB_8888) { "VulkanStampEngine.readback requires ARGB_8888, got ${bitmap.config}" }
        return nativeReadback(nativeHandle, bitmap).also { if (!it) healthy = false }
    }

    fun destroy() {
        val handle = nativeHandle
        if (handle == 0L) return
        val key = poolKey
        nativeHandle = 0L
        poolKey = null
        val mayPool = healthy && key != null && !hardwareBufferExported
        healthy = true
        hardwareBufferExported = false
        if (!mayPool) { nativeDestroy(handle); return }
        val evicted = putPooled(CachedHandle(key!!, handle))
        if (evicted != 0L) nativeDestroy(evicted)
    }

    private external fun nativeInit(width: Int, height: Int): Long
    private external fun nativeInitHardwareBuffer(width: Int, height: Int): Long
    private external fun nativeClear(handle: Long): Boolean
    private external fun nativeGetHardwareBuffer(handle: Long): HardwareBuffer?
    private external fun nativeUpload(handle: Long, inBitmap: Bitmap): Boolean
    private external fun nativeStampDabs(handle: Long, dabData: FloatArray, colorArgb: Int, hardness: Float): Boolean
    private external fun nativeStampResolvedDabs(handle: Long, dabData: FloatArray, hardness: Float): Boolean
    private external fun nativeReadback(handle: Long, outBitmap: Bitmap): Boolean
    private external fun nativeDestroy(handle: Long)
}

data class BrushDab(val x: Float, val y: Float, val radius: Float, val alpha: Float, val angleDeg: Float)

data class ResolvedBrushDab(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float,
    val angleDeg: Float,
    val colorArgb: Int,
    val flow: Float,
)
''')

# ── Regression tests for smudge sensor routing. ──────────────────────────────────────────────
write("feature/editor/src/test/java/com/hereliesaz/graffitixr/feature/editor/ColorSmudgeDynamicsTest.kt", r'''package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.azphalt.BrushParameter
import com.hereliesaz.graffitixr.common.azphalt.BrushSample
import com.hereliesaz.graffitixr.common.azphalt.BrushSensor
import com.hereliesaz.graffitixr.common.azphalt.BrushSensorBinding
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSmudgeDynamicsTest {
    private fun redBlock(width: Int, height: Int) = IntArray(width * height) { i ->
        if (i % width < width / 4) Color.RED else Color.WHITE
    }

    private fun reach(px: IntArray, width: Int, y: Int): Int {
        var out = width / 4 - 1
        for (x in width / 4 until width) {
            val p = px[y * width + x]
            if (Color.red(p) - Color.green(p) > 20) out = x
        }
        return out
    }

    @Test
    fun `pressure can drive smudge rate through shared sensor engine`() {
        val w = 64; val h = 32
        val low = redBlock(w, h); val high = redBlock(w, h)
        val points = List(36) { Offset((8 + it).toFloat(), 16f) }
        fun samples(pressure: Float) = points.mapIndexed { i, p ->
            BrushSample(p.x, p.y, uptimeMillis = i * 8L, pressure = pressure, distancePx = i.toFloat(), speedPxPerMs = 0.125f)
        }
        val settings = ColorSmudgeEngine.Settings(
            radiusPx = 6f,
            smudgeRate = 1f,
            dynamics = listOf(
                BrushSensorBinding(
                    sensor = BrushSensor.PRESSURE,
                    parameter = BrushParameter.SMUDGE_RATE,
                    outputMin = 0.05f,
                    outputMax = 1f,
                )
            ),
        )
        ColorSmudgeEngine.apply(low, w, h, points, settings, samples(0.05f), 42L)
        ColorSmudgeEngine.apply(high, w, h, points, settings, samples(1f), 42L)
        assertTrue("high pressure should carry red further", reach(high, w, 16) > reach(low, w, 16))
    }

    @Test
    fun `pressure can independently drive color rate`() {
        val w = 48; val h = 24
        val low = IntArray(w * h) { Color.WHITE }
        val high = low.copyOf()
        val points = List(24) { Offset((6 + it).toFloat(), 12f) }
        fun samples(pressure: Float) = points.mapIndexed { i, p ->
            BrushSample(p.x, p.y, uptimeMillis = i * 8L, pressure = pressure)
        }
        val settings = ColorSmudgeEngine.Settings(
            radiusPx = 5f,
            colorRate = 1f,
            paintColor = Color.GREEN,
            dynamics = listOf(
                BrushSensorBinding(
                    sensor = BrushSensor.PRESSURE,
                    parameter = BrushParameter.COLOR_RATE,
                    outputMin = 0f,
                    outputMax = 1f,
                )
            ),
        )
        ColorSmudgeEngine.apply(low, w, h, points, settings, samples(0f), 7L)
        ColorSmudgeEngine.apply(high, w, h, points, settings, samples(1f), 7L)
        val lowGreen = Color.green(low[12 * w + 20]) - Color.red(low[12 * w + 20])
        val highGreen = Color.green(high[12 * w + 20]) - Color.red(high[12 * w + 20])
        assertTrue("pressure-driven Color Rate should deposit more green", highGreen > lowGreen)
    }
}
''')

# Add a pure resolver test so enum/aggregation regressions fail in :core:common too.
test_path = "core/common/src/test/java/com/hereliesaz/graffitixr/common/azphalt/BrushSensorDynamicsTest.kt"
text = read(test_path)
insert = r'''

    @Test
    fun `color smudge parameters use the shared sensor resolver`() {
        val bindings = listOf(
            BrushSensorBinding(BrushSensor.PRESSURE, BrushParameter.SMUDGE_RATE, outputMin = 0.2f, outputMax = 1f),
            BrushSensorBinding(BrushSensor.PRESSURE, BrushParameter.COLOR_RATE, outputMin = 0f, outputMax = 0.8f),
            BrushSensorBinding(BrushSensor.PRESSURE, BrushParameter.SMUDGE_RADIUS, outputMin = 0.5f, outputMax = 2f),
        )
        val resolved = BrushSensorEngine.resolve(
            BrushSample(0f, 0f, pressure = 1f), bindings, 0L, 9L, 0,
        )
        assertEquals(1f, resolved.smudgeRateMultiplier, 0.0001f)
        assertEquals(0.8f, resolved.colorRateMultiplier, 0.0001f)
        assertEquals(2f, resolved.smudgeRadiusMultiplier, 0.0001f)
    }
'''
idx = text.rfind("}\n")
if idx < 0: raise RuntimeError("BrushSensorDynamicsTest: class close not found")
write(test_path, text[:idx] + insert + text[idx:])

print("Krita finish patch applied")
