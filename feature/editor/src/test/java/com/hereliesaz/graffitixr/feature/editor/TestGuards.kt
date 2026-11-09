package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset

/**
 * Permissive surface-stroke guards for tests that exercise feed mechanics (cutting, bridging, gaps)
 * and not the guards themselves. The stroke feeds take their finger guard and visibility check as
 * required parameters, so a test says out loud that it accepts everything. Test sources only: this
 * is not reachable from production code, and [StrokeFeedGuardsRequiredTest] fails if a default comes back.
 */
internal object TestGuards {
    val planeSampleOk: (Offset, Offset) -> Boolean = { _, _ -> true }
    val planeJoinOk: (Offset, Offset, Offset, Offset) -> Boolean = { _, _, _, _ -> true }

    fun <S> viewSampleOk(): (ViewSample<S>) -> Boolean = { true }
    fun <S> viewJoinOk(): (ViewSample<S>, ViewSample<S>) -> Boolean = { _, _ -> true }
}

/** [planeSegments] with the permissive [TestGuards]. */
internal fun acceptAllSegments(path: List<Offset>, toFlat: (Offset) -> Offset?) =
    planeSegments(path, TestGuards.planeSampleOk, TestGuards.planeJoinOk, toFlat)
