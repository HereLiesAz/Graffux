package com.hereliesaz.graffitixr.feature.editor

/**
 * What reading one of a project's files found. "Nothing there" and "there, but it could not be read" are different
 * and must never be mixed up: treating an unreadable file as absent lets the next save replace it, with no backup and
 * no warning.
 */
sealed interface ArtifactRead {
    /** There is no such file (or it is an empty leftover of a torn write): nothing to lose by writing. */
    data object Absent : ArtifactRead

    /** The file's bytes. */
    class Found(val bytes: ByteArray) : ArtifactRead

    /** The file exists but could not be read (an I/O error, an unsafe path, too large, not a regular file). */
    class Unreadable(val reason: String) : ArtifactRead
}
