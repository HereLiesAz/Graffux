"""Type — setting text as an object.

The letterform A carries most of Illustrator's type flyout on its own (point type, area
type, type on a path). Paragraph controls borrow the universal ragged-lines-of-text motif;
character controls stay numeric and literal (the A/V pair for kerning, the two-A's for size).
"""

from kit import (
    arc,
    circle,
    dashed,
    dot,
    icon,
    line,
    poly,
    quad,
    rect,
    seq,
    smooth,
    square,
    tip,
)


def glyph_a(cx, top, h, w):
    """A minimal capital A, apex at (cx, top), standing h tall and w wide at the foot."""
    return seq(line(cx, top, cx - w / 2, top + h), line(cx, top, cx + w / 2, top + h),
                line(cx - w * 0.24, top + h * 0.62, cx + w * 0.24, top + h * 0.62))


icon("type", "type", ["text tool", "add text"],
     s=[glyph_a(11.0, 3.4, 15.0, 11.0)],
     f=[],
     apps="PS/AI/PR",
     basis="Every editor's Type tool — a plain capital A.",
     note="A single letterform, standing for the whole alphabet.")

icon("type-point", "type", ["point text", "click to type"],
     s=[glyph_a(11.0, 4.0, 13.6, 10.0)],
     f=[dot(4.0, 20.0, 1.3)],
     apps="AI",
     basis="Illustrator's Point Type — text anchored at a single click.",
     note="A letter growing from one fixed point.")

icon("type-area", "type", ["area text", "text in shape"],
     s=[rect(3.4, 3.4, 17.2, 17.2),
        seq(line(5.8, 7.4, 18.2, 7.4), line(5.8, 11.0, 18.2, 11.0),
            line(5.8, 14.6, 14.2, 14.6))],
     f=[],
     apps="AI/PS",
     basis="Illustrator's Area Type — text poured into a bound shape.",
     note="Text set to the width of a frame, ragged at the last line.")

icon("type-path", "type", ["text on path", "curved type"],
     s=[arc(12, 15.4, 9.2, 195, 345)],
     f=[dot(4.4, 12.6, 1.2)],
     apps="AI/PS",
     basis="Illustrator's Type on a Path.",
     note="A path with one letter riding along it.")

icon("type-vertical", "type", ["vertical text", "top to bottom"],
     s=[line(12, 3.4, 12, 20.6)],
     f=[seq(square(12, 6.6, 2.4), square(12, 12.0, 2.4), square(12, 17.4, 2.4))],
     apps="PS/AI",
     basis="Photoshop and Illustrator's vertical type orientation.",
     note="Characters stacked down a single spine.")

icon("type-mask", "type", ["type selection", "text as selection"],
     s=[dashed(11.0, 3.4, 4.6, 18.4, 4), dashed(11.0, 3.4, 17.4, 18.4, 4),
        dashed(6.9, 12.2, 15.1, 12.2, 3)],
     f=[],
     apps="PS",
     basis="Photoshop's Horizontal Type Mask — a letterform that becomes a selection.",
     note="A letter drawn as marching ants, since it is a selection, not ink.")

icon("type-warp", "type", ["distort text", "arc text"],
     s=[rect(3.4, 3.4, 17.2, 17.2), arc(12, 20.0, 10.4, 200, 340)],
     f=[],
     apps="PS/AI",
     basis="Photoshop's Warp Text.",
     note="A frame with the baseline bent inside it.")

icon("font-family", "type", ["typeface", "font picker"],
     s=[seq(line(4.4, 20.0, 4.4, 4.0), line(4.4, 4.0, 9.0, 4.0), line(4.4, 12.0, 8.0, 12.0)),
        seq(line(14.0, 20.0, 17.6, 4.4), line(20.6, 20.0, 17.6, 4.4), line(15.2, 15.4, 20.0, 15.4))],
     f=[],
     apps="PS/AI/PR",
     basis="A serif F beside a sans A — the two families every font picker distinguishes.",
     note="Two letterforms in two different faces.")

icon("font-size", "type", ["point size", "text scale"],
     s=[glyph_a(6.4, 8.6, 11.6, 8.0)],
     f=[glyph_a(16.6, 4.4, 15.6, 10.0)],
     apps="PS/AI/PR",
     basis="The small-A/large-A pair every size control uses.",
     note="The same letter at two sizes, side by side.")

