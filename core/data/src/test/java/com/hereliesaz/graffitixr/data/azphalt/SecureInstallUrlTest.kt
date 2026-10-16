package com.hereliesaz.graffitixr.data.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SecureInstallUrlTest {

    @Test
    fun `a plain https url is accepted`() {
        val url = ExtensionRepository.requireSecureInstallUrl("https://www.azphalt.store/p/x.azp")
        assertEquals("www.azphalt.store", url.host)
    }

    @Test
    fun `scheme comparison ignores case`() {
        ExtensionRepository.requireSecureInstallUrl("HTTPS://example.com/x.azp")
    }

    @Test
    fun `cleartext http is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionRepository.requireSecureInstallUrl("http://example.com/x.azp")
        }
    }

    @Test
    fun `other schemes are rejected`() {
        for (bad in listOf("file:///sdcard/x.azp", "ftp://example.com/x.azp", "content://a/b", "example.com/x.azp")) {
            assertThrows(bad, IllegalArgumentException::class.java) {
                ExtensionRepository.requireSecureInstallUrl(bad)
            }
        }
    }

    @Test
    fun `userinfo is rejected because it disguises the real host`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionRepository.requireSecureInstallUrl("https://trusted.example@evil.example/x.azp")
        }
    }

    @Test
    fun `a missing host or malformed address is rejected`() {
        for (bad in listOf("https:///x.azp", "https://exa mple.com/x", "", "   ")) {
            assertThrows(bad, IllegalArgumentException::class.java) {
                ExtensionRepository.requireSecureInstallUrl(bad)
            }
        }
    }
}
