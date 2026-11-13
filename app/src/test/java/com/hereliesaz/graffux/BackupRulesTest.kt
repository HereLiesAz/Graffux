package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.data.azphalt.purchase.EntitlementStore
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The entitlement store carries the buyer-session cookie: it never goes to backup or device transfer. */
class BackupRulesTest {
    private val store = EntitlementStore.FILE_NAME
    private val files = listOf(store, "$store.bak", "$store.tmp", "$store.corrupt")

    private fun excludes(path: String, section: String?, domain: String = "file"): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val root = if (section == null) doc.documentElement else doc.getElementsByTagName(section).item(0) as Element
        val nodes = root.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getAttribute("domain") == domain }
            .map { it.getAttribute("path") }
            .toSet()
    }

    @Test fun `entitlements are excluded from cloud backup and device transfer`() {
        val rules = "src/main/res/xml/data_extraction_rules.xml"
        for (section in listOf("cloud-backup", "device-transfer")) {
            val excluded = excludes(rules, section)
            assertTrue("$section misses ${files - excluded}", excluded.containsAll(files))
        }
    }

    @Test fun `entitlements are excluded from legacy auto backup`() {
        val excluded = excludes("src/main/res/xml/backup_rules.xml", null)
        assertTrue("backup_rules misses ${files - excluded}", excluded.containsAll(files))
    }

    @Test fun `the large directories stay out of cloud backup so it cannot exceed the cap and fail whole`() {
        val rules = "src/main/res/xml/data_extraction_rules.xml"
        val big = setOf("projects", "models")
        val cloud = excludes(rules, "cloud-backup")
        assertTrue("cloud-backup misses ${big - cloud}", cloud.containsAll(big))
        val legacy = excludes("src/main/res/xml/backup_rules.xml", null)
        assertTrue("backup_rules misses ${big - legacy}", legacy.containsAll(big))
    }

    @Test fun `device transfer keeps projects but not downloaded models`() {
        val transfer = excludes("src/main/res/xml/data_extraction_rules.xml", "device-transfer")
        assertTrue("models are fetched again", "models" in transfer)
        assertTrue("projects travel to a new phone", "projects" !in transfer)
    }

    /**
     * Consent is a decision made on one device. A cloud backup restores onto a new install, and a
     * device transfer onto a new phone, where the user has not been asked: the restored "on" would
     * switch on public crash filing and raw stroke upload. The recorded stroke files carry the old
     * device's model and sensors, so they stay behind too.
     */
    @Test fun `consent and what it gates are excluded from every backup path`() {
        val sharedPrefs = setOf("privacy_consent.xml", "stroke_prediction.xml", "secure_tokens.xml")
        val files = setOf("stroke-data")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val prefs = excludes("src/main/res/xml/data_extraction_rules.xml", section, "sharedpref")
            val data = excludes("src/main/res/xml/data_extraction_rules.xml", section, "file")
            assertTrue("$section misses ${sharedPrefs - prefs}", prefs.containsAll(sharedPrefs))
            assertTrue("$section misses ${files - data}", data.containsAll(files))
        }
        val prefs = excludes("src/main/res/xml/backup_rules.xml", null, "sharedpref")
        val data = excludes("src/main/res/xml/backup_rules.xml", null, "file")
        assertTrue("backup_rules misses ${sharedPrefs - prefs}", prefs.containsAll(sharedPrefs))
        assertTrue("backup_rules misses ${files - data}", data.containsAll(files))
    }
}
