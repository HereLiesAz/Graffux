package com.hereliesaz.graffitixr.data.azphalt

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.hereliesaz.graffitixr.common.azphalt.AZPHALT_SPEC_VERSION
import com.hereliesaz.graffitixr.common.azphalt.ExtensionInventory
import com.hereliesaz.graffitixr.common.azphalt.ExtensionStateEntry

/**
 * The acquisition handoff from azphalt spec/store-app.md: rather than building its own browse/search
 * catalog, a host launches a separate, installable store app to find and fetch a package, then
 * verifies the bytes it gets back itself — the same [AzpInstaller] path any other install source goes
 * through, since "the store app saves the host work, never judgement" (spec § What this is not).
 *
 * These constants mirror the reference storefront app's own `store.azphalt.storefront.Handoff` object
 * (github.com/HereLiesAz/azphalt, `apps/storefront-cmp`) so the two sides agree on the wire contract
 * without sharing code — any conforming store app, not just the reference one, understands them.
 */
object AzphaltStoreHandoff {
    const val ACTION_BROWSE: String = "store.azphalt.action.BROWSE"

    private const val EXTRA_APP: String = "app"
    private const val EXTRA_MEDIA_DOMAINS: String = "mediaDomains"
    private const val EXTRA_KINDS: String = "kinds"
    private const val EXTRA_COMPAT: String = "compat"

    /** This host's extension states, as a JSON document (spec/state-reporting.md § 3.1). A JSON
     *  string rather than a `Parcelable` because the extra crosses an application boundary: a custom
     *  Parcelable needs a class on both sides, which would make the contract a shared library instead
     *  of a document. */
    private const val EXTRA_INVENTORY: String = "azphalt.extra.INVENTORY"

    /** Authority of this host's state provider, so a store opened on its own can re-read state later
     *  (spec § 3.2). A store MUST NOT query an authority it was not given. */
    private const val EXTRA_STATE_AUTHORITY: String = "azphalt.extra.STATE_AUTHORITY"

    // ── Result extras (spec/store-app.md § The result) ────────────────────────────────────────────
    /** Package id of the delivered bytes. */
    const val EXTRA_ID: String = "azphalt.extra.ID"

    /** The exact version delivered. */
    const val EXTRA_VERSION: String = "azphalt.extra.VERSION"

    /** `sha256-…` over the unsigned package. */
    const val EXTRA_INTEGRITY: String = "azphalt.extra.INTEGRITY"

    /** Whether the package carried a `signature.json` that verified — advisory; this host re-verifies. */
    const val EXTRA_SIGNED: String = "azphalt.extra.SIGNED"

    /** Base64 SPKI of the signer's key, when signed. */
    const val EXTRA_SIGNER_KEY: String = "azphalt.extra.SIGNER_KEY"

    /** Registry-signed entitlement token for a paid package. */
    const val EXTRA_ENTITLEMENT: String = "azphalt.extra.ENTITLEMENT"

    /**
     * The `azphalt-report-token` from the download that produced these bytes (spec § The result;
     * state-reporting.md § 4.2). It travels with the package because the store *downloaded* and the
     * host *installs*: the token authorises exactly one install report, and only the host knows
     * whether an install actually happened. Left unspent, the repository's count simply stays honest.
     */
    const val EXTRA_REPORT_TOKEN: String = "azphalt.extra.REPORT_TOKEN"

    /** MIME hint on the returned content URI (spec § MIME type) — advisory only, not for validation. */
    const val MIME: String = "application/vnd.azphalt.package"

    /**
     * The neutral marketplace on the web. One repository, not the only one — the Repository API is
     * self-hostable — but it is the one this host offers by name.
     *
     * The web route matters because the Android one can be absent: a host with no store app installed
     * is "a host with no *browse* affordance, not a broken one" (spec § Discovery), and a browser is
     * the affordance every device already has.
     *
     * The canonical `www.` host, not the bare domain: the bare domain 308-redirects here, and while a
     * browser (this constant's own [webStoreIntent] use, and the checkout links built from it) follows
     * that transparently, [com.hereliesaz.graffitixr.data.azphalt.RepositoryApiClient]'s raw
     * `HttpURLConnection` calls do not — `HttpURLConnection` only auto-follows 301/302/303, never
     * 307/308 — so every direct API call (search, discover, updates, download) hit the bare domain and
     * failed on the raw redirect status instead of ever reaching the API.
     */
    const val WEB_STORE_URL: String = "https://www.azphalt.store"

    /** Application id of the reference Android storefront, for offering to install it when absent. */
    const val STORE_APP_ID: String = "store.azphalt.storefront"

