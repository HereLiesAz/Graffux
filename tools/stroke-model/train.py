"""Trains the stroke predictor and reports per-horizon error against baselines.

  python train.py DATA_DIR [--epochs 30] [--out model.onnx]

Splits by file (session), so a session never appears in both train and validation.
Baselines: constant-velocity extrapolation from the last two samples, and a least-squares linear
model on the same features (what the network has to beat to be worth shipping).
"""
from __future__ import annotations

import argparse
import random
from pathlib import Path

import numpy as np
import torch

from strokemodel.data import HORIZONS, POS_SCALE, build_arrays, read_file
from strokemodel.model import StrokePredictor


def split_files(root: Path, val_frac: float, seed: int):
    files = sorted(root.rglob("*.jsonl.gz"))
    if len(files) < 2:
        raise SystemExit("need at least two session files to split train/validation")
    random.Random(seed).shuffle(files)
    n_val = max(1, int(len(files) * val_frac))
    load = lambda fs: [s for f in fs for s in read_file(f)]  # noqa: E731
    return load(files[n_val:]), load(files[:n_val])


def px_error(pred: np.ndarray, y: np.ndarray, m: np.ndarray) -> list[float]:
    d = np.linalg.norm(pred - y, axis=2) * POS_SCALE
    return [float((d[:, h] * m[:, h]).sum() / max(m[:, h].sum(), 1)) for h in range(HORIZONS)]


def const_velocity(H: np.ndarray, frame_units: np.ndarray) -> np.ndarray:
    # history[-1] is the anchor (offset 0); history[-2] is the previous sample.
    prev = H[:, -2, :2]
    dt = np.maximum(H[:, -2, 2], 1e-3)  # previous sample's age in TIME_SCALE units
    v = -prev / dt[:, None]
    steps = frame_units[:, None] * np.arange(1, HORIZONS + 1)[None, :]
    return v[:, None, :] * steps[:, :, None]


def flat(H, S, C):
    return np.concatenate([H.reshape(len(H), -1), S, C, np.ones((len(H), 1), np.float32)], axis=1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("data", type=Path)
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--batch", type=int, default=512)
    ap.add_argument("--val", type=float, default=0.2)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--out", type=Path, default=Path("stroke_predictor.onnx"))
    a = ap.parse_args()
    torch.manual_seed(a.seed)

    train_s, val_s = split_files(a.data, a.val, a.seed)
    tr = build_arrays(train_s)
    va = build_arrays(val_s)
    print(f"examples: train {len(tr[0])}, val {len(va[0])}")

    Hv, Sv, Cv, Yv, Mv = va
    frame_units = Cv[:, -2]  # frame ms / TIME_SCALE_MS
    print("const-velocity px:", ["%.2f" % e for e in px_error(const_velocity(Hv, frame_units), Yv, Mv)])
    W, *_ = np.linalg.lstsq(flat(*tr[:3]), tr[3].reshape(len(tr[3]), -1), rcond=1e-3)
    lin = (flat(Hv, Sv, Cv) @ W).reshape(-1, HORIZONS, 2)
    print("linear px:        ", ["%.2f" % e for e in px_error(lin, Yv, Mv)])

    model = StrokePredictor()
    opt = torch.optim.AdamW(model.parameters(), lr=2e-3, weight_decay=1e-4)
    T = [torch.from_numpy(x) for x in tr]
    V = [torch.from_numpy(x) for x in va]
    for epoch in range(a.epochs):
        model.train()
        perm = torch.randperm(len(T[0]))
        for i in range(0, len(perm), a.batch):
            idx = perm[i:i + a.batch]
            h, s, c, y, m = (t[idx] for t in T)
            err = torch.linalg.norm(model(h, s, c) - y, dim=2)
            loss = (torch.nn.functional.huber_loss(err, torch.zeros_like(err), reduction="none") * m).sum() / m.sum()
            opt.zero_grad()
            loss.backward()
            opt.step()
        model.eval()
        with torch.no_grad():
            pred = model(*V[:3]).numpy()
        print(f"epoch {epoch + 1:3d} model px:", ["%.2f" % e for e in px_error(pred, Yv, Mv)])

    model.eval()
    torch.onnx.export(model, tuple(V[i][:1] for i in range(3)), a.out,
                      input_names=["history", "sensors", "context"], output_names=["offsets"],
                      dynamic_axes={n: {0: "batch"} for n in ["history", "sensors", "context", "offsets"]})
    print(f"wrote {a.out}")


if __name__ == "__main__":
    main()
