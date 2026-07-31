"""Layers & compositing — the stack, and everything done to it.

Photoshop draws layers as stacked sheets and Procreate as two offset squares. Both are here:
the stack is three plates on a fixed pitch, and whichever plate the command acts on is the
solid one. That single rule carries most of the family without any extra ornament.
"""

from kit import (
    arc,
    circle,
    dot,
    icon,
    line,
    poly,
    rect,
    seq,
    slash,
    tip,
)

# The stack: three plates, 4.4 tall, on a 6.4 pitch. Nothing else in the family moves.
_W, _X, _H = 17.2, 3.4, 4.4
_Y = (3.4, 9.8, 16.2)


def plate(i, x=_X, w=_W):
    return rect(x, _Y[i], w, _H)


def plates(*idx):
    return seq(*[plate(i) for i in idx])


def eye():
    """An eye, deliberately asymmetric: a high-arcing upper lid over a nearly flat lower one.

    A symmetric pointed almond — which is what two matched arcs give you — does not read as
    an eye to a viewer with no context. It reads as a vulva. The lids are unequal in life and
    have to be unequal here.
    """
    return seq(arc(12, 15.5, 9.1, 202.6, 337.4),      # upper lid: chord 16.8, sagitta 5.6
               arc(12, 2.58, 12.63, 48.3, 131.7))    # lower lid: same chord, sagitta 3.2


icon("layers", "layers", ["stack", "sheets", "panel"],
     s=[plates(0, 2)],
     f=[plate(1)],
     apps="PS/AI/PR",
     basis="Photoshop's Layers panel and Procreate's offset squares — a stack with one active.",
     note="Three plates; the one being worked on is solid.")

icon("layer-add", "layers", ["new layer", "plus"],
     s=[plates(1, 2), seq(line(12, 2.2, 12, 6.8), line(9.7, 4.5, 14.3, 4.5))],
     f=[],
     apps="PS/AI/PR",
     basis="Photoshop's New Layer button and Procreate's plus.",
     note="A plate added above the stack.")

icon("layer-delete", "layers", ["remove layer", "discard"],
     s=[plates(1, 2), line(9.4, 2.2, 14.6, 6.8), line(14.6, 2.2, 9.4, 6.8)],
     f=[],
     apps="PS/AI/PR",
     basis="Photoshop's Delete Layer.",
     note="The top plate taken out of the stack.")

icon("layer-duplicate", "layers", ["copy layer", "clone layer"],
     s=[rect(3.4, 3.4, 14.2, 14.2)],
     f=[rect(6.4, 6.4, 14.2, 14.2)],
     apps="PS/AI/PR",
     basis="Photoshop's Duplicate Layer — the same plate, offset once.",
     note="One plate, twice, out of register.")

icon("layer-group", "layers", ["folder", "nest", "group"],
     s=[poly([(2.8, 20.6), (2.8, 5.0), (9.2, 5.0), (11.0, 7.6), (21.2, 7.6), (21.2, 20.6)],
             close=True)],
     f=[rect(6.4, 11.4, 11.2, 3.2)],
     apps="PS/AI/PR",
     basis="Photoshop's layer group — the folder, holding a plate.",
     note="A folder with a plate in it.")

icon("layer-ungroup", "layers", ["release", "unnest", "flatten group"],
     s=[poly([(2.8, 20.6), (2.8, 11.4), (9.2, 11.4), (11.0, 14.0), (21.2, 14.0), (21.2, 20.6)],
             close=True),
        rect(7.6, 5.4, 8.8, 4.4)],
     f=[],
     apps="AI",
     basis="Illustrator's Ungroup — contents leaving the folder.",
     note="A plate lifted back out of its folder.")

icon("layer-visible", "layers", ["show", "eye", "toggle"],
     s=[eye(), circle(12, 11.4, 3.0)],
     f=[dot(12, 11.4, 1.25)],
     apps="PS/AI/PR",
     basis="Photoshop's eye column — the oldest control in the panel.",
     note="An open eye.")

