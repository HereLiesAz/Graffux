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

    private fun excludes(path: String, section: String?): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val root = if (section == null) doc.documentElement else doc.getElementsByTagName(section).item(0) as Element
        val nodes = root.getElementsByTagName("exclude")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("path") }.toSet()
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
}
