package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.AzpSignatures
import com.hereliesaz.graffitixr.common.azphalt.SignatureStatus
import com.hereliesaz.graffitixr.common.azphalt.TrustStore
import com.hereliesaz.graffitixr.common.azphalt.TrustedKey
import com.hereliesaz.graffitixr.common.azphalt.evaluateTrust
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The azphalt **cross-language conformance suite**, run against this host.
 *
 * The azphalt repo ships nine binary `.azp` fixtures, a fixed trust store, and the reference
 * verifier's outcome for each, precisely so a host that is not written in JavaScript can prove it
 * agrees with `@azphalt/azp` rather than asserting that it agrees. Every other test around this one
 * builds its own archive in memory, which checks that the installer is self-consistent — a thing it
 * would also be if its idea of a valid package had drifted from everyone else's.
 *
 * Per `conformance/fixtures/README.md`, a host matches on the **semantic booleans** — `verify.ok`,
 * `verify.signed`, `signatureValid`, `trust.trusted` — and never on the diagnostic strings, which are
 * the JS reference's own wording. A host in another language may also reject a bad package at an
 * earlier stage than the reference does, so "which error" is deliberately not asserted here; only
 * "accepted or refused" is.
 *
 * The fixtures are copied verbatim from `conformance/fixtures/` and are generated, not hand-written:
 * signing uses Ed25519 keys derived from constant seeds and the archives ride a fixed timestamp, so
 * the bytes are identical in every environment. Refresh them by re-copying, never by editing.
 */
class AzpConformanceFixturesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/azphalt/conformance/$name")) {
            "missing conformance fixture: $name"
        }.use { it.readBytes() }

    private fun expectations(): JsonObject =
        json.parseToJsonElement(resource("expectations.json").decodeToString()).jsonObject

    /** The trust store the fixtures were generated against: the registry key only. */
    private fun conformanceTrustStore(): TrustStore {
        val keys = json.parseToJsonElement(resource("trust-keys.json").decodeToString())
            .jsonObject["trustStore"]!!.jsonObject["keys"]!!.jsonArray
        return TrustStore(
            keys.map {
                TrustedKey(
                    publicKey = it.jsonObject["publicKey"]!!.jsonPrimitive.content,
                    label = it.jsonObject["label"]?.jsonPrimitive?.content,
                )
            }
        )
    }

    /** Pull the entries out of a fixture without going through the installer. */
    private fun rawEntries(azp: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(azp.inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) out[e.name] = zip.readBytes()
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        return out
    }

    /**
     * Does this host accept the package? The installer is the host's whole verify path — container
     * safety, the `files` digests, and the signature — so "did `install` throw" is the host's
     * `verify.ok`.
     */
    private fun hostAccepts(azp: ByteArray, root: File, store: TrustStore): Boolean =
        runCatching { AzpInstaller(root, store).install(azp.inputStream(), nowMs = 1_700_000_000_000L) }
            .isSuccess

    @Test
    fun `every fixture matches the reference outcome`() {
        val store = conformanceTrustStore()
        val fixtures = expectations()["fixtures"]!!.jsonObject
        assertEquals("fixture count drifted from the vendored expectations", 9, fixtures.size)

        val failures = mutableListOf<String>()

        for ((name, spec) in fixtures) {
            val expected = spec.jsonObject
            val azp = resource(name)
            val entries = rawEntries(azp)

            // Each fixture installs into its own root: an id collision between two fixtures would
            // otherwise trip publisher continuity and make a later fixture fail for the wrong reason.
            val root = temp.newFolder(name.removeSuffix(".azp"))

            val wantOk = expected["verify"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean
            val wantSigned = expected["verify"]!!.jsonObject["signed"]!!.jsonPrimitive.boolean
            val wantSigValid = expected["signatureValid"]!!.jsonPrimitive.boolean
            val wantTrusted = expected["trust"]!!.jsonObject["trusted"]!!.jsonPrimitive.boolean

            val gotOk = hostAccepts(azp, root, store)
            val gotSigned = entries.containsKey("signature.json")

            // Signature and trust are evaluated straight from the bytes rather than from the install
            // result, so they can still be asserted for a fixture the host (correctly) refuses.
            val manifestBytes = entries["manifest.json"]
            val sig = AzpSignatures.parse(entries["signature.json"]?.decodeToString())
            val gotSigValid = manifestBytes != null && sig != null &&
                AzpSignatures.isManifestSignatureValid(manifestBytes, sig)
            val gotTrusted = gotSigValid && evaluateTrust(sig!!, store).trusted

            if (gotOk != wantOk) failures += "$name: verify.ok expected $wantOk, host said $gotOk"
            if (gotSigned != wantSigned) failures += "$name: verify.signed expected $wantSigned, host said $gotSigned"
            if (gotSigValid != wantSigValid) failures += "$name: signatureValid expected $wantSigValid, host said $gotSigValid"
            if (gotTrusted != wantTrusted) failures += "$name: trust.trusted expected $wantTrusted, host said $gotTrusted"
        }

        assertTrue(
            "host disagrees with the azphalt reference on:\n  " + failures.joinToString("\n  "),
            failures.isEmpty(),
        )
    }

    @Test
    fun `the trusted fixture names the registry key that vouched for it`() {
        val store = conformanceTrustStore()
        val entries = rawEntries(resource("countersigned-1hop.azp"))
        val sig = checkNotNull(AzpSignatures.parse(entries["signature.json"]!!.decodeToString()))
        val result = evaluateTrust(sig, store)

        assertTrue("countersigned-1hop must be trusted transitively", result.trusted)
        val expectedVia = expectations()["fixtures"]!!.jsonObject["countersigned-1hop.azp"]!!
            .jsonObject["trust"]!!.jsonObject["viaRegistryPublicKey"]!!.jsonPrimitive.content
        assertEquals(
            "trust must be attributed to the registry key the reference names",
            expectedVia,
            result.viaRegistryPublicKey,
        )
    }

    /**
     * The one fixture whose handling the README calls out explicitly. `duplicate-entry.azp` packs two
     * entries under one name with different content — a ZIP-confusion vector. Keeping the **first**
     * copy and accepting the package is named as non-conforming, because the digest then attests to
     * bytes the host did not keep.
     */
    @Test
    fun `a duplicate zip entry is refused`() {
        val root = temp.newFolder("dup")
        assertTrue(
            "a package with two entries of the same name must be refused",
            !hostAccepts(resource("duplicate-entry.azp"), root, conformanceTrustStore()),
        )
    }

    /**
     * A signed fixture that this host accepts must land with its provenance recorded — signature
     * validity and trust are separate answers, and the installed record carries both.
     */
    @Test
    fun `an accepted signed package records its provenance`() {
        val store = conformanceTrustStore()

        val untrusted = AzpInstaller(temp.newFolder("signed"), store)
            .install(resource("signed-valid.azp").inputStream(), nowMs = 1L)
        assertEquals(
            "the author key is not in the conformance trust store",
            SignatureStatus.SIGNED_UNTRUSTED,
            untrusted.signature,
        )

        val trusted = AzpInstaller(temp.newFolder("counter"), store)
            .install(resource("countersigned-1hop.azp").inputStream(), nowMs = 1L)
        assertEquals(
            "the registry counter-signature establishes trust",
            SignatureStatus.SIGNED_TRUSTED,
            trusted.signature,
        )
        assertNotNull(trusted.manifest.id)
    }
}
