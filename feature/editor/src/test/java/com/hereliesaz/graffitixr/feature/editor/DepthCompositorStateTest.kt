@file:Suppress("MagicNumber", "MaxLineLength")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.graphics.BlendMode
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.scene.DepthComposite
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SurfaceProfile
import com.hereliesaz.graffitixr.feature.editor.threed.DepthCompositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Draw-state decisions of the depth pass that must hold without a GL context. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DepthCompositorStateTest {
    @Test
    fun `a draw run always sets depth writes for its first draw, whatever state it inherits`() {
        // A nested run inside a blended (no depth write) draw must turn writes on for opaque draws.
        assertEquals(true, DepthCompositor.depthMaskChange(null, blended = false))
        assertEquals(false, DepthCompositor.depthMaskChange(null, blended = true))
        // Once set, only a change of kind touches it (current = whether blended draws are masked).
        assertNull(DepthCompositor.depthMaskChange(false, blended = false))
        assertEquals(false, DepthCompositor.depthMaskChange(false, blended = true))
        assertEquals(true, DepthCompositor.depthMaskChange(true, blended = false))
    }

    private val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    private val wall = SurfaceProfile(listOf(0f, 0f, 0f, 500f, 0f, 0f, 1000f, 0f, 400f))
    private fun grp(id: String, opacity: Float) = Layer(id, id, type = LayerType.GROUP, opacity = opacity, surface = wall)
    private fun kid(id: String, parent: String) = Layer(id, id, bitmap = bmp, parentId = parent)

    private fun order(layers: List<Layer>): List<DepthCompositor.Draw> {
        val camera = SceneCamera()
        return DepthCompositor.drawOrder(
            DepthScene.build(
                layers, DepthComposite.plan(layers, camera), camera, 1000f, 800f,
                pixels = { l -> l.bitmap?.let { it to 0L } }, vectors = { null }, activeId = null,
            ),
        )
    }

    private val lowFirst = listOf(grp("low", 0.5f), kid("a", "low"), grp("up", 1f), kid("b", "up"))
    private val upFirst = listOf(grp("up", 1f), kid("b", "up"), grp("low", 0.5f), kid("a", "low"))

    @Test
    fun `a translucent group draws in stack order with the other surface groups, writing depth`() {
        // Coincident groups: the one above must draw after the one below, so alpha (source-over)
        // decides where each shows, not a depth tie-break.
        val below = order(lowFirst)
        assertEquals(listOf("low", "b"), below.map { it.id })
        val above = order(upFirst)
        assertEquals(listOf("b", "low"), above.map { it.id })
        assertTrue(below.none { it.blended } && above.none { it.blended })
        assertTrue(below[0].member is DepthCompositor.Isolated)
    }

    @Test
    fun `a cached render's ids and key follow the draw order, so reordering coincident groups changes them`() {
        val ids = listOf("low", "b")
        val a = DepthCompositor.inDrawOrder(ids, order(lowFirst))
        val b = DepthCompositor.inDrawOrder(ids, order(upFirst))
        assertEquals(listOf("low", "b"), a)
        assertEquals(listOf("b", "low"), b)
        assertTrue(a != b)
    }

    @Test
    fun `a group at opacity 0 leaves no fragment, so it cannot occlude what is drawn after it`() {
        // Opaque child paint (alpha 1) at opacity 0 used to pass the unscaled test and write depth.
        assertFalse(DepthCompositor.isolatedFragmentVisible(alpha = 1f, opacity = 0f))
        assertFalse(DepthCompositor.isolatedFragmentVisible(alpha = 0f, opacity = 1f))
        assertTrue(DepthCompositor.isolatedFragmentVisible(alpha = 1f, opacity = 1f))
        assertTrue(DepthCompositor.isolatedFragmentVisible(alpha = 1f, opacity = 0.5f))
    }

    @Test
    fun `the isolated shader discards on the opacity-scaled alpha before writing depth`() {
        val fs = DepthCompositor.ISOLATED_FS
        val scaled = fs.indexOf("* uOpacity")
        val discard = fs.indexOf("discard")
        val depth = fs.indexOf("gl_FragDepth =")
        assertTrue(scaled in 0 until discard)
        assertTrue(discard in 0 until depth)
    }
}
