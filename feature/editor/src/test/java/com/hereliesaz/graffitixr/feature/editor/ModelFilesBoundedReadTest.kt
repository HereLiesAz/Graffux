package com.hereliesaz.graffitixr.feature.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** A model file is read with a cap, never "all of whatever is there". */
class ModelFilesBoundedReadTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a stream up to the cap is read whole`() {
        val bytes = ByteArray(10) { it.toByte() }
        assertArrayEquals(bytes, ModelFiles.readBounded(ByteArrayInputStream(bytes), max = 10))
    }

    @Test
    fun `a stream past the cap is refused after reading only a little more than the cap`() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 1.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int = len.also { served += it }
        }
        assertThrows(IOException::class.java) { ModelFiles.readBounded(endless, max = 100) }
        assertFalse("read $served bytes", served > 100 + 64 * 1024)
    }

    @Test
    fun `a file past the cap is refused by its length, a file within it is read`() {
        val small = tmp.newFile("small.obj").apply { writeText("v 0 0 0") }
        assertEquals("v 0 0 0", ModelFiles.readTextBounded(small, max = 100))
        val big = tmp.newFile("big.obj").apply { writeBytes(ByteArray(101)) }
        assertThrows(IOException::class.java) { ModelFiles.readTextBounded(big, max = 100) }
    }
}
