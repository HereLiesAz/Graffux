"""Whole-stroke prediction from the onset: contact-shape and finger-pose features, targets, labels.

The thesis under test: a stroke's direction and character are set by pose -- hand to finger or
brush, finger to glass -- and the SHAPE of the contact at touchdown already says where the finger
will go. Everything here is computed per stroke, from its onset window only (features) or from the
whole stroke (targets and labels).

Onset window
------------
Sample 0 is the touchdown (ACTION_DOWN). Many touch controllers (the Pixel 5 among them) send no
further report while the finger rests, so the second sample often comes 50-300 ms later, when the
contact first changes. The window therefore runs from touchdown to `onset_ms` after that FIRST
REPORT (anchor="first-report", the default), not after touchdown; anchor="touchdown" measures from
sample 0 instead. The last sample in the window is the ANCHOR: targets start there, so nothing a
target measures is inside the window.

Feature groups (prefix of each feature name)
--------------------------------------------
  kin.      positions and timing in the window: the kinematic baseline. Includes the hold time.
  shape.    contact shape only -- touchMajor/Minor (mm), elongation, area, size, pressure, their
            deltas and rates, orientation/tilt when the device reports them, world-frame device
            attitude, the second pointer's offset, the stylus hover approach. NO position of the
            drawing contact and no velocity.
  pose.     finger pose derived from shape alone: pitch, contact-part label and embedding, the
            tip-only / flatten / side-roll transitions.
  drift.    finger pose that needs the contact's own centroid: yaw from settle drift (and from the
            palm), the egg lead direction. Built only from samples slower than SETTLE_MM_S, so it
            is the contact settling, not the stroke -- but it IS position data, so it is kept apart.
  heatmap.  schema v3, root devices only: moments of the raw capacitive contact image
            (strokemodel/heatmap.py) -- true orientation, elongation, egg skew and lead direction,
            area, peak -- at touchdown and at the window end. All zero with heatmap.present = 0
            when the stroke has no heatmap.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np

from . import heatmap as hm
from .data import Stroke

ONSET_MS = 30.0
DIR_HORIZONS_MS = (50.0, 100.0, 200.0)
MIN_DISP_MM = 0.3       # a direction target needs at least this much displacement
MIN_PATH_MM = 1.0       # shorter strokes are taps: no direction, no type
SETTLE_MM_S = 20.0      # slower than this is the contact settling, not the stroke
RESAMPLE_MM = 1.0       # path resampling for curvature and reversals (finer is mostly jitter)
REVERSAL_DEG = 120.0    # a heading change this sharp between resampled segments is a reversal
KIN_POINTS = 5          # positions sampled across the window for the kinematic features

STROKE_TYPES = ["scratch", "wipe", "detail", "other"]
PARTS = ["tip", "pad", "side", "nail"]

# Stroke-type thresholds (mm on the glass). See README "Stroke types".
SCRATCH_MAX_EXTENT_MM = 6.0
SCRATCH_MAX_PATH_MM = 25.0
SCRATCH_MIN_REVERSALS_PER_10MM = 1.0
WIPE_MIN_PATH_MM = 25.0
WIPE_MIN_EXTENT_MM = 15.0
WIPE_MAX_CURVATURE = 0.35    # rad/mm, mean |turning| per mm: sweeping, not tight loops
DETAIL_MAX_EXTENT_MM = 15.0
DETAIL_MIN_DENSITY = 3.0     # path length / bounding-box diagonal
DETAIL_MIN_REVERSALS_PER_10MM = 1.0


def px_per_mm(stroke: Stroke) -> float:
    s = stroke.session
    dpi = float(s.get("xdpi") or 0) or 160.0 * float(s.get("density") or 1.0)
    return dpi / 25.4


def _col(samples: dict, name: str, n: int) -> np.ndarray:
    v = samples.get(name)
    return np.asarray(v, dtype=np.float64) if v is not None and len(v) == n else np.zeros(n)


@dataclass
class Track:
    """One pointer's samples in mm and ms, times relative to the stroke's touchdown."""
    t: np.ndarray
    xy: np.ndarray
    major: np.ndarray
    minor: np.ndarray
    size: np.ndarray
    pressure: np.ndarray
    orient: np.ndarray
    tilt: np.ndarray
    distance: np.ndarray

    @staticmethod
    def of(samples: dict, t0_ns: float, ppm: float) -> "Track":
        t = np.asarray(samples["t"], dtype=np.float64)
        n = len(t)
        xy = np.stack([_col(samples, "x", n), _col(samples, "y", n)], axis=1) / ppm
        return Track((t - t0_ns) / 1e6, xy, _col(samples, "touchMajor", n) / ppm,
                     _col(samples, "touchMinor", n) / ppm, _col(samples, "size", n),
                     _col(samples, "pressure", n), _col(samples, "orientation", n),
                     _col(samples, "tilt", n), _col(samples, "distance", n))

    def at(self, ms: float) -> np.ndarray:
        """Position at time `ms`, clamped to the track's ends."""
        return np.stack([np.interp(ms, self.t, self.xy[:, k]) for k in range(2)])


