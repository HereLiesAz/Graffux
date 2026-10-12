package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Path
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.Dab
import com.hereliesaz.graffitixr.common.azphalt.DirtyRegion
import com.hereliesaz.graffitixr.common.azphalt.ImpastoEngine
import com.hereliesaz.graffitixr.common.azphalt.PaintMedium
import com.hereliesaz.graffitixr.common.azphalt.WetMaterialTransport
import com.hereliesaz.graffitixr.common.util.SafeBitmap
import com.hereliesaz.graffitixr.feature.editor.gpu.GpuTuningController
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine
import com.hereliesaz.graffitixr.nativebridge.GpuWetSimulation
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A layer's canonical Impasto v2 state, mutated in place when a stroke succeeds. */
internal class ImpastoV2Layer(
    val heightMap: FloatArray,
    val wetness: WetnessReplayState?,
    val material: ImpastoMaterialReplayState?,
)

/** The stroke's selection: its clip path and feather radius in layer pixels. */
internal class StrokeSelection(val clipPath: Path?, val featherRadius: Float)

/**
 * An Impasto v2 stroke (the wet material simulation) on the GPU, in one engine session, in the
 * CPU's order (DrawingEngine's v2 branch): the wet material already on the layer advances to the
 * stroke's first recorded sample (levelling, pigment transport, wetness drying); the colour is
 * stamped into the raw pigment and kept through the selection; height and reservoir transfer and
 * wetness deposit follow the dabs; one fixed post-contact settle; then the touched region's lit
 * presentation is rebuilt from the raw pigment. The engine's solvers (core/wgpu-engine `wet.wgsl`)
 * follow the CPU references step for step, checked by `tests/wet.rs`.
 */
internal object GpuImpastoV2Stroke {

    private const val TAG = "GpuImpastoV2Stroke"

