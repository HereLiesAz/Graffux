from pathlib import Path

vm_path = Path("feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt")
vm = vm_path.read_text()
old_gate = "val needsDynamicDabs = stampBrush.dynamics.isNotEmpty() || hasMaskDynamics || stampBrush.taper.isActive() || stampBrush.blot.isActive()"
new_gate = old_gate + " || stampBrush.contact.isActive()"
count = vm.count(old_gate)
if count != 1:
    raise SystemExit(f"Expected exactly one live dynamic gate, found {count}")
vm = vm.replace(old_gate, new_gate, 1)

marker = """    val customBrushes: StateFlow<List<com.hereliesaz.graffitixr.data.brush.CustomBrush>> =
        customBrushRepository.brushes
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
"""
accessor = marker + """
    /** Current resolved Azphalt brush for topology-aware hover rendering; null = basic round brush. */
    internal fun activeBrushForPreview(): com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush? = activeStampBrush
"""
if marker not in vm:
    raise SystemExit("Custom brush StateFlow marker not found")
if "activeBrushForPreview()" not in vm:
    vm = vm.replace(marker, accessor, 1)
vm_path.write_text(vm)

screen_path = Path("feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorScreen.kt")
screen = screen_path.read_text()
old_call = """                activeColor = uiState.activeColor,
                layerBitmapKey = activeLayer.bitmap,
                gate = strokeGate,
"""
new_call = """                activeColor = uiState.activeColor,
                layerBitmapKey = activeLayer.bitmap,
                gate = strokeGate,
                activeBrushPreview = vm.activeBrushForPreview(),
"""
count = screen.count(old_call)
if count != 1:
    raise SystemExit(f"Expected exactly one DrawingCanvas brush call marker, found {count}")
screen = screen.replace(old_call, new_call, 1)
screen_path.write_text(screen)

Path(".github/workflows/physical-population-ui-patch.yml").unlink(missing_ok=True)
Path("scripts/physical_population_patch.py").unlink(missing_ok=True)