    /** The web storefront, as an intent for a browser. */
    fun webStoreIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(WEB_STORE_URL))

    /**
     * Where to send someone who chose the Android store and does not have it.
     *
     * Play first, by its `market:` scheme, which opens the Play app directly when it is present; the
     * caller falls back to [webStoreIntent] when nothing resolves this, since a device without Play
     * is exactly the device where a dead button would be most annoying.
     */
    fun installStoreAppIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$STORE_APP_ID"))

    /** The media domains this host can actually use (spec/repository-api.md § Media domains), so a
     *  store — the delegated one, or [RepositoryApiClient]'s own in-app search — never offers what
     *  Graffux structurally can't run: a pure audio or font pack doesn't match. Shared rather than
     *  declared twice, so the delegated and in-app routes can never quietly disagree about it. */
    val mediaDomains: List<String> = listOf("image", "3d", "video")

    /** The package kinds this host does something with: ASSET/MIXED contribute LUTs and brushes
     *  ([ExtensionRepository.installedLuts]/[installedBrushes]), CODE/MIXED run in the sandbox
     *  ([ExtensionRepository.executeCodeExtension]). `app`/`mcp`/`pack` have no consumer here yet. */
    val kinds: List<String> = listOf("asset", "code", "mixed")

    /**
     * Builds the browse request (spec § The request) for this host's applicationId.
     *
     * [inventory] is what this host has done with the extensions it already holds
     * (spec/state-reporting.md § 3.1). Sending it is what lets a store card read *Open* instead of
     * *Get* on something the user already has — the store cannot work that out on its own, because
     * acquisition and installation are separate events and only the host saw the second one. It is
     * omitted when empty rather than sent as an empty document, so a host with nothing installed says
     * nothing rather than asserting an empty inventory.
     */
    fun browseIntent(
        appId: String,
        inventory: List<ExtensionStateEntry> = emptyList(),
    ): Intent = Intent(ACTION_BROWSE).apply {
        putExtra(EXTRA_APP, appId)
        putExtra(EXTRA_MEDIA_DOMAINS, mediaDomains.toTypedArray())
        putExtra(EXTRA_KINDS, kinds.toTypedArray())
        putExtra(EXTRA_COMPAT, AZPHALT_SPEC_VERSION)
        if (inventory.isNotEmpty()) {
            // Gate on what the document actually carries, not on what went in. Trimming can empty a
            // non-empty inventory, and an empty document is not the same claim as no document: the
            // first asserts "this host has nothing", which is a lie the store will act on.
            val document = ExtensionInventory.document(inventory)
            if (ExtensionInventory.parse(document).isNotEmpty()) {
                putExtra(EXTRA_INVENTORY, document)
            }
            // The authority goes out regardless, because it is the answer to the case the inventory
            // extra cannot cover: the provider has no size limit, so a host whose inventory did not
            // fit is exactly the host a store most needs to come back and read.
            putExtra(EXTRA_STATE_AUTHORITY, ExtensionStateProvider.authority(appId))
        }
    }

    /**
     * True when at least one store app on the device can handle [browseIntent] (spec § Discovery).
     * `pm` is passed in rather than read off a `Context` here so this stays a plain, unit-testable
     * function — no Robolectric needed to exercise the intent-building half.
     */
    fun isStoreAvailable(pm: PackageManager, appId: String): Boolean =
        browseIntent(appId).resolveActivity(pm) != null

    /**
     * What a store app told this host about the bytes it returned (spec § The result).
     *
     * Every field is **advisory**. The store saves the host work, never judgement, so none of this
     * relaxes a check: the package is verified here exactly as a file the user picked by hand would
     * be, and a store claiming `signed = true` over bytes whose signature does not verify simply gets
     * refused. What the metadata is actually for is the things the bytes cannot say on their own —
     * naming a package in a failure report before its manifest has been parsed, and carrying the
     * report token.
     */
    data class StoreResult(
        val id: String? = null,
        val version: String? = null,
        val integrity: String? = null,
        val signed: Boolean? = null,
        val signerKey: String? = null,
        val entitlement: String? = null,
        /** Spendable exactly once, by the host, to report one install (state-reporting.md § 4.2). */
        val reportToken: String? = null,
    )

    /** Read the result extras a conforming store sets alongside the returned package. */
    fun resultOf(intent: Intent?): StoreResult {
        if (intent == null) return StoreResult()
        return StoreResult(
            id = intent.getStringExtra(EXTRA_ID),
            version = intent.getStringExtra(EXTRA_VERSION),
            integrity = intent.getStringExtra(EXTRA_INTEGRITY),
            signed = if (intent.hasExtra(EXTRA_SIGNED)) intent.getBooleanExtra(EXTRA_SIGNED, false) else null,
            signerKey = intent.getStringExtra(EXTRA_SIGNER_KEY),
            entitlement = intent.getStringExtra(EXTRA_ENTITLEMENT),
            reportToken = intent.getStringExtra(EXTRA_REPORT_TOKEN),
        )
    }

    /**
     * Every package the store returned, in order.
     *
     * A browse request may ask for `multiple`, and the spec answers it with **one `ClipData` item per
     * package**. Reading only `intent.data` therefore installs the first selection and silently drops
     * the rest — the user picks five brushes and gets one, with nothing to say the other four were
     * discarded. `ClipData` takes precedence when present; `data` is the single-selection form.
     */
    fun packageUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val clip = intent.clipData
        if (clip != null && clip.itemCount > 0) {
            return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri }
        }
        return listOfNotNull(intent.data)
    }
}
