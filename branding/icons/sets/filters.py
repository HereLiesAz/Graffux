"""Filters & effects — transformations of pixels already committed.

Photoshop's Filter Gallery icons are mostly literal: a lens for blur, jagged teeth for
noise, a bent grid for distortion, stacked frames for stylize. That literalism is kept here
— a filter is judged by its result, and the result is what these should show.
"""

from kit import (
    Path,
    arc,
    circle,
    dashed,
    dot,
    icon,
    line,
    pie,
    poly,
    quad,
    rays,
    rect,
    seq,
    smooth,
    square,
)


def broken_ring(cx, cy, r, n=8):
    """A ring with a gap at every spoke — falloff dissolving, not a crisp target."""
    step = 360.0 / n
    return seq(*[arc(cx, cy, r, i * step, i * step + step * 0.6) for i in range(n)])


icon("filter-blur-gaussian", "filters", ["soft focus", "smooth"],
     s=[poly([(20.6, 4.6), (3.4, 4.6), (3.4, 19.4), (20.6, 19.4)]),
        seq(line(20.6, 4.6, 20.6, 7.6), line(20.6, 10.0, 20.6, 14.0),
            line(20.6, 16.4, 20.6, 19.4))],
     f=[circle(10.4, 12, 3.6)],
     apps="PS",
     basis="Photoshop's Gaussian Blur — falloff read as concentric rings.",
     note="Rings closing on a single point of focus.")

icon("filter-blur-motion", "filters", ["speed", "streak", "movement"],
     s=[seq(line(3.4, 8.6, 15.4, 8.6), line(3.4, 15.4, 12.6, 15.4))],
     f=[dot(19.0, 8.6, 1.6)],
     apps="PS/PR",
     basis="Photoshop's Motion Blur — a point smeared into a trail.",
     note="A mark trailing behind where it used to be.")

icon("filter-blur-radial", "filters", ["zoom blur", "spin blur"],
     s=[seq(line(16.4, 7.6, 20.6, 3.4), line(7.6, 7.6, 3.4, 3.4),
            line(16.4, 16.4, 20.6, 20.6), line(7.6, 16.4, 3.4, 20.6))],
     f=[square(12, 12, 9.0)],
     apps="PS",
     basis="Photoshop's Radial Blur — the subject held still while everything streaks past it.",
     note="A subject with the streaks flying off its four corners. Drawn the obvious way — a "
          "solid centre inside a full ring of evenly spaced spokes — it is an arsehole, and no "
          "amount of intent survives that. Four streaks, and only from the corners.")

icon("filter-blur-lens", "filters", ["depth of field", "bokeh"],
     s=[circle(12, 12, 8.4)],
     f=[seq(circle(6.6, 15.0, 1.6), circle(17.0, 8.6, 1.9), circle(15.4, 16.6, 1.2))],
     apps="PS",
     basis="Photoshop's Lens Blur — the iris, with bokeh discs floating out of focus.",
     note="An aperture and the soft discs behind it.")

icon("filter-sharpen-unsharp", "filters", ["edge contrast", "unsharp mask"],
     s=[smooth([(3.4, 15.0), (8.0, 13.4), (12.0, 6.0), (16.0, 10.6), (20.6, 9.0)])],
     f=[dot(12.0, 6.0, 1.4)],
     apps="PS",
     basis="Photoshop's Unsharp Mask — a soft curve pulled to a hard peak.",
     note="A profile with one edge steepened.")

icon("filter-noise-add", "filters", ["grain", "static", "speckle"],
     s=[rect(3.4, 3.4, 17.2, 17.2),
        seq(line(6.6, 7.4, 7.6, 7.4), line(12.6, 6.4, 13.6, 6.4), line(16.4, 10.4, 17.4, 10.4),
            line(7.4, 13.4, 8.4, 13.4), line(13.4, 15.4, 14.4, 15.4), line(9.4, 17.6, 10.4, 17.6))],
     f=[],
     apps="PS",
     basis="Photoshop's Add Noise.",
     note="A field with an uneven record laid across it.")

