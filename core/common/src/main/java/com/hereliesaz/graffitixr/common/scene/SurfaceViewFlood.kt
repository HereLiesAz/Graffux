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
        /** Whether the flood from [seed] (a point of one face) reaches the paint point [x], [y] of that face; null if the seed floods nothing. */
        fun flood(seed: PaintTarget): ((Float, Float) -> Boolean)?

        /** [target] moved to the nearest point on its face that is the colour being filled, or null if there is none near it. */
        fun accept(target: PaintTarget): PaintTarget?
    }

    /** The seeds to flood, [tap] first, one per face reached (a face is flooded once, from the first seed that reaches it). */
    fun plan(hull: SurfaceHull, tap: PaintTarget, oracle: Oracle, links: List<CreaseLink> = hull.creaseLinks()): List<PaintTarget> {
        fun key(t: PaintTarget) = t.view to t.region
        val byFace = links.groupBy { key(it.from) }
        val seeds = ArrayList<PaintTarget>()
        val done = HashSet<Pair<SurfaceView, Int>>()
        val queue = ArrayDeque<PaintTarget>()
        queue += tap
        done += key(tap)
        while (queue.isNotEmpty()) {
            val seed = queue.removeFirst()
            val reach = oracle.flood(seed)
            // The tapped face is always filled (a seed the oracle cannot read is the engine's to judge).
            if (reach == null && seeds.isNotEmpty()) continue
            seeds += seed
            if (reach == null) continue
            for (link in byFace[key(seed)].orEmpty()) {
                if (key(link.to) in done || !reach(link.from.x, link.from.y)) continue
                val next = oracle.accept(link.to) ?: continue
                done += key(next)
                queue += next
            }
        }
        return seeds
    }
}
