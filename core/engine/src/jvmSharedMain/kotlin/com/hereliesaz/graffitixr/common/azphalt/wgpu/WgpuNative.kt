package com.hereliesaz.graffitixr.common.azphalt.wgpu

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JNI entry points of libgraffux_wgpu (core/wgpu-engine/src/jni_api.rs). Handles are native
 * pointers; 0 means "no engine". Use [WgpuStampEngine], not this, from app code.
 */
@Suppress("LongParameterList", "TooManyFunctions")
internal object WgpuNative {
    @JvmStatic external fun nativeCreate(width: Int, height: Int, backend: Int): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeClear(handle: Long): Boolean
    @JvmStatic external fun nativeUpload(handle: Long, rgba: ByteArray): Boolean
    @JvmStatic external fun nativeUploadRows(handle: Long, rgba: ByteArray, y: Int, rows: Int): Boolean
    @JvmStatic external fun nativeUploadSubstrateHeight(handle: Long, data: ByteArray, width: Int, height: Int): Boolean
    @JvmStatic external fun nativeUploadPaintHeight(handle: Long, data: FloatArray, width: Int, height: Int): Boolean

    @JvmStatic external fun nativeStampDabs(
        handle: Long,
        dabs: FloatArray,
        colorArgb: Int,
        hardness: Float,
        buildUp: Boolean,
        substrate: FloatArray?,
        strokeMax: Boolean,
    ): Boolean

    @JvmStatic external fun nativeStampMaskedDabs(
        handle: Long,
        dabs: FloatArray,
        colorArgb: Int,
        hardness: Float,
        mask: ByteArray,
        maskWidth: Int,
        maskHeight: Int,
        grain: ByteArray?,
        grainWidth: Int,
        grainHeight: Int,
        grainCanvasLocked: Boolean,
        grainScale: Float,
        grainPhaseX: Float,
        grainPhaseY: Float,
        secondaryDabs: FloatArray?,
        secondaryMask: ByteArray?,
        secondaryWidth: Int,
        secondaryHeight: Int,
        substrate: FloatArray?,
    ): Boolean

    @JvmStatic external fun nativeColorSmudge(
        handle: Long,
        dabs: FloatArray,
        mode: Int,
        radiusPx: Float,
        feathering: Float,
        smearAlpha: Boolean,
        paintColorArgb: Int,
        dilution: Float,
        sampleSource: ByteArray?,
        sampleWidth: Int,
        sampleHeight: Int,
    ): Boolean

    @JvmStatic external fun nativeReadback(handle: Long, out: ByteArray): Boolean
    @JvmStatic external fun nativeAdapterDescription(handle: Long): String
}

/**
 * Loads libgraffux_wgpu once per process. Tried in order:
 * 1. the file named by the `graffux.wgpu.library` system property (development, tests);
 * 2. `System.loadLibrary("graffux_wgpu")` -- Android, where Gradle packages it in the APK, or a
 *    desktop install that put it on `java.library.path`;
 * 3. the copy the desktop build bundles as a classpath resource under `native/<os>-<arch>/`,
 *    extracted to a temp file first because a library cannot be loaded from inside a jar.
 * Every failure is swallowed: [load] returning false just means "use the CPU path".
 */
object WgpuLibrary {
    private const val NAME = "graffux_wgpu"

    @Volatile private var state: Boolean? = null

    /** Why the last [load] failed, for logs and the desktop's engine label. */
    @Volatile var failure: String? = null
        private set

    @Synchronized
    fun load(): Boolean {
        state?.let { return it }
        val loaded = tryProperty() || tryLibraryPath() || tryResource()
        state = loaded
        return loaded
    }

    private fun attempt(what: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: UnsatisfiedLinkError) {
        failure = "$what: ${e.message}"
        false
    } catch (e: SecurityException) {
        failure = "$what: ${e.message}"
        false
    } catch (e: java.io.IOException) {
        failure = "$what: ${e.message}"
        false
    }

    private fun tryProperty(): Boolean {
        val path = System.getProperty("graffux.wgpu.library") ?: return false
        return File(path).isFile && attempt(path) { System.load(File(path).absolutePath) }
    }

    private fun tryLibraryPath(): Boolean = attempt("loadLibrary") { System.loadLibrary(NAME) }

    /** `linux-x86_64`, `windows-x86_64`, `macos-aarch64`, ... -- matches desktop/build.gradle.kts. */
    fun platformDirectory(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val osName = when {
            os.startsWith("windows") -> "windows"
            os.startsWith("mac") -> "macos"
            else -> "linux"
        }
        val arch = when (val a = System.getProperty("os.arch").orEmpty().lowercase()) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> a
        }
        return "$osName-$arch"
    }

    private fun tryResource(): Boolean {
        val fileName = System.mapLibraryName(NAME)
        val stream = WgpuLibrary::class.java.getResourceAsStream("/native/${platformDirectory()}/$fileName")
            ?: return false
        return attempt("resource") {
            val dir = Files.createTempDirectory("graffux-wgpu")
            val target = dir.resolve(fileName)
            stream.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
            target.toFile().deleteOnExit()
            dir.toFile().deleteOnExit()
            System.load(target.toAbsolutePath().toString())
        }
    }
}
