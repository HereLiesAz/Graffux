package com.hereliesaz.graffitixr.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.hereliesaz.graffitixr.common.model.ProjectFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A `.fux` file found on the device, with enough read out of it to show a card for it.
 *
 * [thumbnail] is PNG bytes lifted from the archive without extracting the rest of it — a project
 * can be hundreds of megabytes of layer data, and the Open screen only needs the preview.
 */
data class DiscoveredProjectFile(
    val uri: Uri,
    val fileName: String,
    val projectName: String,
    val location: String,
    val lastModified: Long,
    val thumbnail: ByteArray?,
) {
    override fun equals(other: Any?): Boolean = other is DiscoveredProjectFile && other.uri == uri
    override fun hashCode(): Int = uri.hashCode()
}

/**
 * Finds `.fux` project files in the places they plausibly are.
 *
 * "Known locations" is doing real work here, because on modern Android there is no such thing as
 * looking through the whole device. Scoped storage means an app can freely read its own directories
 * and can ask MediaStore about the rest — where it will reliably see the files it wrote itself and
 * may or may not see anyone else's. A project saved to a cloud provider or an SD card through the
 * document picker is not on any filesystem path this can walk.
 *
 * So the scan is best-effort by design, and the Open screen pairs it with a "choose a location"
 * escape hatch rather than pretending an empty result means the user has no projects.
 */
