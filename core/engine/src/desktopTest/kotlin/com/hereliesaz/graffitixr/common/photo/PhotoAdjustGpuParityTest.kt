package com.hereliesaz.graffitixr.common.photo

import com.hereliesaz.graffitixr.common.azphalt.wgpu.WgpuPhotoAdjuster
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The GPU adjuster (core/wgpu-engine, through JNI) against the Kotlin reference, every stage on at
 * once: tone, colour, Detail, curves, levels, HSL, vignette, grain and the mask. Skipped (passes)
 * where libgraffux_wgpu is not built or there is no adapter; `cargo build --release` in
 * core/wgpu-engine makes the library this picks up.
 *
 * It also times a phone-sized (1080 x 2400) slider move and prints it, the number quoted in the
 * photo-tools PR; on a CI software rasteriser it is only an upper bound.
 */
class PhotoAdjustGpuParityTest {

    private fun adjuster(w: Int, h: Int): WgpuPhotoAdjuster? {
        if (System.getProperty("graffux.wgpu.library") == null) {
            val lib = listOf("../wgpu-engine/target/release/libgraffux_wgpu.so", "core/wgpu-engine/target/release/libgraffux_wgpu.so")
                .map(::File).firstOrNull { it.isFile }
            lib?.let { System.setProperty("graffux.wgpu.library", it.absolutePath) }
        }
        return WgpuPhotoAdjuster.create(w, h)
    }

    private fun source(w: Int, h: Int): ByteArray = ByteArray(w * h * 4).also { b ->
        for (i in 0 until w * h) {
            val x = (i % w).toFloat() / (w - 1)
            val y = (i / w).toFloat() / (h - 1)
            val a = if (i % 13 == 0) 0.5f else 1f
            val rgb = floatArrayOf(x * (1 - y) + 0.1f * y, (1 - x) * 0.8f, 0.2f + 0.7f * x * y)
            for (k in 0..2) b[i * 4 + k] = (rgb[k].coerceIn(0f, 1f) * a * 255f + 0.5f).toInt().toByte()
            b[i * 4 + 3] = (a * 255f + 0.5f).toInt().toByte()
        }
    }

    @Test
    fun `every stage matches the CPU reference`() {
        val w = 64
        val h = 48
        val gpu = adjuster(w, h) ?: return
        gpu.use {
            var adj = PhotoAdjustment(
                PhotoTool.LIGHT,
                curves = mapOf(
                    CurveChannel.RGB to listOf(CurvePoint(0f, 0f), CurvePoint(0.3f, 0.25f), CurvePoint(0.75f, 0.82f), CurvePoint(1f, 1f)),
                    CurveChannel.BLUE to listOf(CurvePoint(0f, 0.05f), CurvePoint(1f, 0.95f)),
                ),
                mask = AdjustmentMask(enabled = true),
            )
            listOf(
                PhotoSliders.EXPOSURE to 0.35f, PhotoSliders.CONTRAST to 12f, PhotoSliders.HIGHLIGHTS to -38f,
                PhotoSliders.SHADOWS to 41f, PhotoSliders.WHITES to 8f, PhotoSliders.BLACKS to -14f,
                PhotoSliders.TEMPERATURE to 5900f, PhotoSliders.TINT to 4f, PhotoSliders.VIBRANCE to 18f,
                PhotoSliders.SATURATION to -6f, PhotoSliders.CLARITY to 18f, PhotoSliders.TEXTURE to 10f,
                PhotoSliders.DEHAZE to 22f, PhotoSliders.NOISE_REDUCTION to 30f, PhotoSliders.SHARPEN to 25f,
                PhotoSliders.VIGNETTE to -40f, PhotoSliders.GRAIN to 30f, PhotoSliders.GRAIN_SIZE to 40f,
                PhotoSliders.LEVELS_BLACK to 12f, PhotoSliders.LEVELS_MID to 1.1f, PhotoSliders.LEVELS_WHITE to 241f,
                PhotoSliders.hsl(HueRange.ORANGE, 0) to 6f, PhotoSliders.hsl(HueRange.ORANGE, 1) to 24f,
                PhotoSliders.hsl(HueRange.BLUE, 2) to -12f,
            ).forEach { (s, v) -> adj = adj.with(s, v) }
            val params = PhotoAdjustParams.pack(adj, grainSeed = 1234)
            params[16] = w.toFloat()
            params[17] = h.toFloat()
            val curves = PhotoAdjustParams.curvesTable(adj)
            val mask = ByteArray(w * h) { ((it * 5) % 256).toByte() }
            val src = source(w, h)
            assertTrue(it.setSource(src) && it.setCurves(curves) && it.setMask(mask))
            val out = ByteArray(w * h * 4)
            assertTrue(it.render(params, out))
            val want = ByteArray(w * h * 4)
            PhotoAdjustMath.render(src, w, h, params, curves, mask, want)
            var worst = 0
            var at = 0
            for (i in out.indices) {
                val d = kotlin.math.abs((out[i].toInt() and 255) - (want[i].toInt() and 255))
                if (d > worst) { worst = d; at = i }
            }
            assertTrue(worst <= 3, "byte $at differs by $worst (gpu ${out[at].toInt() and 255}, cpu ${want[at].toInt() and 255})")
        }
    }

