package com.hereliesaz.graffux

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.IntentCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.aznavrail.*
import com.hereliesaz.aznavrail.model.*
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.design.theme.AppStrings
import com.hereliesaz.graffitixr.design.theme.Cyan
import com.hereliesaz.graffitixr.design.theme.rememberAppStrings
import com.hereliesaz.graffitixr.feature.editor.DocumentSizeDialog
import com.hereliesaz.graffitixr.feature.editor.EditorScreen
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Graffux entry point — hosts the shared [EditorScreen] (the single source of truth for the
 * multi-layer image editor, migrated from GraffitiXR into :feature:editor). The Hilt-provided
 * [EditorViewModel] and its whole dependency graph (core modules + native bridge) resolve here; the
 * screen forces DESIGN mode, so no AR / SLAM / co-op is involved.
 *
 * The editor sits inside the app's [AzHostActivityLayout] — the same AzNavRail host GraffitiXR uses.
 * The canvas + panels are the rail's full-screen `background`; the rail on top carries Graffux's
 * design tools (trimmed to the DESIGN-only subset — no Modes / AR / co-op / library folders).
 *
 * Interop: when launched via an ACTION_SEND image share (e.g. a wall photo shared from GraffitiXR),
 * the shared image is opened as a new editor layer. Graffux is otherwise fully standalone.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedImage = incomingImageUri(intent)
        setContent {
            MaterialTheme {
                GraffuxApp(sharedImageUri = sharedImage)
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
private fun GraffuxApp(sharedImageUri: Uri?) {
    val vm: EditorViewModel = hiltViewModel()
    val uiState by vm.uiState.collectAsState()
    val railExpansion by vm.railExpansion.collectAsState()
    val strings = rememberAppStrings()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val navController = rememberNavController()

    // Graffux is a pure 2D editor: keep DESIGN mode (the EditorViewModel default) so the layer
    // render and Design-mode transform gestures apply. Runs once.
    LaunchedEffect(Unit) { vm.setEditorMode(EditorMode.DESIGN) }

    // Artboard size picker visibility (opened from the rail's "Size" item).
    var showDocDialog by remember { mutableStateOf(false) }

    // Open a shared image (two-app interop) as a layer once, after the ViewModel exists.
    LaunchedEffect(sharedImageUri) {
        sharedImageUri?.let { vm.onAddLayer(it) }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { vm.onAddLayer(it) } }

    // Rail-item colour that stays legible against the current canvas background (mirrors GraffitiXR).
    val navItemColor = remember(uiState.canvasBackground) {
        val bg = uiState.canvasBackground
        val luminance = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
        if (luminance > 0.5f) Color.Black else Color.White
    }

    AzHostActivityLayout(navController = navController, initiallyExpanded = false) {
        azTheme(
            activeColor = Cyan,
            defaultShape = AzButtonShape.RECTANGLE,
            headerIconShape = AzHeaderIconShape.ROUNDED,
            translucentBackground = Color.Transparent
        )
        azConfig(
            packButtons = true,
            dockingSide = if (uiState.isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT
        )

        ConfigureRailItems(
            vm = vm,
            uiState = uiState,
            railExpansion = railExpansion,
            strings = strings,
            navItemColor = navItemColor,
            onOpenImage = {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onDocumentSize = { showDocDialog = true },
            onShare = {
                // Interop hand-off: composite the design to a content:// Uri and offer it to any app
                // (e.g. GraffitiXR to project in AR). No-op silently if there's nothing to share.
                scope.launch {
                    // exportForShare() does suspend I/O (composite + write to cacheDir) and
                    // startActivity can throw (e.g. no chooser target) — catch so a share failure
                    // surfaces as a log line, never an unhandled-coroutine crash.
                    try {
                        val uri = vm.exportForShare() ?: return@launch
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            // Carry the URI in ClipData too: FLAG_GRANT_READ_URI_PERMISSION grants
                            // against the intent's data/ClipData, not EXTRA_STREAM, so the receiver
                            // can otherwise be handed a URI it lacks read access to.
                            clipData = android.content.ClipData.newRawUri(null, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        // createChooser wraps `send` in a new intent; the grant flag + ClipData don't
                        // propagate to that wrapper, so the share sheet (a separate system process on
                        // API 29+) can't read the URI to render its preview. Re-apply them here.
                        val chooser = Intent.createChooser(send, null).apply {
                            clipData = send.clipData
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(chooser)
                    } catch (t: Throwable) {
                        android.util.Log.w("Graffux", "Share failed", t)
                    }
                }
            },
        )

        // The editor (canvas + gestures + drawing + bottom panels) is the rail's full-screen
        // `background`, exactly as GraffitiXR renders its DESIGN-mode canvas behind the rail.
        background(weight = 0) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                EditorScreen(vm = vm, modifier = Modifier.fillMaxSize())
            }
        }

        // Foreground layer. GraffitiXR pairs the rail with an `onscreen` block (its routed AzNavHost
        // + AR overlays); Graffux's interactive editor sits in `background`, so this hosts only
        // transient overlays like the artboard size picker.
        onscreen(alignment = Alignment.Center) {
            if (showDocDialog) {
                DocumentSizeDialog(
                    currentWidth = uiState.documentWidth,
                    currentHeight = uiState.documentHeight,
                    onConfirm = { w, h ->
                        vm.setDocumentSize(w, h)
                        showDocDialog = false
                    },
                    onDismiss = { showDocDialog = false },
                )
            }
        }
    }
}

/**
 * Declares Graffux's AzNavRail items — the DESIGN-only subset of GraffitiXR's rail. Graffux has no
 * AR / Overlay / Mockup / Trace modes, co-op, or a project library, so those folders are dropped; what
 * remains is the Design toolset (open / add / layers / move / brush / colour / adjust / outline /
 * edges / invert / undo / redo) and a small Project folder (save / export / share).
 */
private fun AzNavHostScope.ConfigureRailItems(
    vm: EditorViewModel,
    uiState: EditorUiState,
    railExpansion: Map<String, Boolean>,
    strings: AppStrings,
    navItemColor: Color,
    onOpenImage: () -> Unit,
    onShare: () -> Unit,
    onDocumentSize: () -> Unit,
) {
    val navStrings = strings.nav

    // 1. DESIGN FOLDER — always expanded (Graffux is permanently in Design mode).
    azRailHostItem(
        id = "host.design",
        text = navStrings.design,
        color = Cyan,
        initiallyExpanded = railExpansion["host.design"] ?: true,
        onExpandedChange = { vm.onRailHostExpansionChanged("host.design", it) },
    )
    azRailSubItem(id = "design.open", hostId = "host.design", text = navStrings.open, color = navItemColor, shape = AzButtonShape.NONE) {
        onOpenImage()
    }
    azRailSubItem(id = "design.add", hostId = "host.design", text = navStrings.new, color = navItemColor, shape = AzButtonShape.NONE) {
        vm.onAddBlankLayer()
    }
    azRailSubItem(
        id = "design.layers",
        hostId = "host.design",
        text = strings.editor.layers,
        color = if (uiState.activePanel == EditorPanel.LAYERS) Cyan else navItemColor,
        shape = AzButtonShape.NONE
    ) {
        vm.onLayersClicked()
    }
    azRailSubItem(
        id = "design.move",
        hostId = "host.design",
        text = navStrings.move,
        color = if (uiState.activeTool == Tool.NONE) Cyan else navItemColor,
        shape = AzButtonShape.NONE
    ) {
        vm.setActiveTool(Tool.NONE)
    }
    azRailSubItem(
        id = "design.brush",
        hostId = "host.design",
        text = navStrings.brush,
        color = if (uiState.activeTool == Tool.BRUSH) Cyan else navItemColor,
        shape = AzButtonShape.NONE
    ) {
        vm.setActiveTool(Tool.BRUSH)
    }
    azRailSubItem(
        id = "design.colour",
        hostId = "host.design",
        text = navStrings.color,
        color = if (uiState.showColorPicker) Cyan else navItemColor,
        shape = AzButtonShape.NONE
    ) {
        vm.onColorClicked()
    }
    azRailSubItem(
        id = "design.adjust",
        hostId = "host.design",
        text = navStrings.adjust,
        color = if (uiState.activePanel == EditorPanel.ADJUST) Cyan else navItemColor,
        shape = AzButtonShape.NONE
    ) {
        vm.onAdjustClicked()
    }
    // Core tracing prep (act on the active overlay layer) — matches GraffitiXR's Design sub-items.
    val overlay = uiState.layers.find { it.id == uiState.activeLayerId }
    if (overlay != null) {
        azRailSubItem(id = "design.outline", hostId = "host.design", text = navStrings.outline, color = navItemColor, shape = AzButtonShape.NONE) {
            vm.onSketchClicked()
        }
        azRailSubItem(id = "design.edges", hostId = "host.design", text = navStrings.edges, color = navItemColor, shape = AzButtonShape.NONE) {
            vm.onApplyCannyEdgeClicked()
        }
        azRailSubItem(id = "design.invert", hostId = "host.design", text = navStrings.invert, color = navItemColor, shape = AzButtonShape.NONE) {
            vm.onToggleInvert()
        }
    }
    azRailSubItem(
        id = "design.undo",
        hostId = "host.design",
        text = navStrings.undo,
        color = if (uiState.undoCount > 0) navItemColor else Color.Gray,
        shape = AzButtonShape.NONE
    ) {
        if (uiState.undoCount > 0) vm.onUndoClicked()
    }
    azRailSubItem(
        id = "design.redo",
        hostId = "host.design",
        text = navStrings.redo,
        color = if (uiState.redoCount > 0) navItemColor else Color.Gray,
        shape = AzButtonShape.NONE
    ) {
        if (uiState.redoCount > 0) vm.onRedoClicked()
    }

    azDivider()

    // 2. PROJECT FOLDER — size / save / export / share.
    azRailHostItem(
        id = "host.project",
        text = navStrings.project,
        color = navItemColor,
        initiallyExpanded = railExpansion["host.project"] ?: false,
        onExpandedChange = { vm.onRailHostExpansionChanged("host.project", it) },
    )
    azRailSubItem(
        id = "proj.size",
        hostId = "host.project",
        text = "${uiState.documentWidth}×${uiState.documentHeight}",
        color = navItemColor,
        shape = AzButtonShape.NONE,
    ) {
        onDocumentSize()
    }
    azRailSubItem(id = "proj.save", hostId = "host.project", text = navStrings.save, color = navItemColor, shape = AzButtonShape.NONE) {
        vm.saveProject()
    }
    azRailSubItem(id = "proj.export", hostId = "host.project", text = navStrings.export, color = navItemColor, shape = AzButtonShape.NONE) {
        vm.exportImage()
    }
    azRailSubItem(id = "proj.share", hostId = "host.project", text = navStrings.share, color = navItemColor, shape = AzButtonShape.NONE) {
        onShare()
    }
}
