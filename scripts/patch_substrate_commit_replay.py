from pathlib import Path

path = Path("feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/DrawingEngine.kt")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match, found {count}: {old[:140]!r}")
    text = text.replace(old, new, 1)


replace_once(
'''    suspend fun composite(
        base: Bitmap,
        strokes: List<StrokeCommand>,
        otherLayers: () -> List<Layer> = { emptyList() },
        heightMap: FloatArray? = null,
    ): Bitmap {''',
'''    suspend fun composite(
        base: Bitmap,
        strokes: List<StrokeCommand>,
        otherLayers: () -> List<Layer> = { emptyList() },
        heightMap: FloatArray? = null,
        substrate: SubstrateRenderContext? = null,
    ): Bitmap {''',
)
replace_once(
'''            else applyTool(current, stroke, replaceExisting = true, otherLayers = otherLayers, heightMap = heightMap)''',
'''            else applyTool(
                current, stroke, replaceExisting = true, otherLayers = otherLayers,
                heightMap = heightMap, substrate = substrate,
            )''',
)
replace_once(
'''        otherLayers: List<Layer> = emptyList(),
        heightMap: FloatArray? = null,
    ): Bitmap =
        if (command.tool == Tool.LIQUIFY) applyLiquify(base, command)
        else applyTool(base, command, replaceExisting = false, otherLayers = { otherLayers }, heightMap = heightMap)''',
'''        otherLayers: List<Layer> = emptyList(),
        heightMap: FloatArray? = null,
        substrate: SubstrateRenderContext? = null,
    ): Bitmap =
        if (command.tool == Tool.LIQUIFY) applyLiquify(base, command)
        else applyTool(
            base, command, replaceExisting = false, otherLayers = { otherLayers },
            heightMap = heightMap, substrate = substrate,
        )''',
)
replace_once(
'''        otherLayers: () -> List<Layer> = { emptyList() },
        heightMap: FloatArray? = null,
    ): Bitmap {
        val clipPath''',
'''        otherLayers: () -> List<Layer> = { emptyList() },
        heightMap: FloatArray? = null,
        substrate: SubstrateRenderContext? = null,
    ): Bitmap {
        val clipPath''',
)
replace_once(
'''                    stroke.stampShape, stroke.stampGrain, stroke.stampMaskShape, stroke.seed,
                    stroke.secondaryBrushColor,
                )''',
'''                    stroke.stampShape, stroke.stampGrain, stroke.stampMaskShape, stroke.seed,
                    stroke.secondaryBrushColor, substrate = substrate,
                )''',
)
replace_once(
'''                        stroke.stampShape, stroke.stampGrain, stroke.stampMaskShape, stroke.seed,
                        stroke.secondaryBrushColor, allowBuildUp = true,
                    )''',
'''                        stroke.stampShape, stroke.stampGrain, stroke.stampMaskShape, stroke.seed,
                        stroke.secondaryBrushColor, allowBuildUp = true, substrate = substrate,
                    )''',
)
replace_once(
'''                    stroke.stampShape, stroke.stampGrain, stroke.stampMaskShape,
                    stroke.secondaryBrushColor,
                )''',
'''                    stroke.stampShape, stroke.stampGrain, stroke.stampMaskShape,
                    stroke.secondaryBrushColor, substrate = substrate,
                )''',
)

path.write_text(text, encoding="utf-8")
