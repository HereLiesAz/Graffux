package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test

/**
 * Renders brush packs through the real dab generators so a pack author can see what a stroke
 * program does. Not a regression test: it does nothing unless `-Pgraffux.packs=<dir>` names a
 * folder of unpacked packs (each with a `manifest.json` and its `assets/`).
 *
 * ~~~
 * ./gradlew :core:engine:desktopTest --tests '*PackRenderHarness*' --offline \
 *     -Pgraffux.packs=/path/to/packs -Pgraffux.packs.out=/path/to/out [-Pgraffux.packs.previews=true]
 * ~~~
 *
 * For each pack it writes `<out>/<pack id>.sheet.png` (every brush against six test strokes),
 * prints each program's compile result, dab counts, worst step cost against the budget, and the
 * time the generator took; with `previews=true` it also writes the store card to
 * `<pack>/assets/preview.png`. The stamping here is a CPU approximation of the GPU engine's
 * (max-combine, or over for build-up brushes); shapes and behaviour are real, pixels are close.
 */
class PackRenderHarness {
    @Test
    fun renderPacks() {
        val root = System.getProperty("graffux.packs")?.let(::File) ?: return
        render(root, File(System.getProperty("graffux.packs.out") ?: File(root, "_render").path),
            System.getProperty("graffux.packs.previews") == "true")
    }

