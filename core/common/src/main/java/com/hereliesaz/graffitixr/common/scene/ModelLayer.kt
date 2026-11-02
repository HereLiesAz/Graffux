@file:Suppress("MagicNumber", "ReturnCount", "LongParameterList", "MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hereliesaz.graffitixr.common.mesh.Mesh
import com.hereliesaz.graffitixr.common.mesh.MeshPicker
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A 3D model layer's content (spec/3d-space.md, "3D model layers"): an imported OBJ mesh placed in
 * the canvas space by the layer's plane pose, with a texture the user paints on.
 *
 * Only the file names are saved in project.json; both files live in the project folder, so they
 * ride along in a `.fux`. [mesh] and [texture] are the runtime copies, filled when the project
 * loads (and by a co-op peer's model data). Both compare by identity, so a layer list compares
 * cheaply, and an undo snapshot holding a layer holds exactly the texture revision it had.
 *
 * Like a 3D strokes layer it is RASTER with no bitmap, so an older build opens it as an empty layer.
 */
@Serializable
data class ModelLayerContent(
    /** The OBJ file's name in the project folder. */
    val objFile: String,
    /** The painted texture's PNG in the project folder; null = never painted. */
    val textureFile: String? = null,
    val textureWidth: Int = DEFAULT_TEXTURE_SIZE,
    val textureHeight: Int = DEFAULT_TEXTURE_SIZE,
    @Transient val mesh: ModelMesh? = null,
    /** The current paint; null = unpainted (or, with a [textureFile], not loaded yet). */
    @Transient val texture: ModelTexture? = null,
) {
    companion object {
        /** Square and modest: a paint surface on a phone (4 MB at 1024). */
        const val DEFAULT_TEXTURE_SIZE = 1024

        /** A file name the project folder may hold: no separators, no `..`, nothing hidden. */
        fun isSafeFileName(name: String): Boolean =
            name.isNotEmpty() && name.length <= 128 && !name.startsWith(".") &&
                name.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
    }
}

/** A parsed mesh, compared by identity. Its bounds are what the layer frames the model by. */
class ModelMesh(val mesh: Mesh) {
    val center: FloatArray = mesh.center()
    val radius: Float = mesh.radius()
}

/**
 * One revision of a model's paint, immutable in content. It is held as pixels, as PNG, or both:
 * the current revision keeps its pixels for the renderer, older ones (only undo still holds them)
 * are [compact]ed to PNG, and a revision loaded from disk or a peer starts as PNG and decodes when
 * first drawn. Compared by identity.
 */
class ModelTexture private constructor(private var bitmap: Bitmap?, private var png: ByteArray?) {
    /** The pixels, decoded on first use. Never mutate them. Null when the PNG cannot be read. */
    @Synchronized
    fun pixels(): Bitmap? {
        bitmap?.takeUnless { it.isRecycled }?.let { return it }
        val bytes = png ?: return null
        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inMutable = false
            inPremultiplied = true
        })
        return bitmap
    }

    /** The PNG, encoded on first use. */
    @Synchronized
    fun encoded(): ByteArray? {
        png?.let { return it }
        val b = bitmap?.takeUnless { it.isRecycled } ?: return null
        png = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return png
    }

    /** Keeps only the PNG (an old revision's pixels are 4 MB; its PNG is mostly empty space). */
    @Synchronized
    fun compact() {
        if (encoded() != null) bitmap = null
    }

    companion object {
        /** A revision from pixels the caller no longer mutates. */
        fun ofPixels(bitmap: Bitmap) = ModelTexture(bitmap, null)

        fun ofPng(png: ByteArray) = ModelTexture(null, png)
    }
}

/** A paint stroke on a model layer in progress: the pixels being painted, and a token per dab. */
class ModelDraft(val layerId: String, val bitmap: Bitmap, val version: Long)

/**
 * The model layer's maths (spec/3d-space.md, "3D model layers"): where the mesh sits in the layer's
 * box, and the ray from the camera through a touch down to a UV on the surface. Pure.
 */
