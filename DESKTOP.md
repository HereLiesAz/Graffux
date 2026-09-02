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
  Material3 scaffold: **verified** by `:desktop:run` under Xvfb showing the rail (hamburger menu,
  a "Brush" item with a live size badge, the library's own built-in help item) rendered alongside a
  working canvas, and a scripted drag still painting correctly with the rail present.
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
  part is fixed: the window now opens at 1000x900 and Redo is visible. **State logic verified
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
  `padding(start = 100.dp)` clears the expanded rail's width.
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

### Investigated and deliberately NOT shipped: `azAbout()` — a real crash, not a testing artifact

Android's `MainActivity.kt` calls `azAbout(dedupeAbout = true)` with every other argument left at
its default. Mirroring that exactly on desktop compiled cleanly and, on its own, rendered a real "?"
rail item that opened a genuine "About" overlay (title, a close `X`, an empty body — expected, since
neither Android nor desktop passes an `appRepositoryUrl` for the in-app reader to fetch content
from). **Closing that overlay crashed the composition**: `java.lang.IllegalStateException: Check
failed.` inside `org.jetbrains.skia.paragraph.TextStyle.setHeight`, reached through
`aznavrail-cmp`'s own `AutoSizeText` (`AutoSizeTextKt.shouldShrink` → `TextMeasurer.measure` →
`ParagraphBuilder.build` → the Skia paragraph builder) during a `BoxWithConstraints` re-subcomposition
— most likely the close transition animating the container's width down to something Skia's text
layout can't handle at very small/zero sizes, though the exact trigger wasn't pinned down further.
Reproduced twice with the same click sequence (open the "?" item, click the overlay's close button);
the app process itself survived (Compose "captured" the error in composition) but a Swing "Check
failed." error dialog popped up over the app — not a UI a user should ever see.

This is inside the third-party `aznavrail-cmp` library's own `internal` text-sizing code, not
anything in this repo's `desktop/` module, so it isn't fixable here. Two things worth knowing for
whoever revisits this:

1. **`aboutRailItem` is not something `azAbout()` opts into — it's on by default** (`AzAdvancedConfig
   .aboutRailItem = true`) and has to be explicitly turned off. Simply never calling `azAbout()` does
   NOT remove the auto "?" item, which is why the fix here is an explicit
   `azAbout(aboutRailItem = false)` call, not just deleting the `azAbout()` line. (This surfaced only
   because the window had finally grown tall enough — see the `Window()` size history above — for a
   9th rail item to actually be visible; it was very likely present, and just as likely to crash on
   close, well before this pass touched it.)
2. Whether this reproduces on a real (non-Xvfb, non-software-rendered) Linux or Windows desktop, or
   only under this container's `SKIKO_RENDER_API=SOFTWARE` software rendering path, is **not
   established** — this was only ever tested in this container. If a future pass wants to re-enable
   it, that's the first thing to check, along with whether a newer `aznavrail-cmp`/Compose
   Multiplatform release has since fixed the underlying Skia assertion.

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

## What's deliberately NOT done in this pass, and why

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
- **The rail has a brush-preset switcher and edit actions (Undo/Redo/Clear/Save), but not the rest
  of the Android rail's tool set.** `AzHostActivityLayout`/`azConfig`/`azRailItem` are real and wired
  up (see above), and colour selection has both a fixed 8-swatch row and a real HSV disc picker (see
  below), but the desktop app doesn't yet reproduce layers, selection tools, Brush Studio, or an
  extensions manager. This is a UI population gap now, not a library-capability gap. **About/Help was
  attempted and deliberately reverted** — see the crash writeup further down.
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
- **Windows packaging is configured but unverified.** `nativeDistributions` in
  `desktop/build.gradle.kts` declares `Msi` alongside Linux's `Deb`/`Rpm`. Building an actual `.msi`
  needs the WiX Toolset, which only runs on a Windows host/CI runner — not available here, so the
  Windows installer output itself has never been produced or tested, only configured.

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
  `graffux_1.0.0_amd64.deb` (self-contained JRE runtime image + app jars, `dpkg -c` verified its
  layout under `/opt/graffux`). `packageRpm` was not separately exercised this session (same
  `jpackage` path as `Deb`, just a different target format, so it's expected to behave the same, but
  that's an expectation, not a verification). `Msi` cannot be built from Linux at all — it needs the
  WiX Toolset, only available on a Windows host/CI runner (see above).
- The real AzNavRail rail, brush-preset switching, colour palette, and Undo were all re-verified
  after the release-detection fix above: two scripted strokes, click Undo, screenshot confirms only
  the second stroke is removed (reproduced twice). `:core:engine:desktopTest`,
  `:core:engine:testAndroidHostTest`, and `:desktop:assemble` all still pass after these changes.
