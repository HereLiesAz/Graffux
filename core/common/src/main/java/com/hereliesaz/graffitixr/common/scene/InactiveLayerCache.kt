@file:Suppress("ReturnCount", "MagicNumber", "CyclomaticComplexMethod", "MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

/**
 * The inactive-layer cache (spec/3d-space.md, "Inactive-layer cache"; ARCHITECTURE.md): while the
 * user draws on the active layer, everything behind it and everything in front of it is
 * composited once into at most two GPU render targets, and each frame draws
 * behind + the live active layer + front.
 *
 * This file is the pure part, shared by the 2D stack (HWUI) and the 3D depth pass (GLES): the two
 * slots and their keys ([SplitCache]), the "rebuild once the view rests" gate ([RestGate]), and
 * how a stack or a depth scene splits around the active layer ([StackSplit], [DepthSplit]). The
 * GPU resources themselves are owned by the renderers; this only decides when they are built,
 * kept and freed.
 */
class SplitCache<T : Any>(private val free: (T) -> Unit) {
    enum class Slot { BEHIND, FRONT }

    private class Entry<T>(val key: Any, val value: T)

    private val entries = arrayOfNulls<Entry<T>>(Slot.entries.size)

    /** The active layer the slots were built around; another one dumps both. */
    var activeId: String? = null
        private set

    /** Renders made and freed so far (for tests and logging). */
    var builds = 0
        private set
    var dumps = 0
        private set

    /** How many renders exist right now: never more than two. */
    val liveCount: Int get() = entries.count { it != null }

    fun has(slot: Slot): Boolean = entries[slot.ordinal] != null

    /** The owner's rule: whenever another layer becomes active, both renders go. */
    fun select(activeId: String?) {
        if (activeId != this.activeId) {
            dumpAll()
            this.activeId = activeId
        }
    }

    /**
     * The render for [slot] if it was built for [key]; otherwise the old one is freed and [build]
     * makes a new one (it may return null: nothing is cached, the caller draws that side directly).
     */
    fun get(slot: Slot, key: Any, build: () -> T?): T? {
        val existing = entries[slot.ordinal]
        if (existing != null && existing.key == key) return existing.value
        dump(slot)
        val made = build() ?: return null
        builds++
        entries[slot.ordinal] = Entry(key, made)
        return made
    }

    fun dump(slot: Slot) {
        val e = entries[slot.ordinal] ?: return
        entries[slot.ordinal] = null
        dumps++
        free(e.value)
    }

    fun dumpAll() = Slot.entries.forEach(::dump)

    /** Forgets every render without freeing it (its GPU context is already gone). */
    fun abandon() {
        entries.fill(null)
        activeId = null
    }
}

/**
 * Bypasses the cache while the view moves: [settled] is true only when the view key is the same
 * as on the previous frame, so an orbit or a pan draws uncached and the cache is rebuilt on the
 * first frame after it stops, not on every frame of it.
 */
class RestGate {
    private var last: Any? = null
    private var seen = false

    fun settled(key: Any): Boolean {
        val same = seen && key == last
        last = key
        seen = true
        return same
    }
}

/**
 * The 2D split: stack order. The active layer's top-level subtree draws live; the top-level
 * subtrees under it are "behind", those over it are "front". A side is cached only when every
 * subtree in it composites as plain source-over (no blend mode, no clip to the layer below, no
 * adjustment, also inside groups): such a side composited into its own buffer and then drawn
 * over is the same image, while a blend mode or a clipping mask would see a different backdrop
 * in an isolated buffer. Such a side is drawn directly, uncached.
 */
data class StackSplit(
    val behind: List<String>,
    val activeTopLevelId: String,
    val front: List<String>,
    val cacheBehind: Boolean,
    val cacheFront: Boolean,
) {
    companion object {
        /** Null when [activeTopLevelId] is not one of [topLevelIds]. */
        fun plan(topLevelIds: List<String>, activeTopLevelId: String, plainSourceOver: (String) -> Boolean): StackSplit? {
            val i = topLevelIds.indexOf(activeTopLevelId)
            if (i < 0) return null
            val behind = topLevelIds.subList(0, i).toList()
            val front = topLevelIds.subList(i + 1, topLevelIds.size).toList()
            return StackSplit(
                behind, activeTopLevelId, front,
                cacheBehind = behind.isNotEmpty() && behind.all(plainSourceOver),
                cacheFront = front.isNotEmpty() && front.all(plainSourceOver),
            )
        }
    }
}

/**
 * The 3D split: depth order from the current view. Every member of the depth pass is reduced to
 * points in scene coordinates that bound it ([Member.points]); the active layer's plane (or, for
 * 3D ink and models, its depth range) separates the others.
 *
 * - Active plane: a member wholly on the far side of the plane's infinite extension can never
 *   occlude any pixel of the active layer from this eye, and one wholly on the eye's side can
 *   never be occluded by it or by anything on the far side. That is what lets the behind render
 *   drop its depth buffer after it is built: nothing in it can be in front of the active plane.
 * - Active ink or model: a member is behind when all of it is deeper than all of the active
 *   layer, in front when all of it is shallower.
 * - A member that straddles (or lies in) the active plane, or overlaps its depth range, cannot be
 *   split cleanly: the whole frame is drawn uncached.
 *
 * Exactness also needs the uncached draw order ([drawOrder], opaque before translucent) to put
 * every behind member before the active layer and every front member after it, because the
 * uncached pass lets an earlier opaque edge occlude a later translucent one. When it does not, the
 * frame is drawn uncached rather than differently.
 */
object DepthSplit {
    class Member(val id: String, val points: List<FloatArray>, val planar: Boolean)

    sealed interface Result
    data class Split(val behind: List<String>, val front: List<String>) : Result
    data class Uncached(val reason: String) : Result

    fun classify(cam: CameraFrame, activeId: String, members: List<Member>, drawOrder: List<String>): Result {
        val active = members.firstOrNull { it.id == activeId } ?: return Uncached("active layer not in the depth pass")
        if (active.points.isEmpty()) return Uncached("active layer has no extent")
        val eps = 1e-3f * maxOf(cam.width, cam.height, 1f)
        val side: (Member) -> Int = if (active.planar && active.points.size >= 3) {
            planeSide(cam, active, eps) ?: return Uncached("active plane is edge-on to the eye")
        } else {
            depthSide(cam, active)
        }
        val behind = ArrayList<String>()
        val front = ArrayList<String>()
        for (m in members) {
            if (m.id == activeId) continue
            when (side(m)) {
                FAR -> behind += m.id
                NEAR -> front += m.id
                else -> return Uncached("layer ${m.id} intersects the active layer")
            }
        }
        val at = drawOrder.indexOf(activeId)
        if (at < 0) return Uncached("active layer is not drawn")
        val position = drawOrder.withIndex().associate { it.value to it.index }
        if (behind.any { (position[it] ?: -1) > at } || front.any { (position[it] ?: Int.MAX_VALUE) < at }) {
            return Uncached("draw order disagrees with depth order")
        }
        return Split(behind, front)
    }

    const val FAR = 1
    const val NEAR = -1
    private const val STRADDLE = 0

    /** Which side of the active plane each member is on; null when the eye is in the plane. */
    private fun planeSide(cam: CameraFrame, active: Member, eps: Float): ((Member) -> Int)? {
        val a = cam.toCamera(active.points[0])
        val b = cam.toCamera(active.points[1])
        val c = cam.toCamera(active.points[2])
        val n = SceneMath.cross(
            floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2]),
            floatArrayOf(c[0] - a[0], c[1] - a[1], c[2] - a[2]),
        )
        val len = kotlin.math.sqrt(SceneMath.dot(n, n))
        if (len < 1e-12f) return null
        val unit = floatArrayOf(n[0] / len, n[1] / len, n[2] / len)
        val d = -SceneMath.dot(unit, a)
        // The eye is the camera-space origin, so its signed distance is d.
        if (kotlin.math.abs(d) <= eps) return null
        val eyeSign = if (d > 0f) 1 else -1
        return { m ->
            var near = false
            var far = false
            for (p in m.points) {
                val s = SceneMath.dot(unit, cam.toCamera(p)) + d
                when {
                    s * eyeSign > eps -> near = true
                    s * eyeSign < -eps -> far = true
                }
            }
            when {
                near && !far -> NEAR
                far && !near -> FAR
                else -> STRADDLE
            }
        }
    }

    private fun depthSide(cam: CameraFrame, active: Member): (Member) -> Int {
        val (lo, hi) = depthRange(cam, active)
        return { m ->
            val (mLo, mHi) = depthRange(cam, m)
            when {
                m.points.isEmpty() -> STRADDLE
                mLo > hi -> FAR
                mHi < lo -> NEAR
                else -> STRADDLE
            }
        }
    }

    private fun depthRange(cam: CameraFrame, m: Member): Pair<Float, Float> {
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (p in m.points) {
            val z = cam.toCamera(p)[2]
            lo = minOf(lo, z)
            hi = maxOf(hi, z)
        }
        return lo to hi
    }

    /** The eight corners of an axis-aligned box `[minX,minY,minZ,maxX,maxY,maxZ]`, through [toScene]. */
    fun boxCorners(b: FloatArray, toScene: Affine3): List<FloatArray> = buildList {
        for (i in 0 until 8) {
            add(toScene.apply(if (i and 1 == 0) b[0] else b[3], if (i and 2 == 0) b[1] else b[4], if (i and 4 == 0) b[2] else b[5]))
        }
    }
}
