@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName", "FunctionNaming", "LongMethod", "TooManyFunctions")

package com.hereliesaz.graffux

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.graffitixr.design.theme.Amber

/*
 * The rails' building blocks: a content-height rounded pill, its 44dp buttons and the hairline
 * between groups of them. Both rails, the floating toolboxes and the desktop build draw with these,
 * so a rail looks the same wherever it is.
 */

/** Pill fill: near-black, faintly translucent, so the artwork reads through at the edges. */
internal val RailFill = Color(0xE6161618)

/** Pill and menu outline. */
internal val RailBorder = Color(0xFF2A2A2E)

/** What a rail draws its glyphs and labels in. */
internal val RailInk = Color(0xFFEDEDED)

/** The brush-size chip's fill. */
internal val RailChip = Color(0xFF232326)

/** The pill's width (border included); also what a floating window keeps clear of. */
internal val RailWidth = 56.dp

/** One rail button, as drawn (the mockup's 44). */
internal val RailButtonSize = 44.dp

/**
 * One rail button's touch target: the 48dp accessibility minimum. The 2dp around the drawn button
 * comes out of the gap between items, so the rail's pitch (and look) is unchanged.
 */
internal val RailTouchSize = 48.dp

/** Space between a rail and what opens beside it. */
internal val RailEdgeGap = 8.dp

/** Space between a rail and the side of the screen it sits on. */
internal val RailSideInset = 12.dp

/** The tool rail's distance from the top; the logo's too. */
internal val RailTopInset = 16.dp

/** The layers rail's distance from the top: below the logo, with the same 12 between. */
internal val LayersRailTopInset = RailTopInset + RailButtonSize + RailSideInset

/**
 * Space between neighbouring touch targets in a rail: 2, so drawn buttons still sit 6 apart
 * (48 - 44 + 2) as the mockup has them.
 */
private val RailItemGap = 2.dp

private val PillShape = RoundedCornerShape(RailWidth / 2)
private val ButtonShape = CircleShape

/**
 * A vertical rail: as tall as its buttons, never taller than the space it is given (it scrolls
 * then), rounded at both ends.
 */
@Suppress("FunctionNaming")
@Composable
internal fun RailPill(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RailItemGap),
        modifier = modifier
            .width(RailWidth)
            .clip(PillShape)
            .background(RailFill)
            .border(1.dp, RailBorder, PillShape)
            .verticalScroll(scrollState)
            // 6 inside the 1dp border, less the 2 each 48dp target adds.
            .padding(vertical = 5.dp),
        content = content,
    )
}

/** A hairline between groups of buttons in a [RailPill]. */
@Suppress("FunctionNaming")
@Composable
internal fun RailDivider() {
    Box(
        Modifier
            .padding(vertical = 4.dp)
            .width(28.dp)
            .height(1.dp)
            .background(RailBorder),
    )
}

/** How a [RailButton] is lit: where you are ([active]) and what is true of it ([secondary]). */
internal data class RailLight(val active: Boolean = false, val secondary: Boolean = false)

/**
 * One rail button. [label] is what TalkBack reads and what the long-press menu is titled; the
 * button draws only [content]. Active takes the filled container and the accent ring; secondary
 * (a linked layer, a live selection) takes an amber ring. [badge] sits at the top-right corner.
 * [onClick] null leaves the touch to the caller.
 */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun RailButton(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    light: RailLight = RailLight(),
    enabled: Boolean = true,
    badge: String? = null,
    onLongClick: (() -> Unit)? = null,
    testTag: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(RailTouchSize)
            // Null when the caller handles the touch itself (a layer row, which also drags).
            // No onClickLabel: it repeated the label ("Undo, button, double tap to Undo").
            .then(
                if (onClick == null) Modifier else Modifier.combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onLongClick = onLongClick,
                    onClick = onClick,
                ),
            )
            .semantics {
                contentDescription = label
                selected = light.active
            }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
    ) {
        RailButtonFace(light, accent, badge, content)
    }
}

@Suppress("FunctionNaming")
@Composable
private fun RailButtonFace(light: RailLight, accent: Color, badge: String?, content: @Composable BoxScope.() -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(RailButtonSize)
            .clip(ButtonShape)
            .background(if (light.active) accent.copy(alpha = ACTIVE_FILL_ALPHA) else Color.Transparent)
            .border(
                width = if (light.active || light.secondary) 1.5.dp else 0.dp,
                color = when {
                    light.active -> accent
                    light.secondary -> Amber
                    else -> Color.Transparent
                },
                shape = ButtonShape,
            ),
    ) {
        content()
        if (badge != null) {
            Text(
                badge,
                color = Color.White,
                fontSize = 9.sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 1.dp, end = 1.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 3.dp),
            )
        }
    }
}

/** A Graffux glyph at rail size (22dp, as the mockup draws them), tinted ink or the accent. */
@Suppress("FunctionNaming")
@Composable
internal fun RailGlyph(icon: Int, active: Boolean = false) {
    Icon(
        painterResource(icon),
        contentDescription = null,
        tint = if (active) MaterialTheme.colorScheme.primary else RailInk,
        modifier = Modifier.size(RailGlyphSize),
    )
}

/** One of the mockup's own rail glyphs ([com.hereliesaz.graffitixr.design.RailIcons]). */
@Suppress("FunctionNaming")
@Composable
internal fun RailGlyph(icon: ImageVector, active: Boolean = false) {
    Icon(
        icon,
        contentDescription = null,
        tint = if (active) MaterialTheme.colorScheme.primary else RailInk,
        modifier = Modifier.size(RailGlyphSize),
    )
}

private val RailGlyphSize = 22.dp

private const val ACTIVE_FILL_ALPHA = 0.22f
private const val DISABLED_ALPHA = 0.3f
