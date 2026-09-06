from pathlib import Path

p = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt')
s = p.read_text()

# Stamp latency IDs travel with pending paint, so a busy worker doesn't attribute an older batch
# to whichever input sample happened to arrive most recently.
s = s.replace(
'''    private val stampPendingMovementDabs = AzphaltPendingBatchQueue<Dab>()\n    private val stampPendingHeldDabs = AzphaltPendingBatchQueue<Dab>()\n''',
'''    private val stampPendingMovementDabs = AzphaltPendingBatchQueue<Dab>()\n    private val stampPendingHeldDabs = AzphaltPendingBatchQueue<Dab>()\n    private val stampPendingLatencyIds = AzphaltPendingBatchQueue<Long>()\n''', 1)

s = s.replace(
'''            stampPendingMovementDabs.clear()\n            stampPendingHeldDabs.clear()\n''',
'''            stampPendingMovementDabs.clear()\n            stampPendingHeldDabs.clear()\n            stampPendingLatencyIds.clear()\n''', 1)

old = '''                stampPendingMovementDabs.append(newDabs)\n                stampPendingHeldDabs.append(newHeldDabs)\n'''
new = '''                stampPendingMovementDabs.append(newDabs)\n                stampPendingHeldDabs.append(newHeldDabs)\n                if (generatedLatencyId >= 0L) stampPendingLatencyIds.append(generatedLatencyId)\n'''
assert old in s
s = s.replace(old, new, 1)

old = '''                                val newDabs = stampPendingMovementDabs.drain()\n                                val newHeldDabs = stampPendingHeldDabs.drain()\n                                val latencyId = stampLatestLatencySampleId\n                                if (latencyId >= 0L) azphaltLatencyTracker.markSubmitted(latencyId)\n'''
new = '''                                val newDabs = stampPendingMovementDabs.drain()\n                                val newHeldDabs = stampPendingHeldDabs.drain()\n                                val latencyId = stampPendingLatencyIds.drain().lastOrNull() ?: -1L\n                                if (latencyId >= 0L) azphaltLatencyTracker.markSubmitted(latencyId)\n'''
assert old in s
s = s.replace(old, new, 1)

# Basic latency IDs travel with curve-run groups for the same reason.
s = s.replace(
'''    private val basicPendingDabs = AzphaltPendingBatchQueue<List<BrushDab>>()\n''',
'''    private val basicPendingDabs = AzphaltPendingBatchQueue<Pair<Long, List<BrushDab>>>()\n''', 1)

old = '''        basicPendingDabs.append(dabs)\n'''
new = '''        basicPendingDabs.append(latencyIdAtGeneration to dabs)\n'''
assert old in s
s = s.replace(old, new, 1)

old = '''                    val latencyId = basicLatestLatencySampleId\n                    if (latencyId >= 0L) basicLatencyTracker.markSubmitted(latencyId)\n'''
new = '''                    val latencyId = groups.lastOrNull()?.first ?: -1L\n                    val dabGroups = groups.map { it.second }\n                    if (latencyId >= 0L) basicLatencyTracker.markSubmitted(latencyId)\n'''
assert old in s
s = s.replace(old, new, 1)

s = s.replace('''                                for (group in groups) {\n''', '''                                for (group in dabGroups) {\n''', 1)
s = s.replace('''                        for (group in groups) for (dab in group) {\n''', '''                        for (group in dabGroups) for (dab in group) {\n''', 1)

# Canonicalize Basic Brush on finger-up. Its live bitmap is now intentionally allowed to lag the
# physical input stream, so it is no longer a safe commit source even with no feathered selection.
old = '''            if (featherRadius > 0f && base != null) {\n                val preview = workBitmap\n'''
new = '''            val basicBrushNeedsCanonicalCommit = state.activeTool == Tool.BRUSH && stampBrushForStroke == null\n            if ((featherRadius > 0f || basicBrushNeedsCanonicalCommit) && base != null) {\n                val preview = workBitmap\n'''
assert old in s
s = s.replace(old, new, 1)

# Clear the stamp metric queue on transient teardown as well. There are multiple clear snippets;
# target the one alongside the Engine 2 queues near clearTransientStrokeState.
needle = '''        stampPendingMovementDabs.clear()\n        stampPendingHeldDabs.clear()\n        stampRenderedMovementDabs.clear()\n'''
replacement = '''        stampPendingMovementDabs.clear()\n        stampPendingHeldDabs.clear()\n        stampPendingLatencyIds.clear()\n        stampRenderedMovementDabs.clear()\n'''
if needle in s:
    s = s.replace(needle, replacement, 1)

p.write_text(s)
