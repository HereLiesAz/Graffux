"""The numbers the whole set is built on.

Everything that has to agree between the masters, the Android drawables and the contact
sheet lives here, so that agreeing is automatic rather than remembered.
"""

VIEW = 24.0            # viewport, in units
LIVE = (2.0, 22.0)     # nothing of consequence outside this
BLEED = (1.0, 23.0)    # and nothing at all outside this
STROKE = 0.5
CAP = "round"
JOIN = "round"

# Hatching. Rules are anchored to the viewport rather than to the region they fill, so two
# ruled icons side by side in a rail read as ruled from the same plate. PHASE is where the
# first rule sits, as a fraction of a pitch below y=0.
HATCH_PITCH = 1.05
HATCH_WEIGHT = 0.3
HATCH_PHASE = 0.28

# A glyph that needs more path data than this is describing its subject rather than
# signalling it. A warning, not an error — some drawings genuinely earn it.
#
# There are two numbers because there are two constructions. A stroked glyph states one
# centreline and lets the stroke give it width; a filled outline has to state both walls and
# every corner twice, which costs two to three times the data for the same drawing. Holding
# the second kind to the first kind's budget flagged all 91 of them at once, and a warning
# that fires on every member of a category is one people learn to scroll past. The filled
# budget is set where it still catches the genuinely overdrawn ones.
BUDGET = 260
BUDGET_FILLED = 700

CATEGORIES = [
    ("tools", "Tools", "Brushes, erasers, retouching, brush parameters, symmetry and guides"),
    ("selection", "Selection", "Marquees, lassos, boolean ops, masks"),
    ("layers", "Layers", "The stack and everything done to it"),
    ("color", "Colour", "Tone, hue, gradients, colour instruments"),
    ("filters", "Filters", "Blur, sharpen, noise, distort, stylise, generative fill"),
    ("vector", "Vector", "Pen tool, anchors, boolean path ops, live shapes"),
    ("type", "Type", "Type tools, character and paragraph controls"),
    ("transform", "Transform", "Move, scale, rotate, skew, align and distribute"),
    ("document", "Document", "New/open/save, canvas and image size, crop, view and zoom"),
    ("history", "History", "Undo, redo, history states, actions and macros"),
    ("animation", "Animation", "Timeline, keyframes, playback transport, export"),
    ("generative", "Generative", "AI generation, AR view, camera capture, stencils"),
    ("ui", "Interface", "Navigation chrome, status, sharing, gestures"),
]


def hatch_lines(y0: float, y1: float):
    """The y values of every rule between `y0` and `y1`, on the shared viewport grid."""
    out = []
    k = 0
    while True:
        y = (HATCH_PHASE + k) * HATCH_PITCH
        if y > y1:
            break
        if y >= y0:
            out.append(y)
        k += 1
    return out


def rules_available(y0: float, y1: float) -> int:
    """How many rules a region of this height could carry."""
    return len(hatch_lines(y0, y1))
