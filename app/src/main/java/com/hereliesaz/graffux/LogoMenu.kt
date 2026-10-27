@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName", "FunctionNaming", "LongMethod", "TooManyFunctions")

package com.hereliesaz.graffux

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

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
    Column(horizontalAlignment = Alignment.End, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(RailWidth)
                .clip(CircleShape)
                .background(RailFill)
                .border(1.dp, RailBorder, CircleShape)
                .clickable(role = Role.Button, onClickLabel = if (open) "Close menu" else "Open menu") {
                    onOpenChange(!open)
                }
                .semantics { contentDescription = "Graffux menu" }
                .testTag("logo.menu"),
        ) {
            Image(painterResource(R.drawable.graffuxicon), contentDescription = null, modifier = Modifier.size(34.dp))
        }
        if (open) {
            MenuPanel(rows = rows, onDismiss = { onOpenChange(false) }, modifier = Modifier.padding(top = RailEdgeGap))
        }
    }
}
