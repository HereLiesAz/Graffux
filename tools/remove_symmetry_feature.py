from pathlib import Path
import re

ROOT = Path('.')

def edit(path, fn):
    p = ROOT / path
    s = p.read_text()
    ns = fn(s)
    if ns == s:
        print(f'NO CHANGE: {path}')
    else:
        p.write_text(ns)
        print(f'UPDATED: {path}')

# UI state: remove the symmetry mode and remembered mode entirely.
def editor_models(s):
    s = re.sub(r'\n\s*// Procreate\'s symmetry guide.*?val lastSymmetryMode: SymmetryMode = SymmetryMode\.VERTICAL,\n', '\n', s, flags=re.S)
    return s
edit('core/common/src/main/java/com/hereliesaz/graffitixr/common/model/EditorModels.kt', editor_models)

# Intents/reducer.
def editor_intent(s):
    s = s.replace('import com.hereliesaz.graffitixr.common.model.SymmetryMode\n', '')
    s = re.sub(r'\n\s*/\*\* Toggles the vertical-mirror symmetry guide for painting\. \*/\n\s*/\*\* Flips between \[SymmetryMode\.NONE\].*?data class SetSymmetryMode\(val mode: SymmetryMode\) : EditorIntent\n', '\n', s, flags=re.S)
    return s
edit('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorIntent.kt', editor_intent)

def editor_reducer(s):
    s = s.replace('import com.hereliesaz.graffitixr.common.model.SymmetryMode\n', '')
    s = re.sub(r'\n\s*// Turning symmetry back on restores the mode you were using, rather than resetting to\n.*?is EditorIntent\.SetSymmetryMode -> state\.copy\(\n.*?\n\s*\)\n', '\n', s, flags=re.S)
    return s
edit('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorReducer.kt', editor_reducer)

# Tool Options: no symmetry controls or callback plumbing.
def tool_options(s):
    s = s.replace('import com.hereliesaz.graffitixr.common.model.SymmetryMode\n', '')
    s = s.replace('    symmetryMode: SymmetryMode,\n    onSetSymmetryMode: (SymmetryMode) -> Unit,\n', '')
    s = re.sub(r'\n\s*if \(symmetryMode != SymmetryMode\.NONE\) \{\n\s*Text\("Symmetry".*?\n\s*\}\n\s*\}', '\n', s, flags=re.S)
    return s
edit('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/ToolOptionsWindow.kt', tool_options)

# Main host no longer passes symmetry controls.
def main_activity(s):
    s = s.replace('                        symmetryMode = uiState.symmetryMode,\n                        onSetSymmetryMode = { vm.onSetSymmetryMode(it) },\n', '')
    return s
edit('app/src/main/java/com/hereliesaz/graffux/MainActivity.kt', main_activity)

# Quick Menu symmetry action removed.
def editor_screen(s):
    s = s.replace('import com.hereliesaz.graffitixr.common.model.SymmetryMode\n', '')
    s = re.sub(r'\n\s*QuickAction\(if \(uiState\.symmetryMode != SymmetryMode\.NONE\) "Sym ✓" else "Sym"\) \{ vm\.onToggleSymmetry\(\) \},', '', s)
    return s
edit('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorScreen.kt', editor_screen)

