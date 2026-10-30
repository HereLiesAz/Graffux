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
  has no GPU). It shows a working stamp-brush canvas: pointer drag paints strokes with the same
  falloff math and max-combine compositing the Android renderer uses, on the wgpu GPU engine when
  an adapter exists (Mesa lavapipe here) and `RoundStampCompositor` otherwise, so a soft-hardness
  stroke reads the same way on desktop.
- **Pen-aware input**: `PointerInputChange.pressure` is read and drives per-dab radius/alpha.
  Compose Multiplatform Desktop surfaces real Windows Ink pressure for `PointerType.Stylus` — this
  is the actual hook a Surface Pen would feed. **Not verified**: no Windows machine or pen hardware
  is available in this container, so the pressure *value itself* arriving correctly from real
  Surface Pen hardware is unverified — only that the code path reads and uses whatever pressure
  Compose reports (confirmed at 1.0 from mouse/Xvfb input).
- **Multipass drying (experimental, off by default).** Tool Options -> "Multipass drying
  (experimental)" and a drying-transition slider. Live frames stamp only the new dabs through the
  wgpu engine's multipass path: a draft shows at once and settles to full quality as refinement
  catches up (an idle tick keeps it going while the pointer rests). At stroke end the renderer lands
  the queued work off the UI thread and renders the committed frame exactly as the plain path's last
  frame, so the committed image is identical either way; a stroke started during that wait waits
  for it. `GpuStrokeParityTest.multipassCommitsTheSameFramesAsThePlainPath` checks it. See the
  design doc, §2b "Multipass drying".
- **GPU compositing through wgpu, CPU as the fallback.** The canvas composites strokes on the
  GPU with the wgpu stamp engine (`core/wgpu-engine`, Rust + WGSL), the same engine the Android
  app uses (its only GPU backend). wgpu picks Vulkan, DX12, Metal or GL on each
  machine. `:desktop` builds the crate for the host with cargo (`cargoBuildWgpuHost`) and bundles
  the library as a classpath resource (`native/<os>-<arch>/`). `WgpuLibrary` (core:engine's
  `jvmShared` source set) extracts and loads it. `GpuStrokeRenderer` uploads the pre-stroke canvas,
  stamps the stroke's dabs with the same colour, strength and hardness rules as
  `RoundStampCompositor`, and reads back into the `BufferedImage`/Skia bitmap. Each frame restores
  and reads back only the rows the stroke covers. Zero-copy display is not attempted.
  When there is no library or no adapter with compute support, or a call fails, or the app runs
  with `-Dgraffux.gpu=false`, the canvas uses the CPU compositor below. Without cargo on the build
  host the app still builds, CPU-only (`-Pgraffux.wgpu.require=true` makes that a build failure).
  **Verified in this GPU-less container** on Mesa: the real app under Xvfb logs
  `Graffux canvas: GPU wgpu -- Vulkan: llvmpipe ...` and paints a scripted drag. `GpuStrokeParityTest`
  compares the GPU path with the CPU path through JNI on both wgpu backends (lavapipe Vulkan,
  llvmpipe GL): alpha within 1 level, premultiplied colour within 2. **Not verified:** a real GPU,
  Windows (DX12/Vulkan), and speed against the CPU path on real hardware.
- **Tile-parallel CPU compositing** (the fallback): `compositeTileParallel` splits a stroke's
  dirty region (`DirtyRegion.fromDabs` + `TileGrid`) into tiles and composites them concurrently via
  `kotlinx.coroutines` `Dispatchers.Default`, which uses one thread per CPU core. Surface Pro
  devices are multi-core, so on a machine without a usable GPU adapter this keeps every core busy
  instead of rasterizing single-threaded on the UI thread.
- **The rail.** The desktop rail is the project's own (`desktop/.../DesktopRail.kt`): a pill of
  44dp buttons drawn with the same primitives as the Android rails (one button per bundled brush
  set, then the canvas actions). AzNavRail (`aznavrail-cmp`) now supplies only the About page; the
  rail, its menus and the floating windows are not AzNavRail's.
