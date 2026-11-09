package com.hereliesaz.graffux

import androidx.annotation.StringRes
import com.hereliesaz.graffitixr.common.model.GestureAction
import com.hereliesaz.graffitixr.common.model.GestureSlot

/** The Settings and gesture-help name of a multi-finger gesture, as a string resource. */
@StringRes
internal fun GestureSlot.labelRes(): Int = when (this) {
    GestureSlot.TWO_FINGER_TAP -> R.string.gesture_slot_two_finger_tap
    GestureSlot.THREE_FINGER_TAP -> R.string.gesture_slot_three_finger_tap
    GestureSlot.FOUR_FINGER_TAP -> R.string.gesture_slot_four_finger_tap
    GestureSlot.FOUR_FINGER_HOLD -> R.string.gesture_slot_four_finger_hold
    GestureSlot.THREE_FINGER_SCRUB -> R.string.gesture_slot_three_finger_scrub
}

/** The Settings and gesture-help name of what a gesture does, as a string resource. */
@StringRes
internal fun GestureAction.labelRes(): Int = when (this) {
    GestureAction.NONE -> R.string.gesture_action_none
    GestureAction.UNDO -> R.string.gesture_action_undo
    GestureAction.REDO -> R.string.gesture_action_redo
    GestureAction.HIDE_UI -> R.string.gesture_action_hide_ui
    GestureAction.QUICK_MENU -> R.string.gesture_action_quick_menu
    GestureAction.CLEAR_LAYER -> R.string.gesture_action_clear_layer
}
