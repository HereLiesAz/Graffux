package com.hereliesaz.graffitixr.feature.editor.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapKeyValueStore : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String) {
        map[key] = value
    }
    override fun remove(key: String) {
        map.remove(key)
    }
}

class GpuTierStoreTest {
    private val kv = MapKeyValueStore()
    private val result = CalibrationResult(42.5, 1234.0, 7.25, "wgpu")
    private val adreno = GpuInfo(renderer = "Adreno (TM) 740", vendorId = 0x5143, driver = "0x80000000")

    @Test
    fun `saved tier loads back for the same GPU and driver`() {
        val store = GpuTierStore(kv, appVersion = 10)
        assertTrue(store.needsCalibration(adreno.identityKey))
        store.save(adreno.identityKey, GpuTierTable.STANDARD, result)
        val loaded = store.load(adreno.identityKey)!!
        assertEquals(GpuTierTable.STANDARD, loaded.tier)
        assertEquals(result, loaded.result)
        assertFalse(store.needsCalibration(adreno.identityKey))
        // A new store instance over the same storage (next launch) sees it too.
        assertEquals(GpuTierTable.STANDARD, GpuTierStore(kv, appVersion = 10).load(adreno.identityKey)?.tier)
    }

    @Test
    fun `a driver update misses`() {
        val store = GpuTierStore(kv, appVersion = 10)
        store.save(adreno.identityKey, GpuTierTable.HIGH, result)
        assertNull(store.load(adreno.copy(driver = "0x80000001").identityKey))
    }

    @Test
    fun `a different GPU misses`() {
        val store = GpuTierStore(kv, appVersion = 10)
        store.save(adreno.identityKey, GpuTierTable.HIGH, result)
        assertNull(store.load(GpuInfo(renderer = "Mali-G715", vendorId = 0x13B5).identityKey))
    }

    @Test
    fun `an app update misses`() {
        GpuTierStore(kv, appVersion = 10).save(adreno.identityKey, GpuTierTable.HIGH, result)
        assertNull(GpuTierStore(kv, appVersion = 11).load(adreno.identityKey))
    }

    @Test
    fun `clear forgets the entry`() {
        val store = GpuTierStore(kv, appVersion = 10)
        store.save(adreno.identityKey, GpuTierTable.HIGH, result)
        store.clear()
        assertNull(store.load(adreno.identityKey))
        assertTrue(kv.map.isEmpty())
    }

    @Test
    fun `corrupt entries read as absent, never as a tier`() {
        val store = GpuTierStore(kv, appVersion = 10)
        store.save(adreno.identityKey, GpuTierTable.HIGH, result)
        kv.map[GpuTierStore.KEY_RESULT] = "garbage"
        assertNull(store.load(adreno.identityKey))
        store.save(adreno.identityKey, GpuTierTable.HIGH, result)
        kv.map[GpuTierStore.KEY_TIER] = "ultra"
        assertNull(store.load(adreno.identityKey))
        store.save(adreno.identityKey, GpuTierTable.HIGH, result)
        kv.map[GpuTierStore.KEY_RESULT] = "NaN;1;1;vulkan"
        assertNull(store.load(adreno.identityKey))
    }

    @Test
    fun `result codec round trips`() {
        assertEquals(result, GpuTierStore.decode(GpuTierStore.encode(result)))
    }
}
