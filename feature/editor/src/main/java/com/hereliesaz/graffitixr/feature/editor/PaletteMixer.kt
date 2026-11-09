@file:Suppress("MagicNumber") // Layout sizes and paint amounts are literal, as in RecentColors.kt.

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap as AndroidBitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.azphalt.PaletteBoard
import com.hereliesaz.graffitixr.common.azphalt.PigmentConcentration
import com.hereliesaz.graffitixr.common.azphalt.PigmentMixer
import com.hereliesaz.graffitixr.common.azphalt.PlaceholderPigments
import com.hereliesaz.graffitixr.common.azphalt.RecipeSolver
import com.hereliesaz.graffitixr.design.components.GraffuxButton
import com.hereliesaz.graffitixr.design.components.GraffuxButtonShape
import com.hereliesaz.graffitixr.design.components.SurfaceKind
import com.hereliesaz.graffitixr.design.components.ToolSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The mixing board's resolution in the editor today (spec default is 512; see the P6 PR notes). */
internal const val MIXER_BOARD_SIZE = 96

/** The twelve tubes the board holds: generic names, placeholder data until calibrated. */
internal val MIXER_TUBES = PlaceholderPigments.set.pigments.take(PigmentConcentration.BOARD_TUBES)

/** A new board for an editing session. Hoist it so closing and reopening the mixer keeps the paint. */
fun newMixerBoard(): PaletteBoard = PaletteBoard(MIXER_BOARD_SIZE, MIXER_TUBES)

/** One spoken line, numbered so an identical line said twice is still a new announcement. */
internal data class MixerAnnouncement(val id: Int, val text: String)

private enum class MixerTool(val label: String, val hint: String) {
    SQUEEZE("Squeeze", "Touch the board to squeeze out the chosen tube"),
    MIX("Dip and mix", "Touch to dip the brush, drag to mix and pull paint along"),
}

/**
 * The board operations the mixer's gestures perform, and what each does to the editor's colour. **Board-only**
 * operations (squeezing a tube) just redraw: they never apply a colour, because the editor's colour may have been
 * set since (the eyedropper) and does not come from the brush. Operations that change what the brush holds
 * (dip, mix) redraw and apply the brush's colour.
 */
internal class MixerOps(
    private val board: PaletteBoard,
    private val applyColour: (Color) -> Unit,
    private val redraw: () -> Unit,
) {
    fun squeeze(slot: Int, x: Float, y: Float, radius: Float, amount: Float) {
        board.squeeze(slot, x, y, radius, amount)
        redraw()
    }

    fun dip(x: Float, y: Float) {
        board.dip(x, y)
        applyBrush()
    }

    fun drag(path: FloatArray) {
        board.drag(path)
        applyBrush()
    }

    /**
     * The non-drag mix (spec 6.6 item 5): one tap runs a fixed loop of the brush around ([cx], [cy]), the same
     * transaction a drag runs. [passes] > 1 is "mix more". Deterministic, so it needs no fine motor control.
     */
    fun mixStep(cx: Float, cy: Float, passes: Int = 1) {
        val n = MIX_LOOP_POINTS
        val path = FloatArray((n + 1) * 2)
        for (i in 0..n) {
            val a = 2.0 * Math.PI * i / n
            path[i * 2] = cx + (MIX_LOOP_RADIUS * Math.cos(a)).toFloat()
            path[i * 2 + 1] = cy + (MIX_LOOP_RADIUS * Math.sin(a)).toFloat()
        }
        repeat(passes) { board.drag(path) }
        applyBrush()
    }

    /** The brush now holds something different: redraw and apply its colour (if it holds any paint). */
    fun applyBrush() {
        redraw()
        if (board.brush.amount[0] > 0f) applyColour(Color(board.brushColour()))
    }
}

private const val MIX_LOOP_POINTS = 8
private const val MIX_LOOP_RADIUS = 10f
internal const val MIX_MORE_PASSES = 3

/** The hex text for a saved swatch chip, so its original colour is readable, not only visible. */
internal fun swatchHex(c: Color): String = "#%06X".format(c.toArgb() and 0xFFFFFF)

/** What [matchHexToBrush] says when the text is not a colour. */
internal const val NOT_A_COLOUR = "Not a colour"

/**
 * The "Digital" hex match: finds the nearest tube mix to [hex] and loads it on the brush in hand (filling it).
 * Returns the message to show: matched, how far off the nearest mix is, or [NOT_A_COLOUR] (the brush untouched).
 */
