package com.hereliesaz.graffitixr.common.azphalt

/**
 * Feeds [samples] the way the live stroke and [LiveStrokeDabs] do: each sample is recorded by a
 * [StrokeLengthPredictor] and appended with that running estimate. Tests must not hand the
 * generator the stroke's true total, which production never has.
 */
internal fun IncrementalDynamicDabGenerator.appendLive(samples: List<BrushSample>): List<Dab> {
    val predictor = StrokeLengthPredictor()
    return samples.flatMap {
        predictor.record(it)
        append(it, predictor.predictedTotal)
    }
}
