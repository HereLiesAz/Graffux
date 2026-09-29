# Stroke data schema (version 4)

Written on the device by `StrokeDataRecorder` (feature/editor) and `StrokeDataStore` (core/data).
Uploaded to the `stroke-data` branch as `stroke-data/<device-model>/session-<ms>.jsonl.gz`.

Each file is gzip JSON Lines: one JSON object per line. Every append is its own gzip member;
Python's `gzip` module reads the concatenation transparently.

## Line 1: `type: "session"`

| field | meaning |
|---|---|
| `schema` | this document's version (4; files written as 1, 2 or 3 are still read, see below) |
| `manufacturer`, `model`, `sdk` | device |
| `displayHz` | display refresh rate; one frame = `1000 / displayHz` ms |
| `widthPx`, `heightPx`, `xdpi`, `ydpi`, `density` | screen geometry (px ↔ mm) |
| `startedAtMs` | wall clock at session start |
| `appVersion` | Graffux versionName |
| `heatmap` | v3: raw touch heatmap capture status when the session's first stroke was written, see "`heatmap` status" below; `state: "off"` when the setting is off |

## Following lines: `type: "stroke"`

One touch from `ACTION_DOWN` to `ACTION_UP` / `ACTION_CANCEL`.

| field | meaning |
|---|---|
| `tool` | `finger`, `stylus`, `eraser`, `mouse` or `unknown` (the MotionEvent tool type) |
| `inputDevice` | input device name |
| `cancelled` | ended with ACTION_CANCEL (e.g. a second finger took over) |
| `multiTouch` | another pointer went down during the stroke |
| `pointerId` | v2: the primary pointer's id (the one whose `ACTION_DOWN` began the stroke) |
| `canceled`, `palm` | v2: the primary pointer was lifted with `FLAG_CANCELED` / was reported as `TOOL_TYPE_PALM` |
| `samples` | the primary pointer: columns, same length, one entry per raw input sample (historical samples included) |
| `pointers` | v2: every other pointer that touched during the stroke, see below |
| `hover` | optional: the stylus hover approach just before contact, same layout as a stroke |
| `clock` | v4: `elapsedRealtime`: every time in the stroke (samples, `hover`, `pointers`, `sensors`, `heatmap`) is `SystemClock.elapsedRealtimeNanos` ns, the SensorEvent clock. Absent before v4 (input times were uptime) |
| `clockOffsetNs` | what to subtract from a sensor time to get an input time. v4: always 0. v1–v3: `elapsedRealtimeNanos − uptime` at the stroke's end |
| `uptimeToElapsedNs` | v4: the `elapsedRealtimeNanos − uptime` offset (sampled at the stroke's end) that was added to the MotionEvent and heatmap times to move them onto elapsedRealtime |
| `context` | what it was drawn with: `tool` (editor tool), `brush`, `brushSize`, `brushOpacity`, `stabilizer`, `stabilizerLevel`, `zoom`, `rotationDeg`, `displayRotation`, `sampleRateHz`, `gpu` |
| `sensors` | per sensor, `{t: [ns elapsedRealtime], a: [ns elapsedRealtime], v: [[values...]]}`, see below |
| `sensorsRegistered` | v2: the sensors the recorder successfully registered for this stroke |
| `sensorStatus` | v2: per sensor, `{registered, flushed, events, lagNs}`, see below |
| `flush` | v2: `completed` (every registered sensor reported its flush), `timeout`, `unsupported` or `none` |
| `heatmap` | v3, optional: raw capacitive frames around the stroke, see below. Only with Settings → Raw touch heatmap (root) on, root granted and frames arriving |
| `heatmapStatus` | v3, only when the heatmap setting is on: the capture status at slicing time, see below |

### `samples` columns

| column | MotionEvent source | notes |
|---|---|---|
| `t` | event time, ns | v4: elapsedRealtime (MotionEvent time + `uptimeToElapsedNs`); v1–v3: uptime. ns precision on API 34+, ms × 10⁶ before |
| `action` | `actionMasked` | historical samples are reported as MOVE (2) |
| `x`, `y` | `AXIS_X/Y` | view pixels |
| `pressure` | `AXIS_PRESSURE` | fingers usually report a synthetic value |
| `size` | `AXIS_SIZE` | normalised contact size |
| `touchMajor`, `touchMinor` | `AXIS_TOUCH_MAJOR/MINOR` | contact ellipse, px |
| `toolMajor`, `toolMinor` | `AXIS_TOOL_MAJOR/MINOR` | tool ellipse, px |
| `orientation` | `AXIS_ORIENTATION` | radians; finger ellipse or stylus azimuth |
| `tilt` | `AXIS_TILT` | radians from perpendicular (stylus) |
| `distance` | `AXIS_DISTANCE` | hover distance (stylus) |
| `buttons` | `buttonState` | stylus buttons |

The primary is followed by pointer id, not index: a primary that lifts first (`ACTION_POINTER_UP`,
action 6 in its last sample) simply ends, and the other pointers are never mistaken for it.

### `pointers` (v2)

One object per additional contact (a second finger, a palm, a resting knuckle), in the order they
went down. A pointer id reused after lifting starts a new entry.

| field | meaning |
|---|---|
| `pointerId` | MotionEvent pointer id |
| `tool` | as the stroke's `tool`, plus `palm` (`TOOL_TYPE_PALM`, value 5, hidden in the SDK but reported by some touch controllers) |
| `canceled` | lifted with `MotionEvent.FLAG_CANCELED` (the system judged it accidental, typically a palm), or the gesture was cancelled |
| `palm` | reported as `TOOL_TYPE_PALM` at any sample |
| `samples` | same columns as the primary; `action` is 5 (`POINTER_DOWN`) at its first sample and 6 (`POINTER_UP`) at its last, 2 (MOVE) between |

Its position relative to the primary is the hand-posture signal: where the palm or the other
fingers rest says which way the drawing finger points.

### `sensors`

Every event whose own timestamp **or** whose arrival time falls between 500 ms before the stroke and
150 ms after it. `t` is the event's timestamp; `a` (v2) is when it reached the app, both on the
elapsedRealtime clock. After the 150 ms tail the recorder calls `SensorManager.flush()` and slices
only when every registered sensor has reported `onFlushCompleted` (or after 1 s), so events the hub
had buffered are included.

Why both clocks: in the first real session (Pixel 5, schema 1), every accelerometer-hub stream
(accelerometer, gyroscope, gravity, linearAcceleration, gameRotationVector, rotationVector) had
timestamps trailing real time by ~10 s at session start, shrinking by ~2 % of elapsed time until
they caught up after ~8 minutes; the magnetometer was always on time. A window on `t` alone found
nothing for 55 of 76 strokes. With `a`, such a stream is still captured, and `a − t` measures the
skew. Which of the two is the true time of the measurement is a device question; see `lagNs`.

### `sensorStatus` (v2)

Per sensor the recorder knows (all seven, whether or not the device has them):

| field | meaning |
|---|---|
| `registered` | the device has it and `registerListener` succeeded; false means absent, not lost |
| `flushed` | its `onFlushCompleted` arrived before slicing |
| `events` | events in this stroke's slice |
| `lagNs` | newest event's arrival minus its timestamp at slicing time; null when it never delivered |

### Sensor names

`accelerometer`, `gyroscope`, `gravity`, `linearAcceleration`, `magneticField` (3 values each);
`gameRotationVector`, `rotationVector` (quaternion x, y, z[, w, accuracy]). Device axes; the
display rotation is in `context.displayRotation`. Sampled at up to 200 Hz. A sensor the device
lacks is absent from `sensors`; from v2, `sensorStatus` says whether it was registered.

### `heatmap` (v3)

The touch controller's raw capacitive image, captured by a root-only helper
(`core/nativebridge/src/main/cpp/heatmap/heatmap_helper.c`, run through `su`). Frames whose time falls
from 100 ms before `ACTION_DOWN` to 50 ms after the last sample.

| field | meaning |
|---|---|
| `source` | `v4l2` (`/dev/v4l-touch0` streamed directly) or `sec_delta` (Samsung factory interface, `run_delta_read_all` polled) |
| `w`, `h` | grid columns and rows as the driver reports them (sec: `get_x_num`, `get_y_num`) |
| `dtype` | `int16le`: signed 16-bit little-endian, row-major, `w` per row |
| `t` | per frame, ns, **same clock as `samples.t`** (v4: elapsedRealtime, shifted by `uptimeToElapsedNs`; v3: uptime): the driver's buffer timestamp when it is CLOCK_MONOTONIC (v4l2), else when the helper read the frame |
| `a` | per frame, ns, same clock: when the helper read it |
| `frames` | base64 of all frames' cells packed back to back: `len(t) × h × w` int16 |
| `truncated` | more frames fell in the window than the per-stroke cap (360 frames or 384 KiB raw, whichever is fewer); the EARLIEST are kept |

Clocks: the helper stamps CLOCK_MONOTONIC, which on Android is the clock of `SystemClock.uptimeMillis()`
and MotionEvent times, so `t` needs no conversion. The app checks this at startup (`clockCheckNs`
below) and applies an offset only if the two disagree by more than 50 ms.

**Unverified until run on a device** (the probe in `tools/touch-heatmap-probe` has not been run yet):
the V4L2 pixel format (assumed signed 16-bit deltas; the actual fourcc is in the status `detail`), the
`cmd_result` text layout of `run_delta_read_all` (parsed tolerantly: any integers after an optional
`name:` prefix, taking the first `x_num × y_num`), whether sec rows run along `x_num` as assumed, the
grid's orientation relative to the screen, and whether system_server holds the V4L2 device (then
REQBUFS returns EBUSY and the helper falls back to `sec_delta`). The sec path's frame rate is
whatever the sysfs round trip allows, capped at 120 Hz.

