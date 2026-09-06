from pathlib import Path

p = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt')
s = p.read_text()

# Resolved (non-masked) Airbrush held dabs can stay entirely on the Vulkan layer, so they no longer
# disqualify an otherwise zero-copy stroke. Impasto still needs CPU-visible colour for regional
# shading, and masked Airbrush remains CPU-held until the masked shader gains explicit buildUp mode.
s = s.replace(
'''                val zeroCopyEligible = gpuReady && stampBrush.airbrushDabsPerSecond <= 0f &&\n                    stampBrush.impastoThicknessRate <= 0f\n''',
'''                val zeroCopyEligible = gpuReady && stampBrush.impastoThicknessRate <= 0f &&\n                    (stampBrush.airbrushDabsPerSecond <= 0f || !usesMaskedPipeline)\n''', 1)

# Track whether the held-dab source was handled by Vulkan independently of movement.
s = s.replace(
'''                    var gpuHandled = false\n''',
'''                    var gpuHandled = false\n                    var gpuHandledHeld = false\n''', 1)

# After movement handling/fallback, submit resolved held dabs sequentially with buildUp=true.
needle = '''                    if (hasNewHeldDabs) {\n                        // Airbrush held dabs are always painted on the CPU, matching the reference\n'''
replacement = '''                    if (hasNewHeldDabs && !usesMasked && engine != null &&\n                        ((!hasNewMovementDabs && gpuActive) || gpuHandled)\n                    ) {\n                        fun resolveHeld(dab: Dab) = ResolvedBrushDab(\n                            x = dab.x,\n                            y = dab.y,\n                            radius = dab.radius,\n                            alpha = dab.alpha,\n                            angleDeg = dab.angleDeg,\n                            colorArgb = StampBrushRenderer.resolvedColor(\n                                colorArgb, secondaryColorArgb, brush, dab,\n                            ),\n                            flow = (baseFlow * dab.flowMultiplier).coerceAtLeast(0f),\n                            hardness = dab.hardness,\n                        )\n                        val gpuHeldDabs = newHeldDabs.map(::resolveHeld)\n                        gpuHandledHeld = engine.stampResolvedDabs(gpuHeldDabs, buildUp = true) &&\n                            (usesZeroCopyDisplay || engine.readback(work))\n                        if (!gpuHandledHeld && gpuActive) {\n                            synchronized(stampLiveLock) {\n                                if (stampGpuEngine === engine) {\n                                    stampGpuActive = false\n                                    stampGpuEngine = null\n                                    stampGpuDisplay?.close()\n                                    stampGpuDisplay = null\n                                    engine.destroy()\n                                }\n                            }\n                        }\n                    }\n                    if (hasNewHeldDabs && !gpuHandledHeld) {\n                        // CPU fallback for held dabs, including masked tips and any Vulkan failure.\n'''
assert needle in s
s = s.replace(needle, replacement, 1)

p.write_text(s)

# Commit/replay must enter the telemetry-aware branch for Airbrush even when no other sensor/taper
# dynamics are configured; otherwise held dabs disappear on finger-up.
p = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/DrawingEngine.kt')
s = p.read_text()
s = s.replace(
'''            val needsDynamicDabs = brush.dynamics.isNotEmpty() || hasMaskDynamics || brush.taper.isActive()\n''',
'''            val needsDynamicDabs = brush.dynamics.isNotEmpty() || hasMaskDynamics || brush.taper.isActive() ||\n                brush.airbrushDabsPerSecond > 0f\n''', 1)
p.write_text(s)
