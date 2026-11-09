@file:Suppress("LoopWithTooManyJumpStatements", "CyclomaticComplexMethod", "MaxLineLength", "ReturnCount", "MagicNumber", "DestructuringDeclarationWithTooManyEntries", "LongParameterList")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.mesh.SpatialStroke
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.scene.DepthComposite
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SceneMath
import com.hereliesaz.graffitixr.common.scene.planeChain
import com.hereliesaz.graffitixr.feature.editor.threed.DepthCompositor

/**
 * The GPU depth pass's input for a layer list (spec/3d-space.md, "Per-pixel depth"), built the same
 * way for the canvas and for the PNG/PDF export so both show the same scene.
 */
internal object DepthScene {
    /** A layer's current pixels and a token that changes with them (a live stroke's version). */
    fun interface Pixels {
        fun of(layer: Layer): Pair<Bitmap, Long>?
    }

    @Suppress("LongParameterList", "LongMethod")
    fun build(
        layers: List<Layer>,
        plan: DepthComposite,
        camera: SceneCamera,
        width: Float,
        height: Float,
        pixels: Pixels,
        vectors: (Layer) -> VectorRaster.Image?,
        draft: Pair<String, SpatialStroke>? = null,
        modelDraft: com.hereliesaz.graffitixr.common.scene.ModelDraft? = null,
        /** The layer being drawn on, for the inactive-layer cache; null (the export) caches nothing. */
        activeId: String? = null,
        drawing: Boolean = false,
    ): DepthCompositor.Scene {
        val byId = layers.associateBy { it.id }
        val planes = ArrayList<DepthCompositor.Plane>()
        val inks = ArrayList<DepthCompositor.Ink>()
        val models = ArrayList<DepthCompositor.Model>()
        for (id in plan.memberIds) {
            val layer = byId[id] ?: continue
            val model = SceneMath.chainModel(planeChain(layers, id), width, height)
            val (alpha, isolation) = memberAlpha(layers, plan, layer)
            val owner = if (id in plan.surfaceIds) com.hereliesaz.graffitixr.common.scene.SurfaceMath.ownerOf(layers, id) else null
            val key = if (activeId == null) null else cacheKey(layers, byId, layer, alpha, owner?.profile, isolation)
            val content = layer.model
            if (content != null) {
                val mesh = content.mesh ?: continue
                val toScene = model * com.hereliesaz.graffitixr.common.scene.ModelSpace.meshToLocal(mesh, width, height)
                val painting = modelDraft?.takeIf { it.layerId == id }
                models += if (painting != null) {
                    DepthCompositor.Model(id, mesh.mesh, toScene, painting.bitmap, painting.version, alpha, painting.bitmap, key)
                } else {
                    DepthCompositor.Model(id, mesh.mesh, toScene, content.texture?.pixels(), 0L, alpha, key = key)
                }
                continue
            }
            val spatial = layer.spatial
            if (spatial != null) {
                val live = draft?.takeIf { it.first == id }?.second
                inks += DepthCompositor.Ink(
                    id, if (live == null) spatial.strokes else spatial.strokes + live,
                    model * SceneMath.strokeToLocal(width, height), alpha, key,
                )
                continue
            }
            if (owner != null && owner.profile.hasHull) {
                val hull = com.hereliesaz.graffitixr.common.scene.SurfaceHull.of(owner.profile)
                if (hull != null) {
                    val siblings = plan.memberIds.filter {
                        it in plan.surfaceIds &&
                            com.hereliesaz.graffitixr.common.scene.SurfaceMath.ownerOf(layers, it)?.group?.id == owner.group.id
                    }
                    planes += hullPlanes(
                        layers, layer, owner, hull, width, height, pixels, alpha, key, siblings.indexOf(id).coerceAtLeast(0),
                    )
                    continue
                }
            }
            val (bitmap, token, rect) = if (layer.shapes.isNotEmpty()) {
                val image = vectors(layer) ?: continue
                Triple(image.bitmap, layer.shapes.hashCode().toLong(), DepthComposite.renderedRect(layer, null, null, width, height) ?: image.rect)
            } else {
                val (bmp, tok) = pixels.of(layer) ?: continue
                val r = DepthComposite.renderedRect(layer, bmp.width, bmp.height, width, height) ?: continue
                Triple(bmp, tok, r)
            }
            if (owner != null) {
                val mesh = surfaceMeshForPass(owner, rect, width, height) ?: continue
                val siblings = plan.memberIds.filter { it in plan.surfaceIds && com.hereliesaz.graffitixr.common.scene.SurfaceMath.ownerOf(layers, it)?.group?.id == owner.group.id }
                planes += DepthCompositor.Plane(
                    id, bitmap, token, SceneMath.chainModel(owner.ownerChain, width, height), rect, colorMatrixOf(layer), alpha, key,
                    surface = mesh, surfaceGroup = owner.group.id, surfaceIndex = siblings.indexOf(id).coerceAtLeast(0),
                    surfaceFlat = owner.profile.isFlat,
                    // A relief is arbitrary triangles over a grid, not the strips of one profile.
                    surfaceTriangles = owner.profile.relief != null,
                )
                continue
            }
            planes += DepthCompositor.Plane(id, bitmap, token, model, rect, colorMatrixOf(layer), alpha, key)
        }
        val groups = isolatedGroups(plan, planes, byId, activeId)
        return DepthCompositor.Scene(
            SceneMath.frame(camera, width, height), planes, inks, models,
            activeId = activeId, drawing = drawing, viewKey = camera.takeIf { activeId != null }, groups = groups,
        )
    }