### `heatmap` status (v3)

In the session header (`heatmap`) and on each stroke (`heatmapStatus`):

| field | meaning |
|---|---|
| `state` | `off` (setting off), `starting` (waiting for su / the helper), `unavailable` (no `su` binary, or the helper was not installed), `denied` (su refused, or exited before the helper reported), `v4l2` / `sec_delta` (streaming), `error` (the helper reported a failure; see `detail`), `stopped` |
| `detail` | human-readable: the device and fourcc or sec grid, why V4L2 was skipped (e.g. `EBUSY`), su's stderr and exit code, or the helper's error (e.g. `EACCES (permission denied or SELinux)`) |
| `w`, `h` | grid size once streaming, else 0 |
| `frames` | frames received so far (updated every 30) |
| `clockCheckNs` | app uptime minus the helper's CLOCK_MONOTONIC at its first record (pipe latency; expected well under 1 ms); null before that |

## Reading version 3

v3 input and heatmap times are on the uptime clock and sensors on elapsedRealtime; a sensor time
minus the stroke's `clockOffsetNs` is an input time. `strokemodel.data.sensor_clock_offset` returns
that offset for v1–v3 and 0 for v4 (`clock: "elapsedRealtime"`), so every reader aligns both the same
way. Nothing else changed: v3 and v4 strokes give identical training examples (tests/test_train.py).

