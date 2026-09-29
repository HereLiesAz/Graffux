package com.hereliesaz.graffux

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import com.hereliesaz.graffitixr.design.GraffuxIcons
import com.hereliesaz.graffitixr.feature.editor.BrushPreview

/**
 * The star in a hero or medium card's corner: outlined when not a favorite, filled when it is.
 * Its own click target, so tapping it toggles the favorite without also selecting the item.
 */
@Suppress("FunctionNaming")
@Composable
internal fun FavoriteToggle(
    entry: CarouselEntry,
    tint: Color,
    onToggleFavorite: (CarouselEntry) -> Unit,
    modifier: Modifier,
) {
    IconToggleButton(
        checked = entry.favorite,
        onCheckedChange = { onToggleFavorite(entry) },
        modifier = modifier.size(StarTouchSize),
    ) {
        Icon(
            if (entry.favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
            contentDescription = if (entry.favorite) "Remove from favorites" else "Add to favorites",
            tint = tint,
            modifier = Modifier.size(StarSize),
        )
    }
}

/** The card's tip visual — see [CarouselTip] for what each kind shows. */
@Suppress("FunctionNaming")
@Composable
internal fun TipVisual(tip: CarouselTip, content: CarouselContent, tint: Color, size: Dp) {
    when (tip) {
        is CarouselTip.Stamp -> {
            val bitmap = content.extensionTips[tip.extensionId]
            if (bitmap != null) {
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn),
                    modifier = Modifier.size(size),
                )
            } else {
                Icon(painterResource(GraffuxIcons.BrushImport), null, tint = tint, modifier = Modifier.size(size))
            }
        }
        is CarouselTip.Round -> RoundTip(tip, tint, size)
        is CarouselTip.Ink -> Icon(painterResource(tip.icon), null, tint = tint, modifier = Modifier.size(size))
        is CarouselTip.Glyph -> Icon(painterResource(tip.icon), null, tint = tint, modifier = Modifier.size(size))
    }
}

/**
 * A generated round tip, with the same falloff StampBrushRenderer's generated-round path and
 * BrushPreview use: solid out to `hardness`, then fading to transparent at the edge.
 */
@Suppress("FunctionNaming")
@Composable
private fun RoundTip(tip: CarouselTip.Round, tint: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        val hardness = tip.hardness.coerceIn(0f, MAX_HARDNESS_STOP)
        val ratio = tip.tipRatio.coerceIn(MIN_TIP_RATIO, 1f)
        rotate(tip.angleDeg) {
            scale(scaleX = 1f, scaleY = ratio) {
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to tint,
                        hardness to tint,
                        1f to tint.copy(alpha = 0f),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                )
            }
        }
    }
}

internal const val DETAIL_ALPHA = 0.72f
private const val MAX_HARDNESS_STOP = 0.999f
private const val MIN_TIP_RATIO = 0.1f
