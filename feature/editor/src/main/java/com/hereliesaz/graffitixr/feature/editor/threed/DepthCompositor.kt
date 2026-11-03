@file:Suppress("MagicNumber", "TooManyFunctions", "CyclomaticComplexMethod", "LongMethod", "MaxLineLength", "LongParameterList", "ComplexCondition", "ReturnCount", "DestructuringDeclarationWithTooManyEntries", "LargeClass")

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
import com.hereliesaz.graffitixr.common.scene.DepthSplit
import com.hereliesaz.graffitixr.common.scene.SplitCache
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
 *
 * A translucent surface group (spec/3d-space.md, "Group surfaces") is drawn as one unit: its layers
 * render into an offscreen colour + depth target first, which is then drawn into the scene at the
 * group's opacity with its own per-pixel depth, so the layers merge before they fade, as on the flat
 * canvas. A group at full opacity has no unit and draws straight into the scene.
 *
 * While a stroke is in progress on a member, the others may come from the inactive-layer cache:
 * one texture of the members behind the active layer and one of those in front, split by depth
 * from the current view (spec/3d-space.md, "Inactive-layer cache").
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
        /** What the inactive-layer cache compares (size-independent); null = never cached. */
        val key: Any? = null,
        /**
         * Paint on a group surface (spec/3d-space.md, "Group surfaces"): triangles x, y, z (the
         * owning group's box-local space, which [model] places) then the image's u, v. Null for a
         * flat plane, which draws [rect] instead.
         */
        val surface: FloatArray? = null,
        /** The owning group, for a surface member: its members draw together, in stack order. */
        val surfaceGroup: String? = null,
        /** Stack position inside [surfaceGroup], bottom 0: later ones draw over coplanar paint. */
        val surfaceIndex: Int = 0,
        /** Whether the owning group's profile is straight (one plane). */
        val surfaceFlat: Boolean = true,
    )

    /** A 3D strokes layer: its tubes and the map from stroke units into the scene. */
    class Ink(val id: String, val strokes: List<SpatialStroke>, val toScene: Affine3, val alpha: Float, val key: Any? = null)

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
        val key: Any? = null,
    )

    /**
     * A translucent surface group (see [Scene.groups]): the paint layers drawn on its surface
     * ([memberIds], a subset of [Scene.planes]) and the opacity the merged image is composited with.
     * [key] is what the inactive-layer cache compares for it (null = never cached).
     */
    class Group(
        val id: String,
        val memberIds: List<String>,
        val opacity: Float,
        val key: Any? = null,
    )

    /** The uncached draw unit of a [Group]: its planes in stack order. Never touches GL on its own. */
    class Isolated(val group: Group, val planes: List<Plane>)

    /**
     * [activeId] is the layer being drawn on, [drawing] whether a stroke is in progress on it, and
     * [viewKey] the view (the camera, not the canvas size): with all three, the inactive-layer
     * cache may draw the members behind and in front of the active layer from two renders
     * (spec/3d-space.md, "Inactive-layer cache"). The export leaves them null.
     */
    class Scene(
        val camera: CameraFrame,
        val planes: List<Plane>,
        val inks: List<Ink>,
        val models: List<Model> = emptyList(),
        val activeId: String? = null,
        val drawing: Boolean = false,
        val viewKey: Any? = null,
        /** Translucent surface groups: each draws as one unit (its planes are not drawn singly). */
        val groups: List<Group> = emptyList(),
    )

    /** One member in the uncached draw order; [blended] members draw without depth writes. */
    class Draw(val id: String, val member: Any, val blended: Boolean)

    @Volatile var scene: Scene? = null

    private var quadProgram = 0
    private var inkProgram = 0
    private var modelProgram = 0
    private var blitProgram = 0
    private var surfaceProgram = 0
    private var isolatedProgram = 0

    /** An isolated group's render: premultiplied colour and depth, the size of the viewport. */
    private class IsoTarget(val color: Int, val depth: Int, val fbo: Int, val w: Int, val h: Int) {
        /** What the contents were rendered for; null when they cannot be reused. */
        var key: Any? = null
    }

    private val isoTargets = HashMap<String, IsoTarget>()
    private val isoUsed = HashSet<String>()

    /** How many isolated-group targets are allocated right now (one per translucent group in use). */
    val isolatedTargets: Int get() = isoTargets.size

    /** Isolated-group renders made so far, and target allocations (for tests and logging). */
    var isolatedRenders = 0
        private set
    var isolatedAllocations = 0
        private set

    /** Scales the opacity of every plane drawn, for a group drawn straight in when its target cannot be made. */
    private var alphaScale = 1f

    /** The behind and front renders: colour textures the size of the viewport they were built at. */
    private class Target(val texture: Int, val w: Int, val h: Int)

    private val cache = com.hereliesaz.graffitixr.common.scene.SplitCache<Target> {
        GLES30.glDeleteTextures(1, intArrayOf(it.texture), 0)
    }
    private val rest = com.hereliesaz.graffitixr.common.scene.RestGate()
    private val inkBounds = HashMap<String, Pair<List<SpatialStroke>, FloatArray?>>()
    private val meshBounds = HashMap<String, Pair<com.hereliesaz.graffitixr.common.mesh.Mesh, FloatArray?>>()

    /** How many inactive-layer renders exist (at most two). */
    val cachedRenders: Int get() = cache.liveCount
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
        isoTargets.clear()
        isoUsed.clear()
        cache.abandon()
        quadProgram = program(QUAD_VS, QUAD_FS)
        blitProgram = program(BLIT_VS, BLIT_FS)
        inkProgram = program(INK_VS, INK_FS)
        modelProgram = program(MODEL_VS, MODEL_FS)
        surfaceProgram = program(SURFACE_VS, SURFACE_FS)
        isolatedProgram = program(BLIT_VS, ISOLATED_FS)
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
        if (quadProgram == 0 || inkProgram == 0 || modelProgram == 0 || blitProgram == 0 || surfaceProgram == 0 || isolatedProgram == 0) return
        release(s)
        val order = drawOrder(s)
        isoUsed.clear()
        try {
            if (drawCached(s, order)) return
            beginPass()
            drawMembers(order, s.camera) { true }
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        } finally {
            // A group's target nothing drew from this frame (a cached render served it, or it is
            // gone) is not kept: only a group drawn every frame, like the active one, holds one.
            freeIdleIsolated()
        }
    }

    private fun beginPass() {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    /** The members [include] admits, in [order]; the blended ones without depth writes. */
    private fun drawMembers(order: List<Draw>, cam: CameraFrame, include: (String) -> Boolean) {
        // Unknown at first: a run nested in a blended draw inherits "no depth writes" and must set it.
        var masked: Boolean? = null
        for (d in order) {
            if (!include(d.id)) continue
            depthMaskChange(masked, d.blended)?.let {
                GLES30.glDepthMask(it)
                masked = d.blended
            }
            when (val m = d.member) {
                is Ink -> drawInk(m, cam)
                is Plane -> drawPlane(m, cam)
                is Model -> drawModel(m, cam)
                is Isolated -> drawIsolated(m, cam)
            }
        }
        GLES30.glDepthMask(true)
    }

    /**
     * The inactive-layer cache (spec/3d-space.md, "Inactive-layer cache"): behind render, the
     * active layer live, front render. False when this frame is drawn the ordinary way: no active
     * member, the camera moving, a member crossing the active plane, or nothing worth caching.
     */
    private fun drawCached(s: Scene, order: List<Draw>): Boolean {
        // A layer inside an isolated group is drawn live as that group's unit: the rest is split
        // around the whole group.
        val unitOf = s.groups.flatMap { g -> g.memberIds.map { it to g.id } }.toMap()
        val activeId = s.activeId?.let { unitOf[it] ?: it }
        cache.select(activeId)
        val viewKey = s.viewKey
        if (activeId == null || viewKey == null) return false
        if (!rest.settled(viewKey)) {
            cache.dumpAll()
            return false
        }
        val split = DepthSplit.classify(s.camera, activeId, splitMembers(s, unitOf), order.map { it.id })
        if (split !is DepthSplit.Split) {
            cache.dumpAll()
            return false
        }
        val keys = order.associate { it.id to memberKey(it.member) }
        fun side(slot: SplitCache.Slot, members: List<String>): Target? {
            // In draw order, so reordering stacked groups changes the key and rebuilds the render.
            val ids = inDrawOrder(members, order)
            val memberKeys = ids.map { keys[it] ?: return null.also { cache.dump(slot) } }
            if (ids.isEmpty()) return null.also { cache.dump(slot) }
            val set = ids.toHashSet()
            return cache.get(slot, listOf(viewKey, ids, memberKeys)) {
                if (s.drawing) buildTarget(order, s.camera) { it in set } else null
            }
        }
        val behind = side(SplitCache.Slot.BEHIND, split.behind)
        val front = side(SplitCache.Slot.FRONT, split.front)
        if (behind == null && front == null) return false
        val behindSet = split.behind.toHashSet()
        val frontSet = split.front.toHashSet()
        // Nothing behind can occlude the active layer (it is all beyond its plane), and nothing
        // in front can be occluded by either, so the renders need no depth here.
        if (behind != null) blit(behind) else { beginPass(); drawMembers(order, s.camera) { it in behindSet } }
        beginPass()
        drawMembers(order, s.camera) { it == activeId }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        if (front != null) blit(front) else { beginPass(); drawMembers(order, s.camera) { it in frontSet }; GLES30.glDisable(GLES30.GL_DEPTH_TEST) }
        return true
    }

    private fun memberKey(member: Any): Any? = when (member) {
        is Plane -> member.key?.let { listOf(it, System.identityHashCode(member.bitmap), member.bitmap.generationId, member.contentToken) }
        is Ink -> member.key?.let { listOf(it, System.identityHashCode(member.strokes)) }
        is Model -> member.key?.let {
            listOf(it, System.identityHashCode(member.mesh), member.texture?.let(System::identityHashCode), member.texture?.generationId, member.textureToken)
        }
        // The merged image and the opacity it is drawn with, so a fade rebuilds the render holding it.
        is Isolated -> member.group.key?.let { k ->
            val stamps = member.planes.map { memberKey(it) ?: return null }
            listOf(k, member.group.opacity, stamps)
        }
        else -> null
    }

    /** Bounding points of every member in scene coordinates, for the depth split. */
    private fun splitMembers(s: Scene, unitOf: Map<String, String>): List<DepthSplit.Member> {
        val out = ArrayList<DepthSplit.Member>(s.planes.size + s.inks.size + s.models.size)
        val unitPoints = LinkedHashMap<String, ArrayList<FloatArray>>()
        val unitPlanar = HashMap<String, Boolean>()
        for (p in s.planes) {
            val surface = p.surface
            if (surface != null) {
                val unit = unitOf[p.id]
                val pts = if (unit != null) unitPoints.getOrPut(unit) { ArrayList() } else ArrayList<FloatArray>(surface.size / 5)
                var i = 0
                while (i + 2 < surface.size) {
                    pts += p.model.apply(surface[i], surface[i + 1], surface[i + 2])
                    i += 5
                }
                if (unit != null) {
                    unitPlanar[unit] = (unitPlanar[unit] ?: true) && p.surfaceFlat
                } else {
                    out += DepthSplit.Member(p.id, pts, planar = p.surfaceFlat, group = p.surfaceGroup, stack = p.surfaceIndex)
                }
                continue
            }
            val (l, t, r, b) = p.rect.toList()
            out += DepthSplit.Member(p.id, listOf(p.model.apply(l, t, 0f), p.model.apply(r, t, 0f), p.model.apply(r, b, 0f), p.model.apply(l, b, 0f)), planar = true)
        }
        for (ink in s.inks) {
            val cached = inkBounds[ink.id]?.takeIf { it.first === ink.strokes }
            val box = cached?.second ?: inkBox(ink.strokes).also { inkBounds[ink.id] = ink.strokes to it }
            out += DepthSplit.Member(ink.id, box?.let { DepthSplit.boxCorners(it, ink.toScene) } ?: emptyList(), planar = false)
        }
        for (m in s.models) {
            val cached = meshBounds[m.id]?.takeIf { it.first === m.mesh }
            val box = cached?.second ?: m.mesh.bounds().also { meshBounds[m.id] = m.mesh to it }
            out += DepthSplit.Member(m.id, box?.let { DepthSplit.boxCorners(it, m.toScene) } ?: emptyList(), planar = false)
        }
        // An isolated group splits as one member: it is drawn, cached and kept live as a unit.
        for ((unit, pts) in unitPoints) out += DepthSplit.Member(unit, pts, planar = unitPlanar[unit] ?: false)
        inkBounds.keys.retainAll(s.inks.mapTo(HashSet()) { it.id })
        meshBounds.keys.retainAll(s.models.mapTo(HashSet()) { it.id })
        return out
    }

    /** Renders the admitted members into a new colour texture the size of the viewport. */
    private fun buildTarget(order: List<Draw>, cam: CameraFrame, include: (String) -> Boolean): Target? {
        val w = viewportW
        val h = viewportH
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val tex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glGenRenderbuffers(1, ids, 0)
        val depth = ids[0]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depth)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, w, h)
        GLES30.glGenFramebuffers(1, ids, 0)
        val fbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depth)
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        if (complete) {
            GLES30.glViewport(0, 0, w, h)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClearDepthf(1f)
            GLES30.glDepthMask(true)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            beginPass()
            drawMembers(order, cam, include)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        }
        // The depth attachment only orders the members inside the render; composing needs none.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES30.glDeleteRenderbuffers(1, intArrayOf(depth), 0)
        if (!complete) {
            GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
            return null
        }
        return Target(tex, w, h)
    }

    /**
     * A render over what is drawn, premultiplied source-over. At its own size it is copied texel
     * for texel; after a resize it is scaled into the new viewport rather than rebuilt.
     */
    private fun blit(t: Target) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(blitProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.texture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blitProgram, "uTex"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blitProgram, "uExact"), if (t.w == viewportW && t.h == viewportH) 1 else 0)
        val aPos = GLES30.glGetAttribLocation(blitProgram, "aPos")
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, FULL_SCREEN)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4)
        GLES30.glDisableVertexAttribArray(aPos)
    }

    /**
     * An isolated group: its layers are rendered into the group's target (only when what they show
     * has changed since that target was last rendered), and the target is composited into the scene
     * at the group's opacity. A target that cannot be made falls back to drawing the layers straight
     * in, each at the group's opacity too: the old, per-layer result, never a missing group.
     */
    private fun drawIsolated(u: Isolated, cam: CameraFrame) {
        val target = isoTarget(u.group.id)
        if (target == null) {
            alphaScale = u.group.opacity
            // drawMembers sets depth writes itself for these opaque draws, so a curved surface
            // still occludes itself.
            drawMembers(u.planes.map { Draw(it.id, it, false) }, cam) { true }
            alphaScale = 1f
            return
        }
        isoUsed += u.group.id
        val key = isolatedKey(u, cam)
        if (key == null || target.key != key) {
            renderIsolated(target, u, cam)
            target.key = key
            isolatedRenders++
        }
        compositeIsolated(target, u.group.opacity)
    }

    /** What the group's target shows: the view and every layer's pixels, placement, opacity and surface. */
    private fun isolatedKey(u: Isolated, cam: CameraFrame): Any? {
        val stamps = u.planes.map { memberKey(it) ?: return null }
        return listOf(cameraKey(cam), viewportW, viewportH, stamps)
    }

    private fun cameraKey(c: CameraFrame): List<Any> =
        listOf(c.width, c.height, c.focal, c.eye.toList(), c.target.toList(), c.right.toList(), c.down.toList(), c.forward.toList())

    /** The group's target at the viewport's size: kept while the size holds, made anew when it changes. */
    private fun isoTarget(id: String): IsoTarget? {
        val w = viewportW
        val h = viewportH
        isoTargets[id]?.let { if (it.w == w && it.h == h) return it else freeIsolated(id) }
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val color = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, color)
        nearestClamped()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glGenTextures(1, ids, 0)
        val depth = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depth)
        nearestClamped()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, w, h, 0, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null)
        GLES30.glGenFramebuffers(1, ids, 0)
        val fbo = ids[0]
        val previous = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, previous, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, color, 0)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, depth, 0)
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, previous[0])
        if (!complete) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES30.glDeleteTextures(2, intArrayOf(color, depth), 0)
            Log.w(TAG, "Group target ${w}x$h is not renderable; drawing the group per layer")
            return null
        }
        isolatedAllocations++
        return IsoTarget(color, depth, fbo, w, h).also { isoTargets[id] = it }
    }

    private fun nearestClamped() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    /**
     * The group's layers into its target, in stack order with depth: what an opaque group draws to
     * the screen, over a transparent clear. Premultiplied, so the composite is one multiply. May
     * run inside another render's framebuffer (a cached side holds the group), so the binding and
     * viewport it found are put back.
     */
    private fun renderIsolated(t: IsoTarget, u: Isolated, cam: CameraFrame) {
        val previousFbo = IntArray(1)
        val previousViewport = IntArray(4)
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, previousFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, previousViewport, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.fbo)
        GLES30.glViewport(0, 0, t.w, t.h)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClearDepthf(1f)
        GLES30.glDepthMask(true)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        beginPass()
        drawMembers(u.planes.map { Draw(it.id, it, false) }, cam) { true }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, previousFbo[0])
        GLES30.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3])
    }

    /**
     * The group's target over what is drawn: premultiplied source-over at [opacity], depth-tested
     * against the scene at each pixel's own stored depth (the surface's, where its paint is), no
     * depth write, like the other translucent members.
     */
    private fun compositeIsolated(t: IsoTarget, opacity: Float) {
        beginPass()
        GLES30.glDepthMask(true)
        GLES30.glUseProgram(isolatedProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.color)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.depth)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(isolatedProgram, "uColor"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(isolatedProgram, "uDepth"), 1)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(isolatedProgram, "uOpacity"), opacity.coerceIn(0f, 1f))
        val aPos = GLES30.glGetAttribLocation(isolatedProgram, "aPos")
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, FULL_SCREEN)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4)
        GLES30.glDisableVertexAttribArray(aPos)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun freeIsolated(id: String) {
        val t = isoTargets.remove(id) ?: return
        GLES30.glDeleteFramebuffers(1, intArrayOf(t.fbo), 0)
        GLES30.glDeleteTextures(2, intArrayOf(t.color, t.depth), 0)
    }

    private fun freeIdleIsolated() {
        isoTargets.keys.filter { it !in isoUsed }.forEach(::freeIsolated)
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
        val surface = p.surface
        if (surface != null) {
            drawSurface(p, surface, tex, cam)
            return
        }
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

    /**
     * Paint on a group surface: its strips, depth-tested like any member. Coplanar paint of one
     * group is pulled toward the eye by its stack position, so a layer above draws over the one
     * below on the same surface instead of fighting it for depth.
     */
    private fun drawSurface(p: Plane, surface: FloatArray, tex: Texture, cam: CameraFrame) {
        if (surface.isEmpty()) return
        GLES30.glUseProgram(surfaceProgram)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(surfaceProgram, "uMvp"), 1, false, SceneMath.clipMatrix(p.model, cam), 0)
        val (matrix, offset) = colorUniforms(p.colorMatrix)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(surfaceProgram, "uColor"), 1, false, matrix, 0)
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(surfaceProgram, "uOffset"), 1, offset, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(surfaceProgram, "uAlpha"), (p.alpha * alphaScale).coerceIn(0f, 1f))
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex.handle)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(surfaceProgram, "uTex"), 0)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        val pull = -(p.surfaceIndex + 1).toFloat()
        GLES30.glPolygonOffset(pull, pull * SURFACE_OFFSET_UNITS)
        val data = ByteBuffer.allocateDirect(surface.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(surface); flip() }
        val aPos = GLES30.glGetAttribLocation(surfaceProgram, "aPos")
        val aUv = GLES30.glGetAttribLocation(surfaceProgram, "aUv")
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glEnableVertexAttribArray(aUv)
        data.position(0)
        GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 5 * 4, data)
        data.position(3)
        GLES30.glVertexAttribPointer(aUv, 2, GLES30.GL_FLOAT, false, 5 * 4, data)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, surface.size / 5)
        GLES30.glDisableVertexAttribArray(aPos)
        GLES30.glDisableVertexAttribArray(aUv)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
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

        private val FULL_SCREEN: FloatBuffer =
            ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f)); flip()
            }

        /**
         * The uncached draw order: the ink, opaque models, then opaque planes farthest first, all
         * writing depth; then translucent planes and models in one back-to-front pass without depth
         * writes, so each blends over whatever is behind it, translucent or not.
         */
        fun drawOrder(s: Scene): List<Draw> {
            val out = ArrayList<Draw>(s.inks.size + s.models.size + s.planes.size)
            s.inks.forEach { out += Draw(it.id, it, false) }
            val (opaque, translucent) = s.models.partition { it.alpha >= 1f }
            opaque.forEach { out += Draw(it.id, it, false) }
            val depths = s.planes.associate { p ->
                val cx = (p.rect[0] + p.rect[2]) / 2f
                val cy = (p.rect[1] + p.rect[3]) / 2f
                p.id to s.camera.toCamera(p.model.apply(cx, cy, 0f))[2]
            }
            val byId = s.planes.associateBy { it.id }
            val units = isolatedUnits(s)
            val inUnit = units.flatMapTo(HashSet()) { u -> u.planes.map { it.id } }
            val unitOf = units.associateBy { u -> u.group.id }
            val (surfacePlanes, flatPlanes) = s.planes.partition { it.surface != null }
            val (solidPlanes, clearPlanes) = flatPlanes.partition { it.alpha >= 1f }
            for (id in DepthComposite.backToFront(depths, solidPlanes.map { it.id })) out += Draw(id, byId.getValue(id), false)
            // Paint on group surfaces: each group in stack order (the list's), depth written, so
            // they stack on the surface as in 2D and the surface occludes itself. A translucent
            // group is one draw (its merged image) at its place in that order, so a group above
            // covers it by alpha, source-over, exactly as before it was translucent.
            surfacePlanes.groupBy { it.surfaceGroup }.forEach { (group, planes) ->
                val unit = unitOf[group]
                if (unit != null) {
                    out += Draw(unit.group.id, unit, false)
                } else {
                    planes.filter { it.id !in inUnit }.sortedBy { it.surfaceIndex }.forEach { out += Draw(it.id, it, false) }
                }
            }
            for (item in translucentOrder(clearPlanes, translucent, s.camera)) {
                when (item) {
                    is Plane -> out += Draw(item.id, item, true)
                    is Model -> out += Draw(item.id, item, true)
                }
            }
            return out
        }

        /**
         * The depth-write state to set before a draw of kind [blended], given the kind last set
         * ([current], null when unknown): the mask value, or null when it already holds.
         */
        fun depthMaskChange(current: Boolean?, blended: Boolean): Boolean? = if (current == blended) null else !blended

        /**
         * Whether a composited group's texel survives [ISOLATED_FS]: its alpha scaled by the group's
         * [opacity], which is what is drawn and what writes depth, so opacity 0 never does.
         */
        fun isolatedFragmentVisible(alpha: Float, opacity: Float): Boolean = alpha * opacity >= 0.002f

        /** [ids] in the order [order] draws them: what a cached render holds, and keys on. */
        fun inDrawOrder(ids: List<String>, order: List<Draw>): List<String> {
            val set = ids.toHashSet()
            return order.map { it.id }.filter { it in set }
        }

        /** The scene's translucent surface groups as draw units: their planes in stack order. */
        fun isolatedUnits(s: Scene): List<Isolated> = s.groups.mapNotNull { g ->
            val ids = g.memberIds.toHashSet()
            val planes = s.planes.filter { it.id in ids && it.surface != null }.sortedBy { it.surfaceIndex }
            if (planes.isEmpty()) null else Isolated(g, planes)
        }

        /** Camera depth of a unit: the mean over its surface vertices, which orders it among translucent members. */
        fun unitDepth(u: Isolated, cam: CameraFrame): Float {
            var sum = 0f
            var n = 0
            for (p in u.planes) {
                val v = p.surface ?: continue
                var i = 0
                while (i + 2 < v.size) {
                    sum += cam.toCamera(p.model.apply(v[i], v[i + 1], v[i + 2]))[2]
                    n++
                    i += 5
                }
            }
            return if (n == 0) 0f else sum / n
        }

        /** The bounds of 3D ink in stroke units, padded by the widest tube's radius. */
        fun inkBox(strokes: List<SpatialStroke>): FloatArray? {
            var any = false
            val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (s in strokes) {
                val r = s.width * 0.5f
                for (p in s.points) {
                    any = true
                    b[0] = minOf(b[0], p.x - r); b[1] = minOf(b[1], p.y - r); b[2] = minOf(b[2], p.z - r)
                    b[3] = maxOf(b[3], p.x + r); b[4] = maxOf(b[4], p.y + r); b[5] = maxOf(b[5], p.z + r)
                }
            }
            return b.takeIf { any }
        }

        private const val BLIT_VS = """#version 300 es
            in vec2 aPos;
            out vec2 vUv;
            void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }"""

        // Already premultiplied: copied as is. texelFetch at the same size, so the copy is exact.
        private const val BLIT_FS = """#version 300 es
            precision highp float;
            uniform sampler2D uTex;
            uniform int uExact;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                fragColor = uExact == 1 ? texelFetch(uTex, ivec2(gl_FragCoord.xy), 0) : texture(uTex, vUv);
            }"""

        // A group's target (premultiplied colour and its depth) over the scene: the depth is written
        // as the fragment's, so the test against the scene is per pixel, at the surface's own depth.
        internal const val ISOLATED_FS = """#version 300 es
            precision highp float;
            uniform highp sampler2D uColor;
            uniform highp sampler2D uDepth;
            uniform float uOpacity;
            out vec4 fragColor;
            void main() {
                ivec2 p = ivec2(gl_FragCoord.xy);
                vec4 c = texelFetch(uColor, p, 0) * uOpacity;
                // The scaled alpha decides, before any depth is written: a group faded to nothing
                // must not leave an invisible occluder. Mirrors isolatedFragmentVisible().
                if (c.a < 0.002) discard;
                gl_FragDepth = texelFetch(uDepth, p, 0).r;
                fragColor = c;
            }"""

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

        /** Polygon-offset units per stack step of paint on one surface. */
        private const val SURFACE_OFFSET_UNITS = 4f

        private const val SURFACE_VS = """#version 300 es
            uniform mat4 uMvp;
            in vec3 aPos;
            in vec2 aUv;
            out vec2 vUv;
            void main() { vUv = aUv; gl_Position = uMvp * vec4(aPos, 1.0); }"""

        // QUAD_FS, plus nothing outside the image: the strips cover the image's unrolled bounds.
        private const val SURFACE_FS = """#version 300 es
            precision mediump float;
            uniform sampler2D uTex;
            uniform mat4 uColor;
            uniform vec4 uOffset;
            uniform float uAlpha;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                if (vUv.x < 0.0 || vUv.y < 0.0 || vUv.x > 1.0 || vUv.y > 1.0) discard;
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
