package com.hereliesaz.graffux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** A saved stroke-prediction ranking is filed from the background only with crash consent, redacted. */
class PendingRankingIssueTest {
    private val saved = "Ranking for me@example.com\nfrom 203.0.113.9 with token=abcdef123456"

    @Test fun `nothing is filed without crash-report consent`() {
        assertNull(CrashIssueUploader.pendingRankingIssue(saved, consent = false))
    }

    @Test fun `with consent the title and body are redacted`() {
        val (title, body) = CrashIssueUploader.pendingRankingIssue(saved, consent = true)!!
        assertFalse(title.contains("me@example.com"))
        assertFalse(body.contains("203.0.113.9"))
        assertFalse(body.contains("abcdef123456"))
    }

    @Test fun `an empty file is not filed`() {
        assertNull(CrashIssueUploader.pendingRankingIssue("title only", consent = true))
        assertEquals(null, CrashIssueUploader.pendingRankingIssue("", consent = true))
    }
}
