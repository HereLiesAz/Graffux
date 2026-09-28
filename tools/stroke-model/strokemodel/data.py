"""Loading stroke-data files (SCHEMA.md) and turning strokes into training examples.

An example is anchored at one real input sample of a stroke:

  inputs   - the last HISTORY samples, relative to the anchor (position, timing, pressure, contact
             ellipse, orientation/tilt of finger or stylus), the tool, and the phone's motion
             sensors at the anchor time and 50 ms before it;
  targets  - where the pen really was 1..HORIZONS display frames after the anchor, relative to the
             anchor, interpolated between the real samples around each target time;
  mask     - which horizons exist (a stroke that ends sooner has fewer).

Positions are in pixels divided by POS_SCALE so the network sees O(1) numbers.
"""
from __future__ import annotations

import gzip
import json
import math
from dataclasses import dataclass
from pathlib import Path

import numpy as np

SCHEMA_VERSION = 3
# v1: no `pointers`/`sensorStatus`/arrival times; v2: no `heatmap`. See SCHEMA.md.
READABLE_SCHEMAS = (1, 2, 3)
HISTORY = 16
HORIZONS = 4
POS_SCALE = 100.0  # px per unit
TIME_SCALE_MS = 16.0
TOOLS = ["finger", "stylus"]  # anything else -> "other"
SENSOR_SPECS = [  # (name, values used)
    ("accelerometer", 3),
    ("gyroscope", 3),
    ("gravity", 3),
    ("linearAcceleration", 3),
    ("gameRotationVector", 4),
]
SENSOR_LAG_NS = 50_000_000
# dx, dy, dt, pressure, touchMajor, touchMinor, sin/cos orientation, tilt, size, hover distance.
# The last is the `distance` column (AXIS_DISTANCE, SCHEMA.md): the stylus's height above the glass,
# 0 in contact and for fingers. Every schema (v1-v3) records it; a file missing it reads as 0.
PER_SAMPLE = 11
SENSOR_FEATURES = 2 * sum(n for _, n in SENSOR_SPECS) + len(SENSOR_SPECS)  # two times + presence flags
CONTEXT_FEATURES = len(TOOLS) + 1 + 2  # tool one-hot, frame ms, zoom


@dataclass
class Stroke:
    session: dict
    record: dict

    @property
    def frame_ms(self) -> float:
        hz = float(self.session.get("displayHz") or 60.0)
        return 1000.0 / (hz if hz > 1 else 60.0)


def read_file(path: Path) -> list[Stroke]:
    strokes: list[Stroke] = []
    session: dict = {}
    with gzip.open(path, "rt") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            if obj.get("type") == "session":
                if int(obj.get("schema", 0)) not in READABLE_SCHEMAS:
                    raise ValueError(f"{path}: schema {obj.get('schema')} not in {READABLE_SCHEMAS}")
                session = dict(obj, path=str(path))
            elif obj.get("type") == "stroke":
                strokes.append(Stroke(session, obj))
    return strokes


def read_dir(root: Path) -> list[Stroke]:
    out: list[Stroke] = []
    for p in sorted(Path(root).rglob("*.jsonl.gz")):
        out.extend(read_file(p))
    return out


def _interp_columns(t: np.ndarray, cols: np.ndarray, at: float) -> np.ndarray | None:
    """Linear interpolation of rows of `cols` (N x C) at time `at`; None outside [t0, tN]."""
    if at < t[0] or at > t[-1]:
        return None
    i = int(np.searchsorted(t, at, side="right"))
    if i >= len(t):
        return cols[-1]
    if i == 0:
        return cols[0]
    t0, t1 = t[i - 1], t[i]
    f = 0.0 if t1 == t0 else (at - t0) / (t1 - t0)
    return cols[i - 1] * (1 - f) + cols[i] * f