    @Test
    fun `a phone-sized slider move`() {
        val w = 1080
        val h = 2400
        val gpu = adjuster(w, h) ?: return
        gpu.use {
            it.setSource(source(w, h))
            val out = ByteArray(w * h * 4)
            val times = (0 until 12).map { step ->
                val adj = PhotoAdjustment(PhotoTool.LIGHT).with(PhotoSliders.EXPOSURE, step / 10f)
                assertTrue(it.render(PhotoAdjustParams.pack(adj), out))
                it.lastRenderNanos
            }.drop(2)
            println("PHOTO_ADJUST_TIMING 1080x2400 median ${times.sorted()[times.size / 2] / 1_000_000.0} ms")
        }
    }

    @Test
    fun `outline and sketch match the CPU reference`() {
        val w = 64
        val h = 48
        val gpu = adjuster(w, h) ?: return
        gpu.use {
            val adj = PhotoAdjustment(PhotoTool.EFFECTS)
                .with(PhotoSliders.OUTLINE, 90f).with(PhotoSliders.OUTLINE_THRESHOLD, 8f)
                .with(PhotoSliders.OUTLINE_PAPER, 40f).with(PhotoSliders.SKETCH, 60f)
                .with(PhotoSliders.SKETCH_DARKNESS, 30f)
            val params = PhotoAdjustParams.pack(adj)
            params[16] = w.toFloat()
            params[17] = h.toFloat()
            val src = source(w, h)
            assertTrue(it.setSource(src))
            val out = ByteArray(w * h * 4)
            assertTrue(it.render(params, out))
            val want = ByteArray(w * h * 4)
            PhotoAdjustMath.render(src, w, h, params, null, null, want)
            // Lines are all-or-nothing, so a threshold tie can flip a pixel: count, don't max.
            val wrong = out.indices.count { i -> kotlin.math.abs((out[i].toInt() and 255) - (want[i].toInt() and 255)) > 3 }
            assertTrue(wrong <= out.size / 200, "$wrong bytes differ")
        }
    }

    @Test
    fun `a phone-sized effects slider move`() {
        val w = 1080
        val h = 2400
        val gpu = adjuster(w, h) ?: return
        gpu.use {
            it.setSource(source(w, h))
            val out = ByteArray(w * h * 4)
            val times = (0 until 12).map { step ->
                val adj = PhotoAdjustment(PhotoTool.EFFECTS)
                    .with(PhotoSliders.OUTLINE, 100f).with(PhotoSliders.OUTLINE_THRESHOLD, 10f + step)
                    .with(PhotoSliders.SKETCH, 50f + step)
                assertTrue(it.render(PhotoAdjustParams.pack(adj), out))
                it.lastRenderNanos
            }
            // The first move also builds the blur and edge planes (once per upload).
            println("PHOTO_EFFECTS_TIMING 1080x2400 first ${times[0] / 1_000_000.0} ms")
            val moves = times.drop(2)
            println("PHOTO_EFFECTS_TIMING 1080x2400 median ${moves.sorted()[moves.size / 2] / 1_000_000.0} ms")
        }
    }
}
