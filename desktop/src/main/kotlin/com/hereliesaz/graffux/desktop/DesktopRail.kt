@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName", "FunctionNaming", "LongMethod", "TooManyFunctions")

package com.hereliesaz.graffux.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
internal val RailInk = Color(0xFFEDEDED)
internal val ShortcutInk = Color(0xFF8A8A90)

/** The mockup's accent: an active rail button's ring and fill, a lit menu row. */
internal val RailAccent = Color(0xFFFF3EA5)

/** Drawn size of a rail button; its hit target is [RailTargetSize] around it. */
internal val RailButtonSize = 44.dp
internal val RailTargetSize = 48.dp
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
    val accent = RailAccent
    // A 48dp target around the 44dp drawn button.
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(RailTargetSize)
            .clip(ButtonShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics {
                contentDescription = label
                selected = active
            }
            .alpha(if (enabled) 1f else 0.3f),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(RailButtonSize)
                .clip(ButtonShape)
                .background(if (active) accent.copy(alpha = 0.22f) else Color.Transparent)
                .border(if (active) 1.5.dp else 0.dp, if (active) accent else Color.Transparent, ButtonShape),
        ) {
            RailButtonContent(icon, text)
        }
        if (shortcut != null) {
            // The mockup's key label: mono 9, grey, hanging 2 past the drawn button's bottom-right.
            Text(
                shortcut,
                color = ShortcutInk,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

@Composable
private fun BoxScope.RailButtonContent(icon: Painter?, text: String?) {
    when {
        icon != null -> Icon(icon, contentDescription = null, tint = RailInk, modifier = Modifier.size(22.dp))
        text != null -> Text(text, color = RailInk, fontSize = if (text.length == 1) 20.sp else 12.sp, maxLines = 1)
    }
}

/**
 * A list of labelled rows beside the rail; [onPick] runs one, [lit] marks the current one, and
 * [trailing] (by row) is a grey mono note at the row's end: its shortcut, or › for a page.
 */
@Composable
internal fun RailMenu(
    title: String?,
    rows: List<String>,
    lit: String?,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trailing: List<String?> = emptyList(),
    enabled: List<Boolean> = emptyList(),
) {
    Column(
        modifier = modifier
            .widthIn(min = 180.dp, max = 280.dp)
            .clip(MenuShape)
            .background(RailFill)
            .border(1.dp, RailBorder, MenuShape)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
    ) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        rows.forEachIndexed { index, row ->
            if (row == MENU_DIVIDER) {
                Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(RailBorder))
                return@forEachIndexed
            }
            val on = enabled.getOrElse(index) { true }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .clickable(enabled = on) { onPick(index) }
                    .alpha(if (on) 1f else 0.35f)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    if (row == lit) "$row ✓" else row,
                    color = if (row == lit) RailAccent else Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                trailing.getOrNull(index)?.let { note ->
                    Text(note, color = ShortcutInk, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

/** A row of [RailMenu] drawn as a hairline. */
internal const val MENU_DIVIDER = "\u0000—"
