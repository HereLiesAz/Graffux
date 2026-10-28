@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "FunctionNaming", "LongMethod", "TooManyFunctions", "MatchingDeclarationName", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")

package com.hereliesaz.graffux

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.graffitixr.common.photo.PhotoAdjustment
import com.hereliesaz.graffitixr.common.photo.PhotoSlider
import com.hereliesaz.graffitixr.common.photo.PhotoSliders
import com.hereliesaz.graffitixr.common.photo.PhotoTool
import com.hereliesaz.graffitixr.design.RailIcons
import com.hereliesaz.graffitixr.design.theme.RobotoCondensedFamily
import com.hereliesaz.graffitixr.design.theme.RobotoMonoFamily
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import kotlin.math.abs

/*
 * The photo sheet (the approved mockup's "Photo tools — adjustment layers" boards). The rail's
 * Adjust circle swaps the sheet's tab list from brushes to these tabs; the carousel slot then holds
 * the tab's controls instead of brush cards. Each tab that adjusts the photo edits its own
 * adjustment layer (EditorViewModel.onPhotoGestureStart).
 */

/** The photo sheet's tabs, in the mockup's order, with the type heading each sits under. */
internal enum class PhotoTab(val label: String, val section: String, val tool: PhotoTool?) {
    LIGHT("Light", "LIGHT & COLOR", PhotoTool.LIGHT),
    COLOR("Color", "LIGHT & COLOR", PhotoTool.COLOR),
    HSL("HSL", "LIGHT & COLOR", PhotoTool.HSL),
    CURVES("Curves", "LIGHT & COLOR", PhotoTool.CURVES),
    DETAIL("Detail", "DETAIL", PhotoTool.DETAIL),
    EFFECTS("Effects", "DETAIL", PhotoTool.EFFECTS),
    CROP("Crop", "GEOMETRY", null),
    HEAL("Heal", "RETOUCH", PhotoTool.HEAL),
    RED_EYE("Red-eye", "RETOUCH", PhotoTool.RED_EYE),
    LOCAL("Local", "RETOUCH", null),
    ;

    val key: String get() = "photo.${name.lowercase()}"

    companion object {
        fun ofKey(key: String?): PhotoTab? = entries.firstOrNull { it.key == key }

        /** The tab that edits [tool]'s layers (an adjustment tile tapped in the layers rail). */
        fun of(tool: PhotoTool): PhotoTab = entries.first { it.tool == tool }
    }
}

/** The tabs this build offers; the rest of the mockup's land with their tools. */
internal val PHOTO_TABS: List<PhotoTab> = listOf(
    PhotoTab.LIGHT, PhotoTab.COLOR, PhotoTab.HSL, PhotoTab.CURVES, PhotoTab.DETAIL, PhotoTab.EFFECTS, PhotoTab.CROP,
    PhotoTab.HEAL, PhotoTab.RED_EYE, PhotoTab.LOCAL,
)

/**
 * The tab list's sections for the photo sheet: [CarouselTabList] draws them exactly as it draws the
 * brush packs. The groups carry no carousel entries; the photo panel stands in for the carousel.
 */
internal fun photoTabSections(tabs: List<PhotoTab> = PHOTO_TABS): List<CarouselTabSection> =
    tabs.groupBy { it.section }.map { (section, inSection) ->
        CarouselTabSection(
            section,
            inSection.map { CarouselTabGroup(it.key, it.label, CarouselCategory.BRUSHES, emptyList()) },
        )
    }

/** The editor calls the photo sheet makes, as an interface tests can fake. */
internal interface PhotoActions {
    /** A gesture on [tool]'s controls begins (one undo step; makes the layer if needed). */
    fun gestureStart(tool: PhotoTool)
    fun slider(tool: PhotoTool, slider: PhotoSlider, value: Float)
    fun gestureEnd()
    fun resetSlider(tool: PhotoTool, slider: PhotoSlider)
    fun reset(tool: PhotoTool)
    fun compare(comparing: Boolean)

    /** Any other change to [tool]'s layer inside a gesture (a curve point, a levels handle). */
    fun update(tool: PhotoTool, change: (PhotoAdjustment) -> PhotoAdjustment) = Unit

