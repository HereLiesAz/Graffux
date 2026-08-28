package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.AzphaltJson
import com.hereliesaz.graffitixr.common.azphalt.PackageDetail
import com.hereliesaz.graffitixr.common.azphalt.PackageSearchResponse
import com.hereliesaz.graffitixr.common.azphalt.PackageSummary
import com.hereliesaz.graffitixr.common.azphalt.RepositoryError
import com.hereliesaz.graffitixr.common.azphalt.RepositoryErrorEnvelope
import com.hereliesaz.graffitixr.common.azphalt.RepositoryIndex
import com.hereliesaz.graffitixr.common.azphalt.RevocationEntry
import com.hereliesaz.graffitixr.common.azphalt.RevocationsResponse
import com.hereliesaz.graffitixr.common.azphalt.UpdateAvailable
import com.hereliesaz.graffitixr.common.azphalt.UpdateRef
import com.hereliesaz.graffitixr.common.azphalt.UpdatesResponse
import kotlinx.serialization.builtins.ListSerializer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A client for the azphalt Repository API standard (spec/repository-api.md), talking directly to a
 * conforming registry — by default [AzphaltStoreHandoff.WEB_STORE_URL] — so Graffux can search, browse
 * and acquire extensions **itself** instead of only delegating that to a separate store app.
 *
 * This does not make Graffux a marketplace any more than a browser rendering a page makes it a web
 * server: nothing here hosts a catalog, takes a payment, or grants trust on the registry's say-so.
 * Every byte this fetches still goes through the identical [AzpInstaller] verification path any other
 * install source uses (a file the user picked, a store-app handoff, a web deep link) — a package's
 * `priceStatus`/rating/preview metadata is exactly as advisory here as a store app's
 * [AzphaltStoreHandoff] result is under the delegated route this client complements rather than
 * replaces (that handoff — and a real external store app — can still exist and still be offered).
 *
 * Modelled on the azphalt reference storefront's own Android network layer
 * (`github.com/HereLiesAz/azphalt`, `apps/storefront-cmp/src/androidMain/kotlin/network/AndroidApi.kt`)
 * rather than written from the spec text alone, cross-checked against the reference server
 * (`apps/repository-server/src/handler.ts`) for the exact wire shapes — including catching a real
 * mismatch in that reference client (it sends `kinds=`, the server and the normative spec both read
 * `kind=`; this client uses the verified-correct singular form).
 */
@Singleton
class RepositoryApiClient @Inject constructor() {

    /** A paid or private package's download requires a Bearer token this client did not have. */
    class UnauthorizedException(message: String) : IOException(message)

    /** A paid or private package's download was refused: no valid license for it. */
    class PaymentRequiredException(message: String) : IOException(message)

    /** Any other non-2xx response, carrying the repository's own error code when it sent one. */
    class RepositoryException(val statusCode: Int, val errorCode: String?, message: String) : IOException(message)

    /** A [download] result: the still-open, already-status-checked stream and its report token. */
    class DownloadResult(
        val stream: InputStream,
        /** `azphalt-report-token` header (spec/state-reporting.md § 4.2) — absent on a `206`, or when
         *  the repository keeps no install statistics. Unspent by this build; see [AzphaltStoreHandoff]. */
        val reportToken: String?,
    )

    fun discover(baseUrl: String = AzphaltStoreHandoff.WEB_STORE_URL): RepositoryIndex? =
        runCatching {
            get("$baseUrl/.well-known/azphalt-repository.json") { body ->
                AzphaltJson.decodeFromString(RepositoryIndex.serializer(), body)
            }
        }.getOrNull()

