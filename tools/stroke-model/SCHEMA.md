# Stroke data schema (version 2)

Written on the device by `StrokeDataRecorder` (feature/editor) and `StrokeDataStore` (core/data).
Uploaded to the `stroke-data` branch as `stroke-data/<device-model>/session-<ms>.jsonl.gz`.

Each file is gzip JSON Lines: one JSON object per line. Every append is its own gzip member;
Python's `gzip` module reads the concatenation transparently.

## Line 1: `type: "session"`

| field | meaning |
|---|---|
| `schema` | this document's version (2; files written as 1 are still read, see below) |
| `manufacturer`, `model`, `sdk` | device |
| `displayHz` | display refresh rate; one frame = `1000 / displayHz` ms |
| `widthPx`, `heightPx`, `xdpi`, `ydpi`, `density` | screen geometry (px ↔ mm) |
| `startedAtMs` | wall clock at session start |
| `appVersion` | Graffux versionName |

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
| `clockOffsetNs` | `elapsedRealtimeNanos − uptime` at the stroke's end; sensor time − this = input time |
| `context` | what it was drawn with: `tool` (editor tool), `brush`, `brushSize`, `brushOpacity`, `stabilizer`, `stabilizerLevel`, `zoom`, `rotationDeg`, `displayRotation`, `sampleRateHz`, `gpu` |
| `sensors` | per sensor, `{t: [ns elapsedRealtime], a: [ns elapsedRealtime], v: [[values...]]}`, see below |
| `sensorsRegistered` | v2: the sensors the recorder successfully registered for this stroke |
| `sensorStatus` | v2: per sensor, `{registered, flushed, events, lagNs}`, see below |
| `flush` | v2: `completed` (every registered sensor reported its flush), `timeout`, `unsupported` or `none` |

### `samples` columns

| column | MotionEvent source | notes |
|---|---|---|
| `t` | event time, ns, uptime clock | ns precision on API 34+, ms × 10⁶ before |
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
reports a symmetric ellipse. Direct observation needs the raw capacitive heatmap, which some OEMs
expose only through vendor APIs. Recording it is a possible future addition, as an optional
per-sample `heatmap` field in a later schema version.
