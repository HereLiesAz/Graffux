"""Transform & arrange — position, scale, alignment, order.

Alignment icons are the one place a purely conventional, cross-app language already exists
(the six align/distribute glyphs are near-identical in every design tool ever shipped) — kept
faithfully. Move/scale/rotate/skew reuse one bounding-box-with-handles silhouette throughout
so the family reads as one grammar, distinguished only by the arrow doing the work.
"""

from kit import (
    Path,
    arc,
    circle,
    dashed,
    dot,
    icon,
    line,
    poly,
    rect,
    seq,
    smooth,
    square,
    tip,
)


def handles(x, y, w, h):
    return seq(square(x, y, 2.4), square(x + w, y, 2.4), square(x + w, y + h, 2.4),
               square(x, y + h, 2.4))


icon("move", "transform", ["position", "pan object", "translate"],
     s=[],
     f=[seq(tip(12, 3.4, 2.2, 90), tip(12, 20.6, 2.2, 270),
            tip(3.4, 12, 2.2, 0), tip(20.6, 12, 2.2, 180))],
     apps="PS/AI/PR",
     basis="The four-way move cross every editor uses for the Move tool.",
     note="Four arrows sharing one centre — free movement, no axis preferred.")

icon("scale", "transform", ["resize", "free transform"],
     s=[rect(6.6, 6.6, 10.8, 10.8)],
     f=[seq(handles(6.6, 6.6, 10.8, 10.8))],
     apps="PS/AI/PR",
     basis="Illustrator's Free Transform bounding box — a frame with all eight handles live.",
     note="A frame under its own corner and edge handles.")

icon("scale-proportional", "transform", ["lock aspect", "uniform scale"],
     s=[rect(8.0, 8.0, 8.0, 8.0), line(3.4, 3.4, 8.0, 8.0), line(20.6, 20.6, 16.0, 16.0)],
     f=[seq(tip(3.4, 3.4, 2.0, 225), tip(20.6, 20.6, 2.0, 45))],
     apps="PS/AI/PR",
     basis="The diagonal double arrow every lock-aspect toggle uses.",
     note="A frame growing only along its own diagonal.")

icon("rotate", "transform", ["turn", "spin object"],
     s=[arc(12, 12, 8.4, -40, 220)],
     f=[tip(19.24, 6.6, 2.4, 130)],
     apps="PS/AI/PR",
     basis="The curved arrow every rotate tool uses.",
     note="An arc most of the way around, with the arrowhead at its live end.")

icon("rotate-90-cw", "transform", ["quarter turn right"],
     s=[rect(6.6, 6.6, 10.8, 10.8), arc(12, 12, 9.6, -30, 60)],
     f=[tip(19.31, 6.8, 2.2, 130)],
     apps="PS/AI/PR",
     basis="A fixed quarter-turn — the arc capped at exactly ninety degrees.",
     note="A frame turned a clean quarter, no more.")

icon("rotate-90-ccw", "transform", ["quarter turn left"],
     s=[rect(6.6, 6.6, 10.8, 10.8), arc(12, 12, 9.6, 210, 300)],
     f=[tip(4.69, 6.8, 2.2, 50)],
     apps="PS/AI/PR",
     basis="The same fixed quarter-turn, the other way.",
     note="A frame turned a clean quarter the other direction.")

icon("rotate-180", "transform", ["half turn"],
     s=[rect(7.4, 11.0, 9.2, 9.2), arc(12, 11.0, 8.2, 180, 360)],
     f=[seq(tip(3.8, 11.0, 2.2, 90), tip(20.2, 11.0, 2.2, 270))],
     apps="PS/AI",
     basis="The same arc taken all the way to a half-turn.",
     note="A frame turned exactly upside down.")

