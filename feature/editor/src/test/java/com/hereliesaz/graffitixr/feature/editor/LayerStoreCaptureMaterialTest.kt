package com.hereliesaz.graffitixr.feature.editor

import java.lang.management.ManagementFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a layer save copies out of the stores (audit: the capture copied the wetness field and raw colour twice). */
class LayerStoreCaptureMaterialTest {
    private val w = 512
    private val h = 512

    private fun seeded(): LayerStore {
        val store = LayerStore()
        val wet = WetnessReplayState.empty(w, h)
        wet.field.addWetness(10, 10, 0.7f)
        store.putLiveWetness("a", wet)
        val raw = IntArray(w * h) { 0xFF000000.toInt() or it }
        store.putLiveImpastoMaterial("a", ImpastoMaterialReplayState.fromRaw(w, h, raw))
        store.putHeightBase("a", FloatArray(w * h) { it % 7 * 0.1f })
        return store
    }

    /** The capture as it was: a working copy of each stored state, then a second copy of its arrays. */
    private fun capturedTheOldWay(store: LayerStore): MaterialStateCodec.Snapshot {
        val wetness = store.wetnessStateCopyOrNull("a")!!
        val impasto = store.impastoMaterialStateCopyOrNull("a")!!
        return MaterialStateCodec.Snapshot(
            width = w, height = h,
            heightMap = store.heightBaseCopyOrNull("a"),
            wetness = wetness.snapshot(),
            tileSize = wetness.field.tileSize,
            rawColor = impasto.rawColor.copyOf(),
            mediumTiles = impasto.tileSnapshots(),
        )
    }

    private fun allocatedBy(block: () -> Unit): Long {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        block() // warm up
        val before = bean.getThreadAllocatedBytes(id)
        block()
        return bean.getThreadAllocatedBytes(id) - before
    }

    @Test
    fun `the capture holds the same material the old double copy did`() {
        val store = seeded()
        val old = capturedTheOldWay(store)
        val now = store.captureMaterial("a", w, h, liveHeight = null)!!
        assertArrayEquals(old.heightMap, now.heightMap, 0f)
        assertArrayEquals(old.wetness, now.wetness, 0f)
        assertArrayEquals(old.rawColor, now.rawColor)
        assertEquals(old.tileSize, now.tileSize)
        assertEquals(old.mediumTiles, now.mediumTiles)
    }

    @Test
    fun `the capture is a copy, so a later write to the store cannot reach it`() {
        val store = seeded()
        val taken = store.captureMaterial("a", w, h, liveHeight = null)!!
        val wetAt = taken.wetness!!.copyOf()
        val rawAt = taken.rawColor!!.copyOf()
        val stored = store.wetnessStateCopyOrNull("a")!!
        stored.field.addWetness(10, 10, 0.2f)
        store.putLiveWetness("a", stored)
        store.putLiveImpastoMaterial("a", ImpastoMaterialReplayState.fromRaw(w, h, IntArray(w * h)))
        assertArrayEquals(wetAt, taken.wetness, 0f)
        assertArrayEquals(rawAt, taken.rawColor)
        assertNotSame(taken.wetness, store.wetnessStateCopyOrNull("a")!!.snapshot())
    }

    @Test
    fun `the live height map is copied because strokes edit it in place`() {
        val store = seeded()
        val live = FloatArray(w * h) { 1f }
        val taken = store.captureMaterial("a", w, h, liveHeight = live)!!
        live.fill(9f)
        assertEquals(1f, taken.heightMap!![0], 0f)
    }

    @Test
    fun `a layer with no material captures nothing, and a size mismatch is ignored`() {
        assertNull(LayerStore().captureMaterial("x", w, h, liveHeight = null))
        val store = seeded()
        assertNull(store.captureMaterial("a", w / 2, h / 2, liveHeight = FloatArray(w * h)))
        assertNotNull(store.captureMaterial("a", w, h, liveHeight = null))
    }

    @Test
    fun `the capture allocates one copy of each array where the old path allocated two`() {
        val store = seeded()
        val oldBytes = allocatedBy { capturedTheOldWay(store) }
        val newBytes = allocatedBy { store.captureMaterial("a", w, h, liveHeight = null) }
        println("autosave material capture, $w x $h layer: old $oldBytes bytes allocated, new $newBytes bytes")
        assertTrue("old $oldBytes new $newBytes", newBytes * 10 < oldBytes * 7)
    }
}
