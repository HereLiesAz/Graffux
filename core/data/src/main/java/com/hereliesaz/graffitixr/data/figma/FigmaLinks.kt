package com.hereliesaz.graffitixr.data.figma

/**
 * Turning what a user actually has (a link copied out of Figma) into what the API needs (a file key),
 * and a document tree into the flat frame list the picker shows. Pure — no network, no Android.
 */
object FigmaLinks {

    /**
     * Extracts the file key from a Figma URL, or returns [input] unchanged when it already looks like
     * a bare key. Figma has used several path prefixes over the years (`/file/`, `/design/`,
     * `/proto/`, `/board/`), all with the key in the same position, so this matches on position after
     * a known prefix rather than hardcoding one.
     *
     * Returns null when there's nothing key-shaped to extract.
     */
    fun fileKey(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        if (!trimmed.contains("/")) {
            return trimmed.takeIf { it.isNotEmpty() && it.all(::isKeyChar) }
        }

        // Strip scheme + query/fragment, then find the segment right after a known prefix.
        val withoutScheme = trimmed.substringAfter("://", trimmed)
        val path = withoutScheme.substringBefore('?').substringBefore('#')
        val segments = path.split('/').filter { it.isNotEmpty() }
        val prefixIndex = segments.indexOfFirst { it in KEY_PREFIXES }
        if (prefixIndex == -1 || prefixIndex + 1 >= segments.size) return null
        return segments[prefixIndex + 1].takeIf { it.isNotEmpty() && it.all(::isKeyChar) }
    }

    /**
     * Flattens a document into the frames worth offering: the renderable top-level children of each
     * page, tagged with their page name so two frames called "Cover" stay distinguishable.
     *
     * Only [RENDERABLE_TYPES] are offered — a page's stray vector or text node renders fine but isn't
     * what "import a frame" means, and listing every node would bury the frames the user came for.
     */
    fun frames(document: FigmaNode?): List<FigmaFrame> {
        val pages = document?.children.orEmpty()
        return pages.flatMap { page ->
            page.children
                .filter { it.type in RENDERABLE_TYPES }
                .mapNotNull { node ->
                    val box = node.absoluteBoundingBox
                    // A zero-area node can't be rendered by Figma and would just fail downstream.
                    if (box == null || box.width <= 0f || box.height <= 0f) return@mapNotNull null
                    FigmaFrame(
                        id = node.id,
                        name = node.name.ifBlank { "Untitled" },
                        pageName = page.name.ifBlank { "Page" },
                        width = box.width,
                        height = box.height,
                    )
                }
        }
    }

    private val KEY_PREFIXES = setOf("file", "design", "proto", "board", "slides", "make")

    private val RENDERABLE_TYPES = setOf("FRAME", "COMPONENT", "COMPONENT_SET", "INSTANCE", "GROUP", "SECTION")

    private fun isKeyChar(c: Char) = c.isLetterOrDigit() || c == '-' || c == '_'
}
