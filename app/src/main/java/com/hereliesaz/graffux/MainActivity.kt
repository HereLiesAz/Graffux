package com.hereliesaz.graffux

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.IntentCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.aznavrail.*
import com.hereliesaz.aznavrail.model.*
import com.hereliesaz.graffitixr.common.model.BlendMode
import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.ShapeKind
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.design.theme.AppStrings
import com.hereliesaz.graffitixr.design.theme.Cyan
import com.hereliesaz.graffitixr.design.theme.rememberAppStrings
import com.hereliesaz.graffitixr.feature.editor.AlignMode
import com.hereliesaz.graffitixr.feature.editor.BackgroundColorDialog
import com.hereliesaz.graffitixr.feature.editor.BlendModePicker
import com.hereliesaz.graffitixr.feature.editor.CornerRadiusDialog
import com.hereliesaz.graffitixr.feature.editor.DocumentSizeDialog
import com.hereliesaz.graffitixr.feature.editor.EditorScreen
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.feature.editor.PolygonSidesDialog
import com.hereliesaz.graffitixr.feature.editor.ShapeSizeDialog
import com.hereliesaz.graffitixr.feature.editor.TextEditDialog
import com.hereliesaz.graffitixr.feature.editor.VectorStrokeDialog
import com.hereliesaz.graffitixr.feature.editor.toModelBlendMode
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
    val settingsVm: SettingsViewModel = hiltViewModel()
    val uiState by vm.uiState.collectAsState()
    val strings = rememberAppStrings()
    val scope = rememberCoroutineScope()

    // Settings overlay visibility (opened from the rail's gear item).
    var showSettings by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val navController = rememberNavController()

    // Artboard size picker visibility (opened from the rail's "Size" item).
    var showDocDialog by remember { mutableStateOf(false) }

    // Blend-mode picker visibility (opened from the Design folder's "Blend" item).
    var showBlendDialog by remember { mutableStateOf(false) }

    // Vector stroke-width picker visibility (opened from the Design folder's "Stroke" item).
    var showStrokeDialog by remember { mutableStateOf(false) }

    // Vector corner-radius picker visibility (opened from the Design folder's "Corners" item).
    var showCornerDialog by remember { mutableStateOf(false) }

    // Vector shape-size picker visibility (opened from the Design folder's "Size" item).
    var showShapeSizeDialog by remember { mutableStateOf(false) }

    // Polygon sides picker visibility (opened from the Design folder's "Sides" item).
    var showSidesDialog by remember { mutableStateOf(false) }

    // Text layer being edited via the rail's "Edit Text" item. Adding a text layer opens the editor
    // automatically via uiState.autoEditTextLayerId; this covers re-editing an existing one.
    var manualEditTextId by remember { mutableStateOf<String?>(null) }

    // Canvas background-colour picker visibility (opened from the Project folder's "Background" item).
    var showBgDialog by remember { mutableStateOf(false) }

    // Open a shared image (two-app interop) as a layer once, after the ViewModel exists.
    LaunchedEffect(sharedImageUri) {
        sharedImageUri?.let { vm.onAddLayer(it) }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { vm.onAddLayer(it) } }

    // Opens any design file via the Storage Access Framework (*/* — PSD/AI/Procreate typically
    // report a generic MIME, so the format is sniffed from bytes on import).
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.onImportDocument(it) } }

    // Rail-item colour that stays legible against the current canvas background (mirrors GraffitiXR).
    val navItemColor = remember(uiState.canvasBackground) {
        val bg = uiState.canvasBackground
        val luminance = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
        if (luminance > 0.5f) Color.Black else Color.White
    }

    AzHostActivityLayout(navController = navController, initiallyExpanded = false) {
        azTheme(
            activeColor = Cyan,
            // Circles for the short top-level buttons; nested-rail items override to NONE (text pills).
            defaultShape = AzButtonShape.CIRCLE,
            headerIconShape = AzHeaderIconShape.CIRCLE,
            // A translucent dark fill — NOT fully transparent, or the NONE-shaped nested-rail pills
            // (File / Add / Align / …) render as invisible text-only boxes with no readable backing.
            translucentBackground = Color.Black.copy(alpha = 0.55f)
        )
        azConfig(
            // No always-open menu: the rail folds away when the app icon is tapped.
            noMenu = true,
            packButtons = true,
            dockingSide = if (uiState.isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT
        )

        ConfigureRailItems(
            vm = vm,
            uiState = uiState,
            strings = strings,
            navItemColor = navItemColor,
            onOpenImage = {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onOpenDocument = { documentPicker.launch(arrayOf("*/*")) },
            onDocumentSize = { showDocDialog = true },
            onBlendMode = { showBlendDialog = true },
            onStrokeWidth = { showStrokeDialog = true },
            onCornerRadius = { showCornerDialog = true },
            onShapeSize = { showShapeSizeDialog = true },
            onPolygonSides = { showSidesDialog = true },
            onEditText = { id -> manualEditTextId = id },
            onBackground = { showBgDialog = true },
            onSettings = { showSettings = true },
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

            if (showBlendDialog) {
                val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
                BlendModePicker(
                    current = activeLayer?.blendMode?.toModelBlendMode() ?: BlendMode.SrcOver,
                    onSelect = { mode ->
                        vm.setBlendMode(mode)
                        showBlendDialog = false
                    },
                    onDismiss = { showBlendDialog = false },
                )
            }

            if (showStrokeDialog) {
                val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
                VectorStrokeDialog(
                    currentWidth = activeLayer?.shapes?.firstOrNull()?.strokeWidth ?: 0f,
                    onApply = { w ->
                        vm.setVectorStrokeWidth(w)
                        showStrokeDialog = false
                    },
                    onDismiss = { showStrokeDialog = false },
                )
            }

            if (showCornerDialog) {
                val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
                val rect = activeLayer?.shapes?.firstOrNull { it.kind == ShapeKind.RECTANGLE }
                CornerRadiusDialog(
                    currentRadius = rect?.cornerRadius ?: 0f,
                    onApply = { r ->
                        vm.setVectorCornerRadius(r)
                        showCornerDialog = false
                    },
                    onDismiss = { showCornerDialog = false },
                )
            }

            if (showShapeSizeDialog) {
                val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
                val shape = activeLayer?.shapes?.firstOrNull()
                if (shape != null) {
                    ShapeSizeDialog(
                        currentWidth = shape.width,
                        currentHeight = shape.height,
                        isLine = shape.kind == ShapeKind.LINE,
                        onConfirm = { w, h ->
                            vm.setVectorSize(w, h)
                            showShapeSizeDialog = false
                        },
                        onDismiss = { showShapeSizeDialog = false },
                    )
                }
            }

            if (showSidesDialog) {
                val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
                val polygon = activeLayer?.shapes?.firstOrNull { it.kind == ShapeKind.POLYGON }
                if (polygon != null) {
                    PolygonSidesDialog(
                        currentSides = polygon.sides,
                        onApply = { n ->
                            vm.setPolygonSides(n)
                            showSidesDialog = false
                        },
                        onDismiss = { showSidesDialog = false },
                    )
                }
            }

            // Text editor — opens automatically after adding a text layer (autoEditTextLayerId), or
            // when re-editing one via the rail. Edits apply live (re-rasterized by the view model).
            val editTextId = uiState.autoEditTextLayerId ?: manualEditTextId
            if (editTextId != null) {
                val params = uiState.layers.find { it.id == editTextId }?.textParams
                if (params != null) {
                    TextEditDialog(
                        initialText = params.text,
                        initialSizeDp = params.fontSizeDp,
                        initialColorArgb = params.colorArgb,
                        initialBold = params.isBold,
                        initialItalic = params.isItalic,
                        onTextChange = { vm.onTextContentChanged(editTextId, it) },
                        onSizeChange = { vm.onTextSizeChanged(editTextId, it) },
                        onColorChange = { vm.onTextColorChanged(editTextId, it) },
                        onStyleChange = { b, i ->
                            vm.onTextStyleChanged(editTextId, b, i, params.hasOutline, params.hasDropShadow)
                        },
                        onDismiss = {
                            vm.consumeAutoEditTextLayer()
                            manualEditTextId = null
                        },
                    )
                }
            }

            if (showBgDialog) {
                BackgroundColorDialog(
                    current = uiState.canvasBackground,
                    onSelect = { vm.setCanvasBackground(it) },
                    onDismiss = { showBgDialog = false },
                )
            }

            if (showSettings) {
                SettingsScreen(
                    vm = settingsVm,
                    appVersion = BuildConfig.VERSION_NAME,
                    onClose = { showSettings = false },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * GraffitiXR's "brilliant" two-axis brush control, rebuilt for Graffux. A single drag tunes both the
 * brush's size and its edge hardness: a mostly-vertical drag changes size (up = bigger), a mostly-
 * horizontal drag changes feathering (right = softer). The item paints a live preview — a solid core
 * that shrinks as the tip softens, wrapped in a translucent halo once there's any feathering — so the
 * exact tip is visible while dragging. Renders as custom rail content (which isn't clipped to rail
 * width, so the preview can grow to fill the button).
 *
 * NOTE: for an active azphalt extension (stamp) brush the horizontal axis should tune *flow* rather
 * than hardness — wired once extension-brush selection lands in the editor.
 */
@Composable
private fun BrushSizePad(vm: EditorViewModel) {
    val state by vm.uiState.collectAsState()
    val density = LocalDensity.current
    // Full item width (the fixed GraffitiXR behaviour: the preview may grow to fill the whole button,
    // not just half). Guarded below so a transient 0 during a layout pass can't invert a coerce range.
    var itemPx by remember { mutableFloatStateOf(120f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { itemPx = it.width.toFloat() }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val maxPx = maxOf(itemPx, 1f)
                    // Both axes each frame so a diagonal drag tunes size AND hardness together (no
                    // per-frame axis lock that would jitter between the two on a diagonal).
                    if (drag.y != 0f) {
                        // Vertical → size. Up (negative dy) grows the tip.
                        val cur = vm.uiState.value.brushSize
                        vm.setBrushSize((cur - drag.y * 0.5f).coerceIn(1f, maxPx))
                    }
                    if (drag.x != 0f) {
                        // Horizontal → hardness for the built-in brush, or FLOW for an azphalt stamp
                        // brush (right increases both). Same feel, different parameter.
                        if (vm.uiState.value.activeBrushName != null) {
                            val cur = vm.uiState.value.brushFlow
                            vm.setBrushFlow((cur + drag.x * 0.005f).coerceIn(0f, 1f))
                        } else {
                            val cur = vm.uiState.value.brushFeathering
                            vm.setBrushFeathering((cur + drag.x * 0.005f).coerceIn(0f, 1f))
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val maxPx = maxOf(itemPx, 1f)
        val isStamp = state.activeBrushName != null
        val feather = state.brushFeathering
        // Built-in brush: a hard core that shrinks (and gains a soft halo) as feathering rises.
        // Stamp brush: a solid core whose opacity is the flow value.
        val haloDp = with(density) { state.brushSize.coerceIn(1f, maxPx).toDp() }
        val coreDp = with(density) {
            (if (isStamp) state.brushSize else state.brushSize * (1f - feather * 0.7f))
                .coerceIn(2f, maxPx).toDp()
        }
        if (!isStamp && feather > 0.05f) {
            Box(Modifier.size(haloDp).background(Cyan.copy(alpha = 0.3f), CircleShape))
        }
        val coreAlpha = if (isStamp) state.brushFlow.coerceIn(0.08f, 1f) else 1f
        Box(Modifier.size(coreDp).background(Cyan.copy(alpha = coreAlpha), CircleShape))
    }
}

/**
 * Declares Graffux's AzNavRail items as inline host groups (the DESIGN-only subset of GraffitiXR's
 * rail — no AR / Overlay / Mockup / Trace modes, co-op, or project library). Collapsed, the rail is a
 * short column of icon circles (`azRailHostItem`); expanding one reveals its actions as inline text
 * pills (`azRailSubItem`). **Design** (the brush toolset) is open by default. Reset-view and undo/redo
 * are not here — they're floating controls in the canvas's bottom corners (EditorScreen.ViewportControls).
 *
 * There is deliberately no "Move" tool: transforming/selecting a layer is what the canvas does when no
 * brush tool is active, so a dedicated button was redundant.
 */
private fun AzNavHostScope.ConfigureRailItems(
    vm: EditorViewModel,
    uiState: EditorUiState,
    strings: AppStrings,
    navItemColor: Color,
    onOpenImage: () -> Unit,
    onOpenDocument: () -> Unit,
    onShare: () -> Unit,
    onDocumentSize: () -> Unit,
    onBlendMode: () -> Unit,
    onStrokeWidth: () -> Unit,
    onCornerRadius: () -> Unit,
    onShapeSize: () -> Unit,
    onPolygonSides: () -> Unit,
    onEditText: (String) -> Unit,
    onBackground: () -> Unit,
    onSettings: () -> Unit,
) {
    val navStrings = strings.nav

    // ── Design (brush toolset) — the always-open default group ────────────────────────────────────
    azRailHostItem(
        id = "grp.design", text = "Design", content = Icons.Filled.Brush, color = navItemColor,
        initiallyExpanded = true,
    )
    azRailSubItem(
        // Label reflects the selected brush (an azphalt stamp brush's name, or "Brush" for the round one).
        id = "tool.brush", hostId = "grp.design", text = uiState.activeBrushName ?: navStrings.brush,
        shape = AzButtonShape.NONE,
        color = if (uiState.activeTool == Tool.BRUSH) Cyan else navItemColor,
        // Toggle: tapping the active brush returns to no-tool (selection/transform) — Move's old job.
        onClick = { vm.setActiveTool(if (uiState.activeTool == Tool.BRUSH) Tool.NONE else Tool.BRUSH) },
    )
    // The size/hardness pad — GraffitiXR's two-axis brush control. Drag ↕ to resize, ↔ to soften/harden;
    // the live preview shows the exact tip. Custom content isn't clipped to rail width.
    azRailSubItem(
        id = "tool.size", hostId = "grp.design", text = "Size", shape = AzButtonShape.NONE,
        content = AzComposableContent { BrushSizePad(vm) },
    )
    azRailSubItem(
        id = "tool.pen", hostId = "grp.design", text = "Pen", shape = AzButtonShape.NONE,
        color = if (uiState.activeTool == Tool.PEN) Cyan else navItemColor,
        onClick = { vm.setActiveTool(if (uiState.activeTool == Tool.PEN) Tool.NONE else Tool.PEN) },
    )
    azRailSubItem(
        id = "tool.color", hostId = "grp.design", text = navStrings.color, shape = AzButtonShape.NONE,
        color = if (uiState.showColorPicker) Cyan else navItemColor,
        onClick = { vm.onColorClicked() },
    )
    azRailSubItem(
        id = "panel.layers", hostId = "grp.design", text = strings.editor.layers, shape = AzButtonShape.NONE,
        color = if (uiState.activePanel == EditorPanel.LAYERS) Cyan else navItemColor,
        onClick = { vm.onLayersClicked() },
    )
    // Installed azphalt stamp brushes (if any) + the built-in "Round". Absent until a brush .azp is
    // installed, so this adds nothing to the rail today. Selecting one switches the Size pad to size/flow.
    val brushes = vm.installedBrushes()
    if (brushes.isNotEmpty()) {
        azRailSubItem(
            id = "brush.round", hostId = "grp.design", text = "Round", shape = AzButtonShape.NONE,
            color = if (uiState.activeBrushName == null) Cyan else navItemColor,
            onClick = { vm.selectBrushExtension(null) },
        )
        brushes.forEach { (id, name) ->
            azRailSubItem(
                id = "brush.$id", hostId = "grp.design", text = name, shape = AzButtonShape.NONE,
                color = if (uiState.activeBrushName == name) Cyan else navItemColor,
                onClick = { vm.selectBrushExtension(id) },
            )
        }
    }

    // ── Project (file actions) ────────────────────────────────────────────────────────────────────
    azRailHostItem(id = "grp.project", text = "Project", content = Icons.Filled.FolderOpen, color = navItemColor)
    azRailSubItem(id = "file.open", hostId = "grp.project", text = navStrings.open, shape = AzButtonShape.NONE, onClick = { onOpenImage() })
    azRailSubItem(id = "file.openfile", hostId = "grp.project", text = "Open File", shape = AzButtonShape.NONE, onClick = { onOpenDocument() })
    azRailSubItem(id = "file.new", hostId = "grp.project", text = navStrings.new, shape = AzButtonShape.NONE, onClick = { vm.onAddBlankLayer() })
    azRailSubItem(id = "file.size", hostId = "grp.project", text = "${uiState.documentWidth}×${uiState.documentHeight}", shape = AzButtonShape.NONE, onClick = { onDocumentSize() })
    azRailSubItem(id = "file.background", hostId = "grp.project", text = "Background", shape = AzButtonShape.NONE, onClick = { onBackground() })
    azRailSubItem(id = "file.save", hostId = "grp.project", text = navStrings.save, shape = AzButtonShape.NONE, onClick = { vm.saveProject() })
    azRailSubItem(id = "file.export", hostId = "grp.project", text = navStrings.export, shape = AzButtonShape.NONE, onClick = { vm.exportImage() })
    azRailSubItem(id = "file.share", hostId = "grp.project", text = navStrings.share, shape = AzButtonShape.NONE, onClick = { onShare() })

    // ── Add (new text + vector-shape layers) ──────────────────────────────────────────────────────
    azRailHostItem(id = "grp.add", text = "Add", content = Icons.Filled.Add, color = navItemColor)
    azRailSubItem(id = "add.text", hostId = "grp.add", text = "Text", shape = AzButtonShape.NONE, onClick = { vm.onAddTextLayer() })
    azRailSubItem(id = "add.rect", hostId = "grp.add", text = "Rectangle", shape = AzButtonShape.NONE, onClick = { vm.onAddShapeLayer(ShapeKind.RECTANGLE) })
    azRailSubItem(id = "add.ellipse", hostId = "grp.add", text = "Ellipse", shape = AzButtonShape.NONE, onClick = { vm.onAddShapeLayer(ShapeKind.ELLIPSE) })
    azRailSubItem(id = "add.line", hostId = "grp.add", text = "Line", shape = AzButtonShape.NONE, onClick = { vm.onAddShapeLayer(ShapeKind.LINE) })
    azRailSubItem(id = "add.triangle", hostId = "grp.add", text = "Triangle", shape = AzButtonShape.NONE, onClick = { vm.onAddPolygonLayer(3) })
    azRailSubItem(id = "add.pentagon", hostId = "grp.add", text = "Pentagon", shape = AzButtonShape.NONE, onClick = { vm.onAddPolygonLayer(5) })
    azRailSubItem(id = "add.hexagon", hostId = "grp.add", text = "Hexagon", shape = AzButtonShape.NONE, onClick = { vm.onAddPolygonLayer(6) })

    // ── Align (active layer → artboard) ───────────────────────────────────────────────────────────
    azRailHostItem(id = "grp.align", text = "Align", content = Icons.Filled.FormatAlignCenter, color = navItemColor)
    azRailSubItem(id = "align.left", hostId = "grp.align", text = "Left", shape = AzButtonShape.NONE, onClick = { vm.alignActiveLayer(AlignMode.LEFT) })
    azRailSubItem(id = "align.hcenter", hostId = "grp.align", text = "Center", shape = AzButtonShape.NONE, onClick = { vm.alignActiveLayer(AlignMode.H_CENTER) })
    azRailSubItem(id = "align.right", hostId = "grp.align", text = "Right", shape = AzButtonShape.NONE, onClick = { vm.alignActiveLayer(AlignMode.RIGHT) })
    azRailSubItem(id = "align.top", hostId = "grp.align", text = "Top", shape = AzButtonShape.NONE, onClick = { vm.alignActiveLayer(AlignMode.TOP) })
    azRailSubItem(id = "align.vcenter", hostId = "grp.align", text = "Middle", shape = AzButtonShape.NONE, onClick = { vm.alignActiveLayer(AlignMode.V_CENTER) })
    azRailSubItem(id = "align.bottom", hostId = "grp.align", text = "Bottom", shape = AzButtonShape.NONE, onClick = { vm.alignActiveLayer(AlignMode.BOTTOM) })

    // ── Adjust ────────────────────────────────────────────────────────────────────────────────────
    azRailHostItem(id = "grp.adjust", text = navStrings.adjust, content = Icons.Filled.Tune, color = navItemColor)
    azRailSubItem(
        id = "adj.adjust", hostId = "grp.adjust", text = navStrings.adjust, shape = AzButtonShape.NONE,
        color = if (uiState.activePanel == EditorPanel.ADJUST) Cyan else navItemColor, onClick = { vm.onAdjustClicked() },
    )
    azRailSubItem(
        id = "adj.transform", hostId = "grp.adjust", text = "Transform", shape = AzButtonShape.NONE,
        color = if (uiState.activePanel == EditorPanel.TRANSFORM) Cyan else navItemColor, onClick = { vm.onTransformClicked() },
    )
    azRailSubItem(id = "adj.blend", hostId = "grp.adjust", text = "Blend", shape = AzButtonShape.NONE, onClick = { onBlendMode() })

    // ── Edit (context actions on the active layer) ────────────────────────────────────────────────
    val overlay = uiState.layers.find { it.id == uiState.activeLayerId }
    if (overlay != null) {
        azRailHostItem(id = "grp.edit", text = "Edit", content = Icons.Filled.Edit, color = navItemColor)
        if (overlay.textParams != null) {
            azRailSubItem(id = "edit.text", hostId = "grp.edit", text = "Edit Text", shape = AzButtonShape.NONE, onClick = { onEditText(overlay.id) })
        }
        azRailSubItem(id = "edit.outline", hostId = "grp.edit", text = navStrings.outline, shape = AzButtonShape.NONE, onClick = { vm.onSketchClicked() })
        azRailSubItem(id = "edit.edges", hostId = "grp.edit", text = navStrings.edges, shape = AzButtonShape.NONE, onClick = { vm.onApplyCannyEdgeClicked() })
        azRailSubItem(id = "edit.invert", hostId = "grp.edit", text = navStrings.invert, shape = AzButtonShape.NONE, onClick = { vm.onToggleInvert() })
        if (overlay.shapes.isNotEmpty()) {
            azRailSubItem(id = "edit.size", hostId = "grp.edit", text = "Size", shape = AzButtonShape.NONE, onClick = { onShapeSize() })
            azRailSubItem(id = "edit.stroke", hostId = "grp.edit", text = "Stroke", shape = AzButtonShape.NONE, onClick = { onStrokeWidth() })
        }
        if (overlay.shapes.any { it.kind == ShapeKind.RECTANGLE }) {
            azRailSubItem(id = "edit.corners", hostId = "grp.edit", text = "Corners", shape = AzButtonShape.NONE, onClick = { onCornerRadius() })
        }
        if (overlay.shapes.any { it.kind == ShapeKind.POLYGON }) {
            azRailSubItem(id = "edit.sides", hostId = "grp.edit", text = "Sides", shape = AzButtonShape.NONE, onClick = { onPolygonSides() })
        }
        if (overlay.shapes.any { it.kind != ShapeKind.LINE }) {
            azRailSubItem(
                id = "edit.fill", hostId = "grp.edit", text = "Fill", shape = AzButtonShape.NONE,
                color = if (overlay.shapes.any { it.hasFill }) Cyan else navItemColor, onClick = { vm.toggleVectorFill() },
            )
        }
    }

    // Settings — a standalone gear that opens the settings overlay.
    azRailItem(id = "settings", text = "Settings", content = Icons.Filled.Settings, color = navItemColor) { onSettings() }
}