## Reading version 2

v2 files have no `heatmap`, `heatmapStatus` or header `heatmap`. The loader reads them unchanged;
their `heatmap.*` onset features are zero with `heatmap.present` = 0.

## Reading version 1

v1 files have no `pointers`, `sensorStatus`, `sensorsRegistered`, `flush` or `a`. The loader treats
them as single-pointer strokes whose sensor registration is unknown (`sensorStatus` missing, not
`registered: false`). v1's `samples` could switch to another finger if the first one lifted
before the others; strokes with `multiTouch` in v1 are best treated with suspicion.

## Derived features (computed offline, not recorded)

`tools/stroke-model/strokemodel/onset.py` derives these per stroke from its onset window (README
"Onset window"). Contact sizes are converted to mm with `xdpi`; "relative" means relative to the
device's median over all strokes at touchdown.

| name | definition |
|---|---|
| `pose.pitch_td`, `pose.pitch_end`, `pose.pitch_min` | `asin(clamp(touchMinor / touchMajor, 0, 1))` at touchdown, window end, minimum over the window |
| `pose.area_rel` | log(π/4 · major · minor) at touchdown, relative |
| `pose.hardness` | relative pressure − 0.25 · relative log area |
| `pose.part_{tip,pad,side,nail}` | one-hot contact part (README "Derived finger pose") |
| `pose.tip_only` | mean of: small (relative area), round (ratio), hard (relative pressure), and not growing |
| `pose.flatten`, `pose.flatten_rate` | growth of log area and of elongation over the window; rate per 10 ms |
| `pose.side_roll` | elongation growth × (1 − area growth), both clipped to [0, 1] |
| `drift.dx`, `drift.dy`, `drift.norm` | centroid displacement from touchdown over the settle samples (slower than 20 mm/s), ÷ touchMajor |
| `drift.yaw_cos`, `drift.yaw_sin`, `drift.yaw_conf` | yaw unit vector from −sign(Δ log area) · drift and −(palm − finger), weighted; confidence = resultant length ÷ cue count |
| `drift.egg`, `drift.lead_cos`, `drift.lead_sin` | eggness (README "The owner's rules") and the predicted lead direction |

Egg-shaped contacts are only indirectly observable through public Android APIs: `MotionEvent`
reports a symmetric ellipse. From v3, strokes recorded with the root heatmap carry the capacitive
image itself, and `strokemodel/heatmap.py` measures the shape directly. Per frame, over cells above
15 % of the peak (minus that threshold), with the grid mapped onto the screen in mm:

| name | definition |
|---|---|
| `heatmap.present`, `heatmap.n` | the stroke has contact frames between touchdown (−20 ms) and the onset window's end; how many |
| `heatmap.{td,end}_cos2`, `_sin2` | major-axis orientation θ (eigenvector of the intensity covariance) as elongation × (cos 2θ, sin 2θ): axial, and fading out for round contacts |
| `heatmap.{td,end}_elong` | 1 − √(λ₂/λ₁) of the covariance |
| `heatmap.{td,end}_skew` | \|third standardised moment along the major axis\|: egg asymmetry |
| `heatmap.{td,end}_logarea`, `_logpeak` | log of the area above threshold (mm²) and of the peak count |
| `heatmap.d_elong`, `heatmap.d_logarea` | window end minus touchdown |
| `heatmap.lead_cos`, `heatmap.lead_sin`, `heatmap.lead_conf` | the lead direction: the major axis signed toward the long tail (the narrow end, which leads), voted over the window's frames with weight \|skew\| × elongation; unit vector and agreement |

`td` is the first contact frame, `end` the last one at or before the window's end. All zero with
`heatmap.present` = 0 when the stroke has no heatmap.
