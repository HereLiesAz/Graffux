package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.CarouselItemSettings
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Carousel keys are migrated before anything reads or writes them: (not started, in flight, done) x (load, write). */
class CarouselMigrationTest {
    private val favourites = MutableStateFlow(listOf("builtin.Heavy Oil", "custom.x"))
    private val items = MutableStateFlow(mapOf("builtin.Heavy Oil" to CarouselItemSettings()))
    private var renameCalls = 0

    /** A repository whose rename takes 1 s to land: the race window. */
    private val settings = mockk<SettingsRepository>(relaxed = true).also { s ->
        every { s.carouselFavorites } returns favourites
        every { s.carouselItemSettings } returns items
        coEvery { s.renameCarouselKeys(any()) } coAnswers {
            renameCalls++
            val renames = firstArg<Map<String, String>>()
            delay(1_000)
            favourites.value = favourites.value.map { renames[it] ?: it }.distinct()
            items.value = items.value.mapKeys { renames[it.key] ?: it.key }
        }
        coEvery { s.setCarouselItemSettings(any()) } coAnswers { items.value = firstArg() }
    }

    private val migration = CarouselMigration(settings) { setOf("Round", "Flat") }

    @Test
    fun aLoadDuringTheMigrationSeesTheNewKeys() = runTest {
        val first = launch { migration.ensure() } // migration in flight (the rename is suspended)
        advanceTimeBy(100)
        var loaded: Map<String, CarouselItemSettings>? = null
        val load = launch {
            migration.ensure() // the loader, mid-migration
            loaded = settings.carouselItemSettings.first()
        }
        advanceTimeBy(100)
        assertTrue("the load waits for the rename instead of reading old keys", loaded == null)
        first.join(); load.join()
        assertEquals(setOf("builtin.Round"), loaded!!.keys)
    }

    @Test
    fun aWriteAfterTheLoadDoesNotBringTheOldKeysBack() = runTest {
        migration.ensure()
        val inMemory = settings.carouselItemSettings.first() // loaded after the migration
        migration.ensure()
        settings.setCarouselItemSettings(inMemory + ("custom.y" to CarouselItemSettings()))
        assertFalse("builtin.Heavy Oil" in items.value)
        assertTrue("builtin.Round" in items.value && "custom.y" in items.value)
    }

    @Test
    fun favouritesAreMigratedBeforeTheyAreReadAndNeverTwice() = runTest {
        migration.ensure()
        migration.ensure()
        migration.ensure()
        assertEquals(1, renameCalls)
        assertEquals(listOf("builtin.Round", "custom.x"), favourites.first())
    }

    @Test
    fun withNothingToMigrateNothingIsRenamed() = runTest {
        favourites.value = listOf("builtin.Round")
        items.value = emptyMap()
        migration.ensure()
        assertEquals(0, renameCalls)
    }

    @Test
    fun aFailedMigrationIsRetriedNotLooped() = runTest {
        var fail = true
        coEvery { settings.renameCarouselKeys(any()) } coAnswers {
            if (fail) error("disk")
            renameCalls++
        }
        migration.ensure() // fails once, returns
        assertEquals(0, renameCalls)
        fail = false
        migration.ensure()
        assertEquals(1, renameCalls)
    }
}