@dataclass
class Onset:
    """One stroke's onset: features by name, whole-stroke targets and labels."""
    stroke: Stroke
    features: dict = field(default_factory=dict)
    targets: dict = field(default_factory=dict)
    labels: dict = field(default_factory=dict)
    usable: bool = True
    why: str = ""


# ---------------------------------------------------------------------------------------------
# Whole-stroke geometry: targets and labels


def resample(xy: np.ndarray, step: float) -> np.ndarray:
    seg = np.linalg.norm(np.diff(xy, axis=0), axis=1)
    s = np.concatenate([[0.0], np.cumsum(seg)])
    if s[-1] < step:
        return xy[[0, -1]]
    at = np.arange(0.0, s[-1] + 1e-9, step)
    return np.stack([np.interp(at, s, xy[:, 0]), np.interp(at, s, xy[:, 1])], axis=1)


def geometry(tr: Track) -> dict:
    """Path length, extent, curvature, reversals, density, speed and smoothness of a whole track."""
    xy = tr.xy
    path = float(np.linalg.norm(np.diff(xy, axis=0), axis=1).sum())
    ext = np.ptp(xy, axis=0)
    diag = float(np.hypot(*ext))
    r = resample(xy, RESAMPLE_MM)
    turns = np.zeros(0)
    if len(r) >= 3:
        h = np.arctan2(np.diff(r[:, 1]), np.diff(r[:, 0]))
        turns = np.abs((np.diff(h) + np.pi) % (2 * np.pi) - np.pi)
    curvature = float(turns.sum() / max(path, 1e-6)) if len(turns) else 0.0
    reversals = int((turns > math.radians(REVERSAL_DEG)).sum())
    dur_s = max((tr.t[-1] - tr.t[0]) / 1e3, 1e-3)
    # Speed and jerk on a uniform 10 ms grid, which the raw jittered timing makes meaningless.
    grid = np.arange(tr.t[0], tr.t[-1], 10.0)
    speed_mean, jerk = 0.0, 0.0
    if len(grid) >= 5:
        g = np.stack([np.interp(grid, tr.t, xy[:, 0]), np.interp(grid, tr.t, xy[:, 1])], axis=1)
        v = np.diff(g, axis=0) / 0.01
        sp = np.linalg.norm(v, axis=1)
        speed_mean = float(sp.mean())
        j = np.diff(v, n=2, axis=0) / 0.01 ** 2
        # Dimensionless: RMS jerk scaled by duration^2 / mean speed... kept per-stroke comparable.
        jerk = float(np.sqrt((j ** 2).sum(axis=1).mean()) * 0.1 ** 2 / max(speed_mean, 1e-3)) if len(j) else 0.0
    curv_var = float(np.var(turns / RESAMPLE_MM)) if len(turns) else 0.0
    return {"path": path, "extent": float(ext.max()), "diag": diag, "curvature": curvature,
            "reversals": reversals, "rev_per_10mm": 10.0 * reversals / max(path, 1e-6),
            "density": path / max(diag, 0.5), "duration_s": dur_s, "speed_mean": speed_mean,
            "jerk": jerk, "curv_var": curv_var}


def stroke_type(g: dict) -> str:
    """The owner's stroke types, from the whole stroke's kinematics (thresholds in README)."""
    if (g["extent"] <= SCRATCH_MAX_EXTENT_MM and g["path"] <= SCRATCH_MAX_PATH_MM
            and g["rev_per_10mm"] >= SCRATCH_MIN_REVERSALS_PER_10MM):
        return "scratch"
    if g["extent"] <= DETAIL_MAX_EXTENT_MM and (
            g["density"] >= DETAIL_MIN_DENSITY or g["rev_per_10mm"] >= DETAIL_MIN_REVERSALS_PER_10MM):
        return "detail"
    if (g["path"] >= WIPE_MIN_PATH_MM and g["extent"] >= WIPE_MIN_EXTENT_MM
            and g["curvature"] <= WIPE_MAX_CURVATURE):
        return "wipe"
    return "other"


