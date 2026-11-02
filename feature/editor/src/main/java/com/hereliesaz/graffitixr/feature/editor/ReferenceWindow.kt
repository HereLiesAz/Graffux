// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/ReferenceWindow.kt
package com.hereliesaz.graffitixr.feature.editor

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.hereliesaz.graffitixr.design.components.GraffuxButton
import com.hereliesaz.graffitixr.design.components.GraffuxButtonShape
import com.hereliesaz.graffitixr.design.GraffuxIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.hereliesaz.graffitixr.design.components.AppBottomSheet
import com.hereliesaz.graffitixr.design.components.DockEdge
import com.hereliesaz.graffitixr.design.components.DockedPanel
import com.hereliesaz.graffitixr.design.components.LocalRailInset
import com.hereliesaz.graffitixr.design.components.isCompactWidth

/**
 * Procreate's Reference tool: a floating image that stays on top of the canvas so a piece can be
 * drawn while looking at it, without switching apps. On a tablet it is a split view: a panel docked
 * beside the canvas. On a phone, where a panel would cover most of the artwork, it is a small pinned
 * thumbnail in the corner away from the rail that expands into a bottom sheet on tap (closing the
 * sheet shrinks it back to the thumbnail; Back or the sheet's close then closes the reference). Purely
 * a viewing aid: it carries no artwork state, isn't undo-tracked, and isn't part of the project (a
 * v1 scoping choice, since neither Procreate's reference is part of the artwork either).
 */
@Composable
fun ReferenceWindow(
    imageUri: Uri?,
    onPickImage: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isCompactWidth()) {
        DockedPanel(title = "Reference", onDismiss = onDismiss, edge = DockEdge.SIDE) {
            ReferenceBody(imageUri, onPickImage)
        }
        return
    }
    var expanded by rememberSaveable { mutableStateOf(imageUri == null) }
    if (expanded) {
        AppBottomSheet(title = "Reference", onDismiss = { if (imageUri != null) expanded = false else onDismiss() }) {
            ReferenceBody(imageUri, onPickImage)
        }
    } else {
        val rail = LocalRailInset.current
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            Image(
                painter = rememberAsyncImagePainter(imageUri),
                contentDescription = "Reference image, tap to expand",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(if (rail.dockedOnLeft) Alignment.TopEnd else Alignment.TopStart)
                    .padding(top = 72.dp, start = 16.dp, end = 16.dp)
                    .size(96.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    .clickable(role = Role.Button) { expanded = true }
                    .testTag("surface.thumbnail"),
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ColumnScope.ReferenceBody(imageUri: Uri?, onPickImage: () -> Unit) {
        if (imageUri != null) {
            Image(
                painter = rememberAsyncImagePainter(imageUri),
                contentDescription = "Reference image",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().height(160.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(GraffuxIcons.GalleryImport),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "No reference image",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        GraffuxButton(
            text = if (imageUri != null) "Change Image" else "Pick Image",
            onClick = onPickImage,
            shape = GraffuxButtonShape.RECTANGLE,
        )
}
