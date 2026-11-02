@file:Suppress("TooManyFunctions", "FunctionNaming")

package com.hereliesaz.graffitixr.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.GraffuxIcons
import kotlin.math.roundToInt

/*
 * The containers tool options open in on Android, in place of desktop-style floating windows.
 * A phone (compact width, under 600dp) and a tablet (medium or expanded width) each get the
 * container that fits the kind of tool; the content composable inside is the same either way.
 *
 *  - AnchoredValuePopover: a small card by the control that opened it (single values; tablet pickers)
 *  - AppBottomSheet:       a sheet from the bottom edge (phone pickers and quick forms)
 *  - CenteredSheet:        a centred modal card (tablet quick forms)
 *  - FullScreenPage:       the whole screen, with a back button (phone editors; all browsers)
 *  - DockedPanel:          a panel docked to one edge, the canvas still live beside it (tablet editors)
 *
 * None of them are separate windows (no Popup, no Dialog, no ModalBottomSheet): they lay out in the
 * host's own overlay, so system Back reaches the host's single BackHandler, which closes the most
 * recently opened one through [SurfaceBackStack]. None of them read drawing state either, so the
 * stroke path pays nothing for them.
 */

/**
 * The strip of the screen the tool rail currently occupies. Docked panels and popovers keep clear
 * of it. Provide the real value once near the editor's root, where the rail is docked (see
 * `MainActivity`); the default assumes no rail.
 */
data class RailInset(val dockedOnLeft: Boolean, val width: Dp)

val LocalRailInset = compositionLocalOf { RailInset(dockedOnLeft = false, width = 0.dp) }

/** Width below which the screen is a phone (Material's compact window width class). */
const val COMPACT_WIDTH_DP = 600

/** True on a phone-width window (compact width class); false on a tablet (medium/expanded). */
@Composable
fun isCompactWidth(): Boolean = LocalConfiguration.current.screenWidthDp < COMPACT_WIDTH_DP

/** What a tool surface holds, which decides its container on each form factor. */
enum class SurfaceKind {
    /** One value (a slider or a number): an anchored popover on both. */
    VALUE,

    /** A list to pick from: a bottom sheet on a phone, an anchored popover on a tablet. */
    PICKER,

    /** A short form: a bottom sheet with large targets on a phone, a centred sheet on a tablet. */
    FORM,

    /** A large editor: a full-screen page on a phone, a panel docked beside the canvas on a tablet. */
    EDITOR,

    /** A timeline: a full-screen page on a phone, docked along the bottom on a tablet. */
    TIMELINE,

    /** Something to browse: a full-screen page on both. */
    BROWSER,
}

/** The concrete container a [SurfaceKind] resolves to. */
enum class SurfaceContainer { POPOVER, BOTTOM_SHEET, CENTERED_SHEET, FULL_SCREEN, DOCKED_SIDE, DOCKED_BOTTOM }

/** The container for [kind] on a phone ([compact]) or a tablet. Pure, so it is tested directly. */
fun containerFor(kind: SurfaceKind, compact: Boolean): SurfaceContainer = when (kind) {
    SurfaceKind.VALUE -> SurfaceContainer.POPOVER
    SurfaceKind.PICKER -> if (compact) SurfaceContainer.BOTTOM_SHEET else SurfaceContainer.POPOVER
    SurfaceKind.FORM -> if (compact) SurfaceContainer.BOTTOM_SHEET else SurfaceContainer.CENTERED_SHEET
    SurfaceKind.EDITOR -> if (compact) SurfaceContainer.FULL_SCREEN else SurfaceContainer.DOCKED_SIDE
    SurfaceKind.TIMELINE -> if (compact) SurfaceContainer.FULL_SCREEN else SurfaceContainer.DOCKED_BOTTOM
    SurfaceKind.BROWSER -> SurfaceContainer.FULL_SCREEN
}

