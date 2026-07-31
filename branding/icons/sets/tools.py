"""Tools & brushes — what touches the surface.

Metaphors are inherited from Photoshop, Illustrator and Procreate: the brush is slanted, the
blur is a water drop, the sharpen is a cone, the dodge is a darkroom paddle, the burn is a
cupped hand, the heal is a sticking plaster, the bucket is tipped. None of the geometry is
theirs — every glyph is redrawn on the Graffux grid — but the association a user already
carries is not thrown away to prove a point.

Only the brush and the pencil sit on the 135-degree tool diagonal. An earlier draft put every
hand-held implement on that axis so the family would read as a family; at 24 pixels it read as
twelve copies of a garden trowel instead, because a slanted stick with a wedge on the end is a
slanted stick with a wedge on the end no matter what you carve into the wedge. The differences
that separated them — a split nib, a chisel, bristles — all live below the resolution that has
to carry them. So the axis is gone as an organising idea: the marker, crayon, stylus and oil
brush stand upright, the chalk lies down, and the tools whose body is less recognisable than
their mark (calligraphy, charcoal, smudge, pressure) show the mark instead.

The solid keystone is still always the working end — the part that meets the pixel.
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
    nib,
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
     s=[line(-9, 0, -2.0, 0, t=T), rect(-2.0, -1.6, 2.5, 3.2, t=T)],
     f=[poly([(0.5, -1.6), (2.6, -2.6), (9, -1.2), (6.4, -0.4), (8.8, 0.4), (6.0, 1.0),
              (7.6, 2.2), (2.6, 2.6), (0.5, 1.6)], close=True, t=T)],
     apps="PS/AI/PR",
     basis="Photoshop's slanted brush and Procreate's brush tip — handle up-right, tip down-left.",
     note="Long handle, a boxed metal ferrule, and a head that flares wider than the ferrule "
          "before breaking into three hair points. Any smooth solid wedge on a slanted stick is "
          "a garden trowel — that is what a trowel is — and the only thing that has ever broken "
          "the reading is visible bristles. A head longer than its handle is a spear.")

icon("pencil", "tools", ["draw", "sketch", "graphite", "edit"],
     s=[poly([(-9, -1.7), (2.4, -1.7), (9, 0), (2.4, 1.7), (-9, 1.7)], close=True, t=T),
        line(2.4, -1.7, 2.4, 1.7, t=T)],
     f=[poly([(6.0, -0.78), (9, 0), (6.0, 0.78)], close=True, t=T)],
     apps="PS/AI/PR",
     basis="The Photoshop and Illustrator pencil: a sharpened shaft on the same diagonal.",
     note="Shaft, collar, sharpened cone with a solid graphite point.")

icon("airbrush", "tools", ["spray", "atomiser", "soft", "mist"],
     s=[rect(9.4, 3.0, 5.2, 3.6), rect(10.8, 6.6, 2.4, 2.4)],
     f=[seq(dot(12, 11.0, 1.25), dot(8.6, 14.2, 1.0), dot(15.4, 14.6, 0.9),
            dot(10.8, 18.4, 0.8), dot(17.2, 18.8, 0.65))],
     apps="PS/PR",
     basis="Photoshop's airbrush option — a nozzle throwing a widening cone of mist.",
     note="Nozzle at the top and the mist under it, spreading and thinning as it falls. Ruling "
          "two straight edges onto that cone turned the pair of them into the legs of a drafting "
          "compass, so the cone is described only by where the particles land.\n\n"
          "The fan is kept narrow. Widened to reach the frame edges — which was done to "
          "separate it from brush-flow, also a nozzle with marks under it — the nozzle "
          "assembly above a spreading spray was named as an ejaculating penis. Brush-flow "
          "gave up its nozzle instead.")

icon("marker", "tools", ["felt tip", "chisel", "highlighter"],
     s=[rect(7.6, 3.4, 8.0, 4.2, 1.0), line(16.6, 4.6, 16.6, 9.0), rect(8.8, 7.6, 5.6, 6.4)],
     f=[poly([(8.8, 14.0), (14.4, 14.0), (14.4, 20.6), (8.8, 16.6)], close=True)],
     apps="PS/PR",
     basis="Procreate's inking and marker brush families — a fat barrel cut to a chisel.",
     note="A cap wider than the barrel it sits on, and a felt tip cut hard across at an angle. "
          "The step at the cap is what stops it: a single rounded barrel of even width, banded "
          "or not, is a phone. A shallow bevel on the tip made it a USB stick, so the chisel is "
          "the loudest thing in the glyph — the chisel is the whole tool.")

icon("pen-ink", "tools", ["nib", "dip pen", "ink", "line art"],
     s=[poly([(7.2, 6.6), (9.0, 4.0), (15.0, 4.0), (16.8, 6.6), (12, 20.6)], close=True),
        circle(12, 9.4, 1.5), line(12, 10.9, 12, 16.4)],
     f=[poly([(10.6, 16.4), (13.4, 16.4), (12, 20.6)], close=True)],
     apps="PS/AI/PR",
     basis="Procreate's technical pen and Photoshop's ink brushes — a split dip-pen nib.",
     note="The nib on its own, at full size, with the two things only a nib has: a round "
          "breather hole and the slit running from it to the point. No handle — a handle is "
          "what made this a stick with a wedge on it.")

icon("calligraphy", "tools", ["broad edge", "lettering", "italic nib"],
     s=[quad((5.0, 20.6), (11.4, 12.0), (19.0, 3.6))],
     f=[Path("M19,3.6 Q9.4,10.4 7.4,17.6 Q3.4,8.2 19,3.6Z",
             [(19.0, 3.6), (7.4, 17.6), (5.8, 9.9), (10.9, 12.1)])],
     apps="PS/AI/PR",
     basis="The quill — the broad-edge pen every calligraphic hand was cut from, and the one "
           "writing implement nobody mistakes for anything else.",
     note="Shaft curving to the cut point at lower left, with the vane solid against it.")

_CRY = xf(12, 10.4, 18)

icon("crayon", "tools", ["wax", "kids", "stub"],
     s=[rect(-4.4, -4.4, 8.8, 11.2, t=_CRY),
        poly([(-4.4, -0.6), (-2.4, -1.5), (-0.4, -0.6), (1.6, -1.5), (4.4, -0.6)], t=_CRY),
        line(-4.4, 4.4, 4.4, 4.4, t=_CRY)],
     f=[poly([(-4.4, 6.8), (4.4, 6.8), (0.7, 12.6), (-0.7, 12.6)], close=True, t=_CRY)],
     apps="PR",
     basis="Procreate's Artistic set — the wrapped wax stub.",
     note="Fat and short, with the paper label banded round the middle and the wax worn to a "
          "cone with a small flat left on it. A slim body with a sharp point is the pencil; a "
          "flat foot under a banded box is a battery, and a domed top over a banded box is a "
          "rifle cartridge. Flat top, a cone with barely any flat left on it, and the wrapper "
          "torn along its upper edge — a clean band left it in a pile with the eyedropper and "
          "the stylus, all three of which came back as \"a fountain pen nib\". Only the torn "
          "paper belongs to a crayon and nothing else.")

_CHK = xf(11.0, 10.4, -14)

icon("chalk", "tools", ["pastel", "dry media", "stick"],
     s=[seq(circle(15.8, 16.4, 0.55), circle(18.6, 18.2, 0.55), circle(13.8, 19.2, 0.55))],
     f=[poly([(-8.0, -2.0), (-5.6, -2.4), (5.4, -2.4), (5.4, 0.6), (3.4, 2.4), (-6.0, 2.4),
              (-8.0, 1.6)], close=True, t=_CHK)],
     apps="PR",
     basis="Procreate's chalk and soft-pastel brushes — a square stick that sheds.",
     note="A plain stick lying at the shallow angle chalk is actually held at, worn away at one "
          "corner and shedding powder. Left square, the filled end beside the open barrel read "
          "as a screen beside a button — some handheld device. The wear makes it a stick again. "
          "The powder is dots; charcoal's marks are lines, and that is all that separates them.")

icon("charcoal", "tools", ["carbon", "smudgy", "dry media"],
     s=[seq(line(10.4, 11.6, 20.0, 17.2), line(8.0, 12.2, 17.4, 19.4),
            line(6.6, 14.4, 13.4, 20.4))],
     f=[poly([(8.2, 3.6), (14.6, 5.0), (17.6, 8.2), (15.4, 12.4), (9.6, 12.8), (6.6, 8.6),
              (7.2, 5.2)], close=True)],
     apps="PR",
     basis="Procreate's charcoal brushes — a broken lump, never a manufactured stick.",
     note="No handle at all: a solid irregular lump with the grainy marks running out from under "
          "it. Detached, the marks read as speed lines and the lump becomes a meteor; touching, "
          "the lump is plainly what made them.")

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
     s=[line(12, 3.0, 12, 8.0), rect(9.4, 8.0, 5.2, 3.4)],
     f=[poly([(9.4, 11.4), (14.6, 11.4), (16.2, 20.6), (13.6, 18.4), (12, 20.6), (10.4, 18.4),
              (7.8, 20.6)], close=True)],
     apps="PS/PR",
     basis="Photoshop's bristle-tip brushes — the only tool that shows its hairs.",
     note="Seen face-on rather than in profile: thin handle, crimped ferrule, and bristles that "
          "splay wider than the ferrule and end ragged. In profile this was indistinguishable "
          "from every other slanted stick.")

icon("blob-brush", "tools", ["merge shapes", "vector paint", "blob"],
     s=[],
     f=[poly([(3.4, 19.4), (5.0, 12.4), (9.6, 8.0), (15.4, 6.8), (19.6, 9.4), (20.6, 13.8),
              (16.4, 17.4), (10.4, 18.2), (6.6, 21.0)], close=True)],
     apps="AI",
     basis="Illustrator's Blob Brush — a brush whose strokes fuse into one filled shape.",
     note="A large lobed filled form with a small brush just touching its edge. The output is a "
          "closed shape rather than a line, so the shape has to dominate — but it has to be "
          "lumpy while it does it. There is no brush in the glyph at all any more, because "
          "every position tried for one turned the pair into an animal or an object: on a stalk "
          "above the blob it was a bomb with a lit fuse, cropped short against it a beak, laid "
          "flat across the top a frying pan. The mark carries the idea on its own — a stroke "
          "that came out as a closed lumpy form instead of a line is the whole tool.")

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
     s=[rect(7.0, 3.4, 10.0, 4.2, 1.4), rect(10.4, 7.6, 3.2, 2.8), rect(5.0, 10.4, 14.0, 3.4),
        line(3.4, 20.6, 20.6, 20.6)],
     f=[rect(3.4, 13.8, 17.2, 3.6)],
     apps="PS",
     basis="Photoshop's Clone Stamp — the rubber stamp, unchanged since 1990.",
     note="A wide grip, a short neck, the plate, and the solid face that transfers the sample, "
          "resting on the surface it prints onto. A small knob on a long narrow stem above a "
          "flared base is a butt plug, and the proportions are the whole of the difference.")

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

_ERA = xf(12.6, 10.6, -20)

icon("eraser", "tools", ["rub out", "delete", "undo mark"],
     s=[rect(-6.0, -4.6, 12.0, 9.2, t=_ERA), line(-1.6, -4.6, -1.6, 4.6, t=_ERA),
        line(2.6, 18.4, 7.0, 18.4)],
     f=[poly([(-6.0, -4.6), (-1.6, -4.6), (-1.6, 4.6), (-6.0, 4.6)], close=True, t=_ERA)],
     apps="PS/AI/PR",
     basis="The Photoshop and Procreate block eraser, drawn mid-wipe rather than at rest.",
     note="A rubber block held at the angle it is used at, divided end to end with the "
           "working end solid, and the mark it has come to the end of running in from the "
           "left.\n\n"
           "Which way the block is divided decides what it is. Divided across, into a pale "
           "strip over a dark one, it is a container with a lid: square to the frame it was a "
           "file folder, a wallet, a battery at sixty per cent, a toaster and a briefcase, and "
           "tilted it was an open laptop and a ballot box with a coin slot. Undivided and "
           "solid it stopped being a container and became a spade blade. Divided end to end "
           "it is neither, and it is also how a two-tone eraser is actually made.\n\n"
           "Crumbs were tried three ways and failed three ways: in a row underneath they were "
           "wheels and the block a delivery van; round, they were soap suds and the block a "
           "washing machine; as short flecks they closed into a two-lobed lump a rater called "
           "a bone, then a pair of testicles. Small marks clustered beside a body do not stay "
           "abstract, so the mark being rubbed out does that work instead."
)

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
     s=[poly([(2.2, 17.9), (9.0, 13.0), (10.4, 8.8), (12.4, 6.4), (14.4, 8.6), (16.6, 6.4),
              (18.8, 8.6), (20.0, 10.6), (20.0, 15.2), (18.2, 17.8), (14.4, 18.6),
              (11.6, 17.6), (11.1, 16.0), (4.3, 20.9)], close=True)],
     f=[poly([(2.2, 17.9), (4.3, 20.9), (6.9, 19.7), (4.8, 16.7)], close=True)],
     apps="PS/PR",
     basis="Photoshop's Smudge tool — a closed hand with the index finger put out.",
     note="A fist with the index put out along the tool diagonal, pointing where the brush tip "
          "and the pencil point point. The hand stands upright; only the finger is on the "
          "diagonal. Outlined, with the pad solid where it meets the pixel.\n\n"
          "This is the weakest glyph in the set and it is the twenty-fourth drawing of it. It "
          "is the version that gets named correctly at first glance — a rater with no context "
          "called it a clenched fist — but it carries a knuckle-duster and a stubby handgun as "
          "second readings, and it has been read as a molar tooth and an old-fashioned key by "
          "other raters. Every alternative tried was worse in a way that mattered more:\n\n"
          "Face-on, pointing down, one finger extended — the obvious way to aim a finger at the "
          "bottom of the frame where the eraser's face and the pen's nib are — is a hand giving "
          "the finger, and two separate raters named the gesture before naming anything else in "
          "the drawing. Moving the extended digit from the middle of the hand to the index at "
          "the edge of the group did not fix it, and nor did holding the thumb out sideways "
          "where no finger could be. A fist with a digit out of it reads as that gesture at any "
          "angle it is drawn.\n\n"
          "Adding a cuff at the wrist does fix the key and the tooth — a band across the wrist "
          "says the mass is attached to an arm, and the manicule has carried one since the "
          "twelfth century — but a fist on a cuffed forearm is the raised-fist salute, and the "
          "same rater who named that also named an obscene reading. A political symbol is a "
          "worse failure than a knuckle-duster. Without the cuff and without the solid pad the "
          "same outline is a key with a phallic second reading; the pad is what stops that, and "
          "the pad is also what makes the knuckle-duster. There is no configuration of this "
          "glyph that is clean.\n\n"
          "Turning the whole hand is worse than turning nothing. The pan hand's own outline, "
          "folded and rotated 225 degrees so the index lay on the diagonal, came back as a "
          "kite: at 45 degrees the finger gaps line up with the diagonal and the wrist becomes "
          "a tail. A face-on hand is only read as a hand on a vertical axis. Rebuilt as a "
          "manicule in profile — hand, cuff, curled fingers, thumb on top, laid along the "
          "diagonal — it was a torch at three different sets of proportions.\n\n"
          "Before those: a rocket, a boot, a shoe on a ramp, a remote control, a lightbulb, a "
          "revolver, a sledgehammer, a syringe, a lollipop, a mushroom cloud, a paper bird, a "
          "hexagonal key head with a hamburger menu in it. Drawn solid it was bistable and read "
          "as two different objects on two viewings of the same path. Interior creases did not "
          "settle it, because a crease is a line on a mass and a gap is two objects. Drawing "
          "the folded fingers as separate rolls made the hand a key head.\n\n"
          "Distinct from the pan hand, which is a hand face-on, upright, open, with all five "
          "splayed, and which has never once been misread by anyone."
)

icon("blur", "tools", ["soften", "out of focus", "droplet"],
     s=[seq(line(12, 3.2, 16.99, 9.58), arc(12, 13.6, 6.4, 321.2, 578.8), line(7.01, 9.58, 12, 3.2)),
        seq(line(8.9, 12.6, 15.1, 12.6), line(9.6, 15.8, 14.4, 15.8))],
     f=[],
     apps="PS/AI/PR",
     basis="Photoshop's Blur tool — the water drop, unchanged.",
     note="The drop, with the detail under it collapsing into bands.")

icon("sharpen", "tools", ["crisp", "detail", "cone"],
     s=[ellipse(12, 16.4, 5.4, 1.9), line(6.6, 16.4, 12, 3.6), line(17.4, 16.4, 12, 3.6),
        line(3.0, 20.6, 21.0, 20.6)],
     f=[poly([(12, 3.6), (13.6, 7.4), (10.4, 7.4)], close=True)],
     apps="PS/AI/PR",
     basis="Photoshop's Sharpen tool — the sharpening cone next to the blur drop.",
     note="The cone, solid, leaning off vertical. Stood up straight and symmetrical on its base "
          "it is a pointed hood over a plinth, which is a Klansman, and once seen it cannot be "
          "unseen. The lean costs nothing and removes it.")

icon("dodge", "tools", ["lighten", "hold back", "darkroom"],
     s=[line(13.8, 13.0, 20.6, 19.8)],
     f=[rect(4.4, 4.0, 9.6, 9.2, 2.4)],
     apps="PS",
     basis="Photoshop's Dodge tool — the darkroom paddle on its wire, throwing light back.",
     note="An opaque paddle on a bent wire, held between the lamp and the paper. The paddle is solid and empty: drawn hollow with three small circles in it — the obvious way to say a disc has depth — it was a bowling ball, then a face, then a US power outlet, in that order, from three different raters. Filled in and left round it was a sperm cell: a solid ball trailing a thin bent line is that silhouette exactly. The paddle is square because a darkroom paddle can be any shape, and a square one is neither a lens nor a cell.")

icon("burn", "tools", ["darken", "shade", "cupped hand", "aperture"],
     s=[poly([(17.6, 5.0), (10.6, 4.6), (5.2, 7.4), (3.6, 12.4), (5.6, 17.4), (11.0, 19.6),
              (17.6, 19.0), (13.4, 16.2), (9.6, 15.6), (7.6, 12.4), (9.4, 9.0), (13.2, 8.0)],
             close=True)],
     f=[poly([(4.6, 19.4), (19.4, 19.4), (19.4, 21.6), (4.6, 21.6)], close=True)],
     apps="PS",
     basis="Photoshop's Burn tool — the hand cupped into an aperture to concentrate exposure.",
     note="A hand cupped into a C, seen edge-on, over the band of paper it is darkening. Drawn "
          "as a true C-ring with the solid mark at its exact centre, which is the obvious way "
          "to draw an aperture, it was a nipple and areola — a ring with a filled circle "
          "concentric inside it is that and very little else, whatever the ring is meant to be. "
          "Drawn instead with the solid as a wedge of light narrowing into the cup, it was the "
          "rewind button, and a vulva on the third guess. The solid is the result, not the "
          "light: a band of paper gone dark under the hand that shaded it."
)

icon("sponge", "tools", ["saturation", "soak", "porous"],
     s=[seq(circle(6.2, 7.8, 1.3), circle(9.0, 4.8, 0.95), circle(11.4, 2.6, 0.65))],
     f=[rect(6.2, 10.4, 12.6, 10.0, 1.6)],
     apps="PS",
     basis="Photoshop's Sponge tool — a block being squeezed until the colour comes out of it.",
     note="A solid block with suds coming off it. Six drawings, and the first five all tried "
           "to draw the sponge itself. As a torn blob with three round pores in it, which is "
           "what a sponge actually looks like, it was a slice of Swiss cheese to every rater "
           "who saw it, and a skull at small size once two pores overlapped into eye sockets: "
           "holes in a blob are cheese. With the underside wrung into points, the points were "
           "fangs and the drops were drool — a row of points along a solid edge is a jaw, "
           "which is what went wrong with the brush texture in the same round. Pinched at the "
           "waist it was a ticket stub. Given a soft irregular outline it was a thought "
           "bubble, a cloud, and a pair of buttocks. A lobed blob is a body before it is "
           "anything else; the block is a plain rectangle for that reason, and the bubbles "
           "carry the whole meaning. The block is deep rather than wide: flat and wide with "
           "bubbles over it, it was a bathtub. The bubbles rise in a line off one corner "
           "and are three, not two: two marks side by side above a mass are eyes, and a rater "
           "called the pair of them a blank cartoon face at every size, more strongly the "
           "smaller it got."
)

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
     s=[circle(-6.2, 0, 3.3, t=T),
        poly([(-3.2, -1.2), (4.8, -0.8), (4.8, 0.8), (-3.2, 1.2)], close=True, t=T)],
     f=[poly([(4.8, -0.8), (9, 0), (4.8, 0.8)], close=True, t=T)],
     apps="PS/AI/PR",
     basis="The Photoshop and Illustrator eyedropper — bulb, barrel, point.",
     note="A round squeeze bulb on a thin barrel, solid where it takes the sample. Tapered "
          "instead of round, the bulb is just the wide end of a nib.")

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
     s=[circle(12, 12, 6.4), line(7.8, 12, 16.2, 12)],
     f=[seq(tip(5.4, 12, 2.4, 180), tip(18.6, 12, 2.4, 0))],
     apps="PS/AI/PR",
     basis="Photoshop's brush-size preview, dimensioned the way any width is "
           "dimensioned.",
     note="One footprint with its diameter measured across it, the arrowheads "
          "breaking the outline on both sides.\n\n"
          "The measure has to run through the circle. Set underneath it, a ring "
          "above a double-headed width arrow is the universal girth gesture — a "
          "rater called it a condom-size chart and said the crude reading arrived "
          "first. Stood beside the footprint as a vertical bar between two "
          "arrowheads, the pair read as the letters Oi, and at small size that was "
          "the only thing left. What it costs is small size: with the arrowheads gone "
          "the tiny render is a bar across a circle, which is a prohibition sign. That is a "
          "wrong meaning rather than a crude one, and it is the trade taken.\n\n"
          "Drawn as two rings on one centre with a radius struck through them — the "
          "literal picture of a diameter — it was a dartboard with a dart in it, "
          "and at small size the rings merged and the radius became the bar of a "
          "no-entry sign. Concentric rings around a centre are a target whatever is "
          "meant by them, which is the same finding that took the ring off the "
          "brush cursor and the register mark."
)

icon("brush-opacity", "tools", ["alpha", "coverage", "translucent"],
     s=[rect(3.4, 7.0, 17.2, 10.0)],
     f=[seq(rect(3.4, 7.0, 5.7, 10.0), rect(9.1, 7.0, 2.85, 5.0),
            rect(11.95, 12.0, 2.85, 5.0))],
     apps="PS/AI/PR",
     basis="The chequer every editor shows through a partly transparent pixel, which "
           "is also what alpha-lock is drawn from.",
     note="One swatch, solid at one end and down to the chequer at the other. Drawn "
          "as a disc with one half laid down and the other half a broken arc — the "
          "obvious picture of half-committed — it was the dark-mode toggle almost "
          "exactly, and this set has one of those: theme-toggle is a disc with one "
          "half solid and rays on the light side. A split disc belongs to day and "
          "night and cannot be borrowed. The chequer is three cells across and not two: at "
          "two cells it was the same diagonal pair of squares that sits inside "
          "alpha-lock, and a rater called this one a credit card with the chip "
          "knocked out. It runs solid, then chequer, then nothing, across three thirds: "
          "half solid against half chequer left the swatch mostly black with white "
          "notches in it, and a rater read the notches as a bar chart in negative."
)

icon("brush-flow", "tools", ["rate", "load", "delivery"],
     s=[],
     f=[seq(rect(3.4, 5.6, 17.2, 4.2),
            poly([(6.6, 9.8), (9.0, 9.8), (8.4, 15.4), (7.2, 15.4)], close=True),
            poly([(11.2, 9.8), (13.6, 9.8), (13.0, 19.4), (11.8, 19.4)], close=True),
            poly([(15.8, 9.8), (18.2, 9.8), (17.6, 13.2), (16.4, 13.2)], close=True))],
     apps="PS/PR",
     basis="Photoshop's Flow control — how much arrives per pass, not how dark it ends up.",
     note="A loaded stroke with the paint running off it, three runs at three lengths.\n\n"
          "It was a tall box with two rules across it and the bottom third filled, which is a "
          "battery at a third charge and nothing else — a rectangle part-filled along one edge "
          "is a fill gauge before it is anything, the same finding as the eraser and the mask, "
          "and it put this glyph and mask in one batch reading as one object. Redrawn as a "
          "nozzle with drops falling from it, it was accurate to its own note for the first "
          "time and immediately collided with the airbrush, which is also a nozzle with marks "
          "under it; widening the airbrush's spray to separate them made that glyph anatomical. "
          "So the nozzle is gone from this one. There is no emitter in the frame, only what "
          "was delivered."
)


icon("brush-hardness", "tools", ["edge", "falloff", "core"],
     s=[],
     f=[poly([(4.6, 5.4), (11.4, 5.4), (11.4, 7.8), (13.2, 7.8), (13.2, 10.2), (15.0, 10.2),
              (15.0, 12.6), (16.8, 12.6), (16.8, 15.0), (18.6, 15.0), (18.6, 20.2),
              (4.6, 20.2)], close=True)],
     apps="PS/AI/PR",
     basis="Photoshop's hardness preview — a solid core with its edge stepping away from it.",
     note="A solid core with the falloff cut into its own edge as steps.\n\n"
          "Recorded as unresolved. It reads as a descending staircase, which is wrong and "
          "harmless, and it collides with filter-mosaic — whose whole subject is a staircase — "
          "at every size. It is kept because all six forms tried were worse or no better:\n\n"
          "  two footprints, one solid and one a broken ring: a pair of testicles, named twice "
          "by two raters, and a dividing cell once the two were made unequal. Stacked instead "
          "they are a snowman and still anatomical.\n"
          "  a section wedge standing on a baseline: a doorstop, and at 24 pixels the same "
          "small black triangle on a dash as brush-angle. Mirroring the slope did not separate "
          "them, because anything on a heavy baseline at this size is a triangle on a line.\n"
          "  a solid block with shrinking bars beside it: the system volume icon, functionally "
          "identical in a rater\u2019s words, and indistinguishable from layer-opacity.\n"
          "  steps cut symmetrically into both halves of the right edge: a pixelated speaker, "
          "because a solid block with a symmetrical flare on one side is that icon whatever "
          "the flare is made of.\n"
          "  the same steps cut one way only, coarse or fine: a staircase either way. Five "
          "small steps merge at 24 pixels into the same three the coarse version had.\n\n"
          "The trap under all of it is that the brush-parameter family has no vocabulary but "
          "filled dots and outlined rings, and every escape from a circle lands on a shape "
          "some other icon already owns."
)


icon("brush-spacing", "tools", ["stamps", "interval", "gap"],
     s=[circle(5.6, 15.8, 2.6), circle(18.4, 8.2, 2.6)],
     f=[dot(12.0, 12.0, 2.6)],
     apps="PS/PR",
     basis="Photoshop's Spacing setting — a brush is a run of stamps, not a line.",
     note="Three stamps stepping evenly up a run, the middle one just landed. No line "
          "through them and no line under them. Set in a row above a separate rule they were "
          "a traffic light on its side, or the letters OOO underlined; with the rule passed "
          "through their centres instead, the two hollow ones became barred circles and the "
          "pair of them a set of spectacles, with the solid one as a nose. The even step is "
          "what carries the spacing. Distinct from scatter, whose stamps are thrown off a "
          "line that is actually drawn.")

icon("brush-scatter", "tools", ["jitter", "spread", "random"],
     s=[circle(16.0, 6.4, 1.5), circle(19.6, 12.4, 1.2), circle(16.8, 18.0, 1.35),
        circle(6.0, 5.8, 1.0)],
     f=[dot(9.4, 13.2, 2.4)],
     apps="PS/PR",
     basis="Photoshop's Scattering — marks thrown clear of where they were aimed.",
     note="One stamp landed, and four thrown clear of it at uneven distances, mostly "
          "to one side. No path drawn, because every path drawn turned into "
          "something else: on the diagonal with marks above and below it was a "
          "percent sign at every size, dashed or solid, through two drawings; laid "
          "horizontal and dashed, the dashes became a stitched-shut mouth and the "
          "marks around them eyes, and a rater called the glyph a goofy monster "
          "face; drawn as a curve it was a wavy mouth under two eyes, and a face "
          "again. Marks thrown to one side have no axis to hang a face on."
)

icon("brush-angle", "tools", ["rotation", "nib angle", "protractor"],
     s=[line(3.4, 19.4, 20.6, 19.4), line(4.6, 19.4, 16.6, 7.4), arc(4.6, 19.4, 7.6, 315, 360)],
     f=[poly([(16.6, 7.4), (20.0, 5.6), (18.4, 9.0)], close=True)],
     apps="PS/AI/PR",
     basis="Photoshop's brush Angle dial and Illustrator's calligraphic angle.",
     note="An angle read off the baseline, solid where it points.")

icon("brush-roundness", "tools", ["flatten", "squash", "ellipse nib"],
     s=[Path("M10.67,18.1A5,2.2 -30 1,0 19.33,13.1A5,2.2 -30 1,0 10.67,18.1Z",
             [(10.53, 15.6), (19.47, 15.6), (15.0, 12.46), (15.0, 18.74)])],
     f=[circle(8.6, 8.4, 4.0)],
     apps="PS/AI/PR",
     basis="Photoshop's Roundness control — a round footprint compressed on one axis.",
     note="The round footprint, and the same footprint flattened and turned off "
          "the horizontal.\n\n"
          "The tilt is not decoration. A horizontal ellipse is an orifice in every "
          "company it has been put in, and four arrangements produced four "
          "anatomical readings from four raters. Side by side, a ring with a solid "
          "flat lozenge against its edge was a sperm cell. Filled, between a mark "
          "above and a mark below, it was a vulva. Hollowed, with the compression "
          "arrows moved inside it, two solid triangles converging at the centre of "
          "a dark oval was an anus. Stacked under a solid disc — the plainest "
          "before-and-after there is — it was a butthole under a head. Anything "
          "pressing inward on a round outline is an orifice, and a squat horizontal "
          "lens is one on its own, with nothing else in the frame."
)

icon("brush-texture", "tools", ["grain", "tooth", "paper"],
     s=[rect(3.0, 3.0, 18.0, 18.0)],
     f=[poly([(16.1, 12.8), (15.3, 14.2), (14.1, 15.1), (12.8, 16.0), (11.3, 15.7), (9.7, 15.5),
              (9.0, 14.0), (8.1, 12.8), (7.8, 11.2), (8.8, 9.9), (9.7, 8.6), (11.3, 8.4),
              (12.8, 7.9), (14.2, 8.7), (15.0, 10.0), (16.0, 11.2)], close=True)],
     apps="PS/PR",
     basis="Photoshop's Texture setting and Procreate's grain source — a stroke running dry "
           "over the tooth of the paper.",
     note="One footprint, bitten all round by the tooth, inside the swatch of paper that bit "
          "it.\n\n"
          "Ten drawings. The first eight were strokes, and the brush family does not draw "
          "strokes: size is two discs on one centre, hardness a solid core inside a broken "
          "halo, roundness a circle beside an ellipse, spacing three stamps on a run. The "
          "ninth was a footprint, correctly, but drawn as a clean circle beside a ragged one — "
          "which is the hardness glyph's own composition, and it read as a donut next to a "
          "squashed spider. Drawn with twelve vertices at alternating long and short "
          "radii, which is the obvious way to rough up a circle, it was a six-pointed "
          "star and a rater said Star of David. Sixteen vertices at radii that vary a "
          "little and never alternate give a roughened disc with no points on it.\n\n"
          "What the strokes were: spilled rice, and the fourth icon in the set drawn as a "
          "field of scattered dashes — the same picture as Add Noise, Reduce Noise and Grain. "
          "A band with a sawtooth underside was a mouth full of bared teeth, because a row of "
          "points along a solid edge is a jaw. Broken into slivers it was a barcode at even "
          "and uneven widths both, then road markings in perspective. Over a broken shadow it "
          "was a crossed-out signature; over loose hatching a strikethrough on a scribble; "
          "over boxed hatching a hazard barricade; over unboxed hatching claw marks with a "
          "lightning bolt. Alone and ragged on both edges, it was a strip of torn tape."
)

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
     s=[rect(9.6, 2.6, 4.8, 12.6, 2.4), seq(line(9.6, 8.6, 14.4, 8.6), line(9.6, 11.0, 14.4, 11.0))],
     f=[poly([(10.4, 15.2), (13.6, 15.2), (13.0, 18.6), (14.2, 18.6), (14.2, 19.9),
              (9.8, 19.9), (9.8, 18.6), (11.0, 18.6)], close=True)],
     apps="PR",
     basis="Procreate's stylus settings — the barrel and the tip the digitiser reads.",
     note="Stood on the glass it writes on: a slim capsule barrel, one grip band, and a fine "
          "point actually touching the surface line. The surface is what makes it hardware "
          "rather than another stick of colour.")

icon("pressure", "tools", ["force", "hard press", "weight"],
     s=[quad((3.4, 15.8), (12, 21.4), (20.6, 15.8))],
     f=[poly([(8.6, 3.6), (15.4, 3.6), (13.4, 12.0), (12, 20.4), (10.6, 12.0)], close=True)],
     apps="PR",
     basis="Procreate's Pressure curve — force registered at the point of contact.",
     note="A point bearing down hard enough to bow the surface under it, and nothing else. A "
          "stroke swelling thin to thick, the first attempt, is the universal sign for volume; "
          "an arrowhead above it, the second, is the universal sign for download; strain marks "
          "flying off the contact, the third, made it a firework. The dent alone says force.")

_TILT = xf(8.6, 20.2, -58)

icon("tilt", "tools", ["angle", "shading", "lean"],
     s=[rect(2.2, -2.1, 11.0, 4.2, 2.0, t=_TILT), line(6.4, -2.1, 6.4, 2.1, t=_TILT),
        line(3.0, 20.2, 21.0, 20.2), arc(8.6, 20.2, 5.6, -58, 0)],
     f=[poly([(2.2, -1.5), (0.7, -0.7), (0.7, -1.9), (-0.5, -1.9), (-0.5, 1.9), (0.7, 1.9),
              (0.7, 0.7), (2.2, 1.5)], close=True, t=_TILT)],
     apps="PR",
     basis="Procreate's Tilt settings — the lean of the barrel against the surface.",
     note="The same capsule barrel as the stylus, leant over, with the angle it makes against "
          "the surface swept out beneath it.")

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
     s=[poly([(12, 2.8), (14.6, 7.2), (15.2, 9.4), (13.8, 11.2), (10.2, 11.2), (8.8, 9.4),
              (9.4, 7.2)], close=True)],
     f=[poly([(3.4, 13.4), (20.6, 13.4), (20.6, 20.6), (3.4, 20.6)], close=True)],
     apps="PR",
     basis="Procreate's ColorDrop — colour dragged into a closed region, which takes it whole.",
     note="An actual drop, with shoulders, falling into what it will flood. A bare triangle over "
          "a box is a pointed hood on a plinth.")

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
        poly([(4.0, 18.2), (7.4, 7.0), (10.8, 18.2)], close=True)],
     f=[poly([(13.2, 18.2), (16.6, 7.0), (20.0, 18.2)], close=True)],
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
     s=[circle(12, 12, 8.0),
        seq(dashed(12, 4.2, 12, 19.8, 3), dashed(4.2, 12, 19.8, 12, 3))],
     f=[pie(12, 12, 8.0, 180, 270)],
     apps="PR",
     basis="Procreate's Quadrant symmetry — both axes live at once.",
     note="A field quartered by both live axes, with one quarter marked — the mark made in it "
          "appears in all four. Drawn as four wedges arranged round a cross, which was the "
          "previous attempt, it is an Iron Cross.")

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
     s=[seq(line(2.6, 20.6, 21.4, 20.6), line(2.6, 10.6, 21.4, 10.6)),
        seq(line(3.4, 20.6, 19.0, 10.6), line(9.4, 20.6, 19.0, 10.6),
            line(15.0, 20.6, 19.0, 10.6))],
     f=[dot(19.0, 11.0, 1.5)],
     apps="PS/AI/PR",
     basis="Illustrator's Perspective Grid and Procreate's Perspective guide. The horizon is "
           "not decoration: without it the converging lines rise to a knob on a post and the "
           "whole glyph is a gallows.",
     note="Lines converging on a single solid vanishing point.")

icon("guide-isometric", "tools", ["axonometric", "30 degrees", "isometric"],
     s=[poly([(19.3, 16.2), (12, 20.4), (4.7, 16.2), (4.7, 7.8), (12, 3.6), (19.3, 7.8)],
              close=True),
        seq(line(12, 12, 12, 20.4), line(12, 12, 4.7, 7.8), line(12, 12, 19.3, 7.8))],
     f=[],
     apps="AI/PR",
     basis="Procreate's Isometric guide and Illustrator's isometric grids.",
     note="Three axes at thirty degrees, meeting at one origin.")
