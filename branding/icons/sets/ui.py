"""Interface & system — the shell: navigation, status, files, collaboration.

The chrome around the canvas: what AzNavRail hosts, what a settings screen needs, what a
share sheet needs. Kept deliberately plain — these are seen hundreds of times a session and
must never compete with the art for attention.
"""

from kit import (
    Path,
    arc,
    circle,
    dot,
    ellipse,
    icon,
    line,
    pie,
    poly,
    rays,
    rect,
    seq,
    slash,
    square,
    tip,
)

icon("menu", "ui", ["hamburger", "nav rail", "more"],
     s=[seq(line(4.0, 7.0, 20.0, 7.0), line(4.0, 12.0, 20.0, 12.0), line(4.0, 17.0, 20.0, 17.0))],
     f=[],
     apps="PS/AI/PR",
     basis="The universal hamburger menu.",
     note="Three equal bars.")

icon("close", "ui", ["x", "dismiss", "cancel"],
     s=[line(4.6, 4.6, 19.4, 19.4), line(19.4, 4.6, 4.6, 19.4)],
     f=[],
     apps="PS/AI/PR",
     basis="The universal close X.",
     note="Two strokes crossing at the centre.")

icon("back", "ui", ["navigate back", "previous screen"],
     s=[poly([(14.6, 4.6), (7.4, 12), (14.6, 19.4)])],
     f=[],
     apps="PS/AI/PR",
     basis="The universal back chevron.",
     note="An open chevron pointing the way back.")

icon("forward", "ui", ["navigate forward", "next screen"],
     s=[poly([(9.4, 4.6), (16.6, 12), (9.4, 19.4)])],
     f=[],
     apps="PS/AI/PR",
     basis="The back chevron's mirror.",
     note="An open chevron pointing the way on.")

icon("chevron-down", "ui", ["expand", "dropdown"],
     s=[poly([(5.0, 9.4), (12, 16.6), (19.0, 9.4)])],
     f=[],
     apps="PS/AI/PR",
     basis="The universal expand chevron.",
     note="An open chevron pointing at what it reveals.")

icon("chevron-up", "ui", ["collapse", "close dropdown"],
     s=[poly([(5.0, 14.6), (12, 7.4), (19.0, 14.6)])],
     f=[],
     apps="PS/AI/PR",
     basis="The collapse chevron.",
     note="The same chevron, folded the other way.")

icon("more-horizontal", "ui", ["overflow menu", "kebab horizontal"],
     s=[seq(dot(5.4, 12, 1.5), dot(12, 12, 1.5), dot(18.6, 12, 1.5))],
     f=[],
     apps="PS/AI/PR",
     basis="The universal horizontal overflow menu.",
     note="Three equal marks in a row.")

icon("more-vertical", "ui", ["overflow menu", "kebab vertical"],
     s=[seq(dot(12, 5.4, 1.5), dot(12, 12, 1.5), dot(12, 18.6, 1.5))],
     f=[],
     apps="PS/AI/PR",
     basis="The vertical twin of the overflow menu.",
     note="Three equal marks in a column.")

icon("search", "ui", ["find", "magnifier"],
     s=[circle(10.2, 10.2, 6.8), line(15.0, 15.0, 20.6, 20.6)],
     f=[],
     apps="PS/AI/PR",
     basis="The universal search glass.",
     note="A glass held over the field it searches.")

icon("settings", "ui", ["preferences", "gear"],
     s=[circle(12, 12, 4.0),
        Path("M12,3.2L14.2,3.6L15,6.2L17.2,7.1L19.5,5.8L21,7.9L19.4,9.9L19.8,12.2"
             "L21.8,13.6L20.7,16L18.2,15.6L16.6,17.3L17,19.8L14.7,20.8L13.2,18.7"
             "L10.9,18.8L9.5,20.8L7.2,19.7L7.7,17.2L6.1,15.5L3.6,15.8L2.7,13.4"
             "L4.8,12L4.5,9.7L2.6,8.2L3.9,5.9L6.3,6.5L8.1,4.9L7.8,2.4Z",
             [(2.6, 2.4), (21.8, 20.8)])],
     f=[],
     apps="PS/AI/PR",
     basis="The universal settings gear.",
     note="A ring with four solid teeth set square around it.")

icon("info", "ui", ["about", "help detail"],
     s=[circle(12, 12, 8.4), line(12, 11.0, 12, 16.4)],
     f=[dot(12, 8.0, 1.1)],
     apps="PS/AI/PR",
     basis="The universal info glyph.",
     note="A ring holding a lower-case i.")

icon("help", "ui", ["question", "support"],
     s=[circle(12, 12, 8.4), arc(12, 10.0, 2.6, -90, 140)],
     f=[dot(12, 15.6, 1.1)],
     apps="PS/AI/PR",
     basis="The universal help question mark.",
     note="A ring holding a plain question mark.")

