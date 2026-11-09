#!/usr/bin/env python3
"""Generates the four painterly media packs and the pigment pack (spec/painterly.md 5.5, phase P7).

Output: spec/painterly-packs/com.hereliesaz.azphalt.painterly.{acrylic,oil,water,ink,pigments}/
Each folder is a copy-ready azphalt submission (manifest.json without `files`, LICENSE, assets/), laid
out like the existing brush submissions. Re-run after editing; the generated files are committed.
Pigment data is PLACEHOLDER (hand-tuned, not measured), see spec section 6.1.
"""
import json, math, os, shutil, struct, zlib

ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "spec", "painterly-packs")
LICENSE_SRC = "/home/user/azphalt/LICENSE"
PREFIX = "com.hereliesaz.azphalt.painterly."

def png(path, size, pixel):
    raw = b"".join(b"\x00" + bytes(v for x in range(size) for v in pixel(x, y, size)) for y in range(size))
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 0, 0, 0, 0)) + \
        chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    open(path, "wb").write(data)

def round_tip(x, y, s):
    d = math.hypot(x + .5 - s / 2, y + .5 - s / 2) / (s / 2)
    return (int(255 * max(0, min(1, (1 - d) / 0.35))),)

def flat_tip(x, y, s):
    dx = abs(x + .5 - s / 2) / (s / 2); dy = abs(y + .5 - s / 2) / (s / 2 * 0.45)
    d = max(dx, dy)
    return (int(255 * max(0, min(1, (1 - d) / 0.12))),)

LANES = r'''let pr = clamp(pressure, 0, 1);
let sw = {wmin} + (1 - {wmin}) * pr;
if (phase != 3) {{
  if (index <= 1) {{ dx = cos(heading); dy = sin(heading); }} else {{
    dx = lerp(dx, cos(heading), {follow}); dy = lerp(dy, sin(heading), {follow});
  }}
}}
let dl = hypot(dx, dy);
let nx = dl > 0.001 ? dx / dl : cos(heading);
let ny = dl > 0.001 ? dy / dl : sin(heading);
dx = nx; dy = ny;
let ang = atan2(ny, nx);
hide = 1;
let n = clamp(floor(d0 / {pitch}), 6, 32);
if (phase != 3) {{
  lastW = sw;
  let wspan = d0 * sw * {span};
  let cw = wspan / n;
  let len = max(stepPx * 2.4, cw * 2.2);
  for i in 0..32 {{
    if (i < n) {{
      let u = (i + 0.5) / n - 0.5;
      let e = abs(u) * 2;
      let streak = {streakLo} + {streakRange} * noise(i * 9.1 + dist / (d0 * 1.6));
      let gate = clamp(loadAmount(u * 2) * 1.5 + {dryFloor}, 0, 1);
      let tooth = noise2(i * 3.7, dist / (d0 * 0.8));
      let on = {onExpr};
      let a = baseAlpha * streak * on;
      if (a > 0.03) {{
        emit {{ x: px - ny * u * wspan, y: py + nx * u * wspan, size: len, tip: clamp(cw * 1.15 / len, 0.05, 1), angle: ang,
               alpha: a, lane: u * 2, draw: stepPx * {draw}, lift: stepPx * {lift}, mixWet: {mixWet},
               h: {hBase} + {hRim} * smoothstep(0.55, 0.95, e) }};
      }}
    }}
  }}
}}
if (phase == 3) {{
  let reach = clamp(layerSpeed * {tail}, 0, d0 * 2.4);
  let wspan = d0 * lastW * {span};
  if (reach > 2) {{
    for k in 0..8 {{
      let f = (k + 1) / 8;
      let w = pow(1 - f, 1.3) * wspan;
      let bx = px + nx * reach * f;
      let by = py + ny * reach * f;
      let seg = reach / 8 * 2.2;
      emit {{ x: bx, y: by, size: seg, tip: clamp(max(w * 0.45, 1) / seg, 0.05, 1), angle: ang, alpha: baseAlpha * 0.9, lane: 0, draw: stepPx * {draw} }};
    }}
  }}
}}'''

