@file:Suppress("LoopWithTooManyJumpStatements", "CyclomaticComplexMethod")

package com.hereliesaz.graffitixr.common.scene

/**
 * A flood fill that continues across creases (spec/3d-space.md, "Fill, Clone, lasso"). A flood spreads
 * in one image; the surface is several faces in several images (and the unrolled outline's two ends),
 * so after a face is flooded, [plan] looks at every crease link ([SurfaceHull.creaseLinks]) leaving the
 * flooded part and starts a flood on the face across it, until no link leads to a new face of the same
 * colour. The pixels are the caller's ([Oracle]); this is only the walk.
 */
object SurfaceViewFlood {
    interface Oracle {
        /** The flood from [seed] as a test of paint points of its face; null if it floods nothing. */
        fun flood(seed: PaintTarget): ((Float, Float) -> Boolean)?

        /** [target] moved to the nearest point on its face that is the colour being filled; null if none is near. */
        fun accept(target: PaintTarget): PaintTarget?
    }

    /**
     * The seeds to flood, [tap] first. A seed is planned for every flooded *component* the walk reaches, not
     * for every face: a link into a face whose point an earlier flood of that face already covers adds
     * nothing, but one that lands outside every earlier flood of the face (a second component painted
     * apart by barriers, or the far end of a closed outline's unrolled sheet, which shares the face's
     * `(view, region)`) is a flood of its own.
     */
    fun plan(
        hull: SurfaceHull,
        tap: PaintTarget,
        oracle: Oracle,
        links: List<CreaseLink> = hull.creaseLinks(),
    ): List<PaintTarget> {
        fun key(t: PaintTarget) = t.view to t.region
        val byFace = links.groupBy { key(it.from) }
        val seeds = ArrayList<PaintTarget>()
        // The floods already planned, per face: a point inside one of them is not a new component.
        val reached = HashMap<Pair<SurfaceView, Int>, MutableList<(Float, Float) -> Boolean>>()
        val queue = ArrayDeque<PaintTarget>()
        queue += tap
        while (queue.isNotEmpty()) {
            val seed = queue.removeFirst()
            // Two links can lead into one component before either seed is flooded: the second is redundant.
            if (seeds.isNotEmpty() && reached[key(seed)].orEmpty().any { it(seed.x, seed.y) }) continue
            // Bounded by floods actually run (after the dedupe above), not by links queued: many links into one
            // component must not use up the budget of a second component.
            if (seeds.isNotEmpty() && reached[key(seed)].orEmpty().size >= MAX_FLOODS_PER_FACE) continue
            val reach = oracle.flood(seed)
            // The tapped face is always filled (a seed the oracle cannot read is the engine's to judge).
            if (reach == null && seeds.isNotEmpty()) continue
            seeds += seed
            if (reach == null) continue
            reached.getOrPut(key(seed)) { ArrayList() } += reach
            for (link in byFace[key(seed)].orEmpty()) {
                if (!reach(link.from.x, link.from.y)) continue
                val next = oracle.accept(link.to) ?: continue
                if (reached[key(next)].orEmpty().any { it(next.x, next.y) }) continue
                queue += next
            }
        }
        return seeds
    }

    /** A bound on the separate floods one face can take, so a pathological image cannot grow the walk without end. */
    private const val MAX_FLOODS_PER_FACE = 64
}
