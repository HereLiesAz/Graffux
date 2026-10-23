package com.hereliesaz.graffux

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.design.components.GraffuxAlertDialog

/** Three cards, once: the things a newcomer can't discover by looking. */
private val CARDS = listOf(
    "Pick a brush" to "Brushes, ink and effects are in the sheet at the bottom of the screen. " +
        "Drag or tap its pill to open or shut it.",
    "Size and hardness" to "Drag the brush pad on the rail: up and down for size, left and right " +
        "for hardness. Tap it for sliders.",
    "Undo, and the rest" to "Undo and Redo sit on the rail; a two-finger tap undoes too. Pinch and " +
        "rotate with two fingers. Tap ? on the rail any time for what every button does.",
)

private const val PREFS = "first_run"
private const val KEY_DONE = "tour_done"

/** Shown once, after the first project exists. Skip or Done both mark it seen. */
@Suppress("FunctionNaming")
@Composable
fun FirstRunTour(enabled: Boolean) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var done by remember { mutableStateOf(prefs.getBoolean(KEY_DONE, false)) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    if (!enabled || done) return
    val finish = {
        prefs.edit().putBoolean(KEY_DONE, true).apply()
        done = true
    }
    val (title, body) = CARDS[page]
    val last = page == CARDS.lastIndex
    GraffuxAlertDialog(
        onDismissRequest = finish,
        title = { Text("$title  (${page + 1}/${CARDS.size})") },
        text = { Text(body) },
        confirmButton = {
            AzButton(
                text = if (last) "Done" else "Next",
                onClick = { if (last) finish() else page++ },
                shape = AzButtonShape.RECTANGLE,
            )
        },
        dismissButton = if (last) null else {
            { AzButton(text = "Skip", onClick = finish, shape = AzButtonShape.RECTANGLE) }
        },
    )
}
