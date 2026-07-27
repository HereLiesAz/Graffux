package com.hereliesaz.graffitixr.common.model

/** How Animation Assist playback (and GIF export) advances through frames once it reaches the end. */
enum class AnimationLoopMode(val label: String) {
    LOOP("Loop"),
    PING_PONG("Ping-pong"),
    ONCE("Once"),
}
