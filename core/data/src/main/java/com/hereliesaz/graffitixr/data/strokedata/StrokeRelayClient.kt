package com.hereliesaz.graffitixr.data.strokedata

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends one finished stroke-data session file to the stroke relay (workers/stroke-relay), which
 * commits it to the `stroke-data` branch. The relay holds the GitHub token, so the app carries no
 * credential and the user needs none. Callers delete a local file only after [upload] succeeds.
 *
 * Succeeds only when the relay itself says it stored the file or already had it: HTTP 200 or 201
 * with a JSON body whose `status` is `stored` or `exists`. A bare 200 is not enough, because a login
 * wall or captive portal in front of the relay answers 200 with a page. Redirects are never followed.
 * Anything else, from a rate limit to a missing branch to no network, is a failure and the file
 * stays on the device for the next attempt. See tools/stroke-model/README.md.
 */
class StrokeRelayClient(
    private val baseUrl: String = DEFAULT_URL,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun upload(device: String, fileName: String, bytes: ByteArray): Result<Unit> =
        withContext(io) {
            runCatching {
                val connection = URL("$baseUrl/v1/stroke-data/$device/$fileName").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "PUT"
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    connection.instanceFollowRedirects = false
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/gzip")
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                    val code = connection.responseCode
                    if (code in PERMANENT) throw RejectedException(fileName, code)
                    check(code == HTTP_OK || code == HTTP_CREATED) { "The stroke relay refused $fileName (HTTP $code)" }
                    val body = connection.inputStream.use { it.readBytes() }.decodeToString()
                    check(ACCEPTED.containsMatchIn(body)) {
                        "HTTP $code for $fileName did not come from the stroke relay"
                    }
                } finally {
                    connection.disconnect()
                }
            }
        }

    /** The relay will never accept this file (bad name, too large, not stroke data): don't retry it. */
    class RejectedException(fileName: String, val code: Int) :
        IllegalStateException("The stroke relay rejected $fileName for good (HTTP $code)")

    companion object {
        /** 400 bad name, 413 too large, 415 not stroke data: retrying the same bytes cannot succeed. */
        private val PERMANENT = setOf(400, 413, 415)
        const val DEFAULT_URL = "https://graffux.hereliesaz.workers.dev"
        private const val TIMEOUT_MS = 60_000
        private const val HTTP_OK = 200
        private const val HTTP_CREATED = 201
        private val ACCEPTED = Regex(""""status"\s*:\s*"(stored|exists)"""")
    }
}
