package com.hereliesaz.graffitixr.data.repository

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey

/**
 * Drops settings keys that no longer mean anything. The migration runs only while a retired key is
 * present in the store ([shouldMigrate]); once they are gone, DataStore's check finds nothing to do.
 *
 * `jetpack_ink_brush` was the Settings > Jetpack Ink toggle, which made the legacy round Brush draw
 * through `androidx.ink`. Ink is now a set of art utensils in the brush list (Ink Pen, Ink Marker,
 * ...), picked like any other brush, so the toggle is gone. Its value is deliberately **not** carried
 * forward into "select the Ink Pen": the toggle only ever affected the legacy round, which the brush
 * rail hasn't been able to select since the GPU stamp Round replaced it, so an "on" never changed
 * what anyone actually drew — honouring it now would swap a user's brush out from under them for a
 * setting that had no visible effect. Removing the key is idempotent and leaves every other key
 * untouched.
 */
internal object RetiredSettingsMigration : DataMigration<Preferences> {

    /**
     * Retired keys, by name. Only ever append: a key listed here is wiped whenever it is found in the
     * store, so reusing its name for a new setting would erase that setting.
     */
    val RETIRED_KEYS: List<Preferences.Key<Boolean>> = listOf(
        booleanPreferencesKey("jetpack_ink_brush"),
    )

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        RETIRED_KEYS.any { currentData.contains(it) }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val next = currentData.toMutablePreferences()
        RETIRED_KEYS.forEach { next.remove(it) }
        return next.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}
