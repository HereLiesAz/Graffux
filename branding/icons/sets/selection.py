"""Selection & masking — deciding where the operation is allowed to land.

One signal carries the family: an outline broken once per side is a selection edge (marching
ants, after Photoshop's marquee); an unbroken outline is real geometry. Where a boolean
result exists, the result is the solid.

Corners are square throughout. A round join already softens them, and four arcs cost four
times what four points cost.
"""

from kit import (
    Path,
    quad,
    arc,
    circle,
    dashed,
    dashed_rect,
    dot,
    icon,
    line,
    poly,
    rect,
    ring_rect,
    seq,
    slash,
    smooth,
    square,
    tip,
    xf,
)


def ants_circle(cx, cy, r, n=6):
    step = 360.0 / n
    return seq(*[arc(cx, cy, r, i * step, i * step + step * 0.62) for i in range(n)])


def ants_poly(pts):
    ring = list(pts) + [pts[0]]
    return seq(*[dashed(*ring[i], *ring[i + 1], 2, 0.6) for i in range(len(pts))])


def handle(cx, cy):
    """A transform handle — the solid mark that says a selection is live."""
    return square(cx, cy, 2.6)


# Two discs, r 6, centres 6.4 apart, crossing at (12.4, 6.93) and (12.4, 17.07). Every
# boolean icon is built from this one pair, so the four results can be read against each
# other at a glance. Nonzero winding does the rest.
_A, _B, _R = (9.2, 12.0), (15.6, 12.0), 6.0
_LENS = Path("M12.4,6.93A6,6 0 0,1 12.4,17.07A6,6 0 0,1 12.4,6.93Z", [(9.6, 6.9), (15.2, 17.1)])
_LEFT = Path("M12.4,17.07A6,6 0 1,1 12.4,6.93A6,6 0 0,0 12.4,17.07Z", [(3.2, 6), (15.2, 18)])
_RIGHT = Path("M12.4,6.93A6,6 0 1,1 12.4,17.07A6,6 0 0,0 12.4,6.93Z", [(9.6, 6), (21.6, 18)])

_WAND = xf(12.4, 12.4, 135)
_QUICK = xf(13.0, 10.2, 135)

# ---------------------------------------------------------------------------------------
# Making a selection
# ---------------------------------------------------------------------------------------

icon("select-rect", "selection", ["marquee", "rectangle", "crop area"],
     s=[dashed_rect(4.2, 5.2, 15.6, 13.6)],
     f=[handle(19.8, 18.8)],
     apps="PS/AI/PR",
     basis="Photoshop's Rectangular Marquee — ants, with one corner handle live.",
     note="Rectangular marquee.")

icon("select-ellipse", "selection", ["marquee", "oval", "round"],
     s=[ants_circle(11.4, 11.4, 7.8)],
     f=[handle(18.8, 18.8)],
     apps="PS/AI/PR",
     basis="Photoshop's Elliptical Marquee.",
     note="Elliptical marquee with its handle on the diagonal.")

icon("select-lasso", "selection", ["freehand", "draw selection", "loop"],
     s=[smooth([(7.6, 6.4), (15.4, 4.0), (19.0, 10.4), (12.4, 15.2), (5.4, 11.4), (7.6, 6.4)]),
        line(7.6, 6.4, 9.6, 20.4)],
     f=[dot(9.6, 20.4, 1.3)],
     apps="PS/AI/PR",
     basis="Photoshop's Lasso and Procreate's Freehand selection.",
     note="A loop drawn by hand, still hanging from where it started.")

icon("select-polygon", "selection", ["polygonal lasso", "straight", "vertices"],
     s=[ants_poly([(3.6, 9.6), (10.2, 3.4), (20.4, 7.4), (17.4, 18.8), (7.0, 19.4)])],
     f=[dot(3.6, 9.6, 1.6)],
     apps="PS/AI",
     basis="Photoshop's Polygonal Lasso and Procreate's Polyline selection.",
     note="Straight runs between placed vertices; the live one is solid.")

icon("select-magnetic", "selection", ["snap to edge", "magnetic lasso", "cling"],
     s=[seq(line(4.8, 18.6, 4.8, 13.0), arc(12, 13.0, 7.2, 180, 360), line(19.2, 13.0, 19.2, 18.6)),
        seq(line(8.4, 18.6, 8.4, 13.0), arc(12, 13.0, 3.6, 180, 360), line(15.6, 13.0, 15.6, 18.6))],
     f=[seq(rect(4.8, 17.2, 3.6, 1.6), rect(15.6, 17.2, 3.6, 1.6))],
     apps="PS",
     basis="Photoshop's Magnetic Lasso — a path pulled onto the nearest edge.",
     note="A path clinging to a hard edge.")

