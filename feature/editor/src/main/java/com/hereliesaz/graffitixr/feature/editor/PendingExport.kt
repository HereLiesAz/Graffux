package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.DesignExportFormat
import com.hereliesaz.graffitixr.feature.editor.export.PosterSpec

/** A document export held by the export-frame preview until the user confirms (spec/3d-space.md, "Export"). */
internal sealed interface PendingExport {
    /** The menu's PNG. */
    data object Image : PendingExport

    /** The poster PDF with the user's [spec]. */
    data class Poster(val spec: PosterSpec) : PendingExport

    /** The Export panel's [format] of the document (a PDF of [allFrames] with no frames is the document). */
    data class Design(val format: DesignExportFormat, val allFrames: Boolean) : PendingExport
}
