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
