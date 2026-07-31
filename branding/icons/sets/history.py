"""History & automation — time, states, and repeatable work.

Undo/redo are the one pair every user already knows on sight (the curved back-arrow), kept
without alteration. History states, snapshots, and actions borrow Photoshop's own History
and Actions panels — a ladder of steps with the current rung marked, a camera for a snapshot,
a play button for a recorded action.
"""

from kit import (
    arc,
    circle,
    dashed,
    dot,
    icon,
    line,
    poly,
    rect,
    seq,
    square,
    tip,
)

icon("undo", "history", ["step back", "ctrl z"],
     s=[arc(12, 12, 8.4, 220, 490)],
     f=[tip(4.69, 6.8, 2.4, 230)],
     apps="PS/AI/PR",
     basis="The universal undo arrow.",
     note="An arc most of the way round, curling back to the left.")

icon("redo", "history", ["step forward", "ctrl y"],
     s=[arc(12, 12, 8.4, -40, 230)],
     f=[tip(19.31, 6.8, 2.4, -50)],
     apps="PS/AI/PR",
     basis="The undo arrow's mirror.",
     note="The same arc, curling back to the right instead.")

icon("history-panel", "history", ["state list", "undo stack"],
     s=[seq(line(6.4, 5.0, 20.6, 5.0), line(6.4, 9.6, 20.6, 9.6), line(6.4, 14.2, 20.6, 14.2),
            line(6.4, 18.8, 14.0, 18.8))],
     f=[dot(3.4, 14.2, 1.4)],
     apps="PS",
     basis="Photoshop's History panel — a ladder of states with the current one marked.",
     note="A ladder of states with the live rung marked at its edge.")

icon("history-snapshot", "history", ["checkpoint", "save state"],
     s=[rect(3.4, 7.4, 17.2, 12.0), rect(8.4, 4.0, 7.2, 4.0)],
     f=[dot(12, 13.4, 1.6)],
     apps="PS",
     basis="Photoshop's Snapshot — a camera capturing the state as it is right now.",
     note="A camera with the lens that took the picture.")

icon("history-brush", "history", ["restore from history", "paint back"],
     s=[line(20.6, 3.4, 13.8, 10.2), line(11.6, 12.4, 8.0, 16.0),
        arc(7.0, 17.0, 3.6, 300, 420)],
     f=[poly([(13.4, 9.8), (14.2, 13.4), (10.6, 12.6)], close=True)],
     apps="PS",
     basis="Photoshop's History Brush — a brush that paints an earlier state back in.",
     note="A brush working from an earlier point on the clock it is bound to.")

icon("clear-history", "history", ["purge undo stack", "forget states"],
     s=[seq(line(6.4, 5.0, 20.6, 5.0), line(6.4, 9.6, 14.0, 9.6)), line(3.4, 12.6, 9.4, 18.6),
        line(9.4, 12.6, 3.4, 18.6)],
     f=[],
     apps="PS",
     basis="Photoshop's Clear History.",
     note="A ladder of states with everything below the top struck out.")

icon("actions-panel", "history", ["macro", "recorded steps"],
     s=[seq(square(6.4, 5.4, 2.2), square(6.4, 12.0, 2.2), square(6.4, 18.6, 2.2)),
        line(9.4, 5.4, 20.6, 5.4), line(9.4, 12.0, 20.6, 12.0), line(9.4, 18.6, 17.0, 18.6)],
     f=[],
     apps="PS",
     basis="Photoshop's Actions panel — a checklist of recorded steps.",
     note="A checklist of steps, none yet played.")

icon("action-record", "history", ["record macro", "start recording"],
     s=[circle(12, 12, 8.4)],
     f=[dot(12, 12, 4.0)],
     apps="PS/PR",
     basis="The universal record button.",
     note="A solid disc held inside an open ring.")

icon("action-play", "history", ["run macro", "play action"],
     s=[circle(12, 12, 8.4)],
     f=[poly([(9.4, 7.4), (17.0, 12), (9.4, 16.6)], close=True)],
     apps="PS",
     basis="The universal play button.",
     note="A solid triangle inside a ring, pointed the way it will run.")

icon("action-stop", "history", ["stop recording"],
     s=[circle(12, 12, 8.4)],
     f=[square(12, 12, 6.8)],
     apps="PS",
     basis="The universal stop button.",
     note="A solid square inside a ring.")

icon("batch-process", "history", ["run on folder", "bulk action"],
     s=[seq(rect(3.4, 8.6, 5.0, 6.8), rect(9.6, 8.6, 5.0, 6.8), rect(15.8, 8.6, 5.0, 6.8))],
     f=[poly([(9.6, 3.4), (15.6, 3.4), (12.6, 7.0)], close=True)],
     apps="PS",
     basis="Photoshop's Batch — one action driven down a row of files.",
     note="A row of files with one action dropping onto all of them.")

icon("timer", "history", ["duration", "processing time"],
     s=[circle(12, 13, 8.0), line(12, 13, 12, 8.2), line(12, 13, 15.6, 13),
        line(9.4, 3.4, 14.6, 3.4)],
     f=[],
     apps="new",
     note="A clock face with both hands set to show the time an action takes.")

icon("version-compare", "history", ["diff", "compare states"],
     s=[rect(3.0, 3.4, 8.6, 17.2), rect(12.4, 3.4, 8.6, 17.2)],
     f=[rect(12.4, 8.6, 8.6, 4.0)],
     apps="new",
     note="Two states side by side, with the one difference between them solid.")

icon("checkpoint-restore", "history", ["roll back", "go to state"],
     s=[line(3.0, 20.0, 21.0, 20.0), line(8.0, 20.0, 8.0, 4.0),
        arc(14.0, 20.0, 6.4, 200, 250)],
     f=[poly([(8.0, 4.0), (17.0, 6.6), (8.0, 9.2)], close=True)],
     apps="new",
     note="A run of states with the live one held at the far end.")
