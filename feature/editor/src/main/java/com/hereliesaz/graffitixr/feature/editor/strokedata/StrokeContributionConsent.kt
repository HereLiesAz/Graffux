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

    /** Wall-clock time (ms) of the declaration. Only session files that started at or after it may upload. */
    const val KEY_AGE_16_DECLARED_AT = "age_16_declared_at"
    private val SESSION_START = Regex("""session-(\d+)\.jsonl\.gz""")

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
    fun declareAge16AndEnable(prefs: SharedPreferences, now: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putBoolean(KEY_AGE_16_DECLARED, true)
            .putLong(KEY_AGE_16_DECLARED_AT, now)
            .putBoolean(STROKE_DATA_KEY, true)
            .apply()
    }

    /** When the declaration was made, or Long.MAX_VALUE when there is none (so nothing is eligible). */
    fun declaredAt(prefs: SharedPreferences): Long =
        if (ageDeclared(prefs)) prefs.getLong(KEY_AGE_16_DECLARED_AT, Long.MAX_VALUE) else Long.MAX_VALUE

    /** The session start encoded in a file name (`session-<ms>.jsonl.gz[.open]`), or null if it has none. */
    fun sessionStartMs(fileName: String): Long? =
        SESSION_START.find(fileName.removeSuffix(".open"))?.groupValues?.get(1)?.toLongOrNull()

    /**
     * True when the file was recorded after the declaration, so it may be uploaded. A file recorded before
     * it (or with a name that carries no start time) never is, even if the declaration came first.
     */
    fun mayUpload(prefs: SharedPreferences, fileName: String): Boolean {
        val start = sessionStartMs(fileName) ?: return false
        return start >= declaredAt(prefs)
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
     * Deletes every session file that started before the declaration (all of them while there is none):
     * they were recorded by a version that predates it, and must not upload after a later declaration. Safe
     * to run at any time, and the uploader checks each file again with [mayUpload]. Returns true when it
     * deleted anything.
     */
    fun purgeBeforeDeclaration(prefs: SharedPreferences, store: StrokeDataStore): Boolean {
        val stale = store.allFiles().filterNot { mayUpload(prefs, it.name) }
        stale.forEach { it.delete() }
        return stale.isNotEmpty()
    }
}
