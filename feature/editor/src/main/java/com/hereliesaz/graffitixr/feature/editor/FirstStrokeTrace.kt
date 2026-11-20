package com.hereliesaz.graffitixr.feature.editor

/**
 * Always-on, near-free timing of the first stroke after a paint tool is picked on a canvas with no layer yet
 * (`adb logcat -s GraffuxStroke`): how long the new layer took to land, how many touches were read as gestures
 * meanwhile (the tool is NONE until the layer lands, so they pan or zoom instead of painting), and how long after
 * the layer landed the first dab was presented. One line per milestone, once per request.
 */
internal class FirstStrokeTrace(
    private val clockNs: () -> Long = System::nanoTime,
    private val log: (String) -> Unit,
) {
    private var pickedAt = NONE
    private var landedAt = NONE
    private var swallowed = 0
    private var dabLogged = false

    /** A paint tool was picked and a layer must be made for it first. */
    @Synchronized
    fun toolPicked(tool: Any?) {
        pickedAt = clockNs()
        landedAt = NONE
        swallowed = 0
        dabLogged = false
        log("first stroke: tool $tool picked, waiting for a layer")
    }

    /** A touch was read as a gesture (or ignored) instead of a stroke. Counted only while a layer is awaited; a plain
     *  counter bump (no log, no allocation) - the total is reported once, when the layer lands. */
    @Synchronized
    fun touchSwallowed() {
        if (pickedAt == NONE || landedAt != NONE) return
        swallowed++
    }

    /** A one-off event worth one line (a blank-layer write that failed twice, say). */
    fun note(message: String) = log("first stroke: $message")

    /** The new layer reached the editor state (and with it the tool). */
    @Synchronized
    fun layerLanded() {
        if (pickedAt == NONE || landedAt != NONE) return
        landedAt = clockNs()
        log("first stroke: layer landed ${sincePicked()} ms after the tool was picked; $swallowed touches swallowed")
    }

    /** The first dab of the stroke reached the screen. */
    @Synchronized
    fun firstDabPresented() {
        if (landedAt == NONE || dabLogged) return
        dabLogged = true
        val sinceLanded = (clockNs() - landedAt) / NANOS_PER_MS
        log("first stroke: first dab presented $sinceLanded ms after the layer landed (${sincePicked()} ms after pick)")
        pickedAt = NONE
    }

    private fun sincePicked() = (clockNs() - pickedAt) / NANOS_PER_MS

    private companion object {
        const val NONE = -1L
        const val NANOS_PER_MS = 1_000_000L
    }
}
