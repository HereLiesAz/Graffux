package com.hereliesaz.graffitixr.data.azphalt.purchase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EntitlementVerifierTest {
    private val registry = TestRegistry()
    private val verifier = EntitlementVerifier { setOf(registry.publicKey) }
    private val now = Instant.parse("2026-10-28T00:00:00Z")

    private fun check(token: String, id: String, v: EntitlementVerifier = verifier) = v.verify(token, id, now)

    @Test fun `a registry-signed token for the package verifies`() {
        assertTrue(check(registry.token("com.example.paid"), "com.example.paid") is EntitlementCheck.Valid)
    }

    @Test fun `canonical claims match the registry's JSON stringify order`() {
        assertEquals(
            """{"packageId":"p","subject":"s","kind":"subscription","issuedAt":"i","expiresAt":"e"}""",
            EntitlementVerifier.canonicalClaims(EntitlementClaims("p", "s", "subscription", "i", "e")),
        )
    }

    @Test fun `wrong package, untrusted key, tampered claims and garbage are refused`() {
        assertTrue(check(registry.token("com.example.other"), "com.example.paid") is EntitlementCheck.Invalid)
        val untrusting = EntitlementVerifier { emptySet() }
        val unknownKey = check(registry.token("com.example.paid"), "com.example.paid", untrusting)
        assertTrue(unknownKey is EntitlementCheck.Invalid)
        val tampered = verifier.verify(registry.token("com.example.paid", tamper = true), "com.example.paid", now)
        assertTrue((tampered as EntitlementCheck.Invalid).reason.contains("signature"))
        assertTrue(verifier.verify("not base64 at all!", "com.example.paid", now) is EntitlementCheck.Invalid)
        // A self-signed token naming its own key: valid signature, but not the repository's key.
        val stranger = TestRegistry()
        assertTrue(check(stranger.token("com.example.paid"), "com.example.paid") is EntitlementCheck.Invalid)
    }

    @Test fun `a lapsed subscription is expired, a current one valid`() {
        val lapsed = registry.token("com.example.sub", expiresAt = "2026-10-01T00:00:00Z")
        val current = registry.token("com.example.sub", expiresAt = "2026-11-30T00:00:00Z")
        assertTrue(check(lapsed, "com.example.sub") is EntitlementCheck.Expired)
        assertTrue(check(current, "com.example.sub") is EntitlementCheck.Valid)
    }

    @Test fun `a one-time purchase never expires on the device, whatever the clock says`() {
        val perpetual = registry.token("com.example.paid")
        val farFuture = Instant.parse("2099-01-01T00:00:00Z")
        assertTrue(verifier.verify(perpetual, "com.example.paid", farFuture) is EntitlementCheck.Valid)
        val oddlyDated = registry.token("com.example.paid", expiresAt = "2026-01-01T00:00:00Z", kind = "perpetual")
        assertTrue(check(oddlyDated, "com.example.paid") is EntitlementCheck.Valid)
    }

    @Test fun `the kind claim is checked`() {
        val bogus = check(registry.token("com.example.paid", kind = "trial"), "com.example.paid")
        assertEquals("unknown licence kind", (bogus as EntitlementCheck.Invalid).reason)
        val noExpiry = check(registry.token("com.example.sub", kind = "subscription"), "com.example.sub")
        assertTrue(noExpiry is EntitlementCheck.Invalid)
    }

    @Test fun `an empty trust store says so rather than calling the token untrusted`() {
        val r = EntitlementVerifier { emptySet() }.verify(registry.token("com.example.paid"), "com.example.paid", now)
        assertEquals(EntitlementVerifier.NO_TRUSTED_KEYS, (r as EntitlementCheck.Invalid).reason)
    }

    @Test fun `only a 403 that says revoked is a revocation`() {
        assertTrue(isRevokedReply("""{"error":{"code":"revoked","message":"x"}}"""))
        assertTrue(isRevokedReply("""{"status":"revoked"}"""))
        assertTrue(!isRevokedReply("""{"error":{"code":"app_not_accepted"}}"""))
        assertTrue(!isRevokedReply("""{"error":{"code":"forbidden"}}"""))
        assertTrue(!isRevokedReply(""))
    }
}
