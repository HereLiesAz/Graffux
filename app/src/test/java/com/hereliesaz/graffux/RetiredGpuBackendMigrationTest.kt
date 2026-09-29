package com.hereliesaz.graffux

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetiredGpuBackendMigrationTest {

    /** Minimal in-memory SharedPreferences: enough for contains/remove/apply. */
    private class FakePrefs(initial: Map<String, Any?>) : SharedPreferences {
        val values = initial.toMutableMap()
        var writes = 0

        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?) = values[key] as String? ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as Long? ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as Float? ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as Boolean? ?: defValue
        override fun registerOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val removed = mutableSetOf<String>()
            override fun remove(key: String?) = apply { key?.let(removed::add) }
            override fun apply() { writes++; removed.forEach(values::remove) }
            override fun commit(): Boolean { apply(); return true }
            override fun putString(key: String?, value: String?) = this
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun clear() = this
        }
    }

    @Test
    fun `retired backend key is dropped and every other key survives`() {
        val prefs = FakePrefs(mapOf("backend" to "vulkan", "multipass" to true, "direct_display" to true))

        assertTrue(RetiredGpuBackendMigration.shouldMigrate(prefs))
        assertTrue(RetiredGpuBackendMigration.migrate(prefs))

        assertFalse(prefs.contains("backend"))
        assertEquals(true, prefs.values["multipass"])
        assertEquals(true, prefs.values["direct_display"])
        // Idempotent: nothing left to migrate, and the second run doesn't write.
        assertFalse(RetiredGpuBackendMigration.shouldMigrate(prefs))
        assertFalse(RetiredGpuBackendMigration.migrate(prefs))
        assertEquals(1, prefs.writes)
    }

    @Test
    fun `migration is a no-op on prefs that never had the selector`() {
        val prefs = FakePrefs(mapOf("multipass" to false))
        assertFalse(RetiredGpuBackendMigration.migrate(prefs))
        assertEquals(0, prefs.writes)
        assertFalse(RetiredGpuBackendMigration.migrate(FakePrefs(emptyMap())))
    }
}
