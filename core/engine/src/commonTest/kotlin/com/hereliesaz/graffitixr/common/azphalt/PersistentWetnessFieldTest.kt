package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersistentWetnessFieldTest {
    @Test
    fun idleAdvanceDoesNoPixelWork() {
        val field = PersistentWetnessField(width = 128, height = 128, tileSize = 64)
        val stats = field.advance(deltaSeconds = 1f, dryingRate = 0.2f, transportRate = 0.8f)

        assertTrue(field.isIdle)
        assertEquals(0, stats.activeTilesProcessed)
        assertEquals(0, stats.pixelsProcessed)
        assertEquals(0, stats.remainingActiveTiles)
    }

    @Test
    fun depositActivatesOnlyTouchedTile() {
        val field = PersistentWetnessField(width = 192, height = 64, tileSize = 64)
        field.deposit(DirtyRegion(4, 4, 12, 12), amount = 0.5f)

        assertEquals(listOf(0 to 0), field.activeTileCoordinates())
        assertEquals(0.5f, field.wetnessAt(4, 4), 1e-6f)
        assertEquals(0f, field.wetnessAt(64, 4), 1e-6f)
    }

    @Test
    fun rapidBoundaryCrossingActivatesExactlyCrossedTiles() {
        val field = PersistentWetnessField(width = 192, height = 64, tileSize = 64)
        field.addWetness(63, 10, 1f)
        field.addWetness(64, 10, 1f)

        assertEquals(listOf(0 to 0, 1 to 0), field.activeTileCoordinates())
        val stats = field.advance(deltaSeconds = 0.25f, dryingRate = 0f, transportRate = 1f)
        assertEquals(2, stats.activeTilesProcessed)
        assertEquals(2, stats.remainingActiveTiles)
        assertFalse(2 to 0 in field.activeTileCoordinates())
        assertEquals(0f, field.wetnessAt(128, 10), 1e-6f)
    }

    @Test
    fun transportNeverLeaksIntoInactiveAdjacentTile() {
        val field = PersistentWetnessField(width = 128, height = 64, tileSize = 64)
        field.addWetness(63, 20, 1f)

        field.advance(deltaSeconds = 1f, dryingRate = 0f, transportRate = 1f)

        assertEquals(0f, field.wetnessAt(64, 20), 1e-6f)
        assertEquals(listOf(0 to 0), field.activeTileCoordinates())
    }

    @Test
    fun transportCrossesBoundaryWhenBothTilesWereExplicitlyActivated() {
        val field = PersistentWetnessField(width = 128, height = 64, tileSize = 64)
        field.addWetness(63, 20, 1f)
        field.activate(DirtyRegion(64, 20, 65, 21))

        val before = field.snapshot().sum()
        field.advance(deltaSeconds = 1f, dryingRate = 0f, transportRate = 1f)
        val after = field.snapshot().sum()

        assertTrue(field.wetnessAt(64, 20) > 0f)
        assertTrue(abs(before - after) < 1e-5f, "transport must conserve mass without drying")
    }

    @Test
    fun dryingEventuallyRemovesFullyDryTile() {
        val field = PersistentWetnessField(width = 64, height = 64, tileSize = 64)
        field.addWetness(10, 10, 0.25f)

        val stats = field.advance(
            deltaSeconds = 20f,
            dryingRate = 1f,
            transportRate = 0f,
            dryEpsilon = 1e-4f,
        )

        assertEquals(1, stats.activeTilesProcessed)
        assertEquals(0, stats.remainingActiveTiles)
        assertTrue(field.isIdle)
        assertEquals(0f, field.wetnessAt(10, 10), 1e-6f)
    }

    @Test
    fun explicitTimeMakesRepeatedRunsDeterministic() {
        fun run(): FloatArray {
            val field = PersistentWetnessField(width = 128, height = 64, tileSize = 64)
            field.addWetness(20, 20, 1f)
            field.addWetness(70, 20, 0.7f)
            field.activate(DirtyRegion(0, 0, 128, 64))
            repeat(6) {
                field.advance(deltaSeconds = 0.125f, dryingRate = 0.18f, transportRate = 0.75f)
            }
            return field.snapshot()
        }

        assertTrue(run().contentEquals(run()))
    }

    @Test
    fun dryingMatchesExponentialRateWithoutTransport() {
        val field = PersistentWetnessField(width = 16, height = 16, tileSize = 8)
        field.addWetness(4, 4, 1f)
        field.advance(deltaSeconds = 1f, dryingRate = 1f, transportRate = 0f, dryEpsilon = 0f)

        assertEquals(0.36787945f, field.wetnessAt(4, 4), 1e-5f)
    }

    @Test
    fun clearTouchesOnlyActiveStateAndReturnsIdle() {
        val field = PersistentWetnessField(width = 128, height = 128, tileSize = 64)
        field.addWetness(10, 10, 0.5f)
        field.addWetness(90, 90, 0.75f)
        assertEquals(2, field.activeTileCount)

        field.clearActiveWetness()

        assertTrue(field.isIdle)
        assertEquals(0f, field.snapshot().sum(), 1e-6f)
    }
}
