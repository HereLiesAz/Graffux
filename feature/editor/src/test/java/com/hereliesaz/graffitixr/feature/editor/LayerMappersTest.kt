package com.hereliesaz.graffitixr.feature.editor

import android.net.Uri
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Layer ↔ OverlayLayer is the save/load boundary, and a field missing from it fails silently: the
 * document looks right until it's reopened. That has already happened once in this codebase — the
 * hierarchy fields were dropped here, so groups reset to flat on reload — so the relational fields
 * get an explicit round-trip test rather than relying on review to catch the next omission.
 */
class LayerMappersTest {

    // toOverlayLayer falls back to Uri.EMPTY for a null uri, and that constant is null in the JVM
    // android stub — which would NPE on assignment to a non-null field. Every layer here carries a
    // stand-in Uri so the mapper takes the non-null branch and the test exercises the field
    // mapping rather than the Android stub's gaps.
    private val uri: Uri = mockk(relaxed = true)

    @Test
    fun `component links survive the round trip`() {
        val main = Layer(id = "m", name = "Button", uri = uri, componentId = "C1")
        val restored = main.toOverlayLayer().toLayer()
        assertEquals("C1", restored.componentId)
        assertNull(restored.instanceOf)

        val instance = Layer(id = "i", name = "Button instance", uri = uri, instanceOf = "C1")
        val restoredInstance = instance.toOverlayLayer().toLayer()
        assertEquals("C1", restoredInstance.instanceOf)
        assertNull(restoredInstance.componentId)
    }

    @Test
    fun `hierarchy fields survive the round trip`() {
        // The regression this file exists to prevent.
        val child = Layer(id = "c", name = "child", uri = uri, parentId = "g", type = LayerType.VECTOR)
        val restored = child.toOverlayLayer().toLayer()
        assertEquals("g", restored.parentId)
        assertEquals(LayerType.VECTOR, restored.type)
    }

    @Test
    fun `clipping and alpha lock survive the round trip`() {
        val clipped = Layer(id = "x", name = "x", uri = uri, clipToLayerBelow = true, alphaLock = true)
        val restored = clipped.toOverlayLayer().toLayer()
        assertEquals(true, restored.clipToLayerBelow)
        assertEquals(true, restored.alphaLock)
    }

    @Test
    fun `a plain layer round trips with no component links invented`() {
        val plain = Layer(id = "p", name = "p", uri = uri)
        val restored = plain.toOverlayLayer().toLayer()
        assertNull(restored.componentId)
        assertNull(restored.instanceOf)
        assertNull(restored.parentId)
    }
}