def _targets(tr: Track, a: int) -> dict:
    ta = tr.t[a]
    pa = tr.xy[a]
    out: dict = {}
    for h in DIR_HORIZONS_MS:
        d = tr.at(min(ta + h, tr.t[-1])) - pa
        out[f"dir{int(h)}"] = math.atan2(d[1], d[0]) if np.hypot(*d) >= MIN_DISP_MM else None
    g = geometry(tr)
    out["length"] = g["path"]
    out["curvature"] = g["curvature"] if g["path"] >= 2 * RESAMPLE_MM else None
    out["end"] = (tr.xy[-1] - tr.xy[0]).tolist()
    return out


# ---------------------------------------------------------------------------------------------
# Onset features


def window(tr: Track, onset_ms: float, anchor: str) -> np.ndarray:
    """Indices of the onset window: touchdown plus samples up to onset_ms after the anchor time."""
    start = tr.t[1] if anchor == "first-report" and len(tr.t) > 1 else tr.t[0]
    idx = np.nonzero(tr.t <= start + onset_ms)[0]
    return idx if len(idx) >= 2 else np.arange(min(2, len(tr.t)))


def _shape_row(tr: Track, i: int) -> dict:
    major, minor = tr.major[i], tr.minor[i]
    ratio = minor / major if major > 0 else 1.0
    return {"major": major, "minor": minor, "ratio": ratio, "elong": 1.0 - ratio,
            "logarea": math.log(max(math.pi / 4 * major * minor, 1e-3)),
            "size": tr.size[i], "pressure": tr.pressure[i]}


def _shape_features(tr: Track, w: np.ndarray) -> dict:
    f: dict = {}
    td, first, anc = _shape_row(tr, 0), _shape_row(tr, w[1]), _shape_row(tr, w[-1])
    span = max(tr.t[w[-1]] - tr.t[0], 1.0)
    for k in td:
        f[f"shape.td_{k}"] = td[k]
        f[f"shape.first_{k}"] = first[k]
        f[f"shape.end_{k}"] = anc[k]
        f[f"shape.d_{k}"] = anc[k] - td[k]
        f[f"shape.rate_{k}"] = (anc[k] - td[k]) / span * 10.0  # per 10 ms
    rows = [_shape_row(tr, i) for i in w]
    f["shape.max_logarea"] = max(r["logarea"] for r in rows)
    f["shape.max_elong"] = max(r["elong"] for r in rows)
    f["shape.max_pressure"] = max(r["pressure"] for r in rows)
    has_orient = bool(np.any(tr.orient[w] != 0))
    has_tilt = bool(np.any(tr.tilt[w] != 0))
    f["shape.orient_present"] = float(has_orient)
    th = tr.orient[0]
    f["shape.orient_sin"], f["shape.orient_cos"] = (math.sin(th), math.cos(th)) if has_orient else (0.0, 0.0)
    f["shape.orient_sin2"], f["shape.orient_cos2"] = (math.sin(2 * th), math.cos(2 * th)) if has_orient else (0.0, 0.0)
    f["shape.tilt_present"] = float(has_tilt)
    f["shape.tilt"] = float(tr.tilt[0]) if has_tilt else 0.0
    return f


def _attitude_features(stroke: Stroke) -> dict:
    """Device attitude at touchdown: gravity in device axes, and the world-frame rotation vector."""
    rec = stroke.record
    t_ns = float(rec["samples"]["t"][0])
    f: dict = {}
    for name, key, n in [("gravity", "grav", 3), ("rotationVector", "rv", 4)]:
        v = sensor_at(rec, name, t_ns)
        if v is None and name == "gravity":
            v = sensor_at(rec, "accelerometer", t_ns)  # at rest, close enough to gravity
        f[f"shape.{key}_present"] = float(v is not None)
        vals = np.zeros(n) if v is None else np.asarray((list(v) + [0.0] * n)[:n], dtype=np.float64)
        if name == "gravity" and v is not None:
            vals = vals / max(np.linalg.norm(vals), 1e-6)
        for k in range(n):
            f[f"shape.{key}{k}"] = float(vals[k])
    return f


