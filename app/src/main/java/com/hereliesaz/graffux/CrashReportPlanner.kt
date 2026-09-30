package com.hereliesaz.graffux

import android.app.ApplicationExitInfo
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One `ApplicationExitInfo`, copied into plain fields so the planning below is testable without
 * Android. [trace] is already text: an ANR's trace as-is, a native crash's tombstone reduced to its
 * readable strings ([CrashReportPlanner.tombstoneText]).
 */
internal data class ExitRecord(
    val reason: Int,
    val importance: Int,
    val timestamp: Long,
    val pid: Int,
    val description: String?,
    val processName: String?,
    val pssKb: Long = 0,
    val rssKb: Long = 0,
    val trace: String? = null,
)

/**
 * An issue to file. [keys] are what the dedupe store remembers once it is filed (or found already
 * filed); [exitTimestamp] advances the exit watermark; the two flags say which pending files it
 * consumes, so they are deleted only once GitHub took the issue.
 */
internal data class PlannedIssue(
    val keys: List<String>,
    val title: String,
    val body: String,
    val exitTimestamp: Long? = null,
    val consumesJvmFile: Boolean = false,
    val consumesNativeFile: Boolean = false,
    val alreadyFiled: Boolean = false,
)

/**
 * What the last run left behind, turned into GitHub issues: pure, so it is unit-tested with fakes.
 *
 * Sources: the JVM handler's `last_crash.txt` (stack trace + breadcrumbs + logcat), the signal
 * handler's `native_crash.txt`, and Android's exit records since the last one reported. A JVM file
 * and the newest `REASON_CRASH` record describe the same death, as do a native file and the newest
 * `REASON_CRASH_NATIVE`; each pair becomes one issue, not two. Every issue has stable [dedupe
 * keys][PlannedIssue.keys] (`exit:<time>:<pid>`, `jvm:<sha>`, `native:<sha>`), so an exit that was
 * already filed is never filed again, even if the watermark was lost.
 */
@Suppress("TooManyFunctions")
internal object CrashReportPlanner {
    const val HEADLINE_CHARS = 120

    /** GitHub caps an issue body at 65,536 characters; leave room for the header. */
    const val MAX_BODY_CHARS = 60_000

    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    fun plan(
        jvmText: String?,
        nativeText: String?,
        exits: List<ExitRecord>,
        sinceTimestamp: Long,
        filedKeys: Set<String>,
        previousBreadcrumbs: String?,
        device: String,
        build: String,
    ): List<PlannedIssue> {
        val jvm = jvmText?.takeIf { it.isNotBlank() }
        val native = nativeText?.takeIf { it.isNotBlank() }
        val reportable = exits
            .filter { it.timestamp > sinceTimestamp && CrashIssueUploader.shouldReportExit(it.reason, it.importance) }
            .sortedBy { it.timestamp }
        val jvmPair = if (jvm != null) reportable.lastOrNull { it.reason == ApplicationExitInfo.REASON_CRASH } else null
        val nativePair =
            if (native != null) reportable.lastOrNull { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE } else null

        val out = mutableListOf<PlannedIssue>()
        for (exit in reportable) {
            val exitKey = exitKey(exit)
            val file = when (exit) {
                jvmPair -> jvm
                nativePair -> native
                else -> null
            }
            val fileKey = when (exit) {
                jvmPair -> jvm?.let { "jvm:${sha(it)}" }
                nativePair -> native?.let { "native:${sha(it)}" }
                else -> null
            }
            val headline = when {
                exit === jvmPair && jvm != null -> jvmHeadline(jvm)
                exit === nativePair && native != null -> nativeHeadline(native) ?: exitHeadline(exit)
                else -> exitHeadline(exit)
            }
            val text = buildString {
                if (file != null) {
                    appendLine(file.trimEnd())
                    appendLine()
                    appendLine("---- Android exit record ----")
                }
                append(exitText(exit))
                // The JVM file carries its own breadcrumbs; the other deaths ran no Kotlin on the
                // way down, so the previous run's mirrored copy is all there is.
                if (file == null || exit === nativePair) appendBreadcrumbs(previousBreadcrumbs)
            }
            val keys = listOfNotNull(exitKey, fileKey)
            out += PlannedIssue(
                keys = keys,
                title = title(headline, device, build),
                body = body("Android exit record (${reasonName(exit.reason)})", device, build, text),
                exitTimestamp = exit.timestamp,
                consumesJvmFile = exit === jvmPair,
                consumesNativeFile = exit === nativePair,
                alreadyFiled = keys.any { it in filedKeys },
            )
        }
        if (jvm != null && jvmPair == null) {
            val key = "jvm:${sha(jvm)}"
            out += PlannedIssue(
                keys = listOf(key),
                title = title(jvmHeadline(jvm), device, build),
                body = body("JVM crash", device, build, jvm),
                consumesJvmFile = true,
                alreadyFiled = key in filedKeys,
            )
        }
        if (native != null && nativePair == null) {
            val key = "native:${sha(native)}"
            out += PlannedIssue(
                keys = listOf(key),
                title = title(nativeHeadline(native) ?: "native crash", device, build),
                body = body(
                    "native crash",
                    device,
                    build,
                    buildString { append(native.trimEnd()); appendBreadcrumbs(previousBreadcrumbs) },
                ),
                consumesNativeFile = true,
                alreadyFiled = key in filedKeys,
            )
        }
        return out
    }

