package com.hereliesaz.graffitixr.feature.editor.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuFamilyTest {
    @Test
    fun `renderer strings map to families`() {
        assertEquals(GpuFamily.ADRENO, GpuFamilyDetector.family("Adreno (TM) 740"))
        assertEquals(GpuFamily.MALI, GpuFamilyDetector.family("Mali-G715"))
        assertEquals(GpuFamily.MALI, GpuFamilyDetector.family("Mali-G78 MP20"))
        assertEquals(GpuFamily.MALI, GpuFamilyDetector.family("Immortalis-G720"))
        assertEquals(GpuFamily.XCLIPSE, GpuFamilyDetector.family("Samsung Xclipse 940"))
        assertEquals(GpuFamily.POWERVR, GpuFamilyDetector.family("PowerVR B-Series BXM-8-256"))
        assertEquals(GpuFamily.POWERVR, GpuFamilyDetector.family("PowerVR D-Series DXT-48-1536"))
        assertEquals(GpuFamily.UNKNOWN, GpuFamilyDetector.family("llvmpipe (LLVM 19.1.7, 256 bits)"))
    }

    @Test
    fun `vendor id is the fallback when the renderer says nothing`() {
        assertEquals(GpuFamily.ADRENO, GpuFamilyDetector.family("", GpuInfo.VENDOR_QUALCOMM))
        assertEquals(GpuFamily.MALI, GpuFamilyDetector.family("", GpuInfo.VENDOR_ARM))
        assertEquals(GpuFamily.XCLIPSE, GpuFamilyDetector.family("", GpuInfo.VENDOR_SAMSUNG))
        assertEquals(GpuFamily.POWERVR, GpuFamilyDetector.family("", GpuInfo.VENDOR_IMAGINATION))
    }

    @Test
    fun `tensor is tagged but the GPU decides the family`() {
        val g3 = GpuFamilyDetector.detect(GpuInfo(renderer = "Mali-G715"), "Google", "Tensor G3")
        assertEquals(GpuFamily.MALI, g3.family)
        assertTrue(g3.tensor)
        val dxt = GpuInfo(renderer = "PowerVR D-Series DXT-48-1536")
        val g5 = GpuFamilyDetector.detect(dxt, "Google", "Tensor G5")
        assertEquals(GpuFamily.POWERVR, g5.family)
        assertTrue(g5.tensor)
        assertFalse(GpuFamilyDetector.detect(GpuInfo(renderer = "Mali-G715"), "Mediatek", "MT6985").tensor)
    }

    @Test
    fun `workgroup policy only departs from the default where documented`() {
        assertEquals(WorkgroupPolicy.SMALL_TILE, WorkgroupPolicy.stampTile(GpuFamily.MALI))
        GpuFamily.entries.filter { it != GpuFamily.MALI }.forEach {
            assertEquals(it.name, WorkgroupPolicy.DEFAULT_TILE, WorkgroupPolicy.stampTile(it))
        }
    }

    @Test
    fun `gpu info parses the native key-value lines`() {
        val info = GpuInfo.parse(
            "engine=vulkan\nbackend=Vulkan\nrenderer=Adreno (TM) 740\nvendor_id=20803\ndevice_id=1124\n" +
                "driver=0x80000000\ndriver_info=\napi=1.3.128\ntimestamps=1\ntimestamps_copy=1\n" +
                "shader_f16=1\nstamp_tile=16",
        )
        assertEquals("vulkan", info.engine)
        assertEquals("Adreno (TM) 740", info.renderer)
        assertEquals(0x5143, info.vendorId)
        assertEquals("1.3.128", info.apiVersion)
        assertTrue(info.timestamps)
        assertTrue(info.shaderF16)
        assertEquals(16, info.stampTile)
        assertEquals("Qualcomm", info.vendorLabel)
        assertFalse(GpuInfo.parse(null).isKnown)
        assertFalse(GpuInfo.parse("garbage").isKnown)
    }
}
