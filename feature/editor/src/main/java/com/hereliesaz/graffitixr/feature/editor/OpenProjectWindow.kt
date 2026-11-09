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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.GraffuxIcons
import com.hereliesaz.graffitixr.design.components.GraffuxButton
import com.hereliesaz.graffitixr.design.components.GraffuxButtonShape
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.data.DiscoveredProjectFile
import com.hereliesaz.graffitixr.design.components.FullWidthButtonHeight
import com.hereliesaz.graffitixr.design.components.ConfirmDialog
import com.hereliesaz.graffitixr.design.components.SurfaceKind
import com.hereliesaz.graffitixr.design.components.ToolSurface
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

    data class InApp(val project: GraffitiProject, val isCurrent: Boolean, override val location: String) : OpenItem {
        override val key get() = "app:${project.id}"
        override val name get() = project.name
    }

    data class OnDevice(val file: DiscoveredProjectFile) : OpenItem {
        override val key get() = "file:${file.uri}"
        override val name get() = file.projectName
        override val location get() = file.location
    }
}

/** The grid's items: the app's own projects (the open one marked), then the files the scan found. */
private fun openItems(
    state: OpenScreenState,
    currentProjectId: String?,
    openNowLabel: String,
    inAppLabel: String,
): List<OpenItem> = state.inApp.map {
    val current = it.id == currentProjectId
    OpenItem.InApp(it, current, location = if (current) openNowLabel else inAppLabel)
} + state.files.map { OpenItem.OnDevice(it) }

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
    val openNow = stringResource(R.string.open_location_current)
    val inApp = stringResource(R.string.open_location_in_app)
    val items = openItems(state, currentProjectId, openNow, inApp)

    // A project is gone for good the moment this fires — deleteRecursively() on disk, no trash,
    // no undo. One accidental tap next to "open" must not be enough to do that.
    var pendingDelete by remember { mutableStateOf<OpenItem.InApp?>(null) }
    pendingDelete?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.open_delete_title),
            // Deleting the project that is open also moves the editor elsewhere: say so.
            message = stringResource(
                if (target.isCurrent) R.string.open_delete_message_current else R.string.open_delete_message,
                target.name,
            ),
            confirmLabel = stringResource(R.string.open_delete_confirm),
            onConfirm = { onDelete(target.project.id); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }

    ToolSurface(kind = SurfaceKind.BROWSER, title = stringResource(R.string.open_title), onDismiss = onDismiss) {
        if (state.isEmpty) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(if (state.isScanning) R.string.open_scanning else R.string.open_none_found),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (!state.isScanning) {
                    Text(
                        text = stringResource(R.string.open_none_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                GraffuxButton(
                    text = stringResource(R.string.open_new_project),
                    onClick = { onNew(); onDismiss() },
                    shape = GraffuxButtonShape.RECTANGLE,
                    modifier = Modifier.fillMaxWidth().height(FullWidthButtonHeight),
                )
                GraffuxButton(
                    text = stringResource(R.string.open_choose_location),
                    onClick = onChooseLocation,
                    shape = GraffuxButtonShape.RECTANGLE,
                    modifier = Modifier.fillMaxWidth().height(FullWidthButtonHeight),
                )
            }
            return@ToolSurface
        }

        // Plain Column of Rows, not LazyVerticalGrid: this window's content already sits inside
        // ToolSurface's own scrollable Column, and a lazy grid nested there is built on
        // SubcomposeLayout -- AzWindow's sizing asks its content for an intrinsic measurement,
        // which Compose refuses to do across a SubcomposeLayout boundary, crashing the instant
        // this window opened. Chunking by 2 reproduces the fixed-2-column layout by hand; a
        // device's on-disk + in-app project count is nowhere near where virtualization matters.
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { item ->
                        Box(modifier = Modifier.weight(1f)) {
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
                                    onDelete = { pendingDelete = item },
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
                                    // Deleting someone's file off their device is not this screen's
                                    // business — a project inside the app is the app's to remove, a
                                    // file on disk isn't.
                                    onDelete = null,
                                )
                            }
                        }
                    }
                    // An odd item out leaves a lone card in the last row without this — without a
                    // matching weight(1f) spacer, Row would let it grow to fill the whole row
                    // instead of sitting in its own half-width column like every other card.
                    if (row.size == 1) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        if (state.isScanning) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            }
        }

        GraffuxButton(
            text = stringResource(R.string.open_new_project),
            onClick = { onNew(); onDismiss() },
            shape = GraffuxButtonShape.RECTANGLE,
            modifier = Modifier.fillMaxWidth().height(FullWidthButtonHeight),
        )
        GraffuxButton(
            text = stringResource(R.string.open_choose_location),
            onClick = onChooseLocation,
            shape = GraffuxButtonShape.RECTANGLE,
            modifier = Modifier.fillMaxWidth().height(FullWidthButtonHeight),
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
    val openLabel = stringResource(R.string.open_project_named, name)
    val deleteLabel = stringResource(R.string.open_delete_named, name)
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
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                .clickable(onClickLabel = openLabel, role = Role.Button) { onOpen() }
                // The card is named by its project; the picture and the "no preview" dash are decoration.
                .semantics { contentDescription = name },
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = thumb
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
            } else {
                // A project saved before it had anything on it has no preview, and so does one
                // whose preview didn't decode. Neither is worth an error — the name still opens it.
                Text(
                    "—",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clearAndSetSemantics { },
                )
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onDelete != null) {
                // A 48dp button that says which project it deletes (a screen reader read the old drawn
                // "✕" as "multiplication x", with a 12dp-padded target about 40dp wide).
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(role = Role.Button, onClick = onDelete)
                        .semantics { contentDescription = deleteLabel }
                        .testTag("open.delete"),
                ) {
                    Icon(
                        painterResource(GraffuxIcons.Close),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
