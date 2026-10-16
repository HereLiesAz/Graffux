package com.hereliesaz.graffux

import java.net.URI

/** Longest address the install confirmation prints in full; the host is always shown on its own line. */
internal const val MAX_INSTALL_URL_DISPLAY = 200

/**
 * A download address accepted from an `azphalt://install?url=` deep link, split into the part the
 * user must judge ([host]) and the full text ([url]).
 */
internal data class AzphaltInstallTarget(val url: String, val host: String)

/**
 * Validates the `url` query parameter of an `azphalt://install` link, or returns null.
 *
 * The link is reachable from any web page or app, so the address is held to: an absolute `https`
 * URL (cleartext and non-HTTP schemes would only fail later, noisily), with a real host, and no
 * `user@` part -- `https://trusted.example@evil.example/x` would otherwise display a trusted-looking
 * name while connecting to the host after the `@`. Anything that is not a well-formed URI (spaces,
 * newlines used to push the host off-screen) is rejected by [URI] itself.
 */
internal fun parseAzphaltInstallTarget(raw: String?): AzphaltInstallTarget? {
    val text = raw?.takeIf { it.isNotBlank() } ?: return null
    val uri = runCatching { URI(text) }.getOrNull()
    val host = uri?.host?.takeIf { it.isNotBlank() }
    val acceptable = uri != null && host != null &&
        uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null
    return if (acceptable && host != null) AzphaltInstallTarget(text, host.lowercase()) else null
}

/** [url] cut to [MAX_INSTALL_URL_DISPLAY] characters with an ellipsis, for the confirmation dialog. */
internal fun truncateInstallUrl(url: String): String =
    if (url.length <= MAX_INSTALL_URL_DISPLAY) url else url.take(MAX_INSTALL_URL_DISPLAY) + "…"
