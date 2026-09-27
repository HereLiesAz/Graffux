# Stroke model

Two tasks on the same recorded strokes:

- **Per frame** (`train.py`): given the last few input samples (position, timing, pressure, contact
  ellipse, finger/stylus orientation and tilt, tool type) and the phone's motion sensors, predict
  where the pen will be 1–4 display frames ahead.
- **From the onset** (`onset_eval.py`): given only the first few ms of contact, predict the whole
  stroke -- its initial direction, length, curvature, end point and type. This tests the owner's
  thesis that direction and shape are set by pose (hand to finger or brush, finger to glass), and
  that the shape of the contact at touchdown already says where the finger will go.

Record format: [SCHEMA.md](SCHEMA.md).

## Collecting data

On the device, Settings → Stroke data is on by default. Every stroke is recorded to
`filesDir/stroke-data/`; finished files upload at launch (or via "Upload stroke data now") to the
`stroke-data` branch of this repository under `stroke-data/<device-model>/`. Upload uses the
on-device GitHub token, which needs **Contents: read and write** on the repository.

Rooted devices can also record the raw capacitive touch image: Settings → Raw touch heatmap (root),
off by default. Turning it on runs a small read-only helper through `su` at once (the Magisk prompt
appears then) and shows what it found: `v4l2` or `sec_delta` and the grid size, or why it failed. Each
stroke then carries a `heatmap` (SCHEMA.md), and `onset_eval.py` adds heatmap rows. The frame format
on the Pixel 5 is not verified yet; see SCHEMA.md "`heatmap` (v3)".

## Training

~~~
pip install -r requirements.txt
git fetch origin stroke-data && git worktree add ../stroke-data origin/stroke-data
python train.py ../stroke-data/stroke-data --epochs 30 --out stroke_predictor.onnx
~~~

Output per epoch: mean pixel error at each horizon on held-out sessions, next to two baselines
(constant velocity, linear least squares). Ship only if the model beats both at every horizon.

With a single session a session split is impossible; `--split strokes` splits its strokes instead
(numbers are then within-session and optimistic).

## Onset ablation

~~~
python onset_eval.py ../stroke-data/stroke-data --out report.md            # leave one session out
python onset_eval.py ../stroke-data/stroke-data --split strokes            # one session only
~~~

Feature sets: a trivial baseline (mean direction / mean / majority class), kinematics only, contact
shape only (no position or velocity), shape + kinematics, pose derived from shape alone, the full
derived pose (pitch, yaw, contact part), and derived pose + kinematics. When strokes carry a raw
heatmap (schema v3): heatmap (contact-image moments: true orientation, elongation, egg skew and
lead, area, peak) and heatmap + derived pose. Targets are measured from the
end of the onset window, so nothing a target measures is inside it. Models are ridge regressions /
a class-balanced ridge classifier (numpy only), alpha picked by inner CV on the training fold.

The first real result, 76 finger strokes from one Pixel 5 session, is in
[reports/pixel5-session-1790533675132.md](reports/pixel5-session-1790533675132.md). Read it as a
within-session, single-device, finger-only pilot; see "What one session cannot tell" below.

### Onset window

Sample 0 is the touchdown. Many panels (the Pixel 5 among them) send nothing more while the finger
rests: the next report came 17–1458 ms later (median 136 ms) in the real session. The window
therefore runs from touchdown to `--onset-ms` (default 30) after that first report; the touchdown
shape is still sample 0. `--anchor touchdown` measures from touchdown instead.

### Derived finger pose

All in `strokemodel/onset.py`; names and exact formulas are in SCHEMA.md "Derived features".

- **Pitch** (angle to the glass): `asin(minor / major)`, clamped; a near-vertical tip is small and
  round (90°), a flat finger larger and elongated. Area and the raw ratio go in alongside.
- **Yaw** (which way the finger points), also when `AXIS_ORIENTATION` is 0: the centroid's settle
  drift (only samples slower than 20 mm/s), signed by the area change -- flattening pushes the
  contact back toward the hand, so the tip points against the drift -- and the palm / second
  pointer, which lies behind the finger. Reported as a unit vector plus a confidence (agreement ×
  strength of the cues). It uses the contact's own centroid, so it is kept in its own group.
- **Contact part**: a label and a continuous embedding (area and pressure relative to the device's
  median, elongation, pitch, hardness = pressure beyond what area explains). Rules, in order:
  `nail` = area ≤ −0.5 log units and pressure ≥ median; `side` = min(minor/major) ≤ 0.6 and area
  ≤ +0.4; `pad` = area ≥ +0.4; `tip` = the rest.