    /** HSL's eyedropper: armed, the next touch on the photo picks a range and drags it. */
    fun eyedropper(armed: Boolean) = Unit
}

internal fun EditorViewModel.photoActions(): PhotoActions = object : PhotoActions {
    override fun gestureStart(tool: PhotoTool) {
        onPhotoGestureStart(tool)
    }
    override fun slider(tool: PhotoTool, slider: PhotoSlider, value: Float) = onPhotoSlider(tool, slider, value)
    override fun gestureEnd() = onPhotoGestureEnd()
    override fun resetSlider(tool: PhotoTool, slider: PhotoSlider) = onPhotoSliderReset(tool, slider)
    override fun reset(tool: PhotoTool) = onPhotoReset(tool)
    override fun compare(comparing: Boolean) = setPhotoCompare(comparing)
    override fun update(tool: PhotoTool, change: (PhotoAdjustment) -> PhotoAdjustment) =
        updatePhotoAdjustment(tool, change)
    override fun eyedropper(armed: Boolean) = if (armed) armPhotoEyedropper() else disarmPhotoEyedropper()
}

/**
 * The photo sheet's own choices that are not settings of a layer: which HSL range and curve
 * channel are shown, whether HSL's eyedropper is armed, and the luma histogram drawn behind the
 * curve. Host-owned, so they survive switching tabs.
 */
internal class PhotoSheetState {
    var hslRange by mutableStateOf(com.hereliesaz.graffitixr.common.photo.HueRange.RED)
    var curveChannel by mutableStateOf(com.hereliesaz.graffitixr.common.photo.CurveChannel.RGB)
    var eyedropperArmed by mutableStateOf(false)
    var histogram by mutableStateOf<FloatArray?>(null)
}

/**
 * Which slider row a finger is dragging, shared with the host: while one is, every piece of chrome
 * hides (the rails, the logo, the scrim and the rest of the sheet) except that row, which stays
 * exactly where it was with its live value. Lift the finger and it all returns.
 */
internal class PhotoDrag {
    var sliderId by mutableStateOf<String?>(null)
    val active: Boolean get() = sliderId != null
}

/**
 * What the sheet holds in photo mode: the tab's controls (once a tab is open) above the photo tab
 * list. Swiped down to its first stage, only the tab list. The tab list hides with the rest of the
 * chrome while a slider is dragged.
 */
@Composable
internal fun PhotoSheetBody(
    tab: PhotoTab?,
    showingControls: Boolean,
    adjustment: PhotoAdjustment?,
    actions: PhotoActions,
    drag: PhotoDrag,
    onSelectTab: (PhotoTab) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<PhotoTab> = PHOTO_TABS,
    state: PhotoSheetState = remember { PhotoSheetState() },
    crop: CropContext? = null,
    retouch: RetouchContext? = null,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (showingControls && tab != null) {
            PhotoPanel(tab, adjustment, actions, drag, state = state, crop = crop, retouch = retouch)
        }
        Box(Modifier.alpha(if (drag.active) 0f else 1f)) {
            CarouselTabList(
                photoTabSections(tabs),
                tab?.key,
                onSelect = { group -> PhotoTab.ofKey(group.key)?.let(onSelectTab) },
            )
        }
    }
}

/**
 * HSL's eyedropper on the photo, over the canvas while it is armed: touch a colour to pick its
 * range, then drag up or down to raise or lower that range's saturation, without looking at the
 * sheet. One gesture is one undo step.
 */
@Composable
internal fun HslEyedropperOverlay(
    state: PhotoSheetState,
    actions: PhotoActions,
    hueAt: (androidx.compose.ui.geometry.Offset) -> com.hereliesaz.graffitixr.common.photo.HueRange?,
    saturationOf: (com.hereliesaz.graffitixr.common.photo.HueRange) -> Float,
    modifier: Modifier = Modifier,
) {
    val latestHue by rememberUpdatedState(hueAt)
    val latestSat by rememberUpdatedState(saturationOf)
    Box(
        modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val range = latestHue(down.position) ?: state.hslRange
                    state.hslRange = range
                    val slider = PhotoSliders.hsl(range, 1)
                    val start = latestSat(range)
                    actions.gestureStart(PhotoTool.HSL)
                    var dy = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        dy += change.positionChangeIgnoreConsumed().y
                        // Up raises: a full sweep of 100 per 200dp.
                        actions.slider(PhotoTool.HSL, slider, slider.snap(start - dy / (2f * density)))
                    }
                    actions.gestureEnd()
                }
            }
            .testTag("photo.eyedropper.overlay"),
    )
}

