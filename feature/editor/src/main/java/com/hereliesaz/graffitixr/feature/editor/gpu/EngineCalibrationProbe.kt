package com.hereliesaz.graffitixr.feature.editor.gpu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine
import com.hereliesaz.graffitixr.nativebridge.ResolvedBrushDab
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * The real benchmark: a short offscreen run on the active [GpuStampEngine] backend. Each step
 * builds its own engine and tears it down, so a GPU context lost while the app was hidden is
 * recovered by re-running the step ([StepOutcome.ContextLost] on any failed init or call). All
 * work runs on [dispatcher], never the main thread (wgpu calls additionally hop to its render
 * thread inside [GpuStampEngine]).
 *
 * Sizes are chosen to keep the whole run to roughly 0.5-2 s on a phone-class GPU; that is an
 * estimate, not a measurement.
 *
 * * Stamp: [STAMP_BATCHES] batches of [DABS_PER_BATCH] soft round dabs on a [SIZE] square layer,
 *   ended by a readback so asynchronous backends (GLES) have finished; dabs per millisecond.
 * * Readback: a whole-layer dirty region read back [REPEATS] times; median MB/s.
 * * Composite: [LAYERS] full-size layers drawn with alpha onto one bitmap through
 *   `android.graphics.Canvas`, the path the editor composites with; median ms.
 */
class EngineCalibrationProbe(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : CalibrationProbe {

    override suspend fun identity(): GpuInfo? = withContext(dispatcher) {
        val engine = runCatching { GpuStampEngine() }.getOrNull() ?: return@withContext null
        try {
            if (!engine.init(IDENTITY_SIZE, IDENTITY_SIZE)) return@withContext null
            GpuInfo.parse(engine.gpuInfo())
        } finally {
            engine.destroy()
        }
    }

    override suspend fun measure(step: CalibrationStep): StepOutcome = withContext(dispatcher) {
        when (step) {
            CalibrationStep.STAMP -> withEngine { e, out -> stamp(e, out) }
            CalibrationStep.READBACK -> withEngine { e, out -> readback(e, out) }
            CalibrationStep.COMPOSITE -> StepOutcome.Measured(composite())
        }
    }

    /** Thrown by [gpu] when a call fails: the context is gone, the step reruns on a new engine. */
    private class ContextLostException : RuntimeException()

    private fun gpu(ok: Boolean) {
        if (!ok) throw ContextLostException()
    }

    private inline fun withEngine(block: (GpuStampEngine, Bitmap) -> Double): StepOutcome {
        val engine = runCatching { GpuStampEngine() }.getOrNull() ?: return StepOutcome.ContextLost
        val out = createBitmap(SIZE, SIZE)
        return try {
            gpu(engine.init(SIZE, SIZE))
            StepOutcome.Measured(block(engine, out))
        } catch (@Suppress("SwallowedException") e: ContextLostException) {
            StepOutcome.ContextLost
        } finally {
            engine.destroy()
            out.recycle()
        }
    }

    private fun stamp(engine: GpuStampEngine, out: Bitmap): Double {
        val random = Random(SEED)
        fun batch() = List(DABS_PER_BATCH) {
            ResolvedBrushDab(
                x = random.nextFloat() * SIZE, y = random.nextFloat() * SIZE, radius = DAB_RADIUS,
                alpha = DAB_ALPHA, angleDeg = 0f, colorArgb = DAB_COLOR, flow = 1f, hardness = DAB_HARDNESS,
            )
        }
        // Warm-up: pipeline creation and first-use allocation are not throughput.
        gpu(engine.stampResolvedDabs(batch()) && engine.readback(out))
        val batches = List(STAMP_BATCHES) { batch() }
        val start = System.nanoTime()
        for (b in batches) gpu(engine.stampResolvedDabs(b))
        gpu(engine.readback(out))
        val ms = (System.nanoTime() - start) / NANOS_PER_MS
        return STAMP_BATCHES * DABS_PER_BATCH / ms.coerceAtLeast(MIN_MS)
    }

    private fun readback(engine: GpuStampEngine, out: Bitmap): Double {
        // One dab whose footprint covers the layer dirties all of it, so readback copies it all.
        val cover = ResolvedBrushDab(
            x = SIZE / 2f, y = SIZE / 2f, radius = SIZE.toFloat(), alpha = DAB_ALPHA, angleDeg = 0f,
            colorArgb = DAB_COLOR, flow = 1f,
        )
        val bytes = SIZE.toDouble() * SIZE * BYTES_PER_PIXEL
        val samples = mutableListOf<Double>()
        repeat(REPEATS + 1) { i ->
            gpu(engine.stampResolvedDabs(listOf(cover), buildUp = true))
            val start = System.nanoTime()
            gpu(engine.readback(out))
            val seconds = (System.nanoTime() - start) / NANOS_PER_SECOND
            if (i > 0) samples += bytes / BYTES_PER_MB / seconds.coerceAtLeast(MIN_SECONDS) // i=0 warms up
        }
        return samples.sorted()[samples.size / 2]
    }

    private fun composite(): Double {
        val layers = List(LAYERS) { i ->
            createBitmap(SIZE, SIZE).apply { eraseColor(LAYER_COLORS[i % LAYER_COLORS.size]) }
        }
        val target = createBitmap(SIZE, SIZE)
        val canvas = Canvas(target)
        val paint = Paint().apply { alpha = LAYER_ALPHA }
        return try {
            val samples = List(REPEATS + 1) {
                val start = System.nanoTime()
                target.eraseColor(0)
                layers.forEach { canvas.drawBitmap(it, 0f, 0f, paint) }
                (System.nanoTime() - start) / NANOS_PER_MS
            }.drop(1)
            samples.sorted()[samples.size / 2]
        } finally {
            layers.forEach(Bitmap::recycle)
            target.recycle()
        }
    }

    private companion object {
        const val IDENTITY_SIZE = 64
        const val SIZE = 1024
        const val STAMP_BATCHES = 16
        const val DABS_PER_BATCH = 256
        const val DAB_RADIUS = 24f
        const val DAB_ALPHA = 0.8f
        const val DAB_HARDNESS = 0.5f
        const val DAB_COLOR = 0xFF3366CC.toInt()
        const val REPEATS = 3
        const val LAYERS = 4
        const val LAYER_ALPHA = 200
        val LAYER_COLORS = intArrayOf(0x80FF0000.toInt(), 0x8000FF00.toInt(), 0x800000FF.toInt(), 0x80FFFFFF.toInt())
        const val SEED = 7
        const val BYTES_PER_PIXEL = 4
        const val BYTES_PER_MB = 1_000_000.0
        const val NANOS_PER_MS = 1_000_000.0
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val MIN_MS = 0.001
        const val MIN_SECONDS = 1e-6
    }
}
