package com.hereliesaz.graffux.desktop

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope

/**
 * A thin wrapper over [detectDragGestures] that also forwards each change's own
 * `PointerInputChange.pressure` — Compose Multiplatform reads this from the platform pointer event,
 * which on Windows includes real Windows-Ink pressure for a Surface Pen (`PointerType.Stylus`);
 * elsewhere (mouse, a non-pressure touchpad) it is always `1f`, so [onStart]/[onMove] degrade
 * gracefully to a constant-pressure stroke rather than failing. [onStart] fires with a default
 * pressure of `1f` (drag-start events carry only a position, not the change itself) — the very next
 * [onMove], a frame later, immediately corrects it, which reads as unnoticeable in practice. This
 * intentionally does not read tilt: Compose Multiplatform Desktop does not currently expose stylus
 * tilt, only pressure and [androidx.compose.ui.input.pointer.PointerType] — see DESKTOP.md.
 */
suspend fun PointerInputScope.detectStampGestures(
    onStart: (position: Offset, pressure: Float) -> Unit,
    onMove: (position: Offset, pressure: Float) -> Unit,
    onEnd: () -> Unit,
) {
    detectDragGestures(
        onDragStart = { position -> onStart(position, 1f) },
        onDragEnd = onEnd,
        onDragCancel = onEnd,
        onDrag = { change, _ -> onMove(change.position, change.pressure) },
    )
}
