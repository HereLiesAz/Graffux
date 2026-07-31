"""Graffux icon kit — the geometry DSL and the registry every icon set is written against.

Icons are authored as exact geometry, never as hand-typed path strings. The helpers below
emit absolute SVG path data and remember the points they were built from, so `build.py` can
check every glyph against the live area without re-parsing anything.

The design system itself (grid, stroke, the keystone rule, naming) lives in README.md.
The numbers it depends on are the constants at the top of this file.
"""

from __future__ import annotations

import math

# ---------------------------------------------------------------------------------------
# The grid
# ---------------------------------------------------------------------------------------

VIEW = 24.0          # viewport, square
LIVE = (2.0, 22.0)   # live area — nothing of consequence outside it
BLEED = (1.0, 23.0)  # absolute limit; a stroke centre may reach here, nothing may exceed it
STROKE = 1.4         # the single stroke weight, in viewport units
CAP = "round"
JOIN = "round"
KEYSTONE_R = 1.15    # radius of the standard solid mark
BUDGET = 260         # path-data characters per glyph. Over this, the drawing is saying too much.

_PREC = 2


def _n(v: float) -> str:
    """Format a coordinate: fixed precision, no trailing noise, no negative zero."""
    s = f"{round(float(v), _PREC):.{_PREC}f}".rstrip("0").rstrip(".")
    return "0" if s in ("", "-0") else s


def _c(x: float, y: float) -> str:
    return f"{_n(x)},{_n(y)}"


# ---------------------------------------------------------------------------------------
# Path fragments
# ---------------------------------------------------------------------------------------


class Path:
    """A chunk of absolute SVG path data plus the points that bound it."""

    __slots__ = ("d", "pts", "evenodd")

    def __init__(self, d: str, pts, evenodd: bool = False):
        self.d = d
        self.pts = [(float(x), float(y)) for x, y in pts]
        self.evenodd = evenodd

    def __add__(self, other: "Path") -> "Path":
        return Path(f"{self.d} {other.d}", self.pts + other.pts,
                    self.evenodd or other.evenodd)

    def bbox(self):
        xs = [p[0] for p in self.pts]
        ys = [p[1] for p in self.pts]
        return min(xs), min(ys), max(xs), max(ys)


def seq(*parts: Path) -> Path:
    """Concatenate fragments into one path (one `<path>` element, several subpaths)."""
    out = parts[0]
    for p in parts[1:]:
        out = out + p
    return out


# ---------------------------------------------------------------------------------------
# Frames — design in a convenient local frame, place it in the 24-grid
# ---------------------------------------------------------------------------------------


def xf(cx: float = 0.0, cy: float = 0.0, deg: float = 0.0, sx: float = 1.0, sy: float = 1.0):
    """Return a point transform: scale, then rotate `deg` clockwise, then translate to (cx, cy).

    Angles are clockwise because the viewport is y-down, so `deg` matches what you see.
    """
    r = math.radians(deg)
    co, si = math.cos(r), math.sin(r)

    def t(x: float, y: float):
        x, y = x * sx, y * sy
        return (cx + x * co - y * si, cy + x * si + y * co)

    return t


def _apply(t, pts):
    return list(pts) if t is None else [t(x, y) for x, y in pts]


# ---------------------------------------------------------------------------------------
# Primitives
# ---------------------------------------------------------------------------------------


def line(x1, y1, x2, y2, t=None) -> Path:
    (ax, ay), (bx, by) = _apply(t, [(x1, y1), (x2, y2)])
    return Path(f"M{_c(ax, ay)}L{_c(bx, by)}", [(ax, ay), (bx, by)])


def poly(pts, close=False, t=None) -> Path:
    p = _apply(t, pts)
    d = "M" + _c(*p[0]) + "".join("L" + _c(*q) for q in p[1:]) + ("Z" if close else "")
    return Path(d, p)


