// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt
package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.coop.OpEmitter
import com.hereliesaz.graffitixr.common.importer.DocumentFormat
import com.hereliesaz.graffitixr.common.importer.DocumentImporter
import com.hereliesaz.graffitixr.common.importer.ImportedDocument
import com.hereliesaz.graffitixr.common.model.*
import com.hereliesaz.graffitixr.feature.editor.threed.PaintableTexture
import com.hereliesaz.graffitixr.common.util.ImageUtils
import com.hereliesaz.graffitixr.common.util.computeAutoTune
import com.hereliesaz.graffitixr.common.util.decodeBoundedBitmap
import com.hereliesaz.graffitixr.common.azphalt.BrushStamps
import com.hereliesaz.graffitixr.common.azphalt.applyCubeLut
import com.hereliesaz.graffitixr.common.util.imageStats
import com.hereliesaz.graffitixr.common.util.saveBitmapToGallery
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.common.util.SafeBitmap
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.feature.editor.export.ExportManager
import com.hereliesaz.graffitixr.feature.editor.export.artboardRect
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import javax.inject.Inject
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor
import com.hereliesaz.graffitixr.common.util.StrokeStabilizer
import com.hereliesaz.graffitixr.feature.editor.timelapse.TimeLapseRecorder
import kotlinx.coroutines.flow.collect
import kotlin.math.roundToInt

data class StrokeCommand(
    val path: List<Offset>,
    val canvasSize: IntSize,
    val tool: Tool,
    val brushSize: Float,
    val brushColor: Int,
    val intensity: Float,
    val feathering: Float = 0f,
    val layerScale: Float = 1f,
    val layerOffset: Offset = Offset.Zero,
    val layerRotationZ: Float = 0f,
    val viewportOffset: Offset = Offset.Zero,
    val viewportZoom: Float = 1f,
    val viewportRotation: Float = 0f,
    // Azphalt stamp-brush stroke (null = the built-in round brush). [flow] is per-dab build-up and
    // [seed] fixes the dab jitter so a replayed stroke re-composites to identical pixels. [stampShape]
    // is the brush's optional greyscale/alpha tip image (null = a generated round tip).
    val stampBrush: com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush? = null,
    val flow: Float = 1f,
    val seed: Long = 0L,
    val stampShape: Bitmap? = null,
    // Procreate parity, recorded per stroke so undo/redo replay reproduces the paint exactly:
    // [symmetryMode] mirrors the stroke across one or more axes through the canvas centre;
    // [alphaLock] confines the paint to pixels that already have alpha.
    val symmetryMode: SymmetryMode = SymmetryMode.NONE,
    val alphaLock: Boolean = false,
    // The lasso selection in force when the stroke was drawn, if any. Recorded per stroke (rather
    // than read from live state at replay time) so an undo/redo re-composites against the same
    // boundary the paint was originally clipped to, even after the selection has moved or gone.
    val selection: com.hereliesaz.graffitixr.common.model.Selection? = null,
    // Set only on a [Tool.SELECT] command: the screen-space distance the selected pixels were
    // dragged. Makes a move a replayable command like any stroke — see [DrawingEngine].
    val moveDelta: Offset? = null,
    // Wipes the layer to transparency instead of painting a path — Procreate's clear-layer. Recorded
    // as a command so it undoes by replay like everything else; honours [selection], so clearing
    // with a lasso active wipes only inside it.
    val clearAll: Boolean = false,
    // Deforms the layer's pixels on a moved handle grid (Distort / Warp). Recorded as a command so it undoes by replay.
    val warpHandles: List<Offset>? = null,
    // Floods the selection with brushColor. Recorded as a command so it undoes by replay.
    val fillSelection: Boolean = false,
    // Screen-space offset for the clone stamp tool source point.
    val cloneOffset: Offset? = null,
)

/**
 * How many edits deep undo goes — and therefore how many strokes a layer must keep replayable.
 * Shared by [EditHistory] and the stroke baker so they can't drift apart: if the history were ever
 * deeper than the strokes kept, an undo would silently restore the wrong pixels.
 */
internal const val HISTORY_DEPTH = 20

/** Longest edge, in pixels, a time-lapse GIF frame is downsampled to — keeps captures cheap and small. */
private const val TIME_LAPSE_FRAME_MAX_DIM = 480

// The 3D model and its paint, inside the project folder. Fixed names rather than generated ones:
// a project holds at most one model, so a uuid would only accumulate orphans every time a new
// model replaced the old.
private const val MODEL_OBJ_FILE = "model.obj"
private const val MODEL_TEXTURE_FILE = "model_texture.png"

// Key for the model texture in the pending-write map, which is otherwise keyed by layer id. Prefixed
// so it can never collide with one — layer ids are uuids, but relying on that is a trap for later.
private const val MODEL_TEXTURE_KEY = "model:texture"