KNIFE = r'''let pr = clamp(pressure, 0, 1);
if (index <= 1) {{ dx = cos(heading); dy = sin(heading); }} else {{ dx = lerp(dx, cos(heading), 0.2); dy = lerp(dy, sin(heading), 0.2); }}
let dl = hypot(dx, dy);
let nx = dl > 0.001 ? dx / dl : cos(heading);
let ny = dl > 0.001 ? dy / dl : sin(heading);
dx = nx; dy = ny;
let ang = atan2(ny, nx);
hide = 1;
if (phase != 3) {{
  let w = d0 * (0.7 + 0.3 * pr);
  let len = max(stepPx * 2.2, 6);
  // a slab: thin clean blade, edge ridges, lifts at the trailing edge and lays at the leading edge
  emit {{ x: px, y: py, size: len, tip: clamp(w / len, 0.05, 1), angle: ang + 90, alpha: baseAlpha, lane: 0, draw: stepPx * {draw}, lift: stepPx * {lift}, mixWet: {mixWet}, h: {hBase} }};
  emit {{ x: px - ny * w * 0.5, y: py + nx * w * 0.5, size: len, tip: 0.1, angle: ang, alpha: baseAlpha, h: {hRim} }};
  emit {{ x: px + ny * w * 0.5, y: py - nx * w * 0.5, size: len, tip: 0.1, angle: ang, alpha: baseAlpha, h: {hRim} }};
}}'''

LIFT = r'''hide = 1;
if (phase != 3) {{
  emit {{ x: px, y: py, size: d0 * (0.6 + 0.4 * pressure), alpha: 0.02, lane: 0, lift: stepPx * {lift}{dispose} }};
}}'''

def base(name, spacing, extra=None, **kw):
    p = {"name": name, "spacing": spacing, "opacity": 1, "painterly": 1}
    p.update(extra or {})
    p.update(kw)
    return p

def lane_brush(name, medium, tip, load, spacing=0.12, **kw):
    cfg = dict(wmin=0.3, follow=0.35, pitch=1.7, span=1.0, streakLo=0.72, streakRange=0.28, dryFloor=0.25,
               onExpr="gate", draw=0.004, lift=0.0, mixWet=0.5, hBase=0.6, hRim=1.8, tail=130)
    cfg.update(kw)
    src = LANES.format(**cfg).split("\n")
    p = base(name, spacing, load=load, **medium)
    p["program"] = {"state": {"d0": 26, "dx": 1, "dy": 0, "lastW": 0.5}, "init": ["d0 = diameter;"], "step": src}
    return p, tip

def knife_brush(name, medium, load, **kw):
    cfg = dict(draw=0.004, lift=0.003, mixWet=0.7, hBase=1.4, hRim=2.2)
    cfg.update(kw)
    p = base(name, 0.14, load=load, **medium)
    p["program"] = {"state": {"d0": 26, "dx": 1, "dy": 0}, "init": ["d0 = diameter;"], "step": KNIFE.format(**cfg).split("\n")}
    return p, "flat"

def lift_brush(name, medium, dispose=False, lift=0.02):
    p = base(name, 0.12, load={"lanes": 4, "depth": 2, "capacity": 1.0, "fill": []}, **medium)
    p["program"] = {"state": {"d0": 26}, "init": ["d0 = diameter;"],
                    "step": LIFT.format(lift=lift, dispose=", dispose: 1" if dispose else "").split("\n")}
    return p, "round"

def plain(name, spacing, tip, **kw):
    p = {"name": name, "spacing": spacing, "opacity": 1}
    p.update(kw)
    return p, tip

LOAD = lambda lanes=16, depth=3, cap=1.0: {"lanes": lanes, "depth": depth, "capacity": cap, "fill": []}
PRESS = lambda lo=0.25: [{"sensor": "pressure", "parameter": "size", "outputMin": lo, "outputMax": 1}]

ACRYLIC = dict(impastoThicknessRate=0.3, impastoMaterial={"version": 2, "enabled": True, "initialLoad": 1, "wetness": 0.35,
    "pickupRate": 0.25, "levelingRate": 0.04, "viscosity": 0.6, "yieldLikeStrength": 0.55, "dryingRate": 0.18,
    "substrateResponse": 0.3, "baseRoughness": 0.35, "wetSpecularStrength": 0.4, "sheen": 0.45}, substrateResponse=0.3)
OIL = dict(impastoThicknessRate=0.35, impastoMaterial={"version": 2, "enabled": True, "initialLoad": 1, "wetness": 0.8,
    "pickupRate": 0.4, "levelingRate": 0.08, "viscosity": 0.85, "yieldLikeStrength": 0.7, "dryingRate": 0.03,
    "substrateResponse": 0.35, "baseRoughness": 0.3, "wetSpecularStrength": 0.6, "sheen": 0.6}, substrateResponse=0.35)
