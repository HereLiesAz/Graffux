package com.hereliesaz.graffitixr.common.azphalt.wgpu

import com.hereliesaz.graffitixr.common.azphalt.BrushColorSource
import com.hereliesaz.graffitixr.common.azphalt.Dab
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Kotlin half of the Kotlin <-> WGSL dab layout parity check. [DAB_LAYOUT] / [SMUDGE_LAYOUT] are
 * transcribed from the `Dab` struct comments in stamp.wgsl / stamp_masked.wgsl and the Rust
 * `ColorSmudgeDab`; core/wgpu-engine/tests/layout_parity.rs asserts the same tables against the
 * shader source and the Rust structs, so a change on either side fails one of the two tests.
 */
class WgpuDabsLayoutTest {
    @Test
    fun dabSlotConstantsMatchShaderTable() {
        val constants = listOf(
            WgpuDabs.DAB_SLOT_X, WgpuDabs.DAB_SLOT_Y, WgpuDabs.DAB_SLOT_RADIUS, WgpuDabs.DAB_SLOT_ALPHA,
            WgpuDabs.DAB_SLOT_ANGLE, WgpuDabs.DAB_SLOT_COLOR_R, WgpuDabs.DAB_SLOT_COLOR_G,
            WgpuDabs.DAB_SLOT_COLOR_B, WgpuDabs.DAB_SLOT_COLOR_A, WgpuDabs.DAB_SLOT_FLOW,
            WgpuDabs.DAB_SLOT_RESOLVED, WgpuDabs.DAB_SLOT_HARDNESS_OR_TIP_RATIO,
            WgpuDabs.DAB_SLOT_CONTACT_DEPTH, WgpuDabs.DAB_SLOT_RESERVOIR_LOAD,
            WgpuDabs.DAB_SLOT_DEPOSITION_RATE, WgpuDabs.DAB_SLOT_SUBSTRATE_RESPONSE,
        )
        assertEquals(DAB_LAYOUT.indices.toList(), constants)
        assertEquals(DAB_FLOATS, DAB_LAYOUT.size)
    }

    @Test
    fun resolvedRoundPacksEachFieldIntoItsRoundShaderSlot() {
        val dab = Dab(
            x = 11f, y = 22f, radius = 33f, alpha = 0.5f, angleDeg = 44f,
            tipRatio = 0.25f, hardness = 0.75f, contactDepth = 0.6f,
        )
        val packed = WgpuDabs.resolvedRound(listOf(dab), OPAQUE_COLOR, 0, BrushColorSource.PLAIN, 1f)
        assertEquals(DAB_FLOATS, packed.size)
        assertEquals(11f, packed[DAB_LAYOUT.indexOf("x")])
        assertEquals(22f, packed[DAB_LAYOUT.indexOf("y")])
        assertEquals(33f, packed[DAB_LAYOUT.indexOf("radius")])
        assertEquals(0.5f, packed[DAB_LAYOUT.indexOf("alpha")])
        assertEquals(44f, packed[DAB_LAYOUT.indexOf("angleDeg")])
        assertEquals(0x33 / 255f, packed[DAB_LAYOUT.indexOf("colorR")])
        assertEquals(0x66 / 255f, packed[DAB_LAYOUT.indexOf("colorG")])
        assertEquals(0x99 / 255f, packed[DAB_LAYOUT.indexOf("colorB")])
        assertEquals(1f, packed[DAB_LAYOUT.indexOf("resolved")])
        // resolvedRound feeds the round shader, which reads slot 11 as hardness (not tipRatio).
        assertEquals(0.75f, packed[DAB_LAYOUT.indexOf("hardness|tipRatio")])
        assertEquals(0.6f, packed[DAB_LAYOUT.indexOf("contactDepth")])
    }

    @Test
    fun colorSmudgePacksRustFieldOrder() {
        val v = FloatArray(SMUDGE_LAYOUT.size) { it + 1f }
        val d = WgpuDabs.SmudgeDab(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10])
        val packed = WgpuDabs.colorSmudge(listOf(d, d))
        assertEquals(SMUDGE_LAYOUT.size, WgpuDabs.SMUDGE_FLOATS)
        assertEquals(2 * WgpuDabs.SMUDGE_FLOATS, packed.size)
        for (i in SMUDGE_LAYOUT.indices) {
            assertEquals(v[i], packed[i], SMUDGE_LAYOUT[i])
            assertEquals(v[i], packed[WgpuDabs.SMUDGE_FLOATS + i], SMUDGE_LAYOUT[i])
        }
    }

    private companion object {
        const val DAB_FLOATS = 16
        const val OPAQUE_COLOR = 0xFF336699.toInt()

        /** Keep in sync with core/wgpu-engine/tests/layout_parity.rs `DAB_LAYOUT`. */
        val DAB_LAYOUT = listOf(
            "x", "y", "radius", "alpha",
            "angleDeg", "colorR", "colorG", "colorB",
            "colorA", "flow", "resolved", "hardness|tipRatio",
            "contactDepth", "reservoirLoad", "depositionRate", "substrateResponse",
        )

        /** Keep in sync with core/wgpu-engine/tests/layout_parity.rs `SMUDGE_LAYOUT`. */
        val SMUDGE_LAYOUT = listOf(
            "x", "y", "smudgeRate", "colorRate", "opacity", "smudgeRadius", "colorRateMultiplier",
            "distanceDeltaPx", "baseColorRate", "chargeDecayRate", "pickupRate",
        )
    }
}
