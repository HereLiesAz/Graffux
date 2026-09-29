package com.hereliesaz.graffitixr.feature.editor.strokedata

import android.os.Build
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject

/** Every raw sample of one pointer (one finger, stylus or palm contact), column by column. */
internal class PointerTrack(val pointerId: Int) {
    val cols = LinkedHashMap<String, MutableList<Number>>().apply { COLUMNS.forEach { put(it, ArrayList()) } }
    var toolType = MotionEvent.TOOL_TYPE_UNKNOWN
    /** Lifted with FLAG_CANCELED, or the whole gesture was cancelled: the system judged it unintended. */
    var canceled = false
    /** Reported as a palm at any point. */
    var palm = false
    var firstTimeNs = 0L
    var lastTimeNs = 0L
    val sampleCount: Int get() = cols.getValue("t").size

    /** Adds pointer [p]'s historical samples and then its current sample, the latter with [action]. */
    fun addPointer(e: MotionEvent, p: Int, action: Int) {
        toolType = e.getToolType(p)
        if (toolType == TOOL_TYPE_PALM) palm = true
        for (h in 0 until e.historySize) addSample(e, p, h, action = MotionEvent.ACTION_MOVE)
        addSample(e, p, CURRENT, action)
    }

    private fun addSample(e: MotionEvent, p: Int, h: Int, action: Int) {
        val historical = h != CURRENT
        fun axis(a: Int) = if (historical) e.getHistoricalAxisValue(a, p, h) else e.getAxisValue(a, p)
        val timeNs = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                if (historical) e.getHistoricalEventTimeNanos(h) else e.eventTimeNanos
            else -> (if (historical) e.getHistoricalEventTime(h) else e.eventTime) * NS_PER_MS
        }
        if (firstTimeNs == 0L) firstTimeNs = timeNs
        lastTimeNs = timeNs
        cols.getValue("t").add(timeNs)
        cols.getValue("action").add(action)
        for ((name, axisId) in AXES) cols.getValue(name).add(axis(axisId))
        cols.getValue("buttons").add(e.buttonState)
    }

    /**
     * The columns as JSON. [clockShiftNs] is added to every `t`: the recorder passes the uptime to
     * elapsedRealtime offset so samples land on the sensors' clock (schema 4).
     */
    fun samplesJson(clockShiftNs: Long = 0L): JSONObject = JSONObject().apply {
        cols.forEach { (k, v) ->
            val shifted = k == "t" && clockShiftNs != 0L
            put(k, if (shifted) JSONArray(v.map { it.toLong() + clockShiftNs }) else JSONArray(v))
        }
    }

    /** A secondary pointer as it appears in a stroke's `pointers` array. */
    fun toJson(clockShiftNs: Long = 0L): JSONObject = JSONObject()
        .put("pointerId", pointerId)
        .put("tool", toolName(toolType))
        .put("canceled", canceled)
        .put("palm", palm)
        .put("samples", samplesJson(clockShiftNs))

    companion object {
        private const val CURRENT = -1
        private const val NS_PER_MS = 1_000_000L

        /**
         * MotionEvent.TOOL_TYPE_PALM: the framework's value for a contact the touch controller
         * classified as a palm. Hidden from the public SDK, but some controllers do report it.
         */
        const val TOOL_TYPE_PALM = 5

        val COLUMNS = listOf(
            "t", "action", "x", "y", "pressure", "size", "touchMajor", "touchMinor",
            "toolMajor", "toolMinor", "orientation", "tilt", "distance", "buttons",
        )

        private val AXES = listOf(
            "x" to MotionEvent.AXIS_X,
            "y" to MotionEvent.AXIS_Y,
            "pressure" to MotionEvent.AXIS_PRESSURE,
            "size" to MotionEvent.AXIS_SIZE,
            "touchMajor" to MotionEvent.AXIS_TOUCH_MAJOR,
            "touchMinor" to MotionEvent.AXIS_TOUCH_MINOR,
            "toolMajor" to MotionEvent.AXIS_TOOL_MAJOR,
            "toolMinor" to MotionEvent.AXIS_TOOL_MINOR,
            "orientation" to MotionEvent.AXIS_ORIENTATION,
            "tilt" to MotionEvent.AXIS_TILT,
            "distance" to MotionEvent.AXIS_DISTANCE,
        )

        private val TOOL_NAMES = mapOf(
            MotionEvent.TOOL_TYPE_FINGER to "finger",
            MotionEvent.TOOL_TYPE_STYLUS to "stylus",
            MotionEvent.TOOL_TYPE_ERASER to "eraser",
            MotionEvent.TOOL_TYPE_MOUSE to "mouse",
            TOOL_TYPE_PALM to "palm",
        )

        fun toolName(toolType: Int): String = TOOL_NAMES[toolType] ?: "unknown"
    }
}

