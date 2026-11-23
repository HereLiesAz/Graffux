@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The guided flow's step machine (spec/guided-surface.md, sections 4 and 6): pure, nothing touches a document. */
class SurfaceGuideStateTest {
    private fun outline(view: SurfaceView, pts: List<Pair<Float, Float>>, depth: Float = 0f): SurfaceProfile {
        val (u, v) = when (view) {
            SurfaceView.FRONT -> 0 to 1
            SurfaceView.SIDE -> 2 to 1
            SurfaceView.TOP -> 0 to 2
        }
        val flat = pts.flatMap { (p, q) -> FloatArray(3).also { it[u] = p; it[v] = q; it[view.axis] = depth }.toList() }
        val sweep = FloatArray(3).also { it[view.axis] = 1f }
        return SurfaceProfile(flat, sweep[0], sweep[1], sweep[2])
    }

    private val rect = listOf(0f to 0f, 100f to 0f, 100f to 60f, 0f to 60f, 0f to 0f)
    private val circle = (0 until 32).map { 50f + 40f * cos(2 * PI.toFloat() * it / 32) to 50f + 40f * sin(2 * PI.toFloat() * it / 32) }
    private val line = listOf(0f to 0f, 100f to 40f)

    private fun fresh(first: SurfaceView = SurfaceView.FRONT, existing: SurfaceProfile? = null) =
        SurfaceGuideState(GuideTarget.Group("g"), existing = existing, firstView = first)

    private fun SurfaceGuideState.draw(shape: List<Pair<Float, Float>>) =
        drawn(outline(currentView, shape))

    @Test
    fun `entering a step creates, skips and completes nothing`() {
        val s = fresh()
        assertEquals(1, s.step)
        assertTrue(s.drafts.isEmpty())
        assertFalse(s.capturing)
        assertFalse(s.hasAnySurface)
        assertEquals(GuideStepState.NOT_DRAWN, s.stateOf(1))
    }

    @Test
    fun `later steps are not presented until the earlier one has a surface`() {
        var s = fresh()
        assertTrue(s.isPresented(1))
        assertFalse(s.isPresented(2))
        assertFalse(s.isPresented(3))
        s = s.draw(circle)
        assertTrue(s.isPresented(2))
        assertFalse(s.isPresented(3))
        s = s.keep().state.draw(rect)
        assertTrue(s.isPresented(3))
        // Drawing the third while the second is missing cannot be armed: beginCapture needs a presented step.
        val noSecond = fresh().draw(circle).copy(step = 3)
        assertFalse(noSecond.beginCapture().capturing)
    }

    @Test
    fun `next and keep do nothing on a step with nothing drawn`() {
        val s = fresh()
        assertFalse(s.canNext)
        assertSame(s, s.next().state)
        assertNull(s.next().end)
        assertSame(s, s.keep().state)
        assertEquals(1, s.keep().state.step)
    }

    @Test
    fun `skip on the first step ends the flow with nothing created`() {
        val step = fresh().skip()
        assertEquals(GuideEnd.NOTHING, step.end)
        assertTrue(step.state.commits().isEmpty())
    }

    @Test
    fun `skip on the second step ends the flow with just the first surface`() {
        val step = fresh().draw(circle).keep().state.skip()
        assertEquals(GuideEnd.COMMIT, step.end)
        assertEquals(listOf(SurfaceView.FRONT), step.state.drafts.keys.toList())
        assertEquals(1, step.state.commits().size)
    }

    @Test
    fun `skip on the third step gives the second mirrored, committed as a real third`() {
        val first = outline(SurfaceView.FRONT, circle)
        val second = outline(SurfaceView.SIDE, rect, depth = 4f)
        val s = fresh().drawn(first).keep().state.drawn(second).keep().state
        assertEquals(3, s.step)
        val step = s.skip()
        assertEquals(GuideEnd.COMMIT, step.end)
        val third = step.state.drafts.getValue(SurfaceView.TOP)
        assertEquals(GuideMark.MIRRORED, third.mark)
        assertEquals(SurfaceGuide.mirrored(second, first, SurfaceView.FRONT, SurfaceView.SIDE), third.profile)
        val commits = step.state.commits()
        assertEquals(3, commits.size)
        val last = commits.last()
        assertEquals(listOf(SurfaceView.FRONT, SurfaceView.SIDE, SurfaceView.TOP), last.drawnViews)
        // Ordinary data once committed: no marker anywhere on it.
        assertEquals(third.profile, last.top)
    }

