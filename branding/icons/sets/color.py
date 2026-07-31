"""Colour & adjustment — tone, hue, and the instruments that measure them.

Photoshop's Adjustments panel is the source for most of this: the half-black disc, the S in
a box, the histogram under three sliders, the balance beam, the stepped ramp. A set of
tonal icons must never be described in colour, so the whole family says its piece in solid
against empty — which is what the corrections themselves do.
"""

from kit import (
    arc,
    circle,
    dot,
    ellipse,
    icon,
    line,
    pie,
    poly,
    quad,
    rays,
    rect,
    ring_rect,
    seq,
    smooth,
    square,
    tip,
)


def stair(x, y, w, h, steps=4):
    """A quantised ramp — the shape posterisation makes of a gradient."""
    pts, dx, dy = [(x, y + h)], w / steps, h / steps
    for i in range(steps):
        pts += [(x + dx * i, y + h - dy * i), (x + dx * (i + 1), y + h - dy * i)]
    pts.append((x + w, y))
    return poly(pts)


# ---------------------------------------------------------------------------------------
# Tone
# ---------------------------------------------------------------------------------------

icon("brightness", "color", ["light", "sun", "lighten"],
     s=[circle(12, 12, 5.0), rays(12, 12, 7.4, 9.9)],
     f=[],
     apps="PS/AI/PR",
     basis="Photoshop's Brightness — the sun, the oldest sign in the panel.",
     note="A disc with eight spokes clear of it.")

icon("contrast", "color", ["tonal range", "hard soft"],
     s=[circle(12, 12, 8.4)],
     f=[pie(12, 12, 8.4, 90, 270)],
     apps="PS/AI/PR",
     basis="Photoshop's Contrast — one disc holding both ends of the range at once.",
     note="Black meeting white on a straight edge.")

icon("levels", "color", ["histogram", "black point", "white point"],
     s=[seq(line(4.0, 15.4, 4.0, 12.0), line(7.2, 15.4, 7.2, 7.4), line(10.4, 15.4, 10.4, 4.2),
            line(13.6, 15.4, 13.6, 8.6), line(16.8, 15.4, 16.8, 11.0), line(20.0, 15.4, 20.0, 13.4)),
        line(3.0, 15.4, 21.0, 15.4)],
     f=[seq(tip(4.0, 17.2, 2.4, 270), tip(11.4, 17.2, 2.4, 270), tip(20.0, 17.2, 2.4, 270))],
     apps="PS",
     basis="Photoshop's Levels — a distribution with three sliders under it.",
     note="A histogram and the three points that reset its ends and middle.")

icon("curves", "color", ["tone curve", "s curve", "remap"],
     s=[rect(3.4, 3.4, 17.2, 17.2),
        smooth([(3.4, 20.6), (9.6, 18.8), (14.4, 5.2), (20.6, 3.4)])],
     f=[dot(14.4, 5.2, 1.5)],
     apps="PS/AI/PR",
     basis="Photoshop's Curves — the S in a box, with a point on it.",
     note="Input against output, bent, with one control point held.")

icon("exposure", "color", ["stops", "aperture", "iris"],
     s=[circle(12, 12, 8.4),
        poly([(16.4, 12), (14.2, 15.81), (9.8, 15.81), (7.6, 12), (9.8, 8.19), (14.2, 8.19)],
             close=True),
        seq(line(16.4, 12, 20.4, 12), line(9.8, 15.81, 7.8, 19.27), line(9.8, 8.19, 7.8, 4.73))],
     f=[],
     apps="PS",
     basis="Photoshop's Exposure — the camera iris the word comes from.",
     note="An aperture, three blades in.")

icon("shadows-highlights", "color", ["recover", "lift shadows", "pull highlights"],
     s=[rect(3.4, 3.4, 17.2, 17.2), line(20.6, 3.4, 3.4, 20.6)],
     f=[poly([(3.4, 20.6), (20.6, 3.4), (20.6, 20.6)], close=True)],
     apps="PS",
     basis="Photoshop's Shadows/Highlights — the two ends of the range, worked separately.",
     note="One frame split on the diagonal, each half addressed on its own.")

icon("posterize", "color", ["quantise", "steps", "bands"],
     s=[stair(3.6, 4.4, 16.8, 15.2, 4), line(3.6, 19.6, 20.4, 19.6)],
     f=[],
     apps="PS/PR",
     basis="Photoshop's Posterize — a ramp cut into a fixed number of steps.",
     note="A gradient reduced to a staircase.")