icon("layer-hidden", "layers", ["hide", "eye off", "invisible"],
     s=[eye(), slash(12, 12, 9.2)],
     f=[],
     apps="PS/AI/PR",
     basis="Photoshop's eye column, switched off.",
     note="The same eye, struck through.")

icon("layer-lock", "layers", ["protect", "padlock", "read only"],
     s=[rect(4.6, 11.4, 14.8, 9.2), arc(12, 11.4, 4.6, 180, 360)],
     f=[dot(12, 16.0, 1.8)],
     apps="PS/AI/PR",
     basis="Photoshop's lock icons in the Layers panel.",
     note="A padlock, shut.")

icon("layer-unlock", "layers", ["unprotect", "open lock", "editable"],
     s=[rect(4.6, 11.4, 14.8, 9.2), arc(7.4, 11.4, 4.6, 165, 300)],
     f=[dot(12, 16.0, 1.8)],
     apps="PS/AI/PR",
     basis="Photoshop's unlocked padlock — the shackle swung clear.",
     note="The same padlock, open on one side.")

icon("layer-link", "layers", ["chain", "tie together", "move as one"],
     s=[arc(9.6, 14.4, 4.6, -45, 225), arc(14.4, 9.6, 4.6, 135, 405)],
     f=[],
     apps="PS/AI/PR",
     basis="Photoshop's link chain.",
     note="Two open rings, interlocked.")

icon("layer-unlink", "layers", ["break chain", "release"],
     s=[arc(6.6, 14.4, 4.2, -45, 225), arc(17.4, 9.6, 4.2, 135, 405)],
     f=[],
     apps="PS/AI",
     basis="Photoshop's unlink — the same two rings, pulled apart.",
     note="Two open rings that no longer touch.")

icon("layer-merge-down", "layers", ["combine", "flatten two", "down"],
     s=[plate(0)],
     f=[rect(3.4, 13.4, 17.2, 7.2)],
     apps="PS/AI/PR",
     basis="Photoshop's Merge Down and Procreate's pinch-to-merge.",
     note="The upper plate driven into the one below.")

icon("layer-merge-visible", "layers", ["merge shown", "collapse visible"],
     s=[plate(0), circle(18.6, 7.4, 2.6), line(12, 9.2, 12, 12.6)],
     f=[seq(rect(3.4, 15.0, 17.2, 5.6), dot(18.6, 7.4, 1.0), tip(12, 14.0, 2.4, 90))],
     apps="PS",
     basis="Photoshop's Merge Visible — a merge with the eye column as its condition.",
     note="A merge, qualified.")

icon("layer-flatten", "layers", ["one layer", "collapse all"],
     s=[line(3.4, 4.2, 20.6, 4.2), line(4.8, 8.2, 19.2, 8.2), line(6.2, 12.2, 17.8, 12.2),
        line(6.6, 15.4, 6.6, 12.2), line(17.4, 15.4, 17.4, 12.2)],
     f=[rect(3.4, 15.4, 17.2, 5.2)],
     apps="PS/AI/PR",
     basis="Photoshop's Flatten Image — everything pressed into one plate.",
     note="Three rules pressed down into one solid plate.")

icon("layer-raise", "layers", ["bring forward", "up one"],
     s=[plates(1, 2), line(12, 7.6, 12, 3.4)],
     f=[tip(12, 2.0, 3.0, 270)],
     apps="PS/AI/PR",
     basis="Photoshop's Arrange > Bring Forward.",
     note="A plate moved one place up the stack.")

icon("layer-lower", "layers", ["send backward", "down one"],
     s=[plates(0, 1), line(12, 16.4, 12, 20.6)],
     f=[tip(12, 22.0, 3.0, 90)],
     apps="PS/AI/PR",
     basis="Photoshop's Arrange > Send Backward.",
     note="A plate moved one place down the stack.")

