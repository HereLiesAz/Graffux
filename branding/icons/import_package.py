"""Turn a delivered icon package into masters this repo can build from.

The set is authored elsewhere and arrives as a directory of SVGs plus a manifest. Those
files are not the masters: they render correctly on their own but disagree with each other
in ways that only show up once 404 of them sit in the same rail.

What this fixes, and why each one matters:

**Two coordinate spaces.** 96 of the drawings are Phosphor's, on a 256-unit grid inside a
`<group>` that scales them to share our live area. Left alone that means two viewports in
one set, two different meanings for "0.5 units", and an Android drawable whose
`viewportWidth` depends on who drew it. The transform is baked into the coordinates and the
group removed, so every master is on the 24 grid and nothing downstream has to care.

**A hatch that does not line up.** The rules are supposed to be anchored to the viewport, so
that any two ruled icons look ruled from one plate. In the delivered files the Graffux
drawings put their first rule at y=0.294 and the Phosphor ones — whose pattern is declared
in 256-space and then scaled by the group — land it at y=3.654. That is 3.2 pitches, so
they sit a fifth of a pitch out of phase with everything else. Baking the transform puts
the pattern back in 24-space where the shared phase applies by construction.

**One pattern id for the whole set.** Every file declares `#gfxHatch`. Inline more than one
into a page — which the contact sheet does 404 times — and every glyph after the first
borrows the first one's pattern. Each master gets an id carrying its own key.

Style is resolved into each path rather than inherited from the root, because the two
constructions in the set disagree about the default: ours is a stroked outline on
`fill="none"`, Phosphor's is a filled shape with no stroke at all. Writing both explicitly
is what lets one emitter read either without a special case.

Run it as `python3 branding/icons/import_package.py <package>/exports`.
"""

from __future__ import annotations

import json
import math
import os
import re
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import svgpath  # noqa: E402
from grid import CAP, HATCH_PHASE, HATCH_PITCH, HATCH_WEIGHT, JOIN, STROKE, VIEW  # noqa: E402

MASTERS = os.path.join(HERE, "masters")
OVERRIDES = os.path.join(HERE, "overrides")
META_PATH = os.path.join(HERE, "meta.json")
SVG_NS = "http://www.w3.org/2000/svg"

INHERITED = ("fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "fill-rule")


# ---------------------------------------------------------------------------------------
# Transforms
# ---------------------------------------------------------------------------------------

IDENTITY = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)


def multiply(m1, m2):
    """`m1 ∘ m2` — the matrix that applies m2 first, then m1."""
    a1, b1, c1, d1, e1, f1 = m1
    a2, b2, c2, d2, e2, f2 = m2
    return (
        a1 * a2 + c1 * b2,
        b1 * a2 + d1 * b2,
        a1 * c2 + c1 * d2,
        b1 * c2 + d1 * d2,
        a1 * e2 + c1 * f2 + e1,
        b1 * e2 + d1 * f2 + f1,
    )


def parse_transform(text: str):
    """An SVG `transform` attribute to a matrix. Handles the forms the packages use."""
    m = IDENTITY
    for name, args in re.findall(r"(\w+)\s*\(([^)]*)\)", text or ""):
        v = [float(x) for x in re.findall(r"[-+]?[\d.]+(?:[eE][-+]?\d+)?", args)]
        if name == "translate":
            m = multiply(m, (1.0, 0.0, 0.0, 1.0, v[0], v[1] if len(v) > 1 else 0.0))
        elif name == "scale":
            m = multiply(m, (v[0], 0.0, 0.0, v[1] if len(v) > 1 else v[0], 0.0, 0.0))
        elif name == "rotate":
            th = math.radians(v[0])
            r = (math.cos(th), math.sin(th), -math.sin(th), math.cos(th), 0.0, 0.0)
            if len(v) == 3:
                r = multiply(multiply((1.0, 0, 0, 1.0, v[1], v[2]), r), (1.0, 0, 0, 1.0, -v[1], -v[2]))
            m = multiply(m, r)
        elif name == "matrix":
            m = multiply(m, tuple(v[:6]))
        else:
            raise ValueError(f"unsupported transform {name!r}")
    return m


def is_uniform(m, eps=1e-9):
    a, b, c, d, _, _ = m
    return abs(b) < eps and abs(c) < eps and abs(a - d) < eps


