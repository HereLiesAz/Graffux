package com.hereliesaz.graffux

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushSensor
import com.hereliesaz.graffitixr.common.model.InkUtensil
import com.hereliesaz.graffitixr.design.GraffuxIcons
import com.hereliesaz.graffitixr.feature.editor.BrushPreview

/** What a brush card says: its name, one short type line and at most [MAX_TRAITS] trait tags. */
internal data class BrushCardText(val name: String, val type: String, val traits: List<String>)

private const val MAX_TRAITS = 2

/** Whether [entry] is a brush (built-in, Brush Studio, installed or Ink): it gets the brush card. */
internal fun isBrushCard(entry: CarouselEntry): Boolean = when (entry.action) {
    is CarouselAction.BuiltInBrush, is CarouselAction.CustomBrush,
    is CarouselAction.ExtensionBrush, is CarouselAction.InkUtensilPick,
    -> true
    else -> false
}

/** The brush card's text for [entry]; the same arrangement on every brush card, selected or not. */
internal fun brushCardText(entry: CarouselEntry): BrushCardText = when (val action = entry.action) {
    is CarouselAction.InkUtensilPick -> BrushCardText(entry.label, "Vector ink", inkTraits(action.utensil))
    is CarouselAction.CustomBrush -> BrushCardText(entry.label, "Studio brush", brushTraits(entry.brush))
    is CarouselAction.ExtensionBrush -> BrushCardText(entry.label, "Stamp brush", emptyList())
    else -> BrushCardText(entry.label, "Raster brush", brushTraits(entry.brush))
}

/** What a brush card's gear does: open Brush Studio on which brush, and when. */
internal sealed interface BrushEdit {
    /** A saved Brush Studio brush: open it by id. */
    data class Saved(val id: String) : BrushEdit

    /** The brush already in hand: Studio drafts from it. */
    data object InHand : BrushEdit

    /** An installed brush not in hand: pick it, and open Studio once its tip has loaded. */
    data class AfterLoad(val id: String) : BrushEdit

    /** A built-in not in hand: picking it is immediate, then Studio drafts from it. */
    data object PickThenOpen : BrushEdit
}

/**
 * The gear's plan for [entry], or null for no gear: Ink has no Brush Studio editor (Studio would
 * draft a stamp brush and its apply would switch painting off Ink).
 */
internal fun brushEditPlan(entry: CarouselEntry): BrushEdit? = when (val action = entry.action) {
    is CarouselAction.InkUtensilPick -> null
    is CarouselAction.CustomBrush -> BrushEdit.Saved(action.id)
    is CarouselAction.ExtensionBrush -> if (entry.selected) BrushEdit.InHand else BrushEdit.AfterLoad(action.id)
    is CarouselAction.BuiltInBrush -> if (entry.selected) BrushEdit.InHand else BrushEdit.PickThenOpen
    else -> null
}

private fun inkTraits(utensil: InkUtensil): List<String> = when (utensil) {
    InkUtensil.PEN -> listOf("Pressure")
    InkUtensil.MARKER -> listOf("Opaque")
    InkUtensil.HIGHLIGHTER -> listOf("Chisel", "Translucent")
    InkUtensil.DASHED_LINE -> listOf("Dashed")
}

private const val SOFT_BELOW = 0.5f
private const val SCATTER_ABOVE = 0.2f

private fun brushTraits(brush: AzphaltBrush?): List<String> {
    if (brush == null) return emptyList()
    return listOfNotNull(
        "Pressure".takeIf { brush.dynamics.any { it.sensor == BrushSensor.PRESSURE } },
        "Textured".takeIf { brush.grainPath != null },
        "Airbrush".takeIf { brush.airbrushDabsPerSecond > 0f },
        "Scatter".takeIf { brush.scatter > SCATTER_ABOVE },
        if (brush.hardness < SOFT_BELOW) "Soft" else "Hard",
    ).take(MAX_TRAITS)
}

private val EngineIconSize = 18.dp

/** The stroke keeps at least this much of the card, however large the text around it grows. */
private val MinStrokeHeight = 24.dp
private val EditTouchSize = 32.dp
private val EditIconSize = 18.dp

/**
 * A brush card's face, top to bottom: the name (with the star on the right), the live stroke at
 * the current size and colour edge to edge in the largest area, the type line and trait tags, and
 * the engine icon with the edit button (Brush Studio) at the foot. [horizontalInset] keeps the
 * text inside a carousel card's visible mask; the stroke runs the full width regardless.
 */
