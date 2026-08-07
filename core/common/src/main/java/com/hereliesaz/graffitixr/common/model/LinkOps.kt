package com.hereliesaz.graffitixr.common.model

/**
 * Layer **linking**: the contiguous run of layers that move, scale and rotate as one.
 *
 * A run is bounded by [Layer.isLinked]. The flag means "I am linked to the layer below me", so a
 * group is a stretch of the list in which every layer above the bottom one carries it. Walking down
 * from any member finds the bottom; walking up finds the top; everything between is the group.
 *
 * Pure, and in `:core:common` rather than beside the view model that used to own it, because two
 * callers need the same answer: `EditorViewModel`, which moves the whole run when one member is
 * dragged, and the rail, which has to be able to *show* a run — until it could, linking was a
 * feature you could turn on with nothing anywhere confirming you had.
 */
object LinkOps {

    /**
     * Every id in [layerId]'s link run, itself included. A layer linked to nothing returns just its
     * own id, and an id that names no layer returns that id alone rather than an empty set — the
     * callers treat the result as "the layers this operation touches", and touching nothing at all
     * is a different answer from touching only the one you asked about.
     */
    fun linkedGroupIds(layers: List<Layer>, layerId: String): Set<String> {
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx < 0) return setOf(layerId)
        var bottom = idx
        while (bottom > 0 && layers[bottom].isLinked) bottom--
        var top = idx
        while (top + 1 < layers.size && layers[top + 1].isLinked) top++
        return layers.subList(bottom, top + 1).map { it.id }.toSet()
    }

    /**
     * The other members of [layerId]'s run — what the rail marks so the user can see which layers
     * will come along. Empty when the layer is linked to nothing, which is the common case and the
     * one that must stay unmarked.
     */
    fun linkedPeers(layers: List<Layer>, layerId: String?): Set<String> {
        if (layerId == null) return emptySet()
        val group = linkedGroupIds(layers, layerId)
        return if (group.size <= 1) emptySet() else group - layerId
    }
}
