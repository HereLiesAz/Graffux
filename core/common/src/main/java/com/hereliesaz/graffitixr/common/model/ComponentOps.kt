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
     * Carries the edits that turned [before] into [after] from each main component onto its
     * instances, keeping each instance's overrides.
     *
     * Run on every state transition (the editor runs it at the reducer's exit and on every other
     * state write), so any edit to a main — paint, text, adjustments, shapes, warp — reaches its
     * instances, whichever code path made it. A content field moves to an instance only when the
     * main changed it AND the instance still showed the main's old value: an instance that set that
     * field itself has overridden it, and keeps its own value (Figma's override rule) with no
     * per-instance bookkeeping to store. The pixel fields move as one unit, keyed on the bitmap.
     *
     * Returns [after] itself (same reference) when nothing needs to move, so it costs one map
     * lookup per main when no main changed, and nothing at all when the stack has no components.
     */
    fun propagate(before: List<Layer>, after: List<Layer>): List<Layer> =
        if (before === after) after else InstanceFollow.apply(before, after)

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
        // Where that bitmap sits in its frame, and its height channel, travel with the pixels: an
        // instance handed a main's grown bitmap with its own stale origin draws it shifted.
        frameWidth = source.frameWidth,
        frameHeight = source.frameHeight,
        originX = source.originX,
        originY = source.originY,
        heightMap = source.heightMap,
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
        // blendMode, locks, instanceOf, and layoutWidth/Height (constraints resize each instance
        // on its own) — is deliberately left as the instance had it.
    )
}

/** [ComponentOps.propagate]'s workings: which mains changed, and how each instance follows. */
private object InstanceFollow {

    fun apply(before: List<Layer>, after: List<Layer>): List<Layer> {
        val edits = changedMains(before, after)
        if (edits.isEmpty()) return after
        var touched = false
        val out = after.map { layer ->
            val edit = layer.instanceOf?.let { edits[it] }
            val next = if (edit == null) layer else layer.followEdits(edit.first, edit.second)
            if (next !== layer) touched = true
            next
        }
        return if (touched) out else after
    }

    /** componentId -> (main before, main after), for each main whose content changed. */
    private fun changedMains(before: List<Layer>, after: List<Layer>): Map<String, Pair<Layer, Layer>> {
        val mains = after.filter { it.componentId != null }
        if (mains.isEmpty()) return emptyMap()
        val olds = before.filter { it.componentId != null }.associateBy { it.componentId!! }
        val out = HashMap<String, Pair<Layer, Layer>>()
        for (main in mains) {
            val old = olds[main.componentId!!]
            if (old != null && old !== main && old.contentDiffers(main)) out[main.componentId] = old to main
        }
        return out
    }

    private fun Layer.contentDiffers(o: Layer): Boolean = CONTENT_FIELDS.any { it.get(this) != it.get(o) }

    /**
     * Takes each field [new] changed from [old], unless this instance had overridden it (shows
     * something other than [old]'s value).
     */
    private fun Layer.followEdits(old: Layer, new: Layer): Layer = CONTENT_FIELDS.fold(this) { out, f ->
        val was = f.get(old)
        if (was != f.get(new) && f.get(out) == was) f.set(out, new) else out
    }

    /** One inherited field (or group of fields that only make sense together). */
    private class ContentField(val get: (Layer) -> Any?, val set: (Layer, Layer) -> Layer)

    /** Mirrors ComponentOps.withContentOf; keep the two lists in step. */
    private val CONTENT_FIELDS: List<ContentField> = listOf(
        // The pixels and everything that says where they sit: the bitmap reference changes on
        // every commit, so it keys the group.
        ContentField({ it.bitmap ?: it.uri }) { l, s ->
            l.copy(
                uri = s.uri, bitmap = s.bitmap, frameWidth = s.frameWidth, frameHeight = s.frameHeight,
                originX = s.originX, originY = s.originY, heightMap = s.heightMap,
            )
        },
        ContentField({ it.shapes }) { l, s -> l.copy(shapes = s.shapes) },
        ContentField({ it.textParams }) { l, s -> l.copy(textParams = s.textParams) },
        ContentField({ it.warpMesh }) { l, s -> l.copy(warpMesh = s.warpMesh) },
        ContentField({ it.type }) { l, s -> l.copy(type = s.type) },
        ContentField({ it.brightness }) { l, s -> l.copy(brightness = s.brightness) },
        ContentField({ it.contrast }) { l, s -> l.copy(contrast = s.contrast) },
        ContentField({ it.saturation }) { l, s -> l.copy(saturation = s.saturation) },
        ContentField({ it.colorBalanceR }) { l, s -> l.copy(colorBalanceR = s.colorBalanceR) },
        ContentField({ it.colorBalanceG }) { l, s -> l.copy(colorBalanceG = s.colorBalanceG) },
        ContentField({ it.colorBalanceB }) { l, s -> l.copy(colorBalanceB = s.colorBalanceB) },
        ContentField({ it.isInverted }) { l, s -> l.copy(isInverted = s.isInverted) },
    )
}