WATER = dict(impastoMaterial={"version": 2, "enabled": True, "initialLoad": 1, "wetness": 0.95, "pickupRate": 0.3,
    "levelingRate": 0.2, "viscosity": 0.1, "yieldLikeStrength": 0.05, "dryingRate": 0.12, "substrateResponse": 0.2,
    "baseRoughness": 0.6, "wetSpecularStrength": 0.2, "sheen": 0.0}, substrateResponse=0.5)
INK = dict(substrateResponse=0.15)

def with_dyn(p, tip):
    p.setdefault("dynamics", PRESS())
    return p, tip

def acrylic(): 
    m = ACRYLIC; out = []
    out.append(lane_brush("Flat", m, "flat", LOAD(24), pitch=1.4, wmin=0.35, hRim=1.8))
    out.append(lane_brush("Filbert", m, "flat", LOAD(24), pitch=1.5, wmin=0.3, hRim=1.2, streakRange=0.2))
    out.append(lane_brush("Round", m, "round", LOAD(12), pitch=2.2, wmin=0.08, hRim=0.8, tail=170))
    out.append(lane_brush("Rigger", m, "round", LOAD(8, 3, 1.4), pitch=3.0, wmin=0.12, span=0.35, draw=0.002, tail=220, hRim=0.2))
    out.append(lane_brush("Fan", m, "flat", LOAD(32, 2, 0.5), pitch=1.2, span=1.3, wmin=0.6, draw=0.0015, lift=0.004, hRim=0.1, hBase=0.2))
    out.append(lane_brush("Hog Bristle", m, "flat", LOAD(20), pitch=1.9, wmin=0.5, hBase=1.2, hRim=2.4, streakRange=0.4, draw=0.005))
    out.append(lane_brush("Dry Brush", m, "flat", LOAD(24, 2, 0.4), pitch=1.4, dryFloor=0.0, onExpr="smoothstep(0.0, 0.12, gate - 0.55 * tooth)", draw=0.0025, hRim=0.6))
    out.append(lane_brush("Scumble", m, "round", LOAD(16, 2, 0.5), pitch=1.8, dryFloor=0.0, onExpr="smoothstep(0.0, 0.2, gate - 0.4 * tooth)", draw=0.003, mixWet=0.9, spacing=0.2))
    out.append(lane_brush("Dagger", m, "round", LOAD(10, 3, 1.2), pitch=2.4, wmin=0.1, span=0.6, tail=200, hRim=1.0))
    out.append(knife_brush("Palette Knife", m, LOAD(24, 2, 1.2)))
    out.append(plain("Sponge", 0.5, "round", scatter=0.6, rotationPerPx=4, **{k: v for k, v in m.items()}))
    out.append(lift_brush("Rag", m))
    out.append(lift_brush("Sgraffito", m, dispose=True, lift=0.05))
    out.append(lane_brush("Loaded Petal Flat", m, "flat", LOAD(24), pitch=1.6, wmin=0.25, hRim=2.0, mixWet=0.6, tail=140))
    out.append(lane_brush("Loaded Petal Round", m, "round", LOAD(12), pitch=2.0, wmin=0.06, hRim=1.0, tail=190))
    out.append(lane_brush("Comma Leaf", m, "round", LOAD(14), pitch=1.9, wmin=0.1, hRim=1.2, tail=170, follow=0.2))
    out.append(lane_brush("Double-Load Flat", m, "flat", LOAD(24), pitch=1.6, wmin=0.35, hRim=1.6, mixWet=0.8))
    out.append(lane_brush("Glaze Flat", {**m, "impastoThicknessRate": 0.06}, "flat", LOAD(24, 2, 0.6), pitch=1.4, draw=0.002, hBase=0.1, hRim=0.2, streakRange=0.1))
    out.append(lane_brush("Impasto Bead", m, "flat", LOAD(20, 3, 1.6), pitch=1.7, hBase=1.6, hRim=3.0, draw=0.006))
    return out

