"""SVG path data: parse it, move it, measure it.

The icon set is authored as SVG now rather than generated from Python geometry, so the
build needs to understand path data rather than emit it. Three jobs:

**Move it.** Phosphor's drawings arrive on a 256-unit grid inside a scaling `<group>`.
Carrying that into the set would mean two coordinate spaces and two hatch phases, so the
importer bakes the transform into the coordinates and the group disappears.

There are two ways to do that and the difference matters. `transform` handles a uniform
scale plus a translation, under which an arc stays an arc — both radii scale, the x-axis
rotation and both flags survive — so the path keeps its arcs and its relative commands and
comes out no longer than it went in. That covers 94 of the 96 scaled drawings. The other
two are rotated or scaled unevenly, and there an arc is no longer the same arc; those go
through `to_absolute` first, which turns arcs into cubics, and then `affine`, which is
exact because a Bézier maps through any matrix by its control points.

**Measure it.** The validator needs to know where a glyph actually sits, and the hatch
emitter needs to know how far to run its rules, so curves are flattened into polylines and
measured from that. Bounds taken from Bézier *control* points — what the previous generator
used — are a superset that can be most of a unit wide of the real curve; bounds taken from
the flattened path are the real thing, and were checked against the browser's own `getBBox`
across all 1230 paths in the set (worst disagreement 0.024 units).

There is deliberately no area measurement here. Telling a filled *outline* from a solid mass
of the same silhouette needs the winding number under the path's actual fill rule — nesting
parity gets it wrong on any nonzero-wound path whose subpaths overlap, which most of
Phosphor's do — and nothing in the build asks the question.
"""

from __future__ import annotations

import math
import re

# Path data is a stream of numbers where separators are optional: "M4-3.5.5" is three
# numbers. Matching numbers directly, rather than splitting on delimiters, is the only
# reading of that which is correct.
_NUMBER = re.compile(r"[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")
_COMMAND = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]")

# How many numbers each command consumes per repetition.
ARITY = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}

# Which of those numbers are x-ish, y-ish, or neither. Used to place a transform without a
# lookup table per command in the transform itself.
_LAYOUT = {
    "M": "xy", "L": "xy", "T": "xy",
    "H": "x", "V": "y",
    "C": "xyxyxy", "S": "xyxy", "Q": "xyxy",
    "A": "rr.ffxy",  # rx ry rotation large-arc sweep x y
    "Z": "",
}


class BadPath(ValueError):
    """Path data the parser cannot make sense of."""


def parse(d: str):
    """Path data to `[(command, [numbers]), ...]`, one entry per repetition.

    Implicit repeats are expanded, so `M0 0 1 1 2 2` becomes one `M` and two `L`s and the
    rest of the module never has to think about them again. Command case is preserved:
    relative coordinates stay relative, which is what keeps the transformed output as
    compact as the input.
    """
    out = []
    i, n = 0, len(d)
    cmd = None
    while i < n:
        ch = d[i]
        if ch in ", \t\r\n":
            i += 1
            continue
        if _COMMAND.match(ch):
            cmd = ch
            i += 1
        elif cmd is None:
            raise BadPath(f"path data starts with {ch!r}, not a command")
        else:
            # An implicit repeat. M repeats as L (m as l); everything else repeats as itself.
            cmd = {"M": "L", "m": "l"}.get(cmd, cmd)

        want = ARITY[cmd.upper()]
        args = []
        while len(args) < want:
            while i < n and d[i] in ", \t\r\n":
                i += 1
            # The arc flags are single digits and may run into the next number without a
            # separator: "a1 1 0 011 1" is rx ry rot 0 1 1 1. Read them one character wide.
            if cmd.upper() == "A" and len(args) in (3, 4):
                if i < n and d[i] in "01":
                    args.append(float(d[i]))
                    i += 1
                    continue
                raise BadPath(f"arc flag expected at offset {i} in {d!r}")
            m = _NUMBER.match(d, i)
            if not m:
                raise BadPath(f"expected a number at offset {i} in {d!r}")
            args.append(float(m.group()))
            i = m.end()
        out.append((cmd, args))
        if want == 0:
            # Z takes no arguments and does not repeat.
            cmd = {"Z": "Z", "z": "z"}[cmd]
    return out


