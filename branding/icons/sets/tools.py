"""Tools & brushes — what touches the surface.

Metaphors are inherited from Photoshop, Illustrator and Procreate: the brush is slanted, the
blur is a water drop, the sharpen is a cone, the dodge is a darkroom paddle, the burn is a
cupped hand, the heal is a sticking plaster, the bucket is tipped. None of the geometry is
theirs — every glyph is redrawn on the Graffux grid — but the association a user already
carries is not thrown away to prove a point.

Every hand-held implement shares one diagonal axis so the family reads as a family: local
+x runs toward the point of contact, which lands in the lower-left of the grid, nine units
from centre. The solid keystone is always the working end — the part that meets the pixel.
"""

from kit import (
    Path,
    arc,
    quad,
    circle,
    dashed,
    dashed_rect,
    dot,
    ellipse,
    icon,
    line,
    pie,
    poly,
    rect,
    seq,
    smooth,
    taper,
    tip,
    xf,
)

T = xf(12, 12, 135)

# ---------------------------------------------------------------------------------------
# Mark-making media
# ---------------------------------------------------------------------------------------

icon("brush", "tools", ["paint", "bristle", "draw", "round brush"],
     s=[line(-9, 0, -2.2, 0, t=T), line(-2.2, -2.3, -2.2, 2.3, t=T)],
     f=[poly([(-2.2, -2.3), (3.0, -1.9), (8.8, -1.1), (6.2, -0.3), (9.0, 0.4), (5.8, 1.0),
              (7.8, 1.7), (3.0, 1.9), (-2.2, 2.3)], close=True, t=T)],
     apps="PS/AI/PR",
     basis="Photoshop's slanted brush and Procreate's brush tip — handle up-right, tip down-left.",
     note="Handle, ferrule, solid tapered head.")

icon("pencil", "tools", ["draw", "sketch", "graphite", "edit"],
     s=[poly([(-9, -1.7), (2.4, -1.7), (9, 0), (2.4, 1.7), (-9, 1.7)], close=True, t=T),
        line(2.4, -1.7, 2.4, 1.7, t=T)],
     f=[poly([(6.0, -0.78), (9, 0), (6.0, 0.78)], close=True, t=T)],
     apps="PS/AI/PR",
     basis="The Photoshop and Illustrator pencil: a sharpened shaft on the same diagonal.",
     note="Shaft, collar, sharpened cone with a solid graphite point.")

icon("airbrush", "tools", ["spray", "atomiser", "soft", "mist"],
     s=[rect(-9, -2.4, 7.0, 4.8, t=T),
        line(1.4, -1.6, 6.6, -3.6, t=T), line(1.4, 0, 7.4, 0, t=T), line(1.4, 1.6, 6.6, 3.6, t=T)],
     f=[dot(9.6, 0, 1.1, t=T)],
     apps="PS/PR",
     basis="Photoshop's airbrush option — a nozzle throwing a widening cone.",
     note="Canister, cone of air, one landed particle.")

icon("marker", "tools", ["felt tip", "chisel", "highlighter"],
     s=[rect(-9, -2.7, 9.4, 5.4, 1.1, t=T), line(-4.6, -2.7, -4.6, 2.7, t=T)],
     f=[poly([(0.4, -2.7), (9, -2.7), (9, 0.4), (0.4, 2.7)], close=True, t=T)],
     apps="PS/PR",
     basis="Procreate's inking and marker brush families — a fat barrel cut to a chisel.",
     note="Fat banded barrel, cut off at a solid chisel wedge.")

icon("pen-ink", "tools", ["nib", "dip pen", "ink", "line art"],
     s=[line(-9, 0, -2.6, 0, t=T),
        poly([(-2.6, -2.4), (9, 0), (-2.6, 2.4)], close=True, t=T),
        line(-0.6, 0, 6.2, 0, t=T), circle(-1.8, 0, 1.0, t=T)],
     f=[poly([(6.4, -0.62), (9, 0), (6.4, 0.62)], close=True, t=T)],
     apps="PS/AI/PR",
     basis="Procreate's technical pen and Photoshop's ink brushes — a split dip-pen nib.",
     note="Pointed nib, split down the middle, solid at the point.")

icon("calligraphy", "tools", ["broad edge", "lettering", "italic nib"],
     s=[line(-9, 0, -2.0, 0, t=T), line(-2.0, -1.6, -2.0, 1.6, t=T)],
     f=[poly([(-2.0, -1.6), (4.2, -4.6), (9.0, -3.0), (2.8, 0), (9.0, 3.0), (4.2, 4.6),
              (-2.0, 1.6)], close=True, t=T)],
     apps="PS/AI/PR",
     basis="Illustrator's calligraphic brush — a flat edge held at a fixed angle to the stroke.",
     note="Slim holder and a solid broad edge canted off the axis.")

