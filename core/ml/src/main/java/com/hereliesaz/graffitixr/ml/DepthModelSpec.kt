@file:Suppress("MagicNumber", "TooManyFunctions", "CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")

package com.hereliesaz.graffitixr.ml

import com.hereliesaz.graffitixr.common.azphalt.AssetContribution
import com.hereliesaz.graffitixr.common.azphalt.IoTensor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Channel order the model's input tensor expects. */
enum class ColorOrder { RGB, BGR }

/** Memory order of an image tensor: channels first (`[N,3,H,W]`) or last (`[N,H,W,3]`). */
enum class TensorLayout { NCHW, NHWC }

/** How a farther-is-larger model output is turned into nearer-is-larger. See [DepthPipeline.toNearerLarger]. */
enum class Inversion {
    /** `1 / depth`, floored so zero and tiny depths stay finite. The default: matches inverse-depth statistics. */
    RECIPROCAL,

    /** `min + max - depth`: order-reversing and safe for values at or below zero (e.g. a log-depth output). */
    REFLECT,
}

/**
 * Per-channel `value * scale + bias` applied to a channel value in **0..255** (the spec's
 * `scale`/`bias` form, spec/extension-manifest.md § Model IO). The spec's `mean`/`std` form
 * (`(x/255 - mean) / std`) is the special case built by [meanStd]. Lists are indexed by **tensor
 * channel** (after [ColorOrder]), one entry per channel.
 */
data class Normalization(val scale: List<Float>, val bias: List<Float>) {
    init {
        require(scale.size == CHANNELS && bias.size == CHANNELS) { "Normalization needs one entry per channel" }
    }

    /** Applies the transform to a 0..1 channel value (what the pipeline composites in). */
    fun apply(channel: Int, unit: Float): Float = unit * MAX_CHANNEL * scale[channel] + bias[channel]

    companion object {
        private const val CHANNELS = 3
        private const val MAX_CHANNEL = 255f

        /** `[0,1]` input: what MiDaS v2.1 small expects (`scale = 1/255, bias = 0`). */
        val UNIT: Normalization = Normalization(List(CHANNELS) { 1f / MAX_CHANNEL }, List(CHANNELS) { 0f })

        /** Raw 0..255 values, unchanged: what a listing with no `normalization` means. */
        val RAW: Normalization = Normalization(List(CHANNELS) { 1f }, List(CHANNELS) { 0f })

        /** `(x/255 - mean) / std`. */
        fun meanStd(mean: List<Float>, std: List<Float>): Normalization {
            require(mean.size == CHANNELS && std.size == CHANNELS) { "mean and std need one entry per channel" }
            require(std.all { it != 0f }) { "std must be non-zero" }
            return Normalization(
                List(CHANNELS) { 1f / (MAX_CHANNEL * std[it]) },
                List(CHANNELS) { -mean[it] / std[it] },
            )
        }
    }
}

/** How the model's input resolution is chosen. */
sealed interface SizePolicy {
    /** The graph takes exactly [width] x [height]; the image is letterboxed into it, aspect preserved. */
    data class Fixed(val width: Int, val height: Int) : SizePolicy {
        init {
            require(width > 0 && height > 0) { "A fixed input needs a positive size" }
        }
    }

    /**
     * H and W are dynamic. The image is resized (aspect kept to within one [multipleOf] step, no
     * padding) so each side is a multiple of [multipleOf], at most [maxLongSide] on the long side
     * (never upscaled past the source) and at least [minSide] on the short one.
     */
    data class Dynamic(
        val multipleOf: Int = DEFAULT_MULTIPLE,
        val maxLongSide: Int = DEFAULT_MAX_LONG_SIDE,
        val minSide: Int = DEFAULT_MIN_SIDE,
    ) : SizePolicy {
        init {
            require(multipleOf > 0) { "multipleOf must be positive" }
            require(minSide in 1..maxLongSide) { "minSide must be positive and not above maxLongSide" }
            require(maxLongSide >= multipleOf) { "maxLongSide must fit at least one multiple" }
        }
    }

