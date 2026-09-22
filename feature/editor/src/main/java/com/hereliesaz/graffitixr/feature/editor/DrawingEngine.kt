package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.azphalt.AirbrushEngine
import com.hereliesaz.graffitixr.common.azphalt.BrushStamps
import com.hereliesaz.graffitixr.common.azphalt.Dab
import com.hereliesaz.graffitixr.common.azphalt.DirtyRegion
import com.hereliesaz.graffitixr.common.azphalt.ImpastoEngine
import com.hereliesaz.graffitixr.common.azphalt.ImpastoRegionShader
import com.hereliesaz.graffitixr.common.azphalt.MaterialMixingModel
import com.hereliesaz.graffitixr.common.azphalt.ImpastoMaterialStrokeState
import com.hereliesaz.graffitixr.common.azphalt.PersistentWetnessField
import com.hereliesaz.graffitixr.common.azphalt.SubstrateProfile
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
        substrate: SubstrateRenderContext? = null,
        wetnessState: WetnessReplayState? = null,
        impastoMaterialState: ImpastoMaterialReplayState? = null,
    ): Bitmap {
        var current = SafeBitmap.copy(base)
            ?: throw IllegalStateException("Out of memory copying a ${base.width}x${base.height} layer base")
        for (stroke in strokes) {
            val next = if (stroke.tool == Tool.LIQUIFY) applyLiquify(current, stroke)
            else applyTool(
                current, stroke, replaceExisting = true, otherLayers = otherLayers,
                heightMap = heightMap, substrate = substrate, wetnessState = wetnessState,
                impastoMaterialState = impastoMaterialState,
            )
            if (impastoMaterialState != null && !strokeUsesImpastoV2(stroke)) {
                impastoMaterialState.replaceRawColor(bitmapPixels(next))
            }
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
        substrate: SubstrateRenderContext? = null,
        wetnessState: WetnessReplayState? = null,
        impastoMaterialState: ImpastoMaterialReplayState? = null,
    ): Bitmap {
        val result = if (command.tool == Tool.LIQUIFY) applyLiquify(base, command)
        else applyTool(
            base, command, replaceExisting = false, otherLayers = { otherLayers },
            heightMap = heightMap, substrate = substrate, wetnessState = wetnessState,
            impastoMaterialState = impastoMaterialState,
        )
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

    private fun bitmapPixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also { pixels ->
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    private suspend fun applyTool(
        bitmap: Bitmap,
        stroke: StrokeCommand,
        replaceExisting: Boolean,
        otherLayers: () -> List<Layer> = { emptyList() },
        heightMap: FloatArray? = null,
        substrate: SubstrateRenderContext? = null,
        wetnessState: WetnessReplayState? = null,
        impastoMaterialState: ImpastoMaterialReplayState? = null,
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
            val materialConfig = brush.impastoMaterial.sanitized()
            val usesImpastoV2 = materialConfig.usesV2 &&
                brush.impastoThicknessRate > 0f &&
                heightMap != null &&
                heightMap.size == target.width * target.height
            val materialMedium = materialConfig.toMedium()
            val materialWetness = wetnessState?.takeIf {
                it.field.width == target.width && it.field.height == target.height
            }
            val materialState = if (usesImpastoV2) {
                impastoMaterialState?.takeIf {
                    it.width == target.width && it.height == target.height
                } ?: run {
                    val seed = IntArray(target.width * target.height)
                    bitmap.getPixels(seed, 0, target.width, 0, 0, target.width, target.height)
                    ImpastoMaterialReplayState.fromRaw(target.width, target.height, seed)
                }
            } else {
                null
            }

            // A feathered lasso is a coverage field, not a binary Region. Resolve it once for this
            // authoritative stroke and reuse the same alpha for pigment, height, and wetness.
            val materialSelection = if (usesImpastoV2 && featherRadius <= 0f) {
                SelectionMask.region(clipPath, target.width, target.height)
            } else {
                null
            }
            val materialAllowed: ((Int, Int) -> Boolean)? = materialSelection?.let { region ->
                { x, y -> region.contains(x, y) }
            }
            val materialFeatherPixels = if (usesImpastoV2 && clipPath != null && featherRadius > 0f) {
                SelectionMask.featherMask(clipPath, target.width, target.height, featherRadius)?.let { mask ->
                    val pixels = IntArray(target.width * target.height)
                    mask.getPixels(pixels, 0, target.width, 0, 0, target.width, target.height)
                    mask.recycle()
                    pixels
                }
            } else {
                null
            }
            val materialCoverage: ((Int, Int) -> Float)? = materialFeatherPixels?.let { alpha ->
                { x, y -> (alpha[y * target.width + x] ushr 24 and 0xFF) / 255f }
            }

            // V2 always evolves/paints canonical unlit pigment. Display lighting is reconstructed
            // afterwards from raw pigment + height + wetness and never feeds back into paint.
            if (usesImpastoV2 && materialState != null) {
                target.setPixels(
                    materialState.rawColor, 0, target.width, 0, 0, target.width, target.height,
                )
            }

            // Advance already-wet material to this stroke's first recorded input time before new
            // contact lands. Height leveling is active-tile bounded; Phase-4 advances colour and
            // wetness through the same recorded interval. No wall clock enters canonical replay.
            var preContactMaterialRegion: DirtyRegion? = null
            if (usesImpastoV2 && materialWetness != null && !materialWetness.field.isIdle) {
                val nextTime = mappedSamples.firstOrNull()?.uptimeMillis
                val previousTime = materialWetness.lastUptimeMillis
                if (nextTime != null && previousTime != null && nextTime > previousTime) {
                    preContactMaterialRegion = activeWetnessBounds(materialWetness.field)
                    ImpastoEngine.levelWetHeight(
                        height = requireNotNull(heightMap),
                        width = target.width,
                        imgHeight = target.height,
                        wetness = materialWetness.field,
                        medium = materialMedium,
                        deltaSeconds = (nextTime - previousTime) / 1000f,
                        region = preContactMaterialRegion,
                        substrateProfile = substrate?.profile ?: SubstrateProfile.SMOOTH,
                        substrateField = substrate?.field,
                        mediumAt = materialState?.let { state ->
                            { x, y -> state.mediumAt(x, y, materialMedium) }
                        },
                    )
                }
                val materialPixels = IntArray(target.width * target.height)
                target.getPixels(materialPixels, 0, target.width, 0, 0, target.width, target.height)
                materialWetness.advanceMaterialTo(
                    materialPixels,
                    nextTime,
                    dryingRate = materialMedium.dryingRate,
                    dryingRateAt = materialState?.let { state ->
                        { x, y -> state.mediumAt(x, y, materialMedium).dryingRate }
                    },
                )
                target.setPixels(materialPixels, 0, target.width, 0, 0, target.width, target.height)
            }

            val rawBeforeContact = if (usesImpastoV2 && materialState != null) {
                val pixels = IntArray(target.width * target.height)
                target.getPixels(pixels, 0, target.width, 0, 0, target.width, target.height)
                pixels
            } else {
                null
            }

            val paintedDabs: List<Dab>
            // Mirrors dynamicDabs()'s own gate: contact mechanics are themselves stateful telemetry
            // consumers, so a mechanics-only brush must never fall back to the static legacy path.
            val hasMaskDynamics = brush.maskedBrush?.dynamics?.isNotEmpty() == true
            val needsDynamicDabs = brush.dynamics.isNotEmpty() || hasMaskDynamics || brush.taper.isActive() ||
                brush.contact.isActive() || brush.airbrushDabsPerSecond > 0f
            if (needsDynamicDabs && mappedSamples.isNotEmpty()) {
                // Airbrush (roadmap item 13): this is the commit/replay render. EditorViewModel's
                // live incremental preview has its own, separate integration (tracked by
                // `stampHeldStampedCount`) that computes the same heldDabs while dragging, so the
                // build-up is visible during the drag, not just once committed -- the two call
                // sites are seeded from the same recorded timestamps so they agree. heldDabs needs
                // real recorded timestamps, so it only applies in this telemetry-present branch,
                // same as sensor dynamics above it.
                val diameterPx = stroke.brushSize * brushScale
                val movementDabs = BrushStamps.dynamicDabs(mappedSamples, diameterPx, brush, stroke.seed)
                val heldDabs = if (brush.airbrushDabsPerSecond > 0f) {
                    AirbrushEngine.heldDabs(
                        mappedSamples, diameterPx, brush, brush.airbrushDabsPerSecond,
                        brush.airbrushStillnessRadiusPx, stroke.seed,
                    )
                } else {
                    emptyList()
                }
                // Two calls, not one combined list: movement dabs must NOT build up on each other
                // (paintDabs' default allowBuildUp = false -- see paintRoundDabsMaxCombined's doc
                // comment), but Airbrush's held dabs must (allowBuildUp = true) -- matching the
                // split EditorViewModel's own live-preview integration already uses for newDabs vs
                // newHeldDabs, so the commit render agrees with what was previewed while dragging.
                StampBrushRenderer.paintDabs(
                    stampCanvas, movementDabs, brush, stroke.brushColor, stroke.flow,
                    stroke.stampShape, stroke.stampGrain, stroke.stampMaskShape, stroke.seed,
                    stroke.secondaryBrushColor, substrate = substrate,
                )
                if (heldDabs.isNotEmpty()) {
                    StampBrushRenderer.paintDabs(
                        stampCanvas, heldDabs, brush, stroke.brushColor, stroke.flow,
                        stroke.stampShape, stroke.stampGrain, stroke.stampMaskShape, stroke.seed,
                        stroke.secondaryBrushColor, allowBuildUp = true, substrate = substrate,
                    )
                }
                paintedDabs = movementDabs + heldDabs
            } else {
                StampBrushRenderer.paintStroke(
                    stampCanvas, pts, brush, stroke.brushColor,
                    stroke.brushSize * brushScale, stroke.flow, stroke.seed,
                    stroke.stampShape, stroke.stampGrain, stroke.stampMaskShape,
                    stroke.secondaryBrushColor, substrate = substrate,
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

            // Impasto v1 remains the exact compatibility branch. Phase-5 behavior is opt-in
            // through the versioned brush config and consumes the same canonical height/wetness
            // channels rather than introducing parallel material state.
            if (brush.impastoThicknessRate > 0f && heightMap != null && heightMap.size == target.width * target.height) {
                if (usesImpastoV2) {
                    // Paint has just been rasterized into target's raw canonical pigment. For a
                    // feathered selection, blend that raw result through the exact same soft mask
                    // that scales height/wetness transfer.
                    if (materialFeatherPixels != null && rawBeforeContact != null) {
                        val paintedRaw = IntArray(target.width * target.height)
                        target.getPixels(paintedRaw, 0, target.width, 0, 0, target.width, target.height)
                        for (i in paintedRaw.indices) {
                            val f = (materialFeatherPixels[i] ushr 24 and 0xFF) / 255f
                            if (f <= 0f) {
                                paintedRaw[i] = rawBeforeContact[i]
                            } else if (f < 1f) {
                                paintedRaw[i] = lerpArgb(rawBeforeContact[i], paintedRaw[i], f)
                            }
                        }
                        target.setPixels(paintedRaw, 0, target.width, 0, 0, target.width, target.height)
                    }

                    val transfer = ImpastoEngine.transferMaterialStroke(
                        height = heightMap,
                        width = target.width,
                        imgHeight = target.height,
                        dabs = paintedDabs,
                        hardness = brush.hardness,
                        thicknessRate = brush.impastoThicknessRate,
                        medium = materialMedium,
                        initialState = ImpastoMaterialStrokeState(materialConfig.initialLoad),
                        substrateProfile = substrate?.profile ?: SubstrateProfile.SMOOTH,
                        substrateField = substrate?.field,
                        pixelAllowed = materialAllowed,
                        pixelCoverage = materialCoverage,
                    )
                    val wetDirty = materialWetness?.let { wetState ->
                        ImpastoEngine.depositWetnessStroke(
                            wetness = wetState.field,
                            dabs = paintedDabs,
                            hardness = brush.hardness,
                            wetnessRate = materialConfig.wetness * brush.impastoThicknessRate,
                            pixelAllowed = materialAllowed,
                            pixelCoverage = materialCoverage,
                        )
                    }
                    val contactRegion = unionRegions(transfer.dirtyRegion, wetDirty)
                    materialState?.recordMedium(contactRegion, materialMedium)

                    // One deterministic post-contact quantum: Phase 4 settles pigment/wetness and
                    // Phase 5 levels height over the same bounded touched material region, using
                    // the medium owned by each material tile rather than the newly selected brush.
                    if (materialWetness != null && !materialWetness.field.isIdle) {
                        val materialPixels = IntArray(target.width * target.height)
                        target.getPixels(materialPixels, 0, target.width, 0, 0, target.width, target.height)
                        materialWetness.settleMaterial(materialPixels)
                        target.setPixels(materialPixels, 0, target.width, 0, 0, target.width, target.height)
                        ImpastoEngine.levelWetHeight(
                            height = heightMap,
                            width = target.width,
                            imgHeight = target.height,
                            wetness = materialWetness.field,
                            medium = materialMedium,
                            deltaSeconds = WetnessReplayState.DEFAULT_SETTLE_SECONDS,
                            region = contactRegion,
                            substrateProfile = substrate?.profile ?: SubstrateProfile.SMOOTH,
                            substrateField = substrate?.field,
                            mediumAt = materialState?.let { state ->
                                { x, y -> state.mediumAt(x, y, materialMedium) }
                            },
                        )
                        materialWetness.markThrough(mappedSamples.lastOrNull()?.uptimeMillis)
                    }

                    val rawPixels = IntArray(target.width * target.height)
                    target.getPixels(rawPixels, 0, target.width, 0, 0, target.width, target.height)
                    materialState?.replaceRawColor(rawPixels)

                    val touched = unionRegions(preContactMaterialRegion, contactRegion)
                    // Restore the previous lit presentation everywhere, then reconstruct only the
                    // material region whose canonical state changed from raw pigment.
                    val displayPixels = IntArray(target.width * target.height)
                    bitmap.getPixels(displayPixels, 0, target.width, 0, 0, target.width, target.height)
                    target.setPixels(displayPixels, 0, target.width, 0, 0, target.width, target.height)
                    shadeImpastoRegion(
                        target = target,
                        heightMap = heightMap,
                        touched = touched,
                        wetness = materialWetness?.field,
                        medium = materialMedium,
                        rawColorPixels = materialState?.rawColor ?: rawPixels,
                        mediumAt = materialState?.let { state ->
                            { x, y -> state.mediumAt(x, y, materialMedium) }
                        },
                    )
                    return target
                } else {
                    ImpastoEngine.depositStroke(
                        heightMap, target.width, target.height, paintedDabs,
                        brush.hardness, brush.impastoThicknessRate,
                    )
                    shadeImpastoRegion(
                        target = target,
                        heightMap = heightMap,
                        touched = DirtyRegion.fromDabs(paintedDabs),
                        wetness = null,
                        medium = null,
                    )
                }
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
            val wetnessClip: ((Int, Int) -> Boolean)? = wetnessRegion?.let { region ->
                { x, y -> region.contains(x, y) }
            }

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
            // Native modes 0/1 are the historical RGB Smear/Dulling paths; 2/3 select the exact RYB
            // material mixer. Stateful reservoir load/pickup now runs inside this same native
            // Color Smudge pipeline; a failed native stage still falls back to the CPU reference.
            // Persistent wetness is still CPU-reference-only. Do not let Vulkan silently paint the
            // colour while skipping canonical wetness state; legacy/dry Smudge remains GPU eligible.
            val gpuPainted = if (persistentWetness != null) false else runCatching {
                val engine = VulkanStampEngine()
                try {
                    if (!engine.init(width, height) || !engine.upload(target)) return@runCatching false
                    val baseMode = if (settings.mode == ColorSmudgeEngine.Mode.SMEAR) 0 else 1
                    val mode = baseMode + if (settings.mixingModel == MaterialMixingModel.PIGMENT_RYB) 2 else 0
                    for (plan in plans) {
                        if (plan.dabs.size < 2) continue
                        val nativeDabs = plan.dabs.map { dab ->
                            ColorSmudgeDab(
                                dab.x, dab.y, dab.smudgeRate, dab.colorRate,
                                dab.opacity, dab.smudgeRadius,
                                dab.colorRateMultiplier, dab.distanceDeltaPx,
                            )
                        }
                        if (!engine.colorSmudge(
                                nativeDabs, mode, settings.radiusPx, settings.feathering,
                                settings.smearAlpha, settings.paintColor, settings.dilution,
                                baseColorRate = settings.colorRate,
                                chargeDecayRate = settings.chargeDecayRate,
                                pickupRate = settings.pickupRate,
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

                // Material time is authoritative and comes from recorded sample uptime, never the
                // wall clock. When a selection exists, keep an evolved pre-stroke base so elapsed
                // wet-paint settling remains global while only this stroke is clipped by the lasso.
                val evolvedSelectionBase = if (persistentWetness != null && clipPath != null) {
                    SafeBitmap.copy(bitmap)
                } else null
                val canAdvanceMaterial = clipPath == null || evolvedSelectionBase != null
                if (persistentWetness != null && canAdvanceMaterial) {
                    persistentWetness.advanceMaterialTo(
                        pixels, mappedSamples.firstOrNull()?.uptimeMillis,
                    )
                    evolvedSelectionBase?.setPixels(pixels, 0, width, 0, 0, width, height)
                }

                ColorSmudgeEngine.apply(
                    pixels, width, height, mapped, settings,
                    samples = mappedSamples, strokeSeed = stroke.seed,
                    sampleSource = sampleSource,
                    wetnessField = persistentWetness?.field,
                    wetnessClip = wetnessClip,
                )
                target.setPixels(pixels, 0, width, 0, 0, width, height)

                val confined = SelectionMask.confine(
                    evolvedSelectionBase ?: bitmap, target, clipPath, featherRadius,
                )
                if (evolvedSelectionBase != null && evolvedSelectionBase !== confined) {
                    evolvedSelectionBase.recycle()
                }

                if (persistentWetness != null) {
                    val settled = IntArray(width * height)
                    confined.getPixels(settled, 0, width, 0, 0, width, height)
                    persistentWetness.settleMaterial(settled)
                    persistentWetness.markThrough(mappedSamples.lastOrNull()?.uptimeMillis)
                    confined.setPixels(settled, 0, width, 0, 0, width, height)
                }
                return confined
            }
            return SelectionMask.confine(bitmap, target, clipPath, featherRadius)
        }

        return SelectionMask.feather(
            bitmap,
            ImageProcessor.applyToolToBitmap(
                bitmap, mapped, stroke.tool, stroke.brushSize * brushScale, stroke.brushColor, stroke.intensity,
                replaceExisting && featherRadius <= 0f, stroke.feathering,
                alphaLock = stroke.alphaLock,
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

    private fun lerpArgb(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val av = a ushr shift and 0xFF
            val bv = b ushr shift and 0xFF
            return (av + (bv - av) * f).toInt().coerceIn(0, 255)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or
            (channel(8) shl 8) or channel(0)
    }

    private fun unionRegions(a: DirtyRegion?, b: DirtyRegion?): DirtyRegion? = when {
        a == null -> b
        b == null -> a
        else -> a.union(b)
    }

    private fun activeWetnessBounds(field: PersistentWetnessField): DirtyRegion? {
        var out: DirtyRegion? = null
        for ((tx, ty) in field.activeTileCoordinates()) {
            val left = tx * field.tileSize
            val top = ty * field.tileSize
            val tile = DirtyRegion(
                left = left,
                top = top,
                right = minOf(field.width, left + field.tileSize),
                bottom = minOf(field.height, top + field.tileSize),
            )
            out = out?.union(tile) ?: tile
        }
        return out
    }

    /**
     * Re-shades only material pixels whose height/wetness could have changed plus the one-pixel
     * normal-gradient border. A null [medium] selects the historical v1 relief shader exactly.
     */
    private fun shadeImpastoRegion(
        target: Bitmap,
        heightMap: FloatArray,
        touched: DirtyRegion?,
        wetness: PersistentWetnessField?,
        medium: com.hereliesaz.graffitixr.common.azphalt.PaintMedium?,
        rawColorPixels: IntArray? = null,
        mediumAt: ((x: Int, y: Int) -> com.hereliesaz.graffitixr.common.azphalt.PaintMedium)? = null,
    ) {
        val region = touched?.let {
            DirtyRegion(it.left - 1, it.top - 1, it.right + 1, it.bottom + 1)
        }?.clampTo(target.width, target.height) ?: return
        if (region.isEmpty) return
        val regionWidth = region.right - region.left
        val regionHeight = region.bottom - region.top
        val rawRegion = IntArray(regionWidth * regionHeight)
        if (rawColorPixels != null && rawColorPixels.size >= target.width * target.height) {
            for (localY in 0 until regionHeight) {
                val src = (region.top + localY) * target.width + region.left
                rawColorPixels.copyInto(
                    rawRegion,
                    destinationOffset = localY * regionWidth,
                    startIndex = src,
                    endIndex = src + regionWidth,
                )
            }
        } else {
            target.getPixels(
                rawRegion, 0, regionWidth,
                region.left, region.top, regionWidth, regionHeight,
            )
        }
        val shadedRegion = if (medium == null) {
            ImpastoRegionShader.shade(
                rawRegion, heightMap, target.width, target.height,
                region.left, region.top, regionWidth, regionHeight,
                IMPASTO_LIGHT_AZIMUTH_DEG, IMPASTO_LIGHT_ELEVATION_DEG, IMPASTO_LIGHT_STRENGTH,
            )
        } else {
            ImpastoRegionShader.shadeMaterial(
                rawRegion = rawRegion,
                height = heightMap,
                wetness = wetness,
                canvasWidth = target.width,
                canvasHeight = target.height,
                left = region.left,
                top = region.top,
                regionWidth = regionWidth,
                regionHeight = regionHeight,
                lightAzimuthDeg = IMPASTO_LIGHT_AZIMUTH_DEG,
                lightElevationDeg = IMPASTO_LIGHT_ELEVATION_DEG,
                reliefStrength = IMPASTO_LIGHT_STRENGTH,
                medium = medium,
                mediumAt = mediumAt,
            )
        }
        target.setPixels(
            shadedRegion, 0, regionWidth,
            region.left, region.top, regionWidth, regionHeight,
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