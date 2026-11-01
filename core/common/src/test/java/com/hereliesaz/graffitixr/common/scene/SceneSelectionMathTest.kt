package com.hereliesaz.graffitixr.common.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SceneSelectionMathTest {
    private val w = 1080f
    private val h = 1920f

    @Test
    fun `a flat-space selection point maps back to where it shows on the tilted plane`() {
        val camera = SceneCamera(yaw = 0.4f, pitch = -0.2f)
        val chain = listOf(PlanePose(offsetX = 20f, rotationX = 35f, rotationZ = 15f, scale = 1.3f, positionZ = 90f))
        val (wx, wy) = SceneMath.layerFlatToWorld(400f, 700f, chain, camera, w, h)!!
        val (fx, fy) = SceneMath.worldToLayerFlat(wx, wy, chain, camera, w, h)!!
        assertEquals(400f, fx, 0.05f)
        assertEquals(700f, fy, 0.05f)
    }

    @Test
    fun `hit depth orders crossing planes by where the ray meets them`() {
        val near = listOf(PlanePose(positionZ = -200f))
        val far = listOf(PlanePose(positionZ = 300f))
        val dn = SceneMath.hitDepth(w / 2, h / 2, near, SceneCamera(), w, h)
        val df = SceneMath.hitDepth(w / 2, h / 2, far, SceneCamera(), w, h)
        assertNotNull(dn)
        assertEquals(500f, df!! - dn!!, 0.05f)
    }
}