    /**
     * `GET /packages` (repository-api.md § 2), followed to its last page and flattened into one list.
     *
     * Not optional: `page` is the API's only pagination parameter, and the reference server pages at
     * 20 — a caller that reads just page 1 and calls it the catalogue previously undercounted a
     * hundred-plus-package registry down to twenty with nothing to say the rest existed. [MAX_PAGES]
     * only bounds a repository that never stops claiming another page.
     */
    fun search(
        baseUrl: String = AzphaltStoreHandoff.WEB_STORE_URL,
        query: String? = null,
        kind: List<String> = emptyList(),
        types: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        mediaDomains: List<String> = emptyList(),
        sort: String? = null,
        appId: String? = null,
    ): List<PackageSummary> {
        val params = buildList {
            query?.takeIf { it.isNotBlank() }?.let { add("q" to it) }
            if (kind.isNotEmpty()) add("kind" to kind.joinToString(","))
            if (types.isNotEmpty()) add("types" to types.joinToString(","))
            if (tags.isNotEmpty()) add("tags" to tags.joinToString(","))
            if (mediaDomains.isNotEmpty()) add("mediaDomains" to mediaDomains.joinToString(","))
            sort?.takeIf { it.isNotBlank() }?.let { add("sort" to it) }
            appId?.takeIf { it.isNotBlank() }?.let { add("app" to it) }
        }
        return fetchAllPages(baseUrl, params)
    }

    private fun fetchAllPages(baseUrl: String, params: List<Pair<String, String>>): List<PackageSummary> {
        val out = mutableListOf<PackageSummary>()
        var page = 1
        var pages = 1
        while (page <= pages && page <= MAX_PAGES) {
            val query = (params + ("page" to page.toString())).joinToString("&") { (k, v) -> "$k=${encode(v)}" }
            val response = get("$baseUrl/packages?$query") { body ->
                AzphaltJson.decodeFromString(PackageSearchResponse.serializer(), body)
            }
            pages = response.pages.coerceAtLeast(1)
            if (response.packages.isEmpty()) break
            out += response.packages
            page++
        }
        return out
    }

    /** `GET /packages/{id}` (repository-api.md § 3), or null on a 404. */
    fun packageDetail(id: String, baseUrl: String = AzphaltStoreHandoff.WEB_STORE_URL): PackageDetail? =
        try {
            get("$baseUrl/packages/${encodePathSegment(id)}") { body ->
                AzphaltJson.decodeFromString(PackageDetail.serializer(), body)
            }
        } catch (e: RepositoryException) {
            if (e.statusCode == 404) null else throw e
        }

    /** `GET /revocations` (repository-api.md § 5), optionally only entries after [since] (ISO-8601). */
    fun revocations(
        since: String? = null,
        baseUrl: String = AzphaltStoreHandoff.WEB_STORE_URL,
    ): List<RevocationEntry> {
        val url = "$baseUrl/revocations" + if (since.isNullOrBlank()) "" else "?since=${encode(since)}"
        return get(url) { body -> AzphaltJson.decodeFromString(RevocationsResponse.serializer(), body) }.revocations
    }

    /**
     * `POST /updates` (repository-api.md § 6): which of [installed] have a newer, non-yanked version.
     * Best-effort — a registry with nothing to say (including one that 404s/errors, or the endpoint
     * being genuinely optional) reads as "nothing new" rather than failing an entire browse session
     * over one non-essential call.
     */
    fun checkUpdates(
        installed: List<UpdateRef>,
        baseUrl: String = AzphaltStoreHandoff.WEB_STORE_URL,
    ): List<UpdateAvailable> {
        if (installed.isEmpty()) return emptyList()
        return runCatching {
            val body = AzphaltJson.encodeToString(ListSerializer(UpdateRef.serializer()), installed)
            post("$baseUrl/updates", body) { respBody ->
                AzphaltJson.decodeFromString(UpdatesResponse.serializer(), respBody)
            }.updates
        }.getOrDefault(emptyList())
    }

