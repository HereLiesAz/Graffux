@file:Suppress("LongParameterList", "ComplexCondition", "ReturnCount", "MagicNumber", "MaxLineLength")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.frame
import com.hereliesaz.graffitixr.common.model.isContainer
import com.hereliesaz.graffitixr.common.scene.ArtBounds
import com.hereliesaz.graffitixr.common.scene.ArtSheet
import com.hereliesaz.graffitixr.common.scene.DefaultSurface
import com.hereliesaz.graffitixr.common.scene.DepthComposite
import com.hereliesaz.graffitixr.common.scene.ReliefOptions
import com.hereliesaz.graffitixr.common.scene.ReliefSurface
import com.hereliesaz.graffitixr.common.scene.SurfaceMath
import com.hereliesaz.graffitixr.common.scene.SurfaceProfile
import com.hereliesaz.graffitixr.common.scene.planePose
import kotlin.math.abs

/** Why "Build surface from image" cannot start on a layer. */
internal enum class ReliefRefusal {
    NO_LAYER, NOT_A_PHOTO, LOCKED, NO_PIXELS, NO_CANVAS, ON_SURFACE, PLACED_IN_3D, ROTATED,
}

/** What building a relief from a layer will cover, or why it cannot be built. */
internal sealed interface ReliefPlan {
    /**
     * The photo covers [left], [top], [right], [bottom] of the group's unrolled plane (scene pixels) once it is
     * inside the new group; [photoWidth] x [photoHeight] is its bitmap.
     */
    data class Ok(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val photoWidth: Int,
        val photoHeight: Int,
    ) : ReliefPlan

    data class Refused(val reason: ReliefRefusal) : ReliefPlan
}

/** The ids of the three layers a build adds: the relief group, its skin (the photo's copy) and the flat group. */
internal data class ReliefIds(val relief: String, val skin: String, val flat: String)

/** The names (string resources, resolved by the caller) of the relief group, the flat group and the skin. */
internal data class ReliefNames(val relief: String, val flat: String, val skin: String)

/** The photo's flat surface: its [profile] and how far the original moves, [shift], to start at the sheet's corner. */
internal data class ReliefFlatSheet(val profile: SurfaceProfile, val shift: Offset)

/**
 * "Build surface from image" (spec/3d-space.md, "Relief surfaces", "The photo is duplicated"): the pure parts. The
 * photo stays as it was on a hidden flat surface and a copy of it is the skin of the new relief group.
 */
internal object ReliefBuild {
    /**
     * Whether [layerId] can become a relief and where its photo will sit on the group's plane. The group's
     * unrolled plane is its box plane, and the photo keeps its own 2D scale and offset inside it, so the extent
     * is the photo's image rectangle through that flat pose. A rotated or mirrored photo is refused: the grid is
     * an axis-aligned rectangle of the plane, and the depth is sampled over exactly the photo.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun plan(
        layers: List<Layer>,
        layerId: String,
        bitmapWidth: Int,
        bitmapHeight: Int,
        canvasWidth: Float,
        canvasHeight: Float,
    ): ReliefPlan {
        fun refuse(r: ReliefRefusal) = ReliefPlan.Refused(r)
        val layer = layers.firstOrNull { it.id == layerId } ?: return refuse(ReliefRefusal.NO_LAYER)
        if (layer.isContainer || layer.shapes.isNotEmpty() || layer.model != null || layer.spatial != null ||
            layer.adjustment != null || layer.textParams != null || layer.surfaceViewOf != null
        ) {
            return refuse(ReliefRefusal.NOT_A_PHOTO)
        }
        if (layer.isImageLocked) return refuse(ReliefRefusal.LOCKED)
        if (SurfaceMath.ownerOf(layers, layerId) != null) return refuse(ReliefRefusal.ON_SURFACE)
        if (layer.planePose.isPlacedIn3D) return refuse(ReliefRefusal.PLACED_IN_3D)
        if (!(canvasWidth > 0f && canvasHeight > 0f)) return refuse(ReliefRefusal.NO_CANVAS)
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return refuse(ReliefRefusal.NO_PIXELS)
        val rect = DepthComposite.imageRect(layer, bitmapWidth, bitmapHeight, canvasWidth, canvasHeight)
            ?: return refuse(ReliefRefusal.NO_PIXELS)
        val toPlane = SurfaceMath.boxToUnrolled(listOf(layer.planePose), canvasWidth, canvasHeight)
        val tl = toPlane.apply(rect[0], rect[1], 0f)
        val tr = toPlane.apply(rect[2], rect[1], 0f)
        val bl = toPlane.apply(rect[0], rect[3], 0f)
        val br = toPlane.apply(rect[2], rect[3], 0f)
        val upright = abs(tl[1] - tr[1]) < TOLERANCE && abs(tl[0] - bl[0]) < TOLERANCE &&
            tr[0] > tl[0] + TOLERANCE && bl[1] > tl[1] + TOLERANCE
        if (!upright) return refuse(ReliefRefusal.ROTATED)
        return ReliefPlan.Ok(tl[0], tl[1], br[0], br[1], bitmapWidth, bitmapHeight)
    }

    /**
     * The flat surface the original photo [layer] is tied to ("The photo is duplicated"): the art-sized sheet of
     * "The art-sized sheet" with the photo's whole bitmap as the art box, through the photo's own pose, in a group with
     * no pose of its own. Null when the box, pose or canvas cannot make a sheet.
     */
    fun flatSheet(layer: Layer, bitmapWidth: Int, bitmapHeight: Int, canvasWidth: Float, canvasHeight: Float): ReliefFlatSheet? {
        val box = ArtSheet.boxOf(
            ArtBounds(0, 0, bitmapWidth, bitmapHeight), layer.frame(bitmapWidth, bitmapHeight), canvasWidth, canvasHeight,
        )
        val rect = box?.let { ArtSheet.plan(it, layer.planePose, canvasWidth, canvasHeight) }?.rect
        val profile = DefaultSurface.profile(rect, canvasWidth, canvasHeight, emptyList())
        return if (rect == null || profile == null) null else ReliefFlatSheet(profile, Offset(rect.x, rect.y))
    }

