package com.hereliesaz.graffux

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.GraffuxIcons

/**
 * The sheet's stage controls as a row of two 48dp buttons around the name pill: down (show less, or
 * close the sheet at its lowest stage) and up (show more, dimmed away at the top). The same steps a swipe
 * up or down takes.
 */
@Suppress("FunctionNaming")
@Composable
internal fun SheetStageBar(stages: SheetStages, name: String?, onClose: () -> Unit) {
    val canDown = stages.canStepDown()
    val canUp = stages.canStepUp()
    val downLabel = stringResource(if (canDown) R.string.sheet_show_less else R.string.sheet_close)
    val upLabel = stringResource(R.string.sheet_show_more)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.testTag("carousel.stageBar"),
    ) {
        StageButton(
            icon = GraffuxIcons.ChevronDown,
            label = downLabel,
            tag = "carousel.stage.down",
            enabled = true,
        ) { if (!stages.onStepDown()) onClose() }
        if (name != null) NamePill(name) else Spacer(Modifier.width(PillMaxWidth / 2))
        // At the top stage the up control is drawn away but keeps its place, so the pill does not move,
        // and is neither tappable nor announced.
        StageButton(icon = GraffuxIcons.ChevronUp, label = upLabel, tag = "carousel.stage.up", enabled = canUp) {
            stages.onStepUp()
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun StageButton(icon: Int, label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(StageButtonSize)
            // The tag goes before the clearing modifier: semantics after it (inside it) are dropped.
            .testTag(tag)
            .then(
                if (enabled) {
                    Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
                        .semantics { contentDescription = label }
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            ),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Accessibility actions mirroring the stage steps on a sheet that has stages and is open: show more, show
 * less (or close, when there is no lower stage), and close.
 */
internal fun Modifier.stageActions(
    stages: SheetStages?,
    open: Boolean,
    labels: StageLabels,
    onClose: () -> Unit,
): Modifier = if (stages == null || !open) this else semantics {
    val actions = mutableListOf<CustomAccessibilityAction>()
    if (stages.canStepUp()) actions += CustomAccessibilityAction(labels.up) { stages.onStepUp(); true }
    if (stages.canStepDown()) actions += CustomAccessibilityAction(labels.down) { stages.onStepDown(); true }
    actions += CustomAccessibilityAction(labels.close) { onClose(); true }
    customActions = actions
}

/** What the stage controls and actions are called: show more, show less, close the sheet. */
internal class StageLabels(val up: String, val down: String, val close: String)

private val StageButtonSize = 48.dp

