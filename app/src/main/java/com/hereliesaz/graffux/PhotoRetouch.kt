@file:Suppress(
    "MagicNumber", "MaxLineLength", "LongParameterList", "FunctionNaming", "LongMethod",
    "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "TooManyFunctions",
)

package com.hereliesaz.graffux

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.graffitixr.common.photo.HealSpot
import com.hereliesaz.graffitixr.common.photo.PhotoAdjustment
import com.hereliesaz.graffitixr.common.photo.PhotoSlider
import com.hereliesaz.graffitixr.common.photo.RedEyeSpot
import com.hereliesaz.graffitixr.common.photo.SliderFormat
import com.hereliesaz.graffitixr.design.theme.RobotoCondensedFamily
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import kotlin.math.hypot

/*
 * The RETOUCH tabs (the mockup's PhotoHeal and PhotoMask boards): Heal and Red-eye each keep all
 * their spots on one layer; Local paints the mask of the selected adjustment layer. Their tools act
 * on the photo itself, so each has an overlay over the canvas, drawn in the canvas camera like
 * Crop's, which gets world coordinates straight from the touch.
 */

/** The editor calls the Retouch tabs make, in world pixels; an interface tests can fake. */
internal interface RetouchActions {
    fun healSpot(x: Float, y: Float, radius: Float, feather: Float, opacity: Float, clone: Boolean)
    fun healSourceStart()
    fun moveHealSource(index: Int, x: Float, y: Float)
    fun healSourceEnd()
    fun setHealClone(clone: Boolean)
    fun redEye(x: Float, y: Float, radius: Float)
    fun maskStart(x: Float, y: Float, radius: Float, feather: Float, flow: Float, erase: Boolean)
    fun maskTo(x: Float, y: Float, radius: Float, feather: Float, flow: Float, erase: Boolean)
    fun maskEnd()
    fun maskFromSelection()
    fun maskInvert()
    fun maskReset()
    fun maskOverlay(on: Boolean)
}

internal fun EditorViewModel.retouchActions(): RetouchActions = object : RetouchActions {
    override fun healSpot(x: Float, y: Float, radius: Float, feather: Float, opacity: Float, clone: Boolean) =
        onHealSpot(x, y, radius, feather, opacity, clone)
    override fun healSourceStart() {
        onPhotoGestureStart(com.hereliesaz.graffitixr.common.photo.PhotoTool.HEAL)
    }
    override fun moveHealSource(index: Int, x: Float, y: Float) = this@retouchActions.moveHealSource(index, x, y)
    override fun healSourceEnd() = onPhotoGestureEnd()
    override fun setHealClone(clone: Boolean) = this@retouchActions.setHealClone(clone)
    override fun redEye(x: Float, y: Float, radius: Float) = onRedEyeSpot(x, y, radius)
    override fun maskStart(x: Float, y: Float, radius: Float, feather: Float, flow: Float, erase: Boolean) =
        onMaskStrokeStart(x, y, radius, feather, flow, erase)
    override fun maskTo(x: Float, y: Float, radius: Float, feather: Float, flow: Float, erase: Boolean) =
        onMaskStrokeTo(x, y, radius, feather, flow, erase)
    override fun maskEnd() = onMaskStrokeEnd()
    override fun maskFromSelection() = onMaskFromSelection()
    override fun maskInvert() = onMaskInvert()
    override fun maskReset() = onMaskReset()
    override fun maskOverlay(on: Boolean) = setMaskOverlay(on)
}

/** The Retouch tabs' brush settings: the sheet's, not any layer's (they shape the next spot or stroke). */
internal object RetouchSliders {
    val HEAL_SIZE = PhotoSlider("heal.size", "Size", 4f, 200f, 48f, SliderFormat.PLAIN)
    val HEAL_FEATHER = PhotoSlider("heal.feather", "Feather", 0f, 100f, 40f, SliderFormat.PLAIN)
    val HEAL_OPACITY = PhotoSlider("heal.opacity", "Opacity", 0f, 100f, 100f, SliderFormat.PLAIN)
    val RED_EYE_SIZE = PhotoSlider("redeye.size", "Size", 4f, 120f, 28f, SliderFormat.PLAIN)
    val MASK_SIZE = PhotoSlider("mask.size", "Size", 4f, 300f, 64f, SliderFormat.PLAIN)
    val MASK_FEATHER = PhotoSlider("mask.feather", "Feather", 0f, 100f, 50f, SliderFormat.PLAIN)
    val MASK_FLOW = PhotoSlider("mask.flow", "Flow", 1f, 100f, 30f, SliderFormat.PLAIN)
}

