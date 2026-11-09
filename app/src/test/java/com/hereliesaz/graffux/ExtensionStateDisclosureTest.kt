package com.hereliesaz.graffux

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The extension-state provider is exported and read-gated by a `normal` permission, which any installed
 * app can declare and be granted without a prompt. What it returns (which extensions the user has, at
 * what version, in what state) is personal, so the privacy policy, the manifest and the spec must say
 * who can read it, not only that "the store" can.
 */
class ExtensionStateDisclosureTest {
    private fun read(path: String) = File(path).readText()

    private val policy get() = read("../docs/PRIVACY_POLICY.md")
    private val manifest get() = read("src/main/AndroidManifest.xml")
    private val spec get() = read("../spec/state-reporting.md")

    @Test fun `the privacy policy discloses the provider and who can read it`() {
        val text = policy.replace(Regex("\\s+"), " ")
        assertTrue("names the permission", "azphalt.permission.READ_EXTENSION_STATE" in text)
        assertTrue("says any app can hold it", Regex("any (other )?app", RegexOption.IGNORE_CASE).containsMatchIn(text))
        assertTrue("says what is exposed", "ids, versions and states" in text)
        assertTrue("says it is without a prompt", "without a prompt" in text)
    }

    @Test fun `the manifest and the spec do not claim the list is private to the chosen store`() {
        assertTrue("manifest names who can be granted it", "any installed app" in manifest.replace(Regex("\\s+"), " "))
        assertTrue("spec names who can be granted it", "any installed app" in spec.replace(Regex("\\s+"), " "))
    }

    @Test fun `the provider stays read-only and permission-gated`() {
        val m = manifest.replace(Regex("\\s+"), " ")
        assertTrue("android:readPermission=\"azphalt.permission.READ_EXTENSION_STATE\"" in m)
        assertTrue("android:grantUriPermissions=\"false\"" in m)
    }
}
