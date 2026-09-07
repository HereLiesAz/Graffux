from pathlib import Path

root = Path('.')

# Version catalog: one version drives Android + CMP/Desktop consumers.
p = root / 'gradle/libs.versions.toml'
s = p.read_text()
old = 'azNavRail = "11.45"'
new = 'azNavRail = "11.47"'
if old not in s:
    raise SystemExit('AzNavRail 11.45 version marker not found')
p.write_text(s.replace(old, new, 1))

# Make the stroke latch observable by Compose so host-level Az chrome visibility reacts immediately.
p = root / 'feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/MultiFingerTaps.kt'
s = p.read_text()
if 'import androidx.compose.runtime.mutableStateOf' not in s:
    s = s.replace(
        'import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.rememberUpdatedState\n',
        'import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.getValue\nimport androidx.compose.runtime.mutableStateOf\nimport androidx.compose.runtime.rememberUpdatedState\nimport androidx.compose.runtime.setValue\n',
        1,
    )
s = s.replace(
    '    var strokeActive: Boolean = false\n',
    '    var strokeActive: Boolean by mutableStateOf(false)\n',
    1,
)
p.write_text(s)

# Android host: adopt 11.47 global visibility and bounded unattached hosts.
p = root / 'app/src/main/java/com/hereliesaz/graffux/MainActivity.kt'
s = p.read_text()
needle = '    val strokeGate = remember { StrokeGate() }\n'
insert = '''    val strokeGate = remember { StrokeGate() }\n    // AzNavRail 11.47 keeps its chrome composed while hiding it, so rail state, floating positions,\n    // nested state and window state survive a stroke. Capture mode and active drawing now use that\n    // one host-level visibility contract instead of each Az surface having to disappear independently.\n    val azChromeVisible = !uiState.hideUiForCapture && !strokeGate.strokeActive\n    // Unattached hosts are scroll viewports in 11.47. Keep them comfortably inside the usable\n    // screen while still letting the library apply its own stricter safe-viewport cap if necessary.\n    val unattachedRailMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp\n'''
if needle not in s:
    raise SystemExit('strokeGate marker not found')
s = s.replace(needle, insert, 1)
old_host = '        AzHostActivityLayout(navController = navController, initiallyExpanded = false) {'
new_host = '''        AzHostActivityLayout(\n            navController = navController,\n            initiallyExpanded = false,\n            azVisible = azChromeVisible,\n            azVisibilityAnimation = AzVisibilityAnimation.NEAREST_EDGE,\n            azVisibilityDurationMillis = 160,\n        ) {'''
if old_host not in s:
    raise SystemExit('AzHostActivityLayout marker not found')
s = s.replace(old_host, new_host, 1)
# All current unattached hosts use one of these two anchors. Bounded height activates 11.47's\n# automatic subtree scrolling, including nested children.
s = s.replace(
    'anchor = AzUnattachedAnchor.FLOATING,',
    'anchor = AzUnattachedAnchor.FLOATING, maxHeight = unattachedRailMaxHeight,',
)
s = s.replace(
    'anchor = AzUnattachedAnchor.OPPOSITE,',
    'anchor = AzUnattachedAnchor.OPPOSITE, maxHeight = unattachedRailMaxHeight,',
)
p.write_text(s)

# Update comments that pin the old library version in desktop docs/source.
for rel in ['DESKTOP.md', 'desktop/src/main/kotlin/com/hereliesaz/graffux/desktop/FloatingWindow.kt', 'desktop/src/main/kotlin/com/hereliesaz/graffux/desktop/Main.kt']:
    p = root / rel
    s = p.read_text()
    s = s.replace('11.45', '11.47')
    p.write_text(s)

print('AzNavRail 11.47 migration applied')
