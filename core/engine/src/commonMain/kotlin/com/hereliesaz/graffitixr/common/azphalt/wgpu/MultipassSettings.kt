package com.hereliesaz.graffitixr.common.azphalt.wgpu

/**
 * Settings of the wgpu engine's multipass rendering (experimental, off by default; see
 * core/wgpu-engine/src/multipass.rs and docs/Native Rendering Engine Design.md section 2c).
 *
 * With it on, every stamp call renders a cheap draft of the same stamp at once and its
 * full-quality dab later, in whatever time is left over; the display eases from one to the other.
 * The committed layer is byte-identical to multipass off.
 *
 * @property transitionMs the cosmetic ease when a finished result is swapped in; 0 = snap. Never a
 *   deadline: nothing waits for it.
 * @property passes requested quality levels; the engine renders a draft and the final dab and keeps
 *   the display continuous in between.
 * @property edgeFraction where the draft's trimmed edge sits in the feather, clamped to 1/3..1/2.
 * @property draftScale draft resolution divisor (1, 2, 4, 8); 0 adapts to the device.
 * @property refineBallast benchmark only: multiplies the cost of refinement.
 * @property frameMs frame period hint; 0 measures it.
 */
data class MultipassSettings(
    val enabled: Boolean = false,
    val transitionMs: Float = DEFAULT_TRANSITION_MS,
    val passes: Int = 2,
    val edgeFraction: Float = DEFAULT_EDGE_FRACTION,
    val overtakeMs: Float = DEFAULT_OVERTAKE_MS,
    val draftScale: Int = 0,
    val refineBallast: Float = 1f,
    val frameMs: Float = 0f,
) {
    /** The engine's float layout (MultipassConfig::from_floats). */
    fun toFloatArray(): FloatArray = floatArrayOf(
        if (enabled) 1f else 0f,
        passes.toFloat(),
        edgeFraction,
        transitionMs,
        overtakeMs,
        draftScale.toFloat(),
        refineBallast,
        frameMs,
    )

    companion object {
        const val DEFAULT_TRANSITION_MS = 150f
        const val DEFAULT_EDGE_FRACTION = 0.4f
        const val DEFAULT_OVERTAKE_MS = 50f

        /** Transition choices offered in Settings, ms. */
        val TRANSITION_CHOICES_MS = listOf(0f, 80f, 150f, 300f)
    }
}

/** Diagnostics from the engine (MultipassStats::to_array). */
data class MultipassStats(
    val pendingDrafts: Int,
    val pendingRefinement: Int,
    val activeTiles: Int,
    val draftMs: Double,
    val presentMs: Double,
    val composeMs: Double,
    val refineUnitsPerMs: Double,
    val duty: Double,
    val maxEtaMs: Double,
    val draftScale: Int,
    val frameMs: Double,
    val draftsRun: Long,
    val chunksRun: Long,
) {
    /** Nothing left to refine or animate: the display shows exactly the layer. */
    val settled: Boolean get() = pendingDrafts == 0 && pendingRefinement == 0 && activeTiles == 0

    companion object {
        @Suppress("MagicNumber")
        fun fromArray(a: DoubleArray): MultipassStats? {
            if (a.size < 13) return null
            return MultipassStats(
                pendingDrafts = a[0].toInt(),
                pendingRefinement = a[1].toInt(),
                activeTiles = a[2].toInt(),
                draftMs = a[3],
                presentMs = a[4],
                composeMs = a[5],
                refineUnitsPerMs = a[6],
                duty = a[7],
                maxEtaMs = a[8],
                draftScale = a[9].toInt(),
                frameMs = a[10],
                draftsRun = a[11].toLong(),
                chunksRun = a[12].toLong(),
            )
        }
    }
}
