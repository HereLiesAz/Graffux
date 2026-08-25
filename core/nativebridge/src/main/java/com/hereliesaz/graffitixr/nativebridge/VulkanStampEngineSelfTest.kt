// FILE: core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/VulkanStampEngineSelfTest.kt
package com.hereliesaz.graffitixr.nativebridge

import android.graphics.Bitmap

/**
 * On-device proof that [VulkanStampEngine] actually works on the phone it's running on — the one
 * thing that could not be verified in the sandbox this engine was written in (compiling and
 * linking real Vulkan C++ against the NDK toolchain is verifiable there; a physical GPU driver
 * accepting the compute pipeline and producing correct pixels is not). Wired into Settings as
 * "Test GPU Engine" so a person with the device in hand can run it directly, no build/deploy of a
 * feature-complete brush pipeline required first.
 *
 * Stamps three overlapping dabs — a big soft one, two smaller harder ones — in solid red at 70%
 * flow, so a passing run visibly shows both the hardness/radius falloff profile and SRC_OVER
 * build-up where the dabs overlap, the two things [stamp.comp] has to get right to ever replace
 * `StampBrushRenderer`'s CPU round-tip path.
 */
object VulkanStampEngineSelfTest {

    private const val SIZE = 256

    data class Result(
        val success: Boolean,
        /** Human-readable outcome, safe to show directly in a dialog. */
        val message: String,
        /** The stamped layer, only present when [success] is true. */
        val bitmap: Bitmap?,
    )

    /**
     * Runs init → stampDabs → readback → destroy synchronously. Vulkan calls block on GPU work,
     * so callers MUST invoke this off the main thread (e.g. `withContext(Dispatchers.Default)`).
     */
    fun run(): Result {
        val engine = VulkanStampEngine()
        try {
            if (!engine.init(SIZE, SIZE)) {
                return Result(
                    success = false,
                    message = "init() failed — no compute-capable Vulkan 1.1 device found, or the " +
                        "driver rejected this engine's storage-image/pipeline setup. Check logcat " +
                        "tag VulkanStampEngine for the specific VkResult.",
                    bitmap = null,
                )
            }

            val center = SIZE / 2f
            val dabs = listOf(
                BrushDab(x = center, y = center, radius = 70f, alpha = 0.6f, angleDeg = 0f),
                BrushDab(x = center - 40f, y = center - 20f, radius = 40f, alpha = 1f, angleDeg = 0f),
                BrushDab(x = center + 35f, y = center + 25f, radius = 35f, alpha = 1f, angleDeg = 0f),
            )
            val red = 0xFFFF0000.toInt()
            if (!engine.stampDabs(dabs, red, hardness = 0.4f)) {
                return Result(
                    success = false,
                    message = "stampDabs() failed after a successful init() — the dispatch itself " +
                        "was rejected. Check logcat tag VulkanStampEngine.",
                    bitmap = null,
                )
            }

            val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            if (!engine.readback(bitmap)) {
                return Result(
                    success = false,
                    message = "readback() failed after a successful stampDabs() — the GPU→CPU copy " +
                        "didn't complete. Check logcat tag VulkanStampEngine.",
                    bitmap = null,
                )
            }

            return Result(success = true, message = "Vulkan compute stamp engine works on this device.", bitmap = bitmap)
        } catch (e: UnsatisfiedLinkError) {
            return Result(
                success = false,
                message = "Native library failed to load: ${e.message}",
                bitmap = null,
            )
        } finally {
            engine.destroy()
        }
    }
}
