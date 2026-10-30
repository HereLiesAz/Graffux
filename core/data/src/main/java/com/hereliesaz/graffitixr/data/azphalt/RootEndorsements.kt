package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.AzpSignatures
import com.hereliesaz.graffitixr.common.azphalt.TrustStore
import com.hereliesaz.graffitixr.common.azphalt.TrustedKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * The offline root this app pins (azphalt spec/repository-api.md § Root endorsements). azphalt.store
 * publishes its keys over TLS; TLS proves only who answered, so a key it publishes is trusted for a
 * job only when the owner's offline root has endorsed that exact key for that job. The root's private
 * half never touches a server: it is used offline by azphalt's `tools/root-sign.mjs`.
 */
object RootEndorsements {
    /** The owner's root public key: Ed25519, base64 SPKI DER. Rotating it means shipping a new build. */
    const val ROOT_PUBLIC_KEY_SPKI_B64 = "MCowBQYDK2VwAyEAjVS5e0Si26c3lzFyqnuW7VwMUmnwRF5AmBUBzYwrh6M="

    const val TYPE = "azphalt-root-endorsement"

    /** The key signs entitlement tokens ([purchase.EntitlementVerifier]). */
    const val PURPOSE_ENTITLEMENT = "entitlement"

    /** The key signs, or counter-signs, catalog packages (the package [TrustStore]). */
    const val PURPOSE_PACKAGE_CATALOG = "package-catalog"

    /**
     * The exact bytes the root signs: compact JSON with `type`, `v`, `purpose`, `publicKey`,
     * `notBefore`, then `notAfter` only when present, in that order. Matches `canonicalEndorsement`
     * in azphalt's `tools/root-sign.mjs`.
     */
    fun canonical(purpose: String, publicKey: String, notBefore: String, notAfter: String?): String =
        Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("type", TYPE)
                put("v", 1)
                put("purpose", purpose)
                put("publicKey", publicKey)
                put("notBefore", notBefore)
                notAfter?.let { put("notAfter", it) }
            },
        )

    /**
     * Null when [endorsement] is the root's endorsement of exactly [publicKey] for [purpose], valid at
     * [now]; otherwise why not. Missing, malformed, forged, cross-key, cross-purpose, not-yet-valid
     * and expired endorsements are all refusals.
     */
    fun refusal(
        endorsement: JsonObject?,
        publicKey: String,
        purpose: String,
        now: Instant,
        rootPublicKey: String = ROOT_PUBLIC_KEY_SPKI_B64,
    ): String? {
        if (endorsement == null) return "no root endorsement"
        val e = Parsed(endorsement)
        return when {
            e.type != TYPE || e.version != 1 -> "not a v1 root endorsement"
            e.publicKey != publicKey -> "endorsement is for another key"
            e.purpose != purpose -> "endorsed for ${e.purpose}, not $purpose"
            !e.wellFormed -> "malformed endorsement"
            !AzpSignatures.ed25519Verify(
                rootPublicKey,
                canonical(purpose, publicKey, e.notBefore!!, e.notAfter).toByteArray(Charsets.UTF_8),
                e.signature!!,
            ) -> "root signature does not verify"
            now.isBefore(e.start) -> "endorsement is not valid yet"
            e.end != null && !now.isBefore(e.end) -> "endorsement has expired"
            else -> null
        }
    }

    private class Parsed(private val o: JsonObject) {
        private fun text(name: String) = (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val type = text("type")
        val version = (o["v"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        val purpose = text("purpose")
        val publicKey = text("publicKey")
        val notBefore = text("notBefore")
        val notAfter = text("notAfter")
        val signature = text("signature")
        val start: Instant? = notBefore?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val end: Instant? = notAfter?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val wellFormed = start != null && signature != null && (!o.containsKey("notAfter") || end != null)
    }
}

/** One `signingKeys` entry as published, with its optional root endorsement. */
data class PublishedKey(val key: TrustedKey, val endorsement: JsonObject? = null)

/**
 * What the published keys may be used for. [packages] is the package trust store; [entitlementKeys]
 * are the keys an entitlement token may be signed by.
 */
data class KeyTrust(val packages: TrustStore, val entitlementKeys: Set<String>) {
    companion object {
        val EMPTY = KeyTrust(TrustStore.EMPTY, emptySet())

        /**
         * Sort [published] keys by purpose. With [enforceRoot] (every release build), a key serves a
         * purpose only with a valid root endorsement for it. Without it (a debug build opted out with
         * `-Pgraffux.azphaltRootEnforcement=false`, for testing against a store that has not deployed
         * endorsements yet), every published key serves both, as before endorsements existed.
         */
        fun from(
            published: List<PublishedKey>,
            enforceRoot: Boolean,
            now: Instant = Instant.now(),
            rootPublicKey: String = RootEndorsements.ROOT_PUBLIC_KEY_SPKI_B64,
        ): KeyTrust {
            if (!enforceRoot) {
                return KeyTrust(TrustStore(published.map { it.key }), published.map { it.key.publicKey }.toSet())
            }
            fun endorsedFor(purpose: String) = published.filter {
                RootEndorsements.refusal(it.endorsement, it.key.publicKey, purpose, now, rootPublicKey) == null
            }
            return KeyTrust(
                packages = TrustStore(endorsedFor(RootEndorsements.PURPOSE_PACKAGE_CATALOG).map { it.key }),
                entitlementKeys = endorsedFor(RootEndorsements.PURPOSE_ENTITLEMENT).map { it.key.publicKey }.toSet(),
            )
        }
    }
}
