package com.hereliesaz.graffux

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.hereliesaz.graffitixr.data.prediction.PredictionReportRepository
import com.hereliesaz.graffitixr.feature.editor.prediction.PredictionRankingReporter
import java.io.File

/**
 * TEMPORARY, alongside PredictionReportRepository. On launch, files what the last run left behind
 * as GitHub issues on HereLiesAz/Graffux, through the token pasted in Settings. Nothing is sent
 * without that token, and a file is deleted only once GitHub accepted it.
 *
 * Three sources, because a crash can die in ways one of them misses:
 * - `last_crash.txt` -- CrashReporter's JVM stack trace + logcat (already PII-redacted).
 * - `native_crash.txt` -- NativeCrashHandler's signal backtrace (SIGSEGV, SIGABRT, ...).
 * - Android's own ApplicationExitInfo (API 30+) for crashes, native crashes, ANRs and low-memory
 *   kills since the last one reported; an ANR carries the main-thread trace no in-process handler
 *   can write.
 */
class CrashIssueUploader(
    private val context: Context,
    private val reports: PredictionReportRepository,
) {
    suspend fun uploadPending() {
        if (!reports.isConnected.value) return
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        uploadFile(File(context.cacheDir, JVM_CRASH_FILE), "JVM crash", version)
        uploadFile(File(context.cacheDir, NATIVE_CRASH_FILE), "native crash", version)
        uploadPendingRanking(File(context.cacheDir, PredictionRankingReporter.PENDING_FILE))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) uploadExitInfo(version)
    }

    private suspend fun uploadFile(file: File, kind: String, version: String) {
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull().orEmpty()
        if (text.isBlank()) {
            file.delete()
            return
        }
        val headline = text.lineSequence()
            .firstOrNull { it.contains("Exception") || it.contains("Error") || it.contains("signal") }
            ?.trim()?.take(HEADLINE_CHARS)
            ?: kind
        reports.fileIssue("[crash] $version: $headline", body(kind, version, text))
            .onSuccess { file.delete() }
            .onFailure { Log.w(TAG, "crash report not filed", it) }
    }

    /** The stroke-prediction ranking the last run saved but never filed (it died first). */
    private suspend fun uploadPendingRanking(file: File) {
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull().orEmpty()
        val title = text.substringBefore('\n').trim()
        val body = text.substringAfter('\n', "")
        if (title.isBlank() || body.isBlank()) {
            file.delete()
            return
        }
        reports.fileIssue(title, body)
            .onSuccess { file.delete() }
            .onFailure { Log.w(TAG, "saved ranking not filed", it) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun uploadExitInfo(version: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getLong(KEY_LAST_EXIT, 0L)
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = runCatching { am.getHistoricalProcessExitReasons(null, 0, MAX_EXITS) }
            .getOrNull().orEmpty()
            .filter { it.timestamp > since && shouldReportExit(it.reason, it.importance) }
            .sortedBy { it.timestamp }
        for (exit in exits) {
            val kind = reasonName(exit.reason)
            val filed = reports.fileIssue(
                "[crash] $version: $kind" + (exit.description?.let { " - ${it.take(HEADLINE_CHARS)}" } ?: ""),
                body("Android exit record ($kind)", version, exitText(exit, kind)),
            ).isSuccess
            if (!filed) return
            prefs.edit().putLong(KEY_LAST_EXIT, exit.timestamp).apply()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitText(exit: ApplicationExitInfo, kind: String): String {
        val trace = if (exit.reason == ApplicationExitInfo.REASON_ANR) {
            runCatching { exit.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
        } else {
            null
        }
        return buildString {
            appendLine("reason: $kind")
            appendLine("description: ${exit.description}")
            appendLine("process: ${exit.processName}  importance: ${exit.importance}")
            appendLine("pss: ${exit.pss} KB  rss: ${exit.rss} KB")
            appendLine("time: ${java.util.Date(exit.timestamp)}")
            if (!trace.isNullOrBlank()) {
                appendLine()
                append(trace)
            }
        }
    }

    private fun body(kind: String, version: String, text: String) = """
        |Automatic $kind report (temporary; see `CrashIssueUploader`).
        |
        |- Version: $version
        |- Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}
        |
        |~~~
        |${text.take(MAX_BODY_CHARS)}
        |~~~
    """.trimMargin()

    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory kill"
        else -> "exit $reason"
    }

    companion object {
        /**
         * Whether an exit record is worth an issue. Crashes, native crashes and ANRs always are. A
         * low-memory kill only is when the process was foreground or visible ([importance] at most
         * IMPORTANCE_VISIBLE, 200): Android reclaiming a cached/background process (e.g. 400, #469)
         * is normal lifecycle, not a crash, while a foreground LMK does mean memory trouble.
         */
        fun shouldReportExit(reason: Int, importance: Int): Boolean = when (reason) {
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR -> true
            ApplicationExitInfo.REASON_LOW_MEMORY ->
                importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
            else -> false
        }

        const val JVM_CRASH_FILE = "last_crash.txt"
        const val NATIVE_CRASH_FILE = "native_crash.txt"
        private const val TAG = "CrashIssueUploader"
        private const val PREFS = "crash_issue_uploader"
        private const val KEY_LAST_EXIT = "last_exit_timestamp"
        private const val MAX_EXITS = 10
        private const val HEADLINE_CHARS = 120
        // GitHub caps an issue body at 65,536 characters; leave room for the header.
        private const val MAX_BODY_CHARS = 60_000
    }
}
