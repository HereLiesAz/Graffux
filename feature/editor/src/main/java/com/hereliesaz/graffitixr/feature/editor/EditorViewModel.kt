// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt
package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.defaultParamValue
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
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.SelectionOp
import com.hereliesaz.graffitixr.common.util.ContourTrace
import com.hereliesaz.graffitixr.common.util.SelectionGeometry
import com.hereliesaz.graffitixr.common.util.ImageUtils
import com.hereliesaz.graffitixr.common.util.computeAutoTune
import com.hereliesaz.graffitixr.common.util.decodeBoundedBitmap
import com.hereliesaz.graffitixr.common.azphalt.AirbrushEngine
import com.hereliesaz.graffitixr.common.azphalt.BrushSample
import com.hereliesaz.graffitixr.common.azphalt.BrushParameter
import com.hereliesaz.graffitixr.common.azphalt.DirtyRegion
import com.hereliesaz.graffitixr.common.azphalt.TileGrid
import com.hereliesaz.graffitixr.common.azphalt.TileDelta
import com.hereliesaz.graffitixr.common.azphalt.ImpastoEngine
import com.hereliesaz.graffitixr.common.azphalt.BrushStamps
import com.hereliesaz.graffitixr.common.azphalt.applyCubeLut
import com.hereliesaz.graffitixr.nativebridge.BrushDab
import com.hereliesaz.graffitixr.nativebridge.MaskedBrushDab
import com.hereliesaz.graffitixr.nativebridge.ResolvedBrushDab
import com.hereliesaz.graffitixr.nativebridge.SecondaryBrushDab
import com.hereliesaz.graffitixr.nativebridge.VulkanStampEngine
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import javax.inject.Inject
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import com.hereliesaz.graffitixr.common.util.StrokeStabilizer
import com.hereliesaz.graffitixr.feature.editor.timelapse.TimeLapseRecorder
import kotlinx.coroutines.flow.collect
import kotlin.math.max
import kotlin.math.roundToInt

data class StrokeCommand(
    val path: List<Offset>,
    // Aligned 1:1 with `path` by index. Empty for every tool but Tool.BRUSH, which reads pressure
    // (0..1, 1 = a device/finger with no real pressure sensor) into BrushDynamics — see EditorViewModel.
    val pressures: List<Float> = emptyList(),
    // Canonical per-point brush telemetry. Empty on legacy/remote commands. Smudge stores it too:
    // the same physical pressure/speed/tilt stream can drive Color Smudge sensor routes on replay.
    val brushSamples: List<BrushSample> = emptyList(),
    // Tool.SMUDGE only. Null means a legacy command, whose historical intensity->Smear mapping is
    // retained by DrawingEngine. Snapshotting the settings here makes undo/redo independent of what
    // the Tool Options window is set to later.
    val colorSmudgeSettings: ColorSmudgeEngine.Settings? = null,
    val canvasSize: IntSize,
    val tool: Tool,
    val brushSize: Float,
    val brushColor: Int,
    /** Background/secondary brush colour snapshotted for Krita Source/Mix replay. */
    val secondaryBrushColor: Int = android.graphics.Color.BLACK,
    val intensity: Float,
    val feathering: Float = 0f,
    // Stroke-level opacity ceiling for [Tool.BRUSH] (0..1, default fully opaque — every other tool
    // ignores this). Unlike [flow]'s per-dab build-up, this caps the WHOLE stroke's resulting
    // coverage regardless of how much it overlaps itself, so a translucent stroke that loops back on
    // itself doesn't paint darker where it crosses — see DrawingEngine's Tool.BRUSH branch.
    val opacity: Float = 1f,
    val layerScale: Float = 1f,
    val layerOffset: Offset = Offset.Zero,
    val layerRotationZ: Float = 0f,
    // Azphalt stamp-brush stroke (null = the built-in round brush). [flow] is per-dab build-up and
    // [seed] fixes the dab jitter so a replayed stroke re-composites to identical pixels. [stampShape]
    // is the brush's optional greyscale/alpha tip image (null = a generated round tip).
    val stampBrush: com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush? = null,
    val flow: Float = 1f,
    val seed: Long = 0L,
    val stampShape: Bitmap? = null,
    // Orthogonal Krita-style brush assets. Grain is tiled independently of the primary shape; the
    // masked shape is the optional secondary tip. Both are snapshotted with the stroke just like
    // stampShape so undo/redo cannot depend on whichever extension is selected later.
    val stampGrain: Bitmap? = null,
    val stampMaskShape: Bitmap? = null,
    // Procreate parity, recorded per stroke so undo/redo replay reproduces the paint exactly:
    // [symmetryMode] mirrors the stroke across one or more axes through the canvas centre;
    // [alphaLock] confines the paint to pixels that already have alpha.
    val symmetryMode: SymmetryMode = SymmetryMode.NONE,
    val alphaLock: Boolean = false,
    // Procreate's Wrap Around: tiles the stroke 3x3 across the canvas edges. Recorded per stroke
    // for the same reason [symmetryMode] is -- live drawing already tiles it (see EditorViewModel's
    // drawPathAll/drawDab call sites), but replay never applied it at all until this field existed,
    // so a wrapped stroke's edge tiling vanished the moment it committed under a feathered selection,
    // or on the very next undo/redo/auto-bake.
    val wrapAroundMode: Boolean = false,
    // The lasso selection in force when the stroke was drawn, if any. Recorded per stroke (rather
    // than read from live state at replay time) so an undo/redo re-composites against the same
    // boundary the paint was originally clipped to, even after the selection has moved or gone.
    val selection: com.hereliesaz.graffitixr.common.model.Selection? = null,
    // Set only on a [Tool.SELECT] command: the screen-space distance the selected pixels were
    // dragged. Makes a move a replayable command like any stroke — see [DrawingEngine].
    val moveDelta: Offset? = null,
    // Set only on a [Tool.CLONE] command: the screen-space vector from this stroke to the pixels it
    // copies, measured from the stroke's first point. Recorded rather than read at replay time
    // for the same reason `selection` is — the source can be moved or cleared afterwards, and an
    // undo/redo has to re-composite the pixels that were actually painted.
    val cloneOffset: Offset? = null,
    // Wipes the layer to transparency instead of painting a path — Procreate's clear-layer. Recorded
    // as a command so it undoes by replay like everything else; honours [selection], so clearing
    // with a lasso active wipes only inside it.
    val clearAll: Boolean = false,
    // Procreate's Colour Fill: floods the selection (or the whole layer) with [brushColor] rather
    // than painting a path. Recorded as a command for the same reason [clearAll] is — it undoes by
    // replay like everything else, and honours the selection including its feather.
    val fillSelection: Boolean = false,
    // Procreate's Distort/Warp: the screen-space handle grid the layer's pixels were pushed onto.
    // Recorded rather than baked into the base so the deformation undoes by replay like everything
    // else — and so it replays *after* the strokes beneath it, which is the order it was applied in.
    val warpHandles: List<Offset>? = null,
)

/**
 * How many edits deep undo goes — and therefore how many strokes a layer must keep replayable.
 * Shared by [EditHistory] and the stroke baker so they can't drift apart: if the history were ever
 * deeper than the strokes kept, an undo would silently restore the wrong pixels.
 */
internal const val HISTORY_DEPTH = 20

// Roadmap item 16's undo fast path: the tile size TileGrid partitions a layer into when capturing
// a stroke's before/after tile deltas. 64 matches Krita's own hardcoded tile dimension
// (`libs/image/tiles3/kis_tile_data_interface.h`'s WIDTH/HEIGHT constants, confirmed from source
// this session) -- Krita maps cleanly here, unlike Procreate, whose `tileSize` is a per-document
// file-format field with no single confirmed value to copy (see the roadmap doc's item 16 entry).
internal const val UNDO_TILE_SIZE = 64

/** Longest edge, in pixels, a time-lapse GIF frame is downsampled to — keeps captures cheap and small. */
private const val TIME_LAPSE_FRAME_MAX_DIM = 480

/** Cap on a whole imported document (PSD/PDF/Procreate/etc) read fully into memory by
 *  [EditorViewModel.onImportDocument] — matches ProjectManager's own MAX_IMPORT_BYTES precedent
 *  for "how big a single user-picked file is allowed to be before we refuse it outright". */
private const val MAX_IMPORT_DOCUMENT_BYTES = 512 * 1024 * 1024

/** Cap on the QuickLook/Thumbnail.png entry [EditorViewModel.extractProcreateComposite] pulls out
 *  of a `.procreate` archive. It's a preview PNG, not full artwork — generous but far below
 *  memory-exhaustion territory, so a crafted entry that claims to decompress far past this can't
 *  OOM the app the way an unbounded `ZipInputStream.readBytes()` would let it. */
private const val MAX_PROCREATE_THUMBNAIL_BYTES = 64 * 1024 * 1024

/**
 * Whether the azphalt stamp-brush live-preview path can use a GPU compute stamp shader instead of
 * the CPU renderer (docs/Krita Brush Engine Adoption.md item 15). As of this pass, every azphalt
 * stamp-brush capability has a GPU counterpart -- shaped tips and non-round
 * [AzphaltBrush.tipRatio], grain, and now masked/dual-brush (a full `maskedBrush` config) -- so
 * this is unconditionally true; see [gpuPipelineUsesMaskedShader] for which shader a given
 * combination routes to.
 *
 * Color source (plain/gradient/uniform-random) and any HSV sensor shift were never part of this
 * restriction either: [StampBrushRenderer.resolvedColor] already resolves the final per-dab RGB
 * on the CPU before a dab ever reaches the GPU (see the `stampResolvedDabs`/`stampMaskedDabs` call
 * sites in `onStrokeStart`), and both shaders already render whatever resolved RGB they're given.
 */
internal fun gpuCompatibleStampBrush(
    brush: com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush,
    shape: Bitmap?,
    grain: Bitmap?,
    maskShape: Bitmap?,
): Boolean = true

/**
 * Given a GPU-compatible stroke (see [gpuCompatibleStampBrush]), whether it needs
 * `stamp_masked.comp` rather than the long-proven generated-round-tip-only `stamp.comp` path: a
 * real [shape] bitmap, a non-round [AzphaltBrush.tipRatio] that `stamp.comp`'s round-only
 * `stampCoverage()` can't represent, a [grain] texture, or [AzphaltBrush.maskedBrush] being set --
 * only `stamp_masked.comp` has grain and secondary-tip sampler bindings at all, so a round tip
 * with either still needs the masked pipeline even though neither of the first two conditions
 * applies. `stamp_masked.comp` already handles a null/round tip correctly (see
 * [alphaChannelBytes]'s call site, which rasterizes a generated round mask via
 * `BrushTipMaskCache.tipMask(null, ...)` when [shape] is null) -- the same trick applies to the
 * secondary tip's own mask when [AzphaltBrush.maskedBrush]'s `shapePath` resolved to nothing.
 */
internal fun gpuPipelineUsesMaskedShader(
    brush: com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush,
    shape: Bitmap?,
    grain: Bitmap? = null,
): Boolean = shape != null || brush.tipRatio != 1f || grain != null || brush.maskedBrush != null

/** Reference resolution the masked GPU pipeline's tip mask is rasterized at, once per stroke,
 *  independent of any individual dab's radius -- `stamp_masked.comp` scales it per dab via UV
 *  sampling using that dab's own radius/tipRatio, the same way [BrushTipMaskCache]'s square
 *  [BrushTipMaskCache.tipMask] source is scaled into a non-square CPU dab bitmap per call. */
private const val GPU_MASK_REFERENCE_SIZE = 128

/** Extracts [bitmap]'s alpha channel as a flat row-major byte buffer -- the R8 layout
 *  `VulkanStampEngine.stampMaskedDabs()` expects for its tip-mask texture upload. */
internal fun alphaChannelBytes(bitmap: Bitmap): ByteArray {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val out = ByteArray(pixels.size)
    for (i in pixels.indices) out[i] = ((pixels[i] ushr 24) and 0xFF).toByte()
    return out
}

/**
 * Reads [input] fully, but bails out and returns null the moment more than [maxBytes] have been
 * read — the bounded-read pattern used everywhere in this app that decodes an untrusted archive
 * (see ProjectManager.streamEntryBounded, AzpInstaller's MAX_PACKAGE_BYTES), applied here for the
 * two `readBytes()` calls in the document-import path that weren't bounded like their siblings.
 */
private fun readBytesBounded(input: java.io.InputStream, maxBytes: Int): ByteArray? {
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val n = input.read(chunk)
        if (n < 0) break
        total += n
        if (total > maxBytes) return null
        buffer.write(chunk, 0, n)
    }
    return buffer.toByteArray()
}

// The 3D model and its paint, inside the project folder. Fixed names rather than generated ones:
// a project holds at most one model, so a uuid would only accumulate orphans every time a new
// model replaced the old.
private const val MODEL_OBJ_FILE = "model.obj"
private const val MODEL_TEXTURE_FILE = "model_texture.png"

// Key for the model texture in the pending-write map, which is otherwise keyed by layer id. Prefixed
// so it can never collide with one — layer ids are uuids, but relying on that is a trap for later.
private const val MODEL_TEXTURE_KEY = "model:texture"

/**
 * How long a continuous edit (a rail-slider drag) must be still before it is considered finished.
 *
 * Long enough to bridge the gap between emitted samples of a slow drag, short enough that letting go
 * and immediately hitting Undo does the thing the user expects.
 */
private const val CONTINUOUS_EDIT_IDLE_MS = 400L

/**
 * The tools that rework pixels already on the layer instead of painting new ones.
 *
 * None of them can be previewed with a `Paint` — no colour, xfermode or mask filter expresses "blur
 * what is under here", "add back the contrast a blur would remove", or "carry the colour you are
 * passing over". So all three paint nothing live and commit the whole stroke on finger-up, through
 * `ImageProcessor.applyToolToBitmap`.
 *
 * Named as a set rather than spelled out at each site because the two places that care —
 * `buildStrokePaint`, which must give them a transparent paint, and `onStrokeEnd`, which must route
 * them to the deferred commit — have to agree. A tool in one and not the other paints a translucent
 * black line and never commits, or commits twice.
 *
 * [Tool.LIQUIFY] is deliberately absent: it also has no live paint, but it warps the whole layer
 * through a displacement field rather than compositing a stroke, and has its own commit path.
 */
private val RESAMPLING_TOOLS = setOf(Tool.BLUR, Tool.SHARPEN, Tool.SMUDGE)

// Mirrors ImageProcessor.applyToolToBitmap's BLUR/SHARPEN cheapBlur() factors exactly, at the same
// intensity = 0.5f every StrokeCommand call site in this file hardcodes: BLUR's factor formula is
// (2 + intensity * 12).coerceIn(2, 16); SHARPEN's "soft" reference blur is always factor 2,
// independent of intensity. Used by the live-preview reference blur below.
private const val RESAMPLE_BLUR_FACTOR = 8
private const val RESAMPLE_SHARPEN_SOFT_FACTOR = 2
// The same fixed intensity every StrokeCommand call site in this file hardcodes for BLUR/SHARPEN.
private const val RESAMPLE_INTENSITY = 0.5f

/**
 * BLUR's live-preview composite: stamps [reference] (the stroke-independent full blur computed
 * once in onStrokeStart) into [work] only where [mapped]'s stroke path covers, via the identical
 * opaque-mask-then-SRC_IN technique ImageProcessor.applyToolToBitmap's Tool.BLUR branch uses on
 * commit — see that branch's own comments for why SRC_IN over a fresh mask (rather than composing
 * translucently straight onto [work]) is what keeps a self-overlapping stroke from double-darkening.
 * Whole-canvas, redone from scratch every touch sample on a cancellable background job (mirrors
 * Tool.LIQUIFY's existing live-preview cost/architecture) rather than scoped to the stroke's own
 * bounding box — a real, not-yet-done follow-up optimization if this proves too slow on very large
 * canvases, parallel to readback()'s dirty-rect fix.
 */
private fun liveBlurComposite(
    work: Bitmap,
    reference: Bitmap,
    mapped: List<Offset>,
    brushSizePx: Float,
    feathering: Float,
    wrapAroundMode: Boolean,
    symmetryMode: SymmetryMode,
) {
    val w = work.width
    val h = work.height
    val maskBmp = SafeBitmap.create(w, h) ?: return
    val maskCanvas = Canvas(maskBmp)
    val maskPaint = Paint().apply {
        strokeWidth = brushSizePx
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
        if (feathering > 0f) maskFilter = BlurMaskFilter(brushSizePx * feathering * 0.5f, BlurMaskFilter.Blur.NORMAL)
    }
    ImageProcessor.drawStroke(maskCanvas, mapped, maskPaint, wrapAroundMode, symmetryMode)
    maskCanvas.drawBitmap(reference, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) })
    Canvas(work).drawBitmap(maskBmp, 0f, 0f, null)
    maskBmp.recycle()
}

/**
 * SHARPEN's live-preview composite: the identical per-pixel unsharp-mask formula
 * ImageProcessor.applyToolToBitmap's Tool.SHARPEN branch uses on commit (result = original +
 * amount × (original − blur), lerped by mask coverage, alpha untouched) — see that branch's own
 * comments for why a per-pixel lerp rather than BLUR's SRC_IN stamp is what this tool needs. Same
 * whole-canvas-per-frame cost tradeoff as [liveBlurComposite].
 */
private fun liveSharpenComposite(
    work: Bitmap,
    reference: Bitmap,
    mapped: List<Offset>,
    brushSizePx: Float,
    feathering: Float,
    wrapAroundMode: Boolean,
    symmetryMode: SymmetryMode,
) {
    val amount = 0.4f + RESAMPLE_INTENSITY * 1.6f
    val w = work.width
    val h = work.height
    val maskBmp = SafeBitmap.create(w, h) ?: return
    val maskPaint = Paint().apply {
        strokeWidth = brushSizePx
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
        if (feathering > 0f) maskFilter = BlurMaskFilter(brushSizePx * feathering * 0.5f, BlurMaskFilter.Blur.NORMAL)
    }
    ImageProcessor.drawStroke(Canvas(maskBmp), mapped, maskPaint, wrapAroundMode, symmetryMode)

    val n = w * h
    val out = IntArray(n)
    val blur = IntArray(n)
    val mask = IntArray(n)
    work.getPixels(out, 0, w, 0, 0, w, h)
    reference.getPixels(blur, 0, w, 0, 0, w, h)
    maskBmp.getPixels(mask, 0, w, 0, 0, w, h)
    maskBmp.recycle()
    for (i in 0 until n) {
        val coverage = mask[i] ushr 24 and 0xFF
        if (coverage == 0) continue
        val p = out[i]
        val q = blur[i]
        val k = amount * coverage / 255f
        val pr = p shr 16 and 0xFF
        val pg = p shr 8 and 0xFF
        val pb = p and 0xFF
        val r = (pr + k * (pr - (q shr 16 and 0xFF))).roundToInt().coerceIn(0, 255)
        val g = (pg + k * (pg - (q shr 8 and 0xFF))).roundToInt().coerceIn(0, 255)
        val b = (pb + k * (pb - (q and 0xFF))).roundToInt().coerceIn(0, 255)
        out[i] = (p.toLong() and 0xFF000000L).toInt() or (r shl 16) or (g shl 8) or b
    }
    work.setPixels(out, 0, w, 0, 0, w, h)
}

// Round-brush dab spacing as a fraction of dab diameter — shared by [EditorViewModel.drawCurveRun]
// (live preview) and [ImageProcessor.drawStrokeDynamic] (authoritative commit/replay), both of
// which stamp the round brush as a train of solid filled dabs rather than stroke a variable-width
// path. Tight enough (a dab every ~15% of its own diameter) that consecutive dabs overlap heavily
// and the train reads as one smooth, round-capped stroke with no visible bumps, matching what
// Paint.Style.STROKE + Paint.Cap.ROUND used to draw directly.
internal const val ROUND_BRUSH_DAB_SPACING_FRACTION = 0.15f

sealed class EditCommand {
    data class PropertyChange(val oldLayers: List<Layer>) : EditCommand()

