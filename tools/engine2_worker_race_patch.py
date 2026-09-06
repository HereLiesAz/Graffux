from pathlib import Path

p = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt')
s = p.read_text()

old = '''                        stampGpuJob = viewModelScope.launch(dispatchers.default) {
                            while (true) {
                                val newDabs = stampPendingMovementDabs.drain()
'''
new = '''                        stampGpuJob = viewModelScope.launch(dispatchers.default) {
                            while (true) {
                                // A fast lift/redown can leave the previous stroke's native call
                                // finishing after the new stroke exists. Never let that stale worker
                                // drain shared queues or clear the new stroke's worker reference.
                                if (strokeGeneration != strokeGen || strokeLayerId != strokeLayerIdSnapshot) {
                                    return@launch
                                }
                                val newDabs = stampPendingMovementDabs.drain()
'''
assert old in s, 'worker loop anchor changed'
s = s.replace(old, new, 1)

old = '''                                if (!hasNewMovementDabs && !hasNewHeldDabs) {
                                    synchronized(stampLiveLock) {
                                        if (stampPendingMovementDabs.isEmpty && stampPendingHeldDabs.isEmpty) {
                                            stampGpuJob = null
                                            return@launch
                                        }
                                    }
                                    continue
                                }
'''
new = '''                                if (!hasNewMovementDabs && !hasNewHeldDabs) {
                                    synchronized(stampLiveLock) {
                                        if (strokeGeneration != strokeGen || strokeLayerId != strokeLayerIdSnapshot) {
                                            return@launch
                                        }
                                        if (stampPendingMovementDabs.isEmpty && stampPendingHeldDabs.isEmpty) {
                                            stampGpuJob = null
                                            return@launch
                                        }
                                    }
                                    continue
                                }
'''
assert old in s, 'empty-drain race anchor changed'
s = s.replace(old, new, 1)

# Clean up the reset indentation introduced by the earlier mechanical cutover while here.
old = '''            stampGpuJob = null
            stampMappedPoints.clear()
        stampPendingMovementDabs.clear()
        stampPendingHeldDabs.clear()
        stampRenderedMovementDabs.clear()
        stampStaticDabGenerator = null
        stampDynamicDabGenerator = null
        stampGeneratedMovementDabs.clear()
        stampMovementConsumedSampleCount = 0
        stampAirbrushGenerator = null
        stampGeneratedHeldDabs.clear()
        stampAirbrushConsumedSampleCount = 0
            viewModelScope.launch(dispatchers.default) {
'''
new = '''            stampGpuJob = null
            stampMappedPoints.clear()
            stampPendingMovementDabs.clear()
            stampPendingHeldDabs.clear()
            stampRenderedMovementDabs.clear()
            stampStaticDabGenerator = null
            stampDynamicDabGenerator = null
            stampGeneratedMovementDabs.clear()
            stampMovementConsumedSampleCount = 0
            stampAirbrushGenerator = null
            stampGeneratedHeldDabs.clear()
            stampAirbrushConsumedSampleCount = 0
            viewModelScope.launch(dispatchers.default) {
'''
assert old in s, 'stroke-start reset formatting changed'
s = s.replace(old, new, 1)

p.write_text(s)
