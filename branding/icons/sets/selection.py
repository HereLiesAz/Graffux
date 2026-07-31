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


# Illustrator's Pathfinder pair: two offset squares, A up-left and B down-right, overlapping
# in a 4-unit core. Every boolean result is a plain polygon off this one pair — cheaper than
# circle lunes, unambiguous at 16px, and (unlike two filled discs) it reads as geometry.
_SQ_A = (4.0, 4.0, 10.0, 10.0)
_SQ_B = (10.0, 10.0, 10.0, 10.0)
_UNION = poly([(4, 4), (14, 4), (14, 10), (20, 10), (20, 20), (10, 20), (10, 14), (4, 14)],
              close=True)
_ONLY_A = poly([(4, 4), (14, 4), (14, 10), (10, 10), (10, 14), (4, 14)], close=True)
_ONLY_B = poly([(20, 20), (10, 20), (10, 14), (14, 14), (14, 10), (20, 10)], close=True)
_CORE = rect(10, 10, 4, 4)

_WAND = xf(12.4, 12.4, 135)

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
     s=[smooth([(9.0, 14.6), (4.6, 9.6), (9.6, 4.0), (17.2, 5.0), (19.4, 11.0), (14.0, 15.0),
                (7.6, 14.4)]),
        smooth([(7.6, 14.4), (10.4, 17.6), (6.0, 19.4), (9.4, 21.0)])],
     f=[],
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
     s=[line(5.4, 20.0, 5.4, 11.4), arc(12, 11.4, 6.6, 180, 360), line(18.6, 20.0, 18.6, 11.4),
        line(9.4, 6.2, 9.4, 3.2), line(14.6, 6.2, 14.6, 3.2)],
     f=[seq(rect(3.8, 20.0, 3.2, 2.0), rect(17.0, 20.0, 3.2, 2.0))],
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

_QUICK2 = xf(16.6, 7.4, 135)

icon("select-quick", "selection", ["quick selection", "brush select", "grow"],
     s=[ants_circle(8.6, 15.4, 6.2),
        poly([(-4.6, -1.4), (0.4, -1.4), (0.4, 1.4), (-4.6, 1.4)], close=True, t=_QUICK2)],
     f=[poly([(0.4, -1.9), (4.4, 0), (0.4, 1.9)], close=True, t=_QUICK2)],
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
     s=[dashed_rect(2.8, 2.8, 18.4, 12.6, 3),
        poly([(2.8, 20.6), (7.4, 15.4), (11.4, 18.2), (15.4, 14.4), (21.2, 20.6)])],
     f=[dot(16.6, 7.4, 2.4)],
     apps="PS",
     basis="Photoshop's Select Sky.",
     note="Everything above the ridge line, sun included.")

icon("select-color-range", "selection", ["by colour", "tolerance", "sample"],
     s=[seq(rect(3.0, 8.0, 5.4, 8.0), rect(15.6, 8.0, 5.4, 8.0)),
        dashed_rect(8.8, 6.4, 6.4, 11.2, 2)],
     f=[rect(15.6, 8.0, 5.4, 8.0)],
     apps="PS",
     basis="Photoshop's Color Range dialog.",
     note="A sampled colour driving the range beneath it.")

