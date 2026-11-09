package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.PaletteRecipe
import com.hereliesaz.graffitixr.common.azphalt.PaletteRecipes
import com.hereliesaz.graffitixr.common.azphalt.RecipeBook
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saved mixes. A change names its target when the user acts and is applied to what is STORED for it, so project
 * switches and startup ordering cannot misroute or skip preservation of anything.
 */
class RecipeLibraryTest {
    private class Disk {
        val project = HashMap<String, String>()
        var app: String? = null
        val appLock = Mutex()
        val unreadable = ArrayList<RecipeLibrary.Scope>()
        var failWrites = false
        var appWritesFail = false
        var backupFails = false
        val unreadableProjects = HashSet<String>()
        val projectBackup = HashMap<String, String>()
        val repaired = ArrayList<Int>()
        var appBackup: String? = null

        /** Simulates another writer changing the stored app text between our read and our edit. */
        var appChangesOnceBeforeEdit: String? = null
        val readOnly = HashSet<String>()
    }

    private fun library(disk: Disk) = RecipeLibrary(object : RecipeLibrary.Store {
        override suspend fun readProject(projectId: String): ArtifactRead {
            if (projectId in disk.unreadableProjects) return ArtifactRead.Unreadable("I/O error")
            return disk.project[projectId]?.let { ArtifactRead.Found(it.toByteArray()) } ?: ArtifactRead.Absent
        }
        override fun isReadOnly(projectId: String) = projectId in disk.readOnly
        override suspend fun writeProject(projectId: String, text: String) {
            check(!disk.failWrites) { "disk full" }
            disk.project[projectId] = text
        }
        override suspend fun readApp(): String? = disk.app
        override suspend fun updateApp(transform: (String?) -> String?): Boolean = disk.appLock.withLock {
            disk.appChangesOnceBeforeEdit?.let { disk.app = it; disk.appChangesOnceBeforeEdit = null }
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

    private val projA = RecipeLibrary.Target.Project("A")
    private val projB = RecipeLibrary.Target.Project("B")
    private val app = RecipeLibrary.Target.App
    private val tubes = newMixerBoard()

    private fun recipe(id: String, name: String = id): PaletteRecipe {
        val b = newMixerBoard().also { it.squeeze(2, 20f, 20f, 8f, 1f); repeat(3) { _ -> it.dip(20f, 20f) } }
        return requireNotNull(PaletteRecipes.fromBrush(b, id, name))
    }

    private fun bookOf(disk: Disk, id: String) = RecipeBook.fromJson(disk.project[id]!!)!!.recipes

    @Test
    fun aProjectMixIsSavedWithTheProjectAndComesBackWhenItReopens() = runTest {
        val disk = Disk()
        val lib = library(disk)
        assertTrue(lib.save(projA, recipe("r1", "Sap")))
        val reopened = library(disk).also { it.show("A"); it.openProject("A") }
        assertEquals(listOf("Sap"), reopened.project.value.recipes.map { it.name })
        reopened.show("B"); reopened.openProject("B")
        assertTrue(reopened.project.value.recipes.isEmpty())
    }

    @Test
    fun aMixSavedForProjectAIsNeverMisroutedToBWhenTheUserSwitchesFirst() = runTest {
        val disk = Disk()
        val lib = library(disk)
        lib.show("A"); lib.openProject("A")
        // the user taps Save in A, then switches to B before the save runs: B opens first
        lib.show("B"); lib.openProject("B")
        assertTrue(lib.save(projA, recipe("r1", "For A"))) // the target was fixed when the user acted
        assertEquals(listOf("For A"), bookOf(disk, "A").map { it.name })
        assertFalse("nothing landed in B", disk.project.containsKey("B"))
        assertEquals("B", lib.project.value.id)
        assertTrue("B is the one shown, and it is empty", lib.project.value.recipes.isEmpty())
    }

    @Test
    fun renameAndDeleteInAWhileBIsShownChangeAsBook() = runTest {
        val disk = Disk()
        val lib = library(disk)
        lib.save(projA, recipe("r1", "One"))
        lib.save(projA, recipe("r2", "Two"))
        lib.show("B"); lib.openProject("B")
        assertTrue(lib.rename(projA, "r1", "  Renamed  "))
        assertEquals(listOf("Renamed", "Two"), bookOf(disk, "A").map { it.name })
        assertTrue(lib.delete(projA, "r2"))
        assertEquals(listOf("r1"), bookOf(disk, "A").map { it.id })
        assertFalse("B untouched", disk.project.containsKey("B"))
    }

    @Test
    fun theOrderOfTwoQuickProjectLoadsDoesNotChangeWhichIsShown() = runTest {
        val disk = Disk()
        val lib = library(disk)
        lib.save(projA, recipe("a1", "In A"))
        lib.save(projB, recipe("b1", "In B"))
        lib.show("A")
        lib.show("B") // B is the latest asked for
        lib.openProject("B")
        lib.openProject("A") // A's load runs last
        assertEquals("B", lib.project.value.id)
        assertEquals(listOf("In B"), lib.project.value.recipes.map { it.name })
    }

    @Test
    fun anAppMixIsSharedAcrossProjects() = runTest {
        val disk = Disk()
        val lib = library(disk)
        assertTrue(lib.save(app, recipe("r1")))
        val other = library(disk).also { it.onAppStored(disk.app) }
        assertEquals(listOf("r1"), other.app.value.map { it.id })
    }

    @Test
    fun manyConcurrentAppSavesAreAllKept() = runTest {
        val disk = Disk()
        val lib = library(disk)
        (1..30).map { i -> async { lib.save(app, recipe("r$i")) } }.awaitAll()
        assertEquals(30, RecipeBook.fromJson(disk.app!!)!!.recipes.size)
    }

    @Test
    fun anAppChangeBeforeTheInitialLoadStillPreservesADamagedStoredBook() = runTest {
        val disk = Disk()
        val good = recipe("r1", "Keep")
        val original = RecipeBook(recipes = listOf(good, good.copy(id = "bad", load = 9f))).toJson()
        disk.app = original
        val lib = library(disk) // onAppStored has NOT been called: the swatch migration got here first
        assertTrue(lib.save(app, recipe("r2")))
        assertEquals("the original was kept before the repair overwrote it", original, disk.appBackup)
        assertEquals(listOf(1), disk.repaired)
        assertEquals(listOf("r1", "r2"), RecipeBook.fromJson(disk.app!!)!!.recipes.map { it.id })
    }

    @Test
    fun anAppBookThatCannotBeReadIsLeftExactlyAsItIsEvenBeforeTheInitialLoad() = runTest {
        val disk = Disk()
        disk.app = "garbage"
        val lib = library(disk)
        assertFalse(lib.save(app, recipe("r1")))
        assertEquals("garbage", disk.app)
        assertEquals(listOf(RecipeLibrary.Scope.APP), disk.unreadable)
    }

    @Test
    fun aStoredTextThatChangesBetweenInspectionAndEditIsInspectedAgain() = runTest {
        val disk = Disk()
        val good = recipe("r1", "Keep")
        disk.app = RecipeBook(recipes = listOf(good)).toJson() // healthy when first read
        // another writer replaces it with a damaged book just before our edit
        val damaged = RecipeBook(recipes = listOf(good, good.copy(id = "bad", load = 9f))).toJson()
        disk.appChangesOnceBeforeEdit = damaged
        val lib = library(disk)
        assertTrue(lib.save(app, recipe("r2")))
        assertEquals("the damaged text that was really there was preserved", damaged, disk.appBackup)
    }

    @Test
    fun aProjectFileThatCannotBeReadIsNeverWrittenOverAndTheUserIsTold() = runTest {
        val disk = Disk()
        disk.project["A"] = "{ this is not a recipe book"
        val lib = library(disk)
        lib.show("A"); lib.openProject("A")
        assertEquals(listOf(RecipeLibrary.Scope.PROJECT), disk.unreadable)
        assertFalse(lib.save(projA, recipe("r1")))
        assertEquals("{ this is not a recipe book", disk.project["A"])
    }

    @Test
    fun aMutationBeforeTheProjectIsEverShownStillChecksTheStoredBook() = runTest {
        val disk = Disk()
        disk.project["A"] = "{ this is not a recipe book"
        val lib = library(disk) // no openProject yet
        assertFalse(lib.save(projA, recipe("r1")))
        assertEquals("{ this is not a recipe book", disk.project["A"])
    }

    @Test
    fun aFailedWriteIsReportedAndLeavesTheListAsItWas() = runTest {
        val disk = Disk()
        val lib = library(disk)
        lib.show("A"); lib.openProject("A")
        lib.save(projA, recipe("r1"))
        disk.failWrites = true
        assertFalse(lib.save(projA, recipe("r2")))
        assertEquals(listOf("r1"), lib.project.value.recipes.map { it.id })
    }

    @Test
    fun aFailedAppWriteIsNotReportedAsSavedAndIsNotPublished() = runTest {
        val disk = Disk()
        disk.appWritesFail = true
        val lib = library(disk)
        assertFalse(lib.save(app, recipe("r1")))
        assertTrue(lib.app.value.isEmpty())
    }

    @Test
    fun savingAnIdAgainReplacesItAndTheListIsBounded() = runTest {
        val disk = Disk()
        val lib = library(disk)
        lib.save(app, recipe("r1", "First"))
        lib.save(app, recipe("r1", "Second"))
        assertEquals(listOf("Second"), RecipeBook.fromJson(disk.app!!)!!.recipes.map { it.name })
        (1..RecipeBook.MAX_RECIPES + 5).forEach { lib.save(app, recipe("x$it")) }
        assertEquals(RecipeBook.MAX_RECIPES, RecipeBook.fromJson(disk.app!!)!!.recipes.size)
    }

    @Test
    fun theSwatchMigrationAddsOnlyWhatIsMissingAndNeverRemovesAnything() = runTest {
        val disk = Disk()
        val lib = library(disk)
        lib.save(app, recipe("mine", "Mine"))
        val swatches = listOf(0xFFCC2222.toInt(), 0xFF3366CC.toInt())
        val migrated = PaletteRecipes.migrateSwatches(swatches, MIXER_TUBES, tubes)
        assertTrue(lib.addMissing(app, migrated))
        assertTrue(lib.addMissing(app, migrated))
        assertEquals(1 + migrated.size, RecipeBook.fromJson(disk.app!!)!!.recipes.size)
        assertTrue("mine" in RecipeBook.fromJson(disk.app!!)!!.recipes.map { it.id })
    }

    @Test
    fun aProjectBookWithSomeBadRecipesIsBackedUpBeforeItIsOverwrittenAndTheUserIsTold() = runTest {
        val disk = Disk()
        val good = recipe("r1", "Keep")
        val original = RecipeBook(recipes = listOf(good, good.copy(id = "r2", load = 9f))).toJson()
        disk.project["A"] = original
        val lib = library(disk)
        lib.show("A"); lib.openProject("A")
        assertEquals(listOf("Keep"), lib.project.value.recipes.map { it.name })
        assertEquals(listOf(1), disk.repaired)
        assertEquals(original, disk.projectBackup["A"])
        assertTrue(lib.save(projA, recipe("r3")))
        assertEquals(original, disk.projectBackup["A"])
        assertEquals("told once", listOf(1), disk.repaired)
    }

    @Test
    fun aFailedBackupBlocksTheOverwrite() = runTest {
        val disk = Disk()
        val good = recipe("r1", "Keep")
        val original = RecipeBook(recipes = listOf(good, good.copy(id = "bad", load = 9f))).toJson()
        disk.project["A"] = original
        disk.backupFails = true
        val lib = library(disk)
        lib.show("A"); lib.openProject("A")
        assertFalse(lib.save(projA, recipe("r3")))
        assertEquals(original, disk.project["A"])
    }

    @Test
    fun anAppBookWithSomeBadRecipesIsBackedUpOnce() = runTest {
        val disk = Disk()
        val good = recipe("r1", "Keep")
        val original = RecipeBook(recipes = listOf(good, good.copy(id = "bad", load = 9f))).toJson()
        disk.app = original
        val lib = library(disk).also { it.onAppStored(original) }
        assertTrue(lib.save(app, recipe("r2")))
        assertEquals(original, disk.appBackup)
        assertTrue(lib.save(app, recipe("r3")))
        assertEquals("told once, not on every save", listOf(1), disk.repaired)
    }

    @Test
    fun aProjectBookThatExistsButCannotBeReadIsNeverWrittenOver() = runTest {
        val disk = Disk()
        disk.unreadableProjects += "A"
        disk.project["A"] = "the real book, unreadable right now"
        val lib = library(disk)
        lib.show("A"); lib.openProject("A")
        assertEquals(listOf(RecipeLibrary.Scope.PROJECT), disk.unreadable)
        assertFalse(lib.save(projA, recipe("r1")))
        assertEquals("the real book, unreadable right now", disk.project["A"])
    }

    @Test
    fun anAbsentProjectBookIsFineToWrite() = runTest {
        val disk = Disk()
        val lib = library(disk)
        lib.show("A"); lib.openProject("A")
        assertTrue(disk.unreadable.isEmpty())
        assertTrue(lib.save(projA, recipe("r1")))
    }

    @Test
    fun aReadOnlyProjectsBookIsNeverWrittenOrBackedUpAndItsMixesAreStillShown() = runTest {
        val disk = Disk()
        val good = recipe("r1", "Keep")
        val original = RecipeBook(recipes = listOf(good, good.copy(id = "bad", load = 9f))).toJson()
        disk.project["A"] = original
        disk.readOnly += "A"
        val lib = library(disk)
        lib.show("A"); lib.openProject("A")
        assertEquals(listOf("Keep"), lib.project.value.recipes.map { it.name })
        assertTrue("no backup claimed for a project that is never overwritten", disk.projectBackup.isEmpty())
        assertTrue(disk.repaired.isEmpty())
        assertFalse(lib.save(projA, recipe("r2")))
        assertFalse(lib.rename(projA, "r1", "X"))
        assertFalse(lib.delete(projA, "r1"))
        assertEquals(original, disk.project["A"])
    }

    @Test
    fun anImportThatWouldPassTheCapacityIsRefusedAndNothingOversizedIsStored() = runTest {
        val disk = Disk()
        val lib = library(disk)
        val full = (1..RecipeBook.MAX_RECIPES).map { recipe("x$it") }
        disk.app = RecipeBook(recipes = full).toJson()
        disk.project["A"] = RecipeBook(recipes = full).toJson()
        val before = disk.app
        assertFalse(lib.addMissing(app, listOf(recipe("new1"), recipe("new2"))))
        assertFalse(lib.addMissing(projA, listOf(recipe("new1"))))
        assertEquals("the stored books are untouched", before, disk.app)
        assertEquals(RecipeBook.MAX_RECIPES, bookOf(disk, "A").size)
        assertTrue("ids already there still fit", lib.addMissing(app, listOf(full.first())))
        assertEquals(RecipeBook.MAX_RECIPES, RecipeBook.fromJson(disk.app!!)!!.recipes.size)
    }
}
