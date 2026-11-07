package com.hereliesaz.graffitixr.nativebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuInitReportTest {
    private val noAdapter = "requested backends=Backends(VULKAN | GL) layer=256x256\n" +
        "enumerate_adapters returned no adapters\n" +
        "wgpu log:\n  DEBUG wgpu_hal::vulkan::adapter: Adapter is not Vulkan compliant, hiding adapter: Adreno\n"

    @Test fun failureMessageCarriesTheRealReason() {
        val m = GpuInitReport.failureMessage(noAdapter)
        assertTrue(m.startsWith("init() failed"))
        assertTrue(m.contains("enumerate_adapters returned no adapters"))
        assertTrue(m.contains("not Vulkan compliant, hiding adapter: Adreno"))
        assertTrue("must not claim a CPU fallback", !m.contains("CPU"))
    }

    @Test fun emptyReportSaysSoInsteadOfInventingACause() {
        assertTrue(GpuInitReport.failureMessage("  \n").contains(GpuInitReport.UNAVAILABLE))
        assertEquals(GpuInitReport.UNAVAILABLE, GpuInitReport.compact(""))
    }

    @Test fun compactJoinsLinesAndDropsBlanks() {
        assertEquals("a | b | wgpu log: | c", GpuInitReport.compact("a\n\nb\nwgpu log:\n  c\n"))
    }

    @Test fun compactIsBounded() {
        val c = GpuInitReport.compact("x".repeat(5000))
        assertEquals(GpuInitReport.COMPACT_LIMIT + 1, c.length)
        assertTrue(c.endsWith("…"))
    }

    @Test fun libraryLoadFailureIsShownVerbatim() {
        val m = GpuInitReport.failureMessage("libgraffux_wgpu.so did not load: dlopen failed: library not found")
        assertTrue(m.contains("dlopen failed: library not found"))
    }
}
