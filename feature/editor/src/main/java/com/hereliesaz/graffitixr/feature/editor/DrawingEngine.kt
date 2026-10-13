package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.azphalt.BrushStamps
import com.hereliesaz.graffitixr.common.azphalt.Dab
import com.hereliesaz.graffitixr.common.azphalt.DirtyRegion
import com.hereliesaz.graffitixr.common.azphalt.ImpastoEngine
import com.hereliesaz.graffitixr.common.azphalt.ImpastoRegionShader
import com.hereliesaz.graffitixr.common.azphalt.ImpastoMaterialStrokeState
import com.hereliesaz.graffitixr.common.azphalt.PersistentWetnessField
import com.hereliesaz.graffitixr.common.azphalt.SubstrateProfile
import com.hereliesaz.graffitixr.common.model.CatmullRom
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerFrame
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.SafeBitmap
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.nativebridge.GpuDeviceLoss
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine
import com.hereliesaz.graffitixr.feature.editor.export.ExportManager
import com.hereliesaz.graffitixr.feature.editor.ink.InkAffine
import com.hereliesaz.graffitixr.feature.editor.ink.InkGpuRaster
import com.hereliesaz.graffitixr.feature.editor.ink.InkStrokes
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
    @Suppress("LongParameterList")
    suspend fun composite(
        base: Bitmap,
        strokes: List<StrokeCommand>,
        otherLayers: () -> List<Layer> = { emptyList() },
        heightMap: FloatArray? = null,
        wetnessState: WetnessReplayState? = null,
        impastoMaterialState: ImpastoMaterialReplayState? = null,
        /** Where [base] sits in the layer's frame; null = the bitmap is the frame. */
        frame: LayerFrame? = null,
    ): Bitmap {
        var current = SafeBitmap.copy(base)
            ?: throw IllegalStateException("Out of memory copying a ${base.width}x${base.height} layer base")
        // Consecutive plain GPU strokes share one engine (GpuReplaySession). Impasto's per-stroke raw
        // colour capture reads every result on the CPU, so it gains nothing there.
        val session = if (impastoMaterialState == null) GpuReplaySession() else null
        try {
            for (stroke in strokes) {
                val replay = session?.takeIf { !it.broken && replaysOnSession(stroke, heightMap) }
                if (replay == null) current = synced(session, current, otherLayers, heightMap, frame)
                var next = if (stroke.tool == Tool.LIQUIFY) applyLiquify(current, stroke, frame)
                else applyTool(
                    current, stroke, otherLayers = otherLayers,
                    heightMap = heightMap, wetnessState = wetnessState,
                    impastoMaterialState = impastoMaterialState, replay = replay, frame = frame,
                )
                if (replay != null && replay.broken) {
                    // The GPU failed: repaint what it held, then this stroke, on the CPU path.
                    current = synced(session, current, otherLayers, heightMap, frame)
                    next = applyTool(
                        current, stroke, otherLayers = otherLayers,
                        heightMap = heightMap, wetnessState = wetnessState, frame = frame,
                    )
                }
                if (impastoMaterialState != null && !strokeUsesImpastoV2(stroke)) {
                    impastoMaterialState.replaceRawColor(bitmapPixels(next))
                }
                if (next !== current && current !== base) current.recycle()
                current = next
            }
            return synced(session, current, otherLayers, heightMap, frame)
        } finally {
            session?.close()
        }
    }

    /**
     * [current] with the GPU replay's pending strokes landed in it: read back, or, when the GPU
     * failed, repainted on the CPU path over its pixels from before them.
     */
    private suspend fun synced(
        session: GpuReplaySession?,
        current: Bitmap,
        otherLayers: () -> List<Layer>,
        heightMap: FloatArray?,
        frame: LayerFrame?,
    ): Bitmap {
        if (session == null || session.flush()) return current
        var out = current
        for (lost in session.takeLost()) {
            val next = applyTool(out, lost, otherLayers = otherLayers, heightMap = heightMap, frame = frame)
            if (next !== out && out !== current) out.recycle()
            out = next
        }
        return out
    }


    @Suppress("LongParameterList")
    suspend fun applySingleStroke(
        base: Bitmap,
        command: StrokeCommand,
        otherLayers: List<Layer> = emptyList(),
        heightMap: FloatArray? = null,
        wetnessState: WetnessReplayState? = null,
        impastoMaterialState: ImpastoMaterialReplayState? = null,
        /** An Impasto v2 stroke's live engine, checkpointed at this material state. */
        liveWet: LiveWetEngine? = null,
        /** Where [base] sits in the layer's frame; null = the bitmap is the frame. */
        frame: LayerFrame? = null,
    ): Bitmap {
        val epoch = GpuDeviceLoss.epoch()
        var result = if (command.tool == Tool.LIQUIFY) applyLiquify(base, command, frame)
        else applyTool(
            base, command, otherLayers = { otherLayers },
            heightMap = heightMap, wetnessState = wetnessState,
            impastoMaterialState = impastoMaterialState, liveWet = liveWet, frame = frame,
        )
        // A GPU stroke that failed because the device was lost (a failed GPU call leaves the layer
        // as it was) paints once more on a fresh engine; the live engine died with the device.
        if (result === base && command.tool != Tool.LIQUIFY && GpuDeviceLoss.since(epoch)) {
            result = applyTool(
                base, command, otherLayers = { otherLayers },
                heightMap = heightMap, wetnessState = wetnessState,
                impastoMaterialState = impastoMaterialState, frame = frame,
            )
        }
        if (impastoMaterialState != null && !strokeUsesImpastoV2(command)) {
            impastoMaterialState.replaceRawColor(bitmapPixels(result))
        }
        return result
    }

    private fun strokeUsesImpastoV2(stroke: StrokeCommand): Boolean =
        stroke.tool == Tool.BRUSH &&
            stroke.stampBrush?.let { brush ->
                brush.impastoThicknessRate > 0f && brush.impastoMaterial.sanitized().usesV2
            } == true

    @Suppress("LongParameterList")
    private suspend fun applyTool(
        bitmap: Bitmap,
        stroke: StrokeCommand,
        otherLayers: () -> List<Layer> = { emptyList() },
        heightMap: FloatArray? = null,
        wetnessState: WetnessReplayState? = null,
        impastoMaterialState: ImpastoMaterialReplayState? = null,
        replay: GpuReplaySession? = null,
        liveWet: LiveWetEngine? = null,
        frame: LayerFrame? = null,
    ): Bitmap {
        // Where [bitmap] sits in the layer's frame; every screen-space record maps through it.
        val lf = frame ?: LayerFrame.of(bitmap.width, bitmap.height)
        val clipPath = SelectionMask.bitmapPath(
            stroke.selection, lf, stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ,
        )
        val featherRadius = SelectionMask.featherRadius(
            stroke.selection, lf.width, lf.height, stroke.layerScale,
        )
        val paintClip = SelectionMask.paintClip(clipPath, featherRadius)
        stroke.warpHandles?.let { handles ->
            val inBitmap = ImageProcessor.mapScreenToBitmap(
                handles, stroke.canvasSize.width, stroke.canvasSize.height, lf,
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
                delta, stroke.canvasSize.width, stroke.canvasSize.height, lf.width, lf.height,
                stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ,
            )
            return SelectionMask.moveRegion(bitmap, clipPath, d.x, d.y, featherRadius)
        }
        val mapped = ImageProcessor.mapScreenToBitmap(
            stroke.path, stroke.canvasSize.width, stroke.canvasSize.height, lf,
            stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ,
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
            stroke.canvasSize.width, stroke.canvasSize.height, lf, stroke.layerScale,
        )
        // Jetpack Ink utensil (Ink Pen, Marker, ...): the command carries the finished Ink
        // stroke, which is rendered as-is through Ink's own CanvasStrokeRenderer on the hardware
        // canvas (InkGpuRaster) — the pixels
        // committed and replayed are Ink's, matching what its InProgressStrokesView showed live.
        // Everything around the paint is the editor's usual discipline: the selection clip (and
        // its feather), alpha lock and wrap-around, all read off the recorded command.
        stroke.inkStroke?.let { ink ->
            val worldToBitmap = InkAffine.worldToBitmap(
                stroke.canvasSize.width, stroke.canvasSize.height, lf.width, lf.height,
                stroke.layerScale, stroke.layerOffset.x, stroke.layerOffset.y, stroke.layerRotationZ,
                lf.originX, lf.originY,
            )
            // On Android's hardware canvas (InkGpuRaster): the layer, then the stroke clipped to the
            // selection. A failed render leaves the layer as it was.
            val painted = InkGpuRaster.render(bitmap.width, bitmap.height) { canvas ->
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                val saved = canvas.save()
                SelectionMask.clip(canvas, paintClip)
                InkStrokes.draw(canvas, ink, worldToBitmap, stroke.alphaLock, stroke.wrapAroundMode)
                canvas.restoreToCount(saved)
            } ?: return bitmap
            return SelectionMask.feather(bitmap, painted, clipPath, featherRadius)
        }
        stroke.stampBrush?.let { brush ->
            // The GPU paints every stamp stroke: Impasto v1 (GpuImpastoStroke: colour, height deposit
            // and relief in one session; under a selection the height deposits wherever the dabs
            // reach and the lit colour is kept inside it), Impasto v2 (GpuImpastoV2Stroke: the wet
            // material simulation, selection included) and the plain stamp (GpuStampCommit, or the
            // replay's shared session). There is no CPU renderer: a failed GPU call leaves the
            // layer as it was.
            val impasto = heightMap?.takeIf {
                brush.impastoThicknessRate > 0f && it.size == bitmap.width * bitmap.height
            }
            if (impasto != null && brush.impastoMaterial.sanitized().usesV2) {
                return GpuImpastoV2Stroke.paintStroke(
                    bitmap, stroke, brush, mapped, brushScale,
                    ImpastoV2Layer(impasto, wetnessState, impastoMaterialState, liveWet),
                    StrokeSelection(clipPath, featherRadius),
                ) ?: bitmap
            }
            val painted = when {
                impasto != null -> GpuImpastoStroke.paintStroke(bitmap, stroke, brush, mapped, brushScale, impasto)
                replay != null -> return replay.paint(bitmap, stroke) {
                    GpuStampCommit.paintOnto(it, stroke, brush, mapped, brushScale, bitmap)
                }
                else -> GpuStampCommit.paintStroke(bitmap, stroke, brush, mapped, brushScale)
            } ?: return bitmap
            return SelectionMask.feather(
                bitmap, GpuStampCommit.keepInside(bitmap, painted, paintClip), clipPath, featherRadius,
            )
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
            )
            val plans = ColorSmudgeEngine.resolvePlans(
                mapped, width, height, settings, mappedSamples, stroke.seed,
            )
            val persistentWetness = wetnessState?.takeIf {
                it.field.width == width && it.field.height == height &&
                    (ColorSmudgeEngine.usesPersistentWetness(settings) || !it.field.isIdle)
            }
            val wetnessRegion = persistentWetness?.let {
                SelectionMask.region(clipPath, width, height)
            }

            // Sample Merged: composite the other visible layers into this layer's own pixel space
            // (exact resolution match, required by both ColorSmudgeEngine.apply's sampleSource
            // contract and GpuStampEngine.colorSmudge's sampleSource contract) so pickup reads
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

            // GPU (wgpu) path: one upload, every ordered read/modify/write plan on the layer image,
            // one readback. Native modes 0/1 are the historical RGB Smear/Dulling paths; 2/3 select the
            // exact RYB material mixer; reservoir load/pickup runs in the same pipeline. Persistent
            // wetness runs on the wet simulation of the same engine (GpuWetSmudge): material advance,
            // the smudge on the canvas wetness, the selection confined on the GPU, the settle tick.
            fun smudgeOn(engine: GpuStampEngine) =
                GpuColorSmudge.stamp(engine, plans, settings, sampleSource, width, height)
            val wetSelection = stroke.selection?.takeIf { clipPath != null }?.let { selection ->
                GpuWetSmudge.Selection { engine ->
                    GpuStrokeSelection.prepare(
                        engine, selection, width, height, lf,
                        stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ,
                    )
                }
            }
            val gpuPainted = runCatching {
                com.hereliesaz.graffitixr.feature.editor.gpu.GpuTuningController.stopCalibrationForCanvas()
                val engine = GpuStampEngine()
                try {
                    engine.init(width, height) && engine.upload(target) && if (persistentWetness != null) {
                        GpuWetSmudge.run(
                            engine, target, persistentWetness,
                            mappedSamples.firstOrNull()?.uptimeMillis, mappedSamples.lastOrNull()?.uptimeMillis,
                            wetSelection, wetnessCoverage(wetnessRegion, width, height),
                        ) { smudgeOn(engine) }
                    } else {
                        smudgeOn(engine)
                    } && engine.readback(target)
                } finally {
                    engine.destroy()
                }
            }.getOrDefault(false)
            // No CPU renderer: a failed GPU stage leaves the layer as it was. The wet path confined to
            // the selection on the GPU, before its settle tick.
            return when {
                !gpuPainted -> bitmap.also { target.recycle() }
                persistentWetness != null -> target
                else -> SelectionMask.confine(bitmap, target, clipPath, featherRadius)
            }
        }

        // Basic Brush and Eraser paint on the GPU, the same dab runs the live stroke stamped
        // (GpuBasicStroke); alpha lock is the GPU's SRC_ATOP. No CPU renderer: a failed GPU call
        // leaves the layer as it was.
        if (replay != null) {
            return replay.paint(bitmap, stroke) { GpuBasicStroke.paintOnto(it, stroke, mapped, brushScale, bitmap) }
        }
        if (stroke.tool == Tool.BRUSH || stroke.tool == Tool.ERASER) {
            val painted = GpuBasicStroke.paintStroke(bitmap, stroke, mapped, brushScale) ?: return bitmap
            return SelectionMask.feather(
                bitmap, GpuStampCommit.keepInside(bitmap, painted, paintClip), clipPath, featherRadius,
            )
        }
        // The retouch tools paint on the GPU too (GpuRetouchStroke); a failed GPU call leaves the
        // layer as it was. Any other tool paints nothing.
        if (stroke.tool !in GpuRetouchStroke.TOOLS) return bitmap
        val cloneOffset = stroke.cloneOffset?.let { off ->
            SelectionMask.mapDelta(
                off, stroke.canvasSize.width, stroke.canvasSize.height, lf.width, lf.height,
                stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ,
            )
        }
        val painted = GpuRetouchStroke.paintStroke(bitmap, stroke, mapped, brushScale, cloneOffset) ?: return bitmap
        return SelectionMask.feather(
            bitmap, GpuStampCommit.keepInside(bitmap, painted, paintClip), clipPath, featherRadius,
        )
    }

    private suspend fun applyLiquify(bitmap: Bitmap, stroke: StrokeCommand, frame: LayerFrame? = null): Bitmap {
        val lf = frame ?: LayerFrame.of(bitmap.width, bitmap.height)
        slamManager.prepareLiquify(bitmap)
        val mapped = ImageProcessor.mapScreenToBitmap(
            stroke.path, stroke.canvasSize.width, stroke.canvasSize.height, lf,
            stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ,
        )
        val flatArr = FloatArray(mapped.size * 2)
        mapped.forEachIndexed { i, pt -> flatArr[i * 2] = pt.x; flatArr[i * 2 + 1] = pt.y }
        slamManager.applyLiquify(flatArr, stroke.brushSize, 0.5f)
        val baked = SafeBitmap.copy(bitmap) ?: return bitmap
        slamManager.bakeLiquify(baked)
        val clipPath = SelectionMask.bitmapPath(
            stroke.selection, lf, stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ,
        )
        val featherRadius = SelectionMask.featherRadius(
            stroke.selection, lf.width, lf.height, stroke.layerScale,
        )
        return SelectionMask.confine(bitmap, baked, clipPath, featherRadius)
    }
}