object ModelSpace {
    /** The model's bounding radius, as a fraction of the canvas's shorter side. */
    const val FRAME_FRACTION = 0.3f

    /**
     * Mesh coordinates into the layer's box-local 3D space: the bounding centre on the box centre,
     * the radius [FRAME_FRACTION] of the canvas's shorter side, and the OBJ's y up / z toward the
     * viewer turned into the scene's y down / z away (a half turn about x, not a mirror).
     */
    fun meshToLocal(mesh: ModelMesh, width: Float, height: Float): Affine3 {
        val k = FRAME_FRACTION * minOf(width, height) / mesh.radius
        val c = mesh.center
        return Affine3(floatArrayOf(
            k, 0f, 0f, width / 2f - k * c[0],
            0f, -k, 0f, height / 2f + k * c[1],
            0f, 0f, -k, k * c[2],
        ))
    }

    /** Mesh coordinates into the scene, through the layer's whole group chain. */
    fun meshToScene(chain: List<PlanePose>, mesh: ModelMesh, width: Float, height: Float): Affine3 =
        SceneMath.chainModel(chain, width, height) * meshToLocal(mesh, width, height)

    /** The camera's ray through canvas point ([x], [y]), in mesh coordinates. */
    fun ray(x: Float, y: Float, toScene: Affine3, frame: CameraFrame): MeshPicker.Ray? {
        val inv = toScene.inverse() ?: return null
        val origin = inv.apply(frame.eye[0], frame.eye[1], frame.eye[2])
        val d = frame.rayDirection(x, y)
        val dir = inv.applyVector(d[0], d[1], d[2])
        val len = sqrt(SceneMath.dot(dir, dir))
        if (len < 1e-12f || !len.isFinite()) return null
        return MeshPicker.Ray(origin, FloatArray(3) { dir[it] / len })
    }

    /** The nearest surface point under canvas point ([x], [y]), with its UV; null on a miss. */
    fun pick(mesh: ModelMesh, x: Float, y: Float, toScene: Affine3, frame: CameraFrame): MeshPicker.Hit? =
        ray(x, y, toScene, frame)?.let { MeshPicker.raycast(mesh.mesh, it) }

    /**
     * Texture pixels per canvas pixel on triangle [triangle] as the camera sees it: how big a brush
     * of a given on-screen size is in the texture there. The square root of the triangle's texel
     * area over its projected area; 1 when either is degenerate (edge-on, no UVs).
     */
    fun texelsPerPixel(
        mesh: ModelMesh,
        triangle: Int,
        toScene: Affine3,
        frame: CameraFrame,
        textureWidth: Int,
        textureHeight: Int,
    ): Float {
        val m = mesh.mesh
        val stride = Mesh.FLOATS_PER_VERTEX
        val screen = ArrayList<Pair<Float, Float>>(3)
        val uv = ArrayList<Pair<Float, Float>>(3)
        for (corner in 0..2) {
            val v = m.indices.getOrNull(triangle * 3 + corner) ?: return 1f
            val o = v * stride
            val p = toScene.apply(m.vertices[o], m.vertices[o + 1], m.vertices[o + 2])
            screen += frame.project(p) ?: return 1f
            uv += (m.vertices[o + Mesh.UV_OFFSET] * textureWidth) to (m.vertices[o + Mesh.UV_OFFSET + 1] * textureHeight)
        }
        fun area(t: List<Pair<Float, Float>>) = abs(
            (t[1].first - t[0].first) * (t[2].second - t[0].second) -
                (t[2].first - t[0].first) * (t[1].second - t[0].second),
        ) / 2f
        val screenArea = area(screen)
        val uvArea = area(uv)
        if (screenArea < 1e-3f || uvArea < 1e-6f) return 1f
        return sqrt(uvArea / screenArea).coerceIn(0.01f, 100f)
    }
}
