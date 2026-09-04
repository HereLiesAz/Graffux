package com.hereliesaz.graffux.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density

/**
 * Real icons for the rail, loaded from the same portable master SVGs Android's `GraffuxIcons`
 * (`core/design/.../GraffuxIcons.kt`) generates its `@DrawableRes` set from
 * (the SVGs under `branding/icons/masters`) — copied into `desktop/src/main/resources/icons` rather than
 * text-only rail labels. `GraffuxIcons` itself exposes Android drawable-resource IDs, which have
 * no meaning on desktop, so this loads the same source SVGs directly instead.
 */
object GraffuxDesktopIcons {
    @Composable
    fun undo(): Painter = svgPainter("icons/undo.svg")

    @Composable
    fun redo(): Painter = svgPainter("icons/redo.svg")

    @Composable
    fun clear(): Painter = svgPainter("icons/clear-history.svg")

    @Composable
    fun save(): Painter = svgPainter("icons/document-save.svg")

    @Composable
    fun brush(): Painter = svgPainter("icons/brush.svg")
}

@Composable
private fun svgPainter(resourcePath: String, density: Density = Density(1f)): Painter {
    return remember(resourcePath) {
        val stream = requireNotNull(object {}.javaClass.classLoader.getResourceAsStream(resourcePath)) {
            "Missing icon resource: $resourcePath"
        }
        stream.use { loadSvgPainter(it, density) }
    }
}