icon("crayon", "tools", ["wax", "kids", "stub"],
     s=[poly([(-6.6, -2.5), (3.2, -2.5), (3.2, 2.5), (-6.6, 2.5)], close=True, t=T),
        line(-4.8, -2.5, -2.4, 2.5, t=T), line(-1.6, -2.5, 0.8, 2.5, t=T)],
     f=[poly([(3.2, -2.5), (7.6, -1.1), (7.6, 1.1), (3.2, 2.5)], close=True, t=T)],
     apps="PR",
     basis="Procreate's Artistic set — the wrapped wax stub.",
     note="Short barrel with wrapper bands, worn to a blunt solid nose.")

icon("chalk", "tools", ["pastel", "dry media", "stick"],
     s=[poly([(-7.6, -2.6), (2.6, -2.6), (2.6, 2.6), (-7.6, 2.6)], close=True, t=T),
        seq(line(9.2, -2.4, 10.0, -3.0, t=T), line(9.8, 1.2, 10.8, 1.8, t=T))],
     f=[poly([(2.6, -2.6), (6.8, -2.6), (6.8, 2.6), (2.6, 2.6)], close=True, t=T)],
     apps="PR",
     basis="Procreate's chalk and soft-pastel brushes — a square stick that sheds.",
     note="Square stick, blunt at the solid end, shedding dust.")

icon("charcoal", "tools", ["carbon", "smudgy", "dry media"],
     s=[poly([(-8.2, -1.4), (-5.4, -2.8), (-1.0, -2.2), (2.2, -3.0), (2.6, 2.9),
              (-1.6, 2.4), (-5.0, 3.0), (-8.0, 1.6)], close=True, t=T)],
     f=[poly([(2.2, -3.0), (7.4, -1.8), (7.0, 1.9), (2.6, 2.9)], close=True, t=T)],
     apps="PR",
     basis="Procreate's charcoal brushes — a broken stick, never a manufactured one.",
     note="Irregular chunk, solid where it has been worn flat.")

icon("watercolor", "tools", ["wet", "wash", "bleed"],
     s=[line(-9, 0, -3.0, 0, t=T),
        poly([(-3.0, -2.0), (1.6, -1.5), (4.4, 0), (1.6, 1.5), (-3.0, 2.0)], close=True, t=T),
        seq(quad((2.6, 17.4), (6.4, 14.6), (10.2, 17.4)),
            quad((2.6, 20.8), (6.4, 18.0), (10.2, 20.8)))],
     f=[],
     apps="PS/PR",
     basis="Procreate's Wet Media — a soft round loaded over a spreading pool.",
     note="Round brush above the water it has already put down.")

icon("oil-brush", "tools", ["flat brush", "bristle", "impasto"],
     s=[line(-9, 0, -2.6, 0, t=T),
        poly([(0.4, -3.0), (8.6, -3.0), (7.2, -1.2), (8.8, 0.3), (7.0, 1.8), (8.4, 3.0), (0.4, 3.0)],
             t=T)],
     f=[poly([(-2.6, -3.0), (0.4, -3.0), (0.4, 3.0), (-2.6, 3.0)], close=True, t=T)],
     apps="PS/PR",
     basis="Photoshop's bristle-tip brushes — the only tool that shows its hairs.",
     note="Flat ferrule with splayed bristles.")

icon("blob-brush", "tools", ["merge shapes", "vector paint", "blob"],
     s=[line(-9, 0, -2.6, 0, t=T),
        poly([(-2.6, -2.2), (2.4, -1.2), (2.4, 1.2), (-2.6, 2.2)], close=True, t=T)],
     f=[smooth([(4.2, 14.8), (6.6, 10.4), (11.8, 11.2), (12.6, 16.0), (8.4, 18.6)], close=True)],
     apps="AI",
     basis="Illustrator's Blob Brush — a brush whose strokes fuse into one filled shape.",
     note="A brush laying down a closed form rather than a line.")

icon("spray-can", "tools", ["aerosol", "graffiti", "rattle can"],
     s=[poly([(7.0, 20.6), (7.0, 9.6), (8.8, 7.6), (12.6, 7.6), (14.4, 9.6), (14.4, 20.6)],
              close=True),
        rect(9.2, 3.6, 3.0, 4.0), line(12.2, 5.0, 14.6, 5.0),
        seq(line(16.6, 6.4, 18.8, 5.4), line(16.8, 9.8, 19.2, 10.6))],
     f=[dot(18.4, 8.0, 1.2)],
     apps="PR",
     basis="Procreate's Spraypaints set, and the app's own mural heritage.",
     note="Rattle can, nozzle out the side, one landed burst.")

