package com.hereliesaz.graffitixr.common.crash

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last [CAPACITY] notable events of this process ("save pressed", "engine init start", ...),
 * attached to crash reports so a trace says what the app was doing, not only where it died.
 *
 * In-memory for the JVM handler ([CrashReporter]), and mirrored to [persistTo]'s file on every
 * record so a native crash or ANR, which runs no Kotlin on the way down, still leaves them for the
 * next launch (CrashIssueUploader reads the previous run's copy). Records are rare (a handful per
 * user action, never per frame or per touch sample), so the synchronous rewrite is cheap.
 */
object Breadcrumbs {
    const val CAPACITY = 30

    private val ring = ArrayDeque<String>(CAPACITY)
    private var mirror: File? = null

    /** Test hook: the clock stamped on each entry. */
    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    /** Mirrors every later record to [file] (null stops mirroring). */
    @Synchronized
    fun persistTo(file: File?) {
        mirror = file
        write()
    }

    @Synchronized
    fun record(event: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(clock()))
        if (ring.size == CAPACITY) ring.removeFirst()
        ring.addLast("$stamp [${Thread.currentThread().name}] $event")
        write()
    }

    /** Oldest first. */
    @Synchronized
    fun snapshot(): List<String> = ring.toList()

    @Synchronized
    internal fun clearForTest() {
        ring.clear()
        mirror = null
    }

    private fun write() {
        val file = mirror ?: return
        // Best effort: a breadcrumb must never be what crashes the app.
        runCatching { file.writeText(ring.joinToString("\n")) }
    }
}
