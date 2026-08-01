// FILE: core/design/src/main/java/com/hereliesaz/graffitixr/design/components/FloatingWindow.kt
package com.hereliesaz.graffitixr.design.components

import com.hereliesaz.graffitixr.design.GraffuxIcons

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * A floating, draggable, collapsible panel for tool options — Procreate-style, in place of a
 * modal [androidx.compose.material3.AlertDialog]. The header bar is the drag handle; unlike a
 * dialog it never dims or blocks the canvas, so several can be open over the artwork at once,
 * each positioned and collapsed independently.
 */
@Composable
fun FloatingWindow(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialOffset: Offset = Offset(60f, 160f),
    content: @Composable ColumnScope.() -> Unit,
) {
    var offset by remember { mutableStateOf(initialOffset) }
    var collapsed by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .widthIn(min = 220.dp, max = 320.dp)
            .zIndex(10f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offset += dragAmount
                    }
                }
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { collapsed = !collapsed }, modifier = Modifier.size(28.dp)) {
                Icon(
                    painter = painterResource(if (collapsed) GraffuxIcons.ChevronDown else GraffuxIcons.ChevronUp),
                    contentDescription = if (collapsed) "Expand" else "Collapse",
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(painterResource(GraffuxIcons.Close), contentDescription = "Close")
            }
        }
        AnimatedVisibility(visible = !collapsed) {
            Column(modifier = Modifier.padding(12.dp), content = content)
        }
    }
}
