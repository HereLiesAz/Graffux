from pathlib import Path

p = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt')
s = p.read_text()

# Core incremental compositor import.
old = 'import com.hereliesaz.graffitixr.common.azphalt.IncrementalAirbrushGenerator\n'
new = old + 'import com.hereliesaz.graffitixr.common.azphalt.IncrementalRoundStampCompositor\n'
assert old in s and 'IncrementalRoundStampCompositor' not in s
s = s.replace(old, new, 1)

# Stroke-local state.
old = '''    private val stampRenderedMovementDabs = ArrayList<Dab>()
    // Static stamp brushes now generate only the dabs made possible by newly mapped points. The
'''
new = '''    private val stampRenderedMovementDabs = ArrayList<Dab>()
    // Plain round, non-build-up brushes need max coverage across the whole stroke rather than per
    // batch. Engine 2 keeps that state sparsely by tile so only newly-touched pixels are revisited.
    private var stampRoundMaxCompositor: IncrementalRoundStampCompositor? = null
    // When the Vulkan layer is AHardwareBuffer-backed and this brush has no CPU-only side effects,
    // the actual GPU image is published directly as the live Bitmap and no per-frame readback runs.
    private var stampGpuDisplay: AzphaltGpuDisplay? = null
    // Static stamp brushes now generate only the dabs made possible by newly mapped points. The
'''
assert old in s
s = s.replace(old, new, 1)

# Any reset of the rendered prefix also resets the sparse max-combine state.
s = s.replace(
    '        stampRenderedMovementDabs.clear()\n',
    '        stampRenderedMovementDabs.clear()\n        stampRoundMaxCompositor = null\n',
)

# Close any hardware-buffer display before its owning Vulkan engine is destroyed/replaced.
old = '''            synchronized(stampLiveLock) {
                stampGpuEngine?.destroy()
                stampGpuEngine = null
                stampGpuActive = false
            }
'''
new = '''            synchronized(stampLiveLock) {
                stampGpuDisplay?.close()
                stampGpuDisplay = null
                stampGpuEngine?.destroy()
                stampGpuEngine = null
                stampGpuActive = false
            }
'''
count = s.count(old)
assert count >= 1, count
s = s.replace(old, new)

# Do not stand up Vulkan for the one path whose correct semantics require a persistent max buffer.
# For the remaining GPU-compatible brushes, expose the AHardwareBuffer directly when no CPU-only
# airbrush/impasto pass needs the software bitmap to stay synchronized every frame.
old = '''                val gpuEngine = if (gpuCompatibleBrush) createSeededGpuEngine(work.width, work.height, work) else null
                val gpuReady = gpuEngine != null
'''
new = '''                val plainRoundMaxCpu = !stampBrush.buildUp &&
                    stampShapeForStroke == null && stampGrainForStroke == null &&
                    stampBrush.maskedBrush == null && stampBrush.tipRatio == 1f
                val gpuEngine = if (gpuCompatibleBrush && !plainRoundMaxCpu) {
                    createSeededGpuEngine(work.width, work.height, work)
                } else null
                val gpuReady = gpuEngine != null
                val zeroCopyEligible = gpuReady && stampBrush.airbrushDabsPerSecond <= 0f &&
                    stampBrush.impastoThicknessRate <= 0f
                val gpuDisplay = if (zeroCopyEligible) AzphaltGpuDisplay.tryCreate(gpuEngine!!) else null
'''
assert old in s
s = s.replace(old, new, 1)

# Publish and retain the zero-copy bitmap when available.
old = '''                        synchronized(stampLiveLock) {
                            stampGpuEngine = if (gpuReady) gpuEngine else null
                            stampGpuActive = gpuReady
                        }
'''
new = '''                        synchronized(stampLiveLock) {
                            stampGpuEngine = if (gpuReady) gpuEngine else null
                            stampGpuActive = gpuReady
                            stampGpuDisplay = gpuDisplay
                        }
'''
assert old in s
s = s.replace(old, new, 1)

old = '''                                bitmap = shadedBitmapSeed ?: work,
'''
new = '''                                bitmap = gpuDisplay?.bitmap ?: shadedBitmapSeed ?: work,
'''
assert old in s
s = s.replace(old, new, 1)

old = '''                        gpuEngine?.destroy()
'''
new = '''                        gpuDisplay?.close()
                        gpuEngine?.destroy()
'''
# Exactly the superseded-start cleanup in this section.
assert old in s
s = s.replace(old, new, 1)

# Snapshot whether this batch can stay entirely GPU-resident.
old = '''                    val secondaryMaskAlpha8: ByteArray?
                    val secondaryMaskSize: Int
                    synchronized(stampLiveLock) {
'''
new = '''                    val secondaryMaskAlpha8: ByteArray?
                    val secondaryMaskSize: Int
                    val usesZeroCopyDisplay: Boolean
                    synchronized(stampLiveLock) {
'''
assert old in s
s = s.replace(old, new, 1)

old = '''                        secondaryMaskAlpha8 = stampGpuSecondaryMaskAlpha8
                        secondaryMaskSize = stampGpuSecondaryMaskSize
                    }
'''
new = '''                        secondaryMaskAlpha8 = stampGpuSecondaryMaskAlpha8
                        secondaryMaskSize = stampGpuSecondaryMaskSize
                        usesZeroCopyDisplay = stampGpuDisplay != null
                    }
'''
assert old in s
s = s.replace(old, new, 1)

