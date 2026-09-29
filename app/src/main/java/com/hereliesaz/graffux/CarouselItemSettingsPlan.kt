package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.model.CarouselItemSettings
import com.hereliesaz.graffitixr.common.model.InkUtensil
import com.hereliesaz.graffitixr.common.model.Tool
import kotlin.math.abs

/*
 * Per-item carousel settings: every stamp brush, Ink utensil and effect tool owns its own Size, Flow,
 * Opacity, Softness (and, for Smudge, Strength). The live editor fields (`EditorUiState.brushSize`
 * and friends) are only ever the settings of the item in hand:
 *
 *  - selecting an item — from the carousel, the rail, the shortcuts sheet, anywhere — applies that
 *    item's saved settings ([CarouselItemSettingsSync] watches the active item's key);
 *  - any edit to the live fields while an item is in hand (a hero slider, Tool Options, the size
 *    HUD) is saved back to that item and no other;
 *  - a hero slider on an item that is *not* in hand edits that item's stored settings only.
 *
 * Options (stabilizer stops, selection shapes) are not paint items: their sliders still drive the
 * one global setting they name. A Smudge mode card's Strength is Smudge's own.
 */

/** The key whose settings item [entry] shows and edits, or null for an item that owns none. */
internal fun carouselSettingsKey(entry: CarouselEntry): String? = when (entry.action) {
    is CarouselAction.BuiltInBrush, is CarouselAction.CustomBrush, is CarouselAction.ExtensionBrush,
    is CarouselAction.InkUtensilPick, is CarouselAction.PickTool,
    -> entry.key
    is CarouselAction.SmudgeMode -> SMUDGE_SETTINGS_KEY
    is CarouselAction.StabilizerLevel, is CarouselAction.Stabilizer, is CarouselAction.SelectShape,
    is CarouselAction.ExtensionContribution, is CarouselAction.ExtensionLut, CarouselAction.OpenToolOptions,
    -> null
}

/** Smudge's tool id — the one item that owns Strength. */
internal val SMUDGE_SETTINGS_KEY: String = TOOL_CATALOG.getValue(Tool.SMUDGE).id

/**
 * The key of the paint item in hand, derived from editor state the same way the carousel lights its
 * entries, so an item picked outside the carousel is recognised too. Null when nothing that owns
 * settings is in hand (Eraser, Pen, Select, …): the live values are then nobody's.
 */
internal fun activeCarouselSettingsKey(input: CarouselInputs): String? = when {
    input.activeTool in EFFECT_TOOLS -> TOOL_CATALOG[input.activeTool]?.id
    input.activeTool != Tool.BRUSH -> null
    input.activeInkUtensil != null -> "ink.${input.activeInkUtensil.id}"
    else -> carouselEntries(CarouselCategory.BRUSHES, input.copy(favorites = emptyList()))
        .firstOrNull { it.selected }?.key
}

private const val INK_PEN_SIZE = 12f
private const val INK_MARKER_SIZE = 30f
private const val INK_HIGHLIGHTER_SIZE = 40f
private const val BLUR_SIZE = 80f
private const val SOFT_EFFECT = 0.5f

/**
 * An item's settings before anyone adjusts it — its preset. Stamp brushes carry no size/flow of
 * their own ([com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush] stores tip shape, not paint
 * amount), so they start at the editor's defaults; Ink utensils start at a size that reads as that
 * utensil; the pixel effects that soften start soft.
 */
internal fun carouselItemDefaults(key: String): CarouselItemSettings {
    val base = CarouselItemSettings()
    return when (key) {
        "ink.${InkUtensil.PEN.id}", "ink.${InkUtensil.DASHED_LINE.id}" -> base.copy(size = INK_PEN_SIZE)
        "ink.${InkUtensil.MARKER.id}" -> base.copy(size = INK_MARKER_SIZE)
        "ink.${InkUtensil.HIGHLIGHTER.id}" -> base.copy(size = INK_HIGHLIGHTER_SIZE)
        TOOL_CATALOG[Tool.BLUR]?.id -> base.copy(size = BLUR_SIZE, softness = SOFT_EFFECT)
        TOOL_CATALOG[Tool.SMUDGE]?.id, TOOL_CATALOG[Tool.DODGE]?.id, TOOL_CATALOG[Tool.BURN]?.id ->
            base.copy(softness = SOFT_EFFECT)
        else -> base
    }
}

/** [key]'s settings: what was saved for it, else its preset. */
internal fun carouselItemSettingsFor(key: String, stored: Map<String, CarouselItemSettings>?): CarouselItemSettings =
    stored?.get(key) ?: carouselItemDefaults(key)

