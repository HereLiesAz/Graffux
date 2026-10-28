@file:Suppress(
    "MagicNumber", "MaxLineLength", "LongParameterList", "FunctionNaming", "LongMethod",
    "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "ComplexCondition",
    "DestructuringDeclarationWithTooManyEntries",
)

package com.hereliesaz.graffux

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.graffitixr.common.photo.CropGeometry
import com.hereliesaz.graffitixr.common.photo.DocumentCrop
import com.hereliesaz.graffitixr.common.photo.PhotoSlider
import com.hereliesaz.graffitixr.common.photo.SliderFormat
import com.hereliesaz.graffitixr.design.RailIcons
import com.hereliesaz.graffitixr.design.theme.RobotoCondensedFamily
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.feature.editor.export.artboardRect
import kotlin.math.abs
import kotlin.math.atan2

/*
 * The Crop tab (the mockup's "Crop in progress" board). Crop is a whole-document setting
 * (EditorUiState.documentCrop), not a layer: the canvas draws the layers through it, and while the
 * tab is open it shows the whole transformed photo with the crop frame over it, so re-opening Crop
 * always finds the full photo.
 */

/** The editor calls the Crop tab makes, as an interface tests can fake. */
internal interface CropActions {
    fun gestureStart()
    fun set(crop: DocumentCrop)
    fun gestureEnd()

    /** A one-tap change (aspect, rotate, flip, reset): one undo step. */
    fun apply(change: (DocumentCrop) -> DocumentCrop)
    fun autoLevel()
}

internal fun EditorViewModel.cropActions(): CropActions = object : CropActions {
    override fun gestureStart() = onCropGestureStart()
    override fun set(crop: DocumentCrop) = setDocumentCrop(crop)
    override fun gestureEnd() = onCropGestureEnd()
    override fun apply(change: (DocumentCrop) -> DocumentCrop) = applyCrop(change)
    override fun autoLevel() = onCropAutoLevel()
}

/** The Crop tab's slider rows: Straighten (degrees) and the two perspective corrections. */
internal object CropSliders {
    val STRAIGHTEN = PhotoSlider("crop.straighten", "Straighten", -45f, 45f, format = SliderFormat.DEGREES)
    val VERTICAL = PhotoSlider("crop.vertical", "Vertical", -100f, 100f)
    val HORIZONTAL = PhotoSlider("crop.horizontal", "Horizontal", -100f, 100f)

    fun value(slider: PhotoSlider, crop: DocumentCrop): Float = when (slider) {
        STRAIGHTEN -> crop.straighten
        VERTICAL -> crop.vertical
        else -> crop.horizontal
    }

    fun with(slider: PhotoSlider, crop: DocumentCrop, v: Float): DocumentCrop = when (slider) {
        STRAIGHTEN -> crop.copy(straighten = v)
        VERTICAL -> crop.copy(vertical = v)
        else -> crop.copy(horizontal = v)
    }
}

/** The Crop tab's title: "Crop · 4:5". */
internal fun cropTitle(crop: DocumentCrop): String = "Crop · ${CropGeometry.aspect(crop.aspect).label}"

/**
 * The Crop tab's body: the aspect chips, Straighten, Vertical and Horizontal, then Rotate 90°,
 * Flip (horizontal), Flip (vertical) and Auto level.
 */
@Composable
internal fun CropBody(
    crop: DocumentCrop,
    docW: Int,
    docH: Int,
    actions: CropActions,
    drag: PhotoDrag,
) {
    val hide = Modifier.alpha(if (drag.active) 0f else 1f)
    Column(Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = hide.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            CropGeometry.ASPECTS.forEach { a ->
                AspectChip(a.label, a.id == crop.aspect) {
                    actions.apply { CropGeometry.withAspect(it, a.id, docW, docH) }
                }
            }
        }
        listOf(CropSliders.STRAIGHTEN, CropSliders.VERTICAL, CropSliders.HORIZONTAL).forEach { slider ->
            PhotoSliderRow(
                slider = slider,
                value = CropSliders.value(slider, crop),
                dragging = drag.sliderId == slider.id,
                hidden = drag.active && drag.sliderId != slider.id,
                onStart = {
                    drag.sliderId = slider.id
                    actions.gestureStart()
                },
                onChange = { v -> actions.set(CropSliders.with(slider, crop, v)) },
                onEnd = {
                    drag.sliderId = null
                    actions.gestureEnd()
                },
                onReset = { actions.apply { CropSliders.with(slider, it, slider.default) } },
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = hide.fillMaxWidth().height(44.dp).padding(horizontal = 14.dp),
        ) {
            CropAction("Rotate 90°", RailIcons.Rotate90, "photo.crop.rotate") {
                actions.apply { CropGeometry.rotateQuarter(it, docW, docH) }
            }
            CropAction("Flip", RailIcons.FlipH, "photo.crop.fliph", "Flip horizontal") { actions.apply { it.copy(flipH = !it.flipH) } }
            CropAction("Flip", RailIcons.FlipV, "photo.crop.flipv", "Flip vertical") { actions.apply { it.copy(flipV = !it.flipV) } }
            CropAction("Auto level", null, "photo.crop.autolevel") { actions.autoLevel() }
        }
    }
}

