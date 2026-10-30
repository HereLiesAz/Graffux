@file:Suppress("MagicNumber", "LongMethod")

package com.hereliesaz.graffitixr.feature.editor.threed

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.hereliesaz.graffitixr.common.mesh.MeshPicker
import com.hereliesaz.graffitixr.common.mesh.OrbitCamera
import com.hereliesaz.graffitixr.common.mesh.SpatialGltfExporter
import com.hereliesaz.graffitixr.common.mesh.SpatialStroke
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLES 3.0 depth-buffered spatial ink renderer.
 *
 * The live scene and glTF use the exact same [SpatialGltfExporter.buildMesh] triangle tessellation:
 * a nearer 3D tube hides a farther one per pixel instead of sorting whole curves by their centres.
 * Mesh construction and all GLES calls stay on the GLSurfaceView thread. The Compose UI passes only
 * immutable stroke snapshots and an [OrbitCamera], keeping its gestures and project state unchanged.
 *
 * Transparent ink still uses conventional alpha blending with depth writes; order-independent
 * translucency and physically shaded paint are separate features.
 */
class SpatialStrokeRenderer : GLSurfaceView.Renderer {
    @Volatile private var scene: List<SpatialStroke> = emptyList()
    @Volatile var camera: OrbitCamera = OrbitCamera(pitch = 0f)

    private var uploadedScene: List<SpatialStroke>? = null
    private var program = 0
    private var vertexBuffer = 0
    private var indexBuffer = 0
    private var vertexCount = 0
    private var indexCount = 0
    private var aspect = 1f
    private var aPosition = -1
    private var aColor = -1
    private var uMvp = -1

    /** A new immutable snapshot is uploaded at most once, even across multiple camera frames. */
    fun setStrokes(strokes: List<SpatialStroke>) {
        scene = strokes
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // EGL context recreation invalidates every old handle and upload, even when the scene is
        // unchanged. A subsequent frame must upload its geometry again.
        vertexBuffer = 0
        indexBuffer = 0
        uploadedScene = null
        vertexCount = 0
        indexCount = 0
        program = shaderProgram()
        aPosition = GLES30.glGetAttribLocation(program, "aPosition")
        aColor = GLES30.glGetAttribLocation(program, "aColor")
        uMvp = GLES30.glGetUniformLocation(program, "uMvp")
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glDisable(GLES30.GL_CULL_FACE) // Curved ink must be visible from either side.
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glClearDepthf(1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = if (height > 0) width.toFloat() / height else 1f
    }

    override fun onDrawFrame(gl: GL10?) {
        val current = scene
        if (uploadedScene !== current) {
            upload(current)
            uploadedScene = current
        }

        GLES30.glClearColor(0.125f, 0.125f, 0.145f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (indexCount == 0 || program == 0) return

        val snapshot = camera
        val mvp = MeshPicker.multiply(snapshot.projectionMatrix(aspect), snapshot.viewMatrix())
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
        GLES30.glEnableVertexAttribArray(aPosition)
        GLES30.glEnableVertexAttribArray(aColor)
        GLES30.glVertexAttribPointer(aPosition, 3, GLES30.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, 0)
        GLES30.glVertexAttribPointer(aColor, 4, GLES30.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, 3 * 4)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glDisableVertexAttribArray(aPosition)
        GLES30.glDisableVertexAttribArray(aColor)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    /** Upload only geometry that changed; orbiting requires new matrices, not retessellation. */
    private fun upload(strokes: List<SpatialStroke>) {
        val mesh = try {
            SpatialGltfExporter.buildMesh(strokes)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Spatial scene exceeds the geometry limit", e)
            indexCount = 0
            return
        }
        vertexCount = mesh.positions.size / POSITION_COMPONENTS
        indexCount = mesh.indices.size
        if (vertexCount == 0 || indexCount == 0) return

        val interleaved = ByteBuffer.allocateDirect(vertexCount * FLOATS_PER_VERTEX * 4)
            .order(ByteOrder.nativeOrder())
        repeat(vertexCount) { i ->
            repeat(POSITION_COMPONENTS) { axis ->
                interleaved.putFloat(mesh.positions[i * POSITION_COMPONENTS + axis])
            }
            repeat(COLOR_COMPONENTS) { channel ->
                interleaved.putFloat(mesh.colors[i * COLOR_COMPONENTS + channel])
            }
        }
        interleaved.flip()
        val indices = ByteBuffer.allocateDirect(indexCount * 4).order(ByteOrder.nativeOrder())
        mesh.indices.forEach { indices.putInt(it) }
        indices.flip()

        if (vertexBuffer == 0 || indexBuffer == 0) {
            val handles = IntArray(2)
            GLES30.glGenBuffers(2, handles, 0)
            vertexBuffer = handles[0]
            indexBuffer = handles[1]
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, interleaved.remaining(), interleaved, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.remaining(), indices, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun shaderProgram(): Int {
        val vertex = compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val handle = GLES30.glCreateProgram()
        GLES30.glAttachShader(handle, vertex)
        GLES30.glAttachShader(handle, fragment)
        GLES30.glLinkProgram(handle)
        val success = IntArray(1)
        GLES30.glGetProgramiv(handle, GLES30.GL_LINK_STATUS, success, 0)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        check(success[0] != 0) { "3D ink shader link failed: ${GLES30.glGetProgramInfoLog(handle)}" }
        return handle
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val success = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, success, 0)
        check(success[0] != 0) { "3D ink shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private companion object {
        const val TAG = "SpatialStrokeRenderer"
        const val POSITION_COMPONENTS = 3
        const val COLOR_COMPONENTS = 4
        const val FLOATS_PER_VERTEX = POSITION_COMPONENTS + COLOR_COMPONENTS

        const val VERTEX_SHADER = """
            #version 300 es
            uniform mat4 uMvp;
            in vec3 aPosition;
            in vec4 aColor;
            out vec4 vColor;
            void main() {
                vColor = aColor;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
        """
        const val FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            in vec4 vColor;
            out vec4 fragColor;
            void main() {
                fragColor = vColor;
            }
        """
    }
}