/** The sheet's colours and sizes, from the mockup. */
internal object PhotoSheetStyle {
    val Ink = Color(0xFFEDEDED)
    val Muted = Color(0xFFB4B4BA)
    val Track = Color(0xFF3A3A3F)
    val HeaderHeight = 44.dp
    val BodyHeight = 240.dp
    val RowHeight = 44.dp

    /** Below the header and above the tab list: 96 − 92 and 344 − 336. */
    val HeaderGap = 4.dp
    val TabsGap = 8.dp
    val BodyFade = 28.dp

    /** The tablet and desktop keep the slider column at phone width, centred. */
    val ColumnMaxWidth = 390.dp
}

/**
 * The photo tab's controls in the carousel's slot: the header (tab name, hold-to-compare, Reset)
 * and the tab's body (slider rows for Light and Color). [adjustment] is the layer the tab edits
 * now, null before the first slider move makes one.
 */
@Composable
internal fun PhotoPanel(
    tab: PhotoTab,
    adjustment: PhotoAdjustment?,
    actions: PhotoActions,
    drag: PhotoDrag,
    modifier: Modifier = Modifier,
    state: PhotoSheetState = remember { PhotoSheetState() },
    crop: CropContext? = null,
    retouch: RetouchContext? = null,
) {
    val chromeAlpha = if (drag.active) 0f else 1f
    Column(modifier.widthIn(max = PhotoSheetStyle.ColumnMaxWidth).fillMaxWidth().testTag("photo.panel.${tab.key}")) {
        PhotoHeader(
            title = when {
                tab == PhotoTab.HSL -> "HSL \u00b7 ${state.hslRange.label}"
                tab == PhotoTab.CROP && crop != null -> cropTitle(crop.crop)
                tab == PhotoTab.LOCAL -> localTitle(retouch?.maskTarget)
                else -> tab.label
            },
            tool = tab.tool,
            actions = actions,
            modifier = Modifier.alpha(chromeAlpha),
            eyedropper = if (tab == PhotoTab.HSL) state else null,
            onReset = when {
                tab == PhotoTab.CROP && crop != null -> {
                    { crop.actions.apply { com.hereliesaz.graffitixr.common.photo.DocumentCrop() } }
                }
                tab == PhotoTab.LOCAL && retouch != null -> retouch.actions::maskReset
                else -> null
            },
            overlay = if (tab == PhotoTab.LOCAL) retouch else null,
        )
        Spacer(Modifier.height(PhotoSheetStyle.HeaderGap))
        Box(Modifier.fillMaxWidth().height(PhotoSheetStyle.BodyHeight)) {
            val tool = tab.tool
            when (tab) {
                PhotoTab.HSL -> HslBody(adjustment, actions, drag, state)
                PhotoTab.CURVES -> CurvesBody(adjustment, actions, drag, state)
                PhotoTab.CROP -> if (crop != null) CropBody(crop.crop, crop.docW, crop.docH, crop.actions, drag)
                PhotoTab.HEAL -> if (retouch != null) HealBody(retouch, drag)
                PhotoTab.RED_EYE -> if (retouch != null) RedEyeBody(retouch, drag)
                PhotoTab.LOCAL -> if (retouch != null) LocalBody(retouch, drag)
                else -> if (tool != null) PhotoSliderList(tool, PhotoSliders.of(tool), adjustment, actions, drag)
            }
        }
        Spacer(Modifier.height(PhotoSheetStyle.TabsGap))
    }
}

