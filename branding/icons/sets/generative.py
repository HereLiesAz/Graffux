"""Generative & capture — model-assisted work, camera, AR, stencils.

None of Photoshop, Illustrator or Procreate has a fixed vocabulary for generative AI yet —
the industry is still converging on the sparkle. It is used here because it is already
understood, and reserved for the moment a result is genuinely model-produced. Camera, AR and
stencil icons are original to Graffux, drawn from GraffitiXR's own mural-projection heritage.
"""

from kit import (
    circle,
    dashed,
    dashed_rect,
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


def spark(cx, cy, r):
    return poly([(cx, cy - r), (cx + r * 0.28, cy - r * 0.28), (cx + r, cy),
                 (cx + r * 0.28, cy + r * 0.28), (cx, cy + r), (cx - r * 0.28, cy + r * 0.28),
                 (cx - r, cy), (cx - r * 0.28, cy - r * 0.28)], close=True)


icon("generate", "generative", ["ai generate", "text to image"],
     s=[spark(8.0, 8.0, 3.4)],
     f=[spark(16.4, 16.4, 5.0)],
     apps="new",
     basis="The four-point sparkle every current tool uses for generative AI.",
     note="Two sparkles, the live one solid.")

icon("generate-variations", "generative", ["ai variations", "reroll"],
     s=[rect(3.0, 3.0, 8.0, 8.0), rect(13.0, 3.0, 8.0, 8.0), rect(3.0, 13.0, 8.0, 8.0)],
     f=[spark(17.0, 17.0, 3.2)],
     apps="new",
     note="Three held frames and the sparkle offering a fourth.")

icon("generate-expand", "generative", ["ai outpaint", "generative expand"],
     s=[rect(7.4, 7.4, 9.2, 9.2), dashed_rect(3.0, 3.0, 18.0, 18.0)],
     f=[spark(19.4, 4.6, 2.2)],
     apps="new",
     basis="Photoshop's Generative Expand — a frame growing past its own edge.",
     note="A held frame with new ground opening around it.")

icon("generate-remove", "generative", ["ai remove", "smart erase"],
     s=[circle(10.4, 10.4, 5.4)],
     f=[spark(17.4, 17.4, 2.6)],
     apps="new",
     basis="Photoshop's Generative Fill used to remove — a gap the model closes.",
     note="An empty region with the sparkle that will resolve it.")

icon("upscale", "generative", ["ai upscale", "super resolution"],
     s=[square(9.0, 9.0, 8.0), square(15.0, 15.0, 14.0)],
     f=[],
     apps="new",
     note="A small held frame growing to a larger one, corners aligned.")

icon("style-transfer", "generative", ["ai style", "restyle"],
     s=[circle(8.4, 8.4, 5.0)],
     f=[poly([(15.4, 10.4), (20.6, 15.4), (15.4, 20.6), (10.4, 15.4)], close=True)],
     apps="new",
     note="One field recast into another shape of field entirely.")

icon("prompt", "generative", ["ai text input", "describe"],
     s=[rect(3.0, 8.0, 18.0, 8.0), line(6.4, 12.0, 15.0, 12.0)],
     f=[tip(19.0, 12.0, 1.6, 0)],
     apps="new",
     note="A field of text with the mark that says it will be read as an instruction.")

icon("ar-view", "generative", ["augmented reality", "camera overlay"],
     s=[poly([(4.0, 7.4), (4.0, 4.0), (7.4, 4.0)]),
        poly([(16.6, 4.0), (20.0, 4.0), (20.0, 7.4)]),
        poly([(4.0, 16.6), (4.0, 20.0), (7.4, 20.0)]),
        poly([(20.0, 16.6), (20.0, 20.0), (16.6, 20.0)])],
     f=[poly([(12, 8.0), (16.0, 12), (12, 16.0), (8.0, 12)], close=True)],
     apps="new",
     basis="GraffitiXR's own AR mural-projection view.",
     note="A viewfinder with the projected form already inside it.")

icon("camera-capture", "generative", ["take photo", "reference photo"],
     s=[poly([(3.4, 7.4), (3.4, 18.6), (20.6, 18.6), (20.6, 7.4), (15.4, 7.4), (13.8, 5.4),
              (10.2, 5.4), (8.6, 7.4)], close=True)],
     f=[dot(12, 13.0, 2.6)],
     apps="new",
     note="A camera body with the shutter over its own lens.")

icon("gallery-import", "generative", ["photo library", "import image"],
     s=[rect(3.4, 3.4, 17.2, 17.2)],
     f=[poly([(3.4, 20.6), (9.4, 12.4), (14.0, 17.4), (17.6, 13.2), (20.6, 20.6)], close=True)],
     apps="PS/PR",
     basis="Every editor's Place / Import Image.",
     note="A frame with the picture already loaded into it.")

icon("stencil", "generative", ["screen mask", "spray template"],
     s=[rect(3.4, 3.4, 17.2, 17.2)],
     f=[poly([(9.0, 6.4), (15.0, 6.4), (15.0, 11.0), (12.6, 11.0), (12.6, 17.6), (9.4, 17.6),
              (9.4, 11.0), (9.0, 11.0)], close=True)],
     apps="new",
     basis="GraffitiXR's stencil generation for physical spraying.",
     note="A card with a letterform cut clean through it.")

icon("stencil-layers", "generative", ["multi layer stencil", "registration"],
     s=[seq(square(6.6, 6.6, 9.0), square(10.2, 10.2, 9.0))],
     f=[square(13.8, 13.8, 9.0)],
     apps="new",
     basis="GraffitiXR's multi-layer stencil registration for spray murals.",
     note="Several stencil cards, fanned to the one that is aligned.")

icon("edge-detect", "generative", ["canny", "outline extraction"],
     s=[smooth([(4.0, 15.0), (7.0, 6.0), (12.0, 5.0), (14.0, 11.0), (18.0, 8.0), (20.0, 14.0)])],
     f=[],
     apps="new",
     basis="Graffux's Canny edge-outline effect.",
     note="A field reduced to the single line that describes its edges.")

icon("subject-isolate", "generative", ["ml cutout", "background removal"],
     s=[dashed(2.8, 6.0, 21.2, 6.0, 4), circle(7.4, 10.0, 2.0)],
     f=[poly([(2.8, 20.4), (8.4, 13.4), (13.0, 17.8), (16.6, 14.2), (21.2, 20.4)], close=True)],
     apps="PS",
     basis="Photoshop's Select Subject and ML Kit-style isolation, used at capture time.",
     note="A picture with its subject already lifted from behind it.")

icon("registration-mark", "generative", ["alignment target", "print register"],
     s=[circle(12, 12, 6.4), line(12, 3.4, 12, 20.6), line(3.4, 12, 20.6, 12)],
     f=[],
     apps="new",
     note="The printer's own register mark, used here to align a stencil pass.")

icon("mural-scale", "generative", ["wall scale", "project size"],
     s=[poly([(3.4, 20.6), (3.4, 6.0), (12, 3.4), (20.6, 6.0), (20.6, 20.6)], close=True),
        line(3.4, 20.6, 20.6, 20.6)],
     f=[dot(12, 13.0, 1.4)],
     apps="new",
     basis="GraffitiXR's own AR wall-scale placement.",
     note="A wall in perspective with the point a mural will be set from.")
