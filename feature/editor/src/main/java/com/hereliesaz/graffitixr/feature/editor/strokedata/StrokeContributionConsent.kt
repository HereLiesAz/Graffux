package com.hereliesaz.graffitixr.feature.editor.strokedata

import android.content.SharedPreferences
import com.hereliesaz.graffitixr.data.strokedata.StrokeDataStore

/**
 * The gate in front of stroke-model training contribution. Contributing needs two stored answers in
 * [STROKE_DATA_PREFS]: the switch ([STROKE_DATA_KEY]) and a one-time declaration that the person is 16 or
 * older ([KEY_AGE_16_DECLARED]). Recording and uploading read [isContributing], never the switch alone, so
 * a switch that was turned on before the declaration existed does nothing until the person declares.
 *
 * The declaration is the person's own statement; nothing here verifies it. The preference file is
 * excluded from backup and device transfer (backup_rules.xml, data_extraction_rules.xml), so a restore
 * never brings a declaration onto a device whose user was not asked.
 */
object StrokeContributionConsent {
    const val KEY_AGE_16_DECLARED = "age_16_declared"

    fun ageDeclared(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_AGE_16_DECLARED, false)

    /** True only when the switch is on and the 16+ declaration has been made. */
    fun isContributing(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(STROKE_DATA_KEY, false) && ageDeclared(prefs)

    /** Switches contribution on if the declaration exists; returns whether it is now on. */
    fun enable(prefs: SharedPreferences): Boolean {
        if (ageDeclared(prefs)) prefs.edit().putBoolean(STROKE_DATA_KEY, true).apply()
        return isContributing(prefs)
    }

    /** Stores the 16+ declaration and switches contribution on, in one write. */
    fun declareAge16AndEnable(prefs: SharedPreferences) {
        prefs.edit().putBoolean(KEY_AGE_16_DECLARED, true).putBoolean(STROKE_DATA_KEY, true).apply()
    }

    fun disable(prefs: SharedPreferences) {
        prefs.edit().putBoolean(STROKE_DATA_KEY, false).apply()
    }

    /**
     * For a switch that is on without a declaration (turned on before the declaration existed): turns it
     * off. Returns true when it did, so the caller can tell the person once.
     */
    fun switchOffUndeclared(prefs: SharedPreferences): Boolean {
        val stale = prefs.getBoolean(STROKE_DATA_KEY, false) && !ageDeclared(prefs)
        if (stale) disable(prefs)
        return stale
    }

    /**
     * Deletes every recorded session file while there is no 16+ declaration. Files only exist in that state
     * if they were recorded by a version that predates the declaration, and they must not be sent after a
     * later declaration. Returns true when it deleted anything.
     */
    fun purgeUndeclaredFiles(prefs: SharedPreferences, store: StrokeDataStore): Boolean {
        val stale = !ageDeclared(prefs) && store.allFiles().isNotEmpty()
        if (stale) store.purgeAll()
        return stale
    }
}
