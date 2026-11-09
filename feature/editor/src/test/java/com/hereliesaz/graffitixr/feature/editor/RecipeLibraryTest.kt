package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.PaletteRecipe
import com.hereliesaz.graffitixr.common.azphalt.PaletteRecipes
import com.hereliesaz.graffitixr.common.azphalt.RecipeBook
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saved mixes: this project and all projects, atomic changes, and never writing over a book that could not be read. */
class RecipeLibraryTest {
    private class Disk {
        val project = HashMap<String, String>()
        var app: String? = null
        val appLock = Mutex()
        val unreadable = ArrayList<RecipeLibrary.Scope>()
        var failWrites = false
    }

    private fun library(disk: Disk) = RecipeLibrary(object : RecipeLibrary.Store {
        override suspend fun readProject(projectId: String) = disk.project[projectId]
        override suspend fun writeProject(projectId: String, text: String) {
            check(!disk.failWrites) { "disk full" }
            disk.project[projectId] = text
        }
        override suspend fun updateApp(transform: (String?) -> String?) {
            disk.appLock.withLock { transform(disk.app)?.let { disk.app = it } } // one atomic edit, like the DataStore
        }
        override fun onUnreadable(scope: RecipeLibrary.Scope) { disk.unreadable += scope }
    })

    private val tubes = newMixerBoard()
    private fun recipe(id: String, name: String = id): PaletteRecipe {
        val b = newMixerBoard().also { it.squeeze(2, 20f, 20f, 8f, 1f); repeat(3) { _ -> it.dip(20f, 20f) } }
        return requireNotNull(PaletteRecipes.fromBrush(b, id, name))
    }

    @Test
    fun aProjectMixIsSavedWithTheProjectAndComesBackWhenItReopens() = runTest {
        val disk = Disk()
        val lib = library(disk)
        lib.openProject("A")
        assertTrue(lib.save(RecipeLibrary.Scope.PROJECT, recipe("r1", "Sap")))
        val reopened = library(disk).also { it.openProject("A") }
        assertEquals(listOf("Sap"), reopened.project.value.map { it.name })
        // another project does not see it
        reopened.openProject("B")
        assertTrue(reopened.project.value.isEmpty())
    }

    @Test
    fun anAppMixIsSharedAcrossProjects() = runTest {
        val disk = Disk()
        val lib = library(disk)
        assertTrue(lib.save(RecipeLibrary.Scope.APP, recipe("r1")))
        val other = library(disk).also { it.onAppStored(disk.app) }
        assertEquals(listOf("r1"), other.app.value.map { it.id })
    }

    @Test
    fun renameAndDeleteChangeTheStoredBook() = runTest {
        val disk = Disk()
        val lib = library(disk)
        lib.openProject("A")
        lib.save(RecipeLibrary.Scope.PROJECT, recipe("r1", "One"))
        lib.save(RecipeLibrary.Scope.PROJECT, recipe("r2", "Two"))
        assertTrue(lib.rename(RecipeLibrary.Scope.PROJECT, "r1", "  Renamed  "))
        assertEquals(listOf("Renamed", "Two"), RecipeBook.fromJson(disk.project["A"]!!)!!.recipes.map { it.name })
        assertTrue(lib.delete(RecipeLibrary.Scope.PROJECT, "r2"))
        assertEquals(listOf("r1"), RecipeBook.fromJson(disk.project["A"]!!)!!.recipes.map { it.id })
    }

    @Test
    fun manyConcurrentAppSavesAreAllKept() = runTest {
        val disk = Disk()
        val lib = library(disk)
        (1..30).map { i -> async { lib.save(RecipeLibrary.Scope.APP, recipe("r$i")) } }.awaitAll()
        assertEquals(30, RecipeBook.fromJson(disk.app!!)!!.recipes.size)
    }

    @Test
    fun aProjectFileThatCannotBeReadIsNeverWrittenOverAndTheUserIsTold() = runTest {
        val disk = Disk()
        disk.project["A"] = "{ this is not a recipe book"
        val lib = library(disk)
        lib.openProject("A")
        assertEquals(listOf(RecipeLibrary.Scope.PROJECT), disk.unreadable)
        assertFalse(lib.save(RecipeLibrary.Scope.PROJECT, recipe("r1")))
        assertEquals("{ this is not a recipe book", disk.project["A"])
    }

    @Test
    fun anAppBookThatCannotBeReadIsLeftExactlyAsItIs() = runTest {
        val disk = Disk()
        disk.app = "garbage"
        val lib = library(disk)
        assertFalse(lib.save(RecipeLibrary.Scope.APP, recipe("r1")))
        assertEquals("garbage", disk.app)
        assertEquals(listOf(RecipeLibrary.Scope.APP), disk.unreadable)
    }

    @Test
    fun aFailedWriteIsReportedAndLeavesTheListAsItWas() = runTest {
        val disk = Disk()
        val lib = library(disk)
        lib.openProject("A")
        lib.save(RecipeLibrary.Scope.PROJECT, recipe("r1"))
        disk.failWrites = true
        assertFalse(lib.save(RecipeLibrary.Scope.PROJECT, recipe("r2")))
        assertEquals(listOf("r1"), lib.project.value.map { it.id })
    }

    @Test
    fun savingAnIdAgainReplacesItAndTheListIsBounded() = runTest {
        val disk = Disk()
        val lib = library(disk)
        lib.save(RecipeLibrary.Scope.APP, recipe("r1", "First"))
        lib.save(RecipeLibrary.Scope.APP, recipe("r1", "Second"))
        assertEquals(listOf("Second"), lib.app.value.map { it.name })
        (1..RecipeBook.MAX_RECIPES + 5).forEach { lib.save(RecipeLibrary.Scope.APP, recipe("x$it")) }
        assertEquals(RecipeBook.MAX_RECIPES, lib.app.value.size)
    }

    @Test
    fun theSwatchMigrationAddsOnlyWhatIsMissingAndNeverRemovesAnything() = runTest {
        val disk = Disk()
        val lib = library(disk)
        lib.save(RecipeLibrary.Scope.APP, recipe("mine", "Mine"))
        val swatches = listOf(0xFFCC2222.toInt(), 0xFF3366CC.toInt())
        val migrated = PaletteRecipes.migrateSwatches(swatches, MIXER_TUBES, tubes)
        assertTrue(lib.addMissing(RecipeLibrary.Scope.APP, migrated))
        assertTrue(lib.addMissing(RecipeLibrary.Scope.APP, migrated)) // again: nothing duplicated
        assertEquals(1 + migrated.size, lib.app.value.size)
        assertTrue("mine" in lib.app.value.map { it.id })
    }
}