icon("filter-noise-reduce", "filters", ["denoise", "clean up", "smooth grain"],
     s=[rect(3.4, 3.4, 17.2, 17.2, 6.0),
        seq(line(6.6, 7.4, 7.6, 7.4), line(16.4, 10.4, 17.4, 10.4), line(9.4, 17.6, 10.4, 17.6))],
     f=[],
     apps="PS",
     basis="Photoshop's Reduce Noise — the same field, mostly settled, corners softened.",
     note="The noise field with most of the speckle gone.")

icon("filter-distort-pinch", "filters", ["squeeze", "implode"],
     s=[seq(quad((3.4, 6.0), (12, 10.4), (20.6, 6.0)), quad((3.4, 18.0), (12, 13.6), (20.6, 18.0)),
            quad((6.0, 3.4), (10.4, 12), (6.0, 20.6)), quad((18.0, 3.4), (13.6, 12), (18.0, 20.6)))],
     f=[dot(12, 12, 1.4)],
     apps="PS",
     basis="Photoshop's Pinch — a grid drawn toward a single point.",
     note="A grid bent inward on itself.")

icon("filter-distort-spherize", "filters", ["bulge", "inflate", "fisheye"],
     s=[rect(3.4, 3.4, 17.2, 17.2), arc(9.6, 9.6, 2.4, 200, 340)],
     f=[circle(12, 12, 6.4)],
     apps="PS/AI",
     basis="Photoshop's Spherize — a plane with a sphere pushing through it.",
     note="A disc swelling inside its frame.")

icon("filter-distort-wave", "filters", ["ripple", "undulate"],
     s=[rect(3.4, 5.0, 17.2, 14.0),
        smooth([(3.4, 12), (7.4, 8.6), (11.4, 15.4), (15.4, 8.6), (20.6, 12)])],
     f=[],
     apps="PS",
     basis="Photoshop's Wave and Ripple filters.",
     note="A frame with a sine passed through it.")

icon("filter-distort-twirl", "filters", ["swirl", "vortex"],
     s=[smooth([(18.8, 12), (17.6, 7.6), (12.8, 5.2), (7.8, 7.0), (6.4, 12.2), (9.6, 16.2),
                (14.2, 15.2), (15.2, 11.4), (12.4, 9.6)])],
     f=[dot(12.4, 9.6, 1.2)],
     apps="PS/PR",
     basis="Photoshop's Twirl — the same spiral as Liquify, applied whole.",
     note="A frame's contents wound into a spiral.")

icon("filter-liquify", "filters", ["push", "warp", "reshape"],
     s=[rect(3.4, 3.4, 17.2, 17.2), smooth([(6.4, 16.0), (10.0, 9.0), (17.6, 8.0)])],
     f=[dot(17.6, 8.0, 1.4)],
     apps="PS/PR",
     basis="Photoshop's Liquify filter and Procreate's Liquify — a frame with one push in it.",
     note="A frame with a single deliberate push in it.")

icon("filter-emboss", "filters", ["relief", "3d edge"],
     s=[poly([(4.0, 20.0), (4.0, 8.0), (16.0, 8.0), (16.0, 20.0)], close=True)],
     f=[poly([(4.0, 8.0), (16.0, 8.0), (20.0, 4.0), (8.0, 4.0)], close=True)],
     apps="PS/AI",
     basis="Photoshop's Emboss — flat colour giving way to a lit relief edge.",
     note="A flat plate with one raised bevel.")

icon("filter-mosaic", "filters", ["pixelate", "blocks"],
     s=[seq(line(9.13, 3.4, 9.13, 20.6), line(14.87, 3.4, 14.87, 20.6),
            line(3.4, 9.13, 20.6, 9.13), line(3.4, 14.87, 20.6, 14.87))],
     f=[seq(rect(9.13, 3.4, 5.74, 5.73), rect(14.87, 14.87, 5.73, 5.73))],
     apps="PS",
     basis="Photoshop's Mosaic / Pixelate — detail coarsened into a fixed grid.",
     note="A field reduced to a grid of flat tiles.")

