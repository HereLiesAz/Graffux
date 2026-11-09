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
        var appWritesFail = false
        var backupFails = false
        val projectBackup = HashMap<String, String>()
        val repaired = ArrayList<Int>()
        var appBackup: String? = null
    }

    private fun library(disk: Disk) = RecipeLibrary(object : RecipeLibrary.Store {
        override suspend fun readProject(projectId: String) = disk.project[projectId]
        override suspend fun writeProject(projectId: String, text: String) {
            check(!disk.failWrites) { "disk full" }
            disk.project[projectId] = text
        }
        override suspend fun updateApp(transform: (String?) -> String?): Boolean = disk.appLock.withLock {
            val next = transform(disk.app) // one atomic edit, like the DataStore
            if (next == null || disk.appWritesFail) {
                false
            } else {
                disk.app = next
                true
            }
        }
        override suspend fun keepProjectBackup(projectId: String, original: String): Boolean {
            if (disk.backupFails) return false
            disk.projectBackup[projectId] = original
            return true
        }
        override suspend fun keepAppBackup(original: String): Boolean {
            if (disk.backupFails) return false
            disk.appBackup = original
            return true
        }
        override fun onUnreadable(scope: RecipeLibrary.Scope) { disk.unreadable += scope }
        override fun onRepaired(scope: RecipeLibrary.Scope, dropped: Int, backedUp: Boolean) {
            disk.repaired += dropped
        }
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
        val lib = library(disk).also { it.onAppStored(disk.app) }
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

    @Test
    fun aFailedAppWriteIsNotReportedAsSavedAndIsNotPublished() = runTest {
        val disk = Disk()
        disk.appWritesFail = true
        val lib = library(disk)
        assertFalse("the save failed, so it must say so", lib.save(RecipeLibrary.Scope.APP, recipe("r1")))
        assertTrue("and the list must not show a mix that was never saved", lib.app.value.isEmpty())
    }

    @Test
    fun aProjectBookWithSomeBadRecipesIsBackedUpBeforeItIsOverwrittenAndTheUserIsTold() = runTest {
        val disk = Disk()
        val good = recipe("r1", "Keep")
        val bad = good.copy(id = "r2", load = 9f)
        val original = RecipeBook(recipes = listOf(good, bad)).toJson()
        disk.project["A"] = original
        val lib = library(disk)
        lib.openProject("A")
        assertEquals(listOf("Keep"), lib.project.value.map { it.name })
        assertEquals("the user is told, with the number dropped", listOf(1), disk.repaired)
        assertEquals("the original is kept before anything overwrites it", original, disk.projectBackup["A"])
        assertTrue(lib.save(RecipeLibrary.Scope.PROJECT, recipe("r3")))
        assertEquals(original, disk.projectBackup["A"])
    }

    @Test
    fun aFailedBackupBlocksTheOverwrite() = runTest {
        val disk = Disk()
        val good = recipe("r1", "Keep")
        val original = RecipeBook(recipes = listOf(good, good.copy(id = "bad", load = 9f))).toJson()
        disk.project["A"] = original
        disk.backupFails = true
        val lib = library(disk)
        lib.openProject("A")
        assertFalse(lib.save(RecipeLibrary.Scope.PROJECT, recipe("r3")))
        assertEquals(original, disk.project["A"])
    }

    @Test
    fun anAppBookWithSomeBadRecipesIsBackedUpOnceBeforeItIsRepairedOver() = runTest {
        val disk = Disk()
        val good = recipe("r1", "Keep")
        val original = RecipeBook(recipes = listOf(good, good.copy(id = "bad", load = 9f))).toJson()
        disk.app = original
        val lib = library(disk).also { it.onAppStored(original) }
        assertTrue(lib.save(RecipeLibrary.Scope.APP, recipe("r2")))
        assertEquals(original, disk.appBackup)
        assertEquals(listOf(1), disk.repaired)
        assertTrue(lib.save(RecipeLibrary.Scope.APP, recipe("r3")))
        assertEquals("told once, not on every save", listOf(1), disk.repaired)
    }
}