icon("roller", "tools", ["paint roller", "flat fill", "coat"],
     s=[rect(4.4, 3.4, 15.2, 5.4),
        poly([(8.0, 8.8), (8.0, 11.6), (12.4, 11.6), (12.4, 20.6)]),
        rect(10.6, 15.4, 3.6, 5.4)],
     f=[dot(19.4, 11.4, 1.2)],
     apps="PR",
     basis="Procreate's roller brushes — sleeve, cranked stem, grip.",
     note="Roller and handle, with one drip already laid down.")

icon("stamp", "tools", ["clone", "rubber stamp", "duplicate source"],
     s=[rect(8.4, 3.4, 7.2, 4.0), rect(10.4, 7.4, 3.2, 6.0), rect(5.6, 13.4, 12.8, 3.0)],
     f=[rect(3.4, 17.6, 17.2, 3.0)],
     apps="PS",
     basis="Photoshop's Clone Stamp — the rubber stamp, unchanged since 1990.",
     note="Knob, plate, and the solid face that transfers the sample.")

icon("stamp-pattern", "tools", ["pattern stamp", "tile", "repeat"],
     s=[rect(8.4, 3.0, 7.2, 3.6), rect(10.4, 6.6, 3.2, 5.2), rect(5.6, 11.8, 12.8, 2.8),
        rect(3.4, 15.4, 17.2, 5.2)],
     f=[seq(rect(3.4, 15.4, 8.6, 2.6), rect(12.0, 18.0, 8.6, 2.6))],
     apps="PS",
     basis="Photoshop's Pattern Stamp — the same stamp, printing a tile instead of a sample.",
     note="Stamp over a chequer.")

# ---------------------------------------------------------------------------------------
# Retouch
# ---------------------------------------------------------------------------------------

icon("eraser", "tools", ["rub out", "delete", "undo mark"],
     s=[poly([(5.4, 7.4), (15.4, 7.4), (18.6, 10.6), (18.6, 16.6), (5.4, 16.6)], close=True),
        line(5.4, 11.6, 18.6, 11.6)],
     f=[poly([(5.4, 11.6), (18.6, 11.6), (18.6, 16.6), (5.4, 16.6)], close=True)],
     apps="PS/AI/PR",
     basis="The Photoshop and Procreate block eraser — held flat, not angled like a brush.",
     note="A rubber block, banded, solid at the face doing the wiping.")

icon("eraser-hard", "tools", ["hard edge", "crisp", "block"],
     s=[poly([(5.4, 7.4), (15.4, 7.4), (18.6, 10.6), (18.6, 16.6), (5.4, 16.6)], close=True),
        line(3.6, 19.4, 3.6, 4.6)],
     f=[poly([(5.4, 11.6), (18.6, 11.6), (18.6, 16.6), (5.4, 16.6)], close=True)],
     apps="PS/PR",
     basis="Photoshop's hard round eraser preset.",
     note="The block against an unbroken rule — the edge stays where it is put.")

icon("eraser-soft", "tools", ["feather", "soft edge", "fade"],
     s=[poly([(5.4, 7.4), (15.4, 7.4), (18.6, 10.6), (18.6, 16.6), (5.4, 16.6)], close=True),
        seq(line(3.6, 4.6, 3.6, 7.4), line(3.6, 8.8, 3.6, 11.0), line(3.6, 12.4, 3.6, 15.2),
            line(3.6, 16.6, 3.6, 19.4))],
     f=[poly([(5.4, 11.6), (18.6, 11.6), (18.6, 16.6), (5.4, 16.6)], close=True)],
     apps="PS/PR",
     basis="Photoshop's soft round eraser preset.",
     note="The same block against a broken rule — the edge dissolves.")

icon("smudge", "tools", ["finger", "drag", "blend"],
     s=[seq(line(-8.8, 0, -6.0, 0, t=T), line(-4.2, 0, -2.2, 0, t=T)),
        seq(arc(6.6, 15.0, 2.0, 30, 220), arc(6.6, 15.0, 3.6, 40, 210))],
     f=[circle(6.6, 15.0, 0.9)],
     apps="PS/PR",
     basis="Photoshop's Smudge and Procreate's Smudge — a fingertip dragging wet pigment.",
     note="A solid pad pulled out into three trailing lines.")

icon("blur", "tools", ["soften", "out of focus", "droplet"],
     s=[seq(line(12, 3.2, 16.99, 9.58), arc(12, 13.6, 6.4, 321.2, 578.8), line(7.01, 9.58, 12, 3.2)),
        seq(line(8.9, 12.6, 15.1, 12.6), line(9.6, 15.8, 14.4, 15.8))],
     f=[],
     apps="PS/AI/PR",
     basis="Photoshop's Blur tool — the water drop, unchanged.",
     note="The drop, with the detail under it collapsing into bands.")

icon("sharpen", "tools", ["crisp", "detail", "cone"],
     s=[smooth([(2.8, 17.0), (7.0, 15.6), (10.0, 16.4)]),
        smooth([(14.0, 16.4), (17.0, 15.6), (21.2, 17.0)]), line(2.8, 20.4, 21.2, 20.4)],
     f=[poly([(12, 3.4), (14.6, 16.8), (9.4, 16.8)], close=True)],
     apps="PS/AI/PR",
     basis="Photoshop's Sharpen tool — the sharpening cone next to the blur drop.",
     note="The cone, solid at the tip that does the work.")

