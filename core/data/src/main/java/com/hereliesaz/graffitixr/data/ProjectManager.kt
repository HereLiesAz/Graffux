package com.hereliesaz.graffitixr.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode
import com.hereliesaz.graffitixr.common.model.*
import com.hereliesaz.graffitixr.common.model.BlendMode as ModelBlendMode
import com.hereliesaz.graffitixr.common.util.ImageUtils
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

interface UriProvider {
    fun getUriForFile(file: File): Uri
}

class DefaultUriProvider @Inject constructor() : UriProvider {
    override fun getUriForFile(file: File): Uri {
        return Uri.fromFile(file)
    }
}

@Singleton
class ProjectManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val uriProvider: UriProvider,
    private val projectRepositoryProvider: Provider<ProjectRepository>
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        /**
         * Cap on total decompressed bytes accepted from an imported/peer-received `.fux` archive.
         * Both sources are untrusted (a shared file, or the co-op wire), so a zip bomb must not
         * be able to fill the app sandbox. Generous vs. real projects (multi-layer PNGs).
         */
        private const val MAX_IMPORT_BYTES = 512L * 1024 * 1024

        /** Cap on entries in an imported archive (each costs a temp file + inode). */
        private const val MAX_IMPORT_ENTRIES = 10_000

        /** project.json is decoded as one String; real ones are well under a megabyte. */
        private const val MAX_PROJECT_JSON_BYTES = 32L * 1024 * 1024
    }

    fun getProjectList(context: Context): List<String> {
        val projectsDir = File(context.filesDir, "projects")
        if (!projectsDir.exists()) return emptyList()
        return projectsDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
    }

    fun deleteProject(context: Context, projectName: String) {
        require(isSafeProjectId(projectName)) { "Unsafe project id: $projectName" }
        val projectDir = File(context.filesDir, "projects/$projectName")
        if (projectDir.exists()) {
            projectDir.deleteRecursively()
        }
    }

    suspend fun saveProject(context: Context, projectData: GraffitiProject, targetImages: List<Bitmap>? = null, thumbnail: Bitmap? = null) = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/${projectData.id}")
        if (!root.exists()) root.mkdirs()

        val incoming = projectData

        val thumbnailUri = if (thumbnail != null) {
            val file = File(root, "thumbnail.png")
            FileOutputStream(file).use { out ->
                thumbnail.compress(Bitmap.CompressFormat.PNG, 80, out)
            }
            uriProvider.getUriForFile(file)
        } else {
            incoming.thumbnailUri ?: run {
                val file = File(root, "thumbnail.png")
                if (file.exists()) uriProvider.getUriForFile(file) else null
            }
        }

        // Properly append new targets to the existing list
        val savedTargetUris = if (targetImages != null) {
            // Past the highest index on disk, not the list size: after a target is removed from the
            // middle, size-based numbering would overwrite a surviving target's file.
            val taken = root.listFiles().orEmpty()
                .mapNotNull { Regex("^target_(\\d+)\\.png$").find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            val next = maxOf(incoming.targetImageUris.size, (taken.maxOrNull() ?: -1) + 1)
            val newUris = targetImages.mapIndexed { index, bitmap ->
                val file = File(root, "target_${next + index}.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                uriProvider.getUriForFile(file)
            }
            incoming.targetImageUris + newUris
        } else {
            incoming.targetImageUris
        }

        val updatedGraffitiProject = incoming.copy(
            thumbnailUri = thumbnailUri,
            targetImageUris = savedTargetUris,
            lastModified = System.currentTimeMillis()
        )

        val jsonString = json.encodeToString(updatedGraffitiProject)
        atomicWriteText(File(root, "project.json"), jsonString)
    }

    /**
     * Writes [text] to [target] atomically: stream into a sibling temp file then rename
     * over the target, so a crash/kill/IO-error mid-write can never leave a truncated
     * project.json that would fail to parse and silently drop the project on next load.
     */
    private fun atomicWriteText(target: File, text: String) {
        // A unique temp name per write: unlocked writers (import, migration) must never share one.
        val tmp = File(target.parentFile, "${target.name}.${java.util.UUID.randomUUID()}.tmp")
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    suspend fun loadProject(context: Context, projectId: String): LoadedProject? = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/$projectId")
        val projectFile = File(root, "project.json")
        if (!projectFile.exists()) return@withContext null

        return@withContext try {
            val jsonString = projectFile.readText()
            val decoded = decodeProject(jsonString)
            // Persist the migration only on a full load — loadProjectMetadata stays read-only.
            val projectData = migrateAndPersist(context, decoded)

            val targetBitmaps = projectData.targetImageUris.mapNotNull { uri ->
                ImageUtils.loadBitmapSync(context, uri)
            }

            LoadedProject(projectData, targetBitmaps)
        } catch (e: Exception) {
            Log.e("ProjectManager", "Failed to load project", e)
            null
        }
    }

    suspend fun loadProjectMetadata(context: Context, projectId: String): GraffitiProject? = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/$projectId")
        val projectFile = File(root, "project.json")
        if (!projectFile.exists()) return@withContext null

        return@withContext try {
            val jsonString = projectFile.readText()
            val project = decodeProject(jsonString)
            // In-memory migration only: this is a read path (dashboard listing) and must not
            // write to disk — the persisted migration happens in loadProject.
            migrateInMemory(project)
        } catch (e: Exception) {
            Log.e("ProjectManager", "Failed to load project metadata", e)
            null
        }
    }

    /**
     * Decodes a project.json, reading its `formatVersion` first (a file without one is
     * [LEGACY_FORMAT_VERSION]) and migrating it to [CURRENT_FORMAT_VERSION] with [migrateFormat].
     * A file from a newer build is opened as far as this one understands it (unknown keys are
     * ignored), and logged.
     */
    internal fun decodeProject(text: String): GraffitiProject {
        val element = json.parseToJsonElement(text)
        val version = (element as? kotlinx.serialization.json.JsonObject)
            ?.get("formatVersion")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
            ?: LEGACY_FORMAT_VERSION
        if (version > CURRENT_FORMAT_VERSION) {
            Log.w("ProjectManager", "project.json format $version is newer than $CURRENT_FORMAT_VERSION")
        }
        return migrateFormat(json.decodeFromJsonElement(GraffitiProject.serializer(), element), version)
    }

    /**
     * Brings a project decoded from format [fromVersion] to [CURRENT_FORMAT_VERSION], one step per
     * version. 1 -> 2 (photo adjustment layers): a version 1 file has no adjustment layers and no
     * key that changed meaning, so the step only records the new version; it is written back on
     * the next save. 2 -> 3 (the document crop): a missing crop is no crop, which is the field's
     * default, so this step too only records the version. 3 -> 4 (design frames): a group with a
     * declared box (given a Frame Size before frames existed) was a frame in all but name, so it
     * becomes one, with no fill and no clip so that nothing about how it looks changes; any frame
     * keys a version 3 file could not have written are cleared. Never lowers a newer version.
     */
    internal fun migrateFormat(project: GraffitiProject, fromVersion: Int): GraffitiProject {
        var version = fromVersion
        var migrated = project
        if (version < FORMAT_ADJUSTMENT_LAYERS) {
            migrated = migrated.copy(layers = migrated.layers.map { it.copy(adjustment = null) })
            version = FORMAT_ADJUSTMENT_LAYERS
        }
        if (version < FORMAT_DOCUMENT_CROP) {
            migrated = migrated.copy(documentCrop = com.hereliesaz.graffitixr.common.photo.DocumentCrop())
            version = FORMAT_DOCUMENT_CROP
        }
        if (version < FORMAT_FRAMES) {
            migrated = migrated.copy(layers = migrated.layers.map(::migrateSizedGroupToFrame))
            version = FORMAT_FRAMES
        }
        return migrated.copy(formatVersion = maxOf(version, CURRENT_FORMAT_VERSION))
    }

    private fun migrateSizedGroupToFrame(layer: com.hereliesaz.graffitixr.common.model.OverlayLayer) =
        if (layer.layerType == com.hereliesaz.graffitixr.common.model.LayerType.GROUP &&
            layer.layoutWidth > 0f && layer.layoutHeight > 0f
        ) {
            layer.copy(isFrame = true, frameFillArgb = null, clipsContent = false, frameRadius = 0f)
        } else {
            layer.copy(isFrame = false, frameFillArgb = null, clipsContent = false, frameRadius = 0f)
        }

    /**
     * Migrates a decoded project and, when the migration changed anything, persists it so the
     * next read sees layer visuals rather than `legacyVisuals`. Used by [loadProject], the
     * `.fux` import and the co-op spectator load. The app's own disk load goes through
     * [loadProjectMetadata], which migrates in memory only; that migration is persisted by the next
     * ordinary save.
     */
    private suspend fun migrateAndPersist(context: Context, decoded: GraffitiProject): GraffitiProject {
        val migrated = migrateInMemory(decoded)
        if (migrated !== decoded) {
            saveProject(context, migrated)
            Log.i("ProjectManager", "Migrated legacyVisuals for project ${migrated.id}")
        }
        return migrated
    }

    /**
     * Applies the legacyVisuals→layers migration purely in memory. Returns [project] itself
     * (same reference) when no migration is needed, so callers can detect change by identity
     * and decide whether to persist.
     *
     * Merge rule — the migration never discards a non-default legacy value:
     * - The target is the first layer, or, when there are no layers but an `overlayImageUri`,
     *   a fresh "Overlay" layer built from it.
     * - Field by field: a legacy value that is default, or equal to the layer's, is consumed. A
     *   layer field still at its default takes the legacy value. A layer field that already
     *   holds a *different* non-default value keeps it — the layer is the newer source of truth —
     *   and the legacy value stays behind in `legacyVisuals`.
     * - With no target layer at all, `legacyVisuals` is left untouched.
     *
     * So `legacyVisuals` ends up holding exactly the values that could not be placed, and the
     * migration is idempotent: re-running it on its own output returns the same reference.
     */
    internal fun migrateInMemory(project: GraffitiProject): GraffitiProject {
        val lv = project.legacyVisuals
        val target = project.layers.firstOrNull()
            ?: project.overlayImageUri?.let { OverlayLayer(uri = it, name = "Overlay") }
        if (lv == LegacyVisuals() || target == null) return project

        val (mergedLayer, residual) = mergeLegacyInto(target, lv)
        val layers = listOf(mergedLayer) + project.layers.drop(1)
        val result = project.copy(layers = layers, legacyVisuals = residual)
        return if (result == project) project else result
    }

    /** Per-field merge for [migrateInMemory]: returns (value for the layer, value left in legacy). */
    private fun <T> mergeField(layerValue: T, legacyValue: T, default: T): Pair<T, T> = when {
        legacyValue == default || legacyValue == layerValue -> layerValue to default
        layerValue == default -> legacyValue to default
        else -> layerValue to legacyValue
    }

    private fun mergeLegacyInto(layer: OverlayLayer, lv: LegacyVisuals): Pair<OverlayLayer, LegacyVisuals> {
        val d = LegacyVisuals()
        val scale = mergeField(layer.scale, lv.scale, d.scale)
        val offset = mergeField(layer.offset, lv.offset, d.offset)
        val rotX = mergeField(layer.rotationX, lv.rotationX, d.rotationX)
        val rotY = mergeField(layer.rotationY, lv.rotationY, d.rotationY)
        val rotZ = mergeField(layer.rotationZ, lv.rotationZ, d.rotationZ)
        val opacity = mergeField(layer.opacity, lv.opacity, d.opacity)
        val brightness = mergeField(layer.brightness, lv.brightness, d.brightness)
        val contrast = mergeField(layer.contrast, lv.contrast, d.contrast)
        val saturation = mergeField(layer.saturation, lv.saturation, d.saturation)
        val cbR = mergeField(layer.colorBalanceR, lv.colorBalanceR, d.colorBalanceR)
        val cbG = mergeField(layer.colorBalanceG, lv.colorBalanceG, d.colorBalanceG)
        val cbB = mergeField(layer.colorBalanceB, lv.colorBalanceB, d.colorBalanceB)
        val blend = mergeField(layer.blendMode, lv.blendMode.toModelBlendMode(), ModelBlendMode.SrcOver)
        val blendConflict = blend.second != ModelBlendMode.SrcOver

        val merged = layer.copy(
            scale = scale.first, offset = offset.first,
            rotationX = rotX.first, rotationY = rotY.first, rotationZ = rotZ.first,
            opacity = opacity.first, blendMode = blend.first,
            brightness = brightness.first, contrast = contrast.first, saturation = saturation.first,
            colorBalanceR = cbR.first, colorBalanceG = cbG.first, colorBalanceB = cbB.first,
        )
        val residual = LegacyVisuals(
            opacity = opacity.second, brightness = brightness.second, contrast = contrast.second,
            saturation = saturation.second,
            colorBalanceR = cbR.second, colorBalanceG = cbG.second, colorBalanceB = cbB.second,
            scale = scale.second, rotationX = rotX.second, rotationY = rotY.second, rotationZ = rotZ.second,
            offset = offset.second,
            blendMode = if (blendConflict) lv.blendMode else d.blendMode,
        )
        return merged to residual
    }

    /**
     * Writes project [projectId] to [uri] as a `.fux` archive — `project.json` plus every artifact
     * beside it, so the file is a complete, portable copy of the project.
     *
     * Throws rather than logging. When the user has picked a location and named a file, a failure
     * that produces nothing but a logcat line is indistinguishable from a successful save until
     * they try to open it — the caller needs to be able to say so.
     */
    fun writeProjectArchive(context: Context, projectId: String, uri: Uri) {
        val sourceFolder = File(context.filesDir, "projects/$projectId")
        if (!sourceFolder.exists()) {
            throw java.io.FileNotFoundException("Project $projectId has nothing saved to write")
        }
        val out = context.contentResolver.openOutputStream(uri)
            ?: throw java.io.IOException("Couldn't open the chosen location for writing")
        out.use { os ->
            ZipOutputStream(os).use { zos ->
                // Use empty string for parent to zip contents directly into the root.
                zipFolder(sourceFolder, "", zos)
            }
        }
    }

    /**
     * Read the current ZIP entry, bounding the CUMULATIVE decompressed size: streams in chunks and
     * aborts (returns null) the moment [runningTotal] + this entry would exceed [MAX_IMPORT_BYTES], so
     * a zip bomb can't OOM the app before the cap is hit — never `readBytes()` an entry unbounded.
     * Returns (entryBytes, newRunningTotal), or null once the cap is exceeded.
     */
    private fun streamEntryBounded(zis: ZipInputStream, out: java.io.OutputStream, runningTotal: Long): Long? {
        val chunk = ByteArray(64 * 1024)
        var total = runningTotal
        while (true) {
            val n = zis.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_IMPORT_BYTES) return null
            out.write(chunk, 0, n)
        }
        return total
    }

    /**
     * Imports a `.fux` archive as a NEW local project directory; it never overwrites an existing one.
     * When the archive's project id is already on disk (re-opening an older export of a project you
     * kept editing) the import gets a fresh id and its `projects/<oldId>/` path references are
     * rebased, so both copies survive. Files are staged beside `projects/` and moved in with a
     * single directory rename, so a failure part-way leaves nothing behind.
     */
    suspend fun importProjectFromUri(context: Context, uri: Uri): GraffitiProject? = withContext(Dispatchers.IO) {
        val extracted = ExtractedArchive()
        return@withContext try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zis ->
                    if (!extractArchive(zis, "gxr_", stripTopDir = true, into = extracted)) {
                        return@use null
                    }
                    installExtracted(context, extracted)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ProjectManager", "Import failed", e)
            null
        } finally {
            // Temp files are only moved away on the success path; clear any stragglers.
            extracted.files.values.forEach { if (it.exists()) it.delete() }
        }
    }

    /** Temp files for each archive entry (keyed by in-project relative name) plus project.json's text. */
    private class ExtractedArchive {
        val files = mutableMapOf<String, File>()
        var projectJson: String? = null
    }

    /**
     * Streams every entry of [zis] into [into] under the cumulative byte cap, an entry-count cap and
     * a project.json size cap (project.json is read into a String, and an OutOfMemoryError is not an
     * Exception, so it must be bounded up front). Returns false when a cap is exceeded.
     */
    private fun extractArchive(
        zis: ZipInputStream,
        prefix: String,
        stripTopDir: Boolean,
        into: ExtractedArchive,
    ): Boolean {
        var totalBytes = 0L
        var count = 0
        var withinCaps = true
        var entry = zis.nextEntry
        while (entry != null && withinCaps) {
            val name = entry.name
            val relativeName = if (stripTopDir && name.contains('/')) name.substringAfter('/') else name
            if (!entry.isDirectory && relativeName.isNotEmpty()) {
                val newTotal = if (++count > MAX_IMPORT_ENTRIES) {
                    Log.e("ProjectManager", "Archive rejected: more than $MAX_IMPORT_ENTRIES entries")
                    null
                } else {
                    extractEntry(zis, prefix, relativeName, totalBytes, into)
                }
                if (newTotal == null) withinCaps = false else totalBytes = newTotal
            }
            if (withinCaps) {
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return withinCaps
    }

    /** Copies the current entry to a temp file; returns the new running byte total, or null over a cap. */
    private fun extractEntry(
        zis: ZipInputStream,
        prefix: String,
        relativeName: String,
        totalBytes: Long,
        into: ExtractedArchive,
    ): Long? {
        val tmpFile = File.createTempFile(prefix, null, context.cacheDir)
        val newTotal = try {
            FileOutputStream(tmpFile).use { fos -> streamEntryBounded(zis, fos, totalBytes) }
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
        val isManifest = relativeName == "project.json"
        if (newTotal == null || (isManifest && tmpFile.length() > MAX_PROJECT_JSON_BYTES)) {
            tmpFile.delete()
            Log.e("ProjectManager", "Archive rejected: over the size limits")
            return null
        }
        if (isManifest) into.projectJson = tmpFile.readText()
        // A duplicate entry name replaces the earlier one; don't leak its temp file.
        into.files.put(relativeName, tmpFile)?.delete()
        return newTotal
    }

    /** The archive's project.json decoded, or null when it is missing, unparseable or has an unsafe id. */
    private fun parseArchiveProject(extracted: ExtractedArchive): GraffitiProject? {
        val project = extracted.projectJson?.let {
            try {
                decodeProject(it)
            } catch (e: Exception) {
                Log.e("ProjectManager", "Failed to parse project.json", e)
                null
            }
        }
        // project.id comes from the (untrusted) archive and becomes a path segment.
        if (project != null && !isSafeProjectId(project.id)) {
            Log.e("ProjectManager", "Archive rejected: unsafe project id")
            return null
        }
        return project
    }

    /**
     * Moves an extracted archive into `projects/<id>/` transactionally and returns the (migrated)
     * project, or null if the archive has no usable project.json or the id is unsafe. Never touches an
     * existing project directory: on an id collision the project is given a new id and its absolute
     * `/projects/<oldId>/` paths are rewritten to the new one. On any failure the staging directory
     * and the half-installed project directory are removed.
     */
    private suspend fun installExtracted(context: Context, extracted: ExtractedArchive): GraffitiProject? {
        val original = parseArchiveProject(extracted) ?: return null
        val projectsDir = File(context.filesDir, "projects")
        val newId = if (File(projectsDir, original.id).exists()) {
            java.util.UUID.randomUUID().toString().also {
                Log.i("ProjectManager", "Project id ${original.id} already exists; importing as $it")
            }
        } else {
            original.id
        }
        // Every URI in an imported project is pinned to the project's own folder on this device;
        // one pointing anywhere else (another project, a content:// grant, a path from the
        // exporting device) is dropped, so a crafted .fux can't make a layer read other files.
        val localized = localizeArchiveUris(
            requireNotNull(extracted.projectJson).replace("/projects/${original.id}/", "/projects/$newId/"),
            "file://${projectsDir.absolutePath}/$newId/",
        )
        val project = decodeProject(localized).copy(id = newId)
        val projectJsonOverride: String? = json.encodeToString(project)
        val destDir = File(projectsDir, project.id)
        val staging = File(context.filesDir, "import_staging/${java.util.UUID.randomUUID()}")
        var installed = false
        var result: GraffitiProject? = null
        try {
            stageFiles(extracted, staging, projectJsonOverride)
            projectsDir.mkdirs()
            // Atomic, and fails (rather than merging) if something created destDir meanwhile.
            check(staging.renameTo(destDir)) { "Couldn't move imported project into place" }
            installed = true
            // project.json is now on disk under destDir; migrate it there too.
            result = migrateAndPersist(context, project)
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (installed) destDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            Log.e("ProjectManager", "Install failed; rolling back", e)
            if (installed) destDir.deleteRecursively()
        } finally {
            staging.deleteRecursively()
        }
        return result
    }

    /** Moves each extracted temp file into [staging], skipping Zip-Slip names; swaps in [projectJsonOverride]. */
    private fun stageFiles(extracted: ExtractedArchive, staging: File, projectJsonOverride: String?) {
        staging.mkdirs()
        for ((name, tmpFile) in extracted.files) {
            // Zip-Slip guard: entry names are attacker-controlled and may contain ".." components.
            val dest = resolveInside(staging, name)
            if (dest == null) {
                Log.w("ProjectManager", "Skipping zip entry escaping project dir: $name")
                continue
            }
            dest.parentFile?.mkdirs()
            if (name == "project.json" && projectJsonOverride != null) {
                dest.writeText(projectJsonOverride)
            } else if (!tmpFile.renameTo(dest)) {
                // renameTo can fail across filesystems (cache vs files dir).
                tmpFile.copyTo(dest, overwrite = true)
            }
        }
    }

    /**
     * Resolves [entryName] to a file under [destDir], or null if the name (via ".." components,
     * absolute paths, etc.) would escape it — the Zip-Slip attack. Canonical-path prefix check.
     */
    internal fun resolveInside(destDir: File, entryName: String): File? {
        val candidate = File(destDir, entryName)
        val canonical = candidate.canonicalPath
        return if (canonical.startsWith(destDir.canonicalPath + File.separator)) candidate else null
    }

    /**
     * Project ids become a path segment under filesDir/projects; ids parsed out of imported or
     * peer-received archives must not be able to traverse out of it.
     */
    internal fun isSafeProjectId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 128 && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    private fun ComposeBlendMode.toModelBlendMode(): ModelBlendMode = when (this) {
        ComposeBlendMode.Multiply   -> ModelBlendMode.Multiply
        ComposeBlendMode.Screen     -> ModelBlendMode.Screen
        ComposeBlendMode.Overlay    -> ModelBlendMode.Overlay
        ComposeBlendMode.Darken     -> ModelBlendMode.Darken
        ComposeBlendMode.Lighten    -> ModelBlendMode.Lighten
        ComposeBlendMode.ColorDodge -> ModelBlendMode.ColorDodge
        ComposeBlendMode.ColorBurn  -> ModelBlendMode.ColorBurn
        ComposeBlendMode.Hardlight  -> ModelBlendMode.HardLight
        ComposeBlendMode.Softlight  -> ModelBlendMode.SoftLight
        ComposeBlendMode.Difference -> ModelBlendMode.Difference
        ComposeBlendMode.Exclusion  -> ModelBlendMode.Exclusion
        ComposeBlendMode.Hue        -> ModelBlendMode.Hue
        ComposeBlendMode.Saturation -> ModelBlendMode.Saturation
        ComposeBlendMode.Color      -> ModelBlendMode.Color
        ComposeBlendMode.Luminosity -> ModelBlendMode.Luminosity
        ComposeBlendMode.Clear      -> ModelBlendMode.Clear
        ComposeBlendMode.Src        -> ModelBlendMode.Src
        ComposeBlendMode.Dst        -> ModelBlendMode.Dst
        ComposeBlendMode.DstOver    -> ModelBlendMode.DstOver
        ComposeBlendMode.SrcIn      -> ModelBlendMode.SrcIn
        ComposeBlendMode.DstIn      -> ModelBlendMode.DstIn
        ComposeBlendMode.SrcOut     -> ModelBlendMode.SrcOut
        ComposeBlendMode.DstOut     -> ModelBlendMode.DstOut
        ComposeBlendMode.SrcAtop    -> ModelBlendMode.SrcAtop
        ComposeBlendMode.DstAtop    -> ModelBlendMode.DstAtop
        ComposeBlendMode.Xor        -> ModelBlendMode.Xor
        ComposeBlendMode.Plus       -> ModelBlendMode.Plus
        ComposeBlendMode.Modulate   -> ModelBlendMode.Modulate
        else                        -> ModelBlendMode.SrcOver
    }

    // --- Co-op implementation (Task 17) ---

    /**
     * Returns the ID of the currently open project, or "unknown" if none is loaded.
     */
    fun currentProjectId(): String = projectRepositoryProvider.get().currentProject.value?.id ?: "unknown"

    /**
     * Serialises the current project to bytes for bulk-transfer to a guest device.
     */
    fun serializeCurrentProject(): ByteArray {
        val project = projectRepositoryProvider.get().currentProject.value ?: return ByteArray(0)
        val sourceFolder = File(context.filesDir, "projects/${project.id}")
        if (!sourceFolder.exists()) return ByteArray(0)

        return ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zipFolder(sourceFolder, "", zos)
            }
            baos.toByteArray()
        }
    }

    /**
     * Loads a project received as raw bytes from a host device (spectator/guest path).
     */
    suspend fun loadAsSpectator(bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext

        val extracted = ExtractedArchive()
        try {
            ZipInputStream(bytes.inputStream()).use { zis ->
                // The archive arrived over the co-op wire — id and entry names are untrusted.
                if (!extractArchive(zis, "gxr_spec_", stripTopDir = false, into = extracted)) return@use
                val migrated = installExtracted(context, extracted) ?: return@use
                withContext(Dispatchers.Main) {
                    projectRepositoryProvider.get().createProject(migrated)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ProjectManager", "loadAsSpectator failed", e)
        } finally {
            // Temp files are only moved away on the success path; clear any stragglers
            // left by an early return or an exception.
            extracted.files.values.forEach { if (it.exists()) it.delete() }
        }
    }

    // --- End co-op implementation ---

    private fun zipFolder(folder: File, parentFolder: String, zos: ZipOutputStream) {
        for (file in folder.listFiles() ?: emptyArray()) {
            // Use relative path from the source folder to avoid nested parent directories in the ZIP.
            val zipPath = if (parentFolder.isEmpty()) file.name else "$parentFolder/${file.name}"
            if (file.isDirectory) {
                zipFolder(file, zipPath, zos)
            } else {
                val entry = ZipEntry(zipPath)
                zos.putNextEntry(entry)
                FileInputStream(file).use { fis ->
                    fis.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
    }
}

/** A URI string value in a project.json: `"file://..."`, `"content://..."` and the like. */
private val ARCHIVE_URI = Regex(""""([a-zA-Z][a-zA-Z0-9+.-]*://[^"\\]*)"""")

/**
 * Rewrites every URI in an imported project.json to [localRoot] (`file://…/projects/<id>/`): a
 * `file://` URI naming a file under some `/projects/<anyId>/` keeps its path inside the project and
 * gains this device's root; every other URI becomes empty. `..` segments are refused.
 */
internal fun localizeArchiveUris(projectJson: String, localRoot: String): String =
    ARCHIVE_URI.replace(projectJson) { m ->
        val uri = m.groupValues[1]
        val inProject = Regex("^file://.*?/projects/[^/]+/(.+)$").find(uri)?.groupValues?.get(1)
        val safe = inProject?.takeIf { rest -> rest.split('/').none { it == ".." || it.isEmpty() } }
        "\"" + (if (safe != null) localRoot + safe else "") + "\""
    }

/** The project.json format version that added photo adjustment layers. */
private const val FORMAT_ADJUSTMENT_LAYERS = 2

/** The project.json format version that added the document crop. */
private const val FORMAT_DOCUMENT_CROP = 3

/** Design frames: `OverlayLayer.isFrame` and its fill, radius and clip. */
private const val FORMAT_FRAMES = 4
