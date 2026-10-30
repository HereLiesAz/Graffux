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
}