icon("dodge", "tools", ["lighten", "hold back", "darkroom"],
     s=[circle(10.0, 8.8, 5.4), line(13.4, 12.6, 18.6, 19.4),
        seq(circle(8.2, 7.0, 1.0), circle(11.8, 7.0, 1.0), circle(10.0, 10.4, 1.0))],
     f=[],
     apps="PS",
     basis="Photoshop's Dodge tool — the darkroom paddle on its wire, throwing light back.",
     note="Paddle, rod, and the light it holds off the paper.")

icon("burn", "tools", ["darken", "shade", "cupped hand", "aperture"],
     s=[arc(12, 12, 8.4, 35, 325), arc(12, 12, 4.4, 325, 395)],
     f=[dot(12, 12, 2.0)],
     apps="PS",
     basis="Photoshop's Burn tool — the hand cupped into an aperture to concentrate exposure.",
     note="A cupped C with the light it funnels burning solid at the centre.")

icon("sponge", "tools", ["saturation", "soak", "porous"],
     s=[poly([(3.6, 8.6), (7.0, 6.0), (12.0, 7.2), (16.6, 5.6), (20.4, 8.8), (19.2, 14.2),
              (20.0, 17.6), (15.0, 18.8), (10.0, 17.8), (4.6, 18.4), (4.0, 13.4)], close=True),
        seq(circle(8.4, 11.4, 1.5), circle(15.2, 13.6, 1.7), circle(11.8, 15.6, 1.2))],
     f=[],
     apps="PS",
     basis="Photoshop's Sponge tool — a porous block that pulls colour out or drives it in.",
     note="Pores, one of them filled where the colour is being taken.")

icon("heal", "tools", ["healing brush", "plaster", "repair"],
     s=[rect(-8.4, -3.4, 16.8, 6.8, 3.4, t=T),
        line(-2.6, -3.4, -2.6, 3.4, t=T), line(2.6, -3.4, 2.6, 3.4, t=T)],
     f=[dot(0, 0, 1.3, t=T)],
     apps="PS",
     basis="Photoshop's Healing Brush — the sticking plaster, on the tool diagonal.",
     note="A plaster with the sampled centre solid.")

icon("heal-spot", "tools", ["blemish", "spot removal", "target"],
     s=[rect(-7.0, -3.0, 14.0, 6.0, 3.0, t=T), line(-2.2, -3.0, -2.2, 3.0, t=T),
        line(2.2, -3.0, 2.2, 3.0, t=T)],
     f=[dot(17.4, 6.6, 1.6)],
     apps="PS",
     basis="Photoshop's Spot Healing Brush — the plaster reduced to the spot it targets.",
     note="A ring sighted on a single blemish.")

icon("patch", "tools", ["region repair", "graft", "swap"],
     s=[dashed_rect(2.6, 2.6, 8.4, 8.4, 2), line(9.4, 9.4, 12.6, 12.6)],
     f=[seq(tip(13.6, 13.6, 2.4, 45), rect(13.0, 13.0, 8.4, 8.4))],
     apps="PS",
     basis="Photoshop's Patch tool — a drawn region dragged onto clean ground.",
     note="A hand-drawn area moved to a rectangle of good surface.")

icon("red-eye", "tools", ["pupil", "flash", "portrait fix"],
     s=[smooth([(2.8, 12), (12, 5.6), (21.2, 12), (12, 18.4), (2.8, 12)], close=True),
        circle(12, 12, 3.4)],
     f=[dot(12, 12, 1.4)],
     apps="PS",
     basis="Photoshop's Red Eye tool — an eye reduced to lid, iris and pupil.",
     note="The one solid pupil being corrected.")

icon("liquify", "tools", ["warp", "push pixels", "swirl"],
     s=[seq(quad((3.4, 5.0), (12, 10.6), (20.6, 5.0)), quad((3.4, 11.0), (12, 16.6), (20.6, 11.0)),
            quad((3.4, 17.0), (12, 22.6), (20.6, 17.0)))],
     f=[],
     apps="PS/PR",
     basis="Procreate's Liquify and Photoshop's Twirl — the surface pulled into a spiral.",
     note="Three turns tightening on the point being pushed.")

icon("fill", "tools", ["paint bucket", "flood", "pour"],
     s=[poly([(3.6, 11.4), (11.4, 3.6), (18.6, 10.8), (12.4, 17.0), (7.4, 17.0)], close=True),
        line(8.4, 6.6, 6.0, 4.2),
        poly([(19.6, 13.2), (21.4, 16.6), (19.6, 19.0), (17.8, 16.6)], close=True)],
     f=[dot(20.0, 20.6, 1.1)],
     apps="PS/AI/PR",
     basis="Photoshop's Paint Bucket and Illustrator's Live Paint — the tipped can.",
     note="A tipped bucket, with the drop that has already left it.")