icon("warning", "ui", ["caution", "alert"],
     s=[poly([(12, 3.4), (20.6, 19.4), (3.4, 19.4)], close=True), line(12, 9.4, 12, 14.6)],
     f=[dot(12, 17.0, 1.0)],
     apps="PS/AI/PR",
     basis="The universal warning triangle.",
     note="A triangle holding an exclamation mark.")

icon("error", "ui", ["failure", "problem"],
     s=[circle(12, 12, 8.4), line(8.6, 8.6, 15.4, 15.4), line(15.4, 8.6, 8.6, 15.4)],
     f=[],
     apps="PS/AI/PR",
     basis="The universal error glyph.",
     note="A ring holding a plain cross.")

icon("success", "ui", ["done", "confirmed"],
     s=[circle(12, 12, 8.4), poly([(8.0, 12.4), (10.8, 15.4), (16.4, 9.0)])],
     f=[],
     apps="PS/AI/PR",
     basis="The universal success check.",
     note="A ring holding a plain check.")

icon("notification", "ui", ["bell", "alerts"],
     s=[poly([(7.0, 16.0), (7.0, 10.4), (9.4, 5.4), (14.6, 5.4), (17.0, 10.4), (17.0, 16.0)],
             close=True), arc(12, 16.0, 2.6, 0, 180)],
     f=[dot(19.4, 6.0, 1.5)],
     apps="PS/AI/PR",
     basis="The universal notification bell.",
     note="A bell with one new mark at its shoulder.")

icon("share", "ui", ["send", "export to app"],
     s=[seq(line(12, 3.4, 12, 14.6), poly([(8.4, 7.0), (12, 3.4), (15.6, 7.0)])),
        poly([(5.4, 12.0), (5.4, 20.6), (18.6, 20.6), (18.6, 12.0)])],
     f=[],
     apps="PS/AI/PR",
     basis="The universal share glyph — an arrow lifting out of a tray.",
     note="A tray with its contents rising clear of it.")

icon("download", "ui", ["save to device", "export local"],
     s=[seq(line(12, 3.4, 12, 14.6), poly([(8.4, 11.0), (12, 14.6), (15.6, 11.0)])),
        poly([(5.4, 17.4), (5.4, 20.6), (18.6, 20.6), (18.6, 17.4)])],
     f=[],
     apps="PS/AI/PR",
     basis="The universal download glyph, share's mirror.",
     note="An arrow settling into the tray, rather than leaving it.")

icon("upload", "ui", ["import", "add file"],
     s=[seq(line(12, 17.0, 12, 4.4), poly([(8.4, 8.0), (12, 4.4), (15.6, 8.0)])),
        poly([(5.4, 14.0), (5.4, 20.6), (18.6, 20.6), (18.6, 14.0)])],
     f=[],
     apps="PS/AI/PR",
     basis="The download glyph, upside down.",
     note="An arrow lifting up and out, from underneath a tray.")

icon("link-external", "ui", ["open in browser", "outbound"],
     s=[poly([(5.4, 8.0), (5.4, 18.6), (16.0, 18.6), (16.0, 13.0)]),
        seq(line(11.0, 13.0, 19.4, 4.6), poly([(13.4, 4.6), (19.4, 4.6), (19.4, 10.6)]))],
     f=[],
     apps="PS/AI",
     basis="The universal external-link glyph.",
     note="A frame with an arrow already clear of its corner.")

icon("copy-link", "ui", ["copy url", "share link"],
     s=[arc(9.0, 15.0, 4.2, -45, 225), arc(15.0, 9.0, 4.2, 135, 405)],
     f=[],
     apps="PS/AI",
     basis="The link-chain glyph, used here for a copyable URL.",
     note="Two interlocked rings.")

icon("collaborators", "ui", ["shared users", "team"],
     s=[circle(15.4, 8.0, 3.2), arc(15.4, 19.6, 5.8, 180, 360)],
     f=[seq(circle(8.0, 8.0, 3.2), pie(8.0, 19.6, 5.8, 180, 360))],
     apps="PS/AI",
     basis="Every editor's shared-users glyph.",
     note="One held figure with a second person beside it.")

icon("comment", "ui", ["annotation", "leave a note"],
     s=[poly([(3.4, 4.0), (20.6, 4.0), (20.6, 15.0), (10.0, 15.0), (6.0, 19.0), (6.0, 15.0),
              (3.4, 15.0)], close=True)],
     f=[],
     apps="PS/AI",
     basis="Every editor's comment/annotation bubble.",
     note="A speech bubble with its own pointed tail.")