/**
 * Whether [stroke] paints only through GpuReplaySession: a plain stamp brush, Basic Brush (alpha
 * lock included) or Eraser stroke, no selection, Impasto, or tool that reads the pixels
 * on the CPU.
 */
private fun replaysOnSession(
    stroke: StrokeCommand,
    heightMap: FloatArray?,
) =
    stroke.selection == null && stroke.warpHandles == null && !stroke.fillSelection && !stroke.clearAll &&
        stroke.inkStroke == null &&
        (stroke.tool == Tool.ERASER || stroke.tool == Tool.BRUSH && replaysStamp(stroke, heightMap))

private fun replaysStamp(stroke: StrokeCommand, heightMap: FloatArray?) =
    stroke.stampBrush?.let { !(it.impastoThicknessRate > 0f && heightMap != null) } ?: true

private fun bitmapPixels(bitmap: Bitmap): IntArray =
    IntArray(bitmap.width * bitmap.height).also { pixels ->
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

/** Where a wet smudge may deposit wetness: 1 inside the selection's [region], 0 outside; null = all. */
private fun wetnessCoverage(region: android.graphics.Region?, width: Int, height: Int): FloatArray? =
    region?.let { r -> FloatArray(width * height) { i -> if (r.contains(i % width, i / width)) 1f else 0f } }
