package com.hereliesaz.graffitixr.ml

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the real MiDaS v2.1 small ONNX on a device/emulator. **Skipped (not failed) when the weights are
 * not present** -- weights are never committed.
 *
 * To run it:
 * ```
 * adb push midas.onnx /sdcard/Android/data/com.hereliesaz.graffitixr.ml.test/files/midas.onnx
 * ./gradlew :core:ml:connectedDebugAndroidTest
 * ```
 * (The weights must be the file the listing's `checksum` pins; this test does not fetch or verify them,
 * `ModelAssetCache` does.) Any directory the test app can read works; the test looks in its external
 * files dir and then its internal files dir.
 */
class OnnxDepthEstimatorInstrumentedTest {
    private fun weights(): File? {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        return listOfNotNull(ctx.getExternalFilesDir(null), ctx.filesDir)
            .map { File(it, "midas.onnx") }
            .firstOrNull { it.isFile }
    }

    private fun gradient(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            for (y in 0 until h) for (x in 0 until w) {
                val v = (255 * x / (w - 1))
                bmp.setPixel(x, y, Color.rgb(v, 255 - v, (y * 255) / (h - 1)))
            }
        }

    @Test
    fun estimatesAFiniteDepthMapAtSourceAspect() = runBlocking {
        val file = weights()
        assumeTrue("MiDaS weights not present; push midas.onnx to the test app's files dir (see class doc)", file != null)
        OnnxDepthEstimator(file!!).use { estimator ->
            val map = estimator.estimate(gradient(640, 320))
            assertEquals(256, map.width)
            assertEquals(128, map.height)
            assertTrue(map.values.all { it.isFinite() })
            val n = map.normalized()
            assertTrue(n.values.min() == 0f && n.values.max() == 1f)
            assertEquals(640, n.resampledTo(640, 320).width)
        }
    }

    @Test
    fun honoursARegion() = runBlocking {
        val file = weights()
        assumeTrue("MiDaS weights not present; push midas.onnx to the test app's files dir (see class doc)", file != null)
        OnnxDepthEstimator(file!!).use { estimator ->
            val map = estimator.estimate(gradient(400, 400), Rect(0, 0, 400, 100))
            assertEquals(256, map.width)
            assertEquals(64, map.height)
        }
    }
}
