@file:Suppress("MagicNumber")

package com.hereliesaz.graffux.golden

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Golden-image assertions for the Robolectric screenshot tests.
 *
 * ```
 * Golden.assertMatches("audit-layer-menu", rule.onRoot().captureToImage().asAndroidBitmap())
 * ```
 *
 * The golden lives at `app/src/test/golden/<name>.png`. A missing golden is a FAILURE, never a pass:
 * a test that compares against nothing proves nothing. To create or accept new goldens, run the test
 * with `GRAFFUX_UPDATE_GOLDENS=1`, look at the written PNGs, and commit them.
 *
 * A mismatch writes `<name>-actual.png` and `<name>-diff.png` (differing pixels in red) to
 * `app/build/screenshot-diffs/`, which CI uploads as the `screenshots` artifact.
 */
object Golden {
    private val goldenDir = File(System.getProperty("graffux.goldenDir") ?: "src/test/golden")
    private val diffDir = File(System.getProperty("graffux.screenshotDiffDir") ?: "build/screenshot-diffs")

    fun assertMatches(
        name: String,
        actual: Bitmap,
        channelTolerance: Int = 2,
        maxDiffRatio: Double = 0.001,
    ) {
        require(Regex("[A-Za-z0-9._-]+").matches(name)) { "golden name must be a plain file name: $name" }
        val file = File(goldenDir, "$name.png")
        val pixels = argb(actual)
        if (System.getenv("GRAFFUX_UPDATE_GOLDENS") == "1") {
            goldenDir.mkdirs()
            file.outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return
        }
        if (!file.isFile) {
            writePng(File(diffDir, "$name-actual.png"), pixels, actual.width, actual.height)
            throw AssertionError(
                "No golden image at ${file.path}. Run this test with GRAFFUX_UPDATE_GOLDENS=1, check the " +
                    "result, and commit it. The render was saved to ${File(diffDir, "$name-actual.png").path}.",
            )
        }
        val golden = requireNotNull(BitmapFactory.decodeFile(file.path)) { "${file.path} is not a readable PNG" }
        val expected = argb(golden)
        val diff = ImageCompare.compare(
            expected, golden.width, golden.height, pixels, actual.width, actual.height, channelTolerance,
        )
        if (diff.matches(maxDiffRatio)) return
        writePng(File(diffDir, "$name-actual.png"), pixels, actual.width, actual.height)
        if (!diff.sizeMismatch) {
            writePng(File(diffDir, "$name-diff.png"), ImageCompare.diffImage(expected, pixels, channelTolerance), actual.width, actual.height)
        }
        throw AssertionError("$name does not match its golden: ${diff.describe()}. See ${diffDir.path}.")
    }

    private fun argb(bitmap: Bitmap): IntArray {
        val software = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        return IntArray(software.width * software.height).also {
            software.getPixels(it, 0, software.width, 0, 0, software.width, software.height)
        }
    }

    private fun writePng(file: File, pixels: IntArray, width: Int, height: Int) {
        file.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
