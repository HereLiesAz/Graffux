from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


# EditorScreen: transform geometry only. Sensor values stay in physical hand/screen space.
p = Path("feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorScreen.kt")
s = p.read_text()
if "import com.hereliesaz.graffitixr.common.azphalt.BrushSample\n" not in s:
    s = replace_once(
        s,
        "import androidx.compose.ui.geometry.Offset\n",
        "import androidx.compose.ui.geometry.Offset\nimport com.hereliesaz.graffitixr.common.azphalt.BrushSample\n",
        "EditorScreen BrushSample import",
    )
s = replace_once(
    s,
    """            fun toWorld(screen: Offset) = CanvasHitTest.screenToWorld(
                screen, uiState.viewportOffset, uiState.viewportZoom, uiState.viewportRotation,
            )
            DrawingCanvas(
""",
    """            fun toWorld(screen: Offset) = CanvasHitTest.screenToWorld(
                screen, uiState.viewportOffset, uiState.viewportZoom, uiState.viewportRotation,
            )
            fun toWorldSample(sample: BrushSample): BrushSample {
                val world = toWorld(Offset(sample.x, sample.y))
                return sample.copy(x = world.x, y = world.y)
            }
            DrawingCanvas(
""",
    "EditorScreen world sample helper",
)
s = replace_once(
    s,
    """                onStrokeStart = { offset, size, pressure -> vm.onStrokeStart(toWorld(offset), size, pressure) },
                onStrokePoint = { offset, pressure -> vm.onStrokePoint(toWorld(offset), pressure) },
""",
    """                onStrokeStart = { sample, size -> vm.onStrokeStart(toWorldSample(sample), size) },
                onStrokePoint = { sample -> vm.onStrokePoint(toWorldSample(sample)) },
""",
    "EditorScreen callbacks",
)
p.write_text(s)


# EditorViewModel: record canonical telemetry alongside point/pressure history.
p = Path("feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt")
s = p.read_text()
if "import com.hereliesaz.graffitixr.common.azphalt.BrushSample\n" not in s:
    s = replace_once(
        s,
        "import com.hereliesaz.graffitixr.common.azphalt.BrushStamps\n",
        "import com.hereliesaz.graffitixr.common.azphalt.BrushSample\nimport com.hereliesaz.graffitixr.common.azphalt.BrushParameter\nimport com.hereliesaz.graffitixr.common.azphalt.BrushStamps\n",
        "EditorViewModel brush imports",
    )
s = replace_once(
    s,
    """    val pressures: List<Float> = emptyList(),
    val canvasSize: IntSize,
""",
    """    val pressures: List<Float> = emptyList(),
    // Canonical per-point brush telemetry. Empty on legacy/remote commands.
    val brushSamples: List<BrushSample> = emptyList(),
    val canvasSize: IntSize,
""",
    "StrokeCommand brushSamples",
)

old_storage = """    private var strokeCollectedPressures: MutableList<Float> = mutableListOf()

    private fun resetStrokePoints(initial: Offset? = null, initialPressure: Float = 1f) = synchronized(strokeCollectedPointsLock) {
        // `vararg Offset` is rejected by the compiler (Offset is a Compose value class), so take a
        // single optional seed point — the only two call sites are reset-with-start and reset-empty.
        strokeCollectedPoints = if (initial != null) mutableListOf(initial) else mutableListOf()
        strokeCollectedPressures = if (initial != null) mutableListOf(initialPressure) else mutableListOf()
    }

    private fun addStrokePoint(point: Offset, pressure: Float) = synchronized(strokeCollectedPointsLock) {
        strokeCollectedPoints.add(point)
        strokeCollectedPressures.add(pressure)
    }

    private fun snapshotStrokePoints(): List<Offset> = synchronized(strokeCollectedPointsLock) {
        strokeCollectedPoints.toList()
    }

    private fun snapshotStrokePressures(): List<Float> = synchronized(strokeCollectedPointsLock) {
        strokeCollectedPressures.toList()
    }
"""
new_storage = """    private var strokeCollectedPressures: MutableList<Float> = mutableListOf()
    // Same index as points/pressures. Kinematics remain in physical screen-hand space; only x/y are
    // replaced by the stabilized world-space point before storage.
    private var strokeCollectedSamples: MutableList<BrushSample> = mutableListOf()
    private var pendingStrokeStartSample: BrushSample? = null
    private var pendingStrokePointSample: BrushSample? = null

    private fun resetStrokePoints(initial: Offset? = null, initialPressure: Float = 1f) = synchronized(strokeCollectedPointsLock) {
        // `vararg Offset` is rejected by the compiler (Offset is a Compose value class), so take a
        // single optional seed point — the only two call sites are reset-with-start and reset-empty.
        strokeCollectedPoints = if (initial != null) mutableListOf(initial) else mutableListOf()
        strokeCollectedPressures = if (initial != null) mutableListOf(initialPressure) else mutableListOf()
        strokeCollectedSamples = if (initial != null) {
            val source = pendingStrokeStartSample ?: BrushSample(initial.x, initial.y, pressure = initialPressure)
            mutableListOf(source.copy(x = initial.x, y = initial.y, pressure = initialPressure, predicted = false))
        } else {
            mutableListOf()
        }
    }

    private fun addStrokePoint(point: Offset, pressure: Float) = synchronized(strokeCollectedPointsLock) {
        strokeCollectedPoints.add(point)
        strokeCollectedPressures.add(pressure)
        val source = pendingStrokePointSample ?: BrushSample(point.x, point.y, pressure = pressure)
        strokeCollectedSamples.add(source.copy(x = point.x, y = point.y, pressure = pressure, predicted = false))
    }

    private fun snapshotStrokePoints(): List<Offset> = synchronized(strokeCollectedPointsLock) {
        strokeCollectedPoints.toList()
    }

    private fun snapshotStrokePressures(): List<Float> = synchronized(strokeCollectedPointsLock) {
        strokeCollectedPressures.toList()
    }

    private fun snapshotStrokeSamples(): List<BrushSample> = synchronized(strokeCollectedPointsLock) {
        strokeCollectedSamples.toList()
    }
"""
s = replace_once(s, old_storage, new_storage, "stroke telemetry storage")

