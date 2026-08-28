package com.hereliesaz.graffitixr.data.figma

import android.content.Context
import com.hereliesaz.graffitixr.common.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Upper bound on a Figma JSON response — a guard against an oversized/hostile reply. */
private const val MAX_RESPONSE_BYTES: Long = 32L * 1024 * 1024

/** Upper bound on a single rendered frame PNG. Figma caps exports at 32 megapixels. */
private const val MAX_IMAGE_BYTES: Long = 64L * 1024 * 1024

/**
 * The Graffux side of Figma: hold the user's personal access token, read a file's frames, and fetch
 * rendered PNGs of the frames they pick.
 *
 * **Authentication is a personal access token, not OAuth**, and that is a deliberate constraint
 * rather than a shortcut. Figma's OAuth token exchange requires HTTP Basic auth with the app's
 * client *secret* even when PKCE is used, and a secret shipped inside an APK is extractable by
 * anyone who downloads it. Completing OAuth safely needs a server to hold that secret; until one
 * exists, a token the user generates and revokes themselves is the honest option. [FigmaClient] takes
 * the auth header as data, so an OAuth bearer token can replace this without touching the API layer.
 */
@Singleton
class FigmaRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) {
    private val tokenStore = SecureTokenStore(context)

    private val _isAuthenticated = MutableStateFlow(tokenStore.load(SecureTokenStore.KEY_FIGMA_TOKEN) != null)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private fun client(): FigmaClient {
        val token = tokenStore.load(SecureTokenStore.KEY_FIGMA_TOKEN)
            ?: throw FigmaException("Not connected to Figma")
        return FigmaClient(token, ::httpGetString)
    }

    /**
     * Stores [token] only after it's been proven to work, so a typo surfaces as "that token didn't
     * work" at the point of entry rather than as a mysterious failure on the next import.
     */
    suspend fun connect(token: String): Result<FigmaUser> = withContext(dispatchers.io) {
        runCatching {
            val user = FigmaClient(token.trim(), ::httpGetString).me()
            tokenStore.save(SecureTokenStore.KEY_FIGMA_TOKEN, token.trim())
            _isAuthenticated.value = true
            user
        }
    }

    fun disconnect() {
        tokenStore.clear(SecureTokenStore.KEY_FIGMA_TOKEN)
        _isAuthenticated.value = false
    }

    /** Resolves a pasted file URL (or bare key) to that file's name and its importable frames. */
    suspend fun loadFile(urlOrKey: String): Result<FigmaFileContents> = withContext(dispatchers.io) {
        runCatching {
            val key = FigmaLinks.fileKey(urlOrKey)
                ?: throw FigmaException("That doesn't look like a Figma file link")
            val file = client().file(key)
            FigmaFileContents(
                fileKey = key,
                name = file.name.ifBlank { "Untitled" },
                frames = FigmaLinks.frames(file.document),
            )
        }
    }

    /**
     * Renders [frameIds] and downloads each PNG. Returns node id → bytes; a frame Figma declined to
     * render is simply absent rather than failing the whole import.
     */
    suspend fun renderFrames(
        fileKey: String,
        frameIds: List<String>,
        scale: Float = 2f,
    ): Result<Map<String, ByteArray>> = withContext(dispatchers.io) {
        runCatching {
            client().images(fileKey, frameIds, scale)
                .mapNotNull { (id, url) -> runCatching { id to download(url) }.getOrNull() }
                .toMap()
        }
    }

    /**
     * Blocking https GET returning the response body. Mirrors ExtensionRepository's transport: https
     * only, finite timeouts, bounded body. Figma's own errors arrive as non-2xx with a JSON body, so
     * the status is checked explicitly — otherwise a 403 would surface as a confusing parse failure.
     */
    private fun httpGetString(url: String, headers: Map<String, String>): String {
        if (!url.startsWith("https://")) throw FigmaException("Figma requires https: $url")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                // A revoked/expired token surfaces here as a 403 on the next real call, not at
                // connect() time -- nothing else ever un-sets isAuthenticated, so without this the
                // UI kept showing the connected file picker (FigmaWindow) indefinitely after the
                // token stopped working, instead of falling back to the connect screen.
                if (code == 403) _isAuthenticated.value = false
                throw FigmaException(describe(code))
            }
            val out = ByteArrayOutputStream()
            conn.inputStream.use { copyBounded(it, out, MAX_RESPONSE_BYTES) }
            return out.toByteArray().decodeToString()
        } finally {
            conn.disconnect()
        }
    }

    /** Rendered images live on a CDN and carry no auth header — the signed URL is the credential. */
    private fun download(url: String): ByteArray {
        if (!url.startsWith("https://")) throw FigmaException("Refusing non-https image URL")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "GET"
        }
        try {
            val out = ByteArrayOutputStream()
            conn.inputStream.use { copyBounded(it, out, MAX_IMAGE_BYTES) }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(code: Int): String = when (code) {
        403 -> "Figma rejected the token — check it hasn't expired and has file read access"
        404 -> "No such Figma file, or the token's account can't see it"
        429 -> "Figma is rate-limiting; try again shortly"
        else -> "Figma returned HTTP $code"
    }

    private fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long) {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) throw FigmaException("Response exceeded $maxBytes bytes")
            output.write(buffer, 0, read)
        }
    }
}

/** A resolved Figma file: what to show in the picker and what to render from. */
data class FigmaFileContents(
    val fileKey: String,
    val name: String,
    val frames: List<FigmaFrame>,
)
