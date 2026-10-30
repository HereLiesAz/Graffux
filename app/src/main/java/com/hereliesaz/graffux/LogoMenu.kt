@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName", "FunctionNaming", "LongMethod", "TooManyFunctions")

package com.hereliesaz.graffux

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.RailIcons

/**
 * The logo menu: the Graffux mark at the top-right corner, and the app's menu dropping from it.
 * [rows] is whatever page the menu is on (the top level, or View…); a [MenuRow.Page] row swaps
 * pages without closing it, anything else closes it.
 */
@Suppress("FunctionNaming")
@Composable
internal fun LogoMenu(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    rows: List<MenuRow>,
    modifier: Modifier = Modifier,
) {
    // The mockup's corner: 16 from the top; 18 from the side while shut, 12 while open, where the
    // button takes a fill and an ink ring and the menu drops from under it, 12 from the side too.
    Column(
        horizontalAlignment = Alignment.End,
        modifier = modifier.padding(
            top = RailTopInset,
            end = (if (open) RailSideInset else LogoShutEndInset) - LogoTouchPad,
        ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                // A 48dp target around the mockup's 44dp button; the 2dp each side comes out of the
                // corner insets so the drawn button stays where the mockup puts it.
                .offset(y = -LogoTouchPad)
                .size(RailTouchSize)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = if (open) "Close menu" else "Open menu") {
                    onOpenChange(!open)
                }
                .semantics { contentDescription = "Graffux menu" }
                .testTag("logo.menu"),
        ) {
            if (open) {
                Box(
                    Modifier.size(RailButtonSize).clip(CircleShape).background(LogoOpenFill).border(1.dp, RailInk, CircleShape),
                )
            }
            Image(RailIcons.LogoMark, contentDescription = null, modifier = Modifier.size(26.dp))
        }
        if (open) {
            MenuPanel(
                rows = rows,
                onDismiss = { onOpenChange(false) },
                modifier = Modifier.offset(y = -LogoTouchPad * 2).padding(top = RailEdgeGap, end = LogoTouchPad),
            )
        }
    }
}

private val LogoShutEndInset = 18.dp
private val LogoTouchPad = 2.dp
private val LogoOpenFill = Color(0xFF161618)
