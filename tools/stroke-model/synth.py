"""Writes schema-exact synthetic sessions (SCHEMA.md) for smoke-testing the pipeline.

  python synth.py OUT_DIR [--sessions 6] [--strokes 40]

Strokes are smooth curves with jittered sample timing; the phone "shakes" in a way correlated with
pen acceleration, so sensors carry real signal. Not a substitute for recorded data.
"""
from __future__ import annotations

import argparse
import gzip
import json
import math
import random
from pathlib import Path

NS = 1_000_000


def stroke(rng: random.Random, t0: int, offset: int) -> dict:
    tool = rng.choice(["finger", "stylus"])
    n = rng.randint(20, 120)
    cx, cy = rng.uniform(200, 900), rng.uniform(300, 1800)
    ax, ay, fx, fy, ph = rng.uniform(50, 300), rng.uniform(50, 300), rng.uniform(1, 4), rng.uniform(1, 4), rng.random() * 6
    cols = {k: [] for k in ["t", "action", "x", "y", "pressure", "size", "touchMajor", "touchMinor",
                            "toolMajor", "toolMinor", "orientation", "tilt", "distance", "buttons"]}
    t = t0
    for i in range(n):
        u = i / n
        t += int(rng.uniform(3.5, 8.5) * NS)
        cols["t"].append(t)
        cols["action"].append(0 if i == 0 else 1 if i == n - 1 else 2)
        cols["x"].append(cx + ax * math.sin(fx * u + ph))
        cols["y"].append(cy + ay * math.sin(fy * u))
        stylus = tool == "stylus"
        cols["pressure"].append(0.3 + 0.5 * math.sin(math.pi * u) if stylus else 1.0)
        cols["size"].append(0.02 if stylus else 0.15)
        cols["touchMajor"].append(4.0 if stylus else 40 + 5 * math.sin(u * 7))
        cols["touchMinor"].append(4.0 if stylus else 30.0)
        cols["toolMajor"].append(4.0 if stylus else 40.0)
        cols["toolMinor"].append(4.0 if stylus else 30.0)
        cols["orientation"].append(0.6 + 0.2 * u if stylus else 0.3)
        cols["tilt"].append(0.5 if stylus else 0.0)
        cols["distance"].append(0.0)
        cols["buttons"].append(0)
    sensors = {}
    for name, dims in [("accelerometer", 3), ("gyroscope", 3), ("gravity", 3), ("linearAcceleration", 3),
                       ("gameRotationVector", 5), ("magneticField", 3)]:
        ts, vs = [], []
        st = cols["t"][0] - 500 * NS + offset
        while st < cols["t"][-1] + 150 * NS + offset:
            ts.append(st)
            w = math.sin((st - offset - t0) / 1e8)
            vs.append([0.1 * w * (k + 1) + rng.gauss(0, 0.01) for k in range(dims)])
            st += 5 * NS
        sensors[name] = {"t": ts, "v": vs}
    return {"type": "stroke", "tool": tool, "inputDevice": "synthetic", "cancelled": False, "multiTouch": False,
            "samples": cols, "clockOffsetNs": offset,
            "context": {"tool": "BRUSH", "brush": "round", "brushSize": 20, "brushOpacity": 1, "zoom": 1.0,
                        "rotationDeg": 0, "displayRotation": 0, "sampleRateHz": 240, "gpu": "synthetic"},
            "sensors": sensors}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out", type=Path)
    ap.add_argument("--sessions", type=int, default=6)
    ap.add_argument("--strokes", type=int, default=40)
    a = ap.parse_args()
    rng = random.Random(1)
    d = a.out / "Synthetic"
    d.mkdir(parents=True, exist_ok=True)
    for s in range(a.sessions):
        offset = rng.randint(1, 10**12)
        with gzip.open(d / f"session-{s}.jsonl.gz", "wt") as f:
            f.write(json.dumps({"type": "session", "schema": 1, "manufacturer": "x", "model": "Synthetic",
                                "sdk": 35, "displayHz": rng.choice([60, 90, 120]), "widthPx": 1080,
                                "heightPx": 2400, "xdpi": 400, "ydpi": 400, "density": 2.6,
                                "startedAtMs": 0, "appVersion": "synthetic"}) + "\n")
            t = 10**10
            for _ in range(a.strokes):
                f.write(json.dumps(stroke(rng, t, offset)) + "\n")
                t += 3 * 10**9
    print(f"wrote {a.sessions} sessions to {d}")


if __name__ == "__main__":
    main()
