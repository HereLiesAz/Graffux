package com.hereliesaz.graffitixr.feature.editor.strokedata

import android.content.Context
import com.hereliesaz.graffitixr.data.strokedata.StrokeDataStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StrokeDataPurgeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun prefs() = context.getSharedPreferences(STROKE_DATA_PREFS, Context.MODE_PRIVATE)
    private val store get() = StrokeDataStore.get(context)

    @Before fun clean() {
        // The store is a per-process singleton bound to the first test's filesDir; Robolectric gives each test a
        // new one, so drop the cached instance.
        StrokeDataStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        prefs().edit().clear().commit()
        store.purgeAll()
    }

    private fun record() {
        store.append(JSONObject().put("type", "stroke")) { JSONObject().put("schema", 4) }
        store.closeSession()
    }

    @Test fun `files recorded without a declaration are deleted and never upload later`() {
        record()
        assertEquals(1, store.pending().size)
        assertTrue(StrokeContributionConsent.purgeBeforeDeclaration(prefs(), store))
        assertTrue(store.allFiles().isEmpty())
        StrokeContributionConsent.declareAge16AndEnable(prefs())
        assertTrue(store.pending().isEmpty())
    }

    @Test fun `a declaration made before the purge ran still keeps the old file from uploading`() {
        record()
        val file = store.pending().single()
        // The person declares first (a minute from the file's start), then the purge runs.
        StrokeContributionConsent.declareAge16AndEnable(prefs(), now = System.currentTimeMillis() + 60_000)
        assertFalse(StrokeContributionConsent.mayUpload(prefs(), file.name))
        assertTrue(StrokeContributionConsent.purgeBeforeDeclaration(prefs(), store))
        assertTrue(store.pending().isEmpty())
    }

    @Test fun `files recorded after the declaration are kept`() {
        StrokeContributionConsent.declareAge16AndEnable(prefs(), now = 0L)
        record()
        assertTrue(StrokeContributionConsent.mayUpload(prefs(), store.pending().single().name))
        assertFalse(StrokeContributionConsent.purgeBeforeDeclaration(prefs(), store))
        assertEquals(1, store.pending().size)
    }

    @Test fun `a name without a start time and an undeclared user never upload`() {
        assertFalse(StrokeContributionConsent.mayUpload(prefs(), "notes.txt"))
        assertFalse(StrokeContributionConsent.mayUpload(prefs(), "session-1790680692015.jsonl.gz"))
        assertEquals(1790680692015L, StrokeContributionConsent.sessionStartMs("session-1790680692015.jsonl.gz.open"))
    }
}
