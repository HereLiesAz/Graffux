from pathlib import Path

p = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt')
s = p.read_text()

old = '''    private var strokeGpuEngine: VulkanStampEngine? = null\n    private var strokeGpuActive: Boolean = false\n\n    private val strokeStabilizer = StrokeStabilizer()\n'''
new = '''    private var strokeGpuEngine: VulkanStampEngine? = null\n    private var strokeGpuActive: Boolean = false\n    // Basic Brush now uses the same one-worker/lossless-coalescing scheduling contract as Azphalt\n    // stamp brushes. Its geometry is still the existing Catmull-Rom -> round-dab path; only the\n    // execution moves off the input thread.\n    private val basicLiveLock = Any()\n    private var basicGpuJob: Job? = null\n    private val basicPendingDabs = AzphaltPendingBatchQueue<BrushDab>()\n\n    private val strokeStabilizer = StrokeStabilizer()\n'''
assert old in s
s = s.replace(old, new, 1)

old = '''    private val azphaltRenderCadence = AzphaltRenderCadence()\n    private val azphaltLatencyTracker = AzphaltLatencyTracker()\n    @Volatile private var stampLatestLatencySampleId: Long = -1L\n'''
new = '''    private val azphaltRenderCadence = AzphaltRenderCadence()\n    private val azphaltLatencyTracker = AzphaltLatencyTracker()\n    private val basicLatencyTracker = AzphaltLatencyTracker()\n    @Volatile private var stampLatestLatencySampleId: Long = -1L\n    @Volatile private var basicLatestLatencySampleId: Long = -1L\n'''
assert old in s
s = s.replace(old, new, 1)

old = '''        azphaltRenderCadence.reset()\n        stampLatestLatencySampleId = -1L\n        strokeDynamics = if (state.activeTool == Tool.BRUSH && activeStampBrush == null) BrushDynamics.State() else null\n'''
new = '''        azphaltRenderCadence.reset()\n        stampLatestLatencySampleId = -1L\n        basicLatestLatencySampleId = -1L\n        synchronized(basicLiveLock) {\n            basicPendingDabs.clear()\n            basicGpuJob = null\n        }\n        strokeDynamics = if (state.activeTool == Tool.BRUSH && activeStampBrush == null) BrushDynamics.State() else null\n'''
assert old in s
s = s.replace(old, new, 1)

old = '''        if (activeToolForCadence == Tool.BRUSH && stampBrushForStroke != null) {\n            stampLatestLatencySampleId = azphaltLatencyTracker.beginInput()\n        }\n'''
new = '''        if (activeToolForCadence == Tool.BRUSH) {\n            if (stampBrushForStroke != null) {\n                stampLatestLatencySampleId = azphaltLatencyTracker.beginInput()\n            } else {\n                basicLatestLatencySampleId = basicLatencyTracker.beginInput()\n            }\n        }\n'''
assert old in s
s = s.replace(old, new, 1)

