from pathlib import Path

path = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt')
text = path.read_text()

def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{label}: expected 1 occurrence, found {count}')
    text = text.replace(old, new, 1)

replace_once(
    'import com.hereliesaz.graffitixr.common.azphalt.BrushSample\n',
    'import com.hereliesaz.graffitixr.common.azphalt.BrushSample\nimport com.hereliesaz.graffitixr.common.azphalt.cappedForPerformanceTier\n',
    'performance cap import',
)
replace_once(
    '    private val _uiState = MutableStateFlow(EditorUiState())\n',
    '    private val brushPerformanceTier = BrushPerformanceTierResolver.resolve(context)\n\n    private val _uiState = MutableStateFlow(EditorUiState())\n',
    'performance tier property',
)
replace_once(
    '        val stampBrush = activeStampBrush\n        if (stampBrush != null && state.activeTool == Tool.BRUSH) {\n',
    '        // Freeze the capability-tier tuft cap into this stroke snapshot before either the live\n        // generator or canonical replay sees it. The selected preset itself remains untouched.\n        val stampBrush = activeStampBrush?.cappedForPerformanceTier(brushPerformanceTier)\n        if (stampBrush != null && state.activeTool == Tool.BRUSH) {\n',
    'stroke brush snapshot',
)
path.write_text(text)
