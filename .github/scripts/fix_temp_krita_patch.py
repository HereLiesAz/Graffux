from pathlib import Path

patch = Path('.github/scripts/temp_krita_color_source_patch.py')
text = patch.read_text()
anchor = '''def replace_once(path: str, old: str, new: str) -> None:\n    p = Path(path)\n    text = p.read_text()\n    count = text.count(old)\n    if count != 1:\n        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old[:100]!r}")\n    p.write_text(text.replace(old, new, 1))\n'''
addition = anchor + '''\n\ndef replace_first(path: str, old: str, new: str) -> None:\n    p = Path(path)\n    text = p.read_text()\n    count = text.count(old)\n    if count < 1:\n        raise SystemExit(f"{path}: expected at least one match: {old[:100]!r}")\n    p.write_text(text.replace(old, new, 1))\n'''
if text.count(anchor) != 1:
    raise SystemExit('replace_once helper anchor changed')
text = text.replace(anchor, addition, 1)
needle = "replace_once(path,\n'''            maskStamp,\\n            seed,\\n        )''',"
count = text.count(needle)
if count != 2:
    raise SystemExit(f'expected two renderer forwarding guards, found {count}')
text = text.replace(needle, "replace_first(path,\n'''            maskStamp,\\n            seed,\\n        )''',")
patch.write_text(text)
Path(__file__).unlink()
