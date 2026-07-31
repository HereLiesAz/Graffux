"""Animation & timeline — frames, playback, capture.

Playback transport (play/pause/stop/step) is the most universally fixed vocabulary in all of
software and is kept exactly as every video and audio app draws it. Frame and keyframe icons
follow Photoshop's Timeline panel: a filmstrip, a diamond for a keyframe, onion-skin ghosts.
"""

from kit import (
    arc,
    circle,
    dot,
    icon,
    line,
    poly,
    rect,
    seq,
    square,
    tip,
)

icon("timeline", "animation", ["frames track", "animation panel"],
     s=[line(3.4, 9.6, 20.6, 9.6),
        seq(line(6.4, 9.6, 6.4, 15.4), line(12.0, 9.6, 12.0, 15.4), line(17.6, 9.6, 17.6, 15.4))],
     f=[dot(12.0, 9.6, 1.4)],
     apps="PS/AI",
     basis="Photoshop's Timeline — a track with frames marked along it.",
     note="A track with three frame marks hanging below it and the playhead on one. Crossing "
          "the track instead of hanging from it makes three crosses on a hill, which is "
          "Calvary.")

icon("keyframe", "animation", ["key state", "diamond marker"],
     s=[line(3.4, 12, 20.6, 12)],
     f=[poly([(12, 7.4), (16.6, 12), (12, 16.6), (7.4, 12)], close=True)],
     apps="PS/AI",
     basis="The diamond every timeline uses to mark a keyframe.",
     note="A single diamond set on the track.")

icon("keyframe-add", "animation", ["set key", "record state"],
     s=[line(3.4, 12, 20.6, 12), poly([(12, 7.4), (16.6, 12), (12, 16.6), (7.4, 12)], close=True)],
     f=[],
     apps="PS/AI",
     basis="An empty diamond, waiting to be committed.",
     note="A diamond marked but not yet solid.")

icon("onion-skin", "animation", ["ghost frames", "previous next frame"],
     s=[rect(3.0, 7.0, 8.0, 10.0), rect(6.4, 7.0, 8.0, 10.0)],
     f=[rect(9.8, 7.0, 8.0, 10.0)],
     apps="PS/PR",
     basis="Procreate's Onion Skin and Photoshop's Ghost frames.",
     note="A solid current frame between two faint marks either side.")

icon("frame-add", "animation", ["new frame", "insert frame"],
     s=[seq(rect(2.4, 6.4, 6.0, 11.2), rect(8.8, 6.4, 6.0, 11.2)),
        seq(line(18.4, 8.6, 18.4, 15.4), line(15.0, 12.0, 21.8, 12.0))],
     f=[],
     apps="PS/PR",
     basis="A filmstrip with a new frame being spliced onto its end.",
     note="Two frames of a strip, with a third being added.")

icon("frame-duplicate", "animation", ["copy frame", "hold frame"],
     s=[rect(3.4, 5.4, 8.6, 13.2)],
     f=[rect(9.0, 5.4, 8.6, 13.2)],
     apps="PS/PR",
     basis="A frame copied forward so its hold repeats.",
     note="One frame, twice, held in a row.")

icon("frame-delete", "animation", ["remove frame"],
     s=[rect(9.8, 5.4, 6.0, 13.2), line(9.8, 5.4, 15.8, 18.6), line(15.8, 5.4, 9.8, 18.6)],
     f=[],
     apps="PS/PR",
     basis="A frame taken out of the strip, leaving a gap.",
     note="A single frame's outline, empty.")

icon("frame-rate", "animation", ["fps", "frames per second"],
     s=[circle(11.2, 12, 8.0), line(11.2, 12, 11.2, 6.8), line(11.2, 12, 15.0, 12)],
     f=[],
     apps="PS/PR",
     basis="A clock face standing for the frame rate that drives it.",
     note="A clock with both hands set, the way a rate is always shown.")

icon("play", "animation", ["run", "playback start"],
     s=[circle(12, 12, 8.4)],
     f=[poly([(9.4, 7.4), (17.0, 12), (9.4, 16.6)], close=True)],
     apps="PS/AI/PR",
     basis="The universal play button.",
     note="A triangle inside a ring, pointed the way it plays.")

icon("pause", "animation", ["hold playback"],
     s=[circle(12, 12, 8.4)],
     f=[seq(rect(8.6, 7.4, 2.6, 9.2), rect(12.8, 7.4, 2.6, 9.2))],
     apps="PS/AI/PR",
     basis="The universal pause button.",
     note="Two solid bars inside a ring.")

icon("stop", "animation", ["end playback"],
     s=[circle(12, 12, 8.4)],
     f=[square(12, 12, 6.8)],
     apps="PS/AI/PR",
     basis="The universal stop button.",
     note="A square inside a ring.")

icon("step-forward", "animation", ["next frame"],
     s=[],
     f=[seq(poly([(6.4, 6.4), (14.0, 12), (6.4, 17.6)], close=True), rect(15.6, 6.4, 2.2, 11.2))],
     apps="PS/AI/PR",
     basis="The universal next-frame control.",
     note="A triangle stopped by a bar at the frame's own edge.")

icon("step-backward", "animation", ["previous frame"],
     s=[],
     f=[seq(rect(6.4, 6.4, 2.2, 11.2), poly([(17.6, 6.4), (10.0, 12), (17.6, 17.6)], close=True))],
     apps="PS/AI/PR",
     basis="The universal previous-frame control.",
     note="The same pairing, run the other way.")

icon("loop-playback", "animation", ["repeat", "cycle"],
     s=[arc(12, 12, 8.0, 20, 340)],
     f=[seq(tip(4.69, 6.8, 2.2, 230), tip(19.31, 17.2, 2.2, 50))],
     apps="PS/AI/PR",
     basis="The universal loop icon.",
     note="A ring with both ends arrowed, so it never runs out.")

icon("motion-tween", "animation", ["interpolate", "in between frames"],
     s=[seq(rect(3.0, 8.4, 4.4, 7.2), rect(16.6, 8.4, 4.4, 7.2)),
        seq(line(9.6, 10.0, 9.6, 14.0), line(12.0, 10.0, 12.0, 14.0), line(14.4, 10.0, 14.4, 14.0))],
     f=[],
     apps="AI",
     basis="Illustrator's tweening between two keyframes.",
     note="Two held states with the computed step between them.")

icon("export-video", "animation", ["render animation", "save as video"],
     s=[poly([(3.4, 6.4), (3.4, 17.6), (14.6, 17.6), (14.6, 6.4)], close=True),
        poly([(14.6, 9.4), (20.6, 6.4), (20.6, 17.6), (14.6, 14.6)], close=True)],
     f=[tip(20.0, 21.4, 1.8, 90)],
     apps="PS/AI/PR",
     basis="A camera body with the export arrow beneath it.",
     note="A camera with the mark that says its output is being written out.")

icon("export-gif", "animation", ["animated gif", "loop export"],
     s=[poly([(2.6, 4.6), (2.6, 14.6), (12.6, 14.6), (12.6, 4.6)], close=True),
        poly([(12.6, 7.2), (17.8, 4.6), (17.8, 14.6), (12.6, 12.0)], close=True),
        arc(17.6, 18.2, 2.9, 20, 340)],
     f=[seq(tip(20.0, 15.9, 1.4, 40), tip(15.2, 20.5, 1.4, 220))],
     apps="PS/PR",
     basis="The same camera, marked with a loop rather than a single arrow out.",
     note="A camera with the two-headed mark that says its output repeats.")