    /**
     * [layers] with the photo [layerId] duplicated: in its place (and folder) the relief group [ReliefIds.relief]
     * carrying [relief], whose first child is [skin] (the photo's copy), then the HIDDEN flat group [ReliefIds.flat]
     * carrying [flat], whose only child is the original photo, moved by [ReliefFlatSheet.shift]. Unchanged for an
     * unknown id.
     */
    fun assemble(
        layers: List<Layer>,
        layerId: String,
        ids: ReliefIds,
        names: ReliefNames,
        relief: ReliefSurface,
        flat: ReliefFlatSheet,
        skin: Layer,
    ): List<Layer> {
        val leaf = layers.firstOrNull { it.id == layerId } ?: return layers
        val reliefGroup = Layer(
            id = ids.relief, name = names.relief, type = LayerType.GROUP, parentId = leaf.parentId,
            surface = relief.toProfile(),
        )
        val flatGroup = Layer(
            id = ids.flat, name = names.flat, type = LayerType.GROUP, parentId = leaf.parentId,
            isVisible = false, surface = flat.profile,
        )
        val skinned = skin.copy(id = ids.skin, name = names.skin, parentId = ids.relief)
        val original = leaf.copy(parentId = ids.flat, offset = leaf.offset - flat.shift)
        return layers.flatMap {
            if (it.id == layerId) listOf(reliefGroup, skinned, flatGroup, original) else listOf(it)
        }
    }

    /** [layers] with group [groupId]'s relief replaced by [change] of it; unchanged when it has none. */
    fun tuned(layers: List<Layer>, groupId: String, change: (ReliefSurface) -> ReliefSurface): List<Layer> {
        val group = layers.firstOrNull { it.id == groupId }
        val relief = group?.surface?.relief ?: return layers
        val changed = change(relief)
        if (changed == relief) return layers
        return layers.map { if (it.id == groupId) it.copy(surface = it.surface?.copy(relief = changed)) else it }
    }

    /** The relief group [layerId] is, or is paint on; null when its surface is not a relief. */
    fun reliefGroupOf(layers: List<Layer>, layerId: String?): Layer? =
        SurfaceMath.ownerOf(layers, layerId)?.group?.takeIf { it.surface?.relief != null }

    /**
     * The relief of [bitmap] over [plan]'s extent: the depth model's inverse depth, sampled onto the grid.
     * @throws ReliefDepthException the model's failure, or a depth map that cannot make a surface.
     */
    suspend fun estimate(source: ReliefDepthSource, bitmap: Bitmap, plan: ReliefPlan.Ok, options: ReliefOptions): ReliefSurface {
        val depth = source.estimate(bitmap)
        if (depth.width <= 0 || depth.height <= 0 || depth.values.size != depth.width * depth.height) {
            throw ReliefDepthException("The depth model returned an empty depth map.")
        }
        return ReliefSurface.build(
            depth.values, depth.width, depth.height, plan.photoWidth, plan.photoHeight,
            plan.left, plan.top, plan.right, plan.bottom, options,
        ) ?: throw ReliefDepthException("The depth map could not be made into a surface.")
    }

    private const val TOLERANCE = 1e-2f
}