icon("select-wand", "selection", ["magic wand", "similar pixels", "tolerance"],
     s=[line(3.4, 20.6, 12.6, 11.4),
        seq(line(17.2, 3.4, 17.2, 6.4), line(20.6, 6.8, 17.8, 8.0), line(14.6, 5.0, 17.0, 7.0))],
     f=[dot(14.8, 9.2, 1.6)],
     apps="PS/PR",
     basis="Photoshop's Magic Wand and Procreate's Automatic selection.",
     note="A wand and the sparks it throws.")

icon("select-quick", "selection", ["quick selection", "brush select", "grow"],
     s=[dashed(3.4, 19.0, 20.6, 19.0, 3),
        poly([(-8.0, -1.8), (0.6, -1.8), (0.6, 1.8), (-8.0, 1.8)], close=True, t=_QUICK)],
     f=[poly([(0.6, -2.4), (6.6, 0), (0.6, 2.4)], close=True, t=_QUICK)],
     apps="PS",
     basis="Photoshop's Quick Selection — a brush that paints a selection edge.",
     note="A brush laying down ants instead of pixels.")

icon("select-object", "selection", ["object select", "auto", "detect"],
     s=[dashed_rect(3.2, 4.4, 17.6, 15.2)],
     f=[poly([(7.8, 15.8), (9.8, 8.6), (16.4, 11.4), (15.4, 15.8)], close=True)],
     apps="PS",
     basis="Photoshop's Object Selection.",
     note="A found object, solid inside the box fitted to it.")

icon("select-subject", "selection", ["person", "cut out", "isolate"],
     s=[dashed_rect(3.0, 3.4, 18.0, 17.2)],
     f=[seq(circle(12, 9.0, 2.6),
            poly([(7.4, 18.4), (8.6, 13.8), (15.4, 13.8), (16.6, 18.4)], close=True))],
     apps="PS/PR",
     basis="Photoshop's Select Subject and Procreate's subject isolation.",
     note="A figure held solid inside the frame it was found in.")

icon("select-sky", "selection", ["sky replacement", "horizon", "upper region"],
     s=[dashed(2.8, 5.4, 21.2, 5.4, 4), circle(7.4, 9.6, 2.2)],
     f=[poly([(2.8, 19.6), (8.6, 12.4), (13.4, 17.0), (17.0, 13.2), (21.2, 19.6)], close=True)],
     apps="PS",
     basis="Photoshop's Select Sky.",
     note="Everything above the ridge line, sun included.")

icon("select-color-range", "selection", ["by colour", "tolerance", "sample"],
     s=[arc(12, 17.0, 8.4, 180, 360), arc(12, 17.0, 5.8, 180, 360), arc(12, 17.0, 3.2, 180, 360)],
     f=[],
     apps="PS",
     basis="Photoshop's Color Range dialog.",
     note="A sampled colour driving the range beneath it.")

icon("select-focus", "selection", ["focus area", "depth", "sharp region"],
     s=[circle(12, 12, 5.0),
        seq(poly([(3.4, 7.4), (3.4, 3.4), (7.4, 3.4)]),
            poly([(16.6, 3.4), (20.6, 3.4), (20.6, 7.4)]),
            poly([(20.6, 16.6), (20.6, 20.6), (16.6, 20.6)]),
            poly([(7.4, 20.6), (3.4, 20.6), (3.4, 16.6)]))],
     f=[dot(12, 12, 1.4)],
     apps="PS",
     basis="Photoshop's Focus Area.",
     note="Brackets closing on the part of the frame that is sharp.")

icon("select-similar", "selection", ["grow", "like this", "match"],
     s=[poly([(3.4, 8.4), (8.0, 3.6), (12.6, 8.4), (8.0, 13.2)], close=True),
        ants_poly([(11.4, 15.6), (16.0, 10.8), (20.6, 15.6), (16.0, 20.4)])],
     f=[],
     apps="PS/AI",
     basis="Illustrator's Select Similar and Photoshop's Grow / Similar.",
     note="One shape found, its twin selected by resemblance.")

# ---------------------------------------------------------------------------------------
# Changing a selection
# ---------------------------------------------------------------------------------------

icon("select-all", "selection", ["everything", "whole canvas"],
     s=[dashed_rect(2.8, 2.8, 18.4, 18.4), rect(7.6, 7.6, 8.8, 8.8)],
     f=[],
     apps="PS/AI/PR",
     basis="Photoshop's Select All — ants on the document edge.",
     note="The ants run the full edge, whatever is inside.")

icon("select-none", "selection", ["deselect", "drop selection", "clear"],
     s=[dashed_rect(3.4, 3.4, 17.2, 17.2), line(8.4, 8.4, 15.6, 15.6), line(15.6, 8.4, 8.4, 15.6)],
     f=[],
     apps="PS/AI/PR",
     basis="Photoshop's Deselect and Procreate's Clear selection.",
     note="The selection struck out.")

