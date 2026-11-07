package com.hereliesaz.graffitixr.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
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
import kotlin.math.max

/**
 * [DepthEstimator] backed by ONNX Runtime, for an `onnx`-type asset with role `depth` (MiDaS v2.1
 * small: fixed 256 px `input_image`, relative inverse depth out).
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

    override suspend fun estimate(source: Bitmap, region: Rect?): DepthMap {
        val area = validatedRegion(source, region)
        val (pixels, w, h) = extractPixels(source, area)
        val cancel = CancelHandle()
        return coroutineScope {
            val work = async(dispatcher) {
                ensureActive()
                val runner = OrtRunner(openSession(), cancel)
                DepthPipeline.estimate(pixels, w, h, spec, runner)
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

    /** One ONNX Runtime call per [run]. */
    private class OrtRunner(private val session: OrtSession, private val cancel: CancelHandle) : DepthModelRunner {
        @Suppress("NestedBlockDepth")
        override fun run(input: FloatArray, spec: DepthModelSpec): FloatArray {
            val env = OrtEnvironment.getEnvironment()
            val inputName = session.inputNames.firstOrNull()
                ?: throw DepthEstimationException.UnexpectedOutput("The model declares no input")
            val side = spec.inputSize.toLong()
            val shape = longArrayOf(1, DepthModelSpec.CHANNELS.toLong(), side, side)
            try {
                OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
                    OrtSession.RunOptions().use { options ->
                        cancel.attach(options)
                        try {
                            if (cancel.isCancelled) throw CancellationException("Depth estimation cancelled")
                            session.run(mapOf(inputName to tensor), options).use { result ->
                                val out = result.get(0) as? OnnxTensor
                                    ?: throw DepthEstimationException.UnexpectedOutput("Output is not a tensor")
                                val buf = out.floatBuffer
                                return FloatArray(buf.remaining()).also { buf.get(it) }
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

    private companion object {
        const val PREFILTER_LONG_SIDE = 1024
        const val MAX_THREADS = 4
    }
}
