package com.hereliesaz.graffitixr.domain.repository

import com.hereliesaz.graffitixr.common.model.GestureAction
import com.hereliesaz.graffitixr.common.model.GestureSlot
import kotlinx.coroutines.flow.Flow

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
     * How often and how recently each tool has been picked, for the shortcuts sheet's Recent and
     * Frequent strips. Written on every tool selection, so the implementation must be cheap.
     */
    val toolUsage: Flow<com.hereliesaz.graffitixr.common.model.ToolUsage>
    suspend fun recordToolUse(tool: com.hereliesaz.graffitixr.common.model.Tool, atMs: Long)

    /**
     * The tools the user pinned, in the order they pinned them — the sheet's Favourites strip.
     *
     * A list rather than a set, because the order *is* the arrangement: a favourites strip you have
     * put in an order and the app then re-sorts is a strip you have to read every time instead of
     * reaching for the position you remember.
     */
    val favoriteTools: Flow<List<String>>
    suspend fun toggleFavoriteTool(tool: com.hereliesaz.graffitixr.common.model.Tool)

    /**
     * The bottom carousel's starred entries, as the entries' stable keys (`builtin.<name>`,
     * `custom.<id>`, `ink.<id>`, a tool id, `stabilizer.<level>`, ...), in the order they were
     * starred. Same list-not-set reasoning as [favoriteTools].
     */
    val carouselFavorites: Flow<List<String>>

    /** Stars [key], or un-stars it if already starred. Un-starring keeps the rest in place. */
    suspend fun toggleCarouselFavorite(key: String)

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