- **Real color scheme.** `desktop/.../Theme.kt`'s `GraffuxColors`/`GraffuxDarkColorScheme` are the
  same literal values as `core:design`'s `Color.kt`/`Theme.kt` (`HotPink = 0xFFFF3EA5`,
  `Cyan = 0xFF00FFFF`, `NeonGreen = 0xFF39FF14`, black background, dark-grey surface) — copied by
  hand, since `core:design` is an `com.android.library` module (Google-Fonts-provider + non-CMP
  Android-only dependencies) and can't be depended on directly from `:desktop`. The rail and the
  whole window's `MaterialTheme` both use it; the content pane is wrapped in a `Surface(color = MaterialTheme.colorScheme.background)`
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
  (11.47 at the time, now 11.57; already a `:desktop` dependency) — confirmed by decompiling
  `aznavrail-cmp-desktop-11.47.jar` and reading `aznavrail-cmp`'s own `commonMain` source
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
  (1.12.0 when this was checked; 1.12.1 now)'s `androidx.compose.ui.text.font.Font` has no desktop-side `(String, ByteArray, ...)` or
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
  Brush Studio, extensions manager, GPU acceleration) is exactly as missing
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
- **The GPU engine covers round stamp brushes only on desktop so far.** The wgpu engine also
  implements shaped/masked tips, grain, dual-brush tips, substrate and Color Smudge (it passes the
  same cross-engine scenarios as the Android engines, see `tools/stamp-engine-diff`), but the
  desktop canvas itself only draws round stamp brushes, so only that path is wired. An earlier
  version of this document ruled out a desktop GPU engine as a from-scratch second backend. The
  decision since is the opposite: wgpu is the long-term single engine for Android and desktop
  (ARCHITECTURE.md, Decisions); Android's Vulkan and GLES engines have since been retired.
- **No stylus tilt.** Compose Multiplatform Desktop currently exposes pointer pressure and
  `PointerType`, not tilt/orientation, so tilt-driven brush behavior (available on Android via
  `BrushSample.tiltRadians`) has no desktop input source yet.
- **The rail has a brush-preset switcher, a real About screen, and edit actions
  (Undo/Redo/Clear/Save), but not the rest of the Android rail's tool set.**
  `AzHostActivityLayout`/`azConfig`/`azRailItem` are real and wired up (see above), and colour
  selection has both a fixed 8-swatch row and a real HSV disc picker (see below), but the desktop
  app doesn't yet reproduce layers, selection tools, Brush Studio, or an extensions manager. This is
  a UI population gap now, not a library-capability gap.
- **No Hilt DI or ML Kit segmentation.** Those are genuinely Android-only
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

## CI: desktop installers are not built yet

No workflow builds the desktop installers. An earlier draft of this document described a
`release-apk.yml` with `build-desktop-linux` / `build-desktop-windows` jobs; that file no longer
exists (the repo's workflows are `merged-build.yml`, the two Android release hand-offs,
`label.yml` and `stroke-data-kaggle.yml`). HereLiesAz/workflows has a `desktop-package.yml` that
could take this on. Until then `.deb`/`.rpm` are built locally with `:desktop:packageDeb` /
`:desktop:packageRpm`, and `.msi` needs a Windows host with the WiX Toolset.

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

## Verification performed (historical log)

A record of one past session; version numbers and test counts below are from that time, not now.

wgpu engine pass:

- `cargo test` in `core/wgpu-engine` (at the time of that pass; the suite now has well over a hundred
  `#[test]` functions across 15 files), both wgpu backends (lavapipe Vulkan, llvmpipe GL).
- `tools/stamp-engine-diff/run.sh`: before their retirement, wgpu on GL was byte-identical to
  `GlesStampEngine` in all 26 scenarios and wgpu on Vulkan within 1 level of `VulkanStampEngine`.
  It now checks wgpu alone (Vulkan against GL, multipass, direct display, resident layers).
- `:desktop:test` (`GpuStrokeParityTest`) on `WGPU_BACKEND=vulkan` and `WGPU_BACKEND=gl`.
- `:desktop:run` under Xvfb: the canvas reports the wgpu adapter and paints a `java.awt.Robot`
  drag. The same drag with `-Dgraffux.gpu=false` paints through the CPU compositor.

Earlier passes:

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
  host (see "CI: desktop installers are not built yet" above).
- The rail, brush-preset switching, colour palette, and Undo were all re-verified
  after the release-detection fix above: two scripted strokes, click Undo, screenshot confirms only
  the second stroke is removed (reproduced twice). `:core:engine:desktopTest`,
  `:core:engine:testAndroidHostTest`, and `:desktop:assemble` all still pass after these changes.
