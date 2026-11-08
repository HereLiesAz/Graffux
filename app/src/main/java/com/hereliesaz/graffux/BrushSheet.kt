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

/**
 * What a finger on the canvas does to the sheet. Any other sheet closes; the brush's own sheet
 * stays open, because it only hides while the finger is down (`hiddenForDrawing`) and comes back
 * after the stroke. Closing it here left the brush in hand with no sheet until it was re-picked.
 */
internal fun CarouselUi.afterCanvasTouch(activeTool: Tool): CarouselUi {
    val brushSheet = activeTool == Tool.BRUSH && !photo && category == CarouselCategory.BRUSHES
    return if (sheetOpen && !brushSheet) copy(sheetOpen = false, showingCarousel = false) else this
}

/**
 * Whether the brush sheet is up: shown, open, on a brush-context page (Favorites, Brushes, Ink),
 * with the brush or eraser in hand.
 * The pack wheel stays open exactly while this holds.
 */
internal fun CarouselUi.brushSheetUp(showCarousel: Boolean, activeTool: Tool): Boolean =
    showCarousel && sheetOpen && !photo && carouselContextOf(category) == CarouselContext.BRUSHES &&
        activeTool in PACK_WHEEL_TOOLS

/**
 * Whether the carousel sheet is actually on screen: wanted, not hidden for a capture or docked
 * into a side panel, and not displaced by an open editor panel (a tool group or the design tabs
 * show over one).
 */
@Suppress("LongParameterList")
internal fun carouselVisible(
    showCarousel: Boolean,
    hideUiForCapture: Boolean,
    panelDocked: Boolean,
    panelOpen: Boolean,
    toolGroup: Boolean,
    designMode: Boolean,
): Boolean = showCarousel && !hideUiForCapture && !panelDocked && (!panelOpen || toolGroup || designMode)
