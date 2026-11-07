package com.hereliesaz.graffitixr.ml

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Monocular depth estimation: one image in, one [DepthMap] out.
 *
 * Implementations run **off the calling thread** and are **cancellable**: cancelling the coroutine
 * that called [estimate] stops the work at the next opportunity (the ONNX implementation terminates
 * the in-flight session run). Failures surface as a [DepthEstimationException] subtype; an
 * implementation never returns a made-up map.
 */
interface DepthEstimator : AutoCloseable {
    /**
     * Estimates depth for [source], or for just [region] of it (a selection or subject crop; null =
     * the whole bitmap). [source] is not modified or recycled.
     *
     * The result covers exactly the requested area, at the model's working resolution with the
     * aspect ratio of that area preserved (letterbox padding is already removed), and is
     * **raw relative inverse depth, larger = nearer, whatever the model's own polarity** (a model that
     * emits depth is inverted into this convention, and every sample is finite). To use it on a layer:
     * `map.normalized().resampledTo(w, h)`.
     * Transparent pixels contribute nothing (they are composited over neutral grey before the model
     * sees them), so depth inside a fully transparent area is the model's guess from its surroundings.
     *
     * @throws DepthEstimationException.InvalidInput for an empty/recycled bitmap or a [region] outside it.
     * @throws kotlinx.coroutines.CancellationException when the caller is cancelled.
     */
    suspend fun estimate(source: Bitmap, region: Rect? = null): DepthMap
}

/** Why depth estimation failed. Every failure path in this package maps to one of these. */
sealed class DepthEstimationException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The weights are missing, unreadable, or not a model this runtime can open. */
    class ModelLoadFailed(message: String, cause: Throwable? = null) : DepthEstimationException(message, cause)

    /** The listing's `io` describes a model this runtime cannot drive (see [DepthModelSpecs]); nothing was loaded. */
    class UnsupportedModel(message: String) : DepthEstimationException(message)

    /** The caller passed a bitmap or region that cannot be processed. */
    class InvalidInput(message: String) : DepthEstimationException(message)

    /** The runtime failed while running the model. */
    class InferenceFailed(message: String, cause: Throwable? = null) : DepthEstimationException(message, cause)

    /** The model ran but its output is not the shape this pipeline understands. */
    class UnexpectedOutput(message: String) : DepthEstimationException(message)
}