def sensor_at(rec: dict, name: str, input_t_ns: float, tol_ns: float = 250e6):
    """A sensor's value nearest an input-clock time, by timestamp; failing that by arrival time
    (schema 2's `a`), which survives a stream whose timestamps trail real time."""
    s = (rec.get("sensors") or {}).get(name)
    if not s or not s.get("t"):
        return None
    off = float(rec.get("clockOffsetNs", 0))
    for key in ("t", "a"):
        if key not in s:
            continue
        tt = np.asarray(s[key], dtype=np.float64) - off
        i = int(np.argmin(np.abs(tt - input_t_ns)))
        if abs(tt[i] - input_t_ns) <= tol_ns:
            return s["v"][i]
    return None


def _pointer_features(stroke: Stroke, prim: Track, ppm: float) -> tuple[dict, np.ndarray | None]:
    """The first other pointer's offset from the drawing contact: the hand-posture proxy."""
    others = stroke.record.get("pointers") or []
    f = {"shape.ptr_present": 0.0, "shape.ptr_dx": 0.0, "shape.ptr_dy": 0.0, "shape.ptr_dist": 0.0,
         "shape.ptr_palm": 0.0, "shape.ptr_count": float(len(others))}
    if not others:
        return f, None
    o = others[0]
    ot = Track.of(o["samples"], float(stroke.record["samples"]["t"][0]), ppm)
    off = ot.xy[0] - prim.at(ot.t[0])
    f.update({"shape.ptr_present": 1.0, "shape.ptr_dx": off[0] / 10, "shape.ptr_dy": off[1] / 10,
              "shape.ptr_dist": float(np.hypot(*off)) / 10,
              "shape.ptr_palm": float(bool(o.get("palm")) or o.get("tool") == "palm" or bool(o.get("canceled")))})
    return f, off


def _hover_features(stroke: Stroke, ppm: float) -> dict:
    hv = stroke.record.get("hover")
    f = {"shape.hover_present": 0.0, "shape.hover_ms": 0.0, "shape.hover_dist": 0.0,
         "shape.hover_cos": 0.0, "shape.hover_sin": 0.0, "shape.hover_tilt": 0.0}
    if not hv or len(hv.get("samples", {}).get("t", [])) < 2:
        return f
    t0 = float(stroke.record["samples"]["t"][0])
    h = Track.of(hv["samples"], t0, ppm)
    tail = h.t >= h.t[-1] - 50.0
    d = h.xy[tail][-1] - h.xy[tail][0]
    n = float(np.hypot(*d))
    f.update({"shape.hover_present": 1.0, "shape.hover_ms": float(h.t[-1] - h.t[0]) / 100,
              "shape.hover_dist": float(h.distance[-1]),
              "shape.hover_cos": d[0] / n if n > 0.1 else 0.0, "shape.hover_sin": d[1] / n if n > 0.1 else 0.0,
              "shape.hover_tilt": float(h.tilt[-1])})
    return f


def _kin_features(tr: Track, w: np.ndarray) -> dict:
    a = w[-1]
    t1 = tr.t[w[1]]
    pa = tr.xy[a]
    f = {"kin.hold_ms": (t1 - tr.t[0]) / 100, "kin.window_ms": (tr.t[a] - t1) / 100, "kin.n": float(len(w))}
    jump = tr.xy[w[1]] - tr.xy[0]
    f["kin.jump_dx"], f["kin.jump_dy"] = jump
    for k, at in enumerate(np.linspace(t1, tr.t[a], KIN_POINTS)):
        d = tr.at(at) - pa
        f[f"kin.p{k}_dx"], f[f"kin.p{k}_dy"] = d
    back = tr.at(max(tr.t[a] - 10.0, tr.t[0]))
    v = (pa - back) / max(min(10.0, tr.t[a] - tr.t[0]), 1.0) * 10.0  # mm per 10 ms
    f["kin.vx"], f["kin.vy"] = v
    f["kin.speed"] = float(np.hypot(*v))
    # Reference only (in no feature set): the raw direction the contact moved from touchdown to the
    # anchor -- what any drift-based pose cue has to beat to be more than early motion.
    m = pa - tr.xy[0]
    f["ref.motion_angle"] = math.atan2(m[1], m[0]) if np.hypot(*m) > 1e-3 else float("nan")
    f["ref.settle_only"] = float(len(w) > 2 and np.hypot(*(tr.xy[w[1]] - tr.xy[0])) < 1e-3)
    return f


# ---------------------------------------------------------------------------------------------
# Derived finger pose


