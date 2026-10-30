package com.hereliesaz.graffitixr.data.azphalt

import android.content.Context
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.azphalt.TrustedKey
import com.hereliesaz.graffitixr.data.azphalt.purchase.EntitlementCheck
import com.hereliesaz.graffitixr.data.azphalt.purchase.EntitlementVerifier
import com.hereliesaz.graffitixr.data.azphalt.purchase.TestRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/** Root endorsements, with a throwaway root generated per run (never the owner's). */
class RootEndorsementsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Root {
        val key = Ed25519PrivateKeyParameters(SecureRandom())
        val publicKey: String = Base64.getEncoder().encodeToString(
            SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(key.generatePublicKey()).encoded,
        )

        fun endorse(
            publicKey: String,
            purpose: String,
            notBefore: String = "2026-01-01T00:00:00Z",
            notAfter: String? = null,
        ): JsonObject {
            val bytes = RootEndorsements.canonical(purpose, publicKey, notBefore, notAfter).toByteArray()
            val signer = Ed25519Signer().apply { init(true, key) }
            signer.update(bytes, 0, bytes.size)
            return buildJsonObject {
                put("type", RootEndorsements.TYPE)
                put("v", 1)
                put("purpose", purpose)
                put("publicKey", publicKey)
                put("notBefore", notBefore)
                notAfter?.let { put("notAfter", it) }
                put("signature", Base64.getEncoder().encodeToString(signer.generateSignature()))
            }
        }
    }

    private val root = Root()
    private val store = TestRegistry()
    private val now = Instant.parse("2027-01-01T00:00:00Z")
    private val ent = RootEndorsements.PURPOSE_ENTITLEMENT
    private val pkg = RootEndorsements.PURPOSE_PACKAGE_CATALOG

    private fun refusal(e: JsonObject?, purpose: String = ent, at: Instant = now) =
        RootEndorsements.refusal(e, store.publicKey, purpose, at, root.publicKey)

    @Test
    fun `canonical statement matches the azphalt tool byte for byte`() {
        assertEquals(
            """{"type":"azphalt-root-endorsement","v":1,"purpose":"package-catalog",""" +
                """"publicKey":"K","notBefore":"2026-01-01T00:00:00Z"}""",
            RootEndorsements.canonical(pkg, "K", "2026-01-01T00:00:00Z", null),
        )
    }

    @Test
    fun `an endorsement printed by azphalt's tools root-sign mjs verifies here`() {
        // Produced by signEndorsement in tools/root-sign.mjs with a throwaway root.
        val testRoot = "MCowBQYDK2VwAyEAVtnKRGwMp0umPOLQQc0Oarq0ruOs/Dh2yOZk33QvTA0="
        val e = Json.parseToJsonElement(
            """{"type":"azphalt-root-endorsement","v":1,"purpose":"entitlement",""" +
                """"publicKey":"MCowBQYDK2VwAyEAZ07NQrZZHzIULLinP9an/WwvD8dCi/vbbCzni0oey10=",""" +
                """"notBefore":"2026-10-30T00:00:00Z","notAfter":"2028-10-30T00:00:00Z",""" +
                """"signature":"Y53JjsZ5vTlt1AJqljqlRSaw3/lMQnUCXKuzCiCsSB1CGEA/""" +
                """tPzKor9sAmblmRjYIn4sNSs0aFJC2CHRFsF9Bg=="}""",
        ) as JsonObject
        val key = "MCowBQYDK2VwAyEAZ07NQrZZHzIULLinP9an/WwvD8dCi/vbbCzni0oey10="
        assertNull(RootEndorsements.refusal(e, key, ent, now, testRoot))
        val later = Instant.parse("2029-01-01T00:00:00Z")
        assertEquals("endorsement has expired", RootEndorsements.refusal(e, key, ent, later, testRoot))
    }

    @Test
    fun `a valid endorsement is accepted`() {
        assertNull(refusal(root.endorse(store.publicKey, ent)))
        assertNull(refusal(root.endorse(store.publicKey, ent, notAfter = "2028-01-01T00:00:00Z")))
    }

    @Test
    fun `wrong purpose is rejected`() {
        assertEquals("endorsed for package-catalog, not entitlement", refusal(root.endorse(store.publicKey, pkg)))
        // Relabelling the purpose breaks the root's signature.
        val relabelled = JsonObject(root.endorse(store.publicKey, pkg) + ("purpose" to JsonPrimitive(ent)))
        assertEquals("root signature does not verify", refusal(relabelled))
    }

    @Test
    fun `an unendorsed key is rejected`() {
        assertEquals("no root endorsement", refusal(null))
        assertEquals("endorsement is for another key", refusal(root.endorse(TestRegistry().publicKey, ent)))
    }

    @Test
    fun `a forged endorsement is rejected`() {
        assertEquals("root signature does not verify", refusal(Root().endorse(store.publicKey, ent)))
        val short = root.endorse(store.publicKey, ent, notAfter = "2026-06-01T00:00:00Z")
        val stretched = JsonObject(short + ("notAfter" to JsonPrimitive("2099-01-01T00:00:00Z")))
        assertEquals("root signature does not verify", refusal(stretched))
    }

    @Test
    fun `an expired endorsement is rejected once past the clock-skew grace`() {
        val e = root.endorse(store.publicKey, ent, notAfter = "2026-06-01T00:00:00Z")
        assertEquals("endorsement has expired", refusal(e))
    }

    @Test
    fun `a device clock behind notBefore does not drop the key`() {
        // A dead RTC reading 1970: the root's signature is the proof, not the device's idea of the date.
        val epoch = Instant.parse("1970-01-01T00:00:00Z")
        assertNull(refusal(root.endorse(store.publicKey, ent), at = epoch))
        val published = listOf(
            PublishedKey(TrustedKey(store.publicKey, "store-v1"), root.endorse(store.publicKey, ent)),
        )
        val trust = KeyTrust.from(published, enforceRoot = true, now = epoch, rootPublicKey = root.publicKey)
        assertEquals(setOf(store.publicKey), trust.entitlementKeys)
    }

    @Test
    fun `a device clock a little past notAfter keeps the key, within the grace`() {
        val e = root.endorse(store.publicKey, ent, notAfter = "2027-01-01T00:00:00Z")
        val graceEnds = Instant.parse("2027-01-01T00:00:00Z").plus(RootEndorsements.CLOCK_SKEW)
        assertNull(refusal(e, at = graceEnds.minusSeconds(1)))
        assertEquals("endorsement has expired", refusal(e, at = graceEnds))
    }

    @Test
    fun `the trust document URL is built on the store host constant`() {
        assertTrue(ExtensionRepository.WELL_KNOWN_URL.startsWith(AzphaltStoreHandoff.WEB_STORE_URL + "/"))
    }

    @Test
    fun `keys serve only the purpose they are endorsed for, and the verifier follows`() {
        val catalog = TestRegistry()
        val published = listOf(
            PublishedKey(TrustedKey(store.publicKey, "store-v1"), root.endorse(store.publicKey, ent)),
            PublishedKey(TrustedKey(catalog.publicKey, "packages-v1"), root.endorse(catalog.publicKey, pkg)),
            PublishedKey(TrustedKey(TestRegistry().publicKey, "rogue")),
        )
        val trust = KeyTrust.from(published, enforceRoot = true, now = now, rootPublicKey = root.publicKey)
        assertEquals(setOf(store.publicKey), trust.entitlementKeys)
        assertEquals(listOf(catalog.publicKey), trust.packages.keys.map { it.publicKey })

        val verifier = EntitlementVerifier { trust.entitlementKeys }
        val licence = verifier.verify(store.token("com.example.paid"), "com.example.paid", now)
        assertTrue(licence is EntitlementCheck.Valid)
        // A catalog key, endorsed only for packages, cannot sign a licence.
        val crossed = verifier.verify(catalog.token("com.example.paid"), "com.example.paid", now)
        assertTrue(crossed is EntitlementCheck.Invalid)

        // Opted out (debug only), every published key serves both, as before endorsements existed.
        val legacy = KeyTrust.from(published, enforceRoot = false, now = now, rootPublicKey = root.publicKey)
        assertEquals(3, legacy.entitlementKeys.size)
        assertEquals(3, legacy.packages.keys.size)
    }

    @Test
    fun `the repository pins the owner's root and ignores keys it has not endorsed`() {
        val context = mockk<Context>()
        every { context.filesDir } returns tmp.newFolder("files")
        every { context.cacheDir } returns tmp.newFolder("cache")
        val idle: CoroutineDispatcher = StandardTestDispatcher()
        val dispatchers = object : DispatcherProvider {
            override val main = idle
            override val io = idle
            override val default = idle
            override val unconfined = idle
        }
        val repo = ExtensionRepository(context, dispatchers)
        assertTrue(repo.enforceRootEndorsements)
        val owners = "MCowBQYDK2VwAyEAjVS5e0Si26c3lzFyqnuW7VwMUmnwRF5AmBUBzYwrh6M="
        assertEquals(owners, RootEndorsements.ROOT_PUBLIC_KEY_SPKI_B64)

        // Endorsed by a root that is not the pinned one: refused for both purposes.
        val body = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put(
                    "signingKeys",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            buildJsonObject {
                                put("keyId", "store-v1")
                                put("publicKey", store.publicKey)
                                put("endorsement", root.endorse(store.publicKey, ent))
                            },
                        )
                    },
                )
            },
        )
        assertFalse(repo.applyFetchedTrustStore(body, repo.currentMutationGeneration()))
        assertTrue(repo.entitlementKeys().isEmpty())
        assertTrue(repo.trustStoreForTest().keys.isEmpty())
    }
}
