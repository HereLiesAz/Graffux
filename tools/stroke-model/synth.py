"""Writes schema-exact synthetic sessions (SCHEMA.md, version 3) for smoke-testing the pipeline.

  python synth.py OUT_DIR [--sessions 6] [--strokes 40] [--schema 3]

Each stroke has a planted pose that the recorded shape reveals, so the onset ablation has real
signal to find (and a broken pipeline shows up as none):
  - scratch: the tip only -- small, round, pressed hard -- then a short back-and-forth;
  - wipe:    the tip then flattens -- the contact grows and elongates -- then a long sweep;
  - detail:  rolled onto the side -- elongation rises at constant area -- then a small dense scribble.
The finger's yaw sets the initial direction. Half the sessions report it as AXIS_ORIENTATION (some
panels do); the rest report 0, like the Pixel 5, and a palm pointer behind the finger (on some
strokes) and the settle drift are then the only hints. Contact reports pause after touchdown (a
hold) as real panels do, and the first report carries the settle drift.
Sensors carry the v2 arrival times and status; one sensor is "not registered" per session.
Schema 3 adds a raw touch heatmap to every stroke of every session but the last (whose header says
"off"): an egg-shaped blob -- blunt end behind, narrow end leading along the stroke's initial
direction, major axis along the finger -- on a GRID_W x GRID_H grid over the screen, at 120 Hz,
from 100 ms before touchdown to 200 ms after the first report (the onset; real files cover the
whole stroke). --schema 1 / 2 write the older layouts, to check the loader still reads them.
Not a substitute for recorded data.
"""
from __future__ import annotations

import argparse
import base64
import gzip
import json
import math
import random
from pathlib import Path

import numpy as np

NS = 1_000_000
PPM = 400 / 25.4  # px per mm at the synthetic 400 dpi
COLUMNS = ["t", "action", "x", "y", "pressure", "size", "touchMajor", "touchMinor",
           "toolMajor", "toolMinor", "orientation", "tilt", "distance", "buttons"]
GRID_W, GRID_H = 16, 34          # a plausible Samsung panel grid; the real one is unverified
SCREEN_MM = (1080 / PPM, 2400 / PPM)
HEATMAP_HZ = 120.0
BLUR_MM = 1.2                    # capacitive spreading beyond the contact itself
SENSORS = [("accelerometer", 3), ("gyroscope", 3), ("gravity", 3), ("linearAcceleration", 3),
           ("gameRotationVector", 5), ("rotationVector", 5), ("magneticField", 3)]


def path_for(kind: str, yaw: float, n: int) -> list[tuple[float, float]]:
    """Positions in mm relative to touchdown, starting along `yaw`."""
    c, s = math.cos(yaw), math.sin(yaw)
    pts = []
    for i in range(n):
        u = i / max(n - 1, 1)
        if kind == "scratch":   # ~3 mm back and forth along the finger axis
            a, b = 1.5 * math.sin(2 * math.pi * 2.5 * u), 0.2 * math.sin(7 * u)
        elif kind == "wipe":    # a long gentle sweep
            a, b = 60.0 * u, 8.0 * math.sin(math.pi * u)
        else:                   # detail: a dense scribble in a ~6 mm box
            a = 3.0 * (1 - math.cos(2 * math.pi * 3 * u)) + 0.5 * u
            b = 2.5 * math.sin(2 * math.pi * 5 * u)
        pts.append((a * c - b * s, a * s + b * c))
    return pts


def shape_for(kind: str, u_ms: float, scale: float) -> tuple[float, float, float]:
    """(major mm, minor mm, pressure) at `u_ms` after touchdown."""
    g = min(u_ms / 120.0, 1.0)
    if kind == "scratch":
        return 5.0 * scale, 4.8 * scale, 0.85
    if kind == "wipe":
        return (5.5 + 6.0 * g) * scale, (5.0 + 2.0 * g) * scale, 0.55
    return (6.0 + 3.0 * g) * scale, (5.5 - 2.5 * g) * scale, 0.6


def palm_pointer(rng: random.Random, times: list[int], x0: float, y0: float, yaw: float) -> dict:
    """A palm resting ~60 mm behind the fingertip, against the finger's direction."""
    d = 60.0 + rng.gauss(0, 5)
    a = yaw + math.pi + rng.gauss(0, 0.3)
    pc = {k: [] for k in COLUMNS}
    for j, tt in enumerate(times):
        pc["t"].append(tt)
        pc["action"].append(5 if j == 0 else 2)
        pc["x"].append((x0 + d * math.cos(a)) * PPM)
        pc["y"].append((y0 + d * math.sin(a)) * PPM)
        for k, v in [("pressure", 0.9), ("size", 0.3), ("touchMajor", 300), ("touchMinor", 180),
                     ("toolMajor", 300), ("toolMinor", 180), ("orientation", 0.0), ("tilt", 0.0),
                     ("distance", 0.0), ("buttons", 0)]:
            pc[k].append(v)
    pc["action"][-1] = 6
    return {"pointerId": 1, "tool": "palm", "canceled": True, "palm": True, "samples": pc}


