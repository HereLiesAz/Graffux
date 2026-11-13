package com.hereliesaz.graffux

import android.content.SharedPreferences
import com.hereliesaz.graffitixr.data.strokedata.StrokeDataStore
import com.hereliesaz.graffitixr.feature.editor.strokedata.StrokeContributionConsent

/**
 * Launch-time handling of stroke contribution that predates the 16+ declaration. It runs before any network
 * work: files recorded before the declaration are deleted, a switch left on without one is turned off once,
 * and [onSwitchedOff] tells the person. Any failure is swallowed so it can never take the launch down.
 */
internal suspend fun migrateStrokeData(
    prefs: SharedPreferences,
    store: StrokeDataStore,
    onSwitchedOff: suspend () -> Unit,
) {
    runCatching {
        StrokeContributionConsent.purgeBeforeDeclaration(prefs, store)
        if (StrokeContributionConsent.switchOffUndeclared(prefs)) onSwitchedOff()
    }
}

/** Runs [upload] only when contribution is on with a declaration. A failure is swallowed. */
internal suspend fun uploadStrokeDataIfContributing(prefs: SharedPreferences, upload: suspend () -> Unit) {
    runCatching { if (StrokeContributionConsent.isContributing(prefs)) upload() }
}
