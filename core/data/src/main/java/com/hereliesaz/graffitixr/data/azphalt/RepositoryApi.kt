package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.AzphaltJson
import com.hereliesaz.graffitixr.common.azphalt.ExtensionKind
import com.hereliesaz.graffitixr.common.azphalt.PackManifest
import com.hereliesaz.graffitixr.common.azphalt.Preview
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.net.URLEncoder

/** The official azphalt storefront (https://azphalt.store) and its registry API. */
object AzphaltStore {
    /**
     * Registry API base for the official store. The apex `azphalt.store` 308-redirects to `www`, so we
     * target `www` directly. The store currently serves a bare package array at `GET /packages`
     * (see [RepositoryClient.listPackages]); the richer repository-api.md endpoints may not exist yet.
     */
    const val REGISTRY_BASE_URL: String = "https://www.azphalt.store/api"
}

/**
 * Client + models for an azphalt registry (spec/repository-api.md, format 0.1). A registry lets
 * GraffitiXR browse and download extensions from a live server instead of the bundled seed catalog.
 *
 * Transport-agnostic on purpose: [httpGet]/[httpPost] perform the actual I/O, so this is trivially
 * unit-testable and reuses whatever HTTP stack the caller already has (GraffitiXR has no Retrofit; the
 * caller can pass a tiny HttpURLConnection lambda). Each receives the URL and the request headers (e.g.
 * an Authorization bearer for paid packages) and returns the response body; [httpPost] also gets the
 * request body. [httpPost] defaults to a stub that throws — only [checkUpdates] needs it, so a browse-
 * /install-only caller can keep passing just a GET lambda.
 */
