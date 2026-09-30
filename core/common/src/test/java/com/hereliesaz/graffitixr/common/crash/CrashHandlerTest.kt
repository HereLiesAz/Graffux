package com.hereliesaz.graffitixr.common.crash

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [CrashReporter] as the default handler, and [Breadcrumbs]. Plain JVM: no Android runtime. */
class CrashHandlerTest {

    @get:Rule val tmp = TemporaryFolder()

    private var original: Thread.UncaughtExceptionHandler? = null

    @Before fun setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler()
        Breadcrumbs.clearForTest()
    }

    @After fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original)
        Breadcrumbs.clearForTest()
    }

    private fun reporter(dir: File, logcat: () -> String = { "logcat line" }) = CrashReporter(
        cacheDir = { dir },
        versionName = { "1.48.0 (140010070)" },
        logcat = logcat,
        isMainThread = { true },
    )

    @Test
    fun `writes the report synchronously, then chains to the previous handler`() {
        val dir = tmp.newFolder()
        var chained: Throwable? = null
        var fileAtChain: String? = null
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            chained = e
            fileAtChain = File(dir, CrashReporter.CRASH_FILE).readText()
        }
        reporter(dir).initialize()
        Breadcrumbs.record("project gate: save pressed")

        val boom = IllegalStateException("boom")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)

        assertSame("chained to the previous handler", boom, chained)
        val text = fileAtChain!!
        assertTrue(text.startsWith("FATAL: true"))
        assertTrue(text.contains("java.lang.IllegalStateException: boom"))
        assertTrue(text.contains("VERSION: 1.48.0 (140010070)"))
        assertTrue(text.contains("THREAD: ${Thread.currentThread().name}"))
        assertTrue("breadcrumbs attached", text.contains("project gate: save pressed"))
        assertTrue("logcat appended", text.contains("LOGCAT:\nlogcat line"))
    }

    @Test
    fun `a logcat failure still leaves the trace on disk and still chains`() {
        val dir = tmp.newFolder()
        var chained = false
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> chained = true }
        reporter(dir) { throw OutOfMemoryError("no logcat for you") }.initialize()

        Thread.getDefaultUncaughtExceptionHandler()!!
            .uncaughtException(Thread.currentThread(), RuntimeException("died"))

        assertTrue(chained)
        assertTrue(File(dir, CrashReporter.CRASH_FILE).readText().contains("RuntimeException: died"))
    }

    @Test
    fun `initialize twice does not chain to itself`() {
        val dir = tmp.newFolder()
        var calls = 0
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> calls++ }
        val r = reporter(dir)
        r.initialize()
        r.initialize()
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException())
        assertEquals(1, calls)
    }

    @Test
    fun `breadcrumbs keep only the most recent entries, oldest first`() {
        repeat(Breadcrumbs.CAPACITY + 5) { Breadcrumbs.record("event $it") }
        val crumbs = Breadcrumbs.snapshot()
        assertEquals(Breadcrumbs.CAPACITY, crumbs.size)
        assertTrue(crumbs.first().endsWith("event 5"))
        assertTrue(crumbs.last().endsWith("event ${Breadcrumbs.CAPACITY + 4}"))
    }

    @Test
    fun `breadcrumbs are mirrored to disk for native crashes`() {
        val file = File(tmp.newFolder(), "breadcrumbs.txt")
        Breadcrumbs.persistTo(file)
        Breadcrumbs.record("engine init start")
        assertTrue(file.readText().contains("engine init start"))
        Breadcrumbs.persistTo(null)
        Breadcrumbs.record("not mirrored")
        assertFalse(file.readText().contains("not mirrored"))
    }
}
