package com.hereliesaz.graffitixr.data.azphalt.purchase

import com.hereliesaz.graffitixr.common.azphalt.AzpSignatures
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.Base64

/** The claims of a registry-signed entitlement (azphalt `apps/storefront-worker` § EntitlementClaims). */
@Serializable
data class EntitlementClaims(
    val packageId: String,
    val subject: String,
    val kind: String,
    val issuedAt: String,
    val expiresAt: String? = null,
    val keyId: String? = null,
)

/** The wire token: base64 of `{ claims, signature, publicKey }`. */
@Serializable
data class EntitlementToken(
    val claims: EntitlementClaims,
    /** Base64 Ed25519 signature over [canonicalClaims]. */
    val signature: String,
    /** Base64 SPKI of the signing key. */
    val publicKey: String,
)

sealed interface EntitlementCheck {
    data class Valid(val token: EntitlementToken) : EntitlementCheck
    /** Well-formed and signed by a trusted key, but past `expiresAt` (a lapsed subscription). */
    data class Expired(val token: EntitlementToken) : EntitlementCheck
    data class Invalid(val reason: String) : EntitlementCheck
}

/**
 * Verifies an entitlement ON THE DEVICE, whatever produced it: an in-app Play or Stripe purchase, a
 * purchase on azphalt.store, or a store app's handoff. All of them are signed by the same registry
 * key, so one check covers every route and none is privileged over another.
 *
 * The token is accepted only when its key is one of [trustedKeys] (the repository's published signing
 * keys, `/.well-known/azphalt-repository.json`), the signature verifies over the canonical claims,
 * and the claims name [packageId]. A token that names its own key is never trusted for that alone.
 */
class EntitlementVerifier(private val trustedKeys: () -> Set<String>) {

    fun decode(encoded: String): EntitlementToken? = runCatching {
        val json = String(Base64.getDecoder().decode(encoded.trim()), Charsets.UTF_8)
        LENIENT.decodeFromString(EntitlementToken.serializer(), json)
    }.getOrNull()

    fun verify(encoded: String, packageId: String, now: Instant = Instant.now()): EntitlementCheck {
        val token = decode(encoded) ?: return EntitlementCheck.Invalid("not an entitlement token")
        val expires = token.claims.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return when {
            token.claims.packageId != packageId -> EntitlementCheck.Invalid("entitlement is for another package")
            token.publicKey !in trustedKeys() ->
                EntitlementCheck.Invalid("entitlement is not signed by a trusted repository")
            !signatureVerifies(token) -> EntitlementCheck.Invalid("entitlement signature does not verify")
            token.claims.expiresAt != null && expires == null -> EntitlementCheck.Invalid("unreadable expiry")
            expires != null && !expires.isAfter(now) -> EntitlementCheck.Expired(token)
            else -> EntitlementCheck.Valid(token)
        }
    }

    private fun signatureVerifies(token: EntitlementToken): Boolean = runCatching {
        AzpSignatures.ed25519Verify(
            token.publicKey,
            canonicalClaims(token.claims).toByteArray(Charsets.UTF_8),
            token.signature,
        )
    }.getOrDefault(false)

    companion object {
        private val LENIENT = Json { ignoreUnknownKeys = true }

        /** The exact bytes the registry signs: JSON with fields in this order, absent optionals omitted. */
        fun canonicalClaims(c: EntitlementClaims): String = Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("packageId", c.packageId)
                put("subject", c.subject)
                put("kind", c.kind)
                put("issuedAt", c.issuedAt)
                c.expiresAt?.let { put("expiresAt", it) }
                c.keyId?.let { put("keyId", it) }
            },
        )
    }
}
