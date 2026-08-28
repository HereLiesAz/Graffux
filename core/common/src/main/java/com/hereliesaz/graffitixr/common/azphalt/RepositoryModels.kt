package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.Serializable

/**
 * Wire models for the azphalt Repository API standard (azphalt spec/repository-api.md) — the HTTP/REST
 * contract any conforming backend (azphalt.store's reference registry, or a self-hosted one) exposes for
 * discovery, search, package detail, download and update-checking. [RepositoryApiClient] is the only
 * consumer that talks HTTP; everything here is a plain data mirror of the spec's JSON shapes, verified
 * field-for-field against the reference server (`@azphalt/repository-server`'s `handler.ts`) rather than
 * guessed from the markdown alone.
 *
 * Reuses [ExtensionKind]/[AssetType]/[Preview] from [AzphaltManifest] rather than re-declaring them: the
 * Repository API's `kind`/`types`/`preview` fields are the exact same wire vocabulary as a package's own
 * manifest, right down to sharing the "unknown value degrades to UNKNOWN rather than failing to parse"
 * policy those enums already implement.
 */

/** `GET /.well-known/azphalt-repository.json` — repository-api.md § 1. */
@Serializable
data class RepositoryIndex(
    val name: String? = null,
    val version: String? = null,
    val description: String? = null,
    val supportedTypes: List<AssetType> = emptyList(),
    val profiles: List<String> = emptyList(),
)

/**
 * Free or paid, straight off the wire (repository-api.md § 2 `priceStatus`). Kept as a plain wrapped
 * string rather than an enum with a serializer, unlike [ExtensionKind]/[AssetType]: nothing here reads a
 * third state, and the actual paywall is enforced server-side on download (§ 4) regardless of what this
 * field says — it is purely a display hint for whether a card should read *Get* or *Buy*.
 */
@Serializable
@JvmInline
value class PriceStatus(val wire: String) {
    val isPaid: Boolean get() = wire == "paid"

    companion object {
        val FREE = PriceStatus("free")
        val PAID = PriceStatus("paid")
    }
}

/** One row of `GET /packages` (repository-api.md § 2). */
@Serializable
data class PackageSummary(
    val id: String,
    val name: String,
    val nameLocalized: Map<String, String>? = null,
    val description: String? = null,
    val descriptionLocalized: Map<String, String>? = null,
    val author: String? = null,
    val version: String,
    /** Newest installable version; equals [version] in a summary (repository-api.md § `latest`). */
    val latest: String? = null,
    val kind: ExtensionKind = ExtensionKind.UNKNOWN,
    val types: List<AssetType> = emptyList(),
    val priceStatus: PriceStatus = PriceStatus.FREE,
    /** Non-empty = app-scoped (repository-api.md § App scoping); empty = visible to every host app. */
    val targetApps: List<String> = emptyList(),
    val downloads: Long = 0,
    val rating: Float? = null,
    val ratingCount: Int = 0,
    val updatedAt: String? = null,
    val byteSize: Long? = null,
    val mediaDomains: List<String> = emptyList(),
    val preview: Preview? = null,
) {
    /** The name to show for [locale] (e.g. "es-MX"), falling back BCP-47-region then flat [name]. */
    fun localizedName(locale: String?): String = pickLocalized(nameLocalized, locale) ?: name

    /** The description to show for [locale], same fallback as [localizedName], or null if there is none. */
    fun localizedDescription(locale: String?): String? = pickLocalized(descriptionLocalized, locale) ?: description
}

/** `es-MX` → exact match, then `es`, else null — repository-api.md § Localized strings. */
internal fun pickLocalized(map: Map<String, String>?, locale: String?): String? {
    if (map.isNullOrEmpty() || locale.isNullOrBlank()) return null
    map[locale]?.let { return it }
    val language = locale.substringBefore('-')
    return map[language]
}

/** `GET /packages` response envelope (repository-api.md § 2). */
@Serializable
data class PackageSearchResponse(
    val packages: List<PackageSummary> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pages: Int = 1,
)

/** One entry of `GET /packages/{id}`'s `versions[]` history (repository-api.md § 3). */
@Serializable
data class PackageVersionInfo(
    val version: String,
    val publishedAt: String? = null,
    val size: Long? = null,
    val digest: String? = null,
    val yanked: Boolean = false,
)

/**
 * `GET /packages/{id}` (repository-api.md § 3). [manifest] is the full `AzphaltManifest` of the newest
 * version — where a `kind:"pack"` package's member list actually lives — while the flat [name]/
 * [description]/[kind]/[types] mirror the summary shape so a caller need not reach into [manifest] for
 * the common case.
 */
@Serializable
data class PackageDetail(
    val id: String,
    val name: String,
    val author: String? = null,
    val description: String? = null,
    val nameLocalized: Map<String, String>? = null,
    val descriptionLocalized: Map<String, String>? = null,
    val version: String,
    val latest: String? = null,
    val kind: ExtensionKind = ExtensionKind.UNKNOWN,
    val types: List<AssetType> = emptyList(),
    val targetApps: List<String> = emptyList(),
    val priceStatus: PriceStatus = PriceStatus.FREE,
    val manifest: AzphaltManifest? = null,
    val versions: List<PackageVersionInfo> = emptyList(),
) {
    /** The version to actually download: [latest] when the repository named one, else [version]. */
    val installableVersion: String get() = latest ?: version
}

/** One entry of `GET /revocations` (repository-api.md § 5). */
@Serializable
data class RevocationEntry(
    val id: String,
    val version: String,
    val reason: String? = null,
    val revokedAt: String,
)

@Serializable
data class RevocationsResponse(
    val revocations: List<RevocationEntry> = emptyList(),
)

/** One row of the `POST /updates` request body (repository-api.md § 6). */
@Serializable
data class UpdateRef(
    val id: String,
    val version: String,
)

/** One row of the `POST /updates` response — present only when [id] has something newer. */
@Serializable
data class UpdateAvailable(
    val id: String,
    val latest: String,
)

@Serializable
data class UpdatesResponse(
    val updates: List<UpdateAvailable> = emptyList(),
)

/** The normative non-2xx error body every Repository API endpoint returns (repository-api.md § Error). */
@Serializable
data class RepositoryError(
    val code: String,
    val message: String,
)

@Serializable
data class RepositoryErrorEnvelope(
    val error: RepositoryError,
)
