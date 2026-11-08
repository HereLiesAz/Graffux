@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.common.model.RotationAxis.X
import com.hereliesaz.graffitixr.common.model.RotationAxis.Y
import com.hereliesaz.graffitixr.common.model.RotationAxis.Z
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The owner's invariant (spec/3d-space.md, "Body-fixed axes"): X, Y and Z name lines of the OBJECT.
 * Turning about X turns about the line through the object's sides 1 and 3, wherever it has been
 * turned to; the same for Y and Z.
 */
class BodyFixedAxesTest {
    private val w = 1000f
    private val h = 1600f
    private val eps = 1e-3f

    private fun rot(p: PlanePose) = SceneMath.rotation(p.rotationX, p.rotationY, p.rotationZ)

    private fun turn(p: PlanePose, axis: RotationAxis, deg: Float): PlanePose =
        // pivot = the plane's own centre, so only the rotation changes
        SceneMath.rotatePoseAbout(p, axis, deg, floatArrayOf(w / 2f + p.offsetX, h / 2f + p.offsetY, p.positionZ), w, h)

    /** Where the object's own [axis] line points in the scene: column of R. */
    private fun bodyAxis(p: PlanePose, axis: RotationAxis): FloatArray {
        val r = rot(p)
        val c = axis.ordinal
        return floatArrayOf(r[c], r[3 + c], r[6 + c])
    }

    private fun assertVec(msg: String, a: FloatArray, b: FloatArray) {
        for (i in 0..2) assertEquals("$msg[$i]", a[i], b[i], eps)
    }

    /** A turn about [axis] must leave the object's own [axis] line where it was. */
    private fun assertTurnsAboutOwnAxis(p: PlanePose, axis: RotationAxis, deg: Float) {
        val before = bodyAxis(p, axis)
        assertVec("$axis line moved by $deg", before, bodyAxis(turn(p, axis, deg), axis))
    }

    @Test
    fun `a square turned 90 on Z keeps its X line - sides 1 and 3 are where 2 and 4 began, and X turns about them`() {
        var p = PlanePose()
        p = turn(p, Z, 90f)
        // Side 1 (the +X end of the square, local (1,0,0)) is now where side 2 (+Y end) started.
        val side1 = rot(p).let { r -> floatArrayOf(r[0], r[3], r[6]) }
        assertVec("side 1 at old side 2", floatArrayOf(0f, 1f, 0f), side1)
        // The body X line is now the scene's vertical, and the X slider turns about exactly it.
        assertVec("X line", floatArrayOf(0f, 1f, 0f), bodyAxis(p, X))
        val q = turn(p, X, 40f)
        assertVec("X line unchanged by an X turn", floatArrayOf(0f, 1f, 0f), bodyAxis(q, X))
        // The point at the end of side 1 does not move when turning about X; side 2's end does.
        assertVec("side 1 stays", side1, rot(q).let { r -> floatArrayOf(r[0], r[3], r[6]) })
        val side2Before = rot(p).let { r -> floatArrayOf(r[1], r[4], r[7]) }
        val side2After = rot(q).let { r -> floatArrayOf(r[1], r[4], r[7]) }
        assertEquals(kotlin.math.cos(Math.toRadians(40.0)).toFloat(), SceneMath.dot(side2Before, side2After), eps)
    }

    @Test
    fun `X then Y then X - every turn is about the object's own line, whatever came before`() {
        var p = PlanePose()
        for ((axis, deg) in listOf(X to 30f, Y to 50f, X to 20f, Z to 70f, Y to -35f, X to 15f, Z to 25f)) {
            assertTurnsAboutOwnAxis(p, axis, deg)
            p = turn(p, axis, deg)
        }
    }

    @Test
    fun `an axis's slider reading moves by exactly the turn, and a turn about another axis does not make X mean something else`() {
        var p = PlanePose(rotationX = 20f, rotationY = -35f, rotationZ = 110f)
        for ((axis, deg) in listOf(Y to 12f, X to -33f, Z to 41f, Y to 7f, X to 9f, Z to -80f, Z to 5f)) {
            val before = RotationAxis.entries.associateWith { SceneMath.bodyAngle(p, it) }
            p = turn(p, axis, deg)
            val after = SceneMath.bodyAngle(p, axis)
            val expected = before.getValue(axis) + deg
            val wrapped = ((after - expected + 540f) % 360f) - 180f
            assertEquals("$axis reading after $deg", 0f, wrapped, 0.01f)
        }
    }

