package com.hereliesaz.graffitixr.feature.editor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bitmap / sidecar commit order: whichever step a crash interrupts, the pair on disk is never mismatched. */
class LayerCommitTest {
    /** A model of the two files: what version of the stroke each holds (null: absent). */
    private class Disk {
        var bitmap: Int? = 1
        var sidecar: Int? = 1
        val mismatched get() = bitmap != null && sidecar != null && bitmap != sidecar
    }

    private fun commit(disk: Disk, newVersion: Int, crashAt: Int?): LayerCommit {
        fun step(n: Int, body: () -> Unit): Boolean {
            check(crashAt != n) { "died at step $n" }
            body()
            return true
        }
        return LayerCommit(
            deleteSidecar = { step(1) { disk.sidecar = null } },
            writeBitmap = { step(2) { disk.bitmap = newVersion } },
            writeSidecar = { step(3) { disk.sidecar = newVersion } },
        )
    }

    @Test
    fun `a clean commit leaves the new pair`() = runBlocking {
        val d = Disk()
        assertTrue(commit(d, 2, null).run())
        assertEquals(2, d.bitmap)
        assertEquals(2, d.sidecar)
    }

    @Test
    fun `dying at any step never leaves a new bitmap with the old sidecar or the reverse`() = runBlocking {
        for (crashAt in 1..3) {
            val d = Disk()
            runCatching { commit(d, 2, crashAt).run() }
            assertFalse("crash at step $crashAt left bitmap=${d.bitmap} sidecar=${d.sidecar}", d.mismatched)
        }
    }

    @Test
    fun `the old sidecar is gone before the bitmap changes`() = runBlocking {
        val order = ArrayList<String>()
        LayerCommit({ order += "delete"; true }, { order += "bitmap"; true }, { order += "sidecar"; true }).run()
        assertEquals(listOf("delete", "bitmap", "sidecar"), order)
    }

    @Test
    fun `a layer that ends with no material still loses the old sidecar before its bitmap changes`() = runBlocking {
        val d = Disk()
        runCatching {
            LayerCommit(
                deleteSidecar = { d.sidecar = null; true },
                writeBitmap = { error("died") },
                writeSidecar = { true },
            ).run()
        }
        assertEquals(null, d.sidecar)
    }

    @Test
    fun `a failed step stops the rest`() = runBlocking {
        val order = ArrayList<String>()
        assertFalse(LayerCommit({ order += "d"; false }, { order += "b"; true }, { order += "s"; true }).run())
        assertEquals(listOf("d"), order)
        order.clear()
        assertFalse(LayerCommit({ true }, { order += "b"; false }, { order += "s"; true }).run())
        assertEquals(listOf("b"), order)
    }
}
