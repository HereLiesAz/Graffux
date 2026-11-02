@file:Suppress("MagicNumber", "TooManyFunctions", "LongMethod", "MaxLineLength", "LongParameterList", "ComplexCondition", "ReturnCount", "DestructuringDeclarationWithTooManyEntries")

package com.hereliesaz.graffitixr.feature.editor.threed

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import com.hereliesaz.graffitixr.common.mesh.SpatialGltfExporter
import com.hereliesaz.graffitixr.common.mesh.SpatialStroke
import com.hereliesaz.graffitixr.common.scene.Affine3
import com.hereliesaz.graffitixr.common.scene.CameraFrame
import com.hereliesaz.graffitixr.common.scene.DepthComposite
import com.hereliesaz.graffitixr.common.scene.SceneMath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The GPU depth pass of the 3D space (spec/3d-space.md, "Per-pixel depth"): every
 * [DepthComposite] member drawn into one depth buffer, so planes that cross occlude pixel by pixel
 * and 3D strokes sit in front of or behind them where they really are.
 *
 * Paint layers are textured quads at their placement; the layer bitmap is uploaded once per
 * content change (keyed by its identity and generation, so an unchanged layer costs nothing per
 * frame, and a live stroke costs the one upload HWUI would otherwise make for the same pixels).
 * Their colour adjustments run in the fragment shader with the same matrix the 2D canvas uses.
 * 3D strokes are the tube mesh shared with the glTF export, uploaded once per stroke list.
 *
 * 3D model layers are their mesh, lit by a headlight, with their paint texture over a base colour.
 *
 * Order: ink and models first, then planes farthest-first, depth test on and depth writes on. The test makes
 * crossings exact for opaque pixels; the order makes translucent pixels blend over what is behind.
 * Fully transparent texels are discarded so a layer's empty area never hides what is behind it.
 * Output is premultiplied over transparent, which is what a TextureView (and the export readback)
 * composites.
 */
class DepthCompositor : GLSurfaceView.Renderer {
    /** A paint layer: its image, where the image sits in its box, and the box's placement. */
    class Plane(
        val id: String,
        val bitmap: Bitmap,
        /** Anything that changes when the pixels do (a live stroke's version, say). */
        val contentToken: Long,
        val model: Affine3,
        /** left, top, right, bottom of the image in the layer's box coordinates. */
        val rect: FloatArray,
        /** The canvas's 4x5 colour matrix (row-major, offsets in 0..255), or null for none. */
        val colorMatrix: FloatArray?,
        val alpha: Float,
    )

    /** A 3D strokes layer: its tubes and the map from stroke units into the scene. */
    class Ink(val id: String, val strokes: List<SpatialStroke>, val toScene: Affine3, val alpha: Float)

    /**
     * A 3D model layer: its mesh, the map from mesh coordinates into the scene, and its paint.
     * [texture] is null when unpainted; [lock] (when set) is held while its pixels upload, because
     * a stroke in progress paints into them on the UI thread.
     */
    class Model(
        val id: String,
        val mesh: com.hereliesaz.graffitixr.common.mesh.Mesh,
        val toScene: Affine3,
        val texture: Bitmap?,
        val textureToken: Long,
        val alpha: Float,
        val lock: Any? = null,
    )

    class Scene(val camera: CameraFrame, val planes: List<Plane>, val inks: List<Ink>, val models: List<Model> = emptyList())

    @Volatile var scene: Scene? = null

    private var quadProgram = 0
    private var inkProgram = 0
    private var modelProgram = 0
    private val modelMeshes = HashMap<String, ModelBuffers>()
    private val modelTextures = HashMap<String, Texture>()
    private val textures = HashMap<String, Texture>()
    private val meshes = HashMap<String, Mesh>()
    private var viewportW = 1
    private var viewportH = 1

    private class Texture(val handle: Int, var bitmap: Bitmap?, var generation: Int, var token: Long, var w: Int, var h: Int)

    private class Mesh(val strokes: List<SpatialStroke>, val vbo: Int, val ibo: Int, val count: Int)

