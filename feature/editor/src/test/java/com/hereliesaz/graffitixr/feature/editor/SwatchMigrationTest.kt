package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The swatch migration is only marked done after the swatches were really read and really added. */
class SwatchMigrationTest {
    private var appText: String? = null
    private var appWriteWorks = true

    private val library = RecipeLibrary(object : RecipeLibrary.Store {
        override suspend fun readProject(projectId: String) = ArtifactRead.Absent
        override suspend fun writeProject(projectId: String, text: String) = Unit
        override suspend fun readApp(): String? = appText
        override suspend fun updateApp(transform: (String?) -> String?): Boolean {
            val next = transform(appText)
            if (next == null || !appWriteWorks) return false
            appText = next
            return true
        }
    })

    private fun settings(swatches: List<Int>?, migrated: Boolean = false): SettingsRepository =
        mockk<SettingsRepository>(relaxed = true).also {
            every { it.swatchesMigrated } returns flowOf(migrated)
            coEvery { it.readSavedPalette() } returns swatches
        }

    private val colours = listOf(0xFFCC2222.toInt(), 0xFF3366CC.toInt())

    @Test
    fun aFailedSwatchReadNeverMarksTheMigrationDone() = runTest {
        val s = settings(swatches = null) // the store could not be read: not "no swatches"
        assertFalse(SwatchMigration.run(s, library, newMixerBoard()))
        coVerify(exactly = 0) { s.setSwatchesMigrated() }
        assertEquals("nothing imported", null, appText)
    }

    @Test
    fun aFailedAppBookWriteNeverMarksTheMigrationDone() = runTest {
        appWriteWorks = false
        val s = settings(colours)
        assertFalse(SwatchMigration.run(s, library, newMixerBoard()))
        coVerify(exactly = 0) { s.setSwatchesMigrated() }
    }

    @Test
    fun aReadThatWorksImportsTheSwatchesAndMarksItDone() = runTest {
        val s = settings(colours)
        assertTrue(SwatchMigration.run(s, library, newMixerBoard()))
        coVerify(exactly = 1) { s.setSwatchesMigrated() }
        assertTrue(appText!!.contains("recipes"))
    }

    @Test
    fun anAlreadyMigratedInstallIsLeftAlone() = runTest {
        val s = settings(colours, migrated = true)
        assertTrue(SwatchMigration.run(s, library, newMixerBoard()))
        coVerify(exactly = 0) { s.readSavedPalette() }
    }
}