# ImageProcessor: remove symmetry parameters, transform generation, and twin rendering.
def image_processor(s):
    s = s.replace('import com.hereliesaz.graffitixr.common.model.SymmetryMode\n', '')
    s = re.sub(r'\n\s*// Procreate parity flags: alphaLock confines paint to existing alpha\n\s*// \(SRC_ATOP\); symmetryMode mirrors the stroke across one or more axes\n\s*// through the bitmap\'s centre\. Both must be honoured here so undo/redo\n\s*// replay matches exactly what was painted live\.\n\s*alphaLock: Boolean = false,\n\s*symmetryMode: SymmetryMode = SymmetryMode\.NONE,', '\n        // Alpha Lock confines paint to existing alpha (SRC_ATOP).\n        alphaLock: Boolean = false,', s)
    s = s.replace(', symmetryMode, pressures)', ', pressures)')
    s = s.replace(', symmetryMode)', ')')
    s = s.replace(', symmetryMode = SymmetryMode.NONE)', ')')
    s = s.replace(', SymmetryMode.NONE)', ')')
    s = re.sub(r'\n\s*// Symmetry is handled here rather than by drawStroke because this branch never\n.*?for \(s in strokes\) \{\n\s*smudgeAlong\(px, w, h, s, radius, rate, feathering, wrapAroundMode\)\n\s*\}', '\n                smudgeAlong(px, w, h, stroke, radius, rate, feathering, wrapAroundMode)', s, flags=re.S)
    s = re.sub(r'\n\s*// A glee audit found that under Symmetry, only the destination MASK was mirrored\n.*?val copies = buildList \{\n.*?\n\s*\}', '\n                val copies = listOf(({ p: Offset -> p }) to d)', s, flags=re.S)
    s = re.sub(r'\n\s*/\*\*\n\s*\* The point transforms \[mode\].*?\n\s*internal fun symmetryTransforms\(.*?\n\s*\}\n(?=\n\s*/\*\*\n\s*\* Resamples)', '\n', s, flags=re.S)
    s = re.sub(r'internal fun drawStroke\(canvas: Canvas, stroke: List<Offset>, paint: Paint, wrapAroundMode: Boolean = false, symmetryMode: SymmetryMode = SymmetryMode\.NONE\) \{\n\s*if \(symmetryMode != SymmetryMode\.NONE\) \{.*?\n\s*\}\n\s*if \(stroke\.size == 1\)', 'internal fun drawStroke(canvas: Canvas, stroke: List<Offset>, paint: Paint, wrapAroundMode: Boolean = false) {\n        if (stroke.size == 1)', s, flags=re.S)
    s = s.replace('        symmetryMode: SymmetryMode = SymmetryMode.NONE,\n', '')
    s = s.replace('                drawDab(canvas, Offset(centres[j], centres[j + 1]), radius, paint, wrapAroundMode, symmetryMode)', '                drawDab(canvas, Offset(centres[j], centres[j + 1]), radius, paint, wrapAroundMode)')
    s = re.sub(r'/\*\* Draws one filled round dab of \[radius\].*?private fun drawDab\(\n\s*canvas: Canvas,\n\s*center: Offset,\n\s*radius: Float,\n\s*paint: Paint,\n\s*wrapAroundMode: Boolean,\n\s*symmetryMode: SymmetryMode,\n\s*\) \{\n\s*val centres = ArrayList<Offset>\(4\)\n\s*centres\.add\(center\)\n\s*if \(symmetryMode != SymmetryMode\.NONE\) \{.*?\n\s*\}\n', '/** Draws one filled round dab, tiled only when wrap-around is enabled. */\n    private fun drawDab(\n        canvas: Canvas,\n        center: Offset,\n        radius: Float,\n        paint: Paint,\n        wrapAroundMode: Boolean,\n    ) {\n        val centres = listOf(center)\n', s, flags=re.S)
    return s
edit('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/util/ImageProcessor.kt', image_processor)

# Color Smudge: a stroke resolves exactly one plan and applies exactly once.
def color_smudge(s):
    s = s.replace('import com.hereliesaz.graffitixr.common.model.SymmetryMode\n', '')
    s = s.replace('        val symmetryMode: SymmetryMode = SymmetryMode.NONE,\n', '')
    s = re.sub(r'\n\s*val plans = ArrayList<ResolvedPlan>\(\)\n\s*plans \+= one\(stroke, samples\)\n\s*for \(transform in symmetryTransforms\(settings\.symmetryMode, width\.toFloat\(\), height\.toFloat\(\)\)\) \{.*?\n\s*\}\n\s*return plans', '\n        return listOf(one(stroke, samples))', s, flags=re.S)
    s = re.sub(r'\n\s*applyOne\(pixels, width, height, stroke, settings, samples, strokeSeed, sampleSource\)\n\s*for \(transform in symmetryTransforms\(settings\.symmetryMode, width\.toFloat\(\), height\.toFloat\(\)\)\) \{.*?\n\s*\}', '\n        applyOne(pixels, width, height, stroke, settings, samples, strokeSeed, sampleSource)', s, flags=re.S)
    s = re.sub(r'\n\s*private fun symmetryTransforms\(mode: SymmetryMode, w: Float, h: Float\): List<\(Offset\) -> Offset> \{.*?\n\s*\}\n(?=\n\s*private fun)', '\n', s, flags=re.S)
    return s
edit('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/util/ColorSmudgeEngine.kt', color_smudge)

