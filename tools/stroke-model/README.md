# Stroke model

Training pipeline for Graffux's own stroke predictor: given the last few input samples (position,
timing, pressure, contact ellipse, finger/stylus orientation and tilt, tool type) and the phone's
motion sensors, predict where the pen will be 1–4 display frames ahead.

Record format: [SCHEMA.md](SCHEMA.md).

## Collecting data

On the device, Settings → Stroke data is on by default. Every stroke is recorded to
`filesDir/stroke-data/`; finished files upload at launch (or via "Upload stroke data now") to the
`stroke-data` branch of this repository under `stroke-data/<device-model>/`. Upload uses the
on-device GitHub token, which needs **Contents: read and write** on the repository.

## Training

~~~
pip install -r requirements.txt
git fetch origin stroke-data && git worktree add ../stroke-data origin/stroke-data
python train.py ../stroke-data/stroke-data --epochs 30 --out stroke_predictor.onnx
~~~

Output per epoch: mean pixel error at each horizon on held-out sessions, next to two baselines
(constant velocity, linear least squares). Ship only if the model beats both at every horizon.

## Smoke test

~~~
python synth.py /tmp/synth && python train.py /tmp/synth --epochs 3
~~~

## Layout

| file | role |
|---|---|
| `strokemodel/data.py` | reading files, clock alignment, features and targets |
| `strokemodel/model.py` | GRU predictor |
| `train.py` | split by session, baselines, training, ONNX export |
| `synth.py` | synthetic data in the exact on-device format |