icon("select-focus", "selection", ["focus area", "depth", "sharp region"],
     s=[seq(circle(7.6, 13.4, 3.2), circle(16.0, 8.0, 5.4)),
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
     s=[seq(arc(12, 12, 5.0, 15, 75), arc(12, 12, 5.0, 105, 165),
            arc(12, 12, 5.0, 195, 255), arc(12, 12, 5.0, 285, 345))],
     f=[ring_rect(2.8, 2.8, 18.4, 18.4, 3.2)],
     apps="PS/AI/PR",
     basis="Photoshop's Inverse and Procreate's Invert selection.",
     note="Held ground and released ground have traded places, the boundary still marching. A "
          "solid frame around a solid square is a QR finder pattern, and phones will try to "
          "read it.")

icon("select-grow", "selection", ["expand", "dilate", "outward"],
     s=[rect(8.6, 8.6, 6.8, 6.8), rect(2.8, 2.8, 18.4, 18.4)],
     f=[seq(tip(12, 3.4, 2.6, 270), tip(20.6, 12, 2.6, 0),
            tip(12, 20.6, 2.6, 90), tip(3.4, 12, 2.6, 180))],
     apps="PS",
     basis="Photoshop's Modify > Expand.",
     note="An edge pushed out to a wider one, the pressure applied at the middle of each side. "
          "Four solid wedges driven out of the four corners instead is an Iron Cross, which is "
          "not a thing you can ship however you meant it.")

icon("select-shrink", "selection", ["contract", "erode", "inward"],
     s=[rect(8.6, 8.6, 6.8, 6.8), rect(2.8, 2.8, 18.4, 18.4)],
     f=[seq(tip(6.8, 6.8, 2.6, 45), tip(17.2, 6.8, 2.6, 135),
            tip(17.2, 17.2, 2.6, 225), tip(6.8, 17.2, 2.6, 315))],
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
     s=[dashed(5.4, 3.0, 5.4, 21.0, 4), circle(13.6, 12, 6.4),
        seq(quad((10.4, 8.0), (13.4, 9.6), (10.8, 12.0)), quad((10.6, 12.6), (13.8, 13.6), (11.0, 16.2)))],
     f=[],
     apps="PS",
     basis="Photoshop's Select and Mask / Refine Edge.",
     note="Detail recovered from beyond a straight edge.")

icon("select-transform", "selection", ["transform selection", "handles", "resize"],
     s=[rect(6.0, 6.0, 12.0, 12.0), arc(18.0, 6.0, 4.4, 270, 360)],
     f=[seq(square(6.0, 6.0, 2.6), square(18.0, 6.0, 2.6),
            square(18.0, 18.0, 2.6), square(6.0, 18.0, 2.6))],
     apps="PS/AI",
     basis="Photoshop's Transform Selection.",
     note="A selection edge under its own handles.")

# ---------------------------------------------------------------------------------------
# Boolean combination — one pair of discs, four results, the result always solid
# ---------------------------------------------------------------------------------------

icon("select-add", "selection", ["union", "unite", "add to selection"],
     s=[rect(*_SQ_A), rect(*_SQ_B)],
     f=[_UNION],
     apps="PS/AI/PR",
     basis="Illustrator's Pathfinder Unite and Photoshop's Add to Selection.",
     note="Both regions held.")

icon("select-subtract", "selection", ["minus front", "remove", "take away"],
     s=[rect(*_SQ_A), rect(*_SQ_B)],
     f=[_ONLY_A],
     apps="PS/AI/PR",
     basis="Illustrator's Minus Front and Photoshop's Subtract from Selection.",
     note="What is left of the first once the second is taken out.")

icon("select-intersect", "selection", ["overlap", "common", "both"],
     s=[rect(*_SQ_A), rect(*_SQ_B)],
     f=[_CORE],
     apps="PS/AI/PR",
     basis="Illustrator's Intersect and Photoshop's Intersect with Selection.",
     note="Only the ground both regions hold.")

icon("select-exclude", "selection", ["difference", "xor", "either but not both"],
     s=[rect(*_SQ_A), rect(*_SQ_B)],
     f=[seq(_ONLY_A, _ONLY_B)],
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
     s=[rect(3.0, 5.4, 18.0, 13.2)],
     f=[seq(rect(5.4, 13.6, 2.6, 3.0), rect(9.0, 9.4, 2.6, 7.2),
            rect(12.6, 11.4, 2.6, 5.2), rect(16.2, 14.8, 2.6, 1.8))],
     apps="PS",
     basis="Photoshop's luminosity masking, read off the tonal distribution.",
     note="A mask taken from the histogram rather than drawn.")

icon("mask-channel", "selection", ["save selection", "alpha channel", "store"],
     s=[rect(3.0, 3.4, 18.0, 5.2), rect(3.0, 9.4, 18.0, 5.2), rect(3.0, 15.4, 18.0, 5.2)],
     f=[rect(3.0, 15.4, 9.0, 5.2)],
     apps="PS",
     basis="Photoshop's Channels panel — a selection stored as an alpha channel.",
     note="Three channels, the last carrying a stored selection.")