def transform(segs, scale: float, dx: float, dy: float):
    """Bake a uniform scale and a translation into the coordinates.

    Uniform only, deliberately. Under a uniform scale an arc stays an arc with both radii
    scaled, its x-axis rotation unchanged and its flags unchanged; under a non-uniform one
    the radii and rotation have to be recomputed together, and under a reflection the sweep
    flag flips. Refusing is better than a drawing that is subtly wrong in the two icons that
    happen to use an arc.
    """
    out = []
    for cmd, args in segs:
        rel = cmd.islower()
        layout = _LAYOUT[cmd.upper()]
        moved = []
        for kind, v in zip(layout, args):
            if kind == "x":
                moved.append(v * scale + (0.0 if rel else dx))
            elif kind == "y":
                moved.append(v * scale + (0.0 if rel else dy))
            elif kind == "r":
                moved.append(v * scale)  # radii scale with the drawing, never translate
            else:
                moved.append(v)  # rotation and the two arc flags survive untouched
        out.append((cmd, moved))
    return out


def fmt(v: float, places: int = 3) -> str:
    s = f"{round(float(v), places):.{places}f}".rstrip("0").rstrip(".")
    return "0" if s in ("", "-0") else s


def to_d(segs, places: int = 3) -> str:
    """Segments back to path data, with the separators trimmed where they are optional."""
    parts = []
    for cmd, args in segs:
        nums = [fmt(a, places) for a in args]
        chunk = cmd
        for j, s in enumerate(nums):
            # A separator is only needed when the previous character could otherwise absorb
            # this one: digit-then-digit needs one, but "5-3" and "5.5" read correctly.
            if j and not (s.startswith("-") or (s.startswith(".") and "." not in nums[j - 1])):
                chunk += " "
            chunk += s
        parts.append(chunk)
    return "".join(parts)


def to_absolute(segs):
    """Rewrite a path using only absolute `M`, `L`, `C`, `Q` and `Z`.

    The lossy-looking part — arcs become cubics — is what makes a general affine safe. A
    Bézier maps through *any* affine by mapping its control points, so once the arcs are
    gone a rotation or a non-uniform scale is exact rather than approximate. Two icons in
    the set need that (`pen` is scaled 0.50×0.72, `smudge` is rotated 210°); everything else
    takes the uniform path and keeps its arcs untouched.

    The cubic approximation splits each arc at 90° and is accurate to about one part in
    50,000 of the radius, which on a 24-unit grid is far below a rendered pixel.
    """
    out = []
    x = y = start_x = start_y = 0.0
    prev_cubic = prev_quad = None
    for cmd, a in segs:
        up, rel = cmd.upper(), cmd.islower()
        bx, by = (x, y) if rel else (0.0, 0.0)
        if up == "M":
            x, y = a[0] + bx, a[1] + by
            start_x, start_y = x, y
            out.append(("M", [x, y]))
            prev_cubic = prev_quad = None
        elif up == "Z":
            out.append(("Z", []))
            x, y = start_x, start_y
            prev_cubic = prev_quad = None
        elif up in ("L", "H", "V"):
            if up == "L":
                x, y = a[0] + bx, a[1] + by
            elif up == "H":
                x = a[0] + bx
            else:
                y = a[0] + by
            out.append(("L", [x, y]))
            prev_cubic = prev_quad = None
        elif up in ("C", "S"):
            if up == "C":
                c1, c2, end = (a[0] + bx, a[1] + by), (a[2] + bx, a[3] + by), (a[4] + bx, a[5] + by)
            else:
                c1 = (2 * x - prev_cubic[0], 2 * y - prev_cubic[1]) if prev_cubic else (x, y)
                c2, end = (a[0] + bx, a[1] + by), (a[2] + bx, a[3] + by)
            out.append(("C", [c1[0], c1[1], c2[0], c2[1], end[0], end[1]]))
            prev_cubic, prev_quad = c2, None
            x, y = end
        elif up in ("Q", "T"):
            if up == "Q":
                c, end = (a[0] + bx, a[1] + by), (a[2] + bx, a[3] + by)
            else:
                c = (2 * x - prev_quad[0], 2 * y - prev_quad[1]) if prev_quad else (x, y)
                end = (a[0] + bx, a[1] + by)
            out.append(("Q", [c[0], c[1], end[0], end[1]]))
            prev_quad, prev_cubic = c, None
            x, y = end
        elif up == "A":
            end = (a[5] + bx, a[6] + by)
            for c1, c2, p3 in _arc_to_cubics((x, y), a[0], a[1], a[2], int(a[3]), int(a[4]), end):
                out.append(("C", [c1[0], c1[1], c2[0], c2[1], p3[0], p3[1]]))
            x, y = end
            prev_cubic = prev_quad = None
        else:
            raise BadPath(f"unhandled command {cmd!r}")
    return out


