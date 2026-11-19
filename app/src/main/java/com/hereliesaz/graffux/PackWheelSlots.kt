package com.hereliesaz.graffux

/**
 * One place on the pack wheel's ring. The ring is the packs, in their order, then the tail: the
 * "Get More" entry with an [Empty] place either side of it. The packs keep the places they always
 * had (0 until the pack count), so adding the tail moves none of them; it sits between the last pack
 * and Favorites.
 */
internal sealed interface PackWheelSlot {
    /** The pack at position [index] in the wheel's pack list. */
    data class Pack(val index: Int) : PackWheelSlot

    /** The entry that opens the store view. */
    data object GetMore : PackWheelSlot

    /** A blank place: draws nothing, takes no touch and is not announced. */
    data object Empty : PackWheelSlot
}

/** Empty places kept on each side of "Get More", so a pick of a neighbouring pack cannot land on it. */
internal const val GET_MORE_EMPTY_NEIGHBOURS = 1

/** What a gesture on a slot does. */
internal sealed interface PackWheelAction {
    data class Pick(val index: Int) : PackWheelAction
    data object OpenStore : PackWheelAction
    data object None : PackWheelAction
}

/** The wheel's ring for [packCount] packs: the packs, then [Empty] x N, [GetMore], [Empty] x N. */
internal fun packWheelSlots(packCount: Int): List<PackWheelSlot> {
    if (packCount <= 0) return emptyList()
    val gap = List(GET_MORE_EMPTY_NEIGHBOURS) { PackWheelSlot.Empty }
    return List(packCount) { PackWheelSlot.Pack(it) } + gap + PackWheelSlot.GetMore + gap
}

/**
 * What a gesture on [slot] does. A tap or an accessibility click is [explicit]; a wheel that merely
 * rested on the slot after a drag or fling is not, and never opens the store: a fling that stops on
 * "Get More" only points at it.
 */
internal fun routePackSlot(slot: PackWheelSlot, explicit: Boolean): PackWheelAction = when (slot) {
    is PackWheelSlot.Pack -> PackWheelAction.Pick(slot.index)
    PackWheelSlot.GetMore -> if (explicit) PackWheelAction.OpenStore else PackWheelAction.None
    PackWheelSlot.Empty -> PackWheelAction.None
}

/** The pack [by] steps from pack [from] among the [packCount] packs only: the tail is stepped over. */
internal fun steppedPack(from: Int, by: Int, packCount: Int): Int = wrapIndex(from + by, packCount)
