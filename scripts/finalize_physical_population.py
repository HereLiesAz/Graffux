from pathlib import Path

# Freeze the physical root lattice to the selected brush diameter. Pressure/taper may change
# engagement/splay/paint, but a real ferrule does not shrink its hair roots mid-stroke.
tuft_path = Path("core/engine/src/commonMain/kotlin/com/hereliesaz/graffitixr/common/azphalt/BrushTuftTopology.kt")
tuft = tuft_path.read_text()
old = """    val physicalBundleDiameterPx: Float = 0f,
) {"""
new = """    val physicalBundleDiameterPx: Float = 0f,
    /** Selected brush diameter used as the immutable ferrule/root-spacing scale for this stroke. */
    val physicalRootDiameterPx: Float = 0f,
) {"""
if tuft.count(old) != 1:
    raise SystemExit("BrushTuftConfig physical bundle marker not found exactly once")
tuft = tuft.replace(old, new, 1)
old = """            physicalBundleDiameterPx = physicalBundleDiameterPx.coerceIn(0f, 64f),
        )"""
new = """            physicalBundleDiameterPx = physicalBundleDiameterPx.coerceIn(0f, 64f),
            physicalRootDiameterPx = physicalRootDiameterPx.coerceIn(0f, 8192f),
        )"""
if tuft.count(old) != 1:
    raise SystemExit("BrushTuftConfig sanitize marker not found exactly once")
tuft = tuft.replace(old, new, 1)
tuft_path.write_text(tuft)

# Store the selected diameter when the physical population is resolved. Legacy/non-physical
# configs retain zero and therefore preserve the historical dynamic-diameter behavior.
tip_path = Path("core/engine/src/commonMain/kotlin/com/hereliesaz/graffitixr/common/azphalt/BrushTipTopology.kt")
tip = tip_path.read_text()
old = """            physicalHeightSpan = (size.second / diameter).coerceIn(0.05f, 1.5f),
            physicalBundleDiameterPx = tip.population.mechanicalBundleDiameterPx,
            emitTuftDabs = true,
"""
new = """            physicalHeightSpan = (size.second / diameter).coerceIn(0.05f, 1.5f),
            physicalBundleDiameterPx = tip.population.mechanicalBundleDiameterPx,
            physicalRootDiameterPx = diameter,
            emitTuftDabs = true,
"""
if tip.count(old) != 1:
    raise SystemExit("BrushTipTopology resolved physical marker not found exactly once")
tip = tip.replace(old, new, 1)
tip_path.write_text(tip)

# Physical offsets are expressed in selected-brush-diameter fractions. Use the frozen root scale
# for those offsets; legacy topology continues to use each momentary contact diameter exactly.
expander_path = Path("core/engine/src/commonMain/kotlin/com/hereliesaz/graffitixr/common/azphalt/BrushTuftDabExpander.kt")
expander = expander_path.read_text()
old = """    ): List<Dab> = if (config.sanitized().emitsDabs()) {
        expand(parent, contactDiameterPx, contact.tufts)
    } else {
        listOf(parent)
    }
"""
new = """    ): List<Dab> {
        val cfg = config.sanitized()
        if (!cfg.emitsDabs()) return listOf(parent)
        val topologyDiameterPx = if (cfg.usesPhysicalPopulation() && cfg.physicalRootDiameterPx > 0f) {
            cfg.physicalRootDiameterPx
        } else {
            contactDiameterPx
        }
        return expand(parent, topologyDiameterPx, contact.tufts)
    }
"""
if expander.count(old) != 1:
    raise SystemExit("BrushTuftDabExpander compatibility entry point not found exactly once")
expander = expander.replace(old, new, 1)
expander_path.write_text(expander)

# Paint-level regression: changing the momentary contact diameter must not collapse or expand the
# physical root lattice, and fixed-diameter bundles must remain fixed too.
test_path = Path("core/engine/src/commonTest/kotlin/com/hereliesaz/graffitixr/common/azphalt/BrushPhysicalPopulationDabTest.kt")
test = test_path.read_text()
marker = """    @Test
    fun physicalPopulationMatchesIncrementalAndCanonicalReplay() {"""
insert = """    @Test
    fun dynamicContactDiameterDoesNotResizePhysicalRootLattice() {
        val brush = physicalFlatBrush()
        val cfg = brush.contact.resolvedForBrushDiameter(40f, brush.tipRatio).tufts
        assertEquals(40f, cfg.physicalRootDiameterPx, 0f)
        val contacts = BrushTuftTopology.resolve(
            BrushMechanicalState(initialized = true, dragAngleDeg = 0f),
            BrushContactState(),
            cfg,
        )
        val state = BrushContactState(tufts = contacts)
        val parent = Dab(x = 100f, y = 100f, radius = 20f, alpha = 1f)
        val full = BrushTuftDabExpander.expandIfEnabled(parent, 40f, state, cfg)
        val pressureShrunk = BrushTuftDabExpander.expandIfEnabled(parent.copy(radius = 5f), 10f, state, cfg)

        assertEquals(full.size, pressureShrunk.size)
        full.zip(pressureShrunk).forEachIndexed { index, (a, b) ->
            assertEquals(a.x, b.x, 1e-5f, "x[$index]")
            assertEquals(a.y, b.y, 1e-5f, "y[$index]")
            assertEquals(a.radius, b.radius, 1e-5f, "radius[$index]")
        }
    }

""" + marker
if test.count(marker) != 1:
    raise SystemExit("Physical population parity test marker not found exactly once")
test = test.replace(marker, insert, 1)
test_path.write_text(test)

# Remove the branch-only patch machinery from the resulting source tree.
Path(".github/workflows/finalize-physical-population.yml").unlink(missing_ok=True)
Path("scripts/finalize_physical_population.py").unlink(missing_ok=True)
