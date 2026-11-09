package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.PaletteRecipe
import com.hereliesaz.graffitixr.common.azphalt.RecipeBook

/**
 * Reads the stored project recipe books and remembers which must not be written. Used under [RecipeLibrary]'s lock.
 * A damaged book is backed up first (once per process) and the user told; one that is not a recipe book, could not be
 * read, or could not be preserved is blocked: never written over.
 */
internal class ProjectBooks(private val store: RecipeLibrary.Store) {
    private val preserved = HashSet<String>()
    private val blocked = HashSet<String>()

    /** The recipes stored for project [id] (null: they could not be read). */
    suspend fun read(id: String): List<PaletteRecipe>? = when (val read = store.readProject(id)) {
        ArtifactRead.Absent -> emptyList()
        is ArtifactRead.Unreadable -> block(id) // present but unreadable: there are no bytes to keep
        is ArtifactRead.Found -> inspect(id, String(read.bytes))
    }

    /** May [next] be written over project [id]'s stored book? */
    fun writable(id: String, next: List<PaletteRecipe>): Boolean = id !in blocked && next.none { it.problem() != null }

    private suspend fun inspect(id: String, text: String): List<PaletteRecipe>? {
        val parsed = RecipeBook.fromJsonRepairing(text) ?: return block(id)
        if (parsed.second > 0 && !store.isReadOnly(id) && preserved.add(id)) {
            val kept = store.keepProjectBackup(id, text)
            if (!kept) blocked += id // never overwrite what could not be preserved
            store.onRepaired(RecipeLibrary.Scope.PROJECT, parsed.second, kept)
        }
        return parsed.first.recipes
    }

    private fun block(id: String): List<PaletteRecipe>? {
        blocked += id
        store.onUnreadable(RecipeLibrary.Scope.PROJECT)
        return null
    }
}