    /**
     * [tileDeltas] (roadmap item 16's undo fast path) is attached *after* this command is pushed
     * — a stroke commits synchronously (see [EditorViewModel.commitStampStroke]), but the
     * authoritative pixels it needs to diff against only exist once that commit's async recompute
     * finishes — so this starts `null` (meaning "no fast path yet, fall back to full replay") and
     * is filled in later via [EditHistory.attachTileDeltas], matched by [command]'s own object
     * identity so a stroke that's already been undone by the time attachment runs is a safe no-op.
     * [tileDeltaCanvasWidth]/[tileDeltaCanvasHeight] must match the layer bitmap's current
     * dimensions before [tileDeltas] is trusted — see [EditorViewModel.onUndoClicked]'s guard.
     */
    data class Draw(
        val layerId: String,
        val command: StrokeCommand,
        val tileDeltas: List<com.hereliesaz.graffitixr.common.azphalt.TileDelta.TileSnapshot>? = null,
        val tileDeltaCanvasWidth: Int = 0,
        val tileDeltaCanvasHeight: Int = 0,
    ) : EditCommand()
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
    private val repositoryApiClient: com.hereliesaz.graffitixr.data.azphalt.RepositoryApiClient,
    private val customBrushRepository: com.hereliesaz.graffitixr.data.brush.CustomBrushRepository,
    private val figmaRepository: com.hereliesaz.graffitixr.data.figma.FigmaRepository,
    private val projectFileScanner: com.hereliesaz.graffitixr.data.ProjectFileScanner,
) : ViewModel(), EditorActions {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState = _uiState.asStateFlow()

    /**
     * Live brush-stroke preview — see [com.hereliesaz.graffitixr.common.model.LiveStroke]'s doc
     * comment for why this is a separate StateFlow rather than fields on [EditorUiState]: publishing
     * it there recomposed every layer in the stack on every stroke sample instead of just the one
     * being painted, and a stroke of any length visibly lagged the finger as the backlog grew.
     */
    private val _liveStroke = MutableStateFlow(com.hereliesaz.graffitixr.common.model.LiveStroke())
    val liveStroke = _liveStroke.asStateFlow()

    private val _colorSmudgeSettings = MutableStateFlow(ColorSmudgeEngine.Settings())
    val colorSmudgeSettings = _colorSmudgeSettings.asStateFlow()

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
     * The extensions the Extensions panel offers — everything installed that this host can actually
     * *do* something with when tapped.
     *
     * That is code and mixed packages, which [onExtensionSelected] runs in the sandbox, **and**
     * asset packages carrying a usable LUT, which it applies as a colour grade. The LUT branch has
     * always been there; the filter here had not, so a `kind: "asset"` grade installed successfully,
     * reported itself installed, and then appeared in no panel at all — reachable only by uninstalling
     * it from the store window. Brushes are excluded on purpose: they have their own picker under the
     * Brushes rail group, and listing them twice would imply two different things to do with one.
     */
    val installedExtensions: StateFlow<List<com.hereliesaz.graffitixr.data.azphalt.InstalledExtension>> =
        extensionRepository.installed
            .map { list -> list.filter(::surfacesInExtensionsPanel) }
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

    // Per-layer text rasterize jobs: a glee audit found rerasterizeTextLayer() launched an
    // unserialized coroutine on every keystroke (onTextContentChanged fires per character), each
    // one racing to both rasterize a bitmap AND overwrite the same PNG file on disk -- an older,
    // still-in-flight write landing after a newer one silently reverted the on-disk layer to a
    // stale rasterization, and interleaved FileOutputStream writes to the same file could corrupt
    // it outright. Cancelling whatever was previously in flight for a layer before starting a new
    // rasterize is the same discipline rebuildJobs above already follows for paint layers.
    private val textRasterizeJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

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
    /** Captured at stroke start for the same reason as [strokeSymmetry] -- previously re-read live
     *  on every draw call and never recorded on [StrokeCommand] at all, so a wrapped stroke's edge
     *  tiling could change mid-drag and always vanished on commit/undo/redo/auto-bake. */
    private var strokeWrapAroundMode: Boolean = false
    /** Captured at stroke start for the same reason as [strokeAlphaLock] — 1f for every tool but the
     *  built-in round brush, which reads it from live state right before this. */
    private var strokeOpacity: Float = 1f
    /** The lasso in force when the in-flight stroke began — see [strokeSymmetry] for why captured. */
    private var strokeSelection: com.hereliesaz.graffitixr.common.model.Selection? = null
    /** Uptime of the last touch sample this stroke actually rendered — the input-rate throttle. */
    private var lastSampleMs: Long = 0L
    // Live Catmull-Rom curve window for the round brush (Tool.BRUSH, no stamp) — see
    // feedLiveCurvePoint's doc. Bitmap-space points not yet curve-finalized, oldest first, and the
    // BrushDynamics width of the segment ENDING at each (one shorter than the points — the very
    // first windowed point has no segment yet). A segment is drawn once it has real neighbours on
    // both sides — needs a 4-point window — and never touched again once drawn: like a git commit,
    // each drawn run is a permanent function of the point list as it stood at that moment, not
    // retroactively revised as the stroke grows. Reset per stroke in onStrokeStart/
    // clearTransientStrokeState.
    private val liveCurveLock = Any()
    private val liveCurveWindow = ArrayDeque<Offset>()
    private val liveCurveWidths = ArrayDeque<Float>()
    /** How many segments the live window has drawn so far this stroke — 0 means the window hasn't
     *  filled yet, at which point its FIRST draw also has to cover the stroke's own global first
     *  segment (which uses a reflected phantom point on its near side regardless, live or replayed
     *  — there being no real point before a stroke's first one either way). */
    private var liveCurveFinalizedCount = 0
    @Volatile private var strokeGeneration = 0L
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
    // Parallel to strokeCollectedPoints (same index = same touch sample). 1f for a device/finger
    // that reports no real pressure — Android's own synthetic default — so a stroke's width dynamics
    // are unaffected wherever there is no stylus in play.
    private var strokeCollectedPressures: MutableList<Float> = mutableListOf()
    // Same index as points/pressures. Kinematics remain in physical screen-hand space; only x/y are
    // replaced by the stabilized world-space point before storage.
    private var strokeCollectedSamples: MutableList<BrushSample> = mutableListOf()
    private var pendingStrokeStartSample: BrushSample? = null
    private var pendingStrokePointSample: BrushSample? = null

    private fun resetStrokePoints(initial: Offset? = null, initialPressure: Float = 1f) = synchronized(strokeCollectedPointsLock) {
        // `vararg Offset` is rejected by the compiler (Offset is a Compose value class), so take a
        // single optional seed point — the only two call sites are reset-with-start and reset-empty.
        strokeCollectedPoints = if (initial != null) mutableListOf(initial) else mutableListOf()
        strokeCollectedPressures = if (initial != null) mutableListOf(initialPressure) else mutableListOf()
        strokeCollectedSamples = if (initial != null) {
            val source = pendingStrokeStartSample ?: BrushSample(initial.x, initial.y, pressure = initialPressure)
            mutableListOf(source.copy(x = initial.x, y = initial.y, pressure = initialPressure, predicted = false))
        } else {
            mutableListOf()
        }
    }

    private fun addStrokePoint(point: Offset, pressure: Float) = synchronized(strokeCollectedPointsLock) {
        strokeCollectedPoints.add(point)
        strokeCollectedPressures.add(pressure)
        val source = pendingStrokePointSample ?: BrushSample(point.x, point.y, pressure = pressure)
        strokeCollectedSamples.add(source.copy(x = point.x, y = point.y, pressure = pressure, predicted = false))
    }

    private fun snapshotStrokePoints(): List<Offset> = synchronized(strokeCollectedPointsLock) {
        strokeCollectedPoints.toList()
    }

    private fun snapshotStrokePressures(): List<Float> = synchronized(strokeCollectedPointsLock) {
        strokeCollectedPressures.toList()
    }

    private fun snapshotStrokeSamples(): List<BrushSample> = synchronized(strokeCollectedPointsLock) {
        strokeCollectedSamples.toList()
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

    // Blur/Sharpen/Smudge (RESAMPLING_TOOLS) live-preview state — valid only between onStrokeStart
    // and onStrokeEnd for one of those three tools. These tools previously had NO live preview at
    // all (a fully transparent Paint — see buildStrokePaint's RESAMPLING_TOOLS branch), applying
    // only once on finger-up via ImageProcessor.applyToolToBitmap/DrawingEngine's Color Smudge
    // branch; while dragging, nothing rendered. Mirrors liquifyJob/liquifyOriginalBitmap's pattern:
    // recompute the whole effect from the pristine per-stroke original on Dispatchers.Default each
    // touch sample, cancelling any still-running prior recompute — the commit-time path above is
    // completely untouched and stays the single source of truth for what actually gets painted.
    private var resampleJob: kotlinx.coroutines.Job? = null
    private var resampleOriginalBitmap: Bitmap? = null
    // BLUR's full blur / SHARPEN's tight "soft" reference blur — independent of the stroke path, so
    // computed once per stroke rather than on every touch sample. Unused (stays null) for SMUDGE,
    // which has no such stroke-independent reference.
    private var resampleBlurReference: Bitmap? = null
    // SMUDGE only: fixed once per stroke so every live-preview frame's ColorSmudgeEngine.apply call
    // (each a from-scratch replay of the whole stroke so far, not an incremental append — see
    // onStrokePoint's RESAMPLING_TOOLS branch) resolves any seed-dependent dynamics identically
    // frame to frame. Independent of the real commit seed (System.nanoTime() at onStrokeEnd), the
    // same "live preview is presentation-only" gap every other tool here already has.
    private var resampleSeed: Long = 0L

    // The selected azphalt stamp brush's parsed definition (null = built-in round brush). Set by
    // selectBrushExtension; read at stroke-commit to route through StampBrushRenderer.
    private var activeStampBrush: com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush? = null
    // Decoded primary, grain, and secondary-mask assets for the active brush.
    private var activeStampShape: Bitmap? = null
    private var activeStampGrain: Bitmap? = null
    private var activeStampMaskShape: Bitmap? = null

    // Live stamp-stroke preview state — valid only between onStrokeStart and onStrokeEnd for a stamp
    // brush. Dabs are stamped incrementally onto [stampLiveBitmap]; [stampSeed] fixes the jitter so the
    // preview, the commit, and history replay all render identically.
    private var stampLiveBitmap: Bitmap? = null
    private var stampLiveCanvas: Canvas? = null
    private var stampStampedCount: Int = 0
    // Item 13's airbrush held-run dabs (AirbrushEngine.heldDabs) are tracked as their own
    // independent incremental prefix, painted after each frame's new movement dabs -- see the
    // live-preview block below for why this can't just be concatenated with [stampStampedCount]'s
    // movement-dab list the way commit/replay concatenates them in DrawingEngine.
    private var stampHeldStampedCount: Int = 0
    // Item 12's live-preview follow-up (Impasto). Live shading is necessary, not optional, but
    // calling ImpastoEngine.shade() over the WHOLE canvas every drag frame is a real interactivity
    // risk (that full-image pass is designed to run once, at commit) -- so the live preview keeps
    // its own scratch height map and a SEPARATE, display-only shaded bitmap, and re-shades only the
    // small region each frame's new dabs actually touched (dilated by 1px for shade()'s neighbour-
    // gradient reads) via ImpastoEngine.shadeInto(), never the CPU/GPU dab-compositing target
    // ([stampLiveBitmap]) itself. Both null whenever this stroke's brush has no Impasto thickness.
    //
    // Why a second bitmap, not shading stampLiveBitmap in place: shadeInto()'s multiplier is not
    // idempotent -- it must always be computed from the RAW, unshaded painted colour, never from a
    // previously-shaded frame's output (see shadeInto's own doc comment). If shading were applied
    // destructively onto stampLiveBitmap, a later overlapping dab's alpha blend would read an
    // already-brightened/darkened pixel as if it were the true paint colour, corrupting both the
    // displayed colour and (since GPU readback/CPU paintDabs both write onto the same bitmap) the
    // stroke's actual accumulated pigment -- not just a cosmetic bug.
    private var stampLiveHeightMap: FloatArray? = null
    private var stampLiveShadedBitmap: Bitmap? = null
    private var stampSeed: Long = 0L
    private var stampBrushForStroke: com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush? = null
    private var stampShapeForStroke: Bitmap? = null
    private var stampGrainForStroke: Bitmap? = null
    private var stampMaskShapeForStroke: Bitmap? = null
    // Bitmap-space stroke points, appended incrementally so each drag frame maps only the NEW points
    // instead of re-mapping the whole stroke (interleaved [x0,y0,…]).
    private val stampMappedPoints = ArrayList<Float>()

    // GPU live-preview compositor for the CURRENT stamp stroke (docs/Native Rendering Engine
    // Design.md §9 Phase 3), and only while it's actually kept in sync with [stampLiveBitmap].
    // [stampGpuActive] is the single source of truth for whether new dabs should be attempted on
    // the GPU this stroke; a failure at any point (init/upload/stamp/readback) clears it for the
    // REST of the stroke and every dab from then on falls back to [StampBrushRenderer.paintDabs]
    // on the same, already-correct [stampLiveCanvas] — never a partial GPU composite silently left
    // stale. The one-shot commit (`commitStampStroke`) never touches this: it always re-renders the
    // whole stroke on the CPU from scratch, so a live preview that fell back partway through never
    // affects what's actually saved.
    //
    // [stampGpuUsesMaskedPipeline] picks which shader this stroke dispatches to: false is the
    // long-proven generated-round-tip-only `stamp.comp`/`stampResolvedDabs` path (untouched by
    // item 15's masked-tip addition); true is the newer `stamp_masked.comp`/`stampMaskedDabs` path
    // (docs/Krita Brush Engine Adoption.md item 15), used only when a shaped tip or non-round
    // tipRatio actually needs it — see [gpuCompatibleStampBrush]. When true,
    // [stampGpuMaskAlpha8]/[stampGpuMaskSize] hold the stroke's tip mask pre-rasterized once (at a
    // fixed reference resolution, independent of any individual dab's radius — the shader scales
    // per dab via its own tipRatio field) as an R8 byte buffer ready for
    // `VulkanStampEngine.stampMaskedDabs()`.
    // Guards every touch of stampGpuEngine (and the paired stampGpuActive/etc config it's read
    // alongside) once onStrokePoint's GPU submit/readback moved off the main thread onto a
    // serialized background job -- see that job's own comment for the full race this closes.
    // Mirrors liveCurveLock/strokeGpuEngine below exactly, one lock per independent live-preview
    // GPU engine rather than sharing one, since the two pipelines' engines are otherwise unrelated.
    private val stampLiveLock = Any()
    // The tail of the current stroke's serialized GPU work queue -- see onStrokePoint's stamp-brush
    // branch. Each new batch joins this before starting, so batches run strictly one at a time and
    // in order even though they're no longer on the calling (main) thread; null between strokes.
    private var stampGpuJob: Job? = null
    private var stampGpuEngine: VulkanStampEngine? = null
    private var stampGpuActive: Boolean = false
    private var stampGpuUsesMaskedPipeline: Boolean = false
    private var stampGpuMaskAlpha8: ByteArray? = null
    private var stampGpuMaskSize: Int = 0
    // Item 15's texture/grain follow-up: mirrors stampGpuMaskAlpha8/stampGpuMaskSize, resolved
    // once per stroke via StampBrushRenderer.resolveGrainTileAndPhase (the same function the CPU
    // masked-tip path uses) so the two paths never disagree on grainRandomOffsetPerStroke's seeded
    // draw. Null tile means this stroke has no grain -- stampMaskedDabs() treats that as "disabled".
    private var stampGpuGrainAlpha8: ByteArray? = null
    private var stampGpuGrainWidth: Int = 0
    private var stampGpuGrainHeight: Int = 0
    private var stampGpuGrainCanvasLocked: Boolean = false
    private var stampGpuGrainPhaseX: Float = 0f
    private var stampGpuGrainPhaseY: Float = 0f
    // Item 15's masked/dual-brush follow-up: mirrors stampGpuMaskAlpha8/stampGpuMaskSize for the
    // secondary tip's own mask texture, rasterized once per stroke the same way. Per-dab secondary
    // geometry isn't cached here -- it comes straight from each dab's own BrushStamps-attached
    // `Dab.mask` at the dispatch site, same source the CPU path already reads.
    private var stampGpuHasDualBrush: Boolean = false
    private var stampGpuSecondaryMaskAlpha8: ByteArray? = null
    private var stampGpuSecondaryMaskSize: Int = 0

    // Same contract as stampGpuEngine/stampGpuActive above, for the round brush's live path
    // instead of azphalt stamp brushes — both now stamp dabs (see ROUND_BRUSH_DAB_SPACING_FRACTION),
    // so both get the same GPU compositor treatment. drawCurveRun/feedLiveCurvePoint take the
    // target bitmap to read back into as an explicit parameter (rather than a class field like
    // stampLiveBitmap) since it's set once, early, by onStrokeStart's async setup — the same
    // `workBitmap` local that later becomes strokeWorkingBitmap.
    private var strokeGpuEngine: VulkanStampEngine? = null
    private var strokeGpuActive: Boolean = false

    private val strokeStabilizer = StrokeStabilizer()

    /**
     * Creates and initializes a [VulkanStampEngine] at [width]x[height], seeded with [seed]'s
     * current pixels. Tries the `AHardwareBuffer`-backed path first (docs/Native Rendering Engine
     * Design.md §2's zero-copy interop — real memory, not yet a zero-copy DISPLAY path here, since
     * this call site still reads it back into [seed] every frame same as the plain path would) and
     * falls back to plain device memory if that's unavailable. Returns null (nothing to clean up)
     * if every step fails — the caller stays on the CPU path for this stroke, same as always.
     */
    private fun createSeededGpuEngine(width: Int, height: Int, seed: Bitmap): VulkanStampEngine? {
        // VulkanStampEngine's constructor loads the native library (NativeLibLoader.loadAll()),
        // which THROWS — not returns false — when the .so can't be loaded at all: unit tests
        // (Robolectric has no native code), and in principle any device/build variant that
        // shipped without it. Every other failure mode here (no compute-capable GPU, a rejected
        // format) already returns false cleanly; only the "library isn't there" case needs a
        // catch, so this stays a live-preview fallback to the CPU path instead of crashing the
        // coroutine that would otherwise have gone on to draw the stroke.
        return try {
            val engine = VulkanStampEngine()
            val ready = (engine.initHardwareBufferBacked(width, height) || engine.init(width, height)) &&
                engine.upload(seed)
            if (!ready) {
                engine.destroy()
                null
            } else {
                engine
            }
        } catch (e: Throwable) {
            null
        }
    }

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
                else createProjectWithScreenSize("Untitled")
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
                        dispatch(EditorIntent.SetSavedSelections(project.savedSelections))
                        restoreModel(project)

                        val layersToLoad = layers.filter { it.bitmap == null && it.uri != null }
                        if (layersToLoad.isNotEmpty()) {
                            viewModelScope.launch(dispatchers.io) {
                                // Decoding several full-screen bitmaps can take a while, and this
                                // reads from the `layers` snapshot captured above (before decoding
                                // started) but only to know WHICH layers need a bitmap and WHAT
                                // uri to load — never to build the dispatched result. Building the
                                // result from that snapshot (the old code's `layers.map { ... }`)
                                // and dispatching it as a wholesale SetLayers replace silently
                                // discarded any layer the user added, removed, or edited while
                                // decoding was in flight — the user's own concurrent work, gone
                                // with no error. Instead, collect just the decoded bitmaps here...
                                val decoded = mutableMapOf<String, Bitmap>()
                                layersToLoad.forEach { layer ->
                                    val layerUri = layer.uri ?: return@forEach
                                    val loadedBmp = ImageUtils.loadBitmapAsync(context, layerUri) ?: return@forEach
                                    putLayerBase(layer.id, loadedBmp)
                                    layerStore.initStrokes(layer.id)
                                    decoded[layer.id] = loadedBmp
                                }
                                if (decoded.isEmpty()) return@launch
                                withContext(dispatchers.main) {
                                    // ...and merge them into whatever the LIVE layer list is now,
                                    // touching only the bitmap field of the layers that actually
                                    // decoded. Anything added/removed/edited in the meantime is
                                    // read fresh here and preserved untouched.
                                    val current = _uiState.value.layers
                                    val merged = current.map { layer ->
                                        decoded[layer.id]?.let { layer.copy(bitmap = it) } ?: layer
                                    }
                                    dispatch(EditorIntent.SetLayers(merged))
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
                    // Every other history.clear()/mutation in this file is paired with this call —
                    // without it, undoCount/redoCount keep whatever they were before the project
                    // closed, leaving an enabled Undo control wired to a stack that is now empty.
                    updateHistoryCounts()
                }
            }
        }
    }

    private fun pushHistory() {
        // heightMap stripped alongside bitmap for the same reason: a large runtime-only array with
        // no business sitting in every property-change undo entry (Impasto, item 12).
        history.pushProperty(_uiState.value.layers.map { it.copy(bitmap = null, heightMap = null) })
        updateHistoryCounts()
        val liveIds = _uiState.value.layers.map { it.id }.toSet() + history.referencedLayerIds()
        layerStore.retainOnly(liveIds)
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
                if (!layerStore.removeLastStroke(command.layerId)) {
                    // popUndo already moved this entry's counterpart onto the redo stack on the
                    // assumption the undo would succeed. It didn't — the layer has no cached stroke
                    // list to remove from — so drop that speculative entry rather than leave a Draw
                    // command redoable against a layer LayerStore no longer has anything for, and
                    // make sure undoCount/redoCount reflect the stacks as they actually are now.
                    history.dropTopRedo()
                    updateHistoryCounts()
                    return
                }
                val deltas = command.tileDeltas
                val fastPathHandled = deltas != null && applyTileDeltaFastPath(
                    command.layerId, deltas, command.tileDeltaCanvasWidth, command.tileDeltaCanvasHeight,
                    useAfter = false, emitOp = true,
                )
                if (!fastPathHandled) rebuildLayerBitmap(command.layerId, emitOp = true)
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
                val deltas = command.tileDeltas
                val fastPathHandled = deltas != null && applyTileDeltaFastPath(
                    command.layerId, deltas, command.tileDeltaCanvasWidth, command.tileDeltaCanvasHeight,
                    useAfter = true, emitOp = true,
                )
                if (!fastPathHandled) rebuildLayerBitmap(command.layerId, emitOp = true)
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

        // Impasto (item 12): the height base needs the same fold-old-strokes-in treatment as the
        // bitmap base, on a defensive copy so a failed/discarded bake never corrupts the pristine
        // base other in-flight rebuilds may still be reading.
        val heightWorking = layerStore.heightBase(layerId, base.width * base.height).copyOf()

        viewModelScope.launch(dispatchers.default) {
            try {
                val baked = drawingEngine.composite(
                    base, stale,
                    otherLayers = { _uiState.value.layers.filterNot { it.id == layerId } },
                    heightMap = heightWorking,
                )
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
                    layerStore.putHeightBase(layerId, heightWorking)
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
        val base = layerStore.base(layerId) ?: return
        val strokes = layerStore.strokes(layerId)
        // Impasto (item 12): a fresh copy of the height base, replayed the same way the bitmap
        // itself is -- undo/redo should read the layer's persistent height as it stood *before*
        // this rebuild's strokes, not whatever the live layer.heightMap held a moment ago.
        val heightWorking = layerStore.heightBase(layerId, base.width * base.height).copyOf()

        rebuildJobs[layerId]?.cancel()
        rebuildJobs[layerId] = viewModelScope.launch(dispatchers.default) {
            // Compositing replays strokes through OpenCV (and SLAM for Liquify). Guard it so a
            // failure during undo/redo logs instead of taking down the app — the stroke list and
            // base are unchanged, so the next edit re-renders cleanly.
            try {
                val currentBitmap = drawingEngine.composite(
                    base, strokes,
                    otherLayers = { _uiState.value.layers.filterNot { it.id == layerId } },
                    heightMap = heightWorking,
                )

                // Used by undo/redo: the layer's pixels changed in a way the guest can't replay, so
                // push the whole baked bitmap — but only if something is actually listening. This
                // fires on every undo/redo of a stroke, and bitmapToByteArray is a full-resolution
                // PNG encode; building it for NoOpOpEmitter to drop is real CPU for nothing.
                if (emitOp && opEmitter.isActive) {
                    opEmitter.emit(Op.LayerBitmapReplace(layerId, ImageUtils.bitmapToByteArray(currentBitmap)))
                }

                withContext(dispatchers.main) {
                    _uiState.update { state ->
                        state.copy(
                            layers = state.layers.map {
                                if (it.id == layerId) it.copy(bitmap = currentBitmap, heightMap = heightWorking) else it
                            },
                        )
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

    /**
     * Item 16's undo fast path: applies [tileDeltas] directly onto the layer's *current* live
     * bitmap instead of [rebuildLayerBitmap]'s full stroke-list replay. [useAfter] selects which
     * side of the delta to apply -- `false` for undo ("before"), `true` for redo ("after").
     *
     * Returns `false` (caller falls back to [rebuildLayerBitmap]) when the fast path isn't usable:
     * the layer has no live bitmap to patch, or that bitmap's dimensions don't match what the
     * deltas were captured against (defensive -- not an expected case for a layer, whose
     * dimensions don't change after creation, but cheap to check and refuse rather than assume).
     *
     * Safe by construction from [EditHistory.attachTileDeltas]'s own invariant: a `Draw` command
     * still reachable by undo/redo is guaranteed to be the layer's most recent stroke (or, for
     * redo, the next one to reapply) — see that doc comment — so applying its deltas onto whatever
     * bitmap is currently live is always correct, never stale, regardless of how many other
     * strokes exist beyond it in the layer's full history.
     */
    private fun applyTileDeltaFastPath(
        layerId: String,
        tileDeltas: List<com.hereliesaz.graffitixr.common.azphalt.TileDelta.TileSnapshot>,
        canvasWidth: Int,
        canvasHeight: Int,
        useAfter: Boolean,
        emitOp: Boolean,
    ): Boolean {
        val layer = _uiState.value.layers.find { it.id == layerId } ?: return false
        val current = layer.bitmap ?: return false
        if (current.width != canvasWidth || current.height != canvasHeight) return false
        val patched = SafeBitmap.copy(current) ?: return false
        val pixels = IntArray(canvasWidth * canvasHeight)
        patched.getPixels(pixels, 0, canvasWidth, 0, 0, canvasWidth, canvasHeight)
        if (useAfter) {
            TileDelta.applyAfter(pixels, canvasWidth, tileDeltas)
        } else {
            TileDelta.applyBefore(pixels, canvasWidth, tileDeltas)
        }
        patched.setPixels(pixels, 0, canvasWidth, 0, 0, canvasWidth, canvasHeight)

        // A prior undo/redo on this same layer may still have a full-replay rebuild in flight;
        // this fast path is about to publish a newer, correct bitmap synchronously, so that stale
        // job must not be allowed to overwrite it later -- same discipline rebuildLayerBitmap
        // itself already applies before launching its own replacement job.
        rebuildJobs[layerId]?.cancel()
        rebuildJobs.remove(layerId)

        _uiState.update { state ->
            state.copy(layers = state.layers.map { if (it.id == layerId) it.copy(bitmap = patched) else it })
        }
        if (emitOp && opEmitter.isActive) {
            opEmitter.emit(Op.LayerBitmapReplace(layerId, ImageUtils.bitmapToByteArray(patched)))
        }
        scheduleDiskSave(layerId, patched, layer.uri)
        return true
    }

    fun processNewStroke(layerId: String, activeBitmap: Bitmap, command: StrokeCommand, layer: Layer) {
        layerStore.addStroke(layerId, command)

        history.pushDraw(layerId, command)
        updateHistoryCounts()
        maybeBakeOldStrokes(layerId)

        // Impasto (item 12): continue building on the layer's current live height map (already
        // reflecting every stroke since the last full rebuild, same relationship activeBitmap has
        // to the layer's own base+strokes), falling back to the baked height base on this layer's
        // first live paint since a rebuild/load. A defensive copy, same reasoning as heightWorking
        // elsewhere in this file: applySingleStroke mutates in place.
        val heightWorking = (layer.heightMap ?: layerStore.heightBase(layerId, activeBitmap.width * activeBitmap.height)).copyOf()

        // Tracked in rebuildJobs -- see commitStampStroke's identical comment for why: without
        // this, a fast Undo racing this stroke's own in-flight commit could have its rebuild's
        // publish overwritten by this coroutine's, silently resurrecting the just-undone stroke.
        rebuildJobs[layerId]?.cancel()
        rebuildJobs[layerId] = viewModelScope.launch(dispatchers.default) {
            val otherLayers = _uiState.value.layers.filterNot { it.id == layerId }
            val newBitmap = drawingEngine.applySingleStroke(activeBitmap, command, otherLayers, heightWorking)

            withContext(dispatchers.main) {
                _uiState.update { state ->
                    state.copy(
                        layers = state.layers.map {
                            if (it.id == layerId) it.copy(bitmap = newBitmap, heightMap = heightWorking) else it
                        },
                    )
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
        
        override fun paramNumber(key: String): Double? =
            (_contributionParams.value[key] as? com.hereliesaz.graffitixr.common.azphalt.ParamValue.Num)?.value
        override fun paramBool(key: String): Boolean? =
            (_contributionParams.value[key] as? com.hereliesaz.graffitixr.common.azphalt.ParamValue.Bool)?.value
        override fun paramString(key: String): String? =
            (_contributionParams.value[key] as? com.hereliesaz.graffitixr.common.azphalt.ParamValue.Str)?.value
        
        override fun colorActive(): Int = _uiState.value.activeColor.toArgb()
        override fun colorSetActive(rgba: Int) {
            // Unused for now, but should dispatch intent
        }
        
        override fun assetRead(path: String): ByteArray? = null
        override fun selectionSize(): Int = 0
        override fun selectionRead(): ByteArray = ByteArray(0)
        override fun layerCount(): Int = _uiState.value.layers.size
    }

    // Non-null while ExtensionsPanel is showing one extension's declared filters/tools/commands
    // instead of the top-level extension list — see [onExtensionSelected]'s doc comment for why
    // this second level exists at all.
    private val _extensionContributionsFor =
        kotlinx.coroutines.flow.MutableStateFlow<com.hereliesaz.graffitixr.data.azphalt.InstalledExtension?>(null)
    val extensionContributionsFor: StateFlow<com.hereliesaz.graffitixr.data.azphalt.InstalledExtension?> =
        _extensionContributionsFor.asStateFlow()

    // Non-null while a contribution's own control panel (spec `docs/specs/ui-schema.md`) is showing
    // instead of the contributions list — the extension id + Contribution it belongs to, so onRun
    // knows what to execute.
    private val _activeContribution =
        kotlinx.coroutines.flow.MutableStateFlow<Pair<String, com.hereliesaz.graffitixr.common.azphalt.Contribution>?>(null)
    val activeContribution: StateFlow<Pair<String, com.hereliesaz.graffitixr.common.azphalt.Contribution>?> =
        _activeContribution.asStateFlow()

    private val _activeContributionSchema =
        kotlinx.coroutines.flow.MutableStateFlow<com.hereliesaz.graffitixr.common.azphalt.UiSchema?>(null)
    val activeContributionSchema: StateFlow<com.hereliesaz.graffitixr.common.azphalt.UiSchema?> =
        _activeContributionSchema.asStateFlow()

    // Live control values for the panel above, keyed by UiControl.key — this is exactly what
    // sandboxHost.paramNumber/paramBool/paramString read from at execution time.
    private val _contributionParams =
        kotlinx.coroutines.flow.MutableStateFlow<Map<String, com.hereliesaz.graffitixr.common.azphalt.ParamValue>>(emptyMap())
    val contributionParams: StateFlow<Map<String, com.hereliesaz.graffitixr.common.azphalt.ParamValue>> =
        _contributionParams.asStateFlow()

    /** Flattens a schema's controls (including nested `group` children) into their seed values, by
     *  [com.hereliesaz.graffitixr.common.azphalt.UiControl.key]. */
    private fun seedParams(
        controls: List<com.hereliesaz.graffitixr.common.azphalt.UiControl>,
    ): Map<String, com.hereliesaz.graffitixr.common.azphalt.ParamValue> {
        val out = mutableMapOf<String, com.hereliesaz.graffitixr.common.azphalt.ParamValue>()
        fun visit(list: List<com.hereliesaz.graffitixr.common.azphalt.UiControl>) {
            list.forEach { control ->
                control.defaultParamValue()?.let { out[control.key] = it }
                if (control.controls.isNotEmpty()) visit(control.controls)
            }
        }
        visit(controls)
        return out
    }

    /** [extension]'s own declared filters/tools/commands, each paired with which kind of
     *  contribution it is (a code extension can offer more than one of each). Empty for an
     *  extension with no `contributes` block at all — the single-entry-point kind that still runs
     *  directly from [onExtensionSelected], same as before this list existed. */
    fun contributionsOf(
        extension: com.hereliesaz.graffitixr.data.azphalt.InstalledExtension,
    ): List<Pair<String, com.hereliesaz.graffitixr.common.azphalt.Contribution>> {
        val c = extension.manifest.contributes ?: return emptyList()
        return c.filters.map { "Filter" to it } + c.tools.map { "Tool" to it } + c.commands.map { "Command" to it }
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
        // A code extension can declare several filters/tools/commands, each its OWN entry file
        // (Contribution.entry) distinct from the manifest's single top-level entry — but this used
        // to always run the top-level one regardless, ignoring `contributes` entirely. For an
        // extension with more than one declared contribution that meant either running the wrong
        // one, or — for a manifest with no top-level entry at all, only per-contribution ones —
        // silently doing nothing every single tap. Show a picker over its contributions instead of
        // guessing which one the user meant.
        val ext = extensionRepository.installed.value.find { it.id == id }
        val contributions = ext?.let(::contributionsOf).orEmpty()
        if (ext != null && contributions.isNotEmpty()) {
            _extensionContributionsFor.value = ext
            return
        }
        runCodeExtension(id, entryPath = null)
    }

    /** Runs one specific contribution from the picker [onExtensionSelected] opened — or, when it
     *  declares its own control panel (spec `docs/specs/ui-schema.md`), shows that panel instead so
     *  the user can set parameters before anything actually executes. */
    fun onExtensionContributionSelected(id: String, contribution: com.hereliesaz.graffitixr.common.azphalt.Contribution) {
        val schema = extensionRepository.uiSchemaFor(id, contribution.ui)
        if (schema != null && schema.controls.isNotEmpty()) {
            _activeContribution.value = id to contribution
            _activeContributionSchema.value = schema
            _contributionParams.value = seedParams(schema.controls)
            return
        }
        runCodeExtension(id, entryPath = contribution.entry)
    }

    /** Back out of the contributions picker to the extension list, without running anything. */
    fun onExtensionContributionsBack() {
        _extensionContributionsFor.value = null
    }

    /** Updates one control's live value in the active contribution's params panel. */
    fun onContributionParamChanged(key: String, value: com.hereliesaz.graffitixr.common.azphalt.ParamValue) {
        _contributionParams.value = _contributionParams.value + (key to value)
    }

    /** Runs the active contribution with its current param values — the params panel's "Run"/
     *  declared `button` action (spec: "the apply/commit path for expensive operations"). */
    fun onContributionRun() {
        // sandboxHost.paramNumber/paramBool/paramString read _contributionParams live from inside
        // runCodeExtension's coroutine, so the map has to survive until that finishes — cleared by
        // onExtensionContributionSelected re-seeding it (or onContributionParamsBack/onDismissPanel),
        // never eagerly here.
        val (id, contribution) = _activeContribution.value ?: return
        _activeContribution.value = null
        _activeContributionSchema.value = null
        runCodeExtension(id, entryPath = contribution.entry)
    }

    /** Back out of the params panel to the contributions list, without running anything. */
    fun onContributionParamsBack() {
        _activeContribution.value = null
        _activeContributionSchema.value = null
        _contributionParams.value = emptyMap()
    }

    private fun runCodeExtension(id: String, entryPath: String?) {
        viewModelScope.launch(dispatchers.io) {
            try {
                extensionRepository.executeCodeExtension(id, sandboxHost, entryPath)
                // If it succeeds, maybe dismiss the panel
                withContext(dispatchers.main) {
                    _extensionContributionsFor.value = null
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
                    // Last resort: copy tmp's already-fully-written bytes onto file, rather than
                    // re-compressing straight into it. Re-compressing here was the exact
                    // truncated-on-crash write this whole tmp+rename dance exists to avoid -- and
                    // a second, redundant chance for compress() to throw. tmp is deleted only
                    // after the copy actually lands, so a copy failure (e.g. disk full) leaves
                    // the known-good tmp file behind instead of silently discarding it while file
                    // sits truncated.
                    tmp.copyTo(file, overwrite = true)
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

                putLayerBase(newLayer.id, bitmap)
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
                context.contentResolver.openInputStream(uri)?.use { readBytesBounded(it, MAX_IMPORT_DOCUMENT_BYTES) }
            } catch (e: Exception) {
                null
            }
            if (bytes == null) {
                withContext(dispatchers.main) { toast("Couldn't read that file — it may be too large, or unreadable.") }
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
                    // Bounded, not zip.readBytes(): a compressed entry's declared size can't be
                    // trusted (a crafted small entry can decompress far past it), and this is the
                    // one place in the import path that was reading a zip entry unbounded.
                    val data = readBytesBounded(zip, MAX_PROCREATE_THUMBNAIL_BYTES)
                    found = data?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
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
        putLayerBase(layer.id, bitmap)
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
            putLayerBase(layer.id, full)
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
    private suspend fun createProjectWithScreenSize(name: String): GraffitiProject {
        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels.takeIf { it > 0 } ?: 1080
        val h = metrics.heightPixels.takeIf { it > 0 } ?: 1920
        val project = GraffitiProject(name = name, documentWidth = w, documentHeight = h)
        projectRepository.createProject(project)
        return project
    }

    private suspend fun ensureProjectId(): String {
        _uiState.value.projectId?.let { return it }
        if (projectRepository.currentProject.value == null) {
            createProjectWithScreenSize("Untitled")
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

                putLayerBase(newLayer.id, blankBitmap)
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

    // Also GraffitiXR's: the background bitmap is the camera backdrop its Overlay and Mockup modes
    // composite against. Graffux is a design-only host with no such mode, so nothing here sets one —
    // the exporter still honours it, because a project made in GraffitiXR carries one.
    fun setBackgroundImage(uri: Uri) {
        val projectId = _uiState.value.projectId ?: return
        viewModelScope.launch(dispatchers.io) {
            dispatch(EditorIntent.SetLoading(true))
            val bitmap = ImageUtils.loadBitmapAsync(context, uri)
            if (bitmap != null) {
                val filename = "bg_${UUID.randomUUID()}.png"
                val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(bitmap))
                val localUri = "file://$path".toUri()

                // The transform overload, not updateProject(project.copy(...)): loadBitmapAsync and
                // saveArtifact above are real suspending IO, and a project snapshot taken before them
                // is stale by the time this runs. The plain overload writes that stale snapshot
                // unconditionally, silently reverting anything else that changed in the gap — e.g. the
                // debounced autosave that lands after every stroke. The transform overload applies
                // against whatever the live project actually is at write time and is mutex-protected
                // against a concurrent delete, the same guarantee the editor's own layer save relies on.
                projectRepository.updateProject { current -> current.copy(backgroundImageUri = localUri) }

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
            // The transform overload, not updateProject(project.copy(...)) -- same reasoning as
            // setBackgroundImage above: the plain overload writes whatever snapshot the caller
            // captured unconditionally, silently reverting anything else that changed in the gap
            // (e.g. a debounced autosave landing between this coroutine's dispatch and its own
            // run), and it isn't mutex-protected against a concurrent delete the way this is.
            projectRepository.updateProject { current -> current.copy(backgroundImageUri = null) }
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
                savedSelections = _uiState.value.savedSelections,
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
                    savedSelections = _uiState.value.savedSelections,
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
                exportBitmap.recycle()

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
        if (tool == Tool.TEXT) {
            recordToolUse(tool)
            onAddTextLayer()
            return
        }
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
        // Counted here rather than off the rail's onInteraction: a tool is the thing the user chose,
        // and it is the same choice however they reached it — the strip, a nested rail, the quick
        // menu, or the shortcuts sheet that these counts feed. Counting rail taps would miss three
        // of those four and file the same tool under different names.
        recordToolUse(tool)
        if (tool != Tool.NONE && tool != Tool.PEN && _uiState.value.layers.isEmpty()) {
            onAddBlankLayer(activeToolOverride = tool)
            return
        }
        dispatch(EditorIntent.SetActiveTool(tool))
    }

    /**
     * How often and how recently each tool has been picked, and which ones the user pinned — the
     * three strips the shortcuts sheet offers.
     *
     * Recent and Frequent exist so the sheet is useful before anyone has pinned anything: a
     * shortcuts strip that is empty until you configure it is a shortcuts strip nobody configures.
     */
    val toolUsage: StateFlow<com.hereliesaz.graffitixr.common.model.ToolUsage> =
        settingsRepository.toolUsage.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            com.hereliesaz.graffitixr.common.model.ToolUsage.EMPTY,
        )

    val favoriteTools: StateFlow<List<String>> =
        settingsRepository.favoriteTools.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
        )

    /** Pins or unpins [tool] in the sheet's Favourites strip. */
    fun onToggleFavoriteTool(tool: Tool) = viewModelScope.launch(dispatchers.io) {
        settingsRepository.toggleFavoriteTool(tool)
    }

    private fun recordToolUse(tool: Tool) {
        if (tool == Tool.NONE) return
        val now = System.currentTimeMillis()
        viewModelScope.launch(dispatchers.io) { settingsRepository.recordToolUse(tool, now) }
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

    /**
     * Two jobs, picked by what's actually under the tap and whether the active layer has opted into
     * 3D controls ([Layer.is3D]): with two or more overlapping layers there, cycling which one is
     * active is the useful thing a double-tap can do; with one or none AND a 3D active layer, the
     * gesture is free for its other job -- stepping which axis (X/Y/Z) the next drag rotates around,
     * the natural "what am I rotating right now" toggle for 3D work. Outside that, a double-tap over
     * one-or-no layers stays a no-op, same as before: cycling an axis nothing reads yet would be a
     * silent, unexplained mode change for ordinary flat editing.
     */
    fun onCanvasDoubleTap(offset: Offset, screenWidth: Float, screenHeight: Float) {
        val hits = CanvasHitTest.allHits(_uiState.value.layers, offset, screenWidth, screenHeight)
        if (hits.size < 2) {
            val active = _uiState.value.layers.find { it.id == _uiState.value.activeLayerId }
            if (active?.is3D == true) onCycleRotationAxis()
            return
        }
        val currentId = _uiState.value.activeLayerId
        val currentIdx = hits.indexOf(currentId)
        val nextId = if (currentIdx >= 0) hits[(currentIdx + 1) % hits.size] else hits.first()
        onLayerActivated(nextId)
    }

    fun onRotateLayerHandle(degreesDelta: Float) {
        onTransformGesture(Offset.Zero, 1f, degreesDelta)
    }

    override fun onLayerRemoved(id: String) {
        pushHistory()
        dispatch(EditorIntent.RemoveLayer(id))
        // Deliberately NOT layerStore.remove(id): pushHistory() above recorded a PropertyChange
        // snapshot that includes this layer (bitmap stripped, per currentLayerSnapshot), so undoing
        // this removal has nothing to rebuild its pixels from except LayerStore's still-cached
        // base+strokes. Explicitly dropping the cache here used to restore a permanently blank layer
        // on undo. retainOnly (called from the next pushHistory()) evicts it automatically once it
        // ages out of undo/redo history — the same mechanism the flatten path already relies on.
        evictJobsFor(id)
        opEmitter.emit(Op.LayerRemove(id))
        saveProject()
    }

    /** Cancels and drops [layerId]'s entries in [rebuildJobs]/[textRasterizeJobs], if any. A deleted
     *  layer's own edit isn't coming back (undo rebuilds pixels from [layerStore], not from either
     *  map — see [onLayerRemoved]'s comment), so nothing should keep publishing to this id, and
     *  leaving a completed Job in either map forever would otherwise leak one entry per deleted
     *  layer for the life of the ViewModel. */
    private fun evictJobsFor(layerId: String) {
        rebuildJobs.remove(layerId)?.cancel()
        textRasterizeJobs.remove(layerId)?.cancel()
    }

    override fun onLayerReordered(newOrder: List<String>) {
        val layers = _uiState.value.layers
        // Pre-check against reorderSubset's own refuse conditions (a duplicate id, an unknown id,
        // a dropped slot) before pushing history — a refused reorder used to push a snapshot
        // identical to the current state anyway, consuming a real undo slot with a no-op entry
        // that EditHistory's dedup (which only compares against the immediately preceding entry,
        // not live state) doesn't catch, so the user had to hit Undo twice to reach their last
        // real edit.
        if (LayerListOps.reorderSubset(layers, newOrder) === layers) return
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
                                putLayerBase(id, bmp)
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
    fun onTransformClicked() = dispatch(EditorIntent.ToggleTransformPanel)
    fun onBalanceClicked() = dispatch(EditorIntent.ToggleColorPanel)
    fun onExtensionsClicked() = dispatch(EditorIntent.ToggleExtensionsPanel)
    override fun onDismissPanel() {
        // Otherwise reopening the Extensions panel later could land straight back on a stale
        // contributions picker instead of the extension list this dismiss was meant to leave.
        _extensionContributionsFor.value = null
        _activeContribution.value = null
        _activeContributionSchema.value = null
        _contributionParams.value = emptyMap()
        dispatch(EditorIntent.DismissPanel)
    }

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
        val newRotation = st.viewportRotation + rotationDelta
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

    /**
     * Brackets a run of rapid edits — one drag of a rail slider — into **one** history entry and
     * **one** project write.
     *
     * The rail's sliders have no "drag finished" callback: `azRailSlider` exposes `onValueChange`
     * and nothing else, which is why the panel sliders can bracket themselves ([onLayerEditStart] /
     * [onLayerEditEnd], driven by Material's `onValueChangeFinished`) and the rail's could not. So
     * the bracket closes itself on an idle timeout instead: the first value in a run pushes history,
     * every value applies, and the write happens once the user stops moving.
     *
     * Without it, each emitted sample pushed its own history entry and launched its own un-debounced
     * `saveProject` — so one drag of Opacity buried the undo stack under a frame-by-frame replay of
     * itself and wrote the project manifest dozens of times. (The 1.5 s save debounce covers layer
     * *bitmaps* only; the manifest write is immediate.)
     */
    private var continuousEditJob: Job? = null

    private fun continuousEdit(apply: () -> Unit) {
        // Opening a new run, or continuing one already in flight? Read before the cancel below, and
        // note the coroutine clears the handle itself when it completes — so a run that has already
        // closed correctly opens a fresh history entry rather than silently extending the last one.
        val opening = continuousEditJob == null
        if (opening) pushHistory()
        continuousEditJob?.cancel()
        apply()
        continuousEditJob = viewModelScope.launch(dispatchers.main) {
            delay(CONTINUOUS_EDIT_IDLE_MS)
            continuousEditJob = null
            saveProject()
        }
    }

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
            putLayerBase(layerId, graded)
            graded.recycle()
            layerStore.initStrokes(layerId)
            rebuildLayerBitmap(layerId, emitOp = true)
            dispatch(EditorIntent.SetLoading(false))
        }
    }

    /**
     * Applies a curves adjustment (see [CurvesDialog]) to the active layer's bitmap. [points] are the
     * dialog's normalized (0..1) control points; converted to a 256-entry LUT and applied identically
     * to R/G/B, mirroring [applyInstalledLut]'s pushHistory/SetLoading/rebuild shape.
     */
    fun onCurvesApplied(points: List<Offset>) {
        val layerId = _uiState.value.activeLayerId
        val layer = layerId?.let { id -> _uiState.value.layers.find { it.id == id } }
        val bitmap = layer?.bitmap
        if (layerId == null || bitmap == null) {
            Toast.makeText(context, "Select a layer with an image before applying curves", Toast.LENGTH_SHORT).show()
            return
        }
        pushHistory()
        dispatch(EditorIntent.SetLoading(true))
        viewModelScope.launch(dispatchers.default) {
            val lut = CurvesUtil.calculateAdjustmentCurve(points.map { android.graphics.PointF(it.x, it.y) })
            val adjusted = bitmap.applyCurveLut(lut)
            putLayerBase(layerId, adjusted)
            adjusted.recycle()
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
    // GraffitiXR's, not Graffux's. It grades the artwork against a photographed WALL, and Graffux
    // has no camera, no capture and therefore no wall to pass — every caller would have to invent
    // one. Left unwired here on purpose rather than given a Graffux entry point that grades against
    // something this function was not written to compare against.
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
    fun setStabilizerAlgorithm(algorithm: com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm) =
        dispatch(EditorIntent.SetStabilizerAlgorithm(algorithm))

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

    // No opEmitter.emit here, unlike onToggleVisibility/onToggleAlphaLock: LayerProps (what
    // Op.LayerPropsChange carries) has no room for is3D, and there's no precedent for a rotation-
    // related field going through it -- rotation itself syncs as a computed matrix via
    // Op.LayerTransform, never as individual fields. is3D only gates local UI affordances (the
    // TransformPanel's X/Y fields, double-tap's axis-cycle), so a collaborator not seeing it change
    // is a minor UX inconsistency, not a rendering desync -- the resulting pixels still sync fine.
    override fun onToggleLayer3D(layerId: String) {
        pushHistory()
        dispatch(EditorIntent.ToggleLayer3D(layerId))
        saveProject()
    }

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

    /**
     * Whether [copyLayerModifications] has anything on its clipboard, so a menu can hide "Paste"
     * rather than offer a row that returns immediately.
     *
     * Deliberately a plain property and not UiState: the copied look is a scratch buffer, not part
     * of the document, and a layer menu is rebuilt on every recomposition anyway.
     */
    val hasCopiedLayerModifications: Boolean get() = copiedLayerState != null

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
        dispatch(EditorIntent.SetLoading(true))

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
                putLayerBase(duplicated.id, bmp)
                layerStore.initStrokes(duplicated.id)
            }

            withContext(dispatchers.main) {
                dispatch(EditorIntent.AddLayer(duplicated, resetActivePanel = false))
                opEmitter.emit(Op.LayerAdd(duplicated))
                saveProject()
                dispatch(EditorIntent.SetLoading(false))
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
        alphaLock = alphaLock,
        isInverted = isInverted,
        blendMode = blendMode,
        clipToLayerBelow = clipToLayerBelow,
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
    /**
     * Seams for the commit-path tests, which need to put a layer and a selection in place and then
     * read back what was recorded. Both are `internal` and marked, rather than the tests reaching
     * through public API that means something else — an honest hole beats a contorted route through
     * `onLayerAdded`/`onLayerActivated` that would exercise unrelated code on the way in.
     */
    @androidx.annotation.VisibleForTesting
    internal fun dispatchForTest(intent: EditorIntent) = dispatch(intent)

    /** The commands recorded for [layerId] — what an undo/redo would replay. */
    @androidx.annotation.VisibleForTesting
    internal fun recordedStrokesForTest(layerId: String): List<StrokeCommand> = layerStore.strokes(layerId)

    /** The job currently registered in [textRasterizeJobs] for [layerId], if any -- see its own doc. */
    @androidx.annotation.VisibleForTesting
    internal fun textRasterizeJobForTest(layerId: String): kotlinx.coroutines.Job? = textRasterizeJobs[layerId]

    /**
     * Seeds [layerId]'s pristine base bitmap directly, the way real layer-creation/load code
     * paths do via the private [putLayerBase] -- but which `EditorIntent.SetLayers` (the usual
     * test-seeding intent) never does. Without this, `rebuildLayerBitmap`'s null-base early
     * return makes a test-seeded layer silently skip the `rebuildJobs` cancellation logic
     * entirely, defeating any test built around it.
     */
    @androidx.annotation.VisibleForTesting
    internal fun putLayerBaseForTest(layerId: String, bitmap: Bitmap) = putLayerBase(layerId, bitmap)

    /** The job currently registered in [rebuildJobs] for [layerId], if any -- see its own doc. */
    @androidx.annotation.VisibleForTesting
    internal fun rebuildJobForTest(layerId: String): kotlinx.coroutines.Job? = rebuildJobs[layerId]

    /** Whether item 16's tile-delta fast path actually got attached to the most recent undoable
     *  stroke -- null if there's no undoable Draw entry at all. */
    internal fun topUndoTileDeltaCountForTest(): Int? =
        (history.undoStackTopForTest() as? EditCommand.Draw)?.tileDeltas?.size

    private fun dispatch(intent: EditorIntent) {
        _uiState.update { EditorReducer.reduce(it, intent) }
    }

    /**
     * Records a pristine, immutable copy of [bitmap] as [layerId]'s replay base.
     *
     * Every call site used to be `layerStore.putBase(id, bmp.copy(ARGB_8888, false))`. `Bitmap.copy`
     * returns a platform type, so an out-of-memory copy — and a full-screen layer buffer is the
     * allocation most likely to fail — arrived at `putBase`'s non-null parameter as an NPE, taking
     * the app down mid-edit. Two sites in this same file already wrote `?: return@launch` against
     * that exact call, so the file disagreed with itself about whether it could fail.
     *
     * Failing means the layer keeps no base, so its stroke history cannot be replayed
     * (rebuildLayerBitmap already no-ops for a layer LayerStore never cached) — the visible pixels
     * are untouched and nothing crashes. Aliasing the live bitmap in as its own base would be worse:
     * the next in-place stroke would corrupt the one copy every rebuild and undo depend on.
     */
    private fun putLayerBase(layerId: String, bitmap: Bitmap) {
        val base = SafeBitmap.copy(bitmap, mutable = false)
        if (base == null) {
            android.util.Log.w(
                "EditorViewModel",
                "Out of memory copying the base for layer $layerId; its stroke history won't replay",
            )
            return
        }
        layerStore.putBase(layerId, base)
    }

    /** Emits a co-op LayerPropsChange for the active layer, if any. */
    private fun emitActiveLayerProps() {
        val id = _uiState.value.activeLayerId ?: return
        _uiState.value.layers.find { it.id == id }?.let { opEmitter.emit(Op.LayerPropsChange(id, it.toLayerProps())) }
    }

    /** Returns the IDs of all layers in the same link-group as [layerId].
     *  A group is a contiguous run where each layer above the bottom has isLinked = true. */
    private fun getLinkedGroupIds(layerId: String): Set<String> =
        com.hereliesaz.graffitixr.common.model.LinkOps.linkedGroupIds(_uiState.value.layers, layerId)

    private fun updateLinkedGroup(activeId: String, transform: (Layer) -> Layer) {
        val groupIds = getLinkedGroupIds(activeId)
        _uiState.update { state -> state.copy(layers = state.layers.map { if (it.id in groupIds) transform(it) else it }) }
    }

    override fun onFeedbackShown() = dispatch(EditorIntent.FeedbackShown)

    /** Canonical input path used by DrawingCanvas. Legacy callers keep the Offset overload. */
    fun onStrokeStart(startSample: BrushSample, canvasSize: IntSize) {
        pendingStrokeStartSample = startSample
        try {
            onStrokeStart(Offset(startSample.x, startSample.y), canvasSize, startSample.pressure)
        } finally {
            pendingStrokeStartSample = null
        }
    }

    /** Called when the user first touches the canvas. Prepares a mutable working bitmap for
     *  incremental real-time rendering (all tools except Liquify). */
    fun onStrokeStart(startPoint: Offset, canvasSize: IntSize, pressure: Float = 1f) {
        val state = _uiState.value
        if (state.activeTool == Tool.NONE) return
        val layerId = state.activeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val originalBitmap = layer.bitmap ?: return

        val generation = ++strokeGeneration
        resetLiveCurveState()
        strokeStabilizer.reset()
        val stabilizedStart = strokeStabilizer.stabilize(startPoint, state.stabilizerLevel, state.stabilizerAlgorithm)
        val stabilizedStartPressure = strokeStabilizer.stabilizePressure(pressure, state.stabilizerLevel, state.stabilizerAlgorithm)

        resetStrokePoints(stabilizedStart, stabilizedStartPressure)
        strokeLayerId = layerId
        strokeCanvasW = canvasSize.width
        strokeCanvasH = canvasSize.height
        strokeLayerScale = layer.scale
        strokeLayerOffset = layer.offset
        strokeLayerRotationZ = layer.rotationZ
        // Captured once per stroke: mid-stroke toggles must not desync live paint from the
        // recorded command that undo/redo replays.
        strokeSymmetry = if (state.activeTool != Tool.LIQUIFY) state.symmetryMode else SymmetryMode.NONE
        strokeWrapAroundMode = state.wrapAroundMode
        strokeAlphaLock = layer.alphaLock
        strokeOpacity = if (state.activeTool == Tool.BRUSH) state.brushOpacity else 1f
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
            // A failed copy means no clean original to warp previews from; the stroke simply
            // doesn't start, which beats an NPE inside prepareLiquify on the main thread.
            liquifyOriginalBitmap = SafeBitmap.copy(originalBitmap, mutable = false) ?: return
            slamManager.prepareLiquify(originalBitmap)
            _liveStroke.update { it.copy(layerId = layerId) }
            return
        }

        val stampBrush = activeStampBrush
        if (stampBrush != null && state.activeTool == Tool.BRUSH) {
            // Azphalt stamp brush: stamp dabs incrementally onto a working copy for a live preview.
            // Fix the jitter seed now so the preview, the commit, and history replay all match.
            stampBrushForStroke = stampBrush
            stampShapeForStroke = activeStampShape
            stampGrainForStroke = activeStampGrain
            stampMaskShapeForStroke = activeStampMaskShape
            val currentSeed = System.nanoTime()
            stampSeed = currentSeed
            stampStampedCount = 0
            stampHeldStampedCount = 0
            stampLiveBitmap = null
            stampLiveCanvas = null
            stampLiveHeightMap = null
            stampLiveShadedBitmap = null
            // Guard against a leaked engine if this ever runs without a prior onStrokeEnd/
            // clearTransientStrokeState in between (there shouldn't be one, but destroy() is cheap
            // to call defensively and a leaked Vulkan device is not). Locked: a previous stroke's
            // last GPU batch can still be mid-flight on the background queue when this runs (a fast
            // tap-lift-then-redown beats that batch's own turn on the queue) and reads/destroys this
            // same engine — see stampGpuJob's doc comment.
            synchronized(stampLiveLock) {
                stampGpuEngine?.destroy()
                stampGpuEngine = null
                stampGpuActive = false
            }
            stampGpuUsesMaskedPipeline = false
            stampGpuMaskAlpha8 = null
            stampGpuMaskSize = 0
            stampGpuGrainAlpha8 = null
            stampGpuGrainWidth = 0
            stampGpuGrainHeight = 0
            stampGpuHasDualBrush = false
            stampGpuSecondaryMaskAlpha8 = null
            stampGpuSecondaryMaskSize = 0
            stampGpuJob = null
            stampMappedPoints.clear()
            viewModelScope.launch(dispatchers.default) {
                val work = SafeBitmap.copy(originalBitmap) ?: return@launch
                // GPU live-preview compositor: init the layer at this stroke's bitmap size and seed
                // it with the document's current pixels before any dab is stamped. Both native calls
                // block on GPU work, hence doing this here on Dispatchers.Default alongside the
                // bitmap copy, not on the main thread. A null/failed engine just means every dab
                // this stroke draws through the existing CPU path — see stampGpuActive's doc comment.
                val gpuCompatibleBrush = gpuCompatibleStampBrush(
                    stampBrush, stampShapeForStroke, stampGrainForStroke, stampMaskShapeForStroke,
                )
                val usesMaskedPipeline = gpuCompatibleBrush &&
                    gpuPipelineUsesMaskedShader(stampBrush, stampShapeForStroke, stampGrainForStroke)
                // Rasterized once per stroke at a fixed reference size (see GPU_MASK_REFERENCE_SIZE's
                // doc comment) — cheap CPU bitmap work, safe alongside the bitmap copy on this same
                // background dispatcher.
                val maskAlpha8 = if (usesMaskedPipeline) {
                    alphaChannelBytes(
                        BrushTipMaskCache.tipMask(
                            stampShapeForStroke, GPU_MASK_REFERENCE_SIZE, GPU_MASK_REFERENCE_SIZE,
                            stampBrush.hardness,
                        ),
                    )
                } else null
                // Item 15's texture/grain follow-up: same resolution function the CPU masked-tip
                // path uses, so grainRandomOffsetPerStroke's seeded draw agrees between the two.
                val grainResolution = if (usesMaskedPipeline) {
                    resolveGrainTileAndPhase(stampGrainForStroke, stampBrush, currentSeed)
                } else null
                val grainAlpha8 = grainResolution?.tile?.let { alphaChannelBytes(it) }
                // Item 15's masked/dual-brush follow-up: same fixed-reference-size rasterization
                // as the primary mask, for the secondary tip's own shape. `brush.maskedBrush`
                // being non-null is a per-STROKE decision (every dab gets a `Dab.mask`, see
                // BrushStamps.dabs/dynamicDabs), so this doesn't need to inspect any dab.
                val hasDualBrush = usesMaskedPipeline && stampBrush.maskedBrush != null
                val secondaryMaskAlpha8 = if (hasDualBrush) {
                    alphaChannelBytes(
                        BrushTipMaskCache.tipMask(
                            stampMaskShapeForStroke, GPU_MASK_REFERENCE_SIZE, GPU_MASK_REFERENCE_SIZE,
                            stampBrush.maskedBrush?.hardness ?: 1f,
                        ),
                    )
                } else null
                val gpuEngine = if (gpuCompatibleBrush) createSeededGpuEngine(work.width, work.height, work) else null
                val gpuReady = gpuEngine != null
                // Item 12's live-preview follow-up: a per-stroke scratch height map (a defensive
                // copy of the layer's committed base, never the shared instance itself, so a
                // discarded/failed live preview can never corrupt it) plus a second, display-only
                // bitmap starting identical to `work` -- see stampLiveHeightMap's doc comment for
                // why shading needs its own bitmap rather than mutating `work` in place.
                val heightMapSeed = if (stampBrush.impastoThicknessRate > 0f) {
                    layerStore.heightBase(layerId, work.width * work.height).copyOf()
                } else null
                val shadedBitmapSeed = heightMapSeed?.let { SafeBitmap.copy(work) }
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
                                ),
                            )
                        }
                        stampLiveHeightMap = heightMapSeed
                        stampLiveShadedBitmap = shadedBitmapSeed
                        // Locked: a background onStrokePoint batch (see stampGpuJob) can read/swap
                        // stampGpuEngine concurrently with this publish -- see stampLiveLock's doc
                        // comment for why an unlocked write here would leave that read unsafe.
                        synchronized(stampLiveLock) {
                            stampGpuEngine = if (gpuReady) gpuEngine else null
                            stampGpuActive = gpuReady
                        }
                        stampGpuUsesMaskedPipeline = gpuReady && usesMaskedPipeline
                        stampGpuMaskAlpha8 = if (gpuReady) maskAlpha8 else null
                        stampGpuMaskSize = if (gpuReady && usesMaskedPipeline) GPU_MASK_REFERENCE_SIZE else 0
                        stampGpuGrainAlpha8 = if (gpuReady) grainAlpha8 else null
                        stampGpuGrainWidth = if (gpuReady) grainResolution?.tile?.width ?: 0 else 0
                        stampGpuGrainHeight = if (gpuReady) grainResolution?.tile?.height ?: 0 else 0
                        stampGpuGrainCanvasLocked = stampBrush.grainBehavior == com.hereliesaz.graffitixr.common.azphalt.GrainBehavior.CANVAS_LOCKED
                        stampGpuGrainPhaseX = grainResolution?.phaseX ?: 0f
                        stampGpuGrainPhaseY = grainResolution?.phaseY ?: 0f
                        stampGpuHasDualBrush = gpuReady && hasDualBrush
                        stampGpuSecondaryMaskAlpha8 = if (gpuReady) secondaryMaskAlpha8 else null
                        stampGpuSecondaryMaskSize = if (gpuReady && hasDualBrush) GPU_MASK_REFERENCE_SIZE else 0
                        _liveStroke.update {
                            it.copy(
                                layerId = layerId,
                                bitmap = shadedBitmapSeed ?: work,
                                version = it.version + 1,
                            )
                        }
                    } else {
                        // Superseded by a newer stroke before this coroutine finished — don't leak
                        // the GPU engine this branch may have just stood up.
                        gpuEngine?.destroy()
                    }
                }
            }
            return
        }

        if (state.activeTool in RESAMPLING_TOOLS) {
            // Blur/Sharpen/Smudge previously had NO live preview at all -- buildStrokePaint's
            // RESAMPLING_TOOLS branch built a fully transparent Paint, so nothing rendered while
            // dragging; the whole effect only appeared on finger-up via ImageProcessor.
            // applyToolToBitmap / DrawingEngine's Color Smudge branch. This recomputes the effect
            // from a pristine per-stroke original on a background thread each touch sample
            // (onStrokePoint), cancelling any still-running prior recompute -- the exact pattern
            // liquifyJob/liquifyOriginalBitmap already uses. The commit path is completely
            // untouched and stays the sole source of truth for what actually gets painted.
            resampleJob?.cancel()
            resampleJob = null
            resampleOriginalBitmap = null
            resampleBlurReference = null
            resampleSeed = System.nanoTime()
            val tool = state.activeTool
            viewModelScope.launch(dispatchers.default) {
                val original = SafeBitmap.copy(originalBitmap) ?: return@launch
                if (generation != strokeGeneration || strokeLayerId != layerId) {
                    original.recycle()
                    return@launch
                }
                // Independent of the stroke path, so computed once rather than every touch sample.
                // Matches ImageProcessor.applyToolToBitmap's BLUR/SHARPEN factors exactly (intensity
                // is hardcoded to 0.5f at every StrokeCommand call site in this file already).
                val reference = when (tool) {
                    Tool.BLUR -> ImageProcessor.cheapBlur(original, RESAMPLE_BLUR_FACTOR)
                    Tool.SHARPEN -> ImageProcessor.cheapBlur(original, RESAMPLE_SHARPEN_SOFT_FACTOR)
                    else -> null
                }
                withContext(dispatchers.main) {
                    if (generation != strokeGeneration || strokeLayerId != layerId) {
                        original.recycle()
                        reference?.recycle()
                        return@withContext
                    }
                    resampleOriginalBitmap = original
                    resampleBlurReference = reference
                    _liveStroke.update { it.copy(layerId = layerId, bitmap = original, version = it.version + 1) }
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
            if (generation != strokeGeneration || strokeLayerId != layerId) {
                workBitmap.recycle()
                return@launch
            }
            val workCanvas = Canvas(workBitmap)
            // Clip the live-preview canvas to the lasso once, here. A Canvas clip is sticky until a
            // restore, and this canvas is retained for the whole stroke — so every later segment
            // drawn by onStrokePoint is confined too, without re-clipping per frame.
            SelectionMask.clip(
                workCanvas,
                SelectionMask.bitmapPath(
                    strokeSelection, workBitmap.width, workBitmap.height,
                    layer.scale, layer.offset, layer.rotationZ,
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
            val paint = buildStrokePaint(tool, argb, brushSize * brushScale, feathering, strokeAlphaLock, strokeOpacity)

            // GPU live-preview compositor for the round brush (docs/Native Rendering Engine
            // Design.md §9 Phase 3) — only for the dynamic (round) brush path. The blocking native
            // create() call itself deliberately happens OUTSIDE any lock (it can take real time,
            // and clearTransientStrokeState()/onStrokeEnd must never be blocked waiting on it from
            // the main thread) — only the field publish immediately below is synchronized.
            //
            // strokeGpuEngine/strokeGpuActive are read (via drawCurveRun) and torn down (via
            // clearTransientStrokeState) from other threads while a stroke is live, so every touch
            // of those two fields -- this publish, drawCurveRun's read/fallback-disable, and
            // clearTransientStrokeState's teardown -- shares `liveCurveLock`. Without that, a fast
            // tap-lift landing between this publish and the final generation-check below races
            // clearTransientStrokeState()'s destroy: both sides can end up destroying the same
            // native engine, or drawCurveRun can call into one mid-destroy. `gpuEngine` below is
            // only non-null when this stroke actually won the race to publish, so its two cleanup
            // call sites further down stay engine-object-identity-safe rather than blindly
            // destroying whatever's now in the field.
            val createdGpuEngine = if (strokeDynamics != null) createSeededGpuEngine(workBitmap.width, workBitmap.height, workBitmap) else null
            val gpuEngine = synchronized(liveCurveLock) {
                if (generation != strokeGeneration || strokeLayerId != layerId) {
                    createdGpuEngine?.destroy()
                    null
                } else {
                    // Guard against a leaked engine if this ever runs without a prior onStrokeEnd/
                    // clearTransientStrokeState in between (there shouldn't be one, but destroy()
                    // is cheap to call defensively and a leaked Vulkan device is not).
                    strokeGpuEngine?.destroy()
                    strokeGpuEngine = createdGpuEngine
                    strokeGpuActive = createdGpuEngine != null
                    createdGpuEngine
                }
            }

            // Releases [gpuEngine] only if it's still the one published in strokeGpuEngine --
            // i.e. only if clearTransientStrokeState() hasn't already claimed and destroyed it
            // itself in the meantime. Bare `gpuEngine?.destroy()` at either call site below would
            // reintroduce the double-destroy race this function's field publish was written to
            // close: a fast tap-lift can run clearTransientStrokeState() concurrently with this
            // coroutine reaching either early-exit path.
            fun releaseGpuEngineIfCurrent() {
                if (gpuEngine == null) return
                synchronized(liveCurveLock) {
                    if (strokeGpuEngine === gpuEngine) {
                        strokeGpuEngine = null
                        strokeGpuActive = false
                        gpuEngine.destroy()
                    }
                }
            }

            // Snapshot the collected points at this moment — may include points that arrived
            // during the bitmap-copy phase.
            val catchUpPoints = snapshotStrokePoints()
            val catchUpPressures = snapshotStrokePressures()
            val mappedAll = ImageProcessor.mapScreenToBitmap(
                catchUpPoints, canvasSize.width, canvasSize.height, workBitmap.width, workBitmap.height,
                layerScale, layerOffset, layerRotationZ
            )

            if (mappedAll.isEmpty()) {
                releaseGpuEngineIfCurrent()
                workBitmap.recycle()
                return@launch
            }

            val bw = workBitmap.width.toFloat()
            val bh = workBitmap.height.toFloat()
            // The full transform set for strokeSymmetry -- see symmetryMatrices' doc for why a
            // single hardcoded vertical mirror here would silently break Horizontal/Quadrant/Radial_6.
            val symmetryMats = symmetryMatrices(strokeSymmetry, bw, bh)

            // Draws [seg] with wrap-around tiling, plus its symmetry twins when symmetry is on.
            fun drawPathAll(seg: android.graphics.Path) {
                val targets = ArrayList<android.graphics.Path>(1 + symmetryMats.size)
                targets.add(seg)
                for (m in symmetryMats) {
                    targets.add(android.graphics.Path(seg).apply { transform(m) })
                }
                for (t in targets) {
                    if (strokeWrapAroundMode) {
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
                val points = ArrayList<Offset>(1 + symmetryMats.size)
                points.add(mappedAll[0])
                for (transform in ImageProcessor.symmetryTransforms(strokeSymmetry, bw, bh)) {
                    points.add(transform(mappedAll[0]))
                }
                for (pt in points) {
                    if (strokeWrapAroundMode) {
                        for (dx in -1..1) for (dy in -1..1) workCanvas.drawPoint(pt.x + dx * bw, pt.y + dy * bh, paint)
                    } else {
                        workCanvas.drawPoint(pt.x, pt.y, paint)
                    }
                }
            } else {
                val dyn = strokeDynamics
                if (dyn != null) {
                    // Dynamic brush: each segment at its own velocity-derived width, drawn as a
                    // Catmull-Rom-curved run through the live window once it has real neighbours
                    // on both sides — see feedLiveCurvePoint's doc. Width still advances the same
                    // recursion immediately as each point arrives, only drawing is windowed, so
                    // live pixels match replayed pixels once the stroke commits.
                    feedLiveCurvePoint(
                        workCanvas, paint, workBitmap.width, workBitmap.height, strokeSymmetry,
                        strokeWrapAroundMode, mappedAll[0], 0f, workBitmap,
                        generation = generation,
                    )
                    for (i in 1 until mappedAll.size) {
                        val p = catchUpPressures.getOrNull(i) ?: 1f
                        val width = dyn.next((mappedAll[i] - mappedAll[i - 1]).getDistance(), brushSize * brushScale, p)
                        feedLiveCurvePoint(
                            workCanvas, paint, workBitmap.width, workBitmap.height, strokeSymmetry,
                            strokeWrapAroundMode, mappedAll[i], width, workBitmap,
                            generation = generation,
                        )
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
                if (generation != strokeGeneration || strokeLayerId != layerId) {
                    releaseGpuEngineIfCurrent()
                    workBitmap.recycle()
                    return@withContext
                }
                strokeWorkingBitmap = workBitmap
                strokeWorkingCanvas = workCanvas
                strokePaint = paint
                strokePrevBitmapPoint = lastMapped
                _liveStroke.update { it.copy(
                    layerId = layerId,
                    bitmap = workBitmap,
                    version = it.version + catchUpPoints.size
                )}
            }
        }
    }

    /** Canonical input path used by DrawingCanvas. */
    fun onStrokePoint(sample: BrushSample) {
        pendingStrokePointSample = sample
        try {
            onStrokePoint(Offset(sample.x, sample.y), sample.pressure)
        } finally {
            // The legacy implementation may throttle/return before addStrokePoint. Never leak a
            // skipped sample into the next accepted point.
            pendingStrokePointSample = null
        }
    }

    /** Called for every drag update. Draws only the new segment onto the working bitmap. */
    fun onStrokePoint(currentPoint: Offset, pressure: Float = 1f) {
        val algorithm = _uiState.value.stabilizerAlgorithm
        val stabilizedPoint = strokeStabilizer.stabilize(currentPoint, _uiState.value.stabilizerLevel, algorithm)
        val stabilizedPressure = strokeStabilizer.stabilizePressure(pressure, _uiState.value.stabilizerLevel, algorithm)

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

        addStrokePoint(stabilizedPoint, stabilizedPressure)

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

            // Apply incremental liquify to the native engine
            if (points.size >= 2) {
                val p1 = points[points.size - 2]
                val p2 = points.last()

                // We need to map these screen points to the bitmap space
                val mapped = ImageProcessor.mapScreenToBitmap(
                    listOf(p1, p2), canvasW, canvasH, original.width, original.height,
                    capturedScale, capturedOffset, capturedRotZ
                )

                val strokeArr = floatArrayOf(mapped[0].x, mapped[0].y, mapped[1].x, mapped[1].y)
                slamManager.applyLiquify(strokeArr, brushSize, 0.5f)
            }

            liquifyJob?.cancel()
            liquifyJob = viewModelScope.launch(dispatchers.default) {
                val warpBitmap = SafeBitmap.copy(original) ?: return@launch
                slamManager.bakeLiquify(warpBitmap)

                if (isActive) {
                    withContext(dispatchers.main) {
                        _liveStroke.update { it.copy(
                            bitmap = warpBitmap,
                            version = it.version + 1
                        )}
                    }
                }
            }
            return
        }

        if (_uiState.value.activeTool in RESAMPLING_TOOLS) {
            val original = resampleOriginalBitmap ?: return   // reference not ready yet; points still collected
            val layerId = strokeLayerId ?: return
            val tool = _uiState.value.activeTool
            val points = snapshotStrokePoints()
            if (points.size < 2) return
            val mapped = ImageProcessor.mapScreenToBitmap(
                points, strokeCanvasW, strokeCanvasH, original.width, original.height,
                strokeLayerScale, strokeLayerOffset, strokeLayerRotationZ,
            )
            if (mapped.size < 2) return
            val brushScale = ImageProcessor.screenToBitmapScale(
                strokeCanvasW, strokeCanvasH, original.width, original.height, strokeLayerScale,
            )
            val brushSizePx = _uiState.value.brushSize * brushScale
            val feathering = _uiState.value.brushFeathering
            val symmetry = strokeSymmetry
            val wrap = strokeWrapAroundMode
            val reference = resampleBlurReference
            val generation = strokeGeneration
            val paintColor = _uiState.value.activeColor.toArgb()
            val baseSmudgeSettings = _colorSmudgeSettings.value
            val seed = resampleSeed
            // Aligned 1:1 with snapshotStrokePoints() -- both grow together via addStrokePoint --
            // remapped to bitmap space at the same indices, exactly mirroring how DrawingEngine's
            // own Color Smudge commit branch remaps stroke.brushSamples against `mapped`.
            val mappedSamples = run {
                val samples = snapshotStrokeSamples()
                if (samples.size == mapped.size) {
                    samples.mapIndexed { index, sample ->
                        val p = mapped[index]
                        sample.copy(x = p.x, y = p.y, predicted = false)
                    }
                } else {
                    emptyList()
                }
            }
            resampleJob?.cancel()
            resampleJob = viewModelScope.launch(dispatchers.default) {
                val work = SafeBitmap.copy(original) ?: return@launch
                when (tool) {
                    Tool.BLUR -> if (reference != null) {
                        liveBlurComposite(work, reference, mapped, brushSizePx, feathering, wrap, symmetry)
                    }
                    Tool.SHARPEN -> if (reference != null) {
                        liveSharpenComposite(work, reference, mapped, brushSizePx, feathering, wrap, symmetry)
                    }
                    Tool.SMUDGE -> {
                        // Sample Merged is intentionally not reproduced live (would need recompositing
                        // every other visible layer on every touch sample, its own real cost) -- the
                        // live preview falls back to single-layer sampling; commit always recomputes
                        // the true composite, so the final artwork is unaffected either way.
                        val settings = baseSmudgeSettings.copy(
                            radiusPx = (brushSizePx / 2f).coerceAtLeast(1f),
                            feathering = feathering,
                            wrapAround = false,
                            paintColor = paintColor,
                            symmetryMode = symmetry,
                        )
                        val pixels = IntArray(work.width * work.height)
                        work.getPixels(pixels, 0, work.width, 0, 0, work.width, work.height)
                        ColorSmudgeEngine.apply(
                            pixels, work.width, work.height, mapped, settings,
                            samples = mappedSamples, strokeSeed = seed, sampleSource = null,
                        )
                        work.setPixels(pixels, 0, work.width, 0, 0, work.width, work.height)
                    }
                    else -> {}
                }
                if (isActive) {
                    withContext(dispatchers.main) {
                        if (strokeGeneration == generation && strokeLayerId == layerId) {
                            _liveStroke.update { it.copy(bitmap = work, version = it.version + 1) }
                        } else {
                            work.recycle()
                        }
                    }
                } else {
                    work.recycle()
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
            // Map only the points not yet mapped (per-point transform, so a tail maps the same as the
            // whole) and append to the cache — avoids re-mapping the full stroke every drag frame.
            val all = snapshotStrokePoints()
            val mappedCount = stampMappedPoints.size / 2
            if (all.size > mappedCount) {
                val fresh = ImageProcessor.mapScreenToBitmap(
                    all.subList(mappedCount, all.size), strokeCanvasW, strokeCanvasH, work.width, work.height,
                    strokeLayerScale, strokeLayerOffset, strokeLayerRotationZ
                )
                fresh.forEach { stampMappedPoints.add(it.x); stampMappedPoints.add(it.y) }
            }
            val brushScale = ImageProcessor.screenToBitmapScale(
                strokeCanvasW, strokeCanvasH, work.width, work.height, strokeLayerScale
            )
            val diameterPx = _uiState.value.brushSize * brushScale
            val allSamples = snapshotStrokeSamples()
            val mappedSamples = if (allSamples.size * 2 == stampMappedPoints.size) {
                allSamples.mapIndexed { index, sample ->
                    sample.copy(
                        x = stampMappedPoints[index * 2],
                        y = stampMappedPoints[index * 2 + 1],
                        predicted = false,
                    )
                }
            } else {
                emptyList()
            }
            // Mirrors dynamicDabs()'s own gate (dynamics, maskedBrush's dynamics, or an active taper) --
            // gating on stampBrush.dynamics alone silently dropped taper and masked-tip dynamics from
            // every stroke whose brush used only those, live and on replay alike.
            val hasMaskDynamics = stampBrush.maskedBrush?.dynamics?.isNotEmpty() == true
            val needsDynamicDabs = stampBrush.dynamics.isNotEmpty() || hasMaskDynamics || stampBrush.taper.isActive()
            val dabs = if (needsDynamicDabs && mappedSamples.isNotEmpty()) {
                BrushStamps.dynamicDabs(mappedSamples, diameterPx, stampBrush, stampSeed)
            } else {
                BrushStamps.dabs(stampMappedPoints, diameterPx, stampBrush, stampSeed)
            }
            // Item 13's airbrush held-run dabs, tracked as their own independent incremental
            // prefix (see [stampHeldStampedCount]'s doc comment) computed from the same growing
            // mappedSamples -- painted after this frame's new movement dabs below, rather than
            // concatenated with `dabs` the way DrawingEngine's commit/replay path concatenates
            // them, because the concatenated list is NOT a stable-growing prefix as more samples
            // arrive (a later frame's new movement dabs would need to be inserted *before* an
            // earlier frame's already-painted held dabs to match commit's dabs-then-held ordering,
            // which incremental repaint can't do without repainting the whole stroke every frame).
            // Net effect: within one frame, movement-then-held ordering matches commit exactly;
            // across frames, a held run's dabs can end up painted before a *later* movement dab
            // that arrives after it, which only visibly differs from commit if the stroke's path
            // later crosses back over that same held-still position -- a narrow, documented
            // residual gap, not the "no live build-up at all" gap this replaces.
            val heldDabs = if (stampBrush.airbrushDabsPerSecond > 0f && mappedSamples.isNotEmpty()) {
                AirbrushEngine.heldDabs(
                    mappedSamples, diameterPx, stampBrush,
                    stampBrush.airbrushDabsPerSecond, stampBrush.airbrushStillnessRadiusPx, stampSeed,
                )
            } else {
                emptyList()
            }
            val hasNewMovementDabs = dabs.size > stampStampedCount
            val hasNewHeldDabs = heldDabs.size > stampHeldStampedCount
            if (hasNewMovementDabs || hasNewHeldDabs) {
                val colorArgb = _uiState.value.activeColor.toArgb()
                val secondaryColorArgb = _uiState.value.secondaryColor.toArgb()
                val baseFlow = _uiState.value.brushFlow.coerceIn(0f, 1f)
                val rawFlow = _uiState.value.brushFlow
                // Hoisted above the movement/held-specific branches below so Impasto (further down)
                // can deposit/shade the exact same dabs those branches just painted, without
                // re-slicing after stampStampedCount/stampHeldStampedCount have already advanced.
                val newDabs = if (hasNewMovementDabs) dabs.subList(stampStampedCount, dabs.size).toList() else emptyList()
                val newHeldDabs = if (hasNewHeldDabs) heldDabs.subList(stampHeldStampedCount, heldDabs.size).toList() else emptyList()
                // stampStampedCount/stampHeldStampedCount only ever depend on `dabs`/`heldDabs`,
                // which are pure functions of stampMappedPoints/mappedSamples computed above on this
                // (the calling) thread -- advancing them here, synchronously, is what lets the next
                // onStrokePoint call correctly slice out only ITS new dabs regardless of whether the
                // background job below has actually gotten around to painting this batch yet.
                stampStampedCount = dabs.size
                stampHeldStampedCount = heldDabs.size
                val heightMap = stampLiveHeightMap
                val shadedBitmap = stampLiveShadedBitmap
                val strokeGen = strokeGeneration
                val strokeLayerIdSnapshot = strokeLayerId

                // GPU submit + readback are both blocking native calls (a full command-buffer
                // submit + vkWaitForFences round trip each -- see VulkanStampEngine.cpp) that used
                // to run right here, synchronously, on the caller's thread -- which for every
                // DrawingCanvas sample is the main/UI thread. On a GPU slow enough to miss a frame
                // budget, that stalled the whole app: no new frame drew and no new touch events were
                // even processed until the round trip returned, so the visible stroke fell further
                // and further behind the finger the longer/faster a stroke ran (the reported "gap
                // between the stroke and my touch point" bug). Moving the actual GPU work here, onto
                // dispatchers.default, keeps the calling thread free to keep sampling input; only the
                // presentation of THIS batch's dabs lags, which is exactly what the prediction tail
                // overlay already exists to paper over.
                //
                // `stampGpuJob.join()` below chains every batch onto the previous one so they still
                // run strictly one at a time, in order -- required both because VulkanStampEngine
                // documents that it is not safe to call from multiple threads concurrently, and
                // because each batch's CPU fallback (StampBrushRenderer.paintDabs) and Impasto
                // shading mutate `canvas`/`work`/`heightMap`/`shadedBitmap` in place and would
                // otherwise race a neighbouring batch doing the same. The chain is captured (not
                // read from the field) before launching so this reassignment can't race a
                // concurrently-running previous batch also about to reassign it.
                val prevJob = stampGpuJob
                val brush = stampBrush
                val shape = stampShapeForStroke
                val grain = stampGrainForStroke
                val maskShape = stampMaskShapeForStroke
                val seed = stampSeed
                stampGpuJob = viewModelScope.launch(dispatchers.default) {
                    prevJob?.join()
                    // The stroke this batch was queued for may already be over by the time its turn
                    // comes up (a fast tap-lift-then-redown can win the race) -- onStrokeStart/
                    // clearTransientStrokeState detect that themselves for the engine (via
                    // stampLiveLock's identity check below), but `canvas`/`work`/heightMap/
                    // shadedBitmap have no such guard, so painting into them here would be silently
                    // wasted work at best. Bailing early also means a whole tail of superseded
                    // batches drains near-instantly instead of actually running their GPU/CPU work.
                    if (strokeGeneration != strokeGen || strokeLayerId != strokeLayerIdSnapshot) return@launch

                    // Snapshotted together, under the lock, rather than read as separate field
                    // accesses -- onStrokeStart's (re)publish and this batch's own failure-triggered
                    // teardown below both replace/clear this whole group atomically, and a torn read
                    // across two of them (e.g. a stale `engine` paired with a fresh `usesMasked`)
                    // would dispatch to the wrong shader or read a freed mask buffer.
                    val engine: VulkanStampEngine?
                    val gpuActive: Boolean
                    val usesMasked: Boolean
                    val maskAlpha8: ByteArray?
                    val maskSize: Int
                    val grainAlpha8: ByteArray?
                    val grainWidth: Int
                    val grainHeight: Int
                    val grainLocked: Boolean
                    val grainPhaseX: Float
                    val grainPhaseY: Float
                    val hasDualBrush: Boolean
                    val secondaryMaskAlpha8: ByteArray?
                    val secondaryMaskSize: Int
                    synchronized(stampLiveLock) {
                        engine = stampGpuEngine
                        gpuActive = stampGpuActive
                        usesMasked = stampGpuUsesMaskedPipeline
                        maskAlpha8 = stampGpuMaskAlpha8
                        maskSize = stampGpuMaskSize
                        grainAlpha8 = stampGpuGrainAlpha8
                        grainWidth = stampGpuGrainWidth
                        grainHeight = stampGpuGrainHeight
                        grainLocked = stampGpuGrainCanvasLocked
                        grainPhaseX = stampGpuGrainPhaseX
                        grainPhaseY = stampGpuGrainPhaseY
                        hasDualBrush = stampGpuHasDualBrush
                        secondaryMaskAlpha8 = stampGpuSecondaryMaskAlpha8
                        secondaryMaskSize = stampGpuSecondaryMaskSize
                    }

                    var gpuHandled = false
                    if (hasNewMovementDabs && gpuActive && engine != null) {
                        // GPU path first (docs/Native Rendering Engine Design.md §9 Phase 3) — see
                        // stampGpuActive's doc comment for the fallback contract, and
                        // stampGpuUsesMaskedPipeline's for which of the two shaders this stroke
                        // uses. A failure here disables it for the rest of THIS stroke only — work
                        // is already correctly up to date through the last successful GPU readback
                        // (or, if GPU was never active, was always drawn by the CPU branch below),
                        // so continuing straight into the CPU branch for just `newDabs` is exactly
                        // correct, no re-render of earlier dabs needed either way.
                        gpuHandled = if (usesMasked) {
                            if (maskAlpha8 == null) {
                                false
                            } else {
                                val gpuDabs = newDabs.map { dab ->
                                    MaskedBrushDab(
                                        x = dab.x,
                                        y = dab.y,
                                        radius = dab.radius,
                                        alpha = dab.alpha,
                                        angleDeg = dab.angleDeg,
                                        colorArgb = StampBrushRenderer.resolvedColor(
                                            colorArgb, secondaryColorArgb, brush, dab,
                                        ),
                                        flow = (baseFlow * dab.flowMultiplier).coerceAtLeast(0f),
                                        tipRatio = dab.tipRatio,
                                    )
                                }
                                val secondaryDabs = if (hasDualBrush && newDabs.all { it.mask != null }) {
                                    newDabs.map { dab ->
                                        val maskDab = dab.mask!!
                                        SecondaryBrushDab(
                                            x = maskDab.x,
                                            y = maskDab.y,
                                            radius = maskDab.radius,
                                            tipRatio = maskDab.tipRatio,
                                            alpha = maskDab.alpha,
                                            angleDeg = maskDab.angleDeg,
                                            flowMultiplier = maskDab.flowMultiplier,
                                            keepInside = maskDab.keepInside,
                                        )
                                    }
                                } else {
                                    emptyList()
                                }
                                (!hasDualBrush || newDabs.all { it.mask != null }) &&
                                    engine.stampMaskedDabs(
                                        gpuDabs, brush.hardness.coerceIn(0f, 1f), maskAlpha8,
                                        maskSize, maskSize,
                                        grainAlpha8, grainWidth, grainHeight,
                                        grainLocked, brush.grainScale,
                                        grainPhaseX, grainPhaseY,
                                        secondaryDabs, secondaryMaskAlpha8,
                                        secondaryMaskSize, secondaryMaskSize,
                                    ) && engine.readback(work)
                            }
                        } else {
                            val gpuDabs = newDabs.map { dab ->
                                ResolvedBrushDab(
                                    x = dab.x,
                                    y = dab.y,
                                    radius = dab.radius,
                                    alpha = dab.alpha,
                                    angleDeg = dab.angleDeg,
                                    colorArgb = StampBrushRenderer.resolvedColor(
                                        colorArgb, secondaryColorArgb, brush, dab,
                                    ),
                                    flow = (baseFlow * dab.flowMultiplier).coerceAtLeast(0f),
                                )
                            }
                            engine.stampResolvedDabs(gpuDabs, brush.hardness.coerceIn(0f, 1f)) &&
                                engine.readback(work)
                        }
                    }
                    if (hasNewMovementDabs && !gpuHandled) {
                        if (gpuActive) {
                            // Disable for the rest of THIS stroke only -- release the engine iff
                            // it's still the one this batch snapshotted (identity check): a newer
                            // stroke's onStrokeStart may already have destroyed/replaced it under
                            // the same lock while this batch was doing its (unlocked) GPU work
                            // above, in which case destroying it a second time here would be a
                            // native use-after-free. Mirrors releaseGpuEngineIfCurrent's exact
                            // convention for strokeGpuEngine below.
                            synchronized(stampLiveLock) {
                                if (stampGpuEngine === engine) {
                                    stampGpuActive = false
                                    stampGpuEngine = null
                                    stampGpuUsesMaskedPipeline = false
                                    stampGpuMaskAlpha8 = null
                                    stampGpuMaskSize = 0
                                    stampGpuGrainAlpha8 = null
                                    stampGpuGrainWidth = 0
                                    stampGpuGrainHeight = 0
                                    stampGpuHasDualBrush = false
                                    stampGpuSecondaryMaskAlpha8 = null
                                    stampGpuSecondaryMaskSize = 0
                                    engine?.destroy()
                                }
                            }
                        }
                        StampBrushRenderer.paintDabs(
                            canvas, newDabs, brush, colorArgb, rawFlow,
                            shape, grain, maskShape, seed,
                            secondaryColorArgb,
                        )
                    }
                    if (hasNewHeldDabs) {
                        // Airbrush held dabs are always painted on the CPU, matching the reference
                        // commit/replay path (DrawingEngine's stamp-brush branch deposits held dabs
                        // CPU-only regardless of GPU live-preview availability -- see item 13's
                        // Vulkan target note). There is no GPU dispatch for this secondary dab
                        // source, only for the primary movement dabs above; both draw onto the same
                        // `work` bitmap/`canvas`, so ordering between this block and the movement-
                        // dab block above (movement first, held second, every batch) is
                        // deterministic -- both run on this same serialized background job.
                        StampBrushRenderer.paintDabs(
                            canvas, newHeldDabs, brush, colorArgb, rawFlow,
                            shape, grain, maskShape, seed,
                            secondaryColorArgb,
                        )
                    }
                    // Item 12's live-preview follow-up: deposit height for exactly the dabs just
                    // painted above (both sources), then re-shade only the small region they
                    // touched -- see stampLiveHeightMap's doc comment for why this can't run over
                    // the whole canvas every frame, and shadeInto's doc comment for why it must
                    // read from `work` (raw) rather than the shaded bitmap it writes into.
                    if (brush.impastoThicknessRate > 0f && heightMap != null && shadedBitmap != null) {
                        val impastoDabs = newDabs + newHeldDabs
                        if (impastoDabs.isNotEmpty()) {
                            ImpastoEngine.depositStroke(
                                heightMap, work.width, work.height, impastoDabs,
                                brush.hardness, brush.impastoThicknessRate,
                            )
                            val touched = DirtyRegion.fromDabs(impastoDabs)
                            val region = touched?.let {
                                DirtyRegion(it.left - 1, it.top - 1, it.right + 1, it.bottom + 1)
                            }?.clampTo(work.width, work.height)
                            if (region != null && !region.isEmpty) {
                                val rawPixels = IntArray(work.width * work.height)
                                work.getPixels(rawPixels, 0, work.width, 0, 0, work.width, work.height)
                                val outPixels = IntArray(work.width * work.height)
                                shadedBitmap.getPixels(outPixels, 0, work.width, 0, 0, work.width, work.height)
                                ImpastoEngine.shadeInto(
                                    outPixels, rawPixels, heightMap, work.width, work.height,
                                    region.left, region.top, region.right, region.bottom,
                                    IMPASTO_LIGHT_AZIMUTH_DEG, IMPASTO_LIGHT_ELEVATION_DEG, IMPASTO_LIGHT_STRENGTH,
                                )
                                shadedBitmap.setPixels(outPixels, 0, work.width, 0, 0, work.width, work.height)
                            }
                        }
                    }
                    if (strokeGeneration == strokeGen && strokeLayerId == strokeLayerIdSnapshot) {
                        // Thread-safe by itself (StateFlow.update is lock-free/CAS-based) -- no
                        // dispatchers.main hop needed, which matters here specifically: this job is
                        // joined (awaited) from the main thread elsewhere (clearTransientStrokeState
                        // has no such wait, but a future caller might), and a job that itself needs
                        // the main dispatcher to resume before it can finish would deadlock against
                        // a main-thread wait for it to finish.
                        _liveStroke.update { it.copy(version = it.version + 1) }
                    }
                }
            }
            return
        }

        val canvas = strokeWorkingCanvas ?: return
        val paint = strokePaint ?: return
        val prev = strokePrevBitmapPoint ?: return
        val workBitmap = strokeWorkingBitmap ?: return

        val mapped = ImageProcessor.mapScreenToBitmap(
            listOf(currentPoint), strokeCanvasW, strokeCanvasH, workBitmap.width, workBitmap.height,
            strokeLayerScale, strokeLayerOffset, strokeLayerRotationZ
        ).first()

        val dyn = strokeDynamics
        if (dyn != null) {
            // Dynamic brush width: advance the per-stroke recursion by this segment's length,
            // immediately, every call — the same recursion runs from scratch on commit/replay, so
            // it matches exactly once committed. Drawing itself goes through the live Catmull-Rom
            // curve window instead of a straight chord — see feedLiveCurvePoint's doc for why only
            // width is computed eagerly while drawing is windowed by a point or two.
            val brushScale = ImageProcessor.screenToBitmapScale(
                strokeCanvasW, strokeCanvasH, workBitmap.width, workBitmap.height, strokeLayerScale
            )
            val width = dyn.next((mapped - prev).getDistance(), _uiState.value.brushSize * brushScale, stabilizedPressure)
            feedLiveCurvePoint(
                canvas, paint, workBitmap.width, workBitmap.height, strokeSymmetry,
                strokeWrapAroundMode, mapped, width, workBitmap,
            )
        } else {
            val seg = Path()
            seg.moveTo(prev.x, prev.y)
            seg.lineTo(mapped.x, mapped.y)

            val symmetryMats = symmetryMatrices(strokeSymmetry, workBitmap.width.toFloat(), workBitmap.height.toFloat())
            val segs = ArrayList<Path>(1 + symmetryMats.size)
            segs.add(seg)
            for (m in symmetryMats) {
                segs.add(Path(seg).apply { transform(m) })
            }
            for (s in segs) {
                if (strokeWrapAroundMode) {
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
        }
        strokePrevBitmapPoint = mapped

        _liveStroke.update { it.copy(version = it.version + 1) }
    }

    /** Called when the user lifts their finger. Finalizes the stroke into the layer and undo history. */
    fun onStrokeEnd() {
        val state = _uiState.value
        val layerId = strokeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val points = snapshotStrokePoints()
        // Aligned 1:1 with `points` by index (both grow together — see addStrokePoint). Recorded
        // onto the command so undo/redo replay reproduces the same pressure-responsive width.
        val pressures = snapshotStrokePressures()
        val brushSamples = snapshotStrokeSamples()
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
                state, layer, layerId, points, brushSamples, canvasW, canvasH,
                capturedScale, capturedOffset, capturedRotationZ, stampBrush, stampShapeForStroke,
                stampGrainForStroke, stampMaskShapeForStroke, strokeSelection,
            )
            clearTransientStrokeState()
            return
        }

        if (state.activeTool in RESAMPLING_TOOLS) {
            // These resample the pixels under the stroke, which a Paint can't do — so none of them
            // has a live preview and all commit on finger-up via ImageProcessor.applyToolToBitmap (a
            // full-bitmap pass, hence off the main thread). One branch rather than three copies: the
            // only thing that differs between them is which `tool` goes on the command, and the
            // history push, the co-op emission and the selection clip are identical.
            val base = layer.bitmap ?: run { clearTransientStrokeState(); return }
            val command = StrokeCommand(
                path = points,
                brushSamples = brushSamples,
                colorSmudgeSettings = _colorSmudgeSettings.value.takeIf { state.activeTool == Tool.SMUDGE },
                canvasSize = IntSize(canvasW, canvasH),
                tool = state.activeTool,
                brushSize = state.brushSize,
                brushColor = state.activeColor.toArgb(),
                intensity = 0.5f,
                seed = if (state.activeTool == Tool.SMUDGE) System.nanoTime() else 0L,
                feathering = state.brushFeathering,
                layerScale = capturedScale,
                layerOffset = capturedOffset,
                layerRotationZ = capturedRotationZ,
                symmetryMode = strokeSymmetry,
                wrapAroundMode = strokeWrapAroundMode,
                selection = strokeSelection,
            )
            layerStore.addStroke(layerId, command)
            history.pushDraw(layerId, command)
            updateHistoryCounts()
            maybeBakeOldStrokes(layerId)

            // Tracked in rebuildJobs, same discipline as processNewStroke/commitStampStroke: a
            // glee audit found this launch (and CLONE's/LIQUIFY's below) was never registered,
            // so a fast Undo right after a BLUR/SHARPEN/SMUDGE commit could have its rebuild's
            // publish overwritten by this coroutine's still-in-flight one.
            rebuildJobs[layerId]?.cancel()
            rebuildJobs[layerId] = viewModelScope.launch(dispatchers.default) {
                // Through the replay path rather than a second composite of its own. This branch
                // used to build the clip itself and pass the HARD path straight in, which meant a
                // blur inside a feathered selection committed with a hard edge and then replayed
                // with a soft one — the layer changed the first time it was undone. Everything the
                // composite needs is already on the command, so there is nothing here that
                // DrawingEngine does not do, and doing it there is the only way the two agree.
                //
                // Guarded the same way rebuildLayerBitmap/maybeBakeOldStrokes are: a failure here
                // logs instead of taking the app down. The stroke is already committed to history
                // above regardless, so the worst case is this one commit's visual result not
                // landing — the layer is left exactly as it was, which the next edit re-renders
                // cleanly from.
                try {
                    val resampled = drawingEngine.applySingleStroke(base, command)
                    withContext(dispatchers.main) {
                        _uiState.update { s ->
                            s.copy(
                                layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = resampled) else it },
                            )
                        }
                        _liveStroke.update { it.copy(layerId = null, bitmap = null) }
                        scheduleDiskSave(layerId, resampled, layer.uri)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    android.util.Log.e("EditorViewModel", "Failed to commit $layerId's ${command.tool} stroke", e)
                    withContext(dispatchers.main) {
                        _liveStroke.update { s -> s.copy(layerId = null, bitmap = null) }
                    }
                }
            }
            // Co-op: peers replay the same op from the stroke command. The ordinal comes from the
            // command, not a literal — a literal here would have every sharpen arrive as a blur.
            val coopPoints = ImageProcessor.mapScreenToBitmap(
                points, canvasW, canvasH, base.width, base.height,
                capturedScale, capturedOffset, capturedRotationZ,
            )
            opEmitter.emit(
                Op.StrokeComplete(
                    layerId,
                    BrushStroke(
                        points = coopPoints.flatMap { listOf(it.x, it.y) },
                        colorArgb = state.activeColor.toArgb().toLong() and 0xFFFFFFFFL,
                        brushSize = state.brushSize,
                        brushFeathering = state.brushFeathering,
                        blendModeOrdinal = command.tool.ordinal,
                    )
                )
            )
            clearTransientStrokeState()
            return
        }

        // CLONE has no live preview — a Paint cannot sample pixels from elsewhere on the layer — so
        // whatever the working bitmap holds, it is not this stroke. Its own branch rather than a
        // special case further down, because it has to hold on BOTH commit routes: a quick dab
        // finishes before the background copy lands and takes the fast-stroke fallback, which paints
        // with that same blank Paint and would commit an empty layer. Fixing only the real-time path
        // left exactly that hole, and a ViewModel-level test found it.
        if (state.activeTool == Tool.CLONE) {
            val base = layer.bitmap ?: run { clearTransientStrokeState(); return }
            val command = StrokeCommand(
                path = points,
                brushSamples = brushSamples,
                canvasSize = IntSize(canvasW, canvasH),
                tool = Tool.CLONE,
                brushSize = state.brushSize,
                brushColor = state.activeColor.toArgb(),
                intensity = 0.5f,
                feathering = state.brushFeathering,
                layerScale = capturedScale,
                layerOffset = capturedOffset,
                layerRotationZ = capturedRotationZ,
                symmetryMode = strokeSymmetry,
                wrapAroundMode = strokeWrapAroundMode,
                alphaLock = strokeAlphaLock,
                selection = strokeSelection,
                cloneOffset = cloneOffsetFor(state, points),
            )
            layerStore.addStroke(layerId, command)
            history.pushDraw(layerId, command)
            updateHistoryCounts()
            maybeBakeOldStrokes(layerId)
            // Tracked in rebuildJobs -- see the BLUR/SHARPEN/SMUDGE branch's identical comment.
            rebuildJobs[layerId]?.cancel()
            rebuildJobs[layerId] = viewModelScope.launch(dispatchers.default) {
                val cloned = drawingEngine.applySingleStroke(base, command)
                withContext(dispatchers.main) {
                    _uiState.update { s ->
                        s.copy(
                            layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = cloned) else it },
                        )
                    }
                    _liveStroke.update { it.copy(layerId = null, bitmap = null) }
                    scheduleDiskSave(layerId, cloned, layer.uri)
                }
                if (opEmitter.isActive) {
                    opEmitter.emit(Op.LayerBitmapReplace(layerId, ImageUtils.bitmapToByteArray(cloned)))
                }
            }
            clearTransientStrokeState()
            return
        }

        // Liquify. Its own branch, beside the other tools with no live paint, and for the same
        // reason: it commits through DrawingEngine rather than doing the work itself.
        //
        // It used to bake here directly — `slamManager.bakeLiquify(baked)` on a copy — which bypassed
        // the engine entirely. So the committed warp covered the whole layer while the *replay*, which
        // does go through the engine, confined it to the selection: the layer changed the first time
        // it was undone. Same bug class as the blur, the bucket and the clone; the fourth found in
        // this function. Everything the warp needs is on the command.
        if (state.activeTool == Tool.LIQUIFY) {
            // Fall back to the committed layer bitmap if the original was already cleared (a second
            // onStrokeEnd, or a start that never populated it) rather than NPE.
            val base = liquifyOriginalBitmap ?: layer.bitmap ?: run { clearTransientStrokeState(); return }
            val command = StrokeCommand(
                path = points,
                brushSamples = brushSamples,
                canvasSize = IntSize(canvasW, canvasH),
                tool = Tool.LIQUIFY,
                brushSize = state.brushSize,
                brushColor = state.activeColor.toArgb(),
                intensity = 0.5f,
                feathering = state.brushFeathering,
                layerScale = capturedScale,
                layerOffset = capturedOffset,
                layerRotationZ = capturedRotationZ,
                selection = strokeSelection,
            )
            layerStore.addStroke(layerId, command)
            history.pushDraw(layerId, command)
            updateHistoryCounts()
            maybeBakeOldStrokes(layerId)

            // Tracked in rebuildJobs -- see the BLUR/SHARPEN/SMUDGE branch's identical comment.
            rebuildJobs[layerId]?.cancel()
            rebuildJobs[layerId] = viewModelScope.launch(dispatchers.default) {
                val warped = drawingEngine.applySingleStroke(base, command)
                withContext(dispatchers.main) {
                    _uiState.update { s ->
                        s.copy(
                            layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = warped) else it },
                        )
                    }
                    _liveStroke.update { it.copy(layerId = null, bitmap = null) }
                    scheduleDiskSave(layerId, warped, layer.uri)
                }
            }
            clearTransientStrokeState()
            return
        }

        if (strokeWorkingBitmap == null) {
            // A stroke so fast the background copy hadn't finished: fall back to the full
            // whole-stroke approach.
            val bitmap = layer.bitmap ?: return
            
            val finalBitmap = run {
                // Fast stroke: the background working-bitmap copy never finished before finger-up, so
                // rasterize the whole stroke onto a fresh copy here. Committing `bitmap` unchanged (the
                // old behaviour) silently dropped the stroke — it lived only in history, which isn't
                // replayed on reload, so it vanished on screen and on disk.
                // Bitmap.copy can return null under memory pressure — never construct a Canvas from it
                // unchecked (NPE on the main thread). Fall back to the unmodified bitmap if the copy fails.
                val target = SafeBitmap.copy(bitmap)
                val fastHard = SelectionMask.bitmapPath(
                    strokeSelection, bitmap.width, bitmap.height,
                    capturedScale, capturedOffset, capturedRotationZ,
                )
                val fastRadius = SelectionMask.featherRadius(
                    strokeSelection, bitmap.width, bitmap.height, capturedScale,
                )
                if (target != null && points.isNotEmpty()) {
                    val canvas = android.graphics.Canvas(target)
                    SelectionMask.clip(canvas, SelectionMask.paintClip(fastHard, fastRadius))
                    val brushScale = ImageProcessor.screenToBitmapScale(
                        canvasW, canvasH, target.width, target.height, capturedScale
                    )
                    val paint = buildStrokePaint(
                        state.activeTool, state.activeColor.toArgb(), state.brushSize * brushScale,
                        state.brushFeathering, strokeAlphaLock, strokeOpacity
                    )
                    val mapped = ImageProcessor.mapScreenToBitmap(
                        points, canvasW, canvasH, target.width, target.height,
                        capturedScale, capturedOffset, capturedRotationZ
                    )
                    val bw = target.width.toFloat()
                    val bh = target.height.toFloat()

                    val symmetryMats = symmetryMatrices(strokeSymmetry, bw, bh)
                    fun drawPathAll(seg: android.graphics.Path) {
                        val targets = ArrayList<android.graphics.Path>(1 + symmetryMats.size)
                        targets.add(seg)
                        for (m in symmetryMats) {
                            targets.add(android.graphics.Path(seg).apply { transform(m) })
                        }
                        for (t in targets) {
                            if (strokeWrapAroundMode) {
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
                        val points = ArrayList<Offset>(1 + symmetryMats.size)
                        points.add(mapped[0])
                        for (transform in ImageProcessor.symmetryTransforms(strokeSymmetry, bw, bh)) {
                            points.add(transform(mapped[0]))
                        }
                        for (pt in points) {
                            if (strokeWrapAroundMode) {
                                for (dx in -1..1) for (dy in -1..1) canvas.drawPoint(pt.x + dx * bw, pt.y + dy * bh, paint)
                            } else {
                                canvas.drawPoint(pt.x, pt.y, paint)
                            }
                        }
                    } else if (state.activeTool == Tool.BRUSH) {
                        // Dynamic brush: same recursion the live path and undo replay use. Widths
                        // come from the RAW points (BrushDynamics' speed measure is calibrated to
                        // true touch-sample spacing); geometry is Catmull-Rom-curved per original
                        // segment, same technique as ImageProcessor.drawStrokeDynamic — see its
                        // doc comment for why the two don't share input.
                        val widths = BrushDynamics.segmentWidths(mapped, state.brushSize * brushScale, pressures)
                        val flatMapped = ArrayList<Float>(mapped.size * 2)
                        mapped.forEach { flatMapped.add(it.x); flatMapped.add(it.y) }
                        val curvedSegments = CatmullRom.segments(flatMapped)
                        for (i in 0 until mapped.size - 1) {
                            paint.strokeWidth = widths[i]
                            val run = curvedSegments[i]
                            val seg = android.graphics.Path()
                            seg.moveTo(run[0], run[1])
                            var j = 2
                            while (j < run.size) {
                                seg.lineTo(run[j], run[j + 1])
                                j += 2
                            }
                            drawPathAll(seg)
                        }
                    } else {
                        val seg = android.graphics.Path()
                        seg.moveTo(mapped[0].x, mapped[0].y)
                        for (i in 1 until mapped.size) seg.lineTo(mapped[i].x, mapped[i].y)
                        drawPathAll(seg)
                    }
                }
                // Feathered against the pre-stroke bitmap, matching DrawingEngine — this is the
                // immediate result of a stroke whose recorded command replays through there.
                if (target != null) SelectionMask.feather(bitmap, target, fastHard, fastRadius) else bitmap
            }

            val command = StrokeCommand(
                path = points,
                brushSamples = brushSamples,
                canvasSize = IntSize(canvasW, canvasH),
                tool = state.activeTool,
                brushSize = state.brushSize,
                brushColor = state.activeColor.toArgb(),
                intensity = 0.5f,
                feathering = state.brushFeathering,
                opacity = strokeOpacity,
                pressures = pressures,
                layerScale = capturedScale,
                layerOffset = capturedOffset,
                layerRotationZ = capturedRotationZ,
                symmetryMode = strokeSymmetry,
                wrapAroundMode = strokeWrapAroundMode,
                alphaLock = strokeAlphaLock,
                selection = strokeSelection,
                cloneOffset = cloneOffsetFor(state, points),
            )

            // Add stroke to history
            if (finalBitmap !== bitmap) {
                layerStore.addStroke(layerId, command)
                history.pushDraw(layerId, command)
                updateHistoryCounts()
                maybeBakeOldStrokes(layerId)
            }

            _uiState.update { s ->
                s.copy(
                    layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = finalBitmap) else it },
                )
            }
            _liveStroke.update { it.copy(layerId = null, bitmap = null) }
            scheduleDiskSave(layerId, finalBitmap, layer.uri)
        } else {
            // Real-time path: the working bitmap already contains the complete stroke.
            val workBitmap = strokeWorkingBitmap!!
            val command = StrokeCommand(
                path = points,
                brushSamples = brushSamples,
                canvasSize = IntSize(canvasW, canvasH),
                tool = state.activeTool,
                brushSize = state.brushSize,
                brushColor = state.activeColor.toArgb(),
                intensity = 0.5f,
                feathering = state.brushFeathering,
                opacity = strokeOpacity,
                pressures = pressures,
                layerScale = capturedScale,
                layerOffset = capturedOffset,
                layerRotationZ = capturedRotationZ,
                symmetryMode = strokeSymmetry,
                wrapAroundMode = strokeWrapAroundMode,
                alphaLock = strokeAlphaLock,
                selection = strokeSelection,
                cloneOffset = cloneOffsetFor(state, points),
            )

            // Add stroke to history for undo/redo replay.
            layerStore.addStroke(layerId, command)
            history.pushDraw(layerId, command)
            updateHistoryCounts()
            maybeBakeOldStrokes(layerId)

            // The working bitmap was hard-clipped to the selection when its canvas was made, since a
            // live preview cannot be painted unclipped without spraying paint across the artwork for
            // the length of the drag. That makes it the wrong pixels to commit when the selection is
            // feathered: history replays this stroke through DrawingEngine, which paints unclipped
            // and masks afterwards, so committing the hard-edged preview would leave the layer
            // changing appearance the first time it was undone.
            //
            // So when — and only when — feathering, the stroke is re-rendered through the very path
            // that will replay it. The preview stays on screen until that lands, so there is no
            // flash between the two edges.
            val base = layer.bitmap
            val featherRadius = if (base == null) 0f else SelectionMask.featherRadius(
                strokeSelection, base.width, base.height, capturedScale,
            )
            // The working bitmap was hard-clipped to the selection when its canvas was made, which
            // makes it the wrong pixels to commit under a feathered one: history replays this
            // stroke through DrawingEngine, which paints unclipped and masks afterwards, so
            // committing the hard-edged preview would leave the layer changing appearance the first
            // time it was undone. Re-render through the path that will replay it.
            //
            // (CLONE used to be handled here too. It has its own branch above now, since it must
            // also cover the fast-stroke fallback, which never reaches this code.)
            if (featherRadius > 0f && base != null) {
                val preview = workBitmap
                // Tracked in rebuildJobs -- see the BLUR/SHARPEN/SMUDGE branch's identical comment.
                rebuildJobs[layerId]?.cancel()
                rebuildJobs[layerId] = viewModelScope.launch(dispatchers.default) {
                    val committed = drawingEngine.applySingleStroke(base, command)
                    withContext(dispatchers.main) {
                        _uiState.update { s ->
                            s.copy(
                                layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = committed) else it },
                            )
                        }
                        // Only clear the live-stroke preview if it's still ours -- a newer stroke may
                        // have already started by the time this async rebuild finishes, and clearing
                        // its preview here would flash the wrong (or no) bitmap for that new stroke.
                        _liveStroke.update { s -> if (s.bitmap === preview) s.copy(layerId = null, bitmap = null) else s }
                        scheduleDiskSave(layerId, committed, layer.uri)
                    }
                }
            } else {
                // Commit: working bitmap becomes the displayed layer bitmap.
                _uiState.update { s ->
                    s.copy(
                        layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = workBitmap) else it },
                    )
                }
                _liveStroke.update { it.copy(layerId = null, bitmap = null) }
                scheduleDiskSave(layerId, workBitmap, layer.uri)
            }
        }

        // Co-op sync: replayable brush strokes go as StrokeComplete; Liquify bakes into the
        // bitmap and can't map to a BrushStroke, so it propagates as a whole-bitmap replace.
        if (state.activeTool != Tool.LIQUIFY) {
            val bitmap = layer.bitmap
            if (bitmap != null) {
                val mappedPoints = ImageProcessor.mapScreenToBitmap(
                    points, canvasW, canvasH, bitmap.width, bitmap.height,
                    capturedScale, capturedOffset, capturedRotationZ
                )
                val pointsFlat = mappedPoints.flatMap { listOf(it.x, it.y) }
                val brushStroke = BrushStroke(
                    points = pointsFlat,
                    colorArgb = state.activeColor.toArgb().toLong() and 0xFFFFFFFFL,
                    brushSize = state.brushSize,
                    brushFeathering = state.brushFeathering,
                    blendModeOrdinal = state.activeTool.ordinal,
                    opacity = if (state.activeTool == Tool.BRUSH) state.brushOpacity else 1f,
                    pressures = if (state.activeTool == Tool.BRUSH) pressures else emptyList(),
                )
                opEmitter.emit(Op.StrokeComplete(layerId, brushStroke))
            }
        } else {
            val baked = _uiState.value.layers.find { it.id == layerId }?.bitmap
            if (baked != null && opEmitter.isActive) {
                opEmitter.emit(Op.LayerBitmapReplace(layerId, ImageUtils.bitmapToByteArray(baked)))
            }
        }

        clearTransientStrokeState()
    }

    /**
     * Commit an azphalt stamp-brush stroke: record a replayable [StrokeCommand] (carrying the brush,
     * flow, and the seed fixed at stroke start so undo/redo re-composites identically), then
     * rasterize the whole stroke onto a fresh copy of the layer bitmap via [StampBrushRenderer] off
     * the main thread and publish it. The seed itself is `System.nanoTime()` at stroke start, not
     * derived from the stroke's content -- two visually-identical strokes drawn at different times
     * jitter differently -- but once recorded on the command, replay always reads that same stored
     * value, so [DrawingEngine] reproduces this exact stroke identically on every replay.
     */
    private fun commitStampStroke(
        state: EditorUiState,
        layer: Layer,
        layerId: String,
        points: List<Offset>,
        brushSamples: List<BrushSample>,
        canvasW: Int,
        canvasH: Int,
        scale: Float,
        offset: Offset,
        rotationZ: Float,
        brush: com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush,
        stampShape: Bitmap?,
        stampGrain: Bitmap?,
        stampMaskShape: Bitmap?,
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
            brushSamples = brushSamples,
            canvasSize = IntSize(canvasW, canvasH),
            tool = Tool.BRUSH,
            brushSize = brushSize,
            brushColor = color,
            secondaryBrushColor = state.secondaryColor.toArgb(),
            intensity = 0.5f,
            feathering = state.brushFeathering,
            layerScale = scale,
            layerOffset = offset,
            layerRotationZ = rotationZ,
            stampBrush = brush,
            flow = flow,
            // Reuse the live-preview seed so the committed pixels match what was previewed (no flash).
            seed = stampSeed,
            stampShape = stampShape,
            stampGrain = stampGrain,
            stampMaskShape = stampMaskShape,
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
        // The pre-stroke height base (roadmap item 12): applySingleStroke deposits *this* stroke's own
        // dabs onto it fresh below, so this must be the layer's height map from before this stroke —
        // never stampLiveHeightMap, which already accumulated this same stroke's deposits during the
        // live-preview drag and would double-deposit if reused here.
        val heightWorking = (layer.heightMap ?: layerStore.heightBase(layerId, base.width * base.height)).copyOf()
        // Tracked in rebuildJobs, the same map rebuildLayerBitmap/applyTileDeltaFastPath use to
        // cancel each other's stale publishes: without this, a fast Undo landing right after this
        // stroke's own commit could race it -- undo's rebuild publishes the pre-stroke bitmap, then
        // this coroutine's own publish (already in flight, never cancelled) lands after it and
        // silently resurrects the just-undone stroke's pixels. Cancelling whatever was previously
        // in flight for this layer before starting is the same discipline every other publisher
        // here already follows.
        rebuildJobs[layerId]?.cancel()
        rebuildJobs[layerId] = viewModelScope.launch(dispatchers.default) {
            // Route through the same DrawingEngine.applySingleStroke every other tool's commit and
            // every undo/redo replay already uses, instead of hand-rolling a paintStroke call here.
            // The hand-rolled version used to silently drop grain, the masked/dual tip, secondary
            // color, sensor dynamics, airbrush, and Impasto the instant a stroke committed -- the live
            // preview rendered all of that correctly, then it vanished on finger-up. `command` already
            // carries stampGrain/stampMaskShape/secondaryBrushColor/brushSamples; applyTool reads them.
            val otherLayers = _uiState.value.layers.filterNot { it.id == layerId }
            val target = drawingEngine.applySingleStroke(base, command, otherLayers, heightWorking)
            // Item 16's undo fast path: diff `base` against `target` once, here, while both are
            // already at hand -- pixel-diff based (DirtyRegion.fromPixelDiff), not dab-based, so
            // it doesn't need a resolved dab list this call site doesn't otherwise construct, and
            // stays correct regardless of exactly how applySingleStroke resolved the final pixels.
            // A capture failure (e.g. an OOM building the pixel buffers) is caught and
            // degrades to `null` -- no fast path attached, this stroke's future undo/redo just
            // falls back to the existing full-replay path, same as any stroke this pass doesn't
            // cover.
            val tileDeltas = runCatching {
                val beforePixels = IntArray(base.width * base.height)
                base.getPixels(beforePixels, 0, base.width, 0, 0, base.width, base.height)
                val afterPixels = IntArray(target.width * target.height)
                target.getPixels(afterPixels, 0, target.width, 0, 0, target.width, target.height)
                val dirty = DirtyRegion.fromPixelDiff(beforePixels, afterPixels, base.width, base.height)
                if (dirty == null) {
                    emptyList()
                } else {
                    val grid = TileGrid(base.width, base.height, UNDO_TILE_SIZE)
                    TileDelta.capture(beforePixels, afterPixels, grid, grid.tilesTouching(dirty))
                }
            }.getOrNull()
            withContext(dispatchers.main) {
                _uiState.update { s ->
                    s.copy(
                        layers = s.layers.map {
                            if (it.id == layerId) it.copy(bitmap = target, heightMap = heightWorking) else it
                        },
                    )
                }
                _liveStroke.update { s -> if (s.bitmap === previewBitmap) s.copy(layerId = null, bitmap = null) else s }
                scheduleDiskSave(layerId, target, layer.uri)
                // Attached only after the bitmap publish above, on this same main-dispatcher
                // continuation -- so by the time `command.tileDeltas` is non-null, this stroke's
                // own bitmap is guaranteed already live, closing the one ordering race that would
                // otherwise matter for the fast path (see EditHistory.attachTileDeltas's doc
                // comment for what's still a pre-existing, unrelated race this doesn't fix).
                if (tileDeltas != null) {
                    history.attachTileDeltas(command, tileDeltas, base.width, base.height)
                }
            }
        }
    }

    /**
     * Resets the imperative, in-flight stroke/liquify scratch state. These live as ViewModel fields
     * (not in [EditorUiState]), so the pure reducer can't clear them — any caller that abandons an
     * in-progress stroke (stroke end, mode switch, project load) must invoke this so a later
     * onStrokePoint/onStrokeEnd can't commit to a stale layer or bitmap.
     */
    /**
     * Cancels the in-flight stroke without committing — a second finger landing mid-stroke means
     * the user is gesturing (two-finger tap = undo, pinch = navigate), not painting; Procreate
     * discards the partial stroke the same way. Nothing has been recorded yet (commands are
     * recorded on finger-up), so dropping the live preview is the whole cancel.
     */
    fun onStrokeCancel() {
        liquifyJob?.cancel()
        resampleJob?.cancel()
        _liveStroke.update { it.copy(layerId = null, bitmap = null) }
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
            selection = state.selection,
        )
        layerStore.addStroke(layerId, command)
        history.pushDraw(layerId, command)
        updateHistoryCounts()
        maybeBakeOldStrokes(layerId)
        viewModelScope.launch(dispatchers.default) {
            // Through the replay path, not a second flood of its own. This built the HARD clip and
            // published the result unfeathered, while DrawingEngine's FILL branch feathers — so a
            // bucket fill inside a feathered selection changed edge the first time it was undone.
            val target = drawingEngine.applySingleStroke(base, command)
            withContext(dispatchers.main) {
                _uiState.update { s ->
                    s.copy(layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = target) else it })
                }
                scheduleDiskSave(layerId, target, layer.uri)
            }
            // Fill isn't in the co-op stroke vocabulary; peers get the finished pixels instead.
            if (opEmitter.isActive) {
                opEmitter.emit(Op.LayerBitmapReplace(layerId, ImageUtils.bitmapToByteArray(target)))
            }
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
    /**
     * Procreate's Colour Fill: floods the selection with the active colour.
     *
     * Deliberately not the paint bucket. [Tool.FILL] spreads from a tapped pixel and stops where the
     * colour changes; this fills the *region*, edge to edge, whatever is under it — which is the only
     * one of the two that can fill a selection you drew over textured artwork.
     *
     * With no selection it fills the layer, since "the selection" is then the whole of it — the same
     * reading [onClearLayer] already takes.
     */
    fun onColorFillSelection() {
        val state = _uiState.value
        val layerId = state.activeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val base = layer.bitmap ?: run {
            Toast.makeText(context, "Fill needs a paint layer — vector shapes recolour via Edit", Toast.LENGTH_SHORT).show()
            return
        }
        val argb = state.activeColor.toArgb()
        val command = StrokeCommand(
            path = emptyList(),
            canvasSize = state.selection?.canvasSize ?: IntSize(base.width, base.height),
            tool = Tool.FILL,
            brushSize = 0f,
            brushColor = argb,
            intensity = 1f,
            layerScale = layer.scale,
            layerOffset = layer.offset,
            layerRotationZ = layer.rotationZ,
            selection = state.selection,
            fillSelection = true,
        )
        layerStore.addStroke(layerId, command)
        history.pushDraw(layerId, command)
        updateHistoryCounts()
        maybeBakeOldStrokes(layerId)
        showHud(if (state.selection != null) "Filled selection" else "Filled layer")

        viewModelScope.launch(dispatchers.default) {
            // Straight through the replay path rather than a second implementation of the same
            // composite — the one thing the feather work proved is worth insisting on, since a
            // commit that differs from its replay changes the layer on the first undo.
            val target = drawingEngine.applySingleStroke(base, command)
            withContext(dispatchers.main) {
                _uiState.update { s ->
                    s.copy(layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = target) else it })
                }
                scheduleDiskSave(layerId, target, layer.uri)
            }
            if (opEmitter.isActive) {
                opEmitter.emit(Op.LayerBitmapReplace(layerId, ImageUtils.bitmapToByteArray(target)))
            }
        }
    }

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
            selection = state.selection,
            clearAll = true,
        )
        layerStore.addStroke(layerId, command)
        history.pushDraw(layerId, command)
        updateHistoryCounts()
        maybeBakeOldStrokes(layerId)
        showHud(if (state.selection != null) "Cleared selection" else "Cleared layer")

        // Tracked in rebuildJobs -- see the BLUR/SHARPEN/SMUDGE branch's identical comment: without
        // this, a fast Undo racing this clear's own in-flight publish could have its rebuild's
        // publish overwritten by this coroutine's, silently resurrecting the just-undone clear.
        rebuildJobs[layerId]?.cancel()
        rebuildJobs[layerId] = viewModelScope.launch(dispatchers.default) {
            val work = SafeBitmap.copy(base) ?: return@launch
            val hard = SelectionMask.bitmapPath(
                command.selection, work.width, work.height, layer.scale, layer.offset, layer.rotationZ,
            )
            // Same feather contract as DrawingEngine, and it has to be: this is the immediate
            // result, and the recorded command replays through there on undo/redo. If only one of
            // the two feathered, the layer would change appearance the first time you undid.
            val radius = SelectionMask.featherRadius(command.selection, work.width, work.height, layer.scale)
            val canvas = Canvas(work)
            SelectionMask.clip(canvas, SelectionMask.paintClip(hard, radius))
            canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            val target = SelectionMask.feather(base, work, hard, radius)
            withContext(dispatchers.main) {
                _uiState.update { s ->
                    s.copy(layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = target) else it })
                }
                scheduleDiskSave(layerId, target, layer.uri)
            }
            if (opEmitter.isActive) {
                opEmitter.emit(Op.LayerBitmapReplace(layerId, ImageUtils.bitmapToByteArray(target)))
            }
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
        val upper = layers.find { it.id == layerId } ?: return
        // Siblings only — never reach across a group boundary by flat-list index. `layers` is a
        // flat list where a group's children sit contiguously, so `layers[index - 1]` used to
        // silently grab whatever was physically adjacent: the group CONTAINER itself (when upper
        // was a group's bottom child — deleting it along with upper orphaned the rest of that
        // group's children out of the rail entirely, still painting but unreachable), a layer
        // inside a *different* group, or a layer one level up the tree. A group container also
        // can't be merged into — it has no bitmap of its own.
        val siblings = layers.filter { it.parentId == upper.parentId }
        val siblingIndex = siblings.indexOfFirst { it.id == upper.id }
        val lower = siblings.getOrNull(siblingIndex - 1)
        if (lower == null || lower.type == LayerType.GROUP) {
            Toast.makeText(context, "Nothing below this layer to merge into", Toast.LENGTH_SHORT).show()
            return
        }
        pushHistory()
        dispatch(EditorIntent.SetLoading(true))

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
                // Inherits lower's parentId (== upper's, they're siblings) so the merge result
                // stays in the same group instead of dropping to the root level.
                parentId = lower.parentId,
                uri = "file://$path".toUri(),
                bitmap = merged,
            )

            withContext(dispatchers.main) {
                val current = _uiState.value.layers
                // Re-resolve positions: the list can have changed while the composite was running.
                val stillThere = current.any { it.id == upper.id } && current.any { it.id == lower.id }
                if (!stillThere) {
                    dispatch(EditorIntent.SetLoading(false))
                    return@withContext
                }
                val at = current.indexOfFirst { it.id == lower.id }
                putLayerBase(mergedLayer.id, merged)
                layerStore.initStrokes(mergedLayer.id)
                val next = current.filterNot { it.id == upper.id || it.id == lower.id }
                    .toMutableList()
                    .apply { add(at.coerceIn(0, size), mergedLayer) }
                dispatch(EditorIntent.ReplaceLayers(next, mergedLayer.id))
                evictJobsFor(upper.id)
                evictJobsFor(lower.id)
                opEmitter.emit(Op.LayerRemove(upper.id))
                opEmitter.emit(Op.LayerRemove(lower.id))
                opEmitter.emit(Op.LayerAdd(mergedLayer))
                saveProject()
                showHud("Merged down")
                dispatch(EditorIntent.SetLoading(false))
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
        val current = layers.find { it.id == layerId } ?: return
        // Siblings only — same reasoning as onMergeDown above. layers[index + 1] used to grab
        // whatever was physically next in the flat list regardless of parentId, which could pull
        // an unrelated top-level layer into an existing group (inheriting that group's opacity,
        // blend mode and visibility along the way) or create a group nested inside another group
        // that the rail then has no way to open (a nested group's own hidden menu never mounts).
        val siblings = layers.filter { it.parentId == current.parentId }
        val siblingIndex = siblings.indexOfFirst { it.id == current.id }
        val above = siblings.getOrNull(siblingIndex + 1)
        if (above == null) {
            Toast.makeText(context, "Nothing above this layer to group with", Toast.LENGTH_SHORT).show()
            return
        }
        val newGroupId = UUID.randomUUID().toString()
        // Pre-check against LayerListOps.group's own refuse conditions (an id collision, or —
        // reachable even with the sibling-only lookup above, since siblings can already share a
        // parent — the two layers already being grouped together) before pushing history. A
        // refused group used to push a snapshot identical to the current state anyway, consuming
        // a real undo slot with a no-op entry that EditHistory's dedup (which only compares
        // against the immediately preceding entry, not live state) doesn't catch.
        if (LayerListOps.group(layers, layerId, above.id, newGroupId, "Group") === layers) return
        pushHistory()
        dispatch(EditorIntent.GroupLayers(layerId, above.id, newGroupId, "Group"))
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
        toRemove.forEach(::evictJobsFor)
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

    // ── Selection (Procreate's lasso and its modes) ───────────────────────────────────────────

    /**
     * Folds the region the finger just drew into the selection, under the current
     * [EditorUiState.selectionOp].
     *
     * The polygon is thinned first — a traced loop arrives at touch-event rate, and every retained
     * vertex is re-mapped on every paint op the selection clips. A loop too small to enclose
     * anything leaves the selection alone rather than clearing it: under Add or Remove a stray tap
     * must not throw away the region being built up.
     */
    fun onSelectionEnd(points: List<Offset>, canvasSize: IntSize) {
        val simplified = SelectionGeometry.simplify(points)
        val state = _uiState.value
        dispatch(EditorIntent.SetSelection(
            SelectionGeometry.compose(state.selection, simplified, canvasSize, state.selectionOp)
        ))
    }

    /**
     * Procreate's Automatic selection: selects the contiguous run of similar colour under [at].
     *
     * The one mode that reads the artwork rather than the gesture, so it is the one that has to
     * leave screen space and come back. The wand floods the active layer's pixels, the flood is
     * traced back out to polygons, and those are mapped to screen space through the exact inverse of
     * the transform the paint uses — after which it is an ordinary ring stack that composes with
     * Add/Remove, moves, inverts and feathers like anything drawn by hand.
     *
     * Off the main thread: a flood fill plus a contour trace over a full-resolution layer is far too
     * much to do between two frames.
     */
    fun onAutoSelect(at: Offset, canvasSize: IntSize) {
        val state = _uiState.value
        val layer = state.layers.find { it.id == state.activeLayerId } ?: return
        val bitmap = layer.bitmap ?: run {
            Toast.makeText(context, "Automatic needs a paint layer", Toast.LENGTH_SHORT).show()
            return
        }
        val tolerance = state.magicWandTolerance
        val op = state.selectionOp
        val current = state.selection
        viewModelScope.launch(dispatchers.default) {
            val seed = ImageProcessor.mapScreenToBitmap(
                listOf(at), canvasSize.width, canvasSize.height, bitmap.width, bitmap.height,
                layer.scale, layer.offset, layer.rotationZ,
            ).firstOrNull() ?: return@launch
            val mask = MagicWand.mask(bitmap, seed.x.toInt(), seed.y.toInt(), tolerance)
                ?: return@launch
            val traced = ContourTrace.contours(mask, bitmap.width, bitmap.height)
            if (traced.isEmpty()) return@launch
            val rings = traced.map { ring ->
                ring.copy(
                    path = SelectionGeometry.simplify(
                        ImageProcessor.mapBitmapToScreen(
                            ring.path, canvasSize.width, canvasSize.height,
                            bitmap.width, bitmap.height, layer.scale, layer.offset, layer.rotationZ,
                        ),
                        // Gentler than a traced lasso's thinning. The wand's vertices are already
                        // only the turns, so anything aggressive here rounds off the corners that
                        // are the whole reason the region was traced rather than boxed.
                        minSpacing = 1.5f,
                    ),
                )
            }.filter { it.isUsable }
            val wand = Selection(rings, canvasSize).takeIf { it.isUsable } ?: return@launch
            withContext(dispatchers.main) {
                // Composed through the same path a drawn region takes, so Add and Remove work on a
                // wand selection exactly as they do on a lasso — including wand-then-lasso mixes.
                // No `?: wand` fallback: compose() returning null (Remove with nothing selected)
                // must stay a no-op, per its own documented contract -- a fallback here used to
                // substitute the whole freshly-traced wand region instead, silently turning a
                // Remove-from-nothing into a de facto New.
                dispatch(EditorIntent.SetSelection(
                    if (op == SelectionOp.NEW) wand
                    else wand.rings.fold(current) { acc, ring ->
                        SelectionGeometry.compose(
                            acc, ring.path, canvasSize,
                            // A traced hole stays a hole whatever the user picked: Add means "add
                            // this region", and the region includes its holes.
                            if (ring.additive) op else SelectionOp.REMOVE,
                        )
                    }
                ))
            }
        }
    }

    fun onClearSelection() = dispatch(EditorIntent.SetSelection(null))

    fun onSetMagicWandTolerance(tolerance: Int) =
        dispatch(EditorIntent.SetMagicWandTolerance(tolerance.coerceIn(0, 255)))

    fun onSetSelectionFeather(featherPx: Float) =
        dispatch(EditorIntent.SetSelectionFeather(featherPx))

    /**
     * The editor canvas measured itself. Records the size, and re-expresses anything authored
     * against a different one.
     *
     * A selection, a clone source and a warp grid are all stored in world (container) space —
     * screen space with the viewport camera undone, same as a stroke path — sized against
     * [EditorUiState.canvasSize]. Rotating the device recreates the Activity but not this ViewModel,
     * so all three would survive into a canvas where their numbers mean somewhere else — the
     * marching ants and the hit-test would move (they read the raw coordinates) while the paint
     * would not (it maps through the selection's own recorded canvasSize), leaving a marquee that
     * marks one region and a clip that confines paint to another.
     *
     * The re-expression is a round trip through the active layer's pixels: the same world→bitmap
     * map the paint uses, then back out against the new canvas. That lands the region on exactly the
     * artwork it was drawn on, which is the thing the user actually chose — unlike a proportional
     * rescale, which would shear it when the aspect ratio changes. It has nothing to do with the
     * viewport camera itself (pan/zoom/rotate never call this), only with the canvas's measured
     * size changing under the same camera pose.
     */
    fun onCanvasSizeChanged(size: IntSize) {
        if (size.width <= 0 || size.height <= 0) return
        val state = _uiState.value
        if (state.canvasSize == size) return
        val previous = state.canvasSize
        dispatch(EditorIntent.SetCanvasSize(size))
        if (previous.width <= 0 || previous.height <= 0) return

        val layer = state.layers.find { it.id == state.activeLayerId }
        val bitmap = layer?.bitmap
        fun reframe(points: List<Offset>): List<Offset> {
            if (bitmap == null || layer == null) return points
            val inBitmap = ImageProcessor.mapScreenToBitmap(
                points, previous.width, previous.height, bitmap.width, bitmap.height,
                layer.scale, layer.offset, layer.rotationZ,
            )
            return ImageProcessor.mapBitmapToScreen(
                inBitmap, size.width, size.height, bitmap.width, bitmap.height,
                layer.scale, layer.offset, layer.rotationZ,
            )
        }

        state.selection?.let { selection ->
            if (selection.canvasSize == size) return@let
            dispatch(EditorIntent.SetSelection(
                selection.copy(
                    rings = selection.rings.map { it.copy(path = reframe(it.path)) },
                    canvasSize = size,
                )
            ))
        }
        state.cloneSource?.let { src ->
            dispatch(EditorIntent.SetCloneSource(reframe(listOf(src)).firstOrNull() ?: src))
        }
        if (state.warpHandles.isNotEmpty()) {
            dispatch(EditorIntent.SetWarpHandles(reframe(state.warpHandles)))
        }
    }

    // ── Distort / Warp ───────────────────────────────────────────────────────────────────────

    /**
     * The layer's pixels as they were before the current warp session started.
     *
     * Every re-bake goes from this, never from the last result. Warping a warp resamples pixels that
     * have already been resampled, so dragging a handle back and forth would soften the artwork a
     * little each time and never recover it — the layer would visibly rot under an indecisive user.
     */
    private var warpOriginalBitmap: Bitmap? = null

    /** The in-flight resample launched by the last [onWarpHandleReleased], if any -- tracked so a
     *  second release before the first resample finishes cancels it instead of racing it, and so
     *  [onApplyWarp] can cancel a still-running one before recomputing the final warp itself. */
    private var warpJob: kotlinx.coroutines.Job? = null

    /** Picks Freeform / Distort / Warp, laying a fresh handle grid over the layer for the last two. */
    fun onSetTransformMode(mode: TransformMode) {
        val state = _uiState.value
        // Leaving a session in progress has to resolve it. Each handle release writes deformed
        // pixels into the layer with no command and no disk save, so simply dropping the session —
        // which switching mode used to do — left the layer showing a deformation that was in no
        // history entry and on no disk, silently reverted by the next undo or restart. Switching
        // away keeps the work, which is the reading that loses nothing: Cancel is still there for
        // the user who wants it thrown away.
        if (state.transformMode != TransformMode.FREEFORM && state.warpHandles.isNotEmpty()) {
            onApplyWarp()
        }
        dispatch(EditorIntent.SetTransformMode(mode))
        warpOriginalBitmap = null
        if (mode == TransformMode.FREEFORM) return
        // Same reset the bitmap==null branch just below already does — this branch used to just
        // `return`, leaving TransformMode set to a non-FREEFORM mode with no active layer behind
        // it. The rail then shows a stranded Apply/Cancel pair: Apply reads activeLayerId first
        // and returns before ever resetting the mode, so only Cancel could dismiss it.
        val layer = state.layers.find { it.id == state.activeLayerId } ?: run {
            Toast.makeText(context, "${mode.label} needs a layer to work on", Toast.LENGTH_SHORT).show()
            dispatch(EditorIntent.SetTransformMode(TransformMode.FREEFORM))
            return
        }
        val bitmap = layer.bitmap ?: run {
            Toast.makeText(context, "${mode.label} needs a paint layer", Toast.LENGTH_SHORT).show()
            dispatch(EditorIntent.SetTransformMode(TransformMode.FREEFORM))
            return
        }
        // Through SafeBitmap like every other allocation of this size in the editor: this runs on
        // the main thread, and an OOM here would take the app down mid-edit rather than degrade.
        warpOriginalBitmap = SafeBitmap.copy(bitmap) ?: run {
            Toast.makeText(context, "Not enough memory to start ${mode.label}", Toast.LENGTH_SHORT).show()
            dispatch(EditorIntent.SetTransformMode(TransformMode.FREEFORM))
            return
        }
        // The measured canvas, not strokeCanvasW/H — those are set only by onStrokeStart and are
        // zero until the user has painted, which made a Distort opened on a freshly loaded project
        // lay its handles out in bitmap pixels on a screen overlay.
        val canvasW = state.canvasSize.width.takeIf { it > 0 } ?: bitmap.width
        val canvasH = state.canvasSize.height.takeIf { it > 0 } ?: bitmap.height
        // The grid starts on the layer's own corners, mapped out to screen — so the handles sit
        // exactly on the artwork's edges rather than on the viewport's, whatever the layer's
        // scale, offset and rotation happen to be.
        val corners = ImageWarp.identityGrid(
            mode.gridSize, bitmap.width.toFloat(), bitmap.height.toFloat(),
        )
        dispatch(EditorIntent.SetWarpHandles(
            ImageProcessor.mapBitmapToScreen(
                corners, canvasW, canvasH, bitmap.width, bitmap.height,
                layer.scale, layer.offset, layer.rotationZ,
            )
        ))
    }

    /**
     * Moves one handle. Cheap and synchronous — the re-bake waits for the finger to lift.
     *
     * [to] is in world (container) space, like the rest of the grid: the overlay that reports the
     * drag converts the finger's screen position back through the camera first. Storing the raw
     * screen point put one handle in a different space from its fifteen neighbours, which is what
     * tore single nodes out of the mesh whenever the canvas had been panned, zoomed or rotated.
     */
    fun onWarpHandleMoved(index: Int, to: Offset) {
        val handles = _uiState.value.warpHandles
        if (index !in handles.indices) return
        dispatch(EditorIntent.SetWarpHandles(handles.toMutableList().also { it[index] = to }))
    }

    /**
     * Re-renders the layer from the pre-warp original through the current handles.
     *
     * Called when a handle is *released*, not while it moves: a full-resolution resample per drag
     * frame would stutter, and one on release is fast enough to read as immediate.
     */
    fun onWarpHandleReleased() {
        val state = _uiState.value
        val original = warpOriginalBitmap ?: return
        val layerId = state.activeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val handles = state.warpHandles
        if (handles.isEmpty()) return
        val canvasW = state.canvasSize.width.takeIf { it > 0 } ?: original.width
        val canvasH = state.canvasSize.height.takeIf { it > 0 } ?: original.height
        // A second release before the first resample finishes must cancel it -- otherwise whichever
        // finishes last wins the publish below, and an older release's result can land after a newer
        // one, visibly snapping the preview back to an earlier handle position.
        warpJob?.cancel()
        warpJob = viewModelScope.launch(dispatchers.default) {
            val inBitmap = ImageProcessor.mapScreenToBitmap(
                handles, canvasW, canvasH, original.width, original.height,
                layer.scale, layer.offset, layer.rotationZ,
            )
            val warped = ImageWarp.warp(original, inBitmap) ?: return@launch
            withContext(dispatchers.main) {
                _uiState.update { s ->
                    s.copy(layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = warped) else it })
                }
            }
        }
    }

    /**
     * Keeps the warp: the deformed pixels become the layer, undoably, and the grid resets.
     *
     * One history entry for the whole session, pushed here rather than per handle drag — so an undo
     * restores the pre-warp pixels in a single step, which is what someone who has spent a minute
     * nudging sixteen handles and changed their mind actually wants. (The handle releases along the
     * way write pixels but record nothing; this is the only thing that records.)
     */
    fun onApplyWarp() {
        val state = _uiState.value
        val layerId = state.activeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val original = warpOriginalBitmap
        val handles = state.warpHandles
        val canvasW = state.canvasSize.width.takeIf { it > 0 } ?: (original?.width ?: 0)
        val canvasH = state.canvasSize.height.takeIf { it > 0 } ?: (original?.height ?: 0)
        // onWarpHandleReleased's own resample is async: tapping Apply right after the last release
        // could otherwise read layer.bitmap before that resample has published, committing and
        // disk-saving pixels from an earlier handle position than what's on screen. Cancel it and
        // recompute the final warp here instead of trusting whatever landed last.
        warpJob?.cancel()
        val bitmap = if (original != null && handles.isNotEmpty()) {
            val inBitmap = ImageProcessor.mapScreenToBitmap(
                handles, canvasW, canvasH, original.width, original.height,
                layer.scale, layer.offset, layer.rotationZ,
            )
            ImageWarp.warp(original, inBitmap)?.also { warped ->
                _uiState.update { s ->
                    s.copy(layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = warped) else it })
                }
            }
        } else {
            layer.bitmap
        }
        // Dropped, not recycled. A resample kicked off by the last handle release may still be
        // reading it on another dispatcher, and recycling underneath that read draws from freed
        // memory. Releasing the reference is enough.
        warpOriginalBitmap = null
        dispatch(EditorIntent.SetTransformMode(TransformMode.FREEFORM))
        if (bitmap == null || handles.isEmpty()) return
        // Recorded as a command rather than rebased into the layer, so it undoes by replay like a
        // stroke does. It lands *after* the strokes already on this layer, which is the order it was
        // applied in: the pixels it deformed were those strokes' output.
        val command = StrokeCommand(
            path = emptyList(),
            canvasSize = IntSize(canvasW, canvasH),
            tool = Tool.NONE,
            brushSize = 0f,
            brushColor = 0,
            intensity = 0f,
            layerScale = layer.scale,
            layerOffset = layer.offset,
            layerRotationZ = layer.rotationZ,
            warpHandles = handles,
        )
        layerStore.addStroke(layerId, command)
        history.pushDraw(layerId, command)
        updateHistoryCounts()
        maybeBakeOldStrokes(layerId)
        scheduleDiskSave(layerId, bitmap, layer.uri)
        if (opEmitter.isActive) {
            viewModelScope.launch(dispatchers.default) {
                opEmitter.emit(Op.LayerBitmapReplace(layerId, ImageUtils.bitmapToByteArray(bitmap)))
            }
        }
        showHud("Applied")
    }

    /** Throws the warp away and puts the original pixels back. */
    fun onCancelWarp() {
        val state = _uiState.value
        val layerId = state.activeLayerId
        val original = warpOriginalBitmap
        if (layerId != null && original != null) {
            val restored = SafeBitmap.copy(original)
            if (restored != null) {
                _uiState.update { s ->
                    s.copy(layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = restored) else it })
                }
            }
        }
        // Dropped rather than recycled, for the same reason as onApplyWarp.
        warpOriginalBitmap = null
        dispatch(EditorIntent.SetTransformMode(TransformMode.FREEFORM))
    }

    // ── Copy / Cut / Paste ───────────────────────────────────────────────────────────────────

    /**
     * The selected pixels, waiting to be pasted.
     *
     * A field rather than UiState: it is a full-canvas bitmap, and state is compared on every
     * recomposition. The UI only ever needs to know whether there *is* one, which travels as
     * [EditorUiState.hasClipboard].
     */
    private var selectionClipboard: Bitmap? = null

    /**
     * Takes the selected pixels of the active layer, honouring the feather.
     *
     * Returns whether it actually took them. Every failure here leaves the *previous* clipboard
     * intact, so "is the clipboard non-null" cannot answer that question — and [onCutSelection]
     * has to know, because it is about to erase the pixels this was supposed to have saved.
     */
    fun onCopySelection(showToast: Boolean = true): Boolean {
        val state = _uiState.value
        val layer = state.layers.find { it.id == state.activeLayerId } ?: return false
        val base = layer.bitmap ?: run {
            Toast.makeText(context, "Copy needs a paint layer", Toast.LENGTH_SHORT).show()
            return false
        }
        val clip = SelectionMask.bitmapPath(
            state.selection, base.width, base.height, layer.scale, layer.offset, layer.rotationZ,
        )
        val radius = SelectionMask.featherRadius(state.selection, base.width, base.height, layer.scale)
        val lifted = SelectionMask.lift(base, clip, radius) ?: return false
        // Not recycled here: a paste already in flight on another dispatcher may still be reading
        // it. Dropping the reference is enough — the collector takes it once nothing holds it,
        // whereas recycling underneath a live read draws from freed memory.
        selectionClipboard = lifted
        dispatch(EditorIntent.SetHasClipboard(true))
        if (showToast) showHud(if (state.selection != null) "Copied selection" else "Copied layer")
        return true
    }

    /**
     * Copy, then clear what was copied. Two existing operations rather than a third code path —
     * but only if the first one worked: [onCopySelection] gives up when the full-canvas lift can't
     * be allocated, and clearing anyway would turn Cut into Delete exactly when the user is least
     * able to recover from it.
     */
    fun onCutSelection() {
        if (!onCopySelection(showToast = false)) {
            Toast.makeText(context, "Couldn't cut — the copy didn't succeed", Toast.LENGTH_LONG).show()
            return
        }
        onClearLayer()
        showHud(if (_uiState.value.selection != null) "Cut selection" else "Cut layer")
    }

    /**
     * Drops the clipboard onto a **new layer**, which is what Procreate does and the only choice
     * that is reversible without history: pasting into the current layer would destroy whatever it
     * landed on, and the user has not asked for that by pressing Paste.
     *
     * Lands in register with where it was cut from, because [SelectionMask.lift] keeps the pixels at
     * full canvas size rather than cropping them to the selection's bounds.
     */
    fun onPasteSelection() {
        val source = selectionClipboard ?: return
        val projectId = _uiState.value.projectId ?: return
        pushHistory()
        viewModelScope.launch(dispatchers.io) {
            val bmp = SafeBitmap.copy(source) ?: return@launch
            val filename = "layer_paste_${UUID.randomUUID()}.png"
            val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(bmp))
            val pasted = Layer(
                id = UUID.randomUUID().toString(),
                name = "Pasted",
                bitmap = bmp,
                uri = "file://$path".toUri(),
            )
            putLayerBase(pasted.id, bmp)
            layerStore.initStrokes(pasted.id)
            withContext(dispatchers.main) {
                dispatch(EditorIntent.AddLayer(pasted, resetActivePanel = false))
                opEmitter.emit(Op.LayerAdd(pasted))
                showHud("Pasted as a new layer")
                saveProject()
            }
        }
    }

    // ── Save / Load selections ───────────────────────────────────────────────────────────────

    /** Puts the current selection aside under a name, replacing any saved under the same one. */
    /**
     * The first "Selection N" that is not already taken.
     *
     * Not `size + 1`, which collides the moment anything has been forgotten: save three, delete
     * "Selection 2", and the next save is offered the name "Selection 3" — which
     * [onSaveSelection] then quietly replaces, because it de-duplicates by name. The user is told
     * their selection was saved while a different one is destroyed.
     */
    fun nextSelectionName(): String {
        val taken = _uiState.value.savedSelections.mapTo(HashSet()) { it.name }
        var n = taken.size + 1
        while ("Selection $n" in taken) n++
        return "Selection $n"
    }

    fun onSaveSelection(name: String) {
        val state = _uiState.value
        val selection = state.selection ?: return
        val trimmed = name.trim().ifBlank { nextSelectionName() }
        // Replacing by name is deliberate — saving over a name you typed should overwrite it — but
        // it is only safe because the *generated* names can no longer collide by accident.
        val replaced = state.savedSelections.any { it.name == trimmed }
        val next = state.savedSelections.filterNot { it.name == trimmed } +
            SavedSelection(trimmed, selection)
        dispatch(EditorIntent.SetSavedSelections(next))
        showHud(if (replaced) "Replaced “$trimmed”" else "Saved “$trimmed”")
        saveProject()
    }

    /**
     * Recalls a saved selection.
     *
     * Composed through the same path a drawn region takes, so Add and Remove apply to a loaded
     * selection too — loading one into an existing region unions or cuts it, which is what makes
     * saved selections building blocks rather than only bookmarks.
     */
    fun onLoadSelection(name: String) {
        val state = _uiState.value
        val saved = state.savedSelections.find { it.name == name } ?: return
        if (state.selectionOp == SelectionOp.NEW) {
            dispatch(EditorIntent.SetSelection(saved.selection))
        } else {
            // No `?: saved.selection` fallback -- same reasoning as onAutoSelect: compose()
            // returning null (Remove with nothing selected) must stay a no-op, not silently load
            // the whole saved selection as if New had been picked.
            val composed = saved.selection.rings.fold(state.selection) { acc, ring ->
                SelectionGeometry.compose(
                    acc, ring.path, saved.selection.canvasSize,
                    if (ring.additive) state.selectionOp else SelectionOp.REMOVE,
                )
            }
            dispatch(EditorIntent.SetSelection(composed))
        }
    }

    fun onDeleteSavedSelection(name: String) {
        dispatch(EditorIntent.SetSavedSelections(
            _uiState.value.savedSelections.filterNot { it.name == name }
        ))
        saveProject()
    }

    // ── Clone ────────────────────────────────────────────────────────────────────────────────

    /** Aims the clone brush. A tap does this while the tool is armed but unaimed. */
    fun onSetCloneSource(at: Offset) = dispatch(EditorIntent.SetCloneSource(at))

    /** Forgets the source, so the next tap picks a new one. */
    fun onResetCloneSource() = dispatch(EditorIntent.SetCloneSource(null))

    /**
     * The screen-space vector from a clone stroke to the pixels it copies, or null for any other
     * tool.
     *
     * Measured from the point the stroke *starts*, not from the canvas origin, which is what makes
     * the source track the brush: begin a second stroke somewhere else and it samples from the same
     * relative place, so painting back and forth duplicates a whole region rather than smearing one
     * fixed patch over everything.
     */
    private fun cloneOffsetFor(state: EditorUiState, points: List<Offset>): Offset? {
        if (state.activeTool != Tool.CLONE) return null
        val source = state.cloneSource ?: return null
        val start = points.firstOrNull() ?: return null
        return source - start
    }

    fun onSetSelectionShape(shape: com.hereliesaz.graffitixr.common.model.SelectionShape) =
        dispatch(EditorIntent.SetSelectionShape(shape))

    fun onSetSelectionOp(op: SelectionOp) =
        dispatch(EditorIntent.SetSelectionOp(op))

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
            // `path` carries a representative outline so the command reads sensibly on its own;
            // the clip is built from `selection`, which carries the full ring recipe and whether it
            // was inverted.
            path = selection.outline,
            canvasSize = selection.canvasSize,
            tool = Tool.SELECT,
            brushSize = 0f,
            brushColor = 0,
            intensity = 0f,
            layerScale = layer.scale,
            layerOffset = layer.offset,
            layerRotationZ = layer.rotationZ,
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
            )
            val moved = SelectionMask.moveRegion(
                base, clipPath, d.x, d.y,
                SelectionMask.featherRadius(selection, base.width, base.height, layer.scale),
            )
            withContext(dispatchers.main) {
                _uiState.update { s ->
                    s.copy(layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = moved) else it })
                }
                dispatch(EditorIntent.SetSelection(selection.translated(delta)))
                scheduleDiskSave(layerId, moved, layer.uri)
            }
            // Not in the co-op stroke vocabulary; peers get the finished pixels instead.
            if (opEmitter.isActive) {
                opEmitter.emit(Op.LayerBitmapReplace(layerId, ImageUtils.bitmapToByteArray(moved)))
            }
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
    fun onSetOnionSkinPastCount(count: Int) = dispatch(EditorIntent.SetOnionSkinPastCount(count))
    fun onSetOnionSkinFutureCount(count: Int) = dispatch(EditorIntent.SetOnionSkinFutureCount(count))
    fun onSetAnimationFrameDurationMs(ms: Int) = dispatch(EditorIntent.SetAnimationFrameDurationMs(ms))
    fun onSetAnimationLoopMode(mode: com.hereliesaz.graffitixr.common.model.AnimationLoopMode) =
        dispatch(EditorIntent.SetAnimationLoopMode(mode))
    fun onSetAnimationRange(start: Int, end: Int) = dispatch(EditorIntent.SetAnimationRange(start, end))

    /** The current frame's hold count — see [com.hereliesaz.graffitixr.common.model.Layer.frameHoldCount]. */
    fun currentFrameHoldCount(): Int =
        AnimationFrames.topLevelFrames(_uiState.value.layers)
            .getOrNull(_uiState.value.activeFrameIndex)?.frameHoldCount ?: 1

    /** Sets the current frame's hold count — see [com.hereliesaz.graffitixr.common.model.Layer.frameHoldCount]. */
    fun onSetFrameHoldCount(count: Int) {
        val frame = AnimationFrames.topLevelFrames(_uiState.value.layers)
            .getOrNull(_uiState.value.activeFrameIndex) ?: return
        dispatch(EditorIntent.SetFrameHoldCount(frame.id, count))
    }

    /**
     * [animationRangeStart]..[animationRangeEnd] resolved against the current frame count: the -1
     * end sentinel becomes the last frame, and both ends are clamped so a range that outlived a
     * frame deletion (or was never touched) still names real frames. Empty only when there are no
     * frames at all. Public so the Animation window can show the actual numeric range rather than
     * the raw, possibly-sentinel state fields.
     */
    fun resolvedPlaybackRange(state: EditorUiState = _uiState.value): IntRange {
        val lastIndex = AnimationFrames.topLevelFrames(state.layers).size - 1
        if (lastIndex < 0) return IntRange.EMPTY
        val end = if (state.animationRangeEnd < 0) lastIndex else state.animationRangeEnd.coerceAtMost(lastIndex)
        val start = state.animationRangeStart.coerceIn(0, lastIndex)
        return start.coerceAtMost(end)..end
    }

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

    /**
     * Plays within [resolvedPlaybackRange] (Krita's playback range), not necessarily every frame —
     * so a subrange can loop/preview without touching the layer stack. Each frame's own
     * [com.hereliesaz.graffitixr.common.model.Layer.frameHoldCount] (Krita's hold frame) multiplies
     * how long *that* frame is held before advancing.
     */
    private fun startPlayback() {
        val startRange = resolvedPlaybackRange()
        if (startRange.last - startRange.first < 1) return
        dispatch(EditorIntent.SetAnimationPlaying(true))
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch(dispatchers.main) {
            var index = _uiState.value.activeFrameIndex.coerceIn(startRange)
            var forward = true
            while (isActive) {
                val holdCount = AnimationFrames.topLevelFrames(_uiState.value.layers)
                    .getOrNull(index)?.frameHoldCount?.coerceAtLeast(1) ?: 1
                kotlinx.coroutines.delay(_uiState.value.animationFrameDurationMs.toLong() * holdCount)
                val range = resolvedPlaybackRange()
                if (range.last - range.first < 1) break
                index = index.coerceIn(range)
                when (_uiState.value.animationLoopMode) {
                    com.hereliesaz.graffitixr.common.model.AnimationLoopMode.LOOP ->
                        index = if (index >= range.last) range.first else index + 1
                    com.hereliesaz.graffitixr.common.model.AnimationLoopMode.PING_PONG -> {
                        if (forward && index >= range.last) forward = false
                        else if (!forward && index <= range.first) forward = true
                        index = (index + if (forward) 1 else -1).coerceIn(range)
                    }
                    com.hereliesaz.graffitixr.common.model.AnimationLoopMode.ONCE -> {
                        if (index >= range.last) break
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
     * Exports [resolvedPlaybackRange] as an animated GIF in Downloads — Krita's playback range, so a
     * subrange can be exported without touching the layer stack. Each frame composites only its own
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
        val range = resolvedPlaybackRange(state)
        stopPlayback()
        viewModelScope.launch(dispatchers.default) {
            dispatch(EditorIntent.SetLoading(true))
            try {
                val metrics = context.resources.displayMetrics
                val dir = File(context.cacheDir, "animation").apply { mkdirs() }
                val file = File(dir, "animation_${System.currentTimeMillis()}.gif")
                val written = com.hereliesaz.graffitixr.feature.editor.animation.AnimationGifWriter.write(
                    file = file,
                    range = range,
                    frameDurationMs = state.animationFrameDurationMs,
                    loopMode = state.animationLoopMode,
                    holdCountAt = { index -> frames.getOrNull(index)?.frameHoldCount ?: 1 },
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
            val project = createProjectWithScreenSize("Untitled $n")
            val projectId = project.id

            val (width, height) = newLayerSize()
            val blankBitmap = createBitmap(width, height)
            val filename = "layer_${UUID.randomUUID()}.png"
            val localUri = try {
                val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(blankBitmap))
                "file://$path".toUri()
            } catch (_: Exception) { null }

            val bgLayer = Layer(
                id = UUID.randomUUID().toString(),
                name = "Background",
                isSketch = true,
                bitmap = blankBitmap,
                uri = localUri,
            )

            withContext(dispatchers.main) {
                putLayerBase(bgLayer.id, blankBitmap)
                layerStore.initStrokes(bgLayer.id)
                dispatch(EditorIntent.AddLayer(bgLayer))
                saveProject()
            }
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
                else createProjectWithScreenSize("Untitled")
            }
        }
    }

    /**
     * Draws one already-curved run (interleaved `x0,y0,x1,y1,…`, bitmap space — one output of
     * [CatmullRom.segments]) onto [canvas] with [paint]'s current stroke width, replicated for
     * [symmetryMode] and tiled for [wrapAroundMode] exactly as every other stroke draw in this
     * class does, so a curved live segment and a straight one (the no-dynamics tools that never
     * reach [feedLiveCurvePoint]) can't drift apart on how either gets mirrored/tiled.
     */
    /**
     * Stamps [run] (a curved segment, interleaved `[x0,y0,x1,y1,…]`) as a train of solid filled
     * round dabs at `paint.strokeWidth / 2` radius, spaced by [BrushStamps.place] at
     * [ROUND_BRUSH_DAB_SPACING_FRACTION] of the diameter — the same arc-length-walk spacing model the azphalt
     * stamp brushes use, applied here to the round brush too so both share one rendering primitive
     * (a real brush engine stamps; it doesn't stroke a variable-width path). `paint.style` is
     * forced to `FILL` for the duration of this call and restored after — the shared [paint] this
     * runs on is also used with `Style.STROKE` for other tools (eraser, blur, smudge) in the same
     * stroke lifetime elsewhere, so this must never leak.
     */
    /**
     * `Matrix` equivalents of [ImageProcessor.symmetryTransforms], for call sites that mirror an
     * `android.graphics.Path` directly instead of individual dab centres. Every transform in that
     * set (mirror, rotation) is affine, so transforming every point of a curved path -- including
     * its Bezier control points -- via one of these matrices is exactly equivalent to transforming
     * the curve itself. Kept as a literal parallel enumeration rather than deriving each Matrix
     * from the Offset closures, since Matrix has no generic "wrap an arbitrary point function"
     * constructor; regression coverage (item: symmetry modes) is what actually guards the two
     * staying in sync, not this comment alone.
     */
    private fun symmetryMatrices(mode: SymmetryMode, w: Float, h: Float): List<android.graphics.Matrix> {
        val cx = w / 2f
        val cy = h / 2f
        return when (mode) {
            SymmetryMode.NONE -> emptyList()
            SymmetryMode.VERTICAL -> listOf(android.graphics.Matrix().apply { setScale(-1f, 1f, cx, 0f) })
            SymmetryMode.HORIZONTAL -> listOf(android.graphics.Matrix().apply { setScale(1f, -1f, 0f, cy) })
            SymmetryMode.QUADRANT -> listOf(
                android.graphics.Matrix().apply { setScale(-1f, 1f, cx, 0f) },
                android.graphics.Matrix().apply { setScale(1f, -1f, 0f, cy) },
                android.graphics.Matrix().apply { setScale(-1f, -1f, cx, cy) },
            )
            SymmetryMode.RADIAL_6 -> (1..5).map { k ->
                android.graphics.Matrix().apply { setRotate(60f * k, cx, cy) }
            }
        }
    }

    private fun drawCurveRun(
        canvas: Canvas,
        paint: Paint,
        run: FloatArray,
        bitmapWidth: Int,
        bitmapHeight: Int,
        symmetryMode: SymmetryMode,
        wrapAroundMode: Boolean,
        targetBitmap: Bitmap,
    ) {
        val radius = max(paint.strokeWidth / 2f, 0.5f)
        val centres = BrushStamps.place(run.toList(), max(radius * ROUND_BRUSH_DAB_SPACING_FRACTION, 1f))

        // GPU path first (docs/Native Rendering Engine Design.md §9 Phase 3) — same fallback
        // contract as the azphalt stamp-brush path: a failure disables it for the rest of THIS
        // stroke only, and `targetBitmap` is already correct up through the last successful GPU
        // readback (or was always CPU-drawn if GPU was never active), so falling straight into the
        // CPU loop below for just this run's dabs is exactly right either way. Skipped entirely for
        // wrapAroundMode — replicating every dab 9x to match the CPU tiling is real work this pass
        // doesn't do, so wraparound strokes stay on the CPU path, same as a textured stamp tip does.
        if (strokeGpuActive && !wrapAroundMode) {
            val engine = strokeGpuEngine
            val gpuHandled = engine != null && run {
                // Same full transform set drawDab uses -- see its comment for why a single
                // hardcoded vertical mirror here would silently break Horizontal/Quadrant/Radial_6.
                val symmetryExtras = ImageProcessor.symmetryTransforms(symmetryMode, bitmapWidth.toFloat(), bitmapHeight.toFloat())
                val gpuDabs = ArrayList<BrushDab>(centres.size / 2 * (1 + symmetryExtras.size))
                var k = 0
                while (k < centres.size) {
                    gpuDabs.add(BrushDab(centres[k], centres[k + 1], radius, 1f, 0f))
                    for (transform in symmetryExtras) {
                        val p = transform(Offset(centres[k], centres[k + 1]))
                        gpuDabs.add(BrushDab(p.x, p.y, radius, 1f, 0f))
                    }
                    k += 2
                }
                // Paint.alpha is a separate multiplier from paint.color's own alpha channel for
                // Android's Canvas — the shader only ever multiplies baseAlpha * dab.alpha, so both
                // have to be pre-folded into one alpha channel handed across the JNI boundary.
                val baseAlpha = (paint.color ushr 24) and 0xFF
                val combinedAlpha = (baseAlpha * paint.alpha / 255).coerceIn(0, 255)
                val colorForGpu = (combinedAlpha shl 24) or (paint.color and 0x00FFFFFF)
                // hardness = 1: a solid filled circle (Paint.Style.FILL, no gradient) is the
                // CPU path's equivalent of the shader's hard-core-to-the-edge profile.
                engine.stampDabs(gpuDabs, colorForGpu, 1f) && engine.readback(targetBitmap)
            }
            if (gpuHandled) return
            strokeGpuActive = false
            strokeGpuEngine?.destroy()
            strokeGpuEngine = null
        }

        val originalStyle = paint.style
        paint.style = Paint.Style.FILL
        val bw = bitmapWidth.toFloat()
        val bh = bitmapHeight.toFloat()
        var j = 0
        while (j < centres.size) {
            drawDab(canvas, paint, centres[j], centres[j + 1], radius, bw, bh, symmetryMode, wrapAroundMode)
            j += 2
        }
        paint.style = originalStyle
    }

    /** Draws one filled round dab, mirrored per [symmetryMode] and tiled per [wrapAroundMode] — the
     *  same transform set [drawCurveRun]'s stroked-path predecessor applied to a whole segment, now
     *  applied per dab centre instead. `paint.style` must already be `FILL`; unlike [drawCurveRun]
     *  this does not save/restore it, since it's meant to be called many times per style toggle. */
    private fun drawDab(
        canvas: Canvas,
        paint: Paint,
        cx: Float,
        cy: Float,
        radius: Float,
        bitmapWidth: Float,
        bitmapHeight: Float,
        symmetryMode: SymmetryMode,
        wrapAroundMode: Boolean,
    ) {
        val centres = ArrayList<Offset>(1 + ImageProcessor.symmetryTransforms(symmetryMode, bitmapWidth, bitmapHeight).size)
        centres.add(Offset(cx, cy))
        // The full transform set for `symmetryMode` (0, 1, 3, or 5 extra copies for
        // NONE/VERTICAL|HORIZONTAL/QUADRANT/RADIAL_6) -- not just a single hardcoded vertical
        // mirror. Sharing ImageProcessor's own transform list, the same one DrawingEngine's replay
        // uses, is what keeps this live path from silently degrading Horizontal/Quadrant/Radial-6
        // to a plain mirror while only Vertical replayed correctly.
        for (transform in ImageProcessor.symmetryTransforms(symmetryMode, bitmapWidth, bitmapHeight)) {
            centres.add(transform(Offset(cx, cy)))
        }
        for (c in centres) {
            if (wrapAroundMode) {
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        canvas.drawCircle(c.x + dx * bitmapWidth, c.y + dy * bitmapHeight, radius, paint)
                    }
                }
            } else {
                canvas.drawCircle(c.x, c.y, radius, paint)
            }
        }
    }

    /**
     * Feeds one new bitmap-space [point] — with the [width] [BrushDynamics] computed for the
     * segment ending at it — into the round brush's live Catmull-Rom curve window
     * ([liveCurveWindow]/[liveCurveWidths]/[liveCurveFinalizedCount]), drawing every segment that
     * becomes finalizable as a result.
     *
     * Uniform Catmull-Rom is local — a segment's shape depends only on its 4 nearest points — but
     * the newest drawn segment always used a reflected phantom point as its far-side neighbour,
     * since the real one hadn't arrived yet. So a segment can't be drawn once and left alone until
     * it has REAL neighbours on both sides, which takes a 4-point window: point `i`'s segment to
     * point `i+1` needs point `i+2` to have actually arrived. That's the one thing this function
     * buys over just re-fitting the whole growing point list every frame (which the "redraw only
     * the new tail" live paths can't afford to begin with, since it would retroactively change
     * whichever segment was drawn most recently): a bounded, ~one-sample lag on the newest
     * segment, and every segment drawn is then a PERMANENT function of the point list as it stood
     * the moment it finalized — like a git commit, never revised by what the stroke does next —
     * rather than a value that has to be re-derived and potentially redrawn from a mutable buffer.
     *
     * [liveCurveFinalizedCount] `== 0` on entry means the window has never filled before, at which
     * point drawing the window's middle segment (indices 1-2 of 0-3, i.e. `segs[1]`) also has to
     * be preceded by drawing `segs[0]` — the stroke's own GLOBAL first segment — since it will
     * never again be any later window's middle: `segs[0]` uses a reflected phantom on its near
     * side regardless, which is correct and matches the authoritative (commit/replay) fit exactly,
     * there being no real point before a stroke's first one either way.
     */
    private fun resetLiveCurveState() = synchronized(liveCurveLock) {
        liveCurveWindow.clear()
        liveCurveWidths.clear()
        liveCurveFinalizedCount = 0
    }

    private fun feedLiveCurvePoint(
        canvas: Canvas,
        paint: Paint,
        bitmapWidth: Int,
        bitmapHeight: Int,
        symmetryMode: SymmetryMode,
        wrapAroundMode: Boolean,
        point: Offset,
        width: Float,
        targetBitmap: Bitmap,
        generation: Long? = null,
    ) = synchronized(liveCurveLock) {
        if (generation != null && generation != strokeGeneration) return@synchronized
        if (liveCurveWindow.isNotEmpty()) liveCurveWidths.addLast(width)
        liveCurveWindow.addLast(point)
        if (liveCurveWindow.size < 4) return@synchronized

        val flat = ArrayList<Float>(8)
        liveCurveWindow.forEach { flat.add(it.x); flat.add(it.y) }
        val segs = CatmullRom.segments(flat)

        if (liveCurveFinalizedCount == 0) {
            paint.strokeWidth = liveCurveWidths[0]
            drawCurveRun(canvas, paint, segs[0], bitmapWidth, bitmapHeight, symmetryMode, wrapAroundMode, targetBitmap)
            liveCurveFinalizedCount++
        }
        paint.strokeWidth = liveCurveWidths[1]
        drawCurveRun(canvas, paint, segs[1], bitmapWidth, bitmapHeight, symmetryMode, wrapAroundMode, targetBitmap)
        liveCurveFinalizedCount++

        liveCurveWindow.removeFirst()
        liveCurveWidths.removeFirst()
    }

    private fun clearTransientStrokeState() {
        strokeWorkingBitmap = null
        strokeWorkingCanvas = null
        strokePaint = null
        strokePrevBitmapPoint = null
        strokeDynamics = null
        strokeSymmetry = SymmetryMode.NONE
        strokeAlphaLock = false
        strokeOpacity = 1f
        strokeSelection = null
        lastSampleMs = 0L
        strokeGeneration++
        resetLiveCurveState()
        resetStrokePoints()
        strokeLayerId = null

        liquifyJob?.cancel()
        liquifyJob = null
        liquifyOriginalBitmap = null

        resampleJob?.cancel()
        resampleJob = null
        resampleOriginalBitmap = null
        resampleBlurReference = null

        stampLiveBitmap = null
        stampLiveCanvas = null
        stampLiveHeightMap = null
        stampLiveShadedBitmap = null
        stampStampedCount = 0
        stampHeldStampedCount = 0
        stampBrushForStroke = null
        stampShapeForStroke = null
        stampGrainForStroke = null
        stampMaskShapeForStroke = null
        stampMappedPoints.clear()
        // Locked for the same reason onStrokeStart's defensive reset is: a background onStrokePoint
        // batch (stampGpuJob) for this very stroke can still be running when the finger lifts and
        // this runs almost immediately after — see stampLiveLock's doc comment. Not waited on: that
        // batch's own identity check (comparing against whatever this leaves in stampGpuEngine, i.e.
        // null) makes it a safe no-op for the engine, same as the round-brush's strokeGpuEngine
        // teardown just below deliberately doesn't block on drawCurveRun either.
        synchronized(stampLiveLock) {
            stampGpuEngine?.destroy()
            stampGpuEngine = null
            stampGpuActive = false
        }
        stampGpuUsesMaskedPipeline = false
        stampGpuMaskAlpha8 = null
        stampGpuMaskSize = 0
        stampGpuGrainAlpha8 = null
        stampGpuGrainWidth = 0
        stampGpuGrainHeight = 0
        stampGpuHasDualBrush = false
        stampGpuSecondaryMaskAlpha8 = null
        stampGpuSecondaryMaskSize = 0
        stampGpuJob = null

        // Shares liveCurveLock with onStrokeStart's publish and drawCurveRun's read/fallback-
        // disable of these same two fields — see onStrokeStart's GPU live-preview comment for why:
        // without this, a fast tap-lift landing mid-publish could destroy the engine here while
        // onStrokeStart's coroutine still holds and uses the same reference, or double-destroy it.
        synchronized(liveCurveLock) {
            strokeGpuEngine?.destroy()
            strokeGpuEngine = null
            strokeGpuActive = false
        }
    }

    private fun buildStrokePaint(tool: Tool, argbColor: Int, brushSize: Float, feathering: Float, alphaLock: Boolean = false, opacity: Float = 1f): Paint =
        Paint().apply {
            strokeWidth = brushSize
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            when (tool) {
                Tool.BRUSH -> {
                    color = argbColor
                    // Stroke-level opacity ceiling, layered on top of whatever alpha the colour
                    // itself carries — a translucent colour times a translucent brush stays that
                    // translucent, not fully opaque. Only affects this LIVE-preview/fast-path paint;
                    // the authoritative committed pixels go through DrawingEngine's whole-stroke
                    // buffer composite (see ImageProcessor.applyToolToBitmap's Tool.BRUSH branch),
                    // so a self-overlapping stroke previews with (harmless, transient) per-segment
                    // build-up but commits without it.
                    alpha = (android.graphics.Color.alpha(argbColor) * opacity.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
                    // Alpha Lock: paint only where the layer already has alpha, so strokes
                    // recolour existing content without extending its silhouette.
                    if (alphaLock) xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
                    if (feathering > 0f) maskFilter = BlurMaskFilter(brushSize * feathering * 0.5f, BlurMaskFilter.Blur.NORMAL)
                }
                Tool.ERASER -> {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    if (feathering > 0f) maskFilter = BlurMaskFilter(brushSize * feathering * 0.5f, BlurMaskFilter.Blur.NORMAL)
                }
                Tool.BLUR, Tool.SHARPEN, Tool.SMUDGE -> {
                    // No live paint: a plain Paint can't blur, sharpen or drag the underlying pixels
                    // (the old code painted translucent BLACK — Paint's default color). All three are
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
                Tool.CLONE -> {
                    // No live paint, for the same reason as BLUR: a plain Paint has no way to sample
                    // pixels from elsewhere on the layer, and the default colour would lay down a
                    // black stroke that then snapped to the cloned pixels on finger-up. Because this
                    // leaves the working bitmap empty, onStrokeEnd must NOT commit it — CLONE is one
                    // of the two cases there that re-render through DrawingEngine instead, which is
                    // where ImageProcessor's CLONE branch actually composites the copy.
                    color = android.graphics.Color.TRANSPARENT
                    alpha = 0
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

    /** Opacity (0..1) ceiling for the built-in round brush. No-op for an azphalt stamp brush (use flow). */
    fun setBrushOpacity(amount: Float) {
        dispatch(EditorIntent.SetBrushOpacity(amount))
    }

    /** Flow (0..1) for the active azphalt stamp brush — per-dab build-up. No-op for the round brush. */
    fun setBrushFlow(amount: Float) {
        dispatch(EditorIntent.SetBrushFlow(amount))
    }

    fun setColorSmudgeMode(mode: ColorSmudgeEngine.Mode) =
        _colorSmudgeSettings.update { it.copy(mode = mode) }

    fun setColorSmudgeRate(amount: Float) =
        _colorSmudgeSettings.update { it.copy(smudgeRate = amount.coerceIn(0f, 1f)) }

    fun setColorSmudgeColorRate(amount: Float) =
        _colorSmudgeSettings.update { it.copy(colorRate = amount.coerceIn(0f, 1f)) }

    /** Procreate's Charge decay: 0 keeps Color Rate flat (default); >0 lets it deplete with distance. */
    fun setColorSmudgeChargeDecayRate(amount: Float) =
        _colorSmudgeSettings.update { it.copy(chargeDecayRate = amount.coerceAtLeast(0f)) }

    /** Procreate's Dilution: how much deposited pigment pre-mixes with the colour already there. */
    fun setColorSmudgeDilution(amount: Float) =
        _colorSmudgeSettings.update { it.copy(dilution = amount.coerceIn(0f, 1f)) }

    fun setColorSmudgeRadius(amount: Float) =
        _colorSmudgeSettings.update { it.copy(smudgeRadius = amount.coerceIn(0.05f, 3f)) }

    fun setColorSmudgeOpacity(amount: Float) =
        _colorSmudgeSettings.update { it.copy(opacity = amount.coerceIn(0f, 1f)) }

    fun setColorSmudgeAlphaCarry(enabled: Boolean) =
        _colorSmudgeSettings.update { it.copy(smearAlpha = enabled) }

    /** Item 11: read pickup from the composite of every other visible layer instead of only this
     *  one's own paint. See [DrawingEngine]'s Tool.SMUDGE branch for where this actually applies. */
    fun setColorSmudgeSampleMerged(enabled: Boolean) =
        _colorSmudgeSettings.update { it.copy(sampleMerged = enabled) }

    fun setColorSmudgeDynamics(bindings: List<com.hereliesaz.graffitixr.common.azphalt.BrushSensorBinding>) =
        _colorSmudgeSettings.update { it.copy(dynamics = bindings) }

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

    /**
     * What this host has done with each extension it acquired, for the browse request's inventory
     * extra (spec/state-reporting.md § 3.1). Read straight off the state file: it is a handful of
     * entries and the alternative — caching it — risks handing a store a stale answer, which is the
     * one failure this whole channel exists to prevent.
     */
    fun extensionStateInventory(): List<com.hereliesaz.graffitixr.common.azphalt.ExtensionStateEntry> =
        extensionRepository.stateInventory()

    /**
     * Does the Extensions panel list this extension?
     *
     * One definition, used both to build the panel and to tell the user where their install went, so
     * the two cannot disagree about it. Code and mixed packages are run by [onExtensionSelected];
     * asset packages carrying a usable LUT are applied as a colour grade by the same function.
     * Brushes are excluded on purpose — they have their own picker under the Brushes rail group.
     */
    private fun surfacesInExtensionsPanel(
        ext: com.hereliesaz.graffitixr.data.azphalt.InstalledExtension,
    ): Boolean =
        ext.manifest.kind == com.hereliesaz.graffitixr.common.azphalt.ExtensionKind.CODE ||
            ext.manifest.kind == com.hereliesaz.graffitixr.common.azphalt.ExtensionKind.MIXED ||
            extensionRepository.hasUsableLut(ext)

    /** Uninstall a previously-installed extension by [id]. */
    fun uninstallExtension(id: String) {
        viewModelScope.launch(dispatchers.io) {
            extensionRepository.uninstall(id)
        }
    }

    /**
     * Install an azphalt `.azp` package from a [uri] — a `content://` from the file picker, or one
     * handed off by a store app (spec/store-app.md; see MainActivity's browse-for-result launcher).
     * Opens the stream, verifies + unpacks off the main thread, and reports the outcome; the installed
     * flows ([installedBrushes], [installedExtensions]) update themselves so a new contribution
     * appears where it belongs without a manual refresh.
     *
     * [fromStore] carries what a store app said about the bytes, when they came from one. Its only
     * load-bearing field here is the id: it names the package in a `failed` state report, which
     * matters precisely in the case where the manifest never parsed and the bytes cannot name
     * themselves.
     */
    fun installExtensionFromUri(
        uri: Uri,
        fromStore: com.hereliesaz.graffitixr.data.azphalt.AzphaltStoreHandoff.StoreResult? = null,
    ) {
        viewModelScope.launch(dispatchers.io) {
            val now = System.currentTimeMillis()
            val storeId = fromStore?.id
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Couldn't open that file")
                // Only now are bytes actually in hand, which is what `downloaded` asserts — "the host
                // holds verified bytes". Recording it before opening the stream claimed a state that
                // could be false: a lapsed URI grant threw here, and the store was left showing
                // "downloaded, install pending" for a package this host never read.
                if (storeId != null) {
                    extensionRepository.recordDownloaded(storeId, fromStore.version.orEmpty(), now)
                }
                val installed = input.use {
                    extensionRepository.installFromStream(
                        it,
                        now,
                        knownId = storeId,
                        knownVersion = fromStore?.version,
                    )
                }
                withContext(dispatchers.main) {
                    Toast.makeText(context, installedMessage(installed), Toast.LENGTH_LONG).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // never swallow cancellation — let the coroutine unwind cooperatively
            } catch (e: Exception) {
                // Failures that never reach the installer land here — an unopenable URI, a revoked
                // read grant — and the installer's own `failed` record cannot see them. Without this
                // the state would stay at whatever it was, which for a store hand-off means claiming
                // bytes are held that never were.
                if (storeId != null) {
                    extensionRepository.recordFailed(storeId, fromStore?.version, now, e.message)
                }
                withContext(dispatchers.main) {
                    Toast.makeText(context, "Couldn't install: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun installExtensionFromUrl(url: String) {
        viewModelScope.launch(dispatchers.io) {
            val now = System.currentTimeMillis()
            try {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    error("Download failed: HTTP ${connection.responseCode}")
                }
                val installed = connection.inputStream.use {
                    extensionRepository.installFromStream(it, now)
                }
                withContext(dispatchers.main) {
                    Toast.makeText(context, installedMessage(installed), Toast.LENGTH_LONG).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(dispatchers.main) {
                    Toast.makeText(context, "Couldn't install: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ── In-app azphalt store browse (spec/repository-api.md) ────────────────────────────────────
    //
    // Graffux talks to the Repository API directly here rather than only delegating to a separate
    // store app (AzphaltStoreHandoff): it is still not a marketplace (it hosts no catalog and takes no
    // payment), but there is no reason to make browsing, obtaining and installing free extensions any
    // less than a first-class in-app path. The delegated handoff and the azphalt://install deep link
    // both still exist and still work — this is a second, primary way in, not a replacement.

    private var storeSearchJob: kotlinx.coroutines.Job? = null

    fun onStoreBrowseQueryChanged(query: String) = dispatch(EditorIntent.SetStoreBrowseQuery(query))

    /**
     * Searches the azphalt Repository API (spec/repository-api.md § 2), filtered to the kinds/media
     * domains this host can actually use — the exact same self-declared capability filter
     * [com.hereliesaz.graffitixr.data.azphalt.AzphaltStoreHandoff.browseIntent] sends a delegated store
     * app, shared rather than restated, so the in-app and delegated routes never disagree about what
     * Graffux can run. Cancels any search already in flight, so mashing the search button (or typing
     * fast into a debounced field) can't land results out of order.
     */
    fun onStoreSearch() {
        storeSearchJob?.cancel()
        val query = _uiState.value.storeBrowseQuery
        storeSearchJob = viewModelScope.launch(dispatchers.io) {
            dispatch(EditorIntent.SetStoreBrowseLoading(true))
            dispatch(EditorIntent.SetStoreBrowseError(null))
            try {
                val results = repositoryApiClient.search(
                    query = query.takeIf { it.isNotBlank() },
                    kind = com.hereliesaz.graffitixr.data.azphalt.AzphaltStoreHandoff.kinds,
                    mediaDomains = com.hereliesaz.graffitixr.data.azphalt.AzphaltStoreHandoff.mediaDomains,
                    appId = context.packageName,
                )
                dispatch(EditorIntent.SetStoreBrowseResults(results))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                dispatch(EditorIntent.SetStoreBrowseError(e.message ?: "Couldn't reach the azphalt store"))
            } finally {
                dispatch(EditorIntent.SetStoreBrowseLoading(false))
            }
        }
        refreshStoreUpdates()
    }

    /**
     * Batch-checks every installed extension for a newer version in one request
     * (spec/repository-api.md § 6 `POST /updates`), so [StoreWindow] can badge an "Update" button
     * instead of the user having to notice on their own. Best-effort: [RepositoryApiClient.checkUpdates]
     * already treats a failed/unsupported check as "nothing new" rather than throwing.
     */
    fun refreshStoreUpdates() {
        viewModelScope.launch(dispatchers.io) {
            val refs = extensionRepository.installed.value.map {
                com.hereliesaz.graffitixr.common.azphalt.UpdateRef(it.id, it.manifest.version)
            }
            val updates = repositoryApiClient.checkUpdates(refs)
            dispatch(EditorIntent.SetStoreUpdatesAvailable(updates.associate { it.id to it.latest }))
        }
    }

    /**
     * Downloads and installs a **free** package straight from the Repository API
     * (spec/repository-api.md § 4), through the identical [ExtensionRepository.installFromStream]
     * verification every other acquisition path uses — a repository's metadata is exactly as advisory
     * here as a store app's [com.hereliesaz.graffitixr.data.azphalt.AzphaltStoreHandoff.StoreResult] is
     * under the delegated route. Never called for a paid package: the caller (BrowseStoreWindow via
     * MainActivity) routes those to the web checkout instead, since Graffux has no in-app payment of
     * its own — a [RepositoryApiClient.PaymentRequiredException]/[RepositoryApiClient.UnauthorizedException]
     * surfacing here regardless (a card's `priceStatus` disagreeing with the server's own gate) is
     * reported like any other failure rather than silently swallowed.
     */
    fun installFromRepository(id: String, version: String) {
        if (id in _uiState.value.storeInstallingIds) return
        dispatch(EditorIntent.SetStoreInstalling(id, true))
        viewModelScope.launch(dispatchers.io) {
            val now = System.currentTimeMillis()
            try {
                val result = repositoryApiClient.download(id, version)
                // Only now are verified-pending bytes actually in hand -- same reasoning as
                // installExtensionFromUri: recording `downloaded` before the stream opens would claim
                // something that could be false if the request itself had failed.
                extensionRepository.recordDownloaded(id, version, now)
                val installed = result.stream.use {
                    extensionRepository.installFromStream(it, now, knownId = id, knownVersion = version)
                }
                withContext(dispatchers.main) {
                    Toast.makeText(context, installedMessage(installed), Toast.LENGTH_LONG).show()
                }
                refreshStoreUpdates()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                extensionRepository.recordFailed(id, version, now, e.message)
                withContext(dispatchers.main) {
                    val message = when (e) {
                        is com.hereliesaz.graffitixr.data.azphalt.RepositoryApiClient.PaymentRequiredException,
                        is com.hereliesaz.graffitixr.data.azphalt.RepositoryApiClient.UnauthorizedException,
                        -> "This extension needs a purchase or license — use Buy on its card."
                        else -> "Couldn't install: ${e.message}"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            } finally {
                dispatch(EditorIntent.SetStoreInstalling(id, false))
            }
        }
    }

    /**
     * What landed, and where the user will find it.
     *
     * "Installed X" alone is only half an answer: a package can contribute a brush, a colour grade, a
     * filter or several at once, and each surfaces somewhere different. Naming the destination is the
     * difference between a confirmation and an instruction.
     */
    private fun installedMessage(
        installed: com.hereliesaz.graffitixr.data.azphalt.InstalledExtension,
    ): String {
        val name = installed.manifest.name
        val where = buildList {
            if (extensionRepository.hasUsableBrush(installed)) add("Brushes")
            // Exactly the predicate the Extensions panel filters on, deliberately shared rather than
            // restated. Restating it drifted: this used to look at `contributes.filters`/`tools`
            // while the panel listed every code and mixed package, so a code package contributing
            // only commands was announced as "nothing applies" and then appeared in the panel.
            if (surfacesInExtensionsPanel(installed)) add("Extensions")
        }.distinct()

        return when {
            where.isEmpty() ->
                // Installed and verified, but nothing in it is something this host can apply — an
                // asset type it doesn't support, or a code-only contribution it doesn't run. Saying
                // so is better than a confirmation the user cannot act on.
                "Installed $name — nothing in it applies to this app yet"
            else -> "Installed $name — find it under ${where.joinToString(" and ")}"
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
        // Bracketed: the rail's "Gap" slider drives this once per emitted sample, and the direction
        // and alignment pickers route through the same function.
        continuousEdit { dispatch(EditorIntent.SetAutoLayout(frameId, layout)) }
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

    /**
     * Links (or with null, unlinks) the active shape's **stroke** to a colour token.
     *
     * The fill half of this pair has always had a caller; the stroke half had none, so
     * [EditorIntent.SetShapeStrokeStyle] and the [StyleOps.setShapeStrokeStyle] behind it were
     * reachable only from the reducer's own `when`. A shape has two colours and the token registry
     * could only ever describe one of them.
     */
    fun onApplyStrokeStyle(styleId: String?) {
        val layerId = _uiState.value.activeLayerId ?: return
        pushHistory()
        dispatch(EditorIntent.SetShapeStrokeStyle(layerId, styleId))
        saveProject()
    }

    /**
     * Repoints a text token at the active text layer's current look — every layer using it follows.
     *
     * The colour-token equivalent ([onUpdateColorStyleToActiveColor]) existed; this one did not, so
     * [EditorIntent.UpdateTextStyle] had no caller and a text token, once created, could never be
     * changed — only deleted and made again, which unlinks every layer that was using it.
     */
    fun onUpdateTextStyleToActive(styleId: String) {
        val existing = _uiState.value.textStyles.firstOrNull { it.id == styleId } ?: return
        val layerId = _uiState.value.activeLayerId ?: return
        val params = _uiState.value.layers.firstOrNull { it.id == layerId }?.textParams
        if (params == null) {
            Toast.makeText(context, "Select a text layer first", Toast.LENGTH_SHORT).show()
            return
        }
        val next = com.hereliesaz.graffitixr.common.model.StyleOps
            .textStyleFromParams(params, existing.name)
            .copy(id = existing.id)
        pushHistory()
        dispatch(EditorIntent.UpdateTextStyle(next))
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
            putLayerBase(instance.id, bmp)
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
    fun onInsertPathNode(segmentIndex: Int, t: Float) {
        val applied = discretePathEdit { com.hereliesaz.graffitixr.common.model.PathEditing.insertNode(it, segmentIndex, t) }
        if (!applied) return
        // A glee audit found the selection went stale here: insertNode() inserts the new node at
        // segmentIndex + 1 and shifts every later node's index up by one, but selectedNodeIndex
        // was left untouched -- so a node selected past the insertion point silently became a
        // handle on whichever node now sits at its old index, one before the one the user actually
        // had selected. Shift it the same way the node list itself just shifted.
        _uiState.value.selectedNodeIndex?.let { selected ->
            if (selected > segmentIndex) dispatch(EditorIntent.SelectPathNode(selected + 1))
        }
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

    /** @return whether [transform] actually changed the shape (a no-op transform, e.g. an
     *  out-of-range index, applies nothing and returns false). */
    private fun discretePathEdit(transform: (com.hereliesaz.graffitixr.common.model.VectorShape) -> com.hereliesaz.graffitixr.common.model.VectorShape): Boolean {
        val layerId = _uiState.value.pathEditLayerId ?: return false
        val shape = pathShapeOf(layerId) ?: return false
        val next = transform(shape)
        if (next == shape) return false
        pushHistory()
        dispatch(EditorIntent.SetPathShape(layerId, next))
        saveProject()
        return true
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

    /**
     * Stamp-brush presets that ship with the app, with no extension install and no Brush Studio
     * setup required -- see [com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes] for why
     * these exist: without them, a fresh install's only paintable options were the legacy Round
     * tool (which never touches the native stamp engine at all) and Brush Studio (which needs a
     * brush built before there's anything to paint with).
     */
    val builtInBrushes: List<com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush> =
        com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes.presets

    /** Selects one of [builtInBrushes] by name. A silent no-op if [name] doesn't match one. */
    fun selectBuiltInBrush(name: String) {
        val brush = builtInBrushes.firstOrNull { it.name == name } ?: return
        activeStampBrush = brush
        activeStampShape = null
        activeStampGrain = null
        activeStampMaskShape = null
        dispatch(EditorIntent.SetActiveBrush(brush.name))
        setActiveTool(Tool.BRUSH)
    }

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
        activeStampShape = null
        activeStampGrain = null
        activeStampMaskShape = null
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
        // Brush Studio drafts are params-only. Never let assets from the previously selected extension
        // leak into a generated/custom draft.
        activeStampShape = null
        activeStampGrain = null
        activeStampMaskShape = null
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
            activeStampGrain = null
            activeStampMaskShape = null
            dispatch(EditorIntent.SetActiveBrush(null))
            return
        }
        // loadBrush + the tip-image decode both read from disk — do them off the main thread.
        viewModelScope.launch(dispatchers.io) {
            val brush = extensionRepository.loadBrush(id)
            fun decodeAsset(relativePath: String?): Bitmap? = relativePath
                ?.let { extensionRepository.assetFilePath(id, it) }
                ?.let { path -> runCatching { decodeBoundedBitmap(java.io.File(path).readBytes(), 1024) }.getOrNull() }
            val shape = decodeAsset(brush?.shapePath)
            val grain = decodeAsset(brush?.grainPath)
            val maskShape = decodeAsset(brush?.maskedBrush?.shapePath)
            withContext(dispatchers.main) {
                if (brush == null) {
                    Toast.makeText(context, "Couldn't load that brush — it may be missing or corrupt", Toast.LENGTH_SHORT).show()
                } else {
                    activeStampBrush = brush
                    activeStampShape = shape
                    activeStampGrain = grain
                    activeStampMaskShape = maskShape
                    dispatch(EditorIntent.SetActiveBrush(brush.name))
                    setActiveTool(Tool.BRUSH)
                }
            }
        }
    }

    override fun setSecondaryColor(color: Color) = dispatch(EditorIntent.SetSecondaryColor(color))

    override fun swapBrushColors() = dispatch(EditorIntent.SwapBrushColors)

    override fun setActiveColor(color: Color) {
        dispatch(EditorIntent.SetActiveColor(color))
        // If a vector layer is active, recolour its shapes: fill for rect/ellipse, stroke for lines.
        val st = _uiState.value
        val active = st.layers.find { it.id == st.activeLayerId }
        if (active != null && active.shapes.isNotEmpty()) {
            val argb = color.toArgb().toLong() and 0xFFFFFFFFL
            val recoloured = active.shapes.map { s ->
                if (s.kind == com.hereliesaz.graffitixr.common.model.ShapeKind.LINE) s.copy(strokeArgb = argb)
                else s.copy(fillArgb = argb)
            }
            // Bracketed: the rail's Opacity slider drives this, once per emitted sample.
            continuousEdit { dispatch(EditorIntent.SetLayerShapes(active.id, recoloured)) }
        }
    }

    override fun onColorPickerDismissed() {
        dispatch(EditorIntent.DismissColorPicker)
    }

    override fun onFlattenAllLayers() {
        val projectId = _uiState.value.projectId ?: return
        pushHistory()
        dispatch(EditorIntent.SetLoading(true))
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
                putLayerBase(flatLayer.id, composite)
                layerStore.initStrokes(flatLayer.id)
                dispatch(EditorIntent.ReplaceLayers(listOf(flatLayer), flatLayer.id))
                oldLayerIds.forEach(::evictJobsFor)
                // Without this a spectator's layer list silently diverges from the host's until some
                // unrelated action forces a full resync — flatten replaced every layer wholesale, so
                // guests need the removes and the add, not just a props/transform resync.
                oldLayerIds.forEach { opEmitter.emit(Op.LayerRemove(it)) }
                opEmitter.emit(Op.LayerAdd(flatLayer))
                saveProject()
                dispatch(EditorIntent.SetLoading(false))
            }
        }
    }

    override fun onToggleLinkLayer(layerId: String) {
        // "Linked to below" is meaningless for the very bottom layer — nothing is below it to
        // link to. Without this guard, isLinked could be set true on it anyway, but
        // LinkOps.linkedGroupIds() never reads that flag for the bottom layer (its down-walk
        // requires index > 0 before ever checking), so it was write-only: never consulted, and
        // because linkedGroupIds() therefore always reported a lone group of size 1 for it, this
        // handler always re-set it to true on every tap — it could never be toggled back off.
        if (_uiState.value.layers.indexOfFirst { it.id == layerId } <= 0) {
            Toast.makeText(context, "Nothing below this layer to link to", Toast.LENGTH_SHORT).show()
            return
        }
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
            putLayerBase(newLayer.id, bitmap)
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
        textRasterizeJobs[layerId]?.cancel()
        textRasterizeJobs[layerId] = viewModelScope.launch(dispatchers.io) {
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

            putLayerBase(layerId, bitmap)

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
    // Co-op, so GraffitiXR's. Graffux binds NoOpOpEmitter (see CoopModule) and never joins a
    // session, so no remote op ever arrives for this to apply.
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
                        opacity = stroke.opacity,
                        pressures = stroke.pressures,
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
                    val base = SafeBitmap.copy(decoded, mutable = false) ?: run {
                        decoded.recycle()
                        return@launch
                    }
                    if (base !== decoded) decoded.recycle()
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