    /**
     * The opacity a member draws with, and the isolated group it draws inside (if any). Paint in a
     * translucent surface group composites inside the group's own target and takes the group's
     * opacity once, over the merged image, so there it keeps only its own (and its inner groups').
     */
    private fun memberAlpha(layers: List<Layer>, plan: DepthComposite, layer: Layer): Pair<Float, DepthComposite.IsolatedGroup?> {
        val isolation = plan.isolationOf(layer.id)?.takeIf { layer.spatial == null && layer.model == null }
        val alpha = if (isolation != null) {
            DepthComposite.opacityBelow(layers, layer.id, isolation.groupId)
        } else {
            DepthComposite.effectiveOpacity(layers, layer.id)
        }
        return alpha to isolation
    }

    /**
     * What the inactive-layer cache compares: the layer, its placement chain and the surface it is
     * painted on, never the canvas size (a resize scales the renders instead of rebuilding them).
     * In an isolated group, also what the merged image is drawn with: its opacity and blend.
     */
    private fun cacheKey(
        layers: List<Layer>,
        byId: Map<String, Layer>,
        layer: Layer,
        alpha: Float,
        profile: com.hereliesaz.graffitixr.common.scene.SurfaceProfile?,
        isolation: DepthComposite.IsolatedGroup?,
    ): Any = listOf(layer, planeChain(layers, layer.id), alpha, profile, isolation?.opacity, isolation?.let { byId[it.groupId]?.blendMode })

    /**
     * One unit per translucent group that has paint to draw. Its key is what the cached renders
     * that hold it compare: the group's opacity and blend and its members' keys.
     */
    private fun isolatedGroups(
        plan: DepthComposite,
        planes: List<DepthCompositor.Plane>,
        byId: Map<String, Layer>,
        activeId: String?,
    ): List<DepthCompositor.Group> = plan.isolatedGroups.mapNotNull { g ->
        val inside = planes.filter { it.surface != null && it.surfaceGroup == g.groupId }
        if (inside.isEmpty()) return@mapNotNull null
        val key = if (activeId == null) null else listOf(g.groupId, g.opacity, byId[g.groupId]?.blendMode, inside.map { it.key })
        DepthCompositor.Group(g.groupId, inside.map { it.id }, g.opacity, key)
    }

    /**
     * Paint on a multi-view surface (spec/3d-space.md, "Rendering the hull"): one plane per view
     * image of [layer], each drawing its own paint on that view's patch of the intersected surface
     * ([com.hereliesaz.graffitixr.common.scene.SurfaceHullMesh]). The planes take [layer]'s colour,
     * opacity and place in the group's order, so opacity and isolated compositing work as for any
     * paint on a surface. A view with no image yet draws nothing.
     */
    @Suppress("LongParameterList")
    internal fun hullPlanes(
        layers: List<Layer>,
        layer: Layer,
        owner: com.hereliesaz.graffitixr.common.scene.SurfaceOwner,
        hull: com.hereliesaz.graffitixr.common.scene.SurfaceHull,
        width: Float,
        height: Float,
        pixels: Pixels,
        alpha: Float,
        key: Any?,
        surfaceIndex: Int,
    ): List<DepthCompositor.Plane> {
        val out = ArrayList<DepthCompositor.Plane>()
        for (image in layers.filter { it.surfaceViewOf == layer.id }) {
            val view = image.surfaceView ?: continue
            val (bitmap, token) = pixels.of(image) ?: continue
            val rect = DepthComposite.imageRect(image, bitmap.width, bitmap.height, width, height) ?: continue
            val imageOwner = com.hereliesaz.graffitixr.common.scene.SurfaceMath.ownerOf(layers, image.id) ?: continue
            val toBox = com.hereliesaz.graffitixr.common.scene.SurfaceMath
                .boxToUnrolled(imageOwner.innerChain, width, height).inverse() ?: continue
            val mesh = com.hereliesaz.graffitixr.common.scene.SurfaceHullMesh.textured(hull, view, toBox, rect) ?: continue
            out += DepthCompositor.Plane(
                image.id, bitmap, token, SceneMath.chainModel(owner.ownerChain, width, height), rect, colorMatrixOf(layer), alpha,
                key?.let { listOf(it, image.id, view) },
                surface = mesh, surfaceGroup = owner.group.id, surfaceIndex = surfaceIndex, surfaceFlat = false,
                surfaceTriangles = true,
            )
        }
        return out
    }

