package com.hereliesaz.graffitixr.common.azphalt

/**
 * Where a brush the painterly packs supersede now lives (spec/painterly.md 5.6). Saved projects hold bitmaps,
 * not brush references, so nothing in a project depends on this; it only resolves names the app itself keeps:
 * favourites and the active brush. A name with no alias falls back to [BuiltInBrushes.DEFAULT_NAME].
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
}
