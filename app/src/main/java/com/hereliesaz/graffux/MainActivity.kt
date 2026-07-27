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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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
import com.hereliesaz.graffitixr.design.R as DesignR
import com.hereliesaz.graffitixr.design.theme.AppStrings
import com.hereliesaz.graffitixr.design.theme.Cyan
import com.hereliesaz.graffitixr.design.theme.rememberAppStrings
import com.hereliesaz.graffitixr.feature.editor.AddContentDialog
import com.hereliesaz.graffitixr.feature.editor.AlignDialog
import com.hereliesaz.graffitixr.feature.editor.AlignMode
import com.hereliesaz.graffitixr.feature.editor.BackgroundColorDialog
import com.hereliesaz.graffitixr.feature.editor.BlendModePicker
import com.hereliesaz.graffitixr.feature.editor.CornerRadiusDialog
import com.hereliesaz.graffitixr.feature.editor.DocumentSizeDialog
import com.hereliesaz.graffitixr.feature.editor.EditorScreen
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.feature.editor.GalleryWindow
import com.hereliesaz.graffitixr.feature.editor.LayerOptionsDialog
import com.hereliesaz.graffitixr.feature.editor.PolygonSidesDialog
import com.hereliesaz.graffitixr.feature.editor.ShapeSizeDialog
import com.hereliesaz.graffitixr.feature.editor.StoreWindow
import com.hereliesaz.graffitixr.feature.editor.TextEditDialog
import com.hereliesaz.graffitixr.feature.editor.VectorStrokeDialog
import com.hereliesaz.graffitixr.feature.editor.toModelBlendMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Graffux entry point — hosts the shared [EditorScreen] (the single source of truth for the
 * multi-layer image editor, migrated from GraffitiXR into :feature:editor). The Hilt-provided
 * [EditorViewModel] and its whole dependency graph (core modules + native bridge) resolve here; the
 * screen forces DESIGN mode, so no AR / SLAM / co-op is involved.[span_3](start_span)[span_3](end_span)
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
 * Extracts a single image [Uri] from an inbound share/view intent, or null if this launch isn't one.[span_4](start_span)[span_4](end_span)
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

/** Brush-size range the edge slider maps onto — matches EditorReducer's own clamp on SetBrushSize. */
private const val MIN_BRUSH_SIZE = 1f
private const val MAX_BRUSH_SIZE = 200f

/** Floor for brush opacity: a fully transparent brush paints nothing and just reads as a broken tool. */
private const val MIN_BRUSH_ALPHA = 0.05f