@dataclass
class DeviceNorms:
    """Per-device medians, so 'small', 'hard' and 'large' mean the same on every touch panel."""
    logarea: float = 0.0
    pressure: float = 0.5
    ratio: float = 1.0


def device_norms(tracks: list[Track]) -> DeviceNorms:
    if not tracks:
        return DeviceNorms()
    td = [_shape_row(t, 0) for t in tracks]
    return DeviceNorms(float(np.median([r["logarea"] for r in td])),
                       float(np.median([r["pressure"] for r in td])),
                       float(np.median([r["ratio"] for r in td])))


def pitch(ratio: float) -> float:
    """The finger's angle to the glass, from contact elongation: a near-vertical fingertip makes a
    round contact (ratio 1 -> 90 deg), a flat finger an elongated one (ratio -> 0 -> 0 deg)."""
    return math.asin(min(max(ratio, 0.0), 1.0))


def contact_part(logarea_rel: float, ratio: float, pressure_rel: float) -> str:
    """Coarse contact part (README "Contact part"): nail/edge = very small and hard; side = elongated
    but not large; pad = large; tip = the rest (small to medium, round)."""
    if logarea_rel <= -0.5 and pressure_rel >= 0.0:
        return "nail"
    if ratio <= 0.6 and logarea_rel <= 0.4:
        return "side"
    if logarea_rel >= 0.4:
        return "pad"
    return "tip"


def _clip01(x: float) -> float:
    return min(max(x, 0.0), 1.0)


def _pose_features(tr: Track, w: np.ndarray, norms: DeviceNorms) -> dict:
    td, anc = _shape_row(tr, 0), _shape_row(tr, w[-1])
    rows = [_shape_row(tr, i) for i in w]
    rel_a = td["logarea"] - norms.logarea
    rel_p = td["pressure"] - norms.pressure
    f = {"pose.pitch_td": pitch(td["ratio"]), "pose.pitch_end": pitch(anc["ratio"]),
         "pose.pitch_min": min(pitch(r["ratio"]) for r in rows),
         "pose.area_rel": rel_a, "pose.ratio_td": td["ratio"],
         # Pressure beyond what the area explains: hard contact (nail) vs a soft broad pad.
         "pose.hardness": rel_p - 0.25 * rel_a}
    part = contact_part(rel_a, min(r["ratio"] for r in rows), rel_p)
    for p in PARTS:
        f[f"pose.part_{p}"] = float(part == p)
    span = max(tr.t[w[-1]] - tr.t[0], 1.0)
    grow = max(r["logarea"] for r in rows) - td["logarea"]
    elong = max(r["elong"] for r in rows) - td["elong"]
    # Tip only: small, round, pressed hard at touchdown, and it stays that way.
    f["pose.tip_only"] = (_clip01(-rel_a / 0.5 + 0.5) + _clip01(td["ratio"]) + _clip01(rel_p / 0.2 + 0.5)
                          + _clip01(1.0 - grow / 0.3)) / 4
    # Flatten: area and elongation grow after touchdown.
    f["pose.flatten_rate"] = (grow + elong) / span * 10.0
    f["pose.flatten"] = (_clip01(grow / 0.4) + _clip01(elong / 0.4)) / 2
    # Side roll: elongation rises (minor/major falls) while the area barely grows.
    f["pose.side_roll"] = _clip01(elong / 0.4) * _clip01(1.0 - max(grow, 0.0) / 0.4)
    return f


def settle_drift(tr: Track, w: np.ndarray) -> tuple[np.ndarray, float]:
    """Centroid displacement while the contact is settling -- samples in the window slower than
    SETTLE_MM_S -- and the log-area change over the same samples."""
    end = 0
    for k in range(1, len(w)):
        i, j = w[k - 1], w[k]
        dt = max(tr.t[j] - tr.t[i], 1e-3) / 1e3
        if np.hypot(*(tr.xy[j] - tr.xy[i])) / dt > SETTLE_MM_S:
            break
        end = k
    d = tr.xy[w[end]] - tr.xy[0]
    return d, _shape_row(tr, w[end])["logarea"] - _shape_row(tr, 0)["logarea"]


