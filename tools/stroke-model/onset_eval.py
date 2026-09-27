"""Whole-stroke prediction from the onset: the ablation behind the pose-first thesis.

  python onset_eval.py DATA_DIR [--split session|strokes] [--onset-ms 30] [--out report.md]

Tasks, each predicted from the onset window only (strokemodel/onset.py):
  direction   the stroke's direction over the next 50/100/200 ms after the window (angular error)
  length      total path length (relative error)
  curvature   mean |turning| per mm (relative error)
  end point   end relative to touchdown (mm error, relative error)
  type        scratch / wipe / detail / other from the first 30/60/100 ms (per-class P/R, confusion)
plus two direct tests of the owner's rules (no fitting): onset yaw vs initial direction, and the
egg shape (eggness vs fast-and-controlled strokes, egg lead direction vs initial direction).

Feature sets compared: a trivial baseline (mean direction / mean / majority class), kinematics only,
contact shape only (no position or velocity), shape + kinematics, and the derived finger pose; plus,
when any stroke carries a raw touch heatmap (schema v3), "heatmap" (contact-image moments,
strokemodel/heatmap.py) and heatmap + derived pose, and a direct test of the heatmap egg lead.

Split: by session (leave one session out) by default. With a single session that is impossible, and
--split strokes does repeated K-fold over strokes instead: strokes from the same sitting share hand,
grip and habit, so those numbers are optimistic about new sessions and say so in the report.
"""
from __future__ import annotations

import argparse
import collections
import math
from pathlib import Path

import numpy as np

from strokemodel import evalkit as ek
from strokemodel.data import read_dir
from strokemodel.onset import DIR_HORIZONS_MS, ONSET_MS, STROKE_TYPES, Onset, matrix, onsets

SETS = [
    ("kinematics only", ("kin.",)),
    ("contact shape only", ("shape.",)),
    ("shape + kinematics", ("shape.", "kin.")),
    ("pose from shape only (pitch/part/transitions)", ("pose.",)),
    ("derived pose (pitch/yaw/part)", ("pose.", "drift.")),
    ("derived pose + kinematics", ("pose.", "drift.", "kin.")),
]
CLASS_SETS = [
    ("kinematics only", ("kin.",)),
    ("contact shape only", ("shape.",)),
    ("derived pose only", ("pose.", "drift.")),
    ("pose + kinematics", ("pose.", "drift.", "kin.")),
]
HEATMAP_SETS = [  # used only when some strokes carry a heatmap (schema v3, root)
    ("heatmap", ("heatmap.",)),
    ("heatmap + derived pose", ("heatmap.", "pose.", "drift.")),
]
TYPE_WINDOWS_MS = (30.0, 60.0, 100.0)


def fmt_p(p: float) -> str:
    return "< 0.001" if p < 0.001 else f"{p:.3f}"


class Report:
    def __init__(self):
        self.lines: list[str] = []

    def __call__(self, s: str = ""):
        print(s)
        self.lines.append(s)


def folds(items: list[Onset], split: str, k: int, repeats: int, seed: int):
    if split == "session":
        return list(ek.session_folds([o.stroke.session.get("path", "") for o in items]))
    return list(ek.stroke_folds(len(items), k, repeats, seed))


def _per_repeat(errs: dict[int, list[float]], fn) -> tuple[float, float]:
    vals = [fn(np.asarray(v)) for v in errs.values() if v]
    return float(np.mean(vals)), float(np.std(vals))


def direction(rep: Report, items: list[Onset], a) -> None:
    for h in DIR_HORIZONS_MS:
        key = f"dir{int(h)}"
        sub = [o for o in items if o.targets.get(key) is not None]
        if len(sub) < 10:
            rep(f"direction +{int(h)} ms: only {len(sub)} strokes, skipped")
            continue
        y = np.asarray([o.targets[key] for o in sub])
        Y = np.stack([np.cos(y), np.sin(y)], 1)
        rows = collections.defaultdict(lambda: collections.defaultdict(list))
        for r, tr, te in folds(sub, a.split, a.folds, a.repeats, a.seed):
            rows["baseline: mean direction"][r].extend(ek.ang_err(np.full(len(te), ek.circ_mean(y[tr])), y[te]))
            for name, pre in SETS:
                X, _ = matrix(sub, pre)
                f = ek.ridge_cv(X[tr], Y[tr], seed=a.seed + r)
                p = f(X[te])
                rows[name][r].extend(ek.ang_err(np.arctan2(p[:, 1], p[:, 0]), y[te]))
        rep(f"\n### Direction over the next {int(h)} ms (n = {len(sub)} strokes)\n")
        rep("| features | median err ° | mean err ° | within 45° |")
        rep("|---|---|---|---|")
        for name, errs in rows.items():
            med, sd = _per_repeat(errs, np.median)
            mean, _ = _per_repeat(errs, np.mean)
            w45, _ = _per_repeat(errs, lambda e: 100 * (e < 45).mean())
            rep(f"| {name} | {med:.1f} ± {sd:.1f} | {mean:.1f} | {w45:.0f}% |")


