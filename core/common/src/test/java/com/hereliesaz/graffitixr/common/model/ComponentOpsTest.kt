package com.hereliesaz.graffitixr.common.model

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Components and instances: what propagates, what survives propagation, and what never dangles. */
class ComponentOpsTest {

    private fun layer(id: String) = Layer(id = id, name = id)

    /** A main component with distinctive content, plus one instance placed elsewhere. */
    private fun stack(): List<Layer> {
        val main = layer("main").copy(
            componentId = "C1",
            shapes = listOf(VectorShape(kind = ShapeKind.RECTANGLE, width = 100f)),
            brightness = 0.5f,
            offset = Offset(10f, 10f),
        )
        val instance = layer("inst").copy(
            instanceOf = "C1",
            offset = Offset(500f, 300f),
            scale = 2f,
            rotationZ = 45f,
            opacity = 0.4f,
            isVisible = false,
            name = "moved copy",
        )
        return listOf(main, instance, layer("unrelated"))
    }

    // ── Creating ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `makeComponent marks the layer and leaves the rest alone`() {
        val out = ComponentOps.makeComponent(listOf(layer("a"), layer("b")), "a", "C1")
        assertEquals("C1", out.first { it.id == "a" }.componentId)
        assertNull(out.first { it.id == "b" }.componentId)
    }

    @Test
    fun `an instance cannot be promoted to a component`() {
        // Allowing this is the one easy way to build a cycle: an instance that is also the main its
        // own chain resolves to. Refusing here is cheaper than detecting the cycle later.
        val layers = listOf(layer("a").copy(instanceOf = "C1"))
        assertEquals(layers, ComponentOps.makeComponent(layers, "a", "C2"))
    }

    @Test
    fun `promoting an existing component twice is a no-op`() {
        val layers = listOf(layer("a").copy(componentId = "C1"))
        assertEquals(layers, ComponentOps.makeComponent(layers, "a", "C2"))
    }

    @Test
    fun `makeComponent on a missing layer is a no-op`() {
        val layers = listOf(layer("a"))
        assertEquals(layers, ComponentOps.makeComponent(layers, "nope", "C1"))
    }

    @Test
    fun `buildInstance copies content and starts on top of the main`() {
        val built = ComponentOps.buildInstance(stack(), "C1", newId = "i2")!!
        assertEquals("i2", built.id)
        assertEquals("C1", built.instanceOf)
        // Content came across...
        assertEquals(1, built.shapes.size)
        assertEquals(0.5f, built.brightness, 0f)
        // ...along with the main's placement, so it lands exactly on it, ready to be dragged off.
        assertEquals(Offset(10f, 10f), built.offset)
        // A copy must never become a second main, or mainFor would be ambiguous.
        assertNull(built.componentId)
    }

    @Test
    fun `buildInstance of an unknown component returns null`() {
        assertNull(ComponentOps.buildInstance(stack(), "nope"))
    }

    // ── Propagation ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `editing the main propagates content to its instances`() {
        val edited = stack().map {
            if (it.id == "main") {
                it.copy(shapes = listOf(VectorShape(kind = ShapeKind.ELLIPSE, width = 999f)), brightness = -0.2f)
            } else it
        }
        val inst = ComponentOps.syncInstances(edited).first { it.id == "inst" }
        assertEquals(ShapeKind.ELLIPSE, inst.shapes.single().kind)
        assertEquals(999f, inst.shapes.single().width, 0f)
        assertEquals(-0.2f, inst.brightness, 0f)
    }

    @Test
    fun `propagation never overwrites what the instance owns`() {
        // The whole reason to place a second copy is that it sits somewhere else, at another size,
        // possibly hidden. A sync that clobbered any of this would make instances useless.
        val inst = ComponentOps.syncInstances(stack()).first { it.id == "inst" }
        assertEquals(Offset(500f, 300f), inst.offset)
        assertEquals(2f, inst.scale, 0f)
        assertEquals(45f, inst.rotationZ, 0f)
        assertEquals(0.4f, inst.opacity, 0f)
        assertEquals(false, inst.isVisible)
        assertEquals("moved copy", inst.name)
        assertEquals("inst", inst.id)
    }

    @Test
    fun `syncing is idempotent`() {
        val once = ComponentOps.syncInstances(stack())
        assertEquals(once, ComponentOps.syncInstances(once))
    }

    @Test
    fun `syncing leaves layers that are neither main nor instance untouched`() {
        val out = ComponentOps.syncInstances(stack())
        assertEquals(stack().first { it.id == "unrelated" }, out.first { it.id == "unrelated" })
    }

    @Test
    fun `a stack with no components is returned unchanged`() {
        val plain = listOf(layer("a"), layer("b"))
        assertTrue(plain === ComponentOps.syncInstances(plain))
    }

    @Test
    fun `an instance pointing at a missing component is left as-is rather than blanked`() {
        // Defensive: a project edited by hand, or mid-migration, must not have its content wiped.
        val orphan = listOf(layer("x").copy(instanceOf = "GONE", shapes = listOf(VectorShape(kind = ShapeKind.LINE))))
        assertEquals(orphan, ComponentOps.syncInstances(orphan))
    }

    @Test
    fun `every instance of a component updates, not just the first`() {
        val layers = stack() + layer("inst2").copy(instanceOf = "C1", offset = Offset(1f, 1f))
        val edited = layers.map { if (it.id == "main") it.copy(brightness = 0.9f) else it }
        val synced = ComponentOps.syncInstances(edited)
        assertEquals(0.9f, synced.first { it.id == "inst" }.brightness, 0f)
        assertEquals(0.9f, synced.first { it.id == "inst2" }.brightness, 0f)
        // ...and each keeps its own placement.
        assertEquals(Offset(1f, 1f), synced.first { it.id == "inst2" }.offset)
    }

    // ── Detaching and releasing ──────────────────────────────────────────────────────────────

    @Test
    fun `detaching keeps the content the instance was showing`() {
        val synced = ComponentOps.syncInstances(stack())
        val detached = ComponentOps.detachInstance(synced, "inst").first { it.id == "inst" }
        assertNull(detached.instanceOf)
        assertEquals(1, detached.shapes.size)
        assertEquals(0.5f, detached.brightness, 0f)
        assertEquals(Offset(500f, 300f), detached.offset)
    }

    @Test
    fun `a detached instance stops following the main`() {
        val detached = ComponentOps.detachInstance(ComponentOps.syncInstances(stack()), "inst")
        val edited = detached.map { if (it.id == "main") it.copy(brightness = -1f) else it }
        assertEquals(0.5f, ComponentOps.syncInstances(edited).first { it.id == "inst" }.brightness, 0f)
    }

    @Test
    fun `detaching something that is not an instance is a no-op`() {
        val layers = stack()
        assertEquals(layers, ComponentOps.detachInstance(layers, "unrelated"))
    }

    @Test
    fun `releasing a component detaches its instances rather than leaving them dangling`() {
        // A dangling instance would silently stop updating with nothing on screen to explain why.
        val out = ComponentOps.releaseComponent(ComponentOps.syncInstances(stack()), "C1")
        assertNull(out.first { it.id == "main" }.componentId)
        assertNull(out.first { it.id == "inst" }.instanceOf)
        // Content is retained on the way out.
        assertEquals(1, out.first { it.id == "inst" }.shapes.size)
    }

    // ── Lookups ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the library and instance lists are derived from the stack`() {
        val layers = stack()
        assertEquals(listOf("main"), ComponentOps.components(layers).map { it.id })
        assertEquals(listOf("inst"), ComponentOps.instancesOf(layers, "C1").map { it.id })
        assertNotNull(ComponentOps.mainFor(layers, "C1"))
        assertNull(ComponentOps.mainFor(layers, "nope"))
    }
}