class RepositoryClient(
    baseUrl: String,
    private val httpGet: (url: String, headers: Map<String, String>) -> String,
    private val httpPost: (url: String, body: String, headers: Map<String, String>) -> String =
        { url, _, _ -> throw UnsupportedOperationException("No POST transport configured for $url") },
) {
    private val base: String = baseUrl.trimEnd('/')

    /** GET /.well-known/azphalt-repository.json — registry identity and capabilities. */
    fun discover(): RepositoryInfo =
        AzphaltJson.decodeFromString(httpGet("$base/.well-known/azphalt-repository.json", emptyMap()))

    /**
     * GET /packages — paginated search. [types] filters by contribution type (e.g. "lut", "shader",
     * "code"); [tags] by free-form tag. Empty filters return the full catalog page.
     */
    fun search(
        q: String? = null,
        types: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        page: Int = 1,
    ): SearchResponse {
        val params = buildList {
            if (!q.isNullOrBlank()) add("q=" + enc(q))
            if (types.isNotEmpty()) add("types=" + enc(types.joinToString(",")))
            if (tags.isNotEmpty()) add("tags=" + enc(tags.joinToString(",")))
            add("page=$page")
        }.joinToString("&")
        return AzphaltJson.decodeFromString(httpGet("$base/packages?$params", emptyMap()))
    }

    /**
     * GET /packages returning the registry's package list as a **bare JSON array** — the shape the
     * official azphalt store serves (it returns the whole catalog and ignores paging). [search] is for
     * registries that implement the paginated `{packages,total,page,pages}` envelope from
     * repository-api.md; this is for the flat-array store. An optional [q] is passed through for
     * registries that filter, and harmlessly ignored by ones that don't.
     */
    fun listPackages(q: String? = null): List<RepositoryPackage> {
        val url = if (q.isNullOrBlank()) "$base/packages" else "$base/packages?q=" + enc(q)
        return AzphaltJson.decodeFromString(httpGet(url, emptyMap()))
    }

    /** GET /packages/{id} — full detail and version history. */
    fun detail(id: String): PackageDetail =
        AzphaltJson.decodeFromString(httpGet("$base/packages/${enc(id)}", emptyMap()))

    /**
     * The download URL for a specific version's `.azp`. Handed to [ExtensionRepository.install] as the
     * entry [MarketplaceEntry.source]; paid packages need an Authorization bearer at fetch time
     * (the server answers 401 without a token, 402 without a license).
     */
    fun downloadUrl(id: String, version: String): String =
        "$base/packages/${enc(id)}/versions/${enc(version)}/download"

    /**
     * The member list of an extension pack (spec/pack.md § Discovery & installation → `getPack`). Reads
     * the pack's manifest `pack` block off [detail]. Returns null when the package carries no `pack`
     * block (i.e. it isn't a pack).
     */
    fun getPack(id: String): PackManifest? = detail(id).pack

    /**
     * Resolve an extension pack's members (spec/pack.md § Discovery & installation → `resolvePack`).
     * For each entry this pins a concrete [ResolvedPackMember.version] — the entry's pinned version when
     * present, else the member's newest published version — and reports the member's own free/paid
     * status, because a member honors its **own** entitlement gate even when the pack itself is free.
     * The [PackEntry.required]/[PackEntry.note] curation flags are carried through so a host can install
     * the base set and merely offer the recommended rest.
     *
     * A member whose id doesn't resolve (not yet in this registry, or lives in another repository — the
     * spec resolves members lazily) is skipped rather than failing the whole pack; its id is returned in
     * [ResolvedPack.unresolved] so the host can surface or defer it.
     */
    fun resolvePack(id: String): ResolvedPack {
        val pack = getPack(id) ?: return ResolvedPack(emptyList(), emptyList())
        val members = ArrayList<ResolvedPackMember>()
        val unresolved = ArrayList<String>()
        for (entry in pack.entries) {
            val memberDetail = try {
                detail(entry.id)
            } catch (_: Exception) {
                unresolved.add(entry.id)
                continue
            }
            val version = entry.version ?: memberDetail.versions.firstOrNull()
            if (version == null) {
                unresolved.add(entry.id)
                continue
            }
            members.add(
                ResolvedPackMember(
                    id = entry.id,
                    version = version,
                    required = entry.required,
                    note = entry.note,
                    paid = memberDetail.priceStatus.equals("paid", ignoreCase = true),
                    downloadUrl = downloadUrl(entry.id, version),
                ),
            )
        }
        return ResolvedPack(members, unresolved)
    }

    /**
     * GET /revocations — the registry's revocation feed (spec/repository-api.md § Revocations). Lists
     * package versions the registry has pulled (malware, license violation, …). A host consults this
     * to warn on or disable an installed extension whose version has since been revoked.
     */
    fun revocations(): RevocationsFeed =
        AzphaltJson.decodeFromString(httpGet("$base/revocations", emptyMap()))

    /**
     * POST /updates — batch update check (spec/repository-api.md § Updates). Sends the ids+versions the
     * host has installed; the registry answers only those with a newer release. Requires a POST
     * transport (see [httpPost]).
     */
    fun checkUpdates(installed: List<UpdateQuery>): UpdatesResponse {
        // Nothing installed → nothing to check; skip the network round-trip entirely.
        if (installed.isEmpty()) return UpdatesResponse()
        val body = AzphaltJson.encodeToString(installed)
        return AzphaltJson.decodeFromString(
            httpPost("$base/updates", body, mapOf("Content-Type" to "application/json")),
        )
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/**
 * GET /.well-known/azphalt-repository.json — registry identity and capabilities. [version] is the
 * repository-API format the registry speaks; [signingKeys] seed a host's trust store so packages the
 * registry counter-signs verify as trusted. Extra/unknown fields are ignored (lenient JSON).
 */
@Serializable
data class RepositoryInfo(
    val name: String,
    val version: String,
    val description: String? = null,
    val auth: RepositoryAuth? = null,
    /** Contribution types this registry serves (e.g. "audio", "lut"); advisory. */
    val supportedTypes: List<String> = emptyList(),
    /** Host-app profiles this registry targets (spec profiles), e.g. "video-audio". */
    val profiles: List<String> = emptyList(),
    val signingKeys: List<RepositorySigningKey> = emptyList(),
)

/** The registry's auth protocol (spec/repository-api.md), e.g. `{ "type": "oauth2", "url": … }`. */
@Serializable
data class RepositoryAuth(
    val type: String,
    val url: String? = null,
)

/** An Ed25519 public key the registry signs (or counter-signs) packages with. */
@Serializable
data class RepositorySigningKey(
    val publicKey: String,
    val keyId: String? = null,
    val label: String? = null,
)

/** GET /revocations — the revocation feed body. */
@Serializable
data class RevocationsFeed(
    val revocations: List<Revocation> = emptyList(),
)

/** One revoked package version (spec/repository-api.md § Revocations). */
@Serializable
data class Revocation(
    val id: String,
    val version: String,
    val reason: String? = null,
    val revokedAt: String? = null,
)

/** One installed (id, version) to check for a newer release. POST /updates request element. */
@Serializable
data class UpdateQuery(
    val id: String,
    val version: String,
)

/** POST /updates response — only ids that have a newer [UpdateInfo.latest] than the one sent. */
@Serializable
data class UpdatesResponse(
    val updates: List<UpdateInfo> = emptyList(),
)

/** A package with a newer version available (spec/repository-api.md § Updates). */
@Serializable
data class UpdateInfo(
    val id: String,
    val latest: String,
)

/**
 * A package as it appears in a registry search result or detail response. Fields cover both the
 * repository-api.md envelope and the official store's flat `/packages` objects (which additionally
 * carry [kind], [downloads], [ratingCount], [updatedAt], [mediaDomains] and a nullable [price]).
 */
@Serializable
data class RepositoryPackage(
    val id: String,
    val name: String,
    val author: String? = null,
    val version: String,
    /** Declared package kind when the registry provides it; otherwise inferred from [types]. */
    val kind: ExtensionKind? = null,
    val types: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val description: String? = null,
    /** "free" or "paid"; absent is treated as free. */
    val priceStatus: String? = null,
    /** Official store's price: `null` (or JSON null) means free; any other value means paid. */
    val price: JsonElement? = null,
    val downloads: Int = 0,
    val ratingCount: Int = 0,
    val updatedAt: String? = null,
    val mediaDomains: List<String> = emptyList(),
    val targetApps: List<String> = emptyList(),
    /** Store-card preview (spec/extension-manifest.md § preview) — a browse grid without downloading. */
    val preview: Preview? = null,
)

/** GET /packages — paginated. */
@Serializable
data class SearchResponse(
    val packages: List<RepositoryPackage> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pages: Int = 1,
)

/** GET /packages/{id} — detail plus the version list (newest resolvable via [versions]). */
@Serializable
data class PackageDetail(
    val id: String,
    val name: String,
    val author: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val priceStatus: String? = null,
    val preview: Preview? = null,
    val versions: List<String> = emptyList(),
    /**
     * For a `kind: "pack"` package, its member manifest (spec/pack.md). Absent/null for a normal
     * package. Surfaced to hosts via [RepositoryClient.getPack]/[RepositoryClient.resolvePack].
     */
    val pack: PackManifest? = null,
)

/**
 * The result of [RepositoryClient.resolvePack]: a pack's members resolved to concrete versions, plus
 * the ids that couldn't be resolved against this registry (a host may retry them elsewhere or defer).
 */
data class ResolvedPack(
    val entries: List<ResolvedPackMember>,
    val unresolved: List<String> = emptyList(),
)

/**
 * One resolved member of an extension pack (spec/pack.md § Discovery & installation). [version] is the
 * concrete version to download (the entry's pin, or the member's latest). [paid] mirrors the member's
 * own entitlement gate — a free pack can list a paid member, and that member still needs its own
 * license at download time. [required] distinguishes the base set (install by default) from the
 * recommended rest (offer to the user). [downloadUrl] is the ready-to-fetch `.azp` URL.
 */
data class ResolvedPackMember(
    val id: String,
    val version: String,
    val required: Boolean,
    val note: String? = null,
    val paid: Boolean,
    val downloadUrl: String,
)

/** True when this package requires payment/entitlement to download. */
val RepositoryPackage.isPaid: Boolean
    get() = priceStatus.equals("paid", ignoreCase = true) || (price != null && price !is JsonNull)

/**
 * Map a registry package to a GraffitiXR catalog card. [downloadUrl] is the resolved `.azp` URL the
 * installer fetches (see [RepositoryClient.downloadUrl]). Kind uses the package's declared [kind] when
 * the registry provides one; otherwise it's inferred from [types]: a `code` type alone is CODE, `code`
 * alongside asset types is MIXED, anything else is ASSET.
 */
fun RepositoryPackage.toMarketplaceEntry(downloadUrl: String): MarketplaceEntry {
    val resolvedKind = kind ?: run {
        val hasCode = types.any { it.equals("code", ignoreCase = true) }
        val hasAsset = types.any { !it.equals("code", ignoreCase = true) }
        when {
            hasCode && hasAsset -> ExtensionKind.MIXED
            hasCode -> ExtensionKind.CODE
            else -> ExtensionKind.ASSET
        }
    }
    return MarketplaceEntry(
        id = id,
        name = name,
        kind = resolvedKind,
        author = author ?: "",
        description = description ?: "",
        priceLabel = if (isPaid) "Paid" else "Free",
        downloads = downloads,
        rating = null,
        tags = tags,
        source = downloadUrl,
        previewImage = preview?.image,
    )
}
