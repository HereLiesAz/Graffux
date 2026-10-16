// FILE: core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/GpuStampEngineSelfTest.kt
package com.hereliesaz.graffitixr.nativebridge

import android.graphics.Bitmap
import android.graphics.Color

/**
 * On-device proof that [GpuStampEngine] (the wgpu engine) actually works on the phone it's running
 * on: a physical GPU driver accepting the pipeline and producing correct pixels is the one thing
 * no host build can verify. Wired into Settings as "Test GPU Engine" so a person with the device in
 * hand can run it directly.
 *
 * Stamps three overlapping dabs — a big soft one, two smaller harder ones — in solid red at 70%
 * flow, so a passing run visibly shows both the hardness/radius falloff profile and SRC_OVER
 * build-up where the dabs overlap, the two things the stamp shader has to get right to match
 * the former CPU round-tip renderer (deleted).
 */
object GpuStampEngineSelfTest {

    private const val SIZE = 256

    data class Result(
        val success: Boolean,
        /** Human-readable outcome, safe to show directly in a dialog. */
        val message: String,
        /** The stamped layer, only present when [success] is true. */
        val bitmap: Bitmap?,
    )

    /**
     * Runs init → stampDabs → readback → destroy synchronously. The calls block on GPU work, so
     * callers MUST invoke this off the main thread (e.g. `withContext(Dispatchers.Default)`).
     */
    fun run(): Result {
        val engine = try {
            GpuStampEngine()
        } catch (e: UnsatisfiedLinkError) {
            return failure("Native library failed to load: ${e.message}")
        }
        return try {
            stampAndRead(engine)
        } finally {
            engine.destroy()
        }
    }

    private fun failure(message: String) = Result(success = false, message = message, bitmap = null)

    private fun stampAndRead(engine: GpuStampEngine): Result {
        val center = SIZE / 2f
        val dabs = listOf(
            BrushDab(x = center, y = center, radius = 70f, alpha = 0.6f, angleDeg = 0f),
            BrushDab(x = center - 40f, y = center - 20f, radius = 40f, alpha = 1f, angleDeg = 0f),
            BrushDab(x = center + 35f, y = center + 25f, radius = 35f, alpha = 1f, angleDeg = 0f),
        )
        val red = 0xFFFF0000.toInt()
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        return when {
            !engine.init(SIZE, SIZE) -> failure(
                "init() failed — the wgpu engine found no usable GPU adapter (or the library is " +
                    "missing from this build). Strokes draw on the CPU on this device. Check " +
                    "logcat tag WgpuStampEngine for the reason.",
            )
            !engine.stampDabs(dabs, red, hardness = 0.4f) -> failure(
                "stampDabs() failed after a successful init() — the dispatch itself was " +
                    "rejected. Check logcat tag WgpuStampEngine.",
            )
            !engine.readback(bitmap) -> failure(
                "readback() failed after a successful stampDabs() — the GPU→CPU copy didn't " +
                    "complete. Check logcat tag WgpuStampEngine.",
            )
            else -> verifyPixels(bitmap, center)
        }
    }

    /**
     * The boolean returns above only prove the calls completed; a driver can return a transparent or
     * wrongly coloured bitmap. The core of the small, opaque dab must be solid red and the far
     * corner (outside every dab) untouched.
     */
    private fun verifyPixels(bitmap: Bitmap, center: Float): Result {
        val core = bitmap.getPixel((center - 40f).toInt(), (center - 20f).toInt())
        val corner = bitmap.getPixel(2, 2)
        val coreOk = Color.alpha(core) >= 200 && Color.red(core) >= 200 &&
            Color.green(core) <= 40 && Color.blue(core) <= 40
        val cornerOk = Color.alpha(corner) == 0
        return when {
            !coreOk -> failure(
                "The GPU produced wrong pixels: the dab core read back as " +
                    "#${Integer.toHexString(core)} instead of solid red.",
            )
            !cornerOk -> failure(
                "The GPU produced wrong pixels: an untouched corner read back as " +
                    "#${Integer.toHexString(corner)} instead of transparent.",
            )
            else -> Result(success = true, message = "wgpu stamp engine works on this device.", bitmap = bitmap)
        }
    }
}