/**
 * Opens [content] in the container [kind] calls for on this screen width. [onDismiss] runs on the
 * close button, a tap on the scrim and system Back alike, so a dialog that applies on close still
 * applies however it closes.
 */
@Composable
fun ToolSurface(
    kind: SurfaceKind,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    when (containerFor(kind, isCompactWidth())) {
        SurfaceContainer.POPOVER ->
            AnchoredValuePopover(title, onDismiss, modifier, wide = kind == SurfaceKind.PICKER, content = content)
        SurfaceContainer.BOTTOM_SHEET ->
            AppBottomSheet(title, onDismiss, modifier, largeTargets = kind == SurfaceKind.FORM, content = content)
        SurfaceContainer.CENTERED_SHEET -> CenteredSheet(title, onDismiss, modifier, content)
        SurfaceContainer.FULL_SCREEN -> FullScreenPage(title, onDismiss, modifier, content)
        SurfaceContainer.DOCKED_SIDE -> DockedPanel(title, onDismiss, modifier, DockEdge.SIDE, content)
        SurfaceContainer.DOCKED_BOTTOM -> DockedPanel(title, onDismiss, modifier, DockEdge.BOTTOM, content)
    }
}

// ---- Back ---------------------------------------------------------------------------------------

/**
 * The open tool surfaces, in the order they opened. Each container registers its own dismiss here
 * while it is composed; the host's BackHandler (a `BackLayer` in `MainActivity`) calls [closeTop],
 * so Back closes the most recently opened surface through the same path as its close button.
 */
@Stable
class SurfaceBackStack {
    private val entries = mutableStateListOf<SurfaceBackEntry>()

    val isOpen: Boolean get() = entries.isNotEmpty()

    /** How many surfaces are open. */
    val size: Int get() = entries.size

    fun closeTop() {
        entries.lastOrNull()?.dismiss?.invoke()
    }

    internal fun push(entry: SurfaceBackEntry) {
        entries.add(entry)
    }

    internal fun remove(entry: SurfaceBackEntry) {
        entries.remove(entry)
    }
}

internal class SurfaceBackEntry(val dismiss: () -> Unit)

/** The host's [SurfaceBackStack]. The default is a stack nobody reads, so Back is then the host's. */
val LocalSurfaceBackStack = staticCompositionLocalOf { SurfaceBackStack() }

@Composable
private fun RegisterBack(onDismiss: () -> Unit) {
    val stack = LocalSurfaceBackStack.current
    val latest = rememberUpdatedState(onDismiss)
    DisposableEffect(stack) {
        val entry = SurfaceBackEntry { latest.value() }
        stack.push(entry)
        onDispose { stack.remove(entry) }
    }
}

// ---- Anchor -------------------------------------------------------------------------------------

/**
 * Where the last press landed, in window pixels: the control that opens a popover was just tapped,
 * so this is where that control is. Plain fields, not Compose state, so recording a press never
 * recomposes anything; a popover reads it once, when it opens.
 */
class SurfaceAnchor {
    var lastPress: Offset? = null
        internal set
    internal var origin: Offset = Offset.Zero
}

val LocalSurfaceAnchor = staticCompositionLocalOf { SurfaceAnchor() }

/**
 * Records every press anywhere under this node into [anchor], without consuming it (it watches the
 * Initial pass and only reads the event type), so the canvas and every control below see the same
 * touches as before.
 */
fun Modifier.trackSurfaceAnchor(anchor: SurfaceAnchor): Modifier = this
    .onGloballyPositioned { anchor.origin = it.positionInWindow() }
    .pointerInput(anchor) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) {
                    event.changes.firstOrNull()?.let { anchor.lastPress = anchor.origin + it.position }
                }
            }
        }
    }

// ---- Containers ---------------------------------------------------------------------------------

private val ScrimColor = Color.Black.copy(alpha = 0.32f)
private val HeaderHeight = 48.dp
private val CloseTargetSize = 48.dp
private val PopoverGap = 12.dp
private val EdgeMargin = 16.dp
private val SheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

