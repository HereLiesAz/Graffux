package com.hereliesaz.graffitixr.feature.editor.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverWorkaroundsTest {
    private val adreno =
        GpuInfo(renderer = "Adreno (TM) 740", vendorId = GpuInfo.VENDOR_QUALCOMM, driver = "0x80000200")

    private val entries = listOf(
        DriverWorkaround(
            id = "adreno-old-timestamps",
            reason = "test entry",
            vendorId = GpuInfo.VENDOR_QUALCOMM,
            driverVersions = 0x80000000L..0x800001FFL,
            effect = WorkaroundEffect(disableTimestamps = true),
        ),
        DriverWorkaround(
            id = "adreno-7xx-tile",
            reason = "test entry",
            vendorId = GpuInfo.VENDOR_QUALCOMM,
            rendererContains = "adreno (tm) 7",
            effect = WorkaroundEffect(forceStampTile = 8, disableFp16 = true),
        ),
    )

    @Test
    fun `the shipped table starts empty`() {
        assertTrue(DriverWorkarounds.table.isEmpty())
        assertEquals(WorkaroundEffect(), DriverWorkarounds.effectFor(adreno))
    }

    @Test
    fun `entries match on vendor, renderer and driver range`() {
        assertEquals(listOf("adreno-7xx-tile"), DriverWorkarounds.matching(adreno, entries).map { it.id })
        val older = adreno.copy(driver = "0x80000100")
        assertEquals(
            listOf("adreno-old-timestamps", "adreno-7xx-tile"),
            DriverWorkarounds.matching(older, entries).map { it.id },
        )
        val mali = GpuInfo(renderer = "Mali-G715", vendorId = GpuInfo.VENDOR_ARM, driver = "0x80000100")
        assertTrue(DriverWorkarounds.matching(mali, entries).isEmpty())
    }

    @Test
    fun `an unreadable driver version never matches a ranged entry`() {
        val gles = adreno.copy(driver = "OpenGL ES 3.2 V@0615.0")
        assertNull(DriverWorkarounds.driverVersionCode(gles))
        assertFalse(DriverWorkarounds.matches(entries[0], gles))
    }

    @Test
    fun `effects fold and reach the tuning`() {
        val older = adreno.copy(driver = "0x80000100", shaderF16 = true)
        val effect = DriverWorkarounds.effectFor(older, entries)
        assertTrue(effect.disableTimestamps)
        assertEquals(8, effect.forceStampTile)
        assertTrue(effect.disableFp16)

        val detected = GpuFamilyDetector.detect(older)
        val tuning = GpuTuning.resolve(older, detected, GpuTierTable.HIGH, null, entries)
        assertEquals(8, tuning.stampTile)
        assertFalse(tuning.timestamps)
        assertFalse(tuning.fp16)
        assertEquals(listOf("adreno-old-timestamps", "adreno-7xx-tile"), tuning.workarounds)
    }

    @Test
    fun `fp16 needs tier, device support and no workaround`() {
        val info = adreno.copy(shaderF16 = true)
        val detected = GpuFamilyDetector.detect(info)
        val fp16Tier = GpuTierTable.HIGH.copy(fp16 = true)
        assertTrue(GpuTuning.resolve(info, detected, fp16Tier, null, emptyList()).fp16)
        assertFalse(GpuTuning.resolve(info, detected, GpuTierTable.HIGH, null, emptyList()).fp16)
        val noF16 = info.copy(shaderF16 = false)
        assertFalse(GpuTuning.resolve(noF16, detected, fp16Tier, null, emptyList()).fp16)
    }
}
