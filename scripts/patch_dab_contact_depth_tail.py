from pathlib import Path

path = Path("core/engine/src/commonMain/kotlin/com/hereliesaz/graffitixr/common/azphalt/BrushStamps.kt")
text = path.read_text(encoding="utf-8")
old = """    val hardness: Float = 1f,
    val flowMultiplier: Float = 1f,
    /** Resolved surface penetration; renderers/material stages must not reinterpret raw pressure. */
    val contactDepth: Float = 1f,
    val hueShiftDeg: Float = 0f,
    val saturationMultiplier: Float = 1f,
    val valueMultiplier: Float = 1f,
    val colorMix: Float = 0f,
    val sourceRandom: Float = 0f,
    val mask: MaskDab? = null,
)"""
new = """    val hardness: Float = 1f,
    val flowMultiplier: Float = 1f,
    val hueShiftDeg: Float = 0f,
    val saturationMultiplier: Float = 1f,
    val valueMultiplier: Float = 1f,
    val colorMix: Float = 0f,
    val sourceRandom: Float = 0f,
    val mask: MaskDab? = null,
    /**
     * Resolved surface penetration; renderers/material stages must not reinterpret raw pressure.
     * Appended at the end of the public value contract so existing positional Dab callers retain
     * the exact meaning of every pre-Phase-3 argument.
     */
    val contactDepth: Float = 1f,
)"""
count = text.count(old)
if count != 1:
    raise SystemExit(f"expected exactly one Dab contract match, found {count}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
