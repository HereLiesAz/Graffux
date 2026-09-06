from pathlib import Path

p = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt')
s = p.read_text()

old = 'import com.hereliesaz.graffitixr.common.azphalt.BrushStamps\n'
new = old + 'import com.hereliesaz.graffitixr.common.azphalt.IncrementalStaticDabGenerator\n'
assert old in s and 'IncrementalStaticDabGenerator' not in s, 'incremental generator import state unexpected'
s = s.replace(old, new, 1)

old = '''    private val stampRenderedMovementDabs = ArrayList<Dab>()
    private var stampGpuEngine: VulkanStampEngine? = null
'''
new = '''    private val stampRenderedMovementDabs = ArrayList<Dab>()
    // Static stamp brushes now generate only the dabs made possible by newly mapped points. The
    // complete generated prefix is kept separately from the rendered prefix because the renderer
    // is deliberately allowed to lag/coalesce while input continues at full speed.
    private var stampStaticDabGenerator: IncrementalStaticDabGenerator? = null
    private val stampGeneratedMovementDabs = ArrayList<Dab>()
    private var stampStaticConsumedPointCount: Int = 0
    private var stampGpuEngine: VulkanStampEngine? = null
'''
assert old in s, 'Engine 2 field anchor changed'
s = s.replace(old, new, 1)

old = '''            val dabs = if (needsDynamicDabs && mappedSamples.isNotEmpty()) {
                BrushStamps.dynamicDabs(mappedSamples, diameterPx, stampBrush, stampSeed)
            } else {
                BrushStamps.dabs(stampMappedPoints, diameterPx, stampBrush, stampSeed)
            }
'''
new = '''            val dabs = if (needsDynamicDabs && mappedSamples.isNotEmpty()) {
                // Dynamic/taper brushes still use the canonical resolver for now; unlike the old
                // render path, their output is consumed by one bounded worker so GPU work cannot
                // queue behind the pointer. A stateful dynamic resolver is the next Engine 2 cut.
                BrushStamps.dynamicDabs(mappedSamples, diameterPx, stampBrush, stampSeed)
            } else {
                // Static brushes are fully incremental: process only mapped points this generator
                // has not seen. No BrushStamps.place()/dabs() walk over the growing stroke prefix.
                var generator = stampStaticDabGenerator
                if (generator == null) {
                    generator = IncrementalStaticDabGenerator(diameterPx, stampBrush, stampSeed)
                    stampStaticDabGenerator = generator
                }
                val pointCount = stampMappedPoints.size / 2
                while (stampStaticConsumedPointCount < pointCount) {
                    val index = stampStaticConsumedPointCount * 2
                    stampGeneratedMovementDabs.addAll(
                        generator.appendPoint(stampMappedPoints[index], stampMappedPoints[index + 1])
                    )
                    stampStaticConsumedPointCount++
                }
                stampGeneratedMovementDabs
            }
'''
assert old in s, 'dab generation block changed'
s = s.replace(old, new, 1)

old = '''                if (stampGpuJob?.isActive != true) {
                    stampGpuJob = viewModelScope.launch(dispatchers.default) {
                        while (true) {
                            val newDabs = stampPendingMovementDabs.drain()
                            val newHeldDabs = stampPendingHeldDabs.drain()
                            val hasNewMovementDabs = newDabs.isNotEmpty()
                            val hasNewHeldDabs = newHeldDabs.isNotEmpty()
                            if (!hasNewMovementDabs && !hasNewHeldDabs) break
                            stampRenderedMovementDabs.addAll(newDabs)
'''
new = '''                synchronized(stampLiveLock) {
                    if (stampGpuJob?.isActive != true) {
                        stampGpuJob = viewModelScope.launch(dispatchers.default) {
                            while (true) {
                                val newDabs = stampPendingMovementDabs.drain()
                                val newHeldDabs = stampPendingHeldDabs.drain()
                                val hasNewMovementDabs = newDabs.isNotEmpty()
                                val hasNewHeldDabs = newHeldDabs.isNotEmpty()
                                if (!hasNewMovementDabs && !hasNewHeldDabs) {
                                    synchronized(stampLiveLock) {
                                        if (stampPendingMovementDabs.isEmpty && stampPendingHeldDabs.isEmpty) {
                                            stampGpuJob = null
                                            return@launch
                                        }
                                    }
                                    continue
                                }
                                stampRenderedMovementDabs.addAll(newDabs)
'''
assert old in s, 'worker start block changed'
s = s.replace(old, new, 1)

old = '''                        }
                    }
                }
            }
            return
        }

        val canvas = strokeWorkingCanvas ?: return
'''
new = '''                        }
                    }
                }
                }
            }
            return
        }

        val canvas = strokeWorkingCanvas ?: return
'''
assert old in s, 'worker closing anchor changed'
s = s.replace(old, new, 1)

old = '        stampRenderedMovementDabs.clear()\n'
new = '''        stampRenderedMovementDabs.clear()
        stampStaticDabGenerator = null
        stampGeneratedMovementDabs.clear()
        stampStaticConsumedPointCount = 0
'''
count = s.count(old)
assert count >= 2, f'expected at least two rendered-prefix resets, found {count}'
s = s.replace(old, new)

p.write_text(s)
