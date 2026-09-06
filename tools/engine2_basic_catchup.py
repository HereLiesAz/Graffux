from pathlib import Path

p = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt')
s = p.read_text()

s = s.replace(
'''    private var basicGpuJob: Job? = null\n''',
'''    private var basicGpuJob: Job? = null\n    // Number of canonical points already folded into Basic Brush's live Catmull-Rom/width state.\n    // This closes the async bitmap-setup gap: points recorded after the setup snapshot but before\n    // its publish are consumed on the next live update rather than silently skipped.\n    private var basicLiveConsumedPointCount: Int = 0\n''', 1)

old = '''        basicLatestLatencySampleId = -1L\n        synchronized(basicLiveLock) {\n            basicPendingDabs.clear()\n            basicGpuJob = null\n        }\n'''
new = '''        basicLatestLatencySampleId = -1L\n        basicLiveConsumedPointCount = 0\n        synchronized(basicLiveLock) {\n            basicPendingDabs.clear()\n            basicGpuJob = null\n        }\n'''
assert old in s
s = s.replace(old, new, 1)

# The initial async setup replays catchUpPoints completely before publishing the working canvas.
old = '''                strokePaint = paint\n                strokePrevBitmapPoint = lastMapped\n                _liveStroke.update { it.copy(\n'''
new = '''                strokePaint = paint\n                strokePrevBitmapPoint = lastMapped\n                basicLiveConsumedPointCount = catchUpPoints.size\n                _liveStroke.update { it.copy(\n'''
assert old in s
s = s.replace(old, new, 1)

# Replace the dynamic Basic single-current-point block with canonical-tail catch-up. Non-brush
# tools retain their existing one-segment path below.
old = '''        val dyn = strokeDynamics\n        if (dyn != null) {\n            // Dynamic brush width: advance the per-stroke recursion by this segment's length,\n            // immediately, every call — the same recursion runs from scratch on commit/replay, so\n            // it matches exactly once committed. Drawing itself goes through the live Catmull-Rom\n            // curve window instead of a straight chord — see feedLiveCurvePoint's doc for why only\n            // width is computed eagerly while drawing is windowed by a point or two.\n            val brushScale = ImageProcessor.screenToBitmapScale(\n                strokeCanvasW, strokeCanvasH, workBitmap.width, workBitmap.height, strokeLayerScale\n            )\n            val width = dyn.next((mapped - prev).getDistance(), _uiState.value.effectivePaintBrushSize() * brushScale, stabilizedPressure)\n            feedLiveCurvePoint(\n                canvas, paint, workBitmap.width, workBitmap.height, strokeSymmetry,\n                strokeWrapAroundMode, mapped, width, workBitmap,\n            )\n        } else {\n'''
new = '''        val dyn = strokeDynamics\n        if (dyn != null) {\n            // Engine 2: consume the entire canonical tail, not only this callback's newest point.\n            // The working-bitmap setup is asynchronous, so input can arrive after its catch-up\n            // snapshot but before strokeWorkingCanvas is published; those samples used to be lost\n            // from live curve/width state even though history retained them.\n            val canonicalPoints = snapshotStrokePoints()\n            val canonicalPressures = snapshotStrokePressures()\n            val startIndex = basicLiveConsumedPointCount.coerceIn(1, canonicalPoints.size)\n            val tailScreen = if (startIndex < canonicalPoints.size) {\n                canonicalPoints.subList(startIndex, canonicalPoints.size)\n            } else emptyList()\n            if (tailScreen.isNotEmpty()) {\n                val tailMapped = ImageProcessor.mapScreenToBitmap(\n                    tailScreen, strokeCanvasW, strokeCanvasH, workBitmap.width, workBitmap.height,\n                    strokeLayerScale, strokeLayerOffset, strokeLayerRotationZ,\n                )\n                val brushScale = ImageProcessor.screenToBitmapScale(\n                    strokeCanvasW, strokeCanvasH, workBitmap.width, workBitmap.height, strokeLayerScale\n                )\n                var previous = prev\n                for (i in tailMapped.indices) {\n                    val globalIndex = startIndex + i\n                    val pointPressure = canonicalPressures.getOrNull(globalIndex) ?: stabilizedPressure\n                    val next = tailMapped[i]\n                    val width = dyn.next(\n                        (next - previous).getDistance(),\n                        _uiState.value.effectivePaintBrushSize() * brushScale,\n                        pointPressure,\n                    )\n                    feedLiveCurvePoint(\n                        canvas, paint, workBitmap.width, workBitmap.height, strokeSymmetry,\n                        strokeWrapAroundMode, next, width, workBitmap,\n                    )\n                    previous = next\n                }\n                basicLiveConsumedPointCount = canonicalPoints.size\n                strokePrevBitmapPoint = tailMapped.last()\n            }\n            _liveStroke.update { it.copy(version = it.version + 1) }\n            return\n        } else {\n'''
assert old in s
s = s.replace(old, new, 1)

# The old common tail assignment/update must now apply only to the non-dynamic branch.
# It remains correct there; the dynamic branch returns above.

old = '''        basicLatestLatencySampleId = -1L\n        resetLiveCurveState()\n'''
new = '''        basicLatestLatencySampleId = -1L\n        basicLiveConsumedPointCount = 0\n        resetLiveCurveState()\n'''
assert old in s
s = s.replace(old, new, 1)

p.write_text(s)
