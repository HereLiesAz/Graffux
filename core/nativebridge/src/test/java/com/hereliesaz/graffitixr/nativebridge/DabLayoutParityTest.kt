package com.hereliesaz.graffitixr.nativebridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the 15-float Kotlin -> C++ dab layout ([DabPacking]) against the reader in
 * StampDynamicsJNI.cpp. Adding a dab field on one side only used to make every GPU stroke fail
 * (`count % kStride != 0` -> JNI_FALSE); now it fails here.
 */
class DabLayoutParityTest {

    private val cpp: String by lazy {
        val candidates = listOf(
            "src/main/cpp/StampDynamicsJNI.cpp",
            "core/nativebridge/src/main/cpp/StampDynamicsJNI.cpp",
        )
        val file = candidates.map(::File).firstOrNull { it.isFile }
            ?: error("StampDynamicsJNI.cpp not found from ${File(".").absolutePath}")
        file.readText()
    }

    @Test
    fun everyCppReaderUsesTheKotlinStride() {
        val strides = Regex("""kStride\s*=\s*(\d+)""").findAll(cpp).map { it.groupValues[1].toInt() }.toList()
        assertTrue("expected a kStride in StampDynamicsJNI.cpp", strides.isNotEmpty())
        strides.forEach { assertEquals("C++ kStride drifted from DabPacking.STRIDE", DabPacking.STRIDE, it) }
    }

    @Test
    fun cppReadsEverySlotUpToTheStride() {
        // Each reader copies data[i + 0..STRIDE-1]; the highest slot read must be STRIDE - 1.
        val slots = Regex("""data\[i(?:\s*\+\s*(\d+))?]""").findAll(cpp)
            .map { it.groupValues[1].ifEmpty { "0" }.toInt() }.toSet()
        assertEquals((0 until DabPacking.STRIDE).toSet(), slots)
    }

    @Test
    fun kotlinPackersEmitWholeRecordsWithDistinctSlots() {
        val resolved = ResolvedBrushDab(
            x = 1f, y = 2f, radius = 3f, alpha = 4f, angleDeg = 5f, colorArgb = 0x80FF4020.toInt(),
            flow = 10f, hardness = 0.5f, contactDepth = 0.6f, reservoirLoad = 0.7f,
            depositionRate = 0.8f, substrateResponse = 0.9f,
        )
        val masked = MaskedBrushDab(
            x = 1f, y = 2f, radius = 3f, alpha = 4f, angleDeg = 5f, colorArgb = 0x80FF4020.toInt(),
            flow = 10f, tipRatio = 0.5f, contactDepth = 0.6f, reservoirLoad = 0.7f,
            depositionRate = 0.8f, substrateResponse = 0.9f,
        )
        val r = DabPacking.packResolved(listOf(resolved, resolved))
        val m = DabPacking.packMasked(listOf(masked))
        assertEquals(2 * DabPacking.STRIDE, r.size)
        assertEquals(DabPacking.STRIDE, m.size)
        // Slot 10 is the resolved hardness / the masked tipRatio; 11..14 the four load fields.
        assertEquals(0.5f, r[10], 0f)
        assertEquals(0.5f, m[10], 0f)
        assertEquals(listOf(0.6f, 0.7f, 0.8f, 0.9f), r.slice(11..14))
        assertEquals(r.slice(0 until DabPacking.STRIDE), r.slice(DabPacking.STRIDE until 2 * DabPacking.STRIDE))
        assertEquals(10f, r[9], 0f)
        assertEquals(0x80 / 255f, r[8], 1e-6f)
    }
}
