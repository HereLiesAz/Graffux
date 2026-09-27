# Stroke data schema (version 1)

Written on the device by `StrokeDataRecorder` (feature/editor) and `StrokeDataStore` (core/data).
Uploaded to the `stroke-data` branch as `stroke-data/<device-model>/session-<ms>.jsonl.gz`.

Each file is gzip JSON Lines: one JSON object per line. Every append is its own gzip member;
Python's `gzip` module reads the concatenation transparently.

## Line 1: `type: "session"`

| field | meaning |
|---|---|
| `schema` | this document's version (1) |
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
| `samples` | columns, same length, one entry per raw input sample (historical samples included) |
| `hover` | optional: the stylus hover approach just before contact, same layout as a stroke |
| `clockOffsetNs` | `elapsedRealtimeNanos − uptime` at the stroke's end; sensor time − this = input time |
| `context` | what it was drawn with: `tool` (editor tool), `brush`, `brushSize`, `brushOpacity`, `stabilizer`, `stabilizerLevel`, `zoom`, `rotationDeg`, `displayRotation`, `sampleRateHz`, `gpu` |
| `sensors` | per sensor, `{t: [ns elapsedRealtime], v: [[values...]]}` from 500 ms before the stroke to 150 ms after it |

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

### `sensors`

`accelerometer`, `gyroscope`, `gravity`, `linearAcceleration`, `magneticField` (3 values each);
`gameRotationVector`, `rotationVector` (quaternion x, y, z[, w, accuracy]). Device axes; the
display rotation is in `context.displayRotation`. Sampled at up to 200 Hz. A sensor the device
lacks is simply absent.
