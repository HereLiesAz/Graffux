package com.hereliesaz.graffitixr.feature.editor

/** What the project dialog shows; null in [EditorViewModel.projectGate] means it is not shown. */
data class ProjectGateState(
    val defaultName: String,
    /** The canvas size the dialog starts on: this screen's, as projects have always opened. */
    val defaultWidth: Int = 1080,
    val defaultHeight: Int = 1920,
    /** "Saving…" / "Loading…" while the chosen action (and calibration, capped) finishes. */
    val busyLabel: String? = null,
)