- **Transitions**: `tip_only` (small, round, hard at touchdown, and stays so), `flatten` and
  `flatten_rate` (area and elongation grow), `side_roll` (elongation rises while area barely grows).

### Stroke types

Labels come from each stroke's whole path in mm on the glass (px ÷ xdpi/25.4), resampled every
1 mm (finer is mostly touch jitter). Checked in order:

| type | rule |
|---|---|
| scratch | extent ≤ 6 mm, path ≤ 25 mm, ≥ 1 reversal (a > 120° turn) per 10 mm |
| detail (and fill) | extent ≤ 15 mm and (path ÷ bounding-box diagonal ≥ 3, or ≥ 1 reversal per 10 mm) |
| wipe | path ≥ 25 mm, extent ≥ 15 mm, mean curvature ≤ 0.35 rad/mm |
| other | the rest (mostly short simple lines and dabs) |

These were set once after looking at the real session's geometry (the wipe curvature cap was raised
from 0.08 so that broad back-and-forth sweeps count as wipes), and not tuned against any model.

### The owner's rules

Tested as hand-written rules with no fitting (`owner_rules` in `onset_eval.py`): side roll or a side
contact → detail; flatten ≥ 0.5 → wipe; tip-only ≥ 0.7 → scratch; otherwise other.

The egg rule (an egg-shaped contact means fast but controlled, narrow end leading) cannot be observed
directly: `MotionEvent` reports only a symmetric ellipse (major, minor, orientation). It is
approximated by the centroid drifting toward the narrow end while the size stays nearly constant:
`egg` = mean of the drift normalised by touchMajor, the same scaled down by any area change, and the
elongation; the lead direction is the drift direction. Seeing the egg itself would need the raw
touch heatmap, which some OEMs expose only through vendor APIs (a possible future schema addition,
see SCHEMA.md). *Fast but controlled* = mean speed ≥ the median, curvature variance and normalised
jerk ≤ their medians.

### What one session cannot tell

The real session is one person, one sitting, one device, fingers only, with orientation and tilt
always 0, no hover, no stylus, 6 multi-touch strokes and attitude for 20 strokes. So:

- nothing about stylus pose (tilt/azimuth), which is where pose should matter most;
- nothing about generalisation to a new session, grip or person: the split is by strokes;
- on this panel the settle drift is not separable from motion for most strokes (the first report
  arrives when the centroid moves), so yaw and the egg lead can only restate early motion;
- the touch axes are integers with a discrete "13 × 5" elongated state, so shape carries little.

## Smoke test

~~~
python synth.py /tmp/synth && python train.py /tmp/synth --epochs 3 && python onset_eval.py /tmp/synth
~~~

`synth.py` writes schema 3 (`--schema 1` / `2` for the old layouts) with planted pose -> type and
yaw -> direction relations, so the onset pipeline has signal to find. Schema 3 adds an egg-shaped
heatmap blob (narrow end leading along the initial direction) on a 16 × 34 grid to all sessions but
the last. On that coarse grid the lead is recoverable for pad-sized contacts and not for small round
tips, so expect the direct heatmap-lead test to be significant while the ridge "heatmap" row stays weak.

Unit tests (moment extraction on synthetic blobs, v1/v2/v3 loading):

~~~
python -m unittest discover -s tests -v
~~~

## Layout

| file | role |
|---|---|
| `strokemodel/data.py` | reading files, clock alignment, features and targets |
| `strokemodel/model.py` | GRU predictor |
| `strokemodel/onset.py` | onset window, contact-shape and finger-pose features, whole-stroke targets, stroke types |
| `strokemodel/evalkit.py` | ridge models, circular statistics, splits |
| `train.py` | per-frame task: split by session, baselines, training, ONNX export |
| `onset_eval.py` | onset task: the ablation report |
| `synth.py` | synthetic data in the exact on-device format |

## Kaggle mirror

`.github/workflows/stroke-data-kaggle.yml` copies the `stroke-data` branch to a **private** Kaggle
dataset every 6 hours (or on demand from the Actions tab), via `kaggle_upload.py`. It needs the
`KAGGLE_TOKEN` repository secret (a Kaggle API token, or `kaggle.json` contents). If the token is
a bare API token, also set the `KAGGLE_USERNAME` (or full `KAGGLE_DATASET`, `owner/slug`)
repository variable. Default dataset: `<username>/graffux-stroke-data`.

~~~
kaggle datasets download <username>/graffux-stroke-data --unzip -p data/
python train.py data/
~~~
