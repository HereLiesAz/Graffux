package com.hereliesaz.graffux.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hereliesaz.aznavrail.AzWindow
import com.hereliesaz.aznavrail.AzWindowState

private val MaxWindowWidth = 320.dp
private val MaxWindowHeight = 480.dp

/**
 * Desktop's copy of Android's `core:design` `FloatingWindow` — a floating, draggable,
 * collapsible panel for tool options, Procreate-style, instead of the fixed top-toolbar Row this
 * app used before. Both wrap the same `AzWindow`/`AzWindowState` primitive from `aznavrail-cmp`
 * (11.45, already a `:desktop` dependency), so this is a reuse of Android's real floating-window
 * mechanism, not a second hand-built implementation of dragging/clamping.
 *
 * Simpler than Android's version: no `RailInset`-based obstruction avoidance (this app's rail is
 * a translucent overlay, not docked/inset the way Android's is, so there's no strip of the window
 * a floating panel needs to be kept clear of) and no mount-time re-clamp effect — `AzWindow`
 * itself (11.38+) already keeps a window onscreen. See `DESKTOP.md`'s UI-parity section.
 */
@Composable
fun FloatingWindow(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialOffset: Offset = Offset(120f, 120f),
    state: AzWindowState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val internalState = remember { AzWindowState(initialOffset.x, initialOffset.y, false) }
    val windowState = state ?: internalState

    AzWindow(
        modifier = modifier
            .zIndex(10f)
            .widthIn(min = 220.dp, max = MaxWindowWidth)
            .heightIn(max = MaxWindowHeight),
        title = title,
        state = windowState,
        accent = MaterialTheme.colorScheme.outlineVariant,
        surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        minimizable = false,
        onDismiss = onDismiss,
        obstruction = null,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            content = content,
        )
    }
}
