from pathlib import Path

path = Path("feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/StampBrushRenderer.kt")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match, found {count}: {old[:120]!r}")
    text = text.replace(old, new, 1)


replace_once(
'''        maskStamp: Bitmap? = null,
        secondaryColorArgb: Int = colorArgb,
    ) {
        val curved = CatmullRom.densify(points)''',
'''        maskStamp: Bitmap? = null,
        secondaryColorArgb: Int = colorArgb,
        substrate: SubstrateRenderContext? = null,
    ) {
        val curved = CatmullRom.densify(points)''',
)
replace_once(
'''            seed,
            secondaryColorArgb,
        )
    }

    fun paintDynamicStroke''',
'''            seed,
            secondaryColorArgb,
            substrate = substrate,
        )
    }

    fun paintDynamicStroke''',
)
replace_once(
'''        maskStamp: Bitmap? = null,
        secondaryColorArgb: Int = colorArgb,
    ) {
        paintDabs(
            canvas,
            BrushStamps.dynamicDabs''',
'''        maskStamp: Bitmap? = null,
        secondaryColorArgb: Int = colorArgb,
        substrate: SubstrateRenderContext? = null,
    ) {
        paintDabs(
            canvas,
            BrushStamps.dynamicDabs''',
)
replace_once(
'''            seed,
            secondaryColorArgb,
        )
    }

    fun paintDabs''',
'''            seed,
            secondaryColorArgb,
            substrate = substrate,
        )
    }

    fun paintDabs''',
)
replace_once(
'''        allowBuildUp: Boolean = false,
    ) {
        if (dabs.isEmpty()) return
        val advancedMaskPipeline = brush.tipRatio != 1f || grain != null || brush.maskedBrush != null
        if (advancedMaskPipeline) {
            paintMaskedDabs(canvas, dabs, brush, colorArgb, secondaryColorArgb, flow, stamp, grain, maskStamp, seed)
            return
        }''',
'''        allowBuildUp: Boolean = false,
        substrate: SubstrateRenderContext? = null,
    ) {
        if (dabs.isEmpty()) return
        val advancedMaskPipeline = brush.tipRatio != 1f || grain != null || brush.maskedBrush != null ||
            (substrate != null && (stamp != null || allowBuildUp || brush.buildUp))
        if (advancedMaskPipeline) {
            paintMaskedDabs(
                canvas, dabs, brush, colorArgb, secondaryColorArgb, flow,
                stamp, grain, maskStamp, seed, substrate,
            )
            return
        }''',
)
replace_once(
'''        if (allowBuildUp || brush.buildUp) {
            paintRoundDabsSequential(canvas, dabs, brush, colorArgb, secondaryColorArgb, flow)
        } else {
            paintRoundDabsMaxCombined(canvas, dabs, brush, colorArgb, secondaryColorArgb, flow)
        }''',
'''        if (allowBuildUp || brush.buildUp) {
            paintRoundDabsSequential(canvas, dabs, brush, colorArgb, secondaryColorArgb, flow)
        } else {
            paintRoundDabsMaxCombined(
                canvas, dabs, brush, colorArgb, secondaryColorArgb, flow, substrate,
            )
        }''',
)
replace_once(
'''        flow: Float,
        secondaryColorArgb: Int = colorArgb,
    ) {''',
'''        flow: Float,
        secondaryColorArgb: Int = colorArgb,
        substrate: SubstrateRenderContext? = null,
    ) {''',
)
replace_once(
'''        paintRoundDabsMaxCombined(canvas, dabs, brush, colorArgb, secondaryColorArgb, flow)
    }''',
'''        paintRoundDabsMaxCombined(
            canvas, dabs, brush, colorArgb, secondaryColorArgb, flow, substrate,
        )
    }''',
)
replace_once(
'''        secondaryColorArgb: Int,
        flow: Float,
    ) {
        val baseFlow = flow.coerceIn(0f, 1f)

        var minX''',
'''        secondaryColorArgb: Int,
        flow: Float,
        substrate: SubstrateRenderContext? = null,
    ) {
        val baseFlow = flow.coerceIn(0f, 1f)

        var minX''',
)
replace_once(
'''                    if (rNorm < 1f) {
                        val localAlpha = BrushStamps.stampCoverage(rNorm, d.hardness) * strength
                        val idx = rowBase + (px - left)''',
'''                    if (rNorm < 1f) {
                        val deposition = substrate?.depositionAt(
                            px + 0.5f,
                            py + 0.5f,
                            d,
                            canvas.width,
                            canvas.height,
                        ) ?: 1f
                        val localAlpha = BrushStamps.stampCoverage(rNorm, d.hardness) * strength * deposition
                        val idx = rowBase + (px - left)''',
)
replace_once(
'''        maskStamp: Bitmap?,
        seed: Long,
    ) {''',
'''        maskStamp: Bitmap?,
        seed: Long,
        substrate: SubstrateRenderContext? = null,
    ) {''',
)
replace_once(
'''                if (grainTile != null && brush.grainStrength > 0f) {
                    applyGrain(
                        scratch.primary,
                        grainTile,
                        brush.grainBehavior,
                        brush.grainScale,
                        left,
                        top,
                        grainPhaseX,
                        grainPhaseY,
                    )
                }

                val dabColor''',
'''                if (grainTile != null && brush.grainStrength > 0f) {
                    applyGrain(
                        scratch.primary,
                        grainTile,
                        brush.grainBehavior,
                        brush.grainScale,
                        left,
                        top,
                        grainPhaseX,
                        grainPhaseY,
                    )
                }
                if (substrate != null) {
                    applySubstrate(
                        scratch.primary,
                        substrate,
                        dab,
                        left,
                        top,
                        destination.width,
                        destination.height,
                    )
                }

                val dabColor''',
)
replace_once(
'''    private fun drawCenteredMask(
        canvas: Canvas,''',
'''    private fun applySubstrate(
        mask: Bitmap,
        substrate: SubstrateRenderContext,
        dab: Dab,
        globalLeft: Int,
        globalTop: Int,
        canvasWidth: Int,
        canvasHeight: Int,
    ) {
        val width = mask.width
        val height = mask.height
        val pixels = IntArray(width * height)
        mask.getPixels(pixels, 0, width, 0, 0, width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val currentAlpha = Color.alpha(pixels[index])
                if (currentAlpha == 0) continue
                val deposition = substrate.depositionAt(
                    globalLeft + x + 0.5f,
                    globalTop + y + 0.5f,
                    dab,
                    canvasWidth,
                    canvasHeight,
                )
                val outAlpha = (currentAlpha * deposition).roundToInt().coerceIn(0, 255)
                pixels[index] = (outAlpha shl 24) or 0x00FFFFFF
            }
        }
        mask.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun drawCenteredMask(
        canvas: Canvas,''',
)

path.write_text(text, encoding="utf-8")
