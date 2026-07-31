"""Vector & path — anchors, curves, and boolean geometry.

Illustrator's own vocabulary: the pen nib, the hollow/solid anchor point that shows
selection state, the two handles either side of a curve point, the scissors. Boolean ops
here reuse the same two-circle pair from Selection so the two families read as one grammar.
"""

from kit import (
    Path,
    arc,
    circle,
    cut,
    dashed_rect,
    dot,
    icon,
    line,
    poly,
    quad,
    rect,
    ring,
    seq,
    smooth,
    square,
    tip,
)

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


def anchor(cx, cy, r=1.5):
    """A hollow anchor point — an unselected node on a path."""
    return square(cx, cy, r * 2)


def anchor_smooth(cx, cy, r=1.6):
    return circle(cx, cy, r)


icon("pen", "vector", ["bezier", "path tool", "draw vector"],
     s=[line(3.4, 20.6, 10.2, 13.8), line(0.4 + 13, 0.4 + 8.4, 13, 8.4)],
     f=[poly([(13, 8.4), (20.6, 3.4), (17.6, 3.4), (10.2, 10.8), (10.2, 13.8), (13.2, 13.8)],
             close=True)],
     apps="AI/PS",
     basis="Illustrator's Pen tool — a nib drawing a line that ends where the point was set.",
     note="A nib at the end of the line it just placed.")

icon("pen-add", "vector", ["add anchor", "insert point"],
     s=[smooth([(3.4, 16.0), (9.0, 8.0), (14.6, 12.0), (20.6, 6.0)]), circle(9.0, 8.0, 1.4),
        line(17.6, 2.0, 17.6, 6.0), line(15.6, 4.0, 19.6, 4.0)],
     f=[],
     apps="AI",
     basis="Illustrator's Add Anchor Point tool.",
     note="A curve with a new node placed on it.")

icon("pen-remove", "vector", ["delete anchor", "remove point"],
     s=[smooth([(3.4, 16.0), (9.0, 8.0), (14.6, 12.0), (20.6, 6.0)]), circle(9.0, 8.0, 1.4),
        line(15.6, 4.0, 19.6, 4.0)],
     f=[],
     apps="AI",
     basis="Illustrator's Delete Anchor Point tool.",
     note="A curve with one node taken away, unfilled.")

icon("anchor-convert", "vector", ["corner smooth toggle", "convert point"],
     s=[line(3.4, 20.6, 12, 12), line(12, 12, 20.6, 3.4)],
     f=[seq(square(3.4, 20.6, 3.0), circle(20.6, 3.4, 1.7))],
     apps="AI",
     basis="Illustrator's Convert Anchor Point tool.",
     note="A corner node and a smooth node, either end of the same handle.")

icon("path-select-direct", "vector", ["white arrow", "edit anchors"],
     s=[poly([(3.4, 3.4), (3.4, 14.6), (6.6, 11.8), (8.8, 16.6), (10.8, 15.6), (8.6, 10.8),
              (12.6, 10.4)], close=True),
        line(14.6, 5.0, 20.6, 10.2), seq(square(14.6, 5.0, 2.4), square(20.6, 10.2, 2.4))],
     f=[square(20.6, 10.2, 2.4)],
     apps="AI",
     basis="Illustrator's Direct Selection — the white arrow that edits nodes, not objects.",
     note="A hollow arrow reaching for one anchor on a path, that anchor solid. Without the "
          "anchors it is exactly the group-select arrow, and at 24 pixels neither could be "
          "told from the other.")

icon("path-select-group", "vector", ["black arrow", "select object"],
     s=[dashed_rect(10.6, 3.4, 10.0, 10.0),
        poly([(3.4, 6.4), (3.4, 17.6), (6.6, 14.8), (8.8, 19.6), (10.8, 18.6), (8.6, 13.8),
              (12.6, 13.4)], close=True)],
     f=[poly([(3.4, 6.4), (3.4, 17.6), (6.6, 14.8), (8.8, 19.6), (10.8, 18.6), (8.6, 13.8),
              (12.6, 13.4)], close=True)],
     apps="AI",
     basis="Illustrator's Selection tool — the black arrow that moves whole objects.",
     note="The solid arrow with a whole object's bounds marching around it — it takes the "
          "object, not one node, and the bounds are what say so.")

