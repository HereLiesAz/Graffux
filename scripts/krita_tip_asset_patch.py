from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VM = ROOT / "feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt"
text = VM.read_text()


def replace_once(old: str, new: str):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one anchor, found {count}: {old[:120]!r}")
    text = text.replace(old, new, 1)

replace_once(
'''    val seed: Long = 0L,\n    val stampShape: Bitmap? = null,\n    // Procreate parity,''',
'''    val seed: Long = 0L,\n    val stampShape: Bitmap? = null,\n    // Orthogonal Krita-style brush assets. Grain is tiled independently of the primary shape; the\n    // masked shape is the optional secondary tip. Both are snapshotted with the stroke just like\n    // stampShape so undo/redo cannot depend on whichever extension is selected later.\n    val stampGrain: Bitmap? = null,\n    val stampMaskShape: Bitmap? = null,\n    // Procreate parity,''')
replace_once(
'''    // Decoded tip image for the active stamp brush (null = a generated round tip). Loaded alongside it.\n    private var activeStampShape: Bitmap? = null\n''',
'''    // Decoded primary, grain, and secondary-mask assets for the active brush.\n    private var activeStampShape: Bitmap? = null\n    private var activeStampGrain: Bitmap? = null\n    private var activeStampMaskShape: Bitmap? = null\n''')
replace_once(
'''    private var stampBrushForStroke: com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush? = null\n    private var stampShapeForStroke: Bitmap? = null\n''',
'''    private var stampBrushForStroke: com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush? = null\n    private var stampShapeForStroke: Bitmap? = null\n    private var stampGrainForStroke: Bitmap? = null\n    private var stampMaskShapeForStroke: Bitmap? = null\n''')
replace_once(
'''            stampBrushForStroke = stampBrush\n            stampShapeForStroke = activeStampShape\n            val currentSeed = System.nanoTime()''',
'''            stampBrushForStroke = stampBrush\n            stampShapeForStroke = activeStampShape\n            stampGrainForStroke = activeStampGrain\n            stampMaskShapeForStroke = activeStampMaskShape\n            val currentSeed = System.nanoTime()''')
replace_once(
'''                val gpuEngine = if (stampShapeForStroke == null) createSeededGpuEngine(work.width, work.height, work) else null\n''',
'''                val gpuCompatibleBrush = stampShapeForStroke == null &&\n                    stampGrainForStroke == null &&\n                    stampMaskShapeForStroke == null &&\n                    stampBrush.maskedBrush == null &&\n                    stampBrush.tipRatio == 1f\n                val gpuEngine = if (gpuCompatibleBrush) createSeededGpuEngine(work.width, work.height, work) else null\n''')
replace_once(
'''                    StampBrushRenderer.paintDabs(\n                        canvas, newDabs, stampBrush, colorArgb, _uiState.value.brushFlow, stampShapeForStroke,\n                    )\n''',
'''                    StampBrushRenderer.paintDabs(\n                        canvas, newDabs, stampBrush, colorArgb, _uiState.value.brushFlow,\n                        stampShapeForStroke, stampGrainForStroke, stampMaskShapeForStroke, stampSeed,\n                    )\n''')
replace_once(
'''                capturedScale, capturedOffset, capturedRotationZ, stampBrush, stampShapeForStroke,\n                strokeSelection,\n''',
'''                capturedScale, capturedOffset, capturedRotationZ, stampBrush, stampShapeForStroke,\n                stampGrainForStroke, stampMaskShapeForStroke, strokeSelection,\n''')
replace_once(
'''        brush: com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush,\n        stampShape: Bitmap?,\n        // Passed in rather than read off `strokeSelection`:''',
'''        brush: com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush,\n        stampShape: Bitmap?,\n        stampGrain: Bitmap?,\n        stampMaskShape: Bitmap?,\n        // Passed in rather than read off `strokeSelection`:''')
replace_once(
'''            seed = stampSeed,\n            stampShape = stampShape,\n            selection = selection,\n''',
'''            seed = stampSeed,\n            stampShape = stampShape,\n            stampGrain = stampGrain,\n            stampMaskShape = stampMaskShape,\n            selection = selection,\n''')
replace_once(
'''        stampBrushForStroke = null\n        stampShapeForStroke = null\n        stampMappedPoints.clear()''',
'''        stampBrushForStroke = null\n        stampShapeForStroke = null\n        stampGrainForStroke = null\n        stampMaskShapeForStroke = null\n        stampMappedPoints.clear()''')
replace_once(
'''        activeStampBrush = brush\n        activeStampShape = null   // null → StampBrushRenderer draws its generated round tip\n        dispatch(EditorIntent.SetActiveBrush(brush.name))''',
'''        activeStampBrush = brush\n        activeStampShape = null\n        activeStampGrain = null\n        activeStampMaskShape = null\n        dispatch(EditorIntent.SetActiveBrush(brush.name))''')
replace_once(
'''        activeStampBrush = brush\n        // A draft brush is params-only; drop any tip image the previously-selected brush had, or the\n        // new settings would render against the old brush's shape.\n        activeStampShape = null\n        dispatch(EditorIntent.SetActiveBrush(brush.name))''',
'''        activeStampBrush = brush\n        // Brush Studio drafts are params-only. Never let assets from the previously selected extension\n        // leak into a generated/custom draft.\n        activeStampShape = null\n        activeStampGrain = null\n        activeStampMaskShape = null\n        dispatch(EditorIntent.SetActiveBrush(brush.name))''')
replace_once(
'''            activeStampBrush = null\n            activeStampShape = null\n            dispatch(EditorIntent.SetActiveBrush(null))''',
'''            activeStampBrush = null\n            activeStampShape = null\n            activeStampGrain = null\n            activeStampMaskShape = null\n            dispatch(EditorIntent.SetActiveBrush(null))''')
replace_once(
'''            val shape = brush?.shapePath\n                ?.let { extensionRepository.assetFilePath(id, it) }\n                ?.let { path -> runCatching { decodeBoundedBitmap(java.io.File(path).readBytes(), 1024) }.getOrNull() }\n            withContext(dispatchers.main) {''',
'''            fun decodeAsset(relativePath: String?): Bitmap? = relativePath\n                ?.let { extensionRepository.assetFilePath(id, it) }\n                ?.let { path -> runCatching { decodeBoundedBitmap(java.io.File(path).readBytes(), 1024) }.getOrNull() }\n            val shape = decodeAsset(brush?.shapePath)\n            val grain = decodeAsset(brush?.grainPath)\n            val maskShape = decodeAsset(brush?.maskedBrush?.shapePath)\n            withContext(dispatchers.main) {''')
replace_once(
'''                    activeStampBrush = brush\n                    activeStampShape = shape   // null → StampBrushRenderer draws a generated round tip\n                    dispatch(EditorIntent.SetActiveBrush(brush.name))''',
'''                    activeStampBrush = brush\n                    activeStampShape = shape\n                    activeStampGrain = grain\n                    activeStampMaskShape = maskShape\n                    dispatch(EditorIntent.SetActiveBrush(brush.name))''')