/** A full-size layer that darkens the canvas and closes the surface on a tap; it takes that tap. */
@Composable
private fun Scrim(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(ScrimColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Close",
                onClick = onDismiss,
            )
            .testTag("surface.scrim"),
    )
}

/** Title and close button, shared by every container. */
@Composable
private fun SurfaceHeader(title: String, onDismiss: () -> Unit, back: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(HeaderHeight),
    ) {
        val close = @Composable {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(CloseTargetSize)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .semantics { contentDescription = "Close $title" }
                    .testTag(if (back) "surface.back" else "surface.close"),
            ) {
                Icon(
                    painterResource(if (back) GraffuxIcons.Back else GraffuxIcons.Close),
                    contentDescription = null,
                    tint = colors.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (back) close()
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = if (back) 4.dp else 16.dp),
        )
        if (!back) close()
    }
}

/** The scrolling body every container gives its content (the content never nests a lazy list). */
@Composable
private fun SurfaceBody(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        content = content,
    )
}

/** Swallows taps on a surface's own pixels so they never reach the canvas underneath. */
private fun Modifier.blockTouchThrough(): Modifier = pointerInput(Unit) { detectTapGestures { } }

/**
 * A small card next to the control that opened it ([LocalSurfaceAnchor]): below that point when
 * there is room, above it otherwise, kept onscreen and clear of the rail. A tap outside closes it.
 * [wide] gives a list picker more width than a single slider.
 */
@Composable
fun AnchoredValuePopover(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    RegisterBack(onDismiss)
    val anchorHolder = LocalSurfaceAnchor.current
    // Read once: the popover stays where it opened while the user works in it.
    val anchor = remember { anchorHolder.lastPress?.minus(anchorHolder.origin) }
    val rail = LocalRailInset.current
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().testTag("surface.popover.host")) {
        Scrim(onDismiss)
        Layout(
            content = {
                Column(
                    modifier = modifier
                        .widthIn(min = 240.dp, max = if (wide) 360.dp else 320.dp)
                        .background(colors.surface, RoundedCornerShape(12.dp))
                        .border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp))
                        .blockTouchThrough()
                        .testTag("surface.popover"),
                ) {
                    SurfaceHeader(title, onDismiss)
                    SurfaceBody(content = content)
                }
            },
        ) { measurables, constraints ->
            val margin = with(density) { EdgeMargin.roundToPx() }
            val gap = with(density) { PopoverGap.roundToPx() }
            val railPx = with(density) { rail.width.roundToPx() }
            val left = margin + if (rail.dockedOnLeft) railPx else 0
            val right = constraints.maxWidth - margin - if (rail.dockedOnLeft) 0 else railPx
            val usableW = (right - left).coerceAtLeast(0)
            val usableH = (constraints.maxHeight - margin * 2).coerceAtLeast(0)
            val placeable = measurables.first().measure(Constraints(maxWidth = usableW, maxHeight = usableH))
            val pos = popoverPosition(
                anchor = anchor,
                size = placeable.width to placeable.height,
                bounds = intArrayOf(left, margin, right, constraints.maxHeight - margin),
                gap = gap,
            )
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(pos.first, pos.second) }
        }
    }
}

/**
 * Top-left of a popover of [size] near [anchor], inside [bounds] (left, top, right, bottom): centred
 * on the anchor horizontally, below it if it fits there, else above, else clamped. No anchor puts it
 * centred in the lower half, where a thumb reaches it.
 */
