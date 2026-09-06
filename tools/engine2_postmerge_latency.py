from pathlib import Path

p = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt')
s = p.read_text()

# Track every physical Azphalt input sample through the cadence wait, not only samples that
# immediately trigger a preview render.
s = s.replace(
'''    private val stampPendingLatencyIds = AzphaltPendingBatchQueue<Long>()\n''',
'''    private val stampPendingLatencyIds = AzphaltPendingBatchQueue<Long>()\n    private val stampAwaitingGenerationLatencyIds = AzphaltPendingBatchQueue<Long>()\n''', 1)

# Reset the new queue wherever the Engine 2 queues are reset at stroke start.
s = s.replace(
'''            stampPendingMovementDabs.clear()\n            stampPendingHeldDabs.clear()\n            stampRenderedMovementDabs.clear()\n''',
'''            stampPendingMovementDabs.clear()\n            stampPendingHeldDabs.clear()\n            stampPendingLatencyIds.clear()\n            stampAwaitingGenerationLatencyIds.clear()\n            stampRenderedMovementDabs.clear()\n''', 1)

old = '''        if (activeToolForCadence == Tool.BRUSH) {\n            addStrokePoint(stabilizedPoint, stabilizedPressure)\n            // Stamp brushes can reconstruct every not-yet-previewed sample from canonical history\n            // on the next displayed frame. Basic Brush advances a stateful Catmull-Rom window and\n            // width recursion per point instead, so feed every physical sample into that cheap\n            // geometry path and let its background worker coalesce only the expensive rendering.\n            if (stampBrushForStroke != null && !azphaltRenderCadence.shouldRender(nowMs, rateHz)) return\n        } else {\n'''
new = '''        if (activeToolForCadence == Tool.BRUSH) {\n            addStrokePoint(stabilizedPoint, stabilizedPressure)\n            if (stampBrushForStroke != null) {\n                val latencyId = azphaltLatencyTracker.beginInput()\n                stampLatestLatencySampleId = latencyId\n                stampAwaitingGenerationLatencyIds.append(latencyId)\n            } else {\n                basicLatestLatencySampleId = basicLatencyTracker.beginInput()\n            }\n            // Stamp brushes can reconstruct every not-yet-previewed sample from canonical history\n            // on the next displayed frame. Basic Brush advances a stateful Catmull-Rom window and\n            // width recursion per point instead, so feed every physical sample into that cheap\n            // geometry path and let its background worker coalesce only the expensive rendering.\n            if (stampBrushForStroke != null && !azphaltRenderCadence.shouldRender(nowMs, rateHz)) return\n        } else {\n'''
assert old in s
s = s.replace(old, new, 1)

# Remove the old post-cadence beginInput block; Basic and Azphalt are both started above now.
old = '''        if (activeToolForCadence == Tool.BRUSH) {\n            if (stampBrushForStroke != null) {\n                stampLatestLatencySampleId = azphaltLatencyTracker.beginInput()\n            } else {\n                basicLatestLatencySampleId = basicLatencyTracker.beginInput()\n            }\n        }\n\n'''
assert old in s
s = s.replace(old, '', 1)

# When a preview generation pass catches up canonical history, attribute generation to every
# physical sample that was waiting behind the cadence gate and carry all of those IDs with the
# paint batch into submission/presentation timing.
old = '''            if (hasNewMovementDabs || hasNewHeldDabs) {\n                val generatedLatencyId = stampLatestLatencySampleId\n                if (generatedLatencyId >= 0L) azphaltLatencyTracker.markGenerated(generatedLatencyId)\n'''
new = '''            if (hasNewMovementDabs || hasNewHeldDabs) {\n                val generatedLatencyIds = stampAwaitingGenerationLatencyIds.drain()\n                generatedLatencyIds.forEach { azphaltLatencyTracker.markGenerated(it) }\n'''
assert old in s
s = s.replace(old, new, 1)

s = s.replace(
'''                if (generatedLatencyId >= 0L) stampPendingLatencyIds.append(generatedLatencyId)\n''',
'''                stampPendingLatencyIds.append(generatedLatencyIds)\n''', 1)

# Worker stages apply to every physical input represented by the coalesced paint batch.
s = s.replace(
'''                                val latencyId = stampPendingLatencyIds.drain().lastOrNull() ?: -1L\n                                if (latencyId >= 0L) azphaltLatencyTracker.markSubmitted(latencyId)\n''',
'''                                val latencyIds = stampPendingLatencyIds.drain()\n                                latencyIds.forEach { azphaltLatencyTracker.markSubmitted(it) }\n''', 1)

s = s.replace(
'''                        if (latencyId >= 0L) azphaltLatencyTracker.markPresented(latencyId)\n''',
'''                        latencyIds.forEach { azphaltLatencyTracker.markPresented(it) }\n''', 1)

# Teardown reset, if present.
needle = '''        stampPendingMovementDabs.clear()\n        stampPendingHeldDabs.clear()\n        stampPendingLatencyIds.clear()\n        stampRenderedMovementDabs.clear()\n'''
replacement = '''        stampPendingMovementDabs.clear()\n        stampPendingHeldDabs.clear()\n        stampPendingLatencyIds.clear()\n        stampAwaitingGenerationLatencyIds.clear()\n        stampRenderedMovementDabs.clear()\n'''
if needle in s:
    s = s.replace(needle, replacement, 1)

p.write_text(s)
