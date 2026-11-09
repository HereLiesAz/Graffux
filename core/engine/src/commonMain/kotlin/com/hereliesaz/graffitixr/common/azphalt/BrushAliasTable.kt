package com.hereliesaz.graffitixr.common.azphalt

/**
 * Where a brush the painterly packs supersede now lives (spec/painterly.md 5.6). Saved projects hold bitmaps,
 * not brush references, so nothing in a project depends on this; it only resolves names the app itself keeps:
 * the carousel's favourites (and their per-item settings, keyed the same way) and the brush picked from the
 * carousel. [resolve] returns the successor when it exists, or null; the callers ([resolveOrDefault],
 * [carouselKeyRenames]) turn null into [BuiltInBrushes.DEFAULT_NAME] ("Round"), so a name that cannot be resolved
 * never leaves a dangling favourite or a brush that selects nothing.
 */
object BrushAliasTable {
    /** A successor brush: the pack it ships in (the id suffix after `painterly.`) and its name there. */
    data class Successor(val pack: String, val brush: String)

    private val aliases: Map<String, Successor> = mapOf(
        // loaded-hair
        "Square Flat" to Successor("acrylic", "Flat"),
        "Rigger" to Successor("acrylic", "Rigger"),
        "Fan Softener" to Successor("oil", "Fan Blender"),
        "Scrub" to Successor("acrylic", "Hog Bristle"),
        "Glaze Mop" to Successor("water", "Mop"),
        "Trowel" to Successor("acrylic", "Palette Knife"),
        // kinetic.painterly
        "Heavy Oil" to Successor("oil", "Hog Bristle"),
        "Matte Gouache" to Successor("acrylic", "Dry Brush"),
        "Cauliflower Bloom" to Successor("water", "Wet-in-Wet Round"),
        "Impasto Knife" to Successor("oil", "Palette Knife"),
    )

    /** The successor for a removed brush [name], or null when [name] is not one of them. */
    fun successorOf(name: String): Successor? = aliases[name]

    val removedNames: Set<String> get() = aliases.keys

    /**
     * Resolves a stored brush [name] to one that exists: itself when [exists] says so, else its successor when
     * that exists, else the default. [exists] answers for `(pack or null for a bundled brush, brush name)`.
     */
    fun resolve(name: String, exists: (pack: String?, brush: String) -> Boolean): Successor? = when {
        exists(null, name) -> Successor("", name)
        else -> aliases[name]?.takeIf { exists(it.pack, it.brush) }
    }

    /** [resolve], with the fallback: the stored [name] if it exists, else its successor, else "Round". */
    fun resolveOrDefault(name: String, exists: (pack: String?, brush: String) -> Boolean): Successor =
        resolve(name, exists) ?: Successor("", BuiltInBrushes.DEFAULT_NAME)

    /**
     * The renames a store of carousel keys needs ([keys]: favourites and per-item settings): every `builtin.<name>`
     * naming a REMOVED brush that no longer exists as a built-in ([existsBuiltIn]) maps to `builtin.<successor>` when
     * the successor is itself a built-in, else to `builtin.Round`. Keys that still resolve, and every other kind of
     * key, are left alone (absent from the result).
     */
    fun carouselKeyRenames(keys: Collection<String>, existsBuiltIn: (String) -> Boolean): Map<String, String> {
        val renames = LinkedHashMap<String, String>()
        for (key in keys) {
            val name = key.removePrefix(BUILTIN_PREFIX).takeIf { key.startsWith(BUILTIN_PREFIX) } ?: continue
            if (name !in aliases || existsBuiltIn(name)) continue
            val successor = aliases[name]?.brush?.takeIf { existsBuiltIn(it) }
            renames[key] = BUILTIN_PREFIX + (successor ?: BuiltInBrushes.DEFAULT_NAME)
        }
        return renames
    }

    private const val BUILTIN_PREFIX = "builtin."
}