# Masked GPU path: direct display means no readback is required.
old = '''                                    ) && engine.readback(work)
'''
new = '''                                    ) && (usesZeroCopyDisplay || engine.readback(work))
'''
# First occurrence is stampMaskedDabs in this live path.
assert old in s
s = s.replace(old, new, 1)

# Resolved round GPU path: the pathological non-build-up case is now CPU sparse-max and never gets
# a GPU engine. Every resolved GPU batch can therefore submit just new dabs; direct display skips
# readback, software-display fallback keeps the existing dirty-rect readback.
start = s.index('                            if (!brush.buildUp && preStrokeBase != null) {')
end_marker = '''                            }
                        }
                    }
                    if (hasNewMovementDabs && !gpuHandled) {'''
end = s.index(end_marker, start)
old_block = s[start:end]
new_block = '''                            val gpuDabs = newDabs.map(::resolve)
                            engine.stampResolvedDabs(gpuDabs, buildUp = brush.buildUp) &&
                                (usesZeroCopyDisplay || engine.readback(work))
'''
s = s[:start] + new_block + s[end:]

# On the rare case that a zero-copy GPU call fails mid-stroke, reconstruct the rendered prefix once
# from the pristine base before switching to CPU. This preserves correctness without forcing every
# successful frame through readback.
old = '''                    if (hasNewMovementDabs && !gpuHandled) {
                        if (gpuActive) {
'''
new = '''                    if (hasNewMovementDabs && !gpuHandled) {
                        var replayedGpuPrefix = false
                        if (usesZeroCopyDisplay && preStrokeBase != null) {
                            val restorePaint = Paint().apply {
                                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
                            }
                            canvas.drawBitmap(preStrokeBase, 0f, 0f, restorePaint)
                            restorePaint.xfermode = null
                            StampBrushRenderer.paintDabs(
                                canvas, stampRenderedMovementDabs, brush, colorArgb, rawFlow,
                                shape, grain, maskShape, seed, secondaryColorArgb,
                            )
                            replayedGpuPrefix = true
                        }
                        if (gpuActive) {
'''
assert old in s
s = s.replace(old, new, 1)

# When disabling a failed GPU, also drop the hardware display before destroying its engine.
old = '''                                    stampGpuActive = false
                                    stampGpuEngine = null
                                    stampGpuUsesMaskedPipeline = false
'''
new = '''                                    stampGpuActive = false
                                    stampGpuEngine = null
                                    stampGpuDisplay?.close()
                                    stampGpuDisplay = null
                                    stampGpuUsesMaskedPipeline = false
'''
assert old in s
s = s.replace(old, new, 1)

# Replace the old full-stroke repaint with sparse persistent max-coverage tiles.
old = '''                        val advancedMaskPipeline = brush.tipRatio != 1f || grain != null || brush.maskedBrush != null
                        if (!advancedMaskPipeline && shape == null && !brush.buildUp && preStrokeBase != null) {
                            StampBrushRenderer.repaintRoundStrokeFromBase(
                                canvas, preStrokeBase, stampRenderedMovementDabs, brush, colorArgb, rawFlow, secondaryColorArgb,
                            )
                        } else {
                            StampBrushRenderer.paintDabs(
                                canvas, newDabs, brush, colorArgb, rawFlow,
                                shape, grain, maskShape, seed,
                                secondaryColorArgb,
                            )
                        }
'''
new = '''                        if (!replayedGpuPrefix) {
                            val advancedMaskPipeline = brush.tipRatio != 1f || grain != null || brush.maskedBrush != null
                            if (!advancedMaskPipeline && shape == null && !brush.buildUp && preStrokeBase != null) {
                                var compositor = stampRoundMaxCompositor
                                if (compositor == null) {
                                    compositor = IncrementalRoundStampCompositor(work.width, work.height)
                                    stampRoundMaxCompositor = compositor
                                }
                                val changedTiles = compositor.append(
                                    newDabs, colorArgb, secondaryColorArgb, brush.colorSource, rawFlow,
                                )
                                IncrementalRoundStampRenderer.repaintTilesFromBase(
                                    canvas, preStrokeBase, changedTiles,
                                )
                            } else {
                                StampBrushRenderer.paintDabs(
                                    canvas, newDabs, brush, colorArgb, rawFlow,
                                    shape, grain, maskShape, seed,
                                    secondaryColorArgb,
                                )
                            }
                        }
'''
assert old in s
s = s.replace(old, new, 1)

# Publish whichever backing image is authoritative after this batch. Zero-copy stays hardware;
# a failed GPU immediately switches the live state back to the software bitmap.
old = '''                        _liveStroke.update { it.copy(version = it.version + 1) }
'''
new = '''                        val publishedBitmap = synchronized(stampLiveLock) {
                            stampGpuDisplay?.bitmap
                        } ?: shadedBitmap ?: work
                        _liveStroke.update { it.copy(bitmap = publishedBitmap, version = it.version + 1) }
'''
assert old in s
s = s.replace(old, new, 1)

# Commit cleanup must compare against the bitmap actually published, which may now be hardware-backed.
old = '''        val previewBitmap = stampLiveBitmap
'''
new = '''        val previewBitmap = _liveStroke.value.bitmap
'''
assert old in s
s = s.replace(old, new, 1)

p.write_text(s)
