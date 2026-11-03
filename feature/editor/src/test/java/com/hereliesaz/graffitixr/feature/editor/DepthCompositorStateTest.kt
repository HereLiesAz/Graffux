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

    private fun groups(layers: List<Layer>): List<DepthCompositor.Group> {
        val camera = SceneCamera()
        return DepthScene.build(
            layers, DepthComposite.plan(layers, camera), camera, 1000f, 800f,
            pixels = { l -> l.bitmap?.let { it to 0L } }, vectors = { null }, activeId = null,
        ).groups
    }

    @Test
    fun `a translucent group below an opaque surface group is marked as covered by it, one above is not`() {
        val lowerTranslucent = groups(listOf(grp("low", 0.5f), kid("a", "low"), grp("up", 1f), kid("b", "up")))
        assertTrue(lowerTranslucent.single { it.id == "low" }.coveredByOpaqueAbove)
        val upperTranslucent = groups(listOf(grp("low", 1f), kid("a", "low"), grp("up", 0.5f), kid("b", "up")))
        assertFalse(upperTranslucent.single { it.id == "up" }.coveredByOpaqueAbove)
    }

    @Test
    fun `the composite is pushed back only when an opaque group covers it`() {
        assertTrue(DepthCompositor.compositeBias(covered = true) > 0f)
        assertEquals(0f, DepthCompositor.compositeBias(covered = false), 0f)
    }
}
