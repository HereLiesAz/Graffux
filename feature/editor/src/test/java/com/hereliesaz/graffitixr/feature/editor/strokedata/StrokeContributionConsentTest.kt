package com.hereliesaz.graffitixr.feature.editor.strokedata

import android.content.Context
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
class StrokeContributionConsentTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun prefs() = context.getSharedPreferences(STROKE_DATA_PREFS, Context.MODE_PRIVATE)

    @Before fun clean() {
        prefs().edit().clear().commit()
    }

    @Test fun `off and undeclared by default`() {
        assertFalse(StrokeContributionConsent.ageDeclared(prefs()))
        assertFalse(StrokeContributionConsent.isContributing(prefs()))
    }

    @Test fun `without the declaration the switch cannot be turned on`() {
        assertFalse(StrokeContributionConsent.enable(prefs()))
        assertFalse(StrokeContributionConsent.isContributing(prefs()))
        assertFalse(prefs().getBoolean(STROKE_DATA_KEY, false))
    }

    @Test fun `declaring turns it on and the declaration is persisted`() {
        StrokeContributionConsent.declareAge16AndEnable(prefs())
        assertTrue(StrokeContributionConsent.isContributing(prefs()))
        // A fresh read of the same file, as after a restart.
        assertTrue(context.getSharedPreferences(STROKE_DATA_PREFS, Context.MODE_PRIVATE)
            .getBoolean(StrokeContributionConsent.KEY_AGE_16_DECLARED, false))
    }

    @Test fun `the declaration is one-time, so switching off then on needs no second one`() {
        StrokeContributionConsent.declareAge16AndEnable(prefs())
        StrokeContributionConsent.disable(prefs())
        assertFalse(StrokeContributionConsent.isContributing(prefs()))
        assertTrue(StrokeContributionConsent.enable(prefs()))
    }

    @Test fun `a switch left on before the declaration existed does not contribute and is switched off once`() {
        prefs().edit().putBoolean(STROKE_DATA_KEY, true).commit()
        assertFalse("raw switch alone must not record or upload", StrokeContributionConsent.isContributing(prefs()))
        assertTrue(StrokeContributionConsent.switchOffUndeclared(prefs()))
        assertFalse(prefs().getBoolean(STROKE_DATA_KEY, false))
        assertFalse("nothing left the second time", StrokeContributionConsent.switchOffUndeclared(prefs()))
    }

    @Test fun `a declared user who is on is left alone`() {
        StrokeContributionConsent.declareAge16AndEnable(prefs())
        assertFalse(StrokeContributionConsent.switchOffUndeclared(prefs()))
        assertEquals(true, StrokeContributionConsent.isContributing(prefs()))
    }
}