icon("font-weight", "type", ["bold", "font weight axis"],
     s=[glyph_a(7.4, 6.4, 12.0, 7.6)],
     f=[glyph_a(17.0, 4.4, 15.0, 10.0)],
     apps="PS/AI",
     basis="A light A beside a bold one.",
     note="The same letter, thin then heavy.")

icon("type-bold", "type", ["strong", "b"],
     s=[],
     f=[poly([(6.4, 4.0), (13.0, 4.0), (16.6, 6.8), (16.6, 9.6), (14.6, 11.2), (17.2, 13.0),
              (17.2, 16.6), (13.4, 20.0), (6.4, 20.0)], close=True)],
     apps="PS/AI/PR",
     basis="The solid B every bold toggle in every text editor uses.",
     note="A single heavy capital.")

icon("type-italic", "type", ["oblique", "i"],
     s=[line(11.0, 4.0, 15.6, 4.0), line(8.4, 20.0, 13.0, 20.0), line(14.4, 4.0, 9.6, 20.0)],
     f=[],
     apps="PS/AI/PR",
     basis="The slanted I every italic toggle uses.",
     note="A single slanted stroke with its serifs.")

icon("type-underline", "type", ["u"],
     s=[seq(line(6.6, 4.0, 6.6, 12.4), line(17.4, 4.0, 17.4, 12.4)),
        arc(12, 12.4, 5.4, 0, 180), line(4.4, 20.0, 19.6, 20.0)],
     f=[],
     apps="PS/AI/PR",
     basis="The underlined U every underline toggle uses.",
     note="A U with its own rule struck beneath it.")

icon("type-strikethrough", "type", ["s", "strike"],
     s=[smooth([(7.0, 5.4), (17.0, 4.4), (17.0, 8.4), (7.0, 12.0), (7.0, 16.0), (17.0, 18.6)]),
        line(3.4, 12, 20.6, 12)],
     f=[],
     apps="PS/AI",
     basis="The struck S every strikethrough toggle uses.",
     note="An S with the rule that crosses it out.")

icon("type-superscript", "type", ["exponent", "raised"],
     s=[glyph_a(9.0, 8.6, 12.0, 9.0)],
     f=[],
     apps="PS/AI",
     basis="A baseline letter with a small mark raised above it.",
     note="A letter with a second, smaller mark set high.",
     )

icon("type-subscript", "type", ["index", "lowered"],
     s=[glyph_a(9.0, 3.4, 12.0, 9.0)],
     f=[dot(18.0, 18.6, 1.1)],
     apps="PS/AI",
     basis="A baseline letter with a small mark dropped below it.",
     note="A letter with a second, smaller mark set low.")

icon("type-allcaps", "type", ["uppercase", "caps lock"],
     s=[glyph_a(7.4, 6.0, 13.0, 8.6)],
     f=[glyph_a(16.6, 6.0, 13.0, 8.6)],
     apps="PS/AI",
     basis="Two capitals of matched height — case with nothing lower to compare against.",
     note="Two letters at one uniform height.")

icon("kerning", "type", ["letter spacing pair", "AV"],
     s=[glyph_a(8.6, 5.4, 13.2, 9.0)],
     f=[poly([(15.2, 18.6), (18.6, 5.4), (22.0, 18.6)], close=True)],
     apps="PS/AI",
     basis="The AV pair every kerning control uses to show the gap between two letters.",
     note="Two letterforms whose gap is the thing being set.")

icon("tracking", "type", ["letter spacing all", "expand"],
     s=[seq(line(3.4, 12, 6.0, 12), line(9.4, 12, 12.0, 12), line(15.4, 12, 18.0, 12))],
     f=[dot(21.0, 12, 1.3)],
     apps="PS/AI/PR",
     basis="Photoshop's Tracking — uniform space added between every pair.",
     note="Marks pushed apart by an equal amount, all at once.")

icon("leading", "type", ["line spacing", "row gap"],
     s=[seq(line(4.0, 6.4, 20.0, 6.4), line(4.0, 17.6, 20.0, 17.6))],
     f=[seq(tip(12, 9.0, 1.8, 90), tip(12, 15.0, 1.8, 270))],
     apps="PS/AI",
     basis="Photoshop's Leading — the gap between text rows.",
     note="Two rows with the gap between them held by opposing arrows.")

icon("paragraph-align-left", "type", ["justify left"],
     s=[seq(line(3.4, 6.0, 20.6, 6.0), line(3.4, 11.0, 15.0, 11.0),
            line(3.4, 16.0, 18.0, 16.0), line(3.4, 21.0, 12.0, 21.0))],
     f=[],
     apps="PS/AI/PR",
     basis="The ragged-right paragraph glyph every editor uses for left alignment.",
     note="Rules flush on the left, ragged on the right.")

