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
- **No AzNavRail UI.** AzNavRail ships as an Android AAR only (confirmed: only Android release
  variants are ever resolved in this build's Gradle cache); it has no desktop-JVM Compose
  Multiplatform target. The desktop app's UI is a minimal Material3 scaffold, not a port of the
  Android rail/tool UI.
- **No Hilt DI, OpenCV, CameraX, or the full editor.** Those are Android-only dependencies
  (`core:common`'s AR/vision/import pipeline) not touched or ported this session. The desktop app
  is a standalone drawing-canvas vertical slice proving the shared engine and desktop renderer,
  not a port of `feature:editor`'s full stroke pipeline (sensor-binding UI, shaped/masked tips,
  layers, undo, grain, airbrush hold-to-build-up, etc.).
- **`BrushStamps.place`'s arc-length dab placement is not wired into the desktop canvas.** The
  desktop app stamps a dab per pointer-move event past a minimum spacing threshold, rather than
  resampling the path at fixed arc-length intervals with interpolated pressure the way the Android
  editor does. This is simpler and correctly exercises the shared falloff/compositing math, but a
  faithful port of arc-length placement (which needs per-dab pressure interpolation along the
  resampled path, not just at the original sample points) is real follow-up work.
- **Windows packaging is configured but unverified.** `nativeDistributions` in
  `desktop/build.gradle.kts` declares `Msi` alongside Linux's `Deb`/`Rpm`. Building an actual `.msi`
  needs the WiX Toolset, which only runs on a Windows host/CI runner — not available here, so the
  Windows installer output itself has never been produced or tested, only configured.

## Verification performed this session

- `:core:engine:desktopTest`, `:core:engine:testAndroidHostTest` — pass.
- `:core:common:testDebugUnitTest` (full existing azphalt suite, ~20 files, unmoved) — pass.
- `:feature:editor:testDebugUnitTest` — pass (confirms the extraction didn't disturb the Android
  renderer or its own regression tests, including the live-preview hardening fix).
- `:desktop:compileKotlin`, `:desktop:assemble` — succeed.
- `:desktop:run` under Xvfb with software Skia rendering — launches, shows the canvas, and a
  scripted pointer drag visibly paints a soft-edged stroke (screenshot captured during this
  session).
- `:desktop:packageDeb` was run to completion and produced a real, installable
  `graffux_1.0.0_amd64.deb` (self-contained JRE runtime image + app jars, `dpkg -c` verified its
  layout under `/opt/graffux`). `packageRpm` was not separately exercised this session (same
  `jpackage` path as `Deb`, just a different target format, so it's expected to behave the same, but
  that's an expectation, not a verification). `Msi` cannot be built from Linux at all — it needs the
  WiX Toolset, only available on a Windows host/CI runner (see above).