internal fun matchHexToBrush(board: PaletteBoard, hex: String): String {
    val target = parseHexColor(hex) ?: return NOT_A_COLOUR
    // A Compose Color packs its ARGB in the upper bits of an unsigned long; toArgb() is the 0xAARRGGBB int.
    val argb = target.toArgb()
    val rgb = intArrayOf((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF)
    val result = RecipeSolver.solve(rgb, MIXER_TUBES)
    val q = PigmentConcentration.quantize(result.concentration)
    board.brush.amount[0] = 1f
    for (c in board.brush.conc.indices) {
        board.brush.conc[c] = if (c < q.size) q[c] / PigmentConcentration.SUM.toFloat() else 0f
    }
    val off = (result.error * 100).roundToInt()
    return if (result.error < 0.02f) "Matched" else "Closest mix, off by $off%"
}

/** One sentence naming what a recipe is made of: the paint itself, not a number slider. */
internal fun describeRecipe(r: PaletteBoard.Recipe): String {
    if (r.tubes.isEmpty() && r.basis <= 0f) return "empty"
    val parts = r.tubes.take(3).map { (p, w) -> "${(w * 100).roundToInt()}% ${p.name}" }
    val rest = if (r.basis > 0.02f) listOf("${(r.basis * 100).roundToInt()}% mixed from the canvas") else emptyList()
    return (parts + rest).joinToString(", ")
}

/**
 * The physical palette (spec/painterly.md 6.3), which replaces the HSV colour picker: squeeze named
 * tubes onto a board, dip the brush, mix by dragging, clean the brush. The brush's colour is whatever
 * it holds; it lands on the editor's foreground (or background) colour as you work. Paint is finite
 * and wetness and load are felt, never shown as sliders.
 *
 * Small "Digital" affordances stay, labelled as such: type a hex colour and the palette finds the
 * nearest tube mix and loads it (saying how far off it is); the canvas eyedropper is unchanged.
 */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
@Composable
fun PaletteMixerDialog(
    board: PaletteBoard,
    currentColor: Color,
    secondaryColor: Color,
    onSelectColor: (Color) -> Unit,
    onSelectSecondaryColor: (Color) -> Unit,
    onSwapColors: () -> Unit,
    onDismiss: () -> Unit,
    onChanged: () -> Unit = {},
    mixes: List<SavedMix> = emptyList(),
    mixActions: MixActions = MixActions(),
) {
    var anchor by remember { mutableStateOf(Offset(MIXER_BOARD_SIZE / 2f, MIXER_BOARD_SIZE / 2f)) }
    var version by remember { mutableIntStateOf(0) }
    var tool by remember { mutableStateOf(MixerTool.SQUEEZE) }
    var slot by remember { mutableIntStateOf(0) }
    var hex by remember { mutableStateOf("") }
    var match by remember { mutableStateOf<String?>(null) }
    var boardSize by remember { mutableStateOf(IntSize(1, 1)) }
    // What the last board action did, announced politely: the board changes only as pixels and the brush's
    // recipe, so without this a screen reader (and anyone who is not looking at the board) hears nothing.
    // Each announcement carries a new id, so saying the same thing twice (a second squeeze) is still a change
    // the live region sees: the line is keyed on the id, and a new node announces again.
    var status by remember { mutableStateOf(MixerAnnouncement(0, "")) }
    // The hand whose brush the open "Rinse it?" question is about, or null. Any other change to the board, the
    // brushes or the hand closes it, so "Rinse it" can never rinse a brush the question was not about.
    var rinsing by remember { mutableStateOf<Int?>(null) }
    val resources = LocalContext.current.resources
    fun handName() = resources.getString(
        if (board.hand == 0) R.string.mixer_hand_foreground else R.string.mixer_hand_background,
    )
    fun announce(text: String) {
        rinsing = null
        status = MixerAnnouncement(status.id + 1, text)
    }
    fun say(id: Int, vararg args: Any) = announce(resources.getString(id, *args))

    // Every board or brush change is a palette change (saved with the project); only operations that change what the
    // brush holds also apply its colour (MixerOps).
    val ops = MixerOps(
        board,
        applyColour = { c -> if (board.hand == 0) onSelectColor(c) else onSelectSecondaryColor(c) },
        redraw = {
            version++
            rinsing = null // the board or a brush changed (a gesture, a recalled mix): the question is stale
            onChanged()
        },
    )

    fun squeezeAt(b: Offset) {
        ops.squeeze(slot, b.x, b.y, 7f, 0.9f)
        say(R.string.mixer_status_squeezed, MIXER_TUBES[slot].name)
    }
    fun dipAt(b: Offset) {
        val before = board.brush.amount[0]
        ops.dip(b.x, b.y)
        // What the dip actually added to the brush decides what is said: bare board, or a brush already full,
        // lifts nothing, and must not be reported as a dip.
        if (board.brush.amount[0] > before) say(R.string.mixer_status_dipped, handName())
        else say(R.string.mixer_status_nothing_to_lift, handName())
    }
    // Mixing works with the paint the brush holds; an empty brush has none, so nothing was mixed.
    fun mixed(id: Int) {
        if (board.brush.amount[0] <= 0f) say(R.string.mixer_status_nothing_to_mix, handName())
        else say(id, handName())
    }

    fun toBoard(p: Offset): Offset = Offset(
        p.x / boardSize.width * MIXER_BOARD_SIZE,
        p.y / boardSize.height * MIXER_BOARD_SIZE,
    )

    // The board image is kept current by colouring only what a gesture changed, off the main thread (a full board
    // re-colour of every texel per touch event ran on it). [pixels] and [bitmap] outlive recompositions; [frame]
    // makes the canvas redraw when a render lands.
    val pixels = remember(board) { IntArray(MIXER_BOARD_SIZE * MIXER_BOARD_SIZE) }
    val bitmap = remember(board) {
        AndroidBitmap.createBitmap(MIXER_BOARD_SIZE, MIXER_BOARD_SIZE, AndroidBitmap.Config.ARGB_8888)
    }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var frame by remember(board) { mutableIntStateOf(0) }
    val renderGate = remember(board) { Mutex() }
    val painted = remember(board) { BooleanArray(1) }
    LaunchedEffect(board, version) {
        // NonCancellable: a snapshot taken is always applied, or the texels it covered would never be redrawn.
        withContext(NonCancellable) {
            renderGate.withLock {
                if (!painted[0]) board.markAllDirty()
                val snapshot = board.takeDirty() ?: return@withLock
                withContext(Dispatchers.Default) { board.render(snapshot, pixels) }
                bitmap.setPixels(
                    pixels, snapshot.y * MIXER_BOARD_SIZE + snapshot.x, MIXER_BOARD_SIZE,
                    snapshot.x, snapshot.y, snapshot.width, snapshot.height,
                )
                painted[0] = true
                frame++
            }
        }
    }
    val recipe = remember(version, board.hand) { board.brushRecipe() }

    ToolSurface(kind = SurfaceKind.EDITOR, title = "Paint", onDismiss = onDismiss) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // The brush in hand: foreground or background, what it holds and the colour that makes.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("Foreground" to currentColor, "Background" to secondaryColor).forEachIndexed { i, (label,
                    color) ->
                    Box(
                        Modifier
                            .size(48.dp)
                            .background(color, CircleShape)
                            .border(if (board.hand == i) 3.dp else 1.dp,
                                Color.White.copy(alpha = if (board.hand == i) 1f else 0.4f), CircleShape)
                            .selectable(selected = board.hand == i,
                                role = Role.RadioButton) {
                                board.hand = i
                                version++
                                rinsing = null
                                say(R.string.mixer_status_hand, handName().replaceFirstChar(Char::uppercase))
                            }
                            .semantics { contentDescription = "$label brush" }
                            .testTag("mixer.hand.$i"),
                    )
                }
                GraffuxButton(
                    text = "Swap",
                    onClick = {
                        // The colours swap in the editor state; the paint on the two brushes swaps with them, so
                        // what each brush holds still agrees with the colour shown, and later mixing starts from
                        // the swapped paint rather than overwriting it with the old recipe.
                        board.swapBrushes()
                        onSwapColors()
                        version++
                        onChanged()
                        rinsing = null
                        say(R.string.mixer_status_swapped)
                    },
                    shape = GraffuxButtonShape.RECTANGLE,
                )
            }
            Text(
                "Brush holds: ${describeRecipe(recipe)}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .semantics { contentDescription = "Brush holds ${describeRecipe(recipe)}" }
                    .testTag("mixer.recipe"),
            )
            Spacer(Modifier.height(8.dp))

            // Tubes: named, with a physical note; chosen by name, never by colour alone.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MIXER_TUBES.forEachIndexed { i, tube ->
                    val dot = PigmentMixer.mixSrgb8(FloatArray(MIXER_TUBES.size).also { it[i] = 1f }, MIXER_TUBES)
                    val cover = if (tube.hiding > 0.7f) "opaque" else "transparent"
                    val tint = if (tube.tinting > 0.7f) "strong tinter" else "weak tinter"
                    val note = "$cover, $tint"
                    Row(
                        Modifier
                            .heightIn(min = 48.dp)
                            .border(if (slot == i) 2.dp else 1.dp,
                                MaterialTheme.colorScheme.onSurface.copy(alpha = if (slot == i) 1f else 0.4f),
                                RoundedCornerShape(8.dp))
                            .selectable(selected = slot == i,
                                role = Role.RadioButton) { slot = i; tool = MixerTool.SQUEEZE }
                            .padding(horizontal = 8.dp)
                            .semantics { contentDescription = "${tube.name}, $note" }
                            .testTag("mixer.tube.${tube.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(18.dp).background(Color(dot[0], dot[1], dot[2]), CircleShape))
                        Text(tube.name, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MixerTool.entries.forEach { t ->
                    GraffuxButton(
                        text = t.label,
                        onClick = { tool = t },
                        shape = GraffuxButtonShape.RECTANGLE,
                        modifier = Modifier.semantics { contentDescription = "${t.label}. ${t.hint}" },
                    )
                }
            }
            Text(tool.hint, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))

            // The board.
            Canvas(
                Modifier
                    .size(280.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .background(Color(0xFFEDE6D6), RoundedCornerShape(12.dp))
                    .pointerInput(tool, slot) {
                        boardSize = size
                        detectTapGestures { p ->
                            val b = toBoard(p)
                            anchor = b
                            if (tool == MixerTool.SQUEEZE) squeezeAt(b) else dipAt(b)
                        }
                    }
                    .pointerInput(tool, slot) {
                        boardSize = size
                        var last = Offset.Zero
                        detectDragGestures(
                            onDragEnd = {
                                if (tool == MixerTool.MIX) mixed(R.string.mixer_status_mixed)
                                else say(R.string.mixer_status_squeezed, MIXER_TUBES[slot].name)
                            },
                            onDragStart = { p ->
                                last = toBoard(p)
                                if (tool == MixerTool.MIX) {
                                    ops.dip(last.x, last.y)
                                } else {
                                    ops.squeeze(slot, last.x, last.y, 4f, 0.4f)
                                }
                            },
                            onDrag = { change, _ ->
                                val now = toBoard(change.position)
                                if (tool == MixerTool.MIX) {
                                    ops.drag(floatArrayOf(last.x, last.y, now.x, now.y))
                                } else {
                                    ops.squeeze(slot, now.x, now.y, 4f, 0.4f)
                                }
                                last = now
                            },
                        )
                    }
                    .semantics {
                        contentDescription = resources.getString(R.string.mixer_board_description, tool.hint)
                        // The board takes touches at a place; these do the same at the last touched spot, so a
                        // screen reader or a switch can squeeze and dip without pointing.
                        customActions = listOf(
                            CustomAccessibilityAction(resources.getString(R.string.mixer_action_squeeze)) {
                                squeezeAt(anchor); true
                            },
                            CustomAccessibilityAction(resources.getString(R.string.mixer_action_dip)) {
                                dipAt(anchor); true
                            },
                        )
                    }
                    .testTag("mixer.board"),
            ) {
                frame // read here so the canvas redraws when a render lands
                drawIntoCanvas {
                    drawImage(
                        image = image,
                        dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                        dstOffset = IntOffset.Zero,
                        filterQuality = FilterQuality.Low,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // Mixing without dragging: each press runs the brush round the last touched spot.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GraffuxButton(
                    text = "Mix once",
                    onClick = { ops.mixStep(anchor.x, anchor.y); mixed(R.string.mixer_status_mixed) },
                    shape = GraffuxButtonShape.RECTANGLE,
                    modifier = Modifier
                        .semantics { contentDescription = "Mix once. Runs the brush round the board once" }
                        .testTag("mixer.mixOnce"))
                GraffuxButton(
                    text = "Mix more",
                    onClick = {
                        ops.mixStep(anchor.x, anchor.y, MIX_MORE_PASSES)
                        mixed(R.string.mixer_status_mixed_more)
                    },
                    shape = GraffuxButtonShape.RECTANGLE,
                    modifier = Modifier
                        .semantics { contentDescription = "Mix more. Runs the brush round the board several times" }
                        .testTag("mixer.mixMore"))
            }
            Spacer(Modifier.height(8.dp))

            // Cleaning the brush: wipe takes part of the load, rinse all of it.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GraffuxButton(
                    text = "Wipe brush",
                    onClick = {
                        rinsing = null
                        if (board.brush.amount[0] <= 0f) {
                            say(R.string.mixer_status_already_clean, handName())
                        } else {
                            board.clean(PaletteBoard.Clean.WIPE)
                            ops.applyBrush()
                            say(R.string.mixer_status_wiped, handName())
                        }
                    },
                    shape = GraffuxButtonShape.RECTANGLE,
                    modifier = Modifier.testTag("mixer.wipe"))
                GraffuxButton(
                    text = "Rinse brush",
                    onClick = {
                        // Rinsing takes all the paint off the brush in hand, so it asks first; an empty
                        // brush has nothing to lose and says so instead.
                        if (board.brush.amount[0] <= 0f) say(R.string.mixer_status_already_clean, handName())
                        else rinsing = board.hand
                    },
                    shape = GraffuxButtonShape.RECTANGLE,
                    modifier = Modifier.testTag("mixer.rinse"))
            }
            if (rinsing == board.hand) {
                Column(Modifier.testTag("mixer.rinseRow"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.mixer_rinse_ask, handName()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GraffuxButton(
                            text = stringResource(R.string.mixer_rinse_it),
                            onClick = {
                                rinsing = null
                                board.clean(PaletteBoard.Clean.RINSE)
                                ops.applyBrush()
                                say(R.string.mixer_status_rinsed, handName())
                            },
                            shape = GraffuxButtonShape.RECTANGLE,
                            modifier = Modifier.testTag("mixer.confirmRinse"))
                        GraffuxButton(
                            text = stringResource(R.string.mixer_keep_it),
                            onClick = { rinsing = null },
                            shape = GraffuxButtonShape.RECTANGLE)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            // Saved mixes: the old saved swatches live here too, as mixes (migrated, with their original colour).
            SavedMixesStrip(
                board = board,
                mixes = mixes,
                actions = mixActions,
                context = MixContext({ if (board.hand == 0) currentColor else secondaryColor }) { ops.applyBrush() },
            )
            Spacer(Modifier.height(10.dp))

            // Digital: impossible with real paint, so labelled as such and kept small.
            Text("Digital: match a colour", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = hex,
                    onValueChange = { hex = it },
                    label = { Text("Hex") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("mixer.hex"),
                )
                GraffuxButton(
                    text = "Match",
                    onClick = {
                        val result = matchHexToBrush(board, hex)
                        match = result
                        if (result != NOT_A_COLOUR) {
                            ops.applyBrush()
                            say(R.string.mixer_status_matched, result, handName())
                        } else {
                            announce(result)
                        }
                    },
                    shape = GraffuxButtonShape.RECTANGLE,
                )
            }
            match?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.testTag("mixer.match")) }
            // Announced politely whenever it changes (hidden to the eye only by being empty until a board
            // action happens; it is the line under the board the action's words are written to).
            key(status.id) {
                Text(
                    status.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("mixer.status"),
                )
            }
        }
    }
}

