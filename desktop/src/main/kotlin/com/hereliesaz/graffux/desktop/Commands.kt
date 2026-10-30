package com.hereliesaz.graffux.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JOptionPane

/** macOS takes Cmd where Linux and Windows take Ctrl. */
internal val IsMac: Boolean = System.getProperty("os.name").orEmpty().lowercase().startsWith("mac")

/**
 * A command key: the platform's primary modifier (Cmd on macOS, Ctrl elsewhere) plus [key], with
 * Shift when [shift]. [keyLabel] is how the key itself prints.
 */
internal data class Shortcut(val key: Key, val keyLabel: String, val shift: Boolean = false) {
    /** For the menu bar, which draws it beside the item and binds it. */
    fun keyShortcut(): KeyShortcut = KeyShortcut(key, ctrl = !IsMac, meta = IsMac, shift = shift)

    /** The label a menu row or a rail button's corner shows: ⇧⌘Z on macOS, ⇧^Z elsewhere. */
    val label: String get() = (if (shift) "⇧" else "") + (if (IsMac) "⌘" else "^") + keyLabel

    fun matches(event: KeyEvent): Boolean =
        event.key == key && event.isShiftPressed == shift && !event.isAltPressed &&
            (if (IsMac) event.isMetaPressed && !event.isCtrlPressed else event.isCtrlPressed && !event.isMetaPressed)
}

/** One thing the app can do from a menu (the menu bar and the logo menu list the same ones). */
internal data class Command(
    val label: String,
    val shortcut: Shortcut? = null,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val run: () -> Unit,
)

/** A titled list of [Command]s; null entries are dividers. */
internal data class CommandMenu(val title: String, val items: List<Command?>)

/** The command bound to [event]'s keys, among every menu's enabled commands. */
internal fun List<CommandMenu>.commandFor(event: KeyEvent): Command? =
    asSequence().flatMap { it.items.asSequence() }.filterNotNull().firstOrNull { it.enabled && it.shortcut?.matches(event) == true }

/** Native open/save dialogs (AWT's [FileDialog]: the system dialog on macOS and Windows). */
internal object FileDialogs {
    fun openProject(parent: Frame?): File? =
        show(parent, "Open project", FileDialog.LOAD, null, ProjectFile.EXTENSION)

    fun saveProject(parent: Frame?, suggested: String): File? =
        show(parent, "Save project as", FileDialog.SAVE, suggested, ProjectFile.EXTENSION)?.withExtension(ProjectFile.EXTENSION)

    fun exportPng(parent: Frame?, suggested: String): File? =
        show(parent, "Export PNG", FileDialog.SAVE, suggested, "png")?.withExtension("png")

    private fun show(parent: Frame?, title: String, mode: Int, suggested: String?, extension: String): File? {
        val dialog = FileDialog(parent, title, mode)
        dialog.setFilenameFilter { _, name -> name.endsWith(".$extension", ignoreCase = true) }
        dialog.file = suggested ?: if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "*.$extension" else null
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory ?: "", name)
    }

    private fun File.withExtension(extension: String): File =
        if (name.endsWith(".$extension", ignoreCase = true)) this else File(parentFile, "$name.$extension")

    enum class Unsaved { SAVE, DISCARD, CANCEL }

    /** Asks before unsaved changes to [name] would be lost. */
    fun confirmUnsaved(parent: Frame?, name: String): Unsaved =
        when (
            JOptionPane.showConfirmDialog(
                parent,
                "Save changes to \"$name\" first?",
                "Unsaved changes",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE,
            )
        ) {
            JOptionPane.YES_OPTION -> Unsaved.SAVE
            JOptionPane.NO_OPTION -> Unsaved.DISCARD
            else -> Unsaved.CANCEL
        }

    fun askName(parent: Frame?, current: String): String? =
        JOptionPane.showInputDialog(parent, "Layer name", current)
}

/** Every command key in one place, so the menus and the rail corners agree. */
internal object Shortcuts {
    val New = Shortcut(Key.N, "N")
    val Open = Shortcut(Key.O, "O")
    val Save = Shortcut(Key.S, "S")
    val SaveAs = Shortcut(Key.S, "S", shift = true)
    val ExportPng = Shortcut(Key.E, "E", shift = true)
    val Quit = Shortcut(Key.Q, "Q")
    val Undo = Shortcut(Key.Z, "Z")
    val Redo = Shortcut(Key.Z, "Z", shift = true)
    val NewLayer = Shortcut(Key.N, "N", shift = true)
    val DuplicateLayer = Shortcut(Key.J, "J")
    val MergeDown = Shortcut(Key.E, "E")
    val LayerUp = Shortcut(Key.RightBracket, "]")
    val LayerDown = Shortcut(Key.LeftBracket, "[")
    val Properties = Shortcut(Key.I, "I")
}
