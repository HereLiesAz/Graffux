package com.hereliesaz.graffux

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource


import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.aznavrail.*
import com.hereliesaz.aznavrail.AzPopupKind
import com.hereliesaz.aznavrail.bottomsheet.rememberAzSheetController
import com.hereliesaz.aznavrail.rememberAzPopupController
import com.hereliesaz.aznavrail.model.*
import com.hereliesaz.graffitixr.common.model.ConstraintAnchor
import com.hereliesaz.graffitixr.common.model.LayoutAlign
import com.hereliesaz.graffitixr.common.model.LayoutDirection
import com.hereliesaz.graffitixr.common.model.BlendMode
import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LinkOps
import com.hereliesaz.graffitixr.common.model.supportsAlphaLock
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.ProjectFile
import com.hereliesaz.graffitixr.common.model.SelectionOp
import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.ShapeKind
import com.hereliesaz.graffitixr.common.model.SymmetryMode
import com.hereliesaz.graffitixr.common.model.TransformMode
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.design.GraffuxIcons
import com.hereliesaz.graffitixr.design.theme.AppStrings
import com.hereliesaz.graffitixr.design.theme.Cyan
import com.hereliesaz.graffitixr.design.theme.GraffitiXRTheme
import com.hereliesaz.graffitixr.design.theme.rememberAppStrings
import com.hereliesaz.graffitixr.feature.editor.AddContentDialog
import com.hereliesaz.graffitixr.feature.editor.AlignMode
import com.hereliesaz.graffitixr.feature.editor.AnimationWindow
import com.hereliesaz.graffitixr.feature.editor.ToolOptionsWindow
import com.hereliesaz.graffitixr.feature.editor.BackgroundColorDialog
import com.hereliesaz.graffitixr.data.brush.CustomBrush
import com.hereliesaz.graffitixr.feature.editor.BlendModePicker
import com.hereliesaz.graffitixr.feature.editor.BrushStudioWindow
import com.hereliesaz.graffitixr.feature.editor.CornerRadiusDialog
import com.hereliesaz.graffitixr.feature.editor.DocumentSizeDialog
import com.hereliesaz.graffitixr.feature.editor.EditorScreen
import com.hereliesaz.graffitixr.feature.editor.FigmaWindow
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
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
/**
 * Back to a plain `ComponentActivity`. It was briefly an `AppCompatActivity` so that
 * `AppCompatDelegate.setApplicationLocales` could recreate it and apply the Settings language
 * picker below API 33 — but the only translations in the tree were GraffitiXR's and have been
 * removed, so the picker is gone and with it the reason to carry an AppCompat delegate. The XML
 * theme stays an AppCompat descendant: something in the view stack still requires it (see
 * `themes.xml`), and that predates the locale work.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedImage = incomingImageUri(intent)
        val azpInstallUrl = azphaltInstallUrl(intent)
        setContent {
            // The app's own theme, not a bare `MaterialTheme`. Graffux is a dark app, but a bare
            // MaterialTheme() takes Material 3's default **light** scheme, whose `onSurface` is a
            // near-black neutral — so every piece of chrome that took its colour from the theme
            // rather than setting one explicitly drew dark on dark. The file-operations dropdown was
            // the worst of it: unreadable. GraffitiXRTheme supplies the dark scheme this UI was drawn
            // against plus the brand palette.
            GraffitiXRTheme {
                GraffuxApp(sharedImageUri = sharedImage, azphaltInstallUrl = azpInstallUrl)
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

/**
 * Extracts an azphalt package download URL from an `azphalt://install` deep link, or null if this
 * launch isn't one. The web storefront redirects here after a purchase; the URI's `url` query
 * parameter carries the HTTPS download URL for the `.azp` package.
 */
private fun azphaltInstallUrl(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_VIEW) return null
    val data = intent.data ?: return null
    if (data.scheme != "azphalt" || data.host != "install") return null
    return data.getQueryParameter("url")
}

/** Brush-size range the edge slider maps onto — matches EditorReducer's own clamp on SetBrushSize. */
private const val MIN_BRUSH_SIZE = 1f
private const val MAX_BRUSH_SIZE = 200f

/** Floor for brush opacity: a fully transparent brush paints nothing and just reads as a broken tool. */
private const val MIN_BRUSH_ALPHA = 0.05f

