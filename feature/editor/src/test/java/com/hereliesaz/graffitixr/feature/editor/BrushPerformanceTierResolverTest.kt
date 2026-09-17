package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.BrushPerformanceTier
import org.junit.Assert.assertEquals
import org.junit.Test

class BrushPerformanceTierResolverTest {
    @Test
    fun lowRamAlwaysUsesConstrainedTier() {
        assertEquals(
            BrushPerformanceTier.CONSTRAINED,
            BrushPerformanceTierResolver.resolve(isLowRamDevice = true, memoryClassMb = 1024),
        )
    }

    @Test
    fun memoryClassSelectsStableTierBoundaries() {
        assertEquals(
            BrushPerformanceTier.CONSTRAINED,
            BrushPerformanceTierResolver.resolve(isLowRamDevice = false, memoryClassMb = 256),
        )
        assertEquals(
            BrushPerformanceTier.BALANCED,
            BrushPerformanceTierResolver.resolve(isLowRamDevice = false, memoryClassMb = 257),
        )
        assertEquals(
            BrushPerformanceTier.BALANCED,
            BrushPerformanceTierResolver.resolve(isLowRamDevice = false, memoryClassMb = 512),
        )
        assertEquals(
            BrushPerformanceTier.FULL,
            BrushPerformanceTierResolver.resolve(isLowRamDevice = false, memoryClassMb = 513),
        )
    }
}
