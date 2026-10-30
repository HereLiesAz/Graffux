package com.hereliesaz.graffux

/**
 * One piece of chrome that system Back can close: [open] says whether it is up, [close] shuts it.
 *
 * MainActivity lists them topmost first, and a single BackHandler closes the first one open, so
 * Back unwinds menu -> layer menu -> window -> panel -> sheet instead of leaving the app from under
 * an open panel. Only when nothing is open does Back reach the Activity.
 */
internal class BackLayer(val open: Boolean, val close: () -> Unit)

/** The layer Back should close now, or null when nothing is open. */
internal fun List<BackLayer>.topmost(): BackLayer? = firstOrNull { it.open }

/**
 * When each floating window opened, so Back can close them in the order they are stacked:
 * FloatingWindow gives every newly opened window a z-index above all the open ones, so the most
 * recently opened window is the one on top. Plain (non-state) bookkeeping, updated during
 * composition by [track].
 */
internal class WindowOpenOrder {
    private var next = 0L
    private val openedAt = HashMap<String, Long>()

    /** Records [key] as opened now (the first time [open] is seen true) or forgets it once shut. */
    fun track(key: String, open: Boolean) {
        if (open) openedAt.getOrPut(key) { next++ } else openedAt.remove(key)
    }

    /**
     * [windows] (key to its Back layer), topmost first: the latest opened first; ones not tracked
     * as open go last, in their given order.
     */
    fun topFirst(windows: List<Pair<String, BackLayer>>): List<BackLayer> {
        windows.forEach { (key, layer) -> track(key, layer.open) }
        return windows.sortedByDescending { (key, _) -> openedAt[key] ?: -1L }.map { it.second }
    }
}
