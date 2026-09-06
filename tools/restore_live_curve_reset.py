from pathlib import Path

p = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt')
s = p.read_text()
needle = '''    private fun enqueueBasicLiveDabs(\n'''
helper = '''    /** Resets Basic Brush's per-stroke Catmull-Rom window. */\n    private fun resetLiveCurveState() = synchronized(liveCurveLock) {\n        liveCurveWindow.clear()\n        liveCurveWidths.clear()\n        liveCurveFinalizedCount = 0\n    }\n\n'''
assert needle in s
assert 'private fun resetLiveCurveState()' not in s
s = s.replace(needle, helper + needle, 1)
p.write_text(s)