icon("path-close", "vector", ["closed path", "join ends"],
     s=[smooth([(12, 19.6), (4.4, 14.6), (7.0, 5.4), (12, 4.4)]),
        smooth([(12, 19.6), (19.6, 14.6), (17.0, 5.4), (12, 4.4)])],
     f=[square(12, 4.4, 3.0)],
     apps="AI",
     basis="Illustrator's Join — the moment two open ends become one point.",
     note="A loop closed at a single solid seam.")

icon("path-open", "vector", ["cut path", "scissors"],
     s=[arc(12, 12, 8.0, 40, 320)],
     f=[seq(square(18.1, 6.9, 3.0), square(18.1, 17.1, 3.0))],
     apps="AI",
     basis="Illustrator's Scissors tool — a ring cut at one point.",
     note="A closed loop with one gap opened in its edge.")

icon("path-simplify", "vector", ["reduce points", "clean path"],
     s=[poly([(3.4, 9.4), (6.6, 6.2), (9.0, 9.8), (11.6, 6.6), (14.0, 9.2), (16.8, 6.0), (20.6, 9.0)]),
        seq(circle(6.6, 6.2, 0.85), circle(11.6, 6.6, 0.85), circle(16.8, 6.0, 0.85)),
        smooth([(3.4, 18.0), (12.0, 15.0), (20.6, 18.0)])],
     f=[dot(12.0, 15.0, 1.3)],
     apps="AI/PS",
     basis="Illustrator's Simplify — a jagged run reduced to fewer, truer segments.",
     note="A zigzag with most of its corners already gone.")

icon("path-outline-stroke", "vector", ["stroke to fill", "expand stroke"],
     s=[line(3.4, 12, 10.0, 12),
        poly([(13.0, 8.4), (20.6, 8.4), (20.6, 15.6), (13.0, 15.6)], close=True),
        line(13.0, 12, 20.6, 12)],
     f=[tip(11.8, 12, 2.0, 0)],
     apps="AI",
     basis="Illustrator's Outline Stroke — a line given width and turned into a shape.",
     note="A stroke on one side of the frame, a filled ribbon on the other.")

icon("path-offset", "vector", ["parallel copy", "grow path"],
     s=[smooth([(8.2, 7.0), (15.0, 7.6), (16.0, 14.0), (9.8, 16.4)], close=True),
        smooth([(6.4, 4.2), (17.4, 4.8), (18.8, 15.6), (9.0, 19.2)], close=True)],
     f=[],
     apps="AI",
     basis="Illustrator's Offset Path.",
     note="A curve and its own parallel copy, held at a fixed distance.")

icon("path-reverse", "vector", ["flip direction", "winding"],
     s=[smooth([(4.0, 16.0), (9.0, 6.0), (20.0, 8.0)])],
     f=[tip(20.0, 8.0, 2.6, -20)],
     apps="AI",
     basis="Illustrator's Reverse Path Direction.",
     note="A path with its direction marked at the end it now runs to.")

icon("handle-symmetric", "vector", ["smooth handles", "mirrored tangent"],
     s=[line(3.4, 18.6, 20.6, 5.4)],
     f=[seq(square(12, 12, 2.6), circle(3.4, 18.6, 1.4), circle(20.6, 5.4, 1.4))],
     apps="AI",
     basis="Illustrator's smooth anchor — two handles, locked opposite and equal.",
     note="One node, two equal handles either side of it.")

