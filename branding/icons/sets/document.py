"""Document, canvas & view — the surface itself and how it is looked at.

New/open/save carry the oldest and most stable icon lineage in all of software (the folded
page corner, the folder, the floppy disc) and are kept exactly because their familiarity is
the entire value. View controls (zoom, pan, ruler, grid) come mostly from Photoshop's own
status bar and View menu.
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
    slash,
    square,
    tip,
)

icon("document-new", "document", ["new file", "blank canvas"],
     s=[poly([(5.4, 3.4), (14.6, 3.4), (18.6, 7.4), (18.6, 20.6), (5.4, 20.6)], close=True),
        line(14.6, 3.4, 14.6, 7.4), line(14.6, 7.4, 18.6, 7.4)],
     f=[],
     apps="PS/AI/PR",
     basis="The folded-corner page every New Document dialog uses.",
     note="A blank page with its corner turned down.")

icon("document-open", "document", ["open file"],
     s=[poly([(3.0, 8.6), (3.0, 5.4), (9.0, 5.4), (10.6, 7.4), (18.6, 7.4), (18.6, 8.6)],
             close=True)],
     f=[poly([(3.0, 8.6), (20.6, 8.6), (18.6, 19.4), (5.0, 19.4)], close=True)],
     apps="PS/AI/PR",
     basis="The open folder every Open dialog uses.",
     note="A folder lifted open at its own top edge.")

icon("document-save", "document", ["save file"],
     s=[rect(4.0, 4.0, 16.0, 16.0), rect(7.4, 4.0, 6.0, 5.0)],
     f=[rect(6.4, 12.0, 8.0, 8.0)],
     apps="PS/AI/PR",
     basis="The floppy disc every Save command still uses.",
     note="The disc's shell, label window, and solid write-protect tab.")

icon("document-save-as", "document", ["save copy", "duplicate file"],
     s=[rect(3.0, 5.0, 13.6, 13.6), rect(6.0, 5.0, 5.2, 4.4),
        seq(line(19.0, 15.0, 19.0, 20.6), line(16.2, 17.8, 21.8, 17.8))],
     f=[],
     apps="PS/AI",
     basis="The same disc, with the plus that marks a new copy.",
     note="A disc being written a second time, under a new name.")

icon("document-close", "document", ["close file"],
     s=[poly([(4.6, 3.4), (12.6, 3.4), (16.6, 7.4), (16.6, 20.6), (4.6, 20.6)], close=True),
        line(12.6, 3.4, 12.6, 7.4), line(12.6, 7.4, 16.6, 7.4),
        line(18.0, 15.6, 21.4, 19.0), line(21.4, 15.6, 18.0, 19.0)],
     f=[],
     apps="PS/AI/PR",
     basis="The document page, closed off by the same cross every other close control uses.",
     note="A page struck through by the universal close mark.")

icon("document-revert", "document", ["revert to saved", "discard changes"],
     s=[poly([(5.4, 12.6), (5.4, 3.4), (13.4, 3.4), (17.4, 7.4), (17.4, 12.6)]),
        line(13.4, 3.4, 13.4, 7.4), line(13.4, 7.4, 17.4, 7.4),
        arc(11.4, 16.6, 4.8, 200, 500)],
     f=[tip(15.2, 13.4, 2.2, 55)],
     apps="PS",
     basis="Photoshop's Revert — the saved disc, called back.",
     note="A disc with its last save being pulled back in.")

icon("document-properties", "document", ["file info", "metadata"],
     s=[poly([(5.4, 3.4), (14.6, 3.4), (18.6, 7.4), (18.6, 20.6), (5.4, 20.6)], close=True),
        line(14.6, 3.4, 14.6, 7.4), line(14.6, 7.4, 18.6, 7.4),
        line(8.0, 11.4, 16.0, 11.4), line(8.0, 15.0, 16.0, 15.0), line(8.0, 18.6, 13.0, 18.6)],
     f=[],
     apps="PS/AI",
     basis="Photoshop's File Info.",
     note="A page with the record it carries laid open.")

icon("canvas-size", "document", ["resize canvas", "expand document"],
     s=[rect(7.0, 7.0, 10.0, 10.0), rect(3.4, 3.4, 17.2, 17.2), line(15.4, 15.4, 18.6, 18.6)],
     f=[tip(20.0, 20.0, 2.0, 45)],
     apps="PS",
     basis="Photoshop's Canvas Size — the document, and the new boundary it grows into.",
     note="The current page inside the frame it is about to fill.")

icon("image-size", "document", ["resize image", "resample"],
     s=[rect(3.4, 3.4, 17.2, 17.2), line(3.4, 20.6, 13.0, 11.0)],
     f=[tip(13.0, 11.0, 2.2, -45)],
     apps="PS",
     basis="Photoshop's Image Size — the frame being pulled from its own corner.",
     note="A frame with its own corner being dragged smaller.")

icon("crop", "document", ["trim", "crop tool"],
     s=[poly([(7.0, 2.6), (7.0, 17.0), (21.4, 17.0)]),
        poly([(3.0, 7.0), (17.0, 7.0), (17.0, 21.4)])],
     f=[],
     apps="PS/AI/PR",
     basis="The two overlapping crop brackets every editor uses.",
     note="Two corner brackets marking the region that survives.")

icon("crop-straighten", "document", ["level horizon", "de-skew"],
     s=[rect(3.4, 3.4, 17.2, 17.2), dashed(3.4, 9.6, 20.6, 6.4, 5)],
     f=[],
     apps="PS",
     basis="Photoshop's Straighten — a ruled line laid across a tilted horizon.",
     note="A frame with a measuring line drawn across its own tilt.")

icon("resample", "document", ["interpolation", "pixel resample"],
     s=[rect(3.4, 3.4, 7.6, 7.6), line(7.2, 3.4, 7.2, 11.0), line(3.4, 7.2, 11.0, 7.2)],
     f=[seq(rect(13.0, 3.4, 3.8, 3.8), rect(17.0, 3.4, 3.8, 3.8),
            rect(13.0, 7.4, 3.8, 3.8), rect(17.0, 7.4, 3.8, 3.8))],
     apps="PS",
     basis="Photoshop's resample method preview — samples redrawn onto a new grid.",
     note="A set of columns filled to a new, even measure.")

icon("zoom-in", "document", ["magnify", "increase view"],
     s=[circle(10.2, 10.2, 6.8), line(15.0, 15.0, 20.6, 20.6),
        line(7.0, 10.2, 13.4, 10.2), line(10.2, 7.0, 10.2, 13.4)],
     f=[],
     apps="PS/AI/PR",
     basis="Every editor's zoom-in glass.",
     note="A glass with the plus that grows what is under it.",
     )

icon("zoom-out", "document", ["shrink", "decrease view"],
     s=[circle(10.2, 10.2, 6.8), line(15.0, 15.0, 20.6, 20.6), line(7.0, 10.2, 13.4, 10.2)],
     f=[],
     apps="PS/AI/PR",
     basis="The same glass, with the minus that shrinks what is under it.",
     note="A glass with a single bar across its centre.")

icon("zoom-fit", "document", ["fit to screen", "fit window"],
     s=[rect(3.0, 3.0, 18.0, 18.0),
        seq(line(4.6, 4.6, 8.6, 8.6), line(19.4, 4.6, 15.4, 8.6),
            line(19.4, 19.4, 15.4, 15.4), line(4.6, 19.4, 8.6, 15.4))],
     f=[seq(tip(9.6, 9.6, 2.2, 45), tip(14.4, 9.6, 2.2, 135),
            tip(14.4, 14.4, 2.2, 225), tip(9.6, 14.4, 2.2, 315))],
     apps="PS/AI/PR",
     basis="Photoshop's Fit on Screen.",
     note="A frame held exactly inside the four corners of its window.")

icon("zoom-100", "document", ["actual size", "print size"],
     s=[circle(10.2, 10.2, 6.8), line(15.0, 15.0, 20.6, 20.6),
        line(7.4, 10.2, 9.0, 10.2), line(11.4, 10.2, 13.0, 10.2)],
     f=[],
     apps="PS/AI",
     basis="Photoshop's Actual Pixels — the glass with nothing being magnified.",
     note="The zoom glass with a single point, not a sign, at its centre.")

icon("pan-hand", "document", ["hand tool", "scroll canvas"],
     s=[poly([(8.2, 20.6), (6.0, 15.0), (3.6, 11.0), (5.2, 9.8), (7.6, 12.2), (7.6, 4.6),
              (9.4, 4.6), (9.4, 10.4), (10.4, 10.4), (10.4, 3.4), (12.2, 3.4), (12.2, 10.4),
              (13.2, 10.4), (13.2, 4.4), (15.0, 4.4), (15.0, 10.6), (16.0, 10.6), (16.8, 6.6),
              (18.6, 7.0), (18.0, 14.6), (17.0, 20.6)], close=True)],
     f=[],
     apps="PS/AI/PR",
     basis="Every editor's Hand tool.",
     note="An open hand held flat against the canvas.")

icon("rotate-view", "document", ["rotate canvas view"],
     s=[circle(12, 12, 8.4), arc(12, 12, 8.4, -30, 30)],
     f=[tip(19.28, 8, 2.0, 120)],
     apps="PS",
     basis="Photoshop's Rotate View — the canvas turned without changing the file.",
     note="A dial turned to look at the same page from a new angle.")

icon("rulers", "document", ["measurement guides", "show rulers"],
     s=[rect(3.4, 3.4, 17.2, 17.2),
        seq(line(3.4, 6.4, 6.4, 6.4), line(3.4, 9.4, 5.4, 9.4), line(3.4, 12.4, 6.4, 12.4),
            line(6.4, 3.4, 6.4, 6.4), line(9.4, 3.4, 9.4, 5.4), line(12.4, 3.4, 12.4, 6.4))],
     f=[],
     apps="PS/AI/PR",
     basis="Every editor's rulers, top and left.",
     note="The two graduated edges every canvas is measured against.")

icon("guides", "document", ["ruler guides", "drag out guide"],
     s=[rect(3.4, 3.4, 17.2, 17.2), dashed(3.4, 9.0, 20.6, 9.0, 4), dashed(13.0, 3.4, 13.0, 20.6, 4)],
     f=[],
     apps="PS/AI/PR",
     basis="Guides pulled out from the rulers.",
     note="Two dashed lines laid across a frame, not part of the art.")

icon("grid-overlay", "document", ["pixel grid", "show grid"],
     s=[rect(3.4, 3.4, 17.2, 17.2),
        seq(line(8.0, 3.4, 8.0, 20.6), line(12.6, 3.4, 12.6, 20.6), line(17.2, 3.4, 17.2, 20.6),
            line(3.4, 8.0, 20.6, 8.0), line(3.4, 12.6, 20.6, 12.6), line(3.4, 17.2, 20.6, 17.2))],
     f=[],
     apps="PS/AI/PR",
     basis="Every editor's grid overlay.",
     note="A frame divided into an even field.")

icon("view-actual-colors", "document", ["proof colours", "soft proof"],
     s=[circle(9.0, 9.4, 5.4), circle(15.0, 9.4, 5.4), circle(12.0, 15.0, 5.4)],
     f=[],
     apps="PS",
     basis="Photoshop's Proof Colors — the working view against the output simulation.",
     note="Two fields held side by side, neither one favoured.")

icon("view-cmyk-preview", "document", ["gamut warning", "print preview"],
     s=[circle(12, 12, 6.6), line(12, 2.6, 12, 4.6), line(12, 19.4, 12, 21.4),
        line(2.6, 12, 4.6, 12), line(19.4, 12, 21.4, 12)],
     f=[],
     apps="PS",
     basis="Photoshop's Gamut Warning — a ring where a colour cannot be printed.",
     note="A ring marking the limit of what can be reproduced.")

icon("full-screen", "document", ["immersive mode", "hide panels"],
     s=[seq(poly([(3.4, 7.4), (3.4, 3.4), (7.4, 3.4)]), poly([(16.6, 3.4), (20.6, 3.4), (20.6, 7.4)]),
            poly([(20.6, 16.6), (20.6, 20.6), (16.6, 20.6)]),
            poly([(7.4, 20.6), (3.4, 20.6), (3.4, 16.6)]))],
     f=[],
     apps="PS/AI/PR",
     basis="Every editor's full-screen toggle.",
     note="Four corner marks with nothing held between them.")

icon("split-view", "document", ["compare", "before after"],
     s=[rect(3.4, 3.4, 17.2, 17.2), line(12, 3.4, 12, 20.6)],
     f=[rect(12, 3.4, 8.6, 17.2)],
     apps="PS/PR",
     basis="Procreate's before/after slider and Photoshop's split preview.",
     note="One frame, half of it showing the result.")

icon("multi-window", "document", ["new window", "second view"],
     s=[square(9.4, 9.4, 9.0), square(6.6, 6.6, 9.0)],
     f=[],
     apps="PS/AI",
     basis="Photoshop's New Window for the same document.",
     note="A second frame opened onto the same page.")

icon("thumbnails", "document", ["gallery", "grid view"],
     s=[seq(rect(3.4, 5.6, 5.2, 5.2), rect(9.4, 5.6, 5.2, 5.2), rect(15.4, 5.6, 5.2, 5.2),
            rect(3.4, 13.2, 5.2, 5.2), rect(9.4, 13.2, 5.2, 5.2), rect(15.4, 13.2, 5.2, 5.2))],
     f=[],
     apps="PS/PR",
     basis="Procreate's Gallery grid.",
     note="Four pages laid out as a set to choose from.")

icon("bleed-marks", "document", ["print bleed", "trim marks"],
     s=[rect(6.6, 6.6, 10.8, 10.8),
        seq(line(3.4, 6.6, 5.4, 6.6), line(3.4, 17.4, 5.4, 17.4),
            line(18.6, 6.6, 20.6, 6.6), line(18.6, 17.4, 20.6, 17.4),
            line(6.6, 3.4, 6.6, 5.4), line(17.4, 3.4, 17.4, 5.4),
            line(6.6, 18.6, 6.6, 20.6), line(17.4, 18.6, 17.4, 20.6))],
     f=[],
     apps="AI",
     basis="Illustrator's print bleed and crop marks.",
     note="A trim line with the register marks a printer cuts to.")