def _sensor_features(rec: dict, anchor_ns: float) -> np.ndarray:
    sensors = rec.get("sensors") or {}
    offset = float(rec.get("clockOffsetNs", 0))
    out: list[float] = []
    present: list[float] = []
    for name, n in SENSOR_SPECS:
        s = sensors.get(name)
        vals = None
        if s and s.get("t"):
            t = np.asarray(s["t"], dtype=np.float64) - offset
            v = np.asarray([row[:n] + [0.0] * (n - len(row[:n])) for row in s["v"]], dtype=np.float64)
            now = _interp_columns(t, v, anchor_ns)
            before = _interp_columns(t, v, anchor_ns - SENSOR_LAG_NS)
            if now is not None and before is not None:
                vals = (now, before)
        if vals is None:
            out.extend([0.0] * (2 * n))
            present.append(0.0)
        else:
            out.extend(vals[0].tolist())
            out.extend(vals[1].tolist())
            present.append(1.0)
    return np.asarray(out + present, dtype=np.float32)


def _hover_distance(samples: dict, n: int) -> np.ndarray:
    """The per-sample `distance` column, non-negative; zeros when absent or the wrong length."""
    v = samples.get("distance")
    if v is None or len(v) != n:
        return np.zeros(n)
    return np.clip(np.nan_to_num(np.asarray(v, dtype=np.float64)), 0.0, None)


def stroke_examples(stroke: Stroke):
    """Yields (history[HISTORY, PER_SAMPLE], sensors[SENSOR_FEATURES], context[CONTEXT_FEATURES],
    target[HORIZONS, 2], mask[HORIZONS]) for every usable anchor in the stroke."""
    rec = stroke.record
    s = rec["samples"]
    t = np.asarray(s["t"], dtype=np.float64)
    # v1 followed pointer index 0, which switches finger when the first one lifts early; v2 follows
    # the primary pointer by id, so its multi-touch strokes are clean.
    if len(t) < 3 or (rec.get("multiTouch") and int(stroke.session.get("schema", 1)) < 2):
        return
    xy = np.stack([np.asarray(s["x"], float), np.asarray(s["y"], float)], axis=1)
    pressure = np.asarray(s["pressure"], float)
    major = np.asarray(s["touchMajor"], float)
    minor = np.asarray(s["touchMinor"], float)
    orient = np.asarray(s["orientation"], float)
    tilt = np.asarray(s["tilt"], float)
    size = np.asarray(s["size"], float)
    hover = _hover_distance(s, len(t))
    frame_ns = stroke.frame_ms * 1e6
    tool = rec.get("tool", "unknown")
    tool_onehot = [1.0 if tool == name else 0.0 for name in TOOLS] + [0.0 if tool in TOOLS else 1.0]
    zoom = float((rec.get("context") or {}).get("zoom", 1.0))
    context = np.asarray(tool_onehot + [stroke.frame_ms / TIME_SCALE_MS, math.log(max(zoom, 1e-3))],
                         dtype=np.float32)
    end = t[-1]
    for i in range(1, len(t)):
        horizons = [t[i] + (h + 1) * frame_ns for h in range(HORIZONS)]
        if horizons[0] > end:
            break
        hist = np.zeros((HISTORY, PER_SAMPLE), dtype=np.float32)
        for k in range(HISTORY):
            j = i - (HISTORY - 1 - k)
            if j < 0:
                continue  # zero-padded; the dt channel stays 0 there
            d = (xy[j] - xy[i]) / POS_SCALE
            hist[k] = [
                d[0], d[1], (t[i] - t[j]) / 1e6 / TIME_SCALE_MS, pressure[j],
                major[j] / POS_SCALE, minor[j] / POS_SCALE,
                math.sin(orient[j]), math.cos(orient[j]), tilt[j], size[j], hover[j],
            ]
        target = np.zeros((HORIZONS, 2), dtype=np.float32)
        mask = np.zeros(HORIZONS, dtype=np.float32)
        for h, at in enumerate(horizons):
            p = _interp_columns(t, xy, at)
            if p is not None:
                target[h] = (p - xy[i]) / POS_SCALE
                mask[h] = 1.0
        yield hist, _sensor_features(rec, t[i]), context, target, mask


def build_arrays(strokes: list[Stroke]):
    H, S, C, Y, M = [], [], [], [], []
    for st in strokes:
        for h, s, c, y, m in stroke_examples(st):
            H.append(h); S.append(s); C.append(c); Y.append(y); M.append(m)
    if not H:
        raise ValueError("no usable examples")
    return tuple(np.stack(a) for a in (H, S, C, Y, M))
