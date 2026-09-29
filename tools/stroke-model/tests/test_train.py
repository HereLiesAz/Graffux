"""End-to-end smoke test: synthetic sessions -> train briefly -> per-horizon eval -> ONNX export.
Also checks the schema 4 clock (everything on elapsedRealtime) lines sensors up as v3 did.

  cd tools/stroke-model && python -m unittest discover -s tests -v
"""
from __future__ import annotations

import json
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
import train  # noqa: E402
from strokemodel.data import (HORIZONS, SCHEMA_VERSION, Stroke, _sensor_features, read_dir,  # noqa: E402
                              sensor_clock_offset, stroke_examples)


class TrainSmokeTest(unittest.TestCase):
    def test_synthetic_sessions_train_evaluate_and_export(self):
        with tempfile.TemporaryDirectory() as d:
            data = Path(d) / "data"
            subprocess.run([sys.executable, str(ROOT / "synth.py"), str(data), "--sessions", "3",
                            "--strokes", "8"], check=True, capture_output=True)
            self.assertEqual(read_dir(data)[0].session["schema"], SCHEMA_VERSION)
            tr, va = train.split_files(data, 0.34, seed=0)
            out = Path(d) / "model.onnx"
            res = train.train(tr, va, epochs=2, batch=256, out=out, log=lambda *_: None)

            self.assertEqual(res["horizons"], list(range(1, HORIZONS + 1)))
            self.assertEqual(set(res["baselines"]), set(train.BASELINES))
            for errs in [*res["baselines"].values(), res["model"]]:
                self.assertEqual(len(errs), HORIZONS)
                self.assertTrue(all(np.isfinite(errs)))
            # Standing still gets worse the further ahead it has to guess.
            lp = res["baselines"]["last_point"]
            self.assertLess(lp[0], lp[-1])
            self.assertEqual(len(res["history"]), 2)
            json.dumps(res)  # the --report output

            self.assertTrue(out.exists() and out.stat().st_size > 0)
            try:
                import onnxruntime as ort
            except ImportError:
                return
            sess = ort.InferenceSession(str(out))
            H, S, C, *_ = train.build_arrays(va)
            got = sess.run(["offsets"], {"history": H[:3], "sensors": S[:3], "context": C[:3]})[0]
            self.assertEqual(got.shape, (3, HORIZONS, 2))


class ClockTest(unittest.TestCase):
    def _pair(self):
        """The same stroke as schema 3 (uptime + clockOffsetNs) and schema 4 (all elapsedRealtime)."""
        offset = 123_456_789_000
        v3 = synth.stroke(random.Random(5), 10**10, offset, False, "gyroscope", 3, with_heatmap=True)
        v4 = synth.stroke(random.Random(5), 10**10, offset, False, "gyroscope", 4, with_heatmap=True)
        return offset, v3, v4

    def test_v4_samples_heatmap_and_sensors_share_one_clock(self):
        offset, v3, v4 = self._pair()
        self.assertEqual(v4["clock"], "elapsedRealtime")
        self.assertEqual(v4["clockOffsetNs"], 0)
        self.assertEqual(v4["samples"]["t"][0], v3["samples"]["t"][0] + offset)
        self.assertEqual(v4["heatmap"]["t"][0], v3["heatmap"]["t"][0] + offset)
        self.assertEqual(v4["sensors"]["accelerometer"]["t"], v3["sensors"]["accelerometer"]["t"])
        self.assertEqual(sensor_clock_offset(v3), offset)
        self.assertEqual(sensor_clock_offset(v4), 0)
        # A v4 record whose writer still left an offset in clockOffsetNs is not shifted twice.
        self.assertEqual(sensor_clock_offset(dict(v4, clockOffsetNs=offset)), 0)

    def test_v3_and_v4_give_the_same_training_examples(self):
        _, v3, v4 = self._pair()
        a = list(stroke_examples(Stroke({"schema": 3, "displayHz": 60}, v3)))
        b = list(stroke_examples(Stroke({"schema": 4, "displayHz": 60}, v4)))
        self.assertEqual(len(a), len(b))
        self.assertTrue(a)
        for x, y in zip(a, b):
            for u, w in zip(x, y):
                np.testing.assert_allclose(u, w, atol=1e-5)
        # And the sensors are really found (presence flags set), not zero on both sides.
        t4 = v4["samples"]["t"][2]
        self.assertGreater(_sensor_features(v4, t4)[-5:].sum(), 0)

    def test_every_schema_loads(self):
        with tempfile.TemporaryDirectory() as d:
            for schema in (1, 2, 3, 4):
                out = Path(d) / f"v{schema}"
                subprocess.run([sys.executable, str(ROOT / "synth.py"), str(out), "--sessions", "2",
                                "--strokes", "4", "--schema", str(schema)], check=True, capture_output=True)
                self.assertEqual(len(read_dir(out)), 8)


if __name__ == "__main__":
    unittest.main()