def rect(x, y, w, h, r=0.0, t=None) -> Path:
    if r <= 0:
        return poly([(x, y), (x + w, y), (x + w, y + h), (x, y + h)], close=True, t=t)
    r = min(r, w / 2.0, h / 2.0)
    corners = [
        ((x + r, y), (x + w - r, y), (x + w, y + r)),
        ((x + w, y + r), (x + w, y + h - r), (x + w - r, y + h)),
        ((x + w - r, y + h), (x + r, y + h), (x, y + h - r)),
        ((x, y + h - r), (x, y + r), (x + r, y)),
    ]
    p0 = _apply(t, [corners[0][0]])[0]
    d = "M" + _c(*p0)
    pts = [p0]
    for _, straight_end, arc_end in corners:
        se = _apply(t, [straight_end])[0]
        ae = _apply(t, [arc_end])[0]
        d += f"L{_c(*se)}A{_n(r)},{_n(r)} 0 0,1 {_c(*ae)}"
        pts += [se, ae]
    return Path(d + "Z", pts)


def square(cx, cy, side, r=0.0, t=None) -> Path:
    return rect(cx - side / 2.0, cy - side / 2.0, side, side, r, t)


def circle(cx, cy, r, t=None) -> Path:
    if t is not None:
        cx, cy = t(cx, cy)
    d = (
        f"M{_c(cx - r, cy)}"
        f"A{_n(r)},{_n(r)} 0 1,0 {_c(cx + r, cy)}"
        f"A{_n(r)},{_n(r)} 0 1,0 {_c(cx - r, cy)}Z"
    )
    return Path(d, [(cx - r, cy - r), (cx + r, cy + r)])


def ellipse(cx, cy, rx, ry, t=None) -> Path:
    if t is not None:
        cx, cy = t(cx, cy)
    d = (
        f"M{_c(cx - rx, cy)}"
        f"A{_n(rx)},{_n(ry)} 0 1,0 {_c(cx + rx, cy)}"
        f"A{_n(rx)},{_n(ry)} 0 1,0 {_c(cx - rx, cy)}Z"
    )
    return Path(d, [(cx - rx, cy - ry), (cx + rx, cy + ry)])


def _polar(cx, cy, rx, ry, deg):
    a = math.radians(deg)
    return (cx + rx * math.cos(a), cy + ry * math.sin(a))


def arc(cx, cy, r, a0, a1, ry=None, rot=0.0) -> Path:
    """Arc of a circle/ellipse. Angles in degrees, 0 = east, increasing = clockwise on screen."""
    ry = r if ry is None else ry
    p0 = _polar(cx, cy, r, ry, a0)
    p1 = _polar(cx, cy, r, ry, a1)
    large = 1 if abs(a1 - a0) > 180 else 0
    sweep = 1 if a1 > a0 else 0
    step = 5.0 if a1 > a0 else -5.0
    n = int(abs(a1 - a0) / 5.0) + 1
    samples = [_polar(cx, cy, r, ry, a0 + step * i) for i in range(n + 1)] + [p1]
    d = f"M{_c(*p0)}A{_n(r)},{_n(ry)} {_n(rot)} {large},{sweep} {_c(*p1)}"
    return Path(d, samples)


def pie(cx, cy, r, a0, a1) -> Path:
    """Closed wedge — arc plus two radii back to the centre."""
    a = arc(cx, cy, r, a0, a1)
    return Path(f"M{_c(cx, cy)}L{a.d[1:]}Z", a.pts + [(cx, cy)])


def cubic(p0, c1, c2, p1, t=None) -> Path:
    a, b, c, d_ = _apply(t, [p0, c1, c2, p1])
    return Path(f"M{_c(*a)}C{_c(*b)} {_c(*c)} {_c(*d_)}", [a, b, c, d_])


def quad(p0, c, p1, t=None) -> Path:
    a, b, e = _apply(t, [p0, c, p1])
    return Path(f"M{_c(*a)}Q{_c(*b)} {_c(*e)}", [a, b, e])


def smooth(pts, close=False, tension=0.5, t=None) -> Path:
    """Catmull-Rom through `pts`, emitted as cubics. For the few organic glyphs."""
    p = _apply(t, pts)
    if close:
        ring = p[-1:] + p + p[:2]
    else:
        ring = [p[0]] + p + [p[-1]]
    d = "M" + _c(*p[0])
    for i in range(1, len(ring) - 2):
        p0, p1, p2, p3 = ring[i - 1], ring[i], ring[i + 1], ring[i + 2]
        c1 = (p1[0] + (p2[0] - p0[0]) * tension / 3.0, p1[1] + (p2[1] - p0[1]) * tension / 3.0)
        c2 = (p2[0] - (p3[0] - p1[0]) * tension / 3.0, p2[1] - (p3[1] - p1[1]) * tension / 3.0)
        d += f"C{_c(*c1)} {_c(*c2)} {_c(*p2)}"
    return Path(d + ("Z" if close else ""), p)