icon("flip-horizontal", "transform", ["mirror x", "flip left right"],
     s=[poly([(9.4, 4.0), (3.4, 12), (9.4, 20.0)], close=True), line(12, 3.4, 12, 20.6)],
     f=[poly([(14.6, 4.0), (20.6, 12), (14.6, 20.0)], close=True)],
     apps="PS/AI/PR",
     basis="The two arrowheads flanking an axis every flip toggle uses.",
     note="A shape and its mirror, meeting at a vertical hinge.")

icon("flip-vertical", "transform", ["mirror y", "flip top bottom"],
     s=[poly([(5.0, 3.6), (19.0, 3.6), (12, 10.2)], close=True), dashed(2.8, 12, 21.2, 12, 4)],
     f=[poly([(5.0, 20.4), (19.0, 20.4), (12, 13.8)], close=True)],
     apps="PS/AI/PR",
     basis="The same pair, turned on a horizontal hinge.",
     note="A shape and its mirror, meeting at a horizontal hinge.")

icon("skew", "transform", ["shear", "slant"],
     s=[poly([(7.4, 3.4), (20.6, 3.4), (16.6, 20.6), (3.4, 20.6)], close=True)],
     f=[],
     apps="PS/AI",
     basis="Illustrator's Shear — a frame with only the top pushed sideways.",
     note="A rectangle leant over on its own base.")

icon("distort", "transform", ["free distort", "perspective corners"],
     s=[poly([(5.4, 5.4), (18.6, 3.4), (20.6, 20.6), (3.4, 16.6)], close=True)],
     f=[],
     apps="PS/AI",
     basis="Photoshop's Distort — four corners each free to move on its own.",
     note="A frame with all four corners pulled independently.")

icon("perspective-transform", "transform", ["perspective corners linked"],
     s=[poly([(6.0, 4.0), (18.0, 4.0), (21.0, 20.0), (3.0, 20.0)], close=True),
        line(6.0, 4.0, 3.0, 20.0), line(18.0, 4.0, 21.0, 20.0)],
     f=[],
     apps="PS",
     basis="Photoshop's Perspective — corners that move together in linked pairs.",
     note="A frame tipped back into depth, both sides moving together.")

icon("puppet-warp", "transform", ["mesh warp", "pin and bend"],
     s=[Path("M4,4L20,4Q12,12 20,20L4,20Q12,12 4,4Z", [(4, 4), (20, 20)])],
     f=[seq(dot(4.6, 4.6, 1.3), dot(19.4, 4.6, 1.3), dot(19.4, 19.4, 1.3), dot(4.6, 19.4, 1.3))],
     apps="PS",
     basis="Photoshop's Puppet Warp — pins set into a form to bend it.",
     note="A form with two pins holding it in a bent pose.")

icon("transform-again", "transform", ["repeat transform"],
     s=[square(7.0, 15.0, 5.4), square(16.0, 15.0, 5.4), arc(11.5, 14.0, 7.0, 200, 340)],
     f=[tip(18.7, 8.4, 2.2, 30)],
     apps="AI",
     basis="Illustrator's Transform Again — the same step repeated in place.",
     note="A shape repeated in a straight, equal run.")

icon("reset-transform", "transform", ["clear transform", "original state"],
     s=[rect(6.6, 6.6, 10.8, 10.8), arc(12, 12, 8.8, 220, 480)],
     f=[tip(12.0, 3.2, 2.2, 180)],
     apps="PS/AI",
     basis="A frame returning to true along the arc it was turned through.",
     note="A frame's transform being wound back to nothing.")

icon("align-left", "transform", ["align objects left"],
     s=[line(4.0, 3.4, 4.0, 20.6), rect(4.0, 6.4, 8.0, 4.0), rect(4.0, 13.6, 13.0, 4.0)],
     f=[],
     apps="PS/AI/PR",
     basis="The align-left glyph shared by every design tool: a rule with objects flush to it.",
     note="Two frames flush to a single edge.")

