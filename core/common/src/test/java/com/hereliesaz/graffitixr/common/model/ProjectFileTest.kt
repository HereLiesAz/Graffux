package com.hereliesaz.graffitixr.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The naming rules for `.fux` files. Worth testing properly because every failure here is silent:
 * a rejected filename means the save doesn't happen and the user is told nothing.
 */
class ProjectFileTest {

    @Test
    fun `a plain name gets the fux extension`() {
        assertEquals("Mural.fux", ProjectFile.suggestedFileName("Mural"))
    }

    @Test
    fun `a name the user already ended in fux is not doubled`() {
        // Typing the extension yourself is the obvious thing to do, and "Mural.fux.fux" is the
        // obvious way to punish it.
        assertEquals("Mural.fux", ProjectFile.suggestedFileName(ProjectFile.projectNameFrom("Mural.fux")))
    }

    @Test
    fun `a blank or null name falls back rather than producing a dotfile`() {
        // "" would yield ".fux" — a hidden file on every Unix filesystem, and invisible to the
        // user who just saved it.
        assertEquals("Untitled.fux", ProjectFile.suggestedFileName(""))
        assertEquals("Untitled.fux", ProjectFile.suggestedFileName("   "))
        assertEquals("Untitled.fux", ProjectFile.suggestedFileName(null))
    }

    @Test
    fun `path separators are stripped so a name cannot become a directory`() {
        assertEquals("etcpasswd.fux", ProjectFile.suggestedFileName("etc/passwd"))
        assertEquals("abc.fux", ProjectFile.suggestedFileName("a\\b/c"))
    }

    @Test
    fun `characters windows reserves are stripped`() {
        // A .fux synced to a desktop or written to an SD card lands on NTFS or FAT32.
        assertEquals("what.fux", ProjectFile.suggestedFileName("w:h*a?t"))
        assertEquals("quoted.fux", ProjectFile.suggestedFileName("\"quoted\""))
        assertEquals("ab.fux", ProjectFile.suggestedFileName("a<b>"))
        assertEquals("ab.fux", ProjectFile.suggestedFileName("a|b"))
    }

    @Test
    fun `control characters are stripped`() {
        assertEquals("ab.fux", ProjectFile.suggestedFileName("a\u0001b"))
        assertEquals("ab.fux", ProjectFile.suggestedFileName("a\nb"))
    }

    @Test
    fun `a name made entirely of illegal characters falls back`() {
        assertEquals("Untitled.fux", ProjectFile.suggestedFileName("///"))
    }

    @Test
    fun `trailing dots and spaces are trimmed`() {
        // Windows removes these itself, so keeping them makes the saved name differ from the name
        // the app believes it wrote.
        assertEquals("Mural.fux", ProjectFile.suggestedFileName("Mural..."))
        assertEquals("Mural.fux", ProjectFile.suggestedFileName("Mural   "))
    }

    @Test
    fun `spaces and unicode inside a name are kept`() {
        // Sanitising is about what filesystems reject, not about tidying the user's title.
        assertEquals("My Big Mural.fux", ProjectFile.suggestedFileName("My Big Mural"))
        assertEquals("Grüße 🎨.fux", ProjectFile.suggestedFileName("Grüße 🎨"))
    }

    @Test
    fun `a very long name is capped short of the filesystem limit`() {
        val name = ProjectFile.suggestedFileName("x".repeat(500))
        assertTrue("got ${name.length} chars", name.length < 255)
        assertTrue(name.endsWith(".fux"))
    }

    @Test
    fun `a long name capped mid-dots does not end in a dot`() {
        // The cap can land inside a run of dots, re-creating the trailing-dot problem after it was
        // already trimmed once.
        val name = ProjectFile.sanitizeName("y".repeat(118) + "..." + "y".repeat(10))
        assertFalse("ended with a dot: $name", name.endsWith("."))
    }

    // ── Reading a name back off a file ────────────────────────────────────────────────────────

    @Test
    fun `the project name is recovered from a filename`() {
        assertEquals("Mural", ProjectFile.projectNameFrom("Mural.fux"))
        assertEquals("Mural", ProjectFile.projectNameFrom("/storage/emulated/0/Download/Mural.fux"))
    }

    @Test
    fun `the extension is matched case-insensitively`() {
        // A file that came back from a desktop may well be ".FUX".
        assertEquals("Mural", ProjectFile.projectNameFrom("Mural.FUX"))
        assertTrue(ProjectFile.isProjectFile("Mural.Fux"))
    }

    @Test
    fun `projects saved by older builds still open`() {
        assertEquals("Mural", ProjectFile.projectNameFrom("Mural.gxr"))
        assertTrue(ProjectFile.isProjectFile("Mural.gxr"))
    }

    @Test
    fun `a filename with no known extension keeps its whole name`() {
        assertEquals("Mural.png", ProjectFile.projectNameFrom("Mural.png"))
        assertFalse(ProjectFile.isProjectFile("Mural.png"))
        assertFalse(ProjectFile.isProjectFile(null))
    }

    @Test
    fun `saving an opened file round-trips its name`() {
        val original = "My Big Mural.fux"
        assertEquals(original, ProjectFile.suggestedFileName(ProjectFile.projectNameFrom(original)))
    }
}
