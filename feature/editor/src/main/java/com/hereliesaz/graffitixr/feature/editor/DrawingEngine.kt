package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.SafeBitmap
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.nativebridge.ColorSmudgeDab
import com.hereliesaz.graffitixr.nativebridge.VulkanStampEngine
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor

/**
 * The stroke-compositing pipeline, extracted from EditorViewModel: turns a base bitmap plus
 * recorded [StrokeCommand]s into a rendered bitmap. This is the CPU/OpenCV-bound "how strokes
 * become pixels" logic; the ViewModel still owns when to invoke it, the dispatcher hop, the
 * UiState update, and persistence.
 *
 * Pure with respect to the editor's state (input bitmap + strokes → output bitmap); its only
 * dependencies are the OpenCV [ImageProcessor] and [SlamManager] (for Liquify warps). Callers
 * run these on a background dispatcher — they are CPU-heavy and must not touch the main thread.
 */
internal class DrawingEngine(private val slamManager: SlamManager) {

    /**
     * Replays [strokes] in order onto a fresh mutable copy of [base], returning the result.
     *
     * Throws if that first copy cannot be allocated. Returning [base] itself would be worse than
     * failing: callers publish the result as the layer's live bitmap, which would alias
     * [LayerStore]'s pristine base into the UI and let the next in-place stroke corrupt the one
     * copy every rebuild and undo depends on. Both callers already treat a thrown rebuild as
     * "leave the layer as it was", which is the correct outcome.
     */
    suspend fun composite(base: Bitmap, strokes: List<StrokeCommand>): Bitmap {
        var current = SafeBitmap.copy(base)
            ?: throw IllegalStateException("Out of memory copying a ${base.width}x${base.height} layer base")
        for (stroke in strokes) {
            val next = if (stroke.tool == Tool.LIQUIFY) applyLiquify(current, stroke)
            else applyTool(current, stroke, replaceExisting = true)
            if (next !== current && current !== base) current.recycle()
            current = next
        }
        return current
    }

    suspend fun applySingleStroke(base: Bitmap, command: StrokeCommand): Bitmap =
        if (command.tool == Tool.LIQUIFY) applyLiquify(base, command)
        else applyTool(base, command, replaceExisting = false)

