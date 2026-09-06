from pathlib import Path
p = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt')
s = p.read_text()

s = s.replace(
'''    private var strokeGpuEngine: VulkanStampEngine? = null\n    private var strokeGpuActive: Boolean = false\n''',
'''    private var strokeGpuEngine: VulkanStampEngine? = null\n    private var strokeGpuActive: Boolean = false\n    // Basic Brush uses the same hardware-buffer-backed live presentation as Azphalt when available.\n    private var strokeGpuDisplay: AzphaltGpuDisplay? = null\n''', 1)

old = '''            val createdGpuEngine = if (strokeDynamics != null) createSeededGpuEngine(workBitmap.width, workBitmap.height, workBitmap) else null\n            val gpuEngine = synchronized(liveCurveLock) {\n                if (generation != strokeGeneration || strokeLayerId != layerId) {\n                    createdGpuEngine?.destroy()\n                    null\n                } else {\n                    // Guard against a leaked engine if this ever runs without a prior onStrokeEnd/\n                    // clearTransientStrokeState in between (there shouldn't be one, but destroy()\n                    // is cheap to call defensively and a leaked Vulkan device is not).\n                    strokeGpuEngine?.destroy()\n                    strokeGpuEngine = createdGpuEngine\n                    strokeGpuActive = createdGpuEngine != null\n                    createdGpuEngine\n                }\n            }\n'''
new = '''            val createdGpuEngine = if (strokeDynamics != null) createSeededGpuEngine(workBitmap.width, workBitmap.height, workBitmap) else null\n            val createdGpuDisplay = createdGpuEngine?.let(AzphaltGpuDisplay::tryCreate)\n            val gpuEngine = synchronized(liveCurveLock) {\n                if (generation != strokeGeneration || strokeLayerId != layerId) {\n                    createdGpuDisplay?.close()\n                    createdGpuEngine?.destroy()\n                    null\n                } else {\n                    // Guard against a leaked engine if this ever runs without a prior onStrokeEnd/\n                    // clearTransientStrokeState in between (there shouldn't be one, but destroy()\n                    // is cheap to call defensively and a leaked Vulkan device is not).\n                    strokeGpuDisplay?.close()\n                    strokeGpuDisplay = createdGpuDisplay\n                    strokeGpuEngine?.destroy()\n                    strokeGpuEngine = createdGpuEngine\n                    strokeGpuActive = createdGpuEngine != null\n                    createdGpuEngine\n                }\n            }\n'''
assert old in s
s = s.replace(old, new, 1)

# If a setup is superseded, release both Java display and native engine together.
old = '''                    if (strokeGpuEngine === gpuEngine) {\n                        strokeGpuEngine = null\n                        strokeGpuActive = false\n                        gpuEngine.destroy()\n                    }\n'''
new = '''                    if (strokeGpuEngine === gpuEngine) {\n                        strokeGpuDisplay?.close()\n                        strokeGpuDisplay = null\n                        strokeGpuEngine = null\n                        strokeGpuActive = false\n                        gpuEngine.destroy()\n                    }\n'''
assert old in s
s = s.replace(old, new, 1)

# Publish the hardware bitmap as soon as the seeded engine wins setup.
s = s.replace(
'''                    bitmap = workBitmap,\n                    version = it.version + catchUpPoints.size\n''',
'''                    bitmap = synchronized(liveCurveLock) { strokeGpuDisplay?.bitmap } ?: workBitmap,\n                    version = it.version + catchUpPoints.size\n''', 1)

# Worker avoids readback when the AHardwareBuffer display is available and publishes that bitmap.
old = '''                                gpuHandled = allSubmitted && engine.readback(targetBitmap)\n                                if (!gpuHandled && strokeGpuEngine === engine) {\n                                    strokeGpuActive = false\n                                    strokeGpuEngine = null\n                                    engine.destroy()\n                                }\n'''
new = '''                                val display = strokeGpuDisplay\n                                gpuHandled = allSubmitted && (display != null || engine.readback(targetBitmap))\n                                if (!gpuHandled && strokeGpuEngine === engine) {\n                                    strokeGpuDisplay?.close()\n                                    strokeGpuDisplay = null\n                                    strokeGpuActive = false\n                                    strokeGpuEngine = null\n                                    engine.destroy()\n                                }\n'''
assert old in s
s = s.replace(old, new, 1)

s = s.replace(
'''                        _liveStroke.update { it.copy(bitmap = targetBitmap, version = it.version + 1) }\n                        if (latencyId >= 0L) basicLatencyTracker.markPresented(latencyId)\n''',
'''                        val published = synchronized(liveCurveLock) { strokeGpuDisplay?.bitmap } ?: targetBitmap\n                        _liveStroke.update { it.copy(bitmap = published, version = it.version + 1) }\n                        if (latencyId >= 0L) basicLatencyTracker.markPresented(latencyId)\n''', 1)

# Canonical Basic commit owns any hardware preview until the replacement bitmap is published. This
# prevents clearTransientStrokeState from recycling a bitmap Compose may still be displaying.
old = '''            if ((featherRadius > 0f || basicBrushNeedsCanonicalCommit) && base != null) {\n                val preview = workBitmap\n                // Tracked in rebuildJobs -- see the BLUR/SHARPEN/SMUDGE branch's identical comment.\n'''
new = '''            if ((featherRadius > 0f || basicBrushNeedsCanonicalCommit) && base != null) {\n                val deferredGpuDisplay = if (basicBrushNeedsCanonicalCommit) synchronized(liveCurveLock) {\n                    strokeGpuDisplay.also { strokeGpuDisplay = null }\n                } else null\n                val preview = deferredGpuDisplay?.bitmap ?: workBitmap\n                // Tracked in rebuildJobs -- see the BLUR/SHARPEN/SMUDGE branch's identical comment.\n'''
assert old in s
s = s.replace(old, new, 1)

old = '''                        _liveStroke.update { s -> if (s.bitmap === preview) s.copy(layerId = null, bitmap = null) else s }\n                        scheduleDiskSave(layerId, committed, layer.uri)\n                    }\n                }\n'''
new = '''                        _liveStroke.update { s -> if (s.bitmap === preview) s.copy(layerId = null, bitmap = null) else s }\n                        scheduleDiskSave(layerId, committed, layer.uri)\n                        deferredGpuDisplay?.close()\n                    }\n                }\n'''
assert old in s
s = s.replace(old, new, 1)

# Normal teardown closes only displays still owned by transient state; canonical Basic commit detaches
# its display above and closes it after replacement publication.
old = '''        synchronized(liveCurveLock) {\n            strokeGpuEngine?.destroy()\n            strokeGpuEngine = null\n            strokeGpuActive = false\n        }\n'''
new = '''        synchronized(liveCurveLock) {\n            strokeGpuDisplay?.close()\n            strokeGpuDisplay = null\n            strokeGpuEngine?.destroy()\n            strokeGpuEngine = null\n            strokeGpuActive = false\n        }\n'''
assert old in s
s = s.replace(old, new, 1)

p.write_text(s)