icon("handle-asymmetric", "vector", ["broken handles", "independent tangent"],
     s=[line(3.4, 19.4, 12, 12), line(12, 12, 20.6, 8.6)],
     f=[seq(square(12, 12, 2.6), circle(3.4, 19.4, 1.4), circle(20.6, 8.6, 1.4))],
     apps="AI",
     basis="Illustrator's corner anchor with independent handles.",
     note="One node, two handles free to disagree.")

icon("shape-rectangle", "vector", ["vector rect", "live shape"],
     s=[rect(3.4, 3.4, 17.2, 17.2)],
     f=[square(3.4, 3.4, 2.6)],
     apps="AI",
     basis="Illustrator's Rectangle tool, drawn as a live shape with one editable corner.",
     note="A rectangle with the corner that defines it marked live.")

icon("shape-polygon", "vector", ["vector polygon", "n sides"],
     s=[poly([(12, 3.4), (20.0, 9.2), (17.0, 18.6), (7.0, 18.6), (4.0, 9.2)], close=True)],
     f=[square(12, 3.4, 2.4)],
     apps="AI",
     basis="Illustrator's Polygon tool.",
     note="A regular polygon with its lead vertex marked.")

icon("shape-star", "vector", ["vector star", "points"],
     s=[poly([(12, 3.0), (14.4, 9.4), (21.0, 9.8), (15.8, 13.8), (17.6, 20.2), (12, 16.4),
              (6.4, 20.2), (8.2, 13.8), (3.0, 9.8), (9.6, 9.4)], close=True)],
     f=[],
     apps="AI/PR",
     basis="Illustrator's Star tool and Procreate's star shape.",
     note="A five-point star, unfilled to keep the count legible.")

icon("shape-line", "vector", ["straight segment", "line tool"],
     s=[line(3.4, 20.6, 20.6, 3.4)],
     f=[seq(circle(3.4, 20.6, 1.4), circle(20.6, 3.4, 1.4))],
     apps="AI",
     basis="Illustrator's Line Segment tool.",
     note="One straight run with both ends marked.")

icon("shape-spiral", "vector", ["vector spiral", "coil"],
     s=[smooth([(19.6, 12), (18.2, 6.8), (12.8, 4.0), (7.0, 6.2), (5.4, 12.2), (9.2, 16.8),
                (14.6, 15.8), (15.8, 11.4), (12.4, 9.6)])],
     f=[dot(12.4, 9.6, 1.2)],
     apps="AI",
     basis="Illustrator's Spiral tool.",
     note="A vector spiral, tightening in to a single terminal point.")

icon("boolean-unite", "vector", ["pathfinder add", "merge shapes"],
     s=[rect(*_SQ_A), rect(*_SQ_B)],
     f=[_UNION],
     apps="AI",
     basis="Illustrator's Pathfinder — Unite.",
     note="Both shapes held as one.")

icon("boolean-minus", "vector", ["pathfinder subtract", "minus front"],
     s=[rect(*_SQ_A), rect(*_SQ_B)],
     f=[_ONLY_A],
     apps="AI",
     basis="Illustrator's Pathfinder — Minus Front.",
     note="What remains of the back shape once the front is cut from it.")

icon("boolean-intersect", "vector", ["pathfinder intersect"],
     s=[rect(*_SQ_A), rect(*_SQ_B)],
     f=[_CORE],
     apps="AI",
     basis="Illustrator's Pathfinder — Intersect.",
     note="Only the ground the two shapes share.")

icon("boolean-exclude", "vector", ["pathfinder exclude"],
     s=[rect(*_SQ_A), rect(*_SQ_B)],
     f=[seq(_ONLY_A, _ONLY_B)],
     apps="AI",
     basis="Illustrator's Pathfinder — Exclude.",
     note="Everything except the ground the two shapes share.")

icon("compound-path", "vector", ["make compound", "holes"],
     s=[],
     f=[cut(rect(3.4, 3.4, 17.2, 17.2), rect(8.6, 8.6, 6.8, 6.8))],
     apps="AI",
     basis="Illustrator's Compound Path — a ring made from two circles wound oppositely.",
     note="A disc with a true hole cut through it, not painted over.")