def oil():
    m = OIL; out = []
    out.append(lane_brush("Flat", m, "flat", LOAD(24, 3, 1.3), pitch=1.4, wmin=0.35, mixWet=0.7))
    out.append(lane_brush("Filbert", m, "flat", LOAD(24, 3, 1.3), pitch=1.5, wmin=0.3, hRim=1.2, mixWet=0.75))
    out.append(lane_brush("Round", m, "round", LOAD(12, 3, 1.2), pitch=2.2, wmin=0.08, hRim=0.8, tail=170))
    out.append(lane_brush("Rigger", m, "round", LOAD(8, 3, 1.5), pitch=3.0, wmin=0.12, span=0.35, draw=0.002, tail=220, hRim=0.2))
    out.append(lane_brush("Fan Blender", m, "flat", LOAD(32, 2, 0.4), pitch=1.2, span=1.3, wmin=0.6, draw=0.0008, lift=0.006, mixWet=0.95, hRim=0.1, hBase=0.1))
    out.append(lane_brush("Hog Bristle", m, "flat", LOAD(20, 3, 1.4), pitch=1.9, wmin=0.5, hBase=1.4, hRim=2.6, streakRange=0.4, draw=0.005))
    out.append(lane_brush("Dry Brush", m, "flat", LOAD(24, 2, 0.4), pitch=1.4, dryFloor=0.0, onExpr="smoothstep(0.0, 0.12, gate - 0.55 * tooth)", draw=0.0025, hRim=0.6))
    out.append(knife_brush("Palette Knife", m, LOAD(24, 2, 1.4), hBase=1.8, hRim=2.6))
    out.append(lift_brush("Rag", m))
    out.append(lane_brush("Impasto Bead", m, "flat", LOAD(20, 3, 1.8), pitch=1.7, hBase=1.8, hRim=3.2, draw=0.006))
    return out

def water():
    m = WATER; out = []
    out.append(lane_brush("Mop", m, "round", LOAD(16, 4, 2.0), pitch=2.0, wmin=0.5, hBase=0.0, hRim=0.0, draw=0.006, mixWet=0.9, streakRange=0.1, spacing=0.18))
    out.append(lane_brush("Wash Flat", m, "flat", LOAD(28, 4, 1.8), pitch=1.4, wmin=0.7, hBase=0.0, hRim=0.0, draw=0.005, mixWet=0.95, streakRange=0.08))
    out.append(lane_brush("Wet-in-Wet Round", m, "round", LOAD(12, 3, 1.2), pitch=2.2, wmin=0.15, hBase=0.0, hRim=0.0, draw=0.004, mixWet=1.0, tail=170))
    out.append(lane_brush("Rigger", m, "round", LOAD(8, 3, 1.0), pitch=3.0, wmin=0.12, span=0.35, hBase=0.0, hRim=0.0, draw=0.002, tail=220))
    out.append(lane_brush("Granulating Wash", m, "flat", LOAD(24, 3, 1.4), pitch=1.5, wmin=0.6, hBase=0.0, hRim=0.0, draw=0.004, mixWet=0.8, streakRange=0.5, streakLo=0.5))
    out.append(lift_brush("Sponge Lift", m, lift=0.015))
    out.append(lift_brush("Rag Lift", m, lift=0.03))
    return out

def ink():
    m = INK; out = []
    out.append(plain("Sumi Round", 0.06, "round", dynamics=PRESS(0.1), taper={"startLengthPx": 24, "minSize": 0.3, "minOpacity": 1}, hardness=0.85, **m))
    out.append(plain("Ink Brush Pen", 0.05, "round", dynamics=PRESS(0.15), hardness=0.95, **m))
    out.append(plain("Dip Nib", 0.05, "round", dynamics=[{"sensor": "speed", "parameter": "size", "outputMin": 1, "outputMax": 0.55}, {"sensor": "pressure", "parameter": "size", "outputMin": 0.4, "outputMax": 1}], hardness=1, **m))
    out.append(plain("Ruling Line", 0.04, "round", hardness=1, **m))
    out.append(lane_brush("Dry Ink Brush", {**m, "impastoThicknessRate": 0.0}, "flat", LOAD(20, 2, 0.5), pitch=1.6, dryFloor=0.0, onExpr="smoothstep(0.0, 0.12, gate - 0.6 * tooth)", draw=0.003, hBase=0.0, hRim=0.0))
    out.append(lane_brush("Ink Wash Mop", {**m}, "round", LOAD(16, 3, 1.6), pitch=2.0, wmin=0.5, hBase=0.0, hRim=0.0, draw=0.005, mixWet=0.9, streakRange=0.1, spacing=0.18))
    return out

PACKS = {
    "acrylic": ("Painterly Acrylic", "Acrylic brushes and tools that carry and spend real paint: flats, rounds, a rigger, a fan, hog bristle, dry-brush, scumble, a dagger, palette knife, sponge, rag, sgraffito, and the loaded petal set that makes a flower in one pull.", acrylic),
    "oil": ("Painterly Oil", "Oil brushes and tools for long open time and soft blending: flats, filbert, round, rigger, a fan blender, hog bristle, dry-brush, palette knife, rag and an impasto bead.", oil),
    "water": ("Painterly Water", "Watercolour brushes for wet-in-wet, washes and granulation: a mop, a wash flat, a wet round, a rigger, a granulating wash, and sponge and rag lifts.", water),
    "ink": ("Painterly Ink", "Ink pens and brushes: sumi round, brush pen, dip nib, ruling line, a dry ink brush and an ink wash mop.", ink),
}

