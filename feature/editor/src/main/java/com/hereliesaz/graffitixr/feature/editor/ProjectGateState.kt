package com.hereliesaz.graffitixr.feature.editor

/** What the project dialog shows; null in [EditorViewModel.projectGate] means it is not shown. */
data class ProjectGateState(
    val defaultName: String,
    /** "Saving…" / "Loading…" while the chosen action (and calibration, capped) finishes. */
    val busyLabel: String? = null,
)
