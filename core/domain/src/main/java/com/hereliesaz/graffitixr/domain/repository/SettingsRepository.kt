package com.hereliesaz.graffitixr.domain.repository

import com.hereliesaz.graffitixr.common.model.GestureAction
import com.hereliesaz.graffitixr.common.model.GestureSlot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Interface for managing application-wide settings.
 */
interface SettingsRepository {
    /**
     * A flow emitting the current user preference for handedness.
     * True for right-handed (default), False for left-handed.
     */
    val isRightHanded: Flow<Boolean>

    /**
     * Updates the user's handedness preference.
     *
     * @param isRight True for right-handed, False for left-handed.
     */
    suspend fun setRightHanded(isRight: Boolean)

    /** Whether distances are displayed in imperial (ft) rather than metric (m/cm). */
    val isImperialUnits: Flow<Boolean>

    suspend fun setImperialUnits(imperial: Boolean)

    /**
     * Where a tablet or desktop shows the design properties: `"side_panel"` or `"bottom_sheet"`,
     * null until the user picks (the side panel). Phones always use the sheet and ignore it.
     */
    val propertiesPlacement: Flow<String?>

    suspend fun setPropertiesPlacement(placement: String)

    /**
     * Whether the brush size slider is a document-space footprint (off, default -- the same slider
     * value paints the same real mark on the artwork at any zoom, matching Photoshop/Procreate) or an
     * on-screen footprint (on -- the brush looks the same size on screen at any zoom, so the real
     * mark it leaves shrinks zoomed in and grows zoomed out instead).
     */
    val brushSizeFixedOnScreen: Flow<Boolean>

    suspend fun setBrushSizeFixedOnScreen(fixed: Boolean)

    /**
     * Whether a freehand surface outline is cleaned up when the finger lifts (straight lines straightened,
     * squares and circles made exact, symmetric shapes mirrored; `OutlineAssist`). On by default.
     */
    val surfaceOutlineAssist: Flow<Boolean>

    suspend fun setSurfaceOutlineAssist(enabled: Boolean)

    /**
     * Installed brush-tip ids the user has hidden from pickers (the brush rail, the brush group) --
     * an extension can bundle many brushes, and not every one belongs in a strip reached for
     * mid-painting. Each id is `"<extensionId>::<assetIndex>"`, matching
     * `ExtensionRepository.InstalledBrushAsset`. Hiding is presentation-only: a hidden brush still
     * loads and paints fine if selected some other way (Brush Studio editing it, say); it just
     * doesn't show up in a picker. Empty by default -- nothing is hidden until the user hides it.
     */
    val hiddenBrushTipIds: Flow<Set<String>>

    suspend fun setBrushTipHidden(compositeId: String, hidden: Boolean)

    /** Canvas background color as ARGB Int. Default is opaque black (0xFF000000). */
    val backgroundColor: Flow<Int>

    /**
     * The user's saved colour swatches, as ARGB ints, in the order they arranged them. Ordered, so
     * it is stored as one encoded string rather than on a string-set preference.
     */
    val savedPalette: Flow<List<Int>>

    suspend fun setSavedPalette(colors: List<Int>)

    /**
     * Adds [argb] to the saved palette (a no-op if it is there), as ONE atomic read-modify-write so two saves at once
     * cannot lose one. The default is for fakes; the real store does it inside a single DataStore edit.
     */
    suspend fun addSavedSwatch(argb: Int) {
        val current = savedPalette.first()
        val next = com.hereliesaz.graffitixr.common.util.PaletteCodec.add(current, argb)
        if (next !== current) setSavedPalette(next)
    }

    /** Removes every swatch equal to [argb], atomically (see [addSavedSwatch]). */
    suspend fun removeSavedSwatch(argb: Int) {
        val current = savedPalette.first()
        val next = current.filterNot { it == argb }
        if (next.size != current.size) setSavedPalette(next)
    }

    /**
     * Ceiling on how many touch samples per second a stroke records and previews, in Hz.
     * Modern panels report touch at 120-240 Hz and the editor previously rendered a frame per
     * sample, so this is the main lever on drawing's power draw. 0 means unthrottled.
     */
    val inputSampleRateHz: Flow<Int>
    suspend fun setInputSampleRateHz(hz: Int)

    /**
     * Fraction of the screen resolution new layers are allocated at, in (0, 1]. Every layer costs a
     * full ARGB_8888 bitmap, so this is the main lever on memory: halving it quarters the bytes.
     */
    val canvasRenderScale: Flow<Float>
    suspend fun setCanvasRenderScale(scale: Float)

    /**
     * Which action each customizable multi-finger gesture triggers (see [GestureSlot]);
     * [GestureAction.NONE] disables that gesture. Missing entries fall back to the slot's own
     * [GestureSlot.defaultAction] — the historical hardcoded behaviour.
     */
    val gestureMapping: Flow<Map<GestureSlot, GestureAction>>
    suspend fun setGestureAction(slot: GestureSlot, action: GestureAction)

    /**
     * The bottom carousel's starred entries, as the entries' stable keys (`builtin.<name>`,
     * `custom.<id>`, `ink.<id>`, a tool id, `stabilizer.<level>`, ...), in the order they were
     * starred. A list rather than a set, because the order *is* the arrangement: a favourites
     * page the app re-sorts is one you have to read every time instead of reaching for the position
     * you remember.
     */
    val carouselFavorites: Flow<List<String>>

    /** Stars [key], or un-stars it if already starred. Un-starring keeps the rest in place. */
    suspend fun toggleCarouselFavorite(key: String)

    /** The app-level saved mixes (spec/painterly.md 6.4) as the recipe-book JSON, or null when there are none. */
    val savedRecipes: Flow<String?> get() = kotlinx.coroutines.flow.flowOf(null)

    /**
     * Changes the app-level recipe book in ONE atomic DataStore edit: [transform] gets the stored text (null: none) and
     * returns the new text, or null to leave it exactly as it is (for example when the stored text could not be read:
     * it is never replaced by a book built from nothing). True only if a new text was really persisted: a write that
     * failed (disk full, an unreadable store) is false, so the caller never reports a save that did not happen.
     * No-op (false) by default (fakes).
     */
    suspend fun updateSavedRecipes(transform: (String?) -> String?): Boolean = false

    /** Keeps [original] (an app recipe book some of whose recipes could not be read) once, before it is repaired. */
    suspend fun backupSavedRecipes(original: String): Boolean = false

    /** Whether the old saved swatches were turned into recipes once (they are left in place, so it can be undone). */
    val swatchesMigrated: Flow<Boolean> get() = kotlinx.coroutines.flow.flowOf(true)

    suspend fun setSwatchesMigrated() {}

    /**
     * Renames carousel keys ([renames]: old key to new key) in the favourites (keeping order, dropping a duplicate)
     * and in the per-item settings (an existing entry of the new key wins), as one atomic edit. No-op by default.
     */
    suspend fun renameCarouselKeys(renames: Map<String, String>) {}

    /**
     * Each bottom-carousel item's own paint settings, by the same stable keys as
     * [carouselFavorites]. An item with no entry has never been adjusted and uses its defaults.
     */
    val carouselItemSettings: Flow<Map<String, com.hereliesaz.graffitixr.common.model.CarouselItemSettings>>

    /** Replaces the whole per-item settings map. */
    suspend fun setCarouselItemSettings(
        settings: Map<String, com.hereliesaz.graffitixr.common.model.CarouselItemSettings>,
    )
}