    /** Standalone entry: `PackRenderHarness.main(<packs dir> <out dir> [previews])`, no Gradle needed. */
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            require(args.size >= 2) { "usage: <packs dir> <out dir> [previews|-] [diameter px]" }
            args.getOrNull(3)?.let { System.setProperty("graffux.packs.diameter", it) }
            render(File(args[0]), File(args[1]), args.getOrNull(2) == "previews")
        }

        private fun render(root: File, outDir: File, writePreviews: Boolean) = PackRenderHarness().render0(root, outDir, writePreviews)
    }

    private fun render0(root: File, outDir: File, writePreviews: Boolean) {
        val out = outDir.apply { mkdirs() }
        System.setProperty("java.awt.headless", "true")
        val problems = ArrayList<String>()
        for (dir in (root.listFiles() ?: emptyArray()).filter { File(it, "manifest.json").isFile }.sortedBy { it.name }) {
            val manifest = AzphaltJson.parseToJsonElement(File(dir, "manifest.json").readText()) as JsonObject
            val assets = (manifest["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "brush" }
            val brushes = assets.map { asset ->
                val params = asset["params"] as? JsonObject
                val path = (asset["path"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val name = (params?.get("name") as? JsonPrimitive)?.contentOrNull ?: path
                var brush = AzphaltBrush.fromParams(name, params)
                if (brush.shapePath == null && path.isNotBlank()) brush = brush.copy(shapePath = path)
                brush
            }
            println("== ${dir.name}: ${brushes.size} brushes")
            val rendered = brushes.map { brush -> renderBrush(dir, brush, problems) }
            ImageIO.write(sheet(dir.name, brushes, rendered), "png", File(out, "${dir.name}.sheet.png"))
            if (writePreviews) {
                val card = card(brushes, dir)
                File(dir, "assets").mkdirs()
                ImageIO.write(card, "png", File(dir, "assets/preview.png"))
            }
        }
        if (problems.isNotEmpty()) {
            println("PROBLEMS:\n" + problems.joinToString("\n") { " - $it" })
            throw AssertionError("pack problems:\n" + problems.joinToString("\n"))
        }
    }

    // ---- test strokes ------------------------------------------------------------------------

    private class Path(val name: String, val points: (Float) -> Pair<Float, Float>, val speed: (Float) -> Float,
        val pressure: (Float) -> Float, val lengthPx: Float)

    private fun strokes(w: Float, h: Float): List<Path> {
        val m = h * 0.22f
        val lw = w - 2 * m
        return listOf(
            Path("swoop", { t -> Pair(m + lw * t, h * 0.5f + sin(t * PI.toFloat() * 1.5f) * h * 0.28f) },
                { t -> 1.4f + 0.8f * sin(t * PI.toFloat()) }, { t -> 0.25f + 0.7f * sin(t * PI.toFloat()) }, lw * 1.15f),
            Path("slow", { t -> Pair(m + lw * t, h * 0.45f + sin(t * 9f) * h * 0.07f) },
                { 0.12f }, { 0.85f }, lw),
            Path("loop", { t ->
                val a = t * PI.toFloat() * 2.2f
                Pair(w * 0.5f + cos(a) * lw * 0.28f * (0.7f + t * 0.5f), h * 0.5f + sin(a) * h * 0.27f * (0.8f + t * 0.3f))
            }, { 0.9f }, { 0.55f + 0.35f * sin(it * 6f) }, lw * 1.3f),
            Path("zigzag", { t ->
                val k = t * 6f
                val f = k - floor(k)
                val tri = if (floor(k).toInt() % 2 == 0) f else 1 - f
                Pair(m + lw * t, h * (0.28f + 0.44f * tri))
            }, { 1.1f }, { 0.7f }, lw * 1.8f),
            Path("fast", { t -> Pair(m + lw * t, h * 0.62f - h * 0.34f * t) },
                { 3.2f }, { 0.5f }, lw * 1.05f),
            Path("scrawl", { t ->
                Pair(m + lw * t + sin(t * 31f) * h * 0.1f, h * 0.5f + cos(t * 23f) * h * 0.2f + sin(t * 7f) * h * 0.12f)
            }, { 1.6f }, { 0.6f }, lw * 2.4f),
        )
    }

    private fun samples(path: Path): List<BrushSample> {
        val builder = BrushSampleBuilder()
        val out = ArrayList<BrushSample>()
        var distance = 0f
        var time = 0L
        var u = 0f
        out.add(builder.add(path.points(0f).first, path.points(0f).second, time, path.pressure(0f)))
        while (u < 1f) {
            val step = (path.speed(u) * Const.SAMPLE_MS).coerceAtLeast(0.2f)
            distance += step
            u = (distance / path.lengthPx).coerceAtMost(1f)
            time += Const.SAMPLE_MS
            val (x, y) = path.points(u)
            out.add(builder.add(x, y, time, path.pressure(u)))
        }
        return out
    }

    // ---- rendering ---------------------------------------------------------------------------

    private class Rendered(val tiles: List<BufferedImage>)

    private fun renderBrush(dir: File, brush: AzphaltBrush, problems: MutableList<String>): Rendered {
        val tip = brush.shapePath?.let { loadTip(File(dir, it)) }
        val program = brush.program
        if (program != null) {
            val compiled = StrokeProgram.compile(program)
            if (compiled is StrokeProgramCompilation.Error) problems.add("${dir.name} / ${brush.name}: ${compiled.message}")
        }
        val tiles = strokes(Const.TILE_W.toFloat(), Const.TILE_H.toFloat()).map { path ->
            val diameter = System.getProperty("graffux.packs.diameter")?.toFloatOrNull() ?: Const.DIAMETER
            val generator = IncrementalDynamicDabGenerator(diameter, brush, Const.SEED)
            val predictor = StrokeLengthPredictor()
            val started = System.nanoTime()
            val dabs = ArrayList<Dab>()
            for (s in samples(path)) {
                predictor.record(s)
                dabs.addAll(generator.append(s, predictor.predictedTotal))
            }
            val ms = (System.nanoTime() - started) / 1e6
            val runner = generator.programRunner
            val stats = if (runner != null) " peakOps=${runner.peakOps}/${StrokeProgram.MAX_OPS_PER_STEP} overruns=${runner.overruns}" else ""
            println("  %-26s %-7s dabs=%-6d %.1fms$stats".format(brush.name, path.name, dabs.size, ms))
            if (runner != null && runner.overruns > 0) problems.add("${dir.name} / ${brush.name} (${path.name}): ${runner.overruns} step(s) over budget")
            if (dabs.size > Const.MAX_SANE_DABS) problems.add("${dir.name} / ${brush.name} (${path.name}): ${dabs.size} dabs is more than ${Const.MAX_SANE_DABS}")
            stamp(dabs, tip, brush, Const.TILE_W, Const.TILE_H)
        }
        return Rendered(tiles)
    }

    private fun loadTip(file: File): BufferedImage? = runCatching { ImageIO.read(file) }.getOrNull()

    /** Max-combined (or build-up) stamping of [dabs] over a near-black card, white paint. */
    private fun stamp(dabs: List<Dab>, tip: BufferedImage?, brush: AzphaltBrush, w: Int, h: Int): BufferedImage {
        val cover = FloatArray(w * h)
        val rgb = FloatArray(w * h * 3) { Const.BG }
        val tipW = tip?.width ?: 0
        val tipH = tip?.height ?: 0
        // Tips carry coverage in alpha; an opaque gray tip carries it in luminance instead.
        val tipAlpha = tip?.let { img ->
            val hasAlpha = img.colorModel.hasAlpha()
            FloatArray(tipW * tipH) { i ->
                val argb = img.getRGB(i % tipW, i / tipW)
                if (hasAlpha) (argb ushr 24) / 255f else (argb and 0xFF) / 255f
            }
        }
        for (d in dabs) {
            val r = d.radius
            if (r <= 0.05f || d.alpha <= 0f) continue
            val ratio = d.tipRatio.coerceIn(0.05f, 1f)
            val rad = d.angleDeg * (PI.toFloat() / 180f)
            val ca = cos(rad)
            val sa = sin(rad)
            val reach = r / min(ratio, 1f) + 1f
            val x0 = max(0, floor(d.x - reach).toInt())
            val x1 = min(w - 1, floor(d.x + reach).toInt() + 1)
            val y0 = max(0, floor(d.y - reach).toInt())
            val y1 = min(h - 1, floor(d.y + reach).toInt() + 1)
            val (cr, cg, cb) = shade(d)
            for (py in y0..y1) {
                for (px in x0..x1) {
                    val dx = px + 0.5f - d.x
                    val dy = py + 0.5f - d.y
                    val u = (dx * ca + dy * sa) / r
                    val v = (-dx * sa + dy * ca) / (r * ratio)
                    if (u < -1f || u > 1f || v < -1f || v > 1f) continue
                    val cov = if (tipAlpha != null) {
                        sampleBilinear(tipAlpha, tipW, tipH, (u * 0.5f + 0.5f) * (tipW - 1), (v * 0.5f + 0.5f) * (tipH - 1))
                    } else {
                        val rn = kotlin.math.sqrt(u * u + v * v)
                        BrushStamps.stampCoverage(rn, d.hardness)
                    }
                    val a = (cov * d.alpha).coerceIn(0f, 1f)
                    if (a <= 0.003f) continue
                    val i = py * w + px
                    if (brush.buildUp) {
                        rgb[3 * i] = cr * a + rgb[3 * i] * (1 - a)
                        rgb[3 * i + 1] = cg * a + rgb[3 * i + 1] * (1 - a)
                        rgb[3 * i + 2] = cb * a + rgb[3 * i + 2] * (1 - a)
                        cover[i] = a + cover[i] * (1 - a)
                    } else if (a > cover[i]) {
                        // The strongest single dab wins, as in the engine's non-build-up combine.
                        val k = a
                        rgb[3 * i] = cr * k + Const.BG * (1 - k)
                        rgb[3 * i + 1] = cg * k + Const.BG * (1 - k)
                        rgb[3 * i + 2] = cb * k + Const.BG * (1 - k)
                        cover[i] = a
                    }
                }
            }
        }
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until w * h) {
            val c = Color(rgb[3 * i].coerceIn(0f, 1f), rgb[3 * i + 1].coerceIn(0f, 1f), rgb[3 * i + 2].coerceIn(0f, 1f))
            img.setRGB(i % w, i / w, c.rgb)
        }
        return img
    }

    private fun shade(d: Dab): Triple<Float, Float, Float> {
        val hsb = Color.RGBtoHSB((Const.PAINT * 255).toInt(), (Const.PAINT * 255).toInt(), (Const.PAINT * 255).toInt(), null)
        val hue = (hsb[0] + d.hueShiftDeg / 360f).let { it - floor(it) }
        val sat = (hsb[1] * d.saturationMultiplier + if (abs(d.hueShiftDeg) > 0.5f || d.saturationMultiplier > 1f) 0.6f else 0f).coerceIn(0f, 1f)
        val value = (hsb[2] * d.valueMultiplier).coerceIn(0f, 1f)
        val c = Color(Color.HSBtoRGB(hue, sat, value))
        return Triple(c.red / 255f, c.green / 255f, c.blue / 255f)
    }

    private fun sampleBilinear(a: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val xf = x.coerceIn(0f, (w - 1).toFloat())
        val yf = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = xf.toInt()
        val y0 = yf.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val fx = xf - x0
        val fy = yf - y0
        val top = a[y0 * w + x0] * (1 - fx) + a[y0 * w + x1] * fx
        val bottom = a[y1 * w + x0] * (1 - fx) + a[y1 * w + x1] * fx
        return top * (1 - fy) + bottom * fy
    }

    // ---- sheets and cards --------------------------------------------------------------------

    private fun sheet(pack: String, brushes: List<AzphaltBrush>, rendered: List<Rendered>): BufferedImage {
        val labelW = 190
        val head = 28
        val cols = rendered.firstOrNull()?.tiles?.size ?: 0
        val img = BufferedImage(labelW + cols * Const.TILE_W, head + brushes.size * Const.TILE_H, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(10, 10, 10)
        g.fillRect(0, 0, img.width, img.height)
        g.color = Color(190, 190, 190)
        runCatching {
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
            g.drawString(pack, 8, 18)
            strokes(Const.TILE_W.toFloat(), Const.TILE_H.toFloat()).forEachIndexed { c, p -> g.drawString(p.name, labelW + c * Const.TILE_W + 8, 18) }
            brushes.forEachIndexed { r, b -> g.drawString(b.name, 8, head + r * Const.TILE_H + 22) }
        }
        rendered.forEachIndexed { r, row -> row.tiles.forEachIndexed { c, tile -> g.drawImage(tile, labelW + c * Const.TILE_W, head + r * Const.TILE_H, null) } }
        g.dispose()
        return img
    }

    /** The store card: each brush on its own band, one swoop apiece, on near-black. */
    private fun card(brushes: List<AzphaltBrush>, dir: File): BufferedImage {
        val w = 960
        val h = 540
        val band = h / max(1, brushes.size)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(10, 10, 10)
        g.fillRect(0, 0, w, h)
        brushes.forEachIndexed { i, brush ->
            val tip = brush.shapePath?.let { loadTip(File(dir, it)) }
            val path = Path(
                "card",
                { t -> Pair(60f + (w - 120f) * t, band * 0.5f + sin(t * PI.toFloat() * 2f) * band * 0.18f) },
                { 1.5f }, { t -> 0.3f + 0.65f * sin(t * PI.toFloat()) }, (w - 120f) * 1.1f,
            )
            val generator = IncrementalDynamicDabGenerator(band * 0.42f, brush, Const.SEED + i)
            val predictor = StrokeLengthPredictor()
            val dabs = ArrayList<Dab>()
            for (s in samples(path)) {
                predictor.record(s)
                dabs.addAll(generator.append(s, predictor.predictedTotal))
            }
            g.drawImage(stamp(dabs, tip, brush, w, band), 0, i * band, null)
        }
        g.dispose()
        return img
    }

    private object Const {
        const val TILE_W = 420
        const val TILE_H = 150
        const val DIAMETER = 26f
        const val SAMPLE_MS = 8L
        const val SEED = 20_260_101L
        const val BG = 0.04f
        const val PAINT = 0.93f
        const val MAX_SANE_DABS = 60_000
    }
}
