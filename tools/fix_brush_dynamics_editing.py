from pathlib import Path

p = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/BrushStudioWindow.kt')
s = p.read_text()

old = '''    if (binding != null) {
        val effectiveInputRange = minOf(inputRange.start, binding.inputMin, binding.inputMax)..
            maxOf(inputRange.endInclusive, binding.inputMin, binding.inputMax)
        val effectiveOutputRange = minOf(outputRange.start, binding.outputMin, binding.outputMax)..
            maxOf(outputRange.endInclusive, binding.outputMin, binding.outputMax)
        ParamSlider("Input min", binding.inputMin, effectiveInputRange, unit = inputUnit) { value ->
            onEdit { it.upsertRoute(binding.copy(inputMin = value.coerceAtMost(binding.inputMax - 0.001f))) }
        }
        ParamSlider("Input max", binding.inputMax, effectiveInputRange, unit = inputUnit) { value ->
            onEdit { it.upsertRoute(binding.copy(inputMax = value.coerceAtLeast(binding.inputMin + 0.001f))) }
        }
'''
new = '''    if (binding != null) {
        // Keep the slider envelope stable for the lifetime of this control. Imported bindings may
        // sit outside the preset range; recomputing from every edited value would make the range
        // shrink behind the thumb and prevent restoring/tuning farther outward values.
        val initialInputRange = remember(defaultBinding.sensor, defaultBinding.parameter) {
            minOf(inputRange.start, binding.inputMin, binding.inputMax)..
                maxOf(inputRange.endInclusive, binding.inputMin, binding.inputMax)
        }
        val initialOutputRange = remember(defaultBinding.sensor, defaultBinding.parameter) {
            minOf(outputRange.start, binding.outputMin, binding.outputMax)..
                maxOf(outputRange.endInclusive, binding.outputMin, binding.outputMax)
        }
        val descendingInput = binding.inputMin > binding.inputMax
        ParamSlider("Input min", binding.inputMin, initialInputRange, unit = inputUnit) { value ->
            val adjusted = if (descendingInput) {
                value.coerceAtLeast(binding.inputMax + 0.001f)
            } else {
                value.coerceAtMost(binding.inputMax - 0.001f)
            }
            onEdit { it.upsertRoute(binding.copy(inputMin = adjusted)) }
        }
        ParamSlider("Input max", binding.inputMax, initialInputRange, unit = inputUnit) { value ->
            val adjusted = if (descendingInput) {
                value.coerceAtMost(binding.inputMin - 0.001f)
            } else {
                value.coerceAtLeast(binding.inputMin + 0.001f)
            }
            onEdit { it.upsertRoute(binding.copy(inputMax = adjusted)) }
        }
'''
if old not in s:
    raise SystemExit('range block not found')
s = s.replace(old, new, 1)
s = s.replace('''ParamSlider("Output min", binding.outputMin, effectiveOutputRange, unit = outputUnit)''', '''ParamSlider("Output min", binding.outputMin, initialOutputRange, unit = outputUnit)''')
s = s.replace('''ParamSlider("Output max", binding.outputMax, effectiveOutputRange, unit = outputUnit)''', '''ParamSlider("Output max", binding.outputMax, initialOutputRange, unit = outputUnit)''')

old2 = '''private fun AzphaltBrush.upsertRoute(binding: BrushSensorBinding): AzphaltBrush {
    val remaining = dynamics.filterNot { it.sensor == binding.sensor && it.parameter == binding.parameter }
    return copy(dynamics = remaining + binding)
}
'''
new2 = '''private fun AzphaltBrush.upsertRoute(binding: BrushSensorBinding): AzphaltBrush {
    val index = dynamics.indexOfFirst { it.sensor == binding.sensor && it.parameter == binding.parameter }
    if (index < 0) return copy(dynamics = dynamics + binding)
    val updated = dynamics.toMutableList()
    updated[index] = binding
    return copy(dynamics = updated)
}
'''
if old2 not in s:
    raise SystemExit('upsert block not found')
s = s.replace(old2, new2, 1)

p.write_text(s)