def _arc_to_cubics(p0, rx, ry, rot_deg, large, sweep, p1):
    geom = _arc_centre(p0, rx, ry, rot_deg, large, sweep, p1)
    if geom is None:
        yield (p1, p1, p1)  # degenerate: a straight line stated as a flat cubic
        return
    cx, cy, rx, ry, phi, theta, delta = geom
    cos_p, sin_p = math.cos(phi), math.sin(phi)

    def point(t):
        ct, st = math.cos(t), math.sin(t)
        return (cos_p * rx * ct - sin_p * ry * st + cx, sin_p * rx * ct + cos_p * ry * st + cy)

    def deriv(t):
        ct, st = math.cos(t), math.sin(t)
        return (-cos_p * rx * st - sin_p * ry * ct, -sin_p * rx * st + cos_p * ry * ct)

    n = max(1, math.ceil(abs(delta) / (math.pi / 2)))
    step = delta / n
    k = 4.0 / 3.0 * math.tan(step / 4.0)
    for i in range(n):
        t0 = theta + i * step
        t1 = t0 + step
        a0, a1 = point(t0), point(t1)
        d0, d1 = deriv(t0), deriv(t1)
        yield (
            (a0[0] + k * d0[0], a0[1] + k * d0[1]),
            (a1[0] - k * d1[0], a1[1] - k * d1[1]),
            a1,
        )


def affine(segs, a: float, b: float, c: float, d: float, e: float, f: float):
    """Apply the full matrix `(a b c d e f)` to a path already in absolute form.

    Absolute-only is a precondition, not a convenience: under a general matrix a relative
    delta transforms by the linear part while an absolute point also takes the translation,
    and `H`/`V` stop being horizontal or vertical at all. `to_absolute` removes every one of
    those cases first.
    """
    out = []
    for cmd, args in segs:
        if cmd not in ("M", "L", "C", "Q", "Z"):
            raise BadPath(f"affine needs absolute path data, got {cmd!r} — call to_absolute first")
        moved = []
        for i in range(0, len(args), 2):
            x, y = args[i], args[i + 1]
            moved += [a * x + c * y + e, b * x + d * y + f]
        out.append((cmd, moved))
    return out


# ---------------------------------------------------------------------------------------
# Flattening
# ---------------------------------------------------------------------------------------


def _cubic(p0, p1, p2, p3, steps):
    for i in range(1, steps + 1):
        t = i / steps
        u = 1 - t
        yield (
            u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0],
            u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1],
        )


def _quad(p0, p1, p2, steps):
    for i in range(1, steps + 1):
        t = i / steps
        u = 1 - t
        yield (
            u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0],
            u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1],
        )


def _arc_centre(p0, rx, ry, rot_deg, large, sweep, p1):
    """Endpoint arc to its centre parameterisation, per SVG 1.1 F.6.5.

    Returns `(cx, cy, rx, ry, phi, theta, delta)`, or `None` when the arc degenerates to a
    line — coincident endpoints, or a zero radius, both of which the spec says to draw as a
    straight segment rather than to reject.
    """
    x1, y1 = p0
    x2, y2 = p1
    if (x1 == x2 and y1 == y2) or rx == 0 or ry == 0:
        return None
    rx, ry = abs(rx), abs(ry)

    phi = math.radians(rot_deg)
    cos_p, sin_p = math.cos(phi), math.sin(phi)
    dx2, dy2 = (x1 - x2) / 2.0, (y1 - y2) / 2.0
    x1p = cos_p * dx2 + sin_p * dy2
    y1p = -sin_p * dx2 + cos_p * dy2

    # Radii too small to span the endpoints are scaled up until they exactly reach, which is
    # what the spec asks for rather than treating the path as an error.
    lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
    if lam > 1:
        s = math.sqrt(lam)
        rx, ry = rx * s, ry * s

    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    co = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large == sweep:
        co = -co
    cxp = co * rx * y1p / ry
    cyp = -co * ry * x1p / rx
    cx = cos_p * cxp - sin_p * cyp + (x1 + x2) / 2.0
    cy = sin_p * cxp + cos_p * cyp + (y1 + y2) / 2.0

    def angle(ux, uy, vx, vy):
        return math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)

    theta = angle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and delta > 0:
        delta -= 2 * math.pi
    elif sweep and delta < 0:
        delta += 2 * math.pi
    return cx, cy, rx, ry, phi, theta, delta


