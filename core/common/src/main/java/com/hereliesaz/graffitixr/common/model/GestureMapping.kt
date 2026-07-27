package com.hereliesaz.graffitixr.common.model

/** An action a customizable multi-finger gesture can trigger. [NONE] disables that gesture. */
enum class GestureAction(val label: String) {
    NONE("Off"),
    UNDO("Undo"),
    REDO("Redo"),
    HIDE_UI("Hide UI"),
    QUICK_MENU("Quick Menu"),
    CLEAR_LAYER("Clear Layer"),
}

/**
 * A multi-finger touch gesture the editor recognizes — see EditorScreen's `multiFingerTaps`. Each
 * carries its own historical default so [DEFAULT_GESTURE_MAPPING] reproduces exactly the hardcoded
 * behaviour these gestures had before they became reassignable.
 */
enum class GestureSlot(val defaultAction: GestureAction, val label: String) {
    TWO_FINGER_TAP(GestureAction.UNDO, "Two-finger tap"),
    THREE_FINGER_TAP(GestureAction.REDO, "Three-finger tap"),
    FOUR_FINGER_TAP(GestureAction.HIDE_UI, "Four-finger tap"),
    FOUR_FINGER_HOLD(GestureAction.QUICK_MENU, "Four-finger hold"),
    THREE_FINGER_SCRUB(GestureAction.CLEAR_LAYER, "Three-finger scrub"),
}

val DEFAULT_GESTURE_MAPPING: Map<GestureSlot, GestureAction> =
    GestureSlot.entries.associateWith { it.defaultAction }
