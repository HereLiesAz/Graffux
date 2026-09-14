from pathlib import Path

path = Path('feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt')
text = path.read_text(encoding='utf-8')

round_old = '''                                flow = (baseFlow * dab.flowMultiplier).coerceAtLeast(0f),
                                hardness = dab.hardness,
                            )'''
round_new = '''                                flow = (baseFlow * dab.flowMultiplier).coerceAtLeast(0f),
                                hardness = dab.hardness,
                                contactDepth = dab.contactDepth,
                            )'''
masked_old = '''                                        flow = (baseFlow * dab.flowMultiplier).coerceAtLeast(0f),
                                        tipRatio = dab.tipRatio,
                                    )'''
masked_new = '''                                        flow = (baseFlow * dab.flowMultiplier).coerceAtLeast(0f),
                                        tipRatio = dab.tipRatio,
                                        contactDepth = dab.contactDepth,
                                    )'''

if text.count(round_old) != 2:
    raise SystemExit(f'expected exactly 2 resolved round constructors, found {text.count(round_old)}')
if text.count(masked_old) != 2:
    raise SystemExit(f'expected exactly 2 masked constructors, found {text.count(masked_old)}')
text = text.replace(round_old, round_new)
text = text.replace(masked_old, masked_new)
path.write_text(text, encoding='utf-8')
