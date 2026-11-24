package com.hereliesaz.graffitixr.data.brush

import org.junit.Assert.*
import com.hereliesaz.graffitixr.common.azphalt.BrushParameter
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

    @Test fun returnsEmbeddedShapeAndGrainWithoutExtractingEntries() {
        val shape = byteArrayOf(11, 22, 33)
        val grain = byteArrayOf(44, 55)
        val imported = requireNotNull(ProcreateBrushReader.readWithAssets(zip(
            "Brush.archive" to fixture, "Shape.png" to shape, "Grain.png" to grain,
            "../../unrelated.png" to byteArrayOf(99),
        )))
        assertArrayEquals(shape, imported.shape?.bytes)
        assertArrayEquals(grain, imported.grain?.bytes)
        assertFalse(requireNotNull(imported.shape).inverted)
        assertFalse(imported.missingTexture)
    }

    @Test fun resolvesNamedTextureAndReportsMissingStockReference() {
        // Generated independently with Python plistlib, with a named shape and missing grain.
        val named = Base64.getDecoder().decode("YnBsaXN0MDDUAQIDBAUGHSBZJGFyY2hpdmVyWCRvYmplY3RzVCR0b3BYJHZlcnNpb25fEA9OU0tleWVkQXJjaGl2ZXKjBwgcVSRudWxs2wkKCwwNDg8QERITFBUWFxgZGRkaGxpfEBBidW5kbGVkR3JhaW5QYXRoXxAQYnVuZGxlZFNoYXBlUGF0aF8QFWR5bmFtaWNzSml0dGVyT3BhY2l0eV8QEmR5bmFtaWNzSml0dGVyU2l6ZVRuYW1lW3Bsb3RTcGFjaW5nWnNoYXBlQ291bnRfEBBzaGFwZUNvdW50Sml0dGVyXXNoYXBlSW52ZXJ0ZWRec2hhcGVSb3VuZG5lc3NfEA90ZXh0dXJlSW52ZXJ0ZWRZc3RvY2sucG5nXmN1c3RvbS9UaXAucG5nIz/gAAAAAAAAIz/AAAAAAAAAgAIjP9AAAAAAAAAJIz/oAAAAAAAAXUZpeHR1cmUgQnJ1c2jRHh9Ucm9vdIABEgABhqAACAARABsAJAApADIARABIAE4AZQB4AIsAowC4AL0AyQDUAOcA9QEEARYBIAEvATgBQQFDAUwBTQFWAWQBZwFsAW4AAAAAAAACAQAAAAAAAAAhAAAAAAAAAAAAAAAAAAABcw==")
        val tip = byteArrayOf(9, 8, 7)
        val imported = requireNotNull(ProcreateBrushReader.readWithAssets(zip("Brush.archive" to named, "Tip.png" to tip)))
        assertArrayEquals(tip, imported.shape?.bytes)
        assertTrue(requireNotNull(imported.shape).inverted)
        assertNull(imported.grain)
        assertTrue(imported.missingTexture)
    }

    @Test fun readsXmlSetInManifestOrderAndReportsBadMember() {
        val manifest = """<?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict><key>name</key><string>Ink &amp; Paint</string>
            <key>brushes</key><array><string>second</string><string>bad</string><string>first</string></array></dict></plist>""".trimIndent().toByteArray()
        val set = requireNotNull(ProcreateBrushReader.readSet(zip(
            "brushset.plist" to manifest, "first/Brush.archive" to fixture,
            "first/Shape.png" to byteArrayOf(1), "second/Brush.archive" to fixture,
            "second/Shape.png" to byteArrayOf(2), "bad/Brush.archive" to byteArrayOf(0),
            "unlisted/Brush.archive" to fixture,
        )))
        assertEquals("Ink & Paint", set.name)
        assertEquals(2, set.brushes.size)
        assertEquals(1, set.skipped)
        assertArrayEquals(byteArrayOf(2), set.brushes[0].shape?.bytes)
        assertArrayEquals(byteArrayOf(1), set.brushes[1].shape?.bytes)
    }

    @Test fun rejectsUnsafeOrDuplicateManifestFolders() {
        for (ids in listOf("<string>../outside</string>", "<string>a</string><string>a</string>")) {
            val manifest = "<plist><dict><key>brushes</key><array>$ids</array></dict></plist>".toByteArray()
            assertNull(ProcreateBrushReader.readSet(zip("brushset.plist" to manifest)))
        }
    }

    @Test fun rejectsExternalEntityDeclarations() {
        val manifest = """<!DOCTYPE plist [<!ENTITY ext SYSTEM "file:///etc/passwd">]>
            <plist><dict><key>brushes</key><array><string>&ext;</string></array></dict></plist>""".toByteArray()
        assertNull(ProcreateBrushReader.readSet(zip("brushset.plist" to manifest)))
    }

    @Test fun readsBinarySetManifest() {
        // Independently generated with Python plistlib, not the reader.
        val manifest = Base64.getDecoder().decode("YnBsaXN0MDDSAQIDBldicnVzaGVzVG5hbWWiBAVVZmlyc3RWc2Vjb25kWkJpbmFyeSBzZXQIDRUaHSMqAAAAAAAAAQEAAAAAAAAABwAAAAAAAAAAAAAAAAAAADU=")
        val set = requireNotNull(ProcreateBrushReader.readSet(zip(
            "brushset.plist" to manifest, "second/Brush.archive" to fixture,
            "first/Brush.archive" to fixture, "first/Shape.png" to byteArrayOf(7),
        )))
        assertEquals("Binary set", set.name)
        assertEquals(2, set.brushes.size)
        assertEquals(0, set.skipped)
        assertArrayEquals(byteArrayOf(7), set.brushes[0].shape?.bytes)
        assertNull(set.brushes[1].shape)
    }

    @Test fun rejectsSetInflationAcrossIgnoredEntries() {
        val manifest = "<plist><dict><key>brushes</key><array><string>a</string></array></dict></plist>".toByteArray()
        assertNull(ProcreateBrushReader.readSet(zip("brushset.plist" to manifest,
            "a/Brush.archive" to fixture, "ignored.bin" to ByteArray(64 * 1024 * 1024))))
    }

    @Test fun libraryUsesBothMetadataOrdersAndReportsInvalidMembers() {
        val meta = ".procreate_meta.0123456789abcdef0123456789abcdef"
        val first = zip("Brush.archive" to fixture, "Shape.png" to byteArrayOf(1))
        val second = zip("Brush.archive" to fixture, "Shape.png" to byteArrayOf(2))
        val library = requireNotNull(ProcreateBrushReader.readLibrary(zip(
            meta to "Alpha/1/0\nZulu/-1/0\n\u0000".toByteArray(),
            "Alpha/a.brush" to first,
            "Zulu/a.brush" to first,
            "Zulu/b.brush" to second,
            "Zulu/bad.brush" to byteArrayOf(1),
            "Zulu/$meta" to "a.brush/1/0\nb.brush/-1/0\nbad.brush/0/0\n\u0000".toByteArray(),
            "Empty/.empty" to byteArrayOf(),
        )))
        assertEquals(listOf("Zulu", "Alpha", "Empty"), library.sets.map { it.name })
        assertArrayEquals(byteArrayOf(2), library.sets[0].brushes[0].shape?.bytes)
        assertArrayEquals(byteArrayOf(1), library.sets[0].brushes[1].shape?.bytes)
        assertEquals(1, library.sets[0].skipped)
        assertTrue(library.sets[2].brushes.isEmpty())
    }

    @Test fun rejectsAggregateNestedInflationEvenWhenEachBrushIsWithinItsLimit() {
        val meta = ".procreate_meta.0123456789abcdef0123456789abcdef"
        val nested = zip("Brush.archive" to fixture, "ignored" to ByteArray(23 * 1024 * 1024))
        assertNull(ProcreateBrushReader.readLibrary(zip(
            meta to byteArrayOf(0), "Set/a.brush" to nested,
            "Set/b.brush" to nested, "Set/c.brush" to nested,
        )))
    }

    @Test fun rejectsLibraryTraversalFolder() {
        val meta = ".procreate_meta.0123456789abcdef0123456789abcdef"
        assertNull(ProcreateBrushReader.readLibrary(zip(
            meta to byteArrayOf(0), "../a.brush" to zip("Brush.archive" to fixture),
        )))
    }

    @Test fun exportsSupportedSettingsAndEmbeddedCoverage() {
        val brush = com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush(
            name = "Ink 漢字", spacing = 0.5f, isotropicSpacing = false,
            tipRatio = 0.75f, sizeJitter = 0.25f, count = 7, grainStrength = 0.5f,
        )
        val encoded = ProcreateBrushWriter.write(brush, byteArrayOf(1, 2), byteArrayOf(3))
        val result = requireNotNull(ProcreateBrushReader.readWithAssets(encoded))
        assertEquals("Ink 漢字", result.brush.name)
        assertEquals(0.5f, result.brush.spacing, 0.00001f)
        assertEquals(0.75f, result.brush.tipRatio, 0f)
        assertEquals(0.25f, result.brush.sizeJitter, 0f)
        assertEquals(7, result.brush.count)
        assertEquals(0.5f, result.brush.grainStrength, 0f)
        assertArrayEquals(byteArrayOf(1, 2), result.shape?.bytes)
        assertArrayEquals(byteArrayOf(3), result.grain?.bytes)
    }

    @Test fun exportsSetOrderAndLibraryNamesWithCollisions() {
        val brush = com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush(name = "Same")
        val loader: (String) -> ProcreateBrushWriter.ExportBrush = { id ->
            ProcreateBrushWriter.ExportBrush(brush, byteArrayOf(id.toInt().toByte()), null)
        }
        val set = ProcreateBrushWriter.ExportSet("Z & Ink", listOf("2", "1"))
        val encodedSet = ProcreateBrushWriter.writeSet(set, loader)
        val imported = requireNotNull(ProcreateBrushReader.readSet(encodedSet))
        assertEquals("Z & Ink", imported.name)
        assertArrayEquals(byteArrayOf(2), imported.brushes[0].shape?.bytes)
        assertArrayEquals(byteArrayOf(1), imported.brushes[1].shape?.bytes)
        val library = ProcreateBrushWriter.writeLibrary(listOf(
            ProcreateBrushWriter.ExportSet("Zulu", listOf("3", "1")),
            ProcreateBrushWriter.ExportSet("Alpha", listOf("2")),
            ProcreateBrushWriter.ExportSet("Zulu", listOf("4")),
        ), loader)
        val decoded = requireNotNull(ProcreateBrushReader.readLibrary(library))
        assertEquals(listOf("Zulu", "Alpha", "Zulu (2)"), decoded.sets.map { it.name })
        assertEquals(listOf(3, 1, 2, 4), decoded.sets.flatMap { group ->
            group.brushes.map { it.shape!!.bytes.single().toInt() }
        })
        val names = mutableListOf<String>()
        java.util.zip.ZipInputStream(library.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names.add(entry.name)
                zip.closeEntry()
            }
        }
        assertTrue("Zulu/Same.brush" in names)
        assertTrue("Zulu/Same (2).brush" in names)
        assertTrue("Alpha/Same.brush" in names)
        assertEquals(names.distinct(), names)
    }

    @Test fun rejectsExportOverMemberLimitBeforeLoading() {
        val ids = List(257) { it.toString() }
        val set = ProcreateBrushWriter.ExportSet("Huge", ids)
        assertTrue(runCatching {
            ProcreateBrushWriter.writeSet(set) { error("must not load") }
        }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching {
            ProcreateBrushWriter.writeLibrary(listOf(set)) { error("must not load") }
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun importsSignedPressureAmountsAndComposedCurves() {
        // Independent Python plistlib fixture: overall .25 -> .75, size curve 0 -> .25 -> 1,
        // positive size amount .8 and negative opacity amount -.6.
        val archive = Base64.getDecoder().decode(
            "YnBsaXN0MDDUAQIDBAUGKi1ZJGFyY2hpdmVyWCRvYmplY3RzVCR0b3BYJHZlcnNpb25fEA9OU0tleWVkQXJjaGl2ZX" +
            "KsBwgVFhkeHyAiJygpVSRudWxs1gkKCwwNDg8QERITFF8QF2R5bmFtaWNzUHJlc3N1cmVPcGFjaXR5XxAXZHluYW1p" +
            "Y3NQcmVzc3VyZU92ZXJhbGxfEBRkeW5hbWljc1ByZXNzdXJlU2l6ZV8QGWR5bmFtaWNzUHJlc3N1cmVTaXplQ3Vydm" +
            "VUbmFtZVtwbG90U3BhY2luZyO/4zMzMzMzM4ADIz/pmZmZmZmagAeAAiM/0AAAAAAAAFhwcmVzc3VyZdEXGFZwb2lu" +
            "dHOABNEaG1pOUy5vYmplY3RzohwdgAWABll7MCwgMC4yNX1ZezEsIDAuNzV90RchgAjRGiOjJCUmgAmACoALVnswLC" +
            "AwfVt7MC41LCAwLjI1fVZ7MSwgMX3RKyxUcm9vdIABEgABhqAACAARABsAJAApADIARABRAFcAZAB+AJgArwDLANAA" +
            "3ADlAOcA8ADyAPQA/QEGAQkBEAESARUBIAEjASUBJwExATsBPgFAAUMBRwFJAUsBTQFUAWABZwFqAW8BcQAAAAAAAA" +
            "IBAAAAAAAAAC4AAAAAAAAAAAAAAAAAAAF2"
        )
        val brush = requireNotNull(ProcreateBrushReader.read(zip("Brush.archive" to archive)))
        val size = brush.dynamics.single { it.parameter == BrushParameter.SIZE }
        val opacity = brush.dynamics.single { it.parameter == BrushParameter.OPACITY }
        assertEquals(0.3f, size.map(0f), 0.00001f)
        assertEquals(0.4f, size.map(0.5f), 0.00001f)
        assertEquals(0.7f, size.map(1f), 0.00001f)
        assertEquals(0.85f, opacity.map(0f), 0.00001f)
        assertEquals(0.7f, opacity.map(0.5f), 0.00001f)
        assertEquals(0.55f, opacity.map(1f), 0.00001f)

        val exported = ProcreateBrushWriter.write(brush, byteArrayOf(1), null)
        val restored = requireNotNull(ProcreateBrushReader.read(exported))
        assertEquals(0.4f, restored.dynamics.single { it.parameter == BrushParameter.SIZE }.map(0.5f), 0.00001f)
        assertEquals(0.55f, restored.dynamics.single { it.parameter == BrushParameter.OPACITY }.map(1f), 0.00001f)
    }

    @Test fun pressureTransferUsesItsOwnCurve() {
        // Independent Python plistlib fixture: transfer amount .6 and curve 0 -> .25 -> 1.
        val archive = Base64.getDecoder().decode(
            "YnBsaXN0MDDUAQIDBAUGHiFZJGFyY2hpdmVyWCRvYmplY3RzVCR0b3BYJHZlcnNpb25fEA9OU0tleWVkQXJjaGl2ZX" +
            "KoBwgREhUbHB1VJG51bGzUCQoLDA0ODxBfEB9keW5hbWljc1ByZXNzdXJlT3BhY2l0eVRyYW5zZmVyXxAnZHluYW1p" +
            "Y3NQcmVzc3VyZVRyYW5zZmVyTW9kdWxhdGlvbkN1cnZlVG5hbWVbcGxvdFNwYWNpbmcjP+MzMzMzMzOAA4ACIz/QAA" +
            "AAAAAAVGZsb3fRExRWcG9pbnRzgATRFhdaTlMub2JqZWN0c6MYGRqABYAGgAdWezAsIDB9W3swLjUsIDAuMjV9Vnsx" +
            "LCAxfdEfIFRyb290gAESAAGGoAAIABEAGwAkACkAMgBEAE0AUwBcAH4AqACtALkAwgDEAMYAzwDUANcA3gDgAOMA7g" +
            "DyAPQA9gD4AP8BCwESARUBGgEcAAAAAAAAAgEAAAAAAAAAIgAAAAAAAAAAAAAAAAAAASE="
        )
        val brush = requireNotNull(ProcreateBrushReader.read(zip("Brush.archive" to archive)))
        val route = brush.dynamics.single()
        assertEquals(BrushParameter.FLOW, route.parameter)
        assertEquals(0.4f, route.map(0f), 0.00001f)
        assertEquals(0.55f, route.map(0.5f), 0.00001f)
        assertEquals(1f, route.map(1f), 0.00001f)
        val restored = requireNotNull(ProcreateBrushReader.read(
            ProcreateBrushWriter.write(brush, byteArrayOf(1), null),
        ))
        assertEquals(0.55f, restored.dynamics.single().map(0.5f), 0.00001f)
    }

    @Test fun importsFixedTextureOrientationAndCanvasTileScale() {
        val archive = Base64.getDecoder().decode(
            "YnBsaXN0MDDUAQIDBAUGGRxZJGFyY2hpdmVyWCRvYmplY3RzVCR0b3BYJHZlcnNpb25fEA9OU0tleWVkQXJjaGl2ZX" +
            "KjBwgYVSRudWxs2AkKCwwNDg8QERITExQVFhdfEBBncmFpbk9yaWVudGF0aW9uVG5hbWVbcGxvdFNwYWNpbmdac2hh" +
            "cGVBbmdsZV8QEHNoYXBlT3JpZW50YXRpb25fEBJ0ZXh0dXJlQXBwbGljYXRpb25fEBN0ZXh0dXJlT2Zmc2V0Sml0dG" +
            "VyXHRleHR1cmVTY2FsZRAEgAIjP9AAAAAAAAAQAxABCSM/4AAAAAAAAFd0ZXh0dXJl0RobVHJvb3SAARIAAYagAAgA" +
            "EQAbACQAKQAyAEQASABOAF8AcgB3AIMAjgChALYAzADZANsA3QDmAOgA6gDrAPQA/AD/AQQBBgAAAAAAAAIBAAAAAA" +
            "AAAB0AAAAAAAAAAAAAAAAAAAEL"
        )
        val result = requireNotNull(ProcreateBrushReader.readWithAssets(
            zip("Brush.archive" to archive, "Shape.png" to byteArrayOf(1), "Grain.png" to byteArrayOf(2)),
        ))
        assertEquals(104.323944f, result.brush.angle, 0.0001f)
        assertEquals(0.35355339f, result.brush.grainScale, 0.00001f)
        assertTrue(result.brush.grainRandomOffsetPerStroke)
        assertEquals(1, result.grain?.quarterTurns)
        assertEquals(0, result.shape?.quarterTurns)
        val exported = ProcreateBrushWriter.write(result.brush, byteArrayOf(1), byteArrayOf(2))
        val restored = requireNotNull(ProcreateBrushReader.read(exported))
        assertEquals(104.323944f, restored.angle, 0.0001f)
        assertEquals(0.35355339f, restored.grainScale, 0.00001f)
        assertTrue(restored.grainRandomOffsetPerStroke)
    }

    @Test fun legacyCanvasGrainCorrectsQuarterTurnAndPolarity() {
        val archive = Base64.getDecoder().decode(
            "YnBsaXN0MDDUAQIDBAUGFRhZJGFyY2hpdmVyWCRvYmplY3RzVCR0b3BYJHZlcnNpb25fEA9OU0tleWVkQXJjaGl2ZX" +
            "KjBwgUVSRudWxs1gkKCwwNDg8QERIRE1RuYW1lW3Bsb3RTcGFjaW5nXxASdGV4dHVyZUFwcGxpY2F0aW9uXxAPdGV4" +
            "dHVyZUludmVydGVkXxASdGV4dHVyZU9yaWVudGF0aW9uXHRleHR1cmVTY2FsZYACIz/QAAAAAAAAEAEJIz/gAAAAAA" +
            "AAVmxlZ2FjedEWF1Ryb290gAESAAGGoAgRGyQpMkRITltgbIGTqLW3wMLDzNPW290AAAAAAAABAQAAAAAAAAAZAAAA" +
            "AAAAAAAAAAAAAAAA4g=="
        )
        val result = requireNotNull(ProcreateBrushReader.readWithAssets(
            zip("Brush.archive" to archive, "Grain.png" to byteArrayOf(2)),
        ))
        assertEquals(-1, result.grain?.quarterTurns)
        assertFalse(requireNotNull(result.grain).inverted)
    }

    @Test fun importsMovingGrainTileAndIndependentSmoothingLevels() {
        // Python plistlib fixture: cropped moving scale 0.5, four independently set smoothing levels.
        val archive = Base64.getDecoder().decode(
            "YnBsaXN0MDDUAQIDBAUGHB9ZJGFyY2hpdmVyWCRvYmplY3RzVCR0b3BYJHZlcnNpb25fEA9OU0tleWVkQXJjaGl2ZX" +
            "KjBwgbVSRudWxs2QkKCwwNDg8QERITFBUWFxgZGl8QGWR5bmFtaWNzUHJlc3N1cmVTbW9vdGhpbmdUbmFtZV8QFnBs" +
            "b3RGRlRTbW9vdGhpbmdBbW91bnRfEB5wbG90TW92aW5nQXZlcmFnZVN0YWJpbGl6YXRpb25dcGxvdFNtb290aGluZ1" +
            "twbG90U3BhY2luZ18QEnRleHR1cmVBcHBsaWNhdGlvblx0ZXh0dXJlU2NhbGVbdGV4dHVyZVpvb20jP8mZmZmZmZqA" +
            "AiM/2ZmZmZmZmiM/0zMzMzMzMyM/4zMzMzMzMyM/0AAAAAAAABAAIz/gAAAAAAAAIz/wAAAAAAAAWXNtb290aGluZ9" +
            "EdHlRyb290gAESAAGGoAAIABEAGwAkACkAMgBEAEgATgBhAH0AggCbALwAygDWAOsA+AEEAQ0BDwEYASEBKgEzATUB" +
            "PgFHAVEBVAFZAVsAAAAAAAACAQAAAAAAAAAgAAAAAAAAAAAAAAAAAAABYA=="
        )
        val imported = requireNotNull(ProcreateBrushReader.readWithAssets(
            zip("Brush.archive" to archive, "Grain.png" to byteArrayOf(1)),
        ))
        assertEquals(181.01934f, imported.movingGrainTilePx!!, 0.0001f)
        assertEquals(0.3535534f, imported.grainScaleForSavedImage(1024, 512), 0.00001f)
        assertEquals(
            com.hereliesaz.graffitixr.common.azphalt.BrushInputSmoothing(60, 30, 40, 20),
            imported.brush.inputSmoothing,
        )
        val saved = imported.brush.copy(grainScale = imported.grainScaleForSavedImage(1024, 512))
        val restored = requireNotNull(ProcreateBrushReader.readWithAssets(
            ProcreateBrushWriter.write(saved, byteArrayOf(1), byteArrayOf(2), 512),
        ))
        assertEquals(181.01934f, restored.movingGrainTilePx!!, 0.0001f)
        assertEquals(imported.brush.inputSmoothing, restored.brush.inputSmoothing)
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
        assertNull(ProcreateBrushReader.read(zip("Brush.archive" to fixture, "ignored.bin" to ByteArray(32 * 1024 * 1024))))
    }

    @Test fun rejectsOversizedTexture() {
        assertNull(ProcreateBrushReader.readWithAssets(zip("Brush.archive" to fixture, "Shape.png" to ByteArray(8 * 1024 * 1024 + 1))))
    }
}
