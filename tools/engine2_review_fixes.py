from pathlib import Path
p = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt')
s = p.read_text()

# Basic hardware display is not authoritative until the first successful GPU batch has actually
# painted into it. This keeps CPU-rendered taps/very short strokes visible during setup.
s = s.replace(
'''    private var strokeGpuDisplay: AzphaltGpuDisplay? = null\n''',
'''    private var strokeGpuDisplay: AzphaltGpuDisplay? = null\n    private var strokeGpuDisplayReady: Boolean = false\n''', 1)

s = s.replace(
'''        basicLatestLatencySampleId = -1L\n        basicLiveConsumedPointCount = 0\n''',
'''        basicLatestLatencySampleId = -1L\n        basicLiveConsumedPointCount = 0\n        strokeGpuDisplayReady = false\n''', 1)

# Interrupted stamp strokes must not carry cadence-wait IDs into the next stroke.
s = s.replace(
'''            stampPendingHeldDabs.clear()\n            stampPendingLatencyIds.clear()\n            stampRenderedMovementDabs.clear()\n''',
'''            stampPendingHeldDabs.clear()\n            stampPendingLatencyIds.clear()\n            stampAwaitingGenerationLatencyIds.clear()\n            stampRenderedMovementDabs.clear()\n''', 1)

# Setup publishes the CPU catch-up bitmap until a GPU batch has caught up with live geometry.
s = s.replace(
'''                    bitmap = synchronized(liveCurveLock) { strokeGpuDisplay?.bitmap } ?: workBitmap,\n''',
'''                    bitmap = workBitmap,\n''', 1)

# Dual-brush held Airbrush dabs intentionally have no secondary Dab.mask on the CPU reference path.
# Submit the primary held stamps with hasSecondary=false rather than treating that as GPU failure.
old = '''                                val secondaryHeldDabs = if (hasDualBrush && newHeldDabs.all { it.mask != null }) {\n                                    newHeldDabs.map { dab ->\n                                        val maskDab = dab.mask!!\n                                        SecondaryBrushDab(\n                                            x = maskDab.x,\n                                            y = maskDab.y,\n                                            radius = maskDab.radius,\n                                            tipRatio = maskDab.tipRatio,\n                                            alpha = maskDab.alpha,\n                                            angleDeg = maskDab.angleDeg,\n                                            flowMultiplier = maskDab.flowMultiplier,\n                                            keepInside = maskDab.keepInside,\n                                        )\n                                    }\n                                } else {\n                                    emptyList()\n                                }\n                                (!hasDualBrush || newHeldDabs.all { it.mask != null }) &&\n                                    engine.stampMaskedDabs(\n                                        gpuHeldDabs, brush.hardness.coerceIn(0f, 1f), maskAlpha8,\n                                        maskSize, maskSize,\n                                        grainAlpha8, grainWidth, grainHeight,\n                                        grainLocked, brush.grainScale,\n                                        grainPhaseX, grainPhaseY,\n                                        secondaryHeldDabs, secondaryMaskAlpha8,\n                                        secondaryMaskSize, secondaryMaskSize,\n                                    ) && (usesZeroCopyDisplay || engine.readback(work))\n'''
new = '''                                val secondaryHeldDabs = emptyList<SecondaryBrushDab>()\n                                engine.stampMaskedDabs(\n                                    gpuHeldDabs, brush.hardness.coerceIn(0f, 1f), maskAlpha8,\n                                    maskSize, maskSize,\n                                    grainAlpha8, grainWidth, grainHeight,\n                                    grainLocked, brush.grainScale,\n                                    grainPhaseX, grainPhaseY,\n                                    secondaryHeldDabs, secondaryMaskAlpha8,\n                                    secondaryMaskSize, secondaryMaskSize,\n                                ) && (usesZeroCopyDisplay || engine.readback(work))\n'''
assert old in s
s = s.replace(old, new, 1)

