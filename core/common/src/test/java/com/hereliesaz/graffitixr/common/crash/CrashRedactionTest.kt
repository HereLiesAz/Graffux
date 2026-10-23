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
}
