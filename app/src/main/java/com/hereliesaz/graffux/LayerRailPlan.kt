package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.model.isContainer
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import androidx.compose.ui.graphics.Color

/** The top-level layers host: an `azUnattachedHostItem`. */
internal const val LAYERS_HOST_ID = "grp.layers"

/** A layer's rail item id. For a group this is also the id of the rail host its children sit under. */
internal fun layerRailId(layerId: String): String = "layer.$layerId"

/** One layer row to declare in the rail: [layer] under the rail host [hostId]. */
internal data class LayerRailRow(val layer: Layer, val hostId: String) {
    val isGroup: Boolean get() = layer.isContainer
}

/**
 * The order in which the layers rail declares its rows: depth-first, top-first (the frontmost
 * layer on top), each group immediately followed by its whole subtree.
 *
 * A group layer is an `azRailRelocSubHostItem` (a draggable rail host that is itself a sub-item of
 * its parent's host), and its children are rail sub-items whose `hostId` is that group's id —
 * never a nested rail.
 *
 * The ordering matters because AzNavRail (11.52+) treats a relocatable sub-host plus every item
 * *immediately after it* that descends from it as one block (`RelocItemHandler.blockEnd`): the
 * block is one slot of its parent's reloc cluster and moves as a unit when the group is dragged.
 * Declaring a group's children anywhere else would leave them out of its block, and would split
 * the parent host's run of reloc slots.
 */
internal fun layerRailRows(layers: List<Layer>): List<LayerRailRow> {
    val rows = mutableListOf<LayerRailRow>()
    fun declare(parentId: String?, hostId: String) {
        layers.filter { it.parentId == parentId }.reversed().forEach {
            rows += LayerRailRow(it, hostId)
            if (it.isContainer) declare(it.id, layerRailId(it.id))
        }
    }
    declare(null, LAYERS_HOST_ID)
    return rows
}

/**
 * The outermost [LayerType.GROUP] holding [layer] (a group inside a group counts as its outermost
 * group, so nesting adds nothing of its own), or null when it sits in no group.
 */
internal fun topGroupOf(layers: Map<String, Layer>, layer: Layer): Layer? {
    var top: Layer? = null
    var parent = layer.parentId?.let(layers::get)
    val seen = HashSet<String>()
    while (parent != null && seen.add(parent.id)) {
        if (parent.type == LayerType.GROUP) top = parent
        parent = parent.parentId?.let(layers::get)
    }
    return top
}

/** A group's mark on its layers' tiles: its 1-based number (for TalkBack) and its edge-band colour. */
internal data class GroupBand(val number: Int, val color: Color)

/**
 * Group colours: six, apart in hue and in lightness, and none of the tile outlines' colours (pink,
 * cyan, white, amber, neon green). Handed out in the order the groups sit in the document, so a
 * document always paints the same group the same colour.
 */
@Suppress("MagicNumber")
internal val GROUP_PALETTE: List<Color> = listOf(
    Color(0xFF9B7BFF), // violet
    Color(0xFFE8542B), // red-orange
    Color(0xFF2F5BFF), // blue
    Color(0xFF5FA83A), // leaf green
    Color(0xFF7A1F3D), // wine
    Color(0xFF008F7A), // deep teal
)

/**
 * Which layers wear a group band, and which. Groups have no tile on the rail: layers are shown flat
 * and membership is told by colour, and only when there are two or more groups with layers in them
 * (one group would only tell the user what they already see, so it stays invisible). Keyed by
 * layer id; ungrouped layers are absent.
 */
internal fun layerGroupBands(layers: List<Layer>): Map<String, GroupBand> {
    val byId = layers.associateBy { it.id }
    val members = layers.filter { !it.isContainer }.mapNotNull { l -> topGroupOf(byId, l)?.let { l.id to it.id } }
    val groupIds = layers.filter { g -> members.any { it.second == g.id } }.map { it.id }
    if (groupIds.size < 2) return emptyMap()
    val band = groupIds.mapIndexed { i, id -> id to GroupBand(i + 1, GROUP_PALETTE[i % GROUP_PALETTE.size]) }.toMap()
    return members.associate { (layerId, groupId) -> layerId to band.getValue(groupId) }
}