icon("layer-to-front", "layers", ["bring to front", "top"],
     s=[plates(1, 2), line(3.4, 2.0, 20.6, 2.0), line(12, 7.6, 12, 4.4)],
     f=[tip(12, 3.4, 2.6, 270)],
     apps="PS/AI",
     basis="Illustrator's Bring to Front — up against the ceiling.",
     note="A plate driven to the top of the stack.")

icon("layer-to-back", "layers", ["send to back", "bottom"],
     s=[plates(0, 1), line(3.4, 22.0, 20.6, 22.0), line(12, 16.4, 12, 19.6)],
     f=[tip(12, 20.6, 2.6, 90)],
     apps="PS/AI",
     basis="Illustrator's Send to Back.",
     note="A plate driven to the bottom of the stack.")

icon("layer-opacity", "layers", ["transparency", "alpha", "fade"],
     s=[rect(3.4, 8.0, 17.2, 8.0)],
     f=[seq(rect(3.4, 8.0, 5.8, 8.0), rect(10.6, 8.0, 3.0, 8.0), rect(15.0, 8.0, 1.6, 8.0))],
     apps="PS/AI/PR",
     basis="Photoshop's Opacity field — one tone thinning until the ground shows through.",
     note="A bar of colour breaking up as it fades out. A single bead threaded on a rule, which "
          "is what this was, is a nipple piercing.")

icon("layer-blend", "layers", ["blend mode", "mix", "interaction"],
     s=[circle(9.4, 12, 6.0), circle(14.6, 12, 6.0)],
     f=[],
     apps="PS/AI/PR",
     basis="Photoshop's blend-mode menu — two plates deciding how to meet.",
     note="Two fields overlapping, with nothing yet asserted about the result.")

icon("layer-adjustment", "layers", ["adjustment layer", "non destructive"],
     s=[rect(3.0, 5.0, 18.0, 14.0), circle(12, 12, 5.2)],
     f=[seq(arc(12, 12, 5.2, 270, 450), line(12, 17.2, 12, 6.8))],
     apps="PS",
     basis="Photoshop's adjustment-layer thumbnail — the half-black disc.",
     note="A disc split down the middle: correction, not paint.")

icon("layer-fill", "layers", ["solid colour", "fill layer"],
     s=[rect(3.4, 3.4, 17.2, 17.2)],
     f=[rect(7.4, 7.4, 9.2, 9.2)],
     apps="PS",
     basis="Photoshop's solid-colour fill layer.",
     note="A plate carrying nothing but colour.")

icon("layer-smart", "layers", ["smart object", "linked", "non destructive"],
     s=[rect(3.4, 3.4, 17.2, 17.2)],
     f=[poly([(20.6, 20.6), (14.0, 20.6), (20.6, 14.0)], close=True)],
     apps="PS",
     basis="Photoshop's Smart Object badge in the corner of the thumbnail.",
     note="A plate with the badge that says it is still linked to its source.")

icon("layer-rasterize", "layers", ["to pixels", "commit", "bake"],
     s=[poly([(3.4, 20.6), (3.4, 3.4), (20.6, 3.4)]),
        line(3.4, 20.6, 20.6, 3.4)],
     f=[seq(rect(11.4, 11.4, 4.6, 4.6), rect(16.0, 11.4, 4.6, 4.6),
            rect(11.4, 16.0, 4.6, 4.6), rect(16.0, 16.0, 4.6, 4.6))],
     apps="PS/AI",
     basis="Photoshop's Rasterize — geometry becoming a grid of samples.",
     note="A clean corner giving way to a pixel grid.")

icon("layer-style", "layers", ["fx", "effects", "layer effects"],
     s=[rect(6.6, 6.6, 10.8, 10.8), circle(12, 12, 9.4)],
     f=[],
     apps="PS",
     basis="Photoshop's fx badge — a plate with the shadow its effects throw.",
     note="A plate and the solid shadow cast behind it.")

icon("layer-isolate", "layers", ["solo", "only this", "focus layer"],
     s=[rect(3.0, 8.4, 5.0, 5.0), rect(16.0, 8.4, 5.0, 5.0)],
     f=[plate(1)],
     apps="AI/PR",
     basis="Illustrator's isolation mode — the rest of the stack goes quiet.",
     note="One plate held solid between two that have gone to rules.")

