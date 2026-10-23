package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The colour a PNG export is composited over: the canvas background the user picked, so the file
 * matches the page they were looking at. A fully transparent background stays transparent, which
 * is the one case the PNG keeps alpha.
 */
internal fun exportBackgroundArgb(canvasBackground: Color): Int =
    if (canvasBackground.alpha == 0f) android.graphics.Color.TRANSPARENT else canvasBackground.toArgb()

/** The confirmation for a saved PNG, saying whether the background went with it. */
internal fun exportSavedMessage(backgroundArgb: Int): String =
    if (backgroundArgb ushr 24 == 0) {
        "Image saved to gallery, with a transparent background"
    } else {
        "Image saved to gallery, with the canvas background"
    }
