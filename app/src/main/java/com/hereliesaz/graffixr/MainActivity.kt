package com.hereliesaz.graffixr

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.hereliesaz.graffitixr.feature.editor.EditorScreen
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * GraffiXR entry point — hosts the shared [EditorScreen] (the single source of truth for the
 * multi-layer image editor, migrated from GraffitiXR into :feature:editor). The Hilt-provided
 * [EditorViewModel] and its whole dependency graph (core modules + native bridge) resolve here; the
 * screen forces DESIGN mode, so no AR / SLAM / co-op is involved.
 *
 * Interop: when launched via an ACTION_SEND image share (e.g. a wall photo shared from GraffitiXR),
 * the shared image is opened as a new editor layer. GraffiXR is otherwise fully standalone.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedImage = incomingImageUri(intent)
        setContent {
            MaterialTheme {
                GraffixrApp(sharedImageUri = sharedImage)
            }
        }
    }
}

/**
 * Extracts a single image [Uri] from an inbound share/view intent, or null if this launch isn't one.
 * Handles `ACTION_SEND` (EXTRA_STREAM) and `ACTION_VIEW` (data URI). The sender grants read
 * permission on the URI to this activity, so the editor's ContentResolver load succeeds.
 */
private fun incomingImageUri(intent: Intent?): Uri? {
    if (intent == null) return null
    val isImage = intent.type?.startsWith("image/") == true
    return when (intent.action) {
        Intent.ACTION_SEND ->
            if (isImage) IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) else null
        Intent.ACTION_VIEW -> intent.data?.takeIf { isImage }
        else -> null
    }
}

@Composable
private fun GraffixrApp(sharedImageUri: Uri?) {
    val vm: EditorViewModel = hiltViewModel()

    // Open a shared image (two-app interop) as a layer once, after the ViewModel exists.
    LaunchedEffect(sharedImageUri) {
        sharedImageUri?.let { vm.onAddLayer(it) }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        EditorScreen(vm = vm, modifier = Modifier.fillMaxSize())
    }
}
