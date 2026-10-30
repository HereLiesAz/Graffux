package com.hereliesaz.graffitixr.domain.repository

import com.hereliesaz.graffitixr.common.model.GraffitiProject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for managing [GraffitiProject] data.
 * This repository handles CRUD operations, persistence, and reactive state management
 * for the current active project.
 */
interface ProjectRepository {
    /**
     * A [StateFlow] emitting the currently active project, or null if no project is loaded.
     * Observers can collect this flow to react to project changes (e.g., layer edits).
     */
    val currentProject: StateFlow<GraffitiProject?>

    /**
     * A [Flow] emitting the list of all available projects.
     * Updated whenever a project is created or deleted.
     */
    val projects: Flow<List<GraffitiProject>>

    /**
     * Creates a new project with the given name.
     * @param name The name of the new project.
     * @return The created [GraffitiProject] instance.
     */
    suspend fun createProject(name: String): GraffitiProject

    /**
     * Saves a new project instance to the repository.
     * @param project The project to save.
     */
    suspend fun createProject(project: GraffitiProject)

    /**
     * Retrieves a project by its ID.
     * @param id The unique identifier of the project.
     * @return The project, or null if not found.
     */
    suspend fun getProject(id: String): GraffitiProject?

    /**
     * Retrieves all available projects.
     * @return A list of all projects.
     */
    suspend fun getProjects(): List<GraffitiProject>

    /**
     * Sets the active project by ID, triggering updates to [currentProject].
     * @param id The ID of the project to load.
     * @return A [Result] indicating success or failure.
     */
    suspend fun loadProject(id: String): Result<Unit>

    /**
     * Updates an existing project. The in-memory state changes only once the write succeeded.
     * @param project The project with updated fields.
     * @throws ProjectNotSavedException when the project can't be written (e.g. it is read-only).
     */
    suspend fun updateProject(project: GraffitiProject)

    /**
     * Atomically updates the current project using a transformation function.
     * Useful for modifying immutable data classes safely.
     * @param transform A function that takes the current project and returns the new state.
     * @throws ProjectNotSavedException when the result can't be written; [currentProject] keeps the old state.
     */
    suspend fun updateProject(transform: (GraffitiProject) -> GraffitiProject)

    /**
     * Deletes a project by ID.
     * @param id The ID of the project to delete.
     */
    suspend fun deleteProject(id: String)

    /**
     * Saves a binary artifact (e.g., image, map file) associated with a project.
     * @param projectId The project ID.
     * @param filename The name of the file to save.
     * @param data The binary content.
     * @return The absolute path to the saved file. For a read-only project (a newer-format
     *   project.json) nothing is written into the project: the file goes to a scratch copy in the
     *   cache and that path is returned, so the session can still use it.
     */
    suspend fun saveArtifact(projectId: String, filename: String, data: ByteArray): String

    /**
     * Imports a project from a .gxr zip file URI.
     * Extracts and persists the project, then sets it as the current project.
     * @param uri The URI of the .gxr file to import.
     * @return [Result.success] with the imported project, or [Result.failure] on error.
     */
    suspend fun importProject(uri: android.net.Uri): Result<GraffitiProject>
}

/** A project write was refused — its project.json is from a newer format, or couldn't be read. */
class ProjectNotSavedException(val projectId: String) :
    java.io.IOException("Project $projectId is read-only and was not saved")
