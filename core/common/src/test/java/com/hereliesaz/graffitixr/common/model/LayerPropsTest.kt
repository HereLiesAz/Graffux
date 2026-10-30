package com.hereliesaz.graffitixr.common.model

import com.hereliesaz.graffitixr.common.photo.PhotoAdjustment
import com.hereliesaz.graffitixr.common.photo.PhotoTool
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** LayerProps carries every non-pixel layer field a co-op peer needs, and stays readable from older peers. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class LayerPropsTest {

    private val rich = Layer(
        id = "L", name = "Hero",
        adjustment = PhotoAdjustment(PhotoTool.LIGHT, values = mapOf("exposure" to 0.4f)),
        constraints = Constraints(ConstraintAnchor.END, ConstraintAnchor.STRETCH),
        autoLayout = AutoLayout(direction = LayoutDirection.VERTICAL),
        componentId = "comp", instanceOf = "main",
        warpMesh = listOf(1f, 2f),
        frameFillArgb = 0xFF112233L, clipsContent = true, frameRadius = 8f,
        opacity = 0.5f,
    )

    @Test
    fun `props carry adjustments, constraints, auto-layout and components to a peer`() {
        val peer = Layer(id = "L", name = "old").withLayerProps(rich.toLayerProps())
        assertEquals(rich.adjustment, peer.adjustment)
        assertEquals(rich.constraints, peer.constraints)
        assertEquals(rich.autoLayout, peer.autoLayout)
        assertEquals("comp", peer.componentId)
        assertEquals("main", peer.instanceOf)
        assertEquals(rich.warpMesh, peer.warpMesh)
        assertEquals(0xFF112233L, peer.frameFillArgb)
        assertEquals("Hero", peer.name)
        assertEquals(0.5f, peer.opacity)
    }

    @Test
    fun `removing an adjustment travels too`() {
        val cleared = rich.withLayerProps(rich.copy(adjustment = null).toLayerProps())
        assertNull(cleared.adjustment)
    }

    @Test
    fun `props from an older peer change only what they carry`() {
        val old = LayerProps(opacity = 0.25f)
        val applied = rich.withLayerProps(old)
        assertEquals(0.25f, applied.opacity)
        assertEquals(rich.adjustment, applied.adjustment)
        assertEquals(rich.constraints, applied.constraints)
    }

    @Test
    fun `the extended props round-trip on the wire`() {
        val op: Op = Op.LayerPropsChange("L", rich.toLayerProps())
        assertEquals(op, Cbor.decodeFromByteArray<Op>(Cbor.encodeToByteArray(op)))
    }

    @Test
    fun `text formatting reaches a peer, and props without it leave the peer's text alone`() {
        val styled = TextLayerParams(
            text = "hi", fontName = "Lobster", fontSizeDp = 90f,
            colorArgb = 0xFF00FF00.toInt(), letterSpacingEm = 0.2f, isBold = true,
        )
        val sender = Layer(id = "T", name = "T", textParams = styled)
        val peer = Layer(id = "T", name = "T", textParams = TextLayerParams(text = "hi"))
        assertEquals(styled, peer.withLayerProps(sender.toLayerProps()).textParams)
        val olderPeer = sender.toLayerProps().let { it.copy(extended = it.extended?.copy(textParams = null)) }
        assertEquals(styled, sender.withLayerProps(olderPeer).textParams)
    }

    @Test
    fun `a mask's pixels round-trip on the wire`() {
        val op: Op = Op.AdjustmentMaskData("L", 3, 2, 2, byteArrayOf(1, 2, 3))
        assertEquals(op, Cbor.decodeFromByteArray<Op>(Cbor.encodeToByteArray(op)))
    }
}
