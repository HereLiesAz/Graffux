// FILE: core/design/src/main/java/com/hereliesaz/graffitixr/design/components/AzButtonSizing.kt
package com.hereliesaz.graffitixr.design.components

import androidx.compose.ui.unit.dp

/**
 * The height every full-width `com.hereliesaz.aznavrail.AzButton(shape = AzButtonShape.RECTANGLE,
 * modifier = Modifier.fillMaxWidth() / .weight(1f), ...)` in this app must also pass, or its label
 * renders wildly oversized.
 *
 * Why: `AzButton`'s text is `AutoSizeText`, which always maximizes the font to fill whatever box
 * it's given — it has no notion of a normal, fixed button-text size the way a plain Material
 * `Button` does. For a RECTANGLE shape, `AzNavRailButton` internally applies
 * `modifier.width(72.dp).height(40.dp)`: `modifier` is the OUTER wrapper (Compose composes a
 * modifier chain outer-to-inner left-to-right), so when a caller passes `Modifier.fillMaxWidth()`,
 * that outer exact-width constraint wins over the library's inner `.width(72.dp)` — a `SizeModifier`
 * only ever gets to pick a size within the incoming constraint range it's handed, and `fillMaxWidth`
 * hands it a fixed min==max==parent-width range, so 72dp is discarded and the button stretches to
 * the full available width. The `.height(40.dp)` right after it is untouched by that (we didn't
 * specify a height ourselves), so it stays fixed at 40dp — and `AutoSizeText` searches font sizes up
 * to `min(width, height)`, i.e. up to that same 40dp. A short label like "Get" in a now much-wider
 * box has height as its only binding dimension, so it picks a font that nearly fills 40dp tall —
 * roughly double the size of the rest of the UI's normal text, which is exactly the "the Get button
 * is huge" defect this constant exists to fix.
 *
 * Passing `Modifier.fillMaxWidth().height(AzFullWidthButtonHeight)` clamps the SAME way the bug
 * happens — our own outer, more restrictive `.height()` beats the library's inner 40dp for the
 * identical reason `fillMaxWidth` beat its `.width(72.dp)` — pulling `AutoSizeText`'s ceiling down to
 * a size that reads as normal button text once the button's own vertical `contentPadding` (e.g. a
 * card action row's ~6dp) is subtracted from it.
 */
val AzFullWidthButtonHeight = 32.dp
