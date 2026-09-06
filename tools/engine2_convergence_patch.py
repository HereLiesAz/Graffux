from pathlib import Path

p = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt')
s = p.read_text()

old = '''    private val strokeStabilizer = StrokeStabilizer()\n'''
new = '''    private val strokeStabilizer = StrokeStabilizer()\n    // Engine 2 keeps canonical input fidelity independent from how often a preview is presented.\n    private val azphaltRenderCadence = AzphaltRenderCadence()\n    private val azphaltLatencyTracker = AzphaltLatencyTracker()\n    @Volatile private var stampLatestLatencySampleId: Long = -1L\n'''
assert old in s
s = s.replace(old, new, 1)

old = '''        lastSampleMs = 0L\n        strokeDynamics = if (state.activeTool == Tool.BRUSH && activeStampBrush == null) BrushDynamics.State() else null\n'''
new = '''        lastSampleMs = 0L\n        azphaltRenderCadence.reset()\n        stampLatestLatencySampleId = -1L\n        strokeDynamics = if (state.activeTool == Tool.BRUSH && activeStampBrush == null) BrushDynamics.State() else null\n'''
assert old in s
s = s.replace(old, new, 1)

old = '''        // Input-rate throttle. Touch panels report at 120-240 Hz and this method previously\n        // rendered and published a frame for every single sample — the editor's largest power cost\n        // while drawing, spent on frames the display never showed. Dropping a sample loses nothing\n        // visible: the stroke is a polyline, so the segment simply spans to the next kept point.\n        //\n        // The point is still stabilized first (above) so the filter's history stays continuous, and\n        // the very first point of a stroke is never dropped — a quick tap is a single dab and has\n        // no later sample to fall back on.\n        val rateHz = _uiState.value.inputSampleRateHz\n        if (rateHz > 0 && lastSampleMs != 0L) {\n            val minGapMs = 1000L / rateHz\n            val now = android.os.SystemClock.uptimeMillis()\n            if (now - lastSampleMs < minGapMs) return\n            lastSampleMs = now\n        } else {\n            lastSampleMs = android.os.SystemClock.uptimeMillis()\n        }\n\n        addStrokePoint(stabilizedPoint, stabilizedPressure)\n'''
new = '''        // Engine 2 separates input fidelity from presentation cadence. Every BRUSH sample enters\n        // the canonical stroke first; only the expensive preview work is rate-limited. The old\n        // throttle returned before addStrokePoint(), silently throwing away 120/240 Hz hardware\n        // samples and making commit/replay depend on display settings. Non-brush tools retain the\n        // historical throttle because several of them perform destructive incremental operations\n        // that are not yet history-catch-up based.\n        val rateHz = _uiState.value.inputSampleRateHz\n        val nowMs = android.os.SystemClock.uptimeMillis()\n        val activeToolForCadence = _uiState.value.activeTool\n        if (activeToolForCadence == Tool.BRUSH) {\n            addStrokePoint(stabilizedPoint, stabilizedPressure)\n            if (!azphaltRenderCadence.shouldRender(nowMs, rateHz)) return\n        } else {\n            if (rateHz > 0 && lastSampleMs != 0L) {\n                val minGapMs = 1000L / rateHz\n                if (nowMs - lastSampleMs < minGapMs) return\n            }\n            lastSampleMs = nowMs\n            addStrokePoint(stabilizedPoint, stabilizedPressure)\n        }\n        if (activeToolForCadence == Tool.BRUSH && stampBrushForStroke != null) {\n            stampLatestLatencySampleId = azphaltLatencyTracker.beginInput()\n        }\n'''
assert old in s
s = s.replace(old, new, 1)

old = '''            if (hasNewMovementDabs || hasNewHeldDabs) {\n                val colorArgb = _uiState.value.activeColor.toArgb()\n'''
new = '''            if (hasNewMovementDabs || hasNewHeldDabs) {\n                val generatedLatencyId = stampLatestLatencySampleId\n                if (generatedLatencyId >= 0L) azphaltLatencyTracker.markGenerated(generatedLatencyId)\n                val colorArgb = _uiState.value.activeColor.toArgb()\n'''
assert old in s
s = s.replace(old, new, 1)

old = '''                                val newDabs = stampPendingMovementDabs.drain()\n                                val newHeldDabs = stampPendingHeldDabs.drain()\n                                val hasNewMovementDabs = newDabs.isNotEmpty()\n'''
new = '''                                val newDabs = stampPendingMovementDabs.drain()\n                                val newHeldDabs = stampPendingHeldDabs.drain()\n                                val latencyId = stampLatestLatencySampleId\n                                if (latencyId >= 0L) azphaltLatencyTracker.markSubmitted(latencyId)\n                                val hasNewMovementDabs = newDabs.isNotEmpty()\n'''
assert old in s
s = s.replace(old, new, 1)

old = '''                        _liveStroke.update { it.copy(bitmap = publishedBitmap, version = it.version + 1) }\n                    }\n'''
new = '''                        _liveStroke.update { it.copy(bitmap = publishedBitmap, version = it.version + 1) }\n                        if (latencyId >= 0L) azphaltLatencyTracker.markPresented(latencyId)\n                    }\n'''
assert old in s
s = s.replace(old, new, 1)

old = '''    internal fun topUndoTileDeltaCountForTest(): Int? =\n        (history.undoStackTopForTest() as? EditCommand.Draw)?.tileDeltas?.size\n'''
new = '''    internal fun topUndoTileDeltaCountForTest(): Int? =\n        (history.undoStackTopForTest() as? EditCommand.Draw)?.tileDeltas?.size\n\n    @androidx.annotation.VisibleForTesting\n    internal fun azphaltLatencySnapshotForTest(): AzphaltLatencyTracker.Snapshot = azphaltLatencyTracker.snapshot()\n'''
assert old in s
s = s.replace(old, new, 1)

p.write_text(s)
