from pathlib import Path

p = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt')
s = p.read_text()
old = '''                            engine.stampResolvedDabs(gpuDabs, buildUp = brush.buildUp) &&
                                (usesZeroCopyDisplay || engine.readback(work))
                            }
                        }
                    }
                    if (hasNewMovementDabs && !gpuHandled) {
'''
new = '''                            engine.stampResolvedDabs(gpuDabs, buildUp = brush.buildUp) &&
                                (usesZeroCopyDisplay || engine.readback(work))
                        }
                    }
                    if (hasNewMovementDabs && !gpuHandled) {
'''
assert old in s
s = s.replace(old, new, 1)
p.write_text(s)
