package com.hereliesaz.graffitixr.ml

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import com.hereliesaz.graffitixr.common.azphalt.parseManifest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the real depth ONNX models on a device/emulator: MiDaS v2.1 small, Depth Anything V2 small and
 * Depth Anything V3 small. **Skipped (not failed) when the weights are
 * not present** -- weights are never committed.
 *
 * To run it:
 * ```
 * adb push midas.onnx /sdcard/Android/data/com.hereliesaz.graffitixr.ml.test/files/midas.onnx
 * ./gradlew :core:ml:connectedDebugAndroidTest
 * ```
 * Depth Anything V2 small (the listing's `model_fp16.onnx` or an fp32 export):
 * ```
 * adb push model_fp16.onnx /sdcard/Android/data/com.hereliesaz.graffitixr.ml.test/files/depth_anything_v2_small.onnx
 * ```
 * Depth Anything V3 small is TWO files that must sit together under these exact names:
 * ```
 * adb shell mkdir -p /sdcard/Android/data/com.hereliesaz.graffitixr.ml.test/files/depth_anything_v3_small
 * adb push model.onnx      /sdcard/Android/data/com.hereliesaz.graffitixr.ml.test/files/depth_anything_v3_small/
 * adb push model.onnx_data /sdcard/Android/data/com.hereliesaz.graffitixr.ml.test/files/depth_anything_v3_small/
 * ```
 * Each test skips when its own weights are absent. The V2/V3 tests check that the dynamic and 5-D paths run,
 * that a non-multiple-of-14 image gets a multiple-of-14 map, and that every sample is finite. They do NOT judge
 * depth quality or V3's polarity on a real scene (a synthetic gradient has no depth): after a run, look at
 * `map.normalized().toGrayscaleArgb()` of a real photo -- near things must be white for both models. If V3 looks
 * flat or inverted, try `decode.invert: "reflect"` in the listing (log-depth output). (The weights must be the file the listing's `checksum` pins; this test does not fetch or verify them,
 * `ModelAssetCache` does.) Any directory the test app can read works; the test looks in its external
 * files dir and then its internal files dir.
 */
class OnnxDepthEstimatorInstrumentedTest {
    private fun weights(relative: String = "midas.onnx"): File? {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        return listOfNotNull(ctx.getExternalFilesDir(null), ctx.filesDir)
            .map { File(it, relative) }
            .firstOrNull { it.isFile }
    }

    /** The `io` of the real listings, trimmed to what the runtime reads. */
    private fun spec(inputShape: String, higherIsNearer: Boolean): DepthModelSpec {
        val json = """{"azphalt":"0.1","id":"a.b.c","name":"n","version":"1.0.0","kind":"asset","license":"MIT",
          "compat":">=0.1","assets":[{"type":"onnx","path":"","role":"depth","io":{
          "inputs":[{"name":"pixel_values","shape":$inputShape,"dtype":"float32","layout":"NCHW","color":"RGB",
            "normalization":{"mean":[0.485,0.456,0.406],"std":[0.229,0.224,0.225]}}],
          "outputs":[{"name":"predicted_depth","semantics":"mask","decode":{"kind":"relative-depth",
            "higherIsNearer":$higherIsNearer,"sizeMultipleOf":14}}]}}]}"""
        return DepthModelSpecs.fromAsset(parseManifest(json).assets.single())
    }

    private fun scene(w: Int, h: Int): Bitmap = gradient(w, h)

    private fun checkMultipleOf14AndFinite(file: File, spec: DepthModelSpec) = runBlocking {
        OnnxDepthEstimator(file, spec).use { estimator ->
            val map = estimator.estimate(scene(500, 333)) // deliberately not a multiple of 14
            assertEquals(0, map.width % 14)
            assertEquals(0, map.height % 14)
            assertTrue(map.values.all { it.isFinite() })
            val n = map.normalized()
            assertTrue(n.values.min() == 0f && n.values.max() == 1f)
        }
    }

    @Test
    fun depthAnythingV2SmallRunsDynamicAndFinite() {
        val file = weights("depth_anything_v2_small.onnx")
        assumeTrue("Depth Anything V2 weights not present (see class doc)", file != null)
        checkMultipleOf14AndFinite(file!!, spec("[-1,3,-1,-1]", higherIsNearer = true))
    }

    @Test
    fun depthAnythingV3SmallRunsFiveDim() {
        val file = weights("depth_anything_v3_small/model.onnx")
        assumeTrue("Depth Anything V3 weights not present (see class doc)", file != null)
        assumeTrue("model.onnx_data must sit beside model.onnx", File(file!!.parentFile, "model.onnx_data").isFile)
        checkMultipleOf14AndFinite(file, spec("[-1,-1,3,-1,-1]", higherIsNearer = false))
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