start = s.index('    private fun drawCurveRun(')
end = s.index('    private fun feedLiveCurvePoint(', start)
new_functions = r'''    private fun enqueueBasicLiveDabs(
        canvas: Canvas,
        paint: Paint,
        dabs: List<BrushDab>,
        targetBitmap: Bitmap,
    ) {
        if (dabs.isEmpty()) return
        val generation = strokeGeneration
        val layerId = strokeLayerId ?: return
        val latencyIdAtGeneration = basicLatestLatencySampleId
        if (latencyIdAtGeneration >= 0L) basicLatencyTracker.markGenerated(latencyIdAtGeneration)
        basicPendingDabs.append(dabs)

        synchronized(basicLiveLock) {
            if (basicGpuJob?.isActive == true) return
            val paintSnapshot = Paint(paint).apply { style = Paint.Style.FILL }
            basicGpuJob = viewModelScope.launch(dispatchers.default) {
                while (true) {
                    val batch = synchronized(basicLiveLock) {
                        if (strokeGeneration != generation || strokeLayerId != layerId) return@launch
                        basicPendingDabs.drain()
                    }
                    if (batch.isEmpty()) {
                        synchronized(basicLiveLock) {
                            if (strokeGeneration != generation || strokeLayerId != layerId) return@launch
                            if (basicPendingDabs.isEmpty) {
                                basicGpuJob = null
                                return@launch
                            }
                        }
                        continue
                    }

                    val latencyId = basicLatestLatencySampleId
                    if (latencyId >= 0L) basicLatencyTracker.markSubmitted(latencyId)
                    if (strokeGeneration != generation || strokeLayerId != layerId) return@launch

                    var gpuHandled = false
                    // Unlike the old input-thread call, the native fence/readback now blocks only
                    // this single background consumer. Holding liveCurveLock for the native call
                    // prevents onStrokeEnd/a fast redown from destroying the engine mid-submit.
                    synchronized(liveCurveLock) {
                        if (strokeGeneration == generation && strokeLayerId == layerId && strokeGpuActive) {
                            val engine = strokeGpuEngine
                            if (engine != null) {
                                val baseAlpha = (paintSnapshot.color ushr 24) and 0xFF
                                val combinedAlpha = (baseAlpha * paintSnapshot.alpha / 255).coerceIn(0, 255)
                                val colorForGpu = (combinedAlpha shl 24) or (paintSnapshot.color and 0x00FFFFFF)
                                gpuHandled = engine.stampDabs(batch, colorForGpu, 1f) && engine.readback(targetBitmap)
                                if (!gpuHandled && strokeGpuEngine === engine) {
                                    strokeGpuActive = false
                                    strokeGpuEngine = null
                                    engine.destroy()
                                }
                            }
                        }
                    }

                    if (!gpuHandled) {
                        if (strokeGeneration != generation || strokeLayerId != layerId) return@launch
                        val cpuPaint = Paint(paintSnapshot).apply { style = Paint.Style.FILL }
                        for (dab in batch) {
                            canvas.drawCircle(dab.x, dab.y, dab.radius, cpuPaint)
                        }
                    }

                    if (strokeGeneration == generation && strokeLayerId == layerId) {
                        _liveStroke.update { it.copy(bitmap = targetBitmap, version = it.version + 1) }
                        if (latencyId >= 0L) basicLatencyTracker.markPresented(latencyId)
                    }
                }
            }
        }
    }

    private fun drawCurveRun(
        canvas: Canvas,
        paint: Paint,
        run: FloatArray,
        bitmapWidth: Int,
        bitmapHeight: Int,
        symmetryMode: SymmetryMode,
        wrapAroundMode: Boolean,
        targetBitmap: Bitmap,
    ) {
        val radius = max(paint.strokeWidth / 2f, 0.5f)
        val centres = BrushStamps.place(
            run.toList(),
            max(radius * ROUND_BRUSH_DAB_SPACING_FRACTION, 1f),
        )
        if (centres.isEmpty()) return

        val symmetryExtras = ImageProcessor.symmetryTransforms(
            symmetryMode, bitmapWidth.toFloat(), bitmapHeight.toFloat(),
        )
        val basePositions = ArrayList<Offset>(centres.size / 2 * (1 + symmetryExtras.size))
        var i = 0
        while (i < centres.size) {
            val p = Offset(centres[i], centres[i + 1])
            basePositions.add(p)
            for (transform in symmetryExtras) basePositions.add(transform(p))
            i += 2
        }

        val dabs = if (wrapAroundMode) {
            val bw = bitmapWidth.toFloat()
            val bh = bitmapHeight.toFloat()
            ArrayList<BrushDab>(basePositions.size * 9).also { out ->
                for (p in basePositions) {
                    for (dx in -1..1) for (dy in -1..1) {
                        out.add(BrushDab(p.x + dx * bw, p.y + dy * bh, radius, 1f, 0f))
                    }
                }
            }
        } else {
            basePositions.map { p -> BrushDab(p.x, p.y, radius, 1f, 0f) }
        }
        enqueueBasicLiveDabs(canvas, paint, dabs, targetBitmap)
    }

'''
s = s[:start] + new_functions + s[end:]

old = '''    internal fun azphaltLatencySnapshotForTest(): AzphaltLatencyTracker.Snapshot = azphaltLatencyTracker.snapshot()\n'''
new = '''    internal fun azphaltLatencySnapshotForTest(): AzphaltLatencyTracker.Snapshot = azphaltLatencyTracker.snapshot()\n\n    @androidx.annotation.VisibleForTesting\n    internal fun basicLatencySnapshotForTest(): AzphaltLatencyTracker.Snapshot = basicLatencyTracker.snapshot()\n'''
assert old in s
s = s.replace(old, new, 1)

old = '''        strokeGeneration++\n        resetLiveCurveState()\n        resetStrokePoints()\n'''
new = '''        strokeGeneration++\n        synchronized(basicLiveLock) {\n            basicPendingDabs.clear()\n            basicGpuJob = null\n        }\n        basicLatestLatencySampleId = -1L\n        resetLiveCurveState()\n        resetStrokePoints()\n'''
assert old in s
s = s.replace(old, new, 1)

p.write_text(s)
