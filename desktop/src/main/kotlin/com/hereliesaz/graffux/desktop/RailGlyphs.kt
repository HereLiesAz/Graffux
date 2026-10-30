package com.hereliesaz.graffux.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/**
 * The rail glyphs this app needs, copied path for path from Android's `RailIcons` (`core:design`, an
 * Android library this plain-JVM app cannot depend on): the mockup's 24-unit viewBox and 1.8 stroke,
 * white so an `Icon` tint recolours them.
 */
internal object RailGlyphs {
    private const val STROKE = 1.8f

    private fun stroked(name: String, vararg paths: String, width: Float = STROKE, round: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    fill = null,
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = width,
                    strokeLineCap = if (round) StrokeCap.Round else StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                    strokeLineMiter = 4f,
                )
            }
        }.build()

    val Plus: ImageVector by lazy { stroked("rail.plus", "M12 5v14M5 12h14") }

    /** The hidden-layer badge: stroke 2.2, round caps. */
    val EyeOff: ImageVector by lazy {
        stroked(
            "rail.eyeOff",
            "M3 3l18 18M10.6 6.1A10 10 0 0122 12a17 17 0 01-3 3.6M6.2 6.2A17 17 0 002 12s4 7 10 7a10 10 0 004.2-.9",
            width = 2.2f,
            round = true,
        )
    }

    /** The Graffux mark in its own colours (draw it with `Image`). The SVG's viewBox is 96 96 320 320. */
    val LogoMark: ImageVector by lazy {
        ImageVector.Builder("rail.logo", 26.dp, 26.dp, 320f, 320f).apply {
            group(translationX = -96f, translationY = -96f) {
                addPath(
                    pathData = addPathNodes("m334.5 177.5l-157 157"),
                    stroke = SolidColor(Color(0xFF00E0D0)),
                    strokeLineWidth = 92.2f,
                    strokeLineCap = StrokeCap.Round,
                )
                addPath(
                    pathData = addPathNodes("m177.5 177.5l157 157"),
                    stroke = SolidColor(Color(0xFFFF2D78)),
                    strokeLineWidth = 92.2f,
                    strokeLineCap = StrokeCap.Round,
                )
                addPath(
                    pathData = addPathNodes(
                        "m315 135.9c23 7 15.9 123.8 14.4 146.4-1.4 32.6-19.8 37.6-19.8 61.4 0 6.4 2.5 12.5 7 16.9 4.5 4.5 10.6 7 16.9 7 6.3 0 12.4-2.5 16.9-7 4.5-4.4 7-10.5 7-16.9 0-23.8-18.4-24.8-19.8-61.4-0.6-26.3-7.6-150.4 19.4-144.4-7-6-32-8-42-2z",
                    ),
                    fill = SolidColor(Color.White),
                )
            }
        }.build()
    }
}