@Composable
private fun AspectChip(label: String, picked: Boolean, onPick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (picked) PhotoSheetStyle.Ink else Color.Transparent)
            .then(if (picked) Modifier else Modifier.border(1.dp, RailBorder, RoundedCornerShape(18.dp)))
            .selectable(selected = picked, role = Role.RadioButton, onClick = onPick)
            .padding(horizontal = 14.dp)
            .testTag("photo.aspect.$label"),
    ) {
        Text(label, color = if (picked) Color(0xFF0E0E0F) else PhotoSheetStyle.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = RobotoCondensedFamily, maxLines = 1)
    }
}

@Composable
private fun CropAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    tag: String,
    description: String = label,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 10.dp)
            .testTag(tag),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = PhotoSheetStyle.Ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = PhotoSheetStyle.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = RobotoCondensedFamily, maxLines = 1)
    }
}

/** What part of the crop frame a touch took hold of. */
internal enum class CropHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT, LEFT, TOP, RIGHT, BOTTOM, MOVE }

/**
 * The crop rectangle (fractions of the artboard) after dragging [handle] by ([dx], [dy]) artboard
 * fractions; with an aspect ratio, a corner keeps it (the width leads). Never smaller than
 * [MIN_CROP] of the artboard on a side.
 */
internal fun dragCrop(crop: DocumentCrop, handle: CropHandle, dx: Float, dy: Float, ratio: Float?, docAspect: Float): DocumentCrop {
    var l = crop.left
    var t = crop.top
    var r = crop.right
    var b = crop.bottom
    when (handle) {
        CropHandle.MOVE -> { l += dx; r += dx; t += dy; b += dy }
        CropHandle.LEFT -> l = (l + dx).coerceAtMost(r - MIN_CROP)
        CropHandle.RIGHT -> r = (r + dx).coerceAtLeast(l + MIN_CROP)
        CropHandle.TOP -> t = (t + dy).coerceAtMost(b - MIN_CROP)
        CropHandle.BOTTOM -> b = (b + dy).coerceAtLeast(t + MIN_CROP)
        CropHandle.TOP_LEFT -> { l = (l + dx).coerceAtMost(r - MIN_CROP); t = (t + dy).coerceAtMost(b - MIN_CROP) }
        CropHandle.TOP_RIGHT -> { r = (r + dx).coerceAtLeast(l + MIN_CROP); t = (t + dy).coerceAtMost(b - MIN_CROP) }
        CropHandle.BOTTOM_RIGHT -> { r = (r + dx).coerceAtLeast(l + MIN_CROP); b = (b + dy).coerceAtLeast(t + MIN_CROP) }
        CropHandle.BOTTOM_LEFT -> { l = (l + dx).coerceAtMost(r - MIN_CROP); b = (b + dy).coerceAtLeast(t + MIN_CROP) }
    }
    if (ratio != null && handle != CropHandle.MOVE) {
        // Height (in fractions) for the width, in document proportions: h = w * docAspect / ratio.
        val h = (r - l) * docAspect / ratio
        when (handle) {
            CropHandle.TOP_LEFT, CropHandle.TOP_RIGHT, CropHandle.TOP -> t = b - h
            else -> b = t + h
        }
    }
    return crop.copy(left = l, top = t, right = r, bottom = b)
}

internal const val MIN_CROP = 0.05f

/**
 * The crop frame over the canvas while the Crop tab is open: everything outside the crop dimmed,
 * the frame with corner handles, the thirds grid only while a handle is held. Drag a corner or an
 * edge to resize (holding the aspect), inside to move; two fingers twist to straighten. Drawn in
 * the canvas's own camera ([zoom], [offset], [rotation]: the same transform the layers get), so it
 * stays on the photo however the view is moved.
 */