    companion object {
        /** Most dense-prediction backbones need /32; a listing that needs otherwise says so (`sizeMultipleOf`). */
        const val DEFAULT_MULTIPLE = 32

        /**
         * Host policy, not a listing field: the long side that keeps a dynamic model's activations
         * comfortably inside a mid-range phone's memory (518 is Depth Anything's own working size).
         * A listing may only lower it (`params.maxLongSide`).
         */
        const val DEFAULT_MAX_LONG_SIDE = 518

        /** A 14 x 14 input makes Depth Anything V3 emit NaN; four multiples of 14 is a safe floor. */
        const val DEFAULT_MIN_SIDE = 56
    }
}

/**
 * Everything the runtime needs to drive one depth model, resolved from the manifest's `io` block by
 * [DepthModelSpecs.fromAsset]. The defaults are the **MiDaS v2.1 small** contract (fixed 256 square,
 * 4-D NCHW RGB, `[0,1]`, relative inverse depth out), which is also what a listing with no `io` means.
 */
data class DepthModelSpec(
    /** Input tensor name; null = the graph's first input. */
    val inputName: String? = null,
    /** 4 = `[N,3,H,W]`; 5 = `[N,views,3,H,W]` with one view. N is always 1. */
    val rank: Int = RANK_IMAGE,
    val layout: TensorLayout = TensorLayout.NCHW,
    val colorOrder: ColorOrder = ColorOrder.RGB,
    val size: SizePolicy = SizePolicy.Fixed(MIDAS_SMALL_INPUT, MIDAS_SMALL_INPUT),
    val normalization: Normalization = Normalization.UNIT,
    /** Output tensor to read; null = the graph's first output. Only this one is requested from the runtime. */
    val outputName: String? = null,
    /** Polarity of that output. False = it is depth (larger = farther) and is inverted into [DepthMap]'s convention. */
    val higherIsNearer: Boolean = true,
    val inversion: Inversion = Inversion.RECIPROCAL,
    /** Grey level (0..1) transparent and padded pixels are composited onto before inference. */
    val fill: Float = 0.5f,
) {
    init {
        require(rank == RANK_IMAGE || rank == RANK_VIEWS) { "rank must be 4 (image) or 5 (views)" }
        require(fill in 0f..1f) { "fill must be a 0..1 grey level" }
    }

    /** The input shape for [box]: `[1,3,H,W]` or `[1,H,W,3]`, plus a views axis of 1 after N when rank is 5. */
    fun inputShape(box: Letterbox): LongArray {
        val h = box.inputHeight.toLong()
        val w = box.inputWidth.toLong()
        val c = CHANNELS.toLong()
        val dims = if (layout == TensorLayout.NCHW) listOf(c, h, w) else listOf(h, w, c)
        val lead = if (rank == RANK_VIEWS) listOf(1L, 1L) else listOf(1L)
        return (lead + dims).toLongArray()
    }

    companion object {
        const val MIDAS_SMALL_INPUT = 256
        const val CHANNELS = 3
        const val RANK_IMAGE = 4
        const val RANK_VIEWS = 5
    }
}

/**
 * Resolves a manifest's model `io` block into a [DepthModelSpec].
 *
 * What is read, and from where. Everything the spec (spec/extension-manifest.md § Model IO) can say
 * is read from its own fields; the three things it cannot are carried by conventions the listings
 * already use:
 *
 * | Need | Where |
 * |---|---|
 * | input name, `shape` (rank 4/5, fixed vs `-1` dynamic H/W), `dtype`, `layout`, `color`, `normalization` |
 * `io.inputs[0]` (spec fields) |
 * | which output is depth | `io.outputs[]`: by `decode.kind`, else by name, else `semantics: "mask"` |
 * | **polarity** | the depth output's `decode.higherIsNearer` (bool); absent: inferred from `decode.kind`
 * (`relative-inverse-depth` = nearer) |
 * | **multiple-of-N** | the depth output's `decode.sizeMultipleOf` (int), else `params.sizeMultipleOf` on the asset;
 * absent: 32 |
 * | **5-D views axis** | spec `shape` of rank 5 (`[b, views, 3, H, W]`); the host always sends 1 view |
 * | optional lower long-side cap | `params.maxLongSide` on the asset (lower only; the host cap is
 * [SizePolicy.DEFAULT_MAX_LONG_SIDE]) |
 * | optional inversion | the depth output's `decode.invert`: `"reciprocal"` (default) or `"reflect"` |
 *
 * `decode` and `params` are free-form JSON in the spec, so none of these extend the schema. An asset
 * with no `io` (or no inputs) is the MiDaS v2.1 small contract, so existing listings keep working.
 */
