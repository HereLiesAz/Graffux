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

@RunWith(RobolectricTestRunner::class)
class StrokeDataPurgeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun prefs() = context.getSharedPreferences(STROKE_DATA_PREFS, Context.MODE_PRIVATE)
    private val store get() = StrokeDataStore.get(context)

    @Before fun clean() {
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
        assertTrue(StrokeContributionConsent.purgeUndeclaredFiles(prefs(), store))
        assertTrue(store.allFiles().isEmpty())
        // Declaring afterwards finds nothing left to upload.
        StrokeContributionConsent.declareAge16AndEnable(prefs())
        assertTrue(store.pending().isEmpty())
    }

    @Test fun `files recorded with a declaration are kept`() {
        StrokeContributionConsent.declareAge16AndEnable(prefs())
        record()
        assertFalse(StrokeContributionConsent.purgeUndeclaredFiles(prefs(), store))
        assertEquals(1, store.pending().size)
    }
}
