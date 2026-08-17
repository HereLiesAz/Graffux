// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/GalleryWindow.kt
package com.hereliesaz.graffitixr.feature.editor

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.hereliesaz.graffitixr.design.components.FloatingWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Gallery — Procreate's home surface, as a floating window: every project as a thumbnail card;
 * tap to open, plus New and per-card Delete. Thumbnails are the small previews the editor already
 * maintains per project (see scheduleThumbnailUpdate), decoded off the main thread.
 *
 * NOT WIRED IN (deliberately, not an oversight): `OpenProjectWindow` already covers this exact job
 * — open/new/delete a project — and additionally exposes `onOpenFile`/`onChooseLocation` (opening a
 * project from an arbitrary on-disk file via the system picker) that this composable has no
 * parameters for at all. Giving the app two different "open a project" surfaces would be confusing,
 * and swapping `OpenProjectWindow` out for this one would silently drop the file-picker path. Making
 * this the sole surface would mean adding that capability here first — a real feature change, not an
 * audit-driven bug fix. Left as a nicer-looking (thumbnail grid vs. list) alternative implementation
 * that was built but never swapped in.
 */
@Composable
fun GalleryWindow(
    projects: List<GraffitiProject>,
    currentProjectId: String?,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    FloatingWindow(title = "Gallery", onDismiss = onDismiss) {
        AzButton(
            text = "+ New canvas",
            onClick = onNew,
            shape = AzButtonShape.RECTANGLE,
            modifier = Modifier.fillMaxWidth(),
        )
        if (projects.isEmpty()) {
            Text(
                text = "No projects yet.",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .padding(top = 10.dp),
            ) {
                items(projects, key = { it.id }) { project ->
                    GalleryCard(
                        project = project,
                        isCurrent = project.id == currentProjectId,
                        onOpen = { onOpen(project.id); onDismiss() },
                        onDelete = { onDelete(project.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryCard(
    project: GraffitiProject,
    isCurrent: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumbnailPath = project.thumbnailUri?.path
    val thumb by produceState<ImageBitmap?>(initialValue = null, thumbnailPath) {
        value = thumbnailPath?.let { path ->
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
            }
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
                    contentDescription = project.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
            } else {
                Text("—", color = Color.Gray)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = project.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "✕",
                color = Color.Gray,
                modifier = Modifier
                    .clickable { onDelete() }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
