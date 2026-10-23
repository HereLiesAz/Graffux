package com.hereliesaz.graffitixr.common.azphalt

/**
 * A stamp stroke's dabs exactly as the live stroke produced them, rebuilt from its recorded
 * samples. Commit and undo/redo replay use this instead of a whole-stroke resolver, so the
 * committed paint is the paint that was on screen while drawing.
 *
 * The live generators are incremental: [IncrementalDynamicDabGenerator] resolves each sample
 * against a running [StrokeLengthPredictor] estimate of the stroke's total length, and
 * [IncrementalStaticDabGenerator] places dabs along the raw points. The predictor is a pure
 * function of the sample sequence, so feeding the recorded samples through the same generators in
 * the same order reproduces every prediction, and every dab, without storing either.
 */
object LiveStrokeDabs {

    /** Whether [brush] paints through the dynamic generator (the live path's gate). */
    fun usesDynamicGenerator(brush: AzphaltBrush): Boolean =
        brush.dynamics.isNotEmpty() || brush.maskedBrush?.dynamics?.isNotEmpty() == true ||
            brush.taper.isActive() || brush.blot.isActive() || brush.contact.isActive() ||
            brush.program != null

    /** The stroke's movement dabs, as the live stroke generated them. */
    fun movementDabs(samples: List<BrushSample>, diameterPx: Float, brush: AzphaltBrush, seed: Long): List<Dab> =
        movementStroke(samples, diameterPx, brush, seed, null).dabs

    /** A stroke's movement dabs and the wet film it touched. */
    class Stroke(val dabs: List<Dab>, val film: PaintSnapshot?)

    /**
     * [movementDabs] for a brush whose program reads the wet paint already on the layer
     * ([paintPrior], as the stroke began), returning the film this stroke touched so the host can
     * hand it to the stroke that follows.
     */
    fun movementStroke(
        samples: List<BrushSample>,
        diameterPx: Float,
        brush: AzphaltBrush,
        seed: Long,
        paintPrior: PaintSnapshot?,
    ): Stroke {
        val out = ArrayList<Dab>()
        var film: PaintSnapshot? = null
        if (usesDynamicGenerator(brush)) {
            val generator = IncrementalDynamicDabGenerator(diameterPx, brush, seed, paintPrior)
            val predictor = StrokeLengthPredictor()
            for (sample in samples) {
                predictor.record(sample)
                out.addAll(generator.append(sample, predictor.predictedTotal))
            }
            film = generator.paintFilm()
        } else {
            val generator = IncrementalStaticDabGenerator(diameterPx, brush, seed)
            // Predicted (not-yet-real) samples never place dabs, as on the dynamic path.
            for (sample in samples) if (!sample.predicted) out.addAll(generator.appendPoint(sample.x, sample.y))
        }
        return Stroke(out, film)
    }

    /** The stroke's held (airbrush build-up) dabs, as the live stroke generated them. */
    fun heldDabs(samples: List<BrushSample>, diameterPx: Float, brush: AzphaltBrush, seed: Long): List<Dab> {
        if (brush.airbrushDabsPerSecond <= 0f) return emptyList()
        val generator = IncrementalAirbrushGenerator(
            diameterPx, brush, brush.airbrushDabsPerSecond, brush.airbrushStillnessRadiusPx, seed,
        )
        return samples.flatMap { generator.append(it) }
    }
}