icon("filter-crystallize", "filters", ["facets", "voronoi"],
     s=[seq(poly([(3.4, 9.2), (9.6, 3.4), (15.2, 6.8), (12.4, 12.6), (5.0, 13.4)], close=True),
            poly([(12.4, 12.6), (15.2, 6.8), (20.6, 10.2), (18.0, 17.0)], close=True),
            poly([(5.0, 13.4), (12.4, 12.6), (18.0, 17.0), (11.8, 20.6), (6.0, 19.0)],
                 close=True))],
     f=[],
     apps="PS/AI",
     basis="Photoshop's Crystallize — a field broken into irregular facets.",
     note="A field split into cells around scattered centres.")

icon("filter-halftone", "filters", ["dots", "print screen", "ben-day"],
     s=[rect(3.4, 3.4, 17.2, 17.2)],
     f=[seq(circle(8.0, 8.0, 1.1), circle(16.0, 8.0, 1.8),
            circle(8.0, 16.0, 1.8), circle(16.0, 16.0, 2.6))],
     apps="PS/PR",
     basis="Photoshop's Color Halftone — a tonal ramp reduced to dot size.",
     note="A field of dots that grow toward one corner.")

icon("filter-glow", "filters", ["outer glow", "bloom"],
     s=[seq(arc(12, 12, 8.2, 12, 78), arc(12, 12, 8.2, 102, 168),
            arc(12, 12, 8.2, 192, 258), arc(12, 12, 8.2, 282, 348))],
     f=[dot(12, 12, 3.4)],
     apps="PS/AI/PR",
     basis="Photoshop's Outer Glow effect.",
     note="A core with its light spreading past its own edge, the halo broken into four arcs so "
          "it reads as light rather than as an edge. An unbroken ring around a solid centre is "
          "a nipple, and it is also every target in the set.")

icon("filter-shadow-drop", "filters", ["cast shadow", "depth"],
     s=[square(9.0, 9.0, 11.2)],
     f=[square(15.2, 15.2, 9.6, r=3.4)],
     apps="PS/AI/PR",
     basis="Photoshop's Drop Shadow effect.",
     note="A crisp plate over a softer, rounded shadow offset behind it.")

icon("filter-shadow-inner", "filters", ["inset shadow", "recessed"],
     s=[rect(3.4, 3.4, 17.2, 17.2)],
     f=[poly([(3.4, 3.4), (20.6, 3.4), (16.0, 8.0), (8.0, 8.0), (8.0, 16.0), (3.4, 20.6)],
             close=True)],
     apps="PS",
     basis="Photoshop's Inner Shadow — the frame's own edge cast onto itself.",
     note="A frame with shade gathered inside its own edge.")

icon("filter-bevel", "filters", ["ridge", "3d edge", "chamfer"],
     s=[rect(4.0, 4.0, 16.0, 16.0)],
     f=[poly([(4.0, 4.0), (20.0, 4.0), (14.0, 10.0), (10.0, 10.0)], close=True)],
     apps="PS",
     basis="Photoshop's Bevel and Emboss — one lit chamfer standing for the whole ridge.",
     note="A frame with one bevelled, lit edge.")

icon("filter-stroke-outline", "filters", ["outline effect", "border"],
     s=[circle(12, 12, 5.4), circle(12, 12, 8.2)],
     f=[],
     apps="PS/AI",
     basis="Photoshop's Stroke effect — a second outline drawn around the first.",
     note="A shape traced by a second, larger edge.")

icon("filter-vanishing-point", "filters", ["perspective clone", "3d plane"],
     s=[line(2.6, 20.6, 21.4, 20.6),
        seq(line(2.6, 20.6, 17.0, 5.0), line(9.4, 20.6, 17.0, 5.0), line(16.0, 20.6, 17.0, 5.0))],
     f=[dot(17.6, 4.2, 1.5)],
     apps="PS",
     basis="Photoshop's Vanishing Point — a plane defined in perspective.",
     note="A plane tipped back to a vanishing edge.")

icon("filter-lens-flare", "filters", ["light leak", "sun flare"],
     s=[rays(7.4, 16.6, 3.2, 5.0, 8),
        seq(circle(11.8, 12.2, 1.7), circle(15.2, 8.8, 2.5), circle(18.6, 5.4, 1.2))],
     f=[dot(6.6, 17.4, 2.2)],
     apps="PS",
     basis="Photoshop's Lens Flare.",
     note="A chain of ghosts running toward one bright source.")