icon("select-reselect", "selection", ["restore selection", "again"],
     s=[dashed_rect(3.4, 3.4, 17.2, 17.2), arc(12, 12.6, 5.2, 140, 400)],
     f=[tip(16.0, 9.0, 2.6, 60)],
     apps="PS",
     basis="Photoshop's Reselect.",
     note="The last selection called back.")

icon("select-invert", "selection", ["inverse", "everything else", "swap"],
     s=[rect(8.6, 8.6, 6.8, 6.8)],
     f=[ring_rect(2.8, 2.8, 18.4, 18.4, 4.0)],
     apps="PS/AI/PR",
     basis="Photoshop's Inverse and Procreate's Invert selection.",
     note="Held ground and released ground have traded places.")

icon("select-grow", "selection", ["expand", "dilate", "outward"],
     s=[rect(8.6, 8.6, 6.8, 6.8), rect(2.8, 2.8, 18.4, 18.4)],
     f=[seq(tip(4.4, 4.4, 3.4, 225), tip(19.6, 19.6, 3.4, 45))],
     apps="PS",
     basis="Photoshop's Modify > Expand.",
     note="An edge pushed out to a wider one.")

icon("select-shrink", "selection", ["contract", "erode", "inward"],
     s=[rect(8.6, 8.6, 6.8, 6.8), rect(2.8, 2.8, 18.4, 18.4)],
     f=[seq(tip(7.4, 7.4, 3.4, 45), tip(16.6, 16.6, 3.4, 225))],
     apps="PS",
     basis="Photoshop's Modify > Contract.",
     note="An edge pulled in to a tighter one.")

icon("select-feather", "selection", ["soften edge", "falloff", "blur selection"],
     s=[rect(3.0, 6.0, 11.6, 12.0),
        seq(line(16.6, 6.0, 16.6, 18.0), line(18.8, 8.2, 18.8, 15.8), line(21.0, 10.6, 21.0, 13.4))],
     f=[],
     apps="PS/AI/PR",
     basis="Photoshop's Modify > Feather and Procreate's Feather slider.",
     note="A hard edge that gives out over three steps.")

icon("select-refine", "selection", ["refine edge", "hair", "detail"],
     s=[dashed(8.0, 3.4, 8.0, 20.6, 4),
        seq(quad((8, 7.0), (14, 5.4), (18.4, 8.6)), quad((8, 12.0), (15, 11.2), (20.0, 13.4)),
            quad((8, 17.0), (14, 16.6), (17.6, 19.4)))],
     f=[],
     apps="PS",
     basis="Photoshop's Select and Mask / Refine Edge.",
     note="Detail recovered from beyond a straight edge.")

icon("select-transform", "selection", ["transform selection", "handles", "resize"],
     s=[rect(5.6, 5.6, 12.8, 12.8)],
     f=[seq(square(5.6, 5.6, 2.8), square(18.4, 5.6, 2.8),
            square(18.4, 18.4, 2.8), square(5.6, 18.4, 2.8))],
     apps="PS/AI",
     basis="Photoshop's Transform Selection.",
     note="A selection edge under its own handles.")

# ---------------------------------------------------------------------------------------
# Boolean combination — one pair of discs, four results, the result always solid
# ---------------------------------------------------------------------------------------

icon("select-add", "selection", ["union", "unite", "add to selection"],
     s=[circle(*_A, _R), circle(*_B, _R)],
     f=[seq(circle(*_A, _R), circle(*_B, _R))],
     apps="PS/AI/PR",
     basis="Illustrator's Pathfinder Unite and Photoshop's Add to Selection.",
     note="Both regions held.")

icon("select-subtract", "selection", ["minus front", "remove", "take away"],
     s=[circle(*_A, _R), circle(*_B, _R)],
     f=[_LEFT],
     apps="PS/AI/PR",
     basis="Illustrator's Minus Front and Photoshop's Subtract from Selection.",
     note="What is left of the first once the second is taken out.")

icon("select-intersect", "selection", ["overlap", "common", "both"],
     s=[circle(*_A, _R), circle(*_B, _R)],
     f=[_LENS],
     apps="PS/AI/PR",
     basis="Illustrator's Intersect and Photoshop's Intersect with Selection.",
     note="Only the ground both regions hold.")

icon("select-exclude", "selection", ["difference", "xor", "either but not both"],
     s=[circle(*_A, _R), circle(*_B, _R)],
     f=[seq(_LEFT, _RIGHT)],
     apps="AI",
     basis="Illustrator's Pathfinder Exclude.",
     note="Everything except the ground they share.")

# ---------------------------------------------------------------------------------------
# Masking
# ---------------------------------------------------------------------------------------