    private class ModelBuffers(val mesh: com.hereliesaz.graffitixr.common.mesh.Mesh, val vbo: Int, val ibo: Int, val count: Int)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context: every old handle is gone.
        textures.clear()
        meshes.clear()
        modelMeshes.clear()
        modelTextures.clear()
        quadProgram = program(QUAD_VS, QUAD_FS)
        inkProgram = program(INK_VS, INK_FS)
        modelProgram = program(MODEL_VS, MODEL_FS)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportW = width.coerceAtLeast(1)
        viewportH = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, viewportW, viewportH)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClearDepthf(1f)
        GLES30.glDepthMask(true)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val s = scene ?: return
        if (quadProgram == 0 || inkProgram == 0 || modelProgram == 0) return
        release(s)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        for (ink in s.inks) drawInk(ink, s.camera)
        // Opaque geometry first, writing depth: the ink, opaque models, then opaque planes farthest
        // first. Translucent planes and models follow in one back-to-front pass without depth
        // writes, so each blends over whatever is behind it, translucent or not.
        val (opaque, translucent) = s.models.partition { it.alpha >= 1f }
        for (model in opaque) drawModel(model, s.camera)
        val depths = s.planes.associate { p ->
            val cx = (p.rect[0] + p.rect[2]) / 2f
            val cy = (p.rect[1] + p.rect[3]) / 2f
            p.id to s.camera.toCamera(p.model.apply(cx, cy, 0f))[2]
        }
        val byId = s.planes.associateBy { it.id }
        val (solidPlanes, clearPlanes) = s.planes.partition { it.alpha >= 1f }
        for (id in DepthComposite.backToFront(depths, solidPlanes.map { it.id })) drawPlane(byId.getValue(id), s.camera)
        val blended = translucentOrder(clearPlanes, translucent, s.camera)
        if (blended.isNotEmpty()) {
            GLES30.glDepthMask(false)
            for (item in blended) {
                when (item) {
                    is Plane -> drawPlane(item, s.camera)
                    is Model -> drawModel(item, s.camera)
                }
            }
            GLES30.glDepthMask(true)
        }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    }

    /** Frees what no member uses any more. */
    private fun release(s: Scene) {
        val planes = s.planes.mapTo(HashSet()) { it.id }
        textures.keys.filter { it !in planes }.forEach { id ->
            GLES30.glDeleteTextures(1, intArrayOf(textures.remove(id)!!.handle), 0)
        }
        val inks = s.inks.mapTo(HashSet()) { it.id }
        meshes.keys.filter { it !in inks }.forEach { id ->
            val m = meshes.remove(id)!!
            GLES30.glDeleteBuffers(2, intArrayOf(m.vbo, m.ibo), 0)
        }
        val models = s.models.mapTo(HashSet()) { it.id }
        modelMeshes.keys.filter { it !in models }.forEach { id ->
            val m = modelMeshes.remove(id)!!
            GLES30.glDeleteBuffers(2, intArrayOf(m.vbo, m.ibo), 0)
        }
        modelTextures.keys.filter { it !in models }.forEach { id ->
            GLES30.glDeleteTextures(1, intArrayOf(modelTextures.remove(id)!!.handle), 0)
        }
    }

    private fun modelBuffers(model: Model): ModelBuffers? {
        modelMeshes[model.id]?.takeIf { it.mesh === model.mesh }?.let { return it }
        modelMeshes.remove(model.id)?.let { GLES30.glDeleteBuffers(2, intArrayOf(it.vbo, it.ibo), 0) }
        val mesh = model.mesh
        if (mesh.indices.isEmpty()) return null
        val vertices = ByteBuffer.allocateDirect(mesh.vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertices.put(mesh.vertices).flip()
        val indices = ByteBuffer.allocateDirect(mesh.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        indices.put(mesh.indices).flip()
        val h = IntArray(2)
        GLES30.glGenBuffers(2, h, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, h[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.vertices.size * 4, vertices, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, h[1])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 4, indices as IntBuffer, GLES30.GL_STATIC_DRAW)
        return ModelBuffers(mesh, h[0], h[1], mesh.indices.size).also { modelMeshes[model.id] = it }
    }

    /** The model's paint on the GPU, uploaded only when its pixels or their token change. */
    private fun modelTexture(model: Model): Texture? {
        val bitmap = model.texture?.takeUnless { it.isRecycled } ?: return null
        val existing = modelTextures[model.id]
        if (existing != null && existing.bitmap === bitmap && existing.generation == bitmap.generationId &&
            existing.token == model.textureToken
        ) {
            return existing
        }
        val t = existing ?: run {
            val h = IntArray(1)
            GLES30.glGenTextures(1, h, 0)
            Texture(h[0], null, -1, -1L, 0, 0).also { modelTextures[model.id] = it }
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.handle)
        val upload = {
            if (t.w == bitmap.width && t.h == bitmap.height) {
                GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bitmap)
            } else {
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                // UVs outside the unit square tile, as the painting maths wraps them.
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
                t.w = bitmap.width
                t.h = bitmap.height
            }
        }
        val lock = model.lock
        if (lock != null) synchronized(lock) { upload() } else upload()
        t.bitmap = bitmap
        t.generation = bitmap.generationId
        t.token = model.textureToken
        return t
    }

    private fun drawModel(model: Model, cam: CameraFrame) {
        val buffers = modelBuffers(model) ?: return
        val tex = modelTexture(model)
        GLES30.glUseProgram(modelProgram)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(modelProgram, "uMvp"), 1, false, SceneMath.clipMatrix(model.toScene, cam), 0)
        // Normals into the scene: the linear part is a rotation times a uniform scale, so it is its
        // own normal matrix once normalized in the shader.
        val m = model.toScene.m
        val normal = floatArrayOf(m[0], m[4], m[8], m[1], m[5], m[9], m[2], m[6], m[10])
        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(modelProgram, "uNormal"), 1, false, normal, 0)
        // A headlight: lit from the eye, so no side of the model is ever lost in shadow.
        GLES30.glUniform3f(GLES30.glGetUniformLocation(modelProgram, "uToLight"), -cam.forward[0], -cam.forward[1], -cam.forward[2])
        val base = com.hereliesaz.graffitixr.common.scene.SceneGltfExporter.MODEL_BASE_COLOR
        GLES30.glUniform3f(GLES30.glGetUniformLocation(modelProgram, "uBase"), base[0], base[1], base[2])
        GLES30.glUniform1f(GLES30.glGetUniformLocation(modelProgram, "uAlpha"), model.alpha.coerceIn(0f, 1f))
        GLES30.glUniform1f(GLES30.glGetUniformLocation(modelProgram, "uHasTexture"), if (tex != null) 1f else 0f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex?.handle ?: 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(modelProgram, "uTex"), 0)
        val stride = com.hereliesaz.graffitixr.common.mesh.Mesh.FLOATS_PER_VERTEX * 4
        val aPos = GLES30.glGetAttribLocation(modelProgram, "aPosition")
        val aNormal = GLES30.glGetAttribLocation(modelProgram, "aNormal")
        val aUv = GLES30.glGetAttribLocation(modelProgram, "aUv")
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers.vbo)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers.ibo)
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glEnableVertexAttribArray(aNormal)
        GLES30.glEnableVertexAttribArray(aUv)
        GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glVertexAttribPointer(aNormal, 3, GLES30.GL_FLOAT, false, stride, 3 * 4)
        GLES30.glVertexAttribPointer(aUv, 2, GLES30.GL_FLOAT, false, stride, 6 * 4)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, buffers.count, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glDisableVertexAttribArray(aPos)
        GLES30.glDisableVertexAttribArray(aNormal)
        GLES30.glDisableVertexAttribArray(aUv)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun texture(p: Plane): Texture? {
        if (p.bitmap.isRecycled) return null
        val existing = textures[p.id]
        val gen = p.bitmap.generationId
        if (existing != null && existing.bitmap === p.bitmap && existing.generation == gen && existing.token == p.contentToken) {
            return existing
        }
        val t = existing ?: run {
            val h = IntArray(1)
            GLES30.glGenTextures(1, h, 0)
            Texture(h[0], null, -1, -1L, 0, 0).also { textures[p.id] = it }
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.handle)
        if (t.w == p.bitmap.width && t.h == p.bitmap.height) {
            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, p.bitmap)
        } else {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, p.bitmap, 0)
            t.w = p.bitmap.width
            t.h = p.bitmap.height
        }
        t.bitmap = p.bitmap
        t.generation = gen
        t.token = p.contentToken
        return t
    }

    private fun drawPlane(p: Plane, cam: CameraFrame) {
        val tex = texture(p) ?: return
        val (l, t, r, b) = p.rect.toList()
        val pos = floats(l, t, r, t, r, b, l, b)
        val uv = floats(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        GLES30.glUseProgram(quadProgram)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(quadProgram, "uMvp"), 1, false, SceneMath.clipMatrix(p.model, cam), 0)
        val (matrix, offset) = colorUniforms(p.colorMatrix)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(quadProgram, "uColor"), 1, false, matrix, 0)
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(quadProgram, "uOffset"), 1, offset, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(quadProgram, "uAlpha"), p.alpha.coerceIn(0f, 1f))
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex.handle)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(quadProgram, "uTex"), 0)
        val aPos = GLES30.glGetAttribLocation(quadProgram, "aPos")
        val aUv = GLES30.glGetAttribLocation(quadProgram, "aUv")
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glEnableVertexAttribArray(aUv)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, pos)
        GLES30.glVertexAttribPointer(aUv, 2, GLES30.GL_FLOAT, false, 0, uv)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4)
        GLES30.glDisableVertexAttribArray(aPos)
        GLES30.glDisableVertexAttribArray(aUv)
    }

    private fun mesh(ink: Ink): Mesh? {
        meshes[ink.id]?.takeIf { it.strokes === ink.strokes }?.let { return it }
        meshes.remove(ink.id)?.let { GLES30.glDeleteBuffers(2, intArrayOf(it.vbo, it.ibo), 0) }
        val built = try {
            SpatialGltfExporter.buildMesh(ink.strokes)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "3D ink exceeds the geometry limit", e)
            return null
        }
        if (built.indices.isEmpty()) return null
        val vertices = built.positions.size / 3
        val interleaved = ByteBuffer.allocateDirect(vertices * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until vertices) {
            interleaved.put(built.positions, i * 3, 3)
            interleaved.put(built.colors, i * 4, 4)
        }
        interleaved.flip()
        val indices = ByteBuffer.allocateDirect(built.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        indices.put(built.indices).flip()
        val h = IntArray(2)
        GLES30.glGenBuffers(2, h, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, h[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices * 7 * 4, interleaved, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, h[1])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, built.indices.size * 4, indices as IntBuffer, GLES30.GL_STATIC_DRAW)
        return Mesh(ink.strokes, h[0], h[1], built.indices.size).also { meshes[ink.id] = it }
    }

    private fun drawInk(ink: Ink, cam: CameraFrame) {
        val m = mesh(ink) ?: return
        GLES30.glUseProgram(inkProgram)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(inkProgram, "uMvp"), 1, false, SceneMath.clipMatrix(ink.toScene, cam), 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(inkProgram, "uAlpha"), ink.alpha.coerceIn(0f, 1f))
        val aPos = GLES30.glGetAttribLocation(inkProgram, "aPosition")
        val aColor = GLES30.glGetAttribLocation(inkProgram, "aColor")
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, m.vbo)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.ibo)
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glEnableVertexAttribArray(aColor)
        GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 7 * 4, 0)
        GLES30.glVertexAttribPointer(aColor, 4, GLES30.GL_FLOAT, false, 7 * 4, 3 * 4)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, m.count, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glDisableVertexAttribArray(aPos)
        GLES30.glDisableVertexAttribArray(aColor)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun floats(vararg v: Float): FloatBuffer =
        ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); flip() }

    private fun program(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Depth compositor shader: ${GLES30.glGetShaderInfoLog(s)}" }
            return s
        }
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Depth compositor link: ${GLES30.glGetProgramInfoLog(p)}" }
        return p
    }

    companion object {
        private const val TAG = "DepthCompositor"

        /**
         * The order translucent planes and models draw in, together: farthest first, by the camera
         * depth of each plane's image centre and each mesh's bounding centre. Returns [Plane]s and
         * [Model]s.
         */
        fun translucentOrder(planes: List<Plane>, models: List<Model>, cam: CameraFrame): List<Any> {
            val items = ArrayList<Pair<Any, Float>>(planes.size + models.size)
            planes.forEach { p ->
                val centre = p.model.apply((p.rect[0] + p.rect[2]) / 2f, (p.rect[1] + p.rect[3]) / 2f, 0f)
                items += p to cam.toCamera(centre)[2]
            }
            models.forEach { m ->
                val c = m.mesh.center()
                items += m to cam.toCamera(m.toScene.apply(c[0], c[1], c[2]))[2]
            }
            return items.sortedByDescending { it.second }.map { it.first }
        }

        /**
         * The canvas's 4x5 colour matrix as GL uniforms: a column-major mat4 and an offset in 0..1.
         * Identity when there is none.
         */
        fun colorUniforms(values: FloatArray?): Pair<FloatArray, FloatArray> {
            if (values == null || values.size < 20) {
                return floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f) to FloatArray(4)
            }
            val m = FloatArray(16) { i -> values[(i % 4) * 5 + i / 4] }
            val o = FloatArray(4) { r -> values[r * 5 + 4] / 255f }
            return m to o
        }

        private const val QUAD_VS = """#version 300 es
            uniform mat4 uMvp;
            in vec2 aPos;
            in vec2 aUv;
            out vec2 vUv;
            void main() { vUv = aUv; gl_Position = uMvp * vec4(aPos, 0.0, 1.0); }"""

        // The bitmap is premultiplied; the colour matrix applies to straight colour, as on the canvas.
        private const val QUAD_FS = """#version 300 es
            precision mediump float;
            uniform sampler2D uTex;
            uniform mat4 uColor;
            uniform vec4 uOffset;
            uniform float uAlpha;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                vec4 c = texture(uTex, vUv);
                if (c.a < 0.004) discard;
                vec4 straight = vec4(c.rgb / c.a, c.a);
                vec4 m = clamp(uColor * straight + uOffset, 0.0, 1.0);
                float a = m.a * uAlpha;
                if (a < 0.004) discard;
                fragColor = vec4(m.rgb * a, a);
            }"""

        private const val MODEL_VS = """#version 300 es
            uniform mat4 uMvp;
            uniform mat3 uNormal;
            in vec3 aPosition;
            in vec3 aNormal;
            in vec2 aUv;
            out vec3 vNormal;
            out vec2 vUv;
            void main() { vNormal = uNormal * aNormal; vUv = aUv; gl_Position = uMvp * vec4(aPosition, 1.0); }"""

        // The paint (premultiplied) over the base colour, then a two-sided headlight.
        private const val MODEL_FS = """#version 300 es
            precision mediump float;
            uniform sampler2D uTex;
            uniform float uHasTexture;
            uniform vec3 uBase;
            uniform vec3 uToLight;
            uniform float uAlpha;
            in vec3 vNormal;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                vec3 n = normalize(vNormal);
                float light = abs(dot(n, normalize(uToLight))) * 0.6 + 0.4;
                vec4 paint = texture(uTex, vUv) * uHasTexture;
                vec3 surface = paint.rgb + uBase * (1.0 - paint.a);
                fragColor = vec4(surface * light * uAlpha, uAlpha);
            }"""

        private const val INK_VS = """#version 300 es
            uniform mat4 uMvp;
            in vec3 aPosition;
            in vec4 aColor;
            out vec4 vColor;
            void main() { vColor = aColor; gl_Position = uMvp * vec4(aPosition, 1.0); }"""

        private const val INK_FS = """#version 300 es
            precision mediump float;
            uniform float uAlpha;
            in vec4 vColor;
            out vec4 fragColor;
            void main() {
                float a = vColor.a * uAlpha;
                if (a < 0.004) discard;
                fragColor = vec4(vColor.rgb * a, a);
            }"""
    }
}
