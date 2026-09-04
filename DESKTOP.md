# Graffux desktop (Linux/Windows) — status

This tracks what actually works today in the `:desktop` Compose Multiplatform app and the shared
`:core:engine` module, versus what is deliberately deferred. Written to be checked against, not to
sound finished — see each claim's own verification note.

## What's real and verified from this (Linux) container

- **`core:engine`**: a genuine Kotlin Multiplatform module (`androidTarget` + `jvm("desktop")`)
  holding the azphalt stamp-brush engine's pure math — `BrushStamps`, `AzphaltBrush`,
  `BrushSensorDynamics`/`BrushSensorEngine`, `TileGrid`/`DirtyRegion`, and the new
  `RoundStampCompositor`/`ArgbColor` (the max-combine falloff compositor and its color math,
  extracted from the Android-only `StampBrushRenderer` with zero `android.graphics` dependency).
  `core:common` depends on it under the same package, so nothing on the Android side changed its
  imports. **Verified**: `:core:engine:desktopTest` and `:core:engine:testAndroidHostTest` both
  pass the same `commonTest` suite, and `:core:common:testDebugUnitTest` (the pre-existing ~20
  azphalt test files, unmoved) passes unchanged against the extracted module.
- **`:desktop` app**: compiles and its `:desktop:run` launches a real window under Xvfb with
  software-rendered Skia (`LIBGL_ALWAYS_SOFTWARE=1` / `SKIKO_RENDER_API=SOFTWARE` — this container
  has no GPU). It shows a working stamp-brush canvas: pointer drag paints strokes using
  `RoundStampCompositor`, the same falloff math and max-combine compositing the Android renderer
  uses, so a soft-hardness stroke reads the same way on desktop.
- **Pen-aware input**: `PointerInputChange.pressure` is read and drives per-dab radius/alpha.
  Compose Multiplatform Desktop surfaces real Windows Ink pressure for `PointerType.Stylus` — this
  is the actual hook a Surface Pen would feed. **Not verified**: no Windows machine or pen hardware
  is available in this container, so the pressure *value itself* arriving correctly from real
  Surface Pen hardware is unverified — only that the code path reads and uses whatever pressure
  Compose reports (confirmed at 1.0 from mouse/Xvfb input).
- **Tile-parallel CPU compositing**: `compositeTileParallel` splits a stroke's dirty region
  (`DirtyRegion.fromDabs` + `TileGrid`) into tiles and composites them concurrently via
  `kotlinx.coroutines` `Dispatchers.Default`, which uses one thread per CPU core. This is the
  concrete "optimized for Surface Pro hardware" lever in this pass: Surface Pro devices are
  multi-core (no discrete GPU compute available to a portable Compose Desktop app — see below), so
  spreading rasterization across cores is the real, portable win available here.
