@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName", "FunctionNaming", "LongMethod", "TooManyFunctions")

package com.hereliesaz.graffux

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.theme.Amber
import kotlinx.coroutines.delay

/** A [ChromeMessage]'s weight: a NOTICE goes by itself, a WARNING waits for OK. */
internal enum class MessageKind { NOTICE, WARNING }

internal data class ChromeMessage(val id: Long, val kind: MessageKind, val title: String?, val text: String)

/**
 * The app's transient messages: a failed save, a finished export, a store that couldn't be reached.
 * Shown at the top of the screen over the artwork without blocking it.
 */
@Stable
internal class MessageController {
    val messages = mutableStateListOf<ChromeMessage>()
    private var nextId = 0L

    fun show(kind: MessageKind = MessageKind.NOTICE, title: String? = null, message: String) {
        messages += ChromeMessage(nextId++, kind, title, message)
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
internal fun MessageHost(controller: MessageController, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        controller.messages.forEach { message ->
            if (message.kind == MessageKind.NOTICE) {
                LaunchedEffect(message.id) {
                    delay(NOTICE_MS)
                    controller.dismiss(message.id)
                }
            }
            MessageCard(message) { controller.dismiss(message.id) }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun MessageCard(message: ChromeMessage, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val edge = if (message.kind == MessageKind.WARNING) Amber else RailBorder
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .widthIn(max = 420.dp)
            .clip(MessageCardShape)
            .background(RailFill)
            .border(1.dp, edge, MessageCardShape)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("chrome.message")
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            message.title?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            }
            Text(message.text, style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
        }
        Text(
            "OK",
            style = MaterialTheme.typography.labelLarge,
            color = colors.primary,
            modifier = Modifier
                .clickable(onClick = onDismiss)
                .padding(horizontal = 14.dp, vertical = 2.dp),
        )
    }
}

private const val MAX_SHOWN = 3
private const val NOTICE_MS = 4_000L
