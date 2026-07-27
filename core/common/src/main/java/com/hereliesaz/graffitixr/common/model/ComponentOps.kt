package com.hereliesaz.graffitixr.common.model

import java.util.UUID

/**
 * Figma's components and instances, as pure list transforms.
 *
 * A **main component** is an ordinary layer carrying a [Layer.componentId]. An **instance** is an
 * ordinary layer carrying [Layer.instanceOf] pointing at that id. Neither is a separate entity: the
 * component library is derived by scanning the stack, exactly the way [buildLayerTree] derives the
 * hierarchy and [AnimationFrames] derives the timeline. That keeps components inside the existing
 * save format with no parallel registry to keep consistent.
 *
 * The split between what an instance inherits and what it owns is the whole design:
 *
 * - **Inherited** — the *content*: pixels, vector shapes, text, warp, and the colour adjustments
 *   applied to them. Change the main, every instance follows.
 * - **Owned** — the *placement and presence*: position, scale, rotation, visibility, opacity, blend
 *   mode, name, and parent. These are why you placed a second copy at all, so propagation must never
 *   overwrite them.
 *
 * Anything owned survives a sync; anything inherited is replaced by it.
 */
object ComponentOps {

    /** Every main component in the stack, in stack order. */
    fun components(layers: List<Layer>): List<Layer> = layers.filter { it.componentId != null }

    /** The main component a given [componentId] resolves to, or null when it's dangling. */
    fun mainFor(layers: List<Layer>, componentId: String): Layer? =
        layers.firstOrNull { it.componentId == componentId }

    /** Instances of [componentId], in stack order. */
    fun instancesOf(layers: List<Layer>, componentId: String): List<Layer> =
        layers.filter { it.instanceOf == componentId }

    /**
     * Marks [layerId] as a main component. A layer that is already a component, or that is itself an
     * instance, is left alone — nesting a component inside its own instance chain is the one way to
     * build a cycle, and refusing it here is cheaper than detecting it later.
     */
    fun makeComponent(
        layers: List<Layer>,
        layerId: String,
        componentId: String = UUID.randomUUID().toString(),
    ): List<Layer> {
        val target = layers.firstOrNull { it.id == layerId } ?: return layers
        if (target.componentId != null || target.instanceOf != null) return layers
        return layers.map { if (it.id == layerId) it.copy(componentId = componentId) else it }
    }

    /**
     * Builds an instance layer of [componentId], copying the main's content and starting with the
     * main's placement (so it lands exactly on top, ready to be moved — Figma's behaviour). Returns
     * null when the component doesn't exist.
     */
    fun buildInstance(
        layers: List<Layer>,
        componentId: String,
        newId: String = UUID.randomUUID().toString(),
        name: String? = null,
    ): Layer? {
        val main = mainFor(layers, componentId) ?: return null
        return main.copy(
            id = newId,
            name = name ?: "${main.name} instance",
            // The copy is an instance, never a second main — otherwise mainFor would be ambiguous.
            componentId = null,
            instanceOf = componentId,
        )
    }

    /**
     * Propagates every main component's content onto its instances. Idempotent, and the single place
     * propagation happens — callers run it after any edit rather than trying to work out which edits
     * could have touched a component.
     */
    fun syncInstances(layers: List<Layer>): List<Layer> {
        val mains = layers.filter { it.componentId != null }.associateBy { it.componentId!! }
        if (mains.isEmpty()) return layers
        return layers.map { layer ->
            val source = layer.instanceOf?.let { mains[it] } ?: return@map layer
            layer.withContentOf(source)
        }
    }

    /**
     * Turns an instance back into an independent layer, keeping the content it currently shows.
     * The content is already resolved on the layer (see [syncInstances]), so this only has to drop
     * the link.
     */
    fun detachInstance(layers: List<Layer>, instanceId: String): List<Layer> =
        layers.map { if (it.id == instanceId && it.instanceOf != null) it.copy(instanceOf = null) else it }

    /**
     * Removes a component definition, detaching its instances rather than leaving them pointing at
     * nothing. Called when the main is deleted or demoted — a dangling instance would silently stop
     * updating with no way for the user to tell why.
     */
    fun releaseComponent(layers: List<Layer>, componentId: String): List<Layer> = layers.map {
        when {
            it.componentId == componentId -> it.copy(componentId = null)
            it.instanceOf == componentId -> it.copy(instanceOf = null)
            else -> it
        }
    }

    /**
     * Copies [source]'s inherited content onto this layer, preserving everything the instance owns.
     * Written as an explicit copy rather than a field blacklist so that a new field added to [Layer]
     * defaults to instance-owned — the safe direction, since silently propagating something like a
     * position would move every instance on top of the main.
     */
    private fun Layer.withContentOf(source: Layer): Layer = copy(
        // Content.
        uri = source.uri,
        bitmap = source.bitmap,
        shapes = source.shapes,
        textParams = source.textParams,
        warpMesh = source.warpMesh,
        type = source.type,
        brightness = source.brightness,
        contrast = source.contrast,
        saturation = source.saturation,
        colorBalanceR = source.colorBalanceR,
        colorBalanceG = source.colorBalanceG,
        colorBalanceB = source.colorBalanceB,
        isInverted = source.isInverted,
        // Everything else — id, name, parentId, offset, scale, rotation*, isVisible, opacity,
        // blendMode, locks, instanceOf — is deliberately left as the instance had it.
    )
}
