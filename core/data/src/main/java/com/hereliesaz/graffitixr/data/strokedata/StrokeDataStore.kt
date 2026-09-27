package com.hereliesaz.graffitixr.data.strokedata

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

/**
 * On-device store for stroke-model training data (feature/editor StrokeDataRecorder; schema in
 * tools/stroke-model/SCHEMA.md). Strokes are appended as gzip JSON Lines to the open session file
 * under `filesDir/stroke-data/`; a file is closed and becomes uploadable after
 * [STROKES_PER_FILE] strokes or when a new session starts. Each append is its own gzip member, which
 * concatenated gzip streams allow, so a crash never loses more than the stroke being written.
 */
class StrokeDataStore private constructor(context: Context) {
    private val dir = File(context.filesDir, DIR).apply { mkdirs() }
    private var open: File? = null
    private var strokesInOpen = 0

    init {
        // A file left open by a previous process (killed mid-session) is complete up to its last
        // stroke: close it so it uploads.
        dir.listFiles { f -> f.name.endsWith(OPEN_SUFFIX) }?.forEach(::finalize)
    }

    /** Adds one stroke record; starts a new file (with its session header) when needed. */
    @Synchronized
    fun append(record: JSONObject, sessionHeader: () -> JSONObject) {
        val file = open?.takeIf { strokesInOpen < STROKES_PER_FILE } ?: newFile(sessionHeader())
        writeLine(file, record.put("type", record.optString("type", "stroke")))
        strokesInOpen++
    }

    /** Closes the open file so it can be uploaded; the next stroke starts a new one. */
    @Synchronized
    fun closeSession() {
        open?.let { finalize(it) }
        open = null
        strokesInOpen = 0
    }

    /** Closed files waiting for upload, oldest first. */
    fun pending(): List<File> = dir.listFiles { f -> f.name.endsWith(DONE_SUFFIX) }
        ?.sortedBy { it.name }.orEmpty()

    /** Everything still unsent, open file included (for a manual export). */
    fun allFiles(): List<File> = dir.listFiles()?.sortedBy { it.name }.orEmpty()

    fun strokeCountEstimate(): Int = strokesInOpen

    private fun newFile(header: JSONObject): File {
        open?.let { finalize(it) }
        val file = File(dir, "session-${System.currentTimeMillis()}$OPEN_SUFFIX")
        writeLine(file, header.put("type", "session"))
        open = file
        strokesInOpen = 0
        return file
    }

    private fun finalize(file: File) {
        if (file.exists()) file.renameTo(File(dir, file.name.removeSuffix(OPEN_SUFFIX) + DONE_SUFFIX))
    }

    private fun writeLine(file: File, json: JSONObject) {
        GZIPOutputStream(FileOutputStream(file, true)).use { out ->
            out.write(json.toString().toByteArray())
            out.write('\n'.code)
        }
    }

    companion object {
        const val DIR = "stroke-data"

        @Volatile private var instance: StrokeDataStore? = null

        /** One per process: the editor appends and the uploader drains the same files. */
        fun get(context: Context): StrokeDataStore = instance ?: synchronized(this) {
            instance ?: StrokeDataStore(context.applicationContext).also { instance = it }
        }
        private const val OPEN_SUFFIX = ".jsonl.gz.open"
        private const val DONE_SUFFIX = ".jsonl.gz"
        private const val STROKES_PER_FILE = 100

        /** Device facts for a session header; callers add display and app details. */
        fun deviceHeader(): JSONObject = JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT)
    }
}
