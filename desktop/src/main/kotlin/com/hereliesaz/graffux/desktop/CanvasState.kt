package com.hereliesaz.graffux.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.AlphaComposite
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.imageio.ImageIO

/**
 * One raster layer of the desktop document. Immutable: every edit makes a new [image] (a stroke
 * renders a fresh frame anyway), so a history entry is just the list of layers it had -- no pixel
 * copies. Desktop-local: the Android `Layer` model lives in `core:common`, an Android library this
 * plain-JVM app cannot depend on (see DESKTOP.md).
 */
data class DesktopLayer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val image: BufferedImage,
    val visible: Boolean = true,
    val opacity: Float = 1f,
)

/** The whole document at one point in history: its layers, bottom first, and which is active. */
data class DocumentSnapshot(val layers: List<DesktopLayer>, val activeId: String?)

/**
 * Hoisted document state, shared between [DesktopStampCanvas] (which paints into the active layer)
 * and the chrome (layers rail, properties, menus). History is a stack of [DocumentSnapshot]s; since
 * layers are immutable each snapshot shares every unchanged layer's pixels with its neighbours.
 */
class CanvasState {
    var layers by mutableStateOf<List<DesktopLayer>>(emptyList())
        private set
    var activeLayerId by mutableStateOf<String?>(null)
        private set

    /** The snapshot last written to (or read from) disk; the document is dirty while it differs. */
    private var savedSnapshot by mutableStateOf<DocumentSnapshot?>(null)

    private val undoStack = mutableStateListOf<DocumentSnapshot>()
    private val redoStack = mutableStateListOf<DocumentSnapshot>()

    /** Set while a continuous edit (an opacity drag) has already recorded its one undo step. */
    private var continuousEditOpen = false

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val activeLayer: DesktopLayer? get() = layers.firstOrNull { it.id == activeLayerId }
    val isDirty: Boolean get() = snapshot() != savedSnapshot && layers.isNotEmpty()

    /** The active layer's pixels: what a stroke paints on top of. */
    val committed: BufferedImage? get() = activeLayer?.image

    private fun snapshot() = DocumentSnapshot(layers, activeLayerId)

    private fun restore(s: DocumentSnapshot) {
        layers = s.layers
        activeLayerId = s.activeId
    }

    /** Records the current document as one undo step, then applies [edit]. A fresh edit drops redo. */
    private inline fun edit(edit: () -> Unit) {
        undoStack.add(snapshot())
        redoStack.clear()
        continuousEditOpen = false
        edit()
    }

    private fun replaceLayer(id: String, transform: (DesktopLayer) -> DesktopLayer) {
        layers = layers.map { if (it.id == id) transform(it) else it }
    }

    /** Called once per finished stroke (see [DesktopStampCanvas]'s `onEnd`): [frame] replaces the
     *  active layer's pixels as one undo step. */
    fun commitStroke(frame: BufferedImage) {
        val id = activeLayerId
        if (id == null) {
            // No document yet (a test, or a stroke before the first layout): it becomes layer 1.
            val layer = DesktopLayer(name = "Layer 1", image = frame)
            layers = listOf(layer)
            activeLayerId = layer.id
            return
        }
        edit { replaceLayer(id) { it.copy(image = frame) } }
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.add(snapshot())
        continuousEditOpen = false
        restore(previous)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.add(snapshot())
        continuousEditOpen = false
        restore(next)
    }

