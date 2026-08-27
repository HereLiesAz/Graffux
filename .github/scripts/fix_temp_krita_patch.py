from pathlib import Path

patch = Path('.github/scripts/temp_krita_color_source_patch.py')
text = patch.read_text()
anchor = '''def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1))
'''
addition = anchor + '''

def replace_first(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count < 1:
        raise SystemExit(f"{path}: expected at least one match: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1))
'''
if text.count(anchor) != 1:
    raise SystemExit('replace_once helper anchor changed')
text = text.replace(anchor, addition, 1)

needles = [
    "'''            maskStamp,\n            seed,\n        )''',",
    "'''                    stroke.stampShape, stroke.stampGrain, stroke.stampMaskShape,\n                )''',",
]
for quoted in needles:
    needle = "replace_once(path,\n" + quoted
    replacement = "replace_first(path,\n" + quoted
    count = text.count(needle)
    if count != 2:
        raise SystemExit(f'expected two paired forwarding guards, found {count}: {quoted[:70]}')
    text = text.replace(needle, replacement)

patch.write_text(text)
Path(__file__).unlink()