icon("threshold", "color", ["cutoff", "two tone", "clip"],
     s=[rect(3.4, 4.6, 17.2, 14.8), line(12, 4.6, 12, 19.4)],
     f=[seq(rect(3.4, 4.6, 8.6, 14.8), tip(12, 20.0, 2.0, 270))],
     apps="PS",
     basis="Photoshop's Threshold — everything falls to one side of a movable line.",
     note="A hard cut with the slider that sets it.")

icon("invert", "color", ["negative", "flip tone"],
     s=[rect(3.4, 4.6, 17.2, 14.8), circle(16.4, 12, 2.4)],
     f=[seq(rect(3.4, 4.6, 8.6, 14.8), circle(7.6, 12, 2.4))],
     apps="PS/AI/PR",
     basis="Photoshop's Invert — the negative, where the counter becomes the mark.",
     note="Two halves in opposite tone, each holding the other's disc.")

icon("auto-tone", "color", ["auto levels", "fix", "one tap"],
     s=[seq(line(4.4, 18.0, 4.4, 12.6), line(8.0, 18.0, 8.0, 7.4), line(11.6, 18.0, 11.6, 10.4)),
        line(3.0, 18.0, 14.0, 18.0),
        seq(line(18.6, 4.0, 18.6, 9.6), line(15.8, 6.8, 21.4, 6.8))],
     f=[dot(18.6, 13.6, 1.4)],
     apps="PS/PR",
     basis="Photoshop's Auto Tone — the histogram, corrected without being asked how.",
     note="A distribution and the one gesture that fixes it.")

# ---------------------------------------------------------------------------------------
# Hue
# ---------------------------------------------------------------------------------------

icon("hue", "color", ["colour angle", "shift hue", "wheel"],
     s=[circle(12, 12, 8.2), arc(12, 12, 4.6, 200, 340)],
     f=[seq(dot(12, 4.4, 1.8), tip(16.2, 14.4, 1.8, 35))],
     apps="PS/AI/PR",
     basis="Photoshop's Hue/Saturation — hue as a rotation about the wheel.",
     note="A marker carried round the wheel by the turn beneath it. Spokes drawn from the hub "
          "to the rim make the wheel a dharmachakra.")

icon("saturation", "color", ["intensity", "chroma", "vivid"],
     s=[circle(12, 12, 8.4)],
     f=[pie(12, 12, 8.4, 210, 330)],
     apps="PS/AI/PR",
     basis="Photoshop's Saturation — one wedge of the wheel taken to full.",
     note="A wheel with a single wedge at full strength.")

icon("vibrance", "color", ["smart saturation", "protect skin"],
     s=[poly([(3.6, 19.4), (20.4, 19.4), (20.4, 5.0)], close=True)],
     f=[poly([(15.0, 19.4), (20.4, 19.4), (20.4, 14.8)], close=True)],
     apps="PS",
     basis="Photoshop's Vibrance — a rising ramp that gives more where there is less.",
     note="A wedge, solid only where it is already strong.")

icon("desaturate", "color", ["greyscale", "remove colour"],
     s=[rect(3.0, 8.0, 18.0, 8.0), line(12.0, 8.0, 12.0, 16.0),
        seq(circle(7.4, 12, 2.4), circle(16.6, 12, 2.4))],
     f=[pie(7.4, 12, 2.4, 90, 270)],
     apps="PS/AI/PR",
     basis="Photoshop's Desaturate — the wheel with one half left with nothing in it.",
     note="A divided wheel: sectors on one side, nothing on the other.")

icon("color-balance", "color", ["cast", "cool warm", "scales"],
     s=[seq(line(3.4, 8.6, 20.6, 8.6), line(3.4, 15.4, 20.6, 15.4)), line(12, 4.6, 12, 19.4)],
     f=[seq(dot(7.4, 8.6, 2.0), dot(16.6, 15.4, 2.0))],
     apps="PS",
     basis="Photoshop's Color Balance — a beam balance between two casts.",
     note="Two pans on one beam.")

icon("black-white", "color", ["mono", "channel mix to grey"],
     s=[rect(3.4, 3.4, 17.2, 17.2), circle(12, 12, 5.2)],
     f=[pie(12, 12, 5.2, 90, 270)],
     apps="PS",
     basis="Photoshop's Black & White — colour resolved into two tones.",
     note="Two discs, one filled, one not.")

