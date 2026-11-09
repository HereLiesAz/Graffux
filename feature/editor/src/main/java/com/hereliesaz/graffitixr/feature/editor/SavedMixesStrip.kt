package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.azphalt.PaletteBoard
import com.hereliesaz.graffitixr.common.azphalt.PaletteRecipe
import com.hereliesaz.graffitixr.common.azphalt.PaletteRecipes
import com.hereliesaz.graffitixr.common.azphalt.PigmentConcentration
import com.hereliesaz.graffitixr.design.components.GraffuxButton
import com.hereliesaz.graffitixr.design.components.GraffuxButtonShape

/** A saved mix and where it is kept. */
class SavedMix(val recipe: PaletteRecipe, val scope: RecipeLibrary.Scope)

/**
 * What the strip asks the owner to do; recalling is local (the strip owns the board). Failures are the owner's to
 * tell.
 */
class MixActions(
    val save: (PaletteRecipe, RecipeLibrary.Scope) -> Unit = { _, _ -> },
    val saveColour: (Color) -> Unit = {},
    val rename: (SavedMix, String) -> Unit = { _, _ -> },
    val delete: (SavedMix) -> Unit = {},
)

/** What the strip needs from its host: the editor's selected colour (for "Save current colour") and the brush hook. */
class MixContext(val editorColour: () -> Color, val onBrushChanged: () -> Unit)

/** The words the strip announces, kept in one place so tests and screen readers agree. */
internal object MixWords {
    private fun brushName(hand: Int) = if (hand == 0) "foreground" else "background"

    fun recalled(name: String, hand: Int) = "Recalled $name onto the ${brushName(hand)} brush"

    fun recallLabel(name: String, hand: Int) = "Recall $name onto the ${brushName(hand)} brush"
    const val EMPTY_BRUSH = "The brush holds no paint to save"
    fun refused(name: String, why: String) = "Couldn't recall $name: $why"
    fun saved(name: String, scope: RecipeLibrary.Scope) =
        "Saved $name to ${if (scope == RecipeLibrary.Scope.PROJECT) "this project" else "all projects"}"
}

