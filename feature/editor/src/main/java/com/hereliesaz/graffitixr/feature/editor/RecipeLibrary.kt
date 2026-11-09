package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.PaletteRecipe
import com.hereliesaz.graffitixr.common.azphalt.RecipeBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The saved mixes (spec/painterly.md 6.4): the open PROJECT's (`palette-recipes.json`, saved with the project) and
 * the APP's (one DataStore string, every project). Saving offers a [Scope]. All changes to a scope go through one lock
 * and a read-modify-write of what is stored, so nothing is lost to a concurrent change; a stored book that cannot be
 * read is never replaced by one built from nothing (the change is refused and the user told).
 *
 * File format `azphalt-recipes/1` (see [RecipeBook]): `{"format","recipes":[{id,name,conc[16 u8, sum 255],
 * slots[12 of {id,dataVersion}],load,approximate,originalArgb}]}`, at most 200 recipes, names at most 64 characters.
 */
class RecipeLibrary(private val store: Store) {
    enum class Scope { PROJECT, APP }

    interface Store {
        suspend fun readProject(projectId: String): String?
        suspend fun writeProject(projectId: String, text: String)

        /** The app book changed under one edit: [transform] sees the stored text; null leaves it untouched. */
        suspend fun updateApp(transform: (String?) -> String?)
        fun onUnreadable(scope: Scope) {}
    }

    private val lock = Mutex()
    private val projectBook = MutableStateFlow(emptyList<PaletteRecipe>())
    private val appBook = MutableStateFlow(emptyList<PaletteRecipe>())
    private var projectId: String? = null
    private var projectReadable = true

    /** The open project's recipes. */
    val project: StateFlow<List<PaletteRecipe>> = projectBook.asStateFlow()

    /** The app-level recipes (fed by [onAppStored]). */
    val app: StateFlow<List<PaletteRecipe>> = appBook.asStateFlow()

    /** The stored app text changed (or was first read): what the app list shows. */
    fun onAppStored(text: String?) {
        appBook.value = text?.let { RecipeBook.fromJsonRepairing(it)?.first?.recipes } ?: emptyList()
    }

    /** Project [id] opened: its recipes are read; a file that cannot be read leaves the list empty and unsaved over. */
    suspend fun openProject(id: String) = lock.withLock {
        projectId = id
        projectBook.value = emptyList()
        projectReadable = true
        val text = store.readProject(id) ?: return@withLock
        val parsed = RecipeBook.fromJsonRepairing(text)
        if (parsed == null) {
            projectReadable = false
            store.onUnreadable(Scope.PROJECT)
        } else {
            projectBook.value = parsed.first.recipes
        }
    }

    /** Adds [recipe] to [scope] (an id already there is replaced). False if it could not be saved. */
    suspend fun save(scope: Scope, recipe: PaletteRecipe): Boolean = change(scope) { list ->
        (list.filterNot { it.id == recipe.id } + recipe).takeLast(RecipeBook.MAX_RECIPES)
    }

    suspend fun rename(scope: Scope, id: String, name: String): Boolean = change(scope) { list ->
        list.map { if (it.id == id) it.copy(name = name.trim()) else it }
    }

    suspend fun delete(scope: Scope, id: String): Boolean = change(scope) { list -> list.filterNot { it.id == id } }

    /** Puts [list] into [scope] where it is not already (by id), for the swatch migration; never removes anything. */
    suspend fun addMissing(scope: Scope, list: List<PaletteRecipe>): Boolean = change(scope) { current ->
        current + list.filter { n -> current.none { it.id == n.id } }
    }

    private suspend fun change(
        scope: Scope,
        edit: (List<PaletteRecipe>) -> List<PaletteRecipe>,
    ): Boolean = lock.withLock {
        when (scope) {
            Scope.PROJECT -> changeProject(edit)
            Scope.APP -> changeApp(edit)
        }
    }

    private suspend fun changeProject(edit: (List<PaletteRecipe>) -> List<PaletteRecipe>): Boolean {
        val id = projectId
        val next = edit(projectBook.value)
        // no project open, a file that could not be read (never written over), or a malformed result: refused
        if (id == null || !projectReadable || next.any { it.problem() != null }) return false
        return runCatching { store.writeProject(id, RecipeBook(recipes = next).toJson()) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .onSuccess { if (projectId == id) projectBook.value = next }
            .isSuccess
    }

    private suspend fun changeApp(edit: (List<PaletteRecipe>) -> List<PaletteRecipe>): Boolean {
        var applied = false
        store.updateApp { stored ->
            val parsed = stored?.let { RecipeBook.fromJsonRepairing(it) }
            if (stored != null && parsed == null) {
                store.onUnreadable(Scope.APP) // not a recipe book: left exactly as it is
                null
            } else {
                val next = edit(parsed?.first?.recipes.orEmpty())
                if (next.any { it.problem() != null }) {
                    null
                } else {
                    applied = true
                    appBook.value = next
                    RecipeBook(recipes = next).toJson()
                }
            }
        }
        return applied
    }
}