icon("mask", "selection", ["layer mask", "reveal", "conceal"],
     s=[rect(3.0, 4.6, 18.0, 14.8), line(12, 4.6, 12, 19.4)],
     f=[rect(12, 4.6, 9.0, 14.8)],
     apps="PS/PR",
     basis="Photoshop's layer-mask thumbnail — half revealed, half concealed.",
     note="One plate, half of it withheld.")

icon("mask-add", "selection", ["new mask", "attach mask"],
     s=[rect(3.0, 3.6, 13.6, 13.6), line(9.8, 3.6, 9.8, 17.2),
        seq(line(18.0, 15.4, 18.0, 21.4), line(15.0, 18.4, 21.0, 18.4))],
     f=[rect(9.8, 3.6, 6.8, 13.6)],
     apps="PS/PR",
     basis="Photoshop's Add Layer Mask button and Procreate's Mask.",
     note="A mask attached to what did not have one.")

icon("mask-delete", "selection", ["remove mask", "discard"],
     s=[rect(3.0, 3.6, 13.6, 13.6), line(9.8, 3.6, 9.8, 17.2),
        line(15.8, 16.2, 20.8, 21.2), line(20.8, 16.2, 15.8, 21.2)],
     f=[rect(9.8, 3.6, 6.8, 13.6)],
     apps="PS/PR",
     basis="Photoshop's Delete Layer Mask.",
     note="The mask thrown away; the plate stays.")

icon("mask-apply", "selection", ["bake mask", "commit", "flatten mask"],
     s=[rect(3.0, 3.6, 13.6, 13.6), line(9.8, 3.6, 9.8, 17.2),
        poly([(15.4, 19.0), (17.6, 21.2), (21.2, 15.8)])],
     f=[rect(9.8, 3.6, 6.8, 13.6)],
     apps="PS",
     basis="Photoshop's Apply Layer Mask.",
     note="The mask made permanent.")

icon("mask-disable", "selection", ["turn off mask", "bypass"],
     s=[rect(3.0, 4.6, 18.0, 14.8), slash(12, 12, 9.6)],
     f=[rect(12, 4.6, 9.0, 14.8)],
     apps="PS",
     basis="Photoshop's Disable Layer Mask — the cross over the thumbnail.",
     note="A mask still attached but not acting.")

icon("mask-vector", "selection", ["path mask", "clip path"],
     s=[rect(3.0, 4.6, 18.0, 14.8),
        quad((6.6, 16.4), (12.0, 5.8), (17.4, 16.4))],
     f=[seq(square(6.6, 16.4, 2.6), square(17.4, 16.4, 2.6))],
     apps="PS/AI",
     basis="Photoshop's vector mask and Illustrator's clipping path.",
     note="A mask whose edge is a path, not a painting.")

icon("mask-clipping", "selection", ["clip to layer", "clipped", "inside"],
     s=[rect(3.4, 3.4, 17.2, 17.2), line(10.2, 3.4, 10.2, 13.8), line(10.2, 13.8, 20.6, 13.8)],
     f=[poly([(3.4, 13.8), (10.2, 13.8), (10.2, 3.4), (20.6, 3.4), (20.6, 20.6), (3.4, 20.6)],
             close=True)],
     apps="PS/AI",
     basis="Photoshop's Create Clipping Mask and Procreate's clipping arrow.",
     note="The upper plate bent down into the shape of the one beneath it.")

icon("mask-quick", "selection", ["quick mask", "rubylith", "paint a selection"],
     s=[rect(3.0, 4.6, 18.0, 14.8), ants_circle(12, 12, 4.6)],
     f=[],
     apps="PS",
     basis="Photoshop's Quick Mask mode — the rubylith overlay.",
     note="A temporary overlay, hatched so it is never mistaken for art.")

icon("mask-luminance", "selection", ["luminosity mask", "by tone", "highlights"],
     s=[rect(3.0, 4.6, 18.0, 14.8),
        seq(line(7.0, 16.4, 7.0, 12.4), line(12.0, 16.4, 12.0, 8.4), line(17.0, 16.4, 17.0, 12.4))],
     f=[dot(12.0, 6.6, 1.2)],
     apps="PS",
     basis="Photoshop's luminosity masking, read off the tonal distribution.",
     note="A mask taken from the histogram rather than drawn.")

icon("mask-channel", "selection", ["save selection", "alpha channel", "store"],
     s=[rect(3.0, 3.4, 18.0, 5.2), rect(3.0, 9.4, 18.0, 5.2), rect(3.0, 15.4, 18.0, 5.2)],
     f=[rect(3.0, 15.4, 9.0, 5.2)],
     apps="PS",
     basis="Photoshop's Channels panel — a selection stored as an alpha channel.",
     note="Three channels, the last carrying a stored selection.")