@Composable
internal fun CropOverlay(
    crop: DocumentCrop,
    docW: Int,
    docH: Int,
    zoom: Float,
    offset: Offset,
    rotation: Float,
    actions: CropActions,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    val latestCrop by rememberUpdatedState(crop)
    val latestZoom by rememberUpdatedState(zoom)
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
            }
            .pointerInput(docW, docH) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val art = artboardRect(size.width, size.height, docW, docH)
                    val (al, at, aw, ah) = art.toList()
                    val c0 = latestCrop
                    val rect = Rect(al + c0.left * aw, at + c0.top * ah, al + c0.right * aw, at + c0.bottom * ah)
                    val reach = 28.dp.toPx() / latestZoom
                    val p = down.position
                    fun near(a: Float, b: Float) = abs(a - b) < reach
                    val handle = when {
                        near(p.x, rect.left) && near(p.y, rect.top) -> CropHandle.TOP_LEFT
                        near(p.x, rect.right) && near(p.y, rect.top) -> CropHandle.TOP_RIGHT
                        near(p.x, rect.right) && near(p.y, rect.bottom) -> CropHandle.BOTTOM_RIGHT
                        near(p.x, rect.left) && near(p.y, rect.bottom) -> CropHandle.BOTTOM_LEFT
                        near(p.x, rect.left) && p.y in rect.top..rect.bottom -> CropHandle.LEFT
                        near(p.x, rect.right) && p.y in rect.top..rect.bottom -> CropHandle.RIGHT
                        near(p.y, rect.top) && p.x in rect.left..rect.right -> CropHandle.TOP
                        near(p.y, rect.bottom) && p.x in rect.left..rect.right -> CropHandle.BOTTOM
                        rect.contains(p) -> CropHandle.MOVE
                        else -> null
                    }
                    down.consume()
                    actions.gestureStart()
                    dragging = true
                    val ratio = CropGeometry.ratioOf(c0.aspect, docW, docH)
                    var total = Offset.Zero
                    var twist: Float? = null
                    var startStraighten = c0.straighten
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        event.changes.forEach { it.consume() }
                        if (pressed.size >= 2) {
                            // Two fingers: twist to straighten.
                            val a = pressed[0].position
                            val b = pressed[1].position
                            val angle = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
                            val start = twist ?: angle.also { twist = it; startStraighten = latestCrop.straighten }
                            actions.set(latestCrop.copy(straighten = CropSliders.STRAIGHTEN.snap(startStraighten + angle - start)))
                            continue
                        }
                        if (handle == null || twist != null) continue
                        total += pressed[0].positionChangeIgnoreConsumed()
                        actions.set(dragCrop(c0, handle, total.x / aw, total.y / ah, ratio, docW.toFloat() / docH))
                    }
                    dragging = false
                    actions.gestureEnd()
                }
            }
            .testTag("photo.crop.overlay"),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val (al, at, aw, ah) = artboardRect(size.width.toInt(), size.height.toInt(), docW, docH).toList()
            val r = Rect(al + crop.left * aw, at + crop.top * ah, al + crop.right * aw, at + crop.bottom * ah)
            val px = 1f / zoom
            val far = 100_000f
            val dim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(-far, -far, far, far))
                addRect(r)
            }
            drawPath(dim, CropDim)
            drawRect(PhotoSheetStyle.Ink, r.topLeft, r.size, style = Stroke(1.5f.dp.toPx() * px))
            if (dragging) {
                for (k in 1..2) {
                    val x = r.left + r.width * k / 3f
                    val y = r.top + r.height * k / 3f
                    drawLine(CropGrid, Offset(x, r.top), Offset(x, r.bottom), strokeWidth = 1.dp.toPx() * px)
                    drawLine(CropGrid, Offset(r.left, y), Offset(r.right, y), strokeWidth = 1.dp.toPx() * px)
                }
            }
            // L-shaped corner handles, 22 long and 4 thick (the mockup's), drawn on the frame.
            val len = 22.dp.toPx() * px
            val w = 4.dp.toPx() * px
            val handles = Path().apply {
                moveTo(r.left, r.top + len); lineTo(r.left, r.top); lineTo(r.left + len, r.top)
                moveTo(r.right - len, r.top); lineTo(r.right, r.top); lineTo(r.right, r.top + len)
                moveTo(r.right, r.bottom - len); lineTo(r.right, r.bottom); lineTo(r.right - len, r.bottom)
                moveTo(r.left + len, r.bottom); lineTo(r.left, r.bottom); lineTo(r.left, r.bottom - len)
            }
            drawPath(handles, PhotoSheetStyle.Ink, style = Stroke(w))
        }
    }
}

private val CropDim = Color(0xB8000000)
private val CropGrid = Color(0x73EDEDED)

/** What the Crop tab needs from the host: the crop, the document size and the calls. */
internal class CropContext(val crop: DocumentCrop, val docW: Int, val docH: Int, val actions: CropActions)
