"""Small, dependency-free (numpy) models and statistics for the onset ablation."""
from __future__ import annotations

import math

import numpy as np

ALPHAS = (0.3, 3.0, 30.0, 300.0)


class Scaler:
    def __init__(self, X: np.ndarray):
        self.mu = X.mean(axis=0)
        sd = X.std(axis=0)
        self.sd = np.where(sd > 1e-9, sd, 1.0)

    def __call__(self, X: np.ndarray) -> np.ndarray:
        return (X - self.mu) / self.sd


def ridge_fit(X: np.ndarray, Y: np.ndarray, alpha: float, w: np.ndarray | None = None):
    """Ridge on standardised X with an unpenalised intercept; optional sample weights."""
    sc = Scaler(X)
    Z = sc(X)
    w = np.ones(len(Z)) if w is None else w
    ym = (w[:, None] * Y).sum(0) / w.sum()
    zm = (w[:, None] * Z).sum(0) / w.sum()
    Zc, Yc = Z - zm, Y - ym
    A = (Zc * w[:, None]).T @ Zc + alpha * np.eye(Z.shape[1])
    B = np.linalg.solve(A, (Zc * w[:, None]).T @ Yc)
    return lambda Xn: (sc(Xn) - zm) @ B + ym


def ridge_cv(X: np.ndarray, Y: np.ndarray, seed: int = 0, folds: int = 4):
    """Ridge with alpha chosen by inner K-fold on the training data only."""
    if X.shape[1] == 0:
        m = Y.mean(0)
        return lambda Xn: np.repeat(m[None], len(Xn), 0)
    n = len(X)
    idx = np.random.default_rng(seed).permutation(n)
    parts = np.array_split(idx, min(folds, n))
    best, best_err = ALPHAS[0], math.inf
    for a in ALPHAS:
        err = 0.0
        for p in parts:
            tr = np.setdiff1d(idx, p)
            if len(tr) < 3:
                continue
            f = ridge_fit(X[tr], Y[tr], a)
            err += float(((f(X[p]) - Y[p]) ** 2).sum())
        if err < best_err:
            best, best_err = a, err
    return ridge_fit(X, Y, best)


def ridge_classifier(X: np.ndarray, y: np.ndarray, classes: list[str], alpha: float = 30.0):
    """One-vs-rest least squares on one-hot targets, classes weighted to balance."""
    Y = np.asarray([[1.0 if c == k else 0.0 for k in classes] for c in y])
    counts = Y.sum(0)
    w = np.asarray([1.0 / max(counts[classes.index(c)], 1.0) for c in y])
    f = ridge_fit(X, Y, alpha, w)
    present = counts > 0

    def predict(Xn):
        s = f(Xn)
        s[:, ~present] = -np.inf
        return np.asarray([classes[i] for i in s.argmax(1)])
    return predict


# ---------------------------------------------------------------------------------------------
# Circular statistics (angles in radians)


def ang_err(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """Absolute angular difference in degrees, 0..180."""
    return np.degrees(np.abs((np.asarray(a) - np.asarray(b) + np.pi) % (2 * np.pi) - np.pi))


def axial_err(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """Angular difference ignoring sign (a line, not an arrow), 0..90 degrees."""
    e = ang_err(a, b)
    return np.minimum(e, 180.0 - e)


def circ_mean(a: np.ndarray) -> float:
    return math.atan2(float(np.sin(a).mean()), float(np.cos(a).mean()))


def circ_corr(a: np.ndarray, b: np.ndarray) -> float:
    """Jammalamadaka-SenGupta circular correlation coefficient."""
    sa = np.sin(a - circ_mean(a))
    sb = np.sin(b - circ_mean(b))
    d = math.sqrt(float((sa ** 2).sum() * (sb ** 2).sum()))
    return float((sa * sb).sum() / d) if d > 0 else 0.0


def perm_p_angle(pred: np.ndarray, true: np.ndarray, n: int = 5000, seed: int = 0) -> float:
    """P(median angular error of a random pairing <= observed): is the match better than chance?"""
    obs = np.median(ang_err(pred, true))
    rng = np.random.default_rng(seed)
    hits = sum(np.median(ang_err(rng.permutation(pred), true)) <= obs for _ in range(n))
    return (hits + 1) / (n + 1)


def auc(score: np.ndarray, label: np.ndarray) -> float:
    pos, neg = score[label], score[~label]
    if len(pos) == 0 or len(neg) == 0:
        return float("nan")
    gt = (pos[:, None] > neg[None, :]).sum() + 0.5 * (pos[:, None] == neg[None, :]).sum()
    return float(gt / (len(pos) * len(neg)))


def perm_p_auc(score: np.ndarray, label: np.ndarray, n: int = 5000, seed: int = 0) -> float:
    obs = abs(auc(score, label) - 0.5)
    rng = np.random.default_rng(seed)
    hits = sum(abs(auc(score, rng.permutation(label)) - 0.5) >= obs for _ in range(n))
    return (hits + 1) / (n + 1)


# ---------------------------------------------------------------------------------------------
# Splits


def stroke_folds(n: int, k: int, repeats: int, seed: int):
    """Repeated K-fold over strokes: yields (repeat, train_idx, test_idx)."""
    for r in range(repeats):
        idx = np.random.default_rng(seed + r).permutation(n)
        for p in np.array_split(idx, k):
            yield r, np.setdiff1d(idx, p), p


def session_folds(groups: list[str]):
    """Leave one session out: yields (0, train_idx, test_idx)."""
    g = np.asarray(groups)
    for s in sorted(set(groups)):
        yield 0, np.nonzero(g != s)[0], np.nonzero(g == s)[0]