/**
 * One stroke (or one stylus hover approach): the primary pointer -- the one that went down first --
 * plus every other pointer that touched during it, each its own [PointerTrack]. Pointers are
 * followed by pointer id, so a primary that lifts before the others is never confused with them.
 */
internal class StrokeBuilder(val hovering: Boolean, val approach: StrokeBuilder? = null) {
    private val tracks = ArrayList<PointerTrack>()
    private val active = HashMap<Int, PointerTrack>()
    var deviceName = ""
    var cancelled = false
    val primary: PointerTrack? get() = tracks.firstOrNull()
    val sampleCount: Int get() = primary?.sampleCount ?: 0
    val firstTimeNs: Long get() = primary?.firstTimeNs ?: 0L
    val lastTimeNs: Long get() = tracks.maxOfOrNull { it.lastTimeNs } ?: 0L

    fun addEvent(e: MotionEvent) {
        if (e.pointerCount == 0) return
        deviceName = e.device?.name.orEmpty()
        val action = e.actionMasked
        val pointerAction = action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP
        if (pointerAction) {
            // A new contact: always a new track, even when the id is being reused.
            if (action == MotionEvent.ACTION_POINTER_DOWN) open(e.getPointerId(e.actionIndex))
        }
        for (p in 0 until e.pointerCount) {
            val track = active[e.getPointerId(p)] ?: open(e.getPointerId(p))
            val sampleAction = if (pointerAction && p != e.actionIndex) MotionEvent.ACTION_MOVE else action
            track.addPointer(e, p, sampleAction)
        }
        val canceledFlag = e.flags and MotionEvent.FLAG_CANCELED != 0
        when (action) {
            MotionEvent.ACTION_POINTER_UP -> active.remove(e.getPointerId(e.actionIndex))?.let {
                if (canceledFlag) it.canceled = true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelled = true
                active.values.forEach { it.canceled = true }
            }
        }
    }

    private fun open(pointerId: Int): PointerTrack = PointerTrack(pointerId).also {
        tracks.add(it)
        active[pointerId] = it
    }

    /** The stroke as JSON; [clockShiftNs] is added to every sample time (see [PointerTrack.samplesJson]). */
    fun toJson(clockShiftNs: Long = 0L): JSONObject {
        val first = primary ?: PointerTrack(0)
        return JSONObject().apply {
            put("type", if (hovering) "hover" else "stroke")
            put("tool", PointerTrack.toolName(first.toolType))
            put("inputDevice", deviceName)
            put("cancelled", cancelled)
            put("multiTouch", tracks.size > 1)
            put("pointerId", first.pointerId)
            put("canceled", first.canceled)
            put("palm", first.palm)
            put("samples", first.samplesJson(clockShiftNs))
            put("pointers", JSONArray(tracks.drop(1).map { it.toJson(clockShiftNs) }))
            approach?.takeIf { it.lastTimeNs > 0L }?.let { put("hover", it.toJson(clockShiftNs)) }
        }
    }
}
