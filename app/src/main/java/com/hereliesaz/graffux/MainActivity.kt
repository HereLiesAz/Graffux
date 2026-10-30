package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.model.isContainer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import com.hereliesaz.graffitixr.design.theme.HotPink
import com.hereliesaz.graffitixr.design.theme.Amber
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import com.hereliesaz.graffitixr.feature.editor.ProjectGateDialog
import com.hereliesaz.graffitixr.feature.editor.gpu.GpuTuningController
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.unit.IntOffset
import com.hereliesaz.graffitixr.feature.editor.LocalChromeHidden
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.hereliesaz.graffitixr.common.photo.PhotoSliders
import com.hereliesaz.graffitixr.common.photo.PhotoTool
import androidx.compose.ui.draw.alpha
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.hereliesaz.graffitixr.feature.editor.NoticeKind
import com.hereliesaz.graffitixr.common.model.ConstraintAnchor
import com.hereliesaz.graffitixr.common.model.LayoutAlign
import com.hereliesaz.graffitixr.common.model.LayoutDirection
import com.hereliesaz.graffitixr.common.model.BlendMode
import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LinkOps
import com.hereliesaz.graffitixr.common.model.supportsAlphaLock
import com.hereliesaz.graffitixr.common.model.ProjectFile
import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.ShapeKind
import com.hereliesaz.graffitixr.common.model.CanvasViewport
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
import com.hereliesaz.graffitixr.feature.editor.BackgroundColorDialog
import com.hereliesaz.graffitixr.data.brush.CustomBrush
import com.hereliesaz.graffitixr.feature.editor.BlendModePicker
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
import com.hereliesaz.graffitixr.feature.editor.threed.SpatialSketchWorkspace
import com.hereliesaz.graffitixr.feature.editor.PolygonSidesDialog
import com.hereliesaz.graffitixr.feature.editor.FigmaWindow
import com.hereliesaz.graffitixr.feature.editor.ReferenceWindow
import com.hereliesaz.graffitixr.feature.editor.ShapeSizeDialog
import com.hereliesaz.graffitixr.feature.editor.StoreChooserDialog
import com.hereliesaz.graffitixr.feature.editor.StoreTab
import com.hereliesaz.graffitixr.feature.editor.StoreWindow
import com.hereliesaz.graffitixr.feature.editor.TextEditDialog
import com.hereliesaz.graffitixr.feature.editor.VectorStrokeDialog
import com.hereliesaz.graffitixr.feature.editor.toModelBlendMode
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Graffux entry point — hosts the shared [EditorScreen] (the single source of truth for the
 * multi-layer image editor, migrated from GraffitiXR into :feature:editor). The Hilt-provided
 * [EditorViewModel] and its whole dependency graph (core modules + native bridge) resolve here; the
 * screen forces DESIGN mode, so no co-op is involved.
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
        // The editor is immersive: system bars must never cover the rulers or brush carousel.
        // They can still be revealed temporarily by swiping from a screen edge.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        enterImmersiveMode()
        // Launch intents are acted on once. A recreation (rotation, dark-mode or locale change)
        // hands back the same original intent with a non-null savedInstanceState; replaying it would
        // add the shared image to the project a second time and re-raise an install prompt the user
        // already answered.
        val firstLaunch = savedInstanceState == null
        val sharedImage = if (firstLaunch) incomingImageUri(intent) else null
        val azpInstallUrl = if (firstLaunch) azphaltInstallUrl(intent) else null
        setContent {
            // The app's own theme, not a bare `MaterialTheme`. Graffux is a dark app, but a bare
            // MaterialTheme() takes Material 3's default **light** scheme, whose `onSurface` is a
            // near-black neutral — so every piece of chrome that took its colour from the theme
            // rather than setting one explicitly drew dark on dark. The file-operations dropdown was
            // the worst of it: unreadable. GraffitiXRTheme supplies the dark scheme this UI was drawn
            // against plus the brand palette.
            GraffitiXRTheme {
                GraffuxApp(sharedImageUri = sharedImage, azphaltInstallUrl = azpInstallUrl)
                LaunchUpdatePrompt()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    /** Restore immersive drawing after an external picker or other window takes focus. */
    private fun enterImmersiveMode() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // GPU calibration pauses while the app is off screen (Home, or the system file picker covering
    // it) and resumes when it is back; thermal polling and the ADPF hint session follow the same.
    override fun onStart() {
        super.onStart()
        GpuTuningController.get(this).onAppVisible()
    }

    override fun onStop() {
        GpuTuningController.get(this).onAppHidden()
        super.onStop()
    }

    // Memory pressure shrinks the GPU's resident layer copies (caches of the CPU layers).
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        GpuTuningController.get(this).onTrimMemory(level)
    }
}

/**
 * Extracts a single image [Uri] from an inbound `ACTION_SEND` share intent, or null if this launch
 * isn't one. Only `content://` streams are taken: a sender naming a `file://` path would have
 * Graffux open that path with Graffux's own file access.
 */
private fun incomingImageUri(intent: Intent?): Uri? {
    if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return null
    return IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        ?.takeIf { it.scheme == "content" }
}

/**
 * Extracts an azphalt package download URL from an `azphalt://install` deep link, or null if this
 * launch isn't one or the URL isn't an acceptable `https` address (see [parseAzphaltInstallTarget]).
 * The web storefront redirects here after a purchase; the URI's `url` query parameter carries the
 * HTTPS download URL for the `.azp` package.
 */
private fun azphaltInstallUrl(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_VIEW) return null
    val data = intent.data ?: return null
    if (data.scheme != "azphalt" || data.host != "install") return null
    return parseAzphaltInstallTarget(data.getQueryParameter("url"))?.url
}

// The brush-size range (MIN_BRUSH_SIZE..MAX_BRUSH_SIZE) the edge slider and the hero card's Size
// slider both map onto lives in CarouselHeroAdjustments.kt.

/** Floor for brush opacity: a fully transparent brush paints nothing and just reads as a broken tool. */
private const val MIN_BRUSH_ALPHA = 0.05f

/**
 * The four vector-shape editing dialogs LayerOptionsDialog's own submenu opens — Stroke Width,
 * Corner Radius, Shape Size, Polygon Sides. One tap on that submenu opens exactly one of these at
 * a time, so unlike most of this file's other `show*` dialog flags (several of which deliberately
 * stack — see the `if (showLayerOptionsDialog)` block's own comment on FloatingWindow-style
 * dialogs never blocking each other), these four are genuinely mutually exclusive and collapse
 * into one slot. LayerOptionsDialog itself stays a separate flag: it's the parent these open on
 * top of, and stays open while any of them is up.
 */
private enum class ShapeEditDialog { STROKE, CORNER, SHAPE_SIZE, SIDES }