icon("cloud-sync", "ui", ["backup", "cloud save"],
     s=[poly([(6.6, 16.0), (5.0, 16.0), (5.0, 11.6), (7.6, 9.4), (9.8, 10.4), (12.4, 7.0),
              (16.6, 8.6), (17.4, 11.6), (19.0, 12.4), (19.0, 16.0), (17.4, 16.0)], close=True)],
     f=[],
     apps="PS/PR",
     basis="Every editor's cloud-save glyph.",
     note="A cloud, holding the document that lives inside it.")

icon("cloud-offline", "ui", ["no connection", "local only"],
     s=[poly([(6.6, 16.0), (5.0, 16.0), (5.0, 11.6), (7.6, 9.4), (9.8, 10.4), (12.4, 7.0),
              (16.6, 8.6), (17.4, 11.6), (19.0, 12.4), (19.0, 16.0), (17.4, 16.0)], close=True),
        slash(12, 12, 9.4)],
     f=[],
     apps="PS/PR",
     basis="The same cloud, struck through.",
     note="The cloud with the mark that says it cannot be reached.")

icon("account", "ui", ["profile", "user"],
     s=[circle(12, 8.4, 4.0), arc(12, 21.0, 7.6, 180, 360)],
     f=[],
     apps="PS/AI/PR",
     basis="The universal person glyph.",
     note="A head over solid shoulders.")

icon("subscription", "ui", ["upgrade", "pro badge"],
     s=[poly([(4.0, 9.4), (8.0, 4.6), (12, 9.4), (16.0, 4.6), (20.0, 9.4), (18.0, 18.6),
              (6.0, 18.6)], close=True)],
     f=[dot(12, 12.4, 1.4)],
     apps="new",
     note="The crown every upgrade badge uses.")

icon("theme-toggle", "ui", ["dark mode", "light mode"],
     s=[circle(12, 12, 8.4)],
     f=[pie(12, 12, 8.4, 90, 270)],
     apps="PS/AI/PR",
     basis="The universal light/dark split disc.",
     note="One disc, half in each mode.")

icon("language", "ui", ["locale", "translate"],
     s=[circle(12, 12, 8.4), line(3.6, 12, 20.4, 12), ellipse(12, 12, 3.6, 8.4)],
     f=[],
     apps="PS/AI/PR",
     basis="The universal globe glyph.",
     note="A globe with one meridian and its equator.")

icon("keyboard-shortcut", "ui", ["hotkeys", "key command"],
     s=[rect(3.0, 7.4, 18.0, 9.2, 1.4),
        seq(dot(6.6, 12.0, 0.5), dot(9.8, 12.0, 0.5), dot(13.0, 12.0, 0.5), dot(16.2, 12.0, 0.5))],
     f=[],
     apps="PS/AI",
     basis="Every editor's keyboard-shortcut reference.",
     note="A key row, standing for the whole board.")

icon("brush-cursor", "ui", ["custom cursor", "tool preview"],
     s=[circle(12, 12, 6.4),
        seq(line(12, 2.6, 12, 5.0), line(12, 19.0, 12, 21.4),
            line(2.6, 12, 5.0, 12), line(19.0, 12, 21.4, 12))],
     f=[dot(12, 12, 1.2)],
     apps="PS/PR",
     basis="The live brush-size cursor ring Photoshop and Procreate both draw.",
     note="A footprint ring with the cursor arrow still touching it.")

icon("gesture-pinch", "ui", ["two finger zoom"],
     s=[seq(line(4.4, 4.4, 9.4, 9.4), line(19.6, 19.6, 14.6, 14.6))],
     f=[seq(ellipse(11.0, 11.0, 2.2, 1.6), ellipse(13.0, 13.0, 2.2, 1.6),
            tip(10.0, 10.0, 2.2, 225), tip(14.0, 14.0, 2.2, 45))],
     apps="PR",
     basis="Procreate's own pinch-to-zoom gesture diagram.",
     note="Two touch points being drawn together.")

icon("gesture-two-finger-tap", "ui", ["undo gesture", "quick undo"],
     s=[line(3.0, 17.4, 21.0, 17.4),
        seq(arc(8.6, 13.4, 4.2, 200, 340), arc(15.4, 13.4, 4.2, 200, 340))],
     f=[seq(ellipse(8.6, 14.6, 2.4, 1.7), ellipse(15.4, 14.6, 2.4, 1.7))],
     apps="PR",
     basis="Procreate's two-finger-tap undo gesture.",
     note="Two fingers landing together on the same surface.")

icon("undo-history-slider", "ui", ["scrub history", "time slider"],
     s=[line(3.4, 12, 20.6, 12), seq(line(3.4, 8.6, 3.4, 15.4), line(20.6, 8.6, 20.6, 15.4))],
     f=[rect(3.4, 10.6, 5.6, 2.8)],
     apps="PR",
     basis="Procreate's own undo/redo slider.",
     note="A single control riding along its own track.")