icon("photo-filter", "color", ["warming filter", "gel", "lens filter"],
     s=[rect(3.0, 3.4, 13.0, 13.0), circle(15.0, 15.4, 5.4)],
     f=[],
     apps="PS",
     basis="Photoshop's Photo Filter — the glass gel that screws onto a lens.",
     note="A filter ring with its tab.")

icon("channel-mixer", "color", ["per channel", "recombine"],
     s=[seq(line(6.4, 3.4, 6.4, 20.6), line(12.0, 3.4, 12.0, 20.6), line(17.6, 3.4, 17.6, 20.6)),
        circle(6.4, 8.0, 2.0), circle(17.6, 15.4, 2.0)],
     f=[dot(12.0, 11.4, 2.0)],
     apps="PS",
     basis="Photoshop's Channel Mixer — one rail per channel.",
     note="Three rails set independently.")

icon("color-lookup", "color", ["lut", "preset grade", "cube"],
     s=[rect(3.4, 5.4, 17.2, 13.2),
        seq(line(9.1, 5.4, 9.1, 18.6), line(14.9, 5.4, 14.9, 18.6)),
        seq(line(11.0, 8.0, 13.0, 8.0), line(11.0, 12.0, 13.0, 12.0),
            line(11.0, 16.0, 13.0, 16.0))],
     f=[rect(14.9, 5.4, 5.7, 13.2)],
     apps="PS",
     basis="Photoshop's Color Lookup — the LUT, which really is a cube of samples.",
     note="A colour cube with the identity ramp running corner to corner through it. Two cells "
          "filled off-axis instead read, at a squint, as a swastika crop.")

icon("gradient-map", "color", ["remap tone to colour", "duotone"],
     s=[poly([(3.4, 9.4), (10.2, 3.4), (17.0, 9.4)]), line(12.0, 11.6, 12.0, 14.6),
        rect(3.4, 17.4, 17.2, 3.6)],
     f=[seq(tip(12.0, 15.8, 2.2, 90), rect(3.4, 17.4, 6.0, 3.6))],
     apps="PS",
     basis="Photoshop's Gradient Map — a tonal range mapped onto a ramp.",
     note="A run of tone becoming a run of steps.")

icon("selective-color", "color", ["one hue only", "target colour"],
     s=[circle(12, 12, 8.4)],
     f=[pie(12, 12, 8.4, -90, -30)],
     apps="PS",
     basis="Photoshop's Selective Color — one colour singled out of the set.",
     note="Two fields; only one of them is being touched.")

icon("replace-color", "color", ["swap hue", "recolour"],
     s=[circle(6.6, 12, 4.0), circle(17.4, 12, 4.0), line(11.0, 12, 12.6, 12)],
     f=[tip(14.0, 12, 2.6, 0)],
     apps="PS",
     basis="Photoshop's Replace Color.",
     note="One field handed over to another.")

# ---------------------------------------------------------------------------------------
# Instruments
# ---------------------------------------------------------------------------------------

icon("color-disc", "color", ["picker", "wheel", "choose colour"],
     s=[circle(12, 12, 8.4), circle(12, 12, 4.2)],
     f=[dot(15.6, 8.4, 1.7)],
     apps="PR",
     basis="Procreate's colour disc — outer ring for hue, inner field for the rest.",
     note="A ring and a field, with the current pick on the ring.")

icon("color-swatch", "color", ["chip", "sample", "current colour"],
     s=[rect(3.4, 3.4, 17.2, 17.2)],
     f=[rect(3.4, 12, 17.2, 9.2)],
     apps="PS/AI/PR",
     basis="Photoshop's foreground/background chips.",
     note="A chip, half of it laid in.")

icon("color-swatches", "color", ["palette", "library", "saved colours"],
     s=[seq(rect(3.4, 3.4, 7.6, 7.6), rect(13.0, 3.4, 7.6, 7.6), rect(13.0, 13.0, 7.6, 7.6))],
     f=[rect(3.4, 13.0, 7.6, 7.6)],
     apps="PS/AI/PR",
     basis="Photoshop's Swatches panel and Procreate's palette grid.",
     note="A grid of chips with one of them chosen.")

