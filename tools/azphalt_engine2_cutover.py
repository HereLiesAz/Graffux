from pathlib import Path

p = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt')
s = p.read_text()

old = """    // The tail of the current stroke's serialized GPU work queue -- see onStrokePoint's stamp-brush
    // branch. Each new batch joins this before starting, so batches run strictly one at a time and
    // in order even though they're no longer on the calling (main) thread; null between strokes.
    private var stampGpuJob: Job? = null
"""
new = """    // Engine 2: one live-stamp worker per stroke. Input appends losslessly to the two queues below;
    // the worker drains whatever accumulated while the previous GPU/CPU batch was rendering. This
    // bounds scheduling latency without dropping paint instructions or creating one Job per sample.
    private var stampGpuJob: Job? = null
    private val stampPendingMovementDabs = AzphaltPendingBatchQueue<Dab>()
    private val stampPendingHeldDabs = AzphaltPendingBatchQueue<Dab>()
    // Stable prefix already consumed by the live worker. Needed only for the legacy plain-round,
    // non-build-up compatibility path, which still has to repaint from the pristine base until the
    // native engine grows persistent max-coverage state.
    private val stampRenderedMovementDabs = ArrayList<Dab>()
"""
assert old in s, 'stampGpuJob field block changed'
s = s.replace(old, new, 1)

old = """                stampStampedCount = dabs.size
                stampHeldStampedCount = heldDabs.size
                val heightMap = stampLiveHeightMap
"""
new = """                stampStampedCount = dabs.size
                stampHeldStampedCount = heldDabs.size
                stampPendingMovementDabs.append(newDabs)
                stampPendingHeldDabs.append(newHeldDabs)
                val heightMap = stampLiveHeightMap
"""
assert old in s, 'dab count block changed'
s = s.replace(old, new, 1)

old = """                // `stampGpuJob.join()` below chains every batch onto the previous one so they still
                // run strictly one at a time, in order -- required both because VulkanStampEngine
                // documents that it is not safe to call from multiple threads concurrently, and
                // because each batch's CPU fallback (StampBrushRenderer.paintDabs) and Impasto
                // shading mutate `canvas`/`work`/`heightMap`/`shadedBitmap` in place and would
                // otherwise race a neighbouring batch doing the same. The chain is captured (not
                // read from the field) before launching so this reassignment can't race a
                // concurrently-running previous batch also about to reassign it.
                val prevJob = stampGpuJob
                val brush = stampBrush
                val shape = stampShapeForStroke
                val grain = stampGrainForStroke
                val maskShape = stampMaskShapeForStroke
                val seed = stampSeed
                stampGpuJob = viewModelScope.launch(dispatchers.default) {
                    prevJob?.join()
"""
new = """                // Engine 2: one consumer drains all currently pending paint before sleeping.
                // A slow GPU can make a batch larger, but it can no longer make the Job queue longer.
                val brush = stampBrush
                val shape = stampShapeForStroke
                val grain = stampGrainForStroke
                val maskShape = stampMaskShapeForStroke
                val seed = stampSeed
                if (stampGpuJob?.isActive != true) {
                    stampGpuJob = viewModelScope.launch(dispatchers.default) {
                        while (true) {
                            val newDabs = stampPendingMovementDabs.drain()
                            val newHeldDabs = stampPendingHeldDabs.drain()
                            val hasNewMovementDabs = newDabs.isNotEmpty()
                            val hasNewHeldDabs = newHeldDabs.isNotEmpty()
                            if (!hasNewMovementDabs && !hasNewHeldDabs) break
                            stampRenderedMovementDabs.addAll(newDabs)
"""
assert old in s, 'serialized job block changed'
s = s.replace(old, new, 1)

assert 'val gpuAllDabs = dabs.map(::resolve)' in s
s = s.replace('val gpuAllDabs = dabs.map(::resolve)', 'val gpuAllDabs = stampRenderedMovementDabs.map(::resolve)', 1)
assert 'canvas, preStrokeBase, dabs, brush, colorArgb, rawFlow, secondaryColorArgb,' in s
s = s.replace('canvas, preStrokeBase, dabs, brush, colorArgb, rawFlow, secondaryColorArgb,', 'canvas, preStrokeBase, stampRenderedMovementDabs, brush, colorArgb, rawFlow, secondaryColorArgb,', 1)

marker = """                        _liveStroke.update { it.copy(version = it.version + 1) }
                    }
                }
            }
            return
        }

        val canvas = strokeWorkingCanvas ?: return
"""
replacement = """                        _liveStroke.update { it.copy(version = it.version + 1) }
                    }
                        }
                    }
                }
            }
            return
        }

        val canvas = strokeWorkingCanvas ?: return
"""
assert marker in s, 'live worker tail changed'
s = s.replace(marker, replacement, 1)

reset = '        stampMappedPoints.clear()\n'
count = s.count(reset)
assert count >= 2, f'expected mapped-point resets, found {count}'
s = s.replace(reset, """        stampMappedPoints.clear()
        stampPendingMovementDabs.clear()
        stampPendingHeldDabs.clear()
        stampRenderedMovementDabs.clear()
""")

p.write_text(s)
