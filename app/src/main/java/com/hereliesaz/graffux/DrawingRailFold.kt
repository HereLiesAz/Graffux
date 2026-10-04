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
 * The signal is the derived "UI hidden for drawing" flag ([rememberDrawingUiHidden]): true from
 * stroke start until [DRAWING_UI_RETURN_DELAY_MS] after the stroke ends or is cancelled, with a new
 * stroke inside that window cancelling the return. It is built from `StrokeGate.strokeActive`, a
 * snapshot-state flag the drawing surfaces already maintain, so nothing is added to the stroke path.
 * Multi-finger pan/zoom never sets it, so they do not fold anything.
 *
 * Speed: AzNavRail 11.52 exposes no duration or animation spec for the `isFoldedUp` fold or for a
 * host's expand/collapse (`isFoldedUp` is a plain Boolean; `azKinetics` timings drive only the menu
 * words), so those run at the library's own speed. The hold is the part this app controls.
 */
internal object DrawingRailFold {

    /** Whether the main rail is folded: the user's own fold, or a stroke in progress or just finished. */
    fun mainRailFolded(userFolded: Boolean, drawingHidden: Boolean): Boolean = userFolded || drawingHidden

    /** The `expandWhen` condition for a right-rail host. */
    fun hostExpandWhen(userExpanded: Boolean, drawingHidden: Boolean): Boolean = userExpanded && !drawingHidden

    /**
     * Whether an `onExpandedChange` report should be persisted as the user's choice. A collapse
     * reported while the UI is hidden for drawing is the library applying our own [hostExpandWhen] fold, not the
     * user; saving it would make the fold permanent and the host would never come back.
     */
    fun persistExpansionChange(expanded: Boolean, drawingHidden: Boolean): Boolean = expanded || !drawingHidden
}