sealed class EditCommand {
    data class PropertyChange(val oldLayers: List<Layer>) : EditCommand()
    data class Draw(val layerId: String, val command: StrokeCommand) : EditCommand()
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val settingsRepository: SettingsRepository,
    private val projectManager: ProjectManager,
    private val exportManager: ExportManager,
    @ApplicationContext private val context: Context,
    internal val slamManager: SlamManager,
    private val dispatchers: DispatcherProvider,
    private val opEmitter: OpEmitter,
    private val extensionRepository: com.hereliesaz.graffitixr.data.azphalt.ExtensionRepository,
    private val customBrushRepository: com.hereliesaz.graffitixr.data.brush.CustomBrushRepository,
    private val figmaRepository: com.hereliesaz.graffitixr.data.figma.FigmaRepository,
    private val projectFileScanner: com.hereliesaz.graffitixr.data.ProjectFileScanner,
) : ViewModel(), EditorActions {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState = _uiState.asStateFlow()

    /**
     * Per-host AzNavRail expansion state (host id -> expanded), surfaced from the current project so the
     * rail can restore exactly as the user left it on reopen. Empty until [onRailHostExpansionChanged]
     * populates it (which happens once AzNavRail exposes a per-host onExpandedChange — expected 10.11).
     */
    val railExpansion: StateFlow<Map<String, Boolean>> =
        projectRepository.currentProject
            .map { it?.railExpansion ?: emptyMap() }
            // Seed synchronously from the loaded project: initiallyExpanded is one-shot, so if the first
            // composition saw an empty map the restored state would be ignored when it arrived a frame later.
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                projectRepository.currentProject.value?.railExpansion ?: emptyMap()
            )

    /**
     * Persist a host item's expanded/collapsed state into the project record so it survives reopen.
     * Wired to AzNavRail's per-host `onExpandedChange` (10.11), which fires on manual toggles only.
     */
    fun onRailHostExpansionChanged(hostId: String, expanded: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            projectRepository.updateProject { it.copy(railExpansion = it.railExpansion + (hostId to expanded)) }
        }
    }

    /**
     * Active, loaded code extensions (filters and tools) exposed to the UI panels.
     */
    val installedExtensions: StateFlow<List<com.hereliesaz.graffitixr.data.azphalt.InstalledExtension>> =
        extensionRepository.installed
            .map { list -> list.filter { it.manifest.kind == com.hereliesaz.graffitixr.common.azphalt.ExtensionKind.CODE || it.manifest.kind == com.hereliesaz.graffitixr.common.azphalt.ExtensionKind.MIXED } }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList()
            )

    private val history = EditHistory(HISTORY_DEPTH)

    // Per-layer base-bitmap and stroke caches (thread-safe; see LayerStore).
    private val layerStore = LayerStore()

    // Stroke-compositing pipeline (base + strokes -> rendered bitmap; see DrawingEngine).
    private val drawingEngine = DrawingEngine(slamManager)
    // Debounced disk saves, keyed by layer id. A single shared job would let a save
    // scheduled for layer B cancel a still-pending save for layer A, silently dropping
    // A's strokes; per-layer jobs cancel only the same layer's superseded save.
    private val pendingSaveJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    // Per-layer rebuild jobs: cancels stale compositing coroutines on rapid undo/redo so
    // only the most recent rebuild's result lands in the UI state.
    private val rebuildJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    // Debounced project-preview thumbnail generation. saveProject() fires on nearly every edit,
    // so the thumbnail is regenerated at most once the edits settle, off the main thread.
    private var thumbnailJob: kotlinx.coroutines.Job? = null

    private var copiedLayerState: Layer? = null
    private var anchorHalfExtentMeters: Pair<Float, Float>? = null

    // Real-time stroke state — valid only between onStrokeStart and onStrokeEnd.
    private var strokeWorkingBitmap: Bitmap? = null
    private var strokeWorkingCanvas: Canvas? = null
    private var strokePaint: Paint? = null
    private var strokePrevBitmapPoint: Offset? = null
    // Captured at stroke start so mid-stroke toggles can't desync the live paint from the recorded
    // StrokeCommand (which is what undo/redo replays).
    private var strokeSymmetry: SymmetryMode = SymmetryMode.NONE
    private var strokeAlphaLock: Boolean = false
    /** The lasso in force when the in-flight stroke began — see [strokeSymmetry] for why captured. */
    private var strokeSelection: com.hereliesaz.graffitixr.common.model.Selection? = null
    /** Uptime of the last touch sample this stroke actually rendered — the input-rate throttle. */
    private var lastSampleMs: Long = 0L
    // Incremental brush dynamics for the live stroke; same recursion the commit/replay paths run
    // from scratch, so live pixels match replayed pixels exactly.
    private var strokeDynamics: BrushDynamics.State? = null
    // Eyedropper: the screen-sized composite the long-press samples from (built once per hold).
    private var eyedropComposite: Bitmap? = null
    private val strokeCollectedPointsLock = Any()
    // Touched from the main thread (add/reset) and background Default coroutines (snapshot). All access
    // MUST go through the synchronized helpers below, or a concurrent add during toList() throws a
    // ConcurrentModificationException mid-stroke (uncaught in viewModelScope → crash).
    private var strokeCollectedPoints: MutableList<Offset> = mutableListOf()

    private fun resetStrokePoints(initial: Offset? = null) = synchronized(strokeCollectedPointsLock) {
        // `vararg Offset` is rejected by the compiler (Offset is a Compose value class), so take a
        // single optional seed point — the only two call sites are reset-with-start and reset-empty.
        strokeCollectedPoints = if (initial != null) mutableListOf(initial) else mutableListOf()
    }

    private fun addStrokePoint(point: Offset) = synchronized(strokeCollectedPointsLock) {
        strokeCollectedPoints.add(point)
    }

    private fun snapshotStrokePoints(): List<Offset> = synchronized(strokeCollectedPointsLock) {
        strokeCollectedPoints.toList()
    }
    private var strokeLayerId: String? = null
    private var strokeCanvasW: Int = 0
    private var strokeCanvasH: Int = 0
    // Layer transform snapshot captured at stroke start — held constant for the whole stroke.
    private var strokeLayerScale: Float = 1f
    private var strokeLayerOffset: Offset = Offset.Zero
    private var strokeLayerRotationZ: Float = 0f

    // Liquify live-preview state — valid only between onStrokeStart and onStrokeEnd for LIQUIFY.
    private var liquifyJob: kotlinx.coroutines.Job? = null
    private var liquifyOriginalBitmap: Bitmap? = null

    // The selected azphalt stamp brush's parsed definition (null = built-in round brush). Set by
    // selectBrushExtension; read at stroke-commit to route through StampBrushRenderer.
    private var activeStampBrush: com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush? = null
    // Decoded tip image for the active stamp brush (null = a generated round tip). Loaded alongside it.
    private var activeStampShape: Bitmap? = null

    // Live stamp-stroke preview state — valid only between onStrokeStart and onStrokeEnd for a stamp
    // brush. Dabs are stamped incrementally onto [stampLiveBitmap]; [stampSeed] fixes the jitter so the
    // preview, the commit, and history replay all render identically.
    private var stampLiveBitmap: Bitmap? = null
    private var stampLiveCanvas: Canvas? = null
    private var stampStampedCount: Int = 0
    private var stampSeed: Long = 0L
    private var stampBrushForStroke: com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush? = null
    private var stampShapeForStroke: Bitmap? = null
    // Bitmap-space stroke points, appended incrementally so each drag frame maps only the NEW points
    // instead of re-mapping the whole stroke (interleaved [x0,y0,…]).
    private val stampMappedPoints = ArrayList<Float>()

    private val strokeStabilizer = StrokeStabilizer()

    // Streams a downsampled snapshot to a GIF after every committed stroke while recording is on.
    private val timeLapseRecorder = TimeLapseRecorder()

    init {
        viewModelScope.launch(dispatchers.main) {
            settingsRepository.backgroundColor.collect { argb ->
                dispatch(EditorIntent.SetCanvasBackground(Color(argb.toLong() and 0xFFFFFFFFL)))
            }
        }
        // Performance settings, mirrored into UiState so the hot paths (onStrokePoint, layer
        // allocation) read them without touching DataStore per touch sample.
        viewModelScope.launch(dispatchers.main) {
            settingsRepository.inputSampleRateHz.collect { hz ->
                dispatch(EditorIntent.SetInputSampleRateHz(hz))
            }
        }
        viewModelScope.launch(dispatchers.main) {
            settingsRepository.canvasRenderScale.collect { scale ->
                dispatch(EditorIntent.SetCanvasRenderScale(scale))
            }
        }
        // The Settings screen's "Right-handed" toggle persisted correctly but nothing ever read it
        // back — MainActivity's rail docking side was driven by uiState.isRightHanded, a field only
        // ToggleHandedness (itself never called) could change, so it silently stayed at its default
        // regardless of what the user set in Settings.
        viewModelScope.launch(dispatchers.main) {
            settingsRepository.isRightHanded.collect { isRight ->
                dispatch(EditorIntent.SetHandedness(isRight))
            }
        }
        // Same gap as handedness: the Settings screen's "Imperial units" toggle persisted correctly
        // but the rulers (the only place a unit is ever shown) never read it back — see Rulers().
        viewModelScope.launch(dispatchers.main) {
            settingsRepository.isImperialUnits.collect { imperial ->
                dispatch(EditorIntent.SetImperialUnits(imperial))
            }
        }
        viewModelScope.launch(dispatchers.main) {
            settingsRepository.gestureMapping.collect { mapping ->
                dispatch(EditorIntent.SetGestureMapping(mapping))
            }
        }

        // Bootstrap a project. Nothing else in the app ever loads or creates one, so without this
        // `projectId` stays null for the whole session — and every content entry point guards on it
        // (`?: return`), which made a cold start silently swallow File > New, File > Open and every
        // pen stroke. Reopen the most recent project if there is one, else start a fresh one.
        // (Layers are not created here: picking a raster tool makes one on demand, see setActiveTool.)
        viewModelScope.launch(dispatchers.io) {
            if (projectRepository.currentProject.value == null) {
                val mostRecent = projectRepository.getProjects().maxByOrNull { it.lastModified }
                if (mostRecent != null) projectRepository.loadProject(mostRecent.id)
                else projectRepository.createProject("Untitled")
            }
        }

        viewModelScope.launch(dispatchers.main) {
            projectRepository.currentProject.collect { project ->
                if (project != null) {
                    val projectIdChanged = _uiState.value.projectId != project.id

                    if (projectIdChanged) {
                        val currentLayers = _uiState.value.layers
                        val layers = project.layers.map { overlayLayer ->
                            val existingLayer = currentLayers.find { it.id == overlayLayer.id }
                            val layer = overlayLayer.toLayer()
                            if (existingLayer != null && existingLayer.uri == layer.uri) {
                                layer.copy(bitmap = existingLayer.bitmap)
                            } else {
                                layer
                            }
                        }

                        dispatch(
                            EditorIntent.LoadedProject(
                                project.id, layers, project.colorStyles, project.textStyles,
                            ),
                        )
                        dispatch(EditorIntent.SetDocumentSize(project.documentWidth, project.documentHeight))
                        restoreModel(project)

                        val layersToLoad = layers.filter { it.bitmap == null && it.uri != null }
                        if (layersToLoad.isNotEmpty()) {
                            viewModelScope.launch(dispatchers.io) {
                                val loadedLayers = layers.map { layer ->
                                    val layerUri = layer.uri
                                    if (layer.bitmap == null && layerUri != null) {
                                        val loadedBmp = ImageUtils.loadBitmapAsync(context, layerUri)
                                        if (loadedBmp != null) {
                                            layerStore.putBase(layer.id, loadedBmp.copy(Bitmap.Config.ARGB_8888, false))
                                            layerStore.initStrokes(layer.id)
                                        }
                                        layer.copy(bitmap = loadedBmp)
                                    } else {
                                        layer
                                    }
                                }
                                withContext(dispatchers.main) {
                                    dispatch(EditorIntent.SetLayers(loadedLayers))
                                }
                            }
                        }

                        viewModelScope.launch(dispatchers.io) {
                            slamManager.clearMap()
                            val mapPath = projectManager.getMapPath(context, project.id)
                            if (File(mapPath).exists()) {
                                slamManager.loadModel(mapPath)
                            }

                            project.fingerprint?.let { fp ->
                                val intr = project.fingerprintIntrinsics
                                val anchor = project.fingerprintAnchor
                                if (intr.size >= 4 && anchor.size == 16) {
                                    // Metric fingerprint: replay the true capture intrinsics + anchor
                                    // so reload reloc matches the live capture, not a default guess.
                                    slamManager.restoreWallFingerprintMetric(
                                        fp.descriptorsData,
                                        fp.descriptorsRows,
                                        fp.descriptorsCols,
                                        fp.descriptorsType,
                                        fp.points3d.toFloatArray(),
                                        anchor.toFloatArray(),
                                        intr.toFloatArray(),
                                    )
                                } else {
                                    slamManager.restoreWallFingerprint(
                                        fp.descriptorsData,
                                        fp.descriptorsRows,
                                        fp.descriptorsCols,
                                        fp.descriptorsType,
                                        fp.points3d.toFloatArray()
                                    )
                                }
                                // Restore the distortion-head canonical patch (256x256 raw gray).
                                if (fp.patchData.isNotEmpty()) {
                                    val s = kotlin.math.sqrt(fp.patchData.size.toDouble()).toInt()
                                    if (s * s == fp.patchData.size) slamManager.setWallPatchBytes(fp.patchData, s)
                                }
                            }
                        }

                        project.backgroundImageUri?.let { uri ->
                            viewModelScope.launch(dispatchers.io) {
                                val bitmap = ImageUtils.loadBitmapAsync(context, uri)
                                withContext(dispatchers.main) {
                                    dispatch(EditorIntent.SetBackgroundBitmap(bitmap))
                                }
                            }
                        }
                    }
                } else {
                    dispatch(EditorIntent.ClearProject)
                    slamManager.clearMap()
                    layerStore.clear()
                    history.clear()
                }
            }
        }
    }

    private fun pushHistory() {
        history.pushProperty(_uiState.value.layers.map { it.copy(bitmap = null) })
        updateHistoryCounts()
    }

    private fun updateHistoryCounts() {
        _uiState.update { it.copy(undoCount = history.undoCount, redoCount = history.redoCount) }
    }

    /** The current layer set, stripped of bitmaps — what we record so an undo can be reverted. */
    private fun currentLayerSnapshot(): List<Layer> = _uiState.value.layers.map { it.copy(bitmap = null) }

    // ── Transient HUD (Procreate's confirmations) ────────────────────────────────────────────

    private var hudJob: kotlinx.coroutines.Job? = null
    private var brushHudJob: kotlinx.coroutines.Job? = null

    /** Flash a short confirmation pill ("Undo", "Redo") over the canvas. */
    private fun showHud(message: String) {
        hudJob?.cancel()
        _uiState.update { it.copy(hudMessage = message) }
        hudJob = viewModelScope.launch(dispatchers.main) {
            kotlinx.coroutines.delay(700)
            _uiState.update { it.copy(hudMessage = null) }
        }
    }

    /** Show the brush-diameter preview circle while the size slider is moving. */
    private fun showBrushHud() {
        brushHudJob?.cancel()
        if (!_uiState.value.brushHudVisible) _uiState.update { it.copy(brushHudVisible = true) }
        brushHudJob = viewModelScope.launch(dispatchers.main) {
            kotlinx.coroutines.delay(800)
            _uiState.update { it.copy(brushHudVisible = false) }
        }
    }

    /** Procreate's four-finger tap: hide every panel and fold the rail for full-screen art. */
    fun toggleHideUi() {
        _uiState.update { it.copy(hideUiForCapture = !it.hideUiForCapture) }
    }

    override fun onUndoClicked() {
        if (_uiState.value.undoCount > 0) showHud("Undo")
        val command = history.popUndo { undone ->
            when (undone) {
                is EditCommand.Draw -> undone
                is EditCommand.PropertyChange -> EditCommand.PropertyChange(currentLayerSnapshot())
            }
        } ?: return

        when (command) {
            is EditCommand.Draw -> {
                if (!layerStore.removeLastStroke(command.layerId)) return
                rebuildLayerBitmap(command.layerId, emitOp = true)
                // Undoing a selection move must walk the marquee back with its pixels, or it would
                // sit over content it no longer bounds.
                if (command.command.tool == Tool.SELECT) {
                    dispatch(EditorIntent.SetSelection(command.command.selection))
                }
            }
            is EditCommand.PropertyChange -> {
                val currentBitmaps = _uiState.value.layers.associate { it.id to it.bitmap }
                val restoredLayers = command.oldLayers.map { it.copy(bitmap = currentBitmaps[it.id]) }
                dispatch(EditorIntent.SetLayers(restoredLayers))
                saveProject()
                emitLayerStateResync(restoredLayers)
                rebuildMissingBitmaps(restoredLayers)
            }
        }
        updateHistoryCounts()
    }

    override fun onRedoClicked() {
        if (_uiState.value.redoCount > 0) showHud("Redo")
        val command = history.popRedo { redone ->
            when (redone) {
                is EditCommand.Draw -> redone
                is EditCommand.PropertyChange -> EditCommand.PropertyChange(currentLayerSnapshot())
            }
        } ?: return

        when (command) {
            is EditCommand.Draw -> {
                layerStore.addStroke(command.layerId, command.command)
                rebuildLayerBitmap(command.layerId, emitOp = true)
                // Redo re-applies the move, so the marquee moves forward with it again.
                val delta = command.command.moveDelta
                if (command.command.tool == Tool.SELECT && delta != null) {
                    dispatch(EditorIntent.SetSelection(
                        command.command.selection?.translated(delta)
                    ))
                }
            }
            is EditCommand.PropertyChange -> {
                val currentBitmaps = _uiState.value.layers.associate { it.id to it.bitmap }
                val restoredLayers = command.oldLayers.map { it.copy(bitmap = currentBitmaps[it.id]) }
                dispatch(EditorIntent.SetLayers(restoredLayers))
                saveProject()
                emitLayerStateResync(restoredLayers)
                rebuildMissingBitmaps(restoredLayers)
            }
        }
        updateHistoryCounts()
    }

    /**
     * Rebuilds pixels for any [layers] entry that came back from undo/redo with a null bitmap —
     * i.e. it wasn't in the live state a moment ago (undoing a delete/flatten, or redoing an add),
     * so the `currentBitmaps[id]` lookup in onUndoClicked/onRedoClicked couldn't find it. Without
     * this the layer reappears in the panel but renders nothing for the rest of the session.
     * rebuildLayerBitmap itself no-ops for a layer LayerStore never cached (e.g. vector/text), so
     * this is safe to call unconditionally over every restored layer.
     */
    private fun rebuildMissingBitmaps(layers: List<Layer>) {
        layers.filter { it.bitmap == null }.forEach { rebuildLayerBitmap(it.id, emitOp = true) }
    }

    /**
     * Folds strokes older than the undo depth into the layer's base bitmap and drops them.
     *
     * A layer rebuilds by replaying its *whole* stroke list onto the base, so without this the list
     * grows for the life of the session: every undo costs one full-bitmap composite per stroke ever
     * made on that layer, and every recorded path — plus any stamp-brush bitmap it references — is
     * retained forever. Both were unbounded.
     *
     * [EditHistory] keeps at most [HISTORY_DEPTH] entries, so a stroke older than that can never be
     * undone back to. Baking it produces identical pixels for strictly less work and memory.
     *
     * The composite runs against a *snapshot* and the strokes are only removed afterwards, on the
     * main thread, so a cancelled bake loses nothing: strokes are append-only, which keeps "the
     * oldest N" the same N it composited.
     */
    private fun maybeBakeOldStrokes(layerId: String) {
        val excess = layerStore.strokeCount(layerId) - HISTORY_DEPTH
        if (excess <= 0) return
        val base = layerStore.base(layerId) ?: return
        val stale = layerStore.strokes(layerId).take(excess)
        if (stale.isEmpty()) return

        viewModelScope.launch(dispatchers.default) {
            try {
                val baked = drawingEngine.composite(base, stale)
                withContext(dispatchers.main) {
                    // Re-check under the main thread: a project reload could have replaced the
                    // layer's caches wholesale while this ran, and baking onto a stale base would
                    // resurrect deleted pixels.
                    if (layerStore.base(layerId) !== base) {
                        baked.recycle()
                        return@withContext
                    }
                    layerStore.takeOldestStrokes(layerId, stale.size)
                    layerStore.putBase(layerId, baked)
                    // The superseded base is deliberately NOT recycled: a rebuild launched before
                    // this bake may still be compositing from it on another thread, and recycling
                    // it underneath would fail that rebuild. It is unreachable now, so the
                    // collector reclaims it — a moment later, but safely.
                    //
                    // Swapping the base and dropping the strokes together on the main thread is
                    // what keeps this correct: rebuildLayerBitmap captures base and strokes on the
                    // same thread, so it can never pair a freshly baked base with the strokes
                    // already folded into it and apply them twice.
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Losing a bake is harmless — the strokes are still queued and still replay.
                android.util.Log.w("EditorViewModel", "Failed to bake old strokes for $layerId", e)
            }
        }
    }

    /**
     * The pixel size a newly created layer should allocate at: the screen, scaled by the user's
     * canvas render scale.
     *
     * A layer costs width*height*4 bytes twice over (the displayed bitmap and [LayerStore]'s
     * pristine base), so on a 1440x3120 panel each layer is ~36 MB at full scale and a handful of
     * them exhausts the heap. Scale is quadratic here: 0.5 leaves a quarter of the bytes.
     *
     * Stroke coordinates are unaffected — [ImageProcessor.mapScreenToBitmap] derives its fit scale
     * from the bitmap's own dimensions, so a smaller layer takes the same strokes in the same
     * places and simply renders scaled up.
     */
    private fun newLayerSize(): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val scale = _uiState.value.canvasRenderScale.coerceIn(0.25f, 1f)
        val w = (metrics.widthPixels.takeIf { it > 0 } ?: 1080) * scale
        val h = (metrics.heightPixels.takeIf { it > 0 } ?: 1920) * scale
        return w.toInt().coerceAtLeast(1) to h.toInt().coerceAtLeast(1)
    }

    private fun rebuildLayerBitmap(layerId: String, emitOp: Boolean = false) {
        val base = layerStore.base(layerId)
            ?: _uiState.value.layers.find { it.id == layerId }?.bitmap?.also { layerStore.putBase(layerId, it) }
            ?: return
        val strokes = layerStore.strokes(layerId)

        rebuildJobs[layerId]?.cancel()
        rebuildJobs[layerId] = viewModelScope.launch(dispatchers.default) {
            // Compositing replays strokes through OpenCV (and SLAM for Liquify). Guard it so a
            // failure during undo/redo logs instead of taking down the app — the stroke list and
            // base are unchanged, so the next edit re-renders cleanly.
            try {
                val currentBitmap = drawingEngine.composite(base, strokes)

                if (emitOp) {
                    // Used by undo/redo: the layer's pixels changed in a way the guest can't replay,
                    // so push the whole baked bitmap.
                    opEmitter.emit(Op.LayerBitmapReplace(layerId, ImageUtils.bitmapToByteArray(currentBitmap)))
                }

                withContext(dispatchers.main) {
                    _uiState.update { state ->
                        state.copy(layers = state.layers.map { if (it.id == layerId) it.copy(bitmap = currentBitmap) else it })
                    }
                    val layer = _uiState.value.layers.find { it.id == layerId } ?: return@withContext
                    scheduleDiskSave(layerId, currentBitmap, layer.uri)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("EditorViewModel", "Failed to rebuild layer $layerId on undo/redo", e)
            }
        }
    }

    fun processNewStroke(layerId: String, activeBitmap: Bitmap, command: StrokeCommand, layer: Layer) {
        layerStore.addStroke(layerId, command)

        history.pushDraw(layerId, command)
        updateHistoryCounts()
        maybeBakeOldStrokes(layerId)

        viewModelScope.launch(dispatchers.default) {
            val newBitmap = drawingEngine.applySingleStroke(activeBitmap, command)

            withContext(dispatchers.main) {
                _uiState.update { state ->
                    state.copy(layers = state.layers.map { if (it.id == layerId) it.copy(bitmap = newBitmap) else it })
                }
                // Painting on a main component has to reach its instances. Idempotent and a no-op
                // when the document has no components, so it can run on every stroke unconditionally.
                if (_uiState.value.layers.any { it.componentId != null }) {
                    dispatch(EditorIntent.SyncComponents)
                }
            }

            scheduleDiskSave(layerId, newBitmap, layer.uri)
            if (_uiState.value.isTimeLapseRecording) captureTimeLapseFrame()
        }
    }

    /**
     * Composites the current canvas down to a small [TIME_LAPSE_FRAME_MAX_DIM]-ish snapshot and
     * hands it to [timeLapseRecorder]. Called off the main thread — see [processNewStroke].
     */
    private fun captureTimeLapseFrame() {
        val state = _uiState.value
        val docW = state.documentWidth
        val docH = state.documentHeight
        if (docW <= 0 || docH <= 0) return
        val metrics = context.resources.displayMetrics
        val longestDoc = maxOf(docW, docH)
        val scale = TIME_LAPSE_FRAME_MAX_DIM.toFloat() / longestDoc
        val targetW = (docW * scale).roundToInt().coerceAtLeast(1)
        val targetH = (docH * scale).roundToInt().coerceAtLeast(1)
        val frame = exportManager.compositeToDocument(
            state.layers,
            metrics.widthPixels,
            metrics.heightPixels,
            targetW,
            targetH,
            backgroundColor = android.graphics.Color.WHITE,
        )
        val consumed = timeLapseRecorder.captureFrame(frame, System.currentTimeMillis())
        if (!consumed) frame.recycle()
    }

    /** Starts or stops time-lapse recording, saving the finished GIF to Downloads on stop. */
    fun onToggleTimeLapseRecording() {
        if (_uiState.value.isTimeLapseRecording) {
            dispatch(EditorIntent.ToggleTimeLapseRecording)
            viewModelScope.launch(dispatchers.default) {
                val file = timeLapseRecorder.finish()
                if (file != null) {
                    saveTimeLapseToDownloads(file)
                } else {
                    withContext(dispatchers.main) {
                        Toast.makeText(context, "No time-lapse frames captured", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }
        viewModelScope.launch(dispatchers.default) {
            val dir = File(context.cacheDir, "timelapse").apply { mkdirs() }
            val file = File(dir, "timelapse_${System.currentTimeMillis()}.gif")
            val started = timeLapseRecorder.start(file)
            if (started) {
                captureTimeLapseFrame()
                withContext(dispatchers.main) {
                    dispatch(EditorIntent.ToggleTimeLapseRecording)
                    Toast.makeText(context, "Time-lapse recording started", Toast.LENGTH_SHORT).show()
                }
            } else {
                withContext(dispatchers.main) {
                    Toast.makeText(context, "Couldn't start time-lapse recording", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun saveTimeLapseToDownloads(file: File) =
        copyGifToDownloads(file, "Time-lapse saved to Downloads", "Time-lapse export failed")

    private suspend fun copyGifToDownloads(file: File, successMessage: String, failurePrefix: String) =
        copyToDownloads(file, "image/gif", successMessage, failurePrefix)

    /**
     * Copies a cache-dir file into Downloads (MediaStore on Q+, the public directory below it, the
     * same MediaStore/public-directory split) and deletes the cache copy either way.
     */
    private suspend fun copyToDownloads(
        file: File,
        mimeType: String,
        successMessage: String,
        failurePrefix: String,
    ) {
        try {
            val filename = file.name
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw java.io.IOException("Failed to create MediaStore entry")
                context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                withContext(dispatchers.main) {
                    Toast.makeText(context, successMessage, Toast.LENGTH_LONG).show()
                }
            } else {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val dest = File(downloadsDir, filename)
                file.copyTo(dest, overwrite = true)
                withContext(dispatchers.main) {
                    Toast.makeText(context, "$successMessage (${dest.absolutePath})", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            withContext(dispatchers.main) {
                Toast.makeText(context, "$failurePrefix: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            file.delete()
        }
    }

    override fun onCleared() {
        // Discard rather than save — leaving the encoder open would leak the FileOutputStream.
        timeLapseRecorder.finish()
        playbackJob?.cancel()
        super.onCleared()
    }

    private val sandboxHost = object : com.hereliesaz.graffitixr.data.azphalt.sandbox.AzphaltSandboxHost {
        override fun requestRedraw() {
            val layerId = _uiState.value.activeLayerId ?: return
            rebuildLayerBitmap(layerId, emitOp = true)
        }
        override fun canvasWidth(): Int = _uiState.value.documentWidth
        override fun canvasHeight(): Int = _uiState.value.documentHeight
        override fun canvasDpi(): Int = context.resources.displayMetrics.densityDpi
        
        override fun paramNumber(key: String): Double? = null
        override fun paramBool(key: String): Boolean? = null
        override fun paramString(key: String): String? = null
        
        override fun colorActive(): Int = _uiState.value.activeColor.toArgb()
        override fun colorSetActive(rgba: Int) {
            // Unused for now, but should dispatch intent
        }
        
        override fun assetRead(path: String): ByteArray? = null
        override fun selectionSize(): Int = 0
        override fun selectionRead(): ByteArray = ByteArray(0)
        override fun layerCount(): Int = _uiState.value.layers.size
    }

    fun onExtensionSelected(id: String) {
        // An asset-only (LUT) extension has no entry/runtime for executeCodeExtension to run — it
        // silently no-ops on kind != CODE/MIXED, so tapping a LUT in this panel used to just close
        // it having done nothing. Route it to applyInstalledLut instead, the same payoff the Store's
        // "install this filter" promise implies.
        if (extensionRepository.installedLuts().any { it.id == id }) {
            applyInstalledLut(id)
            dispatch(EditorIntent.DismissPanel)
            return
        }
        viewModelScope.launch(dispatchers.io) {
            try {
                extensionRepository.executeCodeExtension(id, sandboxHost)
                // If it succeeds, maybe dismiss the panel
                withContext(dispatchers.main) {
                    dispatch(EditorIntent.DismissPanel)
                }
            } catch (e: Exception) {
                withContext(dispatchers.main) {
                    Toast.makeText(context, "Extension execution failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * The pixels each layer still owes the disk: what a pending debounced save would write.
     * Recorded separately from the job so [flushPendingSaves] can write them straight out without
     * having to wait the debounce out first.
     */
    private val pendingWrites = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Bitmap>>()

    private fun scheduleDiskSave(layerId: String, bitmap: Bitmap, uri: Uri?) {
        val path = uri?.path ?: return
        pendingWrites[layerId] = path to bitmap
        // Cancel only this layer's previous pending save, never another layer's.
        pendingSaveJobs.remove(layerId)?.cancel()
        val job = viewModelScope.launch(dispatchers.io) {
            kotlinx.coroutines.delay(1500)
            writeLayerBitmap(layerId, path, bitmap)
            // Painting changes the project, so the manifest's modified time should move with it —
            // otherwise a session spent only painting leaves the project sorting as untouched in
            // the gallery, behind projects that were merely opened.
            saveProject()
            // Don't leak completed jobs in the map.
            pendingSaveJobs.remove(layerId, coroutineContext[kotlinx.coroutines.Job])
        }
        pendingSaveJobs[layerId] = job
    }

    /**
     * Writes [bitmap] to [path] atomically: compress into a sibling temp file, then rename over the
     * target. Writing straight onto the live file leaves a truncated PNG if the process is killed
     * mid-compress — and the process being killed mid-edit is exactly the case autosave exists for,
     * so the naive write fails precisely when it matters. A truncated layer doesn't decode, which
     * loses the whole layer rather than the last stroke.
     */
    private suspend fun writeLayerBitmap(layerId: String, path: String, bitmap: Bitmap) {
        try {
            if (bitmap.isRecycled) return
            val file = java.io.File(path)
            val tmp = java.io.File(file.parentFile, "${file.name}.tmp")
            java.io.FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    java.io.FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    tmp.delete()
                }
            }
            pendingWrites.remove(layerId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Normal: a newer stroke superseded this debounced save. Not a failure — and the entry
            // stays in pendingWrites so a flush still catches it.
            throw e
        } catch (e: Exception) {
            // A swallowed failure here means the user's edits are silently lost.
            android.util.Log.e("EditorViewModel", "Failed to save layer bitmap to $path", e)
            withContext(dispatchers.main) {
                Toast.makeText(context, "Couldn't save your changes — storage may be full", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Writes every debounced layer save immediately instead of waiting out its delay.
     *
     * Call this when the app leaves the foreground. Layer saves are debounced by 1.5s so that a
     * flurry of strokes doesn't re-encode a full-screen PNG on every one — but that debounce means
     * painting and then immediately switching away loses the last edits when the process is
     * reclaimed, because the pending coroutine dies with it. The point of saving as you go is that
     * leaving is never a decision the user has to make.
     */
    fun flushPendingSaves() {
        val outstanding = pendingWrites.entries.map { it.key to it.value }
        pendingSaveJobs.values.forEach { it.cancel() }
        pendingSaveJobs.clear()
        if (outstanding.isEmpty()) {
            saveProject()
            return
        }
        viewModelScope.launch(dispatchers.io) {
            outstanding.forEach { (layerId, write) ->
                val (path, bitmap) = write
                writeLayerBitmap(layerId, path, bitmap)
            }
            saveProject()
        }
    }

    override fun onAddLayer(uri: Uri) {
        pushHistory()
        viewModelScope.launch(dispatchers.io) {
            // Cap imported layers at a screen-reasonable size. A full 12MP+ photo is ~48MB as ARGB;
            // decoding/copying/PNG-encoding it (then rendering it as a texture every frame) is what
            // made the first layer take seconds to appear and the canvas lag. 2048px is ample here.
            val bitmap = ImageUtils.loadBitmapAsync(context, uri, maxDimension = 2048)
            val projectId = ensureProjectId()
            if (bitmap != null) {
                val filename = "layer_${UUID.randomUUID()}.png"
                val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(bitmap))
                val localUri = "file://$path".toUri()

                val metrics = context.resources.displayMetrics
                val screenW = metrics.widthPixels.toFloat()
                val screenH = metrics.heightPixels.toFloat()
                
                // Calculate initial scale to fit the screen
                val scaleW = screenW * 0.9f / bitmap.width
                val scaleH = screenH * 0.9f / bitmap.height
                val initialScale = minOf(scaleW, scaleH, 1.0f)

                val newLayer = Layer(
                    id = UUID.randomUUID().toString(),
                    name = "Layer ${_uiState.value.layers.size + 1}",
                    uri = localUri,
                    bitmap = bitmap,
                    isVisible = true,
                    scale = initialScale
                )

                layerStore.putBase(newLayer.id, bitmap.copy(Bitmap.Config.ARGB_8888, false))
                layerStore.initStrokes(newLayer.id)

                withContext(dispatchers.main) {
                    dispatch(EditorIntent.AddLayer(newLayer))
                    opEmitter.emit(Op.LayerAdd(newLayer))
                    saveProject()
                }
            } else {
                withContext(dispatchers.main) {
                    Toast.makeText(context, "Couldn't read that image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Opens a design document handed in as [uri]. A Photoshop `.psd` is decoded into its individual
     * layers — each becomes an editor layer carrying the source layer's name, opacity, and blend
     * mode — and the artboard is resized to the document. Inputs we can't yet decode into layers
     * degrade gracefully: an ordinary image is added as a single layer; anything else is explained
     * with a toast rather than failing silently.
     */
    fun onImportDocument(uri: Uri) {
        if (_uiState.value.projectId == null) return
        viewModelScope.launch(dispatchers.io) {
            val name = queryDisplayName(uri)
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
            if (bytes == null) {
                withContext(dispatchers.main) { toast("Couldn't read that file.") }
                return@launch
            }
            val format = DocumentImporter.detect(name, bytes)
            val doc = if (format == DocumentFormat.PSD) {
                try { DocumentImporter.readLayered(name, bytes) } catch (e: Exception) { null }
            } else {
                null
            }

            if (doc != null && doc.layers.isNotEmpty()) {
                importLayeredDocument(doc)
                return@launch
            }
            // PDF and modern Illustrator .ai (PDF-compatible) flatten to a bitmap via PdfRenderer.
            if (format == DocumentFormat.PDF) {
                val page = renderPdfFirstPage(bytes)
                if (page != null) {
                    importSingleBitmap(page, name?.substringBeforeLast('.') ?: "Imported")
                } else {
                    withContext(dispatchers.main) { toast("Couldn't render that PDF/Illustrator file.") }
                }
                return@launch
            }

            // Procreate: import the embedded composite as a single flattened layer (per-layer chunk
            // decode is a later phase).
            if (format == DocumentFormat.PROCREATE) {
                val composite = extractProcreateComposite(bytes)
                if (composite != null) {
                    importSingleBitmap(composite, name?.substringBeforeLast('.') ?: "Procreate")
                } else {
                    withContext(dispatchers.main) {
                        toast("Couldn't read that Procreate file (no embedded preview).")
                    }
                }
                return@launch
            }

            withContext(dispatchers.main) {
                when (format) {
                    DocumentFormat.IMAGE -> onAddLayer(uri)
                    DocumentFormat.PSD ->
                        toast("This PSD uses a mode that isn't supported yet (only 8-bit RGB).")
                    DocumentFormat.CANVA ->
                        toast("Canva files aren't stored locally — export to PNG or PDF and open that.")
                    else -> toast("That file type isn't supported.")
                }
            }
        }
    }

    /**
     * Pulls the flattened composite out of a Procreate `.procreate` archive (a ZIP). Procreate keeps a
     * full-canvas preview at `QuickLook/Thumbnail.png`; we decode that. Returns null if the archive has
     * no such preview or isn't readable. (True per-layer import needs decoding Procreate's proprietary
     * tiled/compressed layer chunks — a later phase.) Runs off the main thread.
     */
    private fun extractProcreateComposite(bytes: ByteArray): Bitmap? = try {
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
            var found: Bitmap? = null
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory &&
                    entry.name.substringAfterLast('/').equals("Thumbnail.png", ignoreCase = true)
                ) {
                    val data = zip.readBytes()
                    found = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                    break
                }
                entry = zip.nextEntry
            }
            found
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Renders the first page of a PDF (or PDF-compatible Illustrator `.ai`) held in [bytes] to a
     * bitmap, longest side capped at 2048px, on a white background (PDF pages assume white paper).
     * Returns null if the bytes aren't a renderable PDF. Runs off the main thread.
     */
    private fun renderPdfFirstPage(bytes: ByteArray): Bitmap? {
        val tmp = File(context.cacheDir, "import_${UUID.randomUUID()}.pdf")
        return try {
            tmp.writeBytes(bytes)
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount <= 0) return null
                    renderer.openPage(0).use { page ->
                        val longest = maxOf(page.width, page.height).coerceAtLeast(1)
                        // Render at ~150dpi but never exceed the 2048px cap.
                        val renderScale = minOf(150f / 72f, 2048f / longest)
                        val w = (page.width * renderScale).toInt().coerceAtLeast(1)
                        val h = (page.height * renderScale).toInt().coerceAtLeast(1)
                        val bmp = createBitmap(w, h)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        } catch (e: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }

    /** Adds [bitmap] as a single new layer, scaled to fit the screen — the flattened-import path
     *  shared by PDF/Illustrator (and any future single-image decoder). */
    private suspend fun importSingleBitmap(bitmap: Bitmap, name: String) {
        val projectId = _uiState.value.projectId ?: return
        val metrics = context.resources.displayMetrics
        val initialScale = minOf(
            metrics.widthPixels * 0.9f / bitmap.width.coerceAtLeast(1),
            metrics.heightPixels * 0.9f / bitmap.height.coerceAtLeast(1),
            1f,
        )
        val filename = "layer_${UUID.randomUUID()}.png"
        val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(bitmap))
        val layer = Layer(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Imported" },
            uri = "file://$path".toUri(),
            bitmap = bitmap,
            isVisible = true,
            scale = initialScale,
        )
        layerStore.putBase(layer.id, bitmap.copy(Bitmap.Config.ARGB_8888, false))
        layerStore.initStrokes(layer.id)
        withContext(dispatchers.main) {
            pushHistory()
            dispatch(EditorIntent.AddLayer(layer))
            opEmitter.emit(Op.LayerAdd(layer))
            saveProject()
        }
    }

    /**
     * Composites each [ImportedLayer] onto a document-sized transparent canvas and adds it as an
     * editor layer. Every layer shares the same canvas footprint, so their `ContentScale.Fit` mapping
     * is identical and the source layout is reproduced exactly with `offset = 0` and a common scale.
     * The working canvas is capped so a many-layer import doesn't exhaust the heap (per-layer cropping
     * to native bounds is a later optimisation).
     */
    private suspend fun importLayeredDocument(doc: ImportedDocument) {
        val projectId = _uiState.value.projectId ?: return
        val cap = 2048f
        val docScale = minOf(1f, cap / maxOf(doc.width, doc.height).coerceAtLeast(1))
        val fullW = (doc.width * docScale).toInt().coerceAtLeast(1)
        val fullH = (doc.height * docScale).toInt().coerceAtLeast(1)

        val metrics = context.resources.displayMetrics
        val initialScale = minOf(
            metrics.widthPixels * 0.9f / fullW,
            metrics.heightPixels * 0.9f / fullH,
            1f,
        )

        val created = ArrayList<Layer>(doc.layers.size)
        for (imported in doc.layers) {
            if (imported.width <= 0 || imported.height <= 0) continue
            val full = createBitmap(fullW, fullH)
            val small = Bitmap.createBitmap(imported.argb, imported.width, imported.height, Bitmap.Config.ARGB_8888)
            val dst = android.graphics.RectF(
                imported.left * docScale, imported.top * docScale,
                (imported.left + imported.width) * docScale, (imported.top + imported.height) * docScale,
            )
            Canvas(full).drawBitmap(small, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            small.recycle()

            val filename = "layer_${UUID.randomUUID()}.png"
            val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(full))
            val layer = Layer(
                id = UUID.randomUUID().toString(),
                name = imported.name.ifBlank { "Layer ${created.size + 1}" },
                uri = "file://$path".toUri(),
                bitmap = full,
                isVisible = imported.isVisible,
                opacity = imported.opacity,
                scale = initialScale,
                blendMode = imported.blendMode.toComposeBlendMode(),
            )
            layerStore.putBase(layer.id, full.copy(Bitmap.Config.ARGB_8888, false))
            layerStore.initStrokes(layer.id)
            created += layer
        }

        withContext(dispatchers.main) {
            pushHistory()
            dispatch(EditorIntent.SetDocumentSize(doc.width, doc.height))
            created.forEach { layer ->
                dispatch(EditorIntent.AddLayer(layer))
                opEmitter.emit(Op.LayerAdd(layer))
            }
            saveProject()
        }
    }

    /** Best-effort human-readable file name for [uri] (for format detection + layer naming). */
    private fun queryDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment
    } catch (e: Exception) {
        uri.lastPathSegment
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    /**
     * The current project's id, creating a project first if there isn't one yet. Callers used to read
     * [EditorUiState.projectId] and `?: return` when it was null, which silently discarded whatever the
     * user was making; go through this instead so the work always lands somewhere.
     */
    private suspend fun ensureProjectId(): String {
        _uiState.value.projectId?.let { return it }
        if (projectRepository.currentProject.value == null) {
            projectRepository.createProject("Untitled")
        }
        // Return only once the currentProject collector has published it into uiState, so a caller that
        // adds a layer next isn't clobbered by LoadedProject landing behind it.
        return _uiState.first { it.projectId != null }.projectId!!
    }

    /** [activeToolOverride], when set, keeps that tool active on the new layer instead of resetting
     *  to none — see [setActiveTool], the only caller that needs it. */
    fun onAddBlankLayer(activeToolOverride: Tool? = null) {
        pushHistory()
        viewModelScope.launch(dispatchers.io) {
            val projectId = ensureProjectId()
            val (width, height) = newLayerSize()
            val blankBitmap = createBitmap(width, height)

            // Persisting the artifact can fail (storage full, a first-run I/O hiccup) — don't let
            // that leave the layer (and, via activeToolOverride, the tool the user picked) stuck
            // never landing. Fall back to an in-memory-only layer and warn, rather than fail silently.
            val localUri = try {
                val filename = "layer_${UUID.randomUUID()}.png"
                val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(blankBitmap))
                "file://$path".toUri()
            } catch (e: Exception) {
                android.util.Log.e("EditorViewModel", "Failed to persist new layer artifact", e)
                withContext(dispatchers.main) {
                    Toast.makeText(context, "Couldn't save the new layer — storage may be full", Toast.LENGTH_LONG).show()
                }
                null
            }

            withContext(dispatchers.main) {
                val sketchCount = _uiState.value.layers.count { it.isSketch }
                val newLayer = Layer(
                    id = UUID.randomUUID().toString(),
                    name = "Sketch ${sketchCount + 1}",
                    isSketch = true,
                    bitmap = blankBitmap,
                    uri = localUri
                )

                layerStore.putBase(newLayer.id, blankBitmap.copy(Bitmap.Config.ARGB_8888, false))
                layerStore.initStrokes(newLayer.id)

                dispatch(EditorIntent.AddLayer(newLayer, activeToolOverride = activeToolOverride))
                opEmitter.emit(Op.LayerAdd(newLayer))
                saveProject()
            }
        }
    }

    /**
     * Adds a vector layer holding a single [kind] shape, centered and active. Unlike raster layers
     * this needs no bitmap/artifact — the shape is drawn from the model — so it's synchronous.
     */
    fun onAddShapeLayer(kind: com.hereliesaz.graffitixr.common.model.ShapeKind) {
        pushHistory()
        val name = when (kind) {
            com.hereliesaz.graffitixr.common.model.ShapeKind.RECTANGLE -> "Rectangle"
            com.hereliesaz.graffitixr.common.model.ShapeKind.ELLIPSE -> "Ellipse"
            com.hereliesaz.graffitixr.common.model.ShapeKind.LINE -> "Line"
            com.hereliesaz.graffitixr.common.model.ShapeKind.POLYGON -> "Polygon"
            com.hereliesaz.graffitixr.common.model.ShapeKind.PATH -> "Path"
        }
        val count = _uiState.value.layers.count { it.shapes.isNotEmpty() }
        val shape = when (kind) {
            com.hereliesaz.graffitixr.common.model.ShapeKind.LINE ->
                com.hereliesaz.graffitixr.common.model.VectorShape(kind = kind, strokeArgb = 0xFFFFFFFFL, strokeWidth = 6f)
            else ->
                com.hereliesaz.graffitixr.common.model.VectorShape(kind = kind, fillArgb = 0xFF888888L, strokeWidth = 0f)
        }
        val newLayer = Layer(
            id = UUID.randomUUID().toString(),
            name = "$name ${count + 1}",
            shapes = listOf(shape),
        )
        dispatch(EditorIntent.AddLayer(newLayer))
        opEmitter.emit(Op.LayerAdd(newLayer))
        saveProject()
    }

    /**
     * Adds a new vector layer holding a single regular polygon with [sides] vertices (floored at 3)
     * — the [ShapeKind.POLYGON] counterpart to [onAddShapeLayer]. Filled grey by default; resize /
     * fill / stroke controls all apply, as they key off the layer being a vector layer.
     */
    fun onAddPolygonLayer(sides: Int) {
        pushHistory()
        val n = sides.coerceAtLeast(3)
        val count = _uiState.value.layers.count { it.shapes.isNotEmpty() }
        val shape = com.hereliesaz.graffitixr.common.model.VectorShape(
            kind = com.hereliesaz.graffitixr.common.model.ShapeKind.POLYGON,
            fillArgb = 0xFF888888L,
            strokeWidth = 0f,
            sides = n,
        )
        val newLayer = Layer(
            id = UUID.randomUUID().toString(),
            name = "Polygon ${count + 1}",
            shapes = listOf(shape),
        )
        dispatch(EditorIntent.AddLayer(newLayer))
        opEmitter.emit(Op.LayerAdd(newLayer))
        saveProject()
    }

    /**
     * Commits a freeform pen stroke as a new open vector PATH layer. [screenPoints] are the traced
     * points in screen pixels on a canvas of [canvasWidth]×[canvasHeight]; they're mapped back through
     * the camera (undo pan/zoom/rotation) into world space so the path lands exactly where drawn, then
     * re-centred on the layer origin with the layer offset placing it. Stroke colour/width come from
     * the current brush colour/size. No-op for a degenerate (<2 point) stroke.
     */
    fun onCommitPenPath(screenPoints: List<Offset>, canvasWidth: Float, canvasHeight: Float) {
        // No projectId guard: a pen layer is pure vector (no artifact file to write), and saveProject()
        // below creates the project if there isn't one. Bailing here is what made a pen stroke vanish
        // the instant the finger lifted — PenCanvas dropped its preview and nothing took its place.
        if (screenPoints.size < 2) return
        val st = _uiState.value
        val cx = canvasWidth / 2f
        val cy = canvasHeight / 2f
        // screen → world: R(-rot) · (screen - offset) / zoom
        val rad = Math.toRadians(-st.viewportRotation.toDouble())
        val cos = kotlin.math.cos(rad)
        val sin = kotlin.math.sin(rad)
        val world = ArrayList<Float>(screenPoints.size * 2)
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        for (p in screenPoints) {
            val ux = (p.x - st.viewportOffset.x) / st.viewportZoom
            val uy = (p.y - st.viewportOffset.y) / st.viewportZoom
            val wx = (ux * cos - uy * sin).toFloat()
            val wy = (ux * sin + uy * cos).toFloat()
            world.add(wx); world.add(wy)
            if (wx < minX) minX = wx
            if (wx > maxX) maxX = wx
            if (wy < minY) minY = wy
            if (wy > maxY) maxY = wy
        }
        // Simplify the raw per-touch capture into a tidy path (tolerance ~2 screen px in world units).
        val simplified = PathSimplify.rdp(world, 2f / st.viewportZoom.coerceAtLeast(0.01f))
        val shape = VectorPaths.pathShape(
            points = simplified,
            closed = false,
            strokeArgb = st.activeColor.toArgb().toLong() and 0xFFFFFFFFL,
            strokeWidth = st.brushSize.coerceAtLeast(1f),
        ) ?: return
        pushHistory()
        val count = _uiState.value.layers.count { it.shapes.isNotEmpty() }
        val worldCenter = Offset((minX + maxX) / 2f, (minY + maxY) / 2f)
        val newLayer = Layer(
            id = UUID.randomUUID().toString(),
            name = "Path ${count + 1}",
            shapes = listOf(shape),
            offset = Offset(worldCenter.x - cx, worldCenter.y - cy),
        )
        dispatch(EditorIntent.AddLayer(newLayer))
        // AddLayer resets the tool to NONE; keep the pen active so strokes can be drawn continuously.
        dispatch(EditorIntent.SetActiveTool(Tool.PEN))
        opEmitter.emit(Op.LayerAdd(newLayer))
        saveProject()
    }

    /**
     * Aligns the active layer to the artboard by [mode] (left / centre / right / top / middle /
     * bottom). Computes the layer's world bounding box and the artboard rect, then shifts the layer's
     * offset by the pure [AlignOps] delta. A no-op if there's no active layer or it's already aligned.
     */
    fun alignActiveLayer(mode: AlignMode) {
        val st = _uiState.value
        val layer = st.layers.find { it.id == st.activeLayerId } ?: return
        val metrics = context.resources.displayMetrics
        val cw = metrics.widthPixels.toFloat()
        val ch = metrics.heightPixels.toFloat()
        if (cw <= 0f || ch <= 0f) return
        // Layer bounding box in world space (identity camera), matching the artboard's world rect.
        val corners = CanvasHitTest.layerScreenCorners(layer, cw, ch) ?: return
        val box = floatArrayOf(
            corners.minOf { it.x }, corners.minOf { it.y },
            corners.maxOf { it.x }, corners.maxOf { it.y },
        )
        val artboard = artboardRect(cw.toInt(), ch.toInt(), st.documentWidth, st.documentHeight)
        val (dx, dy) = AlignOps.delta(mode, box, artboard)
        if (dx == 0f && dy == 0f) return
        pushHistory()
        dispatch(EditorIntent.AddOffset(Offset(dx, dy)))
        saveProject()
    }

    fun setBackgroundImage(uri: Uri) {
        val projectId = _uiState.value.projectId ?: return
        viewModelScope.launch(dispatchers.io) {
            dispatch(EditorIntent.SetLoading(true))
            val bitmap = ImageUtils.loadBitmapAsync(context, uri)
            if (bitmap != null) {
                val filename = "bg_${UUID.randomUUID()}.png"
                val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(bitmap))
                val localUri = "file://$path".toUri()

                val project = projectRepository.currentProject.value
                if (project != null) {
                    projectRepository.updateProject(project.copy(backgroundImageUri = localUri))
                }

                withContext(dispatchers.main) {
                    dispatch(EditorIntent.SetBackgroundBitmap(bitmap)); dispatch(EditorIntent.SetLoading(false))
                }
            } else {
                withContext(dispatchers.main) {
                    dispatch(EditorIntent.SetLoading(false))
                }
            }
        }
    }

    /** Remove the Mockup wall photo: clears the persisted background URI and the live bitmap. */
    fun clearBackgroundImage() {
        viewModelScope.launch(dispatchers.io) {
            projectRepository.currentProject.value?.let { project ->
                projectRepository.updateProject(project.copy(backgroundImageUri = null))
            }
            withContext(dispatchers.main) {
                dispatch(EditorIntent.SetBackgroundBitmap(null))
            }
        }
    }

    fun saveProject(name: String? = null) {
        viewModelScope.launch(dispatchers.io) {
            try {
                persistProject(name)
            } catch (e: Exception) {
                // Don't let a failed save die silently — the user believes their work is safe.
                android.util.Log.e("EditorViewModel", "Failed to save project", e)
                withContext(dispatchers.main) {
                    Toast.makeText(context, "Couldn't save the project — storage may be full", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Save As: renames the project to [name] and writes a complete `.fux` copy of it to [uri].
     *
     * The rename applies to the project itself, not only to the file — a project the user has just
     * called "Mural" should be called that in the gallery too, or the next Save offers the old name
     * back and the two drift apart.
     */
    fun saveProjectAs(uri: Uri, name: String) {
        viewModelScope.launch(dispatchers.io) {
            val cleanName = ProjectFile.sanitizeName(name)
            try {
                // Flush pixels first: writeProjectArchive zips what is on disk, so a debounced layer
                // save still in flight would be missing from the file the user just asked for.
                pendingSaveJobs.values.forEach { it.cancel() }
                pendingSaveJobs.clear()
                pendingWrites.entries.map { it.key to it.value }.forEach { (layerId, write) ->
                    writeLayerBitmap(layerId, write.first, write.second)
                }
                val saved = persistProject(cleanName)
                val projectId = saved?.id ?: projectRepository.currentProject.value?.id
                if (projectId == null) {
                    withContext(dispatchers.main) { toast("There's no project open to save.") }
                    return@launch
                }
                projectManager.writeProjectArchive(context, projectId, uri)
                withContext(dispatchers.main) { toast("Saved “$cleanName”") }
            } catch (e: Exception) {
                android.util.Log.e("EditorViewModel", "Save As failed", e)
                withContext(dispatchers.main) {
                    toast("Couldn't save “$cleanName”: ${e.message ?: "unknown error"}")
                }
            }
        }
    }

    /**
     * Opens a `.fux` project file the user picked, replacing the open project with it.
     *
     * The current project is flushed first — opening another one is a way of leaving this one, and
     * the user shouldn't have to have thought about that.
     */
    fun openProjectFile(uri: Uri) {
        viewModelScope.launch(dispatchers.io) {
            try {
                pendingSaveJobs.values.forEach { it.cancel() }
                pendingSaveJobs.clear()
                pendingWrites.entries.map { it.key to it.value }.forEach { (layerId, write) ->
                    writeLayerBitmap(layerId, write.first, write.second)
                }
                persistProject(null)

                val displayName = queryDisplayName(uri)
                val result = projectRepository.importProject(uri)
                val project = result.getOrNull()
                if (project == null) {
                    withContext(dispatchers.main) {
                        // Named files are the common miss: someone picks a PNG, or a .fux that got
                        // truncated in transit. Say which, rather than "import failed".
                        toast(
                            if (displayName != null && !ProjectFile.isProjectFile(displayName)) {
                                "“$displayName” isn't a Graffux project file."
                            } else {
                                "Couldn't open that project — the file may be damaged."
                            }
                        )
                    }
                    return@launch
                }
                // importProject sets currentProject directly; load it so the editor rebuilds its
                // layers from the freshly extracted files rather than keeping the old ones.
                projectRepository.loadProject(project.id)
                withContext(dispatchers.main) { toast("Opened “${project.name}”") }
            } catch (e: Exception) {
                android.util.Log.e("EditorViewModel", "Open project failed", e)
                withContext(dispatchers.main) { toast("Couldn't open that project.") }
            }
        }
    }

    /**
     * Persists the editor's current state into the project manifest, renaming it to [name] when one
     * is given. Returns the persisted project, or null if there was nothing to write.
     *
     * The body of [saveProject], split out so Save As can await the write before archiving it —
     * [saveProject] fires and forgets, which is right for autosave and wrong for a file the user is
     * waiting on.
     */
    private suspend fun persistProject(name: String?): GraffitiProject? {
        val currentProject = projectRepository.currentProject.value
        val updatedLayers = _uiState.value.layers.map { it.toOverlayLayer() }

        // Paths derive from the (immutable) project id. Persist the SLAM world first so they're valid.
        val projectId = currentProject?.id ?: GraffitiProject(name = name ?: "New Project").id
        val mapPath = projectManager.getMapPath(context, projectId)
        val cloudPointsPath = projectManager.getCloudPointsPath(context, projectId)
        slamManager.saveModel(mapPath)

        val manifestToSave: GraffitiProject
        if (currentProject == null) {
            manifestToSave = GraffitiProject(
                id = projectId,
                name = name ?: "New Project",
                layers = updatedLayers,
                colorStyles = _uiState.value.colorStyles,
                textStyles = _uiState.value.textStyles,
                mapPath = mapPath,
                cloudPointsPath = cloudPointsPath,
                documentWidth = _uiState.value.documentWidth,
                documentHeight = _uiState.value.documentHeight,
            )
            projectRepository.createProject(manifestToSave)
        } else {
            // Atomic read-modify-write: a concurrent AR wall-feature-map save merges into the SAME
            // currentProject, so writing a full stale copy here would drop its wall map (and vice
            // versa). The transform only touches the editor-owned fields. (docs/AUDIT.md save-race)
            projectRepository.updateProject { current ->
                current.copy(
                    name = name ?: current.name,
                    layers = updatedLayers,
                    colorStyles = _uiState.value.colorStyles,
                    textStyles = _uiState.value.textStyles,
                    lastModified = System.currentTimeMillis(),
                    mapPath = mapPath,
                    cloudPointsPath = cloudPointsPath,
                    documentWidth = _uiState.value.documentWidth,
                    documentHeight = _uiState.value.documentHeight,
                )
            }
            // The merged result the repository just persisted (includes any AR wall map).
            manifestToSave = projectRepository.currentProject.value ?: return null
        }

        scheduleThumbnailUpdate()
        return manifestToSave
    }

    /**
     * Regenerates the project's preview thumbnail off the main thread, debounced so the rapid
     * stream of autosaves doesn't composite on every stroke. Writes the standard
     * projects/<id>/thumbnail.png (which ProjectManager.saveProject also auto-detects) and records
     * its uri on the project. The update keeps the same project id, so the currentProject collector
     * treats it as a no-op and never reloads the editor.
     */
    private fun scheduleThumbnailUpdate() {
        val projectId = _uiState.value.projectId ?: return
        // Confine the job cancel/assign to the main thread so concurrent saveProject() calls (which
        // run on the multi-threaded IO dispatcher) can't race on thumbnailJob and leak coroutines.
        viewModelScope.launch(dispatchers.main) {
            thumbnailJob?.cancel()
            thumbnailJob = viewModelScope.launch(dispatchers.default) {
                try {
                    kotlinx.coroutines.delay(2000)
                    // `bitmap != null` alone would skip vector-only projects (pen paths and shapes
                    // carry no bitmap), leaving them permanently thumbnail-less in the gallery even
                    // though compositeToDocument renders their shapes perfectly well.
                    if (_uiState.value.layers.none { it.isVisible && (it.bitmap != null || it.shapes.isNotEmpty()) }) return@launch
                    val metrics = context.resources.displayMetrics
                    val w = metrics.widthPixels.takeIf { it > 0 } ?: 1080
                    val h = metrics.heightPixels.takeIf { it > 0 } ?: 1920
                    // Thumbnail represents the document (artboard), not the whole screen.
                    val composite = exportManager.compositeToDocument(
                        _uiState.value.layers, w, h,
                        _uiState.value.documentWidth, _uiState.value.documentHeight,
                    )
                    // Downscale to a small preview so the file stays tiny and decodes fast.
                    val maxDim = 512
                    val longest = maxOf(composite.width, composite.height).coerceAtLeast(1)
                    val scale = maxDim.toFloat() / longest
                    val thumb = if (scale < 1f) {
                        Bitmap.createScaledBitmap(
                            composite,
                            (composite.width * scale).toInt().coerceAtLeast(1),
                            (composite.height * scale).toInt().coerceAtLeast(1),
                            true
                        )
                    } else composite
                    val bytes = ImageUtils.bitmapToByteArray(thumb)
                    if (thumb !== composite) thumb.recycle()
                    composite.recycle()
                    val path = projectRepository.saveArtifact(projectId, "thumbnail.png", bytes)
                    projectRepository.updateProject {
                        if (it.id == projectId) it.copy(thumbnailUri = "file://$path".toUri()) else it
                    }
                } catch (e: Exception) {
                    // Thumbnails are best-effort; never let one crash the app.
                    android.util.Log.e("EditorViewModel", "Failed to generate thumbnail", e)
                }
            }
        }
    }

    /**
     * Export the current design as a PNG saved to the gallery.
     *
     * @param backgroundBitmap When non-null, used as the export's background (Overlay: the CameraX
     *   still; AR: the composited GL framebuffer readback that already includes the wall-anchored
     *   overlay). When null, per-mode default applies: Mockup reads uiState.backgroundBitmap;
     *   Overlay/AR paths without a captured frame fall back to transparent (only reachable if
     *   the caller neglected to supply one); Trace/Design have no background.
     * @param skipLayerComposite When true, the [backgroundBitmap] IS the export — no layers are
     *   drawn on top. Set by the AR path because the GL readback already contains the layers as
     *   the wall-anchored quad; drawing them again would double-draw.
     */
    fun exportImage(backgroundBitmap: Bitmap? = null, skipLayerComposite: Boolean = false) {
        viewModelScope.launch(dispatchers.default) {
            dispatch(EditorIntent.SetLoading(true))
            try {
                val exportBitmap = if (skipLayerComposite && backgroundBitmap != null) {
                    // AR path: GL readback already contains camera + wall-anchored overlay.
                    // Save as-is; drawing the flat editor layers on top would double-draw them.
                    backgroundBitmap
                } else {
                    val metrics = context.resources.displayMetrics
                    val bgBmp = backgroundBitmap
                    // Trace previously baked canvasBackground colour into the export. Spec is
                    // "overlay layers only, no background", so use TRANSPARENT unconditionally —
                    // the PNG writer (saveBitmapToGallery uses CompressFormat.PNG) preserves alpha.
                    exportManager.compositeToDocument(
                        _uiState.value.layers,
                        metrics.widthPixels,
                        metrics.heightPixels,
                        _uiState.value.documentWidth,
                        _uiState.value.documentHeight,
                        backgroundBitmap = bgBmp,
                        backgroundColor = android.graphics.Color.TRANSPARENT,
                    )
                }

                val success = saveBitmapToGallery(context, exportBitmap)

                withContext(dispatchers.main) {
                    dispatch(EditorIntent.SetLoading(false))
                    if (success) {
                        Toast.makeText(context, "Image saved to gallery", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Failed to save image", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(dispatchers.main) {
                    dispatch(EditorIntent.SetLoading(false))
                    Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Composites the current layers to a PNG in `cacheDir/shared` and returns a FileProvider
     * `content://` Uri suitable for `ACTION_SEND` — the two-app interop hand-off (send the edited
     * image to GraffitiXR, or any app). Returns null if there's nothing to share. The host fires the
     * share intent; the Uri authority is `${applicationId}.fileprovider`, which each hosting app
     * declares in its manifest.
     */
    suspend fun exportForShare(): Uri? = withContext(dispatchers.default) {
        val layers = _uiState.value.layers
        if (layers.isEmpty()) return@withContext null
        val metrics = context.resources.displayMetrics
        val composite = exportManager.compositeToDocument(
            layers,
            metrics.widthPixels,
            metrics.heightPixels,
            _uiState.value.documentWidth,
            _uiState.value.documentHeight,
            backgroundColor = android.graphics.Color.TRANSPARENT,
        )
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val file = java.io.File(dir, "graffixr_share.png")
        java.io.FileOutputStream(file).use { out ->
            composite.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        composite.recycle()
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    fun toggleHandedness() = dispatch(EditorIntent.ToggleHandedness)
    fun toggleDiagOverlay() = dispatch(EditorIntent.ToggleDiagOverlay)
    fun setActiveTool(tool: Tool) {
        // A raster tool needs something to paint on: with no layers EditorScreen doesn't even mount the
        // touch layer, so the brush silently does nothing. Give it a canvas instead of a dead tool.
        // PEN is exempt — it makes its own vector layer per stroke.
        //
        // The tool activates atomically with the layer landing (AddLayer's activeToolOverride)
        // rather than in a separate coroutine that polls for the layer and then dispatches
        // SetActiveTool afterward: AddLayer's reducer case always resets activeTool to NONE, so a
        // delayed dispatch here was racing it, and on a slow/failed first write (a fresh project's
        // first disk I/O, low storage) the poll's timeout could elapse before the layer ever
        // landed — leaving the tool stuck on NONE and every touch falling through to canvas
        // pan/zoom instead of painting, exactly as if the tool had never been picked at all.
        if (tool != Tool.NONE && tool != Tool.PEN && _uiState.value.layers.isEmpty()) {
            onAddBlankLayer(activeToolOverride = tool)
            return
        }
        dispatch(EditorIntent.SetActiveTool(tool))
    }

    /** Sets the artboard / document size and persists it to the current project. */
    fun setDocumentSize(width: Int, height: Int) {
        dispatch(EditorIntent.SetDocumentSize(width, height))
        saveProject()
    }

    /** Sets the canvas/artboard background colour (behind all layers). Persisted with the project. */
    fun setCanvasBackground(color: androidx.compose.ui.graphics.Color) {
        dispatch(EditorIntent.SetCanvasBackground(color))
        saveProject()
    }

    override fun onLayerActivated(id: String) = dispatch(EditorIntent.ActivateLayer(id))

    override fun onLayerRemoved(id: String) {
        pushHistory()
        dispatch(EditorIntent.RemoveLayer(id))
        // Deliberately NOT layerStore.remove(id): pushHistory() above stripped this layer's bitmap
        // out of the undo snapshot (history only stores bitmap-less Layers to bound memory), so
        // undoing this delete has nothing to rebuild the layer's pixels from except LayerStore's
        // still-cached base+strokes for `id`. Evicting them here would make the layer come back
        // permanently blank on undo. See onUndoClicked/onRedoClicked's PropertyChange branch, which
        // rebuilds any restored layer missing a bitmap from this same cache.
        opEmitter.emit(Op.LayerRemove(id))
        saveProject()
    }

    override fun onLayerReordered(newOrder: List<String>) {
        pushHistory()
        dispatch(EditorIntent.ReorderLayers(newOrder))
        opEmitter.emit(Op.LayerReorder(newOrder))
        saveProject()
    }

    private fun updateLayerUri(id: String, uri: Uri) {
        viewModelScope.launch(dispatchers.io) {
            val bitmap = ImageUtils.loadBitmapAsync(context, uri)
            withContext(dispatchers.main) {
                _uiState.update { state ->
                    val updatedLayers = state.layers.map {
                        if (it.id == id) {
                            bitmap?.let { bmp ->
                                layerStore.putBase(id, bmp.copy(Bitmap.Config.ARGB_8888, false))
                                layerStore.initStrokes(id)
                            }
                            it.copy(uri = uri, bitmap = bitmap)
                        } else it
                    }
                    state.copy(layers = updatedLayers)
                }
            }
            saveProject()
        }
    }

    fun setAnchorExtent(halfW: Float, halfH: Float) {
        anchorHalfExtentMeters = Pair(halfW, halfH)
    }

    private fun fitActiveLayerToAnchor(halfW: Float, halfH: Float) {
        val state = _uiState.value
        val layer = state.layers.find { it.id == state.activeLayerId } ?: return
        val bmp = layer.bitmap ?: return
        // QUAD_HALF_EXTENT = 5.0f (matches OverlayRenderer.QUAD_HALF_EXTENT)
        // The composite canvas is 2048×2048. Scale to fill 80% of the anchor extent.
        val scaleW = halfW * 0.8f * 2048f / (bmp.width * 5.0f)
        val scaleH = halfH * 0.8f * 2048f / (bmp.height * 5.0f)
        val scale = minOf(scaleW, scaleH).coerceIn(0.05f, 20f)
        updateActiveLayer { it.copy(scale = scale, offset = Offset.Zero, rotationX = 0f, rotationY = 0f, rotationZ = 0f) }
    }

    override fun onAdjustClicked() = dispatch(EditorIntent.ToggleAdjustPanel)
    fun setLastCropTool(toolId: String) = dispatch(EditorIntent.SetLastCropTool(toolId))

    fun onTransformClicked() = dispatch(EditorIntent.ToggleTransformPanel)
    fun onBalanceClicked() = dispatch(EditorIntent.ToggleColorPanel)
    fun onExtensionsClicked() = dispatch(EditorIntent.ToggleExtensionsPanel)
    override fun onDismissPanel() = dispatch(EditorIntent.DismissPanel)

    /**
     * A tap on the canvas at [tap] (canvas pixels): selects the topmost layer under the point so a
     * shape can be picked directly on the canvas instead of via the Layers panel. A tap that misses
     * every layer dismisses any open panel (the prior tap behaviour). [canvasWidth]/[canvasHeight]
     * are the canvas size the tap was measured in. Hit-test geometry lives in [CanvasHitTest].
     */
    fun onCanvasTap(tap: Offset, canvasWidth: Float, canvasHeight: Float) {
        val st = _uiState.value
        val hitId = CanvasHitTest.topHit(
            st.layers, tap, canvasWidth, canvasHeight, st.viewportOffset, st.viewportZoom, st.viewportRotation,
        )
        if (hitId != null) {
            if (hitId != _uiState.value.activeLayerId) dispatch(EditorIntent.ActivateLayer(hitId))
        } else {
            dispatch(EditorIntent.DismissPanel)
        }
    }

    fun onTransformGesture(pan: Offset, zoom: Float, rotationDelta: Float, canvasW: Float = 0f, canvasH: Float = 0f) {
        val activeId = _uiState.value.activeLayerId ?: return
        val axis = _uiState.value.activeRotationAxis
        updateLinkedGroup(activeId) { layer ->
            val rx = if (axis == RotationAxis.X) layer.rotationX + rotationDelta else layer.rotationX
            val ry = if (axis == RotationAxis.Y) layer.rotationY + rotationDelta else layer.rotationY
            val rz = if (axis == RotationAxis.Z) layer.rotationZ + rotationDelta else layer.rotationZ
            layer.copy(scale = layer.scale * zoom, offset = layer.offset + pan, rotationX = rx, rotationY = ry, rotationZ = rz)
        }
        // Snap-to-guides only on a pure move (not resize/rotate) and only when the canvas size is known.
        if (canvasW > 0f && zoom == 1f && rotationDelta == 0f) applyMoveSnap(activeId, canvasW, canvasH)
    }

    /** Snaps the active layer's edges/centre to the artboard and other layers, shifting the linked
     *  group by the snap delta and publishing the active guide lines (world space) for the UI. */
    private fun applyMoveSnap(activeId: String, cw: Float, ch: Float) {
        val st = _uiState.value
        val layer = st.layers.find { it.id == activeId }
        val corners = layer?.let { CanvasHitTest.layerScreenCorners(it, cw, ch) }
        if (corners == null) {
            if (st.snapGuidesX.isNotEmpty() || st.snapGuidesY.isNotEmpty()) {
                dispatch(EditorIntent.SetSnapGuides(emptyList(), emptyList()))
            }
            return
        }
        fun bbox(c: List<Offset>) = floatArrayOf(c.minOf { it.x }, c.minOf { it.y }, c.maxOf { it.x }, c.maxOf { it.y })
        val artboard = artboardRect(cw.toInt(), ch.toInt(), st.documentWidth, st.documentHeight)
        val others = st.layers.filter { it.id != activeId && it.isVisible }
            .mapNotNull { l -> CanvasHitTest.layerScreenCorners(l, cw, ch)?.let(::bbox) }
        val (gx, gy) = SnapEngine.guidesFrom(artboard, others)
        val threshold = 12f / st.viewportZoom.coerceAtLeast(0.01f)
        val res = SnapEngine.snap(bbox(corners), gx, gy, threshold)
        if (res.dx != 0f || res.dy != 0f) {
            updateLinkedGroup(activeId) { it.copy(offset = it.offset + Offset(res.dx, res.dy)) }
        }
        // Avoid a state churn every drag frame: only publish when the guide set actually changes.
        if (res.guidesX != st.snapGuidesX || res.guidesY != st.snapGuidesY) {
            dispatch(EditorIntent.SetSnapGuides(res.guidesX, res.guidesY))
        }
    }

    /**
     * Pans/zooms/rotates the infinite-canvas camera (the whole workspace), leaving individual layers
     * alone. [panDelta] is a screen-pixel pan; [zoomFactor] multiplies the current zoom and
     * [rotationDelta] (degrees) adds to the current rotation, both about [focus] (a screen point, e.g.
     * the pinch centroid) so that point stays put under the fingers.
     *
     * The screen↔world mapping is `screen = viewportOffset + viewportZoom · R(rotation) · world`.
     * Holding [focus] fixed while zooming by `k = newZoom/oldZoom` and rotating by `Δ` gives
     * `newOffset = focus + panDelta − k · R(Δ) · (focus − oldOffset)`.
     */
    fun onViewportPanZoom(panDelta: Offset, zoomFactor: Float, focus: Offset, rotationDelta: Float = 0f) {
        val st = _uiState.value
        val oldZoom = st.viewportZoom
        val newZoom = (oldZoom * zoomFactor).coerceIn(0.1f, 10f)
        val k = newZoom / oldZoom
        val old = st.viewportOffset
        val rad = Math.toRadians(rotationDelta.toDouble())
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()
        val dx = focus.x - old.x
        val dy = focus.y - old.y
        // R(Δ) · (dx, dy)
        val rx = dx * cos - dy * sin
        val ry = dx * sin + dy * cos
        val newOffset = Offset(
            focus.x + panDelta.x - k * rx,
            focus.y + panDelta.y - k * ry,
        )
        // Rotation snap: within ~1° of square (0, 90, 180, 270), the canvas clicks straight.
        // A very faint threshold so it's barely felt but still helps square the page.
        var newRotation = st.viewportRotation + rotationDelta
        if (rotationDelta != 0f) {
            val norm = ((newRotation % 90f) + 135f) % 90f - 45f
            if (kotlin.math.abs(norm) < 1f) newRotation -= norm
        }
        dispatch(EditorIntent.SetViewport(newOffset, newZoom, newRotation))
    }

    /** Resets the camera to identity (100%, centred, unrotated). */
    fun resetViewport() = dispatch(EditorIntent.SetViewport(Offset.Zero, 1f, 0f))

    override fun onGestureEnd() {
        saveProject()
        dispatch(EditorIntent.SetGestureInProgress(false))
        if (_uiState.value.snapGuidesX.isNotEmpty() || _uiState.value.snapGuidesY.isNotEmpty()) {
            dispatch(EditorIntent.SetSnapGuides(emptyList(), emptyList()))
        }
        // Emit LayerTransform for the active layer. The editor stores transform as
        // scale/offset/rotationX/Y/Z rather than a Matrix, so we encode them in the
        // first 6 slots of a 16-float list (slots 6-15 are zeros).
        // applySpectatorOp must decode using the same convention.
        val state = _uiState.value
        val activeId = state.activeLayerId ?: return
        val layer = state.layers.find { it.id == activeId } ?: return
        val encodedMatrix = listOf(
            layer.scale, layer.offset.x, layer.offset.y,
            layer.rotationX, layer.rotationY, layer.rotationZ,
            0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f
        )
        opEmitter.emit(Op.LayerTransform(activeId, encodedMatrix))
    }
    override fun onGestureStart() { 
        pushHistory()
        dispatch(EditorIntent.BeginGesture) 
    }
    override fun toggleImageLock() {
        pushHistory()
        dispatch(EditorIntent.ToggleImageLock)
        saveProject()
        emitActiveLayerProps()
    }
    override fun onToggleInvert() {
        pushHistory()
        dispatch(EditorIntent.ToggleInvert)
        saveProject()
        emitActiveLayerProps()
    }
    /**
     * Brackets a drag on a stock Material `Slider` that live-updates the active layer (the Layer
     * Options "Edit" window's opacity slider, the Text window's size slider) — as opposed to the
     * Adjust panel's custom Knob, a stock `Slider` has no drag-start callback, so the caller
     * detects drag-start itself and calls this once per drag. Without this bracket the slider
     * dispatched its per-frame value change but never pushed history or saved, so an edit made
     * this way reverted on relaunch and couldn't be undone — unlike the Adjust-panel knob, which
     * commits correctly via [onAdjustmentStart]/[onAdjustmentEnd].
     */
    fun onLayerEditStart() = pushHistory()

    /** See [onLayerEditStart]. */
    fun onLayerEditEnd() {
        saveProject()
        emitActiveLayerProps()
    }

    /** Opacity / brightness / contrast / saturation knobs adjust the active layer. */
    override fun onOpacityChanged(v: Float) = dispatch(EditorIntent.SetOpacity(v))
    override fun onBrightnessChanged(v: Float) = dispatch(EditorIntent.SetBrightness(v))
    override fun onContrastChanged(v: Float) = dispatch(EditorIntent.SetContrast(v))
    override fun onSaturationChanged(v: Float) = dispatch(EditorIntent.SetSaturation(v))
    override fun onColorBalanceRChanged(v: Float) = dispatch(EditorIntent.SetColorBalanceR(v))
    override fun onColorBalanceGChanged(v: Float) = dispatch(EditorIntent.SetColorBalanceG(v))
    override fun onColorBalanceBChanged(v: Float) = dispatch(EditorIntent.SetColorBalanceB(v))

    /**
     * Apply an installed azphalt LUT extension to the active layer — the "use a marketplace plugin"
     * payoff. Grades the layer's current bitmap through the extension's `.cube` 3D LUT (a transform a
     * ColorMatrix can't express) and replaces the base, pushing undo history first.
     */
    fun applyInstalledLut(extensionId: String) {
        val layerId = _uiState.value.activeLayerId
        val layer = layerId?.let { id -> _uiState.value.layers.find { it.id == id } }
        val bitmap = layer?.bitmap
        if (layerId == null || bitmap == null) {
            // The Marketplace closes on Apply, so a silent return read as "the button does nothing".
            Toast.makeText(context, "Select a layer with an image before applying a filter", Toast.LENGTH_SHORT).show()
            return
        }
        pushHistory()
        dispatch(EditorIntent.SetLoading(true))
        viewModelScope.launch(dispatchers.default) {
            val lut = extensionRepository.loadLut(extensionId)
            if (lut == null) {
                withContext(dispatchers.main) {
                    dispatch(EditorIntent.SetLoading(false))
                    Toast.makeText(context, "Couldn't load that filter — it may be missing or corrupt", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val graded = bitmap.applyCubeLut(lut)
            val base = graded.copy(Bitmap.Config.ARGB_8888, false)
            if (base != graded) graded.recycle()
            layerStore.putBase(layerId, base)
            layerStore.initStrokes(layerId)
            rebuildLayerBitmap(layerId, emitOp = true)
            dispatch(EditorIntent.SetLoading(false))
        }
    }

    /**
     * First-run doodle demo: on the scribble->artwork swap, pre-set the adjustment knobs to values
     * that read well against the wall. Combines [wall] (from the doodle capture) with the active
     * layer's own colour/contrast and applies through the existing setters (which route the
     * multiplicative/additive knobs to the AR mode-adjustment and colour balance to the layer, exactly
     * as the AR composite consumes them). A starting point the user then fine-tunes — not a hard grade.
     */
    fun autoTuneActiveLayer(wall: com.hereliesaz.graffitixr.common.util.ImageStats?) {
        if (wall == null) return
        val bitmap = _uiState.value.layers.find { it.id == _uiState.value.activeLayerId }?.bitmap ?: return
        viewModelScope.launch(dispatchers.default) {
            val art = bitmap.imageStats()
            val t = computeAutoTune(wall, art)
            withContext(dispatchers.main) {
                onOpacityChanged(t.opacity)
                onBrightnessChanged(t.brightness)
                onContrastChanged(t.contrast)
                onSaturationChanged(t.saturation)
                onColorBalanceRChanged(t.colorBalanceR)
                onColorBalanceGChanged(t.colorBalanceG)
                onColorBalanceBChanged(t.colorBalanceB)
            }
        }
    }
    // These (and setLayerTransform below) are also the absolute setters TransformPanel's numeric
    // fields commit through on every keystroke — unlike the drag-gesture path, which brackets its
    // whole gesture in onAdjustmentStart()/onAdjustmentEnd() (pushHistory + saveProject), nothing
    // upstream of these calls did either, so a typed transform value couldn't be undone and (scale/
    // rotation only — setLayerTransform already saved) was never persisted. pushHistory per keystroke
    // mirrors this file's own onToggleVisibility/onLayerRemoved convention of one history entry per
    // discrete state-changing call; it's more undo steps while typing, not a correctness issue.
    override fun onScaleChanged(s: Float) {
        pushHistory()
        dispatch(EditorIntent.SetScale(s))
        saveProject()
    }
    override fun onOffsetChanged(o: Offset) = dispatch(EditorIntent.AddOffset(o))

    fun setStabilizerLevel(level: Int) = dispatch(EditorIntent.SetStabilizerLevel(level))

    /** Persisted; the collector above mirrors it back into [EditorUiState]. */
    fun setInputSampleRateHz(hz: Int) {
        viewModelScope.launch { settingsRepository.setInputSampleRateHz(hz) }
    }

    /** Persisted. Applies to layers created from now on — existing layers keep their pixels. */
    fun setCanvasRenderScale(scale: Float) {
        viewModelScope.launch { settingsRepository.setCanvasRenderScale(scale) }
    }
    fun toggleWrapAroundMode() = dispatch(EditorIntent.ToggleWrapAroundMode)

    override fun onRotationXChanged(d: Float) {
        pushHistory()
        dispatch(EditorIntent.SetRotationX(d))
        saveProject()
    }
    override fun onRotationYChanged(d: Float) {
        pushHistory()
        dispatch(EditorIntent.SetRotationY(d))
        saveProject()
    }
    override fun onRotationZChanged(d: Float) {
        pushHistory()
        dispatch(EditorIntent.SetRotationZ(d))
        saveProject()
    }

    override fun onCycleRotationAxis() = dispatch(EditorIntent.CycleRotationAxis)

    override fun onAdjustmentStart() { pushHistory(); dispatch(EditorIntent.SetGestureInProgress(true)) }

    override fun onAdjustmentEnd() {
        dispatch(EditorIntent.SetGestureInProgress(false))
        saveProject()
        // Emit LayerPropsChange for the active layer after adjustment is committed.
        emitActiveLayerProps()
    }

    override fun setLayerTransform(scale: Float, offset: Offset, rx: Float, ry: Float, rz: Float) {
        pushHistory()
        dispatch(EditorIntent.SetLayerTransform(scale, offset, rx, ry, rz))
        saveProject()
    }

    override fun onLayerWarpChanged(layerId: String, mesh: List<Float>) {
        pushHistory()
        dispatch(EditorIntent.SetLayerWarp(layerId, mesh))
        saveProject()
    }

    override fun copyLayerModifications(id: String) { copiedLayerState = _uiState.value.layers.find { it.id == id } }

    override fun pasteLayerModifications(id: String) {
        val source = copiedLayerState ?: return
        pushHistory()
        dispatch(EditorIntent.PasteLayerModifications(id, source))
        saveProject()
    }

    override fun onCycleBlendMode() {
        pushHistory()
        updateActiveLayer { layer ->
            val domainModes = BlendMode.entries.toTypedArray()
            val currentDomainMode = layer.blendMode.toModelBlendMode()
            val nextIndex = (domainModes.indexOf(currentDomainMode) + 1) % domainModes.size
            layer.copy(blendMode = domainModes[nextIndex].toComposeBlendMode())
        }
        saveProject()
        _uiState.value.activeLayerId?.let { id ->
            _uiState.value.layers.find { it.id == id }?.let { opEmitter.emit(Op.LayerPropsChange(id, it.toLayerProps())) }
        }
    }

    /**
     * Sets the active layer's compositing mode directly to [mode] (from the blend-mode picker),
     * as opposed to [onCycleBlendMode]'s step-through. Snapshots history, persists, and emits the
     * co-op op so a spectator sees the change.
     */
    fun setBlendMode(mode: com.hereliesaz.graffitixr.common.model.BlendMode) {
        pushHistory()
        dispatch(EditorIntent.SetBlendMode(mode))
        saveProject()
        emitActiveLayerProps()
    }

    /**
     * Sets the stroke (outline) width on every shape of the active vector layer. A width of 0
     * removes the outline (fill-only). When a shape had no stroke yet, its stroke colour is seeded
     * from the current active colour so the outline is immediately visible; an existing stroke
     * colour is preserved. No-op if the active layer isn't a vector layer.
     */
    fun setVectorStrokeWidth(width: Float) {
        val st = _uiState.value
        val active = st.layers.find { it.id == st.activeLayerId } ?: return
        if (active.shapes.isEmpty()) return
        val w = width.coerceIn(0f, 100f)
        val seedArgb = st.activeColor.toArgb().toLong() and 0xFFFFFFFFL
        val updated = active.shapes.map { s ->
            val argb = if (s.strokeWidth > 0f) s.strokeArgb else seedArgb
            s.copy(strokeWidth = w, strokeArgb = argb)
        }
        pushHistory()
        dispatch(EditorIntent.SetLayerShapes(active.id, updated))
        saveProject()
    }

    /**
     * Sets the corner radius (px) on every [ShapeKind.RECTANGLE] shape of the active vector layer;
     * ellipse/line shapes are left untouched. The radius is clamped per-shape to half the shape's
     * shorter side (beyond that a rectangle is already fully rounded). No-op on non-vector layers.
     */
    fun setVectorCornerRadius(radius: Float) {
        val st = _uiState.value
        val active = st.layers.find { it.id == st.activeLayerId } ?: return
        if (active.shapes.isEmpty()) return
        val updated = active.shapes.map { s ->
            if (s.kind == com.hereliesaz.graffitixr.common.model.ShapeKind.RECTANGLE) {
                val maxR = minOf(s.width, s.height) / 2f
                s.copy(cornerRadius = radius.coerceIn(0f, maxR))
            } else s
        }
        pushHistory()
        dispatch(EditorIntent.SetLayerShapes(active.id, updated))
        saveProject()
    }

    /**
     * Resizes every shape on the active vector layer to [width]×[height] px. For a
     * [ShapeKind.LINE], height is ignored (it draws as a horizontal line of length [width]). Any
     * rectangle corner radius is re-clamped so it never exceeds half the new shorter side. No-op on
     * non-vector layers. This is the numeric alternative to on-canvas resize handles.
     */
    fun setVectorSize(width: Float, height: Float) {
        val st = _uiState.value
        val active = st.layers.find { it.id == st.activeLayerId } ?: return
        if (active.shapes.isEmpty()) return
        val w = width.coerceIn(1f, 8192f)
        val h = height.coerceIn(1f, 8192f)
        val updated = active.shapes.map { s ->
            val maxR = minOf(w, h) / 2f
            s.copy(width = w, height = h, cornerRadius = s.cornerRadius.coerceIn(0f, maxR))
        }
        pushHistory()
        dispatch(EditorIntent.SetLayerShapes(active.id, updated))
        saveProject()
    }

    /**
     * Changes the vertex count of every [ShapeKind.POLYGON] shape on the active vector layer
     * (floored at 3). Non-polygon shapes are left untouched. No-op on non-vector layers.
     */
    fun setPolygonSides(sides: Int) {
        val st = _uiState.value
        val active = st.layers.find { it.id == st.activeLayerId } ?: return
        if (active.shapes.isEmpty()) return
        val n = sides.coerceAtLeast(3)
        val updated = active.shapes.map { s ->
            if (s.kind == com.hereliesaz.graffitixr.common.model.ShapeKind.POLYGON) s.copy(sides = n) else s
        }
        pushHistory()
        dispatch(EditorIntent.SetLayerShapes(active.id, updated))
        saveProject()
    }

    /**
     * Toggles fill on/off for the active vector layer's rectangle/ellipse shapes by flipping the
     * fill alpha (off = 0, on = fully opaque) while preserving the RGB, so a shape's colour is
     * remembered across toggles. Enables outline-only shapes when paired with a stroke. Line shapes
     * (which have no fill) are untouched; no-op on non-vector layers.
     */
    fun toggleVectorFill() {
        val st = _uiState.value
        val active = st.layers.find { it.id == st.activeLayerId } ?: return
        if (active.shapes.isEmpty()) return
        val anyFilled = active.shapes.any { it.hasFill }
        val updated = active.shapes.map { s ->
            if (s.kind == com.hereliesaz.graffitixr.common.model.ShapeKind.LINE) {
                s
            } else {
                val rgb = s.fillArgb and 0x00FFFFFFL
                s.copy(fillArgb = if (anyFilled) rgb else (0xFF000000L or rgb))
            }
        }
        pushHistory()
        dispatch(EditorIntent.SetLayerShapes(active.id, updated))
        saveProject()
    }

    override fun onLayerDuplicated(id: String) {
        val layer = _uiState.value.layers.find { it.id == id } ?: return
        val projectId = _uiState.value.projectId ?: return
        pushHistory()

        viewModelScope.launch(dispatchers.io) {
            val currentBitmap = layer.bitmap
            val newBitmap = currentBitmap?.copy(currentBitmap.config ?: Bitmap.Config.ARGB_8888, true)
            val newUri = newBitmap?.let { bmp ->
                val filename = "layer_dup_${UUID.randomUUID()}.png"
                val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(bmp))
                "file://$path".toUri()
            } ?: layer.uri

            val duplicated = layer.copy(
                id = UUID.randomUUID().toString(),
                name = "${layer.name} Copy",
                bitmap = newBitmap,
                uri = newUri
            )

            newBitmap?.let { bmp ->
                layerStore.putBase(duplicated.id, bmp.copy(Bitmap.Config.ARGB_8888, false))
                layerStore.initStrokes(duplicated.id)
            }

            withContext(dispatchers.main) {
                dispatch(EditorIntent.AddLayer(duplicated, resetActivePanel = false))
                opEmitter.emit(Op.LayerAdd(duplicated))
                saveProject()
            }
        }
    }

    override fun onLayerRenamed(id: String, name: String) {
        pushHistory()
        dispatch(EditorIntent.RenameLayer(id, name))
        saveProject()
    }

    /** Re-pushes layer order, props and transforms to guests after a non-draw undo/redo. */
    private fun emitLayerStateResync(layers: List<Layer>) {
        opEmitter.emit(Op.LayerReorder(layers.map { it.id }))
        layers.forEach { l ->
            opEmitter.emit(Op.LayerPropsChange(l.id, l.toLayerProps()))
            opEmitter.emit(Op.LayerTransform(l.id, listOf(
                l.scale, l.offset.x, l.offset.y, l.rotationX, l.rotationY, l.rotationZ,
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f
            )))
        }
    }

    private fun Layer.toLayerProps() = LayerProps(
        isVisible = isVisible,
        opacity = opacity,
        brightness = brightness,
        contrast = contrast,
        saturation = saturation,
        colorBalanceR = colorBalanceR,
        colorBalanceG = colorBalanceG,
        colorBalanceB = colorBalanceB,
        isImageLocked = isImageLocked,
        isInverted = isInverted,
        blendMode = blendMode
    )

    private fun updateActiveLayer(transform: (Layer) -> Layer) {
        _uiState.update { state ->
            val id = state.activeLayerId ?: return@update state
            state.copy(layers = LayerListOps.mapLayer(state.layers, id, transform))
        }
    }

    fun updateAllLayers(transform: (Layer) -> Layer) {
        _uiState.update { state ->
            state.copy(layers = state.layers.map(transform))
        }
    }

    /**
     * MVI dispatch: apply a state-only [EditorIntent] through the pure [EditorReducer]. Side
     * effects (history, persistence, co-op op emission) are orchestrated by the caller around
     * this call — the reducer itself stays pure.
     */
    private fun dispatch(intent: EditorIntent) {
        _uiState.update { EditorReducer.reduce(it, intent) }
    }

    /** Emits a co-op LayerPropsChange for the active layer, if any. */
    private fun emitActiveLayerProps() {
        val id = _uiState.value.activeLayerId ?: return
        _uiState.value.layers.find { it.id == id }?.let { opEmitter.emit(Op.LayerPropsChange(id, it.toLayerProps())) }
    }

    /** Returns the IDs of all layers in the same link-group as [layerId].
     *  A group is a contiguous run where each layer above the bottom has isLinked = true. */
    private fun getLinkedGroupIds(layerId: String): Set<String> {
        val layers = _uiState.value.layers
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx < 0) return setOf(layerId)
        // Walk down to find group bottom (first layer in run whose isLinked is false)
        var bottom = idx
        while (bottom > 0 && layers[bottom].isLinked) bottom--
        // Walk up to find group top (last consecutive layer whose next has isLinked = true)
        var top = idx
        while (top + 1 < layers.size && layers[top + 1].isLinked) top++
        return layers.subList(bottom, top + 1).map { it.id }.toSet()
    }

    private fun updateLinkedGroup(activeId: String, transform: (Layer) -> Layer) {
        val groupIds = getLinkedGroupIds(activeId)
        _uiState.update { state -> state.copy(layers = state.layers.map { if (it.id in groupIds) transform(it) else it }) }
    }

    override fun onFeedbackShown() = dispatch(EditorIntent.FeedbackShown)
    override fun onDoubleTapHintDismissed() {}
    override fun onOnboardingComplete(mode: Any) {}

    // Kept for interface compliance; no longer called (DrawingCanvas now uses the three-phase API).
    override fun onDrawingPathFinished(path: List<Offset>, canvasSize: IntSize) {}

    /** Called when the user first touches the canvas. Prepares a mutable working bitmap for
     *  incremental real-time rendering (all tools except Liquify). */
    fun onStrokeStart(startPoint: Offset, canvasSize: IntSize) {
        val state = _uiState.value
        if (state.activeTool == Tool.NONE) return
        val layerId = state.activeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val originalBitmap = layer.bitmap ?: return

        strokeStabilizer.reset()
        val stabilizedStart = strokeStabilizer.stabilize(startPoint, state.stabilizerLevel)

        resetStrokePoints(stabilizedStart)
        strokeLayerId = layerId
        strokeCanvasW = canvasSize.width
        strokeCanvasH = canvasSize.height
        strokeLayerScale = layer.scale
        strokeLayerOffset = layer.offset
        strokeLayerRotationZ = layer.rotationZ
        // Captured once per stroke: mid-stroke toggles must not desync live paint from the
        // recorded command that undo/redo replays.
        strokeSymmetry = if (state.activeTool != Tool.LIQUIFY) state.symmetryMode else SymmetryMode.NONE
        strokeAlphaLock = layer.alphaLock
        strokeSelection = state.selection
        lastSampleMs = 0L
        strokeDynamics = if (state.activeTool == Tool.BRUSH && activeStampBrush == null) BrushDynamics.State() else null

        if (state.activeTool == Tool.LIQUIFY) {
            // ensureInitialized() constructs the native warp engine singleton this whole tool runs
            // on; nothing else in the app ever called it, so every Liquify stroke used to silently
            // no-op against a null engine pointer. Idempotent (synchronized + isInitialized guard),
            // so calling it on every stroke start is cheap after the first.
            slamManager.ensureInitialized()
            // Store the original bitmap so live-preview warps can be applied from a clean copy.
            liquifyOriginalBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, false)
            slamManager.prepareLiquify(originalBitmap)
            _uiState.update { it.copy(liveStrokeLayerId = layerId) }
            return
        }

        val stampBrush = activeStampBrush
        if (stampBrush != null && state.activeTool == Tool.BRUSH) {
            // Azphalt stamp brush: stamp dabs incrementally onto a working copy for a live preview.
            // Fix the jitter seed now so the preview, the commit, and history replay all match.
            stampBrushForStroke = stampBrush
            stampShapeForStroke = activeStampShape
            val currentSeed = System.nanoTime()
            stampSeed = currentSeed
            stampStampedCount = 0
            stampLiveBitmap = null
            stampLiveCanvas = null
            stampMappedPoints.clear()
            viewModelScope.launch(dispatchers.default) {
                val work = SafeBitmap.copy(originalBitmap) ?: return@launch
                withContext(dispatchers.main) {
                    // Only adopt if this is STILL the in-flight stamp stroke — a fast restart bumps
                    // stampSeed, so a late copy from a superseded stroke is dropped (guards the race).
                    if (stampSeed == currentSeed && stampBrushForStroke === stampBrush && strokeLayerId == layerId) {
                        stampLiveBitmap = work
                        // Same sticky-clip trick as the brush preview: bound the stamp canvas once.
                        stampLiveCanvas = Canvas(work).also { c ->
                            SelectionMask.clip(
                                c,
                                SelectionMask.bitmapPath(
                                    strokeSelection, work.width, work.height,
                                    layer.scale, layer.offset, layer.rotationZ,
                                    state.viewportOffset, state.viewportZoom, state.viewportRotation
                                ),
                            )
                        }
                        _uiState.update {
                            it.copy(
                                liveStrokeLayerId = layerId,
                                liveStrokeBitmap = work,
                                liveStrokeVersion = it.liveStrokeVersion + 1,
                            )
                        }
                    }
                }
            }
            return
        }

        val tool = state.activeTool
        val argb = state.activeColor.toArgb()
        val brushSize = state.brushSize
        val feathering = state.brushFeathering

        // Copy the bitmap on a background thread (can be ~10-50 ms for large images).
        // After the copy is done, replay ALL points collected so far (including any that
        // arrived while the copy was in flight) so no input is lost.
        viewModelScope.launch(dispatchers.default) {
            // Skipping the preview beats crashing: the stroke still commits on finger-up through
            // onStrokeEnd's whole-stroke fallback, which is exactly the path a stroke too fast for
            // this copy already takes.
            val workBitmap = SafeBitmap.copy(originalBitmap) ?: return@launch
            val workCanvas = Canvas(workBitmap)
            // Clip the live-preview canvas to the lasso once, here. A Canvas clip is sticky until a
            // restore, and this canvas is retained for the whole stroke — so every later segment
            // drawn by onStrokePoint is confined too, without re-clipping per frame.
            SelectionMask.clip(
                workCanvas,
                SelectionMask.bitmapPath(
                    strokeSelection, workBitmap.width, workBitmap.height,
                    layer.scale, layer.offset, layer.rotationZ,
                    state.viewportOffset, state.viewportZoom, state.viewportRotation
                ),
            )
            // Read the transform off the captured immutable `layer` (not the mutable stroke* members,
            // which a quick second stroke could overwrite before this coroutine runs).
            val layerScale = layer.scale
            val layerOffset = layer.offset
            val layerRotationZ = layer.rotationZ
            // Match the rail size preview exactly: brushSize is screen px; scale it into this layer's
            // bitmap space (1f for an unscaled sketch) so the painted dab is the previewed diameter.
            val brushScale = ImageProcessor.screenToBitmapScale(
                canvasSize.width, canvasSize.height, workBitmap.width, workBitmap.height, layerScale
            )
            val paint = buildStrokePaint(tool, argb, brushSize * brushScale, feathering, strokeAlphaLock)

            // Snapshot the collected points at this moment — may include points that arrived
            // during the bitmap-copy phase.
            val catchUpPoints = snapshotStrokePoints()
            val mappedAll = ImageProcessor.mapScreenToBitmap(
                catchUpPoints, canvasSize.width, canvasSize.height, workBitmap.width, workBitmap.height,
                layerScale, layerOffset, layerRotationZ, state.viewportOffset, state.viewportZoom, state.viewportRotation
            )

            val bw = workBitmap.width.toFloat()
            val bh = workBitmap.height.toFloat()
            val mirrored = strokeSymmetry != SymmetryMode.NONE

            // Draws [seg] with wrap-around tiling, plus its vertical-mirror twin when symmetry is on.
            fun drawPathAll(seg: android.graphics.Path) {
                val targets = ArrayList<android.graphics.Path>(2)
                targets.add(seg)
                if (mirrored) {
                    val m = android.graphics.Matrix().apply { setScale(-1f, 1f, bw / 2f, 0f) }
                    targets.add(android.graphics.Path(seg).apply { transform(m) })
                }
                for (t in targets) {
                    if (state.wrapAroundMode) {
                        for (dx in -1..1) for (dy in -1..1) {
                            if (dx == 0 && dy == 0) workCanvas.drawPath(t, paint)
                            else workCanvas.drawPath(android.graphics.Path(t).apply { offset(dx * bw, dy * bh) }, paint)
                        }
                    } else {
                        workCanvas.drawPath(t, paint)
                    }
                }
            }

            if (mappedAll.size == 1) {
                val xs = if (mirrored) floatArrayOf(mappedAll[0].x, bw - mappedAll[0].x) else floatArrayOf(mappedAll[0].x)
                for (x in xs) {
                    if (state.wrapAroundMode) {
                        for (dx in -1..1) for (dy in -1..1) workCanvas.drawPoint(x + dx * bw, mappedAll[0].y + dy * bh, paint)
                    } else {
                        workCanvas.drawPoint(x, mappedAll[0].y, paint)
                    }
                }
            } else {
                val dyn = strokeDynamics
                if (dyn != null) {
                    // Dynamic brush: each segment at its own velocity-derived width. The same
                    // recursion runs on commit/replay, so live pixels match replayed pixels.
                    for (i in 0 until mappedAll.size - 1) {
                        paint.strokeWidth = dyn.next((mappedAll[i + 1] - mappedAll[i]).getDistance(), brushSize * brushScale)
                        val seg = android.graphics.Path()
                        seg.moveTo(mappedAll[i].x, mappedAll[i].y)
                        seg.lineTo(mappedAll[i + 1].x, mappedAll[i + 1].y)
                        drawPathAll(seg)
                    }
                } else {
                    val seg = android.graphics.Path()
                    seg.moveTo(mappedAll[0].x, mappedAll[0].y)
                    for (i in 1 until mappedAll.size) {
                        seg.lineTo(mappedAll[i].x, mappedAll[i].y)
                    }
                    drawPathAll(seg)
                }
            }

            val lastMapped = mappedAll.last()

            withContext(dispatchers.main) {
                strokeWorkingBitmap = workBitmap
                strokeWorkingCanvas = workCanvas
                strokePaint = paint
                strokePrevBitmapPoint = lastMapped
                _uiState.update { it.copy(
                    liveStrokeLayerId = layerId,
                    liveStrokeBitmap = workBitmap,
                    liveStrokeVersion = it.liveStrokeVersion + catchUpPoints.size
                )}
            }
        }
    }

    /** Called for every drag update. Draws only the new segment onto the working bitmap. */
    fun onStrokePoint(currentPoint: Offset) {
        val stabilizedPoint = strokeStabilizer.stabilize(currentPoint, _uiState.value.stabilizerLevel)

        // Input-rate throttle. Touch panels report at 120-240 Hz and this method previously
        // rendered and published a frame for every single sample — the editor's largest power cost
        // while drawing, spent on frames the display never showed. Dropping a sample loses nothing
        // visible: the stroke is a polyline, so the segment simply spans to the next kept point.
        //
        // The point is still stabilized first (above) so the filter's history stays continuous, and
        // the very first point of a stroke is never dropped — a quick tap is a single dab and has
        // no later sample to fall back on.
        val rateHz = _uiState.value.inputSampleRateHz
        if (rateHz > 0 && lastSampleMs != 0L) {
            val minGapMs = 1000L / rateHz
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastSampleMs < minGapMs) return
            lastSampleMs = now
        } else {
            lastSampleMs = android.os.SystemClock.uptimeMillis()
        }

        addStrokePoint(stabilizedPoint)

        // Liquify live preview: cancel any pending warp job and start a fresh one from the
        // original bitmap so each drag frame shows the full accumulated warp.
        if (_uiState.value.activeTool == Tool.LIQUIFY) {
            val layerId = strokeLayerId ?: return
            // Capture the original bitmap once: onStrokeEnd may null the field while the warp job
            // below is still queued on the default dispatcher, and a fast tool switch can enter this
            // branch before onStrokeStart populated it. Bailing here is preferable to an NPE.
            val original = liquifyOriginalBitmap ?: return
            val points = snapshotStrokePoints()
            val canvasW = strokeCanvasW
            val canvasH = strokeCanvasH
            val brushSize = _uiState.value.brushSize
            val capturedScale = strokeLayerScale
            val capturedOffset = strokeLayerOffset
            val capturedRotZ = strokeLayerRotationZ
            val state = _uiState.value

            // Apply incremental liquify to the native engine
            if (points.size >= 2) {
                val p1 = points[points.size - 2]
                val p2 = points.last()

                // We need to map these screen points to the bitmap space
                val mapped = ImageProcessor.mapScreenToBitmap(
                    listOf(p1, p2), canvasW, canvasH, original.width, original.height,
                    capturedScale, capturedOffset, capturedRotZ, state.viewportOffset, state.viewportZoom, state.viewportRotation
                )

                val strokeArr = floatArrayOf(mapped[0].x, mapped[0].y, mapped[1].x, mapped[1].y)
                slamManager.applyLiquify(strokeArr, brushSize, 0.5f)
            }

            liquifyJob?.cancel()
            liquifyJob = viewModelScope.launch(dispatchers.default) {
                val warpBitmap = original.copy(Bitmap.Config.ARGB_8888, true)
                slamManager.bakeLiquify(warpBitmap)

                if (isActive) {
                    withContext(dispatchers.main) {
                        _uiState.update { it.copy(
                            liveStrokeBitmap = warpBitmap,
                            liveStrokeVersion = it.liveStrokeVersion + 1
                        )}
                    }
                }
            }
            return
        }

        val stampBrush = stampBrushForStroke
        if (stampBrush != null) {
            // Live stamp preview: stamp only the newly-added dabs. BrushStamps.dabs grows a stable
            // prefix, so re-drawing dabs beyond the count already stamped matches a full re-render.
            val canvas = stampLiveCanvas ?: return          // copy not ready yet; points still collected
            val work = stampLiveBitmap ?: return
            val state = _uiState.value
            // Map only the points not yet mapped (per-point transform, so a tail maps the same as the
            // whole) and append to the cache — avoids re-mapping the full stroke every drag frame.
            val all = snapshotStrokePoints()
            val mappedCount = stampMappedPoints.size / 2
            if (all.size > mappedCount) {
                val fresh = ImageProcessor.mapScreenToBitmap(
                    all.subList(mappedCount, all.size), strokeCanvasW, strokeCanvasH, work.width, work.height,
                    strokeLayerScale, strokeLayerOffset, strokeLayerRotationZ, state.viewportOffset, state.viewportZoom, state.viewportRotation
                )
                fresh.forEach { stampMappedPoints.add(it.x); stampMappedPoints.add(it.y) }
            }
            val brushScale = ImageProcessor.screenToBitmapScale(
                strokeCanvasW, strokeCanvasH, work.width, work.height, strokeLayerScale
            )
            val dabs = BrushStamps.dabs(stampMappedPoints, _uiState.value.brushSize * brushScale, stampBrush, stampSeed)
            if (dabs.size > stampStampedCount) {
                StampBrushRenderer.paintDabs(
                    canvas, dabs.subList(stampStampedCount, dabs.size), stampBrush,
                    _uiState.value.activeColor.toArgb(), _uiState.value.brushFlow, stampShapeForStroke,
                )
                stampStampedCount = dabs.size
                _uiState.update { it.copy(liveStrokeVersion = it.liveStrokeVersion + 1) }
            }
            return
        }

        val canvas = strokeWorkingCanvas ?: return
        val paint = strokePaint ?: return
        val prev = strokePrevBitmapPoint ?: return
        val workBitmap = strokeWorkingBitmap ?: return
        val state = _uiState.value

        val mapped = ImageProcessor.mapScreenToBitmap(
            listOf(currentPoint), strokeCanvasW, strokeCanvasH, workBitmap.width, workBitmap.height,
            strokeLayerScale, strokeLayerOffset, strokeLayerRotationZ, state.viewportOffset, state.viewportZoom, state.viewportRotation
        ).first()

        // Dynamic brush width: advance the per-stroke recursion by this segment's length. The same
        // recursion runs from scratch on commit/replay, so the live paint matches exactly.
        strokeDynamics?.let { dyn ->
            val brushScale = ImageProcessor.screenToBitmapScale(
                strokeCanvasW, strokeCanvasH, workBitmap.width, workBitmap.height, strokeLayerScale
            )
            paint.strokeWidth = dyn.next((mapped - prev).getDistance(), _uiState.value.brushSize * brushScale)
        }

        val seg = Path()
        seg.moveTo(prev.x, prev.y)
        seg.lineTo(mapped.x, mapped.y)

        val segs = ArrayList<Path>(2)
        segs.add(seg)
        if (strokeSymmetry != SymmetryMode.NONE) {
            val m = android.graphics.Matrix().apply { setScale(-1f, 1f, workBitmap.width / 2f, 0f) }
            segs.add(Path(seg).apply { transform(m) })
        }
        for (s in segs) {
            if (_uiState.value.wrapAroundMode) {
                val w = workBitmap.width.toFloat()
                val h = workBitmap.height.toFloat()
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        if (dx == 0 && dy == 0) {
                            canvas.drawPath(s, paint)
                        } else {
                            val p = Path(s)
                            p.offset(dx * w, dy * h)
                            canvas.drawPath(p, paint)
                        }
                    }
                }
            } else {
                canvas.drawPath(s, paint)
            }
        }
        strokePrevBitmapPoint = mapped

        _uiState.update { it.copy(liveStrokeVersion = it.liveStrokeVersion + 1) }
    }

    /** Called when the user lifts their finger. Finalizes the stroke into the layer and undo history. */
    fun onStrokeEnd() {
        val state = _uiState.value
        val layerId = strokeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val points = snapshotStrokePoints()
        val canvasW = strokeCanvasW
        val canvasH = strokeCanvasH

        val capturedScale = strokeLayerScale
        val capturedOffset = strokeLayerOffset
        val capturedRotationZ = strokeLayerRotationZ

        // Use the brush the stroke was actually drawn with (captured at start), not the current
        // selection, so the commit matches the live preview even if selection somehow changed.
        val stampBrush = stampBrushForStroke
        if (stampBrush != null && state.activeTool == Tool.BRUSH) {
            commitStampStroke(
                state, layer, layerId, points, canvasW, canvasH,
                capturedScale, capturedOffset, capturedRotationZ, stampBrush, stampShapeForStroke,
                strokeSelection,
            )
            clearTransientStrokeState()
            return
        }

        if (state.activeTool == Tool.BLUR || state.activeTool == Tool.SHARPEN || state.activeTool == Tool.SMUDGE || state.activeTool == Tool.CLONE) {
            val base = layer.bitmap ?: run { clearTransientStrokeState(); return }
            val strokeCloneOffset = if (state.activeTool == Tool.CLONE) {
                state.cloneSource?.let { src -> points.firstOrNull()?.let { start -> src - start } }
            } else null
            val command = StrokeCommand(
                path = points,
                canvasSize = IntSize(canvasW, canvasH),
                tool = state.activeTool,
                brushSize = state.brushSize,
                brushColor = state.activeColor.toArgb(),
                intensity = 0.5f,
                feathering = state.brushFeathering,
                layerScale = capturedScale,
                layerOffset = capturedOffset,
                layerRotationZ = capturedRotationZ,
                viewportOffset = state.viewportOffset,
                viewportZoom = state.viewportZoom,
                viewportRotation = state.viewportRotation,
                symmetryMode = strokeSymmetry,
                selection = strokeSelection,
                cloneOffset = strokeCloneOffset,
            )
            layerStore.addStroke(layerId, command)
            history.pushDraw(layerId, command)
            updateHistoryCounts()
            maybeBakeOldStrokes(layerId)

            val mapped = ImageProcessor.mapScreenToBitmap(
                points, canvasW, canvasH, base.width, base.height,
                capturedScale, capturedOffset, capturedRotationZ, state.viewportOffset, state.viewportZoom, state.viewportRotation
            )
            viewModelScope.launch(dispatchers.default) {
                val updated = drawingEngine.applySingleStroke(base, command)
                withContext(dispatchers.main) {
                    _uiState.update { s ->
                        s.copy(
                            layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = updated) else it },
                            liveStrokeLayerId = null,
                            liveStrokeBitmap = null,
                        )
                    }
                    scheduleDiskSave(layerId, updated, layer.uri)
                }
            }
            opEmitter.emit(
                Op.StrokeComplete(
                    layerId,
                    BrushStroke(
                        points = mapped.flatMap { listOf(it.x, it.y) },
                        colorArgb = state.activeColor.toArgb().toLong() and 0xFFFFFFFFL,
                        brushSize = state.brushSize,
                        brushFeathering = state.brushFeathering,
                        blendModeOrdinal = state.activeTool.ordinal,
                    )
                )
            )
            clearTransientStrokeState()
            return
        }

        if (state.activeTool == Tool.LIQUIFY || strokeWorkingBitmap == null) {
            // Liquify (or a stroke so fast the background copy hadn't finished):
            // fall back to the full whole-stroke approach.
            val bitmap = layer.bitmap ?: return
            
            val finalBitmap = if (state.activeTool == Tool.LIQUIFY) {
                // Fall back to the committed layer bitmap if the original was already cleared
                // (e.g. a second onStrokeEnd, or a start that never populated it) rather than NPE.
                val baked = (liquifyOriginalBitmap ?: bitmap).copy(Bitmap.Config.ARGB_8888, true)
                slamManager.bakeLiquify(baked)
                baked
            } else {
                // Fast stroke: the background working-bitmap copy never finished before finger-up, so
                // rasterize the whole stroke onto a fresh copy here. Committing `bitmap` unchanged (the
                // old behaviour) silently dropped the stroke — it lived only in history, which isn't
                // replayed on reload, so it vanished on screen and on disk.
                // Bitmap.copy can return null under memory pressure — never construct a Canvas from it
                // unchecked (NPE on the main thread). Fall back to the unmodified bitmap if the copy fails.
                val target = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                if (target != null && points.isNotEmpty()) {
                    val canvas = android.graphics.Canvas(target)
                    SelectionMask.clip(
                        canvas,
                        SelectionMask.bitmapPath(
                            strokeSelection, target.width, target.height,
                            capturedScale, capturedOffset, capturedRotationZ,
                            state.viewportOffset, state.viewportZoom, state.viewportRotation
                        ),
                    )
                    val brushScale = ImageProcessor.screenToBitmapScale(
                        canvasW, canvasH, target.width, target.height, capturedScale
                    )
                    val paint = buildStrokePaint(
                        state.activeTool, state.activeColor.toArgb(), state.brushSize * brushScale,
                        state.brushFeathering, strokeAlphaLock
                    )
                    val mapped = ImageProcessor.mapScreenToBitmap(
                        points, canvasW, canvasH, target.width, target.height,
                        capturedScale, capturedOffset, capturedRotationZ, state.viewportOffset, state.viewportZoom, state.viewportRotation
                    )
                    val bw = target.width.toFloat()
                    val bh = target.height.toFloat()

                    fun drawPathAll(seg: android.graphics.Path) {
                        val targets = ArrayList<android.graphics.Path>(2)
                        targets.add(seg)
                        if (strokeSymmetry != SymmetryMode.NONE) {
                            val m = android.graphics.Matrix().apply { setScale(-1f, 1f, bw / 2f, 0f) }
                            targets.add(android.graphics.Path(seg).apply { transform(m) })
                        }
                        for (t in targets) {
                            if (state.wrapAroundMode) {
                                for (dx in -1..1) for (dy in -1..1) {
                                    if (dx == 0 && dy == 0) canvas.drawPath(t, paint)
                                    else canvas.drawPath(android.graphics.Path(t).apply { offset(dx * bw, dy * bh) }, paint)
                                }
                            } else {
                                canvas.drawPath(t, paint)
                            }
                        }
                    }

                    if (mapped.size == 1) {
                        val xs = if (strokeSymmetry != SymmetryMode.NONE) floatArrayOf(mapped[0].x, bw - mapped[0].x) else floatArrayOf(mapped[0].x)
                        for (x in xs) {
                            if (state.wrapAroundMode) {
                                for (dx in -1..1) for (dy in -1..1) canvas.drawPoint(x + dx * bw, mapped[0].y + dy * bh, paint)
                            } else {
                                canvas.drawPoint(x, mapped[0].y, paint)
                            }
                        }
                    } else if (state.activeTool == Tool.BRUSH) {
                        // Dynamic brush: same recursion the live path and undo replay use.
                        val widths = BrushDynamics.segmentWidths(mapped, state.brushSize * brushScale)
                        for (i in 0 until mapped.size - 1) {
                            paint.strokeWidth = widths[i]
                            val seg = android.graphics.Path()
                            seg.moveTo(mapped[i].x, mapped[i].y)
                            seg.lineTo(mapped[i + 1].x, mapped[i + 1].y)
                            drawPathAll(seg)
                        }
                    } else {
                        val seg = android.graphics.Path()
                        seg.moveTo(mapped[0].x, mapped[0].y)
                        for (i in 1 until mapped.size) seg.lineTo(mapped[i].x, mapped[i].y)
                        drawPathAll(seg)
                    }
                }
                target ?: bitmap
            }

            val command = StrokeCommand(
                path = points,
                canvasSize = IntSize(canvasW, canvasH),
                tool = state.activeTool,
                brushSize = state.brushSize,
                brushColor = state.activeColor.toArgb(),
                intensity = 0.5f,
                feathering = state.brushFeathering,
                layerScale = capturedScale,
                layerOffset = capturedOffset,
                layerRotationZ = capturedRotationZ,
                symmetryMode = strokeSymmetry,
                alphaLock = strokeAlphaLock,
                selection = strokeSelection,
            )

            // Add stroke to history
            layerStore.addStroke(layerId, command)
            history.pushDraw(layerId, command)
            updateHistoryCounts()
            maybeBakeOldStrokes(layerId)

            _uiState.update { s ->
                s.copy(
                    layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = finalBitmap) else it },
                    liveStrokeLayerId = null,
                    liveStrokeBitmap = null
                )
            }
            scheduleDiskSave(layerId, finalBitmap, layer.uri)
        } else {
            // Real-time path: the working bitmap already contains the complete stroke.
            val workBitmap = strokeWorkingBitmap!!
            val command = StrokeCommand(
                path = points,
                canvasSize = IntSize(canvasW, canvasH),
                tool = state.activeTool,
                brushSize = state.brushSize,
                brushColor = state.activeColor.toArgb(),
                intensity = 0.5f,
                feathering = state.brushFeathering,
                layerScale = capturedScale,
                layerOffset = capturedOffset,
                layerRotationZ = capturedRotationZ,
                symmetryMode = strokeSymmetry,
                alphaLock = strokeAlphaLock,
                selection = strokeSelection,
            )

            // Add stroke to history for undo/redo replay.
            layerStore.addStroke(layerId, command)
            history.pushDraw(layerId, command)
            updateHistoryCounts()
            maybeBakeOldStrokes(layerId)

            // Commit: working bitmap becomes the displayed layer bitmap.
            _uiState.update { s ->
                s.copy(
                    layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = workBitmap) else it },
                    liveStrokeLayerId = null,
                    liveStrokeBitmap = null
                )
            }
            scheduleDiskSave(layerId, workBitmap, layer.uri)
        }

        // Co-op sync: replayable brush strokes go as StrokeComplete; Liquify bakes into the
        // bitmap and can't map to a BrushStroke, so it propagates as a whole-bitmap replace.
        if (state.activeTool != Tool.LIQUIFY) {
            val bitmap = layer.bitmap
            if (bitmap != null) {
                val mappedPoints = ImageProcessor.mapScreenToBitmap(
                    points, canvasW, canvasH, bitmap.width, bitmap.height,
                    capturedScale, capturedOffset, capturedRotationZ, state.viewportOffset, state.viewportZoom, state.viewportRotation
                )
                val pointsFlat = mappedPoints.flatMap { listOf(it.x, it.y) }
                val brushStroke = BrushStroke(
                    points = pointsFlat,
                    colorArgb = state.activeColor.toArgb().toLong() and 0xFFFFFFFFL,
                    brushSize = state.brushSize,
                    brushFeathering = state.brushFeathering,
                    blendModeOrdinal = state.activeTool.ordinal
                )
                opEmitter.emit(Op.StrokeComplete(layerId, brushStroke))
            }
        } else {
            val baked = _uiState.value.layers.find { it.id == layerId }?.bitmap
            if (baked != null) {
                opEmitter.emit(Op.LayerBitmapReplace(layerId, ImageUtils.bitmapToByteArray(baked)))
            }
        }

        clearTransientStrokeState()
    }

    /**
     * Commit an azphalt stamp-brush stroke: record a replayable [StrokeCommand] (carrying the brush,
     * flow and a content-derived seed so undo/redo re-composites identically), then rasterize the whole
     * stroke onto a fresh copy of the layer bitmap via [StampBrushRenderer] off the main thread and
     * publish it. The seed is derived from the stroke's points so the same stroke always jitters the
     * same way — [DrawingEngine] uses it on replay.
     */
    private fun commitStampStroke(
        state: EditorUiState,
        layer: Layer,
        layerId: String,
        points: List<Offset>,
        canvasW: Int,
        canvasH: Int,
        scale: Float,
        offset: Offset,
        rotationZ: Float,
        brush: com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush,
        stampShape: Bitmap?,
        // Passed in rather than read off `strokeSelection`: the caller clears the transient stroke
        // state the moment this returns, while the rasterization below runs later on a background
        // dispatcher and would otherwise find it already null.
        selection: com.hereliesaz.graffitixr.common.model.Selection?,
    ) {
        val base = layer.bitmap ?: return
        if (points.isEmpty()) return
        val color = state.activeColor.toArgb()
        val brushSize = state.brushSize
        val flow = state.brushFlow
        val command = StrokeCommand(
            path = points,
            canvasSize = IntSize(canvasW, canvasH),
            tool = Tool.BRUSH,
            brushSize = brushSize,
            brushColor = color,
            intensity = 0.5f,
            feathering = state.brushFeathering,
            layerScale = scale,
            layerOffset = offset,
            layerRotationZ = rotationZ,
            viewportOffset = state.viewportOffset,
            viewportZoom = state.viewportZoom,
            viewportRotation = state.viewportRotation,
            stampBrush = brush,
            flow = flow,
            // Reuse the live-preview seed so the committed pixels match what was previewed (no flash).
            seed = stampSeed,
            stampShape = stampShape,
            selection = selection,
        )
        layerStore.addStroke(layerId, command)
        history.pushDraw(layerId, command)
        updateHistoryCounts()
        maybeBakeOldStrokes(layerId)

        // Capture this stroke's preview bitmap synchronously (clearTransientStrokeState nulls the field
        // right after this returns). The async commit only clears the live preview if it's still ours —
        // otherwise a stroke that started before this commit finished would have its preview wiped.
        val previewBitmap = stampLiveBitmap
        viewModelScope.launch(dispatchers.default) {
            val target = base.copy(Bitmap.Config.ARGB_8888, true) ?: return@launch
            val mapped = ImageProcessor.mapScreenToBitmap(
                points, canvasW, canvasH, target.width, target.height,
                scale, offset, rotationZ, state.viewportOffset, state.viewportZoom, state.viewportRotation
            )
            val brushScale = ImageProcessor.screenToBitmapScale(canvasW, canvasH, target.width, target.height, scale)
            val pts = ArrayList<Float>(mapped.size * 2)
            mapped.forEach { pts.add(it.x); pts.add(it.y) }
            val commitCanvas = Canvas(target)
            SelectionMask.clip(
                commitCanvas,
                SelectionMask.bitmapPath(
                    selection, target.width, target.height,
                    scale, offset, rotationZ, state.viewportOffset, state.viewportZoom, state.viewportRotation
                ),
            )
            StampBrushRenderer.paintStroke(
                commitCanvas, pts, brush, color, brushSize * brushScale, flow, command.seed, stampShape
            )
            withContext(dispatchers.main) {
                _uiState.update { s ->
                    val clearPreview = s.liveStrokeBitmap === previewBitmap
                    s.copy(
                        layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = target) else it },
                        liveStrokeLayerId = if (clearPreview) null else s.liveStrokeLayerId,
                        liveStrokeBitmap = if (clearPreview) null else s.liveStrokeBitmap,
                    )
                }
                scheduleDiskSave(layerId, target, layer.uri)
            }
        }
    }


    /**
     * Cancels the in-flight stroke without committing — a second finger landing mid-stroke means
     * the user is gesturing (two-finger tap = undo, pinch = navigate), not painting; Procreate
     * discards the partial stroke the same way. Nothing has been recorded yet (commands are
     * recorded on finger-up), so dropping the live preview is the whole cancel.
     */
    fun onStrokeCancel() {
        liquifyJob?.cancel()
        _uiState.update { it.copy(liveStrokeLayerId = null, liveStrokeBitmap = null) }
        clearTransientStrokeState()
    }

    // ── Long-press eyedropper (Procreate: hold still to sample the canvas) ───────────────────

    /** Begin sampling: composite what's on screen once, off the main thread, to read pixels from. */
    fun onEyedropStart(canvasSize: IntSize) {
        val layers = _uiState.value.layers
        val bg = _uiState.value.canvasBackground
        dispatch(EditorIntent.SetEyedrop(active = true))
        viewModelScope.launch(dispatchers.default) {
            val composite = exportManager.compositeLayers(
                layers, canvasSize.width, canvasSize.height, backgroundColor = bg.toArgb(),
            )
            withContext(dispatchers.main) {
                if (_uiState.value.isEyedropping) {
                    eyedropComposite?.recycle()
                    eyedropComposite = composite
                } else {
                    composite.recycle() // finger already lifted before the composite finished
                }
            }
        }
    }

    /** Update the sampled colour as the finger moves; [position] is in screen coordinates. */
    fun onEyedropSample(position: Offset) {
        val st = _uiState.value
        if (!st.isEyedropping) return
        val comp = eyedropComposite ?: run {
            dispatch(EditorIntent.SetEyedrop(true, st.eyedropColor, position))
            return
        }
        // Screen → world: undo the viewport camera, same math as onCommitPenPath.
        val rad = Math.toRadians(-st.viewportRotation.toDouble())
        val cos = kotlin.math.cos(rad)
        val sin = kotlin.math.sin(rad)
        val ux = (position.x - st.viewportOffset.x) / st.viewportZoom
        val uy = (position.y - st.viewportOffset.y) / st.viewportZoom
        val x = (ux * cos - uy * sin).toInt()
        val y = (ux * sin + uy * cos).toInt()
        val color = if (x in 0 until comp.width && y in 0 until comp.height) Color(comp.getPixel(x, y)) else null
        dispatch(EditorIntent.SetEyedrop(true, color ?: st.eyedropColor, position))
    }

    /** Lift: adopt the sampled colour (keeping the brush's working alpha) or abandon the sample. */
    fun onEyedropEnd(commit: Boolean) {
        val picked = _uiState.value.eyedropColor
        if (commit && picked != null) {
            setActiveColor(picked.copy(alpha = _uiState.value.activeColor.alpha))
        }
        dispatch(EditorIntent.SetEyedrop(active = false))
        eyedropComposite?.recycle()
        eyedropComposite = null
    }

    // ── Flood fill (Procreate's ColorDrop) ────────────────────────────────────────────────────

    /**
     * Fill the contiguous region of the active layer under [position] with the active colour.
     * Recorded as a replayable [StrokeCommand] (the fill re-runs deterministically from its tap
     * point), so it undoes and redoes exactly like a brush stroke.
     */
    fun onFillTap(position: Offset, canvasSize: IntSize) {
        val state = _uiState.value
        if (state.activeTool != Tool.FILL) return
        val layerId = state.activeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val base = layer.bitmap ?: run {
            Toast.makeText(context, "Fill needs a paint layer — vector shapes recolour via Edit", Toast.LENGTH_SHORT).show()
            return
        }
        val command = StrokeCommand(
            path = listOf(position),
            canvasSize = canvasSize,
            tool = Tool.FILL,
            brushSize = 0f,
            brushColor = state.activeColor.toArgb(),
            intensity = 1f,
            layerScale = layer.scale,
            layerOffset = layer.offset,
            layerRotationZ = layer.rotationZ,
            viewportOffset = state.viewportOffset,
            viewportZoom = state.viewportZoom,
            viewportRotation = state.viewportRotation,
            selection = state.selection,
        )
        layerStore.addStroke(layerId, command)
        history.pushDraw(layerId, command)
        updateHistoryCounts()
        maybeBakeOldStrokes(layerId)
        viewModelScope.launch(dispatchers.default) {
            val target = drawingEngine.applySingleStroke(base, command)
            withContext(dispatchers.main) {
                _uiState.update { s ->
                    s.copy(layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = target) else it })
                }
                scheduleDiskSave(layerId, target, layer.uri)
            }
            opEmitter.emit(Op.LayerBitmapReplace(layerId, ImageUtils.bitmapToByteArray(target)))
        }
    }

    // ── Layer operations: clear and merge down ───────────────────────────────────────────────

    /**
     * Wipes the active layer to transparency — Procreate's clear-layer (its three-finger scrub).
     * With a lasso active it clears only inside it, which is what a selection means everywhere else.
     *
     * Recorded as a replayable [StrokeCommand] for the same reason a selection move is: the editor
     * restores pixels by replaying commands onto a base, so a bitmap edit is only undoable if it is
     * replayable.
     */
    fun onClearLayer() {
        val state = _uiState.value
        val layerId = state.activeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val base = layer.bitmap ?: return
        val command = StrokeCommand(
            path = emptyList(),
            canvasSize = IntSize(base.width, base.height),
            tool = Tool.ERASER,
            brushSize = 0f,
            brushColor = 0,
            intensity = 0f,
            layerScale = layer.scale,
            layerOffset = layer.offset,
            layerRotationZ = layer.rotationZ,
            viewportOffset = state.viewportOffset,
            viewportZoom = state.viewportZoom,
            viewportRotation = state.viewportRotation,
            selection = state.selection,
            clearAll = true,
        )
        layerStore.addStroke(layerId, command)
        history.pushDraw(layerId, command)
        updateHistoryCounts()
        maybeBakeOldStrokes(layerId)
        showHud(if (state.selection != null) "Cleared selection" else "Cleared layer")

        viewModelScope.launch(dispatchers.default) {
            val target = drawingEngine.applySingleStroke(base, command)
            withContext(dispatchers.main) {
                _uiState.update { s ->
                    s.copy(layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = target) else it })
                }
                scheduleDiskSave(layerId, target, layer.uri)
            }
            opEmitter.emit(Op.LayerBitmapReplace(layerId, ImageUtils.bitmapToByteArray(target)))
        }
    }

    /**
     * Merges [layerId] into the layer directly beneath it, as Procreate's Merge Down does.
     *
     * Mirrors [onFlattenAllLayers] rather than overwriting the lower layer in place: the merged
     * pixels become a **new** layer and both sources leave the visible list while keeping their
     * [layerStore] base+strokes. That is what makes the undo correct — [pushHistory] strips bitmaps
     * from its snapshot, so restoring the two source layers can only work by rebuilding them from
     * the store, and overwriting the lower layer's cached base would have destroyed exactly what
     * the rebuild needs.
     */
    fun onMergeDown(layerId: String) {
        val projectId = _uiState.value.projectId ?: return
        val layers = _uiState.value.layers
        val index = layers.indexOfFirst { it.id == layerId }
        // Index 0 paints first (bottom), so there is nothing beneath it to merge into.
        if (index <= 0) {
            Toast.makeText(context, "Nothing below this layer to merge into", Toast.LENGTH_SHORT).show()
            return
        }
        val upper = layers[index]
        val lower = layers[index - 1]
        pushHistory()

        viewModelScope.launch(dispatchers.default) {
            val metrics = context.resources.displayMetrics
            val w = metrics.widthPixels.takeIf { it > 0 } ?: 1080
            val h = metrics.heightPixels.takeIf { it > 0 } ?: 1920
            // Composited in paint order (lower first) so the upper layer's blend mode and opacity
            // resolve against the one it is being merged into, exactly as they do on canvas.
            val merged = exportManager.compositeLayers(listOf(lower, upper), w, h)

            val filename = "merged_${UUID.randomUUID()}.png"
            val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(merged))
            val mergedLayer = Layer(
                id = UUID.randomUUID().toString(),
                name = lower.name,
                uri = "file://$path".toUri(),
                bitmap = merged,
            )

            withContext(dispatchers.main) {
                val current = _uiState.value.layers
                // Re-resolve positions: the list can have changed while the composite was running.
                val stillThere = current.any { it.id == upper.id } && current.any { it.id == lower.id }
                if (!stillThere) return@withContext
                val at = current.indexOfFirst { it.id == lower.id }
                layerStore.putBase(mergedLayer.id, merged.copy(Bitmap.Config.ARGB_8888, false))
                layerStore.initStrokes(mergedLayer.id)
                val next = current.filterNot { it.id == upper.id || it.id == lower.id }
                    .toMutableList()
                    .apply { add(at.coerceIn(0, size), mergedLayer) }
                dispatch(EditorIntent.ReplaceLayers(next, mergedLayer.id))
                opEmitter.emit(Op.LayerRemove(upper.id))
                opEmitter.emit(Op.LayerRemove(lower.id))
                opEmitter.emit(Op.LayerAdd(mergedLayer))
                saveProject()
                showHud("Merged down")
            }
        }
    }

    /**
     * Groups [layerId] with the layer immediately above it into a new [LayerType.GROUP] — the rail
     * equivalent of Procreate's pinch-to-group gesture, which AzNavRail's drag-to-reorder has no way
     * to express directly.
     */
    fun onGroupWithLayerAbove(layerId: String) {
        val layers = _uiState.value.layers
        val index = layers.indexOfFirst { it.id == layerId }
        if (index < 0 || index >= layers.lastIndex) {
            Toast.makeText(context, "Nothing above this layer to group with", Toast.LENGTH_SHORT).show()
            return
        }
        val above = layers[index + 1]
        pushHistory()
        dispatch(EditorIntent.GroupLayers(layerId, above.id, UUID.randomUUID().toString(), "Group"))
        saveProject()
    }

    fun onUngroupLayer(groupId: String) {
        pushHistory()
        dispatch(EditorIntent.UngroupLayer(groupId))
        saveProject()
    }

    /** Deletes group [groupId] AND its contents — unlike [onUngroupLayer], nothing survives it. */
    fun onDeleteGroup(groupId: String) {
        val layers = _uiState.value.layers
        if (layers.none { it.id == groupId }) return
        pushHistory()
        val toRemove = descendantIds(layers, groupId) + groupId
        dispatch(EditorIntent.SetLayers(layers.filterNot { it.id in toRemove }))
        saveProject()
    }

    private fun descendantIds(layers: List<Layer>, parentId: String): Set<String> {
        val direct = layers.filter { it.parentId == parentId }.map { it.id }
        return direct.toSet() + direct.flatMap { descendantIds(layers, it) }
    }

    fun onToggleClipToLayerBelow(id: String) {
        pushHistory()
        dispatch(EditorIntent.ToggleClipToLayerBelow(id))
        saveProject()
        _uiState.value.layers.find { it.id == id }?.let { opEmitter.emit(Op.LayerPropsChange(id, it.toLayerProps())) }
    }

    // ── QuickMenu (Procreate's radial six-slot) ──────────────────────────────────────────────

    /** Opens the radial menu centred on [at] (screen space) — normally the summoning centroid. */
    fun onOpenQuickMenu(at: Offset) = dispatch(EditorIntent.SetQuickMenu(at))

    fun onDismissQuickMenu() = dispatch(EditorIntent.SetQuickMenu(null))

    // ── Freehand selection (Procreate's lasso) ───────────────────────────────────────────────

    /**
     * Adopts the lasso the finger just traced. The polygon is thinned first — a traced loop arrives
     * at touch-event rate, and every retained vertex is re-mapped on every paint op the selection
     * clips. A loop too small to enclose anything is treated as a deselect by the reducer.
     */
    fun onSelectionEnd(points: List<Offset>, canvasSize: IntSize) {
        val state = _uiState.value
        val simplified = com.hereliesaz.graffitixr.common.util.SelectionGeometry.simplify(points)
        val worldPoints = com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor.mapScreenToWorld(
            simplified, state.viewportOffset, state.viewportZoom, state.viewportRotation
        )
        dispatch(EditorIntent.SetSelection(
            com.hereliesaz.graffitixr.common.model.Selection.ofPolygon(worldPoints, canvasSize)
        ))
    }

    fun nextSelectionName(): String {
        val currentNames = _uiState.value.savedSelections.map { it.name }.toSet()
        var i = 1
        while ("Selection $i" in currentNames) i++
        return "Selection $i"
    }

    fun onSaveSelection(name: String) {
        val selection = _uiState.value.selection ?: return
        if (!selection.isUsable) return
        val current = _uiState.value.savedSelections.toMutableList()
        val index = current.indexOfFirst { it.name == name }
        val saved = com.hereliesaz.graffitixr.common.model.SavedSelection(name = name, selection = selection)
        if (index >= 0) current[index] = saved else current.add(saved)
        dispatch(EditorIntent.SetSavedSelections(current))
    }

    fun onDeleteSavedSelection(name: String) {
        val current = _uiState.value.savedSelections.filterNot { it.name == name }
        dispatch(EditorIntent.SetSavedSelections(current))
    }

    fun onCanvasSizeChanged(size: IntSize) = dispatch(EditorIntent.SetCanvasSize(size))

    fun onSetCloneSource(at: Offset?) = dispatch(EditorIntent.SetCloneSource(at))

    fun setSelectionShape(shape: com.hereliesaz.graffitixr.common.model.SelectionShape) =
        dispatch(EditorIntent.SetSelectionShape(shape))

    fun cycleSelectionShape() {
        val entries = com.hereliesaz.graffitixr.common.model.SelectionShape.entries
        val currentIndex = entries.indexOf(_uiState.value.selectionShape)
        val nextShape = entries[(currentIndex + 1) % entries.size]
        setSelectionShape(nextShape)
    }

    fun onAutoSelect(at: Offset, canvasSize: IntSize) {
        val state = _uiState.value
        val activeLayer = state.layers.find { it.id == state.activeLayerId } ?: return
        val bitmap = activeLayer.bitmap ?: return
        val mapped = com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor.mapScreenToBitmap(
            listOf(at), canvasSize.width, canvasSize.height,
            bitmap.width, bitmap.height, activeLayer.scale, activeLayer.offset, activeLayer.rotationZ,
            state.viewportOffset, state.viewportZoom, state.viewportRotation
        ).firstOrNull() ?: return
        val x = mapped.x.toInt()
        val y = mapped.y.toInt()
        if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return
        val mask = BooleanArray(bitmap.width * bitmap.height)
        val targetColor = bitmap.getPixel(x, y)
        val tol = state.magicWandTolerance
        val q = java.util.ArrayDeque<Pair<Int, Int>>()
        q.add(x to y)
        mask[y * bitmap.width + x] = true
        val tr = (targetColor shr 16) and 0xFF
        val tg = (targetColor shr 8) and 0xFF
        val tb = targetColor and 0xFF
        while (q.isNotEmpty()) {
            val (cx, cy) = q.removeFirst()
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = cx + dx
                val ny = cy + dy
                if (nx in 0 until bitmap.width && ny in 0 until bitmap.height) {
                    val idx = ny * bitmap.width + nx
                    if (!mask[idx]) {
                        val c = bitmap.getPixel(nx, ny)
                        val cr = (c shr 16) and 0xFF
                        val cg = (c shr 8) and 0xFF
                        val cb = c and 0xFF
                        if (kotlin.math.abs(cr - tr) <= tol && kotlin.math.abs(cg - tg) <= tol && kotlin.math.abs(cb - tb) <= tol) {
                            mask[idx] = true
                            q.add(nx to ny)
                        }
                    }
                }
            }
        }
        val rings = com.hereliesaz.graffitixr.common.util.ContourTrace.contours(mask, bitmap.width, bitmap.height)
        if (rings.isEmpty()) return
        val newSelection = com.hereliesaz.graffitixr.common.model.Selection(rings, canvasSize)
        dispatch(EditorIntent.SetSelection(newSelection))
    }

    internal fun dispatchForTest(intent: EditorIntent) = dispatch(intent)

    fun recordedStrokesForTest(layerId: String): List<StrokeCommand> = layerStore.strokes(layerId)

    fun onColorFillSelection() {
        val state = _uiState.value
        val selection = state.selection ?: return
        if (!selection.isUsable) return
        val layerId = state.activeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val command = StrokeCommand(
            path = selection.path,
            canvasSize = selection.canvasSize,
            tool = Tool.FILL,
            brushSize = 0f,
            brushColor = state.activeColor.toArgb(),
            intensity = 1f,
            fillSelection = true,
            selection = selection,
            layerScale = layer.scale,
            layerOffset = layer.offset,
            layerRotationZ = layer.rotationZ,
            viewportOffset = state.viewportOffset,
            viewportZoom = state.viewportZoom,
            viewportRotation = state.viewportRotation,
        )
        layerStore.addStroke(layerId, command)
        history.pushDraw(layerId, command)
        updateHistoryCounts()
        rebuildLayerBitmap(layerId, emitOp = true)
    }

    fun onWarpHandleMoved(index: Int, at: Offset) {
        val current = _uiState.value.warpHandles.toMutableList()
        if (index in current.indices) {
            current[index] = at
            dispatch(EditorIntent.SetWarpHandles(current))
        }
    }

    fun onWarpHandleReleased() {
        val state = _uiState.value
        val activeLayerId = state.activeLayerId ?: return
        val handles = state.warpHandles
        if (handles.isEmpty()) return
        val command = StrokeCommand(
            path = emptyList(),
            canvasSize = state.canvasSize,
            tool = Tool.NONE,
            brushSize = 0f,
            brushColor = 0,
            intensity = 0f,
            warpHandles = handles,
        )
        layerStore.addStroke(activeLayerId, command)
        history.pushDraw(activeLayerId, command)
        updateHistoryCounts()
        maybeBakeOldStrokes(activeLayerId)
    }

    fun onClearSelection() = dispatch(EditorIntent.SetSelection(null))

    fun onInvertSelection() = dispatch(EditorIntent.InvertSelection)

    /**
     * Moves the selected pixels by [delta] (screen space) on the active layer.
     *
     * Recorded as a [Tool.SELECT] [StrokeCommand] rather than a bitmap snapshot: the editor's
     * history restores pixels by *replaying* stroke commands onto a base (property snapshots
     * deliberately strip bitmaps), so a move only becomes undoable by being replayable. Given the
     * same base, lasso and delta it reproduces identically — see [DrawingEngine].
     *
     * The marquee travels with its pixels so the selection keeps bounding the same content.
     */
    fun onSelectionMove(delta: Offset) {
        val state = _uiState.value
        val selection = state.selection ?: return
        if (!selection.isUsable) return
        // Sub-pixel drags would spend a full-bitmap lift to change nothing.
        if (delta.getDistance() < 1f) return
        val layerId = state.activeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val base = layer.bitmap ?: run {
            Toast.makeText(context, "Move needs a paint layer — vector shapes move via Transform", Toast.LENGTH_SHORT).show()
            return
        }
        val command = StrokeCommand(
            // `path` carries the lasso too so the command reads sensibly on its own; the clip is
            // built from `selection`, which also records whether it was inverted.
            path = selection.path,
            canvasSize = selection.canvasSize,
            tool = Tool.SELECT,
            brushSize = 0f,
            brushColor = 0,
            intensity = 0f,
            layerScale = layer.scale,
            layerOffset = layer.offset,
            layerRotationZ = layer.rotationZ,
            viewportOffset = state.viewportOffset,
            viewportZoom = state.viewportZoom,
            viewportRotation = state.viewportRotation,
            selection = selection,
            moveDelta = delta,
        )
        layerStore.addStroke(layerId, command)
        history.pushDraw(layerId, command)
        updateHistoryCounts()
        maybeBakeOldStrokes(layerId)

        viewModelScope.launch(dispatchers.default) {
            val clipPath = SelectionMask.bitmapPath(
                selection, base.width, base.height, layer.scale, layer.offset, layer.rotationZ,
            ) ?: return@launch
            val d = SelectionMask.mapDelta(
                delta, selection.canvasSize.width, selection.canvasSize.height,
                base.width, base.height, layer.scale, layer.offset, layer.rotationZ,
                state.viewportOffset, state.viewportZoom, state.viewportRotation
            )
            val moved = SelectionMask.moveRegion(base, clipPath, d.x, d.y)
            withContext(dispatchers.main) {
                _uiState.update { s ->
                    s.copy(layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = moved) else it })
                }
                val pts = com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor.mapScreenToWorld(
                    listOf(Offset.Zero, delta), state.viewportOffset, state.viewportZoom, state.viewportRotation
                )
                val worldDelta = if (pts.size == 2) pts[1] - pts[0] else Offset.Zero
                dispatch(EditorIntent.SetSelection(selection.translated(worldDelta)))
                scheduleDiskSave(layerId, moved, layer.uri)
            }
            // Not in the co-op stroke vocabulary; peers get the finished pixels instead.
            opEmitter.emit(Op.LayerBitmapReplace(layerId, ImageUtils.bitmapToByteArray(moved)))
        }
    }

    // ── Animation Assist ─────────────────────────────────────────────────────────────────────

    private var playbackJob: kotlinx.coroutines.Job? = null

    /**
     * Enters/exits Animation Assist. Entering syncs the frame cursor to whichever frame the active
     * layer already belongs to (see the reducer), so the canvas doesn't jump; exiting stops playback
     * so a running loop can't keep mutating state from behind a closed panel.
     */
    fun onToggleAnimationMode() {
        if (_uiState.value.isAnimationMode) stopPlayback()
        dispatch(EditorIntent.ToggleAnimationMode)
    }

    fun onToggleOnionSkin() = dispatch(EditorIntent.ToggleOnionSkin)
    fun onSetOnionSkinFrameCount(count: Int) = dispatch(EditorIntent.SetOnionSkinFrameCount(count))
    fun onSetAnimationFrameDurationMs(ms: Int) = dispatch(EditorIntent.SetAnimationFrameDurationMs(ms))
    fun onSetAnimationLoopMode(mode: com.hereliesaz.graffitixr.common.model.AnimationLoopMode) =
        dispatch(EditorIntent.SetAnimationLoopMode(mode))

    /** Frame count == top-level layer count, since that's what a frame *is*. */
    fun animationFrameCount(): Int = AnimationFrames.topLevelFrames(_uiState.value.layers).size

    /**
     * Moves the frame cursor and points the active layer at that frame, so a stroke drawn right
     * after stepping lands on the frame the user is looking at rather than the one they left.
     */
    fun onSelectFrame(index: Int) {
        val count = animationFrameCount()
        if (count == 0) return
        dispatch(EditorIntent.SetActiveFrameIndex(index.coerceIn(0, count - 1), followActiveLayer = true))
    }

    fun onNextFrame() {
        val count = animationFrameCount()
        if (count == 0) return
        onSelectFrame((_uiState.value.activeFrameIndex + 1) % count)
    }

    fun onPreviousFrame() {
        val count = animationFrameCount()
        if (count == 0) return
        onSelectFrame((_uiState.value.activeFrameIndex - 1 + count) % count)
    }

    /** A new frame is just a new top-level layer, appended — so this is the existing blank-layer add. */
    fun onAddFrame() {
        val before = animationFrameCount()
        onAddBlankLayer()
        viewModelScope.launch(dispatchers.main) {
            // AddLayer lands asynchronously (it writes the layer's artifact first), so wait for the
            // count to grow rather than moving the cursor to a frame that doesn't exist yet.
            withTimeoutOrNull(5_000) { _uiState.first { AnimationFrames.topLevelFrames(it.layers).size > before } }
            dispatch(EditorIntent.SetActiveFrameIndex((animationFrameCount() - 1).coerceAtLeast(0)))
        }
    }

    fun onToggleAnimationPlayback() {
        if (_uiState.value.isAnimationPlaying) stopPlayback() else startPlayback()
    }

    private fun startPlayback() {
        val count = animationFrameCount()
        if (count <= 1) return
        dispatch(EditorIntent.SetAnimationPlaying(true))
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch(dispatchers.main) {
            var index = _uiState.value.activeFrameIndex
            var forward = true
            while (isActive) {
                kotlinx.coroutines.delay(_uiState.value.animationFrameDurationMs.toLong())
                val frames = animationFrameCount()
                if (frames <= 1) break
                when (_uiState.value.animationLoopMode) {
                    com.hereliesaz.graffitixr.common.model.AnimationLoopMode.LOOP -> index = (index + 1) % frames
                    com.hereliesaz.graffitixr.common.model.AnimationLoopMode.PING_PONG -> {
                        if (forward && index >= frames - 1) forward = false
                        else if (!forward && index <= 0) forward = true
                        index = (index + if (forward) 1 else -1).coerceIn(0, frames - 1)
                    }
                    com.hereliesaz.graffitixr.common.model.AnimationLoopMode.ONCE -> {
                        if (index >= frames - 1) break
                        index++
                    }
                }
                // Only the cursor moves during playback — syncActiveLayerToFrame would rewrite the
                // active layer dozens of times a second and clobber whatever the user had selected.
                dispatch(EditorIntent.SetActiveFrameIndex(index))
            }
            dispatch(EditorIntent.SetAnimationPlaying(false))
        }
    }

    private fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        if (_uiState.value.isAnimationPlaying) dispatch(EditorIntent.SetAnimationPlaying(false))
    }

    /**
     * Exports every frame as an animated GIF in Downloads. Each frame composites only its own
     * subtree — [buildLayerTree] roots at `parentId == null`, so a frame's own layers form a
     * complete tree on their own and group/clip/blend handling comes along unchanged.
     */
    fun exportAnimation() {
        val state = _uiState.value
        val frames = AnimationFrames.topLevelFrames(state.layers)
        if (frames.isEmpty()) {
            Toast.makeText(context, "Nothing to export", Toast.LENGTH_SHORT).show()
            return
        }
        stopPlayback()
        viewModelScope.launch(dispatchers.default) {
            dispatch(EditorIntent.SetLoading(true))
            try {
                val metrics = context.resources.displayMetrics
                val dir = File(context.cacheDir, "animation").apply { mkdirs() }
                val file = File(dir, "animation_${System.currentTimeMillis()}.gif")
                val written = com.hereliesaz.graffitixr.feature.editor.animation.AnimationGifWriter.write(
                    file = file,
                    frameCount = frames.size,
                    frameDurationMs = state.animationFrameDurationMs,
                    loopMode = state.animationLoopMode,
                ) { index ->
                    val frame = frames.getOrNull(index) ?: return@write null
                    val ids = AnimationFrames.frameSubtreeIds(state.layers, frame.id)
                    exportManager.compositeToDocument(
                        state.layers.filter { it.id in ids },
                        metrics.widthPixels,
                        metrics.heightPixels,
                        state.documentWidth,
                        state.documentHeight,
                        backgroundColor = android.graphics.Color.WHITE,
                    )
                }
                withContext(dispatchers.main) { dispatch(EditorIntent.SetLoading(false)) }
                if (written > 0) {
                    saveAnimationToDownloads(file)
                } else {
                    file.delete()
                    withContext(dispatchers.main) {
                        Toast.makeText(context, "Animation export produced no frames", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(dispatchers.main) {
                    dispatch(EditorIntent.SetLoading(false))
                    Toast.makeText(context, "Animation export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun saveAnimationToDownloads(file: File) =
        copyGifToDownloads(file, "Animation saved to Downloads", "Animation export failed")

    // ── Symmetry & Alpha Lock ────────────────────────────────────────────────────────────────

    fun onToggleSymmetry() = dispatch(EditorIntent.ToggleSymmetry)
    fun onSetSymmetryMode(mode: SymmetryMode) = dispatch(EditorIntent.SetSymmetryMode(mode))
    fun setLastSymmetryMode(mode: SymmetryMode) = dispatch(EditorIntent.SetLastSymmetryMode(mode))

    fun onToggleAlphaLock(id: String) {
        pushHistory()
        dispatch(EditorIntent.ToggleAlphaLock(id))
        saveProject()
        _uiState.value.layers.find { it.id == id }?.let { opEmitter.emit(Op.LayerPropsChange(id, it.toLayerProps())) }
    }

    // ── QuickShape (pen snapped to an ideal ellipse on hold) ────────────────────────────────

    /**
     * Commits a QuickShape-recognized ellipse as a vector layer. Inputs are in screen space; the
     * centre is mapped back through the camera like a pen path, and the radii divided by the zoom.
     */
    fun onCommitPenEllipse(
        centerScreen: Offset,
        radiusXScreen: Float,
        radiusYScreen: Float,
        canvasWidth: Float,
        canvasHeight: Float,
    ) {
        if (_uiState.value.projectId == null) return
        val st = _uiState.value
        val cx = canvasWidth / 2f
        val cy = canvasHeight / 2f
        val rad = Math.toRadians(-st.viewportRotation.toDouble())
        val cos = kotlin.math.cos(rad)
        val sin = kotlin.math.sin(rad)
        val ux = (centerScreen.x - st.viewportOffset.x) / st.viewportZoom
        val uy = (centerScreen.y - st.viewportOffset.y) / st.viewportZoom
        val wx = (ux * cos - uy * sin).toFloat()
        val wy = (ux * sin + uy * cos).toFloat()
        val rx = (radiusXScreen / st.viewportZoom).coerceAtLeast(1f)
        val ry = (radiusYScreen / st.viewportZoom).coerceAtLeast(1f)
        pushHistory()
        val count = _uiState.value.layers.count { it.shapes.isNotEmpty() }
        val newLayer = Layer(
            id = UUID.randomUUID().toString(),
            name = "Ellipse ${count + 1}",
            shapes = listOf(
                com.hereliesaz.graffitixr.common.model.VectorShape(
                    kind = com.hereliesaz.graffitixr.common.model.ShapeKind.ELLIPSE,
                    width = rx * 2f,
                    height = ry * 2f,
                    fillArgb = 0x00000000L,
                    strokeArgb = st.activeColor.toArgb().toLong() and 0xFFFFFFFFL,
                    strokeWidth = st.brushSize.coerceAtLeast(1f),
                )
            ),
            offset = Offset(wx - cx, wy - cy),
        )
        dispatch(EditorIntent.AddLayer(newLayer))
        // Keep the pen active for the next stroke, matching onCommitPenPath.
        dispatch(EditorIntent.SetActiveTool(Tool.PEN))
        opEmitter.emit(Op.LayerAdd(newLayer))
        saveProject()
    }

    // ── Gallery (Procreate's home surface: browse, open, create, delete projects) ───────────

    val projects: StateFlow<List<GraffitiProject>> =
        projectRepository.projects
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun openProject(id: String) {
        if (id == _uiState.value.projectId) return
        viewModelScope.launch(dispatchers.io) { projectRepository.loadProject(id) }
    }

    fun createNewProject() {
        viewModelScope.launch(dispatchers.io) {
            val n = projectRepository.getProjects().size + 1
            projectRepository.createProject("Untitled $n")
        }
    }

    // ── Open screen (every project the device can offer, wherever it lives) ─────────────────

    private val _openScreenState = MutableStateFlow(OpenScreenState())
    val openScreenState: StateFlow<OpenScreenState> = _openScreenState.asStateFlow()

    /**
     * Rebuilds the Open screen: the projects already in the app, plus every `.fux` the device will
     * admit to holding.
     *
     * Scanning storage is slow enough to see, so the in-app projects are published first and the
     * discovered files land when they land — the screen is usable immediately rather than blank
     * until the slowest part finishes.
     */
    fun refreshOpenScreen() {
        viewModelScope.launch(dispatchers.io) {
            val inApp = runCatching { projectRepository.getProjects() }.getOrDefault(emptyList())
            _openScreenState.value = OpenScreenState(isScanning = true, inApp = inApp)
            val files = runCatching { projectFileScanner.scan() }.getOrDefault(emptyList())
            _openScreenState.value = OpenScreenState(isScanning = false, inApp = inApp, files = files)
        }
    }

    fun deleteProjectById(id: String) {
        viewModelScope.launch(dispatchers.io) {
            projectRepository.deleteProject(id)
            // Deleting the open project leaves the editor pointing at nothing: fall back to the
            // most recent survivor, or a fresh project — the same policy as the boot bootstrap.
            if (_uiState.value.projectId == id) {
                val mostRecent = projectRepository.getProjects().maxByOrNull { it.lastModified }
                if (mostRecent != null) projectRepository.loadProject(mostRecent.id)
                else projectRepository.createProject("Untitled")
            }
        }
    }

    private fun clearTransientStrokeState() {
        strokeWorkingBitmap = null
        strokeWorkingCanvas = null
        strokePaint = null
        strokePrevBitmapPoint = null
        strokeDynamics = null
        strokeSymmetry = SymmetryMode.NONE
        strokeAlphaLock = false
        strokeSelection = null
        lastSampleMs = 0L
        resetStrokePoints()
        strokeLayerId = null

        liquifyJob?.cancel()
        liquifyJob = null
        liquifyOriginalBitmap = null

        stampLiveBitmap = null
        stampLiveCanvas = null
        stampStampedCount = 0
        stampBrushForStroke = null
        stampShapeForStroke = null
        stampMappedPoints.clear()
    }

    private fun buildStrokePaint(tool: Tool, argbColor: Int, brushSize: Float, feathering: Float, alphaLock: Boolean = false): Paint =
        Paint().apply {
            strokeWidth = brushSize
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            when (tool) {
                Tool.BRUSH -> {
                    color = argbColor
                    // Alpha Lock: paint only where the layer already has alpha, so strokes
                    // recolour existing content without extending its silhouette.
                    if (alphaLock) xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
                    if (feathering > 0f) maskFilter = BlurMaskFilter(brushSize * feathering * 0.5f, BlurMaskFilter.Blur.NORMAL)
                }
                Tool.ERASER -> {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    if (feathering > 0f) maskFilter = BlurMaskFilter(brushSize * feathering * 0.5f, BlurMaskFilter.Blur.NORMAL)
                }
                Tool.BLUR -> {
                    // No live paint: a plain Paint can't blur the underlying pixels (the old code
                    // painted translucent BLACK — Paint's default color). The real region blur is
                    // applied on finger-up in onStrokeEnd via ImageProcessor.applyToolToBitmap.
                    color = android.graphics.Color.TRANSPARENT
                    alpha = 0
                }
                Tool.BURN -> {
                    color = android.graphics.Color.BLACK
                    alpha = (255 * 0.3f).toInt().coerceIn(0, 255)
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
                }
                Tool.DODGE -> {
                    color = android.graphics.Color.WHITE
                    alpha = (255 * 0.3f).toInt().coerceIn(0, 255)
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
                }
                Tool.HEAL -> {
                    color = argbColor
                    alpha = 128
                }
                else -> {}
            }
        }

    override fun onColorClicked() {
        dispatch(EditorIntent.ShowColorPicker)
    }

    override fun setBrushSize(size: Float) {
        dispatch(EditorIntent.SetBrushSize(size))
        // Procreate shows the true brush diameter at canvas centre while the size slider moves.
        showBrushHud()
    }

    fun setBrushFeathering(amount: Float) {
        dispatch(EditorIntent.SetBrushFeathering(amount))
    }

    /** Flow (0..1) for the active azphalt stamp brush — per-dab build-up. No-op for the round brush. */
    fun setBrushFlow(amount: Float) {
        dispatch(EditorIntent.SetBrushFlow(amount))
    }

    /**
     * Installed azphalt brush extensions available to paint with (id + display name), reactive so a
     * freshly-installed brush shows up in the picker without a manual refresh.
     */
    val installedBrushes: StateFlow<List<Pair<String, String>>> =
        extensionRepository.installed
            .map { extensionRepository.installedBrushes().map { ext -> ext.id to ext.manifest.name } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The user's saved colour swatches (the colour picker's Palettes tab), reactive so a swatch
     * saved in one place appears everywhere the palette is shown.
     */
    val savedPalette: StateFlow<List<androidx.compose.ui.graphics.Color>> =
        settingsRepository.savedPalette
            .map { argbs -> argbs.map { androidx.compose.ui.graphics.Color(it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Adds [color] to the saved palette. Re-saving a colour already in it is a no-op. */
    fun onSavePaletteColor(color: androidx.compose.ui.graphics.Color) {
        viewModelScope.launch {
            val current = settingsRepository.savedPalette.first()
            val next = com.hereliesaz.graffitixr.common.util.PaletteCodec.add(current, color.toArgb())
            if (next !== current) settingsRepository.setSavedPalette(next)
        }
    }

    /** Removes every swatch matching [color] from the saved palette. */
    fun onRemovePaletteColor(color: androidx.compose.ui.graphics.Color) {
        viewModelScope.launch {
            val argb = color.toArgb()
            val current = settingsRepository.savedPalette.first()
            val next = current.filterNot { it == argb }
            if (next.size != current.size) settingsRepository.setSavedPalette(next)
        }
    }

    /**
     * Every installed azphalt extension, of any kind — unlike [installedExtensions] (which filters to
     * the code/mixed ones the run panel cares about), this backs the "manage installed" list in
     * [com.hereliesaz.graffitixr.feature.editor.StoreWindow], which offers to remove any of them.
     */
    val allInstalledExtensions: StateFlow<List<com.hereliesaz.graffitixr.data.azphalt.InstalledExtension>> =
        extensionRepository.installed

    /** Uninstall a previously-installed extension by [id]. */
    fun uninstallExtension(id: String) {
        viewModelScope.launch(dispatchers.io) {
            extensionRepository.uninstall(id)
        }
    }

    /**
     * Install an azphalt `.azp` package from a [uri] — a `content://` from the file picker, or one
     * handed off by a store app (spec/store-app.md; see MainActivity's browse-for-result launcher).
     * Opens the stream, verifies + unpacks off the main thread, and toasts the outcome; the installed
     * flow ([installedBrushes]) updates itself so a new brush appears in the picker.
     */
    fun installExtensionFromUri(uri: Uri) {
        viewModelScope.launch(dispatchers.io) {
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Couldn't open that file")
                val installed = input.use { extensionRepository.installFromStream(it, System.currentTimeMillis()) }
                withContext(dispatchers.main) {
                    Toast.makeText(context, "Installed ${installed.manifest.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // never swallow cancellation — let the coroutine unwind cooperatively
            } catch (e: Exception) {
                withContext(dispatchers.main) {
                    Toast.makeText(context, "Couldn't install: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ── 3D model viewport ────────────────────────────────────────────────────────────────────

    private val _modelState = MutableStateFlow(com.hereliesaz.graffitixr.feature.editor.threed.ModelUiState())
    val modelState: StateFlow<com.hereliesaz.graffitixr.feature.editor.threed.ModelUiState> =
        _modelState.asStateFlow()

    /**
     * Loads an OBJ model from [uri]. Parsing runs off the main thread — a scanned mesh is megabytes
     * of text and would jank the UI if read inline.
     *
     * The file is copied into the project rather than referenced where the user picked it. A
     * document-picker URI is a grant that expires, so a project reopened next week would find its
     * own model gone; a copy also means the model rides along inside a `.fux`.
     */
    fun loadModel(uri: Uri, displayName: String? = null) {
        _modelState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(dispatchers.io) {
            val result = runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes()
                } ?: throw java.io.IOException("Couldn't open that file")
                val mesh = com.hereliesaz.graffitixr.common.mesh.ObjParser.parse(bytes.decodeToString())
                mesh to bytes
            }
            result
                .onSuccess { (mesh, bytes) ->
                    val name = displayName ?: "Model"
                    // Persist before publishing, so the state the UI shows is one that survives a
                    // reopen — the alternative is a model that looks loaded and isn't saved.
                    val paths = runCatching { persistModel(bytes, name) }.getOrNull()
                    val texture = PaintableTexture(
                        PaintableTexture.DEFAULT_SIZE, PaintableTexture.DEFAULT_SIZE,
                    )
                    modelTexturePath = paths?.second
                    withContext(dispatchers.main) {
                        _modelState.update {
                            it.copy(
                                mesh = mesh, name = name, texture = texture,
                                isLoading = false, error = null,
                            )
                        }
                    }
                }
                .onFailure { e ->
                    withContext(dispatchers.main) {
                        _modelState.update {
                            it.copy(isLoading = false, error = e.message ?: "Couldn't read that model")
                        }
                    }
                }
        }
    }

    /**
     * Adds the model as it currently looks — angle, paint and all — to the document as a layer.
     *
     * A snapshot rather than a live 3D layer: the document is a flat image, and everything that
     * acts on a layer (transform, blend, adjustments, export) already works on pixels. Turning the
     * model and taking another is cheap, so nothing is lost by not keeping it live.
     */
    fun addModelViewToCanvas(bitmap: Bitmap) {
        viewModelScope.launch(dispatchers.io) {
            ensureProjectId()
            val name = _modelState.value.name?.substringBeforeLast('.') ?: "Model"
            importSingleBitmap(bitmap, name)
            withContext(dispatchers.main) { toast("Added “$name” to the canvas") }
        }
    }

    /** Where the current model's texture is written. Null when no model is loaded. */
    private var modelTexturePath: String? = null

    /** Copies the model into the project and records it on the manifest. Returns (objPath, texturePath). */
    private suspend fun persistModel(objBytes: ByteArray, name: String): Pair<String, String> {
        val projectId = ensureProjectId()
        val objPath = projectRepository.saveArtifact(projectId, MODEL_OBJ_FILE, objBytes)
        val texturePath = File(File(objPath).parentFile, MODEL_TEXTURE_FILE).absolutePath
        projectRepository.updateProject { current ->
            if (current.id == projectId) {
                current.copy(
                    modelPath = objPath,
                    modelName = name,
                    // Recorded even before anything is painted: the path is where the texture
                    // *will* be, and a reopen that finds no file there simply starts unpainted.
                    modelTexturePath = texturePath,
                )
            } else {
                current
            }
        }
        return objPath to texturePath
    }

    /**
     * Called when paint lands on the model. Writes the texture on the same debounce as layer
     * bitmaps, and through the same pending-write map — so leaving the app flushes model paint
     * exactly as it flushes a stroke on the flat canvas.
     */
    fun onModelPaintChanged() {
        val texture = _modelState.value.texture ?: return
        val path = modelTexturePath ?: return
        pendingWrites[MODEL_TEXTURE_KEY] = path to texture.bitmap
        pendingSaveJobs.remove(MODEL_TEXTURE_KEY)?.cancel()
        val job = viewModelScope.launch(dispatchers.io) {
            kotlinx.coroutines.delay(1500)
            writeLayerBitmap(MODEL_TEXTURE_KEY, path, texture.bitmap)
            saveProject()
            pendingSaveJobs.remove(MODEL_TEXTURE_KEY, coroutineContext[kotlinx.coroutines.Job])
        }
        pendingSaveJobs[MODEL_TEXTURE_KEY] = job
    }

    /**
     * Brings back the model a project was saved with, paint and all. Silent when the project has
     * none, which is most of them.
     */
    private fun restoreModel(project: GraffitiProject) {
        val objPath = project.modelPath
        if (objPath == null) {
            modelTexturePath = null
            _modelState.value = com.hereliesaz.graffitixr.feature.editor.threed.ModelUiState()
            return
        }
        _modelState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(dispatchers.io) {
            val restored = runCatching {
                val file = File(objPath)
                if (!file.exists()) throw java.io.FileNotFoundException("model missing")
                val mesh = com.hereliesaz.graffitixr.common.mesh.ObjParser.parse(file.readText())
                val texture = PaintableTexture(
                    PaintableTexture.DEFAULT_SIZE, PaintableTexture.DEFAULT_SIZE,
                )
                project.modelTexturePath?.let { texturePath ->
                    val saved = File(texturePath)
                    if (saved.exists()) {
                        BitmapFactory.decodeFile(texturePath)?.let { texture.restore(it) }
                    }
                }
                mesh to texture
            }
            modelTexturePath = project.modelTexturePath
            withContext(dispatchers.main) {
                restored
                    .onSuccess { (mesh, texture) ->
                        _modelState.value = com.hereliesaz.graffitixr.feature.editor.threed.ModelUiState(
                            mesh = mesh, name = project.modelName, texture = texture,
                        )
                    }
                    .onFailure {
                        // A project whose model file is gone shouldn't look broken — it just has
                        // no model any more, which is the same as never having had one.
                        android.util.Log.w("EditorViewModel", "Couldn't restore model for ${project.id}", it)
                        _modelState.value = com.hereliesaz.graffitixr.feature.editor.threed.ModelUiState()
                    }
            }
        }
    }

    // ── Layout (constraints + auto-layout) ───────────────────────────────────────────────────

    /** Sets how the active layer reacts when its parent frame resizes. */
    fun onSetConstraints(constraints: com.hereliesaz.graffitixr.common.model.Constraints) {
        val layerId = _uiState.value.activeLayerId ?: return
        pushHistory()
        dispatch(EditorIntent.SetLayerConstraints(layerId, constraints))
        saveProject()
    }

    /** Sets the active layer's auto-layout, immediately re-laying out its children. */
    fun onSetAutoLayout(layout: com.hereliesaz.graffitixr.common.model.AutoLayout) {
        val frameId = _uiState.value.activeLayerId ?: return
        if (_uiState.value.layers.none { it.parentId == frameId }) {
            Toast.makeText(context, "That layer has no children to lay out", Toast.LENGTH_SHORT).show()
            return
        }
        pushHistory()
        dispatch(EditorIntent.SetAutoLayout(frameId, layout))
        saveProject()
    }

    /** Resizes the active frame to exactly fit its laid-out children (Figma's "hug contents"). */
    fun onHugContents() {
        val frameId = _uiState.value.activeLayerId ?: return
        val size = com.hereliesaz.graffitixr.common.model.LayoutOps
            .hugContentsSize(_uiState.value.layers, frameId)
        if (size == null) {
            Toast.makeText(context, "Turn on auto-layout first", Toast.LENGTH_SHORT).show()
            return
        }
        pushHistory()
        _uiState.update { state ->
            state.copy(
                layers = state.layers.map {
                    if (it.id == frameId) it.copy(layoutWidth = size.first, layoutHeight = size.second) else it
                },
            )
        }
        dispatch(EditorIntent.RelayoutFrame(frameId))
        saveProject()
    }

    // ── Shared styles ────────────────────────────────────────────────────────────────────────

    /** Creates a colour token from the active shape's fill and links that shape to it. */
    fun onCreateColorStyleFromActive(name: String) {
        val layerId = _uiState.value.activeLayerId ?: return
        val shape = _uiState.value.layers.firstOrNull { it.id == layerId }?.shapes?.firstOrNull()
        if (shape == null) {
            Toast.makeText(context, "Select a shape layer first", Toast.LENGTH_SHORT).show()
            return
        }
        val style = com.hereliesaz.graffitixr.common.model.StyleOps.colorStyleFromShape(shape, name)
        pushHistory()
        dispatch(EditorIntent.AddColorStyle(style))
        dispatch(EditorIntent.SetShapeFillStyle(layerId, style.id))
        saveProject()
    }

    /** Creates a text token from the active text layer and links that layer to it. */
    fun onCreateTextStyleFromActive(name: String) {
        val layerId = _uiState.value.activeLayerId ?: return
        val params = _uiState.value.layers.firstOrNull { it.id == layerId }?.textParams
        if (params == null) {
            Toast.makeText(context, "Select a text layer first", Toast.LENGTH_SHORT).show()
            return
        }
        val style = com.hereliesaz.graffitixr.common.model.StyleOps.textStyleFromParams(params, name)
        pushHistory()
        dispatch(EditorIntent.AddTextStyle(style))
        dispatch(EditorIntent.SetLayerTextStyle(layerId, style.id))
        saveProject()
    }

    /** Re-points the active layer at an existing token (or unlinks it with null). */
    fun onApplyColorStyle(styleId: String?) {
        val layerId = _uiState.value.activeLayerId ?: return
        pushHistory()
        dispatch(EditorIntent.SetShapeFillStyle(layerId, styleId))
        saveProject()
    }

    fun onApplyTextStyle(styleId: String?) {
        val layerId = _uiState.value.activeLayerId ?: return
        pushHistory()
        dispatch(EditorIntent.SetLayerTextStyle(layerId, styleId))
        saveProject()
    }

    /** Repoints a colour token at the current colour — every shape using it follows. */
    fun onUpdateColorStyleToActiveColor(styleId: String) {
        val existing = _uiState.value.colorStyles.firstOrNull { it.id == styleId } ?: return
        val argb = _uiState.value.activeColor.toArgb().toLong() and 0xFFFFFFFFL
        pushHistory()
        dispatch(EditorIntent.UpdateColorStyle(existing.copy(argb = argb)))
        saveProject()
    }

    fun onDeleteColorStyle(styleId: String) {
        pushHistory()
        dispatch(EditorIntent.DeleteColorStyle(styleId))
        saveProject()
    }

    fun onDeleteTextStyle(styleId: String) {
        pushHistory()
        dispatch(EditorIntent.DeleteTextStyle(styleId))
        saveProject()
    }

    // ── Components and instances ─────────────────────────────────────────────────────────────
    //
    // Like node editing, these are undoable and persisted but NOT emitted to co-op peers — no Op
    // carries the component link, so a LayerPropsChange would announce a change it can't transmit.

    /** Promotes the active layer to a main component. */
    fun onMakeComponent() {
        val layerId = _uiState.value.activeLayerId ?: return
        val layer = _uiState.value.layers.firstOrNull { it.id == layerId } ?: return
        if (layer.instanceOf != null) {
            Toast.makeText(context, "An instance can't become a component", Toast.LENGTH_SHORT).show()
            return
        }
        if (layer.componentId != null) return
        pushHistory()
        dispatch(EditorIntent.MakeComponent(layerId, UUID.randomUUID().toString()))
        saveProject()
        Toast.makeText(context, "\"${layer.name}\" is now a component", Toast.LENGTH_SHORT).show()
    }

    /** Places a new instance of [componentId] on top of the stack, ready to be dragged into place. */
    fun onPlaceInstance(componentId: String) {
        val instance = com.hereliesaz.graffitixr.common.model.ComponentOps
            .buildInstance(_uiState.value.layers, componentId) ?: return
        pushHistory()
        // The instance shares the main's bitmap reference, so it needs its own stroke-store entries
        // or painting on it would be replayed against the main's base.
        instance.bitmap?.let { bmp ->
            layerStore.putBase(instance.id, bmp.copy(Bitmap.Config.ARGB_8888, false))
            layerStore.initStrokes(instance.id)
        }
        dispatch(EditorIntent.PlaceInstance(instance))
        saveProject()
    }

    /** Breaks the active layer's link to its main, keeping what it currently shows. */
    fun onDetachInstance() {
        val layerId = _uiState.value.activeLayerId ?: return
        if (_uiState.value.layers.firstOrNull { it.id == layerId }?.instanceOf == null) return
        pushHistory()
        dispatch(EditorIntent.DetachInstance(layerId))
        saveProject()
    }

    /** Demotes the active layer from being a component, detaching its instances. */
    fun onReleaseComponent() {
        val layerId = _uiState.value.activeLayerId ?: return
        val componentId = _uiState.value.layers.firstOrNull { it.id == layerId }?.componentId ?: return
        pushHistory()
        dispatch(EditorIntent.ReleaseComponent(componentId))
        saveProject()
    }

    // ── Vector node editing ──────────────────────────────────────────────────────────────────
    //
    // Node edits are undoable and persisted, but NOT emitted to co-op peers: no Op carries vector
    // geometry (LayerProps has no `shapes`), so emitting LayerPropsChange would announce a change
    // it cannot actually transmit. A geometry-carrying op is the honest fix, and is follow-up work.

    /**
     * Enters node-edit mode on [layerId], or leaves it with null. Only a layer whose single shape
     * is a PATH has nodes to edit; anything else silently declines rather than entering a mode with
     * nothing to show.
     */
    fun onSetPathEditLayer(layerId: String?) {
        if (layerId == null) {
            dispatch(EditorIntent.SetPathEditLayer(null))
            return
        }
        val shape = pathShapeOf(layerId)
        if (shape == null) {
            Toast.makeText(context, "That layer has no editable path", Toast.LENGTH_SHORT).show()
            return
        }
        dispatch(EditorIntent.SetPathEditLayer(layerId))
        setActiveTool(Tool.NONE)
    }

    /** Toggles node editing for the active layer — the rail's single "Edit Path" action. */
    fun onToggleActivePathEdit() {
        val current = _uiState.value.pathEditLayerId
        if (current != null) onSetPathEditLayer(null) else onSetPathEditLayer(_uiState.value.activeLayerId)
    }

    fun onSelectPathNode(index: Int?) = dispatch(EditorIntent.SelectPathNode(index))

    /**
     * Brackets a node/handle drag so the whole drag is one undo step rather than one per frame —
     * the same pattern the layer-opacity slider uses (see [onLayerEditStart]).
     */
    fun onPathEditStart() = pushHistory()

    fun onPathEditEnd() = saveProject()

    fun onMovePathNode(index: Int, dx: Float, dy: Float) =
        editPath { com.hereliesaz.graffitixr.common.model.PathEditing.moveNode(it, index, dx, dy) }

    fun onMovePathHandle(index: Int, outgoing: Boolean, x: Float, y: Float, mirror: Boolean) =
        editPath { com.hereliesaz.graffitixr.common.model.PathEditing.moveHandle(it, index, outgoing, x, y, mirror) }

    /** Each of these is a discrete action, so it brackets its own history entry and saves. */
    fun onInsertPathNode(segmentIndex: Int, t: Float) = discretePathEdit {
        com.hereliesaz.graffitixr.common.model.PathEditing.insertNode(it, segmentIndex, t)
    }

    fun onDeletePathNode(index: Int) {
        discretePathEdit { com.hereliesaz.graffitixr.common.model.PathEditing.deleteNode(it, index) }
        // The deleted node's index no longer refers to what the user selected.
        dispatch(EditorIntent.SelectPathNode(null))
    }

    fun onMakePathNodeCorner(index: Int) = discretePathEdit {
        com.hereliesaz.graffitixr.common.model.PathEditing.makeCorner(it, index)
    }

    fun onMakePathNodeSmooth(index: Int) = discretePathEdit {
        com.hereliesaz.graffitixr.common.model.PathEditing.makeSmooth(it, index)
    }

    fun onTogglePathClosed() = discretePathEdit {
        com.hereliesaz.graffitixr.common.model.PathEditing.toggleClosed(it)
    }

    /** The single PATH shape on [layerId], or null when the layer isn't an editable path. */
    private fun pathShapeOf(layerId: String): com.hereliesaz.graffitixr.common.model.VectorShape? =
        _uiState.value.layers.firstOrNull { it.id == layerId }
            ?.shapes?.singleOrNull()
            ?.takeIf { it.kind == com.hereliesaz.graffitixr.common.model.ShapeKind.PATH }

    /** Applies [transform] to the edited path. Used by drags, which bracket their own history. */
    private fun editPath(transform: (com.hereliesaz.graffitixr.common.model.VectorShape) -> com.hereliesaz.graffitixr.common.model.VectorShape) {
        val layerId = _uiState.value.pathEditLayerId ?: return
        val shape = pathShapeOf(layerId) ?: return
        dispatch(EditorIntent.SetPathShape(layerId, transform(shape)))
    }

    private fun discretePathEdit(transform: (com.hereliesaz.graffitixr.common.model.VectorShape) -> com.hereliesaz.graffitixr.common.model.VectorShape) {
        val layerId = _uiState.value.pathEditLayerId ?: return
        val shape = pathShapeOf(layerId) ?: return
        val next = transform(shape)
        if (next == shape) return
        pushHistory()
        dispatch(EditorIntent.SetPathShape(layerId, next))
        saveProject()
    }

    // ── Figma import ─────────────────────────────────────────────────────────────────────────

    /**
     * Figma import panel state: query/loading/error/results, the same load/error/results rhythm as
     * any other async browse-and-pick panel in the editor.
     */
    data class FigmaUiState(
        val isConnected: Boolean = false,
        val fileInput: String = "",
        val fileName: String? = null,
        val fileKey: String? = null,
        val frames: List<com.hereliesaz.graffitixr.data.figma.FigmaFrame> = emptyList(),
        val selectedIds: Set<String> = emptySet(),
        val isLoading: Boolean = false,
        val error: String? = null,
    )

    private val _figmaState = MutableStateFlow(FigmaUiState())
    val figmaState: StateFlow<FigmaUiState> = _figmaState.asStateFlow()

    init {
        viewModelScope.launch(dispatchers.main) {
            figmaRepository.isAuthenticated.collect { connected ->
                _figmaState.update { it.copy(isConnected = connected) }
            }
        }
    }

    fun onFigmaFileInputChanged(value: String) = _figmaState.update { it.copy(fileInput = value) }

    /** Validates and stores a Figma personal access token. */
    fun connectFigma(token: String) {
        if (token.isBlank()) return
        _figmaState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(dispatchers.main) {
            figmaRepository.connect(token)
                .onSuccess { user ->
                    _figmaState.update { it.copy(isLoading = false, error = null) }
                    Toast.makeText(context, "Connected to Figma as ${user.handle}", Toast.LENGTH_SHORT).show()
                }
                .onFailure { e ->
                    _figmaState.update { it.copy(isLoading = false, error = e.message ?: "Couldn't verify that token") }
                }
        }
    }

    fun disconnectFigma() {
        figmaRepository.disconnect()
        _figmaState.update { FigmaUiState(isConnected = false) }
    }

    /** Resolves the pasted link/key and lists that file's importable frames. */
    fun loadFigmaFile() {
        val input = _figmaState.value.fileInput
        if (input.isBlank()) return
        _figmaState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(dispatchers.main) {
            figmaRepository.loadFile(input)
                .onSuccess { contents ->
                    _figmaState.update {
                        it.copy(
                            isLoading = false,
                            error = if (contents.frames.isEmpty()) "No importable frames in that file" else null,
                            fileKey = contents.fileKey,
                            fileName = contents.name,
                            frames = contents.frames,
                            selectedIds = emptySet(),
                        )
                    }
                }
                .onFailure { e ->
                    _figmaState.update { it.copy(isLoading = false, error = e.message ?: "Couldn't load that file") }
                }
        }
    }

    fun toggleFigmaFrame(id: String) = _figmaState.update {
        it.copy(selectedIds = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id)
    }

    /**
     * Renders the selected frames server-side, downloads each PNG, and adds it as a layer. Frames
     * import newest-last so the picker's order is the stacking order.
     */
    fun importFigmaFrames() {
        val state = _figmaState.value
        val fileKey = state.fileKey ?: return
        val ids = state.frames.map { it.id }.filter { it in state.selectedIds }
        if (ids.isEmpty()) return
        _figmaState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(dispatchers.default) {
            figmaRepository.renderFrames(fileKey, ids)
                .onSuccess { rendered ->
                    var imported = 0
                    for (id in ids) {
                        val bytes = rendered[id] ?: continue
                        val bitmap = runCatching { decodeBoundedBitmap(bytes, 4096) }.getOrNull() ?: continue
                        val name = state.frames.firstOrNull { it.id == id }?.name ?: "Figma frame"
                        importSingleBitmap(bitmap, name)
                        imported++
                    }
                    withContext(dispatchers.main) {
                        _figmaState.update { it.copy(isLoading = false, selectedIds = emptySet()) }
                        val missed = ids.size - imported
                        val message = when {
                            imported == 0 -> "Couldn't import any of those frames"
                            missed > 0 -> "Imported $imported of ${ids.size} frames"
                            else -> "Imported $imported frame${if (imported == 1) "" else "s"}"
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
                .onFailure { e ->
                    withContext(dispatchers.main) {
                        _figmaState.update { it.copy(isLoading = false, error = e.message ?: "Import failed") }
                    }
                }
        }
    }

    /**
     * Writes a bundle the Graffux companion Figma plugin can open, preserving each layer separately
     * with its name, opacity, blend mode, and visibility rather than flattening to one PNG.
     *
     * Every layer is composited ALONE at document size with its opacity, blend, and clip neutralised.
     * That bakes the layer's geometry into the image — so the plugin can place every layer full-bleed
     * at the origin and reproduce the composition exactly — while leaving opacity and blend as live
     * Figma properties instead of burning them into pixels. Compositing them here as well would apply
     * both, so the layer copy passed to the compositor deliberately has them reset.
     */
    fun exportForFigma() {
        val state = _uiState.value
        val layers = state.layers
        if (layers.isEmpty()) {
            Toast.makeText(context, "Nothing to export", Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch(dispatchers.default) {
            dispatch(EditorIntent.SetLoading(true))
            try {
                val metrics = context.resources.displayMetrics
                val docW = state.documentWidth.coerceAtLeast(1)
                val docH = state.documentHeight.coerceAtLeast(1)
                val bundleLayers = layers.map { layer ->
                    val flat = layer.copy(
                        opacity = 1f,
                        blendMode = androidx.compose.ui.graphics.BlendMode.SrcOver,
                        clipToLayerBelow = false,
                        isVisible = true,
                    )
                    val bitmap = exportManager.compositeToDocument(
                        listOf(flat), metrics.widthPixels, metrics.heightPixels, docW, docH,
                        backgroundColor = android.graphics.Color.TRANSPARENT,
                    )
                    val png = ImageUtils.bitmapToByteArray(bitmap)
                    bitmap.recycle()
                    com.hereliesaz.graffitixr.data.figma.FigmaBundleLayer(
                        name = layer.name.ifBlank { "Layer" },
                        pngBase64 = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP),
                        opacity = layer.opacity,
                        blendMode = com.hereliesaz.graffitixr.data.figma.figmaBlendMode(layer.blendMode),
                        visible = layer.isVisible,
                    )
                }
                val projectName = projectRepository.currentProject.value?.name ?: "Graffux"
                val bundle = com.hereliesaz.graffitixr.data.figma.FigmaBundle(
                    name = projectName,
                    documentWidth = docW,
                    documentHeight = docH,
                    layers = bundleLayers,
                )
                val json = com.hereliesaz.graffitixr.data.figma.FigmaBundle.encode(bundle)

                val dir = File(context.cacheDir, "figma").apply { mkdirs() }
                val safeName = projectName.replace(Regex("[^A-Za-z0-9_-]"), "_")
                val file = File(dir, "$safeName${com.hereliesaz.graffitixr.data.figma.FigmaBundle.FILE_SUFFIX}")
                file.writeText(json)

                withContext(dispatchers.main) { dispatch(EditorIntent.SetLoading(false)) }
                copyToDownloads(file, "application/json", "Figma bundle saved to Downloads", "Figma export failed")
            } catch (e: Exception) {
                withContext(dispatchers.main) {
                    dispatch(EditorIntent.SetLoading(false))
                    Toast.makeText(context, "Figma export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ── Brush Studio (user-authored brushes) ─────────────────────────────────────────────────

    /** Brushes the user built in Brush Studio, shown in the rail alongside installed ones. */
    val customBrushes: StateFlow<List<com.hereliesaz.graffitixr.data.brush.CustomBrush>> =
        customBrushRepository.brushes
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Selects a saved custom brush. Custom brushes are param-only, so there's no tip image to load. */
    fun selectCustomBrush(id: String) {
        val brush = customBrushRepository.load(id)
        if (brush == null) {
            Toast.makeText(context, "That brush is no longer available", Toast.LENGTH_SHORT).show()
            return
        }
        activeStampBrush = brush
        activeStampShape = null   // null → StampBrushRenderer draws its generated round tip
        dispatch(EditorIntent.SetActiveBrush(brush.name))
        setActiveTool(Tool.BRUSH)
    }

    /**
     * Opens Brush Studio. With no [id] it starts from the brush currently in hand (or the built-in
     * round defaults), so "tweak what I'm painting with" is one tap rather than a rebuild from zero.
     */
    fun onOpenBrushStudio(id: String? = null) {
        val existing = id?.let { customBrushRepository.load(it) }
        val seed = existing
            ?: activeStampBrush?.copy(name = "${activeStampBrush?.name} copy")
            ?: com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush(name = "Custom Brush")
        dispatch(EditorIntent.SetBrushStudioDraft(seed, editingId = id))
        applyBrushDraft(seed)
    }

    fun onCloseBrushStudio() = dispatch(EditorIntent.SetBrushStudioDraft(null))

    /**
     * Updates the draft and immediately makes it the live brush, so the next test stroke paints with
     * the values on screen — the whole point of a brush editor is seeing the change, not imagining it.
     */
    fun onEditBrushDraft(edit: (com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush) -> com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush) {
        val current = _uiState.value.brushStudioDraft ?: return
        val next = edit(current).sanitized()
        dispatch(EditorIntent.SetBrushStudioDraft(next, editingId = _uiState.value.brushStudioEditingId))
        applyBrushDraft(next)
    }

    private fun applyBrushDraft(brush: com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush) {
        activeStampBrush = brush
        // A draft brush is params-only; drop any tip image the previously-selected brush had, or the
        // new settings would render against the old brush's shape.
        activeStampShape = null
        dispatch(EditorIntent.SetActiveBrush(brush.name))
    }

    /** Saves the draft, overwriting the brush it was opened from or creating a new one. */
    fun onSaveBrushDraft() {
        val draft = _uiState.value.brushStudioDraft ?: return
        val id = _uiState.value.brushStudioEditingId ?: UUID.randomUUID().toString()
        viewModelScope.launch(dispatchers.io) {
            val ok = customBrushRepository.save(id, draft)
            withContext(dispatchers.main) {
                if (ok) {
                    // Keep the studio open but bound to the saved id, so further tweaks update this
                    // brush rather than silently creating a second copy on the next Save.
                    dispatch(EditorIntent.SetBrushStudioDraft(draft, editingId = id))
                    Toast.makeText(context, "Saved \"${draft.name}\"", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Couldn't save that brush", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun onDeleteCustomBrush(id: String) {
        viewModelScope.launch(dispatchers.io) {
            customBrushRepository.delete(id)
            withContext(dispatchers.main) {
                if (_uiState.value.brushStudioEditingId == id) {
                    dispatch(EditorIntent.SetBrushStudioDraft(null))
                }
            }
        }
    }

    /**
     * Select an installed azphalt stamp brush by extension [id], or pass null to return to the built-in
     * round brush. The active-brush name drives the UI and switches the size control's second axis to flow.
     */
    fun selectBrushExtension(id: String?) {
        if (id == null) {
            activeStampBrush = null
            activeStampShape = null
            dispatch(EditorIntent.SetActiveBrush(null))
            return
        }
        // loadBrush + the tip-image decode both read from disk — do them off the main thread.
        viewModelScope.launch(dispatchers.io) {
            val brush = extensionRepository.loadBrush(id)
            val shape = brush?.shapePath
                ?.let { extensionRepository.assetFilePath(id, it) }
                ?.let { path -> runCatching { decodeBoundedBitmap(java.io.File(path).readBytes(), 1024) }.getOrNull() }
            withContext(dispatchers.main) {
                if (brush == null) {
                    Toast.makeText(context, "Couldn't load that brush — it may be missing or corrupt", Toast.LENGTH_SHORT).show()
                } else {
                    activeStampBrush = brush
                    activeStampShape = shape   // null → StampBrushRenderer draws a generated round tip
                    dispatch(EditorIntent.SetActiveBrush(brush.name))
                    setActiveTool(Tool.BRUSH)
                }
            }
        }
    }

    private var colorEditJob: kotlinx.coroutines.Job? = null

    override fun setActiveColor(color: Color) {
        dispatch(EditorIntent.SetActiveColor(color))
        val st = _uiState.value
        val active = st.layers.find { it.id == st.activeLayerId }
        if (active != null && active.shapes.isNotEmpty()) {
            val argb = color.toArgb().toLong() and 0xFFFFFFFFL
            val recoloured = active.shapes.map { s ->
                if (s.kind == com.hereliesaz.graffitixr.common.model.ShapeKind.LINE) s.copy(strokeArgb = argb)
                else s.copy(fillArgb = argb)
            }
            if (colorEditJob == null) {
                onLayerEditStart()
            }
            colorEditJob?.cancel()
            colorEditJob = viewModelScope.launch(dispatchers.main) {
                kotlinx.coroutines.delay(100)
                colorEditJob = null
                onLayerEditEnd()
            }
            dispatch(EditorIntent.SetLayerShapes(active.id, recoloured))
        }
    }

    override fun adjustColorLightness(delta: Float) {
        adjustColorHSV(lightnessDelta = delta, saturationDelta = 0f)
    }

    override fun adjustColorHSV(lightnessDelta: Float, saturationDelta: Float) {
        _uiState.update { state ->
            val c = state.activeColor
            val hsv = FloatArray(3)
            android.graphics.Color.RGBToHSV(
                (c.red * 255).toInt(),
                (c.green * 255).toInt(),
                (c.blue * 255).toInt(),
                hsv
            )
            hsv[1] = (hsv[1] + saturationDelta).coerceIn(0f, 1f)
            hsv[2] = (hsv[2] + lightnessDelta).coerceIn(0f, 1f)
            val newArgb = android.graphics.Color.HSVToColor(hsv)
            state.copy(activeColor = Color(newArgb).copy(alpha = c.alpha))
        }
    }

    override fun onColorPickerDismissed() {
        dispatch(EditorIntent.DismissColorPicker)
    }

    override fun onFlattenAllLayers() {
        val projectId = _uiState.value.projectId ?: return
        pushHistory()
        viewModelScope.launch(dispatchers.default) {
            val metrics = context.resources.displayMetrics
            val w = metrics.widthPixels.takeIf { it > 0 } ?: 1080
            val h = metrics.heightPixels.takeIf { it > 0 } ?: 1920
            val composite = exportManager.compositeLayers(_uiState.value.layers, w, h)

            val filename = "flattened_${UUID.randomUUID()}.png"
            val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(composite))
            val localUri = "file://$path".toUri()

            val flatLayer = Layer(
                id = UUID.randomUUID().toString(),
                name = "Flattened",
                uri = localUri,
                bitmap = composite
            )

            withContext(dispatchers.main) {
                // Deliberately NOT layerStore.remove() on the pre-flatten layers: pushHistory()
                // above stripped their bitmaps out of the undo snapshot, so undoing this flatten has
                // nothing to rebuild them from except LayerStore's still-cached base+strokes. See
                // onLayerRemoved for the same reasoning and onUndoClicked/onRedoClicked for the
                // rebuild-on-restore that depends on this cache surviving.
                val oldLayerIds = _uiState.value.layers.map { it.id }
                layerStore.putBase(flatLayer.id, composite.copy(Bitmap.Config.ARGB_8888, false))
                layerStore.initStrokes(flatLayer.id)
                dispatch(EditorIntent.ReplaceLayers(listOf(flatLayer), flatLayer.id))
                // Without this a spectator's layer list silently diverges from the host's until some
                // unrelated action forces a full resync — flatten replaced every layer wholesale, so
                // guests need the removes and the add, not just a props/transform resync.
                oldLayerIds.forEach { opEmitter.emit(Op.LayerRemove(it)) }
                opEmitter.emit(Op.LayerAdd(flatLayer))
                saveProject()
            }
        }
    }

    override fun onToggleLinkLayer(layerId: String) {
        pushHistory()
        val groupIds = getLinkedGroupIds(layerId)
        val isPartToUnlink = groupIds.size > 1
        
        _uiState.update { state ->
            val updatedLayers = state.layers.map { layer ->
                if (isPartToUnlink) {
                    // Dissolve the group
                    if (layer.id in groupIds) layer.copy(isLinked = false) else layer
                } else {
                    // Start linking to below
                    if (layer.id == layerId) layer.copy(isLinked = true) else layer
                }
            }
            state.copy(layers = updatedLayers)
        }
        saveProject()
    }

    override fun onToggleVisibility(layerId: String) {
        pushHistory()
        dispatch(EditorIntent.ToggleVisibility(layerId))
        saveProject()
        _uiState.value.layers.find { it.id == layerId }?.let { opEmitter.emit(Op.LayerPropsChange(layerId, it.toLayerProps())) }
    }

    fun setLayers(layers: List<Layer>) {
        dispatch(EditorIntent.SetLayers(layers))
        saveProject()
    }

    override fun onAddTextLayer() {
        pushHistory()
        val textCount = _uiState.value.layers.count { it.textParams != null }
        val defaultParams = TextLayerParams(text = "Text ${textCount + 1}")
        viewModelScope.launch(dispatchers.io) {
            // ensureProjectId() rather than `projectId ?: return`: this one bailed *after*
            // pushHistory(), so a missing project didn't just silently drop the text layer, it left
            // a phantom undo step behind that reverted nothing.
            val projectId = ensureProjectId()
            val metrics = context.resources.displayMetrics
            val widthPx = metrics.widthPixels.takeIf { it > 0 } ?: 1080
            val heightPx = metrics.heightPixels.takeIf { it > 0 } ?: 1920
            val density = metrics.density

            val typeface = GoogleFontCache.getTypeface(context, defaultParams.fontName, defaultParams.isBold, defaultParams.isItalic)
            val bitmap = TextRasterizer.rasterize(defaultParams, widthPx, heightPx, density, typeface)

            val filename = "text_layer_${UUID.randomUUID()}.png"
            val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(bitmap))
            val localUri = "file://$path".toUri()

            val newLayer = Layer(
                id = UUID.randomUUID().toString(),
                name = "Text${textCount + 1}",
                uri = localUri,
                bitmap = bitmap,
                isVisible = true,
                textParams = defaultParams
            )
            layerStore.putBase(newLayer.id, bitmap.copy(Bitmap.Config.ARGB_8888, false))
            layerStore.initStrokes(newLayer.id)

            withContext(dispatchers.main) {
                dispatch(EditorIntent.AddLayer(newLayer))
                opEmitter.emit(Op.LayerAdd(newLayer))
                // Signal the UI to immediately open this text layer's edit-text box.
                _uiState.update { it.copy(autoEditTextLayerId = newLayer.id) }
                saveProject()
            }
        }
    }

    /** Clear the one-shot auto-edit signal once the UI has opened the text editor. */
    fun consumeAutoEditTextLayer() {
        _uiState.update { it.copy(autoEditTextLayerId = null) }
    }

    private fun rerasterizeTextLayer(layerId: String, params: TextLayerParams) {
        viewModelScope.launch(dispatchers.io) {
            val metrics = context.resources.displayMetrics
            val widthPx = metrics.widthPixels.takeIf { it > 0 } ?: 1080
            val heightPx = metrics.heightPixels.takeIf { it > 0 } ?: 1920
            val density = metrics.density

            val typeface = GoogleFontCache.getTypeface(context, params.fontName, params.isBold, params.isItalic)
            val bitmap = TextRasterizer.rasterize(params, widthPx, heightPx, density, typeface)

            val layer = _uiState.value.layers.find { it.id == layerId } ?: return@launch
            val uri = layer.uri
            if (uri != null) {
                try {
                    val file = java.io.File(uri.path ?: return@launch)
                    java.io.FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                } catch (e: Exception) {
                    // Don't swallow silently — the layer bitmap is still updated in memory, but a
                    // failed disk write means the text edit won't survive reload.
                    android.util.Log.e("EditorViewModel", "Failed to persist text layer bitmap", e)
                }
            }

            layerStore.putBase(layerId, bitmap.copy(Bitmap.Config.ARGB_8888, false))

            withContext(dispatchers.main) {
                dispatch(EditorIntent.RenderTextLayer(layerId, bitmap, params))
            }
        }
    }

    override fun onTextContentChanged(layerId: String, text: String) {
        val layer = _uiState.value.layers.find { it.id == layerId } ?: return
        val params = layer.textParams ?: return
        pushHistory()
        val updated = params.copy(text = text)
        rerasterizeTextLayer(layerId, updated)
        opEmitter.emit(Op.TextContentChange(layerId, text))
        viewModelScope.launch(dispatchers.main) { saveProject() }
    }

    override fun onTextFontChanged(layerId: String, fontName: String) {
        val layer = _uiState.value.layers.find { it.id == layerId } ?: return
        val params = layer.textParams ?: return
        pushHistory()
        val updated = params.copy(fontName = fontName)
        rerasterizeTextLayer(layerId, updated)
        viewModelScope.launch(dispatchers.main) { saveProject() }
    }

    override fun onTextSizeChanged(layerId: String, sizeDp: Float) {
        val layer = _uiState.value.layers.find { it.id == layerId } ?: return
        val params = layer.textParams ?: return
        val updated = params.copy(fontSizeDp = sizeDp.coerceIn(8f, 300f))
        rerasterizeTextLayer(layerId, updated)
    }

    override fun onTextColorChanged(layerId: String, colorArgb: Int) {
        val layer = _uiState.value.layers.find { it.id == layerId } ?: return
        val params = layer.textParams ?: return
        pushHistory()
        val updated = params.copy(colorArgb = colorArgb)
        rerasterizeTextLayer(layerId, updated)
        viewModelScope.launch(dispatchers.main) { saveProject() }
    }

    override fun onTextKerningChanged(layerId: String, letterSpacingEm: Float) {
        val layer = _uiState.value.layers.find { it.id == layerId } ?: return
        val params = layer.textParams ?: return
        val updated = params.copy(letterSpacingEm = letterSpacingEm.coerceIn(-0.2f, 1f))
        rerasterizeTextLayer(layerId, updated)
    }

    override fun onTextStyleChanged(layerId: String, isBold: Boolean, isItalic: Boolean, hasOutline: Boolean, hasDropShadow: Boolean) {
        val layer = _uiState.value.layers.find { it.id == layerId } ?: return
        val params = layer.textParams ?: return
        pushHistory()
        val updated = params.copy(isBold = isBold, isItalic = isItalic, hasOutline = hasOutline, hasDropShadow = hasDropShadow)
        rerasterizeTextLayer(layerId, updated)
        viewModelScope.launch(dispatchers.main) { saveProject() }
    }

    // -------------------------------------------------------------------------
    // Co-op spectator API
    // -------------------------------------------------------------------------

    /** Applies a remote Op received from the host, without echoing it back through opEmitter. */
    fun applySpectatorOp(op: Op) {
        when (op) {
            is Op.LayerAdd -> dispatch(EditorIntent.AppendLayer(op.layer))
            is Op.LayerRemove -> dispatch(EditorIntent.RemoveLayerById(op.layerId))
            is Op.LayerReorder -> dispatch(EditorIntent.ReorderLayers(op.newOrder))
            is Op.LayerTransform -> {
                // The host encodes transform as [scale, offsetX, offsetY, rotX, rotY, rotZ, 0...0].
                // Apply the first 6 slots back to the matching layer.
                if (op.matrix.size >= 6) {
                    dispatch(EditorIntent.SetLayerTransformById(
                        op.layerId,
                        scale = op.matrix[0],
                        offset = androidx.compose.ui.geometry.Offset(op.matrix[1], op.matrix[2]),
                        rx = op.matrix[3], ry = op.matrix[4], rz = op.matrix[5],
                    ))
                }
            }
            is Op.LayerPropsChange -> dispatch(EditorIntent.SetLayerProps(op.layerId, op.props))
            is Op.StrokeComplete -> {
                val layerId = op.layerId
                val stroke = op.stroke
                val layer = _uiState.value.layers.find { it.id == layerId } ?: return
                
                viewModelScope.launch(dispatchers.default) {
                    val points = mutableListOf<Offset>()
                    for (i in 0 until stroke.points.size step 2) {
                        points.add(Offset(stroke.points[i], stroke.points[i+1]))
                    }
                    
                    val tool = Tool.entries.getOrNull(stroke.blendModeOrdinal) ?: Tool.BRUSH
                    val bitmap = layer.bitmap ?: return@launch
                    
                    // The points are already in BITMAP space (mapped by the host).
                    // To bypass mapping in DrawingEngine, we set canvasSize to bitmap size
                    // and identity transform.
                    val command = StrokeCommand(
                        path = points,
                        canvasSize = IntSize(bitmap.width, bitmap.height),
                        tool = tool,
                        brushSize = stroke.brushSize,
                        brushColor = stroke.colorArgb.toInt(),
                        intensity = 0.5f,
                        feathering = stroke.brushFeathering,
                        layerScale = 1f,
                        layerOffset = Offset.Zero,
                        layerRotationZ = 0f
                    )
                    
                    layerStore.addStroke(layerId, command)
                    rebuildLayerBitmap(layerId)
                }
            }
            is Op.TextContentChange -> {
                // rerasterizeTextLayer launches its own coroutine and updates state itself,
                // so it must NOT run inside _uiState.update { } — that lambda can re-run under
                // CAS contention and would launch duplicate rasterizations racing the same file.
                val updatedParams = _uiState.value.layers
                    .find { it.id == op.layerId }?.textParams?.copy(text = op.text)
                if (updatedParams != null) {
                    rerasterizeTextLayer(op.layerId, updatedParams)
                }
            }
            is Op.LayerBitmapReplace -> {
                val layerId = op.layerId
                if (_uiState.value.layers.none { it.id == layerId }) return
                viewModelScope.launch(dispatchers.default) {
                    // Cap the decoded bitmap at 2x the longest screen edge — plenty for any layer
                    // that reasonably rasterises to a screen quad, and prevents a peer accidentally
                    // shipping a giant PNG from OOMing the guest. Log-and-skip on decode failure
                    // rather than throwing across the op-apply.
                    val metrics = context.resources.displayMetrics
                    val maxDim = maxOf(metrics.widthPixels, metrics.heightPixels) * 2
                    val decoded = decodeBoundedBitmap(op.png, maxDim) ?: run {
                        android.util.Log.w(
                            "EditorViewModel",
                            "LayerBitmapReplace: skipping op for layer $layerId (decode returned null; bytes=${op.png.size})"
                        )
                        return@launch
                    }
                    val base = decoded.copy(Bitmap.Config.ARGB_8888, false)
                    if (base != decoded) decoded.recycle()
                    // Re-check the layer still exists — it can be removed while we were decoding
                    // off-thread. Without this, `putBase` on a stale layerId would leak the base
                    // pixel memory (nothing takes ownership of it).
                    if (_uiState.value.layers.none { it.id == layerId }) {
                        base.recycle()
                        return@launch
                    }
                    // The png is the full baked layer; replace base and drop local stroke history.
                    layerStore.putBase(layerId, base)
                    layerStore.initStrokes(layerId)
                    rebuildLayerBitmap(layerId)
                }
            }
        }
    }

    // NOTE: spectator/guest project loading is handled by ProjectManager.loadAsSpectator (unzip →
    // createProject → currentProject emission), which this ViewModel's currentProject collector
    // already reacts to by loading the project's layers. The former loadAsSpectator stub here was
    // dead code from Task 14 — never called — and implied a second, unimplemented load path.

}
