package com.hereliesaz.graffitixr.ml

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer
import kotlin.math.max

/**
 * [DepthEstimator] backed by ONNX Runtime, for an `onnx`-type asset with role `depth`. What the model
 * wants (input name/rank/size/normalisation, which output is depth, its polarity) is the
 * [DepthModelSpec], resolved from the listing's `io` block: use [forAsset]. The default spec is MiDaS
 * v2.1 small. For a multi-file bundle pass the verified **graph** file (ONNX Runtime finds the
 * `model.onnx_data` beside it).
 *
 * **Weights must already be verified.** This class takes a [File]; it does not check a checksum
 * itself. Obtain the file from `ModelAssetCache` (core:data), which refuses to hand out bytes that
 * did not match the manifest's `checksum`. Never point this at a download that skipped that gate.
 *
 * The session is created lazily on the first [estimate] (off the main thread) and reused; one
 * inference runs at a time (the dispatcher is single-threaded), which also bounds peak memory to one
 * tensor set. [close] releases the native session.
 */
class OnnxDepthEstimator(
    private val modelFile: File,
    private val spec: DepthModelSpec = DepthModelSpec(),
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : DepthEstimator {
    private val lock = Any()
    private var session: OrtSession? = null
    private var closed = false

    companion object {
        /** An estimator for [modelFile] driven by [asset]'s `io`. @throws DepthEstimationException.UnsupportedModel */
        fun forAsset(modelFile: File,
            asset: com.hereliesaz.graffitixr.common.azphalt.AssetContribution): OnnxDepthEstimator =
            OnnxDepthEstimator(modelFile, DepthModelSpecs.fromAsset(asset))

        private const val PREFILTER_LONG_SIDE = 1024
        private const val MAX_THREADS = 4
    }

    override suspend fun estimate(source: Bitmap, region: Rect?): DepthMap {
        val area = validatedRegion(source, region)
        val (pixels, w, h) = extractPixels(source, area)
        val cancel = CancelHandle()
        return coroutineScope {
            val work = async(dispatcher) {
                ensureActive()
                val session = openSession()
                if (spec.pointMap != null) {
                    DepthPipeline.estimatePointMap(pixels, w, h, spec, OrtPointMapRunner(session, cancel))
                } else {
                    DepthPipeline.estimate(pixels, w, h, spec, OrtRunner(session, cancel))
                }
            }
            try {
                work.await()
            } catch (e: CancellationException) {
                // Cooperative cancellation cannot interrupt a native run; ask ONNX Runtime to stop
                // between operators instead. The runner turns the resulting OrtException back into
                // a CancellationException, so the scope unwinds promptly rather than finishing a
                // result nobody wants.
                cancel.cancel()
                throw e
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            session?.close()
            session = null
        }
    }

    private fun openSession(): OrtSession = synchronized(lock) {
        check(!closed) { "OnnxDepthEstimator is closed" }
        session?.let { return it }
        if (!modelFile.isFile) {
            throw DepthEstimationException.ModelLoadFailed("Depth model weights not found at ${modelFile.path}")
        }
        try {
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(max(1, minOf(MAX_THREADS, Runtime.getRuntime().availableProcessors() / 2)))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            options.use { OrtEnvironment.getEnvironment().createSession(modelFile.path, it) }
                .also { session = it }
        } catch (e: OrtException) {
            throw DepthEstimationException.ModelLoadFailed("Could not load the depth model: ${e.message}", e)
        }
    }

    @Suppress("ComplexCondition")
    private fun validatedRegion(source: Bitmap, region: Rect?): Rect {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) {
            throw DepthEstimationException.InvalidInput("The bitmap is empty or recycled")
        }
        val whole = Rect(0, 0, source.width, source.height)
        val area = region ?: whole
        if (area.isEmpty || !whole.contains(area)) {
            throw DepthEstimationException.InvalidInput(
                "Region $area is empty or outside the ${source.width}x${source.height} bitmap",
            )
        }
        return area
    }

    /** The region's ARGB pixels, pre-shrunk if enormous so a poster-sized layer does not need 64 MB+ of ints. */
    private suspend fun extractPixels(source: Bitmap, area: Rect): Triple<IntArray, Int, Int> =
        withContext(dispatcher) {
        var crop: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            val base = if (source.config == Bitmap.Config.HARDWARE) {
                source.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw DepthEstimationException.InvalidInput("Could not read back a hardware bitmap")
            } else {
                source
            }
            val whole = Rect(0, 0, base.width, base.height)
            crop = if (area == whole) {
                base
            } else {
                Bitmap.createBitmap(base, area.left, area.top, area.width(), area.height())
            }
            val longSide = max(crop.width, crop.height)
            val target = if (longSide > PREFILTER_LONG_SIDE) {
                val f = PREFILTER_LONG_SIDE.toDouble() / longSide
                scaled = Bitmap.createScaledBitmap(
                    crop, max(1, (crop.width * f).toInt()), max(1, (crop.height * f).toInt()), true,
                )
                scaled
            } else {
                crop
            }
            val px = IntArray(target.width * target.height)
            target.getPixels(px, 0, target.width, 0, 0, target.width, target.height)
            Triple(px, target.width, target.height)
        } finally {
            if (scaled != null && scaled !== source) scaled.recycle()
            if (crop != null && crop !== source) crop.recycle()
        }
    }

    /**
     * Lets another thread abort the run in flight. `RunOptions.setTerminate` is ONNX Runtime's
     * documented way to stop a running session between operators; a cancel that lands before the run
     * starts is remembered and stops it at the gate.
     */
    private class CancelHandle {
        @Volatile var isCancelled = false
            private set
        private var options: OrtSession.RunOptions? = null

        @Synchronized fun attach(o: OrtSession.RunOptions) {
            options = o
            if (isCancelled) o.setTerminate(true)
        }

        @Synchronized fun detach() {
            options = null
        }

        @Synchronized fun cancel() {
            isCancelled = true
            options?.setTerminate(true)
        }
    }

    /** One ONNX Runtime call per [run]. Requests only the depth output (V3's confidence and pose heads never run). */
    private class OrtRunner(private val session: OrtSession, private val cancel: CancelHandle) : DepthModelRunner {
        @Suppress("NestedBlockDepth", "ThrowsCount", "CyclomaticComplexMethod")
        override fun run(input: FloatArray, box: Letterbox, spec: DepthModelSpec): RawDepth {
            val env = OrtEnvironment.getEnvironment()
            val inputName = spec.inputName ?: session.inputNames.firstOrNull()
                ?: throw DepthEstimationException.UnexpectedOutput("The model declares no input")
            if (inputName !in session.inputNames) {
                throw DepthEstimationException.ModelLoadFailed("The model has no input named '$inputName'")
            }
            val outputName = spec.outputName ?: session.outputNames.firstOrNull()
                ?: throw DepthEstimationException.UnexpectedOutput("The model declares no output")
            if (outputName !in session.outputNames) {
                throw DepthEstimationException.UnexpectedOutput("The model has no output named '$outputName'")
            }
            val shape = spec.inputShape(box)
            val half = (session.inputInfo[inputName]?.info as? TensorInfo)?.type == OnnxJavaType.FLOAT16
            try {
                val tensor = if (half) {
                    OnnxTensor.createTensor(env, ShortBuffer.wrap(Float16.toHalfArray(input)), shape,
                        OnnxJavaType.FLOAT16)
                } else {
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape)
                }
                tensor.use {
                    OrtSession.RunOptions().use { options ->
                        cancel.attach(options)
                        try {
                            if (cancel.isCancelled) throw CancellationException("Depth estimation cancelled")
                            session.run(mapOf(inputName to tensor), setOf(outputName), options).use { result ->
                                val out = result.get(outputName).orElse(null) as? OnnxTensor
                                    ?: throw DepthEstimationException.UnexpectedOutput("Output is not a tensor")
                                // getFloatBuffer widens a float16 output to float32.
                                val buf = out.floatBuffer
                                val values = FloatArray(buf.remaining()).also { buf.get(it) }
                                return DepthPipeline.rawFromShape(values, out.info.shape)
                            }
                        } finally {
                            cancel.detach()
                        }
                    }
                }
            } catch (e: OrtException) {
                if (cancel.isCancelled) throw CancellationException("Depth estimation cancelled")
                throw DepthEstimationException.InferenceFailed("Depth inference failed: ${e.message}", e)
            }
        }
    }

    /** The MoGe-2 counterpart of [OrtRunner]. */
    @Suppress("MagicNumber")
    private class OrtPointMapRunner(
        private val session: OrtSession, private val cancel: CancelHandle,
    ) : PointMapRunner {
    /**
     * MoGe-2: feeds `image` and the int64 scalar `num_tokens`, requests only `points`, `mask` and
     * `scale`. Not exercised off-device (see the instrumented test notes).
     */
    @Suppress("NestedBlockDepth", "ThrowsCount", "CyclomaticComplexMethod", "LongMethod")
    override fun run(input: FloatArray, box: Letterbox, spec: DepthModelSpec): RawPointMap {
        val pm = spec.pointMap ?: throw DepthEstimationException.UnsupportedModel("Not a point-map model")
        val env = OrtEnvironment.getEnvironment()
        val inputName = spec.inputName ?: session.inputNames.firstOrNull()
            ?: throw DepthEstimationException.UnexpectedOutput("The model declares no input")
        if (inputName !in session.inputNames) {
            throw DepthEstimationException.ModelLoadFailed("The model has no input named '$inputName'")
        }
        val wanted = listOfNotNull(pm.pointsName, pm.maskName, pm.scaleName)
        for (n in wanted) if (n !in session.outputNames) {
            throw DepthEstimationException.UnexpectedOutput("The model has no output named '$n'")
        }
        val half = (session.inputInfo[inputName]?.info as? TensorInfo)?.type == OnnxJavaType.FLOAT16
        val tensors = mutableListOf<OnnxTensor>()
        try {
            val image = if (half) {
                OnnxTensor.createTensor(env, ShortBuffer.wrap(Float16.toHalfArray(input)), spec.inputShape(box),
                    OnnxJavaType.FLOAT16)
            } else {
                OnnxTensor.createTensor(env, FloatBuffer.wrap(input), spec.inputShape(box))
            }.also { tensors += it }
            val feeds = mutableMapOf<String, OnnxTensor>(inputName to image)
            val tokens = pm.numTokensName
            if (tokens != null && tokens in session.inputNames) {
                feeds[tokens] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(pm.numTokens.toLong())),
                    longArrayOf()).also { tensors += it }
            }
            OrtSession.RunOptions().use { options ->
                cancel.attach(options)
                try {
                    if (cancel.isCancelled) throw CancellationException("Depth estimation cancelled")
                    session.run(feeds, wanted.toSet(), options).use { result ->
                        fun floats(name: String): Pair<FloatArray, LongArray> {
                            val t = result.get(name).orElse(null) as? OnnxTensor
                                ?: throw DepthEstimationException.UnexpectedOutput("Output '$name' is not a tensor")
                            val b = t.floatBuffer
                            return FloatArray(b.remaining()).also { b.get(it) } to t.info.shape
                        }
                        val (pts, shape) = floats(pm.pointsName)
                        // [1,H,W,3] (every leading dim before H must be 1)
                        val ok = shape.size >= 3 && shape.last() == POINT_DIMS.toLong() &&
                            shape.dropLast(3).all { it == 1L } && shape.all { it > 0 }
                        if (!ok) {
                            throw DepthEstimationException.UnexpectedOutput(
                                "Unsupported points shape ${shape.joinToString("x")}; expected [1,H,W,3]",
                            )
                        }
                        val h = shape[shape.size - 3].toInt()
                        val w = shape[shape.size - 2].toInt()
                        if (pts.size != w * h * POINT_DIMS) {
                            throw DepthEstimationException.UnexpectedOutput(
                                "Points hold ${pts.size} values for ${w}x$h",
                            )
                        }
                        val mask = pm.maskName?.let { floats(it).first }
                        val scale = pm.scaleName?.let { floats(it).first.firstOrNull() } ?: 1f
                        return RawPointMap(pts, mask, scale, w, h)
                    }
                } finally {
                    cancel.detach()
                }
            }
        } catch (e: OrtException) {
            if (cancel.isCancelled) throw CancellationException("Depth estimation cancelled")
            throw DepthEstimationException.InferenceFailed("Depth inference failed: ${e.message}", e)
        } finally {
            tensors.forEach { it.close() }
        }
    }

    private companion object {
        const val POINT_DIMS = 3
    }
    }
}