@Suppress("FunctionNaming", "LongParameterList", "LongMethod")
@Composable
internal fun BrushCardFace(
    entry: CarouselEntry,
    content: CarouselContent,
    tint: Color,
    onEntryClick: (CarouselEntry) -> Unit,
    onToggleFavorite: (CarouselEntry) -> Unit,
    modifier: Modifier = Modifier,
    horizontalInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val text = brushCardText(entry)
    Column(
        modifier
            .fillMaxSize()
            .clickable(role = Role.Tab) { onEntryClick(entry) }
            .testTag("carousel.brushcard"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = horizontalInset + 10.dp, end = horizontalInset, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text.name,
                color = tint,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("carousel.brushcard.name"),
            )
            FavoriteToggle(entry, tint, onToggleFavorite, Modifier)
        }
        CardStroke(entry, content)
        Column(Modifier.padding(horizontal = horizontalInset + 10.dp)) {
            Text(
                text.type,
                color = tint.copy(alpha = DETAIL_ALPHA),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier.testTag("carousel.brushcard.type"),
            )
            if (text.traits.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                    text.traits.forEach { TraitTag(it, tint) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = horizontalInset + 10.dp, end = horizontalInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.testTag("carousel.brushcard.engine")) {
                TipVisual(carouselTip(entry), content, tint, EngineIconSize)
            }
            Spacer(Modifier.weight(1f))
            content.onEdit?.takeIf { brushEditPlan(entry) != null }?.let { edit ->
                IconButton(
                    onClick = { edit(entry) },
                    modifier = Modifier.size(EditTouchSize).testTag("carousel.brushcard.edit"),
                ) {
                    Icon(
                        painterResource(GraffuxIcons.Settings),
                        contentDescription = stringResource(R.string.brush_edit),
                        tint = tint,
                        modifier = Modifier.size(EditIconSize),
                    )
                }
            } ?: Spacer(Modifier.size(EditTouchSize))
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun TraitTag(label: String, tint: Color) {
    Text(
        label,
        color = tint,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        modifier = Modifier
            .background(tint.copy(alpha = TAG_ALPHA), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .testTag("carousel.brushcard.trait"),
    )
}

private const val TAG_ALPHA = 0.14f

/** The live stroke, edge to edge in what the rows above and below leave of the card. */
@Suppress("FunctionNaming")
@Composable
private fun ColumnScope.CardStroke(entry: CarouselEntry, content: CarouselContent) {
    val own = content.itemSettings(entry)
    val sizePx = own?.size ?: content.heroState?.brushSize
    BoxWithConstraints(
        Modifier.weight(1f).heightIn(min = MinStrokeHeight).fillMaxWidth().testTag("carousel.brushcard.stroke"),
        contentAlignment = Alignment.Center,
    ) {
        val action = entry.action
        val bitmap = (action as? CarouselAction.ExtensionBrush)?.let { content.extensionPreviews[it.id] }
        when {
            entry.brush != null -> BrushPreview(
                entry.brush, content.brushColor, content.secondaryColor, height = maxHeight,
                flow = own?.flow ?: 1f, sizeOverridePx = sizePx,
            )
            action is CarouselAction.InkUtensilPick ->
                InkStroke(action.utensil, content.brushColor, sizePx, Modifier.fillMaxSize())
            bitmap != null -> Image(
                bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private const val INK_MIN_PX = 2f
private const val INK_HEIGHT_SHARE = 0.4f
private const val HIGHLIGHTER_ALPHA = 0.45f
private const val DASH_PX = 18f
private const val INK_DEFAULT_SHARE = 0.25f
private const val INK_AMPLITUDE_SHARE = 0.25f
private const val INK_STEPS = 32
private const val HALF_STEP = 0.5f

/**
 * An Ink utensil's stroke: an S-curve in [color] at [sizePx] (capped to the strip), drawn the
 * utensil's way — tapered for the pen, translucent for the highlighter, dashed for the dashed line.
 */
@Suppress("FunctionNaming")
@Composable
private fun InkStroke(utensil: InkUtensil, color: Color, sizePx: Float?, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        // No room for even the thinnest line: draw nothing (coerceIn would get an empty range).
        if (size.width <= 0f || size.height * INK_HEIGHT_SHARE < INK_MIN_PX) return@Canvas
        val width = (sizePx ?: (size.height * INK_DEFAULT_SHARE)).coerceIn(INK_MIN_PX, size.height * INK_HEIGHT_SHARE)
        val y = size.height / 2f
        val amp = size.height * INK_AMPLITUDE_SHARE
        val turn = 2f * Math.PI.toFloat()
        fun at(t: Float) = Offset(size.width * t, y - amp * kotlin.math.sin(t * turn))
        val steps = INK_STEPS
        when (utensil) {
            InkUtensil.PEN -> for (i in 0 until steps) {
                val t = i / steps.toFloat()
                val taper = kotlin.math.sin(Math.PI.toFloat() * (t + HALF_STEP / steps))
                val w = (width * taper).coerceAtLeast(1f)
                drawLine(color, at(t), at((i + 1) / steps.toFloat()), w, StrokeCap.Round)
            }
            else -> {
                val path = Path().apply {
                    moveTo(at(0f).x, at(0f).y)
                    for (i in 1..steps) at(i / steps.toFloat()).let { lineTo(it.x, it.y) }
                }
                drawPath(
                    path,
                    color.copy(alpha = if (utensil == InkUtensil.HIGHLIGHTER) HIGHLIGHTER_ALPHA else color.alpha),
                    style = Stroke(
                        width = width,
                        cap = if (utensil == InkUtensil.HIGHLIGHTER) StrokeCap.Butt else StrokeCap.Round,
                        pathEffect = if (utensil == InkUtensil.DASHED_LINE) {
                            PathEffect.dashPathEffect(floatArrayOf(DASH_PX, DASH_PX))
                        } else {
                            null
                        },
                    ),
                )
            }
        }
    }
}
