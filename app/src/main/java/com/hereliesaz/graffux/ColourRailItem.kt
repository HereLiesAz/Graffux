package com.hereliesaz.graffux

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The rail's colour item while the Colour sheet is open: it grows, so the eye goes from the sheet to the
 * swatch it is changing. The sizes live here, apart from the composables, so they are tested directly.
 */

/**
 * The drawn colour swatch while the sheet is open. The pill is [RailWidth] wide with a 1dp border each
 * side, so 54dp is all there is inside it; the touch target ([railTouchSize]) is this plus 4dp and so
 * exactly fills that, and the item can neither overlap the pill's edge nor be clipped by it.
 */
internal val ColourItemOpenFace = 50.dp

/** The swatch's drawn size: [ColourItemOpenFace] while the sheet is open, the usual rail button otherwise. */
internal fun colourItemFace(sheetOpen: Boolean): Dp = if (sheetOpen) ColourItemOpenFace else RailButtonSize

/** A button's touch target for its drawn [face]: the 4dp ring around it, never under the 48dp minimum. */
internal fun railTouchSize(face: Dp): Dp = maxOf(RailTouchSize, face + 4.dp)