def scalar(rep: Report, items: list[Onset], a, key: str, title: str, floor: float) -> None:
    sub = [o for o in items if o.targets.get(key) is not None and o.targets[key] > floor]
    y = np.log(np.asarray([o.targets[key] for o in sub]))
    rows = collections.defaultdict(lambda: collections.defaultdict(list))
    for r, tr, te in folds(sub, a.split, a.folds, a.repeats, a.seed):
        true = np.exp(y[te])
        rows["baseline: mean"][r].extend(np.abs(np.exp(y[tr].mean()) - true) / true)
        for name, pre in SETS:
            X, _ = matrix(sub, pre)
            p = ek.ridge_cv(X[tr], y[tr, None], seed=a.seed + r)(X[te])[:, 0]
            rows[name][r].extend(np.abs(np.exp(p) - true) / true)
    rep(f"\n### {title} (n = {len(sub)} strokes; fitted on log scale)\n")
    rep("| features | median relative error | mean relative error |")
    rep("|---|---|---|")
    for name, errs in rows.items():
        med, sd = _per_repeat(errs, np.median)
        mean, _ = _per_repeat(errs, np.mean)
        rep(f"| {name} | {med:.2f} ± {sd:.2f} | {mean:.2f} |")


def endpoint(rep: Report, items: list[Onset], a) -> None:
    Y = np.asarray([o.targets["end"] for o in items])
    norm = np.maximum(np.linalg.norm(Y, axis=1), 0.5)
    rows = collections.defaultdict(lambda: collections.defaultdict(list))
    rel = collections.defaultdict(lambda: collections.defaultdict(list))
    for r, tr, te in folds(items, a.split, a.folds, a.repeats, a.seed):
        e = np.linalg.norm(Y[tr].mean(0) - Y[te], axis=1)
        rows["baseline: mean"][r].extend(e)
        rel["baseline: mean"][r].extend(e / norm[te])
        for name, pre in SETS:
            X, _ = matrix(items, pre)
            e = np.linalg.norm(ek.ridge_cv(X[tr], Y[tr], seed=a.seed + r)(X[te]) - Y[te], axis=1)
            rows[name][r].extend(e)
            rel[name][r].extend(e / norm[te])
    rep(f"\n### End point relative to touchdown (n = {len(items)} strokes)\n")
    rep("| features | median error mm | mean error mm | median relative error |")
    rep("|---|---|---|---|")
    for name in rows:
        med, sd = _per_repeat(rows[name], np.median)
        mean, _ = _per_repeat(rows[name], np.mean)
        rmed, _ = _per_repeat(rel[name], np.median)
        rep(f"| {name} | {med:.1f} ± {sd:.1f} | {mean:.1f} | {rmed:.2f} |")


# ---------------------------------------------------------------------------------------------


def owner_rules(f: dict) -> str:
    """The painter's rules, hand-coded, no fitting. Thresholds in README "Owner's rules"."""
    if f["pose.side_roll"] >= 0.5 or f["pose.part_side"] == 1.0:
        return "detail"
    if f["pose.flatten"] >= 0.5:
        return "wipe"
    if f["pose.tip_only"] >= 0.7:
        return "scratch"
    return "other"