/** [s] with the per-item field [setter] drives set to [value]; null for a global setter. */
internal fun CarouselItemSettings.adjusted(setter: HeroSetter, value: Float): CarouselItemSettings? = when (setter) {
    HeroSetter.BRUSH_SIZE -> copy(size = value)
    HeroSetter.BRUSH_FLOW -> copy(flow = value)
    HeroSetter.BRUSH_OPACITY -> copy(opacity = value)
    HeroSetter.BRUSH_SOFTNESS -> copy(softness = value)
    HeroSetter.SMUDGE_STRENGTH -> copy(strength = value)
    HeroSetter.SMUDGE_LOAD, HeroSetter.SMUDGE_OPACITY, HeroSetter.STABILIZER, HeroSetter.WAND_TOLERANCE -> null
}?.sanitized()

/** [this] hero state with the per-item fields replaced by [s]'s; the global ones are kept. */
internal fun HeroAdjustmentState.withItem(s: CarouselItemSettings?): HeroAdjustmentState = if (s == null) {
    this
} else {
    copy(
        brushSize = s.size, brushFlow = s.flow, brushOpacity = s.opacity, brushFeathering = s.softness,
        smudgeRate = s.strength,
    )
}

private const val EPSILON = 1e-4f

private fun near(a: Float, b: Float) = abs(a - b) <= EPSILON

/** Equal within float noise, on the fields [key] owns (Strength only for Smudge). */
internal fun sameSettings(key: String, a: CarouselItemSettings, b: CarouselItemSettings): Boolean =
    near(a.size, b.size) && near(a.flow, b.flow) && near(a.opacity, b.opacity) && near(a.softness, b.softness) &&
        (key != SMUDGE_SETTINGS_KEY || near(a.strength, b.strength))

/** What [CarouselItemSettingsSync.observe] asks the host to do. */
internal sealed interface ItemSettingsDecision {
    data object None : ItemSettingsDecision

    /** Make [settings] the live values (the item in hand changed). */
    data class Apply(val settings: CarouselItemSettings, val includeStrength: Boolean) : ItemSettingsDecision

    /** Store [settings] as [key]'s own (the live values changed under it). */
    data class Save(val key: String, val settings: CarouselItemSettings) : ItemSettingsDecision
}

/**
 * Keeps the live paint settings and the item in hand in step. Fed every change of either; pure, so
 * it is unit-tested without a ViewModel.
 *
 * Migration: the first time a session sees an item in hand that has nothing saved, the live values
 * are *adopted* as that item's own instead of being replaced by its preset. Before per-item settings
 * existed the live values were the user's one global Size/Flow/…; they were set for, and last used
 * with, the item in hand, so that item — and only that item — inherits them. Seeding every item
 * would make them all identical and throw the presets away; seeding none would visibly change the
 * brush in hand on the first launch after the update.
 */
internal class CarouselItemSettingsSync {
    private var key: String? = null
    private var pending: CarouselItemSettings? = null
    private var sawFirstItem = false

    @Suppress("ReturnCount") // one early exit per case reads plainer than nesting
    fun observe(
        activeKey: String?,
        live: CarouselItemSettings,
        stored: Map<String, CarouselItemSettings>?,
    ): ItemSettingsDecision {
        if (stored == null) return ItemSettingsDecision.None // not loaded yet
        if (activeKey != key) return switchTo(activeKey, live, stored)
        val k = key ?: return ItemSettingsDecision.None
        pending?.let { expected ->
            // The applied values have not landed yet; wait for them rather than save stale ones.
            if (!sameSettings(k, live, expected)) return ItemSettingsDecision.None
            pending = null
        }
        val own = carouselItemSettingsFor(k, stored)
        if (sameSettings(k, live, own)) return ItemSettingsDecision.None
        // Strength belongs to Smudge alone: another item keeps whatever it had.
        val saved = if (k == SMUDGE_SETTINGS_KEY) live else live.copy(strength = own.strength)
        return ItemSettingsDecision.Save(k, saved.sanitized())
    }

    @Suppress("ReturnCount")
    private fun switchTo(
        activeKey: String?,
        live: CarouselItemSettings,
        stored: Map<String, CarouselItemSettings>,
    ): ItemSettingsDecision {
        key = activeKey
        pending = null
        if (activeKey == null) return ItemSettingsDecision.None
        val first = !sawFirstItem
        sawFirstItem = true
        if (first && activeKey !in stored) return ItemSettingsDecision.Save(activeKey, live.sanitized())
        val target = carouselItemSettingsFor(activeKey, stored)
        if (sameSettings(activeKey, live, target)) return ItemSettingsDecision.None
        pending = target
        return ItemSettingsDecision.Apply(target, includeStrength = activeKey == SMUDGE_SETTINGS_KEY)
    }
}