@Composable
private fun PhotoHeader(
    title: String,
    tool: PhotoTool?,
    actions: PhotoActions,
    modifier: Modifier = Modifier,
    eyedropper: PhotoSheetState? = null,
    onReset: (() -> Unit)? = null,
    overlay: RetouchContext? = null,
) {
    val reset: () -> Unit = onReset ?: { tool?.let(actions::reset) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(PhotoSheetStyle.HeaderHeight)
            .padding(start = 20.dp, end = 12.dp),
    ) {
        Text(
            title,
            color = PhotoSheetStyle.Ink,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = RobotoCondensedFamily,
            maxLines = 1,
            modifier = Modifier.weight(1f).testTag("photo.title"),
        )
        Spacer(Modifier.width(4.dp))
        if (overlay != null) OverlayToggle(overlay)
        if (eyedropper != null) {
            EyedropperButton(eyedropper, actions)
            Spacer(Modifier.width(4.dp))
        }
        CompareButton(actions)
        Spacer(Modifier.width(4.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .semantics {
                    role = Role.Button
                    onClick(label = "Reset") { reset(); true }
                }
                .pointerInput(tool, onReset) {
                    awaitEachGesture {
                        awaitFirstDown()
                        val up = waitForUpOrCancel()
                        if (up != null) reset()
                    }
                }
                .padding(horizontal = 10.dp)
                .testTag("photo.reset"),
        ) {
            Text("Reset", color = PhotoSheetStyle.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = RobotoCondensedFamily)
        }
    }
}

/** HSL's eyedropper: armed (ink), the next touch on the photo picks a range and drags it. */
@Composable
private fun EyedropperButton(state: PhotoSheetState, actions: PhotoActions) {
    val on = state.eyedropperArmed
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (on) PhotoSheetStyle.Ink else Color.Transparent)
            .clickable(onClickLabel = "Pick a colour from the photo, then drag on it") {
                state.eyedropperArmed = !on
                actions.eyedropper(!on)
            }
            .testTag("photo.eyedropper"),
    ) {
        Icon(
            RailIcons.Eyedropper,
            contentDescription = "Pick a colour from the photo, then drag on it",
            tint = if (on) Color(0xFF0E0E0F) else PhotoSheetStyle.Muted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Hold to see the photo without its adjustment layers; let go to see them again. */
@Composable
private fun CompareButton(actions: PhotoActions) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .semantics { contentDescription = "Hold to compare with original" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    actions.compare(true)
                    try {
                        waitForUpOrCancel()
                    } finally {
                        actions.compare(false)
                    }
                }
            }
            .testTag("photo.compare"),
    ) {
        Icon(RailIcons.Compare, contentDescription = null, tint = PhotoSheetStyle.Muted, modifier = Modifier.size(20.dp))
    }
}

/**
 * The slider rows, scrolling, the last 28dp fading out (the mockup's mask). While one row is being
 * dragged the others vanish and that row draws as a pill.
 */
@Composable
internal fun PhotoSliderList(
    tool: PhotoTool,
    sliders: List<PhotoSlider>,
    adjustment: PhotoAdjustment?,
    actions: PhotoActions,
    drag: PhotoDrag,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    val scroll = rememberScrollState()
    // The scroll clips to its bounds, and the dragged row's pill reaches 6 past its row: the list
    // is laid out PillOverhang taller than its slot on each side and padded back by as much.
    Column(
        modifier
            .fillMaxWidth()
            .requiredHeight(PhotoSheetStyle.BodyHeight + PillOverhang * 2)
            .fadeBottom(if (drag.active) 0.dp else PhotoSheetStyle.BodyFade, PillOverhang)
            .verticalScroll(scroll, enabled = !drag.active)
            .padding(vertical = PillOverhang),
    ) {
        header()
        sliders.forEach { slider ->
            val value = adjustment?.value(slider) ?: slider.default
            PhotoSliderRow(
                slider = slider,
                value = value,
                dragging = drag.sliderId == slider.id,
                hidden = drag.active && drag.sliderId != slider.id,
                onStart = {
                    drag.sliderId = slider.id
                    actions.gestureStart(tool)
                },
                onChange = { actions.slider(tool, slider, it) },
                onEnd = {
                    drag.sliderId = null
                    actions.gestureEnd()
                },
                onReset = { actions.resetSlider(tool, slider) },
            )
        }
    }
}

