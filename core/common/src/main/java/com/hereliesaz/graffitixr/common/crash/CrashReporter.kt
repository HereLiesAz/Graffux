package com.hereliesaz.graffitixr.common.crash

import android.content.Context
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Intercepts uncaught exceptions and dumps logs to a file for reporting on next launch.
 */
class CrashReporter(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    fun initialize() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // ARCore drives its camera through an internal CameraX "camera pipe" (logcat tag CXCP) on its
        // own worker threads. On some devices (e.g. Samsung SM-A236U) a long run of ERROR_CAMERA_DEVICE
        // makes the OEM camera HAL drop the device, and ARCore's pipe then throws
        // "Failed to load metadata for CameraId-N" / "Unable to retrieve camera characteristics"
        // out of getCameraCharacteristics while the session is already tearing down. This lands on an
        // ARCore-managed background thread we cannot wrap in try/catch, so it reaches the default
        // handler and kills the whole app — even though the user has just left AR and nothing in the
        // foreground is broken. Record it for diagnostics, but let the process keep running instead of
        // crashing to a full-screen report. The faulting worker thread is already unwinding; not
        // delegating to the default handler simply restores plain-JVM "only that thread dies" semantics.
        if (thread !== Looper.getMainLooper().thread && isRecoverableArCameraCrash(throwable)) {
            try {
                // fatal = false indicates a recovered event, not a force-close.
                saveReport(buildReport(throwable, fatal = false))
            } catch (e: Exception) {
                Log.e("CrashReporter", "Failed to save crash report", e)
            }
            Log.w("CrashReporter", "Swallowed recoverable ARCore camera-pipe crash on ${thread.name}", throwable)
            return
        }
        try {
            val report = buildReport(throwable, fatal = true)
            saveReport(report)
        } catch (e: Exception) {
            Log.e("CrashReporter", "Failed to save crash report", e)
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun buildReport(throwable: Throwable, fatal: Boolean): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val stackTrace = Log.getStackTraceString(throwable)
        val logcat = collectLogcat()

        // FATAL is the first line: true = the process was killed, false = the exception was caught
        val report = """
            FATAL: $fatal
            TIMESTAMP: $timestamp
            DEVICE: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})
            VERSION: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}

            STACK TRACE:
            $stackTrace

            LOGCAT:
            $logcat
        """.trimIndent()
        // Scrub known-sensitive shapes before saving crash report to disk; this is defense-in-depth,
        // not a substitute for not logging secrets in the first place.
        return redactSensitive(report)
    }

    private fun collectLogcat(): String {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "1000", "--pid=${android.os.Process.myPid()}"))
            InputStreamReader(process.inputStream).use { it.readText() }
        } catch (e: Exception) {
            "Failed to collect Logcat: ${e.message}"
        } finally {
            // Release the subprocess's pipes/FDs; readText() above already drained stdout.
            process?.destroy()
        }
    }

    private fun saveReport(report: String) {
        val file = File(context.cacheDir, "last_crash.txt")
        FileOutputStream(file).use {
            it.write(report.toByteArray())
        }
    }

    companion object {
        // Best-effort scrub of shapes that are sensitive if they end up in a public crash report:
        // GPS coordinates, bearer/session/API tokens, and email addresses. Not exhaustive (there is
        // no reliable way to redact arbitrary PII from free-form log text), but it removes the
        // concrete categories this app's own logs and libraries are known to emit.
        private val REDACTION_PATTERNS: List<Pair<Regex, String>> = listOf(
            // Decimal-degree coordinate pairs, e.g. "37.421998,-122.084000" or "37.421998, -122.084".
            Regex("""-?\d{1,3}\.\d{4,}\s*,\s*-?\d{1,3}\.\d{4,}""") to "[REDACTED_COORDS]",
            // key=value / key: value latitude / longitude fields.
            Regex("""(?i)\b(lat(?:itude)?)\s*[=:]\s*-?\d{1,3}\.\d+""") to "$1=[REDACTED]",
            Regex("""(?i)\b(lon(?:g(?:itude)?)?)\s*[=:]\s*-?\d{1,3}\.\d+""") to "$1=[REDACTED]",
            // Bearer/auth headers and token-/secret-/key-/session-shaped key=value pairs.
            Regex("""(?i)\bBearer\s+[A-Za-z0-9\-_.=]{8,}""") to "Bearer [REDACTED]",
            Regex("""(?i)\b(token|secret|api[_-]?key|session[_-]?id|auth)\s*[=:]\s*[A-Za-z0-9\-_.]{6,}""") to "$1=[REDACTED]",
            // Email addresses.
            Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to "[REDACTED_EMAIL]",
        )

        /** Scrubs known-sensitive substrings (see [REDACTION_PATTERNS]) from free-form report text. */
        internal fun redactSensitive(text: String): String =
            REDACTION_PATTERNS.fold(text) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }

        // Substrings that uniquely identify ARCore's camera-pipe "the camera device vanished while we
        // were still using it" failure. Stored lowercase so matching only has to lowercase the
        // message; checked against every message in the throwable's cause + suppressed chain.
        private val CAMERA_PIPE_MESSAGE_SIGNATURES = listOf(
            "failed to load metadata for cameraid",
            "unable to retrieve camera characteristics",
            // CameraX fires this SecurityException on its own worker thread when ARCore evicts it
            // mid-capture: "Attempt to use camera from a different process than original client".
            // The faulting CameraX thread is already dying; no foreground state is broken.
            "attempt to use camera from a different process",
        )

        /**
         * True if [throwable] (or anything in its cause/suppressed chain) is the known ARCore
         * camera-pipe teardown crash — a camera-metadata load failure raised from
         * [android.hardware.camera2.CameraManager.getCameraCharacteristics]. Pure and Android-free so
         * it can be unit-tested. The caller is responsible for restricting recovery to background
         * threads.
         */
        fun isRecoverableArCameraCrash(throwable: Throwable): Boolean {
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            var matchedMessage = false
            var matchedFrame = false

            fun visit(t: Throwable?) {
                if (t == null || !seen.add(t)) return
                val message = t.message
                if (message != null) {
                    val lower = message.lowercase(Locale.US)
                    if (CAMERA_PIPE_MESSAGE_SIGNATURES.any { lower.contains(it) }) {
                        matchedMessage = true
                    }
                }
                if (t.stackTrace.any {
                        (it.className == "android.hardware.camera2.CameraManager" &&
                            it.methodName == "getCameraCharacteristics") ||
                        // CameraX capture-session teardown path — the SecurityException variant
                        // surfaces from Camera2's CaptureSession or CameraDeviceImpl when ARCore
                        // evicts CameraX mid-pipeline.
                        (it.className.startsWith("android.hardware.camera2.") &&
                            (it.methodName == "submitCaptureRequest" ||
                             it.methodName == "close" ||
                             it.methodName == "checkIfCameraClosedOrInError"))
                    }) {
                    matchedFrame = true
                }
                visit(t.cause)
                t.suppressed?.forEach { visit(it) }
            }

            visit(throwable)
            // Require BOTH a camera-pipe message AND a camera2 stack frame so an unrelated crash
            // that merely mentions one of these strings is never silently swallowed.
            return matchedMessage && matchedFrame
        }
    }
}
