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
    val uiState by vm.uiState.collectAsState()
    val railExpansion by vm.railExpansion.collectAsState()
    val strings = rememberAppStrings()
    val scope = rememberCoroutineScope()
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
            railExpansion = railExpansion,
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
                        // Horizontal → feathering (hardness). Right softens.
                        val cur = vm.uiState.value.brushFeathering
                        vm.setBrushFeathering((cur + drag.x * 0.005f).coerceIn(0f, 1f))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val maxPx = maxOf(itemPx, 1f)
        val feather = state.brushFeathering
        // Halo = the full brush diameter; core = the hard centre, shrinking as feathering rises.
        val haloDp = with(density) { state.brushSize.coerceIn(1f, maxPx).toDp() }
        val coreDp = with(density) { (state.brushSize * (1f - feather * 0.7f)).coerceIn(2f, maxPx).toDp() }
        if (feather > 0.05f) {
            Box(Modifier.size(haloDp).background(Cyan.copy(alpha = 0.3f), CircleShape))
        }
        Box(Modifier.size(coreDp).background(Cyan, CircleShape))
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
) {
    val navStrings = strings.nav

    // Primary tools — always-visible circular rail buttons. Tapping the app icon folds the rail away.
    azRailItem(
        id = "tool.move",
        text = navStrings.move,
        color = if (uiState.activeTool == Tool.NONE) Cyan else navItemColor,
    ) { vm.setActiveTool(Tool.NONE) }
    azRailItem(
        id = "tool.brush",
        text = navStrings.brush,
        color = if (uiState.activeTool == Tool.BRUSH) Cyan else navItemColor,
    ) { vm.setActiveTool(Tool.BRUSH) }
    // The size/hardness pad — GraffitiXR's two-axis brush control, restored. Drag ↕ to resize, ↔ to
    // soften/harden; the live preview shows the exact tip. Custom content isn't clipped to rail width.
    azRailItem(
        id = "tool.size",
        text = "Size",
        content = AzComposableContent { BrushSizePad(vm) },
    )
    azRailItem(
        id = "tool.pen",
        text = "Pen",
        color = if (uiState.activeTool == Tool.PEN) Cyan else navItemColor,
    ) { vm.setActiveTool(Tool.PEN) }
    azRailItem(
        id = "tool.color",
        text = navStrings.color,
        color = if (uiState.showColorPicker) Cyan else navItemColor,
    ) { vm.onColorClicked() }
    azRailItem(
        id = "panel.layers",
        text = strings.editor.layers,
        color = if (uiState.activePanel == EditorPanel.LAYERS) Cyan else navItemColor,
    ) { vm.onLayersClicked() }

    // File — a nested-rail popup (open / new / document size / background / save / export / share).
    azNestedRail(id = "grp.file", text = "File", color = navItemColor) {
        azRailItem(id = "file.open", text = navStrings.open, shape = AzButtonShape.NONE) { onOpenImage() }
        azRailItem(id = "file.openfile", text = "Open File", shape = AzButtonShape.NONE) { onOpenDocument() }
        azRailItem(id = "file.new", text = navStrings.new, shape = AzButtonShape.NONE) { vm.onAddBlankLayer() }
        azRailItem(id = "file.size", text = "${uiState.documentWidth}×${uiState.documentHeight}", shape = AzButtonShape.NONE) { onDocumentSize() }
        azRailItem(id = "file.background", text = "Background", shape = AzButtonShape.NONE) { onBackground() }
        azRailItem(id = "file.save", text = navStrings.save, shape = AzButtonShape.NONE) { vm.saveProject() }
        azRailItem(id = "file.export", text = navStrings.export, shape = AzButtonShape.NONE) { vm.exportImage() }
        azRailItem(id = "file.share", text = navStrings.share, shape = AzButtonShape.NONE) { onShare() }
    }

    // Add — nested-rail popup of text + vector shapes (each adds a new layer).
    azNestedRail(id = "grp.add", text = "Add", color = navItemColor) {
        azRailItem(id = "add.text", text = "Text", shape = AzButtonShape.NONE) { vm.onAddTextLayer() }
        azRailItem(id = "add.rect", text = "Rectangle", shape = AzButtonShape.NONE) { vm.onAddShapeLayer(ShapeKind.RECTANGLE) }
        azRailItem(id = "add.ellipse", text = "Ellipse", shape = AzButtonShape.NONE) { vm.onAddShapeLayer(ShapeKind.ELLIPSE) }
        azRailItem(id = "add.line", text = "Line", shape = AzButtonShape.NONE) { vm.onAddShapeLayer(ShapeKind.LINE) }
        azRailItem(id = "add.triangle", text = "Triangle", shape = AzButtonShape.NONE) { vm.onAddPolygonLayer(3) }
        azRailItem(id = "add.pentagon", text = "Pentagon", shape = AzButtonShape.NONE) { vm.onAddPolygonLayer(5) }
        azRailItem(id = "add.hexagon", text = "Hexagon", shape = AzButtonShape.NONE) { vm.onAddPolygonLayer(6) }
    }

    // Align the active layer to the artboard — nested-rail popup.
    azNestedRail(id = "grp.align", text = "Align", color = navItemColor) {
        azRailItem(id = "align.left", text = "Left", shape = AzButtonShape.NONE) { vm.alignActiveLayer(AlignMode.LEFT) }
        azRailItem(id = "align.hcenter", text = "Center", shape = AzButtonShape.NONE) { vm.alignActiveLayer(AlignMode.H_CENTER) }
        azRailItem(id = "align.right", text = "Right", shape = AzButtonShape.NONE) { vm.alignActiveLayer(AlignMode.RIGHT) }
        azRailItem(id = "align.top", text = "Top", shape = AzButtonShape.NONE) { vm.alignActiveLayer(AlignMode.TOP) }
        azRailItem(id = "align.vcenter", text = "Middle", shape = AzButtonShape.NONE) { vm.alignActiveLayer(AlignMode.V_CENTER) }
        azRailItem(id = "align.bottom", text = "Bottom", shape = AzButtonShape.NONE) { vm.alignActiveLayer(AlignMode.BOTTOM) }
    }
    // Adjust — nested-rail popup.
    azNestedRail(id = "grp.adjust", text = navStrings.adjust, color = navItemColor) {
        azRailItem(
            id = "adj.adjust", text = navStrings.adjust, shape = AzButtonShape.NONE,
            color = if (uiState.activePanel == EditorPanel.ADJUST) Cyan else navItemColor,
        ) { vm.onAdjustClicked() }
        azRailItem(
            id = "adj.transform", text = "Transform", shape = AzButtonShape.NONE,
            color = if (uiState.activePanel == EditorPanel.TRANSFORM) Cyan else navItemColor,
        ) { vm.onTransformClicked() }
        azRailItem(id = "adj.blend", text = "Blend", shape = AzButtonShape.NONE) { onBlendMode() }
    }

    // Edit — context actions on the active layer, nested-rail popup.
    val overlay = uiState.layers.find { it.id == uiState.activeLayerId }
    if (overlay != null) {
        azNestedRail(id = "grp.edit", text = "Edit", color = navItemColor) {
            if (overlay.textParams != null) {
                azRailItem(id = "edit.text", text = "Edit Text", shape = AzButtonShape.NONE) { onEditText(overlay.id) }
            }
            azRailItem(id = "edit.outline", text = navStrings.outline, shape = AzButtonShape.NONE) { vm.onSketchClicked() }
            azRailItem(id = "edit.edges", text = navStrings.edges, shape = AzButtonShape.NONE) { vm.onApplyCannyEdgeClicked() }
            azRailItem(id = "edit.invert", text = navStrings.invert, shape = AzButtonShape.NONE) { vm.onToggleInvert() }
            if (overlay.shapes.isNotEmpty()) {
                azRailItem(id = "edit.size", text = "Size", shape = AzButtonShape.NONE) { onShapeSize() }
                azRailItem(id = "edit.stroke", text = "Stroke", shape = AzButtonShape.NONE) { onStrokeWidth() }
            }
            if (overlay.shapes.any { it.kind == ShapeKind.RECTANGLE }) {
                azRailItem(id = "edit.corners", text = "Corners", shape = AzButtonShape.NONE) { onCornerRadius() }
            }
            if (overlay.shapes.any { it.kind == ShapeKind.POLYGON }) {
                azRailItem(id = "edit.sides", text = "Sides", shape = AzButtonShape.NONE) { onPolygonSides() }
            }
            if (overlay.shapes.any { it.kind != ShapeKind.LINE }) {
                azRailItem(
                    id = "edit.fill", text = "Fill", shape = AzButtonShape.NONE,
                    color = if (overlay.shapes.any { it.hasFill }) Cyan else navItemColor,
                ) { vm.toggleVectorFill() }
            }
        }
    }

    // Reset-view and undo/redo are no longer rail items — they live as floating controls in the
    // canvas's bottom corners (EditorScreen.ViewportControls): fit/reset bottom-left, undo/redo
    // bottom-right, matching GraffitiXR's placement.
}
