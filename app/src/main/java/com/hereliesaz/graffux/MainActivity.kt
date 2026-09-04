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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.BitmapPainter
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
import com.hereliesaz.graffitixr.common.model.TransformMode
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.design.GraffuxIcons
import com.hereliesaz.graffitixr.design.components.ConfirmDialog
import com.hereliesaz.graffitixr.design.components.LocalRailInset
import com.hereliesaz.graffitixr.design.components.RailInset
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
import com.hereliesaz.graffitixr.feature.editor.BrushPreview
import com.hereliesaz.graffitixr.feature.editor.BrushStudioWindow
import com.hereliesaz.graffitixr.feature.editor.BrushTipsManagerWindow
import com.hereliesaz.graffitixr.feature.editor.CustomBrushesWindow
import com.hereliesaz.graffitixr.feature.editor.CornerRadiusDialog
import com.hereliesaz.graffitixr.feature.editor.CurvesDialog
import com.hereliesaz.graffitixr.feature.editor.DocumentSizeDialog
import com.hereliesaz.graffitixr.feature.editor.EditorScreen
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
import com.hereliesaz.graffitixr.feature.editor.FigmaWindow
import com.hereliesaz.graffitixr.feature.editor.ReferenceWindow
import com.hereliesaz.graffitixr.feature.editor.ShapeSizeDialog
import com.hereliesaz.graffitixr.feature.editor.SizePickerDialog
import com.hereliesaz.graffitixr.feature.editor.StoreChooserDialog
import com.hereliesaz.graffitixr.feature.editor.StoreTab
import com.hereliesaz.graffitixr.feature.editor.StoreWindow
import com.hereliesaz.graffitixr.feature.editor.TextEditDialog
import com.hereliesaz.graffitixr.feature.editor.VectorStrokeDialog
import com.hereliesaz.graffitixr.feature.editor.toModelBlendMode
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
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
 * The real [com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush] behind [activeBrushName], for
 * a live [BrushPreview] strip -- null both when the plain (non-stamp) round brush is active
 * (`activeBrushName == null`) and, defensively, for an installed-extension brush name this device
 * has no full brush data for yet (only [installedBrushes]' id/name pairs, not a decoded
 * `AzphaltBrush` -- see [com.hereliesaz.graffitixr.feature.editor.EditorViewModel.
 * selectBrushExtension]'s own async decode path). Mirrors the same built-in-then-custom lookup
 * [rail item classifiers][ConfigureRailItems] already do, rather than a third independent one.
 */
private fun resolvePreviewBrush(
    activeBrushName: String?,
    customBrushes: List<CustomBrush>,
): com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush? {
    if (activeBrushName == null) return null
    return com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes.presets.firstOrNull { it.name == activeBrushName }
        ?: customBrushes.firstOrNull { it.brush.name == activeBrushName }?.brush
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

/**
 * Width of each individual rail button — NOT the rail strip's own on-screen width (that's
 * `collapsedWidth`, read back into [RailInset] via `AzNavHostScope.railWidth`; see the
 * `measuredRailWidth` comment where azConfig is called).
 */
private val RAIL_ITEM_WIDTH = 44.dp

@Composable
private fun GraffuxApp(sharedImageUri: Uri?, azphaltInstallUrl: String? = null) {
    val vm: EditorViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val uiState by vm.uiState.collectAsState()
    val railExpansion by vm.railExpansion.collectAsState()
    val colorSmudgeSettings by vm.colorSmudgeSettings.collectAsState()
    val allInstalledExtensions by vm.allInstalledExtensions.collectAsState()
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
    var showCurvesDialog by remember { mutableStateOf(false) }
    var showStrokeDialog by remember { mutableStateOf(false) }
    var showCornerDialog by remember { mutableStateOf(false) }
    var showShapeSizeDialog by remember { mutableStateOf(false) }
    var showSidesDialog by remember { mutableStateOf(false) }
    var showBrushGallery by remember { mutableStateOf(false) }
    var showBrushTipsManager by remember { mutableStateOf(false) }
    var manualEditTextId by remember { mutableStateOf<String?>(null) }
    var showBgDialog by remember { mutableStateOf(false) }
    // Procreate-style windows: things that used to be a wall of always-visible rail buttons now
    // live behind a single rail item that opens one of these instead.
    var showAddDialog by remember { mutableStateOf(false) }
    var showLayerOptionsDialog by remember { mutableStateOf(false) }
    // One window, two tabs (StoreWindow's own doc comment) — showStore alone gates it; storeTab
    // says which tab it opens on, set by whichever entry point (Manage/Get Extensions, the
    // Extensions toggle, "Store…") raised showStore, and freely switchable once open.
    var showStore by remember { mutableStateOf(false) }
    var storeTab by remember { mutableStateOf(StoreTab.BROWSE) }
    var showStoreChooser by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showToolOptions by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showOpenDialog by remember { mutableStateOf(false) }
    var showReferenceWindow by remember { mutableStateOf(false) }
    // A viewing aid, not project state (see ReferenceWindow's own doc comment) — held here, not
    // in the ViewModel, for the same reason.
    var referenceImageUri by remember { mutableStateOf<Uri?>(null) }
    var showFigmaWindow by remember { mutableStateOf(false) }
    // Non-base workspaces get their own independent FLOATING AzNavRail hosts. The second title
    // dropdown below merely decides which of these toolboxes are present; AzNavRail 11.26 owns
    // their drag, screen-edge snap, rail-to-rail docking and group-drag behaviour.
    var showAnimationRail by remember { mutableStateOf(false) }
    var showModelRail by remember { mutableStateOf(false) }
    var showReferenceRail by remember { mutableStateOf(false) }
    var showFigmaRail by remember { mutableStateOf(false) }
    var showExtensionsRail by remember { mutableStateOf(false) }
    // Unlike the toolboxes above, on by default: the layers panel is core, not opt-in. The toggle
    // exists as a recovery switch, not a way to hide it — declaring it (below) drops and recreates
    // its AzNavRail-internal position state, so toggling it off and on is how to un-stick a panel
    // that ends up rendered somewhere unreachable (a real failure mode of the FLOATING anchor's
    // screen-edge-docking/persistence machinery, not merely a hypothetical one this app has hit).
    var showLayersRail by remember { mutableStateOf(true) }
    // Same "core, not opt-in" contract as showLayersRail above: a quick-pick strip of brush
    // thumbnails, always reachable while painting, rather than only through the rail's collapsed
    // "Brushes" group or the full "My Brushes" gallery window -- neither of which stays visible
    // while a stroke is in progress the way this does.
    var showBrushRail by remember { mutableStateOf(true) }
    // The name confirmed in the Save dialog, held while the system location picker is up — the
    // picker hands back a Uri and nothing else, so the name has to survive the round trip.
    var pendingSaveName by remember { mutableStateOf<String?>(null) }
    // "Forget a Selection" is the one delete in the app with no undo (savedSelections isn't part
    // of the layers-only history) and, before this, no confirmation either — every other delete
    // in the app is one or the other. Held here rather than dispatched straight from the rail
    // item's onClick because ConfigureRailItems isn't @Composable and can't own a ConfirmDialog.
    var pendingForgetSelectionName by remember { mutableStateOf<String?>(null) }

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

    // azphalt://install is exported and BROWSABLE (see AndroidManifest.xml) so any web page or
    // installed app can launch Graffux with an arbitrary --url pointing at an unsigned package.
    // Installing it unconditionally the moment the deep link lands was a drive-by-install
    // primitive: the only signal the user ever got was a Toast *after* the extension was already
    // on disk. Route it through a real confirmation instead, naming the URL so there's something
    // to actually evaluate before it downloads and installs anything.
    var pendingAzphaltInstallUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(azphaltInstallUrl) {
        pendingAzphaltInstallUrl = azphaltInstallUrl
    }
    pendingAzphaltInstallUrl?.let { url ->
        ConfirmDialog(
            title = "Install extension?",
            message = "Something wants Graffux to download and install an extension from:\n\n" +
                "$url\n\nExtensions can add code that runs inside Graffux. Only install one " +
                "if you trust where this came from.",
            confirmLabel = "Install",
            onConfirm = { vm.installExtensionFromUrl(url); pendingAzphaltInstallUrl = null },
            onDismiss = { pendingAzphaltInstallUrl = null },
        )
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

    val referencePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { referenceImageUri = it } }

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

    // The rail's "Get Extensions"/"Store…" entry point. In-app browse (StoreWindow's Browse tab) is
    // the primary way in now, not the delegated store chooser below — that used to be the only
    // door, and this button still opened it directly, so the button a user would actually tap to
    // get a brush routed them straight out to a browser/store app instead of the searchable in-app
    // catalogue one tap away inside "Manage Extensions". The chooser is still reachable from
    // StoreWindow's own "Other sources" button, for what only a real store app can do (Play
    // Billing, chiefly).
    val openAzphaltStore: () -> Unit = { storeTab = StoreTab.BROWSE; showStore = true }

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
    ).toMutableSet().apply {
        if (uiState.isAnimationMode || uiState.isTimeLapseRecording) add("area.animation")
        if (showModelDialog) add("area.model")
        if (showReferenceWindow) add("area.reference")
        if (showFigmaWindow) add("area.figma")
        if (uiState.activePanel == EditorPanel.EXTENSIONS || showStore) add("area.extensions")
    }.toSet()

    val navItemColor = Color.White

    // Procreate's history gestures are installed HERE, above the whole app, not inside EditorScreen.
    // The nav rail, the onscreen chrome (dropdown, FAB row) and the floating windows are siblings of
    // the canvas, not children of it, so an observer living on the canvas never saw a two- or
    // three-finger tap that happened to land on any of them. At this level the gestures work
    // wherever the fingers land, whatever tool is active and whatever is selected. It consumes
    // nothing, so every control underneath still behaves exactly as before.
    val strokeGate = remember { StrokeGate() }
    // The rail's actual on-screen width is `collapsedWidth` (a separate knob from `railItemWidth`
    // below, which only sizes each button, not the strip itself) — read back from AzNavHostScope
    // (11.19) right after azConfig, rather than duplicating a guessed constant here, so
    // FloatingWindow's rail-avoidance (see LocalRailInset) can never drift out of sync with what the
    // rail actually renders. Seeded at the library's own 100.dp default until that first read lands.
    var measuredRailWidth by remember { mutableStateOf(100.dp) }
    val railInset = RailInset(dockedOnLeft = uiState.isRightHanded, width = measuredRailWidth)
    CompositionLocalProvider(LocalRailInset provides railInset) {
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
                focusColor = activeRailColor,
                // The third highlight (11.9). `active` answers "where am I", `secondary` answers
                // "what is true of this". They have to be different colours or the rail says the
                // same thing about a layer you are editing and a layer that merely comes along when
                // you drag it. Amber because it reads as a condition rather than a place.
                secondaryColor = Color(0xFFFFB300),
                // The border is what distinguishes "this opens something" from "this does something".
                // The rail's default is therefore borderless (NONE_SQUARE keeps the SQUARE footprint,
                // so a borderless item still lines up with a bordered one) and the only items that
                // opt back into SQUARE are hosts and the items that open a tool window. Every leaf
                // tool, toggle and picker inherits this.
                defaultShape = AzButtonShape.NONE_SQUARE,
                headerIconShape = AzHeaderIconShape.SQUARE,
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
                railItemWidth = RAIL_ITEM_WIDTH
            )
            // See the `measuredRailWidth` comment above — read back once config is settled for this
            // pass, straight from the scope rather than a locally-guessed duplicate.
            measuredRailWidth = railWidth
            // Four-finger tap (see the multiFingerTaps observer above): full-screen art. Folding the
            // rail is the AzNavRail half of "hide the UI"; the onscreen chrome below gates on the
            // same flag.
            isFoldedUp = uiState.hideUiForCapture

            ConfigureRailItems(
                vm = vm,
                uiState = uiState,
                railExpansion = railExpansion,
                brushes = brushes,
                customBrushes = customBrushes,
                installedExtensionCount = allInstalledExtensions.size,
                strings = strings,
                navItemColor = navItemColor,
                activeColor = activeRailColor, // Pass down to DSL builder
                screenCenter = screenCenter,
                onBlendMode = { showBlendDialog = true },
                onCurves = { showCurvesDialog = true },
                onAddClicked = { showAddDialog = true },
                onEditClicked = { showLayerOptionsDialog = true },
                onModelClicked = { showModelDialog = true },
                modelWindowOpen = showModelDialog,
                onToolOptionsClicked = { showToolOptions = !showToolOptions },
                toolOptionsOpen = showToolOptions,
                onForgetSelectionRequested = { pendingForgetSelectionName = it },
                showLayersRail = showLayersRail,
                showBrushRail = showBrushRail,
                onOpenBrushGallery = { showBrushGallery = true },
                onOpenBrushTipsManager = { showBrushTipsManager = true },
            )

            if (showAnimationRail) {
                azUnattachedHostItem(
                    id = "area.animation", text = "Animation",
                    anchor = AzUnattachedAnchor.FLOATING,
                    content = GraffuxIcons.MotionTween, color = navItemColor,
                    shape = AzButtonShape.NONE_SQUARE, classifiers = setOf("area.animation"),
                    initiallyExpanded = railExpansion["area.animation"] ?: false,
                    onExpandedChange = { vm.onRailHostExpansionChanged("area.animation", it) },
                )
                azRailSubItem(
                    id = "tool.animation", hostId = "area.animation",
                    text = if (uiState.isAnimationMode) "Close Animation Assist" else "Animation Assist",
                    content = GraffuxIcons.MotionTween, color = navItemColor,
                    classifiers = setOf("tool.animation"),
                    onClick = { vm.onToggleAnimationMode() },
                )
                if (uiState.isAnimationMode) {
                    azRailSubItem(
                        id = "animation.play", hostId = "area.animation",
                        text = if (uiState.isAnimationPlaying) "Pause" else "Play",
                        content = GraffuxIcons.MotionTween, color = navItemColor,
                        onClick = { vm.onToggleAnimationPlayback() },
                    )
                    azRailSubItem(
                        id = "animation.previous", hostId = "area.animation", text = "Previous Frame",
                        content = GraffuxIcons.Undo, color = navItemColor,
                        onClick = { vm.onPreviousFrame() },
                    )
                    azRailSubItem(
                        id = "animation.next", hostId = "area.animation", text = "Next Frame",
                        content = GraffuxIcons.Redo, color = navItemColor,
                        onClick = { vm.onNextFrame() },
                    )
                    azRailSubItem(
                        id = "animation.add", hostId = "area.animation", text = "Add Frame",
                        content = GraffuxIcons.LayerAdd, color = navItemColor,
                        onClick = { vm.onAddFrame() },
                    )
                    azRailSubItem(
                        id = "animation.onion", hostId = "area.animation",
                        text = if (uiState.onionSkinEnabled) "Onion Skin Off" else "Onion Skin On",
                        content = GraffuxIcons.LayerOpacity, color = navItemColor,
                        onClick = { vm.onToggleOnionSkin() },
                    )
                    azRailSubItem(
                        id = "animation.export", hostId = "area.animation", text = "Export Animation",
                        content = GraffuxIcons.MotionTween, color = navItemColor,
                        onClick = { vm.exportAnimation() },
                    )
                    azRailSubItem(
                        id = "animation.timelapse", hostId = "area.animation",
                        text = if (uiState.isTimeLapseRecording) "Stop Time-lapse" else "Start Time-lapse",
                        content = GraffuxIcons.MotionTween, color = navItemColor,
                        onClick = { vm.onToggleTimeLapseRecording() },
                    )
                }
            }

            if (showModelRail) {
                azUnattachedHostItem(
                    id = "area.model", text = "3D", anchor = AzUnattachedAnchor.FLOATING,
                    content = GraffuxIcons.GuideIsometric, color = navItemColor,
                    shape = AzButtonShape.NONE_SQUARE, classifiers = setOf("area.model"),
                    initiallyExpanded = railExpansion["area.model"] ?: false,
                    onExpandedChange = { vm.onRailHostExpansionChanged("area.model", it) },
                )
                azRailSubItem(
                    id = "tool.model", hostId = "area.model", text = "3D Model",
                    content = GraffuxIcons.GuideIsometric, color = navItemColor,
                    classifiers = setOf("tool.model"), onClick = { showModelDialog = true },
                )
                azRailSubItem(
                    id = "model.choose", hostId = "area.model", text = "Choose Model",
                    content = GraffuxIcons.GuideIsometric, color = navItemColor,
                    onClick = { showModelDialog = true; modelPicker.launch(arrayOf("*/*")) },
                )
            }

            if (showReferenceRail) {
                azUnattachedHostItem(
                    id = "area.reference", text = "Reference", anchor = AzUnattachedAnchor.FLOATING,
                    content = GraffuxIcons.LayerReference, color = navItemColor,
                    shape = AzButtonShape.NONE_SQUARE, classifiers = setOf("area.reference"),
                    initiallyExpanded = railExpansion["area.reference"] ?: false,
                    onExpandedChange = { vm.onRailHostExpansionChanged("area.reference", it) },
                )
                azRailSubItem(
                    id = "reference.open", hostId = "area.reference", text = "Reference Image",
                    content = GraffuxIcons.LayerReference, color = navItemColor,
                    onClick = { showReferenceWindow = true },
                )
                azRailSubItem(
                    id = "reference.choose", hostId = "area.reference", text = "Choose Reference",
                    content = GraffuxIcons.LayerReference, color = navItemColor,
                    onClick = {
                        showReferenceWindow = true
                        referencePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
            }

            if (showFigmaRail) {
                azUnattachedHostItem(
                    id = "area.figma", text = "Figma", anchor = AzUnattachedAnchor.FLOATING,
                    content = GraffuxIcons.Artboard, color = navItemColor,
                    shape = AzButtonShape.NONE_SQUARE, classifiers = setOf("area.figma"),
                    initiallyExpanded = railExpansion["area.figma"] ?: false,
                    onExpandedChange = { vm.onRailHostExpansionChanged("area.figma", it) },
                )
                azRailSubItem(
                    id = "figma.open", hostId = "area.figma", text = "Import from Figma",
                    content = GraffuxIcons.Artboard, color = navItemColor,
                    onClick = { showFigmaWindow = true },
                )
                azRailSubItem(
                    id = "figma.export", hostId = "area.figma", text = "Export for Figma",
                    content = GraffuxIcons.Artboard, color = navItemColor,
                    onClick = { vm.exportForFigma() },
                )
            }

            if (showExtensionsRail) {
                azUnattachedHostItem(
                    id = "area.extensions", text = "Extensions", anchor = AzUnattachedAnchor.FLOATING,
                    content = GraffuxIcons.FilterGallery, color = navItemColor,
                    shape = AzButtonShape.NONE_SQUARE, classifiers = setOf("area.extensions"),
                    initiallyExpanded = railExpansion["area.extensions"] ?: false,
                    onExpandedChange = { vm.onRailHostExpansionChanged("area.extensions", it) },
                )
                azRailSubItem(
                    id = "adj.extensions", hostId = "area.extensions", text = "Run Extension",
                    content = GraffuxIcons.FilterGallery, color = navItemColor,
                    classifiers = setOf("adj.extensions"), onClick = { vm.onExtensionsClicked() },
                )
                // One door, not two: "Manage Extensions" and "Get Extensions" used to be separate
                // rail entries opening the same Store window on different tabs — Browse is still
                // right there once the window's open (one tap on its own tab switcher), so a
                // second rail entry just to land on it first bought nothing.
                azRailSubItem(
                    id = "extensions.manage", hostId = "area.extensions", text = "Extensions",
                    content = GraffuxIcons.FilterGallery, color = navItemColor,
                    onClick = { storeTab = StoreTab.INSTALLED; showStore = true },
                )
            }

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
                if (!uiState.hideUiForCapture) {
                    AzDropdownMenu(navController = navController) {
                        azConfig(
                            design = AzDropdownDesign.MENU,
                            dockingSide = if (uiState.isRightHanded) AzDockingSide.RIGHT else AzDockingSide.LEFT,
                            showFooter = false,
                            trigger = AzDropdownTrigger.Hamburger,
                            triggerPlacement = AzDropdownTriggerPlacement.TITLE,
                        )
                        fun areaToggle(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) =
                            azToggle(
                                isChecked = checked,
                                toggleOnText = label,
                                toggleOffText = label,
                                color = menuItemColor,
                                onToggle = onToggle,
                            )
                        areaToggle("Layers", showLayersRail) { showLayersRail = it }
                        areaToggle("Brushes", showBrushRail) { showBrushRail = it }
                        areaToggle("Animation", showAnimationRail) { showAnimationRail = it }
                        areaToggle("3D", showModelRail) { showModelRail = it }
                        areaToggle("Reference", showReferenceRail) { showReferenceRail = it }
                        areaToggle("Figma", showFigmaRail) { showFigmaRail = it }
                        // Unlike the other toggles here, turning this ON also opens the Extensions
                        // window straight to Installed — azToggle's whole row is one tap target
                        // (the sanctioned DSL has no way to give the label and the switch separate
                        // ones), so "tap the label to open Installed, tap the switch to just show/
                        // hide the rail" isn't directly buildable; treating a check as "yes, show me
                        // what I have" is the closest match. Turning it back off only hides the
                        // rail, same as ever — no navigation on the way out.
                        areaToggle("Extensions", showExtensionsRail) { checked ->
                            showExtensionsRail = checked
                            if (checked) { storeTab = StoreTab.INSTALLED; showStore = true }
                        }
                    }

                    AzDropdownMenu(navController = navController) {
                        azConfig(
                            design = AzDropdownDesign.MENU,
                            dockingSide = if (uiState.isRightHanded) AzDockingSide.RIGHT else AzDockingSide.LEFT,
                            trigger = AzDropdownTrigger.MoreVert,
                            triggerPlacement = AzDropdownTriggerPlacement.TITLE,
                        )
                    // Every entry here is the same colour, and the dropdown's `azConfig` has no hook
                    // to say so once — so it was said twenty-one times over. An entry added without
                    // it falls back to Color.Unspecified rather than to the other twenty, which is a
                    // silent way to end up with one unreadable line. Said once here instead.
                    fun menuItem(text: String, onClick: () -> Unit) =
                        azItem(color = menuItemColor, text = text, onClick = onClick)
                    menuItem(text = strings.nav.new, onClick = { vm.createNewProject() })
                    menuItem(text = strings.nav.open, onClick = { vm.refreshOpenScreen(); showOpenDialog = true })
                    menuItem(text = "Import", onClick = { importPicker.launch(arrayOf("*/*")) })
                    // Opens AddContentDialog (shapes + text as a new layer). Had a menu entry here
                    // until a dropdown cleanup consolidated three adjacent "Add.../Import.../Import
                    // File..." entries into the single Import item above and dropped this one with
                    // them — collateral damage, not an intentional cut: onAddClicked kept being
                    // threaded all the way down into ConfigureRailItems with no caller left to
                    // invoke it, so a rectangle/ellipse/line/polygon layer became uncreatable from
                    // the UI at all. AddContentDialog, ShapeKind and onAddShapeLayer/
                    // onAddPolygonLayer never stopped working — nothing pointed at them any more.
                    menuItem(text = "Add Shape…", onClick = { showAddDialog = true })
                    menuItem(text = strings.nav.save, onClick = { showSaveDialog = true })
                    menuItem(text = strings.nav.export, onClick = { vm.exportImage() })
                    // The azphalt store's only other door in was the "Extensions rail" toggle buried
                    // in the second (title) dropdown below, off by default — nothing here pointed at
                    // it, so there was no way to find the store without already knowing that toggle
                    // existed. This is the direct, always-visible path in.
                    menuItem(text = "Store…", onClick = { openAzphaltStore() })
                    // Writes a .graffux-figma.json bundle (see figma-plugin/README.md) that the
                    // companion Figma plugin rebuilds as a frame, one layer per Graffux layer.
                    // Same "collateral damage" shape as the Add Shape entry above: exportForFigma()
                    // never stopped working, it just had no menu item pointing at it any more.
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
                        menuItem(text = "Settings", onClick = { showSettings = true })
                    }
                }
            }

            // Onscreen Foreground Elements explicitly pinned over the canvas. Hidden while a bottom panel
            // is up: Transform and the adjustment knobs occupy this same strip, and the buttons were
            // landing on top of their fields.
            onscreen(alignment = Alignment.BottomCenter) {
                if (uiState.hideUiForCapture) {
                    // The only way into this mode is a four-finger tap, and the only place that
                    // gesture is documented — the rail's Help item — is itself hidden by this same
                    // flag. A user who triggers it by accident (resting a hand during a multi-touch
                    // smudge, say) would otherwise have no on-screen way back short of re-guessing
                    // the gesture that got them here. One small, low-opacity tap target is enough to
                    // make this a mode rather than a dead end, without undoing the clean look the
                    // gesture exists for.
                    FloatingActionButton(
                        onClick = { vm.toggleHideUi() },
                        containerColor = surfaceVariantColor.copy(alpha = 0.35f),
                        modifier = Modifier.navigationBarsPadding().padding(bottom = 24.dp).size(40.dp),
                    ) {
                        Icon(painterResource(GraffuxIcons.ChevronUp), contentDescription = "Show interface")
                    }
                } else if (uiState.activePanel == EditorPanel.NONE) Row(
                    modifier = Modifier.navigationBarsPadding().padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val viewMoved = uiState.viewportZoom != 1f ||
                        uiState.viewportOffset != Offset.Zero ||
                        uiState.viewportRotation != 0f

                    // These are permanent positions, not a row of whichever buttons happen to exist.
                    // Keeping all three 56dp slots mounted prevents the row from recentering when one
                    // action becomes unavailable. Order is always Undo -> Reset -> Redo.
                    Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        if (uiState.undoCount > 0) {
                            FloatingActionButton(
                                onClick = { vm.onUndoClicked() },
                                containerColor = surfaceVariantColor,
                            ) {
                                Icon(painterResource(GraffuxIcons.Undo), contentDescription = strings.adj.undo)
                            }
                        }
                    }
                    Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        if (viewMoved) {
                            FloatingActionButton(
                                onClick = { vm.resetViewport() },
                                containerColor = surfaceVariantColor,
                            ) {
                                Icon(painterResource(GraffuxIcons.ZoomFit), contentDescription = "Fit to screen")
                            }
                        }
                    }
                    Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        if (uiState.redoCount > 0) {
                            FloatingActionButton(
                                onClick = { vm.onRedoClicked() },
                                containerColor = surfaceVariantColor,
                            ) {
                                Icon(painterResource(GraffuxIcons.Redo), contentDescription = strings.adj.redo)
                            }
                        }
                    }
                }
            }

            onscreen(alignment = Alignment.Center) {
                if (showDocDialog) {
                    DocumentSizeDialog(
                        currentWidth = uiState.documentWidth,
                        currentHeight = uiState.documentHeight,
                        // No Apply button: a preset applies and closes in one tap; a custom size
                        // applies once, when the dialog closes.
                        onConfirm = { w, h -> vm.setDocumentSize(w, h) },
                        onDismiss = { showDocDialog = false },
                    )
                }

                pendingForgetSelectionName?.let { name ->
                    ConfirmDialog(
                        title = "Forget \"$name\"?",
                        message = "This selection can't be recovered afterward — forgetting it isn't undoable.",
                        confirmLabel = "Forget",
                        onConfirm = { vm.onDeleteSavedSelection(name); pendingForgetSelectionName = null },
                        onDismiss = { pendingForgetSelectionName = null },
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

                if (showCurvesDialog) {
                    CurvesDialog(
                        onDismissRequest = { showCurvesDialog = false },
                        onCurvesApplied = { points -> vm.onCurvesApplied(points) },
                    )
                }

                // key(uiState.activeLayerId) on all four of these: the same staleness hazard
                // TextEditDialog's own key() below is documented against — these dialogs seed their
                // fields from `remember { mutableStateOf(current...) }` with no key, so if the active
                // layer changes while one stays open (FloatingWindow-style dialogs never block the
                // canvas/rail), the dialog keeps showing and applying the OLD layer's stale value,
                // silently overwriting the NEW active layer's real property on Apply.
                if (showStrokeDialog) {
                    val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
                    key(uiState.activeLayerId) {
                        VectorStrokeDialog(
                            currentWidth = activeLayer?.shapes?.firstOrNull()?.strokeWidth ?: 0f,
                            // No Apply button: the dialog itself fires this once, with the final
                            // value, when it closes — see VectorStrokeDialog's own doc comment.
                            onApply = { w -> vm.setVectorStrokeWidth(w) },
                            onDismiss = { showStrokeDialog = false },
                        )
                    }
                }

                if (showCornerDialog) {
                    val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
                    val rect = activeLayer?.shapes?.firstOrNull { it.kind == ShapeKind.RECTANGLE }
                    key(uiState.activeLayerId) {
                        CornerRadiusDialog(
                            currentRadius = rect?.cornerRadius ?: 0f,
                            // No Apply button: fires once, on close — see CornerRadiusDialog's own
                            // doc comment.
                            onApply = { r -> vm.setVectorCornerRadius(r) },
                            onDismiss = { showCornerDialog = false },
                        )
                    }
                }

                if (showShapeSizeDialog) {
                    val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
                    val shape = activeLayer?.shapes?.firstOrNull()
                    if (shape != null) {
                        key(uiState.activeLayerId) {
                            ShapeSizeDialog(
                                currentWidth = shape.width,
                                currentHeight = shape.height,
                                isLine = shape.kind == ShapeKind.LINE,
                                // No Apply button: fires once, on close — see ShapeSizeDialog's own
                                // doc comment.
                                onConfirm = { w, h -> vm.setVectorSize(w, h) },
                                onDismiss = { showShapeSizeDialog = false },
                            )
                        }
                    }
                }

                if (showSidesDialog) {
                    val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
                    val polygon = activeLayer?.shapes?.firstOrNull { it.kind == ShapeKind.POLYGON }
                    if (polygon != null) {
                        key(uiState.activeLayerId) {
                            PolygonSidesDialog(
                                currentSides = polygon.sides,
                                // No Apply button: fires once, on close — see PolygonSidesDialog's
                                // own doc comment.
                                onApply = { n -> vm.setPolygonSides(n) },
                                onDismiss = { showSidesDialog = false },
                            )
                        }
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

                if (showReferenceWindow) {
                    ReferenceWindow(
                        imageUri = referenceImageUri,
                        onPickImage = {
                            referencePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onDismiss = { showReferenceWindow = false },
                    )
                }

                if (showFigmaWindow) {
                    val figmaState by vm.figmaState.collectAsState()
                    FigmaWindow(
                        state = figmaState,
                        onTokenSubmit = { vm.connectFigma(it) },
                        onDisconnect = { vm.disconnectFigma() },
                        onFileInputChanged = { vm.onFigmaFileInputChanged(it) },
                        onLoadFile = { vm.loadFigmaFile() },
                        onToggleFrame = { vm.toggleFigmaFrame(it) },
                        // Left open rather than dismissed here: importFigmaFrames() runs
                        // asynchronously and FigmaWindow is what shows its loading state and any
                        // per-import error — closing on click would hide both.
                        onImport = { vm.importFigmaFrames() },
                        onDismiss = { showFigmaWindow = false },
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

                // The azphalt extensions window: one FloatingWindow, two tabs (Installed/Browse) —
                // see StoreWindow's own doc comment for why this replaced two separate windows.
                // "Other sources…" still reaches the delegated handoff (StoreChooserDialog) below,
                // for what only a real store app can do (Play Billing).
                if (showStore) {
                    LaunchedEffect(Unit) { vm.refreshStoreUpdates() }
                    StoreWindow(
                        initialTab = storeTab,
                        installed = allInstalledExtensions,
                        updatesAvailable = uiState.storeUpdatesAvailable,
                        installingIds = uiState.storeInstallingIds,
                        onUpdate = { ext ->
                            uiState.storeUpdatesAvailable[ext.id]?.let { latest ->
                                vm.installFromRepository(ext.id, latest)
                            }
                        },
                        onUninstall = { vm.uninstallExtension(it) },
                        query = uiState.storeBrowseQuery,
                        results = uiState.storeBrowseResults,
                        loading = uiState.storeBrowseLoading,
                        error = uiState.storeBrowseError,
                        onQueryChanged = { vm.onStoreBrowseQueryChanged(it) },
                        onSearch = { vm.onStoreSearch() },
                        onInstall = { pkg -> vm.installFromRepository(pkg.id, pkg.latest ?: pkg.version) },
                        onBuy = { pkg ->
                            val url = "${AzphaltStoreHandoff.WEB_STORE_URL}/packages/" +
                                com.hereliesaz.graffitixr.data.azphalt.RepositoryApiClient.encodePathSegment(pkg.id)
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }.onFailure {
                                alerts.show(
                                    kind = AzPopupKind.WARNING,
                                    title = "No browser",
                                    message = "Nothing on this device can open $url.",
                                )
                            }
                        },
                        onOtherSources = { showStore = false; showStoreChooser = true },
                        onDismiss = { showStore = false },
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
                    val playbackRange = vm.resolvedPlaybackRange()
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
                        onionSkinPastCount = uiState.onionSkinPastCount,
                        onionSkinFutureCount = uiState.onionSkinFutureCount,
                        loopMode = uiState.animationLoopMode,
                        frameDurationMs = uiState.animationFrameDurationMs,
                        rangeStart = playbackRange.first,
                        rangeEnd = playbackRange.last,
                        rawRangeEnd = uiState.animationRangeEnd,
                        currentFrameHoldCount = vm.currentFrameHoldCount(),
                        isTimeLapseRecording = uiState.isTimeLapseRecording,
                        onTogglePlayback = { vm.onToggleAnimationPlayback() },
                        onPreviousFrame = { vm.onPreviousFrame() },
                        onNextFrame = { vm.onNextFrame() },
                        onAddFrame = { vm.onAddFrame() },
                        onToggleOnionSkin = { vm.onToggleOnionSkin() },
                        onSetOnionSkinPastCount = { vm.onSetOnionSkinPastCount(it) },
                        onSetOnionSkinFutureCount = { vm.onSetOnionSkinFutureCount(it) },
                        onSetLoopMode = { vm.onSetAnimationLoopMode(it) },
                        onSetFrameDurationMs = { vm.onSetAnimationFrameDurationMs(it) },
                        onSetRange = { start, end -> vm.onSetAnimationRange(start, end) },
                        onSetFrameHoldCount = { vm.onSetFrameHoldCount(it) },
                        onExport = { vm.exportAnimation() },
                        onToggleTimeLapse = { vm.onToggleTimeLapseRecording() },
                        onDismiss = { vm.onToggleAnimationMode() },
                    )
                }

                if (showToolOptions) {
                    ToolOptionsWindow(
                        stabilizerLevel = uiState.stabilizerLevel,
                        onSetStabilizerLevel = { vm.setStabilizerLevel(it) },
                        stabilizerAlgorithm = uiState.stabilizerAlgorithm,
                        onSetStabilizerAlgorithm = { vm.setStabilizerAlgorithm(it) },
                        // Both are null unless they mean something right now — the window shows the
                        // dials the tool in hand actually has, never a dead control.
                        magicWandTolerance =
                            uiState.magicWandTolerance.takeIf { uiState.selectionShape == SelectionShape.AUTOMATIC },
                        onSetMagicWandTolerance = { vm.onSetMagicWandTolerance(it) },
                        selectionFeatherPx = uiState.selection?.featherPx,
                        onSetSelectionFeather = { vm.onSetSelectionFeather(it) },
                        brushFlow = uiState.brushFlow.takeIf { uiState.activeBrushName != null },
                        onSetBrushFlow = { vm.setBrushFlow(it) },
                        brushOpacity = uiState.brushOpacity.takeIf { uiState.activeBrushName == null },
                        onSetBrushOpacity = { vm.setBrushOpacity(it) },
                        previewBrush = resolvePreviewBrush(uiState.activeBrushName, customBrushes),
                        brushColor = uiState.activeColor,
                        secondaryColor = uiState.secondaryColor,
                        colorSmudgeSettings = colorSmudgeSettings.takeIf { uiState.activeTool == Tool.SMUDGE },
                        onSetColorSmudgeMode = { vm.setColorSmudgeMode(it) },
                        onSetColorSmudgeRate = { vm.setColorSmudgeRate(it) },
                        onSetColorSmudgeColorRate = { vm.setColorSmudgeColorRate(it) },
                        onSetColorSmudgeChargeDecayRate = { vm.setColorSmudgeChargeDecayRate(it) },
                        onSetColorSmudgeDilution = { vm.setColorSmudgeDilution(it) },
                        onSetColorSmudgeRadius = { vm.setColorSmudgeRadius(it) },
                        onSetColorSmudgeOpacity = { vm.setColorSmudgeOpacity(it) },
                        onSetColorSmudgeAlphaCarry = { vm.setColorSmudgeAlphaCarry(it) },
                        onSetColorSmudgeSampleMerged = { vm.setColorSmudgeSampleMerged(it) },
                        symmetryMode = uiState.symmetryMode,
                        onSetSymmetryMode = { vm.onSetSymmetryMode(it) },
                        onDismiss = { showToolOptions = false },
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

                uiState.brushStudioDraft?.let { draft ->
                    BrushStudioWindow(
                        draft = draft,
                        brushColor = uiState.activeColor,
                        secondaryColor = uiState.secondaryColor,
                        isSaved = uiState.brushStudioEditingId != null,
                        onEdit = { edit -> vm.onEditBrushDraft(edit) },
                        onSave = { vm.onSaveBrushDraft() },
                        onDelete = { uiState.brushStudioEditingId?.let { vm.onDeleteCustomBrush(it) } },
                        onDismiss = { vm.onCloseBrushStudio() },
                    )
                }

                if (showBrushGallery) {
                    CustomBrushesWindow(
                        brushes = customBrushes,
                        activeBrushName = uiState.activeBrushName,
                        brushColor = uiState.activeColor,
                        secondaryColor = uiState.secondaryColor,
                        onSelect = { id -> vm.selectCustomBrush(id); showBrushGallery = false },
                        onEdit = { id -> showBrushGallery = false; vm.onOpenBrushStudio(id) },
                        onDelete = { id -> vm.onDeleteCustomBrush(id) },
                        onNew = { showBrushGallery = false; vm.onOpenBrushStudio(null) },
                        onDismiss = { showBrushGallery = false },
                    )
                }

                if (showBrushTipsManager) {
                    val allBrushAssets by vm.allInstalledBrushAssets.collectAsState()
                    val hiddenBrushTipIds by vm.hiddenBrushTipIds.collectAsState()
                    BrushTipsManagerWindow(
                        allBrushAssets = allBrushAssets,
                        hiddenIds = hiddenBrushTipIds,
                        onSetHidden = { id, hidden -> vm.setBrushTipHidden(id, hidden) },
                        onDismiss = { showBrushTipsManager = false },
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun BrushSizePad(vm: EditorViewModel, strings: AppStrings) {
    val state by vm.uiState.collectAsState()
    val density = LocalDensity.current
    var itemPx by remember { mutableFloatStateOf(120f) }
    // The pad is otherwise drag-only — a two-axis pointer gesture with no click, no keyboard
    // path, and nothing for TalkBack to announce beyond "brush size pad". A tap opens the same
    // value through an accessible slider (SizePickerDialog) instead, without touching the drag
    // gesture setters below it depends on.
    var showSizePicker by remember { mutableStateOf(false) }
    val feather = state.brushFeathering.coerceIn(0f, 1f)
    val hardnessPercent = ((1f - feather) * 100f).roundToInt()
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Size, above the preview: the number the drag's vertical axis is currently set to, read
        // at a glance without waiting for the drag to settle or opening SizePickerDialog.
        Text(
            text = "${state.brushSize.roundToInt()}px",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { itemPx = it.width.toFloat() }
                .clickable(onClickLabel = "Set brush size") { showSizePicker = true }
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The preview is the brush's actual footprint at its actual size, so what you see while
            // dragging is what the next stroke puts down.
            //
            // **Softness, drawn as softness.** A hard brush is a disc with a hard edge; a soft one
            // fades out over its radius; and the horizontal drag moves continuously between the two,
            // which is exactly what a radial gradient expresses. This used to be a flat 30%-alpha ring
            // around a shrunken solid core — two hard edges standing in for one soft one, which reads
            // as a translucent brush rather than a soft one. The pad ended up demonstrating
            // *opacity*, the one property it does not control.
            //
            // Nothing here is ever drawn translucent. Alpha is opacity, the pad does not set opacity,
            // and a preview that dims is a preview that lies about which dial you are on.
            //
            // The swatch behind the dab now matches the document's own canvas background rather than
            // this composable's own (transparent/rail) background, so the preview's contrast reads
            // the same as painting onto the actual document would.
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(color = state.canvasBackground)
                    val maxR = maxOf(minOf(size.width, size.height) / 2f, 1f)
                    val radius = (state.brushSize / 2f).coerceIn(1.5f, maxR)
                    // Where the solid core ends and the falloff begins: the whole radius when hard,
                    // the centre point when fully soft.
                    val core = (1f - feather).coerceIn(0f, 1f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            // Two stops at the same offset give the hard-edged case a genuine hard
                            // edge rather than a one-pixel ramp.
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
            // Hardness, to the right of the preview -- the number the drag's horizontal axis is
            // currently set to.
            Text(
                text = "$hardnessPercent%",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
    if (showSizePicker) {
        val customBrushes by vm.customBrushes.collectAsState()
        SizePickerDialog(
            currentSize = state.brushSize,
            onSizeChange = { vm.setBrushSize(it) },
            onDismiss = { showSizePicker = false },
            strings = strings,
            previewBrush = resolvePreviewBrush(state.activeBrushName, customBrushes),
            brushColor = state.activeColor,
            secondaryColor = state.secondaryColor,
            brushFeathering = feather,
        )
    }
}

/** A real painter's-palette glyph: silhouette + thumb hole + separate paint wells. */
@Composable
private fun PaletteSwatch(color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val body = Path().apply {
            moveTo(w * 0.88f, h * 0.42f)
            cubicTo(w * 0.88f, h * 0.16f, w * 0.67f, h * 0.05f, w * 0.43f, h * 0.07f)
            cubicTo(w * 0.18f, h * 0.09f, w * 0.06f, h * 0.28f, w * 0.08f, h * 0.52f)
            cubicTo(w * 0.10f, h * 0.78f, w * 0.30f, h * 0.94f, w * 0.52f, h * 0.84f)
            cubicTo(w * 0.64f, h * 0.78f, w * 0.59f, h * 0.66f, w * 0.72f, h * 0.63f)
            cubicTo(w * 0.82f, h * 0.61f, w * 0.88f, h * 0.54f, w * 0.88f, h * 0.42f)
            close()
        }
        val thumb = Path().apply { addOval(Rect(w * 0.55f, h * 0.62f, w * 0.72f, h * 0.80f)) }
        val silhouette = Path().apply { op(body, thumb, PathOperation.Difference) }
        drawPath(silhouette, color = Color.White.copy(alpha = 0.10f))
        drawPath(silhouette, color = Color.White.copy(alpha = 0.88f), style = Stroke(width = 1.5.dp.toPx()))

        fun paintWell(center: Offset, radius: Float, paint: Color) {
            drawCircle(Color.Black.copy(alpha = 0.72f), radius = radius + 1.dp.toPx(), center = center)
            drawCircle(paint, radius = radius, center = center)
            drawCircle(
                Color.White.copy(alpha = 0.55f), radius = radius, center = center,
                style = Stroke(width = 0.7.dp.toPx()),
            )
        }

        val r = minOf(w, h) * 0.085f
        paintWell(Offset(w * 0.31f, h * 0.29f), r, Cyan)
        paintWell(Offset(w * 0.51f, h * 0.23f), r, Color(0xFFFF2DAA))
        paintWell(Offset(w * 0.68f, h * 0.34f), r, Color(0xFFFFC107))
        paintWell(Offset(w * 0.34f, h * 0.56f), r * 1.35f, color)
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
 * Selection is its own group; every other non-flat-strip tool — the pen, node editing, and what
 * used to be separate retouching (Heal, Clone, Smudge) and focus/tone (Blur, Sharpen, Liquify,
 * Dodge, Burn, Colorize) accordions — now lives in one Vector group. Splitting them into three
 * mirrored Photoshop's own palette, but it meant three nested rails to open instead of one for
 * tools a user reaches for in the same breath.
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
private val VECTOR_TOOLS: List<Tool> = listOf(
    Tool.PEN, Tool.HEAL, Tool.CLONE,
    Tool.BLUR, Tool.SHARPEN, Tool.SMUDGE, Tool.LIQUIFY, Tool.DODGE, Tool.BURN, Tool.COLOR,
)

/** Each group's nested-rail id, which is also its classifier. */
private const val SELECT_ID = "grp.select"
private const val VECTOR_ID = "grp.vector"
private const val TRANSFORM_ID = "grp.transform"

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
    if (uiState.activeTool in SELECT_TOOLS) add(SELECT_ID)
    // Node editing is a vector mode rather than a Tool, so it lights its host separately.
    if (uiState.pathEditLayerId != null) add(VECTOR_ID)
    // Clone's second step. It lights once the tool is aimed, which is the state that decides whether
    // the next stroke copies anything — the item had declared this classifier since it was written
    // and nothing ever added it, so it faked the state in its label instead.

    // Modes and toggles that stay on until turned off.
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
        EditorPanel.TRANSFORM -> {
            add("adj.transform")
            add(TRANSFORM_ID)
        }
        EditorPanel.EXTENSIONS -> add("adj.extensions")
        else -> Unit
    }

    // (No animation entries. Play/pause, onion skin and the loop mode are controls inside the
    // Animation window now, and a window draws its own selected state — a classifier for an item
    // that no longer exists lights nothing and only survives to confuse the next reader.)

    // The current brush. `null` is the built-in round brush, which is why the host itself lights up
    // rather than a member of its list.
    if (uiState.activeBrushName == null) add("grp.brushes")
    com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes.presets
        .firstOrNull { it.name == uiState.activeBrushName }
        ?.let { add("brush.builtin.${it.name}") }
    brushes.firstOrNull { it.second == uiState.activeBrushName }?.let { add("brush.${it.first}") }
    customBrushes.firstOrNull { it.brush.name == uiState.activeBrushName }
        ?.let { add("brush.custom.${it.id}") }
    if (uiState.brushStudioDraft != null) add("brush.studio")

    // The layer being edited, and the two mode pickers — each always lights exactly one member,
    // because each is a choice among all of its options rather than an on/off.
    uiState.activeLayerId?.let { add("layer.$it") }
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
    // Collected via collectAsState() in the calling @Composable (GraffuxApp) -- this builder isn't
    // @Composable itself, so it can't collect the StateFlow directly (same reason screenCenter and
    // the colours below are passed in rather than read here).
    railExpansion: Map<String, Boolean>,
    /** How many azphalt extensions are installed — badged on the Run Extension item. */
    installedExtensionCount: Int,
    strings: AppStrings,
    navItemColor: Color,
    activeColor: Color,
    // Screen centre in px, resolved by the caller: this builder is not @Composable, so it can't
    // read LocalConfiguration/LocalDensity itself (same reason the colours above are passed in).
    screenCenter: Offset,
    onBlendMode: () -> Unit,
    onCurves: () -> Unit,
    onAddClicked: () -> Unit,
    onEditClicked: () -> Unit,
    onModelClicked: () -> Unit,
    modelWindowOpen: Boolean,
    onToolOptionsClicked: () -> Unit,
    toolOptionsOpen: Boolean,
    onForgetSelectionRequested: (String) -> Unit,
    showLayersRail: Boolean,
    showBrushRail: Boolean,
    onOpenBrushGallery: () -> Unit,
    onOpenBrushTipsManager: () -> Unit,
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

    /** A rail item that can be active. [hiddenMenu], when given, opens on a long-press (AzNavRail
     *  11.42's own long-press mechanic for a plain rail item -- a small popup of `listItem`s, not
     *  a direct action, since that's the only long-press hook a non-relocatable item has). */
    fun stateItem(
        id: String,
        text: String,
        content: Any?,
        shape: AzButtonShape? = null,
        hiddenMenu: (com.hereliesaz.aznavrail.HiddenMenuScope.() -> Unit)? = null,
        onClick: () -> Unit,
    ) =
        azRailItem(
            id = id, classifiers = setOf(id), text = text, content = content,
            color = railColor(id), shape = shape, onClick = onClick, hiddenMenu = hiddenMenu ?: {},
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
            color = railColor(id), shape = AzButtonShape.NONE_SQUARE,
            // Restores exactly as the user left it on reopen -- see EditorViewModel.railExpansion's
            // doc comment. AzNavRail 11.44 (this app's pin) has carried per-host onExpandedChange
            // since 10.11; nothing here ever actually read or wrote it before.
            initiallyExpanded = railExpansion[id] ?: false,
            onExpandedChange = { vm.onRailHostExpansionChanged(id, it) },
        )

    /**
     * A tool. Tapping selects it; tapping the tool already in hand clears back to [Tool.NONE], which
     * is Procreate's behaviour and the reason all eleven of these were the same six lines. The id
     * comes from [TOOL_IDS], not the call site, so the rail and [activeRailClassifiers] read one
     * mapping and cannot disagree about which item is lit.
     */
    fun toolItem(
        tool: Tool,
        text: String,
        content: Any?,
        hiddenMenu: (com.hereliesaz.aznavrail.HiddenMenuScope.() -> Unit)? = null,
    ) {
        val id = TOOL_IDS.getValue(tool)
        stateItem(id, text, content, hiddenMenu = hiddenMenu) {
            vm.setActiveTool(if (uiState.activeTool == tool) Tool.NONE else tool)
        }
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

    // ── Brush · Eraser ─────────────────────────────────────────────────────────────────────────────
    // First in the rail: the two tools a stroke starts and ends with, reached for more than anything
    // below them, drawn from the Graffux set (`GraffuxIcons`, generated by branding/icons/build.py)
    // rather than a general-purpose library — a broom stood in for the eraser and a squiggle for
    // smudge, because a library built for dashboards has no drawing for either.
    // Long-press opens Brush Studio on the brush currently in hand -- same "edit this brush" vs
    // "make a new one" resolution as the "Brush Studio" entry in the brush group below, and the
    // one long-press mechanic AzNavRail 11.42 gives a plain rail item (a small popup, not a
    // direct jump -- see stateItem's own doc comment for why).
    toolItem(
        Tool.BRUSH, uiState.activeBrushName ?: navStrings.brush, GraffuxIcons.Brush,
        hiddenMenu = {
            listItem("Brush Studio") {
                val editing = customBrushes.firstOrNull { it.brush.name == uiState.activeBrushName }?.id
                vm.onOpenBrushStudio(editing)
            }
        },
    )
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
        color = navItemColor, content = AzComposableContent { BrushSizePad(vm, strings) },
    )

    // ── Transform · Selection ──────────────────────────────────────────────────────────────────────
    // Transform leads the rest of the strip; the Selection nested rail sits below it.

    azNestedRail(
        id = TRANSFORM_ID, classifiers = setOf(TRANSFORM_ID),
        text = "Crop & Transform", content = GraffuxIcons.Crop,
        color = railColor(TRANSFORM_ID), shape = AzButtonShape.NONE_SQUARE,
        keepNestedRailOpen = true,
    ) {
        azRailItem(
            id = "adj.transform", classifiers = setOf("adj.transform"),
            text = "Move / Transform", content = GraffuxIcons.Move,
            color = railColor("adj.transform"), shape = AzButtonShape.NONE_SQUARE,
            onClick = { vm.onTransformClicked() },
        )
        TransformMode.entries.forEach { mode ->
            val id = "transform.${mode.name}"
            azRailItem(
                id = id, classifiers = setOf(id), text = mode.label,
                content = when (mode) {
                    TransformMode.FREEFORM -> GraffuxIcons.SelectTransform
                    TransformMode.DISTORT -> GraffuxIcons.PerspectiveTransform
                    TransformMode.WARP -> GraffuxIcons.PuppetWarp
                },
                color = railColor(id), shape = AzButtonShape.NONE_SQUARE,
                onClick = { vm.onSetTransformMode(mode) },
            )
        }
        if (uiState.transformMode != TransformMode.FREEFORM) {
            azRailItem(
                id = "transform.apply", text = "Apply",
                content = GraffuxIcons.Success, color = activeColor, shape = AzButtonShape.NONE_SQUARE,
                onClick = { vm.onApplyWarp() },
            )
            azRailItem(
                id = "transform.cancel", text = "Cancel",
                content = GraffuxIcons.Close, color = navItemColor, shape = AzButtonShape.NONE_SQUARE,
                onClick = { vm.onCancelWarp() },
            )
        }
    }

    azNestedRail(
        id = SELECT_ID, classifiers = setOf(SELECT_ID), text = "Selection", content = GraffuxIcons.SelectAll,
        color = railColor(SELECT_ID), shape = AzButtonShape.NONE_SQUARE,
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
                        onClick = { onForgetSelectionRequested(saved.name) },
                    )
                }
            }
        }
    }

    // The colour item shows the current colour itself, the way Procreate's swatch does, rather than
    // a generic palette glyph that says nothing about what you're about to paint with — filling a
    // painter's-palette silhouette ([PaletteSwatch]) rather than a plain square chip, so the shape
    // reads as "the paint you have loaded" on its own, not just by association with the picker it
    // opens. Layers is the other constant and has left this strip entirely — it is an unattached
    // floating host, the rail's nearest equivalent to Procreate's Layers panel.
    stateItem(
        id = "tool.color", text = navStrings.color,
        content = AzComposableContent { PaletteSwatch(uiState.activeColor) },
        // The active ring now supplies the state; no permanent border pretending to be selection.
        shape = AzButtonShape.NONE_SQUARE,
    ) { vm.onColorClicked() }

    // Procreate's ColorDrop: tap the canvas to flood-fill with the active colour. The icon must
    // match TOOL_CATALOG's (GraffuxIcons.Fill) -- the shortcuts sheet reads that catalogue, so a
    // different glyph here would show the tool differently depending on where you find it.
    toolItem(Tool.FILL, "Fill", GraffuxIcons.Fill)

    toolItem(Tool.TEXT, "Text", GraffuxIcons.Type)

    // ── The Vector group ──────────────────────────────────────────────────────────────────────────
    // Everything below the four constants above is Photoshop's inheritance rather than Procreate's.
    // It used to be grouped the way Photoshop groups it — vector, retouching, focus and tone, three
    // separate nested rails — but that meant three accordions to open for tools reached for in the
    // same breath, so they're one Vector group now.
    //
    // The flat top-level strip copied Procreate's *shape* — a row you don't have to open — while
    // carrying Photoshop's whole tool count, so it ran to thirteen items when the thing it was
    // imitating shows five, and none of the relationships between them were visible: Dodge and Burn
    // are one idea in two directions, Blur and Sharpen another, Heal and Clone both sample from
    // elsewhere on the layer. Flat, they read as thirteen unrelated buttons. Grouped, the strip is
    // short and each group is a sentence.
    //
    // Every tool keeps its id, its classifier and its behaviour — only where you reach it moves, so
    // nothing about a recorded stroke, a co-op peer or a saved project changes.
    //
    // Pen draws a real PATH shape rather than pixels, and node editing operates on the shape it
    // drew — the tool and the way you correct its output, which is why they sit together rather
    // than the editing controls living twenty items further down the rail as they used to.
    azNestedRail(
        id = VECTOR_ID, classifiers = setOf(VECTOR_ID), text = "Vector", content = GraffuxIcons.PenInk,
        color = railColor(VECTOR_ID), shape = AzButtonShape.NONE_SQUARE,
        reflectSelectionInParent = true,
    ) {
        nestedTool(Tool.PEN, "Pen", GraffuxIcons.PenInk)

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
        nestedTool(Tool.HEAL, "Heal", GraffuxIcons.Heal)
        nestedTool(Tool.CLONE, "Clone", GraffuxIcons.Stamp)
        // Setting the source is a canvas gesture (EditorScreen's pickingCloneSource/
        // onPickCloneSource), not this button -- while cloneSource is null the canvas itself is
        // already in aiming mode, so a "Tap to aim" item here had nothing to do when tapped (its
        // own onClick just reset an already-null source). Show this only once a source exists,
        // when it has a real job: clearing it so the next canvas tap can re-aim.
        if (uiState.activeTool == Tool.CLONE && uiState.cloneSource != null) {
            azRailItem(
                id = "tool.cloneSource", classifiers = setOf("tool.cloneSource"),
                text = "Re-aim",
                content = GraffuxIcons.FilterCloneSource, color = railColor("tool.cloneSource"),
                shape = AzButtonShape.NONE_SQUARE, fillColor = railColor("tool.cloneSource"),
                onClick = { vm.onResetCloneSource() },
            )
        }
        nestedTool(Tool.BLUR, "Blur", GraffuxIcons.Blur)
        nestedTool(Tool.SHARPEN, "Sharpen", GraffuxIcons.Sharpen)
        nestedTool(Tool.SMUDGE, "Smudge", GraffuxIcons.Smudge)
        nestedTool(Tool.LIQUIFY, "Liquify", GraffuxIcons.Liquify)
        nestedTool(Tool.DODGE, navStrings.dodge, GraffuxIcons.Dodge)
        nestedTool(Tool.BURN, navStrings.burn, GraffuxIcons.Burn)
        nestedTool(Tool.COLOR, "Colorize", GraffuxIcons.ColorDisc)
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
    // GuideIsometric rather than a cube: the Graffux set has no cube, and this is the one glyph in
    // it that draws a solid in three dimensions. Better a near-miss from the app's own set than a
    // stray icon from a library, which is how the rail ended up with a broom for the eraser.
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

    // No brush group in the main rail strip: every brush (round, built-in, custom, and every
    // installed extension) lives exclusively in the detached `grp.brushRail` below, alongside My
    // Brushes and Brush Studio -- see that host's own doc comment. There used to be a second,
    // near-identical `grp.brushes` collapsed rail group here too, which meant every brush the
    // user owned was reachable from two different places in the UI at once for no reason, and its
    // host button wore `GraffuxIcons.BrushLibrary` -- three stacked diamonds, a layers glyph, not
    // a brush -- as the main rail's one entry point into painting tools.
    //
    // `"grp.brushes"` survives as a bare classifier string (see ConfigureRailItems' own use of it
    // for "no extension selected") even with no rail item of that id anymore -- classifiers are
    // just semantic keys railColor()/activeRailClassifiers() look up, independent of whether a
    // host item shares the string.

    // Layers, shown directly in the rail as relocatable (drag-to-reorder) sub-items — the
    // Procreate layers-panel equivalent — instead of a separate floating LayersPanel. Each
    // item's own content IS the layer's thumbnail. uiState.layers is bottom-to-top (index 0
    // paints first, underneath everything); declared here top-first (reversed) so the item at
    // the top of the expanded group is the frontmost layer, matching the old LayersPanel's
    // convention and Photoshop/Procreate's own. A group layer's children render inside its own
    // nested rail (azRailRelocItem's nestedContent), a real recursive popup — not a flat
    // indented stand-in — so reordering stays scoped to siblings at each level.
    //
    // Unattached and pinned to the opposite side of the screen from the main rail, not a group
    // in the rail strip. Layers is the one host you keep reaching for *while* painting —
    // Procreate gives it a panel of its own for that reason — and as a rail group it sat behind
    // the same expand-collapse as every tool, so getting to a layer meant opening the rail and
    // pushing everything else aside. OPPOSITE locks it to the far edge of the screen, level with
    // the rail's own items, so it stays put and never drifts under a stray drag.
    //
    // Was FLOATING (draggable, screen-edge-docking, position persisted per launch). Switched to
    // OPPOSITE: FLOATING's docking/persistence machinery (AzNavRail 11.37/11.38's rewrite of it)
    // is where this host went missing entirely on a real device — a floating host is free to end
    // up rendered somewhere unreachable (off-screen, behind chrome, wherever a stale or malformed
    // persisted position points), which is exactly the failure mode this doc comment already
    // argued against ("stays put... never drifts") when FLOATING was chosen over it. OPPOSITE has
    // a fixed screen position by construction — there is no persisted coordinate for a bug in
    // that upstream machinery to corrupt — so it cannot go missing the same way. The cost is losing
    // drag-to-reposition and rail-to-rail docking, which this host never really wanted per the
    // comment above anyway.
    //
    // Square, not the circle every rail group wears: a panel on the opposite edge is not a rail
    // group, and the shape is what says so before the user reads a label. It also matches what
    // the host contains — layer thumbnails are rectangular images, and a circular clip crops the
    // corners off every one of them.
    //
    // Gated on `showLayersRail` (default true — this is core, not opt-in, unlike the toolbox
    // rails elsewhere in this file): the hamburger menu's "Layers rail" toggle exists purely as a
    // recovery switch. Toggling it off then on drops and recreates this host's AzNavRail-internal
    // state, which is the only way to un-stick a panel that manages to end up unreachable again —
    // the toggle is always reachable even when the panel it controls is not, unlike the old
    // `if (uiState.layers.isNotEmpty())` gate this replaced: hiding the whole host — button and
    // all — the moment the document had zero layers (a fresh project, or the last layer just
    // deleted) left no way back to it short of guessing to pick a drawing tool.
    if (showLayersRail) {
        azUnattachedHostItem(
            id = "grp.layers",
            text = strings.editor.layers,
            anchor = AzUnattachedAnchor.OPPOSITE,
            content = GraffuxIcons.Layers,
            color = navItemColor,
            shape = AzButtonShape.NONE_SQUARE,
            initiallyExpanded = railExpansion["grp.layers"] ?: false,
            onExpandedChange = { vm.onRailHostExpansionChanged("grp.layers", it) },
        )
        uiState.layers.filter { it.parentId == null }.reversed().forEach { layer ->
            renderLayerRailItem(
                layer, uiState, "grp.layers", vm, activeColor, navItemColor, strings,
                onBlendMode = onBlendMode, onEditClicked = onEditClicked, onCurves = onCurves,
            )
        }
        // Always present, not just when the document has zero layers: with one layer already in
        // place, the only other way to add a blank one was a four-finger-hold radial menu whose
        // "Layer" entry says nothing about layers, and nothing in the layers host itself, the rail
        // strip, or the dropdown ever added a plain paint layer. A fixed position at the bottom of
        // the stack (declared after every layer, since the host renders topmost-first), rather than
        // sorted in with the layers, so it's always where the last visit left it — and at the
        // bottom rather than the top so it doesn't shove every layer down a slot each time the list
        // is scanned top-to-bottom.
        azRailSubItem(
            id = "layer.add", hostId = "grp.layers", text = "Add Layer",
            content = GraffuxIcons.LayerAdd,
            color = navItemColor,
            onClick = { vm.onAddBlankLayer() },
        )
        uiState.activeLayerId?.let { activeId ->
            azRailSubItem(
                id = "layer.deleteActive", hostId = "grp.layers", text = "Delete Active Layer",
                content = GraffuxIcons.LayerDelete, color = navItemColor,
                onClick = {
                    val active = uiState.layers.firstOrNull { it.id == activeId }
                    if (active?.type == LayerType.GROUP) vm.onDeleteGroup(activeId)
                    else vm.onLayerRemoved(activeId)
                },
            )
        }
    }

    // Brushes, as a persistent quick-pick strip of thumbnails on the opposite screen edge from the
    // main rail -- the same OPPOSITE-anchored, always-reachable-while-painting pattern grp.layers
    // uses just above (see that host's own doc comment for why OPPOSITE rather than FLOATING).
    // Stacks below grp.layers in that column automatically; the two were never meant to compete for
    // the same reach the way an expand-collapse rail group would.
    //
    // The ONLY brush entry point in the app: there used to be a second, near-identical `grp.brushes`
    // group collapsed into the main rail strip too, which put every brush the user owned in two
    // different places in the UI. My Brushes and Brush Studio, which that group used to host, moved
    // down here alongside the flat per-brush entries (round/built-in/custom/every installed
    // extension) rather than living anywhere else. Every flat entry's own classifier
    // (`classifiers = setOf("brush.…")`) still matches what `ConfigureRailItems` (see its own
    // "no rail item of that id anymore" comment above) tracks as the active brush, so
    // railColor()/activeRailClassifiers() highlight the right one here with no separate bookkeeping.
    //
    // Each item's own content IS the brush's preview -- BrushPreview (BrushStudioWindow.kt),
    // already used as My Brushes' own gallery thumbnail, reused directly rather than rendering a
    // second thumbnail pipeline from scratch. Brush extensions (the `brushes` list) fall back to a
    // plain icon: resolving an installed extension's actual AzphaltBrush here would mean plumbing
    // its asset resolver through this builder for a preview alone, real work of its own rather than
    // a one-line addition -- left as a known gap rather than guessed at.
    //
    // Host icon is GraffuxIcons.Brush -- an actual paintbrush silhouette -- not BrushLibrary (three
    // stacked diamonds, which reads as a layers glyph, not a brush; a real user-reported defect).
    // Yes, this duplicates the Tool.BRUSH rail item's own icon; a host button that looks like a
    // brush is worth more than one that looks unique but wrong.
    if (showBrushRail) {
        azUnattachedHostItem(
            id = "grp.brushRail",
            text = "Brushes",
            anchor = AzUnattachedAnchor.OPPOSITE,
            content = GraffuxIcons.Brush,
            color = navItemColor,
            shape = AzButtonShape.NONE_SQUARE,
            initiallyExpanded = railExpansion["grp.brushRail"] ?: false,
            onExpandedChange = { vm.onRailHostExpansionChanged("grp.brushRail", it) },
        )
        azRailSubItem(
            id = "brushRail.round", hostId = "grp.brushRail", text = "Round",
            content = GraffuxIcons.BrushCursor,
            classifiers = setOf("brush.round"),
            color = railColor("grp.brushes"),
            onClick = { vm.selectBrushExtension(null) },
        )
        com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes.presets.forEach { preset ->
            azRailSubItem(
                id = "brushRail.builtin.${preset.name}", hostId = "grp.brushRail", text = preset.name,
                content = AzComposableContent {
                    BrushPreview(preset, uiState.activeColor, uiState.secondaryColor, height = 32.dp)
                },
                classifiers = setOf("brush.builtin.${preset.name}"),
                color = railColor("brush.builtin.${preset.name}"),
                shape = AzButtonShape.SQUARE,
                onClick = { vm.selectBuiltInBrush(preset.name) },
            )
        }
        customBrushes.forEach { custom ->
            azRailSubItem(
                id = "brushRail.custom.${custom.id}", hostId = "grp.brushRail", text = custom.brush.name,
                content = AzComposableContent {
                    BrushPreview(custom.brush, uiState.activeColor, uiState.secondaryColor, height = 32.dp)
                },
                classifiers = setOf("brush.custom.${custom.id}"),
                color = railColor("brush.custom.${custom.id}"),
                shape = AzButtonShape.SQUARE,
                onClick = { vm.selectCustomBrush(custom.id) },
            )
        }
        brushes.forEach { (id, name) ->
            azRailSubItem(
                id = "brushRail.ext.$id", hostId = "grp.brushRail", text = name,
                content = GraffuxIcons.BrushImport,
                classifiers = setOf("brush.$id"),
                color = railColor("brush.$id"),
                onClick = { vm.selectBrushExtension(id) },
            )
        }
        // A gallery over every saved custom brush — pick one, edit it, or delete it — distinct from
        // the flat per-brush entries above (which only ever select).
        subItem(
            id = "brush.gallery", hostId = "grp.brushRail", text = "My Brushes",
            content = GraffuxIcons.BrushLibrary,
            onClick = onOpenBrushGallery,
        )
        // Opens on the brush currently in hand, so "Brush Studio" reads as "edit this brush" when
        // one is selected and "make a new one" when it isn't — Procreate's own behaviour.
        stateSubItem(
            id = "brush.studio", hostId = "grp.brushRail", text = "Brush Studio",
            content = GraffuxIcons.BrushSettings, shape = AzButtonShape.SQUARE,
        ) {
            val editing = customBrushes.firstOrNull { it.brush.name == uiState.activeBrushName }?.id
            vm.onOpenBrushStudio(editing)
        }
        // An extension can bundle several brushes, and this strip is reached for constantly while
        // painting -- not every one of them earns a permanent slot in it. Opens the hide/show list
        // rather than hiding one directly (no long-press on a plain rail item yet -- see
        // BrushTipsManagerWindow's own doc comment).
        azRailSubItem(
            id = "brushRail.manage", hostId = "grp.brushRail", text = "Manage Brush Tips",
            content = GraffuxIcons.BrushSettings,
            color = navItemColor,
            onClick = onOpenBrushTipsManager,
        )
    }

    // Add and Align are document actions, not painting tools — they live in the drop-down (Procreate's
    // Actions menu), keeping the rail to the tools you reach for mid-stroke.

    // Extensions: runs an installed code extension's filter/tool, or applies an installed LUT.
    // "Run Extension", not "Extensions": the drop-down already has an "Extensions" entry, and it
    // opens the *manager*. This one runs an installed filter or LUT against the artwork. Two
    // windows under one name is how a user ends up in the wrong one.

    // The `?`. It opens the library's help overlay, which draws a card for every item currently in
    // the rail — its label, plus whatever RAIL_HELP has to say about it. The rail is this app's
    // entire interface and a good deal of it is conditional, which means the ordinary way to
    // discover a control is to already have caused it to appear. This is the way that isn't.
    //
    // Bordered, like everything else that opens something rather than doing something. Last in the
    // strip: it is the item you reach for when the others have not explained themselves.
    azHelpRailItem(
        id = "help", text = "Help", content = GraffuxIcons.Help,
        color = navItemColor, shape = AzButtonShape.SQUARE,
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
    azItemState(id = "adj.brush", badge = uiState.brushSize.roundToInt().toString(), persistentBadge = true)
    // The Brush tool's own glyph doesn't say a size at all -- unlike the drag pad above, whose
    // preview already is the size. Same number, so the two never disagree.
    azItemState(id = "tool.brush", badge = uiState.brushSize.roundToInt().toString(), persistentBadge = true)
    // Top-level count, matching what the host actually renders (the `parentId == null` filter
    // above) — not uiState.layers.size, which is the flat list and includes every group's
    // children. With one group of two children plus a loose layer, the flat count is 3 but the
    // host shows 2 rows; a badge that disagrees with what tapping it reveals is worse than none.
    azItemState(
        id = "grp.layers",
        badge = uiState.layers.count { it.parentId == null }.takeIf { it > 0 }?.toString(),
    )
    azItemState(
        id = "grp.brushRail",
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
    onCurves: () -> Unit,
) {
    val isGroup = layer.type == LayerType.GROUP
    val children = if (isGroup) uiState.layers.filter { it.parentId == layer.id } else emptyList()
    azRailRelocItem(
        id = "layer.${layer.id}", classifiers = setOf("layer.${layer.id}"),
        hostId = hostId,
        text = layer.name,
        // A raw android.graphics.Bitmap doesn't match any of AzNavRailButton's explicit content
        // branches (Color/Int-resource/String/ImageVector/Painter), so it fell through to Coil's
        // rememberAsyncImagePainter -- the wrong tool for pixels that are already decoded and, worse,
        // mutated/recycled in place by this app's own paint pipeline out from under whatever Coil
        // cached. Wrapping it as a BitmapPainter hits AzNavRailButton's direct Painter branch instead
        // -- a synchronous Image(painter) draw, no async load or cache to go stale.
        content = when {
            isGroup -> GraffuxIcons.LayerGroup
            layer.bitmap != null -> BitmapPainter(layer.bitmap!!.asImageBitmap())
            else -> GraffuxIcons.LayerThumbnail
        },
        // Square, like the floating host these live in — and unlike a circular clip, it shows the
        // layer's thumbnail whole instead of cropping its corners. A group layer hosts its children's
        // nested rail, so it keeps a border at rest; a leaf layer stays borderless at rest and grows
        // AzNavRailButton's active-state ring (11.19+) only while it's the active layer — that ring
        // draws regardless of a borderless shape once a button is highlighted, so no shape-flipping
        // hack is needed here the way earlier AzNavRail versions required.
        shape = if (isGroup) AzButtonShape.SQUARE else AzButtonShape.NONE_SQUARE,
        color = navItemColor,
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
                        onBlendMode = onBlendMode, onEditClicked = onEditClicked, onCurves = onCurves,
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
            listItem("Curves", on(onCurves))
            listItem(strings.editor.duplicate) { vm.onLayerDuplicated(layer.id) }
            listItem("Merge Down") { vm.onMergeDown(layer.id) }
            listItem("Flatten All") { vm.onFlattenAllLayers() }
            listItem("Group with Above") { vm.onGroupWithLayerAbove(layer.id) }
            listItem("Clear", on { vm.onClearLayer() })
            listItem(strings.editor.delete) { vm.onLayerRemoved(layer.id) }
        }

        // Only a top-level layer can be a frame in the first place (see AnimationFrames' doc
        // comment) -- offered regardless of isGroup, since a GROUP frame is exactly as valid a
        // frame as a plain one, and this only makes sense for a layer that would otherwise BE one.
        if (layer.parentId == null) {
            listItem(if (layer.isPinnedAcrossFrames) "Pinned Across Frames ✓" else "Pin Across Frames") {
                vm.onTogglePinnedAcrossFrames(layer.id)
            }
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
        // Update/Delete moved inside this gate with the rows above: they used to sit outside it
        // and ran for every layer regardless of shape != null, so a plain raster layer's menu
        // listed "Update"/"Delete" for every colour style anyone had ever made in the document —
        // exactly what this function's own doc comment says a raster layer's menu carries none of.
        if (shape != null) {
            listItem(if (fillLinked) "Fill: ${style.name} ✓" else "Fill: ${style.name}", on {
                vm.onApplyColorStyle(if (fillLinked) null else style.id)
            })
            // The stroke half of the pair. It had an intent and a StyleOps function and no way in.
            listItem(if (strokeLinked) "Stroke: ${style.name} ✓" else "Stroke: ${style.name}", on {
                vm.onApplyStrokeStyle(if (strokeLinked) null else style.id)
            })
            listItem("Update ${style.name} to Colour", on { vm.onUpdateColorStyleToActiveColor(style.id) })
            listItem("Delete ${style.name}") { vm.onDeleteColorStyle(style.id) }
        }
    }
    uiState.textStyles.forEach { style ->
        // Same fix as the colour loop above: Delete used to run unconditionally.
        if (params != null) {
            val linked = params.styleId == style.id
            listItem(if (linked) "Text: ${style.name} ✓" else "Text: ${style.name}", on {
                vm.onApplyTextStyle(if (linked) null else style.id)
            })
            // The text equivalent of "Update … to Colour", which until now did not exist: a text
            // token could be made and deleted but never edited.
            listItem("Update ${style.name} from Layer", on { vm.onUpdateTextStyleToActive(style.id) })
            listItem("Delete ${style.name}") { vm.onDeleteTextStyle(style.id) }
        }
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
