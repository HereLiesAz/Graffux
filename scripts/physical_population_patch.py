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

tuft_path = Path("core/engine/src/commonMain/kotlin/com/hereliesaz/graffitixr/common/azphalt/BrushTuftTopology.kt")
tuft = tuft_path.read_text()
old_basis = """        val twist = if (cfg.usesPhysicalPopulation()) contact.tipTwistDeg else 0f
        val localAngleDeg = normalizeDegrees(dragAngleDeg + identity.angleBiasDeg + twist)
        val angleRad = localAngleDeg * TUFT_DEG_TO_RAD
        val dragX = cos(angleRad)
        val dragY = sin(angleRad)
        val lateralX = -dragY
        val lateralY = dragX
        val x = lateralX * lateralFraction +
            dragX * identity.rootLongitudinalFraction -
            dragX * trailingFraction
        val y = lateralY * lateralFraction +
            dragY * identity.rootLongitudinalFraction -
            dragY * trailingFraction
"""
new_basis = """        val physicalPopulation = cfg.usesPhysicalPopulation()
        // Root/ferrule pose and drag are deliberately different frames. Device roll twists the
        // physical tip around its own shaft; changing stroke direction only bends/trails hairs and
        // must not rotate a flat/chisel/fan root array with the path.
        val localDragAngleDeg = normalizeDegrees(dragAngleDeg + identity.angleBiasDeg)
        val dragAngleRad = localDragAngleDeg * TUFT_DEG_TO_RAD
        val dragX = cos(dragAngleRad)
        val dragY = sin(dragAngleRad)
        val rootAngleDeg = if (physicalPopulation) contact.tipTwistDeg else localDragAngleDeg
        val rootAngleRad = rootAngleDeg * TUFT_DEG_TO_RAD
        val rootForwardX = cos(rootAngleRad)
        val rootForwardY = sin(rootAngleRad)
        val rootLateralX = -rootForwardY
        val rootLateralY = rootForwardX
        val rootX = rootLateralX * lateralFraction + rootForwardX * identity.rootLongitudinalFraction
        val rootY = rootLateralY * lateralFraction + rootForwardY * identity.rootLongitudinalFraction
        val x = rootX - dragX * trailingFraction
        val y = rootY - dragY * trailingFraction
"""
count = tuft.count(old_basis)
if count != 1:
    raise SystemExit(f"Expected exactly one physical contact basis block, found {count}")
tuft = tuft.replace(old_basis, new_basis, 1)
tuft = tuft.replace(
    "val localAngleDeg = normalizeDegrees(dragAngleDeg + identity.angleBiasDeg + twist)",
    "val localAngleDeg = localDragAngleDeg",
)
tuft = tuft.replace(
    "val plane = ((x * contact.tipLeanX + y * contact.tipLeanY) / norm).coerceIn(-1f, 1f)",
    "val plane = ((rootX * contact.tipLeanX + rootY * contact.tipLeanY) / norm).coerceIn(-1f, 1f)",
)
tuft = tuft.replace(
    "angleOffsetDeg = wrapSignedDegrees(localAngleDeg - globalDragAngleDeg)",
    "angleOffsetDeg = wrapSignedDegrees(localDragAngleDeg - globalDragAngleDeg)",
)
tuft_path.write_text(tuft)

Path(".github/workflows/physical-population-ui-patch.yml").unlink(missing_ok=True)
Path("scripts/physical_population_patch.py").unlink(missing_ok=True)