ANCH = [  # id, name, colour index, reflectance (440..660 nm), scatter, hiding, tinting, granulation
 ("titanium-white","Titanium White","PW6",[.92]*7,3.0,0.98,1.0,0.05),
 ("ivory-black","Ivory Black","PBk9",[.04]*7,0.6,0.97,0.9,0.10),
 ("cadmium-yellow","Cadmium Yellow","PY35",[.05,.06,.12,.60,.88,.92,.93],1.0,0.95,0.75,0.10),
 ("yellow-ochre","Yellow Ochre","PY43",[.06,.07,.10,.25,.40,.50,.58],0.9,0.85,0.45,0.20),
 ("cadmium-red","Cadmium Red","PR108",[.03,.03,.04,.07,.20,.70,.85],1.0,0.95,0.80,0.10),
 ("quinacridone-magenta","Quinacridone Magenta","PR122",[.35,.20,.05,.04,.08,.35,.60],0.8,0.35,0.95,0.0),
 ("ultramarine","Ultramarine Blue","PB29",[.30,.40,.18,.07,.05,.07,.18],0.7,0.45,0.55,0.55),
 ("phthalo-blue","Phthalo Blue","PB15:3",[.20,.30,.38,.15,.04,.03,.04],1.0,0.30,1.00,0.0),
 ("phthalo-green","Phthalo Green","PG7",[.03,.10,.38,.45,.20,.05,.03],1.0,0.30,1.00,0.0),
 ("viridian","Viridian","PG18",[.05,.12,.30,.30,.12,.05,.04],0.8,0.35,0.60,0.30),
 ("cerulean","Cerulean Blue","PB35",[.30,.45,.45,.25,.10,.06,.06],0.8,0.80,0.50,0.35),
 ("burnt-umber","Burnt Umber","PBr7",[.03,.03,.04,.06,.09,.14,.20],0.7,0.90,0.55,0.25),
]

def write_pack(pid, name, desc, assets, extra_files=None, kind_assets=None):
    d = os.path.join(ROOT, PREFIX + pid)
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(os.path.join(d, "assets"))
    shutil.copy(LICENSE_SRC, os.path.join(d, "LICENSE"))
    manifest = {"azphalt": "0.1", "id": PREFIX + pid, "name": name, "version": "1.0.0", "kind": "asset",
                "license": "Apache-2.0", "compat": ">=0.1", "description": desc, "author": "HereLiesAz",
                "targetApps": ["com.hereliesaz.graffux"], "assets": assets}
    json.dump(manifest, open(os.path.join(d, "manifest.json"), "w"), indent=2)
    return d

def main():
    for pid, (name, desc, build) in PACKS.items():
        brushes = build()
        d_assets = []
        for params, tip in brushes:
            d_assets.append({"type": "brush", "path": f"assets/tip-{tip}.png",
                             "tags": ["painterly", pid] + (["bristle"] if "program" in params else []), "params": params})
        d = write_pack(pid, name, desc, d_assets)
        png(os.path.join(d, "assets", "tip-round.png"), 64, round_tip)
        png(os.path.join(d, "assets", "tip-flat.png"), 64, flat_tip)
    # pigments: a palette asset in azphalt-pigments/1 format. PLACEHOLDER data.
    pig = []
    for id, nm, ci, R, s, h, t, g in ANCH:
        pig.append({"id": id, "name": nm, "colourIndex": ci, "dataQuality": "placeholder", "dataVersion": 1,
                    "hiding": h, "tinting": t, "granulation": g,
                    "K": [s * (1 - r) ** 2 / (2 * r) for r in R], "S": [s] * 7})
    sets = {"format": "azphalt-pigments/1", "wavelengths": [440, 470, 500, 540, 580, 620, 660], "pigments": pig}
    d = write_pack("pigments", "Painterly Pigments (placeholder data)",
                   "Twelve generic-named tube pigments for the painterly mixer. The numbers are hand-tuned and believable, not measured; the format accepts calibrated data later.",
                   [{"type": "palette", "path": "assets/pigments.json", "tags": ["painterly", "pigments", "placeholder"],
                     "params": {"format": "azphalt-pigments/1", "dataQuality": "placeholder"}}])
    json.dump(sets, open(os.path.join(d, "assets", "pigments.json"), "w"), indent=1)
    os.remove(os.path.join(d, "assets", "pigments.json")) if False else None

if __name__ == "__main__":
    main()
