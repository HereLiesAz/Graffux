package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.azphalt.AirbrushEngine
import com.hereliesaz.graffitixr.common.azphalt.BrushStamps
import com.hereliesaz.graffitixr.common.azphalt.Dab
import com.hereliesaz.graffitixr.common.azphalt.ImpastoEngine
import com.hereliesaz.graffitixr.common.model.CatmullRom
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.SafeBitmap
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.nativebridge.ColorSmudgeDab
import com.hereliesaz.graffitixr.nativebridge.VulkanStampEngine
import com.hereliesaz.graffitixr.feature.editor.export.ExportManager
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor

/** Fixed top-left bevel light for Impasto shading (roadmap item 12) -- not yet user-adjustable;
 *  see [DrawingEngine]'s stamp-brush branch for where it's applied. */
// internal, not private: EditorViewModel's live-preview Impasto shading (item 12) must use the
// exact same light so the preview and the commit never diverge.
internal const val IMPASTO_LIGHT_AZIMUTH_DEG = 315f
internal const val IMPASTO_LIGHT_ELEVATION_DEG = 45f
internal const val IMPASTO_LIGHT_STRENGTH = 0.6f

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
internal class DrawingEngine(
    private val slamManager: SlamManager,
    private val exportManager: ExportManager = ExportManager(),
) {

    /**
     * Replays [strokes] in order onto a fresh mutable copy of [base], returning the result.
     *
     * Throws if that first copy cannot be allocated. Returning [base] itself would be worse than
     * failing: callers publish the result as the layer's live bitmap, which would alias
     * [LayerStore]'s pristine base into the UI and let the next in-place stroke corrupt the one
     * copy every rebuild and undo depends on. Both callers already treat a thrown rebuild as
     * "leave the layer as it was", which is the correct outcome.
     *
     * [otherLayers] is read fresh for every stroke in the list (not snapshotted once) since it
     * only feeds Color Smudge's "Sample Merged" (item 11): the *current* state of the other
     * layers, matching what a live composite would show, is what Krita's own Sample Merged reads,
     * and undo/redo replay through this same function should agree with a live draw at the same
     * document state rather than resurrecting whatever those layers looked like when the stroke
     * was first recorded.
     *
     * [heightMap], when supplied, must already be sized `base.width * base.height` and is
     * mutated in place as stamp-brush strokes with a positive `impastoThicknessRate` (roadmap
     * item 12) are replayed — a fresh copy of a layer's height base, so callers can discard it on
     * failure exactly like [base] itself. `null` means Impasto is off for this call; it is never
     * allocated on the caller's behalf, since most strokes never touch it.
     */
    suspend fun composite(
        base: Bitmap,
        strokes: List<StrokeCommand>,
        otherLayers: () -> List<Layer> = { emptyList() },
        heightMap: FloatArray? = null,
    ): Bitmap {
        var current = SafeBitmap.copy(base)
            ?: throw IllegalStateException("Out of memory copying a ${base.width}x${base.height} layer base")
        for (stroke in strokes) {
            val next = if (stroke.tool == Tool.LIQUIFY) applyLiquify(current, stroke)
            else applyTool(current, stroke, replaceExisting = true, otherLayers = otherLayers, heightMap = heightMap)
            if (next !== current && current !== base) current.recycle()
            current = next
        }
        return current
    }

    suspend fun applySingleStroke(
        base: Bitmap,
        command: StrokeCommand,
        otherLayers: List<Layer> = emptyList(),
        heightMap: FloatArray? = null,
    ): Bitmap =
        if (command.tool == Tool.LIQUIFY) applyLiquify(base, command)
        else applyTool(base, command, replaceExisting = false, otherLayers = { otherLayers }, heightMap = heightMap)

    private suspend fun applyTool(
        bitmap: Bitmap,
        stroke: StrokeCommand,
        replaceExisting: Boolean,
        otherLayers: () -> List<Layer> = { emptyList() },
        heightMap: FloatArray? = null,
    ): Bitmap {
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
            val paintedDabs: List<Dab>
            if (brush.dynamics.isNotEmpty() && mappedSamples.isNotEmpty()) {
                // Airbrush (roadmap item 13): this is the commit/replay render. EditorViewModel's
                // live incremental preview has its own, separate integration (tracked by
                // `stampHeldStampedCount`) that computes the same heldDabs while dragging, so the
                // build-up is visible during the drag, not just once committed -- the two call
                // sites are seeded from the same recorded timestamps so they agree. heldDabs needs
                // real recorded timestamps, so it only applies in this telemetry-present branch,
                // same as sensor dynamics above it.
                val diameterPx = stroke.brushSize * brushScale
                val movementDabs = BrushStamps.dynamicDabs(mappedSamples, diameterPx, brush, stroke.seed)
                val allDabs = if (brush.airbrushDabsPerSecond > 0f) {
                    movementDabs + AirbrushEngine.heldDabs(
                        mappedSamples, diameterPx, brush, brush.airbrushDabsPerSecond,
                        brush.airbrushStillnessRadiusPx, stroke.seed,
                    )
                } else {
                    movementDabs
                }
                StampBrushRenderer.paintDabs(
                    stampCanvas, allDabs, brush, stroke.brushColor, stroke.flow,
                    stroke.stampShape, stroke.stampGrain, stroke.stampMaskShape, stroke.seed,
                    stroke.secondaryBrushColor,
                )
                paintedDabs = allDabs
            } else {
                StampBrushRenderer.paintStroke(
                    stampCanvas, pts, brush, stroke.brushColor,
                    stroke.brushSize * brushScale, stroke.flow, stroke.seed,
                    stroke.stampShape, stroke.stampGrain, stroke.stampMaskShape,
                    stroke.secondaryBrushColor,
                )
                // Only materialized when Impasto is actually active (see below) -- paintStroke
                // already resolves the identical dab list internally (via the same
                // CatmullRom.densify + BrushStamps.dabs path) to paint, so this is redundant work
                // only when a stamp brush has impastoThicknessRate > 0, never on the historical
                // path every other stamp brush still takes.
                paintedDabs = if (brush.impastoThicknessRate > 0f && heightMap != null) {
                    BrushStamps.dabs(CatmullRom.densify(pts), stroke.brushSize * brushScale, brush, stroke.seed)
                } else {
                    emptyList()
                }
            }

            // Impasto (roadmap item 12): raises the layer's persistent height map under this
            // stroke's dabs, then re-shades the just-painted pixels against it. This is the
            // commit/replay render, which is what actually persists the height-map contribution
            // onto the layer; EditorViewModel's live preview has its own separate, regional-reshade
            // integration (`stampLiveHeightMap`) so the shading is visible while dragging too.
            if (brush.impastoThicknessRate > 0f && heightMap != null && heightMap.size == target.width * target.height) {
                ImpastoEngine.depositStroke(
                    heightMap, target.width, target.height, paintedDabs, brush.hardness, brush.impastoThicknessRate,
                )
                val colorPixels = IntArray(target.width * target.height)
                target.getPixels(colorPixels, 0, target.width, 0, 0, target.width, target.height)
                val shaded = ImpastoEngine.shade(
                    colorPixels, heightMap, target.width, target.height,
                    IMPASTO_LIGHT_AZIMUTH_DEG, IMPASTO_LIGHT_ELEVATION_DEG, IMPASTO_LIGHT_STRENGTH,
                )
                target.setPixels(shaded, 0, target.width, 0, 0, target.width, target.height)
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

            // Sample Merged: composite the other visible layers into this layer's own pixel space
            // (exact resolution match, required by both ColorSmudgeEngine.apply's sampleSource
            // contract and VulkanStampEngine.colorSmudge's sampleSource contract) so pickup reads
            // what's actually visible underneath/around this layer instead of only this layer's own
            // paint. A mismatched/empty result degrades safely to each path's own single-layer
            // fallback. Computed once, ahead of the GPU attempt below, so the GPU and CPU paths read
            // the identical composite rather than risking two different other-layer snapshots.
            val sampleSource = if (settings.sampleMerged) {
                val others = otherLayers()
                if (others.isNotEmpty()) {
                    val composite = exportManager.compositeOtherLayersForSampling(
                        bitmap, stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ,
                        others, stroke.canvasSize.width, stroke.canvasSize.height,
                    )
                    val src = IntArray(width * height)
                    composite.getPixels(src, 0, width, 0, 0, width, height)
                    composite.recycle()
                    src
                } else null
            } else null

            // Correctness-first Vulkan path: one upload, all ordered read/modify/write plans stay on
            // the persistent layer image, one readback. If Vulkan is unavailable or any stage fails,
            // discard the possibly-partial target and recompute from the pristine CPU source below.
            //
            // Dilution and chargeDecayRate both need no gate: chargeDecayRate is already folded into
            // the per-dab colorRate by resolve() before reaching either path, and dilution's GPU
            // mix now reads the same in-shader source (the destination pixel for Smear, the weighted-
            // average carrier for Dulling) ColorSmudgeEngine.dilutedPigment() reads on the CPU for the
            // non-Sample-Merged case -- see color_smudge.comp's dilutedPigment().
            // Sample Merged (item 11) no longer needs a gate either: VulkanColorSmudge now carries a
            // second sampled RGBA texture (color_smudge.comp's sampleSourceTex) that `sampleSource`
            // seeds once per call, and every dab's pickup reads from it instead of the layer image
            // when supplied -- see color_smudge.comp's `pickedUp`/`under` split, the GPU counterpart
            // to ColorSmudgeEngine's `readSource`/`pixels` split.
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
                                settings.smearAlpha, settings.paintColor, settings.dilution,
                                sampleSource = sampleSource,
                                sampleSourceWidth = width, sampleSourceHeight = height,
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
                    sampleSource = sampleSource,
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
                wrapAroundMode = stroke.wrapAroundMode,
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