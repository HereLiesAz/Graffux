package com.hereliesaz.graffitixr.feature.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class LayerStoreWetnessTest {
    @Test
    fun `wetness bases and live state are defensive copies`() {
        val store = LayerStore()
        val state = WetnessReplayState.empty(4, 4, tileSize = 2)
        state.field.addWetness(1, 1, 0.6f)
        state.markThrough(100L)

        store.putWetnessBase("a", state)
        val working = store.wetnessBaseCopy("a", 4, 4)
        working.field.addWetness(1, 1, 0.4f)
        working.markThrough(200L)

        val baseAgain = store.wetnessBaseCopy("a", 4, 4)
        assertEquals(0.6f, baseAgain.field.wetnessAt(1, 1), 0f)
        assertEquals(100L, baseAgain.lastUptimeMillis)

        store.putLiveWetness("a", working)
        val live = store.liveWetnessCopy("a", 4, 4)
        assertEquals(1f, live.field.wetnessAt(1, 1), 0f)
        assertEquals(200L, live.lastUptimeMillis)
    }

    @Test
    fun `initStrokes clears derived live wetness but preserves baked wetness`() {
        val store = LayerStore()
        val base = WetnessReplayState.empty(4, 4)
        base.field.addWetness(0, 0, 0.4f)
        store.putWetnessBase("a", base)

        val live = base.copyForWork()
        live.field.addWetness(0, 0, 0.4f)
        store.putLiveWetness("a", live)
        assertTrue(store.hasWetnessState("a"))

        store.initStrokes("a")

        assertTrue(store.hasWetnessBase("a"))
        assertEquals(0.4f, store.liveWetnessCopy("a", 4, 4).field.wetnessAt(0, 0), 0f)
    }

    @Test
    fun `clearLiveWetness removes transient state when no base exists`() {
        val store = LayerStore()
        store.putLiveWetness("a", WetnessReplayState.empty(4, 4))
        assertTrue(store.hasWetnessState("a"))

        store.clearLiveWetness("a")

        assertFalse(store.hasWetnessState("a"))
    }
    @Test
    fun `persistence accessors do not allocate dry state and return defensive copies`() {
        val store = LayerStore()
        assertEquals(null, store.heightBaseCopyOrNull("dry"))
        assertEquals(null, store.wetnessStateCopyOrNull("dry"))

        val height = store.heightBase("a", 4)
        height[0] = 0.7f
        val heightCopy = requireNotNull(store.heightBaseCopyOrNull("a"))
        heightCopy[0] = 0f
        assertEquals(0.7f, requireNotNull(store.heightBaseCopyOrNull("a"))[0], 0f)

        val wet = WetnessReplayState.empty(2, 2)
        wet.field.addWetness(1, 1, 0.6f)
        store.putLiveWetness("a", wet)
        val wetCopy = requireNotNull(store.wetnessStateCopyOrNull("a"))
        wetCopy.field.addWetness(1, 1, 0.4f)
        assertEquals(
            0.6f,
            requireNotNull(store.wetnessStateCopyOrNull("a")).field.wetnessAt(1, 1),
            0f,
        )
    }

}
