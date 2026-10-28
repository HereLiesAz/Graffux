package com.hereliesaz.graffitixr.data.repository

import com.hereliesaz.graffitixr.common.model.CarouselItemSettings
import android.content.Context
import androidx.compose.ui.graphics.toArgb
import com.hereliesaz.graffitixr.common.model.DEFAULT_CANVAS_BACKGROUND
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hereliesaz.graffitixr.common.model.GestureAction
import com.hereliesaz.graffitixr.common.model.GestureSlot
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject

import com.hereliesaz.graffitixr.common.util.PaletteCodec

private val Context.dataStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { listOf(RetiredSettingsMigration) },
)

class SettingsRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context
) : SettingsRepository {

    private val IS_RIGHT_HANDED = booleanPreferencesKey("is_right_handed")
    private val IS_IMPERIAL_UNITS = booleanPreferencesKey("is_imperial_units")
    private val propertiesPlacementKey = stringPreferencesKey("properties_placement")
    private val BRUSH_SIZE_FIXED_ON_SCREEN = booleanPreferencesKey("brush_size_fixed_on_screen")
    private val HIDDEN_BRUSH_TIP_IDS = stringSetPreferencesKey("hidden_brush_tip_ids")
    private val BACKGROUND_COLOR = intPreferencesKey("background_color")
    private val SAVED_PALETTE = stringPreferencesKey("saved_palette")
    private val INPUT_SAMPLE_RATE_HZ = intPreferencesKey("input_sample_rate_hz")
    private val CANVAS_RENDER_SCALE = floatPreferencesKey("canvas_render_scale")
    private val GESTURE_KEYS = GestureSlot.entries.associateWith { stringPreferencesKey("gesture_${it.name.lowercase()}") }
    private val TOOL_USAGE = stringPreferencesKey("tool_usage")
    private val FAVORITE_TOOLS = stringPreferencesKey("favorite_tools")
    private val carouselFavoritesKey = stringPreferencesKey("carousel_favorites")
    private val carouselItemSettingsKey = stringPreferencesKey("carousel_item_settings")

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

    override val isImperialUnits: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[IS_IMPERIAL_UNITS] ?: false }

    override suspend fun setImperialUnits(imperial: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IS_IMPERIAL_UNITS] = imperial
        }
    }

    override val propertiesPlacement: Flow<String?> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[propertiesPlacementKey] }

    override suspend fun setPropertiesPlacement(placement: String) {
        context.dataStore.edit { preferences ->
            preferences[propertiesPlacementKey] = placement
        }
    }

    override val brushSizeFixedOnScreen: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[BRUSH_SIZE_FIXED_ON_SCREEN] ?: false }

    override suspend fun setBrushSizeFixedOnScreen(fixed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BRUSH_SIZE_FIXED_ON_SCREEN] = fixed
        }
    }

    override val hiddenBrushTipIds: Flow<Set<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[HIDDEN_BRUSH_TIP_IDS] ?: emptySet() }

    override suspend fun setBrushTipHidden(compositeId: String, hidden: Boolean) {
        context.dataStore.edit { preferences ->
            val current = preferences[HIDDEN_BRUSH_TIP_IDS] ?: emptySet()
            preferences[HIDDEN_BRUSH_TIP_IDS] = if (hidden) current + compositeId else current - compositeId
        }
    }

    override val backgroundColor: Flow<Int> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[BACKGROUND_COLOR] ?: DEFAULT_CANVAS_BACKGROUND.toArgb() }

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

    override val carouselFavorites: Flow<List<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> decodeCarouselFavorites(prefs[carouselFavoritesKey]) }

    override suspend fun toggleCarouselFavorite(key: String) {
        if (key.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = decodeCarouselFavorites(prefs[carouselFavoritesKey])
            val next = if (key in current) current - key else current + key
            prefs[carouselFavoritesKey] = next.joinToString(CAROUSEL_FAVORITES_SEPARATOR)
        }
    }

    override val carouselItemSettings: Flow<Map<String, CarouselItemSettings>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> CarouselItemSettings.decode(prefs[carouselItemSettingsKey]) }

    override suspend fun setCarouselItemSettings(settings: Map<String, CarouselItemSettings>) {
        context.dataStore.edit { prefs -> prefs[carouselItemSettingsKey] = CarouselItemSettings.encode(settings) }
    }

    // Newline-separated, not comma: the keys embed brush names, and a user can put a comma in one.
    private fun decodeCarouselFavorites(raw: String?): List<String> =
        raw?.split(CAROUSEL_FAVORITES_SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList()

    private companion object {
        const val CAROUSEL_FAVORITES_SEPARATOR = "\n"
    }
}