@Composable
private fun GraffuxApp(sharedImageUri: Uri?, azphaltInstallUrl: String? = null) {
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

    // Failures that used to be a Toast, or — worse — a logcat line. An AzPopup binds itself to a
    // rail item and can write back to it (a badge, a warning flag), and `show()` with no item id
    // lands on whichever item the user last touched, which is exactly right for something that
    // failed a moment after they pressed it. A Toast is gone in two seconds and says nothing about
    // which control it belonged to.
    val alerts = rememberAzPopupController()

    // The shortcuts sheet. HIDDEN parks it as a swipe strip along the bottom edge — a touch target
    // and nothing else, so the canvas keeps the screen — and a swipe or a tap brings it to PEEK,
    // which is the whole strip of tools. See ShortcutsSheet for why there are three of them.
    val shortcutsSheet = rememberAzSheetController(initial = AzSheetDetent.HIDDEN)
    val toolUsage by vm.toolUsage.collectAsState()
    val favoriteTools by vm.favoriteTools.collectAsState()

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
    var showLayerOptionsDialog by remember { mutableStateOf(false) }
    var showStoreDialog by remember { mutableStateOf(false) }
    var showStoreChooser by remember { mutableStateOf(false) }
    var showFigmaDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showToolOptions by remember { mutableStateOf(false) }
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

    LaunchedEffect(azphaltInstallUrl) {
        azphaltInstallUrl?.let { vm.installExtensionFromUrl(it) }
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

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val type = context.contentResolver.getType(it)
            val isImage = when {
                type?.startsWith("image/") == true -> true
                type == null || type == "application/octet-stream" -> {
                    val seg = it.lastPathSegment?.lowercase(java.util.Locale.ROOT) ?: ""
                    seg.endsWith(".png") || seg.endsWith(".jpg") || seg.endsWith(".jpeg") ||
                        seg.endsWith(".webp") || seg.endsWith(".gif") || seg.endsWith(".bmp")
                }
                else -> false
            }
            if (isImage) vm.onAddLayer(it) else vm.onImportDocument(it)
        }
    }

    val referencePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { referenceImageUri = it } }

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
                            alerts.show(
                                kind = AzPopupKind.WARNING,
                                title = "No way to reach the store",
                                message = "This device has no store app, no Play, and no browser " +
                                    "to open azphalt.store with. Extensions can still be installed " +
                                    "by hand from a file: Install brush… in the menu at the top right.",
                            )
                        }
                }
        }
    }

    /** Web: the storefront in a browser, the affordance every device already has. */
    val browseAzphaltWeb: () -> Unit = {
        showStoreChooser = false
        runCatching { context.startActivity(AzphaltStoreHandoff.webStoreIntent()) }
            .onFailure {
                alerts.show(
                    kind = AzPopupKind.WARNING,
                    title = "No browser",
                    message = "Nothing on this device can open azphalt.store.",
                )
            }
    }

    // The same set the rail items tag themselves with; azConfig is what actually makes the library
    // render them active, and it is declared here rather than in the item builder.
    val activeClassifiers = activeRailClassifiers(
        uiState, brushes, customBrushes,
        modelWindowOpen = showModelDialog, toolOptionsOpen = showToolOptions,
    )

    val navItemColor = Color.White

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
                focusColor = Color.White,
                // The third highlight (11.9). `active` answers "where am I", `secondary` answers
                // "what is true of this". They have to be different colours or the rail says the
                // same thing about a layer you are editing and a layer that merely comes along when
                // you drag it. Amber because it reads as a condition rather than a place.
                secondaryColor = Color(0xFFFFB300),
                // The border is what distinguishes "this opens something" from "this does something".
                // The rail's default is therefore borderless (AzNavRail's NONE_CIRCLE keeps the
                // CIRCLE footprint, so a borderless item still lines up with a bordered one) and the
                // only items that opt back into CIRCLE are hosts and the items that open a tool
                // window. Every leaf tool, toggle and picker inherits this.
                defaultShape = AzButtonShape.NONE_CIRCLE,
                headerIconShape = AzHeaderIconShape.CIRCLE,
                translucentBackground = Color.Black.copy(alpha = 0.85f)
            )
            azAbout(dedupeAbout = true)
            // The help overlay: a card per rail item, its label plus whatever RAIL_HELP says about
            // it. This is the app explaining itself without a scripted tutorial — every conditional
            // item, every piece of Procreate vocabulary, and the multi-finger gestures that have no
            // button at all are written down in one place the user can reach from the rail.
            //
            // `helpEnabled` is deliberately not passed: `azHelpRailItem` owns the overlay's
            // visibility, and setting the flag as well would mean two things deciding one boolean.
            // Pass it if help ever needs opening from somewhere other than its own item.
            azAdvanced(
                helpList = RAIL_HELP,
                onDismissHelp = { },
                onTitleClick = { _ ->
                    val shapes = SelectionShape.entries
                    val next = shapes[(shapes.indexOf(uiState.selectionShape) + 1) % shapes.size]
                    vm.onSetSelectionShape(next)
                },
            )
            azConfig(
                // What the rail should render as active. Without this only a transient last-tap
                // highlights, which is not a state — it is a memory of a gesture.
                activeClassifiers = activeClassifiers,
                // State the rail could not show at all before 11.9 gave it a second colour: which
                // layers travel with the one you are moving, and whether a selection is quietly
                // confining every raster tool. See secondaryRailClassifiers.
                secondaryClassifiers = secondaryRailClassifiers(uiState),
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
                installedExtensionCount = allInstalledExtensions.size,
                strings = strings,
                navItemColor = navItemColor,
                activeColor = activeRailColor, // Pass down to DSL builder
                screenCenter = screenCenter,
                onBlendMode = { showBlendDialog = true },
                onAddClicked = { showAddDialog = true },
                onEditClicked = { showLayerOptionsDialog = true },
                onModelClicked = { showModelDialog = true },
                modelWindowOpen = showModelDialog,
                onToolOptionsClicked = { showToolOptions = !showToolOptions },
                toolOptionsOpen = showToolOptions,
            )

            // The tools you actually reach for, one swipe from the bottom edge. The rail holds every
            // tool this app has, which is what makes it complete and also what makes it somewhere
            // you have to navigate; this is the flat row of the ones that matter to you.
            //
            // Hidden while the UI is hidden for a clean look at the artwork, like every other piece
            // of chrome — a four-finger tap should leave nothing on screen but the painting.
            if (!uiState.hideUiForCapture) {
                azBottomSheet(controller = shortcutsSheet) {
                    ShortcutsSheet(
                        activeTool = uiState.activeTool,
                        usage = toolUsage,
                        favorites = favoriteTools,
                        onPickTool = { tool ->
                            // Same rule as the rail's own tool items: tapping the tool already in
                            // hand puts it down. Two surfaces disagreeing about what a second tap
                            // means would be worse than either behaviour on its own.
                            vm.setActiveTool(if (uiState.activeTool == tool) Tool.NONE else tool)
                        },
                        onToggleFavorite = { tool -> vm.onToggleFavoriteTool(tool) },
                        modifier = Modifier.navigationBarsPadding().padding(vertical = 8.dp),
                    )
                }
            }

            // Registered on the host so a popup raised from anywhere — a menu action, a coroutine,
            // a callback off the main flow — can bind itself to the rail item that caused it. No
            // body: the built-in title/message/OK panel is what these are.
            azPopup(alerts)

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
                    // to say so once — so it was said twenty-one times over. An entry added without
                    // it falls back to Color.Unspecified rather than to the other twenty, which is a
                    // silent way to end up with one unreadable line. Said once here instead.
                    fun menuItem(text: String, onClick: () -> Unit) =
                        azItem(color = menuItemColor, text = text, onClick = onClick)
                    menuItem(text = strings.nav.new, onClick = { vm.createNewProject() })
                    menuItem(text = strings.nav.open, onClick = { vm.refreshOpenScreen(); showOpenDialog = true })
                    menuItem(text = "Import", onClick = { importPicker.launch(arrayOf("*/*")) })
                    menuItem(text = "Import from Figma…", onClick = { showFigmaDialog = true })
                    menuItem(text = "Reference", onClick = { showReferenceWindow = true })
                    menuItem(text = strings.nav.save, onClick = { showSaveDialog = true })
                    menuItem(text = strings.nav.export, onClick = { vm.exportImage() })
                    menuItem(text = "Export for Figma", onClick = { vm.exportForFigma() })
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
                                // Was a logcat line and nothing else: Share appeared to do nothing
                                // at all, with no way for the user to tell a failure from a slow
                                // export.
                                android.util.Log.w("Graffux", "Share failed", t)
                                alerts.show(
                                    kind = AzPopupKind.WARNING,
                                    title = "Couldn't share",
                                    message = t.message ?: "No app accepted the image.",
                                )
                            }
                        }
                    })
                    azDivider()
                    menuItem(text = "Extensions", onClick = { showStoreDialog = true })
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
                                onAlign = { mode -> vm.alignActiveLayer(mode) },
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
                            onEditStart = { vm.onLayerEditStart() },
                            onEditCommit = { vm.onLayerEditEnd() },
                            // Only for a layer that actually has children to arrange.
                            autoLayoutGap = uiState.layers
                                .firstOrNull { l -> l.id == overlay.id && uiState.layers.any { it.parentId == l.id } }
                                ?.autoLayout?.gap,
                            onGapChange = { gap ->
                                val current = uiState.layers.first { it.id == overlay.id }.autoLayout
                                vm.onSetAutoLayout(current.copy(gap = gap))
                            },
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

                // Animation Assist. Opening the window is what turns animation mode on, and closing
                // it turns it off — the mode and the panel were two separate rail buttons before,
                // which meant the panel could be shut with the mode still running (onion skins still
                // drawn, playback still going) and nothing on screen saying so.
                if (uiState.isAnimationMode) {
                    AnimationWindow(
                        // vm.animationFrameCount(), not `layers.count { it.parentId == null }`.
                        // Every other part of Animation Assist counts frames through
                        // AnimationFrames.topLevelFrames, which is the layer TREE's roots — and the
                        // tree also promotes layers whose parent id names nothing (and, now, members
                        // of a parent cycle) to roots. Counting the flat list instead undercounted
                        // exactly those, so the window's frame total, its step-through range and the
                        // onion-skin neighbours disagreed with each other on any document with a
                        // dangling parent id.
                        frameCount = vm.animationFrameCount(),
                        activeFrameIndex = uiState.activeFrameIndex,
                        isPlaying = uiState.isAnimationPlaying,
                        onionSkinEnabled = uiState.onionSkinEnabled,
                        onionSkinFrameCount = uiState.onionSkinFrameCount,
                        loopMode = uiState.animationLoopMode,
                        frameDurationMs = uiState.animationFrameDurationMs,
                        isTimeLapseRecording = uiState.isTimeLapseRecording,
                        onTogglePlayback = { vm.onToggleAnimationPlayback() },
                        onPreviousFrame = { vm.onPreviousFrame() },
                        onNextFrame = { vm.onNextFrame() },
                        onAddFrame = { vm.onAddFrame() },
                        onToggleOnionSkin = { vm.onToggleOnionSkin() },
                        onSetOnionSkinFrameCount = { vm.onSetOnionSkinFrameCount(it) },
                        onSetLoopMode = { vm.onSetAnimationLoopMode(it) },
                        onSetFrameDurationMs = { vm.onSetAnimationFrameDurationMs(it) },
                        onExport = { vm.exportAnimation() },
                        onToggleTimeLapse = { vm.onToggleTimeLapseRecording() },
                        onDismiss = { vm.onToggleAnimationMode() },
                    )
                }

                if (showToolOptions) {
                    ToolOptionsWindow(
                        stabilizerLevel = uiState.stabilizerLevel,
                        onSetStabilizerLevel = { vm.setStabilizerLevel(it) },
                        // Both are null unless they mean something right now — the window shows the
                        // dials the tool in hand actually has, never a dead control.
                        magicWandTolerance =
                            uiState.magicWandTolerance.takeIf { uiState.selectionShape == SelectionShape.AUTOMATIC },
                        onSetMagicWandTolerance = { vm.onSetMagicWandTolerance(it) },
                        selectionFeatherPx = uiState.selection?.featherPx,
                        onSetSelectionFeather = { vm.onSetSelectionFeather(it) },
                        brushFlow = uiState.brushFlow.takeIf { uiState.activeBrushName != null },
                        onSetBrushFlow = { vm.setBrushFlow(it) },
                        onDismiss = { showToolOptions = false },
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
                        onDocumentSize = { showSettings = false; showDocDialog = true },
                        onBackground = { showSettings = false; showBgDialog = true },
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
                    if (drag.y != 0f) {
                        val cur = vm.uiState.value.brushSize
                        // Clamped to the brush's real range, NOT to this pad's rendered width. The
                        // pad used to cap the value at its own measured size, which was harmless
                        // only while a rail slider could still reach 200 px; it is the sole size
                        // control now, so that ceiling would silently become the maximum brush in
                        // the app — and a pad narrower than the current size would snap it down on
                        // the first touch. The width still bounds the *preview* below, which is all
                        // it was ever entitled to bound.
                        vm.setBrushSize((cur - drag.y * 0.5f).coerceIn(MIN_BRUSH_SIZE, MAX_BRUSH_SIZE))
                    }
                    if (drag.x != 0f) {
                        // Hardness, always — never flow. The axis used to mean feathering for the
                        // round brush and stamp-brush flow for anything else, so the same gesture
                        // did two unrelated things depending on state you could not see from the
                        // pad, and the preview had to show opacity to reflect it. Flow is a stamp
                        // brush's own parameter and lives in Tool Options with the other dials.
                        val cur = vm.uiState.value.brushFeathering
                        vm.setBrushFeathering((cur + drag.x * 0.005f).coerceIn(0f, 1f))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val feather = state.brushFeathering.coerceIn(0f, 1f)
        // The preview is the brush's actual footprint at its actual size, so what you see while
        // dragging is what the next stroke puts down.
        //
        // **Softness, drawn as softness.** A hard brush is a disc with a hard edge; a soft one fades
        // out over its radius; and the horizontal drag moves continuously between the two, which is
        // exactly what a radial gradient expresses. This used to be a flat 30%-alpha ring around a
        // shrunken solid core — two hard edges standing in for one soft one, which reads as a
        // translucent brush rather than a soft one. The pad ended up demonstrating *opacity*, the
        // one property it does not control.
        //
        // Nothing here is ever drawn translucent. Alpha is opacity, the pad does not set opacity,
        // and a preview that dims is a preview that lies about which dial you are on.
        Canvas(Modifier.fillMaxSize()) {
            val maxR = maxOf(minOf(size.width, size.height) / 2f, 1f)
            val radius = (state.brushSize / 2f).coerceIn(1.5f, maxR)
            // Where the solid core ends and the falloff begins: the whole radius when hard, the
            // centre point when fully soft.
            val core = (1f - feather).coerceIn(0f, 1f)
            drawCircle(
                brush = Brush.radialGradient(
                    // Two stops at the same offset give the hard-edged case a genuine hard edge
                    // rather than a one-pixel ramp.
                    0f to Cyan,
                    core to Cyan,
                    1f to Cyan.copy(alpha = 0f),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
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

// The Tool -> (id, label, glyph) catalogue moved to ToolCatalog.kt, where the shortcuts sheet can
// read the same one the rail does. TOOL_IDS is derived from it there.


/**
 * The tool groups that live inside a nested rail of their own rather than the top-level strip.
 *
 * Photoshop's palette, grouped the way Photoshop groups it — vector, retouching, and focus/tone —
 * rather than Procreate's flat strip of five. The two arrangements were being run at once: a strip
 * that copied Procreate's shape while carrying Photoshop's whole tool count, so it was thirteen
 * items long and none of the relationships between them were visible.
 *
 * Declared here rather than inline in the rail because each host has to light up when one of *its*
 * members is the active tool — otherwise picking a tool and closing the rail leaves nothing on
 * screen saying which one is armed — and that condition lives in [activeRailClassifiers], a
 * different function. One list per group, read by both.
 *
 * A tool appears in at most one group; nothing checks that, but the rail would draw it twice and
 * two hosts would light up at once, which is loud enough to notice immediately.
 */
private val SELECT_TOOLS: List<Tool> = listOf(Tool.SELECT)
private val VECTOR_TOOLS: List<Tool> = listOf(Tool.PEN)
private val RETOUCH_TOOLS: List<Tool> = listOf(Tool.HEAL, Tool.CLONE)
private val FOCUS_TOOLS: List<Tool> = listOf(
    Tool.BLUR, Tool.SHARPEN, Tool.SMUDGE, Tool.LIQUIFY, Tool.DODGE, Tool.BURN, Tool.COLOR,
)

/** Each group's nested-rail id, which is also its classifier. */
private const val SELECT_ID = "grp.select"
private const val VECTOR_ID = "grp.vector"
private const val RETOUCH_ID = "grp.retouch"
private const val FOCUS_ID = "grp.focus"

/**
 * Every rail item that is currently *active*, by item id.
 *
 * AzNavRail highlights an item when one of its `classifiers` appears in
 * `azConfig(activeClassifiers = …)`, which gives a filled container rather than only a tinted icon —
 * a much louder "you are here" than colour alone. Each stateful item tags itself with its own id and
 * this decides, in one place, which of those ids are lit.
 *
 * `internal` rather than private so it can be unit-tested: it is the only substantial piece of pure
 * logic in `:app`, it decides what the whole rail looks like, and every one of its conditions is a
 * silent failure when wrong — a mistyped or missing classifier does not break anything, the item
 * just never lights up.
 *
 * One place is the point. Every one of these conditions previously sat inline on the item that used
 * it, restated 33 times; the same duplication in the extensions panel had already drifted into the
 * install toast contradicting the panel it pointed at. Both the classifier and the item's colour now
 * read from this set, so the two cannot disagree about what is active.
 */
internal fun activeRailClassifiers(
    uiState: EditorUiState,
    brushes: List<Pair<String, String>>,
    customBrushes: List<CustomBrush>,
    // Whether the 3D window is open. Passed in rather than read off [EditorUiState] because that is
    // where it lives — the window's visibility is composable state in `GraffuxApp`, like every other
    // floating window's. It is here at all because an item that opens a window has to light up while
    // that window is open, or the rail says nothing about a panel sitting over the artwork.
    modelWindowOpen: Boolean,
    /** Whether the Tool Options window is open — same reasoning as [modelWindowOpen]. */
    toolOptionsOpen: Boolean,
): Set<String> = buildSet {
    // The active tool. One of these at a time, by construction; Tool.NONE has no item, because it is
    // the absence of one.
    TOOL_IDS[uiState.activeTool]?.let { add(it) }
    // …and, when it is one of the tools that lives in a nested rail, the host that holds it. The
    // member's own item is out of sight whenever that rail is closed, so without this the rail would
    // show no armed tool at all while one is armed.
    if (uiState.activeTool in VECTOR_TOOLS) add(VECTOR_ID)
    if (uiState.activeTool in RETOUCH_TOOLS) add(RETOUCH_ID)
    if (uiState.activeTool in FOCUS_TOOLS) add(FOCUS_ID)
    if (uiState.activeTool in SELECT_TOOLS) add(SELECT_ID)
    // Node editing is a vector mode rather than a Tool, so it lights its host separately.
    if (uiState.pathEditLayerId != null) add(VECTOR_ID)
    if (uiState.symmetryMode != SymmetryMode.NONE) add("grp.symmetryMode")
    // Clone's second step. It lights once the tool is aimed, which is the state that decides whether
    // the next stroke copies anything — the item had declared this classifier since it was written
    // and nothing ever added it, so it faked the state in its label instead.

    // Modes and toggles that stay on until turned off.
    if (uiState.symmetryMode != SymmetryMode.NONE) add("tool.symmetry")
    if (uiState.wrapAroundMode) add("tool.wraparound")
    // Time-lapse lives inside the Animation window now, so the thing that lights up for it is the
    // window's own item — otherwise a recording in progress would show nowhere in the rail.
    if (uiState.isAnimationMode || uiState.isTimeLapseRecording) add("tool.animation")
    if (modelWindowOpen) add("tool.model")
    if (toolOptionsOpen) add("tool.options")
    if (uiState.pathEditLayerId != null) add("tool.nodeEdit")
    if (uiState.activeTool == Tool.CLONE && uiState.cloneSource != null) add("tool.cloneSource")
    if (uiState.selection?.inverted == true) add("tool.selectInvert")
    if (uiState.quickMenuAt != null) add("tool.quick")
    if (uiState.showColorPicker) add("tool.color")

    // Which panel is open — the rail item that opened it stays lit while it is.
    //
    // Only two of the five panels have a rail item left to light. Adjust, Balance and Layer Options
    // are opened from a layer's hidden menu now, and a hidden-menu row has no persistent state to
    // show: the menu is closed by the time the panel is up. The panel itself is the indication.
    when (uiState.activePanel) {
        EditorPanel.TRANSFORM -> add("adj.transform")
        EditorPanel.EXTENSIONS -> add("adj.extensions")
        else -> Unit
    }

    // (No animation entries. Play/pause, onion skin and the loop mode are controls inside the
    // Animation window now, and a window draws its own selected state — a classifier for an item
    // that no longer exists lights nothing and only survives to confuse the next reader.)

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
    add("selectOp.${uiState.selectionOp.name}")
    add("transform.${uiState.transformMode.name}")
}

/**
 * Every rail item that is currently **secondary** — a condition that is true of it, as opposed to
 * [activeRailClassifiers]'s "this is where you are".
 *
 * AzNavRail 11.9 added a third highlight for exactly this distinction, and two pieces of editor
 * state had no way to show at all until it did:
 *
 *  * **Linked layers.** Linking makes a contiguous run of layers move, scale and rotate as one, and
 *    the machinery for it has always worked — but nothing on screen said which layers were in the
 *    run, so you dragged one and two others came with it for no visible reason. The active layer
 *    keeps the active colour; its peers take the secondary one.
 *  * **A live selection.** A region confines every raster tool until it is cleared, and it is
 *    invisible whenever the marching ants are off-screen or the Selection group is closed. This is
 *    the rail admitting that the next stroke will be clipped.
 *
 * Kept separate from [activeRailClassifiers] rather than folded into it, because the library takes
 * them as two sets and because they answer two different questions — an item can legitimately be in
 * both, and `focus` beats `active` beats `secondary` when they collide.
 */
internal fun secondaryRailClassifiers(uiState: EditorUiState): Set<String> = buildSet {
    LinkOps.linkedPeers(uiState.layers, uiState.activeLayerId).forEach { add("layer.$it") }
    if (uiState.selection != null) add(SELECT_ID)
}

private fun AzNavHostScope.ConfigureRailItems(
    vm: EditorViewModel,
    uiState: EditorUiState,
    brushes: List<Pair<String, String>>,
    customBrushes: List<CustomBrush>,
    /** How many azphalt extensions are installed — badged on the Run Extension item. */
    installedExtensionCount: Int,
    strings: AppStrings,
    navItemColor: Color,
    activeColor: Color,
    // Screen centre in px, resolved by the caller: this builder is not @Composable, so it can't
    // read LocalConfiguration/LocalDensity itself (same reason the colours above are passed in).
    screenCenter: Offset,
    onBlendMode: () -> Unit,
    onAddClicked: () -> Unit,
    onEditClicked: () -> Unit,
    onModelClicked: () -> Unit,
    modelWindowOpen: Boolean,
    onToolOptionsClicked: () -> Unit,
    toolOptionsOpen: Boolean,
) {
    // Computed once, read by every stateful item below for both its classifier and its colour.
    val activeIds = activeRailClassifiers(
        uiState, brushes, customBrushes,
        modelWindowOpen = modelWindowOpen, toolOptionsOpen = toolOptionsOpen,
    )
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

    /**
     * A group header: the bordered circle every host in this rail shares.
     *
     * The id is the classifier, as it is for every other builder here. It used to default to
     * `emptySet()`, and five of the six hosts took that default — so `railColor(id)` inside this
     * function was computing an accent that the library could never apply, because AzNavRail marks
     * an item active by matching its *classifiers* against the active set, and an empty set matches
     * nothing. An armed Select tool showed nothing at all once its group was closed.
     */
    fun hostItem(id: String, text: String, content: Any?, classifiers: Set<String> = setOf(id)) =
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
            content = content, color = railColor(id), onClick = onClick,
        )
    // No `menuText` here any more. It rendered a "$text ✓" variant for the side drawer, and this app
    // sets `azConfig(noMenu = true)` — the drawer does not exist. The library reads menuText only
    // from its drawer surfaces, so every one of these strings was built and discarded, while the
    // comment beside them described a list the user cannot open. The tick is carried by the item's
    // own active state, which is the thing that is actually on screen.

    /** One tool item, built against whichever rail scope is the current receiver. */
    fun AzNavRailScope.nestedTool(tool: Tool, label: String, icon: Int) {
        val id = TOOL_IDS.getValue(tool)
        azRailItem(
            id = id, classifiers = setOf(id), text = label, content = icon,
            color = railColor(id), shape = AzButtonShape.NONE_SQUARE,
            fillColor = railColor(id),
            onClick = { vm.setActiveTool(if (uiState.activeTool == tool) Tool.NONE else tool) },
        )
    }

    // ── Procreate's top-left group: Adjustments · Selection · Transform ────────────────────────────
    // Adjustments is `grp.adjust`, declared further down as a floating host — Procreate gives it a
    // panel, not a strip slot. Selection and Transform lead the strip.

    // Selection (the "S"): one control holding the selection *modes* and then the actions you perform
    // on a selection. These were four flat rail entries — Select, Quick, Invert, Deselect — sitting
    // among the paint tools, which is neither where Procreate puts them nor what they are: three of
    // the four do nothing at all until a selection exists.
    // SelectAll for the group, not SelectLasso. The lasso glyph appeared three times inside this one
    // group — on the host, on the Select tool, and on the Freehand shape — so opening it showed three
    // identical pictures. The lasso now means exactly one thing: the freehand shape, further down.
    azNestedRail(
        id = SELECT_ID, classifiers = setOf(SELECT_ID), text = "Selection", content = GraffuxIcons.SelectAll,
        color = railColor(SELECT_ID), shape = AzButtonShape.CIRCLE,
        reflectSelectionInParent = true,
    ) {
        nestedTool(Tool.SELECT, "Select", GraffuxIcons.SelectSubject)
        SelectionShape.entries.forEach { mode ->
            val id = "selectShape.${mode.name}"
            azRailItem(
                id = id, classifiers = setOf(id),
                text = mode.label,
                content = when (mode) {
                    SelectionShape.FREEHAND -> GraffuxIcons.SelectLasso
                    SelectionShape.RECTANGLE -> GraffuxIcons.SelectRect
                    SelectionShape.ELLIPSE -> GraffuxIcons.SelectEllipse
                    SelectionShape.AUTOMATIC -> GraffuxIcons.SelectWand
                },
                color = railColor(id),
                shape = AzButtonShape.NONE_SQUARE,
                fillColor = railColor(id),
                onClick = { vm.onSetSelectionShape(mode) },
            )
        }
        SelectionOp.entries.forEach { mode ->
            val id = "selectOp.${mode.name}"
            azRailItem(
                id = id, classifiers = setOf(id),
                text = mode.label,
                content = when (mode) {
                    SelectionOp.NEW -> GraffuxIcons.SelectAll
                    SelectionOp.ADD -> GraffuxIcons.SelectAdd
                    SelectionOp.REMOVE -> GraffuxIcons.SelectSubtract
                },
                color = railColor(id),
                shape = AzButtonShape.NONE_SQUARE,
                fillColor = railColor(id),
                onClick = { vm.onSetSelectionOp(mode) },
            )
        }
        azRailItem(
            id = "tool.quick", classifiers = setOf("tool.quick"), text = "Quick",
            content = GraffuxIcons.SelectQuick, color = railColor("tool.quick"),
            shape = AzButtonShape.NONE_SQUARE, fillColor = railColor("tool.quick"),
            onClick = { vm.onOpenQuickMenu(screenCenter) },
        )
        azRailItem(
            id = "tool.selectInvert", classifiers = setOf("tool.selectInvert"), text = "Invert",
            content = GraffuxIcons.SelectInvert, color = railColor("tool.selectInvert"),
            shape = AzButtonShape.NONE_SQUARE, fillColor = railColor("tool.selectInvert"),
            onClick = { vm.onInvertSelection() },
        )
        azRailItem(
            id = "tool.deselect", text = "Deselect",
            content = GraffuxIcons.SelectNone, color = navItemColor,
            shape = AzButtonShape.NONE_SQUARE,
            onClick = { vm.onClearSelection() },
        )
        if (uiState.selection != null) {
            azRailItem(id = "sel.fill", text = "Colour Fill", content = GraffuxIcons.LayerFill, color = navItemColor, shape = AzButtonShape.NONE_SQUARE, onClick = { vm.onColorFillSelection() })
            azRailItem(id = "sel.copy", text = "Copy", content = GraffuxIcons.LayerDuplicate, color = navItemColor, shape = AzButtonShape.NONE_SQUARE, onClick = { vm.onCopySelection() })
            azRailItem(id = "sel.cut", text = "Cut", content = GraffuxIcons.PathKnife, color = navItemColor, shape = AzButtonShape.NONE_SQUARE, onClick = { vm.onCutSelection() })
            azRailItem(id = "sel.save", text = "Save Selection", content = GraffuxIcons.DocumentSave, color = navItemColor, shape = AzButtonShape.NONE_SQUARE, onClick = { vm.onSaveSelection(vm.nextSelectionName()) })
        }
        if (uiState.hasClipboard) {
            azRailItem(id = "sel.paste", text = "Paste", content = GraffuxIcons.LayerAdd, color = navItemColor, shape = AzButtonShape.NONE_SQUARE, onClick = { vm.onPasteSelection() })
        }
        uiState.savedSelections.forEach { saved ->
            azRailItem(
                id = "sel.load.${saved.name}", text = saved.name,
                content = GraffuxIcons.SelectReselect, color = navItemColor,
                shape = AzButtonShape.NONE_SQUARE,
                onClick = { vm.onLoadSelection(saved.name) },
            )
        }
        if (uiState.savedSelections.isNotEmpty()) {
            azNestedRail(
                id = "sel.manage", text = "Forget a Selection",
                content = GraffuxIcons.LayerDelete, color = navItemColor,
                shape = AzButtonShape.NONE_SQUARE,
            ) {
                uiState.savedSelections.forEach { saved ->
                    azRailItem(
                        id = "sel.forget.${saved.name}", text = saved.name,
                        content = GraffuxIcons.SelectNone, color = navItemColor,
                        shape = AzButtonShape.NONE_SQUARE,
                        onClick = { vm.onDeleteSavedSelection(saved.name) },
                    )
                }
            }
        }
    }

    // Transform is its own control in Procreate's top bar, beside Adjustments and Selection — not an
    // item inside Adjustments, which is where it was. It keeps `adj.transform` as its id, so the
    // classifier set and the panel it opens are unchanged.
    // Move, not SelectTransform: this item sat directly above the Freeform mode carrying the very
    // same glyph, so the group header and its first member were indistinguishable.
    stateItem("adj.transform", "Transform", GraffuxIcons.Move) { vm.onTransformClicked() }
    // Procreate's three transform modes. Freeform moves the layer; Distort and Warp bend it, and
    // are the same grid at two resolutions — four corners for a perspective, sixteen for a mesh.
    TransformMode.entries.forEach { mode ->
        stateItem(
            id = "transform.${mode.name}", text = mode.label,
            content = when (mode) {
                TransformMode.FREEFORM -> GraffuxIcons.SelectTransform
                TransformMode.DISTORT -> GraffuxIcons.PerspectiveTransform
                TransformMode.WARP -> GraffuxIcons.PuppetWarp
            },
        ) { vm.onSetTransformMode(mode) }
    }
    // Only while a bend is in progress: they are the two ways out of it, and mean nothing otherwise.
    if (uiState.transformMode != TransformMode.FREEFORM) {
        azRailItem(
            id = "transform.apply", text = "Apply",
            content = GraffuxIcons.Success, color = activeColor,
            onClick = { vm.onApplyWarp() },
        )
        azRailItem(
            id = "transform.cancel", text = "Cancel",
            content = GraffuxIcons.Close, color = navItemColor,
            onClick = { vm.onCancelWarp() },
        )
    }

    // Procreate's tool strip: a flat row of the tools you paint with, not an accordion you have to
    // open first, drawn from the Graffux set (`GraffuxIcons`, generated by branding/icons/build.py)
    // rather than a general-purpose library — a broom stood in for the eraser and a squiggle for
    // smudge, because a library built for dashboards has no drawing for either.
    // Every item gets its own glyph — the old rail drew Icons.Filled.Brush for the group,
    // the brush tool, the round brush AND every installed brush, and the same pencil for Pen and Edit,
    // so nothing in it was identifiable at a glance.
    toolItem(Tool.BRUSH, uiState.activeBrushName ?: navStrings.brush, GraffuxIcons.Brush)
    toolItem(Tool.ERASER, "Eraser", GraffuxIcons.Eraser)
    // The brush size / hardness pad, in the strip beside the brush it sizes.
    //
    // Drag up/down for size, across for hardness — one gesture for both, which is why this is a pad
    // and not two sliders. It used to hang off an unattached host of its own, so the control reached
    // for most often sat in a different part of the screen from the brush it belongs to.
    //
    // NONE, not NONE_CIRCLE: the content is a drag pad rather than a glyph, and a circular footprint
    // gives the across-axis nothing to travel along. It is the one item in the strip that is wider
    // than it is tall, and the shape is carrying layout rather than affordance.
    azRailItem(
        id = "adj.brush", text = navStrings.brush, shape = AzButtonShape.NONE,
        color = navItemColor, content = AzComposableContent { BrushSizePad(vm) },
    )

    // The colour item IS the current colour, the way Procreate's swatch is, rather than a generic
    // palette glyph that says nothing about what you're about to paint with. Layers is the other
    // constant and has left this strip entirely — it is an unattached floating host, the rail's
    // nearest equivalent to Procreate's Layers panel.
    stateItem(
        id = "tool.color", text = navStrings.color, content = uiState.activeColor,
        // Bordered: it opens the colour picker rather than selecting a tool.
        shape = AzButtonShape.CIRCLE,
    ) { vm.onColorClicked() }

    // Procreate's ColorDrop: tap the canvas to flood-fill with the active colour.
    toolItem(Tool.FILL, "Fill", GraffuxIcons.Colordrop)

    toolItem(Tool.TEXT, "Text", GraffuxIcons.Type)

    // ── The three tool groups ─────────────────────────────────────────────────────────────────────
    // Everything below the four constants above is Photoshop's inheritance rather than Procreate's,
    // and it is grouped the way Photoshop groups it: vector, retouching, focus and tone.
    //
    // Two arrangements were being run at once before this. The strip copied Procreate's *shape* — a
    // flat row you don't have to open — while carrying Photoshop's whole tool count, so it ran to
    // thirteen items when the thing it was imitating shows five, and none of the relationships
    // between them were visible: Dodge and Burn are one idea in two directions, Blur and Sharpen
    // another, Heal and Clone both sample from elsewhere on the layer. Flat, they read as thirteen
    // unrelated buttons. Grouped, the strip is short and each group is a sentence.
    //
    // Every tool keeps its id, its classifier and its behaviour — only where you reach it moves, so
    // nothing about a recorded stroke, a co-op peer or a saved project changes.
    //
    // Vector. Pen draws a real PATH shape rather than pixels, and node editing operates on the shape
    // it drew — the tool and the way you correct its output, which is why they sit together rather
    // than the editing controls living twenty items further down the rail as they used to.
    azNestedRail(
        id = VECTOR_ID, classifiers = setOf(VECTOR_ID), text = "Vector", content = GraffuxIcons.PenInk,
        color = railColor(VECTOR_ID), shape = AzButtonShape.CIRCLE,
        reflectSelectionInParent = true,
    ) {
        nestedTool(Tool.PEN, "Pen", GraffuxIcons.PenInk)

        // Node editing is offered only for a layer that actually has an editable path, so it isn't a
        // permanently dead item on a raster document.
        val active = uiState.layers.firstOrNull { it.id == uiState.activeLayerId }
        val editable = active?.shapes?.singleOrNull()?.kind == ShapeKind.PATH
        if (editable || uiState.pathEditLayerId != null) {
            azRailItem(
                id = "tool.nodeEdit", classifiers = setOf("tool.nodeEdit"), text = "Edit Nodes",
                content = GraffuxIcons.PathSelectDirect, color = railColor("tool.nodeEdit"),
                shape = AzButtonShape.NONE_SQUARE, fillColor = railColor("tool.nodeEdit"),
                onClick = { vm.onToggleActivePathEdit() },
            )
        }
        if (uiState.pathEditLayerId != null) {
            azRailItem(
                id = "tool.nodeClose", text = "Close Path",
                content = GraffuxIcons.PathClose, color = navItemColor,
                shape = AzButtonShape.NONE_SQUARE,
                onClick = { vm.onTogglePathClosed() },
            )
            uiState.selectedNodeIndex?.let { index ->
                azRailItem(
                    id = "tool.nodeDelete", text = "Delete Node",
                    content = GraffuxIcons.PenRemove, color = navItemColor,
                    shape = AzButtonShape.NONE_SQUARE,
                    onClick = { vm.onDeletePathNode(index) },
                )
            }
        }
    }

    // Retouching. Both of these repair by sampling pixels from somewhere else on the same layer —
    // Heal blends what it samples into the surrounding tone, Clone copies it verbatim — which is
    // exactly the distinction the group makes visible by putting them next to each other.
    azNestedRail(
        id = RETOUCH_ID, classifiers = setOf(RETOUCH_ID), text = "Retouch", content = GraffuxIcons.Heal,
        color = railColor(RETOUCH_ID), shape = AzButtonShape.CIRCLE,
        reflectSelectionInParent = true,
    ) {
        nestedTool(Tool.HEAL, "Heal", GraffuxIcons.Heal)
        nestedTool(Tool.CLONE, "Clone", GraffuxIcons.Stamp)
        // Clone is aimed by tapping the canvas once before painting, which is why it has a second
        // step no other tool here has. Shown only while it's the active tool: aiming a tool you
        // aren't holding means nothing.
        if (uiState.activeTool == Tool.CLONE) {
            azRailItem(
                id = "tool.cloneSource", classifiers = setOf("tool.cloneSource"),
                text = if (uiState.cloneSource == null) "Tap to aim" else "Re-aim",
                content = GraffuxIcons.FilterCloneSource, color = railColor("tool.cloneSource"),
                shape = AzButtonShape.NONE_SQUARE, fillColor = railColor("tool.cloneSource"),
                onClick = { vm.onResetCloneSource() },
            )
        }
    }

    // Focus and tone. Everything here reworks pixels that are already on the layer rather than
    // adding new marks, which is the line the group is drawn along — and inside it the members pair
    // off, which is the reason for putting them in this order rather than alphabetically:
    //
    //   Blur / Sharpen    remove local contrast, and add it back
    //   Smudge / Liquify  carry pixels along a drag, at brush scale and at layer scale
    //   Dodge / Burn      lighten, and darken
    //   Colorize          replaces hue while leaving luminance alone
    //
    // Liquify used to sit in the floating Adjust palette on the reasoning that Procreate files it
    // under Adjustments. That is true of Procreate and wrong here: this Liquify is driven by
    // dragging a brush across the canvas, so it is the same gesture as Smudge with a much larger
    // radius, and a user who wants one is choosing between the two.
    azNestedRail(
        id = FOCUS_ID, classifiers = setOf(FOCUS_ID), text = "Focus & Tone", content = GraffuxIcons.Dodge,
        color = railColor(FOCUS_ID), shape = AzButtonShape.CIRCLE,
        reflectSelectionInParent = true,
    ) {
        // Called "Blur", because that is what it does. It was labelled "Smudge" with a smudge glyph
        // while `ImageProcessor` ran an actual box blur under the stroke — the one name in the rail
        // that promised a tool the app didn't have. It does now, two items down, and the two are not
        // substitutes: a blur averages in place, a smudge carries colour and leaves a tail.
        nestedTool(Tool.BLUR, "Blur", GraffuxIcons.Blur)
        nestedTool(Tool.SHARPEN, "Sharpen", GraffuxIcons.Sharpen)
        nestedTool(Tool.SMUDGE, "Smudge", GraffuxIcons.Smudge)
        nestedTool(Tool.LIQUIFY, "Liquify", GraffuxIcons.Liquify)
        nestedTool(Tool.DODGE, navStrings.dodge, GraffuxIcons.Dodge)
        nestedTool(Tool.BURN, navStrings.burn, GraffuxIcons.Burn)
        nestedTool(Tool.COLOR, "Colorize", GraffuxIcons.ColorDisc)
    }

    // Symmetry guide: strokes mirror across one or more axes while it's on. This is the quick
    // on/off; the picker below reaches every mode, including turning it off from there too.
    modeItem("tool.symmetry", "Symmetry", GraffuxIcons.Symmetry) { vm.onToggleSymmetry() }
    // No Layout, Styles or Components hosts in the rail. All three acted on "the active layer,
    // wherever it is" — three more places you had to select a layer first and then go looking, and
    // three more groups in a strip that is meant to hold the tools you reach for mid-stroke. They
    // are rows in each layer's own hidden menu now, on the layer whose menu you opened, gated on
    // that layer actually having children to arrange, a parent to be constrained within, or a shape
    // or text to lift a token from. See renderLayerLayoutMenu / renderLayerStyleMenu /
    // renderLayerComponentMenu.

    // (Vector node editing lives in the "Vector" nested rail above, next to the Pen that produces
    // the paths it edits.)
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
    // ── The two workspaces ────────────────────────────────────────────────────────────────────────
    // Animation and 3D are not tools you reach for mid-stroke; they are surfaces you set up and then
    // work inside, and both have far more state than a rail item can show. Each is one item here
    // that opens one floating window — the same window every other panel in the app uses, so it
    // drags where you want it, collapses, and never dims the artwork underneath.
    //
    // Animation used to be four separate places in this rail: a mode toggle, a host carrying the
    // transport and the loop modes, two sliders further down among the brush sliders, and the
    // time-lapse recorder several items above. Nothing said they were related, and the mode could be
    // left running with its panel shut — onion skins still drawn, playback still going, no sign of
    // it. Opening the window now *is* turning the mode on, and closing it turns it off.
    //
    // 3D was worse off: its only way in was an entry called "3D Model…" filed in the Actions
    // drop-down between "Export for Figma" and "Install brush…". Everything it opens has always been
    // collected — ModelWindow holds the viewport, the model picker, paint mode, clear, and add-to-
    // canvas — but nothing in the rail admitted the feature existed.
    stateItem("tool.animation", "Animation", GraffuxIcons.MotionTween) { vm.onToggleAnimationMode() }
    // GuideIsometric rather than a cube: the Graffux set has no cube, and this is the one glyph in
    // it that draws a solid in three dimensions. Better a near-miss from the app's own set than a
    // stray icon from a library, which is how the rail ended up with a broom for the eraser.
    stateItem("tool.model", "3D Model", GraffuxIcons.GuideIsometric) { onModelClicked() }
    // (No sliders on the rail. Still none, and this is the standing rule rather than an omission: a
    // rail of round buttons is the wrong place for a control that needs a length to be usable and a
    // read-out to be meaningful, and `azRailSlider` has no "you let go" event — which is why the two
    // that wrote to history wrote once per emitted frame. Brush size and hardness are the drag pad
    // up in the tool strip, which shows the real footprint at the real size and carries the number
    // on its badge; stabilize and the selection dials are the Tool Options window; auto-layout's gap
    // is in that layer's own hidden menu with the rest of its properties. Brush *opacity* is gone
    // outright: layer opacity is already an Adjust knob, and the rail slider claimed to move "the
    // value buildStrokePaint actually samples" when only one of fourteen tools ever read it.)
    stateItem("tool.options", "Tool Options", GraffuxIcons.BrushSettings) { onToolOptionsClicked() }

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
        // BrushLibrary, not Brush: the plain brush glyph is the brush *tool*. Using it here too meant
        // the tool, every installed brush and every custom brush were the same picture — verbatim the
        // defect the comment on the tool strip claims to have fixed.
        stateSubItem("brush.$id", "grp.brushes", name, GraffuxIcons.BrushImport) { vm.selectBrushExtension(id) }
    }
    customBrushes.forEach { custom ->
        stateSubItem("brush.custom.${custom.id}", "grp.brushes", custom.brush.name, GraffuxIcons.BrushSettings) {
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
        // Unattached and pinned to the opposite side of the screen from the main rail, not a group
        // in the rail strip. Layers is the one host you keep reaching for *while* painting —
        // Procreate gives it a panel of its own for that reason — and as a rail group it sat behind
        // the same expand-collapse as every tool, so getting to a layer meant opening the rail and
        // pushing everything else aside. OPPOSITE locks it to the far edge of the screen, level with
        // the rail's own items, so it stays put and never drifts under a stray drag.
        //
        // Square, not the circle every rail group wears: a panel on the opposite edge is not a rail
        // group, and the shape is what says so before the user reads a label. It also matches what
        // the host contains — layer thumbnails are rectangular images, and a circular clip crops the
        // corners off every one of them.
        azUnattachedHostItem(
            id = "grp.layers",
            text = strings.editor.layers,
            anchor = AzUnattachedAnchor.OPPOSITE,
            content = GraffuxIcons.Layers,
            color = navItemColor,
            shape = AzButtonShape.SQUARE,
        )
        uiState.layers.filter { it.parentId == null }.reversed().forEach { layer ->
            renderLayerRailItem(
                layer, uiState, "grp.layers", vm, activeColor, navItemColor, strings,
                onBlendMode = onBlendMode, onEditClicked = onEditClicked,
            )
        }
    }

    // Add and Align are document actions, not painting tools — they live in the drop-down (Procreate's
    // Actions menu), keeping the rail to the tools you reach for mid-stroke.

    // Extensions: runs an installed code extension's filter/tool, or applies an installed LUT.
    // "Run Extension", not "Extensions": the drop-down already has an "Extensions" entry, and it
    // opens the *manager*. This one runs an installed filter or LUT against the artwork. Two
    // windows under one name is how a user ends up in the wrong one.
    stateItem("adj.extensions", "Run Extension", GraffuxIcons.FilterGallery, AzButtonShape.CIRCLE) {
        vm.onExtensionsClicked()
    }

    // The `?`. It opens the library's help overlay, which draws a card for every item currently in
    // the rail — its label, plus whatever RAIL_HELP has to say about it. The rail is this app's
    // entire interface and a good deal of it is conditional, which means the ordinary way to
    // discover a control is to already have caused it to appear. This is the way that isn't.
    //
    // Bordered, like everything else that opens something rather than doing something. Last in the
    // strip: it is the item you reach for when the others have not explained themselves.
    azHelpRailItem(
        id = "help", text = "Help", content = GraffuxIcons.Help,
        color = navItemColor, shape = AzButtonShape.CIRCLE,
    )

    // ── Badges ───────────────────────────────────────────────────────────────────────────────────
    //
    // Declared here rather than on each item, for the same reason activeRailClassifiers is one
    // function: `azItemState` is applied after every item is declared, so order does not matter and
    // an unknown id is ignored — which makes this the one place to read what the rail is counting.
    //
    // A badge earns its place when the item's own picture cannot say the number: how big the brush
    // is, how many layers deep the document is, how many brushes or extensions are installed, how
    // many frames the animation has. Items whose content already IS their state get none — the
    // colour swatch, every tool glyph, the mode pickers. A badge repeating what the button already
    // draws is noise in a strip this dense.
    azItemState(id = "adj.brush", badge = uiState.brushSize.roundToInt().toString())
    azItemState(id = "grp.layers", badge = uiState.layers.size.takeIf { it > 0 }?.toString())
    azItemState(
        id = "grp.brushes",
        // +1 for the built-in round brush, which is a real choice in the list and not a placeholder.
        badge = (brushes.size + customBrushes.size + 1).toString(),
    )
    azItemState(id = "adj.extensions", badge = installedExtensionCount.takeIf { it > 0 }?.toString())
    // Frames, but only while Animation Assist is on: outside it the layer count is what `grp.layers`
    // already says, and the same number badged twice under two names is worse than one.
    azItemState(
        id = "tool.animation",
        badge = vm.animationFrameCount().takeIf { uiState.isAnimationMode }?.toString(),
    )
    azItemState(id = SELECT_ID, badge = uiState.savedSelections.size.takeIf { it > 0 }?.toString())
    azItemState(id = "sel.manage", badge = uiState.savedSelections.size.takeIf { it > 0 }?.toString())
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
    onBlendMode: () -> Unit,
    onEditClicked: () -> Unit,
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
                    renderLayerRailItem(
                        child, uiState, "group.${layer.id}", vm, activeColor, navItemColor, strings,
                        onBlendMode = onBlendMode, onEditClicked = onEditClicked,
                    )
                }
            }
        } else null,
    ) {
        // The layer's own tools, on the layer whose menu you opened. Each one selects that layer
        // first, so a tool never lands on whichever layer happened to be active a moment ago — which
        // is what made these worth moving here rather than leaving them as rail buttons that act on
        // "the active layer, wherever it is". Panels are opened, not drawn: a hidden menu is a list
        // of rows (listItem / inputItem — see the AzNavRail guide), so the knobs stay in the panels
        // they already live in and these are the way in to them.
        fun on(action: () -> Unit): () -> Unit = { vm.onLayerActivated(layer.id); action() }

        listItem(strings.nav.adjust, on { vm.onAdjustClicked() })
        listItem("Balance", on { vm.onBalanceClicked() })
        listItem("Blend Mode", on(onBlendMode))
        listItem("Transform", on { vm.onTransformClicked() })
        listItem("Layer Options", on(onEditClicked))

        inputItem(hint = "Rename", initialValue = layer.name) { newName -> vm.onLayerRenamed(layer.id, newName) }
        listItem(if (layer.isVisible) strings.editor.hideLayer else strings.editor.showLayer) { vm.onToggleVisibility(layer.id) }
        if (isGroup) {
            listItem("Ungroup") { vm.onUngroupLayer(layer.id) }
            listItem(strings.editor.delete) { vm.onDeleteGroup(layer.id) }
        } else {
            // The one rule, shared with the quick menu. This used to allow anything that was not a
            // group, so a vector layer offered a lock no stroke would ever consult.
            if (layer.supportsAlphaLock) {
                listItem(if (layer.alphaLock) "Alpha Lock ✓" else "Alpha Lock") { vm.onToggleAlphaLock(layer.id) }
            }
            listItem(if (layer.clipToLayerBelow) "Clip to Below ✓" else "Clip to Below") { vm.onToggleClipToLayerBelow(layer.id) }
            listItem(strings.editor.duplicate) { vm.onLayerDuplicated(layer.id) }
            listItem("Merge Down") { vm.onMergeDown(layer.id) }
            listItem("Flatten All") { vm.onFlattenAllLayers() }
            listItem("Group with Above") { vm.onGroupWithLayerAbove(layer.id) }
            listItem("Clear", on { vm.onClearLayer() })
            listItem(strings.editor.delete) { vm.onLayerRemoved(layer.id) }
        }

        // Link to the layer below. The machinery behind this was complete — getLinkedGroupIds walks
        // the contiguous run, and onTransformGesture already moves the whole linked group as one —
        // but onToggleLinkLayer had no caller anywhere, so no layer could ever be linked and the
        // group-move path was unreachable. The README advertised the feature all the same.
        listItem(if (layer.isLinked) "Linked ✓" else "Link to Below") { vm.onToggleLinkLayer(layer.id) }

        // Copy/paste the layer's *look* — opacity, the tone knobs, blend mode and the warp mesh —
        // without touching its pixels. Both halves were implemented and neither had a caller.
        listItem("Copy Adjustments") { vm.copyLayerModifications(layer.id) }
        if (vm.hasCopiedLayerModifications) {
            listItem("Paste Adjustments") { vm.pasteLayerModifications(layer.id) }
        }

        renderLayerStyleMenu(layer, uiState, vm, ::on)
        renderLayerLayoutMenu(layer, uiState, vm, ::on)
        renderLayerComponentMenu(layer, uiState, vm, ::on)
    }
    if (layer.alphaLock) {
        azItemState(id = "layer.${layer.id}", badge = "🔒", persistentBadge = true)
    }
}

/**
 * Shared styles (Figma tokens) for one layer, as hidden-menu rows.
 *
 * Every row is gated on the layer actually having something for it to act on, so a raster layer's
 * menu carries none of this. Creating a token also links the layer to it, which is why "New …" only
 * shows where there is a look to lift.
 */
private fun HiddenMenuScope.renderLayerStyleMenu(
    layer: Layer,
    uiState: EditorUiState,
    vm: EditorViewModel,
    on: (() -> Unit) -> () -> Unit,
) {
    val shape = layer.shapes.firstOrNull()
    val params = layer.textParams
    if (shape != null) {
        listItem("New Colour Style", on {
            vm.onCreateColorStyleFromActive("Colour ${uiState.colorStyles.size + 1}")
        })
    }
    if (params != null) {
        listItem("New Text Style", on {
            vm.onCreateTextStyleFromActive("Text ${uiState.textStyles.size + 1}")
        })
    }
    uiState.colorStyles.forEach { style ->
        val fillLinked = layer.shapes.any { it.fillStyleId == style.id }
        val strokeLinked = layer.shapes.any { it.strokeStyleId == style.id }
        if (shape != null) {
            listItem(if (fillLinked) "Fill: ${style.name} ✓" else "Fill: ${style.name}", on {
                vm.onApplyColorStyle(if (fillLinked) null else style.id)
            })
            // The stroke half of the pair. It had an intent and a StyleOps function and no way in.
            listItem(if (strokeLinked) "Stroke: ${style.name} ✓" else "Stroke: ${style.name}", on {
                vm.onApplyStrokeStyle(if (strokeLinked) null else style.id)
            })
        }
        listItem("Update ${style.name} to Colour", on { vm.onUpdateColorStyleToActiveColor(style.id) })
        listItem("Delete ${style.name}") { vm.onDeleteColorStyle(style.id) }
    }
    uiState.textStyles.forEach { style ->
        if (params != null) {
            val linked = params.styleId == style.id
            listItem(if (linked) "Text: ${style.name} ✓" else "Text: ${style.name}", on {
                vm.onApplyTextStyle(if (linked) null else style.id)
            })
            // The text equivalent of "Update … to Colour", which until now did not exist: a text
            // token could be made and deleted but never edited.
            listItem("Update ${style.name} from Layer", on { vm.onUpdateTextStyleToActive(style.id) })
        }
        listItem("Delete ${style.name}") { vm.onDeleteTextStyle(style.id) }
    }
}

/**
 * Auto-layout and constraints (Figma) for one layer.
 *
 * Auto-layout only where the layer has children to arrange; constraints only where it has a parent
 * to be constrained within — and not even then if that parent runs auto-layout, since the parent
 * owns placement in that case and a constraint would be a control that does nothing.
 */
private fun HiddenMenuScope.renderLayerLayoutMenu(
    layer: Layer,
    uiState: EditorUiState,
    vm: EditorViewModel,
    on: (() -> Unit) -> () -> Unit,
) {
    val hasChildren = uiState.layers.any { it.parentId == layer.id }
    if (hasChildren) {
        val current = layer.autoLayout
        LayoutDirection.entries.forEach { dir ->
            val label = when (dir) {
                LayoutDirection.NONE -> "Free"
                LayoutDirection.HORIZONTAL -> "Row"
                LayoutDirection.VERTICAL -> "Column"
            }
            listItem(if (current.direction == dir) "Layout: $label ✓" else "Layout: $label", on {
                vm.onSetAutoLayout(current.copy(direction = dir))
            })
        }
        if (current.direction != LayoutDirection.NONE) {
            listItem("Hug Contents", on { vm.onHugContents() })
            LayoutAlign.entries.forEach { align ->
                val name = align.name.lowercase().replaceFirstChar { c -> c.uppercase() }
                listItem(if (current.align == align) "Align $name ✓" else "Align $name", on {
                    vm.onSetAutoLayout(current.copy(align = align))
                })
            }
        }
    }
    val parent = layer.parentId?.let { pid -> uiState.layers.firstOrNull { it.id == pid } }
    if (parent != null && parent.autoLayout.direction == LayoutDirection.NONE) {
        ConstraintAnchor.entries.forEach { anchor ->
            val name = anchor.name.lowercase().replaceFirstChar { c -> c.uppercase() }
            listItem(if (layer.constraints.horizontal == anchor) "H: $name ✓" else "H: $name", on {
                vm.onSetConstraints(layer.constraints.copy(horizontal = anchor))
            })
        }
        ConstraintAnchor.entries.forEach { anchor ->
            val name = anchor.name.lowercase().replaceFirstChar { c -> c.uppercase() }
            listItem(if (layer.constraints.vertical == anchor) "V: $name ✓" else "V: $name", on {
                vm.onSetConstraints(layer.constraints.copy(vertical = anchor))
            })
        }
    }
}

/**
 * Components and instances (Figma) for one layer. What is offered follows what the layer already
 * is, so the menu never shows a control that would no-op — Detach only on an instance, Release only
 * on a main, Make Component only on a plain layer.
 */
private fun HiddenMenuScope.renderLayerComponentMenu(
    layer: Layer,
    uiState: EditorUiState,
    vm: EditorViewModel,
    on: (() -> Unit) -> () -> Unit,
) {
    if (layer.componentId == null && layer.instanceOf == null) {
        listItem("Make Component", on { vm.onMakeComponent() })
    }
    if (layer.instanceOf != null) listItem("Detach Instance", on { vm.onDetachInstance() })
    if (layer.componentId != null) listItem("Release Component", on { vm.onReleaseComponent() })
    uiState.layers.filter { it.componentId != null }.forEach { main ->
        listItem("Place ${main.name}") { main.componentId?.let { vm.onPlaceInstance(it) } }
    }
}