icon("layer-reference", "layers", ["reference layer", "source", "pin"],
     s=[plates(0, 1, 2)],
     f=[dot(18.4, 18.4, 1.7)],
     apps="PR",
     basis="Procreate's Reference layer — one plate marked as the source others read.",
     note="A stack with one plate flagged.")

icon("layer-rename", "layers", ["label", "title", "edit name"],
     s=[line(3.4, 6.0, 12.4, 6.0), line(3.4, 11.0, 9.0, 11.0),
        poly([(11.6, 16.4), (18.0, 10.0), (20.6, 12.6), (14.2, 19.0), (10.8, 19.8)], close=True)],
     f=[],
     apps="PS/PR",
     basis="Photoshop's double-click-to-rename, drawn as a pencil over the row.",
     note="A panel row under a pencil.")

icon("layer-tag", "layers", ["colour label", "flag", "sort"],
     s=[plates(0, 2)],
     f=[plate(1, x=3.4, w=4.4)],
     apps="PS",
     basis="Photoshop's colour labels on layer rows — one row flagged at its edge.",
     note="A stack with one row flagged down its edge.")

icon("layer-filter", "layers", ["find layer", "search stack"],
     s=[plates(0, 1), circle(14.8, 16.8, 3.8), line(17.6, 19.6, 20.8, 22.0)],
     f=[],
     apps="PS",
     basis="Photoshop's layer-filter bar at the top of the panel.",
     note="A stack being searched.")

icon("layer-count", "layers", ["how many", "stack depth"],
     s=[plates(0, 1, 2), seq(line(3.4, 3.4, 3.4, 20.6), line(20.6, 3.4, 20.6, 20.6))],
     f=[],
     apps="new",
     note="A stack bracketed top to bottom, counted as one span.")

icon("artboard", "layers", ["canvas", "page", "document frame"],
     s=[rect(3.4, 6.4, 17.2, 14.2), line(3.4, 3.4, 9.4, 3.4)],
     f=[],
     apps="AI",
     basis="Illustrator's artboard — a frame with its name tab at the corner.",
     note="A bounded page with a title tab.")

icon("artboard-add", "layers", ["new artboard", "new page"],
     s=[rect(3.4, 6.4, 11.4, 11.4), line(3.4, 3.4, 9.4, 3.4),
        seq(line(18.2, 12.6, 18.2, 19.4), line(14.8, 16.0, 21.6, 16.0))],
     f=[],
     apps="AI",
     basis="Illustrator's New Artboard.",
     note="A second page being started.")

icon("layer-background", "layers", ["backdrop", "base", "foundation"],
     s=[plates(0, 1)],
     f=[rect(2.2, 16.2, 19.6, 5.2)],
     apps="PS/PR",
     basis="Photoshop's transparency chequer, which is what a missing background looks like.",
     note="The chequer that means there is nothing behind.")

icon("layer-frame", "layers", ["frame tool", "placeholder", "crop into"],
     s=[seq(poly([(3.4, 8.0), (3.4, 3.4), (8.0, 3.4)]),
            poly([(16.0, 3.4), (20.6, 3.4), (20.6, 8.0)]),
            poly([(20.6, 16.0), (20.6, 20.6), (16.0, 20.6)]),
            poly([(8.0, 20.6), (3.4, 20.6), (3.4, 16.0)]))],
     f=[],
     apps="PS",
     basis="Photoshop's Frame tool — a placeholder waiting for content.",
     note="Corner marks around nothing yet placed.")

icon("layer-thumbnail", "layers", ["preview", "row", "panel row"],
     s=[rect(3.4, 7.0, 9.0, 10.0), line(15.0, 9.6, 20.6, 9.6), line(15.0, 14.4, 19.0, 14.4)],
     f=[],
     apps="PS",
     basis="A row of Photoshop's Layers panel: thumbnail left, name right.",
     note="One row of the stack, at rest.")
