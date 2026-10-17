package com.hereliesaz.graffitixr.common.model

sealed class FeedbackEvent {
    object VibrateSingle : FeedbackEvent()
    object VibrateDouble : FeedbackEvent()
    data class Toast(val message: String) : FeedbackEvent()

    /** A recoverable failure that should be surfaced to the user rather than swallowed. */
    data class Error(val message: String, val cause: Throwable? = null) : FeedbackEvent()
}