    /** `[crash] <exception or signal> — <device>, <build>` */
    fun title(headline: String, device: String, build: String): String =
        "[crash] ${headline.trim().take(HEADLINE_CHARS)} — $device, $build"

    fun body(kind: String, device: String, build: String, text: String): String = buildString {
        appendLine("Automatic $kind report (see `CrashIssueUploader`).")
        appendLine()
        appendLine("- Build: $build")
        appendLine("- Device: $device")
        appendLine()
        appendLine("~~~")
        val capped = if (text.length > MAX_BODY_CHARS) {
            text.take(MAX_BODY_CHARS) + "\n… [truncated ${text.length - MAX_BODY_CHARS} chars]"
        } else {
            text
        }
        appendLine(capped.replace("~~~", "~ ~ ~"))
        append("~~~")
    }

    /** The exception line of a JVM report: the first line of its stack trace. */
    fun jvmHeadline(text: String): String {
        val trace = text.substringAfter("STACK TRACE:", text)
        return trace.lineSequence().map { it.trim() }
            .firstOrNull { it.contains("Exception") || it.contains("Error") || it.contains("Throwable") }
            ?: "JVM crash"
    }

    /** The signal line of a native report ("Fatal signal 11 (SIGSEGV) ..."), if it has one. */
    fun nativeHeadline(text: String): String? = text.lineSequence().map { it.trim() }
        .firstOrNull { it.contains("signal", ignoreCase = true) || it.contains("SIG") }

    fun exitHeadline(exit: ExitRecord): String {
        val kind = reasonName(exit.reason)
        return exit.description?.takeIf { it.isNotBlank() }?.let { "$kind: $it" } ?: kind
    }

    fun exitKey(exit: ExitRecord): String = "exit:${exit.timestamp}:${exit.pid}"

    fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory kill"
        else -> "exit $reason"
    }

    fun exitText(exit: ExitRecord): String = buildString {
        appendLine("reason: ${reasonName(exit.reason)}")
        appendLine("description: ${exit.description}")
        appendLine("process: ${exit.processName}  pid: ${exit.pid}  importance: ${exit.importance}")
        appendLine("pss: ${exit.pssKb} KB  rss: ${exit.rssKb} KB")
        appendLine("time: ${utc(exit.timestamp)}")
        exit.trace?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine(it.trimEnd())
        }
    }

    /**
     * A native crash's trace stream is a tombstone protobuf (API 31+), not text. Its useful parts
     * (signal, abort message, backtrace frames, library names) are embedded strings, so keep the
     * runs of printable characters at least [minRun] long, one per line, as `strings(1)` would.
     */
    fun tombstoneText(bytes: ByteArray, minRun: Int = 4): String {
        val out = StringBuilder()
        val run = StringBuilder()
        fun flush() {
            if (run.length >= minRun) out.append(run).append('\n')
            run.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and BYTE_MASK
            if (c in PRINTABLE_FIRST..PRINTABLE_LAST || c == '\t'.code) run.append(c.toChar()) else flush()
        }
        flush()
        return out.toString()
    }

    private fun StringBuilder.appendBreadcrumbs(crumbs: String?) {
        if (crumbs.isNullOrBlank()) return
        appendLine()
        appendLine("BREADCRUMBS from the run that died (oldest first):")
        appendLine(crumbs.trimEnd())
    }

    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .take(SHA_BYTES)
        .joinToString("") { "%02x".format(it) }

    private fun utc(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'UTC'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))

    private const val SHA_BYTES = 8
    private const val BYTE_MASK = 0xFF
    private const val PRINTABLE_FIRST = 0x20
    private const val PRINTABLE_LAST = 0x7E
}
