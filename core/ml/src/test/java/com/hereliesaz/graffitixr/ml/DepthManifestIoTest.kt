package com.hereliesaz.graffitixr.ml

import com.hereliesaz.graffitixr.common.azphalt.AssetContribution
import com.hereliesaz.graffitixr.common.azphalt.parseManifest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses the three real store listings (copied from the azphalt repo) and checks what the runtime derives. */
class DepthManifestIoTest {
    private fun asset(name: String): AssetContribution {
        val json = checkNotNull(javaClass.getResourceAsStream("/manifests/$name.json")).bufferedReader().readText()
        return parseManifest(json).assets.single()
    }

    @Test fun `MiDaS listing is the fixed 4-D unit-scaled contract`() {
        val s = DepthModelSpecs.fromAsset(asset("midas"))
        assertEquals("input_image", s.inputName)
        assertEquals(4, s.rank)
        assertEquals(SizePolicy.Fixed(256, 256), s.size)
        assertEquals("output_depth", s.outputName)
        assertTrue(s.higherIsNearer)
        assertEquals(1f / 255f, s.normalization.scale[0], 1e-9f)
        assertEquals(0f, s.normalization.bias[0], 0f)
        assertEquals(DepthModelSpec().normalization, s.normalization)
    }

    @Test fun `Depth Anything V2 listing is dynamic 4-D multiple of 14 with ImageNet statistics`() {
        val s = DepthModelSpecs.fromAsset(asset("depth-anything-v2-small"))
        assertEquals("pixel_values", s.inputName)
        assertEquals(4, s.rank)
        assertEquals(SizePolicy.Dynamic(14, 518, 56), s.size)
        assertEquals("predicted_depth", s.outputName)
        assertTrue(s.higherIsNearer)
        assertEquals(imagenetScale(0.229f), s.normalization.scale[0], 1e-9f)
        assertEquals(-0.485f / 0.229f, s.normalization.bias[0], 1e-6f)
        assertEquals(-0.406f / 0.225f, s.normalization.bias[2], 1e-6f)
    }

    @Test fun `Depth Anything V3 listing is 5-D views, depth output, farther-is-larger`() {
        val a = asset("depth-anything-v3-small")
        val s = DepthModelSpecs.fromAsset(a)
        assertEquals(5, s.rank)
        assertEquals(SizePolicy.Dynamic(14, 518, 56), s.size)
        assertEquals("predicted_depth", s.outputName)
        assertFalse(s.higherIsNearer)
        assertEquals(Inversion.RECIPROCAL, s.inversion)
        // The bundle: two members with their own checksums and sizes.
        assertEquals(listOf("model.onnx", "model.onnx_data"), a.files.orEmpty().map { it.name })
        assertTrue(a.files.orEmpty().all { it.checksum!!.startsWith("sha256-") && it.byteSize!! > 0 })
    }

    @Test fun `the depth output is found by kind even when confidence is listed first`() {
        val outs = asset("depth-anything-v3-small").io!!.outputs!!
        assertEquals("predicted_depth", DepthModelSpecs.pickDepthOutput(outs.reversed())!!.name)
        assertEquals("confidence", outs[1].name)
    }

    @Test fun `an asset with no io is the MiDaS default`() {
        val a = asset("midas").copy(io = null)
        assertEquals(DepthModelSpec(), DepthModelSpecs.fromAsset(a))
    }

    @Test fun `io the runtime cannot drive is refused with UnsupportedModel`() {
        val base = asset("depth-anything-v2-small")
        fun withInput(json: String): AssetContribution {
            val m = """{"azphalt":"0.1","id":"a.b.c","name":"n","version":"1.0.0","kind":"asset","license":"MIT","compat":">=0.1","assets":[
              {"type":"onnx","path":"","role":"depth","io":{"inputs":[$json]}}]}"""
            return parseManifest(m).assets.single()
        }
        for (bad in listOf(
            """{"name":"x","shape":[-1,3,-1]}""", // 3-D
            """{"name":"x","shape":[2,3,-1,-1]}""", // fixed batch of 2
            """{"name":"x","shape":[-1,1,-1,-1]}""", // grey
            """{"name":"x","shape":[-1,3,224,-1]}""", // half dynamic
            """{"name":"x","shape":[-1,3,-1,-1],"dtype":"uint8"}""",
            """{"name":"x","shape":[-1,3,-1,-1],"layout":"CHWN"}""",
            """{"name":"x","shape":[-1,3,-1,-1],"normalization":{"mean":[1,2],"std":[1,1]}}""",
        )) {
            assertThrows(bad, DepthEstimationException.UnsupportedModel::class.java) {
                DepthModelSpecs.fromAsset(withInput(bad))
            }
        }
        assertThrows(DepthEstimationException.UnsupportedModel::class.java) {
            DepthModelSpecs.fromAsset(base.copy(io = base.io!!.copy(inputs = base.io!!.inputs!! + base.io!!.inputs!!)))
        }
    }

    @Test fun `params can lower the long side cap but never raise it`() {
        val a = asset("depth-anything-v2-small")
        fun capped(n: Int) = a.copy(
            params = JsonObject(mapOf("maxLongSide" to JsonPrimitive(n))),
        )
        assertEquals(336, (DepthModelSpecs.fromAsset(capped(336)).size as SizePolicy.Dynamic).maxLongSide)
        assertEquals(518, (DepthModelSpecs.fromAsset(capped(4000)).size as SizePolicy.Dynamic).maxLongSide)
    }

    @Test fun `explicit scale and bias and absent normalization`() {
        fun spec(norm: String): DepthModelSpec {
            val n = if (norm.isEmpty()) "" else ""","normalization":$norm"""
            val m = """{"azphalt":"0.1","id":"a.b.c","name":"n","version":"1.0.0","kind":"asset","license":"MIT","compat":">=0.1","assets":[
              {"type":"onnx","path":"","role":"depth","io":{"inputs":[{"name":"x","shape":[1,3,64,64]$n}]}}]}"""
            return DepthModelSpecs.fromAsset(parseManifest(m).assets.single())
        }
        assertEquals(Normalization.RAW, spec("").normalization)
        val s = spec("""{"scale":0.5,"bias":[1,2,3]}""").normalization
        assertEquals(listOf(0.5f, 0.5f, 0.5f), s.scale)
        assertEquals(listOf(1f, 2f, 3f), s.bias)
    }

    private fun imagenetScale(std: Float) = 1f / (255f * std)
}
