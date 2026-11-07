// FILE: core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/GpuInitReport.kt
package com.hereliesaz.graffitixr.nativebridge

/**
 * Turns the native engine's "why did init fail" report (core/wgpu-engine diag.rs, plus the C++
 * adapter's library-load failure) into the texts the app shows and logs. Pure string work so it is
 * unit-testable on the JVM.
 */
object GpuInitReport {
    /** Cap for the one-line form, so a breadcrumb stays readable. */
    const val COMPACT_LIMIT = 1200

    /** Shown when the native side returned nothing (an old library, or JNI itself failed). */
    const val UNAVAILABLE = "no report from the native engine (the library predates init reports)"

    private fun lines(report: String): List<String> =
        report.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()

    /** The report on one line (` | ` between lines), cut at [COMPACT_LIMIT]; for breadcrumbs and logs. */
    fun compact(report: String): String {
        val ls = lines(report)
        if (ls.isEmpty()) return UNAVAILABLE
        val joined = ls.joinToString(" | ") { it.trim() }
        return if (joined.length <= COMPACT_LIMIT) joined else joined.take(COMPACT_LIMIT) + "…"
    }

    /** The Settings "Test the drawing engine" failure text: what happened, then the real reason. */
    fun failureMessage(report: String): String {
        val body = lines(report).ifEmpty { listOf(UNAVAILABLE) }.joinToString("\n")
        return "init() failed: the GPU engine could not start on this device. Strokes cannot be " +
            "painted until it does.\n\nReason reported by the engine:\n$body"
    }
}
