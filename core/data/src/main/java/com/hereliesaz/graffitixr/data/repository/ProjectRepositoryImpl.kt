package com.hereliesaz.graffitixr.data.repository

import android.content.Context
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val projectManager: ProjectManager
) : ProjectRepository {

    private val _currentProject = MutableStateFlow<GraffitiProject?>(null)
    override val currentProject: StateFlow<GraffitiProject?> = _currentProject.asStateFlow()

    // Serializes the disk write in [updateProject]'s transform overload so two concurrent transforms
    // (the editor's layer save and AR's wall-feature-map save) always persist the latest merged state.
    private val saveMutex = Mutex()

    // Backing state for the project list so observers see creates/deletes/imports,
    // as the ProjectRepository contract promises. A plain cold flow emitted once.
    private val _projects = MutableStateFlow<List<GraffitiProject>>(emptyList())
    override val projects: Flow<List<GraffitiProject>> = _projects.onStart { refreshProjects() }

    private suspend fun refreshProjects() {
        _projects.value = getProjects()
    }

    override suspend fun createProject(name: String): GraffitiProject {
        val newProject = GraffitiProject(name = name)
        projectManager.saveProject(context, newProject)
        _currentProject.value = newProject
        refreshProjects()
        return newProject
    }

    override suspend fun createProject(project: GraffitiProject) {
        projectManager.saveProject(context, project)
        _currentProject.value = project
        refreshProjects()
    }

    override suspend fun getProject(id: String): GraffitiProject? {
        return projectManager.loadProjectMetadata(context, id)
    }

    override suspend fun getProjects(): List<GraffitiProject> {
        val projectIds = projectManager.getProjectList(context)
        return projectIds.mapNotNull { id ->
            projectManager.loadProjectMetadata(context, id)
        }
    }

    override suspend fun loadProject(id: String): Result<Unit> {
        val project = getProject(id)
        return if (project != null) {
            _currentProject.value = project
            Result.success(Unit)
        } else {
            Result.failure(Exception("Project not found"))
        }
    }

    override suspend fun updateProject(project: GraffitiProject) {
        projectManager.saveProject(context, project)
        if (_currentProject.value?.id == project.id) {
            _currentProject.value = project
        }
        refreshProjects()
    }

    override suspend fun updateProject(transform: (GraffitiProject) -> GraffitiProject) {
        // Apply the transform atomically against the live state so two concurrent callers can't both
        // read the same base and clobber each other's mutation.
        val updated = _currentProject.updateAndGet { current -> current?.let(transform) } ?: return
        // Persist under the same mutex deleteProject uses, and write the LATEST merged state (not
        // this call's `updated` snapshot), so a concurrent transform's disk write can't overwrite
        // the file with a staler in-memory value. This is what makes the editor's layer save and
        // AR's wall-map save non-destructive when they run at the same time (docs/AUDIT.md save-race).
        saveMutex.withLock {
            // If currentProject is null here, this project was deleted by a concurrent
            // deleteProject() while we were between updateAndGet and acquiring the lock (`updated`
            // was non-null, so this can't be "never had a project" — deleteProject is the only other
            // writer that nulls it). Falling back to `updated` would resurrect the just-deleted
            // project's directory; skip the save and let the delete stand.
            val toSave = _currentProject.value ?: return@withLock
            projectManager.saveProject(context, toSave)
        }
        refreshProjects()
    }

    override suspend fun deleteProject(id: String) {
        // Same mutex as updateProject's transform overload: without it, a delete racing a concurrent
        // updateProject{...} could lose the race after the transform reads `_currentProject.value`
        // but before its save — that save would then recreate the just-deleted project's directory
        // and resurrect it on disk and in the project list.
        saveMutex.withLock {
            projectManager.deleteProject(context, id)
            if (_currentProject.value?.id == id) {
                _currentProject.value = null
            }
        }
        refreshProjects()
    }

    override suspend fun saveArtifact(projectId: String, filename: String, data: ByteArray): String = withContext(Dispatchers.IO) {
        require(projectManager.isSafeProjectId(projectId)) { "Unsafe project id: $projectId" }
        val root = File(context.filesDir, "projects/$projectId")
        if (!root.exists()) root.mkdirs()
        val file = requireNotNull(projectManager.resolveInside(root, filename)) {
            "Unsafe artifact filename: $filename"
        }
        // Atomic write: a half-written map.bin / fingerprint can crash native loaders.
        val tmp = File(root, "$filename.tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
        }
        if (tmp.exists()) tmp.delete()
        file.absolutePath
    }

    override suspend fun updateTargetFingerprint(projectId: String, path: String) {
        // The transform overload, not getProject(id).copy(...): getProject used to read straight
        // from disk, independent of _currentProject, so it could miss an in-memory-only change (e.g.
        // a layer edit whose disk write is still in flight) that exists at the moment this runs —
        // and the plain updateProject(project) overload writes its stale copy unconditionally,
        // discarding that change everywhere it applies. Same hazard, same fix, as
        // EditorViewModel.setBackgroundImage. Guarded on id rather than assuming [projectId] is
        // always the live project: a mismatch is a genuine no-op rather than silently mutating
        // whatever else happens to be loaded.
        updateProject { current -> if (current.id == projectId) current.copy(targetFingerprintPath = path) else current }
    }

    override suspend fun updateMapPath(projectId: String, path: String) {
        updateProject { current -> if (current.id == projectId) current.copy(mapPath = path) else current }
    }

    override suspend fun importProject(uri: android.net.Uri): Result<GraffitiProject> {
        val project = projectManager.importProjectFromUri(context, uri)
            ?: return Result.failure(Exception("Failed to import project from $uri"))
        _currentProject.value = project
        refreshProjects()
        return Result.success(project)
    }
}
