package com.hereliesaz.graffitixr.common.util

import java.util.Base64

/** A ProCraft-style palette: stable identity, name, and thirty independently addressable swatches. */
data class NamedPalette(val id: String, val name: String, val slots: List<Int?>) {
    companion object {
        const val SLOT_COUNT = 30
        const val MAX_NAME = 80
    }
}

/** Null activeId means the original saved-colour list; it is never copied, truncated or deleted. */
data class NamedPaletteBook(val palettes: List<NamedPalette> = emptyList(), val activeId: String? = null) {
    val active: NamedPalette? get() = palettes.firstOrNull { it.id == activeId }

    /** Apply an edit to the latest persisted book, not to a snapshot captured by the UI. */
    fun edit(action: PaletteEdit, newId: String): NamedPaletteBook? {
        fun validName(name: String) = name.trim().takeIf { it.isNotEmpty() && it.length <= NamedPalette.MAX_NAME }
        fun append(name: String, slots: List<Int?>, after: Int = palettes.lastIndex): NamedPaletteBook? {
            if (palettes.size >= MAX_PALETTES || !validId(newId) || palettes.any { it.id == newId }) return null
            return copy(palettes = palettes.toMutableList().apply { add(after + 1, NamedPalette(newId, name, slots)) },
                activeId = newId)
        }
        fun change(id: String, transform: (NamedPalette) -> NamedPalette?): NamedPaletteBook? {
            val index = palettes.indexOfFirst { it.id == id }
            if (index < 0) return null
            val replacement = transform(palettes[index]) ?: return null
            return copy(palettes = palettes.toMutableList().apply { set(index, replacement) })
        }
        return when (action) {
            is PaletteEdit.Create -> append(validName(action.name) ?: return null, List(NamedPalette.SLOT_COUNT) { null })
            is PaletteEdit.Duplicate -> {
                val index = palettes.indexOfFirst { it.id == action.id }
                val source = palettes.getOrNull(index) ?: return null
                append(source.name.take(NamedPalette.MAX_NAME - 5) + " copy", source.slots.toList(), index)
            }
            is PaletteEdit.Rename -> { val name = validName(action.name) ?: return null; change(action.id) { it.copy(name = name) } }
            is PaletteEdit.Select -> if (action.id == null || palettes.any { it.id == action.id }) copy(activeId = action.id) else null
            is PaletteEdit.Delete -> if (palettes.none { it.id == action.id }) null else
                copy(palettes = palettes.filterNot { it.id == action.id }, activeId = activeId.takeUnless { it == action.id })
            is PaletteEdit.SetSlot -> change(action.id) { palette ->
                if (action.index !in palette.slots.indices) null else palette.copy(slots = palette.slots.toMutableList().apply {
                    set(action.index, action.argb?.or(0xff000000.toInt()))
                })
            }
        }
    }

    companion object {
        const val MAX_PALETTES = 64
        internal fun validId(id: String) = id.length in 1..64 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
    }
}

/** Identity-based operations prevent an edit from targeting a shifted list index. */
sealed interface PaletteEdit {
    data class Create(val name: String) : PaletteEdit
    data class Duplicate(val id: String) : PaletteEdit
    data class Rename(val id: String, val name: String) : PaletteEdit
    data class Select(val id: String?) : PaletteEdit
    data class Delete(val id: String) : PaletteEdit
    data class SetSlot(val id: String, val index: Int, val argb: Int?) : PaletteEdit
}

/**
 * Versioned preference encoding. Names are UTF-8 base64, slots are exact eight-digit ARGB
 * or "-" (empty), and IDs are restricted ASCII. Null means unreadable, never an empty book.
 * Strict decoding prevents a subsequent edit from silently discarding a damaged collection.
 */
object NamedPaletteCodec {
    private const val VERSION = "1"
    private const val MAX_TEXT = 128 * 1024

    fun encode(book: NamedPaletteBook): String = buildString {
        append(VERSION).append('|').append(book.activeId.orEmpty())
        for (palette in book.palettes) {
            append('\n').append(palette.id).append('|')
            append(Base64.getEncoder().encodeToString(palette.name.toByteArray(Charsets.UTF_8))).append('|')
            append(palette.slots.joinToString(",") { it?.toUInt()?.toString(16)?.padStart(8, '0') ?: "-" })
        }
    }

    fun decode(raw: String?): NamedPaletteBook? {
        if (raw == null) return NamedPaletteBook()
        if (raw.length > MAX_TEXT) return null
        return runCatching {
            val lines = raw.split('\n')
            require(lines.size <= NamedPaletteBook.MAX_PALETTES + 1)
            val header = lines.first().split('|')
            require(header.size == 2 && header[0] == VERSION)
            val active = header[1].ifEmpty { null }
            val palettes = lines.drop(1).map { line ->
                val parts = line.split('|')
                require(parts.size == 3 && NamedPaletteBook.validId(parts[0]))
                val bytes = Base64.getDecoder().decode(parts[1])
                val name = bytes.decodeToString(throwOnInvalidSequence = true)
                require(name.isNotBlank() && name.length <= NamedPalette.MAX_NAME && name == name.trim())
                val slots = parts[2].split(',')
                require(slots.size == NamedPalette.SLOT_COUNT)
                NamedPalette(parts[0], name, slots.map {
                    if (it == "-") null else {
                        require(it.length == 8 && it.all { c -> c in '0'..'9' || c in 'a'..'f' })
                        it.toUInt(16).toInt().also { color -> require(color ushr 24 == 255) }
                    }
                })
            }
            require(palettes.map { it.id }.toSet().size == palettes.size)
            require(active == null || palettes.any { it.id == active })
            NamedPaletteBook(palettes, active)
        }.getOrNull()
    }
}