def class_metrics(true: list[str], pred: list[str], classes: list[str]) -> dict:
    t, p = np.asarray(true), np.asarray(pred)
    cm = np.asarray([[int(((t == a) & (p == b)).sum()) for b in classes] for a in classes])
    prec = {c: cm[i, i] / max(cm[:, i].sum(), 1) for i, c in enumerate(classes)}
    rec = {c: cm[i, i] / max(cm[i].sum(), 1) for i, c in enumerate(classes)}
    f1 = [2 * prec[c] * rec[c] / max(prec[c] + rec[c], 1e-9) for c in classes if cm[classes.index(c)].sum()]
    present = [c for c in classes if cm[classes.index(c)].sum()]
    return {"cm": cm, "prec": prec, "rec": rec, "acc": float((t == p).mean()),
            "bacc": float(np.mean([rec[c] for c in present])), "f1": float(np.mean(f1))}


def classification(rep: Report, strokes, a) -> None:
    for wms in TYPE_WINDOWS_MS:
        items = [o for o in onsets(strokes, wms, a.anchor) if o.usable]
        y = [o.labels["type"] for o in items]
        preds: dict[str, dict[int, tuple[list, list]]] = collections.defaultdict(
            lambda: collections.defaultdict(lambda: ([], [])))
        for r, tr, te in folds(items, a.split, a.folds, a.repeats, a.seed):
            ytr = [y[i] for i in tr]
            maj = collections.Counter(ytr).most_common(1)[0][0]
            for name, fn in [("majority class", lambda i: maj), ("owner's rules (no fitting)",
                                                                  lambda i: owner_rules(items[i].features))]:
                preds[name][r][0].extend(y[i] for i in te)
                preds[name][r][1].extend(fn(i) for i in te)
            for name, pre in CLASS_SETS:
                X, _ = matrix(items, pre)
                f = ek.ridge_classifier(X[tr], np.asarray(ytr), STROKE_TYPES)
                preds[name][r][0].extend(y[i] for i in te)
                preds[name][r][1].extend(f(X[te]))
        rep(f"\n### Stroke type from the first {int(wms)} ms after the first report (n = {len(items)})\n")
        rep("| predictor | accuracy | balanced acc. | macro F1 | " +
            " | ".join(f"{c} P/R" for c in STROKE_TYPES) + " |")
        rep("|---|---|---|---|" + "---|" * len(STROKE_TYPES))
        cms = {}
        for name, per in preds.items():
            ms = [class_metrics(t, p, STROKE_TYPES) for t, p in per.values()]
            cms[name] = sum(m["cm"] for m in ms) / len(ms)
            pr = " | ".join(f"{np.mean([m['prec'][c] for m in ms]):.2f}/{np.mean([m['rec'][c] for m in ms]):.2f}"
                            for c in STROKE_TYPES)
            rep(f"| {name} | {np.mean([m['acc'] for m in ms]):.2f} | {np.mean([m['bacc'] for m in ms]):.2f} | "
                f"{np.mean([m['f1'] for m in ms]):.2f} | {pr} |")
        rep("\nConfusion matrices (rows true, columns predicted: " + ", ".join(STROKE_TYPES) +
            "; counts averaged over repeats):\n")
        rep("~~~")
        for name, cm in cms.items():
            rep(f"{name}:")
            for c, row in zip(STROKE_TYPES, cm):
                rep(f"  {c:8s} " + " ".join(f"{v:5.1f}" for v in row))
        rep("~~~")


# ---------------------------------------------------------------------------------------------


def _motion_ref(sub: list[Onset], d: np.ndarray) -> str:
    m = np.asarray([o.features["ref.motion_angle"] for o in sub])
    ok = ~np.isnan(m)
    if ok.sum() < 3:
        return "–"
    return f"{np.median(ek.ang_err(m[ok], d[ok])):.0f}"


