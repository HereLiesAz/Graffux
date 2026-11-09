package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The painterly pack drafts under `spec/painterly-packs` (spec/painterly.md 5.5): every manifest is a
 * well-formed azphalt asset package, every brush parses and, where it has a stroke program, compiles and stays
 * inside the op budget on a petal pull, and the pigment pack matches the placeholder set in code.
 */
class PainterlyPacksTest {
    private val root = File("../../spec/painterly-packs")
    private val packs = listOf("acrylic", "oil", "water", "ink", "pigments")

    private fun dir(name: String) = File(root, "com.hereliesaz.azphalt.painterly.$name")

    private fun manifest(name: String): JsonObject =
        AzphaltJson.parseToJsonElement(File(dir(name), "manifest.json").readText()) as JsonObject

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull

    private fun pull(): List<BrushSample> {
        val b = BrushSampleBuilder()
        val n = 90
        val out = (0..n).map { i ->
            val t = i / n.toFloat()
            b.add(20f + 200f * t, 80f - 30f * t, uptimeMillis = i * 8L, pressure = maxOf(0.05f, sin(t * PI.toFloat())))
        }.toMutableList()
        val last = out.removeAt(out.lastIndex)
        out.add(last.copy(telemetry = last.telemetry.copy(contactPhase = BrushContactPhase.LIFT_OFF)))
        return out
    }

    @Test
    fun everyManifestIsAWellFormedFreeApacheAssetPackage() {
        for (p in packs) {
            val m = manifest(p)
            assertEquals("com.hereliesaz.azphalt.painterly.$p", str(m, "id"))
            assertEquals("asset", str(m, "kind"))
            assertEquals("Apache-2.0", str(m, "license"), p)
            assertEquals("1.0.0", str(m, "version"))
            assertTrue(File(dir(p), "LICENSE").isFile && File(dir(p), "LICENSE").readText().contains("Apache License"))
            assertTrue(m["files"] == null, "submissions carry no files digest map; CI computes it")
            val targets = (m["targetApps"] as JsonArray).map { (it as JsonPrimitive).content }
            assertEquals(listOf("com.hereliesaz.graffux"), targets)
            for (asset in m["assets"] as JsonArray) {
                val path = str(asset as JsonObject, "path").orEmpty()
                assertTrue(path.isNotEmpty() && File(dir(p), path).isFile, "$p asset file $path exists")
            }
        }
    }

    @Test
    fun everyBrushParsesAndEveryProgramCompilesAndStaysInBudget() {
        var program = 0
        for (p in packs.filter { it != "pigments" }) {
            val assets = (manifest(p)["assets"] as JsonArray).map { it as JsonObject }
            assertTrue(assets.isNotEmpty())
            val names = HashSet<String>()
            for (asset in assets) {
                assertEquals("brush", str(asset, "type"))
                val params = asset["params"] as JsonObject
                val name = str(params, "name").orEmpty()
                assertTrue(names.add(name), "$p: duplicate brush name $name")
                val brush = AzphaltBrush.fromParams(name, params)
                assertEquals(name, brush.name)
                val spec = brush.program
                if (spec != null) {
                    program++
                    assertTrue(brush.painterly > 0, "$p/$name: a program brush declares painterly")
                    assertTrue(StrokeProgram.compile(spec) is StrokeProgramCompilation.Ok, "$p/$name compiles")
                    val gen = IncrementalDynamicDabGenerator(34f, brush, 7L)
                    val dabs = ArrayList<Dab>()
                    for (s in pull()) dabs += gen.append(s)
                    val runner = assertNotNull(gen.programRunner)
                    assertEquals(0, runner.overruns, "$p/$name overran its budget")
                    assertTrue(runner.peakOps <= 8_000, "$p/$name peakOps=${runner.peakOps}")
                    assertTrue(dabs.isNotEmpty() && dabs.size < 20_000, "$p/$name dabs=${dabs.size}")
                    assertTrue(dabs.any { it.hasLaneIntent } || name.contains("Sponge"), "$p/$name names a lane")
                } else {
                    val dabs = LiveStrokeDabs.movementDabs(pull(), 34f, brush, 7L)
                    assertTrue(dabs.isNotEmpty(), "$p/$name paints")
                }
            }
        }
        assertTrue(program >= 25, "the packs ship real program brushes: $program")
    }

    @Test
    fun theSgraffitoAndRagUseDisposalAndLiftNotTheLegacyLedger() {
        val acrylic = (manifest("acrylic")["assets"] as JsonArray).map { (it as JsonObject)["params"] as JsonObject }
        val sgraffito = AzphaltBrush.fromParams("Sgraffito", acrylic.first { str(it, "name") == "Sgraffito" })
        val dabs = LiveStrokeDabs.movementDabs(pull(), 34f, sgraffito, 3L)
        assertTrue(dabs.any { it.dispose && it.lift > 0f })
        val rag = AzphaltBrush.fromParams("Rag", acrylic.first { str(it, "name") == "Rag" })
        assertTrue(LiveStrokeDabs.movementDabs(pull(), 34f, rag, 3L).any { it.lift > 0f && !it.dispose })
    }

    @Test
    fun theLoadedPetalFlatSwellsAndClosesToAPoint() {
        val acrylic = (manifest("acrylic")["assets"] as JsonArray).map { (it as JsonObject)["params"] as JsonObject }
        val petal = AzphaltBrush.fromParams("Loaded Petal Flat", acrylic.first { str(it, "name") == "Loaded Petal Flat" })
        val samples = pull()
        val dabs = LiveStrokeDabs.movementDabs(samples, 34f, petal, 7L)
        val lastX = samples.last().x
        val tail = dabs.filter { it.x > lastX + 1f }
        assertTrue(tail.isNotEmpty(), "the petal has a lift tail")
        fun spread(from: Float, to: Float): Float {
            val ys = dabs.filter { it.x in from..to && !it.dispose }.map { it.y - 30f * (it.x - 20f) / 200f }
            return if (ys.isEmpty()) 0f else ys.max() - ys.min()
        }
        assertTrue(spread(100f, 140f) > spread(20f, 50f) * 1.4f, "width swells with pressure")
        assertTrue(dabs.any { it.heightMul > 1.5f } && dabs.any { it.heightMul < 1f }, "edge lanes carry a ridge, the centre less")
    }

    @Test
    fun theWaterAndInkPacksAreFreeOfImpastoWhereRealPaintHasNone() {
        val water = (manifest("water")["assets"] as JsonArray).map { (it as JsonObject)["params"] as JsonObject }
        for (p in water) {
            val b = AzphaltBrush.fromParams(str(p, "name").orEmpty(), p)
            assertEquals(0f, b.impastoThicknessRate, "${b.name}: watercolour lays no paint height")
        }
    }

    @Test
    fun thePigmentPackMatchesThePlaceholderSetInCode() {
        val file = File(dir("pigments"), "assets/pigments.json")
        val set = assertNotNull(PigmentSet.fromJson(file.readText()), "pigments.json parses and validates")
        val code = PlaceholderPigments.set
        assertEquals(code.pigments.map { it.id }, set.pigments.map { it.id })
        assertTrue(set.pigments.all { it.isPlaceholder })
        for ((a, b) in set.pigments.zip(code.pigments)) {
            for (i in 0 until PigmentModel.BANDS) {
                assertTrue(abs(a.K[i] - b.K[i]) < 1e-4f && abs(a.S[i] - b.S[i]) < 1e-4f, "${a.id} band $i")
            }
        }
    }
}
