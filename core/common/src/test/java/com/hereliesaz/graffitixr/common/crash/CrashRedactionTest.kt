package com.hereliesaz.graffitixr.common.crash

import org.junit.Assert.assertFalse
import org.junit.Test

class CrashRedactionTest {
    private fun redacted(text: String) = CrashReporter.redactSensitive(text)

    @Test fun jsonTokenIsRedacted() = assertFalse(redacted("""{"token":"abc123secret"}""").contains("abc123secret"))
    @Test fun bareGitHubTokenIsRedacted() =
        assertFalse(redacted("auth failed ghp_ABCDEFGHIJKLMNOPQRSTUVWX12").contains("ghp_ABCDEFGHIJ"))
    @Test fun fineGrainedTokenIsRedacted() =
        assertFalse(redacted("github_pat_11ABCDEFG_abcdefghijklmnopqrstu").contains("github_pat_11"))
    @Test fun contentUriIsRedacted() =
        assertFalse(redacted("open content://media/external/images/1234 failed").contains("media/external"))
    @Test fun privatePathIsRedacted() =
        assertFalse(redacted("at /data/user/0/com.hereliesaz.graffux/files/projects/p1").contains("projects/p1"))
    @Test fun emailIsRedacted() = assertFalse(redacted("user me@example.com").contains("me@example.com"))
    @Test fun passwordKeyValueIsRedacted() =
        assertFalse(redacted("login password=hunter2!x failed").contains("hunter2"))
    @Test fun basicAuthIsRedacted() =
        assertFalse(redacted("Authorization: Basic dXNlcjpwYXNzd29yZA==").contains("dXNlcjpwYXNzd29yZA"))
    @Test fun cookieHeaderIsRedacted() =
        assertFalse(redacted("Cookie: azphalt_buyer_session=v1.abc.def").contains("v1.abc.def"))
    @Test fun ipv4IsRedacted() = assertFalse(redacted("connect to 203.0.113.42:443 failed").contains("203.0.113.42"))
    @Test fun ipv6IsRedacted() =
        assertFalse(redacted("from 2001:db8:85a3:0:0:8a2e:370:7334").contains("8a2e:370:7334"))
    @Test fun versionNumbersSurvive() =
        org.junit.Assert.assertTrue(redacted("[crash] x, 1.53.2.1 (140010150)").contains("1.53.2.1 (140010150)"))

    // Gaps: prefixed key names, other credential shapes, and identifiers that name a person or device.
    @Test fun prefixedTokenKeysAreRedacted() {
        for (t in listOf(
            "access_token=abc123xyz", "refresh_token: abc123xyz", "client_secret=abc123xyz",
            "id_token=abc123xyz", "x-api-key: abc123xyz", "apikey=abc123xyz", "?sig=abc&access_token=abc123xyz&x=1",
        )) {
            assertFalse(t, redacted(t).contains("abc123xyz"))
        }
    }
    @Test fun authorizationHeaderOfAnySchemeIsRedacted() =
        assertFalse(redacted("Authorization: Token abcdef123456").contains("abcdef123456"))
    @Test fun jwtIsRedacted() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertFalse(redacted("failed with $jwt here").contains("eyJzdWIi"))
    }
    @Test fun providerKeysAreRedacted() {
        // Assembled at run time so no secret-shaped literal sits in the repository (push protection).
        val body = "abcdefghijklmnopqrstuvwx"
        for ((prefix, tail) in listOf(
            "AI" + "zaSy" to "A1234567890$body",
            "AK" + "IA" to "IOSFODNN7EXAMPLE",
            "sk" + "_live_" to body,
            "xo" + "xb-" to "1234567890-$body",
        )) {
            assertFalse(prefix, redacted("seen $prefix$tail here").contains(tail))
        }
    }
    @Test fun urlUserInfoIsRedacted() =
        assertFalse(redacted("GET https://bob:hunter2pw@example.com/x").contains("hunter2pw"))
    @Test fun macAddressIsRedacted() =
        assertFalse(redacted("bssid 3c:5a:b4:12:9f:ee seen").contains("3c:5a:b4"))
    @Test fun lngCoordinateIsRedacted() =
        assertFalse(redacted("lng=-122.084123 and lat: 37.4219").contains("122.084123"))
    @Test fun sdCardAndUserHomePathsAreRedacted() {
        assertFalse(redacted("open /storage/1A2B-3C4D/DCIM/wall.jpg").contains("DCIM"))
        assertFalse(redacted("at /home/alice/work/x").contains("alice"))
        assertFalse(redacted("C:\\Users\\alice\\x").contains("alice"))
    }
    @Test fun ordinaryTextSurvives() {
        val text = "java.lang.IllegalStateException: layer 3 of 7 failed at EditorViewModel.kt:120 (auth screen)"
        org.junit.Assert.assertEquals(text, redacted(text))
    }
}
