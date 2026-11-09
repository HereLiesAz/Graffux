package com.hereliesaz.graffitixr.common.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class AtomicFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun leftovers(dir: File) = dir.listFiles { f -> f.name.endsWith(".tmp") }!!.toList()

    @Test
    fun `writes a new file and replaces an existing one, leaving no temp file`() {
        val f = File(tmp.root, "a.bin")
        AtomicFiles.write(f, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), f.readBytes())
        AtomicFiles.write(f, byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), f.readBytes())
        assertTrue(leftovers(tmp.root).isEmpty())
    }

    @Test
    fun `creates missing parent directories`() {
        val f = File(tmp.root, "x/y/z.bin")
        AtomicFiles.write(f, byteArrayOf(5))
        assertArrayEquals(byteArrayOf(5), f.readBytes())
    }

    @Test
    fun `a failure while producing the bytes leaves the old file untouched and no temp file`() {
        val f = File(tmp.root, "keep.bin").also { it.writeBytes(byteArrayOf(7, 7)) }
        val failed = runCatching { AtomicFiles.write(f) { out -> out.write(1); throw IOException("disk full") } }
        assertTrue(failed.exceptionOrNull() is IOException)
        assertArrayEquals(byteArrayOf(7, 7), f.readBytes())
        assertTrue(leftovers(tmp.root).isEmpty())
    }

    @Test
    fun `a rename that cannot happen fails the write and never falls back to a partial copy`() {
        // the target is a non-empty directory: the move cannot replace it
        val dir = File(tmp.root, "dir").also { it.mkdirs() }
        File(dir, "child").writeBytes(byteArrayOf(1))
        val failed = runCatching { AtomicFiles.write(dir, byteArrayOf(1, 2)) }
        assertTrue(failed.exceptionOrNull() is IOException)
        assertTrue(File(dir, "child").exists())
        assertTrue(leftovers(tmp.root).isEmpty())
    }

    @Test
    fun `concurrent writers each end with one whole file`() {
        val f = File(tmp.root, "c.bin")
        val threads = (1..8).map { i -> Thread { AtomicFiles.write(f, ByteArray(1000) { i.toByte() }) } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val bytes = f.readBytes()
        assertEquals(1000, bytes.size)
        assertTrue("one writer's bytes throughout", bytes.all { it == bytes[0] })
        assertFalse(leftovers(tmp.root).isNotEmpty())
    }

    @Test
    fun `without createParent a missing directory fails and is not recreated`() {
        val gone = File(tmp.root, "deleted-project")
        val failed = runCatching { AtomicFiles.write(File(gone, "layer.png"), createParent = false) { it.write(1) } }
        assertTrue(failed.exceptionOrNull() is IOException)
        assertFalse("the deleted folder was not brought back", gone.exists())
    }

    @Test
    fun `createParent false still writes when the directory exists`() {
        val f = File(tmp.root, "ok.png")
        AtomicFiles.write(f, createParent = false) { it.write(7) }
        assertArrayEquals(byteArrayOf(7), f.readBytes())
    }

    @Test
    fun `deleteDurably removes a file, and a missing one counts as gone`() {
        val f = File(tmp.root, "side.gxmt").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(AtomicFiles.deleteDurably(f))
        assertFalse(f.exists())
        assertTrue(AtomicFiles.deleteDurably(f))
    }

    @Test
    fun `a file is emptied in place so a lost unlink still leaves nothing readable`() {
        val f = File(tmp.root, "side.gxmt").also { it.writeBytes(ByteArray(100) { 9 }) }
        assertTrue(AtomicFiles.emptyInPlace(f))
        assertTrue(f.exists())
        assertEquals("an empty sidecar is rejected by every reader", 0L, f.length())
    }

    @Test
    fun `a directory can be synced`() {
        assertTrue(AtomicFiles.syncDirectory(tmp.root))
    }
}
