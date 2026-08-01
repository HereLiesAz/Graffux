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
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.design.GraffuxIcons
import com.hereliesaz.graffitixr.design.components.FloatingWindow

/**
 * Procreate's Reference tool: a floating image that stays on top of the canvas so a piece can be
 * drawn while looking at it, without switching apps. [FloatingWindow] already gives it drag/collapse
 * for free — this just supplies the "picked image or an empty prompt" body and a swap button. Purely
 * a viewing aid: it carries no artwork state, isn't undo-tracked, and isn't part of the project (a
 * v1 scoping choice, since neither Procreate's reference is part of the artwork either).
 */
@Composable
fun ReferenceWindow(
    imageUri: Uri?,
    onPickImage: () -> Unit,
    onDismiss: () -> Unit,
) {
    FloatingWindow(title = "Reference", onDismiss = onDismiss) {
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
                Icon(painterResource(GraffuxIcons.GalleryImport), contentDescription = null, tint = Color.Gray)
            }
            Text(
                "No reference image",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        AzButton(
            text = if (imageUri != null) "Change Image" else "Pick Image",
            onClick = onPickImage,
            shape = AzButtonShape.RECTANGLE,
        )
    }
}
