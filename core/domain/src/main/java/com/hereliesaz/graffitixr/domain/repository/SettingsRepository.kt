package com.hereliesaz.graffitixr.domain.repository

import com.hereliesaz.graffitixr.common.model.ArScanMode
import com.hereliesaz.graffitixr.common.model.GestureAction
import com.hereliesaz.graffitixr.common.model.GestureSlot
import com.hereliesaz.graffitixr.common.model.MuralMethod
import com.hereliesaz.graffitixr.common.model.AppLanguage
import kotlinx.coroutines.flow.Flow

/**
 * Interface for managing application-wide settings.
 */
interface SettingsRepository {
    /**
     * A flow emitting the current user preference for app language.
     */
    val language: Flow<AppLanguage>

    /**
     * Updates the user's language preference.
     */
    suspend fun setLanguage(language: AppLanguage)

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

    /** Which AR depth/mapping mode the user has selected. Defaults to [ArScanMode.MURAL]. */
    val arScanMode: Flow<ArScanMode>

    suspend fun setArScanMode(mode: ArScanMode)
    
    /** The specific engine used when [ArScanMode.MURAL] is active. */
    val muralMethod: Flow<MuralMethod>
    
    suspend fun setMuralMethod(method: MuralMethod)

    /** Whether to draw an orange boundary rectangle around the AR overlay quad when anchor is active. */
    val showAnchorBoundary: Flow<Boolean>

    suspend fun setShowAnchorBoundary(show: Boolean)

    /**
     * Set once a device proves it can't run forced hardware-stereo (ARCore motion-stereo disparity
     * fails / VIO never tracks): future sessions skip the stereo config and stay on Canvas, so the
     * broken path can't thrash the device. Cleared when the user explicitly re-selects Mural.
     */
    val forcedStereoUnstable: Flow<Boolean>

    suspend fun setForcedStereoUnstable(unstable: Boolean)

    /**
     * Cached result of the one-time hardware-stereo capability probe:
     * -1 = not yet probed, 0 = device can't run forced stereo (use mono), 1 = stereo tracks (use it).
     * Probing runs a short throwaway stereo session on a worker thread the first time AR is entered,
     * so we only adopt the dual-lens path on a device whose motion-stereo actually tracks.
     */
    val stereoCapability: Flow<Int>

    suspend fun setStereoCapability(value: Int)

    /** Whether distances are displayed in imperial (ft) rather than metric (m/cm). */
    val isImperialUnits: Flow<Boolean>

    suspend fun setImperialUnits(imperial: Boolean)

    /**
     * Whether the brush size slider is a document-space footprint (off, default -- the same slider
     * value paints the same real mark on the artwork at any zoom, matching Photoshop/Procreate) or an
     * on-screen footprint (on -- the brush looks the same size on screen at any zoom, so the real
     * mark it leaves shrinks zoomed in and grows zoomed out instead).
     */
    val brushSizeFixedOnScreen: Flow<Boolean>

    suspend fun setBrushSizeFixedOnScreen(fixed: Boolean)

    /** Canvas background color as ARGB Int. Default is opaque black (0xFF000000). */
    val backgroundColor: Flow<Int>
    suspend fun setBackgroundColor(argb: Int)

    /** Minimum viewpoint shift (degrees) before a re-observation parallax-verifies a voxel. Default 4. */
    val parallaxMinDegrees: Flow<Float>
    suspend fun setParallaxMinDegrees(deg: Float)

    /** ARCore camera target frame rate: 60 (default) or 30. Lower = less power/heat. */
    val cameraTargetFps: Flow<Int>
    suspend fun setCameraTargetFps(fps: Int)

    /** Perception-throttle triggers: each, when on, drops perception to 30fps while active. Default on. */
    val throttleOnThermal: Flow<Boolean>
    suspend fun setThrottleOnThermal(on: Boolean)
    val throttleOnPowerSave: Flow<Boolean>
    suspend fun setThrottleOnPowerSave(on: Boolean)
    val throttleOnLowBattery: Flow<Boolean>
    suspend fun setThrottleOnLowBattery(on: Boolean)
    val throttleOnLag: Flow<Boolean>
    suspend fun setThrottleOnLag(on: Boolean)

    /**
     * Master toggle for the adaptive AR frame-rate coach (gates heavy SLAM work while idle, plus the
     * battery-tier degradation). Default on. Off = always full rate (more drain, no behaviour change).
     */
    val adaptiveRateEnabled: Flow<Boolean>
    suspend fun setAdaptiveRateEnabled(on: Boolean)

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
}
