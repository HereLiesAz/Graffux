// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/DocumentPresets.kt
package com.hereliesaz.graffitixr.feature.editor

/**
 * A named artboard / document size. [width]/[height] are in pixels; a value of 0 for both marks the
 * "Custom" entry, which the size picker turns into editable fields seeded from the current document.
 */
data class DocumentPreset(val label: String, val width: Int, val height: Int) {
    val isCustom: Boolean get() = width == 0 && height == 0
}

/**
 * The built-in artboard presets offered by the size picker — common social, print, and screen sizes.
 * Ordered roughly square → portrait → landscape, ending with Custom.
 */
val DOCUMENT_PRESETS: List<DocumentPreset> = listOf(
    DocumentPreset("Square", 1080, 1080),
    DocumentPreset("Portrait 4:5", 1080, 1350),
    DocumentPreset("Story 9:16", 1080, 1920),
    DocumentPreset("Landscape 16:9", 1920, 1080),
    DocumentPreset("Wide 3:2", 1620, 1080),
    DocumentPreset("Print A4", 2480, 3508),
    DocumentPreset("Custom", 0, 0),
)