object DepthModelSpecs {
    private val DEPTH_KINDS = setOf(
        "relative-inverse-depth", "inverse-depth", "relative-depth", "depth", "metric-depth",
    )
    private val INVERSE_KINDS = setOf("relative-inverse-depth", "inverse-depth")
    private val DEPTH_NAMES = setOf("predicted_depth", "output_depth", "depth", "inverse_depth")

    /** @throws DepthEstimationException.UnsupportedModel when the declared io cannot be driven by this runtime. */
    fun fromAsset(asset: AssetContribution): DepthModelSpec {
        val io = asset.io
        val inputs = io?.inputs.orEmpty()
        if (inputs.isEmpty()) return DepthModelSpec()
        if (inputs.size > 1) unsupported("it declares ${inputs.size} inputs; the depth runtime feeds exactly one image")
        val input = inputs.single()
        val output = pickDepthOutput(io?.outputs.orEmpty())
        val rank = rankOf(input)
        val layout = layoutOf(input)
        checkDtype(input.dtype)
        val decode = output?.decode as? JsonObject
        return DepthModelSpec(
            inputName = input.name,
            rank = rank,
            layout = layout,
            colorOrder = colorOf(input),
            size = sizeOf(input, rank, layout, decode, asset.params),
            normalization = normalizationOf(input.normalization),
            outputName = output?.name,
            higherIsNearer = polarityOf(decode),
            inversion = if (decode?.string("invert").equals("reflect",
                true)) Inversion.REFLECT else Inversion.RECIPROCAL,
        )
    }

    /** The depth output: by `decode.kind`, then conventional name, then the first `mask`, then the first. */
    internal fun pickDepthOutput(outputs: List<IoTensor>): IoTensor? =
        outputs.firstOrNull { ((it.decode as? JsonObject)?.string("kind")?.lowercase()) in DEPTH_KINDS }
            ?: outputs.firstOrNull { it.name?.lowercase() in DEPTH_NAMES }
            ?: outputs.firstOrNull { it.semantics.equals("mask", true) }
            ?: outputs.firstOrNull()

    private fun rankOf(input: IoTensor): Int {
        val shape = input.shape ?: unsupported("its input declares no shape")
        if (shape.size != DepthModelSpec.RANK_IMAGE && shape.size != DepthModelSpec.RANK_VIEWS) {
            unsupported("its input is ${shape.size}-D; only [N,3,H,W] and [N,views,3,H,W] are supported")
        }
        return shape.size
    }

    private fun layoutOf(input: IoTensor): TensorLayout = when (input.layout?.uppercase()) {
        null, "NCHW" -> TensorLayout.NCHW
        "NHWC" -> TensorLayout.NHWC
        else -> unsupported("its input layout '${input.layout}' is not NCHW or NHWC")
    }

    private fun colorOf(input: IoTensor): ColorOrder = when (input.color?.uppercase()) {
        null, "RGB" -> ColorOrder.RGB
        "BGR" -> ColorOrder.BGR
        else -> unsupported("its input colour '${input.color}' is not RGB or BGR")
    }

    private fun checkDtype(dtype: String?) {
        if (dtype != null && !dtype.equals("float32", true) && !dtype.equals("float16", true)) {
            unsupported("its input dtype '$dtype' is not float32 or float16")
        }
    }