    /**
     * [stroke] painted onto a copy of [bitmap] (the lit layer) with [layer]'s state updated in place;
     * null, with [layer] untouched, when the GPU or its wet support is unavailable.
     */
    @Suppress("LongParameterList")
    fun paintStroke(
        bitmap: Bitmap,
        stroke: StrokeCommand,
        brush: AzphaltBrush,
        mapped: List<Offset>,
        brushScale: Float,
        layer: ImpastoV2Layer,
        selection: StrokeSelection,
    ): Bitmap? {
        val width = bitmap.width
        val height = bitmap.height
        val material = layer.material?.takeIf { it.width == width && it.height == height }
            ?: ImpastoMaterialReplayState.fromRaw(width, height, pixels(bitmap))
        val wetness = layer.wetness?.takeIf { it.field.width == width && it.field.height == height }
        val (movementDabs, heldDabs) = StrokeDabs.recorded(stroke, brush, mapped, brushScale, width, height)
            ?: (StrokeDabs.fromPath(stroke, brush, mapped, brushScale, width, height) to emptyList())
        // The GPU's tile grid serves both the wetness tiles and the media tiles.
        val usable = wetness == null || wetness.field.tileSize == material.tileSize
        val target = if (usable) SafeBitmap.copy(bitmap) else null
        // The CPU reads recorded time only from samples that map 1:1 onto the path.
        val samples = stroke.brushSamples.takeIf { it.size == mapped.size }
        val session = target?.let {
            Session(
                it, stroke, brush, material.copyForWork(), wetness,
                StrokeDabs.batches(movementDabs, heldDabs, stroke.stampBatches), movementDabs + heldDabs,
                samples?.firstOrNull()?.uptimeMillis to samples?.lastOrNull()?.uptimeMillis,
            )
        }
        val result = session?.run(bitmap, layer.heightMap, coverage(selection, width, height))
        if (session == null || result == null) {
            target?.recycle()
            return null
        }
        result.heightOut.copyInto(layer.heightMap)
        if (layer.material === material) {
            material.replaceRawColor(result.rawOut)
            material.recordMedium(result.contact, session.medium)
        }
        wetness?.restore(result.wetOut, result.lastUptimeMillis)
        return target
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    /**
     * The selection as per-pixel coverage: the feather mask's alpha, or the hard clip as 0/1 (the
     * CPU clips the colour to it and gates height and wetness with it); null without a selection.
     */
    private fun coverage(selection: StrokeSelection, width: Int, height: Int): FloatArray? {
        val clip = selection.clipPath
        return when {
            clip == null -> null
            selection.featherRadius > 0f -> featherCoverage(clip, width, height, selection.featherRadius)
            else -> hardCoverage(clip, width, height)
        }
    }

    private fun featherCoverage(clip: Path, width: Int, height: Int, radius: Float): FloatArray? =
        SelectionMask.featherMask(clip, width, height, radius)?.let { mask ->
            val alpha = pixels(mask)
            mask.recycle()
            FloatArray(width * height) { (alpha[it] ushr ALPHA_SHIFT and BYTE_MASK) / BYTE_MAX }
        }

    private fun hardCoverage(clip: Path, width: Int, height: Int): FloatArray {
        val out = FloatArray(width * height)
        SelectionMask.region(clip, width, height)?.let { region ->
            for (y in 0 until height) for (x in 0 until width) if (region.contains(x, y)) out[y * width + x] = 1f
        }
        return out
    }

    /** The union of [dabs]' CPU pixel boxes whose [control] flag is set (the CPU's dirty union). */
    private fun touched(dabs: List<Dab>, control: IntArray, offset: Int, width: Int, height: Int): DirtyRegion? {
        var out: DirtyRegion? = null
        dabs.forEachIndexed { i, d ->
            if (control[offset + i] == 0 || d.radius <= 0f) return@forEachIndexed
            val box = DirtyRegion(
                max(0, floor(d.x - d.radius).toInt()), max(0, floor(d.y - d.radius).toInt()),
                min(width - 1, ceil(d.x + d.radius).toInt()) + 1, min(height - 1, ceil(d.y + d.radius).toInt()) + 1,
            )
            out = out?.union(box) ?: box
        }
        return out
    }

    /** The union of the active wet tiles (DrawingEngine.activeWetnessBounds). */
    private fun activeBounds(state: WetnessReplayState): DirtyRegion? {
        val field = state.field
        var out: DirtyRegion? = null
        for ((tx, ty) in field.activeTileCoordinates()) {
            val left = tx * field.tileSize
            val top = ty * field.tileSize
            val tile = DirtyRegion(
                left, top, min(field.width, left + field.tileSize), min(field.height, top + field.tileSize),
            )
            out = out?.union(tile) ?: tile
        }
        return out
    }

    private fun unionOf(a: DirtyRegion?, b: DirtyRegion?): DirtyRegion? = when {
        a == null -> b
        b == null -> a
        else -> a.union(b)
    }

    /** What a successful session hands back. */
    private class Result(
        val rawOut: IntArray,
        val heightOut: FloatArray,
        val wetOut: FloatArray,
        val contact: DirtyRegion?,
        val lastUptimeMillis: Long?,
    )

    @Suppress("LongParameterList")
    private class Session(
        private val target: Bitmap,
        private val stroke: StrokeCommand,
        private val brush: AzphaltBrush,
        private val work: ImpastoMaterialReplayState,
        private val wetness: WetnessReplayState?,
        private val batches: List<Pair<List<Dab>, List<Dab>>>,
        private val dabs: List<Dab>,
        /** The first and last recorded sample times. */
        private val times: Pair<Long?, Long?>,
    ) {
        private val config = brush.impastoMaterial.sanitized()
        val medium: PaintMedium = config.toMedium().sanitized()
        private val width = target.width
        private val height = target.height
        private val tileSize = work.tileSize
        private val cols = (width + tileSize - 1) / tileSize
        private val tiles = cols * ((height + tileSize - 1) / tileSize)
        private var lastUptime: Long? = wetness?.lastUptimeMillis
        private var preContact: DirtyRegion? = null

        fun run(display: Bitmap, heightMap: FloatArray, coverage: FloatArray?): Result? {
            // Calibration's engines and the canvas's never coexist (GpuCalibration.stopForCanvas);
            // this runs on a commit worker, never on the touch path.
            GpuTuningController.stopCalibrationForCanvas()
            val engine = runCatching { GpuStampEngine() }.getOrNull() ?: return null
            return try {
                simulate(engine, display, heightMap, coverage)
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                Log.w(TAG, "GPU Impasto v2 stroke failed; the CPU renderer takes this stroke", e)
                null
            } finally {
                engine.destroy()
            }
        }

        private fun simulate(
            engine: GpuStampEngine,
            display: Bitmap,
            heightMap: FloatArray,
            coverage: FloatArray?,
        ): Result? {
            val begun = engine.init(width, height) &&
                GpuWetSimulation(engine).begin(
                    work.rawColor, heightMap, wetness?.snapshot() ?: FloatArray(width * height),
                    activeTiles(), tileSize, mediaTable(),
                ) &&
                GpuWetSimulation(engine).buffer(GpuWetSimulation.COVERAGE, coverage) &&
                advanceToStroke(engine) &&
                paint(engine)
            val contact = if (begun) contact(engine) else null
            val settled = begun && contact != null && settle(engine, contact.region, contact.wet)
            return if (settled) finish(engine, display, contact?.region) else null
        }

        private fun activeTiles(): IntArray = IntArray(tiles).also { out ->
            wetness?.field?.activeTileCoordinates()?.forEach { (tx, ty) -> out[ty * cols + tx] = 1 }
        }

        /** Each tile's effective medium (ImpastoMaterialReplayState.mediumAt), sanitized. */
        private fun mediaTable(): FloatArray {
            val out = FloatArray(tiles * GpuWetSimulation.MEDIA_FLOATS)
            for (t in 0 until tiles) {
                val m = work.mediumAt((t % cols) * tileSize, (t / cols) * tileSize, medium).sanitized()
                val o = t * GpuWetSimulation.MEDIA_FLOATS
                out[o] = m.viscosity
                out[o + M_YIELD] = m.yieldLikeStrength
                out[o + M_LEVELING] = m.levelingRate
                out[o + M_DRYING] = m.dryingRate
                out[o + M_ROUGHNESS] = m.baseRoughness
                out[o + M_SPECULAR] = m.wetSpecularStrength
            }
            return out
        }

        /** The already-wet material advanced to the stroke's first recorded sample. */
        private fun advanceToStroke(engine: GpuStampEngine): Boolean {
            val state = wetness?.takeIf { !it.field.isIdle } ?: return true
            val next = times.first
            val previous = state.lastUptimeMillis
            val elapsed = next != null && previous != null && next > previous
            var ok = true
            if (elapsed) {
                val dt = (requireNotNull(next) - requireNotNull(previous)) / MILLIS_PER_SECOND
                preContact = activeBounds(state)
                ok = level(engine, dt, preContact) &&
                    GpuWetSimulation(engine).op(
                        GpuWetSimulation.PIGMENT, dt, WetnessReplayState.DEFAULT_PIGMENT_TRANSPORT_RATE,
                        WetMaterialTransport.DEFAULT_ITERATIONS.toFloat(),
                    ) &&
                    GpuWetSimulation(engine).op(
                        GpuWetSimulation.ADVANCE, dt, medium.dryingRate,
                        WetnessReplayState.DEFAULT_TRANSPORT_RATE, 1f,
                    )
            }
            if (next != null) lastUptime = next
            return ok
        }

        /** The colour stamped into the raw pigment, kept only through the selection. */
        private fun paint(engine: GpuStampEngine): Boolean =
            GpuWetSimulation(engine).op(GpuWetSimulation.SAVE_RAW) &&
                GpuWetSimulation(engine).op(GpuWetSimulation.RAW_TO_LAYER) &&
                GpuStampCommit.stampOnto(engine, batches, StampPaint.of(stroke, brush)) &&
                GpuWetSimulation(engine).op(GpuWetSimulation.LAYER_TO_RAW) &&
                GpuWetSimulation(engine).op(GpuWetSimulation.BLEND_SELECTION)

        private class Contact(val region: DirtyRegion?, val wet: Boolean)

        /** Height transfer and wetness deposit; the touched region and whether anything is wet. */
        private fun contact(engine: GpuStampEngine): Contact? {
            val flat = FloatArray(dabs.size * GpuWetSimulation.DAB_FLOATS)
            dabs.forEachIndexed { i, d ->
                val o = i * GpuWetSimulation.DAB_FLOATS
                flat[o] = d.x
                flat[o + 1] = d.y
                flat[o + D_RADIUS] = d.radius
                flat[o + D_ALPHA] = d.alpha
                flat[o + D_FLOW] = d.flowMultiplier
                flat[o + D_CONTACT] = d.contactDepth
            }
            val control = IntArray(tiles * 2 + dabs.size)
            val ok = GpuWetSimulation(engine).buffer(GpuWetSimulation.DABS, flat) &&
                GpuWetSimulation(engine).op(
                    GpuWetSimulation.TRANSFER, brush.hardness, brush.impastoThicknessRate, medium.heightResponse,
                    medium.depositionRate, medium.pickupRate, medium.substrateResponse, config.initialLoad,
                ) &&
                (wetness == null || GpuWetSimulation(engine).op(
                    GpuWetSimulation.DEPOSIT, brush.hardness, config.wetness * brush.impastoThicknessRate,
                )) &&
                GpuWetSimulation(engine).readControl(control)
            val wet = (0 until tiles).any { control[it] != 0 }
            return if (ok) Contact(touched(dabs, control, tiles * 2, width, height), wet) else null
        }

        /** The contacted tiles take the stroke's medium; then the fixed post-contact settle. */
        private fun settle(engine: GpuStampEngine, contact: DirtyRegion?, wet: Boolean): Boolean {
            work.recordMedium(contact, medium)
            var ok = GpuWetSimulation(engine).buffer(GpuWetSimulation.MEDIA, mediaTable())
            if (ok && wetness != null && wet) {
                val dt = WetnessReplayState.DEFAULT_SETTLE_SECONDS
                ok = GpuWetSimulation(engine).op(
                    GpuWetSimulation.PIGMENT, dt, WetnessReplayState.DEFAULT_PIGMENT_TRANSPORT_RATE,
                    WetMaterialTransport.DEFAULT_ITERATIONS.toFloat(),
                ) &&
                    GpuWetSimulation(engine).op(
                        GpuWetSimulation.ADVANCE, dt, 0f, WetnessReplayState.DEFAULT_TRANSPORT_RATE, 0f,
                    ) &&
                    level(engine, dt, contact)
                times.second?.let { lastUptime = it }
            }
            return ok
        }

        private fun level(engine: GpuStampEngine, dt: Float, region: DirtyRegion?): Boolean {
            val r = (region ?: DirtyRegion(0, 0, width, height)).clampTo(width, height)
            return r.isEmpty || GpuWetSimulation(engine).op(
                GpuWetSimulation.LEVEL, dt, ImpastoEngine.DEFAULT_LEVELING_ITERATIONS.toFloat(),
                r.left.toFloat(), r.top.toFloat(), r.width.toFloat(), r.height.toFloat(),
            )
        }

        /** Reads the state back and rebuilds the touched region's lit presentation into [target]. */
        private fun finish(engine: GpuStampEngine, display: Bitmap, contact: DirtyRegion?): Result? {
            val n = width * height
            val result = Result(IntArray(n), FloatArray(n), FloatArray(n), contact, lastUptime)
            val shade = unionOf(preContact, contact)?.let {
                DirtyRegion(it.left - 1, it.top - 1, it.right + 1, it.bottom + 1).clampTo(width, height)
            }
            val ok = GpuWetSimulation(engine).read(result.rawOut, result.heightOut, result.wetOut) &&
                engine.upload(display) &&
                (shade == null || shade.isEmpty || GpuWetSimulation(engine).op(
                    GpuWetSimulation.SHADE, shade.left.toFloat(), shade.top.toFloat(), shade.width.toFloat(),
                    shade.height.toFloat(), IMPASTO_LIGHT_AZIMUTH_DEG, IMPASTO_LIGHT_ELEVATION_DEG,
                    IMPASTO_LIGHT_STRENGTH, if (wetness != null) 1f else 0f,
                )) &&
                engine.readback(target)
            return result.takeIf { ok }
        }
    }

    private const val ALPHA_SHIFT = 24
    private const val BYTE_MASK = 0xFF
    private const val BYTE_MAX = 255f
    private const val MILLIS_PER_SECOND = 1000f
    private const val M_YIELD = 1
    private const val M_LEVELING = 2
    private const val M_DRYING = 3
    private const val M_ROUGHNESS = 4
    private const val M_SPECULAR = 5
    private const val D_RADIUS = 2
    private const val D_ALPHA = 3
    private const val D_FLOW = 4
    private const val D_CONTACT = 5
}