VM.write_text(text)

DRAWING = ROOT / "feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/DrawingEngine.kt"
drawing = DRAWING.read_text()
old_dynamic = '''                StampBrushRenderer.paintDynamicStroke(\n                    stampCanvas, mappedSamples, brush, stroke.brushColor,\n                    stroke.brushSize * brushScale, stroke.flow, stroke.seed, stroke.stampShape,\n                )'''
new_dynamic = '''                StampBrushRenderer.paintDynamicStroke(\n                    stampCanvas, mappedSamples, brush, stroke.brushColor,\n                    stroke.brushSize * brushScale, stroke.flow, stroke.seed,\n                    stroke.stampShape, stroke.stampGrain, stroke.stampMaskShape,\n                )'''
if drawing.count(old_dynamic) != 1:
    raise SystemExit(f"DrawingEngine dynamic anchor count={drawing.count(old_dynamic)}")
drawing = drawing.replace(old_dynamic, new_dynamic, 1)
old_static = '''                StampBrushRenderer.paintStroke(\n                    stampCanvas, pts, brush, stroke.brushColor,\n                    stroke.brushSize * brushScale, stroke.flow, stroke.seed, stroke.stampShape,\n                )'''
new_static = '''                StampBrushRenderer.paintStroke(\n                    stampCanvas, pts, brush, stroke.brushColor,\n                    stroke.brushSize * brushScale, stroke.flow, stroke.seed,\n                    stroke.stampShape, stroke.stampGrain, stroke.stampMaskShape,\n                )'''
if drawing.count(old_static) != 1:
    raise SystemExit(f"DrawingEngine static anchor count={drawing.count(old_static)}")
drawing = drawing.replace(old_static, new_static, 1)
DRAWING.write_text(drawing)

print("Krita tip/grain/mask asset plumbing patched")
