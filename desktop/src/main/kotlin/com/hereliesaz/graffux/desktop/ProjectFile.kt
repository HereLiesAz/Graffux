package com.hereliesaz.graffux.desktop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.prefs.Preferences
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * The desktop project file (`.graffuxd`): a ZIP of `document.properties` (format version, layer
 * order, names, visibility, opacity, the active layer) and one `layers/<index>.png` per layer,
 * bottom first. Desktop-only: Android's `.graffux` projects are written by `core:data`'s
 * `ProjectManager` from the Android `Layer` model, which this app cannot load (see DESKTOP.md).
 */
object ProjectFile {
    const val EXTENSION = "graffuxd"
    const val FORMAT_VERSION = 1
    private const val DOCUMENT = "document.properties"

    /** Writes [snapshot] to [file] through a sibling temp file, so a failed write leaves the old project intact. */
    fun save(snapshot: DocumentSnapshot, file: File) {
        val props = Properties().apply {
            setProperty("format", FORMAT_VERSION.toString())
            setProperty("layers", snapshot.layers.size.toString())
            snapshot.layers.indexOf(snapshot.layers.firstOrNull { it.id == snapshot.activeId }).let { setProperty("active", it.toString()) }
            snapshot.layers.forEachIndexed { i, layer ->
                setProperty("layer.$i.name", layer.name)
                setProperty("layer.$i.visible", layer.visible.toString())
                setProperty("layer.$i.opacity", layer.opacity.toString())
            }
        }
        val target = file.absoluteFile
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.tmp")
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(DOCUMENT))
                props.store(zip, "Graffux desktop project")
                zip.closeEntry()
                snapshot.layers.forEachIndexed { i, layer ->
                    zip.putNextEntry(ZipEntry("layers/$i.png"))
                    val bytes = ByteArrayOutputStream().also { ImageIO.write(layer.image, "png", it) }
                    bytes.writeTo(zip)
                    zip.closeEntry()
                }
            }
            if (target.exists() && !target.delete()) throw IOException("Cannot replace $target")
            if (!temp.renameTo(target)) throw IOException("Cannot write $target")
        } finally {
            temp.delete()
        }
    }

    /** Reads a project written by [save]; throws [IOException] on anything it cannot read. */
    fun load(file: File): DocumentSnapshot {
        var props: Properties? = null
        val images = HashMap<Int, java.awt.image.BufferedImage>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val bytes = zip.readBytes()
                when {
                    entry.name == DOCUMENT -> props = Properties().apply { load(ByteArrayInputStream(bytes)) }
                    entry.name.startsWith("layers/") && entry.name.endsWith(".png") -> {
                        val index = entry.name.removePrefix("layers/").removeSuffix(".png").toIntOrNull() ?: continue
                        ImageIO.read(ByteArrayInputStream(bytes))?.let { images[index] = CanvasState.copyOf(it) }
                    }
                }
            }
        }
        val doc = props ?: throw IOException("Not a Graffux desktop project: ${file.name}")
        val format = doc.getProperty("format")?.toIntOrNull() ?: throw IOException("Missing format version")
        if (format > FORMAT_VERSION) throw IOException("Saved by a newer Graffux (format $format)")
        val count = doc.getProperty("layers")?.toIntOrNull() ?: throw IOException("Missing layer count")
        val layers = (0 until count).map { i ->
            DesktopLayer(
                name = doc.getProperty("layer.$i.name") ?: "Layer ${i + 1}",
                image = images[i] ?: throw IOException("Missing layer image $i"),
                visible = doc.getProperty("layer.$i.visible")?.toBooleanStrictOrNull() ?: true,
                opacity = doc.getProperty("layer.$i.opacity")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f,
            )
        }
        if (layers.isEmpty()) throw IOException("Project has no layers")
        val active = doc.getProperty("active")?.toIntOrNull()?.let { layers.getOrNull(it) } ?: layers.last()
        return DocumentSnapshot(layers, active.id)
    }
}

/** The recently opened or saved projects, newest first, kept in the user's Java preferences. */
class RecentProjects(private val prefs: Preferences = Preferences.userRoot().node("com/hereliesaz/graffux/desktop")) {
    fun list(): List<File> =
        prefs.get(KEY, "").split('\n').filter { it.isNotBlank() }.map(::File).filter { it.isFile }

    fun add(file: File): List<File> = write(listOf(file.absoluteFile) + list().filter { it.absoluteFile != file.absoluteFile })

    fun remove(file: File): List<File> = write(list().filter { it.absoluteFile != file.absoluteFile })

    fun clear(): List<File> = write(emptyList())

    private fun write(files: List<File>): List<File> {
        val kept = files.take(MAX)
        prefs.put(KEY, kept.joinToString("\n") { it.absolutePath })
        runCatching { prefs.flush() }
        return kept
    }

    private companion object {
        const val KEY = "recent"
        const val MAX = 8
    }
}
