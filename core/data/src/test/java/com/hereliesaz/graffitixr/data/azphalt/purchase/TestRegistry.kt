package com.hereliesaz.graffitixr.data.azphalt.purchase

import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import java.security.SecureRandom
import java.util.Base64

/** Mints tokens exactly as the azphalt registry does: Ed25519 over the canonical claims, SPKI key. */
class TestRegistry {
    private val key = Ed25519PrivateKeyParameters(SecureRandom())
    val publicKey: String = Base64.getEncoder().encodeToString(
        SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(key.generatePublicKey()).encoded,
    )

    fun token(
        packageId: String,
        subject: String = "buyer_x",
        expiresAt: String? = null,
        tamper: Boolean = false,
    ): String {
        val kind = if (expiresAt != null) "subscription" else "perpetual"
        val claims = EntitlementClaims(packageId, subject, kind, "2026-10-01T00:00:00.000Z", expiresAt)
        val signer = Ed25519Signer().apply { init(true, key) }
        val bytes = EntitlementVerifier.canonicalClaims(claims).toByteArray()
        signer.update(bytes, 0, bytes.size)
        val sig = Base64.getEncoder().encodeToString(signer.generateSignature())
        val signed = if (tamper) claims.copy(subject = "someone_else") else claims
        val json = Json.encodeToString(EntitlementToken.serializer(), EntitlementToken(signed, sig, publicKey))
        return Base64.getEncoder().encodeToString(json.toByteArray())
    }
}
