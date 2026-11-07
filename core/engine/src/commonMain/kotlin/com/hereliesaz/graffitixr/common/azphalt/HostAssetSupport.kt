package com.hereliesaz.graffitixr.common.azphalt

/**
 * Which manifest assets this host actually consumes -- the single answer the store card, the install
 * message and the model downloader all read, so the "(x not supported)" badge can never disagree with
 * what the app will really do. Everything else parses and is retained on the manifest but is inert.
 */
object HostAssetSupport {
    /** Model role this host's depth runtime consumes (`ml` module, `OnnxDepthEstimator`). */
    const val DEPTH_ROLE = "depth"

    /** An `onnx` model graph whose role routes it to the host's depth estimator. */
    fun isDepthModel(asset: AssetContribution): Boolean =
        asset.type == AssetType.ONNX && asset.role.equals(DEPTH_ROLE, ignoreCase = true)

    /** Whether the host consumes [asset]: brushes, LUTs and `onnx` + role `depth` models. */
    fun isSupported(asset: AssetContribution): Boolean = when (asset.type) {
        AssetType.BRUSH, AssetType.LUT -> true
        AssetType.ONNX -> isDepthModel(asset)
        else -> false
    }
}
