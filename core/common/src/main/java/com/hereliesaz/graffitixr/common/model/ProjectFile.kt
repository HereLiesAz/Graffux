package com.hereliesaz.graffitixr.common.model

/**
 * The `.fux` project file — what a Graffux document is when it leaves the app.
 *
 * A `.fux` is a zip holding `project.json` plus every artifact the project references (layer
 * bitmaps, thumbnail, SLAM map). Saving one is a complete, portable copy: hand it to someone else
 * or move it between devices and the project opens exactly as it was left.
 *
 * The naming rules below are the fiddly part and the reason this is a separate, pure object — a
 * name the user typed goes on to become a real filename on a filesystem nobody chose (a `.fux` on
 * an SD card lands on FAT32; one synced to a desktop lands on NTFS), and the failure mode of
 * getting it wrong is the save silently not happening.
 */
object ProjectFile {

    /** The extension, without the dot. */
    const val EXTENSION = "fux"

    /** With the dot, since that's what most call sites actually want to append or strip. */
    const val DOT_EXTENSION = ".$EXTENSION"

    /**
     * `.fux` has no registered MIME type, so a generic binary one is what SAF and the share sheet
     * get. Claiming `application/zip` would be truer to the container but wrong in effect: file
     * managers would offer to unzip it, and the picker would then also list every ordinary archive
     * on the device as if it were a project.
     */
    const val MIME_TYPE = "application/octet-stream"

    /** Superseded by [EXTENSION]; still opened so projects saved by older builds keep working. */
    const val LEGACY_EXTENSION = "gxr"

    /** Used when the name is blank or is entirely made of characters a filesystem won't take. */
    const val FALLBACK_NAME = "Untitled"

    /**
     * Characters no filesystem the app can plausibly write to will accept. `/` is the Unix path
     * separator, `\` and `:` the Windows ones, and the rest are reserved by Windows/FAT — which
     * matters because a `.fux` saved to a cloud provider or SD card ends up on exactly those.
     */
    private val ILLEGAL = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    /**
     * The maximum length of the name portion. Filesystems generally cap a whole filename at 255
     * bytes; leaving room for the extension and a `(1)` de-duplication suffix that a document
     * provider may add keeps a long title from being silently truncated into a different file.
     */
    private const val MAX_NAME_LENGTH = 120

    /**
     * The filename to offer the user for a project called [projectName] — a sanitised name with
     * [DOT_EXTENSION] on the end, never blank, never already-doubled.
     */
    fun suggestedFileName(projectName: String?): String = sanitizeName(projectName) + DOT_EXTENSION

    /**
     * The project name inside [fileName]: any directory prefix and known project extension removed.
     * The inverse of [suggestedFileName], so opening a file and saving it again round-trips the
     * name rather than accreting extensions.
     */
    fun projectNameFrom(fileName: String?): String {
        val bare = fileName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val withoutExtension = when {
            bare.endsWith(DOT_EXTENSION, ignoreCase = true) ->
                bare.dropLast(DOT_EXTENSION.length)
            bare.endsWith(".$LEGACY_EXTENSION", ignoreCase = true) ->
                bare.dropLast(LEGACY_EXTENSION.length + 1)
            else -> bare
        }
        return sanitizeName(withoutExtension)
    }

    /** Whether [fileName] looks like a project file this app can open. */
    fun isProjectFile(fileName: String?): Boolean {
        val name = fileName.orEmpty()
        return name.endsWith(DOT_EXTENSION, ignoreCase = true) ||
            name.endsWith(".$LEGACY_EXTENSION", ignoreCase = true)
    }

    /**
     * A user-typed name reduced to something a filesystem will accept, keeping as much of the
     * original as possible — a name that survives recognisably is worth more than a strict one.
     */
    fun sanitizeName(raw: String?): String {
        val cleaned = raw.orEmpty()
            // Control characters are rejected outright by some providers and invisible in the UI,
            // so they'd produce a failure with nothing on screen to explain it.
            .filter { it.code >= 0x20 && it !in ILLEGAL }
            .trim()
            // Windows silently strips trailing dots and spaces, which would make the name the app
            // thinks it saved and the name on disk disagree.
            .trimEnd('.', ' ')
            .take(MAX_NAME_LENGTH)
            .trimEnd('.', ' ')
        return cleaned.ifBlank { FALLBACK_NAME }
    }
}
