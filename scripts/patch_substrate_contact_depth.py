from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file_path = Path(path)
    text = file_path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old[:120]!r}")
    file_path.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "core/engine/src/commonMain/kotlin/com/hereliesaz/graffitixr/common/azphalt/BrushContactModel.kt",
    """    val lean: Float = 0f,
    val splay: Float = 0f,
    /** Stable bundle contacts relative to this global contact center. Empty when topology is off. */""",
    """    val lean: Float = 0f,
    val splay: Float = 0f,
    /**
     * Normalized resolved surface penetration. 1 is the compatibility/full-contact fallback;
     * active mechanics replace it with pressure/contact evidence before renderers see the dab.
     */
    val contactDepth: Float = 1f,
    /** Stable bundle contacts relative to this global contact center. Empty when topology is off. */""",
)

replace_once(
    "core/engine/src/commonMain/kotlin/com/hereliesaz/graffitixr/common/azphalt/BrushContactModel.kt",
    """        val splay = (motionSplay + pressureSplay).coerceAtLeast(0f)
        val width = 1f + splay
        val flattening = cfg.dragElongation * bend + cfg.tiltElongation * lean""",
    """        val splay = (motionSplay + pressureSplay).coerceAtLeast(0f)
        val contactDepth = maxOf(
            state.intent.pressure * state.intent.pressureConfidence,
            state.intent.contactMajor * state.intent.contactConfidence,
            compression,
        ).coerceIn(0f, 1f)
        val width = 1f + splay
        val flattening = cfg.dragElongation * bend + cfg.tiltElongation * lean""",
)

replace_once(
    "core/engine/src/commonMain/kotlin/com/hereliesaz/graffitixr/common/azphalt/BrushContactModel.kt",
    """            compression = compression,
            lean = lean,
            splay = splay,
            tipLeanX = state.presentation.leanX,""",
    """            compression = compression,
            lean = lean,
            splay = splay,
            contactDepth = contactDepth,
            tipLeanX = state.presentation.leanX,""",
)

replace_once(
    "core/engine/src/commonMain/kotlin/com/hereliesaz/graffitixr/common/azphalt/BrushStamps.kt",
    """    val hardness: Float = 1f,
    val flowMultiplier: Float = 1f,
    val hueShiftDeg: Float = 0f,""",
    """    val hardness: Float = 1f,
    val flowMultiplier: Float = 1f,
    /** Resolved surface penetration; renderers/material stages must not reinterpret raw pressure. */
    val contactDepth: Float = 1f,
    val hueShiftDeg: Float = 0f,""",
)

replace_once(
    "core/engine/src/commonMain/kotlin/com/hereliesaz/graffitixr/common/azphalt/BrushStamps.kt",
    """                    hardness = (brush.hardness * dynamic.hardnessMultiplier).coerceIn(0f, 1f),
                    flowMultiplier = dynamic.flowMultiplier,
                    hueShiftDeg = dynamic.hueShiftDeg,""",
    """                    hardness = (brush.hardness * dynamic.hardnessMultiplier).coerceIn(0f, 1f),
                    flowMultiplier = dynamic.flowMultiplier,
                    contactDepth = contact.contactDepth,
                    hueShiftDeg = dynamic.hueShiftDeg,""",
)

replace_once(
    "core/engine/src/commonMain/kotlin/com/hereliesaz/graffitixr/common/azphalt/BrushStamps.kt",
    """                            hardness = (brush.hardness * dynamic.hardnessMultiplier).coerceIn(0f, 1f),
                            flowMultiplier = dynamic.flowMultiplier,
                            hueShiftDeg = dynamic.hueShiftDeg,""",
    """                            hardness = (brush.hardness * dynamic.hardnessMultiplier).coerceIn(0f, 1f),
                            flowMultiplier = dynamic.flowMultiplier,
                            contactDepth = contact.contactDepth,
                            hueShiftDeg = dynamic.hueShiftDeg,""",
)

replace_once(
    "core/engine/src/commonMain/kotlin/com/hereliesaz/graffitixr/common/azphalt/BrushTuftDabExpander.kt",
    """                alpha = (parent.alpha * alphaScale).coerceIn(0f, 1f),
                angleDeg = parent.angleDeg + tuft.angleOffsetDeg,
                // A physical bundle is a cluster of fixed-diameter hairs.""",
    """                alpha = (parent.alpha * alphaScale).coerceIn(0f, 1f),
                angleDeg = parent.angleDeg + tuft.angleOffsetDeg,
                contactDepth = (parent.contactDepth * tuft.contactWeight).coerceIn(0f, 1f),
                // A physical bundle is a cluster of fixed-diameter hairs.""",
)

replace_once(
    "core/engine/src/commonTest/kotlin/com/hereliesaz/graffitixr/common/azphalt/BrushContactModelTest.kt",
    """        assertTrue(heavy.state.compression > light.state.compression)
        assertTrue(heavy.contact.widthMultiplier > light.contact.widthMultiplier)
        assertTrue(heavy.state.intent.pressure > light.state.intent.pressure)""",
    """        assertTrue(heavy.state.compression > light.state.compression)
        assertTrue(heavy.contact.widthMultiplier > light.contact.widthMultiplier)
        assertTrue(heavy.contact.contactDepth > light.contact.contactDepth)
        assertTrue(heavy.state.intent.pressure > light.state.intent.pressure)""",
)

replace_once(
    "core/engine/src/commonTest/kotlin/com/hereliesaz/graffitixr/common/azphalt/BrushTuftDabExpanderTest.kt",
    """        hardness = 0.7f,
        flowMultiplier = 0.9f,
        colorMix = 0.25f,""",
    """        hardness = 0.7f,
        flowMultiplier = 0.9f,
        contactDepth = 0.8f,
        colorMix = 0.25f,""",
)

replace_once(
    "core/engine/src/commonTest/kotlin/com/hereliesaz/graffitixr/common/azphalt/BrushTuftDabExpanderTest.kt",
    """        assertEquals(parent.colorMix, children[0].colorMix, 0f)
        assertEquals(parent.sourceRandom, children[2].sourceRandom, 0f)
    }

    @Test
    fun childMaskTracksBundleOffsetAndScale()""",
    """        assertEquals(parent.colorMix, children[0].colorMix, 0f)
        assertEquals(parent.sourceRandom, children[2].sourceRandom, 0f)
        assertEquals(parent.contactDepth, children[1].contactDepth, 0f)
    }

    @Test
    fun bundleEngagementScalesSubstrateContactDepth() {
        val tuft = BrushTuftContact(
            id = 0,
            rootLateralFraction = 0f,
            offsetXFraction = 0f,
            offsetYFraction = 0f,
            radiusScale = 1f,
            alphaScale = 1f,
            angleOffsetDeg = 0f,
            stiffnessScale = 1f,
            contactWeight = 0.25f,
        )

        val child = BrushTuftDabExpander.expand(parent, 40f, listOf(tuft)).single()
        assertEquals(0.2f, child.contactDepth, 1e-6f)
    }

    @Test
    fun childMaskTracksBundleOffsetAndScale()""",
)
