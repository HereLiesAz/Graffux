# ARCHITECTURE.md

Companion file mandated by `_AGENTS.md` and `AGENTS.jules.md`. Holds what shouldn't live in
a prompt: module boundaries, invariants, current version, and decisions with their reasons.
Read before proposing structural changes. Never recalled — opened.

---

## Current version

- App version: `versionMajor.versionMinor.versionPatch`, computed from `version.properties`
  and auto-incremented on every `assembleDebug`/`bundleRelease` — do not hardcode a snapshot
  of it here, it drifts on the next local build. See `CLAUDE.md`'s "versionCode and Play
  publishing" section before touching that file or this one.
- Kotlin `2.4.20`, AGP `9.4.1`.
- `AzNavRail` (`com.github.HereLiesAz.AzNavRail:aznavrail`) `11.18`+ required — `11.15`
  through `11.17` have the reloc-item-under-`azUnattachedHostItem` bug described below;
  `11.18` is the first version confirmed (via upstream's own `AzUnattachedRelocItemClickTest`)
  to fix it. Do not downgrade below `11.18`.

---

## Module boundaries

| Module | Responsibility |
|---|---|
| `:app` | The Graffux application shell — `MainActivity`/`GraffuxApp` host the AzNavRail rail configuration and the shared editor. Only this module is Graffux-specific (`com.hereliesaz.graffux`). |
| `:feature:editor` | The editor: `EditorReducer`, `EditorViewModel`, canvas, panels, brush/stroke engine, export, dialogs/windows (Curves, Figma, Reference, Gallery, Store, etc.). |
| `:core:common` | Models (`Layer`, `EditorUiState`, `GraffitiProject`), pure ops (`LayerListOps`, `LinkOps`), serialization, the `azphalt` extension format's Android-free types (`AzphaltManifest`, `CubeLut`, `TrustStore`). |
| `:core:domain` | Repository interfaces. |
| `:core:data` | Project + settings persistence, the `azphalt` runtime: `AzpInstaller`, `ExtensionRepository`, `ExtensionStateStore`/`ExtensionStateProvider` (state-reporting persistence and its exported, read-only `ContentProvider` — `spec/state-reporting.md`), and the Chicory-based sandboxes (`JsSandbox`, `WasmSandbox`). |
| `:core:design` | Design system: theme, `AppStrings`, reusable components (`FloatingWindow`, `AdjustmentsPanel`, `ConfirmDialog`, etc.). |
| `:core:nativebridge` | JNI bridge to the native (OpenCV/Vulkan) world used by Liquify, drawing, and GPU compositing. Hosts the three GPU stamp engines behind `StampEngine.h`: Vulkan, OpenGL ES, and the wgpu adapter. |
| `core/wgpu-engine` | Not a Gradle module: the Rust crate of the wgpu GPU stamp engine (WGSL compute), built by cargo from `:core:nativebridge` (Android, arm64-v8a) and `:desktop` (host). Exposes a C ABI for the C++ adapter and JNI for `core:engine`'s `WgpuStampEngine`. |
| `:core:engine` | The azphalt stamp-brush engine as pure Kotlin Multiplatform math/data (`BrushStamps`, `AzphaltBrush`, `BrushSensorDynamics`, `TileGrid`, `DirtyRegion`, ...), zero Android dependency, targeting both `androidMain` and `jvm("desktop")`. `:core:common` depends on this under the same package name. Its `jvmShared` source set (Android + desktop) holds the JNI wrapper of the wgpu engine. |
| `:desktop` | The real Graffux desktop app (Linux/Windows, Compose Multiplatform) — not published from this table's other modules, but a third consumer of `:core:engine`'s shared math alongside Android Graffux and GraffitiXR. See `DESKTOP.md`. |

`:core:*` and `:feature:editor` keep the `com.hereliesaz.graffitixr` namespace — they are the
shared single source of truth also consumed by [GraffitiXR](https://github.com/HereLiesAz/GraffitiXR),
which adds AR on top of the same editor stack, AND by `:desktop` in this same repo. A change to
any `:core:*` or `:feature:editor` file is a change to GraffitiXR too, whether or not this repo's
CI can see that — and, for `:core:engine` specifically, a change the desktop app's own `commonTest`
suite in this repo *does* see, since it runs the identical brush-math tests against both targets.

---

## Invariants

1. **MVI, one reducer.** All editor state transitions go through `EditorIntent` →
   `EditorReducer.reduce` (`feature/editor/.../EditorReducer.kt`), a pure function with no
   Android dependencies — it's unit-tested in isolation (`EditorReducerTest.kt`).
   `EditorViewModel` is where every side effect (history, persistence, OpenCV, coroutines)
   lives; the reducer itself must never gain one.
2. **`EditHistory` snapshots layers, not bitmaps.** Undo/redo pushes `EditorUiState.layers`
   with bitmaps stripped; `LayerStore`'s cached base+stroke data is what a restore rebuilds
   pixels from. A destructive async operation (merge, flatten, duplicate) must bracket its
   work in `EditorIntent.SetLoading(true/false)` — the loading state blocks input for the
   duration, closing the window where an Undo tap could race the in-flight composite.
3. **`version.properties` is never reverted, restored, or `git checkout`-ed.** It
   auto-increments on `assembleDebug`/`bundleRelease`; that's intended. `versionCode` is
   computed from the *committed* `versionBuild + 1`, and CI does not write the increment
   back — see `CLAUDE.md` for the exact failure mode this has caused repeatedly.
4. **`ConfigureRailItems` is not `@Composable`.** It's a plain extension function on
   `AzNavHostScope`, so any Compose state it needs (`remember { mutableStateOf(...) }`) has
   to live in the calling `@Composable` (`GraffuxApp`) and thread down as a parameter or
   callback — never `remember`ed inside the DSL builder itself.
5. **Rail highlighting has one source of truth.** `activeRailClassifiers()` in
   `MainActivity.kt` is the single place that decides which rail-item ids are "active"; every
   item's `classifiers` and its manual `color` fallback both read from that same set, so the
   two can't disagree. Don't inline a second copy of an active-state condition on the item
   that uses it.
6. **`azphalt` extensions are deny-by-default.** A capability (`canvas`, `color`, `time`, …)
   an extension's manifest didn't request is never mapped into its sandbox — not omitted from
   the WASM import list (that breaks module linking outright, since `quickjs.wasm` declares
   `clock_time_get`/`random_get` as mandatory imports), but replaced with a fixed, non-real
   answer. See `JsSandbox.kt`'s `timeDenyHostFunctions()`. A granted `assets` capability reads
   only the invoking extension's own manifest-listed files, via `ExtensionScopedSandboxHost` /
   `ExtensionAssetReader` (path rules shared with `AzpInstaller`, 4 MiB per-read cap;
   `spec/package-format.md` § 5.1.1).
7. **`azNavRail` must stay pinned to `11.18` or newer.** `azRailRelocItem` items (every layer
   row in the `"grp.layers"` panel is one) were completely unclickable under an
   `azUnattachedHostItem` in `11.15` through `11.17` — `RailContent.kt` nulls `onClick` for
   any `isRelocItem`, relying entirely on an externally-supplied `dragModifier` that
   `AzUnattachedRail.kt`'s `UnattachedNode` never provided. Confirmed fixed in `11.18`
   (`UnattachedNode` now wires its own tap/long-press gesture; see upstream's
   `AzUnattachedRelocItemClickTest.kt`). This was never fixable from this repo —
   `dragModifier` isn't exposed through `azRailRelocItem`'s public API — so don't reintroduce
   the bug by downgrading the version pin below `11.18` for an unrelated reason.
8. **Group layers are rail hosts, never nested rails.** In the `"grp.layers"` panel a `GROUP`
   layer is an `azRailSubHostItem` (id `layer.<groupId>`) under its parent's host, and its
   children are `azRailRelocItem`s whose `hostId` is that id — see `LayerRailPlan.kt`'s
   `layerRailRows`. Do not use `azRailRelocItem`'s `nestedContent` / `keepNestedRailOpen` for
   layers. AzNavRail has no item that is both a reloc item and a host, so a group itself can't be
   dragged, and since a reloc cluster is a contiguous run of same-host reloc items, a group also
   bounds how far its leaf siblings can be dragged past it.

---

## Known documentation gaps

`spec/package-format.md`, `spec/store-app.md`, and `spec/state-reporting.md` exist now
(reverse-engineered from the code that implements them), but the first two still reference six
more normative documents that are cited throughout the `azphalt` code and still don't exist
anywhere in this repo: `spec/extension-manifest.md`, `spec/pack.md`, `spec/companion-app.md`,
`spec/mcp-server.md`, `spec/ui-schema.md`, `spec/repository-api.md`. Each is a genuine gap, not a
broken link — the code paths they'd document are real and working. Write them the same way: read
every referencing file first, ground every claim in code, mark anything unconfirmable as a TODO
rather than inventing it.

`spec/package-format.md` and `spec/state-reporting.md` also flag three open questions worth
resolving with someone who has product context, not just code-reading: whether the `bitmap`/`audio`
`Capability` wire values (declared but with no sandbox host-function bridge) are an intentional
future reservation or a gap; whether `JsSandbox.eval()` discarding the QuickJS exception's actual
message (in favor of a generic `RuntimeException`) is acceptable or should propagate more detail;
and whether `EXTRA_REPORT_TOKEN` (`spec/state-reporting.md` § 6) not being spent anywhere in this
codebase is deferred scope or a real gap in the install-report flow.

## Decisions

- **AzNavRail drives the whole UI, not a custom Compose layout.** The rail/host/sub-item DSL
  (`azRailItem`, `azRailRelocItem`, `azNestedRail`, `azUnattachedHostItem`, hidden menus) is
  the one and only UI framework for chrome; floating windows (`FloatingWindow`-based dialogs)
  are the escape hatch for anything that doesn't fit a rail item. Bypassing the DSL to work
  around a library limitation (invariant 7/8 above) is treated as a bigger change than the
  limitation warrants — fixes go upstream instead.
- **Curves, per-channel LUT extensions, and the ColorMatrix adjustments are three separate
  pixel-transform paths on purpose.** `ColorMatrixUtils.createColorMatrix` (opacity/
  brightness/contrast/balance) is a 4×5 affine transform applied live via a `ColorFilter` —
  cheap, reversible, non-destructive until export. `CurvesUtil.calculateAdjustmentCurve` (a
  monotone cubic spline LUT, identical across R/G/B) and `CubeLut` (a full 3D `.cube` grade,
  trilinearly sampled) are both destructive bitmap bakes pushed through `pushHistory()` —
  neither can be expressed as a `ColorMatrix`, which is why they exist as their own code
  paths rather than extra knobs on the existing one.
- **Extension acquisition is delegated, not built in.** Graffux is a host, not a marketplace:
  browsing/searching/purchasing an azphalt extension happens in a separate store app, reached
  via an intent (`spec/store-app.md` § Discovery) or an `azphalt://` deep link. The deep-link
  install path requires an explicit user confirmation dialog before `installExtensionFromUrl`
  runs — added after an earlier audit found it firing on nothing but tapping the link.
- **The sandbox bounds memory and execution time, not just capabilities.** `MAX_GUEST_MEMORY_PAGES`
  (256 MiB) caps a `JsSandbox`/`WasmSandbox` instance regardless of what the guest module
  itself declares as its maximum; `runSandboxBounded` (`SandboxExecution.kt`) runs guest code
  on a daemon thread with a 15s timeout and interrupts it on expiry, verified against
  Chicory's own interpreter honoring `Thread.isInterrupted()` mid-execution. Both exist
  because a capability grant (e.g. `canvas`) says nothing about how much memory or CPU time a
  misbehaving or malicious extension can consume once it's running.
- **`GraffitiProject`/layer persistence favors "never silently drop the user's concurrent
  edit" over simplicity.** The project-load bitmap-decode path merges decoded bitmaps into
  whatever the *live* layer list is when decoding finishes, rather than replacing the whole
  list with the stale pre-decode snapshot — decoding a full-screen bitmap can take long enough
  for the user to have added, removed, or edited a layer in the meantime.
- **wgpu is the long-term single brush engine, for Android and desktop.** The GPU stamp engine
  exists three times today: `VulkanStampEngine` and `GlesStampEngine` (C++, Android only) and the
  wgpu engine (`core/wgpu-engine`, Rust + WGSL). New brush work targets wgpu. Vulkan and GLES stay
  selectable in Settings → GPU engine for now, for comparison and as fallbacks, and Vulkan is
  still the default. Direct display (`LiveStrokeOverlay`) stays raw Vulkan through
  AHardwareBuffer interop and is ineligible while wgpu is selected. wgpu was chosen over
  consolidating on Vulkan because of the desktop app (one engine on Vulkan, DX12, Metal or GL,
  where the NDK engines cannot run at all), its automatic synchronization (no hand-written
  barriers between the ordered smudge phases), one shader language (WGSL, compiled by naga,
  instead of two GLSL dialects and a port script), and a path to WebGPU in the browser. Parity is
  checked by `tools/stamp-engine-diff`, which runs every scenario through all engines on Mesa. See
  `docs/Native Rendering Engine Design.md` §2b.
- **wgpu keeps layers resident on the GPU across strokes** (§2b of the design doc). The engine
  holds each recently painted layer keyed by layer and content generation, under an LRU memory
  budget. A stroke that starts on an unchanged layer binds it with no upload. The commit then
  refreshes only what changed. `GpuLayerResidency` (feature/editor) owns the generations: any
  swapped layer bitmap is a new generation, and every non-stroke mutation path invalidates
  explicitly. When unsure, invalidate: that costs one upload, never wrong pixels. Readback copies
  only the dirty rectangle. Every wgpu native call runs on `GpuRenderThread`, one FIFO thread,
  so batches, commit refreshes and invalidations stay in order and teardown never blocks the main
  thread. Vulkan and GLES do none of this. `StampEngine.h`'s new methods default to the old
  behaviour for them.
- **Graffux's hardware floor is Android 10 + Vulkan 1.1, and the brush engine tunes itself within
  it.** `:app` minSdk went from 26 to 29, and the manifest requires `android.hardware.vulkan.version`
  0x401000, so Play offers the app only to devices whose GPU runs the stamp engines. The shared
  library modules stay at 26 for GraffitiXR. Inside the floor, a short calibration picks a tier from
  one table (`GpuTierTable`). It runs behind the mandatory project dialog (`ProjectGateDialog`: no
  project, nothing to do, so the dialog has no cancel path), capped at 2 s beyond Save/Load. Its
  result is stored per GPU+driver+app version. Thermal state scales the tier through
  `GpuBudgetProvider`, the hook the multipass scheduler consumes. Per-family and per-driver knobs
  change only where documented (Mali's 8x8 workgroup, from Arm's guide) or evidenced (the driver
  workaround table starts empty). GraffitiXR keeps the old silent "Untitled" bootstrap:
  `EditorViewModel.projectGateEnabled` is set only by `GraffuxApplication`. See §2b of the design doc.
- **Multipass drying is an experiment in the wgpu engine, off by default** (design doc §2b,
  "Multipass drying"). Each stamp call renders a low-resolution draft of the same stamp at once and
  its full-quality dab later, in whatever time is left of each frame; the display eases between
  them per tile. The draft is a hard guarantee, never behind refinement. The committed layer is the
  final queue run strictly in submission order, split only in exact ways, so it is byte-identical
  to multipass off: never let a reordering or an approximation reach the layer. The scheduler lives
  in the Rust engine (`scheduler.rs`, pure and unit-tested) because batching and exact chunking need
  the engine's pipelines, and one copy serves Android and desktop. It follows the device's
  `GpuBudget` (collected, since the tier can change mid-session). On Android nothing waits for
  refinement (the CPU commit is the committed layer; the stroke-end refresh drops queued work); on
  desktop stroke end lands it before committing the GPU frame.
