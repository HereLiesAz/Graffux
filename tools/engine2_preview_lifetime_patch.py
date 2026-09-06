from pathlib import Path

p = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt')
s = p.read_text()

old = '''        // Capture this stroke's preview bitmap synchronously (clearTransientStrokeState nulls the field
        // right after this returns). The async commit only clears the live preview if it's still ours —
        // otherwise a stroke that started before this commit finished would have its preview wiped.
        val previewBitmap = _liveStroke.value.bitmap
'''
new = '''        // A zero-copy preview owns a Java HardwareBuffer reference that must outlive transient stroke
        // teardown: clearTransientStrokeState destroys the Vulkan engine immediately after this
        // method returns, while the canonical CPU commit below can still be rendering. Detach the
        // display wrapper from transient ownership now and close it only after this commit replaces
        // (or discovers it no longer owns) the live preview. The Java HardwareBuffer reference keeps
        // the imported memory alive even after the native engine releases its own reference.
        val retainedGpuDisplay = synchronized(stampLiveLock) {
            stampGpuDisplay.also { stampGpuDisplay = null }
        }
        val previewBitmap = retainedGpuDisplay?.bitmap ?: _liveStroke.value.bitmap
'''
assert old in s
s = s.replace(old, new, 1)

old = '''                _liveStroke.update { s -> if (s.bitmap === previewBitmap) s.copy(layerId = null, bitmap = null) else s }
                scheduleDiskSave(layerId, target, layer.uri)
'''
new = '''                _liveStroke.update { s -> if (s.bitmap === previewBitmap) s.copy(layerId = null, bitmap = null) else s }
                retainedGpuDisplay?.close()
                scheduleDiskSave(layerId, target, layer.uri)
'''
assert old in s
s = s.replace(old, new, 1)

p.write_text(s)
