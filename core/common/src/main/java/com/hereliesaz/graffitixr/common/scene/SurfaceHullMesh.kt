package com.hereliesaz.graffitixr.common.scene

/**
 * The intersected surface ([SurfaceHull]) as textured triangles for drawing and export
 * (spec/3d-space.md, "Rendering the hull"): each view's patch carries that view's own paint image.
 */
@Suppress("MagicNumber", "ReturnCount")
object SurfaceHullMesh {
    /** Floats per vertex: x, y, z in the group's box space, then the image's u, v. */
    const val STRIDE = 5

    /**
     * [view]'s patch with texture coordinates into its paint image, which sits at [rect] (left, top,
     * right, bottom in the image layer's box coordinates). A vertex's sheet coordinates `(u, t)` are
     * taken to the image's flat point ([SurfaceHull.paintPointAt], the same mapping a brush touch
     * uses) and from there to the image through [toBox] (unrolled to box, the inverse of
     * [SurfaceMath.boxToUnrolled]), so a stroke lands exactly where the patch shows it. Null when
     * the view carries no surface or [rect] is empty.
     */
    fun textured(hull: SurfaceHull, view: SurfaceView, toBox: Affine3, rect: FloatArray): FloatArray? {
        val l = rect[0]
        val t = rect[1]
        val r = rect[2]
        val b = rect[3]
        if (r <= l || b <= t) return null
        val sheet = hull.meshByView()[view]?.copyOf()
        val caps = hull.capMeshByView()[view]?.copyOf()
        if (sheet == null && caps == null) return null
        var i = 0
        while (sheet != null && i + STRIDE <= sheet.size) {
            val flat = hull.paintPointAt(view, sheet[i + 3], sheet[i + 4]) ?: return null
            place(sheet, i, flat.first, flat.second, toBox, l, t, r, b)
            i += STRIDE
        }
        i = 0
        // A cap's vertices already carry its point in the view's image (its own region of it).
        while (caps != null && i + STRIDE <= caps.size) {
            place(caps, i, caps[i + 3], caps[i + 4], toBox, l, t, r, b)
            i += STRIDE
        }
        return (sheet ?: FloatArray(0)) + (caps ?: FloatArray(0))
    }

    @Suppress("LongParameterList")
    private fun place(
        mesh: FloatArray,
        i: Int,
        fx: Float,
        fy: Float,
        toBox: Affine3,
        l: Float,
        t: Float,
        r: Float,
        b: Float,
    ) {
        val box = toBox.apply(fx, fy, 0f)
        mesh[i + 3] = (box[0] - l) / (r - l)
        mesh[i + 4] = (box[1] - t) / (b - t)
    }

    /** Every view of [hull] that carries surface, with its textured patch for the rect [rects] gives it. */
    fun all(hull: SurfaceHull, toBox: Affine3, rects: (SurfaceView) -> FloatArray?): Map<SurfaceView, FloatArray> {
        val out = LinkedHashMap<SurfaceView, FloatArray>()
        for (view in hull.meshByView().keys + hull.capMeshByView().keys) {
            val rect = rects(view) ?: continue
            textured(hull, view, toBox, rect)?.let { out[view] = it }
        }
        return out
    }
}