@Composable
private fun GraffuxApp(sharedImageUri: Uri?) {
    val vm: EditorViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val uiState by vm.uiState.collectAsState()
    val storeState by vm.storeState.collectAsState()
    val installedExtensionIds by vm.installedExtensionIds.collectAsState()
    val projects by vm.projects.collectAsState()
    val strings = rememberAppStrings()
    val scope = rememberCoroutineScope()

    var showSettings by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val navController = rememberNavController()

    var showDocDialog by remember { mutableStateOf(false) }
    var showBlendDialog by remember { mutableStateOf(false) }
    var showStrokeDialog by remember { mutableStateOf(false) }
    var showCornerDialog by remember { mutableStateOf(false) }
    var showShapeSizeDialog by remember { mutableStateOf(false) }
    var showSidesDialog by remember { mutableStateOf(false) }
    var manualEditTextId by remember { mutableStateOf<String?>(null) }
    var showBgDialog by remember { mutableStateOf(false) }
    // Procreate-style windows: things that used to be a wall of always-visible rail buttons now
    // live behind a single rail item that opens one of these instead.
    var showAddDialog by remember { mutableStateOf(false) }
    var showAlignDialog by remember { mutableStateOf(false) }
    var showLayerOptionsDialog by remember { mutableStateOf(false) }
    var showStoreDialog by remember { mutableStateOf(false) }
    var showGalleryDialog by remember { mutableStateOf(false) }

    // Pre-calculate `@Composable` colors outside the non-composable DSL block
    val activeRailColor = MaterialTheme.colorScheme.onSurface
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    // The QuickMenu opens where its gesture landed; from the rail there is no finger, so it opens
    // at the middle of the screen — the canvas is full-bleed, so that is the middle of the artwork.
    val screenConfig = LocalConfiguration.current
    val screenCenter = with(LocalDensity.current) {
        Offset(screenConfig.screenWidthDp.dp.toPx() / 2f, screenConfig.screenHeightDp.dp.toPx() / 2f)
    }

    LaunchedEffect(sharedImageUri) {
        sharedImageUri?.let { vm.onAddLayer(it) }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { vm.onAddLayer(it) } }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.onImportDocument(it) } }

    val brushPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.installExtensionFromUri(it) } }

    val brushes by vm.installedBrushes.collectAsState()

    val navItemColor = remember(uiState.canvasBackground) {
        val bg = uiState.canvasBackground
        val luminance = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
        if (luminance > 0.5f) Color.Black else Color.White
    }

    AzHostActivityLayout(navController = navController, initiallyExpanded = false) {
        azTheme(
            activeColor = activeRailColor, // Passed dynamically to avoid `@Composable` invocation errors
            defaultShape = AzButtonShape.CIRCLE,
            headerIconShape = AzHeaderIconShape.CIRCLE,
            translucentBackground = Color.Black.copy(alpha = 0.55f)
        )
        azConfig(
            noMenu = true,
            packButtons = true,
            dockingSide = if (uiState.isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT,
            railItemWidth = 44.dp
        )
        // Four-finger tap (see EditorScreen's multiFingerTaps): full-screen art. Folding the rail
        // is the AzNavRail half of "hide the UI"; the onscreen chrome below gates on the same flag.
        isFoldedUp = uiState.hideUiForCapture

        ConfigureRailItems(
            vm = vm,
            uiState = uiState,
            brushes = brushes,
            strings = strings,
            navItemColor = navItemColor,
            activeColor = activeRailColor, // Pass down to DSL builder
            screenCenter = screenCenter,
            onBlendMode = { showBlendDialog = true },
            onAddClicked = { showAddDialog = true },
            onAlignClicked = { showAlignDialog = true },
            onEditClicked = { showLayerOptionsDialog = true },
        )

        background(weight = 0) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                EditorScreen(vm = vm, modifier = Modifier.fillMaxSize())
            }
        }
        
        // Standalone Top-Right File Operations Dropdown (hidden in full-screen art mode)[span_5](start_span)[span_5](end_span)
        onscreen(alignment = Alignment.TopEnd) {
            if (!uiState.hideUiForCapture) AzDropdownMenu(navController = navController) {
                azConfig(design = AzDropdownDesign.MENU, dockingSide = if (uiState.isRightHanded) AzDockingSide.RIGHT else AzDockingSide.LEFT)
                // New/Open/Import/Save/Export map onto distinct actions rather than overloading one
                // another: New starts a blank project; Open browses the saved ones (the Gallery IS
                // that browser, so it lives under this single entry rather than a separate duplicate);
                // Import brings an image or another design file INTO the current project, which is
                // what the old "Open"/"Open File" pair actually did despite their names.
                azItem(text = strings.nav.new, onClick = { vm.createNewProject() })
                azItem(text = strings.nav.open, onClick = { showGalleryDialog = true })
                azItem(text = "Import Image…", onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                azItem(text = "Import File…", onClick = { documentPicker.launch(arrayOf("*/*")) })
                azItem(text = "Add…", onClick = { showAddDialog = true })
                azItem(text = "Align…", onClick = { showAlignDialog = true })
                azItem(text = "${uiState.documentWidth}×${uiState.documentHeight}", onClick = { showDocDialog = true })
                azItem(text = "Background", onClick = { showBgDialog = true })
                // onFlattenAllLayers() was fully implemented (rasterizes every layer to one, undo-safe)
                // but had no menu entry anywhere — see LayerOptionsDialog's "Merge Down" for the
                // per-layer equivalent this complements at the whole-project level.
                azItem(text = "Flatten", onClick = { vm.onFlattenAllLayers() })
                // saveProject(name) both persists (like every autosave elsewhere) AND exports a
                // portable .gxr copy to Downloads when name is non-null — passing the project's own
                // current name does both at once without renaming it, i.e. Save and Save As in one tap.
                azItem(text = strings.nav.save, onClick = { vm.saveProject(name = projects.find { it.id == uiState.projectId }?.name) })
                azItem(text = strings.nav.export, onClick = { vm.exportImage() })
                azItem(text = strings.nav.share, onClick = {
                    scope.launch {
                        try {
                            val uri = vm.exportForShare() ?: return@launch
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = android.content.ClipData.newRawUri(null, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            val chooser = Intent.createChooser(send, null).apply {
                                clipData = send.clipData
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(chooser)
                        } catch (t: Throwable) {
                            android.util.Log.w("Graffux", "Share failed", t)
                        }
                    }
                })
                azDivider()
                azItem(text = "Store", onClick = { vm.openStore(); showStoreDialog = true })
                azItem(text = "Install brush…", onClick = { brushPicker.launch(arrayOf("*/*")) })
                azItem(text = "Settings", onClick = { showSettings = true })
            }
        }

        // Onscreen Foreground Elements explicitly pinned over the canvas. Hidden while a bottom panel
        // is up: Transform and the adjustment knobs occupy this same strip, and the buttons were
        // landing on top of their fields.
        onscreen(alignment = Alignment.BottomCenter) {
            if (uiState.activePanel == EditorPanel.NONE && !uiState.hideUiForCapture) Row(
                modifier = Modifier.navigationBarsPadding().padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val viewMoved = uiState.viewportZoom != 1f ||
                    uiState.viewportOffset != Offset.Zero ||
                    uiState.viewportRotation != 0f
                if (viewMoved) {
                    FloatingActionButton(onClick = { vm.resetViewport() }, containerColor = surfaceVariantColor) {
                        Icon(painterResource(DesignR.drawable.ic_ps_fit), contentDescription = "Fit to screen")
                    }
                }
                if (uiState.undoCount > 0) {
                    FloatingActionButton(onClick = { vm.onUndoClicked() }, containerColor = surfaceVariantColor) {
                        Icon(painterResource(DesignR.drawable.ic_ps_undo), contentDescription = "Undo")
                    }
                }
                if (uiState.redoCount > 0) {
                    FloatingActionButton(onClick = { vm.onRedoClicked() }, containerColor = surfaceVariantColor) {
                        Icon(painterResource(DesignR.drawable.ic_ps_redo), contentDescription = "Redo")
                    }
                }
            }
        }

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

            val editTextId = uiState.autoEditTextLayerId ?: manualEditTextId
            if (editTextId != null) {
                val params = uiState.layers.find { it.id == editTextId }?.textParams
                if (params != null) {
                    // key() on the layer id: without it, if editTextId flips to a different layer
                    // while this composable stays mounted (e.g. autoEditTextLayerId fires for a new
                    // layer while the dialog is already open for a previous one), TextEditDialog's
                    // internal `remember`s keep the OLD layer's text/size/color/style — so the dialog
                    // shows stale values and further edits get sent out tagged with the new id but
                    // carrying the old id's field values. key() forces a fresh composable instance
                    // (and fresh `remember`s) whenever the id changes, while recomposition for the
                    // SAME id (e.g. every keystroke) still preserves in-progress local state.
                    key(editTextId) {
                        TextEditDialog(
                            initialText = params.text,
                            initialFontName = params.fontName,
                            initialSizeDp = params.fontSizeDp,
                            initialKerningEm = params.letterSpacingEm,
                            initialColorArgb = params.colorArgb,
                            initialBold = params.isBold,
                            initialItalic = params.isItalic,
                            initialHasOutline = params.hasOutline,
                            initialHasDropShadow = params.hasDropShadow,
                            onTextChange = { vm.onTextContentChanged(editTextId, it) },
                            onFontChange = { vm.onTextFontChanged(editTextId, it) },
                            onSizeChange = { vm.onTextSizeChanged(editTextId, it) },
                            onSizeStart = { vm.onLayerEditStart() },
                            onSizeCommit = { vm.onLayerEditEnd() },
                            onKerningChange = { vm.onTextKerningChanged(editTextId, it) },
                            onKerningStart = { vm.onLayerEditStart() },
                            onKerningCommit = { vm.onLayerEditEnd() },
                            onColorChange = { vm.onTextColorChanged(editTextId, it) },
                            onStyleChange = { b, i, o, s ->
                                vm.onTextStyleChanged(editTextId, b, i, o, s)
                            },
                            onDismiss = {
                                vm.consumeAutoEditTextLayer()
                                manualEditTextId = null
                            },
                        )
                    }
                }
            }

            if (showBgDialog) {
                BackgroundColorDialog(
                    current = uiState.canvasBackground,
                    onSelect = { vm.setCanvasBackground(it) },
                    onDismiss = { showBgDialog = false },
                )
            }

            if (showAddDialog) {
                AddContentDialog(
                    onAddText = { vm.onAddTextLayer() },
                    onAddRectangle = { vm.onAddShapeLayer(ShapeKind.RECTANGLE) },
                    onAddEllipse = { vm.onAddShapeLayer(ShapeKind.ELLIPSE) },
                    onAddLine = { vm.onAddShapeLayer(ShapeKind.LINE) },
                    onAddTriangle = { vm.onAddPolygonLayer(3) },
                    onAddPentagon = { vm.onAddPolygonLayer(5) },
                    onAddHexagon = { vm.onAddPolygonLayer(6) },
                    onDismiss = { showAddDialog = false },
                )
            }

            if (showAlignDialog) {
                AlignDialog(
                    onAlign = { mode -> vm.alignActiveLayer(mode) },
                    onDismiss = { showAlignDialog = false },
                )
            }

            if (showLayerOptionsDialog) {
                val overlay = uiState.layers.find { it.id == uiState.activeLayerId }
                if (overlay != null) {
                    LayerOptionsDialog(
                        overlay = overlay,
                        navStrings = strings.nav,
                        onEditText = { manualEditTextId = overlay.id },
                        onInvert = { vm.onToggleInvert() },
                        onShapeSize = { showShapeSizeDialog = true },
                        onStrokeWidth = { showStrokeDialog = true },
                        onCornerRadius = { showCornerDialog = true },
                        onPolygonSides = { showSidesDialog = true },
                        onToggleFill = { vm.toggleVectorFill() },
                        onOpacityChange = { vm.onOpacityChanged(it) },
                        onOpacityStart = { vm.onLayerEditStart() },
                        onOpacityCommit = { vm.onLayerEditEnd() },
                        onBlendMode = { showBlendDialog = true },
                        onToggleAlphaLock = { vm.onToggleAlphaLock(overlay.id) },
                        onDismiss = { showLayerOptionsDialog = false },
                    )
                }
            }

            if (showGalleryDialog) {
                GalleryWindow(
                    projects = projects,
                    currentProjectId = uiState.projectId,
                    onOpen = { vm.openProject(it) },
                    onNew = { vm.createNewProject() },
                    onDelete = { vm.deleteProjectById(it) },
                    onDismiss = { showGalleryDialog = false },
                )
            }

            if (showStoreDialog) {
                StoreWindow(
                    state = storeState,
                    installedIds = installedExtensionIds,
                    onSearch = { vm.searchStore(it) },
                    onInstall = { vm.installFromStore(it) },
                    onUninstall = { vm.uninstallStoreExtension(it) },
                    onDismiss = { showStoreDialog = false },
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

@Composable
private fun BrushSizePad(vm: EditorViewModel) {
    val state by vm.uiState.collectAsState()
    val density = LocalDensity.current
    var itemPx by remember { mutableFloatStateOf(120f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { itemPx = it.width.toFloat() }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val maxPx = maxOf(itemPx, 1f)
                    if (drag.y != 0f) {
                        val cur = vm.uiState.value.brushSize
                        vm.setBrushSize((cur - drag.y * 0.5f).coerceIn(1f, maxPx))
                    }
                    if (drag.x != 0f) {
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
 * ConfigureRailItems builder block.
 *
 * Procreate-shaped: the rail is only the tools you reach for mid-stroke — brush, smudge, eraser,
 * pen, colour, layers — each with its own glyph. Brush size and opacity live on the edge sliders
 * beside the rail; Adjust/Transform/Blend float as a draggable palette; and everything
 * document-level (open/new/add/align/save/export/share/background/store/settings) is in the
 * drop-down, Procreate's Actions menu — see the `AzDropdownMenu` block in [GraffuxApp].
 */
private fun AzNavHostScope.ConfigureRailItems(
    vm: EditorViewModel,
    uiState: EditorUiState,
    brushes: List<Pair<String, String>>,
    strings: AppStrings,
    navItemColor: Color,
    activeColor: Color,
    // Screen centre in px, resolved by the caller: this builder is not @Composable, so it can't
    // read LocalConfiguration/LocalDensity itself (same reason the colours above are passed in).
    screenCenter: Offset,
    onBlendMode: () -> Unit,
    onAddClicked: () -> Unit,
    onAlignClicked: () -> Unit,
    onEditClicked: () -> Unit,
) {
    val navStrings = strings.nav

    // Procreate's tool strip: a flat row of the tools you paint with, not an accordion you have to
    // open first, drawn with the app's own Photoshop-style vector set (core/design's ic_ps_*) rather
    // than stock Material glyphs — a broom stood in for the eraser and a squiggle for smudge.
    // Every item gets its own glyph — the old rail drew Icons.Filled.Brush for the group,
    // the brush tool, the round brush AND every installed brush, and the same pencil for Pen and Edit,
    // so nothing in it was identifiable at a glance.
    azRailItem(
        id = "tool.brush", text = uiState.activeBrushName ?: navStrings.brush,
        content = DesignR.drawable.ic_ps_brush,
        color = if (uiState.activeTool == Tool.BRUSH) activeColor else navItemColor,
        onClick = { vm.setActiveTool(if (uiState.activeTool == Tool.BRUSH) Tool.NONE else Tool.BRUSH) },
    )
    azRailItem(
        id = "tool.smudge", text = "Smudge",
        content = DesignR.drawable.ic_ps_blur,
        color = if (uiState.activeTool == Tool.BLUR) activeColor else navItemColor,
        onClick = { vm.setActiveTool(if (uiState.activeTool == Tool.BLUR) Tool.NONE else Tool.BLUR) },
    )
    azRailItem(
        id = "tool.eraser", text = "Eraser",
        content = DesignR.drawable.ic_ps_eraser,
        color = if (uiState.activeTool == Tool.ERASER) activeColor else navItemColor,
        onClick = { vm.setActiveTool(if (uiState.activeTool == Tool.ERASER) Tool.NONE else Tool.ERASER) },
    )
    azRailItem(
        id = "tool.pen", text = "Pen",
        content = DesignR.drawable.ic_ps_pencil,
        color = if (uiState.activeTool == Tool.PEN) activeColor else navItemColor,
        onClick = { vm.setActiveTool(if (uiState.activeTool == Tool.PEN) Tool.NONE else Tool.PEN) },
    )
    azRailItem(
        id = "tool.liquify", text = "Liquify",
        content = DesignR.drawable.ic_ps_liquify,
        color = if (uiState.activeTool == Tool.LIQUIFY) activeColor else navItemColor,
        onClick = { vm.setActiveTool(if (uiState.activeTool == Tool.LIQUIFY) Tool.NONE else Tool.LIQUIFY) },
    )
    // Heal/Burn/Dodge/Color: ImageProcessor and buildStrokePaint already implement all four — they
    // just had no rail entry, so a user could never actually select them.
    azRailItem(
        id = "tool.heal", text = "Heal",
        content = DesignR.drawable.ic_ps_heal,
        color = if (uiState.activeTool == Tool.HEAL) activeColor else navItemColor,
        onClick = { vm.setActiveTool(if (uiState.activeTool == Tool.HEAL) Tool.NONE else Tool.HEAL) },
    )
    azRailItem(
        id = "tool.burn", text = navStrings.burn,
        content = DesignR.drawable.ic_ps_burn,
        color = if (uiState.activeTool == Tool.BURN) activeColor else navItemColor,
        onClick = { vm.setActiveTool(if (uiState.activeTool == Tool.BURN) Tool.NONE else Tool.BURN) },
    )
    azRailItem(
        id = "tool.dodge", text = navStrings.dodge,
        content = DesignR.drawable.ic_ps_dodge,
        color = if (uiState.activeTool == Tool.DODGE) activeColor else navItemColor,
        onClick = { vm.setActiveTool(if (uiState.activeTool == Tool.DODGE) Tool.NONE else Tool.DODGE) },
    )
    azRailItem(
        id = "tool.colorize", text = "Colorize",
        content = DesignR.drawable.ic_ps_color,
        color = if (uiState.activeTool == Tool.COLOR) activeColor else navItemColor,
        onClick = { vm.setActiveTool(if (uiState.activeTool == Tool.COLOR) Tool.NONE else Tool.COLOR) },
    )
    // Procreate's ColorDrop: tap the canvas to flood-fill with the active colour.
    azRailItem(
        id = "tool.fill", text = "Fill",
        content = DesignR.drawable.ic_ps_fill,
        color = if (uiState.activeTool == Tool.FILL) activeColor else navItemColor,
        onClick = { vm.setActiveTool(if (uiState.activeTool == Tool.FILL) Tool.NONE else Tool.FILL) },
    )
    // Procreate's freehand selection: lasso a region, and every raster tool is confined to it until
    // it's cleared. Dragging inside the marquee moves the selected pixels.
    azRailItem(
        id = "tool.select", text = "Select",
        content = DesignR.drawable.ic_ps_select,
        color = if (uiState.activeTool == Tool.SELECT) activeColor else navItemColor,
        onClick = { vm.setActiveTool(if (uiState.activeTool == Tool.SELECT) Tool.NONE else Tool.SELECT) },
    )
    // Only meaningful with something selected, so they appear with the selection rather than
    // sitting permanently greyed in the strip.
    if (uiState.selection != null) {
        azRailItem(
            id = "tool.selectInvert", text = "Invert",
            content = DesignR.drawable.ic_ps_invert,
            color = if (uiState.selection?.inverted == true) activeColor else navItemColor,
            onClick = { vm.onInvertSelection() },
        )
        azRailItem(
            id = "tool.deselect", text = "Deselect",
            content = DesignR.drawable.ic_ps_deselect,
            color = navItemColor,
            onClick = { vm.onClearSelection() },
        )
    }
    // QuickMenu, for discovery: the gesture is a four-finger hold (and opens where the fingers
    // landed), but a gesture nobody knows about is a feature nobody has. From here it opens at the
    // middle of the screen — the canvas is full-bleed, so that is the middle of the artwork too.
    azRailItem(
        id = "tool.quick", text = "Quick",
        content = DesignR.drawable.ic_ps_quickmenu,
        color = if (uiState.quickMenuAt != null) activeColor else navItemColor,
        onClick = { vm.onOpenQuickMenu(screenCenter) },
    )
    // Symmetry guide: strokes mirror across the vertical centre while it's on.
    azRailToggle(
        id = "tool.symmetry",
        isChecked = uiState.symmetryEnabled,
        toggleOnText = "Sym On",
        toggleOffText = "Sym Off",
        color = if (uiState.symmetryEnabled) activeColor else navItemColor,
        onClick = { vm.onToggleSymmetry() },
    )
    // Wrap-around canvas: strokes (and the canvas itself) tile past the edges instead of clipping.
    // Backend was complete (EditorScreen tiles the canvas, ImageProcessor tiles stroke rendering) but
    // toggleWrapAroundMode() had no caller anywhere in the UI, so it could never actually be turned on.
    azRailToggle(
        id = "tool.wraparound",
        isChecked = uiState.wrapAroundMode,
        toggleOnText = "Wrap On",
        toggleOffText = "Wrap Off",
        color = if (uiState.wrapAroundMode) activeColor else navItemColor,
        onClick = { vm.toggleWrapAroundMode() },
    )
    // Stroke stabilizer: smooths jitter out of a drag before it becomes a stroke point. Same gap as
    // wrap-around — StrokeStabilizer and setStabilizerLevel() were both wired ViewModel-side with
    // nothing in the rail ever calling it, so every stroke stayed unstabilized regardless of level.
    azRailSlider(
        id = "tool.stabilizer",
        text = "Stabilize",
        value = uiState.stabilizerLevel.toFloat(),
        config = AzSliderConfig(
            orientation = AzSliderOrientation.VERTICAL,
            valueFrom = 0f,
            valueTo = 100f,
        ),
        color = navItemColor,
        valueFormatter = { "${it.roundToInt()}%" },
        onValueChange = { vm.setStabilizerLevel(it.roundToInt()) },
    )
    // Procreate's edge sliders, as first-class rail items (AzNavRail 11.5's azRailSlider) rather than
    // the hand-rolled pair that used to float over the canvas unlabelled. Vertical, and each formats
    // its own read-out while dragging, so it's obvious which is which and what value you're on.
    azRailSlider(
        id = "tool.size",
        text = "Size",
        value = uiState.brushSize,
        config = AzSliderConfig(
            orientation = AzSliderOrientation.VERTICAL,
            valueFrom = MIN_BRUSH_SIZE,
            valueTo = MAX_BRUSH_SIZE,
        ),
        color = navItemColor,
        valueFormatter = { "${it.roundToInt()} px" },
        onValueChange = { vm.setBrushSize(it) },
    )
    // Brush opacity is the active colour's alpha — the value buildStrokePaint actually samples, so
    // this moves the stroke itself rather than a proxy for it.
    azRailSlider(
        id = "tool.opacity",
        text = "Opacity",
        value = uiState.activeColor.alpha,
        config = AzSliderConfig(
            orientation = AzSliderOrientation.VERTICAL,
            valueFrom = MIN_BRUSH_ALPHA,
            valueTo = 1f,
        ),
        color = navItemColor,
        valueFormatter = { "${(it * 100).roundToInt()}%" },
        onValueChange = { vm.setActiveColor(uiState.activeColor.copy(alpha = it)) },
    )
    // The colour item IS the current colour, the way Procreate's swatch is, rather than a generic
    // palette glyph that says nothing about what you're about to paint with.
    azRailItem(
        id = "tool.color", text = navStrings.color,
        content = uiState.activeColor,
        color = if (uiState.showColorPicker) activeColor else navItemColor,
        onClick = { vm.onColorClicked() },
    )
    if (brushes.isNotEmpty()) {
        azRailHostItem(id = "grp.brushes", text = "Brushes", content = DesignR.drawable.ic_ps_brush, color = navItemColor)
        azRailSubItem(
            id = "brush.round", hostId = "grp.brushes", text = "Round", shape = AzButtonShape.NONE,
            content = DesignR.drawable.ic_ps_circle,
            color = if (uiState.activeBrushName == null) activeColor else navItemColor,
            onClick = { vm.selectBrushExtension(null) },
        )
        brushes.forEach { (id, name) ->
            azRailSubItem(
                id = "brush.$id", hostId = "grp.brushes", text = name, shape = AzButtonShape.NONE,
                content = DesignR.drawable.ic_ps_brush,
                color = if (uiState.activeBrushName == name) activeColor else navItemColor,
                onClick = { vm.selectBrushExtension(id) },
            )
        }
    }

    // Layers, shown directly in the rail as relocatable (drag-to-reorder) sub-items — the
    // Procreate layers-panel equivalent — instead of a separate floating LayersPanel. Each
    // item's own content IS the layer's thumbnail. uiState.layers is bottom-to-top (index 0
    // paints first, underneath everything); declared here top-first (reversed) so the item at
    // the top of the expanded group is the frontmost layer, matching the old LayersPanel's
    // convention and Photoshop/Procreate's own. onRelocate's newOrder comes back in that same
    // top-first rail order, so it's reversed again before reaching onLayerReordered, which
    // expects bottom-first (it becomes the new uiState.layers verbatim).
    if (uiState.layers.isNotEmpty()) {
        azRailHostItem(id = "grp.layers", text = strings.editor.layers, content = DesignR.drawable.ic_ps_layers, color = navItemColor)
        uiState.layers.reversed().forEach { layer ->
            azRailRelocItem(
                id = "layer.${layer.id}",
                hostId = "grp.layers",
                text = layer.name,
                content = layer.bitmap ?: DesignR.drawable.ic_ps_layers,
                shape = AzButtonShape.NONE,
                color = if (layer.id == uiState.activeLayerId) activeColor else navItemColor,
                onClick = { vm.onLayerActivated(layer.id) },
                // newOrder comes back as this rail item's own ids ("layer.<uuid>"), not the raw
                // layer ids LayerListOps.reorder matches against — every entry used to miss,
                // silently reordering the layer list down to empty and saving that. Filtering to
                // "layer."-prefixed entries also protects against newOrder carrying ids from other
                // rail groups, if the library's relocate scope is ever wider than this host.
                onRelocate = { _, _, newOrder ->
                    val layerOrder = newOrder.filter { it.startsWith("layer.") }.map { it.removePrefix("layer.") }
                    vm.onLayerReordered(layerOrder.reversed())
                },
            ) {
                inputItem(hint = "Rename", initialValue = layer.name) { newName -> vm.onLayerRenamed(layer.id, newName) }
                listItem(if (layer.isVisible) strings.editor.hideLayer else strings.editor.showLayer) { vm.onToggleVisibility(layer.id) }
                listItem(if (layer.alphaLock) "Alpha Lock ✓" else "Alpha Lock") { vm.onToggleAlphaLock(layer.id) }
                listItem(strings.editor.duplicate) { vm.onLayerDuplicated(layer.id) }
                listItem("Merge Down") { vm.onMergeDown(layer.id) }
                listItem("Clear") { vm.onLayerActivated(layer.id); vm.onClearLayer() }
                listItem(strings.editor.delete) { vm.onLayerRemoved(layer.id) }
            }
        }
    }

    // Add and Align are document actions, not painting tools — they live in the drop-down (Procreate's
    // Actions menu), keeping the rail to the tools you reach for mid-stroke.

    // Adjust/Transform/Blend float free of the rail as a draggable palette (AzNavRail 11.3's
    // unattached host): the user parks it wherever it suits the artwork and the position persists
    // across launches, the way Procreate's panels stay where you leave them.
    azUnattachedHostItem(
        id = "grp.adjust", text = navStrings.adjust, anchor = AzUnattachedAnchor.FLOATING,
        content = DesignR.drawable.ic_ps_adjust, color = navItemColor,
    )
    azRailSubItem(
        id = "adj.adjust", hostId = "grp.adjust", text = navStrings.adjust, shape = AzButtonShape.NONE,
        content = DesignR.drawable.ic_ps_adjust,
        color = if (uiState.activePanel == EditorPanel.ADJUST) activeColor else navItemColor, onClick = { vm.onAdjustClicked() },
    )
    azRailSubItem(
        id = "adj.transform", hostId = "grp.adjust", text = "Transform", shape = AzButtonShape.NONE,
        content = DesignR.drawable.ic_ps_transform,
        color = if (uiState.activePanel == EditorPanel.TRANSFORM) activeColor else navItemColor, onClick = { vm.onTransformClicked() },
    )
    azRailSubItem(id = "adj.blend", hostId = "grp.adjust", text = "Blend", content = DesignR.drawable.ic_ps_blend, shape = AzButtonShape.NONE, onClick = { onBlendMode() })
    // Color Balance: onBalanceClicked()/ToggleColorPanel and the panel it opens (ColorBalanceKnobsRow,
    // rendered by EditorUi.kt when activePanel == EditorPanel.COLOR) were both already fully wired —
    // this was the only piece missing, an entry point to actually call onBalanceClicked().
    azRailSubItem(
        id = "adj.balance", hostId = "grp.adjust", text = "Balance", shape = AzButtonShape.NONE,
        content = DesignR.drawable.ic_ps_balance,
        color = if (uiState.activePanel == EditorPanel.COLOR) activeColor else navItemColor, onClick = { vm.onBalanceClicked() },
    )
    // Extensions: runs an installed code extension's filter/tool, or applies an installed LUT — both
    // already worked end to end once selected, but nothing ever opened the panel to select from.
    azRailSubItem(
        id = "adj.extensions", hostId = "grp.adjust", text = "Extensions", shape = AzButtonShape.NONE,
        content = DesignR.drawable.ic_ps_extension,
        color = if (uiState.activePanel == EditorPanel.EXTENSIONS) activeColor else navItemColor, onClick = { vm.onExtensionsClicked() },
    )
    // The size/feathering pad keeps its home here rather than in the rail: the edge slider covers
    // size, but feathering (drag across) and stamp-brush flow have nowhere else to live.
    azRailSubItem(
        id = "adj.brush", hostId = "grp.adjust", text = "Brush", shape = AzButtonShape.NONE,
        content = AzComposableContent { BrushSizePad(vm) },
    )

    val overlay = uiState.layers.find { it.id == uiState.activeLayerId }
    if (overlay != null) {
        azRailItem(id = "edit", text = "Edit", content = DesignR.drawable.ic_ps_more, color = navItemColor, onClick = { onEditClicked() })
    }
}