# ---------------------------------------------------------------------------------------
# Compound helpers used often enough to be worth naming
# ---------------------------------------------------------------------------------------


def dot(cx, cy, r=KEYSTONE_R, t=None) -> Path:
    """The keystone: the one solid mark an icon is allowed."""
    return circle(cx, cy, r, t)


def chevron(cx, cy, w, h, deg=0.0) -> Path:
    """A `>`-shaped mark, centred on (cx, cy), pointing right before rotation."""
    t = xf(cx, cy, deg)
    return poly([(-w / 2, -h / 2), (w / 2, 0), (-w / 2, h / 2)], t=t)


def barb(x, y, size, deg=0.0) -> Path:
    """An open arrowhead at (x, y), pointing right before rotation."""
    t = xf(x, y, deg)
    return poly([(-size, -size), (0, 0), (-size, size)], t=t)


def tip(x, y, size=2.0, deg=0.0) -> Path:
    """A solid arrowhead at (x, y), pointing right before rotation. Counts as the keystone."""
    t = xf(x, y, deg)
    return poly([(0, 0), (-size, -size * 0.72), (-size, size * 0.72)], close=True, t=t)


def taper(x1, y1, x2, y2, w1, w2) -> Path:
    """A closed quadrilateral from a width-`w1` end at (x1,y1) to a width-`w2` end at (x2,y2)."""
    dx, dy = x2 - x1, y2 - y1
    ln = math.hypot(dx, dy) or 1.0
    nx, ny = -dy / ln, dx / ln
    return poly(
        [
            (x1 + nx * w1 / 2, y1 + ny * w1 / 2),
            (x2 + nx * w2 / 2, y2 + ny * w2 / 2),
            (x2 - nx * w2 / 2, y2 - ny * w2 / 2),
            (x1 - nx * w1 / 2, y1 - ny * w1 / 2),
        ],
        close=True,
    )


def nib(x, y, length, half, deg=0.0) -> Path:
    """A pen/brush head: a flat back, two flanks, a point. Points right before rotation."""
    t = xf(x, y, deg)
    return poly(
        [(0, -half), (length * 0.62, -half * 0.72), (length, 0), (length * 0.62, half * 0.72), (0, half)],
        close=True,
        t=t,
    )


def ticks(x, y, count, step, length, deg=0.0) -> Path:
    """A run of registration marks — the recurring `record, not decoration` motif."""
    t = xf(x, y, deg)
    parts = [line(i * step, 0, i * step, -length, t=t) for i in range(count)]
    return seq(*parts)


def dashed(x1, y1, x2, y2, count=4, ratio=0.55) -> Path:
    """A dashed run drawn as real segments — VectorDrawable has no stroke-dasharray."""
    dx, dy = (x2 - x1) / count, (y2 - y1) / count
    parts = []
    for i in range(count):
        sx, sy = x1 + dx * i, y1 + dy * i
        parts.append(line(sx, sy, sx + dx * ratio, sy + dy * ratio))
    return seq(*parts)


def dashed_rect(x, y, w, h, per_side=2, ratio=0.6) -> Path:
    return seq(
        dashed(x, y, x + w, y, per_side, ratio),
        dashed(x + w, y, x + w, y + h, per_side, ratio),
        dashed(x + w, y + h, x, y + h, per_side, ratio),
        dashed(x, y + h, x, y, per_side, ratio),
    )


def slab(cx, cy, w, h, r=0.6) -> Path:
    """The generic `sheet / plate / layer` body, centred."""
    return rect(cx - w / 2, cy - h / 2, w, h, r)


def rays(cx, cy, r0, r1, n=8, start=0.0) -> Path:
    """n spokes between two radii about (cx, cy) — the sun, and everything descended from it."""
    out = []
    for i in range(n):
        a = math.radians(start + i * 360.0 / n)
        c, si = math.cos(a), math.sin(a)
        out.append(line(cx + r0 * c, cy + r0 * si, cx + r1 * c, cy + r1 * si))
    return seq(*out)


def ring(cx, cy, r_outer, r_inner) -> Path:
    """A true hole: a solid disc with an inner disc cut out, via even-odd fill."""
    outer = circle(cx, cy, r_outer)
    inner = circle(cx, cy, r_inner)
    return Path(f"{outer.d} {inner.d}", outer.pts + inner.pts, evenodd=True)


