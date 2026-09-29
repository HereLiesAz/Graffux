@file:Suppress("MaxLineLength", "LongParameterList")

package com.hereliesaz.graffux

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CrashReportPlanner]: formatting, pairing, dedupe and exit-record parsing, with fake records. */
class CrashReportPlannerTest {
    private val device = "Google Pixel 5, Android 16"
    private val build = "1.48.0 (140010070)"

    private val jvmReport = """
        FATAL: true
        TIMESTAMP: 2026-09-29 19:00:00
        DEVICE: Google Pixel 5, Android 16
        VERSION: 1.48.0 (140010070)
        THREAD: main

        STACK TRACE:
        java.lang.IllegalStateException: layer not found
            at com.hereliesaz.Foo.bar(Foo.kt:1)
        BREADCRUMBS (oldest first):
        19:00:00.000 [main] project gate: save pressed
    """.trimIndent()

    private fun exit(reason: Int, ts: Long, importance: Int = RunningAppProcessInfo.IMPORTANCE_FOREGROUND, trace: String? = null) =
        ExitRecord(reason, importance, ts, pid = 4242, description = "desc $ts", processName = "com.hereliesaz.graffux", trace = trace)

    private fun plan(
        jvm: String? = null,
        native: String? = null,
        exits: List<ExitRecord> = emptyList(),
        since: Long = 0L,
        filed: Set<String> = emptySet(),
        crumbs: String? = null,
    ) = CrashReportPlanner.plan(jvm, native, exits, since, filed, crumbs, device, build)

    @Test
    fun `a JVM report is titled by its exception, device and build`() {
        val issues = plan(jvm = jvmReport)
        assertEquals(1, issues.size)
        assertEquals(
            "[crash] java.lang.IllegalStateException: layer not found — Google Pixel 5, Android 16, 1.48.0 (140010070)",
            issues[0].title,
        )
        assertTrue(issues[0].body.contains("project gate: save pressed"))
        assertTrue(issues[0].consumesJvmFile)
    }

    @Test
    fun `a JVM report and its REASON_CRASH record become one issue`() {
        val issues = plan(jvm = jvmReport, exits = listOf(exit(ApplicationExitInfo.REASON_CRASH, 100)))
        assertEquals(1, issues.size)
        val issue = issues[0]
        assertTrue(issue.title.contains("IllegalStateException"))
        assertTrue(issue.consumesJvmFile)
        assertEquals(100L, issue.exitTimestamp)
        assertTrue(issue.keys.contains("exit:100:4242"))
        assertTrue(issue.keys.any { it.startsWith("jvm:") })
    }

    @Test
    fun `a native crash carries its tombstone strings and the dying run's breadcrumbs`() {
        val tombstone = CrashReportPlanner.tombstoneText(
            byteArrayOf(0x0A, 0x01) + "signal 11 (SIGSEGV), code 1".toByteArray() + byteArrayOf(0x00, 0x12) +
                "libwgpu_native.so".toByteArray() + byteArrayOf(0x7F, 0x02, 'a'.code.toByte()),
        )
        assertEquals("signal 11 (SIGSEGV), code 1\nlibwgpu_native.so\n", tombstone)

        val issues = plan(
            exits = listOf(exit(ApplicationExitInfo.REASON_CRASH_NATIVE, 7, trace = tombstone)),
            crumbs = "12:00:00.000 [DefaultDispatcher-worker-1] calibration start",
        )
        assertEquals(1, issues.size)
        assertTrue(issues[0].title.startsWith("[crash] native crash: desc 7 — "))
        assertTrue(issues[0].body.contains("libwgpu_native.so"))
        assertTrue(issues[0].body.contains("calibration start"))
    }

    @Test
    fun `the native handler's file pairs with the newest native exit and names the signal`() {
        val issues = plan(
            native = "Fatal signal 6 (SIGABRT) in tid 123\n#00 pc 0001 libc.so",
            exits = listOf(exit(ApplicationExitInfo.REASON_CRASH_NATIVE, 9)),
        )
        assertEquals(1, issues.size)
        assertTrue(issues[0].title.startsWith("[crash] Fatal signal 6 (SIGABRT) in tid 123 — "))
        assertTrue(issues[0].consumesNativeFile)
    }

    @Test
    fun `ANR, crash and native crash records are parsed, old and background ones dropped`() {
        val issues = plan(
            exits = listOf(
                exit(ApplicationExitInfo.REASON_ANR, 30, trace = "\"main\" prio=5 tid=1 Blocked"),
                exit(ApplicationExitInfo.REASON_CRASH, 20),
                exit(ApplicationExitInfo.REASON_CRASH_NATIVE, 5), // before the watermark
                exit(ApplicationExitInfo.REASON_LOW_MEMORY, 25, importance = RunningAppProcessInfo.IMPORTANCE_CACHED),
                exit(ApplicationExitInfo.REASON_USER_REQUESTED, 26),
            ),
            since = 10,
        )
        assertEquals(listOf(20L, 30L), issues.map { it.exitTimestamp })
        assertTrue(issues[0].title.startsWith("[crash] crash: desc 20"))
        assertTrue(issues[1].title.startsWith("[crash] ANR: desc 30"))
        assertTrue(issues[1].body.contains("tid=1 Blocked"))
    }

    @Test
    fun `an exit already filed is never filed again, even without the watermark`() {
        val first = plan(exits = listOf(exit(ApplicationExitInfo.REASON_CRASH_NATIVE, 50)))
        assertFalse(first.single().alreadyFiled)
        val again = plan(exits = listOf(exit(ApplicationExitInfo.REASON_CRASH_NATIVE, 50)), filed = first.single().keys.toSet())
        assertTrue(again.single().alreadyFiled)

        val jvmFirst = plan(jvm = jvmReport).single()
        assertTrue(plan(jvm = jvmReport, filed = jvmFirst.keys.toSet()).single().alreadyFiled)
    }

    @Test
    fun `the body is capped under GitHub's limit`() {
        val huge = "STACK TRACE:\njava.lang.OutOfMemoryError: x\n" + "a".repeat(200_000)
        val body = plan(jvm = huge).single().body
        assertTrue(body.length < 65_536)
        assertTrue(body.contains("[truncated"))
    }
}
