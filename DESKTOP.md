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
- **The rail only has a brush-preset switcher and an 8-swatch palette.** `AzHostActivityLayout`/
  `azConfig`/`azRailItem` are real and wired up (see above), and colour selection genuinely works
  (a fixed swatch row, not a full HSV picker), but the desktop app doesn't yet reproduce the rest of
  the Android rail's tool set (layers, selection tools, Brush Studio, undo/redo, extensions manager,
  About/Help screens the library provides for free but this app hasn't populated with app-specific
  content). This is a UI population gap now, not a library-capability gap.
- **No Hilt DI, OpenCV, CameraX, or the AR/vision pipeline.** Those are genuinely Android-only
  dependencies (camera capture, ML Kit segmentation, wall-surface detection, image import/warp) with
  no Compose Multiplatform or portable-Kotlin equivalent available — not attempted, not planned as
  a near-term follow-up.
- **No layers, undo/redo, or project persistence** (save/load a `.graffux` project, export). The
  desktop canvas is a single always-live bitmap.
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
