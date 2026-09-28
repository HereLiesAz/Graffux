package com.hereliesaz.graffitixr.feature.editor.gpu

import android.content.Context

/** The string key-value storage [GpuTierStore] needs; SharedPreferences in the app, a map in tests. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

class SharedPreferencesKeyValueStore(context: Context, name: String = PREFS) : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    companion object {
        const val PREFS = "gpu_tier"
    }
}

/** A tier the calibration picked, with the numbers behind it. */
data class StoredTier(
    val tier: GpuTier,
    val result: CalibrationResult,
    /** App `versionCode` at calibration: an app update invalidates the entry. */
    val appVersion: Long,
)

/**
 * Persists the calibrated tier keyed by GPU and driver ([GpuInfo.identityKey]), so a driver update
 * (a new driver string) or a different GPU simply misses. An app update invalidates it through
 * [StoredTier.appVersion]: new shaders or engine changes can move the numbers.
 *
 * One entry is kept (the device has one GPU); a key for another identity reads as absent.
 */
class GpuTierStore(private val store: KeyValueStore, private val appVersion: Long) {

    /** The stored tier for [identity], or null when absent, for another GPU/driver, or stale. */
    fun load(identity: String): StoredTier? {
        val fresh = store.getString(KEY_IDENTITY) == identity &&
            store.getString(KEY_APP_VERSION)?.toLongOrNull() == appVersion
        val tier = GpuTierTable.byName(store.getString(KEY_TIER))
        val result = decode(store.getString(KEY_RESULT))
        return if (fresh && tier != null && result != null) StoredTier(tier, result, appVersion) else null
    }

    fun needsCalibration(identity: String): Boolean = load(identity) == null

    fun save(identity: String, tier: GpuTier, result: CalibrationResult) {
        store.putString(KEY_IDENTITY, identity)
        store.putString(KEY_APP_VERSION, appVersion.toString())
        store.putString(KEY_TIER, tier.name)
        store.putString(KEY_RESULT, encode(result))
    }

    /** Settings > Re-run calibration: forget the entry so the next run measures again. */
    fun clear() {
        listOf(KEY_IDENTITY, KEY_APP_VERSION, KEY_TIER, KEY_RESULT).forEach(store::remove)
    }

    internal companion object {
        const val KEY_IDENTITY = "identity"
        const val KEY_APP_VERSION = "app_version"
        const val KEY_TIER = "tier"
        const val KEY_RESULT = "result"
        private const val FIELDS = 4

        fun encode(r: CalibrationResult): String =
            listOf(r.stampDabsPerMs, r.readbackMBps, r.compositeMs).joinToString(";") + ";" + r.backend

        fun decode(text: String?): CalibrationResult? {
            val parts = text?.split(';').orEmpty()
            val numbers = parts.dropLast(1).mapNotNull { it.toDoubleOrNull() }
            return if (parts.size == FIELDS && numbers.size == FIELDS - 1) {
                CalibrationResult(numbers[0], numbers[1], numbers[2], parts.last()).takeIf { it.isValid }
            } else {
                null
            }
        }
    }
}