icon("eyedropper", "tools", ["picker", "sample", "colour pick"],
     s=[poly([(-8.6, -2.6), (-4.0, -2.0), (-4.0, 2.0), (-8.6, 2.6)], close=True, t=T),
        poly([(-4.0, -1.4), (4.8, -0.8), (4.8, 0.8), (-4.0, 1.4)], close=True, t=T)],
     f=[poly([(4.8, -0.8), (9, 0), (4.8, 0.8)], close=True, t=T)],
     apps="PS/AI/PR",
     basis="The Photoshop and Illustrator eyedropper — bulb, barrel, point.",
     note="The dropper, solid where it takes the sample.")

icon("mixer-brush", "tools", ["wet mix", "blend paint", "load"],
     s=[line(-9, 0, -2.4, 0, t=T),
        poly([(-2.4, -2.2), (2.4, -1.6), (5.2, 0), (2.4, 1.6), (-2.4, 2.2)], close=True, t=T),
        arc(8.4, 16.4, 4.4, 150, 430), arc(8.4, 16.4, 2.1, 200, 430)],
     f=[dot(8.4, 16.4, 1.0)],
     apps="PS",
     basis="Photoshop's Mixer Brush — a loaded brush over the swirl it stirs.",
     note="A brush working colour already on the surface.")

# ---------------------------------------------------------------------------------------
# Brush behaviour
# ---------------------------------------------------------------------------------------

icon("brush-library", "tools", ["brushes", "presets", "pot"],
     s=[poly([(5.0, 10.6), (19.0, 10.6), (17.6, 21.0), (6.4, 21.0)], close=True),
        line(8.4, 10.6, 7.0, 3.4), line(12.0, 10.6, 12.0, 2.6), line(15.6, 10.6, 17.0, 3.4)],
     f=[seq(poly([(6.2, 3.4), (7.8, 3.4), (8.6, 7.4), (7.0, 7.4)], close=True),
            poly([(10.8, 2.6), (13.2, 2.6), (13.2, 6.6), (10.8, 6.6)], close=True),
            poly([(16.2, 3.4), (17.8, 3.4), (17.0, 7.4), (15.4, 7.4)], close=True))],
     apps="PS/PR",
     basis="Procreate's Brush Library — a set of loaded brushes rather than one.",
     note="A pot of brushes, each with paint on it.")

icon("brush-settings", "tools", ["brush studio", "parameters", "engine"],
     s=[line(4.0, 6.4, 20.0, 6.4), line(4.0, 12.0, 20.0, 12.0), line(4.0, 17.6, 20.0, 17.6),
        circle(9.0, 6.4, 1.9), circle(15.4, 17.6, 1.9)],
     f=[dot(12.6, 12.0, 1.9)],
     apps="PS/PR",
     basis="Procreate's Brush Studio and Photoshop's Brush Settings — stacked parameter rails.",
     note="Three rails; the middle one is the one being moved.")

icon("brush-size", "tools", ["diameter", "radius", "scale brush"],
     s=[circle(12, 12, 8.4), circle(12, 12, 4.2), line(12, 12, 17.9, 6.1)],
     f=[dot(12, 12, 1.35)],
     apps="PS/AI/PR",
     basis="Photoshop's brush-size preview — nested footprints on one centre.",
     note="Two diameters measured out from a single origin.")

icon("brush-opacity", "tools", ["alpha", "coverage", "translucent"],
     s=[circle(12, 12, 8.4), line(12, 3.6, 12, 20.4)],
     f=[pie(12, 12, 8.4, 90, 270)],
     apps="PS/AI/PR",
     basis="Procreate's opacity slider thumb — one disc half laid down.",
     note="Half committed, half withheld.")

icon("brush-flow", "tools", ["rate", "load", "delivery"],
     s=[rect(7.0, 3.4, 10.0, 17.2), seq(line(7.0, 9.0, 17.0, 9.0), line(7.0, 14.8, 17.0, 14.8))],
     f=[rect(7.0, 14.8, 10.0, 5.8)],
     apps="PS/PR",
     basis="Photoshop's Flow control — how much arrives per pass, not how dark it ends up.",
     note="Three drops at three depths of fall.")

icon("brush-hardness", "tools", ["edge", "falloff", "core"],
     s=[seq(*[arc(16.6, 12, 4.4, a, a + 40) for a in (0, 60, 120, 180, 240, 300)])],
     f=[circle(7.4, 12, 4.4)],
     apps="PS/AI/PR",
     basis="Photoshop's hardness preview — solid core, soft shoulder, outer limit.",
     note="A solid core, a broken halo, an outer edge.")