    @Test
    fun `the stored angles reproduce the turned orientation exactly, so every renderer agrees`() {
        var p = PlanePose(rotationX = 20f, rotationY = -35f, rotationZ = 10f)
        var expected = rot(p)
        for ((axis, deg) in listOf(Y to 50f, X to 20f, Z to 70f, Y to -35f)) {
            p = turn(p, axis, deg)
            expected = SceneMath.mul3(expected, SceneMath.axisRotation(axis, deg))
            val stored = rot(p) // what SceneMath.model, GPU, Compose and glTF all derive from the 3 angles
            for (i in 0..8) assertEquals("m[$i]", expected[i], stored[i], 1e-4f)
        }
    }

    @Test
    fun `a pose saved by an earlier build still means the same orientation, and the X slider still turns about its own line`() {
        // Existing angles are read unchanged (Rz*Ry*Rx): nothing is migrated on load.
        val saved = PlanePose(rotationX = 20f, rotationY = -35f, rotationZ = 110f)
        val m = SceneMath.model(saved, w, h)
        assertEquals(rot(saved)[0], m.m[0], 1e-6f)
        // And X, the innermost Euler angle, reads and turns exactly as it always did.
        assertEquals(20f, SceneMath.bodyAngle(saved, X), 0.01f)
        val turned = turn(saved, X, 15f)
        assertEquals(35f, turned.rotationX, 0.01f)
        assertEquals(-35f, turned.rotationY, 0.01f)
        assertEquals(110f, turned.rotationZ, 0.01f)
    }

    @Test
    fun `the pivot stays put under a body-axis turn, so the layer revolves about it`() {
        val pose = PlanePose(offsetX = 30f, offsetY = -20f, positionZ = 100f, rotationX = 25f, rotationY = 40f, rotationZ = 15f)
        val pivot = floatArrayOf(500f, 800f, 800f)
        // The box point that sits on the pivot, in the layer's own coordinates.
        val inv = SceneMath.model(pose, w, h).inverse()!!
        val local = inv.apply(pivot[0], pivot[1], pivot[2])
        var p = pose
        for ((axis, deg) in listOf(Y to 33f, X to -50f, Z to 70f, Y to 10f)) {
            p = SceneMath.rotatePoseAbout(p, axis, deg, pivot, w, h)
            val now = SceneMath.model(p, w, h).apply(local[0], local[1], local[2])
            assertVec("pivot after $axis $deg", pivot, now)
        }
    }

    @Test
    fun `gimbal - Y at 90 degrees - still turns about the object's own line`() {
        var p = PlanePose(rotationY = 90f)
        for ((axis, deg) in listOf(X to 20f, Z to 30f, Y to 15f)) {
            assertTurnsAboutOwnAxis(p, axis, deg)
            p = turn(p, axis, deg)
        }
    }

    /**
     * The defect, kept as evidence: adding the slider delta to a stored Euler angle (what the
     * slider did before) turns about the object's own line for X only. Y turns about the line left
     * after Z alone, and Z about the scene's z, so after an X turn they are not the object's lines.
     */
    @Test
    fun `adding a delta to a stored Euler angle is body-fixed for X alone`() {
        val p = PlanePose(rotationX = 40f)
        val legacyY = p.copy(rotationY = p.rotationY + 30f)
        val legacyZ = p.copy(rotationZ = p.rotationZ + 30f)
        val ownX = bodyAxis(p, X)
        assertVec("X", ownX, bodyAxis(p.copy(rotationX = 70f), X))
        val moved = SceneMath.dot(bodyAxis(p, Y), bodyAxis(legacyY, Y))
        val movedZ = SceneMath.dot(bodyAxis(p, Z), bodyAxis(legacyZ, Z))
        assert(moved < 0.999f) { "legacy Y turn left the object's Y line alone: $moved" }
        assert(movedZ < 0.999f) { "legacy Z turn left the object's Z line alone: $movedZ" }
    }
}
