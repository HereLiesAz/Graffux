package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class InkUtensilTest {

    @Test
    fun `ids and names are unique and round trip`() {
        val ids = InkUtensil.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        InkUtensil.entries.forEach {
            assertEquals(it, InkUtensil.fromId(it.id))
            assertEquals(it, InkUtensil.fromDisplayName(it.displayName))
        }
        assertNull(InkUtensil.fromId(null))
        assertNull(InkUtensil.fromId("ink.quill"))
    }

    @Test
    fun `a co-op Ink stroke carries its utensil id`() {
        val op: Op = Op.StrokeComplete("L1", BrushStroke(points = listOf(1f, 2f), inkUtensilId = InkUtensil.MARKER.id))
        val decoded = Cbor.decodeFromByteArray<Op>(Cbor.encodeToByteArray(op)) as Op.StrokeComplete
        assertEquals(InkUtensil.MARKER, InkUtensil.fromId(decoded.stroke.inkUtensilId))
    }

    @Test
    fun `a round-brush stroke is byte-identical on the wire to before the field existed`() {
        val stroke = BrushStroke(points = listOf(1f, 2f), pressures = listOf(0.5f))
        val bytes = Cbor.encodeToByteArray(stroke)
        assertNull(Cbor.decodeFromByteArray<BrushStroke>(bytes).inkUtensilId)
        // Null defaults aren't encoded, so the field adds nothing to the payload.
        assertArrayEquals(bytes, Cbor.encodeToByteArray(stroke.copy(inkUtensilId = null)))
        assertEquals(false, bytes.decodeToString(throwOnInvalidSequence = false).contains("inkUtensilId"))
    }
}
