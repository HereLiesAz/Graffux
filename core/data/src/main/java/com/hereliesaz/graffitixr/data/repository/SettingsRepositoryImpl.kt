package com.hereliesaz.graffitixr.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hereliesaz.graffitixr.common.model.AppLanguage
import com.hereliesaz.graffitixr.common.model.ArScanMode
import com.hereliesaz.graffitixr.common.model.GestureAction
import com.hereliesaz.graffitixr.common.model.GestureSlot
import com.hereliesaz.graffitixr.common.model.MuralMethod
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject

import com.hereliesaz.graffitixr.common.util.PaletteCodec

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Language codes written by older builds, mapped onto the entry that replaced them.
 *
 * The two Chinese entries stored an Android resource qualifier (`zh-rCN`) where a BCP-47 tag
 * (`zh-CN`) was meant — see [AppLanguage]. Without this, correcting the tag would read every
 * already-stored Chinese preference as unrecognised and silently reset those users to System
 * Default; the setting is only ever written from the enum, so the old spellings need no writer.
 */
private val LEGACY_LANGUAGE_CODES: Map<String, AppLanguage> = mapOf(
    "zh-rCN" to AppLanguage.CHINESE_SIMPLIFIED,
    "zh-rHK" to AppLanguage.CHINESE_TRADITIONAL,
)

class SettingsRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context
) : SettingsRepository {

    private val LANGUAGE = stringPreferencesKey("language")
    private val IS_RIGHT_HANDED = booleanPreferencesKey("is_right_handed")
    private val AR_SCAN_MODE = stringPreferencesKey("ar_scan_mode")
    private val MURAL_METHOD = stringPreferencesKey("mural_method")
    private val SHOW_ANCHOR_BOUNDARY = booleanPreferencesKey("show_anchor_boundary")
    private val FORCED_STEREO_UNSTABLE = booleanPreferencesKey("forced_stereo_unstable")
    // Key intentionally renamed from "stereo_capability": the probe's meaning changed from "stereo
    // tracks" to "dual lenses actually triangulate depth", so pre-existing verdicts must be discarded
    // and re-probed under the stricter test. Reading the new key returns -1 (unprobed) on old installs.
    private val STEREO_CAPABILITY = intPreferencesKey("depth_triangulation_capability")
    private val IS_IMPERIAL_UNITS = booleanPreferencesKey("is_imperial_units")
    private val BACKGROUND_COLOR = intPreferencesKey("background_color")
    private val PARALLAX_MIN_DEG = floatPreferencesKey("parallax_min_degrees")
    private val CAMERA_TARGET_FPS = intPreferencesKey("camera_target_fps")
    private val THROTTLE_ON_THERMAL = booleanPreferencesKey("throttle_on_thermal")
    private val THROTTLE_ON_POWER_SAVE = booleanPreferencesKey("throttle_on_power_save")
    private val THROTTLE_ON_LOW_BATTERY = booleanPreferencesKey("throttle_on_low_battery")
    private val THROTTLE_ON_LAG = booleanPreferencesKey("throttle_on_lag")
    private val ADAPTIVE_RATE_ENABLED = booleanPreferencesKey("adaptive_rate_enabled")
    private val SAVED_PALETTE = stringPreferencesKey("saved_palette")
    private val INPUT_SAMPLE_RATE_HZ = intPreferencesKey("input_sample_rate_hz")
    private val CANVAS_RENDER_SCALE = floatPreferencesKey("canvas_render_scale")
    private val GESTURE_KEYS = GestureSlot.entries.associateWith { stringPreferencesKey("gesture_${it.name.lowercase()}") }
    private val TOOL_USAGE = stringPreferencesKey("tool_usage")
    private val FAVORITE_TOOLS = stringPreferencesKey("favorite_tools")

    override val language: Flow<AppLanguage> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            val code = preferences[LANGUAGE] ?: ""
            AppLanguage.entries.find { it.code == code }
                ?: LEGACY_LANGUAGE_CODES[code]
                ?: AppLanguage.SYSTEM
        }

    override suspend fun setLanguage(language: AppLanguage) {
        context.dataStore.edit { preferences ->
            preferences[LANGUAGE] = language.code
        }
    }

    override val isRightHanded: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            preferences[IS_RIGHT_HANDED] ?: true
        }

    override suspend fun setRightHanded(isRight: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IS_RIGHT_HANDED] = isRight
        }
    }

    override val arScanMode: Flow<ArScanMode> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            when (preferences[AR_SCAN_MODE]) {
                ArScanMode.CLOUD_POINTS.name -> ArScanMode.CLOUD_POINTS
                else -> ArScanMode.MURAL  // default
            }
        }

    override val muralMethod: Flow<MuralMethod> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            when (preferences[MURAL_METHOD]) {
                MuralMethod.SURFACE_MESH.name -> MuralMethod.SURFACE_MESH
                MuralMethod.CLOUD_OFFSET.name -> MuralMethod.CLOUD_OFFSET
                else -> MuralMethod.VOXEL_HASH // default
            }
        }

    override suspend fun setMuralMethod(method: MuralMethod) {
        context.dataStore.edit { preferences ->
            preferences[MURAL_METHOD] = method.name
        }
    }

    override suspend fun setArScanMode(mode: ArScanMode) {
        context.dataStore.edit { preferences ->
            preferences[AR_SCAN_MODE] = mode.name
        }
    }

    override val showAnchorBoundary: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[SHOW_ANCHOR_BOUNDARY] ?: false }

    override val forcedStereoUnstable: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[FORCED_STEREO_UNSTABLE] ?: false }

    override suspend fun setForcedStereoUnstable(unstable: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[FORCED_STEREO_UNSTABLE] = unstable
        }
    }

    override val stereoCapability: Flow<Int> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[STEREO_CAPABILITY] ?: -1 }

    override suspend fun setStereoCapability(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[STEREO_CAPABILITY] = value
        }
    }

    override suspend fun setShowAnchorBoundary(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SHOW_ANCHOR_BOUNDARY] = show
        }
    }

    override val isImperialUnits: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[IS_IMPERIAL_UNITS] ?: false }

    override suspend fun setImperialUnits(imperial: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IS_IMPERIAL_UNITS] = imperial
        }
    }

    override val backgroundColor: Flow<Int> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[BACKGROUND_COLOR] ?: 0xFF000000.toInt() }

    override suspend fun setBackgroundColor(argb: Int) {
        context.dataStore.edit { preferences ->
            preferences[BACKGROUND_COLOR] = argb
        }
    }

    override val parallaxMinDegrees: Flow<Float> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PARALLAX_MIN_DEG] ?: 4.0f }

    override suspend fun setParallaxMinDegrees(deg: Float) {
        context.dataStore.edit { preferences ->
            preferences[PARALLAX_MIN_DEG] = deg
        }
    }

    override val cameraTargetFps: Flow<Int> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[CAMERA_TARGET_FPS] ?: 60 }

    override suspend fun setCameraTargetFps(fps: Int) {
        context.dataStore.edit { preferences ->
            preferences[CAMERA_TARGET_FPS] = fps
        }
    }

    private fun throttleFlow(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>): Flow<Boolean> =
        context.dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map { preferences -> preferences[key] ?: true }

    override val throttleOnThermal: Flow<Boolean> = throttleFlow(THROTTLE_ON_THERMAL)
    override suspend fun setThrottleOnThermal(on: Boolean) {
        context.dataStore.edit { it[THROTTLE_ON_THERMAL] = on }
    }

    override val throttleOnPowerSave: Flow<Boolean> = throttleFlow(THROTTLE_ON_POWER_SAVE)
    override suspend fun setThrottleOnPowerSave(on: Boolean) {
        context.dataStore.edit { it[THROTTLE_ON_POWER_SAVE] = on }
    }

    override val throttleOnLowBattery: Flow<Boolean> = throttleFlow(THROTTLE_ON_LOW_BATTERY)
    override suspend fun setThrottleOnLowBattery(on: Boolean) {
        context.dataStore.edit { it[THROTTLE_ON_LOW_BATTERY] = on }
    }

    override val throttleOnLag: Flow<Boolean> = throttleFlow(THROTTLE_ON_LAG)
    override suspend fun setThrottleOnLag(on: Boolean) {
        context.dataStore.edit { it[THROTTLE_ON_LAG] = on }
    }

    override val adaptiveRateEnabled: Flow<Boolean> = throttleFlow(ADAPTIVE_RATE_ENABLED)
    override suspend fun setAdaptiveRateEnabled(on: Boolean) {
        context.dataStore.edit { it[ADAPTIVE_RATE_ENABLED] = on }
    }

    override val savedPalette: Flow<List<Int>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> PaletteCodec.decode(preferences[SAVED_PALETTE]) }

    override suspend fun setSavedPalette(colors: List<Int>) {
        context.dataStore.edit { it[SAVED_PALETTE] = PaletteCodec.encode(colors) }
    }

    override val inputSampleRateHz: Flow<Int> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        // 60 Hz by default rather than unthrottled: a stroke cannot be shown faster than the
        // display refreshes, so sampling above it spent power on frames nobody ever saw.
        .map { preferences -> preferences[INPUT_SAMPLE_RATE_HZ] ?: 60 }

    override suspend fun setInputSampleRateHz(hz: Int) {
        context.dataStore.edit { it[INPUT_SAMPLE_RATE_HZ] = hz.coerceIn(0, 240) }
    }

    override val canvasRenderScale: Flow<Float> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> (preferences[CANVAS_RENDER_SCALE] ?: 1f).coerceIn(0.25f, 1f) }

    override suspend fun setCanvasRenderScale(scale: Float) {
        context.dataStore.edit { it[CANVAS_RENDER_SCALE] = scale.coerceIn(0.25f, 1f) }
    }

    override val gestureMapping: Flow<Map<GestureSlot, GestureAction>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            GestureSlot.entries.associateWith { slot ->
                val raw = preferences[GESTURE_KEYS.getValue(slot)]
                GestureAction.entries.find { it.name == raw } ?: slot.defaultAction
            }
        }

    override suspend fun setGestureAction(slot: GestureSlot, action: GestureAction) {
        context.dataStore.edit { it[GESTURE_KEYS.getValue(slot)] = action.name }
    }

    override val toolUsage: Flow<com.hereliesaz.graffitixr.common.model.ToolUsage> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { com.hereliesaz.graffitixr.common.model.ToolUsageCodec.decode(it[TOOL_USAGE]) }

    override suspend fun recordToolUse(
        tool: com.hereliesaz.graffitixr.common.model.Tool,
        atMs: Long,
    ) {
        if (tool == com.hereliesaz.graffitixr.common.model.Tool.NONE) return
        context.dataStore.edit { prefs ->
            val next = com.hereliesaz.graffitixr.common.model.ToolUsageCodec
                .decode(prefs[TOOL_USAGE])
                .recording(tool, atMs)
            prefs[TOOL_USAGE] = com.hereliesaz.graffitixr.common.model.ToolUsageCodec.encode(next)
        }
    }

    override val favoriteTools: Flow<List<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        // A LIST in a string, not a stringSetPreferencesKey: DataStore's set has no order, and the
        // order the user pinned them in is the arrangement they are relying on.
        .map { prefs -> prefs[FAVORITE_TOOLS]?.split(',')?.filter { it.isNotBlank() } ?: emptyList() }

    override suspend fun toggleFavoriteTool(tool: com.hereliesaz.graffitixr.common.model.Tool) {
        if (tool == com.hereliesaz.graffitixr.common.model.Tool.NONE) return
        context.dataStore.edit { prefs ->
            val current = prefs[FAVORITE_TOOLS]?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
            // Un-pinning removes; pinning appends, so a newly pinned tool lands at the end rather
            // than displacing whatever the user had at the front.
            val next = if (tool.name in current) current - tool.name else current + tool.name
            prefs[FAVORITE_TOOLS] = next.joinToString(",")
        }
    }
}