icon("brush-spacing", "tools", ["stamps", "interval", "gap"],
     s=[circle(6.0, 11.4, 2.7), circle(18.0, 11.4, 2.7), line(3.3, 18.6, 20.7, 18.6)],
     f=[dot(12.0, 11.4, 2.7)],
     apps="PS/PR",
     basis="Photoshop's Spacing setting — a brush is a run of stamps, not a line.",
     note="Three stamps on a measured run; the middle one just landed.")

icon("brush-scatter", "tools", ["jitter", "spread", "random"],
     s=[line(2.8, 17.2, 21.2, 6.8), circle(7.6, 10.2, 1.7), circle(16.8, 15.4, 1.7),
        circle(13.4, 7.6, 1.4)],
     f=[dot(10.0, 17.6, 1.35)],
     apps="PS/PR",
     basis="Photoshop's Scattering — marks thrown off the path they were meant to follow.",
     note="A stroke path with its stamps scattered around it.")

icon("brush-angle", "tools", ["rotation", "nib angle", "protractor"],
     s=[line(3.4, 19.4, 20.6, 19.4), line(4.6, 19.4, 16.6, 7.4), arc(4.6, 19.4, 7.6, 315, 360)],
     f=[poly([(16.6, 7.4), (20.0, 5.6), (18.4, 9.0)], close=True)],
     apps="PS/AI/PR",
     basis="Photoshop's brush Angle dial and Illustrator's calligraphic angle.",
     note="An angle read off the baseline, solid where it points.")

icon("brush-roundness", "tools", ["flatten", "squash", "ellipse nib"],
     s=[circle(6.8, 12, 3.8)],
     f=[ellipse(16.2, 12, 4.6, 2.2)],
     apps="PS/AI/PR",
     basis="Photoshop's Roundness control — a round footprint compressed on one axis.",
     note="A circle squeezed against its own limit.")

icon("brush-jitter", "tools", ["variance", "wobble", "noise"],
     s=[line(3.0, 12, 21.0, 12),
        seq(line(5.4, 12, 5.4, 6.6), line(8.4, 12, 8.4, 16.4), line(11.4, 12, 11.4, 8.4),
            line(14.4, 12, 14.4, 17.6), line(17.4, 12, 17.4, 7.8), line(20.0, 12, 20.0, 14.8))],
     f=[],
     apps="PS/PR",
     basis="Photoshop's jitter controls — a value that refuses to hold still.",
     note="A wandering parameter above a fixed baseline.")

icon("brush-taper", "tools", ["pressure taper", "stroke ends", "swell"],
     s=[seq(line(2.4, 12, 4.6, 12), line(19.4, 12, 21.6, 12))],
     f=[poly([(4.6, 12), (9.2, 8.6), (14.8, 8.6), (19.4, 12),
              (14.8, 15.4), (9.2, 15.4)], close=True)],
     apps="PR",
     basis="Procreate's Taper settings — a stroke that opens, swells and closes.",
     note="One stroke, thin at both ends.")

icon("brush-wet", "tools", ["wet mix", "dilute", "bleed"],
     s=[seq(line(12, 2.6, 14.5, 6.0), arc(12, 7.6, 3.1, 317, 583), line(9.5, 6.0, 12, 2.6)),
        seq(arc(12, 17.0, 4.0, 200, 340), arc(12, 17.4, 7.6, 205, 335))],
     f=[],
     apps="PS/PR",
     basis="Procreate's Wet Mix — two loads bleeding into each other.",
     note="Two footprints overlapping; the wet edge is where they meet.")

icon("brush-buildup", "tools", ["accumulate", "density", "layering"],
     s=[arc(7.0, 15.0, 5.0, 180, 360), arc(12.0, 15.0, 5.0, 180, 360),
        arc(17.0, 15.0, 5.0, 180, 360), line(2.0, 15.0, 22.0, 15.0)],
     f=[],
     apps="PS/PR",
     basis="Photoshop's Build-up airbrush behaviour — passes stacking over one another.",
     note="Three passes over the same ground.")

icon("brush-dual", "tools", ["dual brush", "second tip", "combine"],
     s=[circle(12, 12, 8.2), circle(12, 12, 4.6)],
     f=[dot(12, 12, 2.0)],
     apps="PS",
     basis="Photoshop's Dual Brush — a second tip riding inside the first.",
     note="A smaller footprint carried by a larger one.")

icon("brush-texture", "tools", ["grain", "tooth", "paper"],
     s=[seq(line(4.4, 7.0, 5.6, 7.0), line(11.0, 8.6, 12.2, 8.6), line(17.4, 6.6, 18.6, 6.6),
            line(6.2, 13.4, 7.4, 13.4), line(14.0, 12.2, 15.2, 12.2),
            line(4.8, 18.6, 6.0, 18.6), line(12.6, 17.4, 13.8, 17.4))],
     f=[dot(19.2, 17.4, 1.1)],
     apps="PS/PR",
     basis="Photoshop's Texture setting and Procreate's grain source — paper tooth as a field.",
     note="Tooth recorded as a field of marks, one of them struck.")