def egg_frame(cx: float, cy: float, lead: float, major: float, minor: float, pressure: float,
              rng: random.Random, grid: tuple[int, int] = (GRID_W, GRID_H),
              screen: tuple[float, float] = SCREEN_MM) -> np.ndarray:
    """One heatmap frame (rows x cols): an egg centred at (cx, cy) mm, narrow end toward `lead` (radians)."""
    cols, rows = np.meshgrid(np.arange(grid[0]), np.arange(grid[1]))
    px, py = screen[0] / grid[0], screen[1] / grid[1]
    dx, dy = (cols + 0.5) * px - cx, (rows + 0.5) * py - cy
    u = dx * math.cos(lead) + dy * math.sin(lead)       # along the finger, + toward the narrow end
    v = -dx * math.sin(lead) + dy * math.cos(lead)
    su0, sv0 = major / 4, minor / 4
    su = np.where(u > 0, 1.5 * su0, 0.7 * su0)          # long thin tail forward, blunt end behind
    sv = sv0 * np.exp(-0.5 * np.clip(u, 0, None) / su0)  # and narrowing toward the front
    su = np.sqrt(su ** 2 + BLUR_MM ** 2)
    sv = np.sqrt(sv ** 2 + BLUR_MM ** 2)
    img = 900.0 * pressure * np.exp(-0.5 * (u / su) ** 2 - 0.5 * (v / sv) ** 2)
    noise = np.asarray([rng.gauss(0, 4.0) for _ in range(img.size)]).reshape(img.shape)
    return np.clip(np.round(img + noise), -32768, 32767).astype("<i2")


def heatmap(rng: random.Random, cols: dict, kind: str, scale: float, lead: float) -> dict:
    """Frames from 100 ms before touchdown to 200 ms after the first report (schema v3 `heatmap`)."""
    t = np.asarray(cols["t"], dtype=np.float64)
    x = np.asarray(cols["x"]) / PPM
    y = np.asarray(cols["y"]) / PPM
    t0 = t[0]
    stop = min(t[-1], t[1] + 200 * NS) + 50 * NS
    ts, frames = [], []
    ft = t0 - 100 * NS + rng.uniform(0, 1e9 / HEATMAP_HZ)
    while ft <= stop:
        if t0 <= ft <= t[-1]:
            major, minor, pressure = shape_for(kind, (ft - t0) / NS, scale)
            fr = egg_frame(float(np.interp(ft, t, x)), float(np.interp(ft, t, y)), lead, major, minor, pressure, rng)
        else:
            fr = np.asarray([rng.gauss(0, 4.0) for _ in range(GRID_W * GRID_H)]).round().astype("<i2")
        ts.append(int(ft))
        frames.append(fr.reshape(-1))
        ft += 1e9 / HEATMAP_HZ
    raw = np.concatenate(frames).astype("<i2").tobytes()
    return {"source": "sec_delta", "w": GRID_W, "h": GRID_H, "dtype": "int16le", "t": ts, "a": ts,
            "frames": base64.b64encode(raw).decode(), "truncated": False}


def heatmap_status(on: bool) -> dict:
    if not on:
        return {"state": "off", "detail": "", "w": 0, "h": 0, "frames": 0, "clockCheckNs": None}
    return {"state": "sec_delta", "detail": "synthetic", "w": GRID_W, "h": GRID_H, "frames": 0,
            "clockCheckNs": 200_000}