def cut(outer: Path, inner: Path) -> Path:
    """A solid shape with `inner` genuinely punched through it, via even-odd fill.

    Use this rather than relying on opposite winding: winding is easy to get wrong by
    hand (and silently renders as a solid blob when you do), whereas even-odd holes
    regardless of the direction either contour happens to be drawn in.
    """
    return Path(f"{outer.d} {inner.d}", outer.pts + inner.pts, evenodd=True)


def ring_rect(x, y, w, h, thick) -> Path:
    """A solid rectangular frame: an outer rect and a counter-wound inner rect that holes it."""
    outer = poly([(x, y), (x + w, y), (x + w, y + h), (x, y + h)], close=True)
    i = thick
    inner = poly([(x + i, y + i), (x + i, y + h - i), (x + w - i, y + h - i), (x + w - i, y + i)],
                 close=True)
    return outer + inner


def diamond(cx, cy, rx, ry=None) -> Path:
    ry = rx if ry is None else ry
    return poly([(cx, cy - ry), (cx + rx, cy), (cx, cy + ry), (cx - rx, cy)], close=True)


def cross(cx, cy, r) -> Path:
    return seq(line(cx - r, cy, cx + r, cy), line(cx, cy - r, cx, cy + r))


def ex(cx, cy, r) -> Path:
    return seq(line(cx - r, cy - r, cx + r, cy + r), line(cx + r, cy - r, cx - r, cy + r))


def slash(cx, cy, r=8.0) -> Path:
    """The universal `disabled / off` overlay, at a fixed angle across the whole glyph."""
    d = r / math.sqrt(2)
    return line(cx - d, cy + d, cx + d, cy - d)


# ---------------------------------------------------------------------------------------
# Registry
# ---------------------------------------------------------------------------------------

CATEGORIES = [
    ("tools", "Tools & brushes", "What touches the surface."),
    ("selection", "Selection & masking", "Deciding where the operation is allowed to land."),
    ("layers", "Layers & compositing", "The stack, and everything done to it."),
    ("color", "Colour & adjustment", "Tone, hue, and the instruments that measure them."),
    ("filters", "Filters & effects", "Transformations of pixels already committed."),
    ("vector", "Vector & path", "Anchors, curves, and boolean geometry."),
    ("type", "Type", "Setting text as an object."),
    ("transform", "Transform & arrange", "Position, scale, alignment, order."),
    ("document", "Document, canvas & view", "The surface itself and how it is looked at."),
    ("history", "History & automation", "Time, states, and repeatable work."),
    ("animation", "Animation & timeline", "Frames, playback, capture."),
    ("generative", "Generative & capture", "Model-assisted work, camera, AR, stencils."),
    ("ui", "Interface & system", "The shell: navigation, status, files, collaboration."),
]

_CATEGORY_KEYS = {c[0] for c in CATEGORIES}

ICONS: list[dict] = []
_SEEN: set[str] = set()


def icon(key, cat, kw=(), s=(), f=(), apps="new", basis="", note=""):
    """Register one icon.

    key   — kebab-case identifier; becomes `ic_gx_<snake>` and a PascalCase Kotlin property.
    cat   — one of CATEGORIES.
    kw    — search keywords, for the in-app picker.
    s     — stroked path fragments.
    f     — filled fragments. At most one: the keystone. See README.
    apps  — which of Photoshop / Illustrator / Procreate ships this concept (PS / AI / PR),
            or "new" for something Graffux will need that none of them has.
    basis — the inherited metaphor, named. Every glyph is drawn from scratch on the Graffux
            grid, but where those apps have taught users what a thing looks like, the
            lineage is stated here rather than invented away.
    note  — one line on what the drawing actually is, for the contact sheet.
    """
    if key in _SEEN:
        raise ValueError(f"duplicate icon key: {key}")
    if cat not in _CATEGORY_KEYS:
        raise ValueError(f"{key}: unknown category {cat!r}")
    _SEEN.add(key)
    s = [s] if isinstance(s, Path) else list(s)
    f = [f] if isinstance(f, Path) else list(f)
    ICONS.append(
        {
            "key": key,
            "cat": cat,
            "kw": list(kw),
            "strokes": s,
            "fills": f,
            "apps": apps,
            "basis": basis,
            "note": note,
        }
    )
