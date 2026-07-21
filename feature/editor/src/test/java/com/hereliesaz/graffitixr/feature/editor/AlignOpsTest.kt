// FILE: feature/editor/src/test/java/com/hereliesaz/graffitixr/feature/editor/AlignOpsTest.kt
package com.hereliesaz.graffitixr.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class AlignOpsTest {

    // Layer box [100,100,200,300] (100x200) within artboard [0,0,1000,800].
    private val box = floatArrayOf(100f, 100f, 200f, 300f)
    private val artboard = floatArrayOf(0f, 0f, 1000f, 800f)

    private fun d(mode: AlignMode) = AlignOps.delta(mode, box, artboard)

    @Test fun left() = assertEquals(-100f to 0f, d(AlignMode.LEFT))
    @Test fun right() = assertEquals(800f to 0f, d(AlignMode.RIGHT))
    @Test fun hCenter() = assertEquals(350f to 0f, d(AlignMode.H_CENTER))
    @Test fun top() = assertEquals(0f to -100f, d(AlignMode.TOP))
    @Test fun bottom() = assertEquals(0f to 500f, d(AlignMode.BOTTOM))
    @Test fun vCenter() = assertEquals(0f to 200f, d(AlignMode.V_CENTER))
}
