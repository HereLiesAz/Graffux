package com.hereliesaz.graffitixr.data.brush

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProcreateBrushReaderTest {
    // Independently generated with Python plistlib (FMT_BINARY, UID references), not our reader.
    private val fixture = Base64.getDecoder().decode("YnBsaXN0MDDUAQIDBAUGFhlZJGFyY2hpdmVyWCRvYmplY3RzVCR0b3BYJHZlcnNpb25fEA9OU0tleWVkQXJjaGl2ZXKjBwgVVSRudWxs1wkKCwwNDg8QERITExMUXxAVZHluYW1pY3NKaXR0ZXJPcGFjaXR5XxASZHluYW1pY3NKaXR0ZXJTaXplVG5hbWVbcGxvdFNwYWNpbmdac2hhcGVDb3VudF8QEHNoYXBlQ291bnRKaXR0ZXJec2hhcGVSb3VuZG5lc3MjP+AAAAAAAAAjP8AAAAAAAACAAiM/0AAAAAAAACM/6AAAAAAAAF1GaXh0dXJlIEJydXNo0RcYVHJvb3SAARIAAYagAAgAEQAbACQAKQAyAEQASABOAF0AdQCKAI8AmwCmALkAyADRANoA3ADlAO4A/AD/AQQBBgAAAAAAAAIBAAAAAAAAABoAAAAAAAAAAAAAAAAAAAEL")

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun readsKeyedArchiveAndMapsSupportedScalars() {
        val brush = requireNotNull(ProcreateBrushReader.read(zip("Brush.archive" to fixture)))
        assertEquals("Fixture Brush", brush.name)
        assertEquals(0.3618534f, brush.spacing, 0.000001f)
        assertEquals(0.75f, brush.tipRatio, 0f)
        assertEquals(0.125f, brush.sizeJitter, 0f)
        assertEquals(0.5f, brush.opacityJitter, 0f)
        assertEquals(4, brush.count)
        assertEquals(0.25f, brush.countJitter, 0f)
        assertFalse(brush.isotropicSpacing)
    }

    @Test fun rejectsMalformedOrMissingArchives() {
        assertNull(ProcreateBrushReader.read(byteArrayOf(1, 2, 3)))
        assertNull(ProcreateBrushReader.read(zip("Brush.archive" to fixture.copyOf(30))))
        assertNull(ProcreateBrushReader.read(zip("nested/Brush.archive" to fixture)))
    }

    @Test fun rejectsOversizedArchiveEvenWhenCompressed() {
        assertNull(ProcreateBrushReader.read(zip("Brush.archive" to ByteArray(2 * 1024 * 1024 + 1))))
    }

    @Test fun capsInflationOfIgnoredEntries() {
        assertNull(ProcreateBrushReader.read(zip("Brush.archive" to fixture, "Shape.png" to ByteArray(32 * 1024 * 1024))))
    }
}