@Singleton
class ProjectFileScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * Every project file the scan can see, newest first.
     *
     * Bounded on both axes: at most [MAX_RESULTS] files, and each one's preview read out with a
     * byte cap. The Open screen is a browsing surface — being fast and approximately complete beats
     * being exhaustive and stalling on a folder full of large archives.
     */
    suspend fun scan(): List<DiscoveredProjectFile> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, DiscoveredProjectFile>()

        // App-owned directories first: always readable, no permission, and where the app's own
        // exports land. These are the results most likely to be what the user is looking for.
        appDirectories().forEach { dir ->
            walk(dir, depth = 0).forEach { file ->
                if (found.size >= MAX_RESULTS) return@forEach
                val entry = read(file) ?: return@forEach
                found[entry.uri.toString()] = entry
            }
        }

        // Then whatever MediaStore is willing to admit to. Deliberately second: a file reachable
        // both ways is better represented by its real path.
        if (found.size < MAX_RESULTS) {
            scanMediaStore().forEach { entry ->
                if (found.size >= MAX_RESULTS) return@forEach
                found.putIfAbsent(entry.uri.toString(), entry)
            }
        }

        found.values.sortedByDescending { it.lastModified }
    }

    /**
     * Reads the name and preview out of a project file the user picked by hand, so a file from a
     * location the scan can't reach still shows as a proper card once chosen.
     */
    suspend fun inspect(uri: Uri, fileName: String?): DiscoveredProjectFile? = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val name = fileName ?: uri.lastPathSegment.orEmpty()
            val peeked = stream.use { peek(it) }
            DiscoveredProjectFile(
                uri = uri,
                fileName = name,
                projectName = peeked.name ?: ProjectFile.projectNameFrom(name),
                location = "Chosen file",
                lastModified = System.currentTimeMillis(),
                thumbnail = peeked.thumbnail,
            )
        } catch (e: Exception) {
            Log.w("ProjectFileScanner", "Couldn't inspect $uri", e)
            null
        }
    }

    /**
     * The directories this app can always read: its own external files area (including the
     * Downloads folder inside it, where the app's own exports go).
     */
    private fun appDirectories(): List<File> = buildList {
        context.getExternalFilesDir(null)?.let { add(it) }
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { add(it) }
        context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.let { add(it) }
        // Before scoped storage the public directories were directly readable, and a project saved
        // by an older build of the app is sitting in one of them.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            listOf(Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS).forEach {
                runCatching { Environment.getExternalStoragePublicDirectory(it) }
                    .getOrNull()?.let { dir -> add(dir) }
            }
        }
    }.filter { it.isDirectory }

    /** Project files under [dir], recursing to [MAX_DEPTH] so a deep tree can't stall the scan. */
    private fun walk(dir: File, depth: Int): List<File> {
        if (depth > MAX_DEPTH) return emptyList()
        val children = try {
            dir.listFiles() ?: return emptyList()
        } catch (e: SecurityException) {
            return emptyList()
        }
        return children.flatMap { child ->
            when {
                child.isDirectory -> walk(child, depth + 1)
                ProjectFile.isProjectFile(child.name) -> listOf(child)
                else -> emptyList()
            }
        }
    }

    private fun read(file: File): DiscoveredProjectFile? = try {
        val peeked = file.inputStream().use { peek(it) }
        DiscoveredProjectFile(
            uri = Uri.fromFile(file),
            fileName = file.name,
            projectName = peeked.name ?: ProjectFile.projectNameFrom(file.name),
            location = file.parentFile?.name.orEmpty().ifBlank { "Device" },
            lastModified = file.lastModified(),
            thumbnail = peeked.thumbnail,
        )
    } catch (e: Exception) {
        // A file that can't be read is one the user can't open either; leaving it off the screen is
        // more honest than showing a card that fails on tap.
        Log.w("ProjectFileScanner", "Skipping unreadable ${file.name}", e)
        null
    }

    private fun scanMediaStore(): List<DiscoveredProjectFile> = try {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Files.getContentUri("external")
        }
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR " +
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%${ProjectFile.DOT_EXTENSION}", "%.${ProjectFile.LEGACY_EXTENSION}")

        val results = mutableListOf<DiscoveredProjectFile>()
        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            while (cursor.moveToNext() && results.size < MAX_RESULTS) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                val name = cursor.getString(nameColumn) ?: continue
                val peeked = try {
                    context.contentResolver.openInputStream(uri)?.use { peek(it) }
                } catch (e: Exception) {
                    null
                } ?: continue
                results += DiscoveredProjectFile(
                    uri = uri,
                    fileName = name,
                    projectName = peeked.name ?: ProjectFile.projectNameFrom(name),
                    // DATE_MODIFIED is seconds since the epoch, unlike File.lastModified().
                    lastModified = cursor.getLong(dateColumn) * 1000L,
                    location = "Device storage",
                    thumbnail = peeked.thumbnail,
                )
            }
        }
        results
    } catch (e: Exception) {
        // A device or OS version that won't answer this query is a reason to show fewer results,
        // never a reason for the Open screen to fail to appear.
        Log.w("ProjectFileScanner", "MediaStore scan unavailable", e)
        emptyList()
    }

    private data class Peeked(val name: String?, val thumbnail: ByteArray?)

    /**
     * Pulls the preview and project name out of an archive without extracting it.
     *
     * Reads entries until both are found and then stops — the thumbnail is usually near the front,
     * and the layer data behind it can be enormous. Each entry read is capped, so a hostile or
     * corrupt archive can't be used to exhaust memory from the Open screen, which runs against
     * files the app never wrote.
     */
    private fun peek(stream: InputStream): Peeked {
        var name: String? = null
        var thumbnail: ByteArray? = null
        ZipInputStream(stream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null && (name == null || thumbnail == null)) {
                val entryName = entry.name.substringAfterLast('/')
                when {
                    !entry.isDirectory && entryName == "thumbnail.png" ->
                        thumbnail = readCapped(zis, MAX_THUMBNAIL_BYTES)
                    !entry.isDirectory && entryName == "project.json" ->
                        name = readCapped(zis, MAX_MANIFEST_BYTES)
                            ?.decodeToString()
                            ?.let(::projectNameFromJson)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return Peeked(name, thumbnail)
    }

    /** Reads the current entry, giving up (null) rather than allocating past [limit]. */
    private fun readCapped(zis: ZipInputStream, limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(32 * 1024)
        while (true) {
            val n = zis.read(chunk)
            if (n < 0) break
            if (out.size() + n > limit) return null
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * The project's `name` field, without deserialising the whole manifest — which would mean
     * parsing an untrusted document into the app's model classes just to put a word on a card, and
     * would fail outright on a manifest from a newer or older schema.
     */
    private fun projectNameFromJson(json: String): String? =
        Regex("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
            ?.groupValues?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?.takeIf { it.isNotBlank() }

    private companion object {
        const val MAX_RESULTS = 60
        const val MAX_DEPTH = 4
        const val MAX_THUMBNAIL_BYTES = 4 * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
    }
}
