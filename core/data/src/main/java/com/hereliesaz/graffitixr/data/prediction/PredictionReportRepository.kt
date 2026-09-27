package com.hereliesaz.graffitixr.data.prediction

import android.content.Context
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.data.figma.SecureTokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TEMPORARY. Files stroke-prediction rankings as GitHub issues on HereLiesAz/Graffux, using a token
 * the user pastes into Settings. Remove once the ranking has chosen the models.
 *
 * The token lives only on the device, encrypted with the same Keystore-backed [SecureTokenStore]
 * the Figma token uses -- never in the APK or the repo. Use a fine-grained token limited to this
 * repository with Issues: read and write. [connect] checks it against the repository before storing
 * it, so a typo fails at entry instead of silently on the next report.
 */
@Singleton
class PredictionReportRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) {
    private val tokenStore = SecureTokenStore(context)

    private val _isConnected = MutableStateFlow(tokenStore.load(KEY_TOKEN) != null)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    suspend fun connect(token: String): Result<Unit> = withContext(dispatchers.io) {
        runCatching {
            val trimmed = token.trim()
            require(trimmed.isNotEmpty()) { "Paste a token first" }
            val code = request("GET", REPO_URL, trimmed, null)
            check(code in HTTP_OK) { "GitHub rejected that token for HereLiesAz/Graffux (HTTP $code)" }
            tokenStore.save(KEY_TOKEN, trimmed)
            _isConnected.value = true
        }
    }

    fun disconnect() {
        tokenStore.clear(KEY_TOKEN)
        _isConnected.value = false
    }

    /** No-op success when no token is stored. */
    suspend fun fileIssue(title: String, body: String): Result<Unit> = withContext(dispatchers.io) {
        runCatching {
            val token = tokenStore.load(KEY_TOKEN) ?: return@runCatching
            val json = JSONObject().put("title", title).put("body", body).toString()
            val code = request("POST", "$REPO_URL/issues", token, json)
            if (code == HTTP_UNAUTHORIZED) disconnect()
            check(code in HTTP_OK) { "GitHub refused the ranking issue (HTTP $code)" }
        }
    }

    private fun request(method: String, url: String, token: String, json: String?): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (json != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(json.toByteArray()) }
            }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val KEY_TOKEN = "github_prediction_report_token"
        const val REPO_URL = "https://api.github.com/repos/HereLiesAz/Graffux"
        const val TIMEOUT_MS = 15_000
        const val HTTP_UNAUTHORIZED = 401
        val HTTP_OK = 200..299
    }
}