    /**
     * `GET /packages/{id}/versions/{version}/download` (repository-api.md § 4). [entitlementToken],
     * when non-null, is sent as `Authorization: Bearer <token>`; omitted entirely for a free package.
     * Throws [UnauthorizedException]/[PaymentRequiredException] on the paid/private gate so a caller
     * can route to a purchase flow instead of a generic failure.
     *
     * Returns the **live connection stream**, not buffered bytes: the caller feeds it straight to
     * [ExtensionRepository.installFromStream], which already does its own bounded streaming copy (the
     * zip-bomb guard every acquisition path shares) — buffering the whole package here first would
     * only hold it in memory twice. The bytes are, as always, unverified until that call returns.
     */
    fun download(
        id: String,
        version: String,
        entitlementToken: String? = null,
        baseUrl: String = AzphaltStoreHandoff.WEB_STORE_URL,
    ): DownloadResult {
        val url = URL("$baseUrl/packages/${encodePathSegment(id)}/versions/${encodePathSegment(version)}/download")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "${AzphaltStoreHandoff.MIME}, application/octet-stream")
        if (entitlementToken != null) connection.setRequestProperty("Authorization", "Bearer $entitlementToken")
        connection.connect()
        val code = connection.responseCode
        if (code == 200 || code == 206) {
            return DownloadResult(
                stream = connection.inputStream,
                reportToken = connection.getHeaderField("azphalt-report-token"),
            )
        }
        val err = errorEnvelope(connection)
        when (code) {
            401 -> throw UnauthorizedException(err?.message ?: "Authentication required")
            402 -> throw PaymentRequiredException(err?.message ?: "Payment required")
            else -> throw RepositoryException(code, err?.code, err?.message ?: "Download failed: HTTP $code")
        }
    }

    // ── Plumbing ──────────────────────────────────────────────────────────────────────────────

    /** For a query-string value (`?q=`, `?since=`, the joined `kind=a,b`-style params). */
    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun <T> get(url: String, parse: (String) -> T): T {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/json")
        connection.connect()
        val code = connection.responseCode
        if (code !in 200..299) {
            val err = errorEnvelope(connection)
            throw RepositoryException(code, err?.code, err?.message ?: "HTTP $code")
        }
        return parse(readBounded(connection.inputStream))
    }

    private fun <T> post(url: String, body: String, parse: (String) -> T): T {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.doOutput = true
        connection.requestMethod = "POST"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        if (code !in 200..299) {
            val err = errorEnvelope(connection)
            throw RepositoryException(code, err?.code, err?.message ?: "HTTP $code")
        }
        return parse(readBounded(connection.inputStream))
    }

    /** Bounded read guard for a JSON response body — a misbehaving/compromised registry streaming an
     *  unbounded body must not be able to grow this without limit, the same reasoning [ExtensionRepository]
     *  applies to its own package/trust-store downloads. */
    private fun readBounded(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        input.use { stream ->
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_JSON_BYTES) throw IOException("Response exceeds ${MAX_JSON_BYTES / (1024 * 1024)} MB")
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray().decodeToString()
    }

    /** Parse the normative `{"error":{"code","message"}}` envelope off a failed response, once. */
    private fun errorEnvelope(connection: HttpURLConnection): RepositoryError? = runCatching {
        val stream = connection.errorStream ?: return null
        AzphaltJson.decodeFromString(RepositoryErrorEnvelope.serializer(), readBounded(stream)).error
    }.getOrNull()

    companion object {
        private const val MAX_JSON_BYTES = 4 * 1024 * 1024
        private const val MAX_PAGES = 200

        /**
         * For a URL **path segment** (a package id/version embedded between slashes), NOT a query
         * value — [URLEncoder] is `application/x-www-form-urlencoded`, which encodes a space as `+`,
         * a character a path parser reads literally rather than decoding. An id or version containing
         * a space (nothing in spec/package-format.md forbids one) would otherwise request a
         * different, likely-404 path. `+` -> `%20` after [URLEncoder] gives correct path-segment
         * escaping without pulling in a separate URI-encoding dependency. Public — [AzphaltStoreHandoff]'s
         * own callers (e.g. building a web-checkout URL for a package id) need the exact same escaping.
         */
        fun encodePathSegment(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