/** Fades the last [fade] of the content to transparent (CSS's mask-image on the mockup's list). */
private fun Modifier.fadeBottom(fade: androidx.compose.ui.unit.Dp, inset: androidx.compose.ui.unit.Dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val f = fade.toPx()
            val end = size.height - inset.toPx()
            if (f > 0f) {
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black,
                        ((end - f) / size.height).coerceIn(0f, 1f) to Color.Black,
                        (end / size.height).coerceIn(0f, 1f) to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

/**
 * One slider row: label, track and value, 44dp tall. Drag the row's track (anywhere along it) to
 * move the value with the finger; double-tap it to put the slider back to its default. TalkBack
 * reads it as a slider and can set it. While [dragging], the row draws as the mockup's pill (12 in
 * from each side, 56 tall, centred where the row is), its value in full ink.
 */
@Composable
internal fun PhotoSliderRow(
    slider: PhotoSlider,
    value: Float,
    dragging: Boolean,
    hidden: Boolean,
    onStart: () -> Unit,
    onChange: (Float) -> Unit,
    onEnd: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onChange)
    val latestStart by rememberUpdatedState(onStart)
    val latestEnd by rememberUpdatedState(onEnd)
    val latestReset by rememberUpdatedState(onReset)
    var trackWidth by remember { mutableFloatStateOf(1f) }
    var lastTapAt by remember { mutableStateOf(0L) }
    Box(
        modifier
            .fillMaxWidth()
            .height(PhotoSheetStyle.RowHeight)
            .alpha(if (hidden) 0f else 1f)
            .testTag("photo.slider.${slider.id}"),
    ) {
        if (dragging) {
            // The pill: 12 in from each side, 56 tall around the 44 row.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .requiredHeight(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(RailFill)
                    .border(1.dp, RailBorder, RoundedCornerShape(28.dp)),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(start = if (dragging) 32.dp else 20.dp, end = if (dragging) 28.dp else 20.dp),
        ) {
            Text(
                slider.label,
                color = PhotoSheetStyle.Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = RobotoCondensedFamily,
                maxLines = 1,
                modifier = Modifier.width(if (dragging) 92.dp else 112.dp),
            )
            Spacer(Modifier.width(10.dp))
            BoxWithConstraints(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics {
                        contentDescription = "${slider.label} ${slider.display(value)}"
                        progressBarRangeInfo = ProgressBarRangeInfo(value, slider.min..slider.max)
                        setProgress { target ->
                            latestStart()
                            latestChange(slider.snap(target))
                            latestEnd()
                            true
                        }
                    }
                    .pointerInput(slider.id) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startValue = latestValue
                            var dx = 0f
                            var dragging0 = false
                            var pointer = down.id
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointer } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                dx += change.positionChange().x
                                if (!dragging0 && abs(dx) > viewConfiguration.touchSlop) {
                                    dragging0 = true
                                    latestStart()
                                }
                                if (dragging0) {
                                    change.consume()
                                    val f = slider.fraction(startValue) + dx / trackWidth
                                    latestChange(slider.valueAt(f))
                                }
                                pointer = change.id
                            }
                            if (dragging0) {
                                latestEnd()
                            } else {
                                val now = System.currentTimeMillis()
                                if (now - lastTapAt < DOUBLE_TAP_MS) {
                                    lastTapAt = 0L
                                    latestReset()
                                } else {
                                    lastTapAt = now
                                }
                            }
                        }
                    },
            ) {
                trackWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val thumb = 20.dp
                Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(PhotoSheetStyle.Track))
                Box(
                    Modifier
                        .offset(x = (maxWidth - thumb) * slider.fraction(value))
                        .size(thumb)
                        .background(PhotoSheetStyle.Ink, CircleShape),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                slider.display(value),
                color = if (dragging) PhotoSheetStyle.Ink else PhotoSheetStyle.Muted,
                fontSize = if (dragging) 13.sp else 12.sp,
                fontFamily = RobotoMonoFamily,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(if (dragging) 56.dp else 52.dp).testTag("photo.value.${slider.id}"),
            )
        }
    }
}

private const val DOUBLE_TAP_MS = 300L

/** How far the dragged row's pill reaches past its 44dp row, above and below: (56 - 44) / 2. */
private val PillOverhang = 6.dp

/** Waits for the pointer to lift; null when the gesture was cancelled. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitForUpOrCancel():
    androidx.compose.ui.input.pointer.PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.all { !it.pressed }) return event.changes.firstOrNull()
        if (event.changes.any { it.isConsumed && it.pressed && it.positionChangeIgnoreConsumed() != androidx.compose.ui.geometry.Offset.Zero }) {
            return null
        }
    }
}