start_marker = """    /** Called when the user first touches the canvas. Prepares a mutable working bitmap for
     *  incremental real-time rendering (all tools except Liquify). */
    fun onStrokeStart(startPoint: Offset, canvasSize: IntSize, pressure: Float = 1f) {
"""
s = replace_once(
    s,
    start_marker,
    """    /** Canonical input path used by DrawingCanvas. Legacy callers keep the Offset overload. */
    fun onStrokeStart(startSample: BrushSample, canvasSize: IntSize) {
        pendingStrokeStartSample = startSample
        try {
            onStrokeStart(Offset(startSample.x, startSample.y), canvasSize, startSample.pressure)
        } finally {
            pendingStrokeStartSample = null
        }
    }

""" + start_marker,
    "onStrokeStart sample overload",
)

point_marker = """    /** Called for every drag update. Draws only the new segment onto the working bitmap. */
    fun onStrokePoint(currentPoint: Offset, pressure: Float = 1f) {
"""
s = replace_once(
    s,
    point_marker,
    """    /** Canonical input path used by DrawingCanvas. */
    fun onStrokePoint(sample: BrushSample) {
        pendingStrokePointSample = sample
        try {
            onStrokePoint(Offset(sample.x, sample.y), sample.pressure)
        } finally {
            // The legacy implementation may throttle/return before addStrokePoint. Never leak a
            // skipped sample into the next accepted point.
            pendingStrokePointSample = null
        }
    }

""" + point_marker,
    "onStrokePoint sample overload",
)

end_start = s.index("    fun onStrokeEnd() {")
commit_start = s.index("    private fun commitStampStroke(", end_start)
before = s[:end_start]
end_body = s[end_start:commit_start]
after = s[commit_start:]
end_body = replace_once(
    end_body,
    "        val pressures = snapshotStrokePressures()\n",
    "        val pressures = snapshotStrokePressures()\n        val brushSamples = snapshotStrokeSamples()\n",
    "onStrokeEnd sample snapshot",
)
end_body = end_body.replace(
    "                path = points,\n",
    "                path = points,\n                brushSamples = brushSamples,\n",
)
end_body = end_body.replace(
    "            path = points,\n",
    "            path = points,\n            brushSamples = brushSamples,\n",
)
end_body = replace_once(
    end_body,
    "                state, layer, layerId, points, canvasW, canvasH,\n",
    "                state, layer, layerId, points, brushSamples, canvasW, canvasH,\n",
    "commitStampStroke call",
)
s = before + end_body + after

s = replace_once(
    s,
    """        points: List<Offset>,
        canvasW: Int,
""",
    """        points: List<Offset>,
        brushSamples: List<BrushSample>,
        canvasW: Int,
""",
    "commitStampStroke signature",
)
commit_start = s.index("    private fun commitStampStroke(")
commit_tail = s[commit_start:]
commit_tail = replace_once(
    commit_tail,
    "            path = points,\n",
    "            path = points,\n            brushSamples = brushSamples,\n",
    "stamp StrokeCommand telemetry",
)
s = s[:commit_start] + commit_tail

live_old = "            val dabs = BrushStamps.dabs(stampMappedPoints, _uiState.value.brushSize * brushScale, stampBrush, stampSeed)\n"
live_new = """            val diameterPx = _uiState.value.brushSize * brushScale
            val allSamples = snapshotStrokeSamples()
            val mappedSamples = if (allSamples.size * 2 == stampMappedPoints.size) {
                allSamples.mapIndexed { index, sample ->
                    sample.copy(
                        x = stampMappedPoints[index * 2],
                        y = stampMappedPoints[index * 2 + 1],
                        predicted = false,
                    )
                }
            } else {
                emptyList()
            }
            val dabs = if (stampBrush.dynamics.isNotEmpty() && mappedSamples.isNotEmpty()) {
                BrushStamps.dynamicDabs(mappedSamples, diameterPx, stampBrush, stampSeed)
            } else {
                BrushStamps.dabs(stampMappedPoints, diameterPx, stampBrush, stampSeed)
            }
"""
s = replace_once(s, live_old, live_new, "live dynamic dabs")

s = replace_once(
    s,
    "                val gpuHandled = stampGpuActive && stampGpuEngine?.let { engine ->\n",
    """                val needsPerDabPaint = stampBrush.dynamics.any { route ->
                    route.parameter == BrushParameter.FLOW ||
                        route.parameter == BrushParameter.HUE ||
                        route.parameter == BrushParameter.SATURATION ||
                        route.parameter == BrushParameter.VALUE
                }
                val gpuHandled = !needsPerDabPaint && stampGpuActive && stampGpuEngine?.let { engine ->
""",
    "GPU dynamic compatibility gate",
)

p.write_text(s)