icon("color-history", "color", ["recent", "used", "last colours"],
     s=[seq(rect(7.2, 8.0, 4.4, 7.4), rect(12.0, 8.0, 4.4, 7.4), rect(16.8, 8.0, 4.4, 7.4)),
        line(2.4, 18.8, 21.2, 18.8)],
     f=[rect(2.4, 8.0, 4.4, 7.4)],
     apps="PR",
     basis="Procreate's recent-colours row.",
     note="A row of what has been used, newest first.")

icon("color-harmony", "color", ["complementary", "triad", "scheme"],
     s=[circle(12, 12, 8.2),
        seq(circle(12, 3.8, 1.9), circle(19.1, 16.1, 1.9), circle(4.9, 16.1, 1.9))],
     f=[dot(12, 3.8, 0.9)],
     apps="PR",
     basis="Procreate's Harmony modes — related hues found by geometry on the wheel.",
     note="A triad inscribed in a closed wheel, with the lead hue solid. Left open at the tail, "
          "the arc and its one loaded head are a sperm.")

icon("gradient", "color", ["ramp", "blend", "fade"],
     s=[rect(3.4, 8.6, 17.2, 6.8),
        seq(circle(13.4, 12, 0.5), circle(15.8, 12, 0.5), circle(18.4, 12, 0.5))],
     f=[rect(3.4, 8.6, 8.0, 6.8)],
     apps="PS/AI/PR",
     basis="Photoshop's Gradient tool — solid giving way to nothing by measured steps.",
     note="A ramp read as a rhythm that opens out.")

icon("gradient-linear", "color", ["axis ramp", "straight"],
     s=[rect(3.4, 3.4, 17.2, 17.2),
        seq(circle(13.4, 12, 0.5), circle(15.8, 12, 0.5), circle(18.2, 12, 0.5))],
     f=[rect(3.4, 3.4, 8.0, 17.2)],
     apps="PS/AI",
     basis="Photoshop's Linear Gradient.",
     note="A ramp running one way across the frame.")

icon("gradient-radial", "color", ["from centre", "round ramp"],
     s=[rect(3.4, 3.4, 17.2, 17.2), circle(12, 12, 5.2), circle(12, 12, 7.8)],
     f=[dot(12, 12, 2.6)],
     apps="PS/AI",
     basis="Photoshop's Radial Gradient.",
     note="A ramp running out from one point.")

icon("gradient-angle", "color", ["sweep", "conical"],
     s=[rect(3.4, 3.4, 17.2, 17.2), line(12, 12, 12, 3.4), line(12, 12, 20.6, 12)],
     f=[pie(12, 12, 6.4, 270, 360)],
     apps="PS",
     basis="Photoshop's Angle Gradient — the ramp swept about a centre.",
     note="A ramp measured as a turn.")

icon("gradient-mesh", "color", ["mesh", "surface", "multi point"],
     s=[rect(3.4, 3.4, 17.2, 17.2),
        seq(quad((3.4, 9.6), (12, 13.4), (20.6, 9.6)), quad((3.4, 15.4), (12, 19.2), (20.6, 15.4)),
            quad((9.6, 3.4), (13.4, 12), (9.6, 20.6)), quad((15.4, 3.4), (19.2, 12), (15.4, 20.6)))],
     f=[],
     apps="AI",
     basis="Illustrator's Gradient Mesh — a grid that bends.",
     note="A surface with colour held at its intersections.")

icon("temperature", "color", ["warm cool", "kelvin", "white balance"],
     s=[seq(line(8.0, 14.6, 8.0, 5.4), arc(10.4, 5.4, 2.4, 180, 360), line(12.8, 5.4, 12.8, 14.6)),
        circle(10.4, 17.0, 4.2),
        seq(line(12.8, 7.6, 16.6, 7.6), line(12.8, 10.2, 15.4, 10.2),
            line(12.8, 12.8, 16.6, 12.8))],
     f=[dot(10.4, 17.0, 2.2)],
     apps="PS",
     basis="Camera Raw's Temperature slider — a thermometer against a scale.",
     note="A bulb and the graduations it is read against.")

icon("tint", "color", ["cast", "shift", "green magenta"],
     s=[seq(line(7.0, 3.0, 9.9, 6.9), arc(7.0, 9.0, 3.6, 313, 587), line(4.1, 6.9, 7.0, 3.0)),
        rect(3.0, 15.0, 18.0, 5.6)],
     f=[rect(12.0, 15.0, 9.0, 5.6)],
     apps="PS",
     basis="Camera Raw's Tint slider — a drop of cast let into the whole field.",
     note="One drop above a field that has taken half of it.")

