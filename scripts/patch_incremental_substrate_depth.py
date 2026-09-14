from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old[:120]!r}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


path = "core/engine/src/commonMain/kotlin/com/hereliesaz/graffitixr/common/azphalt/IncrementalDynamicDabGenerator.kt"
replace_once(
    path,
    """                hardness = (brush.hardness * dynamic.hardnessMultiplier).coerceIn(0f, 1f),
                flowMultiplier = dynamic.flowMultiplier,
                hueShiftDeg = dynamic.hueShiftDeg,""",
    """                hardness = (brush.hardness * dynamic.hardnessMultiplier).coerceIn(0f, 1f),
                flowMultiplier = dynamic.flowMultiplier,
                contactDepth = contact.contactDepth,
                hueShiftDeg = dynamic.hueShiftDeg,""",
)
replace_once(
    path,
    """                        hardness = (brush.hardness * dynamic.hardnessMultiplier).coerceIn(0f, 1f),
                        flowMultiplier = dynamic.flowMultiplier,
                        hueShiftDeg = dynamic.hueShiftDeg,""",
    """                        hardness = (brush.hardness * dynamic.hardnessMultiplier).coerceIn(0f, 1f),
                        flowMultiplier = dynamic.flowMultiplier,
                        contactDepth = contact.contactDepth,
                        hueShiftDeg = dynamic.hueShiftDeg,""",
)

replace_once(
    "core/engine/src/commonTest/kotlin/com/hereliesaz/graffitixr/common/azphalt/BrushMorphologyDabParityTest.kt",
    """                assertEquals(canonical.angleDeg, live.angleDeg, 1e-4f, \"${morphology.name} angle[$index]\")
                assertEquals(canonical.alpha, live.alpha, 1e-4f, \"${morphology.name} alpha[$index]\")""",
    """                assertEquals(canonical.angleDeg, live.angleDeg, 1e-4f, \"${morphology.name} angle[$index]\")
                assertEquals(canonical.alpha, live.alpha, 1e-4f, \"${morphology.name} alpha[$index]\")
                assertEquals(
                    canonical.contactDepth,
                    live.contactDepth,
                    1e-4f,
                    \"${morphology.name} contactDepth[$index]\",
                )""",
)
