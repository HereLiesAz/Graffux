// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/threed/ModelRenderer.kt
package com.hereliesaz.graffitixr.feature.editor.threed

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.hereliesaz.graffitixr.common.mesh.Mesh
import com.hereliesaz.graffitixr.common.mesh.OrbitCamera
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws a [Mesh] from an [OrbitCamera]. GLES 2.0, because that is the floor this app already
 * targets (minSdk 26) and nothing here needs more.
 *
 * The renderer owns no interaction state: the camera is set from outside, so gesture handling stays
 * in Compose where the rest of the app's input lives, and the whole camera model stays pure and
 * testable. This class is only the GL half — upload, transform, shade.
 *
 * Lit with a single headlight (a directional light aligned with the view). Cheap, and it means a
 * model can never be lost in shadow no matter where the user orbits to, which matters more for an
 * inspection view than physical accuracy does.
 */
class ModelRenderer : GLSurfaceView.Renderer {

    /** Set from the UI thread; read on the GL thread. Volatile so the change is seen. */
    @Volatile var camera: OrbitCamera = OrbitCamera()
    @Volatile private var pendingMesh: Mesh? = null
    @Volatile private var aspect: Float = 1f

    private var program = 0
    private var vertexBuffer: FloatBuffer? = null
    private var indexBuffer: IntBuffer? = null
    private var indexCount = 0

    private var aPosition = 0
    private var aNormal = 0
    private var uMvp = 0
    private var uModel = 0
    private var uLightDir = 0
    private var uColor = 0

    /** Queues a mesh for upload on the next frame — GL calls are only legal on the GL thread. */
    fun setMesh(mesh: Mesh) {
        pendingMesh = mesh
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.078f, 0.086f, 0.114f, 1f)   // the app's #14161D ground
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        // Back-face culling: an OBJ from a sane exporter is wound consistently, and culling halves
        // the fragment work on a closed model.
        GLES20.glEnable(GLES20.GL_CULL_FACE)

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uLightDir = GLES20.glGetUniformLocation(program, "uLightDir")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = if (height > 0) width.toFloat() / height.toFloat() else 1f
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingMesh?.let { upload(it); pendingMesh = null }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val vb = vertexBuffer ?: return
        val ib = indexBuffer ?: return
        if (indexCount == 0) return

        val cam = camera
        val view = cam.viewMatrix()
        val proj = cam.projectionMatrix(aspect)
        // Model is identity for now — the mesh is drawn in its own coordinates and the camera does
        // the framing, so there is nothing to transform per-object yet.
        val mvp = multiply(proj, view)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, IDENTITY, 0)

        // Headlight: point the light along the camera's own view direction.
        val eye = cam.eye()
        val lx = cam.target[0] - eye[0]
        val ly = cam.target[1] - eye[1]
        val lz = cam.target[2] - eye[2]
        GLES20.glUniform3f(uLightDir, -lx, -ly, -lz)
        GLES20.glUniform3f(uColor, 0.82f, 0.84f, 0.88f)

        val stride = Mesh.FLOATS_PER_VERTEX * 4
        vb.position(Mesh.POSITION_OFFSET)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, stride, vb)
        GLES20.glEnableVertexAttribArray(aPosition)
        vb.position(Mesh.NORMAL_OFFSET)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, stride, vb)
        GLES20.glEnableVertexAttribArray(aNormal)

        ib.position(0)
        // GL_UNSIGNED_INT indices need GLES 3 or OES_element_index_uint. It's near-universal on
        // API 26+ hardware, and 16-bit indices would cap a model at 65k vertices — too low for a
        // scanned or sculpted mesh, which is exactly what people will load.
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_INT, ib)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aNormal)
    }

    private fun upload(mesh: Mesh) {
        vertexBuffer = ByteBuffer.allocateDirect(mesh.vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(mesh.vertices); position(0) }
        indexBuffer = ByteBuffer.allocateDirect(mesh.indices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
            .apply { put(mesh.indices); position(0) }
        indexCount = mesh.indices.size
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val handle = GLES20.glCreateProgram()
        GLES20.glAttachShader(handle, vs)
        GLES20.glAttachShader(handle, fs)
        GLES20.glLinkProgram(handle)
        val status = IntArray(1)
        GLES20.glGetProgramiv(handle, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(handle)
            GLES20.glDeleteProgram(handle)
            throw RuntimeException("Failed to link 3D shader program: $log")
        }
        return handle
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Failed to compile 3D shader: $log")
        }
        return shader
    }

    /** Column-major 4x4 multiply, `a * b`. */
    private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0..3) {
            for (row in 0..3) {
                var sum = 0f
                for (k in 0..3) sum += a[k * 4 + row] * b[col * 4 + k]
                out[col * 4 + row] = sum
            }
        }
        return out
    }

    private companion object {
        val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )

        const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform mat4 uModel;
            attribute vec4 aPosition;
            attribute vec3 aNormal;
            varying vec3 vNormal;
            void main() {
                vNormal = mat3(uModel) * aNormal;
                gl_Position = uMvp * aPosition;
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec3 uLightDir;
            uniform vec3 uColor;
            varying vec3 vNormal;
            void main() {
                vec3 n = normalize(vNormal);
                vec3 l = normalize(-uLightDir);
                // Half-Lambert: remaps N·L into 0..1 instead of clamping at zero, so surfaces facing
                // away stay readable as form rather than collapsing into a flat silhouette.
                float lambert = dot(n, l) * 0.5 + 0.5;
                gl_FragColor = vec4(uColor * lambert, 1.0);
            }
        """
    }
}