def yaw_test(rep: Report, items: list[Onset]) -> None:
    rep("\n### Onset yaw vs the stroke's initial direction (no fitting)\n")
    rep("Yaw from settle drift and the palm offset; directed error (uniform chance: median 90°) and "
        "axial error (finger axis without its sign; chance: median 45°).\n")
    rep("The last column is the reference any drift-based cue must beat to be more than early motion: the "
        "raw direction the contact moved from touchdown to the end of the onset window, no fitting.\n")
    rep("| horizon | n with a yaw | median directed ° | median axial ° | circular corr. | perm. p (directed) "
        "| raw onset motion, median ° |")
    rep("|---|---|---|---|---|---|---|")
    for h in DIR_HORIZONS_MS:
        key = f"dir{int(h)}"
        sub = [o for o in items if o.features["drift.yaw_conf"] > 0 and o.targets.get(key) is not None]
        if len(sub) < 5:
            rep(f"| {int(h)} ms | {len(sub)} | – | – | – | – | – |")
            continue
        yaw = np.asarray([math.atan2(o.features["drift.yaw_sin"], o.features["drift.yaw_cos"]) for o in sub])
        d = np.asarray([o.targets[key] for o in sub])
        rep(f"| {int(h)} ms | {len(sub)} | {np.median(ek.ang_err(yaw, d)):.0f} | {np.median(ek.axial_err(yaw, d)):.0f} | "
            f"{ek.circ_corr(yaw, d):+.2f} | {fmt_p(ek.perm_p_angle(yaw, d))} | {_motion_ref(sub, d)} |")


def egg_test(rep: Report, items: list[Onset]) -> None:
    g = [o.labels["geometry"] for o in items]
    sp = np.asarray([x["speed_mean"] for x in g])
    cv = np.asarray([x["curv_var"] for x in g])
    jk = np.asarray([x["jerk"] for x in g])
    fast = (sp >= np.median(sp)) & (cv <= np.median(cv)) & (jk <= np.median(jk))
    egg = np.asarray([o.features["drift.egg"] for o in items])
    rep("\n### Egg shape\n")
    rep(f"Fast-but-controlled strokes (mean speed ≥ median {np.median(sp):.0f} mm/s, curvature variance and "
        f"normalised jerk ≤ their medians): {int(fast.sum())} of {len(items)}.\n")
    rep(f"- Eggness as a score for fast-but-controlled: AUC {ek.auc(egg, fast):.2f} "
        f"(0.5 = chance), permutation p = {fmt_p(ek.perm_p_auc(egg, fast))}.")
    rep(f"- Mean eggness: fast-controlled {egg[fast].mean():.2f}, others {egg[~fast].mean():.2f}.")
    for h in DIR_HORIZONS_MS:
        key = f"dir{int(h)}"
        sub = [o for o in items if o.features["drift.norm"] > 0 and o.targets.get(key) is not None]
        if len(sub) < 5:
            continue
        lead = np.asarray([math.atan2(o.features["drift.lead_sin"], o.features["drift.lead_cos"]) for o in sub])
        d = np.asarray([o.targets[key] for o in sub])
        e = ek.ang_err(lead, d)
        q = np.percentile(e, [25, 50, 75])
        rep(f"- Lead direction vs direction over the next {int(h)} ms (n = {len(sub)}): error quartiles "
            f"{q[0]:.0f}° / {q[1]:.0f}° / {q[2]:.0f}° (uniform: 45 / 90 / 135), within 45°: "
            f"{100 * (e < 45).mean():.0f}% (uniform 25%), permutation p = {fmt_p(ek.perm_p_angle(lead, d))}; "
            f"raw onset motion on the same strokes: median {_motion_ref(sub, d)}°.")
    heat = [o for o in items if o.features.get("heatmap.lead_conf", 0) > 0]
    for h in DIR_HORIZONS_MS:
        key = f"dir{int(h)}"
        sub = [o for o in heat if o.targets.get(key) is not None]
        if len(sub) < 5:
            continue
        lead = np.asarray([math.atan2(o.features["heatmap.lead_sin"], o.features["heatmap.lead_cos"]) for o in sub])
        d = np.asarray([o.targets[key] for o in sub])
        e = ek.ang_err(lead, d)
        q = np.percentile(e, [25, 50, 75])
        rep(f"- Heatmap egg lead (skew along the contact's major axis) vs direction over the next {int(h)} ms "
            f"(n = {len(sub)}): error quartiles {q[0]:.0f}° / {q[1]:.0f}° / {q[2]:.0f}°, within 45°: "
            f"{100 * (e < 45).mean():.0f}%, permutation p = {fmt_p(ek.perm_p_angle(lead, d))}.")
    settle = sum(o.features["ref.settle_only"] > 0 for o in items)
    rep(f"- Strokes whose first report after touchdown kept the centroid still (so a settle phase could be "
        f"seen apart from motion): {settle} of {len(items)}.")


