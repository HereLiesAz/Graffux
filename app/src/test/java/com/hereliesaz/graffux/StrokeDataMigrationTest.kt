package com.hereliesaz.graffux

import android.content.Context
import com.hereliesaz.graffitixr.data.strokedata.StrokeDataStore
import com.hereliesaz.graffitixr.feature.editor.strokedata.STROKE_DATA_KEY
import com.hereliesaz.graffitixr.feature.editor.strokedata.STROKE_DATA_PREFS
import com.hereliesaz.graffitixr.feature.editor.strokedata.StrokeContributionConsent
import kotlinx.coroutines.runBlocking
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
class StrokeDataMigrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun prefs() = context.getSharedPreferences(STROKE_DATA_PREFS, Context.MODE_PRIVATE)
    private val store get() = StrokeDataStore.get(context)

    @Before fun clean() {
        StrokeDataStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        prefs().edit().clear().commit()
        store.purgeAll()
    }

    private fun record() {
        store.append(JSONObject().put("type", "stroke")) { JSONObject().put("schema", 4) }
        store.closeSession()
    }

    @Test fun `a switch left on without a declaration is turned off, files are purged, and the person is told`() =
        runBlocking {
            prefs().edit().putBoolean(STROKE_DATA_KEY, true).commit()
            record()
            var told = 0
            migrateStrokeData(prefs(), store) { told++ }
            assertEquals(1, told)
            assertFalse(prefs().getBoolean(STROKE_DATA_KEY, false))
            assertTrue(store.allFiles().isEmpty())
            migrateStrokeData(prefs(), store) { told++ }
            assertEquals("told once", 1, told)
        }

    @Test fun `a declared user is left alone`() = runBlocking {
        StrokeContributionConsent.declareAge16AndEnable(prefs(), now = 0L)
        record()
        var told = 0
        migrateStrokeData(prefs(), store) { told++ }
        assertEquals(0, told)
        assertTrue(StrokeContributionConsent.isContributing(prefs()))
        assertEquals(1, store.pending().size)
    }

    @Test fun `a failure in the notice is swallowed`() = runBlocking {
        prefs().edit().putBoolean(STROKE_DATA_KEY, true).commit()
        migrateStrokeData(prefs(), store) { error("toast failed") }
    }

    @Test fun `upload runs only when contributing`() = runBlocking {
        var uploads = 0
        uploadStrokeDataIfContributing(prefs()) { uploads++ }
        assertEquals(0, uploads)
        prefs().edit().putBoolean(STROKE_DATA_KEY, true).commit()
        uploadStrokeDataIfContributing(prefs()) { uploads++ }
        assertEquals("switch alone is not enough", 0, uploads)
        StrokeContributionConsent.declareAge16AndEnable(prefs(), now = 0L)
        uploadStrokeDataIfContributing(prefs()) { uploads++ }
        assertEquals(1, uploads)
    }
}