icon("brush-import", "tools", ["load brush", "install", "abr"],
     s=[line(12, 3.0, 12, 10.4),
        poly([(4.2, 13.6), (4.2, 20.4), (19.8, 20.4), (19.8, 13.6)])],
     f=[tip(12, 13.6, 3.4, 90)],
     apps="PS/PR",
     basis="Procreate's brush import and Photoshop's .abr load.",
     note="A brush dropped into the shelf that holds them.")

# ---------------------------------------------------------------------------------------
# Input and assistance
# ---------------------------------------------------------------------------------------

icon("stylus", "tools", ["pen hardware", "pencil", "digitiser"],
     s=[poly([(-9, -1.9), (3.4, -1.9), (3.4, 1.9), (-9, 1.9)], close=True, t=T),
        line(-4.0, -1.9, -4.0, 1.9, t=T)],
     f=[poly([(3.4, -1.9), (9, 0), (3.4, 1.9)], close=True, t=T)],
     apps="PR",
     basis="Procreate's stylus settings — the barrel and the tip the digitiser reads.",
     note="A stylus on the tool diagonal.")

icon("pressure", "tools", ["force", "hard press", "weight"],
     s=[poly([(-8.6, -1.7), (1.6, -1.7), (1.6, 1.7), (-8.6, 1.7)], close=True, t=T),
        seq(arc(8.04, 15.96, 3.2, 92, 188), arc(8.04, 15.96, 5.0, 92, 188))],
     f=[poly([(1.6, -1.7), (5.6, 0), (1.6, 1.7)], close=True, t=T)],
     apps="PR",
     basis="Procreate's Pressure curve — force registered at the point of contact.",
     note="A tip pressed hard enough into the surface to ring.")

_TILT = xf(9.0, 20.4, -55)

icon("tilt", "tools", ["angle", "shading", "lean"],
     s=[poly([(1.6, -1.7), (14.0, -1.7), (14.0, 1.7), (1.6, 1.7)], close=True, t=_TILT),
        line(3.0, 20.4, 21.0, 20.4), arc(9.0, 20.4, 5.4, -55, 0)],
     f=[poly([(0, 0), (1.6, -1.7), (1.6, 1.7)], close=True, t=_TILT)],
     apps="PR",
     basis="Procreate's Tilt settings — the lean of the barrel against the surface.",
     note="An angle measured off the plane the tool rests on.")

icon("streamline", "tools", ["stabiliser", "smoothing", "steady"],
     s=[poly([(4.0, 6.6), (8.0, 3.0), (12.0, 8.0), (16.0, 3.4), (20.0, 6.2)]),
        smooth([(4.0, 20.4), (8.5, 17.8), (12.0, 20.4), (15.5, 17.8), (20.0, 20.4)]),
        line(12, 10.0, 12, 15.4)],
     f=[tip(12, 16.6, 2.0, 90)],
     apps="PR",
     basis="Procreate's StreamLine — the same gesture above and below the filter.",
     note="Raw input over corrected output.")

icon("quickshape", "tools", ["snap to shape", "clean up", "auto shape"],
     s=[arc(12, 12, 8.0, -90, 90),
        poly([(12, 20.0), (6.6, 18.8), (4.0, 12), (6.6, 5.2), (12, 4.0)])],
     f=[dot(12, 4.0, 1.3)],
     apps="PR",
     basis="Procreate's QuickShape — a hand-drawn loop pulled onto true geometry.",
     note="A wobble becoming a circle.")

icon("colordrop", "tools", ["flood fill", "drag colour", "drop"],
     s=[rect(3.4, 12.6, 17.2, 8.0)],
     f=[poly([(12, 3.0), (16.2, 12.0), (7.8, 12.0)], close=True)],
     apps="PR",
     basis="Procreate's ColorDrop — colour dragged into a closed region, which takes it whole.",
     note="A dropped colour flooding what it lands in.")

icon("alpha-lock", "tools", ["lock transparency", "paint inside", "preserve"],
     s=[rect(4.6, 10.0, 14.8, 10.6, 1.8), arc(12, 10.0, 4.2, 180, 360)],
     f=[seq(rect(9.0, 12.6, 3.0, 2.6), rect(12.0, 15.2, 3.0, 2.6))],
     apps="PS/PR",
     basis="Procreate's Alpha Lock and Photoshop's Lock Transparent Pixels — the chequer, shackled.",
     note="Paint may land only where pixels already are.")

