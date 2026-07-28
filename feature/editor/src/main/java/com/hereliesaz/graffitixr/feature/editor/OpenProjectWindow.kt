// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/OpenProjectWindow.kt
package com.hereliesaz.graffitixr.feature.editor

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.data.DiscoveredProjectFile
import com.hereliesaz.graffitixr.design.components.FloatingWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the Open screen has to show: the app's own projects, plus whatever the device scan found. */
data class OpenScreenState(
    val isScanning: Boolean = false,
    val inApp: List<GraffitiProject> = emptyList(),
    val files: List<DiscoveredProjectFile> = emptyList(),
) {
    val isEmpty: Boolean get() = inApp.isEmpty() && files.isEmpty()
}

/** One openable thing, whichever of the two places it came from. */
private sealed interface OpenItem {
    val key: String
    val name: String
    val location: String

    data class InApp(val project: GraffitiProject, val isCurrent: Boolean) : OpenItem {
        override val key get() = "app:${project.id}"
        override val name get() = project.name
        override val location get() = if (isCurrent) "Open now" else "In app"
    }

    data class OnDevice(val file: DiscoveredProjectFile) : OpenItem {
        override val key get() = "file:${file.uri}"
        override val name get() = file.projectName
        override val location get() = file.location
    }
}

/**
 * Open: every project the device can offer, each with a preview of its canvas, wherever it lives.
 *
 * Projects already in the app and `.fux` files sitting in storage are shown in one grid rather than
 * in two sections, because the distinction is the app's problem and not the user's — what they want
 * is the picture they recognise.
 *
 * The empty state matters more than it looks. Scoped storage means an empty scan doesn't mean "you
 * have no projects", it means "none in the places I'm allowed to look" — a project saved to a cloud
 * provider or an SD card through the document picker is on no path this can walk. So empty offers
 * both ways forward: start something new, or point at where the file actually is.
 */
@Composable
fun OpenProjectWindow(
    state: OpenScreenState,
    currentProjectId: String?,
    onOpenProject: (String) -> Unit,
    onOpenFile: (Uri) -> Unit,
    onNew: () -> Unit,
    onChooseLocation: () -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val items = state.inApp.map { OpenItem.InApp(it, it.id == currentProjectId) } +
        state.files.map { OpenItem.OnDevice(it) }

    FloatingWindow(title = "Open", onDismiss = onDismiss) {
        if (state.isEmpty) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (state.isScanning) "Looking for projects…" else "No .fux found.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (!state.isScanning) {
                    Text(
                        text = "Nothing in the places this app can see. If your project is on a " +
                            "cloud drive or an SD card, point at it directly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
                AzButton(
                    text = "+ New project",
                    onClick = { onNew(); onDismiss() },
                    shape = AzButtonShape.RECTANGLE,
                    modifier = Modifier.fillMaxWidth(),
                )
                AzButton(
                    text = "Choose location…",
                    onClick = onChooseLocation,
                    shape = AzButtonShape.RECTANGLE,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            return@FloatingWindow
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .padding(vertical = 10.dp),
        ) {
            items(items, key = { it.key }) { item ->
                when (item) {
                    is OpenItem.InApp -> OpenCard(
                        name = item.name,
                        location = item.location,
                        isCurrent = item.isCurrent,
                        thumbnailKey = item.project.thumbnailUri?.path,
                        loadThumbnail = {
                            item.project.thumbnailUri?.path?.let { BitmapFactory.decodeFile(it) }
                        },
                        onOpen = { onOpenProject(item.project.id); onDismiss() },
                        onDelete = { onDelete(item.project.id) },
                    )
                    is OpenItem.OnDevice -> OpenCard(
                        name = item.name,
                        location = item.location,
                        isCurrent = false,
                        thumbnailKey = item.file.uri.toString(),
                        loadThumbnail = {
                            item.file.thumbnail?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        },
                        onOpen = { onOpenFile(item.file.uri); onDismiss() },
                        // Deleting someone's file off their device is not this screen's business —
                        // a project inside the app is the app's to remove, a file on disk isn't.
                        onDelete = null,
                    )
                }
            }
        }

        if (state.isScanning) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            }
        }

        AzButton(
            text = "+ New project",
            onClick = { onNew(); onDismiss() },
            shape = AzButtonShape.RECTANGLE,
            modifier = Modifier.fillMaxWidth(),
        )
        AzButton(
            text = "Choose location…",
            onClick = onChooseLocation,
            shape = AzButtonShape.RECTANGLE,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OpenCard(
    name: String,
    location: String,
    isCurrent: Boolean,
    thumbnailKey: String?,
    loadThumbnail: () -> android.graphics.Bitmap?,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    // Decoding is keyed on the source rather than done inline: a grid of previews is a grid of PNG
    // decodes, and on the composition thread that is a visibly stuttering scroll.
    val thumb by produceState<ImageBitmap?>(initialValue = null, thumbnailKey) {
        value = withContext(Dispatchers.IO) {
            runCatching { loadThumbnail()?.asImageBitmap() }.getOrNull()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .border(
                width = if (isCurrent) 2.dp else 0.dp,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                .clickable { onOpen() },
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = thumb
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
            } else {
                // A project saved before it had anything on it has no preview, and so does one
                // whose preview didn't decode. Neither is worth an error — the name still opens it.
                Text("—", color = Color.Gray)
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = location,
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onDelete != null) {
                Text(
                    text = "✕",
                    color = Color.Gray,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clickable { onDelete() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}