def _drift_features(tr: Track, w: np.ndarray, ptr_off: np.ndarray | None) -> dict:
    drift, dlog = settle_drift(tr, w)
    major = max(tr.major[0], 1e-3)
    dn = float(np.hypot(*drift))
    vecs, weights = [], []
    # Flattening pushes the contact back along the finger toward the hand, so the tip points
    # against the drift; lifting onto the tip does the reverse. No area change: sign unknown.
    if dn > 1e-3 and abs(dlog) > 0.05:
        vecs.append(-np.sign(dlog) * drift / dn)
        weights.append(_clip01(dn / (0.25 * major)))
    if ptr_off is not None and np.hypot(*ptr_off) > 1e-3:
        vecs.append(-ptr_off / np.hypot(*ptr_off))  # the finger points away from the palm
        weights.append(0.5)
    f = {"drift.dx": drift[0] / major, "drift.dy": drift[1] / major, "drift.norm": dn / major,
         "drift.dlogarea": dlog}
    if vecs:
        s = sum(wt * v for wt, v in zip(weights, vecs))
        n = float(np.hypot(*s))
        f["drift.yaw_cos"], f["drift.yaw_sin"] = (s / n) if n > 1e-9 else (0.0, 0.0)
        # Confidence: agreement of the cues (resultant length) times their strength.
        f["drift.yaw_conf"] = n / len(vecs)
    else:
        f["drift.yaw_cos"] = f["drift.yaw_sin"] = f["drift.yaw_conf"] = 0.0
    # Egg: MotionEvent only reports a symmetric ellipse, so asymmetry is read off indirectly --
    # the centroid drifting toward the narrow end while the size barely changes, on an elongated
    # contact. The narrow end leads, so the drift direction is the predicted lead direction.
    a = _clip01(dn / (0.25 * major))
    b = a * math.exp(-abs(dlog) / 0.2)
    c = _clip01(max(_shape_row(tr, i)["elong"] for i in w) / 0.5)
    f["drift.egg"] = (a + b + c) / 3
    f["drift.lead_cos"], f["drift.lead_sin"] = (drift / dn) if dn > 1e-3 else (0.0, 0.0)
    return f


# ---------------------------------------------------------------------------------------------


def onsets(strokes: list[Stroke], onset_ms: float = ONSET_MS, anchor: str = "first-report") -> list[Onset]:
    """One Onset per stroke; taps and strokes without a usable onset are marked unusable."""
    tracks = []
    for st in strokes:
        rec = st.record
        tracks.append(Track.of(rec["samples"], float(rec["samples"]["t"][0]), px_per_mm(st)))
    by_device: dict[str, list[Track]] = {}
    for st, tr in zip(strokes, tracks):
        by_device.setdefault(str(st.session.get("model")), []).append(tr)
    norms = {k: device_norms(v) for k, v in by_device.items()}
    out = []
    for st, tr in zip(strokes, tracks):
        o = Onset(st)
        out.append(o)
        if len(tr.t) < 4:
            o.usable, o.why = False, "fewer than 4 samples"
            continue
        g = geometry(tr)
        if g["path"] < MIN_PATH_MM:
            o.usable, o.why = False, "tap"
            continue
        w = window(tr, onset_ms, anchor)
        if w[-1] >= len(tr.t) - 1:
            o.usable, o.why = False, "ends inside the onset window"
            continue
        ppm = px_per_mm(st)
        pf, ptr_off = _pointer_features(st, tr, ppm)
        o.features.update(_shape_features(tr, w))
        o.features.update(_attitude_features(st))
        o.features.update(pf)
        o.features.update(_hover_features(st, ppm))
        tool = st.record.get("tool", "unknown")
        o.features["shape.tool_finger"] = float(tool == "finger")
        o.features["shape.tool_stylus"] = float(tool == "stylus")
        o.features.update(_kin_features(tr, w))
        o.features.update(_pose_features(tr, w, norms[str(st.session.get("model"))]))
        o.features.update(_drift_features(tr, w, ptr_off))
        t0_ns = float(st.record["samples"]["t"][0])
        o.features.update(hm.features(st, t0_ns + tr.t[w[-1]] * 1e6))
        o.targets = _targets(tr, int(w[-1]))
        o.labels = {"type": stroke_type(g), "geometry": g,
                    "part": next(p for p in PARTS if o.features[f"pose.part_{p}"] == 1.0)}
    return out


def matrix(items: list[Onset], prefixes: tuple[str, ...]) -> tuple[np.ndarray, list[str]]:
    names = sorted(k for k in items[0].features if k.startswith(prefixes))
    X = np.asarray([[o.features[k] for k in names] for o in items], dtype=np.float64)
    return X, names