icon("symmetry", "tools", ["mirror", "reflect drawing", "guide"],
     s=[seq(line(12, 2.4, 12, 5.2), line(12, 7.4, 12, 10.2),
            line(12, 13.8, 12, 16.6), line(12, 18.8, 12, 21.6)),
        poly([(9.2, 5.6), (4.0, 12), (9.2, 18.4)], close=True),
        poly([(14.8, 5.6), (20.0, 12), (14.8, 18.4)], close=True)],
     f=[],
     apps="PS/PR",
     basis="Photoshop's Paint Symmetry and Procreate's Symmetry guide — a broken axis, answered.",
     note="The same form on both sides of a guide.")

icon("symmetry-vertical", "tools", ["mirror x", "left right"],
     s=[seq(line(12, 2.4, 12, 5.2), line(12, 7.4, 12, 10.2),
            line(12, 13.8, 12, 16.6), line(12, 18.8, 12, 21.6)),
        poly([(9.4, 6.0), (3.6, 12), (9.4, 18.0)], close=True)],
     f=[poly([(14.6, 6.0), (20.4, 12), (14.6, 18.0)], close=True)],
     apps="PS/PR",
     basis="Procreate's Vertical symmetry — the generated half is the solid one.",
     note="Vertical axis, right half generated.")

icon("symmetry-horizontal", "tools", ["mirror y", "top bottom"],
     s=[seq(line(2.4, 12, 5.2, 12), line(7.4, 12, 10.2, 12),
            line(13.8, 12, 16.6, 12), line(18.8, 12, 21.6, 12)),
        poly([(6.0, 9.4), (12, 3.6), (18.0, 9.4)], close=True)],
     f=[poly([(6.0, 14.6), (12, 20.4), (18.0, 14.6)], close=True)],
     apps="PS/PR",
     basis="Procreate's Horizontal symmetry.",
     note="Horizontal axis, lower half generated.")

icon("symmetry-quadrant", "tools", ["four way", "kaleidoscope"],
     s=[seq(dashed(12, 2.6, 12, 21.4, 5), dashed(2.6, 12, 21.4, 12, 5)),
        seq(poly([(8.6, 4.4), (5.0, 4.4), (5.0, 8.0)]), poly([(15.4, 4.4), (19.0, 4.4), (19.0, 8.0)]),
            poly([(8.6, 19.6), (5.0, 19.6), (5.0, 16.0)]))],
     f=[poly([(15.4, 19.6), (19.0, 19.6), (19.0, 16.0)], close=True)],
     apps="PR",
     basis="Procreate's Quadrant symmetry — both axes live at once.",
     note="One mark becomes four.")

icon("symmetry-radial", "tools", ["rotational", "mandala", "spokes"],
     s=[seq(line(12, 12, 12, 4.6), line(12, 12, 19.0, 9.7), line(12, 12, 16.4, 18.0),
            line(12, 12, 7.6, 18.0), line(12, 12, 5.0, 9.7))],
     f=[seq(dot(12, 3.6, 1.5), dot(19.9, 9.4, 0.9), dot(16.9, 18.7, 0.9),
            dot(7.1, 18.7, 0.9), dot(4.1, 9.4, 0.9))],
     apps="PR",
     basis="Procreate's Radial symmetry — spokes around one centre.",
     note="A mark repeated around the wheel.")

icon("guide-drawing", "tools", ["assist", "grid guide", "on rails"],
     s=[rect(3.4, 3.4, 17.2, 17.2),
        seq(line(9.2, 3.4, 9.2, 20.6), line(14.8, 3.4, 14.8, 20.6),
            line(3.4, 9.2, 20.6, 9.2), line(3.4, 14.8, 20.6, 14.8))],
     f=[dot(14.8, 9.2, 1.5)],
     apps="PR",
     basis="Procreate's Drawing Guide with Assisted Drawing on — a stroke held to the grid.",
     note="A drawing that cannot leave the grid it was given.")

icon("guide-perspective", "tools", ["vanishing point", "one point", "recede"],
     s=[line(2.6, 20.4, 21.4, 20.4),
        seq(line(2.6, 20.4, 17.6, 6.4), line(8.0, 20.4, 17.6, 6.4), line(15.0, 20.4, 17.6, 6.4))],
     f=[dot(18.4, 5.6, 1.5)],
     apps="PS/AI/PR",
     basis="Illustrator's Perspective Grid and Procreate's Perspective guide.",
     note="Lines converging on a single solid vanishing point.")

icon("guide-isometric", "tools", ["axonometric", "30 degrees", "isometric"],
     s=[poly([(19.3, 16.2), (12, 20.4), (4.7, 16.2), (4.7, 7.8), (12, 3.6), (19.3, 7.8)],
              close=True),
        seq(line(12, 12, 12, 20.4), line(12, 12, 4.7, 7.8), line(12, 12, 19.3, 7.8))],
     f=[],
     apps="AI/PR",
     basis="Procreate's Isometric guide and Illustrator's isometric grids.",
     note="Three axes at thirty degrees, meeting at one origin.")
