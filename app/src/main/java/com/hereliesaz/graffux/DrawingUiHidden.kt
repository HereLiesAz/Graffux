package com.hereliesaz.graffux

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * How long everything that hides for a stroke (the main rail's fold, the right-rail hosts, the
 * carousel sheet) waits after the stroke ends or is cancelled before coming back. One value for all
 * of them; tune it here.
 */
internal const val DRAWING_UI_RETURN_DELAY_MS = 1500L

/**
 * Drives the "UI hidden for drawing" flag from [strokeActive]: true the moment a stroke starts, and
 * still true for [holdMs] after it ends. A stroke that starts inside that window cancels the pending
 * return (`collectLatest` drops the waiting `delay`), so nothing flickers back between strokes.
 * Runs until cancelled.
 */
internal suspend fun trackDrawingUiHidden(
    strokeActive: Flow<Boolean>,
    holdMs: Long = DRAWING_UI_RETURN_DELAY_MS,
    onHidden: (Boolean) -> Unit,
) {
    strokeActive.collectLatest { active ->
        if (active) {
            onHidden(true)
        } else {
            delay(holdMs)
            onHidden(false)
        }
    }
}

/**
 * [trackDrawingUiHidden] as Compose state. [strokeActive] is read in a snapshot flow, off the
 * stroke path; only the hidden flag's own flips (two per burst of strokes) reach readers.
 */
@Composable
internal fun rememberDrawingUiHidden(strokeActive: () -> Boolean): State<Boolean> {
    val hidden = remember { mutableStateOf(false) }
    val current by rememberUpdatedState(strokeActive)
    LaunchedEffect(Unit) {
        trackDrawingUiHidden(snapshotFlow { current() }) { hidden.value = it }
    }
    return hidden
}