    /**
     * The strips that carry an image at [rect] (its leaf's box coordinates) on [owner]'s surface:
     * x, y, z in the owner's box space, then the image's u, v. The image's corners are unrolled by
     * the leaf's flat chain below the owner; arc length along the profile is the image's x.
     */
    fun surfaceMesh(owner: com.hereliesaz.graffitixr.common.scene.SurfaceOwner, rect: FloatArray, width: Float, height: Float): FloatArray? {
        val math = com.hereliesaz.graffitixr.common.scene.SurfaceMath
        val toUnrolled = math.boxToUnrolled(owner.innerChain, width, height)
        val toBox = toUnrolled.inverse() ?: return null
        val (l, t, r, b) = rect.toList()
        if (r <= l || b <= t) return null
        val range = math.unrolledRange(owner.innerChain, rect, width, height)
        val mesh = math.mesh(owner.profile, range[0], range[1], range[2], range[3])
        if (mesh.isEmpty()) return null
        var i = 0
        while (i < mesh.size) {
            val box = toBox.apply(mesh[i + 3], mesh[i + 4], 0f)
            mesh[i + 3] = (box[0] - l) / (r - l)
            mesh[i + 4] = (box[1] - t) / (b - t)
            i += 5
        }
        return mesh
    }

    /**
     * [surfaceMesh] for the depth pass. A relief's mesh is ~130k triangles, far too many to rebuild (and for the
     * compositor to re-upload) every frame, so it is built once per relief, photo rectangle and placement and the
     * same array is handed back until one of them changes (the strength slider and the near/far swap change the
     * relief, so they rebuild it). The array is shared: the pass only reads it, and the export, which moves its
     * vertices into scene space in place, calls [surfaceMesh] and so gets its own copy. Other surfaces are cheap
     * strips and are rebuilt as before.
     */
    internal fun surfaceMeshForPass(
        owner: com.hereliesaz.graffitixr.common.scene.SurfaceOwner,
        rect: FloatArray,
        width: Float,
        height: Float,
    ): FloatArray? {
        val relief = owner.profile.relief ?: return surfaceMesh(owner, rect, width, height)
        val key = ReliefMeshKey(relief, rect.toList(), owner.innerChain, width, height)
        synchronized(reliefMeshes) { reliefMeshes[key]?.let { return it } }
        val mesh = surfaceMesh(owner, rect, width, height) ?: return null
        synchronized(reliefMeshes) {
            reliefMeshes[key] = mesh
            while (reliefMeshes.size > RELIEF_MESHES_KEPT) reliefMeshes.remove(reliefMeshes.keys.first())
        }
        return mesh
    }

    private data class ReliefMeshKey(
        val relief: com.hereliesaz.graffitixr.common.scene.ReliefSurface,
        val rect: List<Float>,
        val innerChain: List<com.hereliesaz.graffitixr.common.scene.PlanePose>,
        val width: Float,
        val height: Float,
    )

    // Insertion-ordered; the oldest goes first. A document has a handful of reliefs at most.
    private val reliefMeshes = LinkedHashMap<ReliefMeshKey, FloatArray>()
    private const val RELIEF_MESHES_KEPT = 4

    /** The canvas's colour adjustment for [layer]; null when it changes nothing. */
    fun colorMatrixOf(layer: Layer): FloatArray? {
        val neutral = layer.saturation == 1f && layer.contrast == 1f && layer.brightness == 0f &&
            layer.colorBalanceR == 1f && layer.colorBalanceG == 1f && layer.colorBalanceB == 1f && !layer.isInverted
        if (neutral) return null
        return createColorMatrix(
            saturation = layer.saturation,
            contrast = layer.contrast,
            brightness = layer.brightness,
            colorBalanceR = layer.colorBalanceR,
            colorBalanceG = layer.colorBalanceG,
            colorBalanceB = layer.colorBalanceB,
            isInverted = layer.isInverted,
        ).values
    }
}
