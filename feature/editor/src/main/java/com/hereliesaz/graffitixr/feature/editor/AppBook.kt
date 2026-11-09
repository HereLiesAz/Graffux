package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.PaletteRecipe
import com.hereliesaz.graffitixr.common.azphalt.RecipeBook

/**
 * Changes the app recipe book against what is stored NOW: the stored text is read, any damage is preserved, and the
 * edit only applies if the text is still the one inspected (otherwise it is inspected again), so a write can never skip
 * the preservation check for a text it did not look at. Used under [RecipeLibrary]'s lock.
 */
internal class AppBook(private val store: RecipeLibrary.Store) {
    private class Attempt(val result: List<PaletteRecipe>?, val conflict: Boolean)

    private var preserved = false

    /** The new list once it is really persisted; null if it could not be (nothing was changed). */
    suspend fun change(edit: (List<PaletteRecipe>) -> List<PaletteRecipe>): List<PaletteRecipe>? {
        var attempt = Attempt(null, true)
        var tries = 0
        while (attempt.conflict && tries < ATTEMPTS) {
            tries++
            attempt = once(edit)
        }
        return attempt.result
    }

    private suspend fun once(edit: (List<PaletteRecipe>) -> List<PaletteRecipe>): Attempt {
        val inspected = store.readApp()
        if (!mayChange(inspected)) return Attempt(null, false)
        var conflict = false
        var next: List<PaletteRecipe>? = null
        val persisted = store.updateApp { current ->
            val book = current?.let { RecipeBook.fromJsonRepairing(it) }
            val edited = edit(book?.first?.recipes.orEmpty())
            val refused = (current != null && book == null) || edited.any { it.problem() != null }
            conflict = current != inspected
            if (conflict || refused) {
                null
            } else {
                next = edited
                RecipeBook(recipes = edited).toJson()
            }
        }
        return Attempt(if (persisted) next else null, conflict)
    }

    /** False when the stored book is not a recipe book (left alone) or its damaged original could not be preserved. */
    private suspend fun mayChange(stored: String?): Boolean {
        val parsed = stored?.let { RecipeBook.fromJsonRepairing(it) }
        if (stored != null && parsed == null) {
            store.onUnreadable(RecipeLibrary.Scope.APP) // not a recipe book: left exactly as it is
            return false
        }
        val damaged = parsed != null && parsed.second > 0
        if (damaged && !preserved) {
            preserved = store.keepAppBackup(stored.orEmpty()) // once; then the user is told
            store.onRepaired(RecipeLibrary.Scope.APP, parsed?.second ?: 0, preserved)
        }
        return !damaged || preserved
    }

    private companion object {
        const val ATTEMPTS = 3
    }
}
