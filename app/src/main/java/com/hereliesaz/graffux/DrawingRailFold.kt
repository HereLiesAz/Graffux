package com.hereliesaz.graffux

/**
 * The rails get out of the way while a stroke is being painted and come back when it ends.
 *
 * Both halves use what AzNavRail already provides rather than a hand-rolled animation:
 *
 * - **Main rail**: `AzNavHostScope.isFoldedUp`, the library's own "fold the rail away" switch,
 *   already used by the four-finger full-screen gesture (`hideUiForCapture`). The rail is folded
 *   while either reason holds, so a stroke never unfolds a rail the user folded on purpose.
 * - **Right rail** (the OPPOSITE-anchored `grp.layers` / `grp.brushRail` unattached hosts): the host
 *   builders' `expandWhen` condition. Per the guide, a true→false edge collapses the host and a
 *   false→true edge re-expands it. The condition is "the user left it expanded AND no stroke is
 *   live", so a host the user collapsed stays collapsed.
 *
 * The signal is `StrokeGate.strokeActive`, a snapshot-state flag the drawing surfaces already set on
 * stroke start and clear on stroke end, cancel (a second finger landing) and dispose. Reading it adds
 * nothing to the stroke path. Multi-finger pan/zoom never sets it, so they do not fold anything.
 */
internal object DrawingRailFold {

    /** Whether the main rail is folded: the user's own fold, or a stroke in progress. */
    fun mainRailFolded(userFolded: Boolean, strokeActive: Boolean): Boolean = userFolded || strokeActive

    /** The `expandWhen` condition for a right-rail host. */
    fun hostExpandWhen(userExpanded: Boolean, strokeActive: Boolean): Boolean = userExpanded && !strokeActive

    /**
     * Whether an `onExpandedChange` report should be persisted as the user's choice. A collapse
     * reported while a stroke is live is the library applying our own [hostExpandWhen] fold, not the
     * user; saving it would make the fold permanent and the host would never come back.
     */
    fun persistExpansionChange(expanded: Boolean, strokeActive: Boolean): Boolean = expanded || !strokeActive
}
