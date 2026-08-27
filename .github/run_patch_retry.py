from pathlib import Path

p = Path('.github/patch_brush_telemetry_once.py')
script = p.read_text()

old = '''end_body = end_body.replace(
    "                path = points,\\n",
    "                path = points,\\n                brushSamples = brushSamples,\\n",
)
end_body = end_body.replace(
    "            path = points,\\n",
    "            path = points,\\n            brushSamples = brushSamples,\\n",
)
'''
new = '''_recorded = []
for _line in end_body.splitlines(keepends=True):
    _recorded.append(_line)
    if _line.strip() == "path = points,":
        _indent = _line[:len(_line) - len(_line.lstrip())]
        _recorded.append(f"{_indent}brushSamples = brushSamples,\\n")
end_body = "".join(_recorded)
'''
if script.count(old) != 1:
    raise RuntimeError('could not narrow onStrokeEnd path insertions')
script = script.replace(old, new, 1)

old = '''commit_tail = replace_once(
    commit_tail,
    "            path = points,\\n",
    "            path = points,\\n            brushSamples = brushSamples,\\n",
    "stamp StrokeCommand telemetry",
)
'''
new = '''_stamp_path = "            path = points,\\n"
if _stamp_path not in commit_tail:
    raise RuntimeError("stamp StrokeCommand telemetry: path not found")
commit_tail = commit_tail.replace(
    _stamp_path,
    "            path = points,\\n            brushSamples = brushSamples,\\n",
    1,
)
'''
if script.count(old) != 1:
    raise RuntimeError('could not narrow stamp command insertion')
script = script.replace(old, new, 1)

exec(compile(script, str(p), 'exec'))
