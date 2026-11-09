package com.hereliesaz.graffitixr.data.repository

import com.hereliesaz.graffitixr.common.model.CarouselItemSettings
import android.content.Context
import androidx.compose.ui.graphics.toArgb
import com.hereliesaz.graffitixr.common.model.DEFAULT_CANVAS_BACKGROUND
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import com.hereliesaz.graffitixr.common.model.GestureAction
import com.hereliesaz.graffitixr.common.model.GestureSlot
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException
import javax.inject.Inject

import com.hereliesaz.graffitixr.common.util.PaletteCodec

/**
 * What to do when the settings file cannot be read (a torn write, bit rot): keep a copy of the bad file beside it
 * (`settings.preferences_pb.corrupt-<time>`), then carry on with defaults, instead of crashing every read and write.
 * [file] is set by [SettingsRepositoryImpl] before the store is first used.
 */
internal object SettingsCorruptionHandler {
    @Volatile
    var file: File? = null

    /** The handler the store uses: keep a copy of the bad file, then start from defaults. */
    val handler = ReplaceFileCorruptionHandler<Preferences> {
        keepCopy(file)
        emptyPreferences()
    }

    /**
     * Copies [bad] to a `.corrupt-<time>[-n]` sibling and returns it, or null if there is nothing to copy or the copy
     * failed (which is logged, never silent). A name already taken is never overwritten and never skipped: the next
     * free suffix is used, so two corrupt files in the same millisecond are both kept. [now] is injectable for tests.
     */
    internal fun keepCopy(bad: File?, now: () -> Long = System::currentTimeMillis): File? {
        if (bad == null || !bad.isFile) return null
        val stamp = now()
        val copy = (0 until MAX_COPY_ATTEMPTS).asSequence()
            .map { n -> File(bad.parentFile, "${bad.name}.corrupt-$stamp" + if (n == 0) "" else "-$n") }
            .firstOrNull { !it.exists() }
        val saved = copy?.let { runCatching { it.also { target -> bad.copyTo(target, overwrite = false) } } }
        saved?.exceptionOrNull()?.let { android.util.Log.e("SettingsRepository", "Could not keep a copy", it) }
        if (copy == null) android.util.Log.e("SettingsRepository", "No free name to keep the unreadable settings file")
        return saved?.getOrNull()
    }

    private const val MAX_COPY_ATTEMPTS = 1_000
}

private val Context.dataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = SettingsCorruptionHandler.handler,
    produceMigrations = { listOf(RetiredSettingsMigration) },
)

class SettingsRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context
) : SettingsRepository {

    init {
        SettingsCorruptionHandler.file = context.preferencesDataStoreFile("settings")
    }

    /**
     * Every write goes through here: a failed write (disk full, a corrupt store) is logged and dropped, never thrown
     * into a bare `launch` where it would crash the app. Cancellation is rethrown.
     */
    private suspend fun update(transform: suspend (MutablePreferences) -> Unit): Boolean {
        try {
            context.dataStore.edit(transform)
            return true
        } catch (e: IOException) {
            android.util.Log.w("SettingsRepository", "Could not save a setting", e)
        } catch (e: CorruptionException) {
            android.util.Log.w("SettingsRepository", "Could not save a setting (unreadable store)", e)
        }
        return false
    }

    private val IS_RIGHT_HANDED = booleanPreferencesKey("is_right_handed")
    private val IS_IMPERIAL_UNITS = booleanPreferencesKey("is_imperial_units")
    private val propertiesPlacementKey = stringPreferencesKey("properties_placement")
    private val BRUSH_SIZE_FIXED_ON_SCREEN = booleanPreferencesKey("brush_size_fixed_on_screen")
    private val surfaceOutlineAssistKey = booleanPreferencesKey("surface_outline_assist")
    private val HIDDEN_BRUSH_TIP_IDS = stringSetPreferencesKey("hidden_brush_tip_ids")
    private val BACKGROUND_COLOR = intPreferencesKey("background_color")
    private val SAVED_PALETTE = stringPreferencesKey("saved_palette")
    private val INPUT_SAMPLE_RATE_HZ = intPreferencesKey("input_sample_rate_hz")
    private val CANVAS_RENDER_SCALE = floatPreferencesKey("canvas_render_scale")
    private val GESTURE_KEYS = GestureSlot.entries.associateWith { stringPreferencesKey("gesture_${it.name.lowercase()}") }
    private val carouselFavoritesKey = stringPreferencesKey("carousel_favorites")
    private val carouselItemSettingsKey = stringPreferencesKey("carousel_item_settings")
    private val savedRecipesKey = stringPreferencesKey("saved_recipes")
    private val savedRecipesBackupKey = stringPreferencesKey("saved_recipes_backup")
    private val swatchesMigratedKey = booleanPreferencesKey("swatches_migrated_to_recipes")

    override val isRightHanded: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            preferences[IS_RIGHT_HANDED] ?: true
        }

    override suspend fun setRightHanded(isRight: Boolean) {
        update { preferences ->
            preferences[IS_RIGHT_HANDED] = isRight
        }
    }

    override val isImperialUnits: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[IS_IMPERIAL_UNITS] ?: false }

    override suspend fun setImperialUnits(imperial: Boolean) {
        update { preferences ->
            preferences[IS_IMPERIAL_UNITS] = imperial
        }
    }

    override val propertiesPlacement: Flow<String?> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[propertiesPlacementKey] }

    override suspend fun setPropertiesPlacement(placement: String) {
        update { preferences ->
            preferences[propertiesPlacementKey] = placement
        }
    }

    override val brushSizeFixedOnScreen: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[BRUSH_SIZE_FIXED_ON_SCREEN] ?: false }

    override suspend fun setBrushSizeFixedOnScreen(fixed: Boolean) {
        update { preferences ->
            preferences[BRUSH_SIZE_FIXED_ON_SCREEN] = fixed
        }
    }

    override val surfaceOutlineAssist: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[surfaceOutlineAssistKey] ?: true }

    override suspend fun setSurfaceOutlineAssist(enabled: Boolean) {
        update { preferences ->
            preferences[surfaceOutlineAssistKey] = enabled
        }
    }

    override val hiddenBrushTipIds: Flow<Set<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[HIDDEN_BRUSH_TIP_IDS] ?: emptySet() }

    override suspend fun setBrushTipHidden(compositeId: String, hidden: Boolean) {
        update { preferences ->
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

    override suspend fun readSavedPalette(): List<Int>? = try {
        PaletteCodec.decode(context.dataStore.data.first()[SAVED_PALETTE])
    } catch (e: IOException) {
        android.util.Log.w("SettingsRepository", "Could not read the saved swatches", e)
        null
    } catch (e: CorruptionException) {
        android.util.Log.w("SettingsRepository", "Could not read the saved swatches (unreadable store)", e)
        null
    }

    override suspend fun addSavedSwatch(argb: Int) {
        // read, change and write inside ONE edit: two concurrent saves cannot lose each other's swatch, and a read
        // that fails throws out of the edit without writing anything (never an "empty palette" that gets saved).
        update { p ->
            val next = PaletteCodec.add(PaletteCodec.decode(p[SAVED_PALETTE]), argb)
            p[SAVED_PALETTE] = PaletteCodec.encode(next)
        }
    }

    override suspend fun removeSavedSwatch(argb: Int) {
        update { p ->
            val next = PaletteCodec.decode(p[SAVED_PALETTE]).filterNot { it == argb }
            p[SAVED_PALETTE] = PaletteCodec.encode(next)
        }
    }

    override suspend fun setSavedPalette(colors: List<Int>) {
        update { it[SAVED_PALETTE] = PaletteCodec.encode(colors) }
    }

    override val inputSampleRateHz: Flow<Int> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        // 60 Hz by default rather than unthrottled: a stroke cannot be shown faster than the
        // display refreshes, so sampling above it spent power on frames nobody ever saw.
        .map { preferences -> preferences[INPUT_SAMPLE_RATE_HZ] ?: 60 }

    override suspend fun setInputSampleRateHz(hz: Int) {
        update { it[INPUT_SAMPLE_RATE_HZ] = hz.coerceIn(0, 240) }
    }

    override val canvasRenderScale: Flow<Float> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> (preferences[CANVAS_RENDER_SCALE] ?: 1f).coerceIn(0.25f, 1f) }

    override suspend fun setCanvasRenderScale(scale: Float) {
        update { it[CANVAS_RENDER_SCALE] = scale.coerceIn(0.25f, 1f) }
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
        update { it[GESTURE_KEYS.getValue(slot)] = action.name }
    }

    override val carouselFavorites: Flow<List<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> decodeCarouselFavorites(prefs[carouselFavoritesKey]) }

    override suspend fun toggleCarouselFavorite(key: String) {
        if (key.isBlank()) return
        update { prefs ->
            val current = decodeCarouselFavorites(prefs[carouselFavoritesKey])
            val next = if (key in current) current - key else current + key
            prefs[carouselFavoritesKey] = next.joinToString(CAROUSEL_FAVORITES_SEPARATOR)
        }
    }

    override suspend fun renameCarouselKeys(renames: Map<String, String>) {
        if (renames.isEmpty()) return
        update { prefs ->
            val favourites = decodeCarouselFavorites(prefs[carouselFavoritesKey]).map { renames[it] ?: it }.distinct()
            prefs[carouselFavoritesKey] = favourites.joinToString(CAROUSEL_FAVORITES_SEPARATOR)
            val settings = CarouselItemSettings.decode(prefs[carouselItemSettingsKey])
            val moved = LinkedHashMap<String, CarouselItemSettings>()
            settings.forEach { (k, v) -> moved.putIfAbsent(renames[k] ?: k, v) }
            // a setting already stored under the new key wins over one moved onto it
            settings.forEach { (k, v) -> if (k !in renames) moved[k] = v }
            prefs[carouselItemSettingsKey] = CarouselItemSettings.encode(moved)
        }
    }

    override val savedRecipes: Flow<String?> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[savedRecipesKey] }

    override suspend fun updateSavedRecipes(transform: (String?) -> String?): Boolean {
        var wrote = false
        val persisted = update { prefs ->
            val next = transform(prefs[savedRecipesKey])
            if (next != null) {
                prefs[savedRecipesKey] = next
                wrote = true
            }
        }
        return persisted && wrote
    }

    override suspend fun backupSavedRecipes(original: String): Boolean {
        var kept = false
        val persisted = update { prefs ->
            // the first backup stays: a later repair never replaces the earliest original
            if (prefs[savedRecipesBackupKey] == null) prefs[savedRecipesBackupKey] = original
            kept = true
        }
        return persisted && kept
    }

    override val swatchesMigrated: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[swatchesMigratedKey] ?: false }

    override suspend fun setSwatchesMigrated() {
        update { it[swatchesMigratedKey] = true }
    }

    override val carouselItemSettings: Flow<Map<String, CarouselItemSettings>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> CarouselItemSettings.decode(prefs[carouselItemSettingsKey]) }

    override suspend fun setCarouselItemSettings(settings: Map<String, CarouselItemSettings>) {
        update { prefs -> prefs[carouselItemSettingsKey] = CarouselItemSettings.encode(settings) }
    }

    // Newline-separated, not comma: the keys embed brush names, and a user can put a comma in one.
    private fun decodeCarouselFavorites(raw: String?): List<String> =
        raw?.split(CAROUSEL_FAVORITES_SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList()

    private companion object {
        const val CAROUSEL_FAVORITES_SEPARATOR = "\n"
    }
}
