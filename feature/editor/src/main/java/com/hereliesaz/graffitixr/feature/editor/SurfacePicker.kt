@file:Suppress("MagicNumber", "FunctionNaming")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.scene.SurfaceParse
import com.hereliesaz.graffitixr.common.scene.SurfacePayload
import com.hereliesaz.graffitixr.data.azphalt.ExtensionRepository.InstalledSurfaceAsset
import com.hereliesaz.graffitixr.design.components.SurfaceKind
import com.hereliesaz.graffitixr.design.components.ToolSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Insert surface": the ready-made surfaces of installed packs of surfaces, each with its front outline.
 * Tapping one inserts it as a new group ([EditorViewModel.onInsertInstalledSurface]) and closes the picker.
 * Reached from the 3D toolbox. A plain Column, as in [BlendModePicker]: the window already scrolls.
 */
@Composable
fun SurfacePicker(vm: EditorViewModel, onDismiss: () -> Unit) {
    val items = remember { vm.installedSurfaces() }
    ToolSurface(kind = SurfaceKind.PICKER, title = "Insert surface", onDismiss = onDismiss) {
        if (items.isEmpty()) {
            Text(
                "No surfaces installed. Install a pack of surfaces from the Store, then insert one here.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 12.dp).testTag("surface.picker.empty"),
            )
        } else {
            Column {
                items.forEach { item ->
                    SurfaceRow(vm, item) {
                        vm.onInsertInstalledSurface(item.extensionId, item.assetIndex)
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun SurfaceRow(vm: EditorViewModel, item: InstalledSurfaceAsset, onPick: () -> Unit) {
    // Read off the main thread; a payload that fails validation shows no outline and says why on insert.
    val parsed by produceState<SurfaceParse?>(null, item) {
        value = withContext(Dispatchers.IO) { vm.loadInstalledSurface(item.extensionId, item.assetIndex) }
    }
    val payload = (parsed as? SurfaceParse.Ok)?.payload
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 8.dp)
            .testTag("surface.picker.item"),
    ) {
        val line = MaterialTheme.colorScheme.primary
        Canvas(Modifier.size(56.dp)) {
            val pts = payload?.front ?: return@Canvas
            val pad = 6.dp.toPx()
            val k = size.minDimension - 2 * pad
            val path = Path()
            pts.forEachIndexed { i, (x, y) ->
                val o = Offset(pad + x * k, pad + y * k)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            if (SurfacePayload.isClosedOutline(pts)) path.close()
            drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (parsed is SurfaceParse.Rejected) "Can't be used" else item.packName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
