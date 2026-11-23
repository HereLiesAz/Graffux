package com.hereliesaz.graffitixr.common.model

/**
 * What a drag on empty canvas draws with the Shape tool ([Tool.FRAME], whose id and
 * ordinal remain stable for saved strokes). The tool and its mode choices are now reached through
 * the Vector group's table; the Design sheet also exposes the same modes (`spec/shape-tool.md`).
 *
 * This is a preference, not project data: it is never written to `project.json`, so it cannot change
 * a file format. [FRAME] is today's Frame tool and the default, so a user who never opens the
 * selector sees the tool they had.
 *
 * [POLYGON] and [STAR] are declared because a glyph exists for each, but they are not offered
 * or drawable until a later phase (see [OFFERED]).
 */
enum class ShapeToolMode {
    FRAME,
    RECTANGLE,
    ROUNDED_RECTANGLE,
    ELLIPSE,
    LINE,
    POLYGON,
    STAR,
    ;

    /** Whether a drag in this mode adds a vector shape rather than a frame. */
    val drawsShape: Boolean get() = this in OFFERED && this != FRAME

    companion object {
        /** The mode a fresh install starts in: the Frame tool as it was. */
        val DEFAULT: ShapeToolMode = FRAME

        /** The modes the selector offers in this build, in the order it lists them. */
        val OFFERED: List<ShapeToolMode> = listOf(FRAME, RECTANGLE, ROUNDED_RECTANGLE, ELLIPSE, LINE)

        /** The stored [name] as a mode; anything unknown, not yet offered or absent is [DEFAULT]. */
        fun fromName(name: String?): ShapeToolMode = OFFERED.firstOrNull { it.name == name } ?: DEFAULT
    }
}
