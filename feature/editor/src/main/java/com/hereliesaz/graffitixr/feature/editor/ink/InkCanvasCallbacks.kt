package com.hereliesaz.graffitixr.feature.editor.ink

/**
 * What InkBrushCanvas reports back besides the finished stroke: the TEMPORARY feel and prediction
 * measurements, tagged with the Jetpack Ink engine by the receiver. Grouped so the composable's
 * parameter list stays readable; every member defaults to a no-op.
 */
internal class InkCanvasCallbacks(
    /** One Ink input's touch-to-paint, ms; [strokeStart] marks the stroke's first input. */
    val onLatency: (latencyMs: Double, strokeStart: Boolean) -> Unit = { _, _ -> },
    /** A real sample reached the editor ([eventUptimeMs] = its hardware timestamp). */
    val onSampleAccepted: (eventUptimeMs: Long) -> Unit = {},
    /** The stabilizer moved a sample this far from the raw point, world px. */
    val onStabilized: (lagPx: Float) -> Unit = {},
    /** A Brush stroke ended; the tournament's ranking report and the display's refresh rate. */
    val onPredictionRanked: (report: String, refreshRateHz: Float) -> Unit = { _, _ -> },
    /** The tournament is being discarded (canvas left composition or Settings rebuilt it). */
    val onPredictionSessionEnd: (report: String, refreshRateHz: Float) -> Unit = { _, _ -> },
    /** Measured touch-to-paint lag, ms, that the tournament's predictions reach ahead by. */
    val predictionLeadMs: () -> Long? = { null },
)