icon("filter-render-clouds", "filters", ["procedural sky", "fractal"],
     s=[Path("M3.4,16.2A3.4,3.4 0 0,1 8.4,11.8A4.2,4.2 0 0,1 16.4,11.4"
             "A3.4,3.4 0 0,1 20.6,16.2Z", [(3.4, 8.6), (20.6, 16.2)])],
     f=[],
     apps="PS",
     basis="Photoshop's Clouds render filter.",
     note="A procedural horizon with nothing behind it yet.")

icon("filter-vignette-post", "filters", ["darken corners", "frame edge"],
     s=[circle(12, 12, 6.4)],
     f=[seq(pie(5.4, 5.4, 4.0, 90, 180), pie(18.6, 5.4, 4.0, 0, 90),
            pie(18.6, 18.6, 4.0, 270, 360), pie(5.4, 18.6, 4.0, 180, 270))],
     apps="PS",
     basis="Camera Raw's Post-Crop Vignetting, drawn as darkened corners rather than a ring.",
     note="Four corners closing in on an untouched centre.")

icon("filter-clone-source", "filters", ["sample point", "reference"],
     s=[circle(7.4, 7.4, 3.8), rect(11.6, 11.6, 9.0, 9.0), dashed(9.8, 9.8, 12.4, 12.4, 2)],
     f=[dot(7.4, 7.4, 1.3)],
     apps="PS",
     basis="Photoshop's Clone Source panel — the sample and the frame it will be copied into.",
     note="A sampled point and the frame waiting to receive it.")

icon("filter-gallery", "filters", ["stack of filters", "fx library"],
     s=[seq(square(6.6, 6.6, 9.0), square(10.2, 10.2, 9.0))],
     f=[square(13.8, 13.8, 9.0)],
     apps="PS",
     basis="Photoshop's Filter Gallery — stacked, re-orderable effects.",
     note="Frames fanned like cards, the top one active.")

icon("filter-smart-radius", "filters", ["adaptive edge", "AI mask edge"],
     s=[smooth([(4.6, 12), (7.4, 7.0), (13.0, 6.0), (17.4, 9.6), (18.0, 15.6), (12.6, 18.8),
                (7.0, 16.6), (4.6, 12)], close=True)],
     f=[dot(13.0, 5.4, 1.3)],
     apps="PS",
     basis="Photoshop's Smart Radius — an edge that thickens and thins with the detail.",
     note="An outline that widens where the edge is uncertain.")

icon("filter-content-aware-fill", "filters", ["ai fill", "remove and fill"],
     s=[rect(3.4, 3.4, 17.2, 17.2),
        seq(line(9.13, 3.4, 9.13, 20.6), line(14.87, 3.4, 14.87, 20.6),
            line(3.4, 9.13, 20.6, 9.13), line(3.4, 14.87, 20.6, 14.87))],
     f=[rect(9.13, 9.13, 5.74, 5.73)],
     apps="PS",
     basis="Photoshop's Content-Aware Fill — a gap the tiles around it are used to fill.",
     note="A grid with one tile regrown from its neighbours.")

icon("filter-sky-replace", "filters", ["ai sky", "replace background"],
     s=[dashed(2.8, 2.8, 2.8, 13.4, 3), dashed(2.8, 2.8, 21.2, 2.8, 5), dashed(21.2, 2.8, 21.2, 13.4, 3),
        poly([(2.8, 20.6), (7.4, 15.4), (11.4, 18.2), (15.4, 14.4), (21.2, 20.6)])],
     f=[dot(16.6, 7.4, 2.2)],
     apps="PS",
     basis="Photoshop's Sky Replacement.",
     note="A new horizon dropped in above the ants.")

icon("filter-generative-fill", "filters", ["ai generate", "text to image fill"],
     s=[rect(3.4, 3.4, 17.2, 17.2)],
     f=[poly([(12, 7.4), (13.4, 10.6), (16.6, 12), (13.4, 13.4), (12, 16.6), (10.6, 13.4),
              (7.4, 12), (10.6, 10.6)], close=True)],
     apps="new",
     note="A gap in the frame with a generated form taking its place.")
