package com.hereliesaz.graffux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AzphaltInstallLinkTest {

    @Test
    fun `accepts an https address and reports its lower-cased host`() {
        val target = parseAzphaltInstallTarget("https://Store.Example.com/pkg/brush.azp?token=abc")
        assertEquals("store.example.com", target?.host)
        assertEquals("https://Store.Example.com/pkg/brush.azp?token=abc", target?.url)
    }

    @Test
    fun `rejects cleartext and non-http schemes`() {
        assertNull(parseAzphaltInstallTarget("http://store.example.com/brush.azp"))
        assertNull(parseAzphaltInstallTarget("ftp://store.example.com/brush.azp"))
        assertNull(parseAzphaltInstallTarget("file:///sdcard/brush.azp"))
        assertNull(parseAzphaltInstallTarget("javascript:alert(1)"))
    }

    @Test
    fun `rejects a user-info prefix that would spoof the displayed host`() {
        assertNull(parseAzphaltInstallTarget("https://trusted.example@evil.example/brush.azp"))
    }

    @Test
    fun `rejects missing, blank, relative and newline-padded values`() {
        assertNull(parseAzphaltInstallTarget(null))
        assertNull(parseAzphaltInstallTarget("   "))
        assertNull(parseAzphaltInstallTarget("/brush.azp"))
        assertNull(parseAzphaltInstallTarget("https:///brush.azp"))
        assertNull(parseAzphaltInstallTarget("https://evil.example/\n\n\n\n\nstore.example.com"))
    }

    @Test
    fun `truncates long addresses for display only`() {
        val long = "https://store.example.com/" + "a".repeat(500)
        val shown = truncateInstallUrl(long)
        assertEquals(MAX_INSTALL_URL_DISPLAY + 1, shown.length)
        assertTrue(shown.endsWith("…"))
        assertEquals("https://x.example/y", truncateInstallUrl("https://x.example/y"))
    }
}