def apply(d: str, m):
    """Move path data through `m`, keeping arcs whenever that is safe."""
    segs = svgpath.parse(d)
    if is_uniform(m):
        return svgpath.to_d(svgpath.transform(segs, m[0], m[4], m[5]))
    return svgpath.to_d(svgpath.affine(svgpath.to_absolute(segs), *m))


# ---------------------------------------------------------------------------------------
# Reading a delivered file
# ---------------------------------------------------------------------------------------


def tag(el):
    return el.tag.split("}")[-1]


def circle_to_path(cx, cy, r) -> str:
    """A circle as two half-arcs, so everything downstream only handles path data."""
    n = svgpath.fmt
    return (f"M{n(cx - r)} {n(cy)}A{n(r)} {n(r)} 0 1 0 {n(cx + r)} {n(cy)}"
            f"A{n(r)} {n(r)} 0 1 0 {n(cx - r)} {n(cy)}Z")


def shapes(el, style, m, out):
    """Walk the tree, resolving inherited style and accumulating the transform."""
    style = dict(style)
    for k in INHERITED:
        if el.get(k) is not None:
            style[k] = el.get(k)
    m = multiply(m, parse_transform(el.get("transform", "")))

    name = tag(el)
    if name == "defs":
        return
    if name == "path" and el.get("d"):
        out.append((apply(el.get("d"), m), style))
    elif name == "circle":
        cx, cy, r = (float(el.get(a, 0)) for a in ("cx", "cy", "r"))
        out.append((apply(circle_to_path(cx, cy, r), m), style))
    elif name in ("line", "rect", "polygon", "polyline", "ellipse"):
        raise ValueError(f"<{name}> outside <defs> is not handled — convert it to a path")
    for child in el:
        shapes(child, style, m, out)


def read(path):
    """A delivered SVG to `[(path_data, style), ...]`, all of it on the 24 grid."""
    root = ET.parse(path).getroot()
    vb = [float(x) for x in re.split(r"[\s,]+", root.get("viewBox", f"0 0 {VIEW} {VIEW}").strip())]
    u = VIEW / vb[2]
    base = multiply((u, 0.0, 0.0, u, -vb[0] * u, -vb[1] * u), IDENTITY)

    style = {"fill": root.get("fill", "black"), "stroke": root.get("stroke", "none")}
    for k in INHERITED[2:]:
        if root.get(k) is not None:
            style[k] = root.get(k)

    out = []
    for child in root:
        shapes(child, style, base, out)

    # The stroke width is a length, so it scales with the drawing like everything else.
    for _, st in out:
        if "stroke-width" in st:
            st["stroke-width"] = svgpath.fmt(float(st["stroke-width"]) * u)
    return out


# ---------------------------------------------------------------------------------------
# Writing a master
# ---------------------------------------------------------------------------------------


def hatch_id(key: str) -> str:
    return "gfxHatch-" + key


def master_svg(key: str, source: str, drawn) -> str:
    n = svgpath.fmt
    ruled = any(st.get("fill", "").startswith("url(") for _, st in drawn)
    lines = [
        f'<svg xmlns="{SVG_NS}" viewBox="0 0 {int(VIEW)} {int(VIEW)}"',
        f'     width="{int(VIEW)}" height="{int(VIEW)}" fill="none" stroke="currentColor"',
        f'     stroke-width="{STROKE}" stroke-linecap="{CAP}" stroke-linejoin="{JOIN}">',
        f"  <!-- graffux/{key} — {source} -->",
    ]
    if ruled:
        y = HATCH_PHASE * HATCH_PITCH
        lines += [
            "  <defs>",
            f'    <pattern id="{hatch_id(key)}" patternUnits="userSpaceOnUse"',
            f'             width="{n(HATCH_PITCH * 4)}" height="{n(HATCH_PITCH)}">',
            f'      <line x1="0" y1="{n(y)}" x2="{n(HATCH_PITCH * 4)}" y2="{n(y)}"',
            f'            stroke="currentColor" stroke-width="{HATCH_WEIGHT}"/>',
            "    </pattern>",
            "  </defs>",
        ]

    for d, st in drawn:
        attrs = [f'd="{d}"']
        fill = st.get("fill", "none")
        if fill.startswith("url("):
            attrs.append(f'fill="url(#{hatch_id(key)})"')
        elif fill not in ("none",):
            attrs.append('fill="currentColor"')
        stroke = st.get("stroke", "none")
        if stroke == "none":
            attrs.append('stroke="none"')
        else:
            for k, default in (("stroke-width", str(STROKE)), ("stroke-linecap", CAP),
                               ("stroke-linejoin", JOIN)):
                if k in st and st[k] != default:
                    attrs.append(f'{k}="{st[k]}"')
        if st.get("fill-rule") == "evenodd" and fill != "none":
            attrs.append('fill-rule="evenodd"')
        lines.append("  <path " + " ".join(attrs) + "/>")

    lines.append("</svg>")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------------------