/**
 * Hosts the mixer: the board is held **outside** the `show` condition, so closing the dialog keeps the paint on
 * the board and both brushes, and reopening finds it as it was. [onBoard] reports the board in use (tests, and a
 * session-level owner that wants to save it).
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun PaletteMixerHost(
    show: Boolean,
    currentColor: Color,
    secondaryColor: Color,
    onSelectColor: (Color) -> Unit,
    onSelectSecondaryColor: (Color) -> Unit,
    onSwapColors: () -> Unit,
    onDismiss: () -> Unit,
    onChanged: () -> Unit = {},
    board: PaletteBoard? = null,
    onBoard: (PaletteBoard) -> Unit = {},
    mixes: List<SavedMix> = emptyList(),
    mixActions: MixActions = MixActions(),
) {
    // A view-model-owned board (saved with the project) when given, else one held for the session; either way
    // it is held outside the `show` condition.
    val held = board ?: remember { newMixerBoard() }
    androidx.compose.runtime.SideEffect { onBoard(held) }
    if (show) {
        PaletteMixerDialog(
            board = held,
            currentColor = currentColor,
            secondaryColor = secondaryColor,
            onSelectColor = onSelectColor,
            onSelectSecondaryColor = onSelectSecondaryColor,
            onSwapColors = onSwapColors,
            onDismiss = onDismiss,
            onChanged = onChanged,
            mixes = mixes,
            mixActions = mixActions,
        )
    }
}
