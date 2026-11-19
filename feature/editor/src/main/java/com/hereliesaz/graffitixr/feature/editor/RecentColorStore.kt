package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The Colour sheet's recent colours and how they reach the settings store.
 *
 * Two races lived in the view-model's first version of this, and both are closed here:
 *
 *  - **Load first.** What an earlier run saved arrives asynchronously. A commit or delete that lands before
 *    it is applied to the screen at once (so the sheet never lags) and also queued; [onLoaded] replays the
 *    queue over the stored colours, so a delete cannot be undone by the load and a commit sits in front of the
 *    stored ones. Nothing is written until the load is in: writing first would replace the stored colours with
 *    only what this run did.
 *  - **One writer, the latest state.** Every change only asks for a save ([requestSave]); a single
 *    [runWriter] loop turns the requests (conflated, so a burst is one) into writes of whatever the list is
 *    *when the write starts*. Writes never overlap, so an older snapshot cannot finish after a newer one.
 */
class RecentColorStore(private val persist: suspend (encoded: String) -> Unit) {
    private val lock = Any()
    private var loaded = false
    private val queued = ArrayList<(List<Color>) -> List<Color>>()
    private val _colors = MutableStateFlow<List<Color>>(emptyList())
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)

    /** The list, newest first, at most [RECENT_COLORS_MAX]. */
    val colors: StateFlow<List<Color>> = _colors.asStateFlow()

    /** [color] was settled: it goes to the front (opaque, no duplicate). Returns false when it already was. */
    fun commit(color: Color): Boolean {
        val opaque = color.copy(alpha = 1f)
        synchronized(lock) {
            if (_colors.value.firstOrNull() == opaque) return false
            apply { pushRecentColor(it, opaque) }
        }
        return true
    }

    /** [color] was deleted from the list. */
    fun delete(color: Color) {
        synchronized(lock) { apply { removeRecentColor(it, color) } }
    }

    private fun apply(change: (List<Color>) -> List<Color>) {
        _colors.update(change)
        if (loaded) requestSave() else queued += change
    }

    /** What an earlier run stored arrived: it sits under what was done meanwhile, and the result is saved. */
    fun onLoaded(stored: String?) {
        synchronized(lock) {
            if (loaded) return
            _colors.value = queued.fold(decodeRecentColors(stored)) { list, change -> change(list) }
            queued.clear()
            loaded = true
            requestSave()
        }
    }

    private fun requestSave() {
        saveRequests.trySend(Unit)
    }

    /** Writes the latest list for each request, one at a time. Never returns; cancel it with its scope. */
    suspend fun runWriter() {
        for (request in saveRequests) persist(encodeRecentColors(_colors.value))
    }
}
