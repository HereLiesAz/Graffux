package com.hereliesaz.graffitixr.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerStoreImpastoV2Test {
    @Test
    fun structureBaseStartsRecoveredAndIsDefensivelyCopiedByCaller() {
        val store = LayerStore()
        val base = store.structureBase("a", 8)
        assertTrue(base.all { it == 1f })
        base[2] = 0.2f
        assertSame(base, store.structureBase("a", 8))

        val copy = store.structureBase("a", 8).copyOf()
        copy[2] = 0f
        assertEquals(0.2f, store.structureBase("a", 8)[2], 0f)
    }

    @Test
    fun dimensionChangeReplacesStructureAndWorkspace() {
        val store = LayerStore()
        val firstStructure = store.structureBase("a", 8)
        val secondStructure = store.structureBase("a", 12)
        assertNotSame(firstStructure, secondStructure)
        assertTrue(secondStructure.all { it == 1f })

        val firstWorkspace = store.impastoWorkspace("a", 4, 2)
        val sameWorkspace = store.impastoWorkspace("a", 4, 2)
        val resizedWorkspace = store.impastoWorkspace("a", 6, 2)
        assertSame(firstWorkspace, sameWorkspace)
        assertNotSame(firstWorkspace, resizedWorkspace)
    }

    @Test
    fun removeAndClearDropPhase5Caches() {
        val store = LayerStore()
        store.structureBase("a", 4)[0] = 0f
        store.impastoWorkspace("a", 2, 2)
        store.remove("a")
        assertTrue(store.structureBase("a", 4).all { it == 1f })

        store.structureBase("b", 4)[0] = 0f
        store.impastoWorkspace("b", 2, 2)
        store.clear()
        assertTrue(store.structureBase("b", 4).all { it == 1f })
    }
}
