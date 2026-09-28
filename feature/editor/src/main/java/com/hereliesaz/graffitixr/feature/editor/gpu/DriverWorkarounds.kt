package com.hereliesaz.graffitixr.feature.editor.gpu

/**
 * What a known-bad driver needs changed. Every field defaults to "no change", so an entry names
 * only the knob it turns.
 */
data class WorkaroundEffect(
    /** Don't request GPU timestamp queries (telemetry falls back to CPU wall time). */
    val disableTimestamps: Boolean = false,
    /** Force this stamp workgroup edge (8 or 16) regardless of [WorkgroupPolicy]. */
    val forceStampTile: Int? = null,
    /** Never enable the fp16 path, even when the tier allows it. */
    val disableFp16: Boolean = false,
)

/**
 * One driver-workaround entry: which drivers it applies to and what it changes.
 *
 * Matching: [vendorId] must equal [GpuInfo.vendorId] (0 = any vendor); [rendererContains], when
 * set, must appear in [GpuInfo.renderer] (case-insensitive); and the driver version must fall in
 * [driverVersions]. The version is the Vulkan `driverVersion` the engine reports as hex (see
 * [DriverWorkarounds.driverVersionCode]); its encoding is vendor-specific, so a range is only
 * meaningful together with a [vendorId]. A driver whose version cannot be read never matches a
 * ranged entry.
 */
data class DriverWorkaround(
    val id: String,
    /** Why, with a link to the bug or vendor note. Required: no anonymous workarounds. */
    val reason: String,
    val vendorId: Int = 0,
    val rendererContains: String? = null,
    val driverVersions: LongRange? = null,
    val effect: WorkaroundEffect,
)

/**
 * The driver-workaround table, keyed by vendor and driver-version range. Deliberately empty: no
 * driver bug affecting these engines has been observed yet. Add an entry only with a [reason]
 * that points at evidence (a filed issue, a vendor erratum), and a unit test.
 */
object DriverWorkarounds {
    val table: List<DriverWorkaround> = emptyList()

    /** Entries of [entries] that apply to [info]. */
    fun matching(info: GpuInfo, entries: List<DriverWorkaround> = table): List<DriverWorkaround> =
        entries.filter { matches(it, info) }

    /** All matching effects folded into one; later entries win where two set the same knob. */
    fun effectFor(info: GpuInfo, entries: List<DriverWorkaround> = table): WorkaroundEffect =
        matching(info, entries).fold(WorkaroundEffect()) { acc, w ->
            WorkaroundEffect(
                disableTimestamps = acc.disableTimestamps || w.effect.disableTimestamps,
                forceStampTile = w.effect.forceStampTile ?: acc.forceStampTile,
                disableFp16 = acc.disableFp16 || w.effect.disableFp16,
            )
        }

    fun matches(w: DriverWorkaround, info: GpuInfo): Boolean {
        val vendorOk = w.vendorId == 0 || w.vendorId == info.vendorId
        val rendererOk = w.rendererContains?.let { info.renderer.contains(it, ignoreCase = true) } ?: true
        val range = w.driverVersions
        val versionOk = range == null || driverVersionCode(info)?.let { it in range } == true
        return vendorOk && rendererOk && versionOk
    }

    /** The Vulkan `driverVersion` (reported as `0x...` hex), or null for non-numeric drivers. */
    fun driverVersionCode(info: GpuInfo): Long? {
        val d = info.driver.trim()
        if (!d.startsWith("0x", ignoreCase = true)) return null
        return d.substring(2).toLongOrNull(HEX)
    }

    private const val HEX = 16
}