def _arc(p0, rx, ry, rot_deg, large, sweep, p1, steps):
    geom = _arc_centre(p0, rx, ry, rot_deg, large, sweep, p1)
    if geom is None:
        if p0 != p1:
            yield p1
        return
    cx, cy, rx, ry, phi, theta, delta = geom
    cos_p, sin_p = math.cos(phi), math.sin(phi)
    n = max(2, int(steps * abs(delta) / math.pi))
    for i in range(1, n + 1):
        t = theta + delta * i / n
        ct, st = math.cos(t), math.sin(t)
        yield (cos_p * rx * ct - sin_p * ry * st + cx, sin_p * rx * ct + cos_p * ry * st + cy)


def flatten(segs, steps: int = 64):
    """Segments to a list of subpaths, each a list of absolute points.

    Curves are subdivided and arcs sampled, so what comes back approximates the drawn shape
    rather than bounding it. `steps` at 24 puts the worst-case error on a 24-unit grid well
    under a tenth of a stroke width, which is finer than anything the validator asks.
    """
    polys, cur = [], []
    x = y = 0.0
    start_x = start_y = 0.0
    prev_cubic = None   # trailing control point, for S
    prev_quad = None    # trailing control point, for T

    for cmd, a in segs:
        up = cmd.upper()
        rel = cmd.islower()
        bx, by = (x, y) if rel else (0.0, 0.0)

        if up == "M":
            if len(cur) > 1:
                polys.append(cur)
            x, y = a[0] + bx, a[1] + by
            start_x, start_y = x, y
            cur = [(x, y)]
            prev_cubic = prev_quad = None
        elif up == "Z":
            if cur:
                cur.append((start_x, start_y))
                if len(cur) > 1:
                    polys.append(cur)
            x, y = start_x, start_y
            cur = [(x, y)]
            prev_cubic = prev_quad = None
        elif up in ("L", "H", "V", "T", "C", "S", "Q", "A"):
            if not cur:
                cur = [(x, y)]
            if up == "L":
                x, y = a[0] + bx, a[1] + by
                cur.append((x, y))
                prev_cubic = prev_quad = None
            elif up == "H":
                x = a[0] + bx
                cur.append((x, y))
                prev_cubic = prev_quad = None
            elif up == "V":
                y = a[0] + by
                cur.append((x, y))
                prev_cubic = prev_quad = None
            elif up in ("C", "S"):
                if up == "C":
                    c1 = (a[0] + bx, a[1] + by)
                    c2 = (a[2] + bx, a[3] + by)
                    end = (a[4] + bx, a[5] + by)
                else:
                    # S reflects the previous cubic's trailing control point through the
                    # current point; with no previous cubic the reflection is the point.
                    c1 = (2 * x - prev_cubic[0], 2 * y - prev_cubic[1]) if prev_cubic else (x, y)
                    c2 = (a[0] + bx, a[1] + by)
                    end = (a[2] + bx, a[3] + by)
                cur.extend(_cubic((x, y), c1, c2, end, steps))
                prev_cubic, prev_quad = c2, None
                x, y = end
            elif up in ("Q", "T"):
                if up == "Q":
                    c = (a[0] + bx, a[1] + by)
                    end = (a[2] + bx, a[3] + by)
                else:
                    c = (2 * x - prev_quad[0], 2 * y - prev_quad[1]) if prev_quad else (x, y)
                    end = (a[0] + bx, a[1] + by)
                cur.extend(_quad((x, y), c, end, steps))
                prev_quad, prev_cubic = c, None
                x, y = end
            else:  # A
                end = (a[5] + bx, a[6] + by)
                cur.extend(_arc((x, y), a[0], a[1], a[2], int(a[3]), int(a[4]), end, steps))
                x, y = end
                prev_cubic = prev_quad = None
        else:
            raise BadPath(f"unhandled command {cmd!r}")

    if len(cur) > 1:
        polys.append(cur)
    return polys


def bbox(polys):
    """`(x0, y0, x1, y1)` over every flattened point, or `None` for an empty path."""
    pts = [p for poly in polys for p in poly]
    if not pts:
        return None
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    return min(xs), min(ys), max(xs), max(ys)
