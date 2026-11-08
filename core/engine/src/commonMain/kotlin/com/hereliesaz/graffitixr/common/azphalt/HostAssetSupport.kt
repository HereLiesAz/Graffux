package com.hereliesaz.graffitixr.common.azphalt

/**
 * Which manifest assets this host actually consumes -- the single answer the store card, the install
 * message and the model downloader all read, so the "(x not supported)" badge can never disagree with
 * what the app will really do. Everything else parses and is retained on the manifest but is inert.
 */
object HostAssetSupport {
    /** Model role this host's depth runtime consumes (`ml` module, `OnnxDepthEstimator`). */
    const val DEPTH_ROLE = "depth"

    /**
     * An `onnx` model graph whose role routes it to the host's depth estimator. A code-dependent asset
     * (`standalone: false`) is excluded: it needs its package's code, which this host does not run
     * (spec § Mixed-package asset independence), so it is neither downloaded nor exposed.
     */
    fun isDepthModel(asset: AssetContribution): Boolean =
        asset.type == AssetType.ONNX && asset.standalone && asset.role.equals(DEPTH_ROLE, ignoreCase = true)

    /**
     * Which installed depth model the host uses when the caller does not choose: the first of these
     * that is installed **and has verified weights**, then any other depth model by id. Depth Anything
     * V2 first (best-validated relative inverse depth), then V3 (newer, polarity inverted by the host,
     * least field-tested), then MiDaS v2.1 small (smallest, lowest quality, fixed 256 px), then MoGe-2 small (metric point
     * map, 141 MB and the heaviest to run; the host fits its shift, see `core:ml` `MoGeDepth`).
     */
    val DEPTH_PREFERENCE: List<String> = listOf(
        "com.hereliesaz.azphalt.depth-anything-v2-small",
        "com.hereliesaz.azphalt.depth-anything-v3-small",
        "com.hereliesaz.azphalt.midas",
        "com.hereliesaz.azphalt.models.moge-2-vits-metric-depth",
    )

    /** Sort key for [DEPTH_PREFERENCE]: listed ids by position, unlisted ones after, ties by id. */
    fun <T> orderedByDepthPreference(items: List<T>, idOf: (T) -> String): List<T> =
        items.sortedWith(
            compareBy<T> { DEPTH_PREFERENCE.indexOf(idOf(it)).let { i -> if (i < 0) DEPTH_PREFERENCE.size else i } }
                .thenBy { idOf(it) },
        )

    /** Whether the host consumes [asset]: brushes, LUTs, surfaces and `onnx` + role `depth` models. */
    fun isSupported(asset: AssetContribution): Boolean = when (asset.type) {
        AssetType.BRUSH, AssetType.LUT, AssetType.SURFACE -> true
        AssetType.ONNX -> isDepthModel(asset)
        else -> false
    }
}
