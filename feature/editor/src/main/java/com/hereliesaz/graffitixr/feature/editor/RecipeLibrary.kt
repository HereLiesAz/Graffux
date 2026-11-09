package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.PaletteRecipe
import com.hereliesaz.graffitixr.common.azphalt.RecipeBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The saved mixes (spec/painterly.md 6.4): a PROJECT's (`palette-recipes.json`, saved with that project) and the APP's
 * (one DataStore string, every project).
 *
 * **No mutable "current project" decides where a change goes.** Every change names its [Target], captured when the user
 * acted (a project id, or the app), and is a read-modify-write of what is STORED for that target at that moment, under
 * one lock: it never depends on what the library happened to have loaded, so it cannot be misrouted by a project switch
 * and cannot be ordered wrongly against the initial load or the swatch migration. The open project's book is only what
 * the strip DISPLAYS ([project], [openProject]).
 *
 * Preserving what could not be read: a stored book that is not a recipe book, or could not be read, is never replaced;
 * a book some of whose recipes were bad is backed up (once) before a repaired one replaces it, with the user told.
 *
 * File format `azphalt-recipes/1` (see [RecipeBook]): `{"format","recipes":[{id,name,conc[16 u8, sum 255],
 * slots[12 of {id,dataVersion}],load,approximate,originalArgb}]}`, at most 200 recipes, names at most 64 characters.
 */
class RecipeLibrary(private val store: Store) {
    enum class Scope { PROJECT, APP }

    /** Where a change goes: a named project, or the app. */
    sealed interface Target {
        data class Project(val id: String) : Target
        data object App : Target
    }

    /** The open project's recipes as shown, with which project they belong to. */
    class ProjectBook(val id: String?, val recipes: List<PaletteRecipe>)

    interface Store {
        suspend fun readProject(projectId: String): ArtifactRead
        suspend fun writeProject(projectId: String, text: String)

        /**
         * Project [projectId] is read-only (made by a newer version): its book is never written, backed up or
         * replaced (a write would go to a session copy that is never read back).
         */
        fun isReadOnly(projectId: String): Boolean = false

        /** The stored app book text right now (null: none, or it could not be read). */
        suspend fun readApp(): String?

        /**
         * The app book changed under one edit: [transform] sees the text stored AT THE TIME OF THE EDIT; null leaves it
         * untouched. True only if a new text was really persisted.
         */
        suspend fun updateApp(transform: (String?) -> String?): Boolean

        /** Keeps the original text of a project book before a repaired one replaces it; true if it was kept. */
        suspend fun keepProjectBackup(projectId: String, original: String): Boolean = false

        /** Keeps the original text of the app book before a repaired one replaces it; true if it was kept. */
        suspend fun keepAppBackup(original: String): Boolean = false

        fun onUnreadable(scope: Scope) {}

        /** Some recipes of a book could not be read and were dropped; [backedUp] says whether the original was kept. */
        fun onRepaired(scope: Scope, dropped: Int, backedUp: Boolean) {}
    }

    private val lock = Mutex()
    private val projectBook = MutableStateFlow(ProjectBook(null, emptyList()))
    private val appBook = MutableStateFlow(emptyList<PaletteRecipe>())

    private val projects = ProjectBooks(store)
    private val appEditor = AppBook(store)

    @Volatile
    private var wanted: String? = null

    /** The open project's recipes. */
    val project: StateFlow<ProjectBook> = projectBook.asStateFlow()

    /** The app-level recipes (fed by [onAppStored]). */
    val app: StateFlow<List<PaletteRecipe>> = appBook.asStateFlow()

    /** The stored app text changed (or was first read): what the app list shows. */
    fun onAppStored(text: String?) {
        appBook.value = text?.let { RecipeBook.fromJsonRepairing(it)?.first?.recipes } ?: emptyList()
    }

    /** Project [id] is now the open one (call synchronously when it opens, before [openProject] runs). */
    fun show(id: String) {
        wanted = id
        projectBook.value = ProjectBook(id, emptyList())
    }

    /**
     * Loads project [id]'s recipes for display and preserves/reports a damaged stored book right away. It shows them
     * only if [id] is still the project asked for (the loads of two quick switches may run in either order).
     */
    suspend fun openProject(id: String) = lock.withLock {
        val read = projects.read(id)
        if (read != null && wanted == id) projectBook.value = ProjectBook(id, read)
    }

    /** Adds [recipe] to [target] (an id already there is replaced). False if it could not be saved. */
    suspend fun save(target: Target, recipe: PaletteRecipe): Boolean = change(target) { list ->
        (list.filterNot { it.id == recipe.id } + recipe).takeLast(RecipeBook.MAX_RECIPES)
    }

    suspend fun rename(target: Target, id: String, name: String): Boolean = change(target) { list ->
        list.map { if (it.id == id) it.copy(name = name.trim()) else it }
    }

    suspend fun delete(target: Target, id: String): Boolean = change(target) { list -> list.filterNot { it.id == id } }

    /** Puts [list] into [target] where it is not already (by id), for the swatch migration; never removes anything. */
    suspend fun addMissing(target: Target, list: List<PaletteRecipe>): Boolean = change(target) { current ->
        current + list.filter { n -> current.none { it.id == n.id } }
    }

    private suspend fun change(
        target: Target,
        edit: (List<PaletteRecipe>) -> List<PaletteRecipe>,
    ): Boolean = lock.withLock {
        when (target) {
            is Target.Project -> changeProject(target.id, edit)
            Target.App -> appEditor.change(edit)?.also { appBook.value = it } != null
        }
    }

    // the stored book is read now, for THIS project: nothing depends on which project is shown
    private suspend fun changeProject(id: String, edit: (List<PaletteRecipe>) -> List<PaletteRecipe>): Boolean {
        val next = if (store.isReadOnly(id)) null else projects.read(id)?.let(edit)
        if (next == null || !projects.writable(id, next)) return false
        return runCatching { store.writeProject(id, RecipeBook(recipes = next).toJson()) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .onSuccess { if (projectBook.value.id == id) projectBook.value = ProjectBook(id, next) }
            .isSuccess
    }
}