    /**
     * Makes every layer [width] x [height] (pixels outside are cropped, new area is clear), creating
     * the first layer when there is none. Not an undo step: the canvas calls it on layout, on a window
     * resize and after an undo, open or redo that brought back layers of another size.
     */
    fun ensureSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (layers.isEmpty()) {
            val layer = DesktopLayer(name = "Layer 1", image = blank(width, height))
            layers = listOf(layer)
            activeLayerId = layer.id
            savedSnapshot = snapshot()
            return
        }
        if (layers.all { it.image.width == width && it.image.height == height }) return
        val wasClean = !isDirty
        layers = layers.map { layer ->
            if (layer.image.width == width && layer.image.height == height) {
                layer
            } else {
                layer.copy(image = blank(width, height).also { fresh -> fresh.createGraphics().apply { drawImage(layer.image, 0, 0, null); dispose() } })
            }
        }
        if (wasClean) savedSnapshot = snapshot()
    }

    /** Wipes the active layer back to clear, as one undo step. */
    fun clear() {
        val layer = activeLayer ?: return
        edit { replaceLayer(layer.id) { it.copy(image = blank(layer.image.width, layer.image.height)) } }
    }

    // ---- layers ---------------------------------------------------------------------------------

    fun setActive(id: String) {
        if (layers.any { it.id == id }) activeLayerId = id
    }

    /** Adds a clear layer above the active one and makes it active. */
    fun addLayer() {
        val reference = layers.firstOrNull()?.image ?: return
        val layer = DesktopLayer(name = nextLayerName(), image = blank(reference.width, reference.height))
        edit {
            val at = layers.indexOfFirst { it.id == activeLayerId }.let { if (it < 0) layers.size else it + 1 }
            layers = layers.toMutableList().apply { add(at, layer) }
            activeLayerId = layer.id
        }
    }

    fun duplicateLayer(id: String) {
        val index = layers.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val source = layers[index]
        val copy = source.copy(id = UUID.randomUUID().toString(), name = "${source.name} copy", image = copyOf(source.image))
        edit {
            layers = layers.toMutableList().apply { add(index + 1, copy) }
            activeLayerId = copy.id
        }
    }

    /** Deletes [id]; the last layer stays (cleared instead), so there is always one to paint on. */
    fun deleteLayer(id: String) {
        val index = layers.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        if (layers.size == 1) {
            setActive(id)
            clear()
            return
        }
        edit {
            layers = layers.filterNot { it.id == id }
            if (activeLayerId == id) activeLayerId = layers[(index - 1).coerceAtLeast(0)].id
        }
    }

    /** Moves [id] [by] places up (positive, toward the top) or down the stack. */
    fun moveLayer(id: String, by: Int) {
        val from = layers.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val to = (from + by).coerceIn(0, layers.lastIndex)
        if (to == from) return
        edit { layers = layers.toMutableList().apply { add(to, removeAt(from)) } }
    }

    /** Puts [id] at [index] (bottom-first), as a drag-reorder in the rail does. */
    fun moveLayerTo(id: String, index: Int) {
        val from = layers.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        moveLayer(id, index.coerceIn(0, layers.lastIndex) - from)
    }

    /** Draws the active layer onto the one below it and removes it. */
    fun mergeDown(id: String) {
        val index = layers.indexOfFirst { it.id == id }.takeIf { it > 0 } ?: return
        val top = layers[index]
        val below = layers[index - 1]
        val merged = copyOf(below.image).also { drawLayer(it, top) }
        edit {
            layers = layers.toMutableList().apply {
                set(index - 1, below.copy(image = merged))
                removeAt(index)
            }
            activeLayerId = below.id
        }
    }

    fun setVisible(id: String, visible: Boolean) {
        if (layers.firstOrNull { it.id == id }?.visible == visible) return
        edit { replaceLayer(id) { it.copy(visible = visible) } }
    }

    fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || layers.firstOrNull { it.id == id }?.name == trimmed) return
        edit { replaceLayer(id) { it.copy(name = trimmed) } }
    }

    /** An opacity drag: the first change records one undo step, later ones join it until [endContinuousEdit]. */
    fun setOpacity(id: String, opacity: Float) {
        val value = opacity.coerceIn(0f, 1f)
        if (!continuousEditOpen) {
            edit { }
            continuousEditOpen = true
        }
        replaceLayer(id) { it.copy(opacity = value) }
    }

    fun endContinuousEdit() {
        continuousEditOpen = false
    }

    private fun nextLayerName(): String {
        val used = layers.mapNotNull { Regex("""Layer (\d+)""").matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }
        return "Layer ${(used.maxOrNull() ?: 0) + 1}"
    }

    // ---- documents ------------------------------------------------------------------------------

    /** Starts an empty document (one clear layer once the canvas knows its size); history is dropped. */
    fun newDocument() {
        val reference = layers.firstOrNull()?.image
        layers = emptyList()
        activeLayerId = null
        undoStack.clear()
        redoStack.clear()
        continuousEditOpen = false
        if (reference != null) ensureSize(reference.width, reference.height)
        savedSnapshot = snapshot()
    }

    /** Replaces the document with [snapshot] (an opened project); history is dropped. */
    fun load(snapshot: DocumentSnapshot) {
        restore(snapshot)
        undoStack.clear()
        redoStack.clear()
        continuousEditOpen = false
        savedSnapshot = snapshot
    }

    /** What to write to disk; call [markSaved] with it once the write succeeded. */
    fun currentSnapshot(): DocumentSnapshot = snapshot()

    fun markSaved(snapshot: DocumentSnapshot) {
        savedSnapshot = snapshot
    }

    // ---- compositing ----------------------------------------------------------------------------

    /** Every visible layer drawn bottom-up with its opacity, or null with no layers. */
    fun flatten(): BufferedImage? = composite(layers)

    /** The visible layers under the active one, flattened (null when there are none). */
    fun compositeBelowActive(): BufferedImage? = layers.indexOfFirst { it.id == activeLayerId }.let { i ->
        if (i <= 0) null else composite(layers.subList(0, i))
    }

    /** The visible layers over the active one, flattened (null when there are none). */
    fun compositeAboveActive(): BufferedImage? = layers.indexOfFirst { it.id == activeLayerId }.let { i ->
        if (i < 0 || i == layers.lastIndex) null else composite(layers.subList(i + 1, layers.size))
    }

    /** Exports the flattened canvas as a timestamped PNG under [directory] (created if missing) and
     *  returns the file written, or `null` if there's nothing to export yet. */
    fun exportPng(directory: File = File(System.getProperty("user.home"), "Graffux")): File? =
        exportPng(directory) { image, file -> ImageIO.write(image, "png", file) }

    /** [exportPng] with the encoder injectable, so a test can make it fail. If [write] throws, the
     *  name [reserveUniqueFile] claimed is deleted rather than left behind as a zero-byte file. */
    internal fun exportPng(directory: File, write: (BufferedImage, File) -> Unit): File? {
        val frame = flatten() ?: return null
        directory.mkdirs()
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = reserveUniqueFile(directory, "graffux-$timestamp")
        var written = false
        try {
            write(frame, file)
            written = true
        } finally {
            if (!written) file.delete()
        }
        return file
    }

    /** Writes the flattened canvas to exactly [file] (Export PNG's chosen path). */
    fun exportPngTo(file: File): Boolean {
        val frame = flatten() ?: return false
        file.absoluteFile.parentFile?.mkdirs()
        return ImageIO.write(frame, "png", file)
    }

    /** The timestamp only has one-second resolution, so a second export in the same second would
     *  overwrite the first. Claims `base.png`, else `base-2.png`, `base-3.png`, ... -- atomically,
     *  via [File.createNewFile], so two exports can never be handed the same name. */
    private fun reserveUniqueFile(directory: File, base: String): File {
        var n = 1
        while (true) {
            val name = if (n == 1) "$base.png" else "$base-$n.png"
            val candidate = File(directory, name)
            if (candidate.createNewFile()) return candidate
            n++
        }
    }

    internal companion object {
        fun blank(width: Int, height: Int) = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

        fun copyOf(image: BufferedImage): BufferedImage =
            blank(image.width, image.height).also { it.createGraphics().apply { drawImage(image, 0, 0, null); dispose() } }

        fun drawLayer(target: BufferedImage, layer: DesktopLayer) {
            if (!layer.visible || layer.opacity <= 0f) return
            target.createGraphics().apply {
                composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, layer.opacity)
                drawImage(layer.image, 0, 0, null)
                dispose()
            }
        }

        fun composite(layers: List<DesktopLayer>): BufferedImage? {
            val first = layers.firstOrNull()?.image ?: return null
            val out = blank(first.width, first.height)
            layers.forEach { drawLayer(out, it) }
            return out
        }
    }
}
