package com.hereliesaz.graffitixr.common.util

import org.junit.Assert.*
import org.junit.Test

class NamedPaletteCodecTest {
    // Independent literal preference fixture: "Ink", one occupied slot and twenty-nine empty slots.
    private val fixture = "1|ink\nink|SW5r|ff123456," + List(29) { "-" }.joinToString(",")

    @Test fun readsKnownFixture() {
        val book = requireNotNull(NamedPaletteCodec.decode(fixture))
        assertEquals("ink", book.activeId)
        assertEquals("Ink", book.active?.name)
        assertEquals(listOf(0xff123456.toInt()) + List<Int?>(29) { null }, book.active?.slots)
    }

    @Test fun duplicatesAndClearsWithoutChangingOriginal() {
        val initial = requireNotNull(NamedPaletteCodec.decode(fixture))
        val duplicated = requireNotNull(initial.edit(PaletteEdit.Duplicate("ink"), "copy"))
        assertEquals(listOf("ink", "copy"), duplicated.palettes.map { it.id })
        assertEquals("Ink copy", duplicated.active?.name)
        val cleared = requireNotNull(duplicated.edit(PaletteEdit.SetSlot("copy", 0, null), "unused"))
        assertNull(cleared.active?.slots?.first())
        assertEquals(0xff123456.toInt(), cleared.palettes.first().slots.first())
        val deleted = requireNotNull(cleared.edit(PaletteEdit.Delete("copy"), "unused"))
        assertNull(deleted.activeId)
        assertEquals(listOf("ink"), deleted.palettes.map { it.id })
        assertNull(deleted.edit(PaletteEdit.SetSlot("copy", 0, 0), "unused"))
    }

    @Test fun namesAndOpaqueColorsRoundTrip() {
        val initial = requireNotNull(NamedPaletteBook().edit(PaletteEdit.Create(" Ink | 紙 "), "ink"))
        val saved = requireNotNull(initial.edit(PaletteEdit.SetSlot("ink", 29, 0x00123456), "unused"))
        val decoded = requireNotNull(NamedPaletteCodec.decode(NamedPaletteCodec.encode(saved)))
        assertEquals("Ink | 紙", decoded.active?.name)
        assertEquals(List<Int?>(29) { null } + 0xff123456.toInt(), decoded.active?.slots)
        assertNull(decoded.edit(PaletteEdit.SetSlot("ink", 30, 0), "unused"))
        assertNull(decoded.edit(PaletteEdit.Create(" "), "id"))
    }

    @Test fun damagedBooksAreNotTreatedAsEmpty() {
        assertEquals(NamedPaletteBook(), NamedPaletteCodec.decode(null))
        assertNull(NamedPaletteCodec.decode("2|"))
        assertNull(NamedPaletteCodec.decode("1|missing"))
        assertNull(NamedPaletteCodec.decode(fixture + "\n" + fixture.substringAfter('\n')))
        assertNull(NamedPaletteCodec.decode(fixture.replace("ff123456", "00123456")))
        assertNull(NamedPaletteCodec.decode(fixture.dropLast(2)))
    }
}