- **The real AzNavRail UI.** `aznavrail-cmp` (`com.github.HereLiesAz.AzNavRail:aznavrail-cmp`,
  bumped to 11.44 for this) is a genuine Compose Multiplatform port of the same DSL the Android app
  uses — `AzHostActivityLayout`, `azConfig`, `azTheme`, `azRailItem`, all package-compatible — with
  a published `jvm("desktop")` target. An earlier draft of this document claimed AzNavRail was
  Android-AAR-only; that was wrong (checked only the locally-resolved Gradle cache, not the
  upstream repo's actual module list). The desktop app now runs the real rail, not a placeholder
  Material3 scaffold: **verified** by `:desktop:run` under Xvfb showing the rail (hamburger menu, a
  "Brush" item with a live size badge, the library's automatic "?" help rail item) rendered
  alongside a working canvas, and a scripted drag still painting correctly with the rail present.
  (That "?" item was briefly disabled after a crash was found in it, then re-enabled once the
  upstream fix shipped — see the `azAbout()` writeup further down for the full story.)
- **Brush presets and dab generation now use the SAME shared engine calls Android does**, not a
  desktop-only approximation. `DesktopStampCanvas` builds `BrushSample`s via `BrushSampleBuilder`
  and calls `BrushStamps.dynamicDabs(samples, diameter, brush, seed)` — the shared entry point that
  resolves sensor-bound dynamics (`BrushSensorEngine.resolve`), taper, first-touch blot and jitter,
  exactly like the Android stroke pipeline — instead of a hand-rolled pressure→size curve. The rail
  offers `BuiltInBrushes.presets` (Soft Round, Hard Round, Airbrush, Ink Pen) — the same shared,
  pure-Kotlin brush definitions Android ships with. **Verified**: compiles, and a scripted drag
  still paints a correct soft-falloff stroke through this path (screenshot captured).
- **Real Undo.** `CanvasState` keeps a whole-canvas-snapshot stack (`commitStroke` pushes the
  pre-stroke bitmap on every release, `undo` pops it back); an "Undo" rail item wired to it, disabled
  when the stack is empty. **Verified**: after fixing the release-detection bug below, drawing two
  strokes and clicking Undo removes exactly the second stroke, leaving the first — confirmed by
  screenshot, reproduced twice. This is a real (if simple, whole-snapshot-per-step) history, not a
  single always-live bitmap with no way back.
- **Redo, added alongside Undo, same mechanism (a second stack, cleared on any new stroke commit).**
  The window's default 800x600 size was too short for a 6th rail item (Undo + Redo pushed the rail
  past 600px tall, so Redo silently rendered off the bottom with no overflow/scroll indicator) — that
  part is fixed: the window now opens at 1000x1100 (bumped again from an intermediate 1000x900 —
  see the window-size history in the `Window()` call's own comment) and Redo is visible. **State
  logic verified
  correct** via debug instrumentation: `undoStack`/`redoStack` sizes update exactly as expected
  through a full commit → commit → undo → (would-be redo) sequence, symmetric with the already-proven
  Undo path. **The click itself could not be reproduced working** in this session's Xvfb/Robot test
  harness, despite roughly a dozen coordinate/timing/warm-up-click variations that all worked for
  Undo — so Redo's rail button is implemented and its logic is verified, but its click path is
  unverified end-to-end pending on-device (or non-headless) testing.
- **Clear.** `CanvasState.clear()` wipes the canvas to a blank `BufferedImage` the same size as the
  current one, pushing the pre-clear content onto the undo stack first (so Clear is itself undoable)
  and dropping the redo stack, exactly like any other edit. A "Clear" rail item is wired to it,
  disabled only when there's no canvas yet (`committed == null`, i.e. before the surface has ever been
  sized). **Verified end-to-end, click included** — unlike Redo above, this one reproduced cleanly:
  a scripted drag painted a stroke (screenshot confirms it on screen), clicking Clear removed it
  (screenshot confirms a blank canvas), and debug instrumentation on `canUndo`/`canRedo` confirmed the
  state transition was exactly right — `canUndo` was `false` before the stroke, `true` after it
  committed, and *still* `true` after Clear (the pre-clear stroke is sitting in the undo stack, so
  Undo would bring it back), while `canRedo` stayed `false` throughout (Clear cleared the redo stack,
  same as it started). No Xvfb-focus quirk was hit this time — the click registered on the first try.
- **Save (export to PNG).** `CanvasState.exportPng()` writes the current canvas to a timestamped
  `graffux-yyyyMMdd-HHmmss.png` under `~/Graffux` (created if missing) via `javax.imageio.ImageIO`,
  and returns the `File` written. A "Save" rail item is wired to it (disabled before the canvas has
  ever been sized), and the app shows the written path under the toolbar row on success. **This is
  deliberately not a save/save-as file-chooser dialog** — Compose Desktop has none built in, and a
  blocking AWT `FileDialog` under this session's Xvfb-without-a-window-manager test harness is its
  own risk (a modal dialog with no way to dismiss it via the same synthetic-click approach used
  everywhere else could hang the process rather than fail loudly) — so this first pass is a fixed,
  predictable destination, the same "real, if minimal" scoping this file uses throughout, not a
  placeholder. **Verified end-to-end**: drew a stroke, clicked Save, confirmed a real PNG landed on
  disk at the exact path the UI reported, and confirmed by reading that PNG back that its pixel
  content is the same stroke that was on screen (not a blank or corrupt file).
- **Brush flow.** Android's `feature:editor` (`EditorViewModel.brushFlow`) exposes a real flow
  control -- per-dab colour build-up along a stroke, distinct from opacity -- that `EditorViewModel`
  threads into the shared engine's `compositeTileParallel(..., flow = ...)`. The desktop app's
  `DesktopStampCanvas` already called that exact function but had `flow` hardcoded to `1f`; it's now
  a real parameter threaded from a "Flow" slider next to the brush-size one in `Main.kt`, through
  `DesktopStampCanvas(flow = ...)`, into the same shared compositor call Android uses. **Verified
  end-to-end**: drew a stroke at 100% flow (solid), lowered the slider to 17%, drew a second stroke,
  and confirmed by screenshot that the second stroke rendered visibly and correctly lighter than the
  first — not just that the slider moved, that the pixels it produced actually differ.
- **Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y / Ctrl+S keyboard shortcuts (Undo/Redo/Save).** A genuine
  desktop-only addition — Android's touch-only editor has no keyboard, so there's no Android
  behavior to match here, just the standard desktop convention. Wired via `Window`'s own
  `onKeyEvent` parameter in `main()` (which is why `CanvasState` and `lastSavedPath` are created
  there now, one level up from the rest of this app's UI state in `GraffuxDesktopApp`, instead of
  down inside it — `onKeyEvent` needs to reach them directly).
  - **Ctrl+S verified fully end-to-end**: warmed up the window, drew a stroke, pressed Ctrl+S,
    confirmed a real PNG landed on disk (same `CanvasState.exportPng()` the Save rail item calls)
    and that reading it back matched the exact stroke on screen, and confirmed the "Saved to ..."
    label updated exactly as clicking Save would.
  - **Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y verified functionally correct via debug instrumentation** —
    logging every `KeyEvent` confirmed Ctrl+Z is recognized and dispatched exactly once per press,
    and logging `undoStack.size` inside `CanvasState.undo()` confirmed each press pops exactly one
    entry matching whatever the stack actually holds (`size=0` → no-op, `size=1` → one real pop,
    consistent across repeated presses). **Clean visual, pixel-level verification was confounded**
    by the same pre-existing Xvfb+Robot stroke-release quirk documented below (a fresh window's
    first stroke sometimes fails to fire its release event, leaving the gesture loop "stuck" so the
    next stroke's samples get appended to the first instead of starting fresh) — not a defect in
    this feature (Ctrl+S, going through the exact same canvas/pointer pipeline, verified cleanly),
    but it made an unambiguous "drew one stroke, Ctrl+Z, exactly that one stroke disappeared"
    screenshot impractical in this session's time budget. The mechanism (key recognition + correct
    stack manipulation) is proven correct; full visual behavior should be re-verified on a real
    desktop OS where the underlying release-detection quirk doesn't apply.
- **A real HSV disc colour picker (`ColorWheel.kt`), not just the 8-swatch palette.** Android's
  `feature:editor` already has one (`ColorPickerDialog`/`ColorWheel` in `SketchToolsDialog.kt`) built
  on `android.graphics.Color.HSVToColor`/`RGBToHSV` and `android.graphics.Bitmap` — neither exists on
  desktop JVM, so this isn't a straight port. Instead it's rebuilt on the shared, pure-Kotlin HSV<->RGB
  math already in `core:engine`'s `ArgbColor` (used by Android's own dab-color resolution, so it's
  the same math, not a second hand-rolled implementation) plus a JVM `BufferedImage` for the wheel
  raster instead of `android.graphics.Bitmap`. A swatch at the end of the palette row toggles the
  wheel; picking a hue/saturation point or dragging the brightness slider updates the selected colour
  live, same as Android's dialog (no "Apply" step). **Verified end-to-end**: opened the wheel,
  confirmed it renders as a real hue/saturation disc (not a placeholder swatch), dragged brightness up
  from near-black, clicked a point on the disc, confirmed the custom swatch updated to the exact
  picked hue, then drew a stroke and confirmed it painted in that exact colour — the full
  pick-to-paint path, not just the picker rendering. One layout issue was caught and fixed in the
  same pass: `AzHostActivityLayout`'s rail floats as a translucent overlay on top of full-bleed
  content rather than insetting it (nothing before this grew tall enough to visibly reach into the
  rail's item region to notice), so the wheel initially rendered partially under the rail; a
  `padding(start = 100.dp)` clears the expanded rail's width. A second issue was caught later by
  adversarial review, not this session's own manual testing: the 200x200 per-pixel raster was
  regenerated synchronously on the composition thread on every `onValueChange` tick of the
  brightness slider (fired continuously while dragging, not just on release) — a real anti-pattern
  even though a single pass is cheap enough not to visibly jank on typical desktop hardware today.
  Fixed by debouncing the regeneration and moving it to `Dispatchers.Default`; re-verified the wheel
  still opens, picks, and paints correctly after the change (screenshot-confirmed end to end).
- **A live brush-size cursor preview on hover.** A thin circle outline, radius matched to the current
  brush size, tracks the mouse over the canvas — mouse hover has no equivalent on Android's
  touch/stylus input, so this is a genuine desktop-only addition, not a port of anything. Implemented
  as a second, passive `pointerInput` block on `DesktopStampCanvas` (`awaitPointerEventScope` reading
  `PointerEventType.Move`/`Exit`) that only observes position, layered as a `Canvas` overlay in the
  same `Box` as the stroke-drawing surface — it never consumes an event, so it coexists with (and
  doesn't interfere with) the actual stroke-drawing `pointerInput` already there. **Verified
  end-to-end**: moved the mouse over the canvas with no button pressed, confirmed the circle rendered
  at the exact cursor position with the exact configured radius and that nothing was painted, then
  moved again and confirmed the circle tracked to the new position.

### Fixed: `azAbout()` — a real upstream crash, found, filed, fixed, re-enabled

Android's `MainActivity.kt` calls `azAbout(dedupeAbout = true)` with every other argument left at
its default. Mirroring that exactly on desktop compiled cleanly and, on its own, rendered a real "?"
rail item that opened a genuine "About" overlay (title, a close `X`, an empty body — expected, since
neither Android nor desktop passes an `appRepositoryUrl` for the in-app reader to fetch content
from). **Closing that overlay crashed the composition**: `java.lang.IllegalStateException: Check
failed.` inside `org.jetbrains.skia.paragraph.TextStyle.setHeight`, reached through
`aznavrail-cmp`'s own `AutoSizeText` (`AutoSizeTextKt.shouldShrink` → `TextMeasurer.measure` →
`ParagraphBuilder.build` → the Skia paragraph builder) during a `BoxWithConstraints` re-subcomposition
— the close transition animating the container's width down to zero collapsed the candidate
font-size search to a single 0.sp entry, and measuring text at `fontSize=0` with a lineHeight scaled
from it produces a 0/0 = NaN ratio, which trips a native Skia assertion. Reproduced twice with the
same click sequence (open the "?" item, click the overlay's close button); the app process itself
survived (Compose "captured" the error in composition) but a Swing "Check failed." error dialog
popped up over the app — not a UI a user should ever see.

This was inside the third-party `aznavrail-cmp` library's own `internal` text-sizing code, not
anything in this repo's `desktop/` module, so it wasn't fixable here directly — it was filed as a
follow-up task against `aznavrail-cmp` instead, with the full repro and root-cause analysis above.
That task was picked up and fixed: `shouldShrink` now short-circuits on a non-finite or non-positive
font size before ever calling `textMeasurer.measure`, released as **`aznavrail-cmp` 11.45** (commit
`50c56cd`, "Fix AutoSizeText crash on zero font-size candidate"). This app bumped to 11.45 and
re-enabled `azAbout(dedupeAbout = true)` (matching Android exactly again, no more
`aboutRailItem = false` workaround) — **re-verified end-to-end**: opened the "?" item, closed it,
confirmed no crash and no error dialog, and confirmed the rail was still fully functional afterward.

One thing worth keeping in mind for whoever next touches this: **`aboutRailItem` is not something
`azAbout()` opts into — it's on by default** (`AzAdvancedConfig.aboutRailItem = true`), so simply
never calling `azAbout()` does not remove the auto "?" item; it has to be explicitly turned off with
`aboutRailItem = false` if a future pass ever wants it gone again.

### A real bug this session's own testing found and fixed: release detection

`DetectStampGestures.kt` originally ended a stroke on `PointerInputChange.changedToUp()`. Under this
container's Xvfb + `java.awt.Robot`-driven testing, that check **never fired** — not occasionally,
never. Every scripted "stroke" was silently becoming part of one never-ending gesture: `onEnd` (and
therefore `CanvasState.commitStroke`) had not run a single time all session, despite strokes visibly
painting and appearing to stack correctly (each frame's `displayBitmap` update alone was enough to
*look* right, which is exactly how this went undetected — a single isolated stroke, or even several
in sequence, looks indistinguishable on screen whether or not they're separately committed). Only
adding Undo — which depends on strokes actually committing — surfaced it.

Root cause traced with temporary `System.err.println` instrumentation (confirmed present in the
running jar via `unzip | strings`, then confirmed the `onEnd`/`commitStroke` debug lines never
appeared across two full strokes). Fixed by checking `!change.pressed` directly instead of
`changedToUp()`, which depends on the change's `previous` state being chained correctly — something
the AWT/Robot-driven synthetic pointer stream apparently wasn't providing. After the fix, `onEnd`
fired for every real stroke and each one now composites and commits independently (confirmed: two
sequential drags now paint as two independent strokes with correct dab counts each, not one
concatenated stroke with dabs from both).

**This was not a testing-only artifact** — it changes real behavior: before the fix, `samples` in
`DesktopStampCanvas` never got cleared between strokes (`onEnd`'s cleanup never ran), so every stroke
in a session was accumulating into one ever-growing sample list, recomposited from scratch each
frame. That's both incorrect (no stroke boundary ever existed) and a growing-memory/CPU problem the
longer a session ran. Whether `changedToUp()` also fails on real Linux/Windows desktop pointer input
(vs. being an Xvfb+Robot-specific synthetic-event quirk) is **not established** — `!change.pressed`
is the more defensive check either way and is what shipped.

A second, separate finding while chasing this: clicking the **Undo** rail item specifically did not
register on the very first attempt after a canvas drag, but worked reliably once preceded by a click
on any other rail item. This reproduced consistently and is almost certainly an artifact of this
environment (Xvfb has no window manager, so a synthetic `Robot` click may not transfer input focus to
the window the way a real user's click does) rather than an app bug — every other explanation (state
logic, `disabled` wiring, rail layout/footer overlap) was ruled out with debug instrumentation before
landing on this one. Flagged here rather than silently working around it, since it could not be
fully confirmed from this container.

### A second real bug, caught by adversarial review: Undo/Redo across a window resize

Not something this session's own manual testing exercised, but a `glee` adversarial-review pass
over the Undo/Redo/Clear/Save/colour-wheel/cursor-preview/flow/keyboard-shortcut work traced a real
one through the code: `DesktopStampCanvas`'s `onSizeChanged` used to be the *only* place that ever
created or resized `CanvasState.committed`'s backing `BufferedImage`, and `CanvasState.undo()`/
`redo()` restore whatever `BufferedImage` was pushed onto their stacks verbatim, at whatever size it
was when it was pushed.

Concretely: draw a stroke, resize the window (this rebuilds `committed` at the new size and blits
the old content in — `replaceWithoutHistory`, which deliberately never touches the undo/redo
stacks), draw a second stroke (this pushes the *new-sized* pre-stroke canvas onto `undoStack`).
Press Undo twice: the first pop is fine (still new-sized); the second pop restores the *old-sized*
snapshot from before the resize — and nothing ever re-checks it against the window's actual current
layout size, because the only code that did that check was `onSizeChanged`, which doesn't fire again
just because `committed` changed out from under it. Every stroke after that renders and composites
against the stale, wrong `canvasWidth`/`canvasHeight`, and `compositeTileParallel`'s
`DirtyRegion.clampTo(canvasWidth, canvasHeight)` silently clips or discards any dab outside that
stale rectangle — painting in a large part of the actual window would silently do nothing, with no
error or visual indication why.

**Fixed** by unifying all three cases — first layout, live resize, and an undo/redo that crosses a
resize — into the single `LaunchedEffect(state.committed, canvasSize)` that used to only update
`displayBitmap`: it now checks `committed`'s dimensions against the live `canvasSize` every time
*either* one changes (not just on layout events) and rebuilds/re-blits through
`replaceWithoutHistory` whenever they disagree, before ever touching `displayBitmap`. `onSizeChanged`
itself is now just `{ size -> canvasSize = size }` — one recorded size, one place that reacts to
either input changing.

Not re-verified with a scripted resize-then-undo repro in this container (the fix was traced and
applied directly from the adversarial-review finding, which reasoned through the bug from the code
as written rather than reproducing it interactively) — a reasonable next step for whoever picks this
up next, alongside everything else in this file marked logic-verified-but-not-visually-confirmed.

## UI parity: theme, icons, and layout, not just features

"Parity" isn't just matching feature checkboxes — the desktop UI itself was, until this pass, its
own invented look (a purple `#7C4DFF` accent, a bare unstyled `MaterialTheme`, no icons, a fixed
top-toolbar `Row` of sliders) that had never been checked against Android's actual design system
(`core:design`). This pass closes the checkable parts of that gap:

- **Real color scheme.** `desktop/.../Theme.kt`'s `GraffuxColors`/`GraffuxDarkColorScheme` are the
  same literal values as `core:design`'s `Color.kt`/`Theme.kt` (`HotPink = 0xFFFF00C8`,
  `Cyan = 0xFF00FFFF`, `NeonGreen = 0xFF39FF14`, black background, dark-grey surface) — copied by
  hand, since `core:design` is an `com.android.library` module (Google-Fonts-provider + non-CMP
  AzNavRail dependencies) and can't be depended on directly from `:desktop`. The rail's
  `azTheme(activeColor = ..., focusColor = ...)` and the whole window's `MaterialTheme` now both
  use it; the content pane is wrapped in a `Surface(color = MaterialTheme.colorScheme.background)`
  so the window background is actually black instead of Compose's plain-white default.
- **Real icons.** `desktop/.../Icons.kt` (`GraffuxDesktopIcons`) loads the same portable master
  SVGs Android's `GraffuxIcons.kt` generates its `@DrawableRes` set from
  (`branding/icons/masters/*.svg` — undo, redo, clear-history, document-save, brush), copied into
  `desktop/src/main/resources/icons` and rendered via `androidx.compose.ui.res.loadSvgPainter`.
  Every rail item (brush presets, Undo, Redo, Clear, Save, the new "Tool Options" toggle) now shows
  its real icon instead of a text-only label. Not exhaustive: only the icons this app's existing
  six rail actions needed were ported, not the full 400+-icon set.
- **Real app icon.** The desktop window/taskbar now uses `branding/icon-512.png` — the same
  launcher icon Android ships — via `Window(icon = ...)`, instead of the unbranded default JVM
  coffee-cup icon.
- **Floating, draggable tool-option panels — the actual layout paradigm change, not just a
  mechanical recolor.** The fixed top-toolbar `Row` (brush size/flow sliders, inline swatches) is
  gone. `desktop/.../FloatingWindow.kt` is a copy of `core:design`'s `FloatingWindow` composable,
  adapted for desktop; both wrap the same `AzWindow`/`AzWindowState` primitive from `aznavrail-cmp`
  (11.45, already a `:desktop` dependency) — confirmed by decompiling
  `aznavrail-cmp-desktop-11.45.jar` and reading `aznavrail-cmp`'s own `commonMain` source
  (`AzWindow.kt`) before writing this, not assumed. This is a genuine reuse of Android's real
  floating-window mechanism (dragging, onscreen clamping, z-index stacking all come from the same
  library code both platforms call), not a second hand-built implementation. "Tool Options" (brush
  radius/flow/color swatches) and "Color" (the HSV wheel, see below) are now each their own
  draggable, closable `FloatingWindow`, Procreate-style, matching how Android's
  `SketchToolsDialog`/`ColorPickerDialog` present themselves — multiple can be open over the
  artwork at once, and closing one doesn't block the canvas the way a modal dialog would.
  Simplifications versus Android's version: no rail-avoidance obstruction (this app's rail is a
  translucent overlay, not docked/inset, so there's no strip a panel needs to stay clear of) and no
  mount-time re-clamp effect (`AzWindow` itself, 11.38+, already keeps a window onscreen on its
  own).
- Verified visually, not just by compiling: built and ran the real desktop app under Xvfb
  (`:desktop:run`, software-rendered — `Cannot create Linux GL context` is expected in this
  GPU-less container and Skiko falls back automatically) and screenshotted it. The black
  background, hot-pink accent, draggable "Tool Options" panel with its close button, and real
  icons on every rail item are all visible in the captured frame, not just present in source.

**What "all the way" still doesn't cover, honestly:**

- **No bundled Roboto Condensed typography.** Android's `Typography.kt` loads Roboto Condensed via
  `androidx.compose.ui.text.googlefonts.GoogleFont.Provider`, a Google-Play-Services mechanism with
  no desktop-JVM equivalent. The real TTF files were fetched from Google Fonts' CDN (Apache-2.0,
  safe to bundle) with the intent of loading them directly, but this Compose Multiplatform version
  (1.12.0)'s `androidx.compose.ui.text.font.Font` has no desktop-side `(String, ByteArray, ...)` or
  file-based overload — confirmed by decompiling `ui-text-desktop-1.12.0.jar`, not assumed. The
  real path is `org.jetbrains.compose.resources`' font-resource codegen (`composeResources/font/`,
  a generated `Res.font.*` accessor) — a new Gradle-module convention, not just a dependency bump,
  and out of scope for this pass. Desktop text still renders in Compose's stock default font.
- **The `ColorWheel` FloatingWindow is still just the wheel + brightness slider** — no
  foreground/background swap, no Disc/Harmony/Palettes tabs, no saved-palette/recent-color rows the
  way Android's `ColorPickerDialog` has. It's in a real floating panel now, matching the layout
  paradigm, but the picker's own contents inside that panel are unchanged from before this pass.
- **Icon coverage is 5 icons, not 400+.** Every brush preset currently shares the single generic
  "brush" icon rather than a per-brush icon — Android's `GraffuxIcons` almost certainly has
  distinct icons per brush type, not looked up here.
- Every other item in "What's deliberately NOT done in this pass" below (layers, selection tools,
  Brush Studio, extensions manager, the AR/vision pipeline, GPU acceleration) is exactly as missing
  from the UI as it was before — this pass is about how the existing surface *looks*, not about
  growing that surface.

## What's deliberately NOT done in this pass, and why

- **No Opacity/Feathering/Stabilizer/Symmetry/Alpha-Lock/Wrap-Around controls — and this is not
  actually a parity gap.** A first pass at "what other brush controls does Android's toolbar have
  that desktop doesn't" turned these up, but tracing each one through `DrawingEngine.kt` shows they
  all belong to `ImageProcessor.applyToolToBitmap`'s plain `Tool.BRUSH` path (`drawStrokeDynamic`,
  a velocity-width curve stroke) — a *different, non-stamp* brush pipeline Android also has, not the
  azphalt stamp-brush path (`stroke.stampBrush` → `BrushStamps.dynamicDabs` → `StampBrushRenderer`)
  desktop exclusively uses. That stamp path only ever reads `stroke.flow` (already wired on
  desktop) — never `opacity`, `feathering`, or any of the others; a comment on the opacity code
  itself says as much ("as opposed to a stamp brush's per-dab Flow — see StampBrushRenderer,
  deliberately left alone"). Wiring these on desktop would mean inventing behavior Android's own
  stamp pipeline doesn't have, not closing a real gap. Noted here so a future pass doesn't
  re-discover the same list and assume it's actionable without re-tracing it.
- **No native GPU-accelerated engine rewrite.** The Android app's real GPU path
  (`core:nativebridge`, Vulkan via JNI/NDK) is Android-ABI-specific and cannot run on desktop JVM.
  A genuine GPU-accelerated desktop engine would mean a second native backend from scratch (Vulkan
  via LWJGL, or DirectX on Windows specifically) — a multi-week undertaking on its own, and one that
  cannot be meaningfully verified from a GPU-less Linux container. What ships here instead is the
  CPU-side, multi-core-parallel compositor described above — a real, tested, in-scope optimization,
  not a placeholder for the GPU rewrite.
- **No stylus tilt.** Compose Multiplatform Desktop currently exposes pointer pressure and
  `PointerType`, not tilt/orientation, so tilt-driven brush behavior (available on Android via
  `BrushSample.tiltRadians`) has no desktop input source yet.
- **The rail has a brush-preset switcher, a real About screen, and edit actions
  (Undo/Redo/Clear/Save), but not the rest of the Android rail's tool set.**
  `AzHostActivityLayout`/`azConfig`/`azRailItem` are real and wired up (see above), and colour
  selection has both a fixed 8-swatch row and a real HSV disc picker (see below), but the desktop
  app doesn't yet reproduce layers, selection tools, Brush Studio, or an extensions manager. This is
  a UI population gap now, not a library-capability gap.
- **No Hilt DI, OpenCV, CameraX, or the AR/vision pipeline.** Those are genuinely Android-only
  dependencies (camera capture, ML Kit segmentation, wall-surface detection, image import/warp) with
  no Compose Multiplatform or portable-Kotlin equivalent available — not attempted, not planned as
  a near-term follow-up.
- **No layers or `.graffux` project save/load.** Undo/redo and flattened PNG export are real and
  verified (see above) — what's still missing is a layer stack and round-tripping the app's own
  project format, not history or "getting pixels out" in general.
- **Shaped/masked brush tips, grain, and the masked-brush dual-tip compositor are not wired in** —
  `BrushStamps.dynamicDabs` resolves them when a preset declares them, but no built-in preset does
  and the desktop app has no extension-install flow to bring in tip/grain assets yet, so this is
  untested on desktop specifically (it IS tested via the Android-side azphalt suite, which still
  passes against the same shared code).
- **Windows packaging is configured but unverified from this container.** `nativeDistributions` in
  `desktop/build.gradle.kts` declares `Msi` alongside Linux's `Deb`/`Rpm`. Building an actual `.msi`
  needs the WiX Toolset, which only runs on a Windows host/CI runner — not available here, so the
  Windows installer output itself has never been produced or tested locally, only configured. A CI
  job now exists that should build and verify it for real on every release — see below.

## CI: desktop installers now ship alongside the APK

`.github/workflows/release-apk.yml` (renamed in spirit to "Compile and Release APK + Desktop", same
filename) builds the Android APK and all three desktop installers in parallel
(`build-android`, `build-desktop-linux` for `.deb`/`.rpm`, `build-desktop-windows` for `.msi`), then
a `publish-release` job downloads everything and attaches all four files to the one GitHub Release
the APK job has always published to (tag `latest-release-v${MAJOR}.${MINOR}`) — a single manual
`workflow_dispatch` now produces every platform's build in one place. None of the desktop jobs touch
the Android signing keystore or any other secret.

What's verified vs. not: the Linux job's two Gradle tasks (`packageDeb`, `packageRpm`) were both run
to completion in this session (see the Verification section below) — genuinely producing an
installable `.deb` and `.rpm` with the dynamic version wired up. The Windows job (WiX Toolset via
Chocolatey, then `packageMsi`) has **never actually run** — there is no Windows runner available
from this sandboxed authoring environment, so that job is reasoned-through-and-should-work, not
verified. The workflow YAML itself was validated for syntax (`python3 -c "import yaml; ..."`) and
its Gradle task names confirmed real (`:desktop:tasks --all`), but the workflow as a whole has never
been run by GitHub Actions. The first real `workflow_dispatch` of this file is the actual test of
the Windows leg — check that run's `build-desktop-windows` job log before trusting it.

## Caught by adversarial review (glee), fixed before this was pushed

A `glee` review pass (per the user's explicit request to use it "along the way") found the first
draft's two headline claims were both false as implemented:

1. **The stroke's last dab(s) were silently dropped on every release.** The move handler fired a
   `scope.launch` per pointer-move and the release handler baked `displayBitmap` synchronously,
   with nothing joining the in-flight render job first — so release routinely ran before the last
   move's render had even started. Fixed by replacing `detectDragGestures` (whose callbacks are
   plain, non-suspend lambdas — the root cause of the fire-and-forget) with a hand-rolled gesture
   loop (`detectStampGestures` in `DetectStampGestures.kt`) whose start/move/end callbacks are
   suspend functions invoked sequentially from one coroutine: each move's render is awaited before
   the next pointer event is even read.
2. **"Tile-parallel" compositing never left one thread.** `compositeTileParallel`'s `async { }`
   calls never specified `Dispatchers.Default`, so they inherited the caller's own dispatcher —
   `rememberCoroutineScope()`'s single-threaded composition-bound one — meaning every tile
   rasterized sequentially despite the doc comments (and this file) claiming multi-core use. Fixed
   by explicitly launching each tile's `async` on `Dispatchers.Default`.

Both fixes were re-verified the same way as the first pass (`:desktop:compileKotlin`, a fresh
`:desktop:run` under Xvfb, and a scripted drag + screenshot confirming the stroke now reaches its
actual endpoint instead of stopping short). The "verified" claims below reflect the code as it
stands after these fixes, not the first draft glee reviewed.

## Verification performed this session

- `:core:engine:desktopTest`, `:core:engine:testAndroidHostTest` — pass.
- `:core:common:testDebugUnitTest` (full existing azphalt suite, ~20 files, unmoved) — pass.
- `:feature:editor:testDebugUnitTest` — pass (confirms the extraction didn't disturb the Android
  renderer or its own regression tests, including the live-preview hardening fix).
- `:desktop:compileKotlin`, `:desktop:assemble` — succeed.
- `:desktop:run` under Xvfb with software Skia rendering — launches, shows the canvas, and a
  scripted pointer drag visibly paints a soft-edged stroke reaching its actual endpoint (screenshots
  captured during this session, before and after the glee-caught fixes above).
- `:desktop:packageDeb` was run to completion and produced a real, installable
  `graffux_1.39.74_amd64.deb` (self-contained JRE runtime image + app jars, `dpkg -c` verified its
  layout under `/opt/graffux`). The version in that filename is read live from the repo's own
  `version.properties` (`desktop/build.gradle.kts`'s `desktopPackageVersion`, read-only — it does
  NOT advance the shared version counter the Android release pipeline owns), not a hardcoded
  placeholder, so a `.deb`/`.rpm`/`.msi` built alongside a given APK reports the same
  major.minor.patch. `:desktop:packageRpm` was also run to completion (after installing the `rpm`
  package, which provides `rpmbuild`) and produced a real `graffux-1.39.74-1.x86_64.rpm`. `Msi`
  still cannot be built from Linux at all — it needs the WiX Toolset, only available on a Windows
  host/CI runner (see above, and see `.github/workflows/release-apk.yml`'s `build-desktop-windows`
  job, which installs it via Chocolatey — that job has never run for real, no Windows runner being
  available from this sandboxed session either).
- The real AzNavRail rail, brush-preset switching, colour palette, and Undo were all re-verified
  after the release-detection fix above: two scripted strokes, click Undo, screenshot confirms only
  the second stroke is removed (reproduced twice). `:core:engine:desktopTest`,
  `:core:engine:testAndroidHostTest`, and `:desktop:assemble` all still pass after these changes.
