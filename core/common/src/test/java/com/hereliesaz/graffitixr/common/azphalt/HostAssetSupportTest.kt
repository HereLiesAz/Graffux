package com.hereliesaz.graffitixr.common.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAssetSupportTest {
    private fun asset(type: AssetType, role: String? = null) = AssetContribution(type = type, path = "", role = role)

    @Test fun `onnx with role depth is supported`() {
        assertTrue(HostAssetSupport.isSupported(asset(AssetType.ONNX, "depth")))
        assertTrue(HostAssetSupport.isDepthModel(asset(AssetType.ONNX, "Depth")))
    }

    @Test fun `onnx with another or no role is not supported`() {
        assertFalse(HostAssetSupport.isSupported(asset(AssetType.ONNX, "segmentation")))
        assertFalse(HostAssetSupport.isSupported(asset(AssetType.ONNX)))
    }

    @Test fun `other model families stay unsupported even with the depth role`() {
        for (t in listOf(AssetType.TFLITE, AssetType.LITERT, AssetType.MODEL, AssetType.TASK)) {
            assertFalse("$t", HostAssetSupport.isSupported(asset(t, "depth")))
        }
    }

    @Test fun `surfaces are supported`() {
        assertTrue(HostAssetSupport.isSupported(asset(AssetType.SURFACE)))
        val m = parseManifest(
            """
            {"azphalt":"0.1","id":"a.b","name":"n","version":"1.0.0","kind":"asset","license":"CC0-1.0","compat":">=0.1",
             "assets":[{"type":"surface","path":"assets/a.json"}]}
            """.trimIndent(),
        )
        assertEquals(AssetType.SURFACE, m.assets.single().type)
    }

    @Test fun `brushes and luts remain supported, others not`() {
        assertTrue(HostAssetSupport.isSupported(asset(AssetType.BRUSH)))
        assertTrue(HostAssetSupport.isSupported(asset(AssetType.LUT)))
        assertFalse(HostAssetSupport.isSupported(asset(AssetType.SHADER)))
        assertFalse(HostAssetSupport.isSupported(asset(AssetType.UNKNOWN)))
    }

    @Test fun `the published MiDaS listing parses to a supported depth model`() {
        val m = parseManifest(
            """
            {"azphalt":"0.1","id":"com.hereliesaz.azphalt.midas","name":"MiDaS","version":"1.0.0","kind":"asset",
             "license":"MIT","compat":">=0.1",
             "assets":[{"type":"onnx","path":"","role":"depth",
               "remoteUrl":"https://github.com/HereLiesAz/azphalt-midas/releases/latest/download/midas.onnx"}]}
            """.trimIndent(),
        )
        assertTrue(HostAssetSupport.isSupported(m.assets.single()))
        assertEquals(AssetType.ONNX, m.assets.single().type)
    }

    @Test fun `depth models are ordered V2, V3, MiDaS, MoGe-2, then unknown ids alphabetically`() {
        val p = "com.hereliesaz.azphalt."
        val ids = listOf("z.other", p + "midas", "a.other", p + "depth-anything-v3-small")
            .plus(p + "depth-anything-v2-small").plus(p + "models.moge-2-vits-metric-depth")
        assertEquals(
            listOf(
                "com.hereliesaz.azphalt.depth-anything-v2-small", "com.hereliesaz.azphalt.depth-anything-v3-small",
                "com.hereliesaz.azphalt.midas", "com.hereliesaz.azphalt.models.moge-2-vits-metric-depth",
                "a.other", "z.other",
            ),
            HostAssetSupport.orderedByDepthPreference(ids) { it },
        )
    }

    @Test fun `a code-dependent depth model is not a host depth model`() {
        val dependent = AssetContribution(type = AssetType.ONNX, path = "", role = "depth", standalone = false)
        assertFalse(HostAssetSupport.isDepthModel(dependent))
        assertFalse(HostAssetSupport.isSupported(dependent))
    }
}