    @Test
    fun `skip on a step that already has a surface is not a skip`() {
        val s = fresh().draw(circle)
        assertSame(s, s.skip().state)
        assertNull(s.skip().end)
    }

    @Test
    fun `done commits what exists and with nothing drawn it is cancel's twin`() {
        assertEquals(GuideEnd.NOTHING, fresh().finish().end)
        val one = fresh().draw(circle).finish()
        assertEquals(GuideEnd.COMMIT, one.end)
        val three = fresh().draw(circle).keep().state.draw(rect).keep().state.draw(rect).finish()
        assertEquals(GuideEnd.COMMIT, three.end)
        assertEquals(3, three.state.commits().size)
    }

    @Test
    fun `keep on the third step is done`() {
        val s = fresh().draw(circle).keep().state.draw(rect).keep().state.draw(rect)
        assertEquals(GuideEnd.COMMIT, s.keep().end)
    }

    @Test
    fun `cancel from every state discards every draft`() {
        val states = listOf(
            fresh(),
            fresh().draw(circle),
            fresh().draw(circle).keep().state.draw(rect),
            fresh().draw(circle).beginCapture(),
        )
        for (s in states) {
            val c = s.cancel()
            assertEquals(GuideEnd.CANCELLED, c.end)
            assertTrue(c.state.drafts.isEmpty())
            assertFalse(c.state.capturing)
        }
    }

    @Test
    fun `back keeps every draft and is disabled on the first step`() {
        val s = fresh().draw(circle).keep().state.draw(rect)
        val back = s.back().state
        assertEquals(1, back.step)
        assertEquals(s.drafts, back.drafts)
        assertFalse(back.canBack)
        assertSame(back, back.back().state)
        // Redrawing an earlier surface keeps the later drafts.
        val redrawn = back.draw(line)
        assertEquals(2, redrawn.drafts.size)
        assertEquals(redrawn.drafts.getValue(SurfaceView.SIDE), s.drafts.getValue(SurfaceView.SIDE))
    }

    @Test
    fun `every first perspective walks its cyclic second and third`() {
        for (first in SurfaceView.entries) {
            var s = fresh(first)
            val expected = SurfaceGuide.order(first)
            for (n in 1..3) {
                assertEquals(expected[n - 1], s.currentView)
                s = s.draw(rect)
                if (n < 3) s = s.keep().state
            }
            assertEquals(expected, s.commits().last().drawnViews)
            assertEquals(first, s.commits().first().baseView)
        }
    }

    @Test
    fun `the first perspective may be chosen only until a surface exists`() {
        var s = fresh()
        assertTrue(s.canChooseFirstView)
        s = s.chooseFirstView(SurfaceView.TOP)
        assertEquals(SurfaceView.TOP, s.currentView)
        s = s.draw(circle)
        assertFalse(s.canChooseFirstView)
        assertEquals(SurfaceView.TOP, s.chooseFirstView(SurfaceView.SIDE).first)
        val existing = outline(SurfaceView.SIDE, circle).copy(base = SurfaceView.SIDE)
        assertEquals(SurfaceView.SIDE, fresh(existing = existing).first)
        assertFalse(fresh(existing = existing).canChooseFirstView)
    }

    @Test
    fun `a closed first surface commits a solid and an open one today's swept surface`() {
        val closed = fresh().draw(circle).commits().single()
        assertTrue(closed.solid)
        assertTrue(closed.hasHull)
        assertNull(closed.base)
        val open = fresh().draw(line).commits().single()
        assertFalse(open.solid)
        assertFalse(open.hasHull)
        assertTrue(fresh().draw(circle).firstIsClosed)
        assertFalse(fresh().draw(line).firstIsClosed)
        val sideFirst = fresh(SurfaceView.SIDE).draw(circle).commits().single()
        assertEquals(SurfaceView.SIDE, sideFirst.base)
        assertTrue(sideFirst.solid)
    }

