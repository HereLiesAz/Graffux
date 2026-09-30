package com.hereliesaz.graffux

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.pm.PackageInfoCompat
import com.hereliesaz.graffitixr.common.crash.CrashReporter
import com.hereliesaz.graffitixr.data.prediction.PredictionReportRepository
import com.hereliesaz.graffitixr.feature.editor.prediction.PredictionRankingReporter
import java.io.File

/**
 * TEMPORARY, alongside PredictionReportRepository. On launch, files what the last run left behind
 * as GitHub issues on HereLiesAz/Graffux, through the same transport, token and opt-in as
 * [PredictionRankingReporter]: the token pasted in Settings. Nothing is sent without it (the files
 * simply wait), and a file is deleted only once GitHub accepted it.
 *
 * Three sources, because a crash can die in ways one of them misses:
 * - `last_crash.txt` -- CrashReporter's JVM stack trace, thread, breadcrumbs and logcat
 *   (PII-redacted).
 * - `native_crash.txt` -- NativeCrashHandler's signal backtrace (SIGSEGV, SIGABRT, ...).
 * - Android's own ApplicationExitInfo (API 30+) for crashes, native crashes (with the tombstone's
 *   readable strings), ANRs (with their trace) and foreground low-memory kills since the last one
 *   reported, plus the dying run's breadcrumbs ([PREVIOUS_BREADCRUMBS_FILE]).
 *
 * [CrashReportPlanner] decides the issues (one per death, deduped by stable keys); this class only
 * gathers the inputs and files them.
 */
class CrashIssueUploader(
    private val context: Context,
    private val reports: PredictionReportRepository,
) {
    suspend fun uploadPending() {
        if (!reports.isConnected.value) return
        uploadCrashes()
        uploadPendingRanking(File(context.cacheDir, PredictionRankingReporter.PENDING_FILE))
    }

    private suspend fun uploadCrashes() {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getLong(KEY_LAST_EXIT, 0L)
        val filed = prefs.getString(KEY_FILED, null).orEmpty().split('\n').filter { it.isNotBlank() }
        val jvmFile = File(context.cacheDir, JVM_CRASH_FILE)
        val nativeFile = File(context.cacheDir, NATIVE_CRASH_FILE)
        val crumbsFile = File(context.cacheDir, PREVIOUS_BREADCRUMBS_FILE)
        val plan = CrashReportPlanner.plan(
            jvmText = readOrNull(jvmFile),
            nativeText = readOrNull(nativeFile),
            exits = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) exitRecords() else emptyList(),
            sinceTimestamp = since,
            filedKeys = filed.toSet(),
            previousBreadcrumbs = readOrNull(crumbsFile),
            device = CrashReporter.deviceLabel(),
            build = buildLabel(),
        )
        val remembered = ArrayDeque(filed)
        for (issue in plan) {
            if (!issue.alreadyFiled) {
                val result = reports.fileIssue(issue.title, issue.body)
                if (result.isFailure) {
                    Log.w(TAG, "crash report not filed", result.exceptionOrNull())
                    return // Keep everything for the next launch.
                }
            }
            issue.keys.forEach { if (it !in remembered) remembered.addLast(it) }
            while (remembered.size > MAX_REMEMBERED) remembered.removeFirst()
            val edit = prefs.edit().putString(KEY_FILED, remembered.joinToString("\n"))
            issue.exitTimestamp?.let { if (it > prefs.getLong(KEY_LAST_EXIT, 0L)) edit.putLong(KEY_LAST_EXIT, it) }
            edit.commit()
            if (issue.consumesJvmFile) jvmFile.delete()
            if (issue.consumesNativeFile) nativeFile.delete()
        }
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
    private fun exitRecords(): List<ExitRecord> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return runCatching { am.getHistoricalProcessExitReasons(null, 0, MAX_EXITS) }
            .getOrNull().orEmpty()
            .map { it.toRecord() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun ApplicationExitInfo.toRecord(): ExitRecord {
        val trace = when (reason) {
            ApplicationExitInfo.REASON_ANR -> runCatching {
                traceInputStream?.bufferedReader()?.use { it.readText().take(MAX_TRACE_BYTES) }
            }.getOrNull()
            ApplicationExitInfo.REASON_CRASH_NATIVE -> runCatching {
                traceInputStream?.use { CrashReportPlanner.tombstoneText(readAtMost(it, MAX_TRACE_BYTES)) }
            }.getOrNull()
            else -> null
        }
        return ExitRecord(
            reason = reason,
            importance = importance,
            timestamp = timestamp,
            pid = pid,
            description = description,
            processName = processName,
            pssKb = pss,
            rssKb = rss,
            trace = trace,
        )
    }

    private fun readAtMost(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(BUFFER_BYTES)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun buildLabel(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    }.getOrDefault("?")

    private fun readOrNull(file: File): String? =
        if (file.exists()) runCatching { file.readText() }.getOrNull() else null

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

        const val JVM_CRASH_FILE = CrashReporter.CRASH_FILE
        const val NATIVE_CRASH_FILE = "native_crash.txt"

        /** This run's breadcrumbs, mirrored on every record ([com.hereliesaz.graffitixr.common.crash.Breadcrumbs]). */
        const val BREADCRUMBS_FILE = "breadcrumbs.txt"

        /** The previous run's [BREADCRUMBS_FILE], moved aside at launch before anything records. */
        const val PREVIOUS_BREADCRUMBS_FILE = "breadcrumbs_prev.txt"

        private const val TAG = "CrashIssueUploader"
        private const val PREFS = "crash_issue_uploader"
        private const val KEY_LAST_EXIT = "last_exit_timestamp"
        private const val KEY_FILED = "filed_keys"
        private const val MAX_EXITS = 10
        private const val MAX_REMEMBERED = 64
        private const val MAX_TRACE_BYTES = 512 * 1024
        private const val BUFFER_BYTES = 8 * 1024
    }
}
