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
import androidx.compose.runtime.DisposableEffect
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
import com.hereliesaz.graffitixr.common.model.AnimationLoopMode
import com.hereliesaz.graffitixr.common.model.ConstraintAnchor
import com.hereliesaz.graffitixr.common.model.LayoutAlign
import com.hereliesaz.graffitixr.common.model.LayoutDirection
import com.hereliesaz.graffitixr.common.model.BlendMode
import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.ProjectFile
import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.ShapeKind
import com.hereliesaz.graffitixr.common.model.SymmetryMode
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.design.GraffuxIcons
import com.hereliesaz.graffitixr.design.theme.AppStrings
import com.hereliesaz.graffitixr.design.theme.Cyan
import com.hereliesaz.graffitixr.design.theme.GraffitiXRTheme
import com.hereliesaz.graffitixr.design.theme.rememberAppStrings
import com.hereliesaz.graffitixr.feature.editor.AddContentDialog
import com.hereliesaz.graffitixr.feature.editor.AlignDialog
import com.hereliesaz.graffitixr.feature.editor.AlignMode
import com.hereliesaz.graffitixr.feature.editor.BackgroundColorDialog
import com.hereliesaz.graffitixr.data.brush.CustomBrush
import com.hereliesaz.graffitixr.feature.editor.BlendModePicker
import com.hereliesaz.graffitixr.feature.editor.BrushStudioWindow
import com.hereliesaz.graffitixr.feature.editor.CornerRadiusDialog
import com.hereliesaz.graffitixr.feature.editor.DocumentSizeDialog
import com.hereliesaz.graffitixr.feature.editor.EditorScreen
import com.hereliesaz.graffitixr.feature.editor.FigmaWindow
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.feature.editor.GalleryWindow
import com.hereliesaz.graffitixr.feature.editor.OpenProjectWindow
import com.hereliesaz.graffitixr.feature.editor.SaveProjectDialog
import com.hereliesaz.graffitixr.feature.editor.StrokeGate
import com.hereliesaz.graffitixr.feature.editor.multiFingerTaps
import com.hereliesaz.graffitixr.feature.editor.performGestureAction
import com.hereliesaz.graffitixr.data.azphalt.AzphaltStoreHandoff
import com.hereliesaz.graffitixr.feature.editor.LayerOptionsDialog
import com.hereliesaz.graffitixr.feature.editor.threed.ModelWindow
import com.hereliesaz.graffitixr.feature.editor.PolygonSidesDialog
import com.hereliesaz.graffitixr.feature.editor.ReferenceWindow
import com.hereliesaz.graffitixr.feature.editor.ShapeSizeDialog
import com.hereliesaz.graffitixr.feature.editor.StoreChooserDialog
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
            // The app's own theme, not a bare `MaterialTheme`. Graffux is a dark app, but a bare
            // MaterialTheme() takes Material 3's default **light** scheme, whose `onSurface` is a
            // near-black neutral — so every piece of chrome that took its colour from the theme
            // rather than setting one explicitly drew dark on dark. The file-operations dropdown was
            // the worst of it: unreadable. GraffitiXRTheme supplies the dark scheme this UI was drawn
            // against plus the brand palette.
            GraffitiXRTheme {
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
    val allInstalledExtensions by vm.allInstalledExtensions.collectAsState()
    val figmaState by vm.figmaState.collectAsState()
    val modelState by vm.modelState.collectAsState()
    val projects by vm.projects.collectAsState()
    val openScreenState by vm.openScreenState.collectAsState()
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
    var showStoreChooser by remember { mutableStateOf(false) }
    var showFigmaDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showGalleryDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showOpenDialog by remember { mutableStateOf(false) }
    // The name confirmed in the Save dialog, held while the system location picker is up — the
    // picker hands back a Uri and nothing else, so the name has to survive the round trip.
    var pendingSaveName by remember { mutableStateOf<String?>(null) }
    // Reference tool (Procreate's floating image-to-draw-from): purely a viewing aid, so it lives
    // as local UI state rather than in EditorUiState/the project — it isn't artwork, isn't
    // undo-tracked, and shouldn't survive into a save file the way a layer does.
    var showReferenceWindow by remember { mutableStateOf(false) }
    var referenceImageUri by remember { mutableStateOf<Uri?>(null) }

    // Pre-calculate `@Composable` colors outside the non-composable DSL block
    // The rail's active/selected colour, and it has to differ from the colour items already are.
    // It was `onSurface` — white under this theme — while every rail item's base colour is
    // `navItemColor`, which is also white on any dark canvas. Selecting an item therefore repainted
    // it the shade it already was, which is why nothing in the rail ever appeared to respond. The
    // brand accent reads against both of navItemColor's two values (white on a dark canvas, black on
    // a light one), so the highlight survives whatever the artwork is.
    val activeRailColor = MaterialTheme.colorScheme.primary
    // The file-operations dropdown sets this on every row rather than leaving it unspecified.
    //
    // Under 11.7 it had to: an unspecified row colour resolved through AzNavRail's own rail palette,
    // seeded here from `azTheme(activeColor = …)`, so a menu row inherited an accent chosen for the
    // rail and came out unreadable on the menu's dark panel. 11.8 fixes that end — it picks ink by
    // the panel's luminance (`azInkOn` / `azReadableOn`) and no longer consults the rail accent.
    //
    // Still set, now for a different reason: it makes the menu take its ink from *this app's* theme
    // rather than from the library's built-in near-white, so the menu matches the rest of the chrome
    // instead of merely being legible against it. The two currently agree, which is the point.
    val menuItemColor = MaterialTheme.colorScheme.onSurface
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

    // Autosave is debounced so a flurry of strokes doesn't re-encode a full-screen PNG on each one.
    // That debounce is exactly what loses the last edits when the user switches away and the
    // process is reclaimed, so leaving the foreground forces the pending writes out immediately.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) vm.flushPendingSaves()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { vm.onAddLayer(it) } }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.onImportDocument(it) } }

    val referencePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { referenceImageUri = it } }

    val brushPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.installExtensionFromUri(it) } }

    // The azphalt acquisition handoff (spec/store-app.md): a separate store app does the browsing and
    // fetching, and hands back a package this app still verifies itself — installExtensionFromUri runs
    // the exact same AzpInstaller check it would on a file the user picked by hand. RESULT_CANCELED
    // (the user backed out) is a clean no-op per the spec.
    val storeBrowser = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            // A browse request may ask for several packages, and the spec answers with one ClipData
            // item each (spec § The result). Reading only `data` installed the first and dropped the
            // rest without a word.
            val handoff = AzphaltStoreHandoff.resultOf(result.data)
            val packages = AzphaltStoreHandoff.packageUris(result.data)
            // The id/version extras describe a single delivered package, so they are only attached
            // when one came back; for a multi-selection they would mislabel every item but one.
            val metadata = handoff.takeIf { packages.size == 1 }
            packages.forEach { vm.installExtensionFromUri(it, metadata) }
        }
    }

    // Save: the system picker chooses the location, seeded with the filename built from the name
    // the user typed. Cancelling it leaves the project untouched — the work is already autosaved,
    // so backing out of Save costs nothing.
    val projectSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ProjectFile.MIME_TYPE)
    ) { uri ->
        val name = pendingSaveName
        pendingSaveName = null
        if (uri != null && name != null) vm.saveProjectAs(uri, name)
    }

    // Open: like the model picker, `.fux` has no registered MIME type, so the picker can't filter
    // by one — it opens on all files and the importer rejects anything that isn't a project.
    val projectOpener = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.openProjectFile(it) } }

    // OBJ has no registered MIME type on most devices, so the picker can't filter by one — it
    // opens on all files and the parser rejects anything that isn't a model.
    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.loadModel(it, it.lastPathSegment?.substringAfterLast('/')) } }

    val brushes by vm.installedBrushes.collectAsState()
    val customBrushes by vm.customBrushes.collectAsState()

    // Resolves and launches the azphalt store's browse intent (spec/store-app.md § Discovery), or
    // says plainly that none is installed — the spec requires degrading gracefully here, not silently
    // doing nothing or crashing on ActivityNotFoundException.
    // Acquisition is delegated (spec/store-app.md), and there are two ways to reach a store: the
    // Android store app, which hands verified bytes straight back through an Intent, and the web
    // storefront, which is a browser. Only the second is guaranteed to exist, so the user is asked
    // rather than silently given whichever happens to be present — which previously meant a toast
    // saying "no store app is installed" and no way forward at all.
    val openAzphaltStore: () -> Unit = { showStoreChooser = true }

    /** Android: browse in the installed store app, or go get one when there isn't one. */
    val browseAzphaltAndroid: () -> Unit = {
        showStoreChooser = false
        if (AzphaltStoreHandoff.isStoreAvailable(context.packageManager, context.packageName)) {
            // Carry what this host has already done with its extensions (spec/state-reporting.md
            // § 3.1), so the store's cards can read Open rather than Get on things the user has.
            storeBrowser.launch(
                AzphaltStoreHandoff.browseIntent(context.packageName, vm.extensionStateInventory())
            )
        } else {
            // Play first; a device without Play falls through to the web storefront, which is where
            // the app is distributed anyway. Either beats a dead button.
            runCatching { context.startActivity(AzphaltStoreHandoff.installStoreAppIntent()) }
                .onFailure {
                    runCatching { context.startActivity(AzphaltStoreHandoff.webStoreIntent()) }
                        .onFailure {
                            android.widget.Toast.makeText(
                                context,
                                "Nothing on this device can open the store",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                }
        }
    }

    /** Web: the storefront in a browser, the affordance every device already has. */
    val browseAzphaltWeb: () -> Unit = {
        showStoreChooser = false
        runCatching { context.startActivity(AzphaltStoreHandoff.webStoreIntent()) }
            .onFailure {
                android.widget.Toast.makeText(
                    context, "No browser to open azphalt.store", android.widget.Toast.LENGTH_LONG,
                ).show()
            }
    }

    // The same set the rail items tag themselves with; azConfig is what actually makes the library
    // render them active, and it is declared here rather than in the item builder.
    val activeClassifiers = activeRailClassifiers(uiState, brushes, customBrushes)

    val navItemColor = remember(uiState.canvasBackground) {
        val bg = uiState.canvasBackground
        val luminance = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
        if (luminance > 0.5f) Color.Black else Color.White
    }

    // Procreate's history gestures are installed HERE, above the whole app, not inside EditorScreen.
    // The nav rail, the onscreen chrome (dropdown, FAB row) and the floating windows are siblings of
    // the canvas, not children of it, so an observer living on the canvas never saw a two- or
    // three-finger tap that happened to land on any of them. At this level the gestures work
    // wherever the fingers land, whatever tool is active and whatever is selected. It consumes
    // nothing, so every control underneath still behaves exactly as before.
    val strokeGate = remember { StrokeGate() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .multiFingerTaps(strokeGate) { slot, at ->
                performGestureAction(uiState.gestureMapping, slot, vm, at)
            }
    ) {
        AzHostActivityLayout(navController = navController, initiallyExpanded = false) {
            azTheme(
                activeColor = activeRailColor, // Passed dynamically to avoid `@Composable` invocation errors
                // The border is what distinguishes "this opens something" from "this does something".
                // The rail's default is therefore borderless (AzNavRail's NONE_CIRCLE keeps the
                // CIRCLE footprint, so a borderless item still lines up with a bordered one) and the
                // only items that opt back into CIRCLE are hosts and the items that open a tool
                // window. Every leaf tool, toggle and picker inherits this.
                defaultShape = AzButtonShape.NONE_CIRCLE,
                headerIconShape = AzHeaderIconShape.CIRCLE,
                translucentBackground = Color.Black.copy(alpha = 0.55f)
            )
            azConfig(
                // What the rail should render as active. Without this only a transient last-tap
                // highlights, which is not a state — it is a memory of a gesture.
                activeClassifiers = activeClassifiers,
                noMenu = true,
                packButtons = true,
                dockingSide = if (uiState.isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT,
                railItemWidth = 44.dp
            )
            // Four-finger tap (see the multiFingerTaps observer above): full-screen art. Folding the
            // rail is the AzNavRail half of "hide the UI"; the onscreen chrome below gates on the
            // same flag.
            isFoldedUp = uiState.hideUiForCapture

            ConfigureRailItems(
                vm = vm,
                uiState = uiState,
                brushes = brushes,
                customBrushes = customBrushes,
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
                    EditorScreen(
                        vm = vm,
                        modifier = Modifier.fillMaxSize(),
                        // The host owns the multi-finger history gestures (installed above the whole
                        // app, see above); the screen just feeds this gate from its drawing surfaces.
                        hostStrokeGate = strokeGate,
                    )
                }
            }
        
            // Standalone Top-Right File Operations Dropdown (hidden in full-screen art mode)[span_5](start_span)[span_5](end_span)
            onscreen(alignment = Alignment.TopEnd) {
                if (!uiState.hideUiForCapture) AzDropdownMenu(navController = navController) {
                    azConfig(design = AzDropdownDesign.MENU, dockingSide = if (uiState.isRightHanded) AzDockingSide.RIGHT else AzDockingSide.LEFT)
                    // Every entry here is the same colour, and the dropdown's `azConfig` has no hook
                    // to say so once — so it was said twenty times over. An entry added without it
                    // falls back to Color.Unspecified rather than to the other nineteen, which is a
                    // silent way to end up with one unreadable line. Said once here instead.
                    fun menuItem(text: String, onClick: () -> Unit) =
                        azItem(color = menuItemColor, text = text, onClick = onClick)
                    // New/Open/Gallery/Import/Save map onto distinct actions rather than overloading one
                    // another: New starts a blank project; Open reopens a `.fux` file from anywhere on
                    // the device; Gallery browses the projects already in the app; Import brings an
                    // image or another design file INTO the current project, which is what the old
                    // "Open"/"Open File" pair actually did despite their names.
                    menuItem(text = strings.nav.new, onClick = { vm.createNewProject() })
                    menuItem(text = strings.nav.open, onClick = { vm.refreshOpenScreen(); showOpenDialog = true })
                    menuItem(text = "Gallery", onClick = { showGalleryDialog = true })
                    menuItem(text = "Import Image…", onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                    menuItem(text = "Import File…", onClick = { documentPicker.launch(arrayOf("*/*")) })
                    menuItem(text = "Add…", onClick = { showAddDialog = true })
                    menuItem(text = "Reference", onClick = { showReferenceWindow = true })
                    menuItem(text = "Align…", onClick = { showAlignDialog = true })
                    menuItem(text = "${uiState.documentWidth}×${uiState.documentHeight}", onClick = { showDocDialog = true })
                    menuItem(text = "Background", onClick = { showBgDialog = true })
                    // onFlattenAllLayers() was fully implemented (rasterizes every layer to one, undo-safe)
                    // but had no menu entry anywhere — see LayerOptionsDialog's "Merge Down" for the
                    // per-layer equivalent this complements at the whole-project level.
                    menuItem(text = "Flatten", onClick = { vm.onFlattenAllLayers() })
                    // Save names the project and writes a `.fux` wherever the user chooses. It isn't
                    // what keeps their work — autosave does that continuously — so it's free to be a
                    // deliberate, two-step action rather than something they must remember to press.
                    menuItem(text = strings.nav.save, onClick = { showSaveDialog = true })
                    menuItem(text = strings.nav.export, onClick = { vm.exportImage() })
                    menuItem(text = strings.nav.share, onClick = {
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
                    // Two entries, because they are two jobs. Store is where you *get* something and
                    // opens the chooser; Extensions is what you already have, and is where the
                    // installed list and its uninstall buttons live. Folding them together is what
                    // previously made "Store" open a management window with a browse button buried
                    // inside it.
                    menuItem(text = "Store", onClick = { showStoreChooser = true })
                    menuItem(text = "Extensions", onClick = { showStoreDialog = true })
                    menuItem(text = "Import from Figma…", onClick = { showFigmaDialog = true })
                    menuItem(text = "Export for Figma", onClick = { vm.exportForFigma() })
                    menuItem(text = "3D Model…", onClick = { showModelDialog = true })
                    menuItem(text = "Install brush…", onClick = { brushPicker.launch(arrayOf("*/*")) })
                    menuItem(text = "Settings", onClick = { showSettings = true })
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
                            Icon(painterResource(GraffuxIcons.ZoomFit), contentDescription = "Fit to screen")
                        }
                    }
                    if (uiState.undoCount > 0) {
                        FloatingActionButton(onClick = { vm.onUndoClicked() }, containerColor = surfaceVariantColor) {
                            Icon(painterResource(GraffuxIcons.Undo), contentDescription = "Undo")
                        }
                    }
                    if (uiState.redoCount > 0) {
                        FloatingActionButton(onClick = { vm.onRedoClicked() }, containerColor = surfaceVariantColor) {
                            Icon(painterResource(GraffuxIcons.Redo), contentDescription = "Redo")
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

                if (showOpenDialog) {
                    OpenProjectWindow(
                        state = openScreenState,
                        currentProjectId = uiState.projectId,
                        onOpenProject = { vm.openProject(it) },
                        onOpenFile = { vm.openProjectFile(it) },
                        onNew = { vm.createNewProject() },
                        // Stays open behind the system picker: cancelling it should put the user back
                        // where they were rather than making them find Open again.
                        onChooseLocation = { projectOpener.launch(arrayOf("*/*")) },
                        onDelete = { vm.deleteProjectById(it); vm.refreshOpenScreen() },
                        onDismiss = { showOpenDialog = false },
                    )
                }

                if (showSaveDialog) {
                    SaveProjectDialog(
                        currentName = projects.find { it.id == uiState.projectId }?.name.orEmpty(),
                        onConfirm = { name ->
                            showSaveDialog = false
                            pendingSaveName = name
                            projectSaver.launch(ProjectFile.suggestedFileName(name))
                        },
                        onDismiss = { showSaveDialog = false },
                    )
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

                if (showStoreChooser) {
                    StoreChooserDialog(
                        storeAppInstalled = AzphaltStoreHandoff.isStoreAvailable(
                            context.packageManager, context.packageName,
                        ),
                        onWeb = browseAzphaltWeb,
                        onAndroid = browseAzphaltAndroid,
                        onCancel = { showStoreChooser = false },
                    )
                }

                if (showStoreDialog) {
                    StoreWindow(
                        installed = allInstalledExtensions,
                        onBrowse = openAzphaltStore,
                        onUninstall = { vm.uninstallExtension(it) },
                        onDismiss = { showStoreDialog = false },
                    )
                }

                if (showModelDialog) {
                    ModelWindow(
                        state = modelState,
                        onPickModel = { modelPicker.launch(arrayOf("*/*")) },
                        onDismiss = { showModelDialog = false },
                        onPaintChanged = { vm.onModelPaintChanged() },
                        onAddToCanvas = { bitmap ->
                            vm.addModelViewToCanvas(bitmap)
                            showModelDialog = false
                        },
                        // The same colour and size the 2D canvas is set to: switching to a model is a
                        // change of surface, not of brush.
                        paintColor = uiState.activeColor,
                        brushRadiusPx = uiState.brushSize / 2f,
                    )
                }

                if (showFigmaDialog) {
                    FigmaWindow(
                        state = figmaState,
                        onTokenSubmit = { vm.connectFigma(it) },
                        onDisconnect = { vm.disconnectFigma() },
                        onFileInputChanged = { vm.onFigmaFileInputChanged(it) },
                        onLoadFile = { vm.loadFigmaFile() },
                        onToggleFrame = { vm.toggleFigmaFrame(it) },
                        onImport = { vm.importFigmaFrames() },
                        onDismiss = { showFigmaDialog = false },
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

                if (showReferenceWindow) {
                    ReferenceWindow(
                        imageUri = referenceImageUri,
                        onPickImage = { referencePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onDismiss = { showReferenceWindow = false },
                    )
                }

                uiState.brushStudioDraft?.let { draft ->
                    BrushStudioWindow(
                        draft = draft,
                        brushColor = uiState.activeColor,
                        isSaved = uiState.brushStudioEditingId != null,
                        onEdit = { edit -> vm.onEditBrushDraft(edit) },
                        onSave = { vm.onSaveBrushDraft() },
                        onDelete = { uiState.brushStudioEditingId?.let { vm.onDeleteCustomBrush(it) } },
                        onDismiss = { vm.onCloseBrushStudio() },
                    )
                }
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

/**
 * The rail item id for each [Tool].
 *
 * The single statement of that mapping. `ConfigureRailItems` builds its tool items from it and
 * [activeRailClassifiers] derives the lit id from it, so the two cannot disagree about which item a
 * tool belongs to — they used to be two hand-written lists, and three of the names differ from their
 * enum ([Tool.BLUR] is "Smudge", [Tool.COLOR] is "Colorize"), which is exactly the kind of pair that
 * drifts. [Tool.NONE] is absent on purpose: it is the absence of a tool, so nothing lights up.
 */
private val TOOL_IDS: Map<Tool, String> = mapOf(
    Tool.BRUSH to "tool.brush",
    Tool.BLUR to "tool.smudge",
    Tool.ERASER to "tool.eraser",
    Tool.PEN to "tool.pen",
    Tool.LIQUIFY to "tool.liquify",
    Tool.HEAL to "tool.heal",
    Tool.BURN to "tool.burn",
    Tool.DODGE to "tool.dodge",
    Tool.COLOR to "tool.colorize",
    Tool.FILL to "tool.fill",
    Tool.SELECT to "tool.select",
)

/**
 * Every rail item that is currently *active*, by item id.
 *
 * AzNavRail highlights an item when one of its `classifiers` appears in
 * `azConfig(activeClassifiers = …)`, which gives a filled container rather than only a tinted icon —
 * a much louder "you are here" than colour alone. Each stateful item tags itself with its own id and
 * this decides, in one place, which of those ids are lit.
 *
 * One place is the point. Every one of these conditions previously sat inline on the item that used
 * it, restated 33 times; the same duplication in the extensions panel had already drifted into the
 * install toast contradicting the panel it pointed at. Both the classifier and the item's colour now
 * read from this set, so the two cannot disagree about what is active.
 */
private fun activeRailClassifiers(
    uiState: EditorUiState,
    brushes: List<Pair<String, String>>,
    customBrushes: List<CustomBrush>,
): Set<String> = buildSet {
    // The active tool. One of these at a time, by construction; Tool.NONE has no item, because it is
    // the absence of one.
    TOOL_IDS[uiState.activeTool]?.let { add(it) }

    // Modes and toggles that stay on until turned off.
    if (uiState.symmetryMode != SymmetryMode.NONE) add("tool.symmetry")
    if (uiState.wrapAroundMode) add("tool.wraparound")
    if (uiState.isTimeLapseRecording) add("tool.timelapse")
    if (uiState.isAnimationMode) add("tool.animation")
    if (uiState.pathEditLayerId != null) add("tool.nodeEdit")
    if (uiState.selection?.inverted == true) add("tool.selectInvert")
    if (uiState.quickMenuAt != null) add("tool.quick")
    if (uiState.showColorPicker) add("tool.color")

    // Which panel is open — the rail item that opened it stays lit while it is.
    when (uiState.activePanel) {
        EditorPanel.ADJUST -> add("adj.adjust")
        EditorPanel.TRANSFORM -> add("adj.transform")
        EditorPanel.COLOR -> add("adj.balance")
        EditorPanel.EXTENSIONS -> add("adj.extensions")
        else -> Unit
    }

    // Animation.
    if (uiState.isAnimationPlaying) add("anim.play")
    if (uiState.onionSkinEnabled) add("anim.onion")
    add("anim.loop.${uiState.animationLoopMode.name}")

    // The current brush. `null` is the built-in round brush, which is why the host itself lights up
    // rather than a member of its list.
    if (uiState.activeBrushName == null) add("grp.brushes")
    brushes.firstOrNull { it.second == uiState.activeBrushName }?.let { add("brush.${it.first}") }
    customBrushes.firstOrNull { it.brush.name == uiState.activeBrushName }
        ?.let { add("brush.custom.${it.id}") }
    if (uiState.brushStudioDraft != null) add("brush.studio")

    // The layer being edited, and the two mode pickers — each always lights exactly one member,
    // because each is a choice among all of its options rather than an on/off.
    uiState.activeLayerId?.let { add("layer.$it") }
    add("symmetryMode.${uiState.symmetryMode.name}")
    add("selectShape.${uiState.selectionShape.name}")
}

private fun AzNavHostScope.ConfigureRailItems(
    vm: EditorViewModel,
    uiState: EditorUiState,
    brushes: List<Pair<String, String>>,
    customBrushes: List<CustomBrush>,
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
    // Computed once, read by every stateful item below for both its classifier and its colour.
    val activeIds = activeRailClassifiers(uiState, brushes, customBrushes)
    val navStrings = strings.nav

    /** An item's colour: the accent while it is active, the rail's base colour otherwise. */
    fun railColor(id: String): Color = if (id in activeIds) activeColor else navItemColor

    // ── Item builders ─────────────────────────────────────────────────────────────────────────────
    // Every item below was six near-identical lines, and an item that can be "on" restated one string
    // three times over — `id = "x"`, `classifiers = setOf("x")`, `color = railColor("x")`. Three
    // chances to mistype one of them, and a mistyped classifier is invisible: the item just never
    // lights up. Declared once here, the id *is* the classifier and the colour key.

    /** A rail item that can be active. */
    fun stateItem(id: String, text: String, content: Any?, shape: AzButtonShape? = null, onClick: () -> Unit) =
        azRailItem(
            id = id, classifiers = setOf(id), text = text, content = content,
            color = railColor(id), shape = shape, onClick = onClick,
        )

    /** [stateItem], under a host. */
    fun stateSubItem(id: String, hostId: String, text: String, content: Any?, shape: AzButtonShape? = null, onClick: () -> Unit) =
        azRailSubItem(
            id = id, hostId = hostId, classifiers = setOf(id), text = text, content = content,
            color = railColor(id), shape = shape, onClick = onClick,
        )

    /** A sub-item with no state to show — a one-shot action, so it never carries a classifier. */
    fun subItem(id: String, hostId: String, text: String, content: Any?, shape: AzButtonShape? = null, onClick: () -> Unit) =
        azRailSubItem(
            id = id, hostId = hostId, text = text, content = content,
            color = navItemColor, shape = shape, onClick = onClick,
        )

    /** A group header: the bordered circle every host in this rail shares. */
    fun hostItem(id: String, text: String, content: Any?, classifiers: Set<String> = emptySet()) =
        azRailHostItem(
            id = id, text = text, content = content, classifiers = classifiers,
            color = railColor(id), shape = AzButtonShape.CIRCLE,
        )

    /**
     * A tool. Tapping selects it; tapping the tool already in hand clears back to [Tool.NONE], which
     * is Procreate's behaviour and the reason all eleven of these were the same six lines. The id
     * comes from [TOOL_IDS], not the call site, so the rail and [activeRailClassifiers] read one
     * mapping and cannot disagree about which item is lit.
     */
    fun toolItem(tool: Tool, text: String, content: Any?) {
        val id = TOOL_IDS.getValue(tool)
        stateItem(id, text, content) { vm.setActiveTool(if (uiState.activeTool == tool) Tool.NONE else tool) }
    }

    /** [toolItem], under a host. */
    fun toolSubItem(tool: Tool, hostId: String, text: String, content: Any?) {
        val id = TOOL_IDS.getValue(tool)
        stateSubItem(id, hostId, text, content) { vm.setActiveTool(if (uiState.activeTool == tool) Tool.NONE else tool) }
    }

    /**
     * A mode that stays on until turned off.
     *
     * These were `azRailToggle`s, which take no `content` at all: the rail drew them as the words
     * "Sym On"/"Sym Off", so five items in a strip of glyphs were text. As classified items they
     * carry a glyph and take the filled container while the mode is on — the same "this is active"
     * every other item gets. The words stay in the drawer, which is a list and can afford them.
     */
    fun modeItem(id: String, text: String, content: Any?, onClick: () -> Unit) =
        azRailItem(
            id = id, classifiers = setOf(id), text = text,
            menuText = if (id in activeIds) "$text ✓" else text,
            content = content, color = railColor(id), onClick = onClick,
        )

    /**
     * A rail slider. Always vertical — the rail is — which is the whole of what the six
     * `AzSliderConfig(orientation = VERTICAL, …)` blocks below used to restate between them.
     */
    fun railSlider(
        id: String,
        text: String,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        format: (Float) -> String,
        onChange: (Float) -> Unit,
    ) = azRailSlider(
        id = id, text = text, value = value,
        config = AzSliderConfig(
            orientation = AzSliderOrientation.VERTICAL,
            valueFrom = range.start,
            valueTo = range.endInclusive,
        ),
        color = navItemColor, valueFormatter = format, onValueChange = onChange,
    )

    // ── Procreate's top-left group: Adjustments · Selection · Transform ────────────────────────────
    // Adjustments is `grp.adjust`, declared further down as a floating host — Procreate gives it a
    // panel, not a strip slot. Selection and Transform lead the strip.

    // Selection (the "S"): one control holding the selection *modes* and then the actions you perform
    // on a selection. These were four flat rail entries — Select, Quick, Invert, Deselect — sitting
    // among the paint tools, which is neither where Procreate puts them nor what they are: three of
    // the four do nothing at all until a selection exists.
    hostItem(id = "grp.select", text = "Selection", content = GraffuxIcons.SelectLasso)
    // Selecting: pick up the tool, then pick how a drag is read. Procreate's modes sit inside its
    // Selection control the same way, and for the same reason — they are not four tools, they are
    // one tool that makes a region four ways. A region confines every raster tool until it's
    // cleared; dragging inside the marquee moves the selected pixels.
    toolSubItem(Tool.SELECT, hostId = "grp.select", text = "Select", content = GraffuxIcons.SelectLasso)
    SelectionShape.entries.forEach { mode ->
        stateSubItem(
            id = "selectShape.${mode.name}", hostId = "grp.select", text = mode.label,
            content = when (mode) {
                SelectionShape.FREEHAND -> GraffuxIcons.SelectLasso
                SelectionShape.RECTANGLE -> GraffuxIcons.SelectRect
                SelectionShape.ELLIPSE -> GraffuxIcons.SelectEllipse
            },
        ) { vm.onSetSelectionShape(mode) }
    }
    // QuickMenu, for discovery: the gesture is a four-finger hold (and opens where the fingers
    // landed), but a gesture nobody knows about is a feature nobody has. From here it opens at the
    // middle of the screen — the canvas is full-bleed, so that is the middle of the artwork too.
    stateSubItem("tool.quick", "grp.select", "Quick", GraffuxIcons.SelectQuick) {
        vm.onOpenQuickMenu(screenCenter)
    }
    stateSubItem("tool.selectInvert", "grp.select", "Invert", GraffuxIcons.SelectInvert) {
        vm.onInvertSelection()
    }
    subItem("tool.deselect", "grp.select", "Deselect", GraffuxIcons.SelectNone) { vm.onClearSelection() }

    // Transform is its own control in Procreate's top bar, beside Adjustments and Selection — not an
    // item inside Adjustments, which is where it was. It keeps `adj.transform` as its id, so the
    // classifier set and the panel it opens are unchanged.
    stateItem("adj.transform", "Transform", GraffuxIcons.SelectTransform) { vm.onTransformClicked() }

    // Procreate's tool strip: a flat row of the tools you paint with, not an accordion you have to
    // open first, drawn from the Graffux set (`GraffuxIcons`, generated by branding/icons/build.py)
    // rather than a general-purpose library — a broom stood in for the eraser and a squiggle for
    // smudge, because a library built for dashboards has no drawing for either.
    // Every item gets its own glyph — the old rail drew Icons.Filled.Brush for the group,
    // the brush tool, the round brush AND every installed brush, and the same pencil for Pen and Edit,
    // so nothing in it was identifiable at a glance.
    toolItem(Tool.BRUSH, uiState.activeBrushName ?: navStrings.brush, GraffuxIcons.Brush)
    toolItem(Tool.BLUR, "Smudge", GraffuxIcons.Smudge)
    toolItem(Tool.ERASER, "Eraser", GraffuxIcons.Eraser)

        // Procreate's constant tools run Brush, Smudge, Erase, Layers, Colour. The colour well
        // is one of those five, not something to hunt for below a dozen extras, which is where
        // it sat. Layers has left this strip entirely — it is an unattached floating host, the
        // rail's nearest equivalent to Procreate's Layers panel — so Colour follows Erase
        // directly. Everything below these is a tool Procreate does not put at top level.
    // The colour item IS the current colour, the way Procreate's swatch is, rather than a generic
    // palette glyph that says nothing about what you're about to paint with.
    stateItem(
        id = "tool.color", text = navStrings.color, content = uiState.activeColor,
        // Bordered: it opens the colour picker rather than selecting a tool.
        shape = AzButtonShape.CIRCLE,
    ) { vm.onColorClicked() }

    // Below the five Procreate keeps at top level: tools it puts elsewhere, and Graffux's own.
    toolItem(Tool.PEN, "Pen", GraffuxIcons.PenInk)
    // Heal/Burn/Dodge/Color: ImageProcessor and buildStrokePaint already implement all four — they
    // just had no rail entry, so a user could never actually select them.
    toolItem(Tool.HEAL, "Heal", GraffuxIcons.Heal)
    toolItem(Tool.BURN, navStrings.burn, GraffuxIcons.Burn)
    toolItem(Tool.DODGE, navStrings.dodge, GraffuxIcons.Dodge)
    toolItem(Tool.COLOR, "Colorize", GraffuxIcons.ColorDisc)
    // Procreate's ColorDrop: tap the canvas to flood-fill with the active colour.
    toolItem(Tool.FILL, "Fill", GraffuxIcons.Colordrop)

    // Symmetry guide: strokes mirror across one or more axes while it's on. This is the quick
    // on/off; the picker below reaches every mode, including turning it off from there too.
    modeItem("tool.symmetry", "Symmetry", GraffuxIcons.Symmetry) { vm.onToggleSymmetry() }
    // Auto-layout and constraints (Figma). Auto-layout only appears on a layer that actually has
    // children to arrange; constraints only on a layer that has a parent to be constrained within.
    run {
        val active = uiState.layers.firstOrNull { it.id == uiState.activeLayerId }
        val hasChildren = active != null && uiState.layers.any { it.parentId == active.id }
        val hasParent = active?.parentId != null
        if (hasChildren || hasParent) {
            hostItem(id = "grp.layout", text = "Layout", content = GraffuxIcons.AlignToArtboard)
            if (hasChildren) {
                val current = active.autoLayout
                LayoutDirection.entries.forEach { dir ->
                    azRailSubItem(
                        id = "lay.dir.${dir.name}", hostId = "grp.layout",
                        text = when (dir) {
                            LayoutDirection.NONE -> "Free"
                            LayoutDirection.HORIZONTAL -> "Row"
                            LayoutDirection.VERTICAL -> "Column"
                        },
                        content = when (dir) {
                            LayoutDirection.NONE -> GraffuxIcons.SelectTransform
                            LayoutDirection.HORIZONTAL -> GraffuxIcons.DistributeHorizontal
                            LayoutDirection.VERTICAL -> GraffuxIcons.DistributeVertical
                        },
                        color = if (current.direction == dir) activeColor else navItemColor,
                        onClick = { vm.onSetAutoLayout(current.copy(direction = dir)) },
                    )
                }
                if (current.direction != LayoutDirection.NONE) {
                    subItem("lay.hug", "grp.layout", "Hug Contents", GraffuxIcons.ScaleProportional) {
                        vm.onHugContents()
                    }
                    railSlider("lay.gap", "Gap", current.gap, 0f..100f, { "${it.roundToInt()}" }) {
                        vm.onSetAutoLayout(current.copy(gap = it))
                    }
                    LayoutAlign.entries.forEach { align ->
                        azRailSubItem(
                            id = "lay.align.${align.name}", hostId = "grp.layout",
                            text = "Align ${align.name.lowercase().replaceFirstChar { c -> c.uppercase() }}",
                            content = when (align) {
                                LayoutAlign.START -> GraffuxIcons.AlignTop
                                LayoutAlign.CENTER -> GraffuxIcons.AlignMiddleV
                                LayoutAlign.END -> GraffuxIcons.AlignBottom
                            },
                            color = if (current.align == align) activeColor else navItemColor,
                            onClick = { vm.onSetAutoLayout(current.copy(align = align)) },
                        )
                    }
                }
            }
            // Constraints are per-axis; a parent running auto-layout owns placement instead, so
            // they're hidden in that case rather than shown as controls that do nothing.
            val parentAutoLays = active?.parentId
                ?.let { pid -> uiState.layers.firstOrNull { it.id == pid } }
                ?.autoLayout?.direction != LayoutDirection.NONE
            if (hasParent && !parentAutoLays) {
                ConstraintAnchor.entries.forEach { anchor ->
                    azRailSubItem(
                        id = "lay.h.${anchor.name}", hostId = "grp.layout",
                        text = "H: ${anchor.name.lowercase().replaceFirstChar { c -> c.uppercase() }}",
                        content = when (anchor) {
                            ConstraintAnchor.START -> GraffuxIcons.AlignLeft
                            ConstraintAnchor.CENTER -> GraffuxIcons.AlignCenterH
                            ConstraintAnchor.END -> GraffuxIcons.AlignRight
                            ConstraintAnchor.STRETCH -> GraffuxIcons.Scale
                            ConstraintAnchor.SCALE -> GraffuxIcons.ScaleProportional
                        },
                        color = if (active.constraints.horizontal == anchor) activeColor else navItemColor,
                        onClick = { vm.onSetConstraints(active.constraints.copy(horizontal = anchor)) },
                    )
                }
                ConstraintAnchor.entries.forEach { anchor ->
                    azRailSubItem(
                        id = "lay.v.${anchor.name}", hostId = "grp.layout",
                        text = "V: ${anchor.name.lowercase().replaceFirstChar { c -> c.uppercase() }}",
                        content = when (anchor) {
                            ConstraintAnchor.START -> GraffuxIcons.AlignTop
                            ConstraintAnchor.CENTER -> GraffuxIcons.AlignMiddleV
                            ConstraintAnchor.END -> GraffuxIcons.AlignBottom
                            ConstraintAnchor.STRETCH -> GraffuxIcons.Scale
                            ConstraintAnchor.SCALE -> GraffuxIcons.ScaleProportional
                        },
                        color = if (active.constraints.vertical == anchor) activeColor else navItemColor,
                        onClick = { vm.onSetConstraints(active.constraints.copy(vertical = anchor)) },
                    )
                }
            }
        }
    }

    // Shared styles (Figma tokens). The library is a real registry on UiState, so the group shows
    // whenever a token exists or the active layer is something a token could be lifted from.
    run {
        val active = uiState.layers.firstOrNull { it.id == uiState.activeLayerId }
        val hasShape = active?.shapes?.isNotEmpty() == true
        val hasText = active?.textParams != null
        if (uiState.colorStyles.isNotEmpty() || uiState.textStyles.isNotEmpty() || hasShape || hasText) {
            hostItem(id = "grp.styles", text = "Styles", content = GraffuxIcons.LayerStyle)
            if (hasShape) {
                subItem("sty.newColor", "grp.styles", "New Colour Style", GraffuxIcons.ColorSwatch) {
                    vm.onCreateColorStyleFromActive("Colour ${uiState.colorStyles.size + 1}")
                }
            }
            if (hasText) {
                subItem("sty.newText", "grp.styles", "New Text Style", GraffuxIcons.FontFamily) {
                    vm.onCreateTextStyleFromActive("Text ${uiState.textStyles.size + 1}")
                }
            }
            // Applying a token to the active layer, and re-pointing a token at the current colour.
            uiState.colorStyles.forEach { style ->
                val linked = active?.shapes?.any { it.fillStyleId == style.id } == true
                azRailSubItem(
                    id = "sty.color.${style.id}", hostId = "grp.styles", text = style.name,
                    content = GraffuxIcons.ColorSwatches,
                    color = if (linked) activeColor else navItemColor,
                    onClick = { vm.onApplyColorStyle(if (linked) null else style.id) },
                )
            }
            uiState.textStyles.forEach { style ->
                val linked = active?.textParams?.styleId == style.id
                azRailSubItem(
                    id = "sty.text.${style.id}", hostId = "grp.styles", text = style.name,
                    content = GraffuxIcons.GlyphsPanel,
                    color = if (linked) activeColor else navItemColor,
                    onClick = { vm.onApplyTextStyle(if (linked) null else style.id) },
                )
            }
        }
    }

    // Components and instances (Figma). The actions offered depend on what the active layer already
    // is, so the rail never shows a control that would no-op — "Detach" only on an instance,
    // "Release" only on a main, "Make Component" only on a plain layer.
    run {
        val active = uiState.layers.firstOrNull { it.id == uiState.activeLayerId }
        val components = uiState.layers.filter { it.componentId != null }
        if (active != null || components.isNotEmpty()) {
            hostItem(id = "grp.components", text = "Components", content = GraffuxIcons.LayerSmart)
            if (active != null && active.componentId == null && active.instanceOf == null) {
                subItem("cmp.make", "grp.components", "Make Component", GraffuxIcons.LayerSmart) {
                    vm.onMakeComponent()
                }
            }
            // Detach and Release are one-shots, but they only exist while the active layer *is* an
            // instance or a main — the accent says "this is what the layer you're on already is".
            if (active?.instanceOf != null) {
                azRailSubItem(
                    id = "cmp.detach", hostId = "grp.components", text = "Detach Instance",
                    content = GraffuxIcons.LayerUnlink, color = activeColor,
                    onClick = { vm.onDetachInstance() },
                )
            }
            if (active?.componentId != null) {
                azRailSubItem(
                    id = "cmp.release", hostId = "grp.components", text = "Release Component",
                    content = GraffuxIcons.LayerUngroup, color = activeColor,
                    onClick = { vm.onReleaseComponent() },
                )
            }
            // One "place" entry per defined component — the library, derived from the stack.
            components.forEach { main ->
                subItem(
                    "cmp.place.${main.componentId}", "grp.components",
                    "Place ${main.name}", GraffuxIcons.LayerDuplicate,
                ) { main.componentId?.let { vm.onPlaceInstance(it) } }
            }
        }
    }

    // Vector node editing (Figma's vector pen). Shown only for a layer that actually has an
    // editable path, so it isn't a permanently-dead item on a raster document.
    run {
        val active = uiState.layers.firstOrNull { it.id == uiState.activeLayerId }
        val editable = active?.shapes?.singleOrNull()?.kind == ShapeKind.PATH
        if (editable || uiState.pathEditLayerId != null) {
            modeItem("tool.nodeEdit", "Edit Nodes", GraffuxIcons.PathSelectDirect) {
                vm.onToggleActivePathEdit()
            }
        }
        if (uiState.pathEditLayerId != null) {
            azRailItem(
                id = "tool.nodeClose", text = "Close Path",
                content = GraffuxIcons.PathClose, color = navItemColor,
                onClick = { vm.onTogglePathClosed() },
            )
            uiState.selectedNodeIndex?.let { index ->
                azRailItem(
                    id = "tool.nodeDelete", text = "Delete Node",
                    content = GraffuxIcons.PenRemove, color = navItemColor,
                    onClick = { vm.onDeletePathNode(index) },
                )
            }
        }
    }
    hostItem(id = "grp.symmetryMode", text = "Symmetry Mode", content = GraffuxIcons.Symmetry)
    SymmetryMode.entries.forEach { mode ->
        stateSubItem(
            id = "symmetryMode.${mode.name}", hostId = "grp.symmetryMode", text = mode.label,
            // Each mode draws its own axes. Every entry shared one glyph before, so the open
            // group was five identical buttons distinguishable only by their labels.
            content = when (mode) {
                SymmetryMode.NONE -> GraffuxIcons.SelectNone
                SymmetryMode.VERTICAL -> GraffuxIcons.SymmetryVertical
                SymmetryMode.HORIZONTAL -> GraffuxIcons.SymmetryHorizontal
                SymmetryMode.QUADRANT -> GraffuxIcons.SymmetryQuadrant
                SymmetryMode.RADIAL_6 -> GraffuxIcons.SymmetryRadial
            },
        ) { vm.onSetSymmetryMode(mode) }
    }
    // Wrap-around canvas: strokes (and the canvas itself) tile past the edges instead of clipping.
    // Backend was complete (EditorScreen tiles the canvas, ImageProcessor tiles stroke rendering) but
    // toggleWrapAroundMode() had no caller anywhere in the UI, so it could never actually be turned on.
    modeItem("tool.wraparound", "Wrap Around", GraffuxIcons.StampPattern) { vm.toggleWrapAroundMode() }
    // Stroke stabilizer: smooths jitter out of a drag before it becomes a stroke point. Same gap as
    // wrap-around — StrokeStabilizer and setStabilizerLevel() were both wired ViewModel-side with
    // nothing in the rail ever calling it, so every stroke stayed unstabilized regardless of level.
    railSlider("tool.stabilizer", "Stabilize", uiState.stabilizerLevel.toFloat(), 0f..100f, { "${it.roundToInt()}%" }) {
        vm.setStabilizerLevel(it.roundToInt())
    }
    // Time-lapse: streams a downsampled snapshot to a GIF after every committed stroke while on,
    // then saves the finished clip to Downloads the moment it's turned back off.
    modeItem("tool.timelapse", "Time-lapse", GraffuxIcons.ActionRecord) { vm.onToggleTimeLapseRecording() }
    // Animation Assist. Every top-level layer is a frame (see AnimationFrames), so the layer group
    // above doubles as the frame timeline — this group is the transport, onion-skin, and export
    // controls that turn that stack into an animation.
    modeItem("tool.animation", "Animation", GraffuxIcons.MotionTween) { vm.onToggleAnimationMode() }
    if (uiState.isAnimationMode) {
        val frameCount = uiState.layers.count { it.parentId == null }
        hostItem(
            id = "grp.animation",
            text = "Frame ${(uiState.activeFrameIndex + 1).coerceAtMost(frameCount.coerceAtLeast(1))}/$frameCount",
            content = GraffuxIcons.Timeline,
        )
        stateSubItem(
            id = "anim.play", hostId = "grp.animation",
            text = if (uiState.isAnimationPlaying) "Pause" else "Play",
            content = if (uiState.isAnimationPlaying) GraffuxIcons.Pause else GraffuxIcons.Play,
        ) { vm.onToggleAnimationPlayback() }
        subItem("anim.prev", "grp.animation", "Previous", GraffuxIcons.StepBackward) { vm.onPreviousFrame() }
        subItem("anim.next", "grp.animation", "Next", GraffuxIcons.StepForward) { vm.onNextFrame() }
        subItem("anim.add", "grp.animation", "Add Frame", GraffuxIcons.FrameAdd) { vm.onAddFrame() }
        stateSubItem("anim.onion", "grp.animation", "Onion Skin", GraffuxIcons.OnionSkin) {
            vm.onToggleOnionSkin()
        }
        AnimationLoopMode.entries.forEach { mode ->
            stateSubItem(
                id = "anim.loop.${mode.name}", hostId = "grp.animation", text = mode.label,
                content = when (mode) {
                    AnimationLoopMode.LOOP -> GraffuxIcons.LoopPlayback
                    AnimationLoopMode.PING_PONG -> GraffuxIcons.Symmetry
                    AnimationLoopMode.ONCE -> GraffuxIcons.Play
                },
            ) { vm.onSetAnimationLoopMode(mode) }
        }
        subItem("anim.export", "grp.animation", "Export GIF", GraffuxIcons.ExportGif) { vm.exportAnimation() }
        // Onion-skin depth and frame duration as sliders, since both are "dial it in while watching"
        // values rather than discrete picks.
        railSlider("anim.onionCount", "Onion", uiState.onionSkinFrameCount.toFloat(), 1f..5f, { "${it.roundToInt()}" }) {
            vm.onSetOnionSkinFrameCount(it.roundToInt())
        }
        railSlider("anim.speed", "Speed", uiState.animationFrameDurationMs.toFloat(), 20f..500f, { "${it.roundToInt()}ms" }) {
            vm.onSetAnimationFrameDurationMs(it.roundToInt())
        }
    }
    // Procreate's edge sliders, as first-class rail items (AzNavRail 11.5's azRailSlider) rather than
    // the hand-rolled pair that used to float over the canvas unlabelled. Vertical, and each formats
    // its own read-out while dragging, so it's obvious which is which and what value you're on.
    railSlider("tool.size", "Size", uiState.brushSize, MIN_BRUSH_SIZE..MAX_BRUSH_SIZE, { "${it.roundToInt()} px" }) {
        vm.setBrushSize(it)
    }
    // Brush opacity is the active colour's alpha — the value buildStrokePaint actually samples, so
    // this moves the stroke itself rather than a proxy for it.
    railSlider("tool.opacity", "Opacity", uiState.activeColor.alpha, MIN_BRUSH_ALPHA..1f, { "${(it * 100).roundToInt()}%" }) {
        vm.setActiveColor(uiState.activeColor.copy(alpha = it))
    }

    // The brush group is now unconditional: Brush Studio means there's always something to do here
    // (make one), where previously the whole group vanished unless a brush extension was installed.
    hostItem(id = "grp.brushes", text = "Brushes", content = GraffuxIcons.BrushLibrary, classifiers = setOf("grp.brushes"))
    // The built-in round brush is "no extension selected", which is the state `grp.brushes` itself
    // carries — so this entry tracks the host's colour rather than one of its own.
    azRailSubItem(
        id = "brush.round", hostId = "grp.brushes", text = "Round",
        content = GraffuxIcons.BrushCursor,
        color = railColor("grp.brushes"),
        onClick = { vm.selectBrushExtension(null) },
    )
    brushes.forEach { (id, name) ->
        stateSubItem("brush.$id", "grp.brushes", name, GraffuxIcons.Brush) { vm.selectBrushExtension(id) }
    }
    customBrushes.forEach { custom ->
        stateSubItem("brush.custom.${custom.id}", "grp.brushes", custom.brush.name, GraffuxIcons.Brush) {
            vm.selectCustomBrush(custom.id)
        }
    }
    // Opens on the brush currently in hand, so "Brush Studio" reads as "edit this brush" when one is
    // selected and "make a new one" when it isn't — Procreate's own behaviour.
    stateSubItem(
        id = "brush.studio", hostId = "grp.brushes", text = "Brush Studio",
        content = GraffuxIcons.BrushSettings, shape = AzButtonShape.CIRCLE,
    ) {
        val editing = customBrushes.firstOrNull { it.brush.name == uiState.activeBrushName }?.id
        vm.onOpenBrushStudio(editing)
    }

    // Layers, shown directly in the rail as relocatable (drag-to-reorder) sub-items — the
    // Procreate layers-panel equivalent — instead of a separate floating LayersPanel. Each
    // item's own content IS the layer's thumbnail. uiState.layers is bottom-to-top (index 0
    // paints first, underneath everything); declared here top-first (reversed) so the item at
    // the top of the expanded group is the frontmost layer, matching the old LayersPanel's
    // convention and Photoshop/Procreate's own. A group layer's children render inside its own
    // nested rail (azRailRelocItem's nestedContent), a real recursive popup — not a flat
    // indented stand-in — so reordering stays scoped to siblings at each level.
    if (uiState.layers.isNotEmpty()) {
        // Unattached and floating, not a group in the rail strip. Layers is the one host you keep
        // reaching for *while* painting — Procreate gives it a panel of its own for that reason — and
        // as a rail group it sat behind the same expand-collapse as every tool, so getting to a layer
        // meant opening the rail and pushing everything else aside. FLOATING parks it wherever the
        // user drags it and remembers that across launches, which is the nearest thing the rail has
        // to a panel. The host and its whole subtree leave the rail strip and the drawer menu.
        //
        // Square, not the circle every rail group wears: a floating panel is not a rail group, and
        // the shape is what says so before the user reads a label. It also matches what the host
        // contains — layer thumbnails are rectangular images, and a circular clip crops the corners
        // off every one of them.
        azUnattachedHostItem(
            id = "grp.layers",
            text = strings.editor.layers,
            anchor = AzUnattachedAnchor.FLOATING,
            content = GraffuxIcons.Layers,
            color = navItemColor,
            shape = AzButtonShape.SQUARE,
        )
        uiState.layers.filter { it.parentId == null }.reversed().forEach { layer ->
            renderLayerRailItem(layer, uiState, "grp.layers", vm, activeColor, navItemColor, strings)
        }
    }

    // Add and Align are document actions, not painting tools — they live in the drop-down (Procreate's
    // Actions menu), keeping the rail to the tools you reach for mid-stroke.

    // Adjust/Transform/Blend float free of the rail as a draggable palette (AzNavRail 11.3's
    // unattached host): the user parks it wherever it suits the artwork and the position persists
    // across launches, the way Procreate's panels stay where you leave them.
    azUnattachedHostItem(
        id = "grp.adjust", text = navStrings.adjust, anchor = AzUnattachedAnchor.FLOATING,
        content = GraffuxIcons.LayerAdjustment, color = navItemColor, shape = AzButtonShape.CIRCLE,
    )
    stateSubItem(
        id = "adj.adjust", hostId = "grp.adjust", text = navStrings.adjust,
        content = GraffuxIcons.LayerAdjustment, shape = AzButtonShape.CIRCLE,
    ) { vm.onAdjustClicked() }
    // Procreate keeps Liquify in Adjustments, alongside the blurs and the colour work: it is a filter
    // you apply, not a brush you paint with. It sat among the paint tools purely because it happens
    // to be driven by dragging.
    toolSubItem(Tool.LIQUIFY, hostId = "grp.adjust", text = "Liquify", content = GraffuxIcons.Liquify)
    subItem("adj.blend", "grp.adjust", "Blend", GraffuxIcons.LayerBlend, AzButtonShape.CIRCLE) { onBlendMode() }
    // Color Balance: onBalanceClicked()/ToggleColorPanel and the panel it opens (ColorBalanceKnobsRow,
    // rendered by EditorUi.kt when activePanel == EditorPanel.COLOR) were both already fully wired —
    // this was the only piece missing, an entry point to actually call onBalanceClicked().
    stateSubItem(
        id = "adj.balance", hostId = "grp.adjust", text = "Balance",
        content = GraffuxIcons.ColorBalance, shape = AzButtonShape.CIRCLE,
    ) { vm.onBalanceClicked() }
    // Extensions: runs an installed code extension's filter/tool, or applies an installed LUT — both
    // already worked end to end once selected, but nothing ever opened the panel to select from.
    stateSubItem(
        id = "adj.extensions", hostId = "grp.adjust", text = "Extensions",
        content = GraffuxIcons.FilterGallery, shape = AzButtonShape.CIRCLE,
    ) { vm.onExtensionsClicked() }
    // The size/feathering pad keeps its home here rather than in the rail: the edge slider covers
    // size, but feathering (drag across) and stamp-brush flow have nowhere else to live.
    azRailSubItem(
        // The one item that keeps the wide NONE footprint rather than NONE_CIRCLE: its content is a
        // drag pad, not a glyph, and BrushSizePad clamps the maximum brush size to its own measured
        // width (`maxPx = itemPx`). On a circle footprint that ceiling would collapse to the rail's
        // 44dp, so the shape here is carrying layout, not affordance.
        id = "adj.brush", hostId = "grp.adjust", text = "Brush", shape = AzButtonShape.NONE,
        content = AzComposableContent { BrushSizePad(vm) },
    )

    val overlay = uiState.layers.find { it.id == uiState.activeLayerId }
    if (overlay != null) {
        // Bordered: opens the layer-options window.
        azRailItem(id = "edit", text = "Edit", content = GraffuxIcons.Menu, color = navItemColor, shape = AzButtonShape.CIRCLE, onClick = { onEditClicked() })
    }
}

/**
 * Renders one layer row under [hostId] and, if it's a [LayerType.GROUP], recurses into its own
 * nested rail for its children (their own host scope, so drag-reordering a group's contents never
 * touches a sibling group or the top level). onRelocate's newOrder comes back in this rail's own
 * top-first display order restricted to this host's items; reversed to bottom-first and applied via
 * [LayerListOps.reorderSubset], which — unlike the plain [LayerListOps.reorder] the rest of the
 * app's history assumed — only touches the named layers' own slots, since a scoped relocate no
 * longer covers the whole flat list the way the single flat "grp.layers" host used to.
 */
private fun AzNavHostScope.renderLayerRailItem(
    layer: Layer,
    uiState: EditorUiState,
    hostId: String,
    vm: EditorViewModel,
    activeColor: Color,
    navItemColor: Color,
    strings: AppStrings,
) {
    val isGroup = layer.type == LayerType.GROUP
    val children = if (isGroup) uiState.layers.filter { it.parentId == layer.id } else emptyList()
    azRailRelocItem(
        id = "layer.${layer.id}", classifiers = setOf("layer.${layer.id}"),
        hostId = hostId,
        text = layer.name,
        content = if (isGroup) GraffuxIcons.LayerGroup else (layer.bitmap ?: GraffuxIcons.LayerThumbnail),
        // Square, like the floating host these live in — and unlike a circular clip, it shows the
        // layer's thumbnail whole instead of cropping its corners. A group layer hosts its children's
        // nested rail, so it keeps a border; a leaf layer is just a selection and stays borderless.
        shape = if (isGroup) AzButtonShape.SQUARE else AzButtonShape.NONE_SQUARE,
        color = if (!isGroup && layer.id == uiState.activeLayerId) activeColor else navItemColor,
        onClick = { if (!isGroup) vm.onLayerActivated(layer.id) },
        onRelocate = { _, _, newOrder ->
            val ids = newOrder.filter { it.startsWith("layer.") }.map { it.removePrefix("layer.") }
            vm.onLayerReordered(ids.reversed())
        },
        keepNestedRailOpen = isGroup,
        nestedContent = if (isGroup) {
            {
                children.reversed().forEach { child ->
                    renderLayerRailItem(child, uiState, "group.${layer.id}", vm, activeColor, navItemColor, strings)
                }
            }
        } else null,
    ) {
        inputItem(hint = "Rename", initialValue = layer.name) { newName -> vm.onLayerRenamed(layer.id, newName) }
        listItem(if (layer.isVisible) strings.editor.hideLayer else strings.editor.showLayer) { vm.onToggleVisibility(layer.id) }
        if (isGroup) {
            listItem("Ungroup") { vm.onUngroupLayer(layer.id) }
            listItem(strings.editor.delete) { vm.onDeleteGroup(layer.id) }
        } else {
            listItem(if (layer.alphaLock) "Alpha Lock ✓" else "Alpha Lock") { vm.onToggleAlphaLock(layer.id) }
            listItem(if (layer.clipToLayerBelow) "Clip to Below ✓" else "Clip to Below") { vm.onToggleClipToLayerBelow(layer.id) }
            listItem(strings.editor.duplicate) { vm.onLayerDuplicated(layer.id) }
            listItem("Merge Down") { vm.onMergeDown(layer.id) }
            listItem("Group with Above") { vm.onGroupWithLayerAbove(layer.id) }
            listItem("Clear") { vm.onLayerActivated(layer.id); vm.onClearLayer() }
            listItem(strings.editor.delete) { vm.onLayerRemoved(layer.id) }
        }
    }
}