/** The Retouch tabs' sheet state: brush settings and modes. Host-owned, so they outlive the tab. */
internal class RetouchState {
    val values = androidx.compose.runtime.mutableStateMapOf<String, Float>()
    var clone by mutableStateOf(false)
    var erase by mutableStateOf(false)
    var overlay by mutableStateOf(false)
    fun value(s: PhotoSlider): Float = values[s.id] ?: s.default
}

/** What the Retouch tabs need from the host. */
internal class RetouchContext(
    val state: RetouchState,
    val actions: RetouchActions,
    /** The selected adjustment layer's name, for "Local · mask on Light"; null when there is none. */
    val maskTarget: String?,
)

/** Local's title: "Local · mask on Light". */
internal fun localTitle(target: String?): String = if (target == null) "Local" else "Local · mask on $target"

/** Sliders that set the sheet's own state (not a layer's): Size, Feather, Opacity, Flow. */
@Composable
private fun StateSliders(sliders: List<PhotoSlider>, state: RetouchState, drag: PhotoDrag) {
    sliders.forEach { s ->
        PhotoSliderRow(
            slider = s,
            value = state.value(s),
            dragging = drag.sliderId == s.id,
            hidden = drag.active && drag.sliderId != s.id,
            onStart = { drag.sliderId = s.id },
            onChange = { state.values[s.id] = it },
            onEnd = { drag.sliderId = null },
            onReset = { state.values.remove(s.id) },
        )
    }
}

@Composable
private fun ChipRow(drag: PhotoDrag, content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.alpha(if (drag.active) 0f else 1f).fillMaxWidth().height(44.dp).padding(horizontal = 20.dp),
    ) { content() }
}

@Composable
internal fun RetouchChip(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (on) PhotoSheetStyle.Ink else Color.Transparent)
            .then(if (on) Modifier else Modifier.border(1.dp, RailBorder, RoundedCornerShape(18.dp)))
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp)
            .testTag(tag),
    ) {
        Text(label, color = if (on) Color(0xFF0E0E0F) else PhotoSheetStyle.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = RobotoCondensedFamily, maxLines = 1)
    }
}

/** Heal: Content-aware or Clone, then Size, Feather and Opacity for the next spot. */
@Composable
internal fun HealBody(ctx: RetouchContext, drag: PhotoDrag) {
    Column(Modifier.fillMaxWidth()) {
        ChipRow(drag) {
            RetouchChip("Content-aware", !ctx.state.clone, "photo.heal.aware") { ctx.state.clone = false; ctx.actions.setHealClone(false) }
            RetouchChip("Clone", ctx.state.clone, "photo.heal.clone") { ctx.state.clone = true; ctx.actions.setHealClone(true) }
        }
        StateSliders(listOf(RetouchSliders.HEAL_SIZE, RetouchSliders.HEAL_FEATHER, RetouchSliders.HEAL_OPACITY), ctx.state, drag)
    }
}

/** Red-eye: tap each eye; Size sets the circle. */
@Composable
internal fun RedEyeBody(ctx: RetouchContext, drag: PhotoDrag) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Tap each eye on the photo.",
            color = PhotoSheetStyle.Muted, fontSize = 14.sp, fontFamily = RobotoCondensedFamily,
            modifier = Modifier.alpha(if (drag.active) 0f else 1f).height(44.dp).padding(horizontal = 20.dp, vertical = 12.dp),
        )
        StateSliders(listOf(RetouchSliders.RED_EYE_SIZE), ctx.state, drag)
    }
}