/**
 * The saved-mixes strip of the mixer (spec/painterly.md 6.4): save the brush's mix to this project or to all projects,
 * save the current colour, and for a chosen mix recall it onto the foreground or the background brush, rename it, or
 * delete it (after a confirmation). Every action is a labelled 48dp button (no long-press anywhere), and each result is
 * announced in a polite live region. Old swatches appear here as mixes with their original colour in the name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun SavedMixesStrip(
    board: PaletteBoard,
    mixes: List<SavedMix>,
    actions: MixActions,
    context: MixContext,
    modifier: Modifier = Modifier,
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<SavedMix?>(null) }
    var deleting by remember { mutableStateOf<SavedMix?>(null) }
    val selected = mixes.firstOrNull { it.recipe.id == selectedId }

    Column(modifier.testTag("mixer.mixes"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Saved mixes",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, scope) in listOf("Save mix: this project" to RecipeLibrary.Scope.PROJECT,
                "Save mix: all projects" to RecipeLibrary.Scope.APP)) {
                GraffuxButton(
                    text = label,
                    onClick = {
                        val name = "Mix ${mixes.size + 1}"
                        val recipe = PaletteRecipes.fromBrush(board, "mix-" + java.util.UUID.randomUUID(), name)
                        if (recipe == null) {
                            status = MixWords.EMPTY_BRUSH
                        } else {
                            actions.save(recipe, scope)
                            status = MixWords.saved(name, scope)
                        }
                    },
                    shape = GraffuxButtonShape.RECTANGLE,
                    modifier = Modifier.testTag("mixer.saveMix.${scope.name}"),
                )
            }
            GraffuxButton(
                text = "Save current colour",
                onClick = {
                    actions.saveColour(context.editorColour()) // read when pressed: the hand may have changed
                    status = "Saved the current colour as a mix"
                },
                shape = GraffuxButtonShape.RECTANGLE,
                modifier = Modifier.testTag("mixer.saveColour"),
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.testTag("mixer.mixList"),
        ) {
            mixes.forEach { mix -> MixChip(board, mix, mix.recipe.id == selectedId) { selectedId = mix.recipe.id } }
        }
        if (selected != null) {
            val name = selected.recipe.name
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (hand in 0..1) {
                    GraffuxButton(
                        text = if (hand == 0) "Recall to foreground" else "Recall to background",
                        onClick = { status = recall(board, selected, hand, context.onBrushChanged) },
                        shape = GraffuxButtonShape.RECTANGLE,
                        modifier = Modifier
                            .semantics { contentDescription = MixWords.recallLabel(name, hand) }
                            .testTag("mixer.recall.$hand"),
                    )
                }
                GraffuxButton(
                    text = "Rename", onClick = { renaming = selected }, shape = GraffuxButtonShape.RECTANGLE,
                    modifier = Modifier.semantics { contentDescription = "Rename $name" }.testTag("mixer.rename"),
                )
                GraffuxButton(
                    text = "Delete", onClick = { deleting = selected }, shape = GraffuxButtonShape.RECTANGLE,
                    modifier = Modifier.semantics { contentDescription = "Delete $name" }.testTag("mixer.delete"),
                )
            }
        }
        renaming?.let { mix ->
            RenameRow(mix, onCancel = { renaming = null }) { name ->
                actions.rename(mix, name)
                status = "Renamed to $name"
                renaming = null
            }
        }
        deleting?.let { mix ->
            DeleteRow(mix, onKeep = { deleting = null }) {
                actions.delete(mix)
                status = "Deleted ${mix.recipe.name}"
                selectedId = null
                deleting = null
            }
        }
        // announced politely whenever it changes
        Text(
            status,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("mixer.mixStatus"),
        )
    }
}

/** Recalls [mix] onto brush [hand]; the announcement says what happened, including a refusal and why. */
private fun recall(board: PaletteBoard, mix: SavedMix, hand: Int, onBrushChanged: () -> Unit): String {
    // The recall goes onto brush [hand]; the brush in hand is only changed if it succeeds.
    val prior = board.hand
    board.hand = hand
    return when (val r = PaletteRecipes.recall(board, mix.recipe)) {
        is PaletteRecipes.Recall.Ok -> {
            onBrushChanged()
            MixWords.recalled(mix.recipe.name, hand)
        }
        is PaletteRecipes.Recall.Refused -> {
            board.hand = prior
            MixWords.refused(mix.recipe.name, r.reason)
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun MixChip(board: PaletteBoard, mix: SavedMix, selected: Boolean, onSelect: () -> Unit) {
    val r = mix.recipe
    val share = FloatArray(PigmentConcentration.BOARD_CHANNELS) { r.conc[it] / PigmentConcentration.SUM.toFloat() }
    val argb = board.colourOf(share, 0)
    val where = if (mix.scope == RecipeLibrary.Scope.PROJECT) "this project" else "all projects"
    val approx = if (r.approximate) ", approximate match" else ""
    val original = r.originalArgb?.let { ", from colour " + swatchHex(Color(it)) }.orEmpty()
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .border(
                if (selected) 2.dp else 1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 1f else 0.4f),
                RoundedCornerShape(8.dp),
            )
            .clickable(role = Role.Button, onClick = onSelect)
            .padding(horizontal = 8.dp)
            .semantics { contentDescription = "${r.name}, saved in $where$approx$original. Select for actions" }
            .testTag("mixer.mix.${r.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(18.dp).background(Color(argb), CircleShape))
        // the colour it was saved from
        r.originalArgb?.let { Box(Modifier.size(10.dp).background(Color(it), CircleShape)) }
        Text(r.name + if (r.approximate) " ≈" else "", style = MaterialTheme.typography.labelSmall)
    }
}

@Suppress("FunctionNaming")
@Composable
private fun RenameRow(mix: SavedMix, onCancel: () -> Unit, onRename: (String) -> Unit) {
    var text by remember { mutableStateOf(mix.recipe.name) }
    val ok = text.isNotBlank() && text.length <= PaletteRecipe.MAX_NAME
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.testTag("mixer.renameRow"),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("mixer.renameField"),
        )
        GraffuxButton(
            text = "Save name", onClick = { onRename(text.trim()) }, enabled = ok, shape = GraffuxButtonShape.RECTANGLE,
            modifier = Modifier.testTag("mixer.confirmRename"),
        )
        GraffuxButton(text = "Cancel", onClick = onCancel, shape = GraffuxButtonShape.RECTANGLE)
    }
}

@Suppress("FunctionNaming")
@Composable
private fun DeleteRow(mix: SavedMix, onKeep: () -> Unit, onDelete: () -> Unit) {
    val where = if (mix.scope == RecipeLibrary.Scope.PROJECT) "this project" else "all projects"
    Column(Modifier.testTag("mixer.deleteRow"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Delete ${mix.recipe.name}? It will be removed from $where.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GraffuxButton(
                text = "Delete it", onClick = onDelete, shape = GraffuxButtonShape.RECTANGLE,
                modifier = Modifier.testTag("mixer.confirmDelete"),
            )
            GraffuxButton(text = "Keep it", onClick = onKeep, shape = GraffuxButtonShape.RECTANGLE)
        }
    }
}