def stroke(rng: random.Random, t0: int, offset: int, report_orient: bool, missing: str, schema: int,
           with_heatmap: bool = False) -> dict:
    kind = rng.choice(["scratch", "wipe", "detail", "wipe", "detail"])
    yaw = rng.uniform(-math.pi, math.pi)
    n = {"scratch": 40, "wipe": 90, "detail": 160}[kind] + rng.randint(-10, 10)
    heading = yaw + rng.gauss(0, 0.25)
    pts = path_for(kind, heading, n)
    x0, y0 = rng.uniform(15, 55), rng.uniform(20, 130)
    scale = rng.uniform(0.9, 1.1)
    cols = {k: [] for k in COLUMNS}
    t = t0
    hold = int(rng.uniform(40, 250) * NS)
    # While the finger rests it settles: a flattening pad slides the centroid back toward the hand
    # (against the yaw), a side roll shifts it sideways. The panel reports that with the first sample.
    back = {"wipe": (-0.8, 0.0), "detail": (0.0, 0.5), "scratch": (0.0, 0.0)}[kind]
    settle = (back[0] * math.cos(yaw) - back[1] * math.sin(yaw), back[0] * math.sin(yaw) + back[1] * math.cos(yaw))
    first = pts[1]
    pts = [pts[0], settle] + [(px - first[0] + settle[0], py - first[1] + settle[1]) for px, py in pts[1:]]
    n = len(pts)
    for i, (px, py) in enumerate(pts):
        if i == 1:
            t += hold
        elif i > 1:
            t += int(rng.uniform(4.0, 7.0) * NS)
        major, minor, pressure = shape_for(kind, (t - t0) / NS, scale)
        cols["t"].append(t)
        cols["action"].append(0 if i == 0 else 1 if i == n - 1 else 2)
        cols["x"].append((x0 + px) * PPM)
        cols["y"].append((y0 + py) * PPM)
        cols["pressure"].append(pressure + rng.gauss(0, 0.02))
        cols["size"].append(major * minor / 400)
        for k, v in [("touchMajor", major), ("touchMinor", minor), ("toolMajor", major), ("toolMinor", minor)]:
            cols[k].append(round(v * PPM))
        cols["orientation"].append(yaw if report_orient else 0.0)
        cols["tilt"].append(0.0)
        cols["distance"].append(0.0)
        cols["buttons"].append(0)
    rec = {"type": "stroke", "tool": "finger", "inputDevice": "synthetic", "cancelled": False,
           "multiTouch": False, "samples": cols, "clockOffsetNs": offset,
           "context": {"tool": "BRUSH", "brush": "round", "brushSize": 20, "brushOpacity": 1, "zoom": 1.0,
                       "rotationDeg": 0, "displayRotation": 0, "sampleRateHz": 240, "gpu": "synthetic"}}
    if schema >= 2:
        rec.update({"pointerId": 0, "canceled": False, "palm": False, "pointers": []})
        if rng.random() < 0.4:
            rec["multiTouch"] = True
            rec["pointers"] = [palm_pointer(rng, cols["t"][1::4], x0, y0, yaw)]
    rec["sensors"], status = sensors(rng, cols["t"][0], cols["t"][-1], offset, missing, schema)
    if schema >= 2:
        rec["sensorStatus"] = status
        rec["sensorsRegistered"] = [k for k, v in status.items() if v["registered"]]
        rec["flush"] = "completed"
    if schema >= 3:
        rec["heatmapStatus"] = heatmap_status(with_heatmap)
        if with_heatmap:
            # The narrow end leads: along the direction the stroke actually sets off in.
            rec["heatmap"] = heatmap(rng, cols, kind, scale, heading)
    return rec


def sensors(rng: random.Random, first: int, last: int, offset: int, missing: str, schema: int):
    out, status = {}, {}
    for name, dims in SENSORS:
        if name == missing:
            status[name] = {"registered": False, "flushed": False, "events": 0, "lagNs": None}
            continue
        ts, arr, vs = [], [], []
        st = first - 500 * NS + offset
        while st < last + 150 * NS + offset:
            ts.append(st)
            arr.append(st + int(rng.uniform(0.2, 2.0) * NS))
            w = math.sin((st - offset - first) / 1e8)
            vs.append([0.1 * w * (k + 1) + rng.gauss(0, 0.01) for k in range(dims)])
            st += 5 * NS
        out[name] = {"t": ts, "v": vs} if schema < 2 else {"t": ts, "a": arr, "v": vs}
        status[name] = {"registered": True, "flushed": True, "events": len(ts), "lagNs": arr[-1] - ts[-1]}
    return out, status


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out", type=Path)
    ap.add_argument("--sessions", type=int, default=6)
    ap.add_argument("--strokes", type=int, default=40)
    ap.add_argument("--schema", type=int, choices=[1, 2, 3], default=3)
    a = ap.parse_args()
    rng = random.Random(1)
    d = a.out / "Synthetic"
    d.mkdir(parents=True, exist_ok=True)
    for s in range(a.sessions):
        offset = rng.randint(1, 10**12)
        report_orient = s % 2 == 0
        missing = SENSORS[s % len(SENSORS)][0]
        with gzip.open(d / f"session-{s}.jsonl.gz", "wt") as f:
            f.write(json.dumps({"type": "session", "schema": a.schema, "manufacturer": "x", "model": "Synthetic",
                                "sdk": 35, "displayHz": rng.choice([60, 90, 120]), "widthPx": 1080,
                                "heightPx": 2400, "xdpi": 400, "ydpi": 400, "density": 2.6,
                                "startedAtMs": 0, "appVersion": "synthetic",
                                **({"heatmap": heatmap_status(s < a.sessions - 1)} if a.schema >= 3 else {})})
                    + "\n")
            t = 10**10
            for _ in range(a.strokes):
                f.write(json.dumps(stroke(rng, t, offset, report_orient, missing, a.schema,
                                          with_heatmap=a.schema >= 3 and s < a.sessions - 1)) + "\n")
                t += 3 * 10**9
    print(f"wrote {a.sessions} sessions (schema {a.schema}) to {d}")


if __name__ == "__main__":
    main()
