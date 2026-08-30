// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/FigmaWindow.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.data.figma.FigmaFrame
import com.hereliesaz.graffitixr.design.components.FloatingWindow
import kotlin.math.roundToInt

/**
 * Import frames from a Figma file. Paste a file link, pick frames, and each arrives as its own
 * layer at the size Figma rendered it.
 *
 * The connect step asks for a Figma **personal access token** rather than running an OAuth sign-in.
 * Figma's OAuth token exchange requires the app's client secret even under PKCE, and a secret inside
 * an APK is extractable — so a proper OAuth flow needs a server to hold it. A token the user creates
 * and can revoke is the honest option until that exists.
 */
@Composable
fun FigmaWindow(
    state: EditorViewModel.FigmaUiState,
    onTokenSubmit: (String) -> Unit,
    onDisconnect: () -> Unit,
    onFileInputChanged: (String) -> Unit,
    onLoadFile: () -> Unit,
    onToggleFrame: (String) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    FloatingWindow(title = "Figma", onDismiss = onDismiss) {
        if (!state.isConnected) {
            ConnectSection(isLoading = state.isLoading, error = state.error, onTokenSubmit = onTokenSubmit)
            return@FloatingWindow
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.fileInput,
                onValueChange = onFileInputChanged,
                label = { Text("Figma file link") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onLoadFile() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            AzButton(text = "Open", onClick = onLoadFile, shape = AzButtonShape.RECTANGLE)
        }

        Spacer(Modifier.height(10.dp))

        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            state.error != null -> Text(
                text = state.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            state.frames.isEmpty() -> Text(
                text = "Paste a Figma file link to see its frames.",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall,
            )
            else -> {
                state.fileName?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                }
                // Plain Column, not LazyColumn: this window's content already sits inside
                // FloatingWindow's own scrollable Column, and a lazy list nested there is built
                // on SubcomposeLayout -- AzWindow's sizing asks its content for an intrinsic
                // measurement, which Compose refuses to do across a SubcomposeLayout boundary,
                // crashing the instant this window opened. One Figma file's frame list is short
                // enough that virtualization was never buying anything here.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.frames.forEach { frame ->
                        FrameRow(
                            frame = frame,
                            isSelected = frame.id in state.selectedIds,
                            onToggle = { onToggleFrame(frame.id) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                AzButton(
                    text = if (state.selectedIds.isEmpty()) {
                        "Select frames to import"
                    } else {
                        "Import ${state.selectedIds.size} frame${if (state.selectedIds.size == 1) "" else "s"}"
                    },
                    onClick = onImport,
                    shape = AzButtonShape.RECTANGLE,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        AzButton(text = "Disconnect", onClick = onDisconnect, shape = AzButtonShape.RECTANGLE)
    }
}

@Composable
private fun ConnectSection(isLoading: Boolean, error: String?, onTokenSubmit: (String) -> Unit) {
    var token by remember { mutableStateOf("") }

    Text(
        "Connect with a Figma personal access token — create one in Figma under " +
            "Settings → Security → Personal access tokens, with file read access.",
        style = MaterialTheme.typography.bodySmall,
        color = Color.Gray,
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text("Personal access token") },
        singleLine = true,
        // The token grants full account access, so it's masked like any other credential.
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onTokenSubmit(token) }),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    if (isLoading) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        AzButton(text = "Connect", onClick = { onTokenSubmit(token) }, shape = AzButtonShape.RECTANGLE)
    }
    error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FrameRow(frame: FigmaFrame, isSelected: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column {
            Text(
                frame.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${frame.pageName} · ${frame.width.roundToInt()}×${frame.height.roundToInt()}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