    private suspend fun applyTool(bitmap: Bitmap, stroke: StrokeCommand, replaceExisting: Boolean): Bitmap {
        val clipPath = SelectionMask.bitmapPath(
            stroke.selection, bitmap.width, bitmap.height,
            stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ,
        )
        val featherRadius = SelectionMask.featherRadius(
            stroke.selection, bitmap.width, bitmap.height, stroke.layerScale,
        )
        val paintClip = SelectionMask.paintClip(clipPath, featherRadius)
        stroke.warpHandles?.let { handles ->
            val inBitmap = ImageProcessor.mapScreenToBitmap(
                handles, stroke.canvasSize.width, stroke.canvasSize.height,
                bitmap.width, bitmap.height,
                stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ,
            )
            return ImageWarp.warp(bitmap, inBitmap) ?: bitmap
        }
        if (stroke.fillSelection) {
            val target = SafeBitmap.copy(bitmap) ?: return bitmap
            val canvas = android.graphics.Canvas(target)
            SelectionMask.clip(canvas, paintClip)
            canvas.drawColor(stroke.brushColor, android.graphics.PorterDuff.Mode.SRC_OVER)
            return SelectionMask.feather(bitmap, target, clipPath, featherRadius)
        }
        if (stroke.clearAll) {
            val target = SafeBitmap.copy(bitmap) ?: return bitmap
            val canvas = android.graphics.Canvas(target)
            SelectionMask.clip(canvas, paintClip)
            canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            return SelectionMask.feather(bitmap, target, clipPath, featherRadius)
        }
        if (stroke.tool == Tool.SELECT) {
            val delta = stroke.moveDelta ?: return bitmap
            if (clipPath == null) return bitmap
            val d = SelectionMask.mapDelta(
                delta, stroke.canvasSize.width, stroke.canvasSize.height, bitmap.width, bitmap.height,
                stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ,
            )
            return SelectionMask.moveRegion(bitmap, clipPath, d.x, d.y, featherRadius)
        }
        val mapped = ImageProcessor.mapScreenToBitmap(
            stroke.path, stroke.canvasSize.width, stroke.canvasSize.height, bitmap.width, bitmap.height,
            stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ
        )
        if (stroke.tool == Tool.FILL) {
            val target = SafeBitmap.copy(bitmap) ?: return bitmap
            val p = mapped.firstOrNull() ?: return target
            ImageProcessor.floodFill(
                target, p.x.toInt(), p.y.toInt(), stroke.brushColor,
                clipRegion = SelectionMask.region(clipPath, target.width, target.height),
            )
            return SelectionMask.feather(bitmap, target, clipPath, featherRadius)
        }
        val brushScale = ImageProcessor.screenToBitmapScale(
            stroke.canvasSize.width, stroke.canvasSize.height, bitmap.width, bitmap.height, stroke.layerScale
        )
        stroke.stampBrush?.let { brush ->
            val target = SafeBitmap.copy(bitmap) ?: return bitmap
            val pts = ArrayList<Float>(mapped.size * 2)
            mapped.forEach { pts.add(it.x); pts.add(it.y) }
            val stampCanvas = android.graphics.Canvas(target)
            SelectionMask.clip(stampCanvas, paintClip)

            // Kinematics (speed/distance/time/tilt/orientation) were recorded in screen-hand space.
            // Replay remaps only x/y into this bitmap, preserving exactly what the sensor curves saw
            // when the stroke was drawn. Legacy/remote commands with no telemetry keep the old path.
            val mappedSamples = if (stroke.brushSamples.size == mapped.size) {
                stroke.brushSamples.mapIndexed { index, sample ->
                    val point = mapped[index]
                    sample.copy(x = point.x, y = point.y, predicted = false)
                }
            } else {
                emptyList()
            }
            if (brush.dynamics.isNotEmpty() && mappedSamples.isNotEmpty()) {
                StampBrushRenderer.paintDynamicStroke(
                    stampCanvas, mappedSamples, brush, stroke.brushColor,
                    stroke.brushSize * brushScale, stroke.flow, stroke.seed, stroke.stampShape,
                )
            } else {
                StampBrushRenderer.paintStroke(
                    stampCanvas, pts, brush, stroke.brushColor,
                    stroke.brushSize * brushScale, stroke.flow, stroke.seed, stroke.stampShape,
                )
            }
            return SelectionMask.feather(bitmap, target, clipPath, featherRadius)
        }

        // Color Smudge is stateful read/modify/write, not a Canvas blend. The stroke snapshots its
        // settings so changing Tool Options later cannot alter undo/redo. A null snapshot is a legacy
        // command and intentionally receives the exact old Smear/intensity preset.
        if (stroke.tool == Tool.SMUDGE) {
            val target = SafeBitmap.copy(bitmap) ?: return bitmap
            val width = target.width
            val height = target.height
            val baseSettings = stroke.colorSmudgeSettings ?: ColorSmudgeEngine.Settings(
                mode = ColorSmudgeEngine.Mode.SMEAR,
                smudgeRate = 0.35f + stroke.intensity.coerceIn(0f, 1f) * 0.6f,
                colorRate = 0f,
                opacity = 1f,
                smearAlpha = true,
            )
            val mappedSamples = if (stroke.brushSamples.size == mapped.size) {
                stroke.brushSamples.mapIndexed { index, sample ->
                    val point = mapped[index]
                    sample.copy(x = point.x, y = point.y, predicted = false)
                }
            } else emptyList()
            val settings = baseSettings.copy(
                radiusPx = (stroke.brushSize * brushScale / 2f).coerceAtLeast(1f),
                feathering = stroke.feathering,
                wrapAround = false,
                paintColor = stroke.brushColor,
                symmetryMode = stroke.symmetryMode,
            )
            val plans = ColorSmudgeEngine.resolvePlans(
                mapped, width, height, settings, mappedSamples, stroke.seed,
            )

            // Correctness-first Vulkan path: one upload, all ordered read/modify/write plans stay on
            // the persistent layer image, one readback. If Vulkan is unavailable or any stage fails,
            // discard the possibly-partial target and recompute from the pristine CPU source below.
            val gpuPainted = runCatching {
                val engine = VulkanStampEngine()
                try {
                    if (!engine.init(width, height) || !engine.upload(target)) return@runCatching false
                    val mode = if (settings.mode == ColorSmudgeEngine.Mode.SMEAR) 0 else 1
                    for (plan in plans) {
                        if (plan.dabs.size < 2) continue
                        val nativeDabs = plan.dabs.map { dab ->
                            ColorSmudgeDab(
                                dab.x, dab.y, dab.smudgeRate, dab.colorRate,
                                dab.opacity, dab.smudgeRadius,
                            )
                        }
                        if (!engine.colorSmudge(
                                nativeDabs, mode, settings.radiusPx, settings.feathering,
                                settings.smearAlpha, settings.paintColor,
                            )) return@runCatching false
                    }
                    engine.readback(target)
                } finally {
                    engine.destroy()
                }
            }.getOrDefault(false)

            if (!gpuPainted) {
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                ColorSmudgeEngine.apply(
                    pixels, width, height, mapped, settings,
                    samples = mappedSamples, strokeSeed = stroke.seed,
                )
                target.setPixels(pixels, 0, width, 0, 0, width, height)
            }
            return SelectionMask.confine(bitmap, target, clipPath, featherRadius)
        }

        return SelectionMask.feather(
            bitmap,
            ImageProcessor.applyToolToBitmap(
                bitmap, mapped, stroke.tool, stroke.brushSize * brushScale, stroke.brushColor, stroke.intensity,
                replaceExisting && featherRadius <= 0f, stroke.feathering,
                alphaLock = stroke.alphaLock, symmetryMode = stroke.symmetryMode,
                clipPath = paintClip,
                opacity = stroke.opacity,
                pressures = stroke.pressures,
                cloneOffset = stroke.cloneOffset?.let { off ->
                    SelectionMask.mapDelta(
                        off, stroke.canvasSize.width, stroke.canvasSize.height,
                        bitmap.width, bitmap.height,
                        stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ,
                    )
                },
            ),
            clipPath, featherRadius,
        )
    }

    private suspend fun applyLiquify(bitmap: Bitmap, stroke: StrokeCommand): Bitmap {
        slamManager.prepareLiquify(bitmap)
        val mapped = ImageProcessor.mapScreenToBitmap(
            stroke.path, stroke.canvasSize.width, stroke.canvasSize.height, bitmap.width, bitmap.height,
            stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ
        )
        val flatArr = FloatArray(mapped.size * 2)
        mapped.forEachIndexed { i, pt -> flatArr[i * 2] = pt.x; flatArr[i * 2 + 1] = pt.y }
        slamManager.applyLiquify(flatArr, stroke.brushSize, 0.5f)
        val baked = SafeBitmap.copy(bitmap) ?: return bitmap
        slamManager.bakeLiquify(baked)
        val clipPath = SelectionMask.bitmapPath(
            stroke.selection, bitmap.width, bitmap.height,
            stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ,
        )
        val featherRadius = SelectionMask.featherRadius(
            stroke.selection, bitmap.width, bitmap.height, stroke.layerScale,
        )
        return SelectionMask.confine(bitmap, baked, clipPath, featherRadius)
    }
}