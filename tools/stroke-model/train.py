"""Trains the stroke predictor and reports per-horizon error against baselines.

  python train.py DATA_DIR [--epochs 30] [--out model.onnx] [--report metrics.json]

Splits by file (session), so a session never appears in both train and validation. With a single
session, --split strokes splits its strokes instead (within-session numbers: optimistic).
Baselines, each scored at every horizon (1..HORIZONS display frames ahead):
  - last point: the pen stays where it last was (offset 0) -- what drawing with no prediction shows;
  - constant velocity: extrapolation from the last two samples;
  - linear: least squares on the same features (what the network has to beat to be worth shipping).
The model is exported to ONNX (inputs history/sensors/context, output offsets) and checked by
onnx.checker when the onnx package is installed.
"""
from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

import numpy as np
import torch

from strokemodel.data import HORIZONS, POS_SCALE, build_arrays, read_file
from strokemodel.model import StrokePredictor

BASELINES = ("last_point", "const_velocity", "linear")


def split_files(root: Path, val_frac: float, seed: int, by: str = "session"):
    files = sorted(Path(root).rglob("*.jsonl.gz"))
    if by == "strokes":
        strokes = [s for f in files for s in read_file(f)]
        random.Random(seed).shuffle(strokes)
        n_val = max(1, int(len(strokes) * val_frac))
        print(f"split: by strokes ({len(strokes)} strokes, {len(files)} session(s)) -- within-session, optimistic")
        return strokes[n_val:], strokes[:n_val]
    if len(files) < 2:
        raise SystemExit("need at least two session files to split train/validation (or --split strokes)")
    random.Random(seed).shuffle(files)
    n_val = max(1, int(len(files) * val_frac))
    load = lambda fs: [s for f in fs for s in read_file(f)]  # noqa: E731
    return load(files[n_val:]), load(files[:n_val])


def px_error(pred: np.ndarray, y: np.ndarray, m: np.ndarray) -> list[float]:
    """Mean Euclidean error in pixels at each horizon, over the examples that have it."""
    d = np.linalg.norm(pred - y, axis=2) * POS_SCALE
    return [float((d[:, h] * m[:, h]).sum() / max(m[:, h].sum(), 1)) for h in range(HORIZONS)]


def last_point(H: np.ndarray) -> np.ndarray:
    # Offsets are relative to the anchor, so "stay at the last sample" is all zeros.
    return np.zeros((len(H), HORIZONS, 2), dtype=np.float32)


def const_velocity(H: np.ndarray, frame_units: np.ndarray) -> np.ndarray:
    # history[-1] is the anchor (offset 0); history[-2] is the previous sample.
    prev = H[:, -2, :2]
    dt = np.maximum(H[:, -2, 2], 1e-3)  # previous sample's age in TIME_SCALE units
    v = -prev / dt[:, None]
    steps = frame_units[:, None] * np.arange(1, HORIZONS + 1)[None, :]
    return v[:, None, :] * steps[:, :, None]


def flat(H, S, C):
    return np.concatenate([H.reshape(len(H), -1), S, C, np.ones((len(H), 1), np.float32)], axis=1)


def fmt(errs: list[float]) -> list[str]:
    return ["%.2f" % e for e in errs]


def export_onnx(model: StrokePredictor, example: tuple, out: Path) -> None:
    model.eval()
    torch.onnx.export(model, example, str(out),
                      input_names=["history", "sensors", "context"], output_names=["offsets"],
                      dynamic_axes={n: {0: "batch"} for n in ["history", "sensors", "context", "offsets"]})
    try:
        import onnx
    except ImportError:
        return
    onnx.checker.check_model(onnx.load(str(out)))


def train(train_s, val_s, epochs: int = 30, batch: int = 512, seed: int = 0, out: Path | None = None,
          log=print) -> dict:
    """Fits the baselines and the model on `train_s`, scores all of them per horizon on `val_s`
    (pixel error, one value per horizon), exports the model to `out` when given. Returns
    `{"horizons", "examples", "baselines": {name: [px]}, "model": [px], "history": [[px] per epoch], "onnx"}`."""
    torch.manual_seed(seed)
    tr = build_arrays(train_s)
    va = build_arrays(val_s)
    log(f"examples: train {len(tr[0])}, val {len(va[0])}")

    Hv, Sv, Cv, Yv, Mv = va
    frame_units = Cv[:, -2]  # frame ms / TIME_SCALE_MS
    W, *_ = np.linalg.lstsq(flat(*tr[:3]), tr[3].reshape(len(tr[3]), -1), rcond=1e-3)
    baselines = {
        "last_point": px_error(last_point(Hv), Yv, Mv),
        "const_velocity": px_error(const_velocity(Hv, frame_units), Yv, Mv),
        "linear": px_error((flat(Hv, Sv, Cv) @ W).reshape(-1, HORIZONS, 2), Yv, Mv),
    }
    for name in BASELINES:
        log(f"{name + ' px:':<20}{fmt(baselines[name])}")

    model = StrokePredictor()
    opt = torch.optim.AdamW(model.parameters(), lr=2e-3, weight_decay=1e-4)
    T = [torch.from_numpy(x) for x in tr]
    V = [torch.from_numpy(x) for x in va]
    history: list[list[float]] = []
    for epoch in range(epochs):
        model.train()
        perm = torch.randperm(len(T[0]))
        for i in range(0, len(perm), batch):
            idx = perm[i:i + batch]
            h, s, c, y, m = (t[idx] for t in T)
            err = torch.linalg.norm(model(h, s, c) - y, dim=2)
            loss = (torch.nn.functional.huber_loss(err, torch.zeros_like(err), reduction="none") * m).sum() / m.sum()
            opt.zero_grad()
            loss.backward()
            opt.step()
        model.eval()
        with torch.no_grad():
            pred = model(*V[:3]).numpy()
        history.append(px_error(pred, Yv, Mv))
        log(f"epoch {epoch + 1:3d} model px: {fmt(history[-1])}")

    result = {"horizons": list(range(1, HORIZONS + 1)), "examples": {"train": len(tr[0]), "val": len(va[0])},
              "baselines": baselines, "model": history[-1] if history else [], "history": history, "onnx": None}
    if out is not None:
        export_onnx(model, tuple(V[i][:1] for i in range(3)), out)
        result["onnx"] = str(out)
        log(f"wrote {out}")
    return result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("data", type=Path)
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--batch", type=int, default=512)
    ap.add_argument("--val", type=float, default=0.2)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--split", choices=["session", "strokes"], default="session")
    ap.add_argument("--out", type=Path, default=Path("stroke_predictor.onnx"))
    ap.add_argument("--report", type=Path, help="also write the per-horizon metrics as JSON")
    a = ap.parse_args()

    train_s, val_s = split_files(a.data, a.val, a.seed, a.split)
    result = train(train_s, val_s, epochs=a.epochs, batch=a.batch, seed=a.seed, out=a.out)
    if a.report:
        a.report.write_text(json.dumps(result, indent=2))
        print(f"wrote {a.report}")


if __name__ == "__main__":
    main()