icon("clarity", "color", ["local contrast", "punch", "dehaze"],
     s=[smooth([(3.4, 14.6), (7.4, 12.4), (11.0, 13.6)])],
     f=[poly([(11.0, 20.6), (13.6, 8.6), (16.2, 14.6), (18.6, 11.0), (20.6, 20.6)], close=True)],
     apps="PS",
     basis="Camera Raw's Clarity and Dehaze — detail recovered from under a flat sky.",
     note="A ridge coming clear of the haze above it.")

icon("vignette", "color", ["edge darkening", "corners"],
     s=[circle(12, 12, 7.6)],
     f=[ring_rect(2.8, 2.8, 18.4, 18.4, 2.4)],
     apps="PS",
     basis="Camera Raw's Post-Crop Vignetting — the frame closing in on the centre.",
     note="A dark border with an open centre.")

icon("grain", "color", ["noise", "film", "texture"],
     s=[rect(3.4, 3.4, 17.2, 17.2),
        seq(line(6.6, 7.4, 7.6, 7.4), line(12.6, 6.4, 13.6, 6.4), line(16.4, 10.4, 17.4, 10.4),
            line(7.4, 13.4, 8.4, 13.4), line(13.4, 15.4, 14.4, 15.4), line(9.4, 17.6, 10.4, 17.6))],
     f=[],
     apps="PS/PR",
     basis="Photoshop's Add Noise and Camera Raw's Grain.",
     note="A field with an uneven record laid across it.")

icon("histogram", "color", ["distribution", "readout", "levels display"],
     s=[line(3.0, 19.6, 21.0, 19.6)],
     f=[seq(rect(3.8, 15.2, 2.4, 4.4), rect(7.0, 9.8, 2.4, 9.8), rect(10.2, 4.6, 2.4, 15.0),
            rect(13.4, 8.2, 2.4, 11.4), rect(16.6, 14.0, 2.4, 5.6))],
     apps="PS",
     basis="Photoshop's Histogram panel.",
     note="What the image contains, counted.")

icon("color-profile", "color", ["gamut", "icc", "colour space"],
     s=[poly([(4.0, 21.0), (4.0, 3.0), (14.6, 3.0), (20.0, 8.4), (20.0, 21.0)], close=True),
        circle(12.0, 14.6, 3.8)],
     f=[pie(12.0, 14.6, 3.8, 90, 270)],
     apps="PS/AI",
     basis="The chromaticity horseshoe with a gamut triangle inside it.",
     note="What a device can reach, inside what the eye can.")

icon("color-rgb", "color", ["additive", "screen", "channels"],
     s=[circle(12, 8.4, 5.4), circle(8.4, 14.6, 5.4), circle(15.6, 14.6, 5.4)],
     f=[],
     apps="PS",
     basis="The additive primaries, drawn as three discs since Maxwell.",
     note="Three lights overlapping.")

icon("color-cmyk", "color", ["subtractive", "print", "process"],
     s=[circle(9.0, 9.0, 4.8), circle(15.0, 9.0, 4.8), circle(9.0, 15.0, 4.8)],
     f=[circle(15.0, 15.0, 4.8)],
     apps="PS/AI",
     basis="The four process inks, with the black one solid.",
     note="Four plates, one of them black.")

icon("color-alpha", "color", ["transparency", "opacity channel"],
     s=[rect(3.4, 3.4, 17.2, 17.2), line(12, 3.4, 12, 20.6), line(3.4, 12, 20.6, 12)],
     f=[seq(rect(3.4, 3.4, 8.6, 8.6), rect(12, 12, 8.6, 8.6))],
     apps="PS/PR",
     basis="The transparency chequer, wrapped onto a disc.",
     note="A footprint that is only half there.")

icon("color-hex", "color", ["code", "value", "exact"],
     s=[poly([(12, 3.0), (19.8, 7.5), (19.8, 16.5), (12, 21.0), (4.2, 16.5), (4.2, 7.5)],
             close=True)],
     f=[dot(12, 12, 2.6)],
     apps="PS/AI/PR",
     basis="The hexadecimal field every picker ends with.",
     note="Six sides for six digits.")