icon("clipping-mask-vector", "vector", ["crop to shape", "clip path"],
     s=[poly([(12, 3.4), (20.6, 9.6), (17.4, 20.0), (6.6, 20.0), (3.4, 9.6)], close=True)],
     f=[rect(7.4, 7.4, 9.2, 9.2)],
     apps="AI",
     basis="Illustrator's clipping mask — content cropped to a vector shape.",
     note="A shape holding a rectangle to exactly its own outline.")

icon("trace-image", "vector", ["image trace", "raster to vector"],
     s=[rect(3.4, 3.4, 8.6, 8.6)],
     f=[smooth([(15.0, 5.0), (19.0, 6.4), (20.4, 10.4), (18.0, 14.0), (13.6, 13.4), (12.6, 9.0)],
               close=True)],
     apps="AI",
     basis="Illustrator's Image Trace — pixels resolved into an editable outline.",
     note="A pixel block on one side, a traced outline on the other.")

icon("vectorize", "vector", ["auto vector", "convert to path"],
     s=[seq(line(3.4, 5.4, 3.4, 3.4), line(3.4, 3.4, 5.4, 3.4), line(18.6, 3.4, 20.6, 3.4),
            line(20.6, 3.4, 20.6, 5.4), line(20.6, 18.6, 20.6, 20.6), line(20.6, 20.6, 18.6, 20.6),
            line(5.4, 20.6, 3.4, 20.6), line(3.4, 20.6, 3.4, 18.6))],
     f=[smooth([(9.0, 15.0), (7.6, 9.4), (12.6, 6.6), (17.0, 10.0), (14.6, 15.4)], close=True)],
     apps="new",
     note="A raster field of crop marks resolving into one solid vector shape.")

icon("path-width", "vector", ["variable width", "width profile"],
     s=[poly([(3.4, 12.6), (8.6, 9.4), (8.6, 14.6)], close=True),
        poly([(8.6, 10.6), (20.6, 6.2), (20.6, 17.8), (8.6, 13.4)], close=True)],
     f=[dot(20.6, 12.0, 1.4)],
     apps="AI",
     basis="Illustrator's Width tool.",
     note="A path with the corner where its width would be pulled.")

icon("path-knife", "vector", ["cut shape", "slice"],
     s=[Path("M6.86,17.14A8.4,8.4 0 0,1 16.66,6.06M18.14,7.54A8.4,8.4 0 0,1 8.34,18.62",
             [(4.0, 4.6), (20.0, 20.0)]),
        line(4.6, 19.4, 19.4, 4.6)],
     f=[],
     apps="AI",
     basis="Illustrator's Knife tool.",
     note="A shape crossed clean through by one cut.")

icon("path-eraser-vector", "vector", ["vector eraser", "erase path"],
     s=[seq(line(3.4, 16.6, 8.0, 16.6), line(15.4, 16.6, 20.6, 16.6)),
        rect(8.6, 5.4, 6.8, 6.8)],
     f=[rect(9.6, 13.4, 4.8, 5.2)],
     apps="AI",
     basis="Illustrator's Path Eraser.",
     note="A curve broken open where the eraser has crossed it.")

icon("guide-ruler", "vector", ["smart guide", "snap"],
     s=[seq(line(3.4, 3.4, 20.6, 3.4), line(3.4, 3.4, 3.4, 20.6),
            line(6.4, 3.4, 6.4, 6.4), line(10.4, 3.4, 10.4, 6.4), line(14.4, 3.4, 14.4, 6.4),
            line(3.4, 6.4, 6.4, 6.4), line(3.4, 10.4, 6.4, 10.4), line(3.4, 14.4, 6.4, 14.4))],
     f=[],
     apps="AI",
     basis="Illustrator's rulers, top and left.",
     note="The two rulers a canvas is measured against.")