icon("align-center-h", "transform", ["align objects centre horizontal"],
     s=[line(12, 3.0, 12, 21.0), rect(7.0, 5.0, 10.0, 6.4), rect(4.4, 13.0, 15.2, 6.4)],
     f=[],
     apps="PS/AI/PR",
     basis="The align-centre glyph shared by every design tool.",
     note="Two frames centred on a single axis. Drawn as thin bars rather than frames, a stem "
          "crossing two of them is the Cross of Lorraine.")

icon("align-right", "transform", ["align objects right"],
     s=[line(20.0, 3.4, 20.0, 20.6), rect(12.0, 6.4, 8.0, 4.0), rect(7.0, 13.6, 13.0, 4.0)],
     f=[],
     apps="PS/AI/PR",
     basis="The align-right glyph shared by every design tool.",
     note="Two frames flush to a single edge, the other side.")

icon("align-top", "transform", ["align objects top"],
     s=[line(3.4, 4.0, 20.6, 4.0), rect(6.4, 4.0, 4.0, 8.0), rect(13.6, 4.0, 4.0, 13.0)],
     f=[],
     apps="PS/AI/PR",
     basis="The align-top glyph, rotated ninety degrees from align-left.",
     note="Two frames flush to a single edge overhead.")

icon("align-middle-v", "transform", ["align objects centre vertical"],
     s=[line(3.4, 12, 20.6, 12), rect(6.4, 8.0, 4.0, 8.0), rect(13.6, 5.5, 4.0, 13.0)],
     f=[],
     apps="PS/AI/PR",
     basis="The vertical-centre glyph, matching align-centre-horizontal at ninety degrees.",
     note="Two frames centred on a single horizontal axis.")

icon("align-bottom", "transform", ["align objects bottom"],
     s=[line(3.4, 20.0, 20.6, 20.0), rect(6.4, 12.0, 4.0, 8.0), rect(13.6, 7.0, 4.0, 13.0)],
     f=[],
     apps="PS/AI/PR",
     basis="The align-bottom glyph.",
     note="Two frames flush to a single edge underneath.")

icon("distribute-horizontal", "transform", ["equal gaps across"],
     s=[seq(rect(3.4, 8.0, 4.0, 8.0), rect(10.0, 8.0, 4.0, 8.0), rect(16.6, 8.0, 4.0, 8.0))],
     f=[],
     apps="PS/AI/PR",
     basis="The three-equal-blocks glyph every distribute-horizontally control uses.",
     note="Three frames spaced by one equal gap.")

icon("distribute-vertical", "transform", ["equal gaps down"],
     s=[seq(rect(8.0, 3.4, 8.0, 4.0), rect(8.0, 10.0, 8.0, 4.0), rect(8.0, 16.6, 8.0, 4.0))],
     f=[],
     apps="PS/AI/PR",
     basis="The vertical twin of distribute-horizontal.",
     note="Three frames stacked by one equal gap.")

icon("align-to-artboard", "transform", ["align to canvas", "align to page"],
     s=[rect(3.0, 3.0, 18.0, 18.0), rect(9.4, 9.4, 5.2, 5.2)],
     f=[],
     apps="AI",
     basis="Illustrator's Align To Artboard mode.",
     note="A frame aligned against the whole page, not its neighbours.")

icon("snap-to-grid", "transform", ["snap", "grid magnet"],
     s=[seq(line(8.0, 3.4, 8.0, 20.6), line(16.0, 3.4, 16.0, 20.6),
            line(3.4, 8.0, 20.6, 8.0), line(3.4, 16.0, 20.6, 16.0))],
     f=[square(8.0, 8.0, 3.2)],
     apps="PS/AI/PR",
     basis="Every editor's Snap to Grid.",
     note="A grid with one object caught exactly on its intersection.")

icon("smart-guides", "transform", ["alignment guides", "magenta lines"],
     s=[square(6.6, 6.6, 6.4), square(17.4, 17.4, 6.4), dashed(9.8, 9.8, 14.2, 14.2, 3)],
     f=[],
     apps="AI",
     basis="Illustrator's Smart Guides — a dashed line appearing between two aligned objects.",
     note="Two objects with the guide that appears once they line up.")
