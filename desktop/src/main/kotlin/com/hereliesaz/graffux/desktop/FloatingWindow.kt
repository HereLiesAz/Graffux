@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName", "FunctionNaming", "LongMethod", "TooManyFunctions")

package com.hereliesaz.graffux.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

private val MaxWindowWidth = 320.dp
private val MaxWindowHeight = 480.dp
private val TitleBarHeight = 36.dp

/**
 * Desktop's copy of Android's `core:design` `FloatingWindow`: a floating, draggable panel for tool
 * options, Procreate-style. The title bar is the drag handle; ✕ closes it.
 *
 * Simpler than Android's version: no `RailInset` avoidance (this app's rail is a translucent overlay,
 * not docked) and no onscreen clamp. See `DESKTOP.md`'s UI-parity section.
 */
@Composable
fun FloatingWindow(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialOffset: Offset = Offset(120f, 120f),
    content: @Composable ColumnScope.() -> Unit,
) {
    var offset by remember { mutableStateOf(initialOffset) }
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .zIndex(10f)
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .widthIn(min = 220.dp, max = MaxWindowWidth)
            .heightIn(max = MaxWindowHeight)
            .background(colors.surface.copy(alpha = 0.85f))
            .border(1.dp, colors.outlineVariant),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(TitleBarHeight)
                .background(colors.outlineVariant.copy(alpha = 0.35f))
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        offset += drag
                    }
                }
                .padding(start = 12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(TitleBarHeight).clickable(onClickLabel = "Close $title", onClick = onDismiss),
            ) { Text("✕") }
        }
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            content = content,
        )
    }
}
