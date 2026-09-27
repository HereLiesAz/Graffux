package com.hereliesaz.graffitixr.feature.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generation bookkeeping behind the wgpu engine's GPU-resident layers. */
class GpuLayerResidencyTest {
    private val invalidated = ArrayList<Long?>()
    private val residency = GpuLayerResidency { invalidated += it }

    @Test
    fun `same bitmap object keeps its generation, any other object gets a new one`() {
        val a = Any()
        val g = residency.generationFor("L", a)
        assertEquals(g, residency.generationFor("L", a))
        assertTrue(residency.isCurrent("L", g))
        val b = Any() // e.g. undo published a rebuilt bitmap without anyone calling invalidate
        val g2 = residency.generationFor("L", b)
        assertNotEquals(g, g2)
        assertFalse(residency.isCurrent("L", g))
    }

    @Test
    fun `generations are unique across layers and instances`() {
        val other = GpuLayerResidency()
        val a = Any()
        val g1 = residency.generationFor("L", a)
        val g2 = residency.generationFor("M", a)
        val g3 = other.generationFor("L", a)
        assertEquals(3, setOf(g1, g2, g3).size)
    }

    @Test
    fun `invalidate marks the layer stale even when the bitmap object is unchanged`() {
        val a = Any()
        val g = residency.generationFor("L", a)
        residency.invalidate("L")
        assertFalse(residency.isCurrent("L", g))
        assertNotEquals(g, residency.generationFor("L", a))
        assertEquals(listOf<Long?>(GpuLayerResidency.layerKey("L")), invalidated)
    }

    @Test
    fun `invalidateAll marks every layer stale and notifies once`() {
        val gl = residency.generationFor("L", Any())
        val gm = residency.generationFor("M", Any())
        residency.invalidateAll()
        assertFalse(residency.isCurrent("L", gl))
        assertFalse(residency.isCurrent("M", gm))
        assertEquals(listOf<Long?>(null), invalidated)
    }

    @Test
    fun `a commit is adopted only when nothing changed the layer since the stroke began`() {
        val base = Any()
        val committed = Any()
        val g = residency.generationFor("L", base)
        val adopted = residency.adoptCommit("L", g, base, committed)!!
        assertNotEquals(g, adopted)
        assertEquals(adopted, residency.generationFor("L", committed))

        // An invalidation mid-stroke (undo, co-op op...) refuses the commit.
        val g2 = residency.generationFor("L", committed)
        residency.invalidate("L")
        assertNull(residency.adoptCommit("L", g2, committed, Any()))

        // So does a commit that composited onto a different base than the stroke bound.
        val b3 = Any()
        val g3 = residency.generationFor("L", b3)
        assertNull(residency.adoptCommit("L", g3, Any(), Any()))
        // And a stale generation.
        assertNull(residency.adoptCommit("L", g3 - 1, b3, Any()))
    }

    @Test
    fun `layer keys are stable and distinct`() {
        assertEquals(GpuLayerResidency.layerKey("layer-1"), GpuLayerResidency.layerKey("layer-1"))
        assertNotEquals(GpuLayerResidency.layerKey("layer-1"), GpuLayerResidency.layerKey("layer-2"))
    }

    @Test
    fun `changedRect bounds exactly the differing pixels`() {
        val w = 7
        val h = 5
        val before = IntArray(w * h) { it }
        assertArrayEquals(intArrayOf(0, 0, 0, 0), GpuLayerResidency.changedRect(before, before.copyOf(), w, h))
        val after = before.copyOf()
        after[1 * w + 5] = -1
        after[3 * w + 2] = -1
        assertArrayEquals(intArrayOf(2, 1, 4, 3), GpuLayerResidency.changedRect(before, after, w, h))
    }
}
