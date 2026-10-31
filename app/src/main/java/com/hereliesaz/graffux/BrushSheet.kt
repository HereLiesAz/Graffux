package com.hereliesaz.graffux

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.hereliesaz.graffitixr.common.model.Tool

/**
 * The sheet open on the Brushes page with its carousel up. A brush tab already chosen is kept;
 * anything else (photo tabs, a tool group) gives way to brushes.
 */
internal fun CarouselUi.openOnBrushes(): CarouselUi = copy(
    category = CarouselCategory.BRUSHES,
    sheetOpen = true,
    tab = tab.takeIf { category == CarouselCategory.BRUSHES && !photo },
    showingCarousel = true,
    photo = false,
)

/**
 * Whenever the brush becomes the tool in hand — the rail, a keyboard shortcut, undo restoring it,
 * another tool handing back to it, or the app starting with it — [onOpen] puts up the brush sheet.
 * The last tool seen survives rotation, so turning the device does not reopen a sheet the user shut.
 */
@Composable
internal fun brushOpensSheet(activeTool: Tool, onOpen: () -> Unit) {
    var lastTool by rememberSaveable { mutableStateOf<Tool?>(null) }
    LaunchedEffect(activeTool) {
        if (activeTool == Tool.BRUSH && lastTool != Tool.BRUSH) onOpen()
        lastTool = activeTool
    }
}
