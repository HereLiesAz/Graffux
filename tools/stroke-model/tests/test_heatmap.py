"""Heatmap moments recover orientation and the egg's lead on synthetic blobs; v1-v3 files load.

  cd tools/stroke-model && python -m unittest discover -s tests -v
"""
from __future__ import annotations

import base64
import math
import random
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import synth  # noqa: E402
from strokemodel import heatmap as hm  # noqa: E402
from strokemodel.data import Stroke, read_dir  # noqa: E402
from strokemodel.onset import onsets  # noqa: E402


def axial_deg(a: float, b: float) -> float:
    d = (a - b) % math.pi
    return math.degrees(min(d, math.pi - d))


def directed_deg(a: float, b: float) -> float:
    return math.degrees(abs((a - b + math.pi) % (2 * math.pi) - math.pi))


class MomentsTest(unittest.TestCase):
    def test_fine_grid_recovers_orientation_lead_and_centroid(self):
        rng = random.Random(3)
        grid, screen = (70, 150), (70.0, 150.0)  # 1 mm cells: the algorithm, not the panel, is under test
        for _ in range(40):
            lead = rng.uniform(-math.pi, math.pi)
            cx, cy = rng.uniform(20, 50), rng.uniform(30, 120)
            m = hm.moments(synth.egg_frame(cx, cy, lead, 11.0, 7.0, 0.7, rng, grid, screen), (1.0, 1.0))
            self.assertLess(axial_deg(m["theta"], lead), 6.0)
            self.assertLess(directed_deg(m["lead"], lead), 10.0)
            self.assertGreater(abs(m["skew"]), 0.2)  # signed along an arbitrary eigenvector sign
            self.assertGreater(m["elong"], 0.15)
            self.assertLess(math.hypot(m["cx"] - cx, m["cy"] - cy), 3.0)

    def test_symmetric_blob_has_no_skew_and_round_blob_no_elongation(self):
        yy, xx = np.mgrid[0:60, 0:60].astype(float)
        ellipse = 500 * np.exp(-0.5 * (((xx - 30) / 6) ** 2 + ((yy - 30) / 3) ** 2))
        m = hm.moments(ellipse)
        self.assertLess(abs(m["skew"]), 0.02)
        self.assertLess(axial_deg(m["theta"], 0.0), 1.0)
        round_ = 500 * np.exp(-0.5 * (((xx - 30) / 4) ** 2 + ((yy - 30) / 4) ** 2))
        self.assertLess(hm.moments(round_)["elong"], 0.02)

    def test_panel_grid_recovers_pad_contacts_better_than_chance(self):
        rng = random.Random(5)
        pitch = (synth.SCREEN_MM[0] / synth.GRID_W, synth.SCREEN_MM[1] / synth.GRID_H)
        lead_errs, orient_errs = [], []
        for _ in range(150):
            lead = rng.uniform(-math.pi, math.pi)
            m = hm.moments(synth.egg_frame(rng.uniform(15, 55), rng.uniform(20, 130), lead, 11.0, 7.0, 0.6, rng), pitch)
            lead_errs.append(directed_deg(m["lead"], lead))
            orient_errs.append(axial_deg(m["theta"], lead))
        self.assertLess(np.median(orient_errs), 20.0)   # chance: 45
        self.assertLess(np.median(lead_errs), 45.0)     # chance: 90

    def test_noise_only_is_no_contact(self):
        rng = np.random.default_rng(0)
        self.assertIsNone(hm.moments(rng.normal(0, 4, (34, 16))))

    def test_decode_round_trip_and_transpose(self):
        frames = np.arange(2 * 3 * 4, dtype="<i2").reshape(2, 3, 4)  # 2 frames, 3 rows, 4 cols
        rec = {"w": 4, "h": 3, "dtype": "int16le", "t": [1, 2],
               "frames": base64.b64encode(frames.tobytes()).decode()}
        t, out, _ = hm.decode(rec, {"widthPx": 400, "heightPx": 300, "xdpi": 100, "ydpi": 100})
        self.assertEqual(out.shape, (2, 3, 4))
        self.assertTrue(np.array_equal(out, frames))
        # A landscape grid on a portrait screen is transposed onto the screen's axes.
        t, out, pitch = hm.decode(rec, {"widthPx": 300, "heightPx": 400, "xdpi": 100, "ydpi": 100})
        self.assertEqual(out.shape, (2, 4, 3))
        self.assertAlmostEqual(pitch[0], 300 / 100 * 25.4 / 3)


class PipelineTest(unittest.TestCase):
    def test_v1_v2_v3_load_and_v3_strokes_carry_heatmap_features(self):
        with tempfile.TemporaryDirectory() as d:
            for schema in (1, 2, 3):
                out = Path(d) / f"v{schema}"
                subprocess.run([sys.executable, str(ROOT / "synth.py"), str(out), "--sessions", "2",
                                "--strokes", "6", "--schema", str(schema)], check=True, capture_output=True)
                strokes = read_dir(out)
                self.assertEqual(len(strokes), 12)
                items = [o for o in onsets(strokes) if o.usable]
                present = [o.features["heatmap.present"] for o in items]
                if schema < 3:
                    self.assertFalse(any(present))
                else:
                    # Session 0 has heatmaps; the last session's header says "off".
                    self.assertTrue(any(present))
                    self.assertEqual(strokes[-1].session["heatmap"]["state"], "off")
                # Every stroke has the same feature keys, heatmap or not.
                self.assertEqual(len({tuple(sorted(o.features)) for o in items}), 1)

    def test_heatmap_features_ignore_frames_after_the_window(self):
        rng = random.Random(1)
        rec = synth.stroke(rng, 10**10, 0, False, "gyroscope", 3, with_heatmap=True)
        st = Stroke({"widthPx": 1080, "heightPx": 2400, "xdpi": 400, "ydpi": 400}, rec)
        t0 = rec["samples"]["t"][0]
        self.assertEqual(hm.features(st, t0 - 50e6)["heatmap.present"], 0.0)
        f = hm.features(st, t0 + 400e6)
        self.assertEqual(f["heatmap.present"], 1.0)
        self.assertGreater(f["heatmap.n"], 1)


if __name__ == "__main__":
    unittest.main()