@Composable
private fun GraffuxApp(sharedImageUri: Uri?, azphaltInstallUrl: String? = null) {
    val vm: EditorViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val uiState by vm.uiState.collectAsState()
    val activeInkUtensil by vm.activeInkUtensil.collectAsState()
    val projectGate by vm.projectGate.collectAsState()
    val railExpansion by vm.railExpansion.collectAsState()
    val colorSmudgeSettings by vm.colorSmudgeSettings.collectAsState()
    val allInstalledExtensions by vm.allInstalledExtensions.collectAsState()
    val modelState by vm.modelState.collectAsState()
    val spatialStrokes by vm.spatialStrokes.collectAsState()
    val spatialGuide by vm.spatialGuide.collectAsState()
    val projects by vm.projects.collectAsState()
    val openScreenState by vm.openScreenState.collectAsState()
    val strings = rememberAppStrings()
    val scope = rememberCoroutineScope()

    // Failures that used to be a Toast, or — worse — a logcat line: a message at the top of the
    // screen (MessageHost), which a WARNING keeps there until it is read.
    val alerts = rememberMessageController()
    // A save or project creation that failed (OOM included) is reported, never fatal.
    LaunchedEffect(vm) {
        vm.saveErrors.collect { message ->
            alerts.show(kind = MessageKind.WARNING, title = strings.messages.saveFailed, message = message)
        }
    }
    // Every other editor notice, confirmation or failure, takes the same road.
    val noticeOpenContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(vm) {
        vm.notices.collect { notice ->
            val file = notice.openUri
            alerts.show(
                kind = if (notice.kind == NoticeKind.WARNING) MessageKind.WARNING else MessageKind.NOTICE,
                message = notice.text,
                // An export in Downloads: offer to open it right there.
                actionLabel = if (file != null) strings.messages.open else null,
                onAction = file?.let { uri ->
                    {
                        val view = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, notice.mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            noticeOpenContext.startActivity(view)
                        } catch (_: android.content.ActivityNotFoundException) {
                            alerts.show(kind = MessageKind.NOTICE, message = strings.messages.noAppOpens)
                        }
                    }
                },
            )
        }
    }
    // The read-only warning for a newer-format project is retained state, not a notice: on a cold
    // start's auto-reopen it is set before this collector exists. Shown once, then consumed.
    LaunchedEffect(vm) {
        vm.readOnlyNotice.collect { notice ->
            if (notice != null) {
                alerts.show(kind = MessageKind.WARNING, message = notice.text)
                vm.consumeReadOnlyNotice(notice)
            }
        }
    }
    val noticeContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        HardwareFloorNotice.takeOnce(noticeContext)?.let { alerts.show(kind = MessageKind.NOTICE, message = it) }
    }

    // The shortcuts sheet: shut, a swipe strip along the bottom edge (see EdgeSheet).
    var shortcutsOpen by rememberSaveable { mutableStateOf(false) }
    val toolUsage by vm.toolUsage.collectAsState()
    val favoriteTools by vm.favoriteTools.collectAsState()

    var showSettings by rememberSaveable { mutableStateOf(false) }
    // The logo menu: open state, which page it shows, and a reopen request (see the menu).
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var showViewMenu by rememberSaveable { mutableStateOf(false) }
    var showPosterMenu by rememberSaveable { mutableStateOf(false) }
    var posterChoices by rememberSaveable(stateSaver = PosterChoicesSaver) { mutableStateOf(PosterChoices()) }
    // The brush's long-press menu, a layer's menu (by layer id), and the About reader.
    var brushMenuOpen by rememberSaveable { mutableStateOf(false) }
    var layerMenuFor by rememberSaveable { mutableStateOf<String?>(null) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showGestureHelp by rememberSaveable { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    var showDocDialog by rememberSaveable { mutableStateOf(false) }
    var showBlendDialog by rememberSaveable { mutableStateOf(false) }
    var showCurvesDialog by rememberSaveable { mutableStateOf(false) }
    var activeShapeDialog by rememberSaveable { mutableStateOf<ShapeEditDialog?>(null) }
    var showBrushGallery by rememberSaveable { mutableStateOf(false) }
    var showBrushTipsManager by rememberSaveable { mutableStateOf(false) }
    var manualEditTextId by rememberSaveable { mutableStateOf<String?>(null) }
    var showBgDialog by rememberSaveable { mutableStateOf(false) }
    // Procreate-style windows: things that used to be a wall of always-visible rail buttons now
    // live behind a single rail item that opens one of these instead.
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showLayerOptionsDialog by rememberSaveable { mutableStateOf(false) }
    // One window, two tabs (StoreWindow's own doc comment) — showStore alone gates it; storeTab
    // says which tab it opens on, set by whichever entry point (Manage/Get Extensions, the
    // Extensions toggle, "Add-ons…") raised showStore, and freely switchable once open.
    var showStore by rememberSaveable { mutableStateOf(false) }
    var storeTab by rememberSaveable { mutableStateOf(StoreTab.BROWSE) }
    var showStoreChooser by rememberSaveable { mutableStateOf(false) }
    var showModelDialog by rememberSaveable { mutableStateOf(false) }
    var showSpatialSketch by rememberSaveable { mutableStateOf(false) }
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var showOpenDialog by rememberSaveable { mutableStateOf(false) }
    var showReferenceWindow by rememberSaveable { mutableStateOf(false) }
    // A viewing aid, not project state (see ReferenceWindow's own doc comment) — held here, not
    // in the ViewModel, for the same reason.
    var referenceImageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var showFigmaWindow by rememberSaveable { mutableStateOf(false) }
    // Non-base workspaces get their own floating toolboxes (FloatingToolbox); View… in the logo
    // menu decides which are present.
    var showAnimationRail by rememberSaveable { mutableStateOf(false) }
    var showModelRail by rememberSaveable { mutableStateOf(false) }
    var showReferenceRail by rememberSaveable { mutableStateOf(false) }
    var showFigmaRail by rememberSaveable { mutableStateOf(false) }
    var showExtensionsRail by rememberSaveable { mutableStateOf(false) }
    // Unlike the toolboxes above, on by default: the layers panel is core, not opt-in.
    var showLayersRail by rememberSaveable { mutableStateOf(true) }
    // The bottom carousel (BottomCarousel.kt): additive to the Tool Options window, not a
    // replacement. Shown by default; the "Carousel" toggle in the areas dropdown removes it
    // outright, and its own chevron collapses it to a single button when the canvas needs the room.
    var showCarousel by rememberSaveable { mutableStateOf(true) }
    // Starts shut: only the grab pill, until a swipe up brings the tab list.
    var carouselUi by rememberSaveable(stateSaver = CarouselUi.Saver) {
        mutableStateOf(CarouselUi(CarouselCategory.BRUSHES, sheetOpen = false, showingCarousel = false))
    }
    // The photo sheet (Adjust on the rail): which tab it showed last, reopened on the next entry.
    var lastPhotoTab by rememberSaveable { mutableStateOf<PhotoTab?>(null) }
    val photoDrag = remember { PhotoDrag() }
    val photoSheet = rememberSaveable(saver = PhotoSheetState.Saver) { PhotoSheetState() }
    val photoHistogram by vm.photoHistogram.collectAsState()
    photoSheet.histogram = photoHistogram
    // HSL's eyedropper and Curves' histogram belong to their tabs.
    val photoTabShown = PhotoTab.ofKey(carouselUi.tab).takeIf { carouselUi.photo && carouselUi.showingCarousel }
    val cropActions = remember(vm) { vm.cropActions() }
    val retouchActions = remember(vm) { vm.retouchActions() }
    val retouchState = rememberSaveable(saver = RetouchState.Saver) { RetouchState() }
    val maskOverlay by vm.maskOverlay.collectAsState()
    val maskThumbs by vm.maskThumbs.collectAsState()
    LaunchedEffect(photoTabShown) {
        if (photoTabShown != PhotoTab.LOCAL && retouchState.overlay) {
            retouchState.overlay = false
            vm.setMaskOverlay(false)
        }
    }
    val canvasCamera = CanvasCamera(uiState.viewportZoom, uiState.viewportOffset, uiState.viewportRotation)
    // The Crop tab shows the whole photo under the crop frame; elsewhere the crop is applied.
    LaunchedEffect(photoTabShown) { vm.setCropEditing(photoTabShown == PhotoTab.CROP) }
    LaunchedEffect(photoTabShown) {
        if (photoTabShown != PhotoTab.HSL && photoSheet.eyedropperArmed) {
            photoSheet.eyedropperArmed = false
            vm.disarmPhotoEyedropper()
        }
    }
    LaunchedEffect(photoTabShown, uiState.undoCount, uiState.redoCount, uiState.layers.size) {
        if (photoTabShown == PhotoTab.CURVES) {
            kotlinx.coroutines.delay(EFFECT_SNAPSHOT_DEBOUNCE_MS)
            vm.requestPhotoHistogram()
        }
    }
    val photoActions = remember(vm) { vm.photoActions() }
    // Any tool picked puts the sheet back on brushes: the photo tabs are a mode of the sheet.
    LaunchedEffect(uiState.activeTool) {
        if (uiState.activeTool != Tool.NONE && carouselUi.photo) carouselUi = carouselUi.copy(photo = false, tab = null)
    }
    // Arming the brush or eraser puts the sheet on brushes; nothing else is listed with one in hand.
    LaunchedEffect(uiState.activeTool) {
        if (uiState.activeTool in BRUSH_CONTEXT_TOOLS &&
            carouselContextOf(carouselUi.category) != CarouselContext.BRUSHES
        ) {
            carouselUi = carouselUi.copy(category = CarouselCategory.BRUSHES, tab = null)
        }
    }
    val carouselFavorites by vm.carouselFavorites.collectAsState()
    // The design tools (the rail's Frame circle): the Frame tool in hand turns the sheet into the
    // design tabs, or on a tablet / desktop docks the properties in a side panel instead (the
    // "Show properties in" setting).
    val designMode = uiState.activeTool == Tool.FRAME
    var designTab by rememberSaveable { mutableStateOf(DesignTab.FRAME) }
    val storedPlacement by settingsVm.propertiesPlacement.collectAsState()
    val propertiesPlacement =
        PropertiesPlacement.resolve(storedPlacement, LocalConfiguration.current.smallestScreenWidthDp)
    val panelDocked = designMode && propertiesPlacement == PropertiesPlacement.SIDE_PANEL
    val designActions = remember(vm) { vm.designActions() }
    val designLibraryActions = remember(vm) { vm.designLibraryActions() }
    val designLibrary = if (designMode) designLibraryOf(uiState) else DesignLibrary()
    val designExportActions = remember(vm) { vm.designExportActions() }
    val designExport = if (designMode) {
        DesignExportContext(
            targetName = vm.designExportFrameId()
                ?.let { id -> uiState.layers.firstOrNull { it.id == id }?.name } ?: "The page",
            hasFrames = uiState.layers.any { it.type == com.hereliesaz.graffitixr.common.model.LayerType.FRAME },
            actions = designExportActions,
        )
    } else {
        null
    }
    val designTarget = if (designMode) designTargetIn(uiState) { vm.designBox(it) } else null
    // Picking up the Frame tool opens the sheet on the design tab (unless the side panel has them).
    LaunchedEffect(designMode, panelDocked) {
        if (designMode && !panelDocked) {
            showCarousel = true
            carouselUi = carouselUi.copy(sheetOpen = true, showingCarousel = true, photo = false)
        }
    }
    // Selecting a main-rail tool group must reveal its carousel, even if an editor panel is open.
    // The ordinary carousel pages retain the original hide-while-panel-open behavior.
    val carouselOnScreen = showCarousel && !uiState.hideUiForCapture && !panelDocked &&
        (uiState.activePanel == EditorPanel.NONE || carouselUi.category.isToolGroup || designMode)
    // The hero card "More" grew, shared by the carousel's page and the expanded card's page in front.
    val heroExpansion = remember { HeroExpansion() }
    LaunchedEffect(carouselOnScreen) { if (!carouselOnScreen) heroExpansion.collapse() }
    // The name confirmed in the Save dialog, held while the system location picker is up — the
    // picker hands back a Uri and nothing else, so the name has to survive the round trip.
    var pendingSaveName by rememberSaveable { mutableStateOf<String?>(null) }
    // "Forget a Selection" is the one delete in the app with no undo (savedSelections isn't part
    // of the layers-only history) and, before this, no confirmation either — every other delete
    // in the app is one or the other. Held here so the dialog composes with the others.
    var pendingForgetSelectionName by rememberSaveable { mutableStateOf<String?>(null) }

    // System Back closes the topmost open piece of chrome instead of leaving the app. Settings,
    // the project gate and the expanded hero card register their own handlers later in the
    // composition, so they take precedence while they are up; AlertDialogs handle Back themselves.
    val windowOrder = remember { WindowOpenOrder() }
    // Floating windows stack in the order they opened (FloatingWindow's z-index), so Back closes
    // them latest-opened first, not in a fixed order.
    val windowLayers = windowOrder.topFirst(
        listOf(
            "spatial" to BackLayer(showSpatialSketch) { showSpatialSketch = false },
            "store" to BackLayer(showStore) { showStore = false },
            "open" to BackLayer(showOpenDialog) { showOpenDialog = false },
            "reference" to BackLayer(showReferenceWindow) { showReferenceWindow = false },
            "figma" to BackLayer(showFigmaWindow) { showFigmaWindow = false },
            "brushes" to BackLayer(showBrushGallery) { showBrushGallery = false },
            "tips" to BackLayer(showBrushTipsManager) { showBrushTipsManager = false },
            "add" to BackLayer(showAddDialog) { showAddDialog = false },
            "layerOptions" to BackLayer(showLayerOptionsDialog) { showLayerOptionsDialog = false },
        ),
    )
    val backLayers = listOf(
        BackLayer(showPosterMenu) { showPosterMenu = false },
        BackLayer(showViewMenu) { showViewMenu = false },
        BackLayer(menuOpen) { menuOpen = false },
        BackLayer(brushMenuOpen) { brushMenuOpen = false },
        BackLayer(layerMenuFor != null) { layerMenuFor = null },
        BackLayer(showAbout) { showAbout = false },
        BackLayer(showGestureHelp) { showGestureHelp = false },
    ) + windowLayers + listOf(
        BackLayer(shortcutsOpen) { shortcutsOpen = false },
        BackLayer(uiState.activePanel != EditorPanel.NONE) { vm.onDismissPanel() },
        BackLayer(carouselUi.sheetOpen) { carouselUi = carouselUi.copy(sheetOpen = false, showingCarousel = false) },
    )
    androidx.activity.compose.BackHandler(enabled = backLayers.any { it.open }) { backLayers.topmost()?.close() }

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
    // Saveable, so an open prompt survives a rotation (the intent is no longer replayed to refill it).
    var pendingAzphaltInstallUrl by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(azphaltInstallUrl) {
        if (azphaltInstallUrl != null) pendingAzphaltInstallUrl = azphaltInstallUrl
    }
    pendingAzphaltInstallUrl?.let { url ->
        val target = parseAzphaltInstallTarget(url)
        if (target == null) {
            pendingAzphaltInstallUrl = null
        } else {
            ConfirmDialog(
                title = "Install extension?",
                message = "Something wants Graffux to download and install an extension from:\n\n" +
                    "${target.host}\n\nFull address:\n${truncateInstallUrl(url)}\n\nExtensions can add " +
                    "code that runs inside Graffux. Only install one if you trust where this came from.",
                confirmLabel = "Install",
                onConfirm = { vm.installExtensionFromUrl(url); pendingAzphaltInstallUrl = null },
                onDismiss = { pendingAzphaltInstallUrl = null },
            )
        }
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

    // Images chosen in the new-project dialog; they join the project as layers once it exists.
    var gateImages by rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.listSaver<List<Uri>, Uri>({ it }, { it }),
    ) { mutableStateOf<List<Uri>>(emptyList()) }
    val gateImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> gateImages = uris }

    val referencePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { referenceImageUri = it } }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.onImportDocument(it) } }

    // An extension (.azp) picked by hand: the user chose the file, so it installs straight away,
    // through the same AzpInstaller verification as every other path.
    val extensionFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.installExtensionFromUri(it) } }
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
    var pendingStoreHandoff by remember { mutableStateOf<StoreHandoffInstall?>(null) }
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
            // A store app's result is confirmed like a link: the app that answered may not be the
            // store the user meant.
            if (packages.isNotEmpty()) pendingStoreHandoff = StoreHandoffInstall(packages, metadata)
        }
    }
    pendingStoreHandoff?.let { pending ->
        val what = pending.metadata?.id ?: "${pending.packages.size} extension(s)"
        ConfirmDialog(
            title = "Install extension?",
            message = "The store app returned $what for Graffux to install.\n\nExtensions can add " +
                "code that runs inside Graffux. Only install it if you chose it in a store you trust.",
            confirmLabel = "Install",
            onConfirm = {
                pending.packages.forEach { vm.installExtensionFromUri(it, pending.metadata) }
                pendingStoreHandoff = null
            },
            onDismiss = { pendingStoreHandoff = null },
        )
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

    // 3D ink exports geometry rather than a screenshot; the SAF picker owns the destination.
    val spatialGltfExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("model/gltf+json")
    ) { uri -> uri?.let { vm.exportSpatialGltf(it) } }

    val brushes by vm.installedBrushes.collectAsState()
    val brushPreviews by vm.installedBrushPreviews.collectAsState()
    val brushTips by vm.installedBrushTips.collectAsState()
    val runnableExtensions by vm.installedExtensions.collectAsState()
    val extensionIcons by vm.installedExtensionIcons.collectAsState()
    val extensionEffects = remember(runnableExtensions) { extensionEffectsOf(runnableExtensions, vm::hasUsableLut) }
    val customBrushes by vm.customBrushes.collectAsState()

    // Per-item carousel settings (CarouselItemSettingsPlan): whatever item is in hand — however it
    // was picked — gets its own saved Size/Flow/Opacity/Softness/Strength, and any change to the live
    // values while it is in hand (hero slider, Tool Options, size HUD) is saved as that item's.
    val carouselItemSettings by vm.carouselItemSettings.collectAsState()
    LaunchedEffect(Unit) { vm.loadCarouselItemSettings() }
    val carouselStateInputs = CarouselInputs(
        activeTool = uiState.activeTool,
        activeBrushName = uiState.activeBrushName,
        activeInkUtensil = activeInkUtensil,
        builtInBrushes = com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes.presets,
        customBrushes = customBrushes.map { it.id to it.brush },
        extensionBrushes = brushes,
        stabilizerLevel = uiState.stabilizerLevel,
        stabilizerAlgorithm = uiState.stabilizerAlgorithm,
        smudgeMode = colorSmudgeSettings.mode,
        selectionShape = uiState.selectionShape,
    )
    val activeSettingsKey = activeCarouselSettingsKey(carouselStateInputs)
    // Tool Options (the rail item, or a tap on the brush-size pad): bring the carousel up on the
    // page holding what is in hand and grow that item's card. Pressed again while it is grown, it
    // shrinks it — the rail item is a toggle, as it was for the old window.
    val openToolOptionsCard: () -> Unit = {
        val target = toolOptionsTarget(carouselStateInputs)
        if (heroExpansion.expandedKey != null) {
            heroExpansion.collapse()
        } else if (target != null) {
            showCarousel = true
            carouselUi = carouselUi.copy(category = target.first, sheetOpen = true, tab = null, showingCarousel = true)
            heroExpansion.request(target.second)
        }
    }
    val liveItemSettings = com.hereliesaz.graffitixr.common.model.CarouselItemSettings(
        size = uiState.brushSize,
        flow = uiState.brushFlow,
        opacity = uiState.brushOpacity,
        softness = uiState.brushFeathering,
        strength = colorSmudgeSettings.smudgeRate,
    )
    val itemSettingsSync = remember { CarouselItemSettingsSync() }
    LaunchedEffect(activeSettingsKey, liveItemSettings, carouselItemSettings) {
        when (val d = itemSettingsSync.observe(activeSettingsKey, liveItemSettings, carouselItemSettings)) {
            is ItemSettingsDecision.Apply -> vm.applyCarouselItemSettings(d.settings, d.includeStrength)
            is ItemSettingsDecision.Save -> vm.setCarouselItemSettings(d.key, d.settings)
            ItemSettingsDecision.None -> Unit
        }
    }

    // The menu's "Add-ons…" entry point. In-app browse (StoreWindow's Browse tab) is
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
            // Through the chooser, so the user picks which store app receives the inventory; an
            // implicit launch would hand it to any app that registered the action.
            storeBrowser.launch(
                android.content.Intent.createChooser(
                    AzphaltStoreHandoff.browseIntent(context.packageName, vm.extensionStateInventory()),
                    "Open the Azphalt Store with",
                )
            )
        } else {
            // Play first; a device without Play falls through to the web storefront, which is where
            // the app is distributed anyway. Either beats a dead button.
            runCatching { context.startActivity(AzphaltStoreHandoff.installStoreAppIntent()) }
                .onFailure {
                    runCatching { context.startActivity(AzphaltStoreHandoff.webStoreIntent()) }
                        .onFailure {
                            alerts.show(
                                kind = MessageKind.WARNING,
                                title = "No way to reach the Azphalt Store",
                                message = "This device has no store app, no Play, and no browser " +
                                    "to open azphalt.store with. Extensions can still be installed " +
                                    "by hand from a file: Install extension from file… in the menu at the top right.",
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
                    kind = MessageKind.WARNING,
                    title = "No browser",
                    message = "Nothing on this device can open azphalt.store.",
                )
            }
    }

    // What the rails light: where you are, and (secondary) what is true of it. One set, read by the
    // rails and by the tests (RailClassifiersTest), so the two cannot disagree.
    val activeClassifiers = activeRailClassifiers(
        RailClassifierInputs(
            uiState, brushes, customBrushes,
            modelWindowOpen = showModelDialog,
            activeInkUtensil = activeInkUtensil,
        ),
    ).toMutableSet().apply {
        if (uiState.isAnimationMode || uiState.isTimeLapseRecording) add("area.animation")
        if (showModelDialog) add("area.model")
        if (carouselOnScreen && carouselUi.sheetOpen && !carouselUi.photo) {
            carouselGroupRailId(carouselUi.category)?.let(::add)
        }
        if (carouselOnScreen && carouselUi.photo) add(PHOTO_RAIL_ID)
        if (showReferenceWindow) add("area.reference")
        if (showFigmaWindow) add("area.figma")
        if (uiState.activePanel == EditorPanel.EXTENSIONS || showStore) add("area.extensions")
    }.toSet()
    val secondaryClassifiers = secondaryRailClassifiers(uiState)

    // Procreate's history gestures are installed HERE, above the whole app, not inside EditorScreen,
    // so a two- or three-finger tap works wherever the fingers land — on the rails and windows too.
    // It consumes nothing, so every control underneath still behaves exactly as before.
    val strokeGate = remember { StrokeGate() }
    // A finger on the canvas hides every piece of chrome at once and closes whatever was open; the
    // rails and the sheet's pill come back DRAWING_UI_RETURN_DELAY_MS after it lifts. See CanvasTouch.
    val canvasTouch = remember { CanvasTouch() }
    // The rail's Adjust button, and "A" on a keyboard.
    val openAdjust: () -> Unit = {
        heroExpansion.collapse()
        if (carouselUi.photo && carouselUi.sheetOpen) {
            // Lit already: a second tap leaves, back to brushes.
            carouselUi = carouselUi.copy(photo = false, tab = null, showingCarousel = false)
        } else {
            showCarousel = true
            if (uiState.activeTool != Tool.NONE) vm.setActiveTool(Tool.NONE)
            // Straight to the last tab used; the first time, the tab list.
            carouselUi = carouselUi.copy(
                photo = true, sheetOpen = true,
                tab = lastPhotoTab?.key, showingCarousel = lastPhotoTab != null,
            )
        }
    }
    val keysFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keysFocus.requestFocus() } }
    val chrome = rememberChromeVisibility(canvasTouch, strokeGate, forceHidden = { photoDrag.active }) {
        menuOpen = false
        showViewMenu = false
        showPosterMenu = false
        brushMenuOpen = false
        layerMenuFor = null
        shortcutsOpen = false
        if (carouselUi.sheetOpen) carouselUi = carouselUi.copy(sheetOpen = false, showingCarousel = false)
        heroExpansion.collapse()
        TOOLBOX_IDS.filter { railExpansion[it] == true }.forEach { vm.onRailHostExpansionChanged(it, false) }
    }
    // The rail is docked on the brush hand's side; floating windows keep clear of it.
    val railInset = RailInset(dockedOnLeft = uiState.isRightHanded, width = RailWidth + RailEdgeGap * 2)
    val toolRailActions = remember(vm) { vm.toolRailActions() }
    val toolSide = if (uiState.isRightHanded) Alignment.CenterStart else Alignment.CenterEnd
    val layerSide = if (uiState.isRightHanded) Alignment.CenterEnd else Alignment.CenterStart
    val toolCorner = if (uiState.isRightHanded) Alignment.TopStart else Alignment.TopEnd
    val layerCorner = if (uiState.isRightHanded) Alignment.TopEnd else Alignment.TopStart
    // The rails hang from the top as the mockup has them: the tool rail 16 from the top and 12 from
    // its side, the layers rail under the logo (top 72), each as tall as its buttons and scrolling
    // only if it would reach the bottom swipe strip. Whichever rail shares the logo's (right) side
    // is the one that drops below it.
    val railBottom = RailWidth + RailEdgeGap * 2
    val toolTop = if (uiState.isRightHanded) RailTopInset else LayersRailTopInset
    val layersTop = if (uiState.isRightHanded) LayersRailTopInset else RailTopInset
    val toolRailPadding = Modifier.padding(horizontal = RailSideInset).padding(top = toolTop, bottom = railBottom)
    val layersRailPadding = Modifier.padding(horizontal = RailSideInset).padding(top = layersTop, bottom = railBottom)
    val besideRail = RailWidth + RailEdgeGap * 2
    val layersRailBesideTools = Modifier
        .padding(horizontal = RailSideInset + RailWidth + RailSideInset)
        .padding(top = LayersRailTopInset, bottom = railBottom)
    CompositionLocalProvider(LocalRailInset provides railInset, LocalChromeHidden provides { chrome.hidden }) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .multiFingerTaps(strokeGate) { slot, at ->
                performGestureAction(uiState.gestureMapping, slot, vm, at)
            }
    ) {
        Surface(
            // Focusable so a hardware keyboard's keys reach it when nothing inside has focus.
            modifier = Modifier.fillMaxSize().focusRequester(keysFocus).focusable().onKeyEvent { e ->
                photoShortcut(e, photoOpen = carouselUi.photo, openAdjust = openAdjust, compare = photoActions::compare)
            },
            color = MaterialTheme.colorScheme.background,
        ) {
            EditorScreen(
                vm = vm,
                // Outermost, so it sees what every surface inside the canvas left unclaimed.
                modifier = Modifier.observeCanvasTouch(canvasTouch).fillMaxSize(),
                // The host owns the multi-finger history gestures (installed above the whole
                // app, see above); the screen just feeds this gate from its drawing surfaces.
                hostStrokeGate = strokeGate,
            )
        }

        // Crop: the frame over the photo while the Crop tab is open.
        if (photoTabShown == PhotoTab.CROP) {
            CropOverlay(
                crop = uiState.documentCrop,
                docW = uiState.documentWidth,
                docH = uiState.documentHeight,
                zoom = uiState.viewportZoom,
                offset = uiState.viewportOffset,
                rotation = uiState.viewportRotation,
                actions = cropActions,
            )
        }

        // Retouch: Heal's spots, Red-eye's circles, Local's mask brush, on the photo.
        val retouchCtx = RetouchContext(retouchState, retouchActions, null)
        when (photoTabShown) {
            PhotoTab.HEAL -> HealOverlay(
                uiState.layers.lastOrNull { it.adjustment?.tool == PhotoTool.HEAL }?.adjustment?.healSpots.orEmpty(),
                canvasCamera, retouchCtx,
            )
            PhotoTab.RED_EYE -> RedEyeOverlay(
                uiState.layers.lastOrNull { it.adjustment?.tool == PhotoTool.RED_EYE }
                    ?.adjustment?.redEyeSpots.orEmpty(),
                canvasCamera, retouchCtx,
            )
            PhotoTab.LOCAL -> LocalMaskOverlay(maskOverlay, canvasCamera, retouchCtx)
            else -> Unit
        }

        // HSL's eyedropper: while armed, a touch on the photo picks a range and drags it.
        if (photoSheet.eyedropperArmed && photoTabShown == PhotoTab.HSL) {
            HslEyedropperOverlay(
                state = photoSheet,
                actions = photoActions,
                hueAt = vm::photoHueRangeAt,
                saturationOf = { range ->
                    uiState.layers.find { it.id == uiState.activeLayerId && it.adjustment?.tool == PhotoTool.HSL }
                        ?.adjustment?.value(PhotoSliders.hsl(range, 1)) ?: 0f
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // The bottom carousel, centred on the whole screen and over the canvas.
        if (carouselOnScreen) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                CarouselSheet(
                    open = carouselUi.sheetOpen,
                    // Swiped up from shut, the sheet shows its tab list only; a tab, or a
                    // second swipe up, brings the carousel back where it was.
                    onOpenChange = { open ->
                        carouselUi = carouselUi.copy(sheetOpen = open, showingCarousel = false)
                    },
                    stages = SheetStages(
                        onStepUp = {
                            if (!carouselUi.showingCarousel) {
                                carouselUi = carouselUi.copy(showingCarousel = true)
                            }
                        },
                        onStepDown = {
                            carouselUi.showingCarousel.also { up ->
                                if (up) carouselUi = carouselUi.copy(showingCarousel = false)
                            }
                        },
                    ),
                    // Gone at once on a canvas touch, back DRAWING_UI_RETURN_DELAY_MS after
                    // it lifts (read in the sheet, so this screen does not recompose).
                    hiddenForDrawing = { chrome.hidden && !chrome.forcedOnly },
                    chromeHidden = { photoDrag.active },
                    // Clears the nav bar itself, plus the shortcuts sheet's swipe strip,
                    // which sits along the bottom edge and would otherwise take the grab
                    // pill's touches.
                    bottomInset = EdgeStripHeight + 4.dp,
                ) {
                    val carouselInputs = CarouselInputs(
                        groupEntries = if (carouselUi.category == CarouselCategory.FAVORITES) {
                            CarouselCategory.entries.filter { it.isToolGroup }.flatMap { group ->
                                toolGroupCarouselEntries(
                                    group, uiState, strings.nav.dodge, strings.nav.burn,
                                )
                            }
                        } else {
                            toolGroupCarouselEntries(
                                carouselUi.category, uiState, strings.nav.dodge, strings.nav.burn,
                            )
                        },
                        activeTool = uiState.activeTool,
                        activeBrushName = uiState.activeBrushName,
                        activeInkUtensil = activeInkUtensil,
                        builtInBrushes = com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes.presets,
                        customBrushes = customBrushes.map { it.id to it.brush },
                        extensionBrushes = brushes,
                        stabilizerLevel = uiState.stabilizerLevel,
                        stabilizerAlgorithm = uiState.stabilizerAlgorithm,
                        smudgeMode = colorSmudgeSettings.mode,
                        selectionShape = uiState.selectionShape,
                        extensionEffects = extensionEffects,
                        favorites = carouselFavorites,
                    )
                    val carouselTabs = carouselTabSections(
                        carouselContextOf(carouselUi.category), carouselUi.category, carouselInputs,
                        packNames = allInstalledExtensions.associate { it.id to it.manifest.name },
                    )
                    val carouselTab = selectedTabGroup(carouselTabs, carouselUi.tab)
                    val carouselEntries = carouselTab?.entries
                        ?: carouselEntries(carouselUi.category, carouselInputs)
                    val runCarouselEntry: (CarouselEntry) -> Unit = { entry ->
                            when (val action = entry.action) {
                                is CarouselAction.BuiltInBrush -> vm.selectBuiltInBrush(action.name)
                                is CarouselAction.CustomBrush -> vm.selectCustomBrush(action.id)
                                is CarouselAction.ExtensionBrush -> vm.selectBrushExtension(action.id)
                                is CarouselAction.InkUtensilPick -> vm.selectInkUtensil(action.utensil)
                                is CarouselAction.PickTool -> vm.setActiveTool(
                                    if (uiState.activeTool == action.tool) Tool.NONE else action.tool,
                                )
                                is CarouselAction.StabilizerLevel -> vm.setStabilizerLevel(action.level)
                                is CarouselAction.Stabilizer -> vm.setStabilizerAlgorithm(action.algorithm)
                                is CarouselAction.SmudgeMode -> vm.setColorSmudgeMode(action.mode)
                                is CarouselAction.SelectShape -> vm.onSetSelectionShape(action.shape)
                                is CarouselAction.GroupCommand -> executeToolGroupCommand(
                                    vm, uiState, action.id, screenCenter,
                                ) { pendingForgetSelectionName = it }
                                // The Extensions panel's LUT branch (onExtensionSelected), minus its
                                // DismissPanel: the carousel opened no panel to dismiss.
                                is CarouselAction.ExtensionLut -> vm.applyInstalledLut(action.extensionId)
                                is CarouselAction.ExtensionContribution -> {
                                    val contribution = runnableExtensions
                                        .find { it.id == action.extensionId }
                                        ?.let(vm::contributionsOf)
                                        ?.map { it.second }
                                        ?.find { it.id == action.contributionId }
                                    if (contribution != null) {
                                        // A contribution with its own params panel shows it inside the
                                        // Extensions panel, so that has to be open for it to appear.
                                        if (contribution.ui != null &&
                                            uiState.activePanel != EditorPanel.EXTENSIONS
                                        ) {
                                            vm.onExtensionsClicked()
                                        }
                                        vm.onExtensionContributionSelected(action.extensionId, contribution)
                                    }
                                }
                            }
                    }
                    // Effect cards' layer thumbnails (CarouselEffectPreviews.kt): LUTs render
                    // through applyInstalledLut's own grade, without committing.
                    val effectPreviews = remember {
                        EffectPreviewEngine({ entry, source, _ ->
                            when (val action = entry.action) {
                                is CarouselAction.ExtensionLut ->
                                    vm.previewInstalledLut(action.extensionId, source)
                                else -> null
                            }
                        })
                    }
                    val previewLayer = uiState.layers.find { it.id == uiState.activeLayerId }
                    // Re-snapshot (debounced) when the active layer, its bitmap, or its history moves.
                    LaunchedEffect(
                        previewLayer?.id, previewLayer?.bitmap, uiState.undoCount, uiState.redoCount,
                        uiState.isLoading,
                    ) {
                        kotlinx.coroutines.delay(EFFECT_SNAPSHOT_DEBOUNCE_MS)
                        effectPreviews.updateSource(previewLayer?.bitmap)
                    }
                    DisposableEffect(effectPreviews) { onDispose { effectPreviews.clear() } }
                    if (designMode) {
                        DesignSheetBody(
                            tab = designTab,
                            showingControls = carouselUi.showingCarousel,
                            target = designTarget,
                            actions = designActions,
                            library = designLibrary,
                            libraryActions = designLibraryActions,
                            export = designExport,
                            onSelectTab = { tab ->
                                designTab = tab
                                carouselUi = carouselUi.copy(showingCarousel = true)
                            },
                        )
                    } else if (carouselUi.photo) {
                        val photoTab = PhotoTab.ofKey(carouselUi.tab)
                        val photoLayer = photoTab?.tool?.let { tool ->
                            uiState.layers.find { it.id == uiState.activeLayerId && it.adjustment?.tool == tool }
                        }
                        PhotoSheetBody(
                            tab = photoTab,
                            showingControls = carouselUi.showingCarousel,
                            adjustment = photoLayer?.adjustment,
                            actions = photoActions,
                            drag = photoDrag,
                            state = photoSheet,
                            retouch = RetouchContext(
                                retouchState, retouchActions,
                                uiState.layers.find { it.id == uiState.activeLayerId && it.adjustment != null }?.name,
                            ),
                            crop = CropContext(
                                uiState.documentCrop, uiState.documentWidth, uiState.documentHeight, cropActions,
                            ),
                            onSelectTab = { tab ->
                                lastPhotoTab = tab
                                carouselUi = carouselUi.copy(tab = tab.key, showingCarousel = true)
                            },
                        )
                    } else BottomCarousel(
                        ui = carouselUi,
                        onUiChange = { carouselUi = it },
                        content = CarouselContent(
                            entries = carouselEntries,
                            brushColor = uiState.activeColor,
                            secondaryColor = uiState.secondaryColor,
                            extensionPreviews = brushPreviews,
                            extensionTips = brushTips,
                            extensionIcons = extensionIcons,
                            // The same fields Tool Options, the size picker and the brush HUD
                            // read and set; the hero sliders hold no state of their own.
                            heroState = HeroAdjustmentState(
                                brushSize = uiState.brushSize,
                                brushFlow = uiState.brushFlow,
                                brushOpacity = uiState.brushOpacity,
                                brushFeathering = uiState.brushFeathering,
                                smudgeRate = colorSmudgeSettings.smudgeRate,
                                stabilizerLevel = uiState.stabilizerLevel,
                                magicWandTolerance = uiState.magicWandTolerance,
                                smudgeColorRate = colorSmudgeSettings.colorRate,
                                smudgeOpacity = colorSmudgeSettings.opacity,
                                more = HeroMoreState(
                                    smudgeMode = colorSmudgeSettings.mode,
                                    smudgeChargeDecay = colorSmudgeSettings.chargeDecayRate,
                                    smudgeDilution = colorSmudgeSettings.dilution,
                                    smudgePickup = colorSmudgeSettings.pickupRate,
                                    smudgeRadius = colorSmudgeSettings.smudgeRadius,
                                    smudgePigmentMixing =
                                        colorSmudgeSettings.mixingModel == heroMixingModel(pigment = true),
                                    smudgeCarryAlpha = colorSmudgeSettings.smearAlpha,
                                    smudgeSampleMerged = colorSmudgeSettings.sampleMerged,
                                    stabilizerAlgorithm = uiState.stabilizerAlgorithm,
                                    selectionFeatherPx = uiState.selection?.featherPx,
                                ),
                            ),
                            itemSettings = { entry ->
                                carouselSettingsKey(entry)?.let {
                                    carouselItemSettingsFor(it, carouselItemSettings)
                                }
                            },
                            onAdjust = { entry, setter, value ->
                                val key = carouselSettingsKey(entry)
                                val own = key?.let {
                                    carouselItemSettingsFor(it, carouselItemSettings).adjusted(setter, value)
                                }
                                // An item not in hand: only its own stored settings change.
                                // The item in hand: the live setter, which the sync above
                                // then saves as that item's.
                                if (key != null && own != null && key != activeSettingsKey) {
                                    vm.setCarouselItemSettings(key, own)
                                } else when (setter) {
                                    HeroSetter.BRUSH_SIZE -> vm.setBrushSize(value)
                                    HeroSetter.BRUSH_FLOW -> vm.setBrushFlow(value)
                                    HeroSetter.BRUSH_OPACITY -> vm.setBrushOpacity(value)
                                    HeroSetter.BRUSH_SOFTNESS -> vm.setBrushFeathering(value)
                                    HeroSetter.SMUDGE_STRENGTH -> vm.setColorSmudgeRate(value)
                                    HeroSetter.SMUDGE_LOAD -> vm.setColorSmudgeColorRate(value)
                                    HeroSetter.SMUDGE_OPACITY -> vm.setColorSmudgeOpacity(value)
                                    HeroSetter.SMUDGE_CHARGE_DECAY -> vm.setColorSmudgeChargeDecayRate(value)
                                    HeroSetter.SMUDGE_DILUTION -> vm.setColorSmudgeDilution(value)
                                    HeroSetter.SMUDGE_PICKUP -> vm.setColorSmudgePickupRate(value)
                                    HeroSetter.SMUDGE_RADIUS -> vm.setColorSmudgeRadius(value)
                                    HeroSetter.SELECTION_FEATHER -> vm.onSetSelectionFeather(value)
                                    HeroSetter.STABILIZER -> vm.setStabilizerLevel(value.roundToInt())
                                    HeroSetter.WAND_TOLERANCE -> vm.onSetMagicWandTolerance(value.roundToInt())
                                }
                            },
                            // "More" grows the hero card in place to that item's full
                            // settings (ExpandedHeroLayer, on the page in front of this
                            // one); it no longer opens Tool Options, and needs no selection
                            // first, since the card edits the item's own settings.
                            onMore = {},
                            strokeActive = { strokeGate.strokeActive },
                            effectPreviews = effectPreviews,
                            onChoose = { _, setter, index ->
                                when (setter) {
                                    HeroChoiceSetter.SMUDGE_MODE ->
                                        vm.setColorSmudgeMode(ColorSmudgeEngine.Mode.entries[index])
                                    HeroChoiceSetter.STABILIZER_ALGORITHM ->
                                        vm.setStabilizerAlgorithm(StabilizerAlgorithm.entries[index])
                                }
                            },
                            onToggle = { _, setter, on ->
                                when (setter) {
                                    HeroToggleSetter.SMUDGE_PIGMENT_MIXING ->
                                        vm.setColorSmudgeMixingModel(heroMixingModel(on))
                                    HeroToggleSetter.SMUDGE_CARRY_ALPHA -> vm.setColorSmudgeAlphaCarry(on)
                                    HeroToggleSetter.SMUDGE_SAMPLE_MERGED -> vm.setColorSmudgeSampleMerged(on)
                                }
                            },
                        ),
                        tabs = carouselTabs,
                        selectedTab = carouselTab?.key,
                        onToggleFavorite = { entry -> vm.onToggleCarouselFavorite(entry.key) },
                        onEntryClick = runCarouselEntry,
                        expansion = heroExpansion,
                    )
                }
            }
            // The carousel's expanded ("More") hero card, in front of the carousel: it grows over the
            // neighbouring cards without the carousel's own layout moving at all.
            ExpandedHeroLayer(heroExpansion)
        }

        // The onscreen chrome stays inside the safe drawing area; the canvas remains full bleed.
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing,
                ),
        ) {
            if (!uiState.hideUiForCapture) ShownWhenNotDrawing(chrome) {
                ToolRail(
                    vm = toolRailActions,
                    uiState = uiState,
                    lit = activeClassifiers,
                    secondary = secondaryClassifiers,
                    callbacks = ToolRailCallbacks(
                        onToolOptions = openToolOptionsCard,
                        onToolGroup = { category ->
                            showCarousel = true
                            heroExpansion.collapse()
                            carouselUi = carouselUi.copy(
                                category = category, sheetOpen = true, tab = null, showingCarousel = true,
                                photo = false,
                            )
                        },
                        onBrushMenu = { brushMenuOpen = true; layerMenuFor = null; menuOpen = false },
                        onAdjust = openAdjust,
                    ),
                    modifier = Modifier.align(toolCorner).then(toolRailPadding),
                )
                if (brushMenuOpen) {
                    MenuPanel(
                        rows = buildMenu {
                            brushMenu(
                                vm, uiState, customBrushes,
                                onOpenBrushGallery = { showBrushGallery = true },
                                onOpenBrushTipsManager = { showBrushTipsManager = true },
                            )
                        },
                        onDismiss = { brushMenuOpen = false },
                        title = uiState.activeBrushName ?: strings.nav.brush,
                        modifier = Modifier.align(toolSide).padding(horizontal = besideRail),
                    )
                }

                if (showLayersRail) {
                    LayersRail(
                        layers = uiState.layers,
                        activeLayerId = uiState.activeLayerId,
                        secondary = secondaryClassifiers,
                        expansion = railExpansion,
                        maskThumbs = maskThumbs,
                        callbacks = LayersRailCallbacks(
                            onActivate = { id ->
                                vm.onLayerActivated(id)
                                // An adjustment tile opens the sheet on its own tab, to edit it.
                                uiState.layers.find { it.id == id }?.adjustment?.let { adjustment ->
                                    val tab = PhotoTab.of(adjustment.tool)
                                    lastPhotoTab = tab
                                    showCarousel = true
                                    carouselUi = carouselUi.copy(
                                        photo = true, sheetOpen = true, tab = tab.key, showingCarousel = true,
                                    )
                                }
                            },
                            onOpenMenu = { id ->
                                vm.onLayerActivated(id)
                                layerMenuFor = id
                                brushMenuOpen = false
                                menuOpen = false
                            },
                            onExpand = { id, open -> vm.onRailHostExpansionChanged(id, open) },
                            onRelocate = { order, parentId -> vm.onLayerRailRelocated(order, parentId) },
                            onAdd = { vm.onAddBlankLayer() },
                            onDelete = { id ->
                                val group = uiState.layers.find { it.id == id }?.isContainer == true
                                if (group) vm.onDeleteGroup(id) else vm.onLayerRemoved(id)
                            },
                        ),
                        // With the side panel docked on its side, the layers rail moves beside the
                        // tool rail so the two never overlap (the mockup's tablet panel board).
                        modifier = if (panelDocked) {
                            Modifier.align(toolCorner).then(layersRailBesideTools)
                        } else {
                            Modifier.align(layerCorner).then(layersRailPadding)
                        },
                    )
                }
                layerMenuFor?.let { id -> uiState.layers.firstOrNull { it.id == id } }?.let { layer ->
                    val env = LayerMenuEnv(
                        vm, strings,
                        onBlendMode = { showBlendDialog = true },
                        onEditClicked = { showLayerOptionsDialog = true },
                        onCurves = { showCurvesDialog = true },
                    )
                    MenuPanel(
                        rows = buildMenu { layerMenu(layer, uiState, env) },
                        onDismiss = { layerMenuFor = null },
                        title = layer.name,
                        modifier = Modifier.align(layerSide).padding(horizontal = besideRail),
                    )
                }

                // The workspace toolboxes switched on from View….
                val toolboxes = listOfNotNull(
                    animationToolbox(vm, uiState, activeClassifiers).takeIf { showAnimationRail },
                    ToolboxSpec(
                        "area.model", "3D", GraffuxIcons.GuideIsometric, "area.model" in activeClassifiers,
                        listOf(
                            ToolboxAction("Sketch in 3D", lit = showSpatialSketch) { showSpatialSketch = true },
                            ToolboxAction("3D Model", lit = showModelDialog) { showModelDialog = true },
                            ToolboxAction("Choose Model") { showModelDialog = true; modelPicker.launch(arrayOf("*/*")) },
                        ),
                    ).takeIf { showModelRail },
                    ToolboxSpec(
                        "area.reference", "Reference", GraffuxIcons.LayerReference,
                        "area.reference" in activeClassifiers,
                        listOf(
                            ToolboxAction("Reference Image") { showReferenceWindow = true },
                            ToolboxAction("Choose Reference") {
                                showReferenceWindow = true
                                referencePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        ),
                    ).takeIf { showReferenceRail },
                    ToolboxSpec(
                        "area.figma", "Figma", GraffuxIcons.Artboard, "area.figma" in activeClassifiers,
                        listOf(
                            ToolboxAction("Import from Figma") { showFigmaWindow = true },
                            ToolboxAction("Export for Figma") { vm.exportForFigma() },
                        ),
                    ).takeIf { showFigmaRail },
                    ToolboxSpec(
                        "area.extensions", "Extensions", GraffuxIcons.FilterGallery,
                        "area.extensions" in activeClassifiers,
                        listOf(
                            ToolboxAction(
                                "Run Extension" + allInstalledExtensions.size.takeIf { it > 0 }?.let { " ($it)" }.orEmpty(),
                                lit = "adj.extensions" in activeClassifiers,
                            ) { vm.onExtensionsClicked() },
                            ToolboxAction("Installed") { storeTab = StoreTab.INSTALLED; showStore = true },
                        ),
                    ).takeIf { showExtensionsRail },
                )
                val toolboxStart = with(LocalDensity.current) { (RailWidth + RailEdgeGap * 3).roundToPx() }
                val toolboxStep = with(LocalDensity.current) { (RailWidth + RailEdgeGap).roundToPx() }
                toolboxes.forEachIndexed { index, spec ->
                    FloatingToolbox(
                        spec = spec,
                        expanded = railExpansion[spec.id] ?: false,
                        onExpandedChange = { vm.onRailHostExpansionChanged(spec.id, it) },
                        initialOffset = IntOffset(toolboxStart + index * toolboxStep, toolboxStart / 2),
                    )
                }

                // The design properties docked on the layers' side (tablet / desktop); the logo
                // menu, composed after it, keeps its corner over the panel's header.
                if (panelDocked) {
                    PropertiesPanel(
                        target = designTarget,
                        actions = designActions,
                        placement = propertiesPlacement,
                        onPlacement = { settingsVm.setPropertiesPlacement(it.key) },
                        library = designLibrary,
                        libraryActions = designLibraryActions,
                        export = designExport,
                        logoClearance = if (uiState.isRightHanded) 64.dp else 16.dp,
                        modifier = Modifier.align(layerCorner).fillMaxHeight(),
                    )
                }

                // One menu behind the app logo. "View…" swaps it to the panel toggles without
                // closing it; closing it (or picking anything) goes back to the top.
                LogoMenu(
                    open = menuOpen,
                    onOpenChange = { open ->
                        menuOpen = open
                        if (!open) { showViewMenu = false; showPosterMenu = false }
                        if (open) { brushMenuOpen = false; layerMenuFor = null }
                    },
                    rows = buildMenu {
                        if (showPosterMenu) {
                            val (artW, artH) =
                                posterArtSize(uiState.documentWidth, uiState.documentHeight, uiState.documentCrop)
                            posterPage(
                                choices = posterChoices,
                                imperial = uiState.isImperialUnits,
                                artW = artW,
                                artH = artH,
                                onChange = { posterChoices = it },
                                onBack = { showPosterMenu = false },
                                onExport = { spec -> showPosterMenu = false; vm.exportPosterPdf(spec) },
                            )
                        } else if (showViewMenu) {
                            page(strings.mainMenu.back) { showViewMenu = false }
                            // Each row says what it shows and whether it's showing, so a hidden panel
                            // can be traced back here.
                            toggle(strings.mainMenu.viewLayers, showLayersRail) { showLayersRail = it }
                            toggle(strings.mainMenu.viewSheet, showCarousel) { showCarousel = it }
                            toggle(strings.mainMenu.viewAnimation, showAnimationRail) { showAnimationRail = it }
                            toggle(strings.mainMenu.view3d, showModelRail) { showModelRail = it }
                            toggle(strings.mainMenu.viewReference, showReferenceRail) { showReferenceRail = it }
                            toggle(strings.mainMenu.viewFigma, showFigmaRail) { showFigmaRail = it }
                            // Turning this on also opens the Extensions window straight to Installed:
                            // "yes, show me what I have". Turning it off only hides the toolbox.
                            toggle(strings.mainMenu.viewAddOns, showExtensionsRail) { checked ->
                                showExtensionsRail = checked
                                if (checked) { storeTab = StoreTab.INSTALLED; showStore = true }
                            }
                        } else {
                            page(strings.mainMenu.view) { showViewMenu = true }
                            listItem(strings.nav.new) { vm.createNewProject() }
                            listItem(strings.mainMenu.open) { vm.refreshOpenScreen(); showOpenDialog = true }
                            listItem(strings.mainMenu.importFile) { importPicker.launch(arrayOf("*/*")) }
                            // Opens AddContentDialog (shapes + text as a new layer).
                            listItem(strings.mainMenu.addShape) { showAddDialog = true }
                            listItem(strings.mainMenu.save) { showSaveDialog = true }
                            listItem(strings.mainMenu.exportPng) { vm.exportImage() }
                            page(strings.mainMenu.posterPdf) { showPosterMenu = true }
                            // The always-visible way into the azphalt store.
                            listItem(strings.mainMenu.addOns) { openAzphaltStore() }
                            listItem(strings.mainMenu.installAddOn) { extensionFilePicker.launch(arrayOf("*/*")) }
                            listItem(strings.mainMenu.share) {
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
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // The scope going away (rotation, leaving composition) isn't a failed
                            // share, and must not raise a "Couldn't share" popup.
                            throw e
                        } catch (t: Throwable) {
                            // Was a logcat line and nothing else: Share appeared to do nothing
                            // at all, with no way for the user to tell a failure from a slow
                            // export.
                            android.util.Log.w("Graffux", "Share failed", t)
                            alerts.show(
                                kind = MessageKind.WARNING,
                                title = strings.messages.couldntShare,
                                message = t.message ?: strings.messages.noAppAccepted,
                            )
                        }
                    }
                            }
                            // A whole-document operation, so it lives here rather than in one layer's menu.
                            listItem(strings.mainMenu.flattenAll) { vm.onFlattenAllLayers() }
                            divider()
                            listItem(strings.mainMenu.gestures) { showGestureHelp = true }
                            listItem(strings.mainMenu.settings) { showSettings = true }
                            listItem(strings.mainMenu.about) { showAbout = true }
                        }
                    },
                    modifier = Modifier.align(Alignment.TopEnd),
                )

                // The tools you actually reach for, one swipe from the bottom edge: the flat row of
                // the ones that matter to you, where the rail holds every tool the app has.
                EdgeSheet(
                    open = shortcutsOpen,
                    onOpenChange = { shortcutsOpen = it },
                    label = "Shortcuts",
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    ShortcutsSheet(
                        activeTool = uiState.activeTool,
                        usage = toolUsage,
                        favorites = favoriteTools,
                        onPickTool = { tool ->
                            // Same rule as the rail's own tool items: tapping the tool already in
                            // hand puts it down.
                            vm.setActiveTool(if (uiState.activeTool == tool) Tool.NONE else tool)
                        },
                        onToggleFavorite = { tool -> vm.onToggleFavoriteTool(tool) },
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            // The way back out of full-screen art mode (four-finger tap): the gesture is documented
            // nowhere on screen, so a user who triggers it by accident needs one small, low-opacity
            // tap target to make this a mode rather than a dead end.
            if (uiState.hideUiForCapture) {
                FloatingActionButton(
                    onClick = { vm.toggleHideUi() },
                    containerColor = surfaceVariantColor.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).size(48.dp),
                ) {
                    Icon(painterResource(GraffuxIcons.ChevronUp), contentDescription = "Show interface")
                }
            }

            // Failures and confirmations, over the artwork, never blocking it.
            MessageHost(
                alerts,
                okLabel = strings.messages.ok,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = RailEdgeGap, start = besideRail, end = besideRail),
            )
        }

        // Windows and dialogs, over the rails.
        Box(Modifier.fillMaxSize()) {
            if (showDocDialog) {
                DocumentSizeDialog(
                    currentWidth = uiState.documentWidth,
                    currentHeight = uiState.documentHeight,
                    // No Apply button: a preset applies and closes in one tap; a custom size
                    // applies once, when the dialog closes.
                    onConfirm = { w, h -> vm.setDocumentSize(w, h) },
                    onDismiss = { showDocDialog = false },
                    currentSubstrateId = uiState.canvasSubstrateId,
                    onSubstrate = { vm.setCanvasSubstrate(it) },
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
            if (activeShapeDialog == ShapeEditDialog.STROKE) {
                val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
                key(uiState.activeLayerId) {
                    VectorStrokeDialog(
                        currentWidth = activeLayer?.shapes?.firstOrNull()?.strokeWidth ?: 0f,
                        // No Apply button: the dialog itself fires this once, with the final
                        // value, when it closes — see VectorStrokeDialog's own doc comment.
                        onApply = { w -> vm.setVectorStrokeWidth(w) },
                        onDismiss = { activeShapeDialog = null },
                    )
                }
            }

            if (activeShapeDialog == ShapeEditDialog.CORNER) {
                val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
                val rect = activeLayer?.shapes?.firstOrNull { it.kind == ShapeKind.RECTANGLE }
                key(uiState.activeLayerId) {
                    CornerRadiusDialog(
                        currentRadius = rect?.cornerRadius ?: 0f,
                        // No Apply button: fires once, on close — see CornerRadiusDialog's own
                        // doc comment.
                        onApply = { r -> vm.setVectorCornerRadius(r) },
                        onDismiss = { activeShapeDialog = null },
                    )
                }
            }

            if (activeShapeDialog == ShapeEditDialog.SHAPE_SIZE) {
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
                            onDismiss = { activeShapeDialog = null },
                        )
                    }
                }
            }

            if (activeShapeDialog == ShapeEditDialog.SIDES) {
                val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
                val polygon = activeLayer?.shapes?.firstOrNull { it.kind == ShapeKind.POLYGON }
                if (polygon != null) {
                    key(uiState.activeLayerId) {
                        PolygonSidesDialog(
                            currentSides = polygon.sides,
                            // No Apply button: fires once, on close — see PolygonSidesDialog's
                            // own doc comment.
                            onApply = { n -> vm.setPolygonSides(n) },
                            onDismiss = { activeShapeDialog = null },
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
                        onShapeSize = { activeShapeDialog = ShapeEditDialog.SHAPE_SIZE },
                        onStrokeWidth = { activeShapeDialog = ShapeEditDialog.STROKE },
                        onCornerRadius = { activeShapeDialog = ShapeEditDialog.CORNER },
                        onPolygonSides = { activeShapeDialog = ShapeEditDialog.SIDES },
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
                    onNew = { showOpenDialog = false; vm.createNewProject() },
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
                val purchaseStates by vm.purchaseStates.collectAsState()
                // Back from a Stripe Checkout Custom Tab: confirm the payment and install.
                androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
                    vm.resumeCheckouts()
                    onPauseOrDispose { }
                }
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
                    // In-app purchase: the repository picks Play Billing, Google's choice screen or
                    // Stripe for this buyer; Stripe Checkout opens in a Custom Tab and is picked up
                    // again on resume (below).
                    onBuy = { pkg ->
                        val activity = context.findActivity()
                        if (activity == null) {
                            alerts.show(
                                kind = MessageKind.WARNING,
                                title = "Can't start a purchase",
                                message = "Try again from the main screen.",
                            )
                        } else {
                            vm.buyPackage(activity, pkg.id) { checkoutUrl ->
                                runCatching {
                                    androidx.browser.customtabs.CustomTabsIntent.Builder().build()
                                        .launchUrl(activity, Uri.parse(checkoutUrl))
                                }.onFailure {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(checkoutUrl)))
                                    }
                                }
                            }
                        }
                    },
                    // Buying on azphalt.store stays a first-class option: the licence comes back through
                    // the store handoff / Restore purchases and is honoured like an in-app one.
                    onBuyOnWeb = { pkg ->
                        val url = "${AzphaltStoreHandoff.WEB_STORE_URL}/packages/" +
                            com.hereliesaz.graffitixr.data.azphalt.RepositoryApiClient.encodePathSegment(pkg.id)
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }.onFailure {
                            alerts.show(
                                kind = MessageKind.WARNING,
                                title = "No browser",
                                message = "Nothing on this device can open $url.",
                            )
                        }
                    },
                    purchaseStates = purchaseStates,
                    onRestorePurchases = { vm.restorePurchases() },
                    onCheckPurchase = { pkg -> vm.recheckPurchase(pkg.id) },
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
                val animationPreviewBuffer by vm.animationPreviewBuffer.collectAsState()
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
                    previewIsRendering = animationPreviewBuffer.isRendering,
                    previewIsReady = vm.isAnimationPreviewReady(uiState),
                    previewRenderedFrames = animationPreviewBuffer.renderedFrames,
                    previewTotalFrames = animationPreviewBuffer.totalFrames,
                    previewError = animationPreviewBuffer.error,
                    onTogglePlayback = { vm.onToggleAnimationPlayback() },
                    onPreviousFrame = { vm.onPreviousFrame() },
                    onNextFrame = { vm.onNextFrame() },
                    onSeekFrame = { vm.onSelectFrame(it) },
                    onAddFrame = { vm.onAddFrame() },
                    onToggleOnionSkin = { vm.onToggleOnionSkin() },
                    onSetOnionSkinPastCount = { vm.onSetOnionSkinPastCount(it) },
                    onSetOnionSkinFutureCount = { vm.onSetOnionSkinFutureCount(it) },
                    onSetLoopMode = { vm.onSetAnimationLoopMode(it) },
                    onSetFrameDurationMs = { vm.onSetAnimationFrameDurationMs(it) },
                    onSetRange = { start, end -> vm.onSetAnimationRange(start, end) },
                    onRenderPreview = { vm.renderAnimationPreview() },
                    onSetFrameHoldCount = { vm.onSetFrameHoldCount(it) },
                    onExport = { vm.exportAnimation() },
                    onToggleTimeLapse = { vm.onToggleTimeLapseRecording() },
                    onDismiss = { vm.onToggleAnimationMode() },
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
        // Unlike the imported-OBJ texture painter, spatial ink needs a full-screen 3D scene.
        if (showSpatialSketch) {
            SpatialSketchWorkspace(
                strokes = spatialStrokes,
                guide = spatialGuide,
                onGuideChange = vm::onSpatialGuideChanged,
                color = uiState.activeColor,
                brushRadiusPx = uiState.brushSize / 2f,
                onStroke = vm::onSpatialStroke,
                onErase = vm::onSpatialErase,
                onUndo = vm::onSpatialUndo,
                onRedo = vm::onSpatialRedo,
                onExport = { spatialGltfExporter.launch("Graffux-Spatial-Sketch.gltf") },
                onClose = { showSpatialSketch = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Above every rail and window: a long operation's labelled, cancellable progress.
        if (uiState.isLoading) {
            com.hereliesaz.graffitixr.feature.editor.LongOperationOverlay(
                label = uiState.loadingLabel,
                onCancel = if (uiState.loadingCancellable) vm::cancelLongOperations else null,
            )
        }
        if (showAbout) AboutReader(onDismiss = { showAbout = false })
        if (showGestureHelp) {
            GestureHelpDialog(
                mapping = uiState.gestureMapping,
                strings = strings.gestureHelp,
                close = strings.common.close,
                onOpenSettings = { showGestureHelp = false; showSettings = true },
                onDismiss = { showGestureHelp = false },
            )
        }
        // The project dialog, above the rail and every window: mandatory with no project open,
        // cancellable over one (File > New). Load goes through the same picker as File > Open; a cancelled picker
        // returns null and the dialog simply stays.
        projectGate?.let { gate ->
            ProjectGateDialog(
                state = gate,
                onLoad = { projectOpener.launch(arrayOf("*/*")) },
                onSave = { name, w, h ->
                    vm.onProjectGateSave(name, w, h, gateImages)
                    gateImages = emptyList()
                },
                pickedImageCount = gateImages.size,
                onPickImages = {
                    gateImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                imperial = uiState.isImperialUnits,
                onCancel = { vm.dismissProjectGate(); gateImages = emptyList() },
            )
        }
    }
    }
}

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
 * The rails light an item whose id is in this set (RailButton's `light`), which gives a filled
 * container and a ring rather than only a tinted icon — a much louder "you are here" than colour
 * alone. Each stateful item tags itself with its own id and
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
/** [activeRailClassifiers]' modes and toggles that stay on until turned off. */
private fun modeRailClassifiers(
    uiState: EditorUiState,
    modelWindowOpen: Boolean,
): Set<String> = buildSet {
    // Time-lapse lives inside the Animation window now, so the thing that lights up for it is the
    // window's own item — otherwise a recording in progress would show nowhere in the rail.
    if (uiState.isAnimationMode || uiState.isTimeLapseRecording) add("tool.animation")
    if (modelWindowOpen) add("tool.model")
    if (uiState.pathEditLayerId != null) add("tool.nodeEdit")
    if (uiState.activeTool == Tool.CLONE && uiState.cloneSource != null) add("tool.cloneSource")
    if (uiState.selection?.inverted == true) add("tool.selectInvert")
    if (uiState.quickMenuAt != null) add("tool.quick")
    if (uiState.showColorPicker) add("tool.color")
}

/**
 * The brush-list entry lit for the brush in hand. With an Ink utensil in hand its entry is the brush,
 * and no stamp brush that happens to share its name (the built-in "Ink Pen") lights too.
 */
private fun activeBrushClassifiers(
    activeBrushName: String?,
    brushes: List<Pair<String, String>>,
    customBrushes: List<CustomBrush>,
    activeInkUtensil: com.hereliesaz.graffitixr.common.model.InkUtensil?,
): Set<String> = buildSet {
    val inkEntry = inkUtensilEntryFor(activeInkUtensil)
    if (inkEntry != null) {
        add(inkEntry.classifier)
        return@buildSet
    }
    com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes.presets
        .firstOrNull { it.name == activeBrushName }
        ?.let { add("brush.builtin.${it.name}") }
    brushes.firstOrNull { it.second == activeBrushName }?.let { add("brush.${it.first}") }
    customBrushes.firstOrNull { it.brush.name == activeBrushName }
        ?.let { add("brush.custom.${it.id}") }
}

/** Everything [activeRailClassifiers] reads. Each is a separate source of "what is lit". */
internal data class RailClassifierInputs(
    val uiState: EditorUiState,
    val brushes: List<Pair<String, String>>,
    val customBrushes: List<CustomBrush>,
    // Whether the 3D window is open. Passed in rather than read off [EditorUiState] because that is
    // where it lives — the window's visibility is composable state in `GraffuxApp`, like every other
    // floating window's. It is here at all because an item that opens a window has to light up while
    // that window is open, or the rail says nothing about a panel sitting over the artwork.
    val modelWindowOpen: Boolean,
    /**
     * The view-model's `activeInkUtensil`. Ink is classified by this, never by `activeBrushName`: a
     * stamp brush may share a utensil's name (the built-in "Ink Pen" does).
     */
    val activeInkUtensil: com.hereliesaz.graffitixr.common.model.InkUtensil? = null,
)

internal fun activeRailClassifiers(inputs: RailClassifierInputs): Set<String> = with(inputs) { buildSet {
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
    addAll(modeRailClassifiers(uiState, modelWindowOpen))

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

    // The current brush. `null` is the legacy built-in round brush (no longer selectable; the default
    // is now the bundled "Round" preset), which is why the host itself lights up rather than a member
    // of its list.
    if (uiState.activeBrushName == null) add("grp.brushes")
    addAll(activeBrushClassifiers(uiState.activeBrushName, brushes, customBrushes, activeInkUtensil))

    // The layer being edited, and the mode pickers — each always lights exactly one member,
    // because each is a choice among all of its options rather than an on/off.
    uiState.activeLayerId?.let { add("layer.$it") }
    add("selectShape.${uiState.selectionShape.name}")
    add("transform.${uiState.transformMode.name}")
} }

/**
 * Every rail item that is currently **secondary** — a condition that is true of it, as opposed to
 * [activeRailClassifiers]'s "this is where you are".
 *
 * The rails draw these with a second (amber) ring, and two pieces of editor state have no other
 * way to show:
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

/** Every floating toolbox, by id; a canvas touch folds the open ones. */
private val TOOLBOX_IDS = listOf("area.animation", "area.model", "area.reference", "area.figma", "area.extensions")

/** The Animation toolbox: Animation Assist, and its transport while it is on. */
private fun animationToolbox(vm: EditorViewModel, uiState: EditorUiState, lit: Set<String>): ToolboxSpec {
    val actions = mutableListOf(
        ToolboxAction(
            if (uiState.isAnimationMode) "Close Animation Assist" else "Animation Assist",
            lit = "tool.animation" in lit,
        ) { vm.onToggleAnimationMode() },
    )
    if (uiState.isAnimationMode) {
        actions += listOf(
            ToolboxAction(if (uiState.isAnimationPlaying) "Pause" else "Play") { vm.onToggleAnimationPlayback() },
            ToolboxAction("Previous Frame") { vm.onPreviousFrame() },
            ToolboxAction("Next Frame") { vm.onNextFrame() },
            ToolboxAction("Add Frame (${vm.animationFrameCount()})") { vm.onAddFrame() },
            ToolboxAction(if (uiState.onionSkinEnabled) "Onion Skin Off" else "Onion Skin On") { vm.onToggleOnionSkin() },
            ToolboxAction("Export Animation") { vm.exportAnimation() },
            ToolboxAction(
                if (uiState.isTimeLapseRecording) "Stop Time-lapse" else "Start Time-lapse",
            ) { vm.onToggleTimeLapseRecording() },
        )
    }
    return ToolboxSpec("area.animation", "Animation", GraffuxIcons.MotionTween, "area.animation" in lit, actions)
}

/** A store app's answer, held until the user confirms the install. */
private data class StoreHandoffInstall(
    val packages: List<Uri>,
    val metadata: com.hereliesaz.graffitixr.data.azphalt.AzphaltStoreHandoff.StoreResult?,
)

/** The Activity behind a Compose [android.content.Context], unwrapping ContextWrappers. */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