# Once a Basic GPU batch succeeds, the hardware image becomes the live source. If a later GPU call
# fails, keep that last-good hardware prefix alive instead of switching to a stale CPU mirror. The
# canonical finger-up renderer remains authoritative and catches up the tail.
old = '''                                val display = strokeGpuDisplay\n                                gpuHandled = allSubmitted && (display != null || engine.readback(targetBitmap))\n                                if (!gpuHandled && strokeGpuEngine === engine) {\n                                    strokeGpuDisplay?.close()\n                                    strokeGpuDisplay = null\n                                    strokeGpuActive = false\n                                    strokeGpuEngine = null\n                                    engine.destroy()\n                                }\n'''
new = '''                                val display = strokeGpuDisplay\n                                gpuHandled = allSubmitted && (display != null || engine.readback(targetBitmap))\n                                if (gpuHandled && display != null) {\n                                    strokeGpuDisplayReady = true\n                                }\n                                if (!gpuHandled && strokeGpuEngine === engine) {\n                                    // Keep the last-good hardware image alive as a frozen prefix.\n                                    // targetBitmap is intentionally not a full CPU mirror on the\n                                    // zero-copy path, so switching presentation to it would erase\n                                    // everything rendered successfully before this failure.\n                                    strokeGpuActive = false\n                                    strokeGpuEngine = null\n                                    engine.destroy()\n                                }\n'''
assert old in s
s = s.replace(old, new, 1)

s = s.replace(
'''                        val published = synchronized(liveCurveLock) { strokeGpuDisplay?.bitmap } ?: targetBitmap\n''',
'''                        val published = synchronized(liveCurveLock) {\n                            strokeGpuDisplay?.bitmap?.takeIf { strokeGpuDisplayReady }\n                        } ?: targetBitmap\n''', 1)

# Detaching the Basic display transfers ownership to the canonical commit job; clear readiness too.
s = s.replace(
'''                    strokeGpuDisplay.also { strokeGpuDisplay = null }\n''',
'''                    strokeGpuDisplay.also {\n                        strokeGpuDisplay = null\n                        strokeGpuDisplayReady = false\n                    }\n''', 1)

# Cancellation must release detached graphics memory, and must first remove the hardware bitmap from
# LiveStroke if that preview is still the one being displayed.
old = '''                rebuildJobs[layerId] = viewModelScope.launch(dispatchers.default) {\n                    val committed = drawingEngine.applySingleStroke(base, command)\n                    withContext(dispatchers.main) {\n                        _uiState.update { s ->\n                            s.copy(\n                                layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = committed) else it },\n                            )\n                        }\n                        // Only clear the live-stroke preview if it's still ours -- a newer stroke may\n                        // have already started by the time this async rebuild finishes, and clearing\n                        // its preview here would flash the wrong (or no) bitmap for that new stroke.\n                        _liveStroke.update { s -> if (s.bitmap === preview) s.copy(layerId = null, bitmap = null) else s }\n                        scheduleDiskSave(layerId, committed, layer.uri)\n                        deferredGpuDisplay?.close()\n                    }\n                }\n'''
new = '''                rebuildJobs[layerId] = viewModelScope.launch(dispatchers.default) {\n                    try {\n                        val committed = drawingEngine.applySingleStroke(base, command)\n                        withContext(dispatchers.main) {\n                            _uiState.update { s ->\n                                s.copy(\n                                    layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = committed) else it },\n                                )\n                            }\n                            // Only clear the live-stroke preview if it's still ours -- a newer stroke may\n                            // have already started by the time this async rebuild finishes, and clearing\n                            // its preview here would flash the wrong (or no) bitmap for that new stroke.\n                            _liveStroke.update { s -> if (s.bitmap === preview) s.copy(layerId = null, bitmap = null) else s }\n                            scheduleDiskSave(layerId, committed, layer.uri)\n                        }\n                    } finally {\n                        withContext(kotlinx.coroutines.NonCancellable + dispatchers.main) {\n                            _liveStroke.update { state ->\n                                if (state.bitmap === preview) state.copy(layerId = null, bitmap = null) else state\n                            }\n                            deferredGpuDisplay?.close()\n                        }\n                    }\n                }\n'''
assert old in s
s = s.replace(old, new, 1)

# Any display still owned by transient Basic state is closed here.
s = s.replace(
'''            strokeGpuDisplay?.close()\n            strokeGpuDisplay = null\n            strokeGpuEngine?.destroy()\n''',
'''            strokeGpuDisplay?.close()\n            strokeGpuDisplay = null\n            strokeGpuDisplayReady = false\n            strokeGpuEngine?.destroy()\n''', 1)

p.write_text(s)
