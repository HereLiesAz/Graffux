@file:Suppress("MagicNumber") // Layout sizes and paint amounts are literal, as in RecentColors.kt.

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap as AndroidBitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.Role
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
import kotlin.math.roundToInt

/** The mixing board's resolution in the editor today (spec default is 512; see the P6 PR notes). */
internal const val MIXER_BOARD_SIZE = 96

/** The twelve tubes the board holds: generic names, placeholder data until calibrated. */
internal val MIXER_TUBES = PlaceholderPigments.set.pigments.take(PigmentConcentration.BOARD_TUBES)

/** A new board for an editing session. Hoist it so closing and reopening the mixer keeps the paint. */
fun newMixerBoard(): PaletteBoard = PaletteBoard(MIXER_BOARD_SIZE, MIXER_TUBES)

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
    savedPalette: List<Color> = emptyList(),
    onSavePaletteColor: (Color) -> Unit = {},
    onRemovePaletteColor: (Color) -> Unit = {},
) {
    var anchor by remember { mutableStateOf(Offset(MIXER_BOARD_SIZE / 2f, MIXER_BOARD_SIZE / 2f)) }
    var version by remember { mutableIntStateOf(0) }
    var tool by remember { mutableStateOf(MixerTool.SQUEEZE) }
    var slot by remember { mutableIntStateOf(0) }
    var hex by remember { mutableStateOf("") }
    var match by remember { mutableStateOf<String?>(null) }
    var boardSize by remember { mutableStateOf(IntSize(1, 1)) }

    val ops = MixerOps(
        board,
        applyColour = { c -> if (board.hand == 0) onSelectColor(c) else onSelectSecondaryColor(c) },
        redraw = { version++ },
    )

    fun toBoard(p: Offset): Offset = Offset(
        p.x / boardSize.width * MIXER_BOARD_SIZE,
        p.y / boardSize.height * MIXER_BOARD_SIZE,
    )

    val image = remember(version) {
        val bmp = AndroidBitmap.createBitmap(board.render(), MIXER_BOARD_SIZE, MIXER_BOARD_SIZE,
            AndroidBitmap.Config.ARGB_8888)
        bmp.asImageBitmap()
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
                                role = Role.RadioButton) { board.hand = i; version++ }
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
                            if (tool == MixerTool.SQUEEZE) ops.squeeze(slot, b.x, b.y, 7f, 0.9f) else ops.dip(b.x, b.y)
                        }
                    }
                    .pointerInput(tool, slot) {
                        boardSize = size
                        var last = Offset.Zero
                        detectDragGestures(
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
                    .semantics { contentDescription = "Mixing board. ${tool.hint}" }
                    .testTag("mixer.board"),
            ) {
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
                    onClick = { ops.mixStep(anchor.x, anchor.y) },
                    shape = GraffuxButtonShape.RECTANGLE,
                    modifier = Modifier
                        .semantics { contentDescription = "Mix once. Runs the brush round the board once" }
                        .testTag("mixer.mixOnce"))
                GraffuxButton(
                    text = "Mix more",
                    onClick = { ops.mixStep(anchor.x, anchor.y, MIX_MORE_PASSES) },
                    shape = GraffuxButtonShape.RECTANGLE,
                    modifier = Modifier
                        .semantics { contentDescription = "Mix more. Runs the brush round the board several times" }
                        .testTag("mixer.mixMore"))
            }
            Spacer(Modifier.height(8.dp))

            // Cleaning the brush: wipe takes part of the load, rinse all of it.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GraffuxButton(text = "Wipe brush", onClick = { board.clean(PaletteBoard.Clean.WIPE); ops.applyBrush() },
                    shape = GraffuxButtonShape.RECTANGLE)
                GraffuxButton(
                    text = "Rinse brush",
                    onClick = { board.clean(PaletteBoard.Clean.RINSE); ops.applyBrush() },
                    shape = GraffuxButtonShape.RECTANGLE)
            }
            Spacer(Modifier.height(10.dp))

            // Saved swatches (the old picker's Palettes tab): tap loads the nearest tube mix, the chip keeps the
            // original colour and its hex. Long-press removes.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Saved", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                GraffuxButton(
                    text = "Save current",
                    onClick = { onSavePaletteColor(if (board.hand == 0) currentColor else secondaryColor) },
                    shape = GraffuxButtonShape.RECTANGLE,
                    modifier = Modifier.testTag("mixer.saveSwatch"))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("mixer.swatches")) {
                savedPalette.forEach { c ->
                    val hexText = swatchHex(c)
                    Row(
                        Modifier
                            .heightIn(min = 48.dp)
                            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                RoundedCornerShape(8.dp))
                            .combinedClickable(
                                onClick = {
                                    match = matchHexToBrush(board, hexText)
                                    if (match != NOT_A_COLOUR) ops.applyBrush()
                                },
                                onLongClick = { onRemovePaletteColor(c) })
                            .padding(horizontal = 8.dp)
                            .semantics {
                                contentDescription = "Saved swatch $hexText. Tap to mix the nearest tube colour, " +
                                    "long-press to remove"
                            }
                            .testTag("mixer.swatch.$hexText"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(18.dp).background(c, CircleShape))
                        Text(hexText, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
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
                        match = matchHexToBrush(board, hex)
                        if (match != NOT_A_COLOUR) ops.applyBrush()
                    },
                    shape = GraffuxButtonShape.RECTANGLE,
                )
            }
            match?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.testTag("mixer.match")) }
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
    onBoard: (PaletteBoard) -> Unit = {},
    savedPalette: List<Color> = emptyList(),
    onSavePaletteColor: (Color) -> Unit = {},
    onRemovePaletteColor: (Color) -> Unit = {},
) {
    val board = remember { newMixerBoard() }
    androidx.compose.runtime.SideEffect { onBoard(board) }
    if (show) {
        PaletteMixerDialog(
            board = board,
            currentColor = currentColor,
            secondaryColor = secondaryColor,
            onSelectColor = onSelectColor,
            onSelectSecondaryColor = onSelectSecondaryColor,
            onSwapColors = onSwapColors,
            onDismiss = onDismiss,
            savedPalette = savedPalette,
            onSavePaletteColor = onSavePaletteColor,
            onRemovePaletteColor = onRemovePaletteColor,
        )
    }
}