@Suppress("MagicNumber") // bounds[0..3] are left, top, right, bottom.
internal fun popoverPosition(anchor: Offset?, size: Pair<Int, Int>, bounds: IntArray, gap: Int): Pair<Int, Int> {
    val (w, h) = size
    val left = bounds[0]
    val top = bounds[1]
    val right = bounds[2]
    val bottom = bounds[3]
    val maxX = (right - w).coerceAtLeast(left)
    val maxY = (bottom - h).coerceAtLeast(top)
    if (anchor == null) {
        val x = ((left + right - w) / 2).coerceIn(left, maxX)
        return x to maxY
    }
    val x = (anchor.x.roundToInt() - w / 2).coerceIn(left, maxX)
    val below = anchor.y.roundToInt() + gap
    val above = anchor.y.roundToInt() - gap - h
    val y = when {
        below + h <= bottom -> below
        above >= top -> above
        else -> maxY
    }.coerceIn(top, maxY)
    return x to y
}

/**
 * A sheet up from the bottom edge, at most 85% of the screen tall, over a scrim that closes it.
 * [largeTargets] raises the minimum touch target to 56dp for forms filled in with a thumb.
 */
@Composable
fun AppBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    largeTargets: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    RegisterBack(onDismiss)
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Scrim(onDismiss)
        Column(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.85f)
                .background(colors.surface, SheetShape)
                .blockTouchThrough()
                .navigationBarsPadding()
                .imePadding()
                .testTag("surface.sheet"),
        ) {
            // Grab pill, as on the carousel sheet.
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(width = 32.dp, height = 4.dp)
                        .background(colors.outlineVariant, RoundedCornerShape(2.dp)),
                )
            }
            SurfaceHeader(title, onDismiss)
            if (largeTargets) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 56.dp) {
                    SurfaceBody(content = content)
                }
            } else {
                SurfaceBody(content = content)
            }
        }
    }
}

/** A centred modal card for a short form on a tablet, over a scrim that closes it. */
@Composable
fun CenteredSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    RegisterBack(onDismiss)
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
        Scrim(onDismiss)
        Column(
            modifier = modifier
                .widthIn(min = 360.dp, max = 560.dp)
                .heightIn(max = maxHeight * 0.85f)
                .background(colors.surface, RoundedCornerShape(16.dp))
                .blockTouchThrough()
                .testTag("surface.centered"),
        ) {
            SurfaceHeader(title, onDismiss)
            SurfaceBody(content = content)
        }
    }
}

/** The whole screen, opaque, with a back button in the header. */
@Composable
fun FullScreenPage(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    RegisterBack(onDismiss)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .blockTouchThrough()
            .safeDrawingPadding()
            .testTag("surface.page"),
    ) {
        SurfaceHeader(title, onDismiss, back = true)
        SurfaceBody(Modifier.fillMaxWidth(), content = content)
    }
}

/** Which edge a [DockedPanel] sits on. */
enum class DockEdge {
    /** The side away from the rail. */
    SIDE,

    /** Along the bottom, between the rails. */
    BOTTOM,
}

private val DockedSideWidth = 380.dp

/**
 * A panel docked to one edge with no scrim: the canvas beside it stays live and keeps taking strokes,
 * so an editor's changes preview on the artwork as they are made.
 */
@Composable
fun DockedPanel(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    edge: DockEdge = DockEdge.SIDE,
    content: @Composable ColumnScope.() -> Unit,
) {
    RegisterBack(onDismiss)
    val colors = MaterialTheme.colorScheme
    val rail = LocalRailInset.current
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val panel = when (edge) {
            DockEdge.SIDE -> Modifier
                .align(if (rail.dockedOnLeft) Alignment.CenterEnd else Alignment.CenterStart)
                .width(DockedSideWidth)
                .fillMaxHeight()
            DockEdge.BOTTOM -> Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = if (rail.dockedOnLeft) rail.width else 0.dp,
                    end = if (rail.dockedOnLeft) 0.dp else rail.width,
                )
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.4f)
        }
        Column(
            modifier = panel
                .then(modifier)
                .background(colors.surface)
                .border(1.dp, colors.outlineVariant)
                .blockTouchThrough()
                .testTag("surface.docked"),
            verticalArrangement = Arrangement.Top,
        ) {
            SurfaceHeader(title, onDismiss)
            SurfaceBody(content = content)
        }
    }
}
