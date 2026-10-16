package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.azphalt.WetMaterialTransport
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine
import com.hereliesaz.graffitixr.nativebridge.GpuWetSimulation

/**
 * Color Smudge with persistent canvas wetness on the GPU: material advance, the smudge, the
 * selection confine and the settle tick ([WetnessReplayState.advanceMaterialTo] /
 * [WetnessReplayState.settleMaterial]'s sequence), run on the wet simulation (GpuWetSimulation) of
 * the engine that holds the layer.
 *
 * 1. The layer and its [WetnessReplayState] go to the simulation; material time advances from the
 *    state's last recorded uptime to the stroke's first (pigment transport, then wetness flux and
 *    drying), as [WetnessReplayState.advanceMaterialTo] does.
 * 2. The smudge runs on the canvas wetness (GpuWetSimulation.SMUDGE_WETNESS), confined to the
 *    selection on the GPU against the advanced layer.
 * 3. One fixed settle tick ([WetnessReplayState.settleMaterial]), then the wetness comes back into
 *    the state, marked through the stroke's last uptime.
 */
internal object GpuWetSmudge {

    /** The stroke's selection, prepared the way GpuStrokeSelection.prepare takes it. */
    class Selection(val prepare: (GpuStampEngine) -> GpuStrokeSelection.Prepared?)

    /**
     * Runs [smudge] on [engine] (whose layer holds [layer]) with [state]'s wetness. [firstUptime] and
     * [lastUptime] are the stroke's first and last recorded sample times; [coverage] (one value per
     * pixel, null = everywhere) is where the smudge may deposit wetness.
     *
     * Never touches [state]: returns the wetness to [Result.applyTo] it once the caller has also
     * read the layer back, or null when a stage failed. Restoring here, before the caller's
     * readback, left the state holding a deposit whose pixels never landed when that readback
     * failed.
     */
    @Suppress("LongParameterList")
    fun run(
        engine: GpuStampEngine,
        layer: Bitmap,
        state: WetnessReplayState,
        firstUptime: Long?,
        lastUptime: Long?,
        selection: Selection?,
        coverage: FloatArray?,
        smudge: () -> Boolean,
    ): Result? {
        val wet = GpuWetSimulation(engine)
        val elapsed = elapsedSeconds(state, firstUptime)
        val ready = begin(wet, layer, state, coverage) &&
            (elapsed <= 0f || transport(wet, elapsed, WetnessReplayState.DEFAULT_DRYING_RATE))
        val settled = ready && smudgeConfined(engine, wet, selection, smudge) &&
            wet.op(GpuWetSimulation.LAYER_TO_RAW) &&
            transport(wet, WetnessReplayState.DEFAULT_SETTLE_SECONDS, 0f)
        val n = layer.width * layer.height
        val wetness = FloatArray(n)
        val read = settled && wet.read(IntArray(n), FloatArray(n), wetness)
        return if (read) Result(wetness, lastUptime ?: firstUptime ?: state.lastUptimeMillis) else null
    }

    /** A finished wet smudge's wetness, marked through [lastUptime]. */
    class Result(private val wetness: FloatArray, private val lastUptime: Long?) {
        fun applyTo(state: WetnessReplayState) = state.restore(wetness, lastUptime)
    }

    /** [layer] (straight ARGB) and [state]'s wetness and active tiles into the simulation. */
    private fun begin(wet: GpuWetSimulation, layer: Bitmap, state: WetnessReplayState, coverage: FloatArray?): Boolean {
        val width = layer.width
        val height = layer.height
        val tile = state.field.tileSize
        val cols = (width + tile - 1) / tile
        val tiles = cols * ((height + tile - 1) / tile)
        val raw = IntArray(width * height).also { layer.getPixels(it, 0, width, 0, 0, width, height) }
        return wet.begin(
            raw, FloatArray(width * height), state.snapshot(), WetUpload.activeTiles(state, cols, tiles), tile,
            FloatArray(tiles * GpuWetSimulation.MEDIA_FLOATS),
        ) && (coverage == null || wet.buffer(GpuWetSimulation.COVERAGE, coverage))
    }

    /** Material time from [state]'s last uptime to [firstUptime]; 0 when none passed or it is dry. */
    private fun elapsedSeconds(state: WetnessReplayState, firstUptime: Long?): Float {
        val previous = state.lastUptimeMillis
        val first = firstUptime?.takeIf { previous != null && it > previous && !state.field.isIdle }
        return if (first != null && previous != null) (first - previous) / MILLIS_PER_SECOND else 0f
    }

    /** The smudge on the canvas wetness, confined to [selection] (taken against the layer as it is). */
    private fun smudgeConfined(
        engine: GpuStampEngine,
        wet: GpuWetSimulation,
        selection: Selection?,
        smudge: () -> Boolean,
    ): Boolean {
        val prepared = selection?.let { it.prepare(engine) ?: return false }
        return smudgeOnWetness(wet, smudge) && (prepared == null || GpuStrokeSelection.confine(engine, prepared))
    }

    private fun smudgeOnWetness(wet: GpuWetSimulation, smudge: () -> Boolean): Boolean {
        if (!wet.op(GpuWetSimulation.SMUDGE_WETNESS, 1f)) return false
        return try {
            smudge()
        } finally {
            wet.op(GpuWetSimulation.SMUDGE_WETNESS, 0f)
        }
    }

    /**
     * [WetnessReplayState]'s material step over [seconds] on the raw pigment (transport, then the
     * wetness field's flux and [drying]), written back into the layer.
     */
    private fun transport(wet: GpuWetSimulation, seconds: Float, drying: Float): Boolean =
        wet.op(
            GpuWetSimulation.PIGMENT, seconds, WetnessReplayState.DEFAULT_PIGMENT_TRANSPORT_RATE,
            WetMaterialTransport.DEFAULT_ITERATIONS.toFloat(),
        ) &&
            wet.op(GpuWetSimulation.ADVANCE, seconds, drying, WetnessReplayState.DEFAULT_TRANSPORT_RATE, 0f) &&
            wet.op(GpuWetSimulation.RAW_TO_LAYER)

    private const val MILLIS_PER_SECOND = 1000f
}
