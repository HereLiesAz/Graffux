package com.hereliesaz.graffux

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CrashIssueUploader.shouldReportExit]: background low-memory kills are not crashes (#469). */
class CrashExitFilterTest {
    private fun report(reason: Int, importance: Int) = CrashIssueUploader.shouldReportExit(reason, importance)

    @Test
    fun `background and cached low memory kills are not filed`() {
        assertFalse(report(ApplicationExitInfo.REASON_LOW_MEMORY, RunningAppProcessInfo.IMPORTANCE_CACHED))
        assertFalse(report(ApplicationExitInfo.REASON_LOW_MEMORY, RunningAppProcessInfo.IMPORTANCE_SERVICE))
    }

    @Test
    fun `foreground and visible low memory kills are filed`() {
        assertTrue(report(ApplicationExitInfo.REASON_LOW_MEMORY, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertTrue(report(ApplicationExitInfo.REASON_LOW_MEMORY, RunningAppProcessInfo.IMPORTANCE_VISIBLE))
    }

    @Test
    fun `crashes and ANRs are filed at any importance`() {
        for (reason in listOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
        )) {
            assertTrue(report(reason, RunningAppProcessInfo.IMPORTANCE_CACHED))
            assertTrue(report(reason, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        }
    }

    @Test
    fun `other exits are never filed`() {
        assertFalse(report(ApplicationExitInfo.REASON_USER_REQUESTED, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertFalse(report(ApplicationExitInfo.REASON_EXIT_SELF, RunningAppProcessInfo.IMPORTANCE_CACHED))
    }
}
