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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val projectManager: ProjectManager
) : ProjectRepository {

    private val _currentProject = MutableStateFlow<GraffitiProject?>(null)
    override val currentProject: StateFlow<GraffitiProject?> = _currentProject.asStateFlow()

    // Serializes every project write and every swap of [_currentProject] (create, load, import,
    // delete, and the transform overload of [updateProject]). A transform is applied and saved under
    // this lock, so it always persists the project it edited -- a concurrent project switch can't
    // redirect the save to a different project -- and two concurrent transforms (the editor's layer
    // save and AR's wall-feature-map save) persist the latest merged state in order.
    private val saveMutex = Mutex()

    // Ids deleted this session. A late saveArtifact (debounced layer write, thumbnail job) for one of
    // them must not recreate its directory. Cleared when the id is created/loaded/imported again.
    // Guarded by [saveMutex].
    private val deletedIds = mutableSetOf<String>()

    // Backing state for the project list so observers see creates/deletes/imports,
    // as the ProjectRepository contract promises. A plain cold flow emitted once.
    private val _projects = MutableStateFlow<List<GraffitiProject>>(emptyList())
    override val projects: Flow<List<GraffitiProject>> = _projects.onStart {
        saveMutex.withLock { _projects.value = getProjects() }
    }

    /**
     * Re-reads just [id] from disk and patches the published list, instead of re-parsing every
     * project.json on every edit. Callers hold [saveMutex], so publishes are ordered.
     */
    private suspend fun refreshProject(id: String) {
        val fresh = getProject(id)
        _projects.update { list ->
            val index = list.indexOfFirst { it.id == id }
            when {
                fresh == null -> list.filterNot { it.id == id }
                index >= 0 -> list.toMutableList().also { it[index] = fresh }
                else -> list + fresh
            }
        }
    }

    override suspend fun createProject(name: String): GraffitiProject {
        val newProject = GraffitiProject(name = name)
        saveMutex.withLock {
            projectManager.saveProject(context, newProject)
            deletedIds.remove(newProject.id)
            _currentProject.value = newProject
            refreshProject(newProject.id)
        }
        return newProject
    }

    override suspend fun createProject(project: GraffitiProject) {
        require(projectManager.isSafeProjectId(project.id)) { "Unsafe project id: ${project.id}" }
        saveMutex.withLock {
            projectManager.saveProject(context, project)
            deletedIds.remove(project.id)
            _currentProject.value = project
            refreshProject(project.id)
        }
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

    override suspend fun loadProject(id: String): Result<Unit> = saveMutex.withLock {
        val project = getProject(id)
        if (project != null) {
            deletedIds.remove(id)
            _currentProject.value = project
            Result.success(Unit)
        } else {
            Result.failure(Exception("Project not found"))
        }
    }

    override suspend fun updateProject(project: GraffitiProject) {
        saveMutex.withLock {
            projectManager.saveProject(context, project)
            if (_currentProject.value?.id == project.id) {
                _currentProject.value = project
            }
            refreshProject(project.id)
        }
    }

    override suspend fun updateProject(transform: (GraffitiProject) -> GraffitiProject) {
        // Transform and save under one lock: the project saved is exactly the one the transform
        // edited, a project switch (which takes the same lock) can't land in between, and a
        // concurrent deleteProject can't be followed by a save that resurrects its directory
        // (after a delete `_currentProject` is null, so there is nothing to transform or save).
        saveMutex.withLock {
            val updated = _currentProject.updateAndGet { current -> current?.let(transform) } ?: return@withLock
            projectManager.saveProject(context, updated)
            refreshProject(updated.id)
        }
    }

    override suspend fun deleteProject(id: String) {
        saveMutex.withLock {
            projectManager.deleteProject(context, id)
            deletedIds.add(id)
            if (_currentProject.value?.id == id) {
                _currentProject.value = null
            }
            refreshProject(id)
        }
    }

    override suspend fun saveArtifact(projectId: String, filename: String, data: ByteArray): String = withContext(Dispatchers.IO) {
        require(projectManager.isSafeProjectId(projectId)) { "Unsafe project id: $projectId" }
        val root = File(context.filesDir, "projects/$projectId")
        val file = requireNotNull(projectManager.resolveInside(root, filename)) {
            "Unsafe artifact filename: $filename"
        }
        // Under the same lock as deleteProject, so a delete can't interleave with the write.
        saveMutex.withLock {
            if (projectId in deletedIds) {
                // Deleted while this write was in flight: writing would recreate the directory as an
                // invisible, never-reclaimed orphan.
                return@withLock file.absolutePath
            }
            file.parentFile?.mkdirs()
            // Atomic write: a half-written map.bin / fingerprint can crash native loaders. A unique
            // temp name so concurrent writers to one filename can't share (and truncate) it.
            val tmp = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
            try {
                tmp.writeBytes(data)
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            file.absolutePath
        }
    }

    override suspend fun updateTargetFingerprint(projectId: String, path: String) {
        // The transform overload, not getProject(id).copy(...): getProject used to read straight
        // from disk, independent of _currentProject, so it could miss an in-memory-only change (e.g.
        // a layer edit whose disk write is still in flight) that exists at the moment this runs --
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
        saveMutex.withLock {
            deletedIds.remove(project.id)
            _currentProject.value = project
            refreshProject(project.id)
        }
        return Result.success(project)
    }
}
