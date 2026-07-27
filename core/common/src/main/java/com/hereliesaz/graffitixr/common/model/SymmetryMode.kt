package com.hereliesaz.graffitixr.common.model

/**
 * A stroke symmetry/mirroring guide (Procreate's Drawing Guides § Symmetry). [NONE] draws normally;
 * every other mode mirrors each stroke across one or more axes through the canvas centre so a
 * symmetric shape only needs to be drawn once.
 */
enum class SymmetryMode(val label: String) {
    NONE("Off"),
    VERTICAL("Vertical"),
    HORIZONTAL("Horizontal"),
    QUADRANT("Quadrant"),
    RADIAL_6("Radial (6)"),
}