icon("paragraph-align-center", "type", ["centre text"],
     s=[seq(line(4.4, 6.0, 19.6, 6.0), line(7.4, 11.0, 16.6, 11.0),
            line(6.4, 16.0, 17.6, 16.0), line(9.4, 21.0, 14.6, 21.0))],
     f=[],
     apps="PS/AI/PR",
     basis="The centred paragraph glyph.",
     note="Rules centred, ragged on both ends.")

icon("paragraph-align-right", "type", ["justify right"],
     s=[seq(line(3.4, 6.0, 20.6, 6.0), line(9.0, 11.0, 20.6, 11.0),
            line(6.0, 16.0, 20.6, 16.0), line(12.0, 21.0, 20.6, 21.0))],
     f=[],
     apps="PS/AI/PR",
     basis="The ragged-left paragraph glyph, for right alignment.",
     note="Rules flush on the right, ragged on the left.")

icon("paragraph-justify", "type", ["full justify"],
     s=[seq(line(3.4, 6.0, 20.6, 6.0), line(3.4, 11.0, 20.6, 11.0),
            line(3.4, 16.0, 20.6, 16.0), line(3.4, 21.0, 13.0, 21.0))],
     f=[],
     apps="PS/AI/PR",
     basis="The fully justified paragraph glyph — flush on both sides but the last row.",
     note="Rules flush on both ends, the final row left short.")

icon("paragraph-indent", "type", ["first line indent"],
     s=[seq(line(8.0, 6.0, 20.6, 6.0), line(3.4, 11.0, 20.6, 11.0), line(3.4, 16.0, 20.6, 16.0))],
     f=[tip(6.6, 6.0, 1.6, 0)],
     apps="PS/AI",
     basis="A paragraph glyph with its first line pushed in from an arrow.",
     note="One row pushed in from the rest by a visible step.")

icon("glyphs-panel", "type", ["special characters", "ligatures"],
     s=[rect(3.4, 3.4, 17.2, 17.2)],
     f=[glyph_a(12, 7.4, 10.0, 7.0)],
     apps="PS/AI",
     basis="Illustrator's Glyphs panel — one character held up in its own cell.",
     note="A single glyph shown in the cell it is picked from.")

icon("find-replace-text", "type", ["search text", "swap words"],
     s=[circle(9.6, 9.6, 5.6), line(13.6, 13.6, 18.6, 18.6)],
     f=[],
     apps="PS/AI",
     basis="Find/Change — the search glass, turned on a word instead of a file.",
     note="A search glass, held over running text.")

icon("spell-check", "type", ["proofing", "typo underline"],
     s=[glyph_a(11.0, 4.0, 12.0, 8.6),
        seq(line(4.0, 20.0, 6.0, 18.6), line(7.0, 20.0, 9.0, 18.6),
            line(10.0, 20.0, 12.0, 18.6), line(13.0, 20.0, 15.0, 18.6),
            line(16.0, 20.0, 18.0, 18.6))],
     f=[],
     apps="PS/AI",
     basis="The red squiggle every spell-checker draws under a flagged word.",
     note="A letter sitting over the wavy underline that flags it.")

icon("baseline-shift", "type", ["raise lower glyph"],
     s=[line(4.0, 12, 20.0, 12), glyph_a(12, 4.4, 10.0, 7.0)],
     f=[tip(20.0, 9.0, 1.6, 90)],
     apps="PS/AI",
     basis="Photoshop's Baseline Shift.",
     note="A letter lifted clear of the line it would otherwise sit on.")

icon("text-color", "type", ["font colour", "fill text"],
     s=[glyph_a(11.0, 3.4, 13.0, 9.6)],
     f=[rect(3.4, 18.6, 17.2, 2.6)],
     apps="PS/AI/PR",
     basis="The letter-over-a-colour-bar every text colour control uses.",
     note="A letter standing on the colour it will be filled with.")

icon("text-outline-convert", "type", ["create outlines", "text to shape"],
     s=[glyph_a(8.0, 5.4, 13.2, 9.0)],
     f=[glyph_a(17.0, 5.4, 13.2, 9.0)],
     apps="AI",
     basis="Illustrator's Create Outlines — a live letter beside its converted, solid twin.",
     note="One editable letter and one converted to a fixed shape.")