/** Local: Brush, Erase, From selection, Invert; then Size, Feather and Flow of the mask brush. */
@Composable
internal fun LocalBody(ctx: RetouchContext, drag: PhotoDrag) {
    Column(Modifier.fillMaxWidth()) {
        ChipRow(drag) {
            RetouchChip("Brush", !ctx.state.erase, "photo.mask.brush") { ctx.state.erase = false }
            RetouchChip("Erase", ctx.state.erase, "photo.mask.erase") { ctx.state.erase = true }
            RetouchChip("From selection", false, "photo.mask.selection") { ctx.actions.maskFromSelection() }
            RetouchChip("Invert", false, "photo.mask.invert") { ctx.actions.maskInvert() }
        }
        StateSliders(listOf(RetouchSliders.MASK_SIZE, RetouchSliders.MASK_FEATHER, RetouchSliders.MASK_FLOW), ctx.state, drag)
    }
}

/** Local's Overlay toggle in the header: ink when on. */
@Composable
internal fun OverlayToggle(ctx: RetouchContext) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClickLabel = "Show mask overlay") {
                ctx.state.overlay = !ctx.state.overlay
                ctx.actions.maskOverlay(ctx.state.overlay)
            }
            .padding(horizontal = 10.dp)
            .testTag("photo.mask.overlay"),
    ) {
        Text(
            "Overlay",
            color = if (ctx.state.overlay) PhotoSheetStyle.Ink else PhotoSheetStyle.Muted,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = RobotoCondensedFamily,
        )
    }
}

/** A full-screen box in the canvas camera: children draw, and touches arrive, in world pixels. */
@Composable
private fun InCamera(zoom: Float, offset: Offset, rotation: Float, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                rotationZ = rotation
                translationX = offset.x
                translationY = offset.y
                transformOrigin = TransformOrigin(0f, 0f)
            },
        content = content,
    )
}

/** The camera the canvas draws through: the overlays follow it. */
internal data class CanvasCamera(val zoom: Float, val offset: Offset, val rotation: Float)

/**
 * Heal on the photo: tap or brush over a spot to heal it (a brush heals the circle round what it
 * covered); each healed spot shows its target (solid) and its source (dashed); drag the dashed
 * ring to borrow from somewhere else. While touching, the brush's ring and its feather show.
 */
@Composable
internal fun HealOverlay(spots: List<HealSpot>, camera: CanvasCamera, ctx: RetouchContext, modifier: Modifier = Modifier) {
    val latestSpots by rememberUpdatedState(spots)
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var cursorR by remember { mutableFloatStateOf(0f) }
    InCamera(camera.zoom, camera.offset, camera.rotation, modifier.testTag("photo.heal.overlay")) {
        Box(
            Modifier.fillMaxSize().pointerInput(camera.zoom) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val radius = ctx.state.value(RetouchSliders.HEAL_SIZE) * density / 2f / camera.zoom
                    val source = latestSpots.indexOfFirst { hypot(it.sourceX - down.position.x, it.sourceY - down.position.y) <= it.radius }
                    if (source >= 0) {
                        ctx.actions.healSourceStart()
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                            ctx.actions.moveHealSource(source, c.position.x, c.position.y)
                        }
                        ctx.actions.healSourceEnd()
                        return@awaitEachGesture
                    }
                    val points = mutableListOf(down.position)
                    cursor = down.position
                    cursorR = radius
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        points += c.position
                        cursor = c.position
                    }
                    cursor = null
                    // The spot: the centre of what was covered, big enough to hold it all.
                    val cx = points.map { it.x }.average().toFloat()
                    val cy = points.map { it.y }.average().toFloat()
                    val r = (points.maxOf { hypot(it.x - cx, it.y - cy) } + radius).coerceAtLeast(radius)
                    ctx.actions.healSpot(
                        cx, cy, r,
                        ctx.state.value(RetouchSliders.HEAL_FEATHER) / 100f,
                        ctx.state.value(RetouchSliders.HEAL_OPACITY) / 100f,
                        ctx.state.clone,
                    )
                }
            },
        )
        Canvas(Modifier.fillMaxSize()) {
            val px = 1f / camera.zoom
            val line = 1.5f.dp.toPx() * px
            latestSpots.forEach { s ->
                drawCircle(PhotoSheetStyle.Ink, s.radius, Offset(s.x, s.y), style = Stroke(line))
                drawCircle(
                    PhotoSheetStyle.Ink, s.radius, Offset(s.sourceX, s.sourceY),
                    style = Stroke(line, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx() * px, 3.dp.toPx() * px))),
                )
                val d = hypot(s.sourceX - s.x, s.sourceY - s.y)
                if (d > s.radius * 2) {
                    val ux = (s.sourceX - s.x) / d
                    val uy = (s.sourceY - s.y) / d
                    drawLine(
                        PhotoSheetStyle.Ink,
                        Offset(s.x + ux * s.radius, s.y + uy * s.radius),
                        Offset(s.sourceX - ux * s.radius, s.sourceY - uy * s.radius),
                        strokeWidth = line,
                    )
                }
            }
            cursor?.let { c ->
                drawCircle(PhotoSheetStyle.Ink, cursorR, c, style = Stroke(line))
                drawCircle(PhotoSheetStyle.Ink.copy(alpha = 0.5f), cursorR * (1f - ctx.state.value(RetouchSliders.HEAL_FEATHER) / 100f), c, style = Stroke(1.dp.toPx() * px))
            }
        }
    }
}

