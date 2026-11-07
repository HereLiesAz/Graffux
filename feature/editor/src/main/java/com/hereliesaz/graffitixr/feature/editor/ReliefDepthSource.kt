@file:Suppress("MaxLineLength", "MagicNumber")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.data.azphalt.ExtensionRepository
import com.hereliesaz.graffitixr.data.azphalt.ModelAssetCache
import com.hereliesaz.graffitixr.ml.DepthEstimationException
import com.hereliesaz.graffitixr.ml.OnnxDepthEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Inverse depth for a photo: larger is nearer, any scale. Row-major, [width] x [height] samples. */
internal class ReliefDepth(val width: Int, val height: Int, val values: FloatArray)

/** A depth estimate that could not be made; [message] is fit to show the user. */
internal class ReliefDepthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Whether a depth model can run now. */
internal sealed interface ReliefDepthAvailability {
    data object Ready : ReliefDepthAvailability

    /** No package that contributes a depth model is installed. */
    data object NoModel : ReliefDepthAvailability

    /** A depth model is installed but its weights are still coming ([percent] when the size is known). */
    data class Downloading(val percent: Int?) : ReliefDepthAvailability

    /** A depth model is installed but cannot be used as listed; [message] says why. */
    data class Unavailable(val message: String) : ReliefDepthAvailability
}

/** Where the editor gets a photo's depth from: the installed depth model, or a fake in tests. */
internal interface ReliefDepthSource {
    suspend fun availability(): ReliefDepthAvailability

    /** @throws ReliefDepthException when the model cannot run; cancelling the caller stops inference. */
    suspend fun estimate(bitmap: Bitmap): ReliefDepth
}

/** The installed depth model (an `onnx` asset with role `depth`, see ExtensionRepository) run on ONNX Runtime. */
internal class InstalledDepthSource(private val repository: ExtensionRepository) : ReliefDepthSource {
    override suspend fun availability(): ReliefDepthAvailability = withContext(Dispatchers.IO) {
        val models = repository.installedDepthModels()
        when {
            models.isEmpty() -> ReliefDepthAvailability.NoModel
            repository.readyDepthModel() != null -> ReliefDepthAvailability.Ready
            else -> models.firstNotNullOfOrNull { m ->
                when (val state = repository.modelAssets.stateOf(m.extension.id, m.asset)) {
                    is ModelAssetCache.State.Downloading ->
                        ReliefDepthAvailability.Downloading(state.total?.takeIf { it > 0 }?.let { (state.downloaded * 100 / it).toInt() })
                    is ModelAssetCache.State.Unavailable -> ReliefDepthAvailability.Unavailable(state.message)
                    is ModelAssetCache.State.Failed -> ReliefDepthAvailability.Unavailable(state.message)
                    else -> null
                }
            } ?: ReliefDepthAvailability.Downloading(null)
        }
    }

    override suspend fun estimate(bitmap: Bitmap): ReliefDepth {
        val ready = withContext(Dispatchers.IO) { repository.readyDepthModel() }
            ?: throw ReliefDepthException("The depth model is not ready yet.")
        try {
            OnnxDepthEstimator.forAsset(ready.file, ready.model.asset).use { estimator ->
                val map = estimator.estimate(bitmap)
                return ReliefDepth(map.width, map.height, map.values)
            }
        } catch (e: DepthEstimationException) {
            throw ReliefDepthException(describe(e), e)
        }
    }

    private fun describe(e: DepthEstimationException): String = when (e) {
        is DepthEstimationException.ModelLoadFailed -> "The depth model could not be opened. Try reinstalling it."
        is DepthEstimationException.UnsupportedModel -> "This depth model is not one the app can run."
        is DepthEstimationException.InvalidInput -> "This image cannot be used for depth."
        is DepthEstimationException.InferenceFailed -> "The depth model failed while running."
        is DepthEstimationException.UnexpectedOutput -> "The depth model gave an output the app does not understand."
    }
}