# Replay/commit call sites: remove symmetry arguments and settings assignment.
for path in [
    'feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/DrawingEngine.kt',
    'feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt',
]:
    def strip_calls(s):
        s = s.replace('import com.hereliesaz.graffitixr.common.model.SymmetryMode\n', '')
        s = re.sub(r'\n\s*symmetryMode = [^,\n]+,', '', s)
        s = re.sub(r'\n\s*symmetryMode = [^\n]+', '', s)
        s = re.sub(r'\n\s*val symmetryMode = [^\n]+', '', s)
        s = re.sub(r'\n\s*fun onToggleSymmetry\(\).*?\n', '\n', s)
        s = re.sub(r'\n\s*fun onSetSymmetryMode\(.*?\).*?\n', '\n', s)
        return s
    edit(path, strip_calls)

# Remove stale help wording that advertises the feature.
def rail_help(s):
    s = s.replace(', " +\n        "and which axis the symmetry guide mirrors across while it\'s on', '')
    s = re.sub(r'.*symmetry guide.*\n', '', s, flags=re.I)
    return s
edit('app/src/main/java/com/hereliesaz/graffux/RailHelp.kt', rail_help)

# Delete tests devoted to the removed feature; mixed tests are pruned by test name blocks below.
for path in [
    'feature/editor/src/test/java/com/hereliesaz/graffitixr/feature/editor/CloneSymmetryRenderTest.kt',
]:
    p = ROOT / path
    if p.exists(): p.unlink()

for path in [
    'feature/editor/src/test/java/com/hereliesaz/graffitixr/feature/editor/EditorReducerTest.kt',
    'feature/editor/src/test/java/com/hereliesaz/graffitixr/feature/editor/ProcreateParityTest.kt',
    'feature/editor/src/test/java/com/hereliesaz/graffitixr/feature/editor/CommitPathRoutingTest.kt',
]:
    def prune_tests(s):
        s = s.replace('import com.hereliesaz.graffitixr.common.model.SymmetryMode\n', '')
        # Remove whole @Test functions whose declaration/body mentions symmetry before the next @Test/closing class.
        s = re.sub(r'\n\s*@Test\n\s*fun `[^`]*symmetr[^`]*`\(\).*?(?=\n\s*@Test|\n\})', '\n', s, flags=re.S|re.I)
        s = re.sub(r'\n\s*@Test\n\s*fun `SetSymmetryMode[^`]*`\(\).*?(?=\n\s*@Test|\n\})', '\n', s, flags=re.S)
        return s
    edit(path, prune_tests)

# Remove the model and feature-specific icon assets.
for path in [
    'core/common/src/main/java/com/hereliesaz/graffitixr/common/model/SymmetryMode.kt',
    'branding/icons/masters/symmetry.svg',
    'branding/icons/masters/symmetry-radial.svg',
    'branding/icons/masters/symmetry-quadrant.svg',
    'branding/icons/masters/symmetry-vertical.svg',
    'branding/icons/masters/symmetry-horizontal.svg',
    'core/design/src/main/res/drawable/ic_ps_symmetry.xml',
    'core/design/src/main/res/drawable/ic_gx_symmetry.xml',
    'core/design/src/main/res/drawable/ic_gx_symmetry_radial.xml',
    'core/design/src/main/res/drawable/ic_gx_symmetry_vertical.xml',
    'core/design/src/main/res/drawable/ic_gx_symmetry_quadrant.xml',
    'core/design/src/main/res/drawable/ic_gx_symmetry_horizontal.xml',
]:
    p = ROOT / path
    if p.exists():
        p.unlink()
        print('DELETED:', path)

# Documentation comments should no longer claim symmetry is present.
for path in ['feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/util/KritaPresetMapper.kt']:
    edit(path, lambda s: s.replace('/`symmetryMode`', '').replace('`symmetryMode`/', ''))

print('Remaining code references:')
for p in ROOT.rglob('*.kt'):
    if any(part in {'.gradle', 'build'} for part in p.parts):
        continue
    txt = p.read_text(errors='ignore')
    hits = [x for x in ('SymmetryMode','symmetryMode','ToggleSymmetry','SetSymmetryMode','onToggleSymmetry','onSetSymmetryMode','lastSymmetryMode') if x in txt]
    if hits:
        print(p, hits)
