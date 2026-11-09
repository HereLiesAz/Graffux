package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.data.azphalt.ExtensionStateProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The extension-state provider is exported and read-gated by a `normal` permission, which any installed
 * app can declare and be granted without a prompt. What it returns (which extensions the user has, at
 * what version, in what state, when) is personal, so the privacy policy, the manifest and the specs must
 * say who can read it, not only that "the store" can, and the provider must stay what they say it is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ExtensionStateDisclosureTest {
    private fun read(path: String) = File(path).readText()

    private fun squashed(path: String) = read(path).replace(Regex("\\s+"), " ")

    // ---- the wording ------------------------------------------------------------------------------

    @Test fun `the privacy policy discloses the provider, who can read it and what it exposes`() {
        val text = squashed("../docs/PRIVACY_POLICY.md")
        assertTrue("names the permission", "azphalt.permission.READ_EXTENSION_STATE" in text)
        assertTrue("says any app can hold it", Regex("any other app", RegexOption.IGNORE_CASE).containsMatchIn(text))
        assertTrue("says what is exposed", "ids, versions, states and timestamps" in text)
        assertTrue("says it is without a prompt", "without a prompt" in text)
    }

    @Test fun `the manifest and both specs say any installed app can read the list`() {
        for (path in listOf("src/main/AndroidManifest.xml", "../spec/state-reporting.md", "../spec/store-app.md")) {
            assertTrue("$path names who can be granted it", "any installed app" in squashed(path))
        }
    }

    // ---- what the manifest actually declares -------------------------------------------------------

    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val readPermission = "azphalt.permission.READ_EXTENSION_STATE"

    @Suppress("CyclomaticComplexMethod") // one flat list of independent checks on one element
    /** What is wrong with [manifestXml]'s provider and permission, parsed as XML (comments and text never count). */
    private fun providerProblems(manifestXml: String): List<String> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifestXml.byteInputStream())
        fun Element.attr(name: String) = getAttributeNS(androidNs, name)
        fun Element.has(name: String) = hasAttributeNS(androidNs, name)
        fun elements(tag: String): List<Element> {
            val nodes = doc.getElementsByTagName(tag)
            return (0 until nodes.length).map { nodes.item(it) as Element }
        }
        val provider = elements("provider").singleOrNull { it.attr("name").endsWith("ExtensionStateProvider") }
            ?: return listOf("no ExtensionStateProvider <provider> element")
        val problems = mutableListOf<String>()
        if (provider.attr("exported") != "true") problems += "provider is not exported"
        if (provider.attr("readPermission") != readPermission) problems += "wrong readPermission"
        if (provider.has("writePermission")) problems += "provider has a writePermission"
        if (provider.has("permission")) problems += "provider has a blanket permission"
        if (provider.attr("grantUriPermissions") != "false") problems += "provider grants URI permissions"
        if (provider.getElementsByTagName("grant-uri-permission").length > 0) problems += "has grant-uri-permission"
        val permission = elements("permission").singleOrNull { it.attr("name") == readPermission }
        if (permission == null) {
            problems += "the permission is not declared"
        } else if (permission.attr("protectionLevel") != "normal") {
            problems += "protectionLevel is not normal"
        }
        return problems
    }

    @Test fun `the real manifest declares the provider as the disclosure describes`() {
        assertEquals(emptyList<String>(), providerProblems(read("src/main/AndroidManifest.xml")))
    }

    @Test fun `the check fails for a weakened manifest, wherever the weakness is`() {
        val manifest = read("src/main/AndroidManifest.xml")
        val declared = "android:readPermission=\"$readPermission\""
        fun weakened(from: String, to: String): String {
            assertTrue("fixture text present: $from", from in manifest)
            return manifest.replace(from, to)
        }
        val variants = mapOf(
            "writePermission added" to weakened(declared, "$declared android:writePermission=\"x.y\""),
            "readPermission removed" to weakened(declared, ""),
            "uri grants on" to weakened(
                "android:grantUriPermissions=\"false\"",
                "android:grantUriPermissions=\"true\"",
            ),
            "protection level changed" to manifest.replace(
                Regex("(<permission[^>]*READ_EXTENSION_STATE[^>]*android:protectionLevel=\")normal"),
                "\$1dangerous",
            ),
        )
        for ((what, xml) in variants) {
            assertTrue("$what must be caught", providerProblems(xml).isNotEmpty())
        }
        // The attribute text inside an XML comment is not a declaration: the real attribute is gone, the
        // same text survives only in a comment, and the parse must still see the provider without it.
        val commentOnly = manifest.replace(declared, "")
            .replace("<application", "<!-- $declared -->\n<application")
        assertTrue("the attribute text is still in the file, inside a comment", declared in commentOnly)
        assertTrue(
            "a comment is not a declaration: ${providerProblems(commentOnly)}",
            "wrong readPermission" in providerProblems(commentOnly),
        )
    }

    // ---- what the provider does -------------------------------------------------------------------

    @Test fun `the provider refuses every write`() {
        val provider = ExtensionStateProvider()
        val uri = android.net.Uri.parse("content://x.azphalt.state/extensions")
        for (call in listOf<() -> Any?>(
            { provider.insert(uri, null) },
            { provider.update(uri, null, null, null) },
            { provider.delete(uri, null, null) },
        )) {
            try {
                call()
                fail("a write did not throw")
            } catch (e: UnsupportedOperationException) {
                assertTrue(e.message.orEmpty(), "read-only" in e.message.orEmpty())
            }
        }
    }
}
