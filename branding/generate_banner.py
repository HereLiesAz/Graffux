#!/usr/bin/env python3
"""
Builds the Graffux Play Store feature graphic (1024x500) from branding/graffux-icon.svg.

Design: "Controlled Bleed" (see DESIGN-PHILOSOPHY.md). A rigid measurement system — hairline grid,
ruler ticks, registration crosses, coordinate annotations — interrupted by exactly one act of
disobedience: the mark's white drip, which falls out of the logo and is caught by the rule below it.
The instrument measures the spill. That tension is the app.

Rendered at 3x and downsampled, which is what keeps the hairlines and micro-type crisp.
"""

from PIL import Image, ImageDraw, ImageFont
import cairosvg
import io
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
FONTS = "/root/.claude/skills/canvas-design/canvas-fonts"

W, H = 1024, 500
S = 3  # supersample

INK = (0x14, 0x16, 0x1D)
CYAN = (0x00, 0xE0, 0xD0)
MAGENTA = (0xFF, 0x2D, 0x78)
WHITE = (0xFF, 0xFF, 0xFF)
# The measurement system sits just above the ground — present, never competing.
RULE = (0x2A, 0x2E, 0x3A)
RULE_BRIGHT = (0x3C, 0x42, 0x52)
MUTED = (0x6B, 0x74, 0x88)


def font(name, size):
    return ImageFont.truetype(os.path.join(FONTS, name), size * S)


def logo_transparent(px):
    """The mark with its background rect stripped, so it sits on the banner's own ground."""
    svg = open(os.path.join(HERE, "graffux-icon.svg")).read()
    svg = re.sub(r'<path[^>]*class="a"[^>]*/>', "", svg)
    png = cairosvg.svg2png(bytestring=svg.encode(), output_width=px, output_height=px)
    return Image.open(io.BytesIO(png)).convert("RGBA")


def tracked(draw, xy, text, fnt, fill, tracking):
    """Letter-spaced text. Returns the width drawn."""
    x, y = xy
    for ch in text:
        draw.text((x, y), ch, font=fnt, fill=fill)
        x += draw.textlength(ch, font=fnt) + tracking
    return x - xy[0] - tracking


def tracked_width(draw, text, fnt, tracking):
    return sum(draw.textlength(c, font=fnt) + tracking for c in text) - tracking


def build():
    img = Image.new("RGB", (W * S, H * S), INK)
    d = ImageDraw.Draw(img, "RGBA")

    M = 56 * S              # margin
    baseline_y = 416 * S    # the rule that catches the drip
    hair = max(1, S // 2)

    # ── The system: a hairline field, dense enough to read as measured, faint enough to recede ──
    step = 16 * S
    for x in range(M, W * S - M + 1, step):
        d.line([(x, M), (x, H * S - M)], fill=(*RULE, 42), width=hair)
    for y in range(M, H * S - M + 1, step):
        d.line([(M, y), (W * S - M, y)], fill=(*RULE, 42), width=hair)

    # Frame — the survey's outer bound.
    d.rectangle([M, M, W * S - M, H * S - M], outline=(*RULE_BRIGHT, 200), width=hair)

    # ── The mark ────────────────────────────────────────────────────────────────────────────
    logo_px = 258 * S
    logo = logo_transparent(logo_px)
    lx, ly = 104 * S, 108 * S
    img.paste(logo, (lx, ly), logo)

    # The drip's plumb line: the one uncontrolled gesture, extended until the instrument catches it.
    # x is the drip's own axis (0.655 across the mark), taken from the icon's geometry.
    drip_x = lx + int(logo_px * 0.655)
    drip_bottom = ly + int(logo_px * 0.735)
    d.line([(drip_x, drip_bottom), (drip_x, baseline_y)], fill=(*WHITE, 62), width=hair)
    # Caught: the tick where the spill meets the rule.
    d.line([(drip_x, baseline_y - 7 * S), (drip_x, baseline_y + 7 * S)], fill=(*WHITE, 235), width=S)

    # ── Wordmark ────────────────────────────────────────────────────────────────────────────
    f_word = font("Outfit-Bold.ttf", 88)
    f_mono = font("GeistMono-Regular.ttf", 12)
    f_micro = font("GeistMono-Regular.ttf", 9)

    wx = 432 * S
    word_y = 178 * S
    track = 5 * S
    tracked(d, (wx, word_y), "GRAFFUX", f_word, WHITE, track)
    word_w = tracked_width(d, "GRAFFUX", f_word, track)

    # Rule under the wordmark, terminating the type block at the same measure.
    ruled_y = word_y + 108 * S
    d.line([(wx, ruled_y), (wx + int(word_w), ruled_y)], fill=(*RULE_BRIGHT, 255), width=hair)

    # Two chromatic voices, opposed, as index rather than ornament.
    d.rectangle([wx, ruled_y - 3 * S, wx + 34 * S, ruled_y], fill=MAGENTA)
    d.rectangle([wx + 38 * S, ruled_y - 3 * S, wx + 72 * S, ruled_y], fill=CYAN)

    tracked(d, (wx, ruled_y + 20 * S), "PAINT ENGINE / VECTOR PRECISION", f_mono, MUTED, 2.4 * S)

    # ── The rule, and its accumulation of ticks ─────────────────────────────────────────────
    d.line([(M, baseline_y), (W * S - M, baseline_y)], fill=(*RULE_BRIGHT, 255), width=hair)
    span = W * S - 2 * M
    divisions = 96
    for i in range(divisions + 1):
        x = M + span * i / divisions
        major = i % 8 == 0
        length = (11 if major else 5) * S
        d.line([(x, baseline_y), (x, baseline_y + length)],
               fill=(*RULE_BRIGHT, 255) if major else (*RULE, 220), width=hair)

    # The rule earns its place by measuring the composition itself: an index tick in each voice,
    # dropped from the left edge of the mark and the left edge of the wordmark.
    for x, colour in ((lx, MAGENTA), (wx, CYAN)):
        d.line([(x, baseline_y - 6 * S), (x, baseline_y + 17 * S)], fill=colour, width=hair)

    # ── Instrumentation: located, indexed, never explanatory ────────────────────────────────
    top_y = M - 20 * S
    tracked(d, (M, top_y), "FIG. 01 — THE MARK", f_micro, MUTED, 1.6 * S)
    label = "V 1.37"
    tracked(d, (W * S - M - tracked_width(d, label, f_micro, 1.6 * S), top_y), label, f_micro, MUTED, 1.6 * S)

    bot_y = H * S - M + 10 * S
    tracked(d, (M, bot_y), "#FF2D78 · #00E0D0 · #14161D", f_micro, MUTED, 1.6 * S)
    tag = "HERELIESAZ / GRAFFUX"
    tracked(d, (W * S - M - tracked_width(d, tag, f_micro, 1.6 * S), bot_y), tag, f_micro, MUTED, 1.6 * S)

    # Registration crosses at the survey's corners.
    r = 5 * S
    for cx, cy in [(M, M), (W * S - M, M), (M, H * S - M), (W * S - M, H * S - M)]:
        d.line([(cx - r, cy), (cx + r, cy)], fill=(*RULE_BRIGHT, 255), width=hair)
        d.line([(cx, cy - r), (cx, cy + r)], fill=(*RULE_BRIGHT, 255), width=hair)

    return img.resize((W, H), Image.LANCZOS)


if __name__ == "__main__":
    out = os.path.join(HERE, "banner-1024x500.png")
    build().save(out)
    print("wrote", out)
