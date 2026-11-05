@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName", "FunctionNaming", "LongMethod", "TooManyFunctions")

package com.hereliesaz.graffux

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.theme.Amber
import kotlinx.coroutines.delay

/** A [ChromeMessage]'s weight: a NOTICE goes by itself, a WARNING waits for OK. */
internal enum class MessageKind { NOTICE, WARNING }

internal data class ChromeMessage(
    val id: Long,
    val kind: MessageKind,
    val title: String?,
    val text: String,
    /** An optional second button ("Open"), which also dismisses the message. */
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    /** How many identical messages this one stands for; shown as "×n" from 2 up. */
    val count: Int = 1,
)

/**
 * The app's transient messages: a failed save, a finished export, a store that couldn't be reached.
 * Shown at the top of the screen over the artwork without blocking it.
 */
@Stable
internal class MessageController {
    val messages = mutableStateListOf<ChromeMessage>()
    private var nextId = 0L

    fun show(
        kind: MessageKind = MessageKind.NOTICE,
        title: String? = null,
        message: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        // An identical message (same kind, title, text and action label) already showing is not
        // stacked again: it counts up, and keeps its place.
        val shown = messages.indexOfFirst {
            it.kind == kind && it.title == title && it.text == message && it.actionLabel == actionLabel
        }
        if (shown >= 0) {
            messages[shown] = messages[shown].copy(count = messages[shown].count + 1, onAction = onAction)
            return
        }
        messages += ChromeMessage(nextId++, kind, title, message, actionLabel, onAction)
        while (messages.size > MAX_SHOWN) messages.removeAt(0)
    }

    fun dismiss(id: Long) {
        messages.removeAll { it.id == id }
    }
}

@Composable
internal fun rememberMessageController(): MessageController = remember { MessageController() }

private val MessageCardShape = RoundedCornerShape(12.dp)

/** Draws [controller]'s messages, newest last. A NOTICE leaves after [NOTICE_MS]. */
@Suppress("FunctionNaming")
@Composable
internal fun MessageHost(controller: MessageController, modifier: Modifier = Modifier, okLabel: String = "OK",
    countLabel: (Int) -> String = { "$it times" },
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        controller.messages.forEach { message ->
            if (message.kind == MessageKind.NOTICE) {
                // Keyed on the count too: a merged message gets its full time again.
                LaunchedEffect(message.id, message.count) {
                    delay(NOTICE_MS)
                    controller.dismiss(message.id)
                }
            }
            MessageCard(message, okLabel, countLabel) { controller.dismiss(message.id) }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun MessageCard(message: ChromeMessage, okLabel: String, countLabel: (Int) -> String, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val edge = if (message.kind == MessageKind.WARNING) Amber else RailBorder
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = 420.dp)
            .clip(MessageCardShape)
            .background(RailFill)
            .border(1.dp, edge, MessageCardShape)
            .testTag("chrome.message")
            .padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
    ) {
        // Announced as one phrase, count included ("... 3 times"), and again when it changes.
        val spoken = listOfNotNull(message.title, message.text, if (message.count > 1) countLabel(message.count) else null)
            .joinToString(" ")
        Column(
            Modifier
                .weight(1f, fill = false)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    liveRegion = LiveRegionMode.Polite
                },
        ) {
            message.title?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (message.count > 1) {
                    Text(
                        "×${message.count}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp).testTag("chrome.message.count"),
                    )
                }
            }
        }
        val action = message.onAction
        if (message.actionLabel != null && action != null) {
            MessageButton(message.actionLabel, "chrome.message.action") { onDismiss(); action() }
        }
        MessageButton(okLabel, "chrome.message.ok", onDismiss)
    }
}

/** A message's button: a 48dp target (it was ~24dp of bare text), announced as a button. */
@Suppress("FunctionNaming")
@Composable
private fun MessageButton(label: String, tag: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp)
            .testTag(tag),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

private const val MAX_SHOWN = 3
private const val NOTICE_MS = 4_000L