/** Red-eye on the photo: tap each eye; its circles show. */
@Composable
internal fun RedEyeOverlay(spots: List<RedEyeSpot>, camera: CanvasCamera, ctx: RetouchContext, modifier: Modifier = Modifier) {
    InCamera(camera.zoom, camera.offset, camera.rotation, modifier.testTag("photo.redeye.overlay")) {
        Box(
            Modifier.fillMaxSize().pointerInput(camera.zoom) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val radius = ctx.state.value(RetouchSliders.RED_EYE_SIZE) * density / 2f / camera.zoom
                    ctx.actions.redEye(down.position.x, down.position.y, radius)
                }
            },
        )
        Canvas(Modifier.fillMaxSize()) {
            spots.forEach { s -> drawCircle(PhotoSheetStyle.Ink, s.radius, Offset(s.x, s.y), style = Stroke(1.5f.dp.toPx() / camera.zoom)) }
        }
    }
}

/**
 * Local on the photo: brush (or erase) the selected adjustment layer's mask. The cursor is the
 * brush's size (outer ring) and where its feather starts (inner, dashed). With Overlay on, the
 * mask shows in white over the photo.
 */
@Composable
internal fun LocalMaskOverlay(mask: Bitmap?, camera: CanvasCamera, ctx: RetouchContext, modifier: Modifier = Modifier) {
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var cursorR by remember { mutableFloatStateOf(0f) }
    InCamera(camera.zoom, camera.offset, camera.rotation, modifier.testTag("photo.mask.canvas")) {
        if (mask != null) {
            Image(
                mask.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                colorFilter = ColorFilter.tint(MaskOverlayInk),
            )
        }
        Box(
            Modifier.fillMaxSize().pointerInput(camera.zoom) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val r = ctx.state.value(RetouchSliders.MASK_SIZE) * density / 2f / camera.zoom
                    val feather = ctx.state.value(RetouchSliders.MASK_FEATHER) / 100f
                    val flow = ctx.state.value(RetouchSliders.MASK_FLOW) / 100f
                    val erase = ctx.state.erase
                    cursor = down.position
                    cursorR = r
                    ctx.actions.maskStart(down.position.x, down.position.y, r, feather, flow, erase)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        cursor = c.position
                        ctx.actions.maskTo(c.position.x, c.position.y, r, feather, flow, erase)
                    }
                    cursor = null
                    ctx.actions.maskEnd()
                }
            },
        )
        Canvas(Modifier.fillMaxSize()) {
            val c = cursor ?: return@Canvas
            val px = 1f / camera.zoom
            drawCircle(PhotoSheetStyle.Ink, cursorR, c, style = Stroke(1.5f.dp.toPx() * px))
            drawCircle(
                PhotoSheetStyle.Ink.copy(alpha = 0.5f), cursorR * (1f - ctx.state.value(RetouchSliders.MASK_FEATHER) / 100f), c,
                style = Stroke(1.dp.toPx() * px, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx() * px, 3.dp.toPx() * px))),
            )
        }
    }
}

/** The mask overlay's white (the mockup's: white at about a third). */
private val MaskOverlayInk = Color(0x52EDEDED)

/** The spots of [adjustment] (Heal's or Red-eye's), or none. */
internal fun healSpotsOf(adjustment: PhotoAdjustment?): List<HealSpot> = adjustment?.healSpots.orEmpty()
