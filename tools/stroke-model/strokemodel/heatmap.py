"""The raw touch heatmap (schema v3 `heatmap`, SCHEMA.md): decoding and contact-image moments.

MotionEvent reports a symmetric ellipse, and on the Pixel 5 not even its orientation. The heatmap is
the controller's capacitive image, so the contact's real shape can be measured from it:

  centroid     intensity-weighted mean position (mm)
  covariance   -> orientation of the major axis (axial, mod 180 deg) and elongation 1 - sqrt(l2/l1)
  skewness     |third standardised moment| along the major axis (the eigenvector's sign is arbitrary). An egg-shaped contact has its mass at
               the blunt end and a long thin tail toward the narrow end, so the skew is positive
               toward the narrow end. The owner's rule is that the narrow end leads, so
               sign(skew) * major axis is the predicted LEAD direction.
  area, peak   cells above the threshold (mm^2), and the strongest cell

Grid geometry (UNVERIFIED until the helper runs on a device): the grid is assumed to span the whole
screen, columns along the screen's x and rows along its y. If the grid's long side disagrees with
the screen's, the frames are transposed. Cell pitch = screen size in mm / cells.
"""
from __future__ import annotations

import base64
import math

import numpy as np

MIN_PEAK = 40.0          # counts; below this a frame is treated as "no contact" (noise only)
REL_THRESHOLD = 0.15     # cells below this fraction of the peak are ignored (fringe and noise)
TOUCHDOWN_SLACK_NS = 20e6  # a contact frame this long before ACTION_DOWN still counts as touchdown


def decode(hm: dict, session: dict | None = None) -> tuple[np.ndarray, np.ndarray, tuple[float, float]]:
    """(t [N] uptime ns, frames [N, rows, cols] float, (mm per column, mm per row))."""
    w, h = int(hm["w"]), int(hm["h"])
    if hm.get("dtype", "int16le") != "int16le":
        raise ValueError(f"unsupported heatmap dtype {hm.get('dtype')}")
    raw = np.frombuffer(base64.b64decode(hm["frames"]), dtype="<i2")
    t = np.asarray(hm["t"], dtype=np.float64)
    n = min(len(t), raw.size // max(w * h, 1))
    frames = raw[: n * w * h].reshape(n, h, w).astype(np.float64)
    t = t[:n]
    s = session or {}
    wpx, hpx = float(s.get("widthPx") or 0), float(s.get("heightPx") or 0)
    xdpi, ydpi = float(s.get("xdpi") or 0), float(s.get("ydpi") or 0)
    if wpx > 0 and hpx > 0 and (w > h) != (wpx > hpx):
        frames = frames.transpose(0, 2, 1)
        w, h = h, w
    if wpx > 0 and hpx > 0 and xdpi > 0 and ydpi > 0:
        pitch = (wpx / xdpi * 25.4 / w, hpx / ydpi * 25.4 / h)
    else:
        pitch = (1.0, 1.0)  # unknown screen: cell units
    return t, frames, pitch


def moments(frame: np.ndarray, pitch: tuple[float, float] = (1.0, 1.0)) -> dict | None:
    """Contact-image moments of one frame (rows x cols); None when nothing touches."""
    v = np.asarray(frame, dtype=np.float64)
    peak = float(v.max())
    if peak < MIN_PEAK:
        return None
    thr = REL_THRESHOLD * peak
    wgt = np.clip(v - thr, 0.0, None)
    m0 = wgt.sum()
    rows, cols = np.indices(v.shape)
    X = (cols + 0.5) * pitch[0]
    Y = (rows + 0.5) * pitch[1]
    cx, cy = (wgt * X).sum() / m0, (wgt * Y).sum() / m0
    dx, dy = X - cx, Y - cy
    cxx, cyy, cxy = (wgt * dx * dx).sum() / m0, (wgt * dy * dy).sum() / m0, (wgt * dx * dy).sum() / m0
    evals, evecs = np.linalg.eigh(np.asarray([[cxx, cxy], [cxy, cyy]]))
    l2, l1 = max(float(evals[0]), 0.0), max(float(evals[1]), 1e-12)
    e1 = evecs[:, 1]
    u = dx * e1[0] + dy * e1[1]
    skew = float((wgt * u ** 3).sum() / m0 / l1 ** 1.5)
    lead = e1 * (1.0 if skew >= 0 else -1.0)
    return {
        "cx": float(cx), "cy": float(cy),
        "theta": float(math.atan2(e1[1], e1[0]) % math.pi),   # major axis, axial
        "elong": 1.0 - math.sqrt(l2 / l1),
        "skew": abs(skew),                                     # egg asymmetry; its sign is in `lead`
        "lead": float(math.atan2(lead[1], lead[0])),           # toward the narrow end
        "area": float((v > thr).sum() * pitch[0] * pitch[1]),
        "peak": peak,
        "major_sd": math.sqrt(l1), "minor_sd": math.sqrt(l2),
    }


FEATURE_KEYS = ["present", "n", "td_cos2", "td_sin2", "td_elong", "td_skew", "td_logarea", "td_logpeak",
                "end_cos2", "end_sin2", "end_elong", "end_skew", "end_logarea", "end_logpeak",
                "d_elong", "d_logarea", "lead_cos", "lead_sin", "lead_conf"]


def features(stroke, t_end_ns: float) -> dict:
    """heatmap.* onset features from the frames between touchdown and `t_end_ns` (the onset
    window's end, uptime ns). Always the same keys; zeros with `heatmap.present` = 0 without data."""
    f = {f"heatmap.{k}": 0.0 for k in FEATURE_KEYS}
    hm = stroke.record.get("heatmap")
    if not hm or not hm.get("t"):
        return f
    try:
        t, frames, pitch = decode(hm, stroke.session)
    except (ValueError, KeyError):
        return f
    t0 = float(stroke.record["samples"]["t"][0])
    ms = [(ti, moments(fr, pitch)) for ti, fr in zip(t, frames) if t0 - TOUCHDOWN_SLACK_NS <= ti <= t_end_ns]
    ms = [(ti, m) for ti, m in ms if m is not None]
    if not ms:
        return f
    td, end = ms[0][1], ms[-1][1]
    f["heatmap.present"] = 1.0
    f["heatmap.n"] = float(len(ms))
    for tag, m in (("td", td), ("end", end)):
        f[f"heatmap.{tag}_cos2"] = math.cos(2 * m["theta"]) * m["elong"]
        f[f"heatmap.{tag}_sin2"] = math.sin(2 * m["theta"]) * m["elong"]
        f[f"heatmap.{tag}_elong"] = m["elong"]
        f[f"heatmap.{tag}_skew"] = m["skew"]
        f[f"heatmap.{tag}_logarea"] = math.log(max(m["area"], 1e-3))
        f[f"heatmap.{tag}_logpeak"] = math.log(max(m["peak"], 1.0))
    f["heatmap.d_elong"] = end["elong"] - td["elong"]
    f["heatmap.d_logarea"] = f["heatmap.end_logarea"] - f["heatmap.td_logarea"]
    # Lead: the window's frames vote with weight |skew|; confidence is their agreement x strength.
    vec = np.zeros(2)
    total = 0.0
    for _, m in ms:
        wgt = min(abs(m["skew"]), 1.0) * m["elong"]
        vec += wgt * np.asarray([math.cos(m["lead"]), math.sin(m["lead"])])
        total += 1.0
    n = float(np.hypot(*vec))
    if n > 1e-9:
        f["heatmap.lead_cos"], f["heatmap.lead_sin"] = vec / n
        f["heatmap.lead_conf"] = n / total
    return f
