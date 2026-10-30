@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName", "FunctionNaming", "LongMethod", "TooManyFunctions")

package com.hereliesaz.graffux.desktop

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The desktop rail's building blocks, matching Android's (app/RailPrimitives.kt): a content-height
 * rounded pill, 44dp buttons and hairline dividers. Copied rather than shared — the Android ones
 * live in an Android application module this plain-JVM app cannot depend on.
 */

internal val RailFill = Color(0xE6161618)
internal val RailBorder = Color(0xFF2A2A2E)
internal val RailWidth = 56.dp
internal val RailGap = 8.dp

/** The mockup's corner: 12 from the side, 16 from the top. */
internal val RailSideInset = 12.dp
internal val RailTopInset = 16.dp
private val RailInk = Color(0xFFEDEDED)
private val ShortcutInk = Color(0xFF8A8A90)
private val PillShape = RoundedCornerShape(RailWidth / 2)
private val ButtonShape = CircleShape
private val MenuShape = RoundedCornerShape(14.dp)

@Composable
internal fun RailPill(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .width(RailWidth)
            .clip(PillShape)
            .background(RailFill)
            .border(1.dp, RailBorder, PillShape)
            .verticalScroll(rememberScrollState())
            // 6 inside the 1dp border.
            .padding(vertical = 7.dp),
        content = content,
    )
}

@Composable
internal fun RailDivider() {
    Box(Modifier.padding(vertical = 4.dp).width(28.dp).height(1.dp).background(RailBorder))
}

/**
 * One rail button: [icon] (or [text] when there is none), the accent fill while [active], and
 * [shortcut] — the key that does the same thing — in the corner.
 */
@Composable
internal fun RailButton(
    label: String,
    onClick: () -> Unit,
    icon: Painter? = null,
    text: String? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    shortcut: String? = null,
) {
    val accent = GraffuxColors.HotPink
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(ButtonShape)
            .background(if (active) accent.copy(alpha = 0.22f) else Color.Transparent)
            .border(if (active) 1.5.dp else 0.dp, if (active) accent else Color.Transparent, ButtonShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                selected = active
            }
            .alpha(if (enabled) 1f else 0.3f),
    ) {
        RailButtonContent(icon, text)
        if (shortcut != null) {
            // The mockup's key label: mono 9, grey, hanging 2 past the button's bottom-right.
            Text(
                shortcut,
                color = ShortcutInk,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp),
            )
        }
    }
}

@Composable
private fun BoxScope.RailButtonContent(icon: Painter?, text: String?) {
    when {
        icon != null -> Icon(icon, contentDescription = null, tint = RailInk, modifier = Modifier.size(22.dp))
        text != null -> Text(text, color = RailInk, fontSize = 12.sp, maxLines = 1)
    }
}

/** A list of labelled rows beside the rail; [onPick] runs one, [lit] marks the current one. */
@Composable
internal fun RailMenu(
    title: String,
    rows: List<String>,
    lit: String?,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(min = 180.dp, max = 260.dp)
            .clip(MenuShape)
            .background(RailFill)
            .border(1.dp, RailBorder, MenuShape)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        rows.forEachIndexed { index, row ->
            Text(
                if (row == lit) "$row ✓" else row,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = row == lit, role = Role.RadioButton) { onPick(index) }
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