    @Test
    fun `the flat sheet is offered on step 1 for the Z-view only and is an open first surface`() {
        val sheet = outline(SurfaceView.FRONT, line).copy(reach = 300f)
        assertTrue(fresh().canUseFlatSheet)
        assertFalse(fresh(SurfaceView.SIDE).canUseFlatSheet)
        assertFalse(fresh().draw(circle).canUseFlatSheet)
        assertFalse(fresh(existing = sheet).canUseFlatSheet)
        val s = fresh().flatSheet(sheet)
        assertTrue(s.drafts.getValue(SurfaceView.FRONT).flatSheet)
        val commit = s.draw2(rect).commits()
        // The sheet then a second: the sheet is the open first surface, the second bounds it.
        assertFalse(commit.first().solid)
        assertEquals(300f, commit.first().reach)
        assertNotNull(commit.last().side)
        assertEquals(fresh(SurfaceView.SIDE), fresh(SurfaceView.SIDE).flatSheet(sheet))
    }

    private fun SurfaceGuideState.draw2(shape: List<Pair<Float, Float>>) = keep().state.draw(shape)

    @Test
    fun `an existing group's surfaces start as drawn in step order and edit replaces only their own perspective`() {
        val base = outline(SurfaceView.FRONT, circle).copy(solid = true)
        val withSide = base.withView(SurfaceView.SIDE, outline(SurfaceView.SIDE, rect))
        var s = fresh(existing = withSide)
        assertEquals(GuideStepState.EXISTING, s.stateOf(1))
        assertEquals(GuideStepState.EXISTING, s.stateOf(2))
        assertEquals(GuideStepState.NOT_DRAWN, s.stateOf(3))
        assertTrue(s.isPresented(3))
        // Nothing drawn, nothing to commit: Done is "nothing was drawn" for the document.
        assertEquals(GuideEnd.NOTHING, s.finish().end)
        // Edit the second: the first and (absent) third are untouched.
        s = s.keep().state.draw(listOf(0f to 0f, 50f to 0f, 50f to 20f))
        val edited = s.commits().single()
        assertEquals(withSide.points, edited.points)
        assertTrue(edited.solid)
        assertEquals(s.drafts.getValue(SurfaceView.SIDE).profile.copy(side = null, top = null), edited.side)
    }

    @Test
    fun `an unchanged redraw adds no commit`() {
        val base = outline(SurfaceView.FRONT, circle).copy(solid = true)
        val s = fresh(existing = base).drawn(outline(SurfaceView.FRONT, circle))
        assertTrue(s.commits().isEmpty())
    }

    @Test
    fun `each commit is one cumulative surface so each is one history entry`() {
        val s = fresh().draw(circle).keep().state.draw(rect).keep().state.draw(rect)
        val commits = s.commits()
        assertEquals(listOf(1, 2, 3), commits.map { it.drawnViews.size })
    }

    @Test
    fun `the tool is sticky across steps and never leaves the guide`() {
        var s = fresh().chooseTool(GuideTool.PEN)
        s = s.draw(circle).keep().state
        assertEquals(GuideTool.PEN, s.tool)
        assertEquals(GuideTool.ELLIPSE, fresh().tool)
    }

    @Test
    fun `capturing is armed by draw and dropped when the stroke lands, is cancelled or the step changes`() {
        val s = fresh().beginCapture()
        assertTrue(s.capturing)
        assertFalse(s.endCapture().capturing)
        assertFalse(s.drawn(outline(SurfaceView.FRONT, circle)).capturing)
        assertFalse(s.drawn(outline(SurfaceView.FRONT, circle)).keep().state.capturing)
        assertFalse(s.cancel().state.capturing)
    }
}