    private fun sizeOf(
        input: IoTensor, rank: Int, layout: TensorLayout, decode: JsonObject?, params: JsonObject?,
    ): SizePolicy {
        val shape = checkNotNull(input.shape)
        val dim = { i: Int -> shape[i].takeIf { it > 0 } } // -1 / 0 = dynamic
        val lead = rank - DepthModelSpec.RANK_IMAGE // 1 for the views axis, else 0
        val channels = if (layout == TensorLayout.NCHW) dim(1 + lead) else dim(rank - 1)
        if (channels != null
            && channels != DepthModelSpec.CHANNELS) unsupported("its input has $channels channels, not 3")
        val batch = dim(0)
        val views = if (rank == DepthModelSpec.RANK_VIEWS) dim(1) else null
        if ((batch != null && batch != 1) || (views != null && views != 1)) {
            unsupported("its input is fixed to ${batch ?: "N"} x ${views ?: "views"}; the host sends one image")
        }
        val (hIndex, wIndex) = if (layout == TensorLayout.NCHW) (rank - 2) to (rank - 1) else (rank - 3) to (rank - 2)
        val h = dim(hIndex)
        val w = dim(wIndex)
        return when {
            h != null && w != null -> SizePolicy.Fixed(w, h)
            h == null && w == null -> dynamic(decode, params)
            else -> unsupported("only one of its input's height and width is dynamic")
        }
    }

    private fun dynamic(decode: JsonObject?, params: JsonObject?): SizePolicy.Dynamic {
        val multiple = decode?.int("sizeMultipleOf") ?: params?.int("sizeMultipleOf") ?: SizePolicy.DEFAULT_MULTIPLE
        if (multiple <= 0) unsupported("its sizeMultipleOf is $multiple")
        val cap = (params?.int("maxLongSide") ?: SizePolicy.DEFAULT_MAX_LONG_SIDE)
            .coerceIn(multiple, SizePolicy.DEFAULT_MAX_LONG_SIDE.coerceAtLeast(multiple))
        val min = SizePolicy.DEFAULT_MIN_SIDE.coerceIn(multiple, cap)
        return SizePolicy.Dynamic(multipleOf = multiple, maxLongSide = cap, minSide = min)
    }

    private fun polarityOf(decode: JsonObject?): Boolean {
        (decode?.get("higherIsNearer") as? JsonPrimitive)?.booleanOrNull?.let { return it }
        val kind = decode?.string("kind")?.lowercase() ?: return true
        return when {
            kind in INVERSE_KINDS -> true
            kind in DEPTH_KINDS -> false
            else -> true
        }
    }

    /** `mean`/`std` or `scale`/`bias`, each a scalar or a 3-array; absent = raw 0..255. */
    private fun normalizationOf(element: JsonElement?): Normalization {
        val obj = element as? JsonObject ?: return Normalization.RAW
        val mean = obj["mean"]
        val std = obj["std"]
        if (mean != null || std != null) {
            val m = triple(mean, 0f)
            val s = triple(std, 1f)
            if (s.any { it == 0f }) unsupported("its normalization std contains zero")
            return Normalization.meanStd(m, s)
        }
        return Normalization(triple(obj["scale"], 1f), triple(obj["bias"], 0f))
    }

    private fun triple(element: JsonElement?, default: Float): List<Float> {
        val numbers = when (element) {
            null -> return List(DepthModelSpec.CHANNELS) { default }
            is JsonArray -> element.map { number(it) }
            else -> listOf(number(element))
        }
        return when (numbers.size) {
            1 -> List(DepthModelSpec.CHANNELS) { numbers[0] }
            DepthModelSpec.CHANNELS -> numbers
            else -> unsupported("a normalization entry has ${numbers.size} values, not 1 or 3")
        }
    }

    private fun number(element: JsonElement): Float =
        (element as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: unsupported("a normalization value is not a number")

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun unsupported(why: String): Nothing = throw DepthEstimationException.UnsupportedModel(
        "This depth model cannot be run by this app: $why.",
    )
}