def derive_keywords(key: str):
    return sorted({w for w in key.split("-") if len(w) > 2})


def main(argv):
    if len(argv) != 2:
        print(__doc__.strip().splitlines()[-1])
        return 2
    src = argv[1]
    manifest = {i["key"]: i for i in json.load(open(os.path.join(src, "icons.json")))}

    meta = json.load(open(META_PATH)) if os.path.exists(META_PATH) else {}
    # A first run has no meta of its own; the previous build's manifest carries the category
    # and search keywords for every key, and those outlive any particular set of drawings.
    legacy = os.path.join(HERE, "dist", "icons.json")
    if not meta and os.path.exists(legacy):
        for ic in json.load(open(legacy))["icons"]:
            meta[ic["key"]] = {"category": ic["category"], "keywords": ic["keywords"]}

    os.makedirs(MASTERS, exist_ok=True)
    for stale in os.listdir(MASTERS):
        if stale.endswith(".svg") and stale[:-4] not in manifest:
            os.remove(os.path.join(MASTERS, stale))

    fresh, failed, held = [], [], []
    for key, entry in manifest.items():
        # An override is a drawing this repo owns because the delivered one could not ship —
        # two keys that rendered identically, a glyph off the grid, a marquee missing a side.
        # It wins over the package so that re-importing a later drop cannot quietly undo the
        # fix; overrides/README.md says why each one exists, and deleting the file is how you
        # go back to whatever the package now supplies.
        override = os.path.join(OVERRIDES, key + ".svg")
        if os.path.exists(override):
            with open(override, encoding="utf-8") as fh:
                text = fh.read()
            with open(os.path.join(MASTERS, key + ".svg"), "w", encoding="utf-8") as out:
                out.write(text)
            held.append(key)
            meta.setdefault(key, {"category": entry.get("family", "ui"), "keywords": derive_keywords(key)})
            # Lineage follows the drawing, not the key. Three of the overrides replace what
            # the package sourced from Phosphor; keeping the package's answer would credit
            # Phosphor for geometry that is not theirs and inflate the count in the NOTICE.
            # The override states its own source in its header comment.
            declared = re.search(r"<!--\s*graffux/\S+\s+—\s+(.*?)\s*-->", text)
            meta[key]["source"] = declared.group(1) if declared else "graffux"
            continue
        path = os.path.join(src, "svg", key + ".svg")
        try:
            drawn = read(path)
        except Exception as exc:  # noqa: BLE001 — the key matters more than the traceback
            failed.append(f"{key}: {type(exc).__name__}: {exc}")
            continue
        with open(os.path.join(MASTERS, key + ".svg"), "w", encoding="utf-8") as fh:
            fh.write(master_svg(key, entry["source"], drawn))
        if key not in meta:
            meta[key] = {"category": entry.get("family", "ui"), "keywords": derive_keywords(key)}
            fresh.append(key)
        meta[key]["source"] = entry["source"]

    meta = {k: meta[k] for k in meta if k in manifest}
    with open(META_PATH, "w", encoding="utf-8") as fh:
        json.dump(meta, fh, indent=2, sort_keys=True)
        fh.write("\n")

    print(f"{len(manifest) - len(failed)}/{len(manifest)} masters written to {os.path.relpath(MASTERS)}")
    if held:
        print(f"  {len(held)} kept from overrides/ instead of the package: {', '.join(sorted(held))}")
    if fresh:
        print(f"  {len(fresh)} new key(s) with derived keywords — check meta.json: {', '.join(fresh[:8])}")
    for f in failed:
        print(f"  FAILED {f}", file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