def describe(rep: Report, strokes, all_items: list[Onset], items: list[Onset], split: str) -> None:
    sessions = sorted({st.session.get("path", "") for st in strokes})
    rep("# Onset ablation\n")
    rep(f"Data: {len(strokes)} strokes in {len(sessions)} session(s); {len(items)} usable "
        f"(dropped: {dict(collections.Counter(o.why for o in all_items if not o.usable))}).")
    tools = collections.Counter(st.record.get("tool") for st in strokes)
    rep(f"Tools: {dict(tools)}. Strokes with another pointer: "
        f"{sum(bool(st.record.get('pointers')) or bool(st.record.get('multiTouch')) for st in strokes)}. "
        f"Nonzero orientation: {sum(o.features['shape.orient_present'] > 0 for o in items)}, nonzero tilt: "
        f"{sum(o.features['shape.tilt_present'] > 0 for o in items)}, attitude (gravity) at touchdown: "
        f"{sum(o.features['shape.grav_present'] > 0 for o in items)}, hover: "
        f"{sum(o.features['shape.hover_present'] > 0 for o in items)}.")
    if split == "strokes":
        rep("\n**Split: by strokes, repeated K-fold, all from the same session(s).** Train and test strokes "
            "share the sitting, hand and grip, so these numbers are within-session and optimistic for a new "
            "session. The default (and the one to trust once there is more data) is --split session.")
    else:
        rep("\nSplit: leave one session out.")
    rep(f"\nStroke types (whole-stroke kinematics): {dict(collections.Counter(o.labels['type'] for o in items))}. "
        f"Contact part at onset: {dict(collections.Counter(o.labels['part'] for o in items))}.")
    ruled = collections.Counter((owner_rules(o.features), o.labels["type"]) for o in items)
    true = [o.labels["type"] for o in items]
    pred = [owner_rules(o.features) for o in items]
    obs = class_metrics(true, pred, STROKE_TYPES)["bacc"]
    rng = np.random.default_rng(0)
    null = [class_metrics(list(rng.permutation(true)), pred, STROKE_TYPES)["bacc"] for _ in range(2000)]
    p_rules = (sum(v >= obs for v in null) + 1) / (len(null) + 1)
    rep(f"Owner's rules fired: {dict(collections.Counter(owner_rules(o.features) for o in items))}; "
        f"(rule, true type) pairs: {dict(ruled)}. On all usable strokes (the rules fit nothing): balanced "
        f"accuracy {obs:.2f}, label-permutation p = {fmt_p(p_rules)}.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("data", type=Path)
    ap.add_argument("--split", choices=["session", "strokes"], default="session")
    ap.add_argument("--folds", type=int, default=5)
    ap.add_argument("--repeats", type=int, default=20)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--onset-ms", type=float, default=ONSET_MS)
    ap.add_argument("--anchor", choices=["first-report", "touchdown"], default="first-report")
    ap.add_argument("--out", type=Path)
    a = ap.parse_args()
    strokes = read_dir(a.data)
    if a.split == "session" and len({st.session.get("path") for st in strokes}) < 2:
        raise SystemExit("only one session: a session split is impossible. Pass --split strokes and read the "
                         "result as within-session.")
    if a.split == "session":
        a.repeats = 1
    all_items = onsets(strokes, a.onset_ms, a.anchor)
    items = [o for o in all_items if o.usable]
    if any(o.features.get("heatmap.present") for o in items):
        SETS.extend(HEATMAP_SETS)
        CLASS_SETS.extend(HEATMAP_SETS)
    rep = Report()
    describe(rep, strokes, all_items, items, a.split)
    rep(f"\n## Whole-stroke prediction from the first {a.onset_ms:.0f} ms after the first report\n")
    rep(f"Errors are pooled over each repeat's out-of-fold predictions, then averaged over {a.repeats} "
        "repeat(s) (± is the spread across repeats, not a confidence interval).")
    direction(rep, items, a)
    scalar(rep, items, a, "length", "Total length", 0.0)
    scalar(rep, items, a, "curvature", "Mean curvature", 0.01)
    endpoint(rep, items, a)
    rep("\n## Stroke type from the onset\n")
    classification(rep, strokes, a)
    rep("\n## The owner's rules, tested directly\n")
    yaw_test(rep, items)
    egg_test(rep, items)
    if a.out:
        a.out.write_text("\n".join(rep.lines) + "\n")


if __name__ == "__main__":
    main()
