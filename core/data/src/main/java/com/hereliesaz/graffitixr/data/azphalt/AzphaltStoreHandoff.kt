package com.hereliesaz.graffitixr.data.azphalt

import android.content.Intent
import android.content.pm.PackageManager
import com.hereliesaz.graffitixr.common.azphalt.AZPHALT_SPEC_VERSION

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

    /** MIME hint on the returned content URI (spec § MIME type) — advisory only, not for validation. */
    const val MIME: String = "application/vnd.azphalt.package"

    /** The media domains this host can actually use (spec/repository-api.md § Media domains), so the
     *  store never offers what Graffux structurally can't run — a pure audio or font pack doesn't
     *  match. Mirrors the filter [ExtensionRepository] used to pass its own catalog search. */
    private val mediaDomains = arrayOf("image", "3d", "video")

    /** The package kinds this host does something with: ASSET/MIXED contribute LUTs and brushes
     *  ([ExtensionRepository.installedLuts]/[installedBrushes]), CODE/MIXED run in the sandbox
     *  ([ExtensionRepository.executeCodeExtension]). `app`/`mcp`/`pack` have no consumer here yet. */
    private val kinds = arrayOf("asset", "code", "mixed")

    /** Builds the browse request (spec § The request) for this host's applicationId. */
    fun browseIntent(appId: String): Intent = Intent(ACTION_BROWSE).apply {
        putExtra(EXTRA_APP, appId)
        putExtra(EXTRA_MEDIA_DOMAINS, mediaDomains)
        putExtra(EXTRA_KINDS, kinds)
        putExtra(EXTRA_COMPAT, AZPHALT_SPEC_VERSION)
    }

    /**
     * True when at least one store app on the device can handle [browseIntent] (spec § Discovery).
     * `pm` is passed in rather than read off a `Context` here so this stays a plain, unit-testable
     * function — no Robolectric needed to exercise the intent-building half.
     */
    fun isStoreAvailable(pm: PackageManager, appId: String): Boolean =
        browseIntent(appId).resolveActivity(pm) != null
}
