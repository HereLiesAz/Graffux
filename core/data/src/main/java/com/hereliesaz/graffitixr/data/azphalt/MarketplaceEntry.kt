package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.ExtensionKind

/**
 * A marketplace catalog card. In a deployed setup these come from the azphalt registry HTTP API;
 * here the seed is bundled so the feature works offline. [source] tells the installer where to fetch
 * the `.azp` from — a bundled asset (`asset:...`) or an `https:` URL.
 */
data class MarketplaceEntry(
    val id: String,
    val name: String,
    val kind: ExtensionKind,
    val author: String,
    val description: String,
    val priceLabel: String,
    val downloads: Int,
    val rating: Float?,
    val tags: List<String>,
    /** Where the .azp lives: "asset:marketplace/foo.azp" (bundled) or "https://…". */
    val source: String,
    /** Optional store-card preview image (spec preview.image): in-package path or `https:` URL. */
    val previewImage: String? = null,
) {
    /**
     * True when GraffitiXR can install this today. As an asset-only host it takes `asset` and `mixed`
     * packages (a mixed package installs, but only its asset contributions are used). Pure `code`
     * extensions need the JS/WASM sandbox (not here yet), and the `app`/`mcp` host-integration kinds
     * this host doesn't run at all.
     */
    val installable: Boolean get() = kind == ExtensionKind.ASSET || kind == ExtensionKind.MIXED
}
