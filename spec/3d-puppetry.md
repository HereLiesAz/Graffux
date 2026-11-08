# 3D puppetry: rigid parts, joints, rigidity, and (later) soft bodies and keyframes

Status: design only. Nothing here is implemented. **Revision 2** makes standard formats (glTF 2.0 skins,
nodes and animations) the backbone; sections 0A to 0C are new and win over any earlier text they contradict. Written against `origin/main` at 1.74.1.1 (project
format 13), the open draft PR #753 (rotation triangle gizmo) and the in-progress mesh work on
`claude/mesh-all-views` (no commits beyond main when this was written; every statement about it
below is an assumption to re-check, flagged "MESH:").

Owner brief: let the user define rigidity and connection points so 3D surface groups can be posed like
puppets. Owner decisions: build both models, staged. Stage 1 = rigid groups plus joints. Stage 2 = one
bendable surface (soft body). Stage 3 = keyframes, playback, export.

This document sits beside `spec/3d-space.md`, whose vocabulary (group, surface profile, hull, body
frame, orbit pivot, depth pass) it uses without redefining. Read "Group surfaces", "The orbit point"
and "Body-fixed axes" there first.

## 0. Decisions needed from the owner

Collected here so they are not buried. Each has a recommendation; the spec is written as if the
recommendation is accepted. Section numbers point to the reasoning.

| # | Question | Recommendation | Where |
|---|---|---|---|
| Q1 | Is a part a *group with a surface*, or may a flat 2D group / a single layer be a part too? | Any group (`GROUP`/`FRAME`), profiled or not. A flat group is a rigid plane; costs nothing extra. | 2.1 |
| Q2 | What does "rigidity" mean on a joint? | A 0..1 *stiffness* that scales both resistance to being driven and how much of a drag the child inherits (defined numerically in 3.4). Not a physical torque. | 3.4 |
| Q3 | Do joints constrain translation (a dislocated part pulls away)? | No. Joints are always coincident: the child's anchor is welded to the parent's anchor. No stretch in Stage 1. | 2.3 |
| Q4 | Pose vs rest pose: does the existing layer transform *become* the rest pose, and is the pose a separate overlay? | Yes. Rest = today's `offset/scale/rotation*/positionZ`; pose = new per-joint angles on top. Nothing existing is reinterpreted. | 2.4 |
| Q5 | Drag-follow strategy: forward-only (swing the children) or IK (drag the end effector, bend the chain)? | Stage 1 ships forward drag-follow plus an optional one-chain CCD "pull" handle. Full IK is out of scope. | 3.6 |
| Q6 | May a part have several children and be the child of one joint only (a tree), with closed loops forbidden? | Yes: a forest of trees. One parent joint per part. No loops, ever. | 2.2 |
| Q7 | Does posing write into the layer's stored transform (destructive) or live beside it? | Beside it. Posing never edits a layer's stored transform; "Bake pose into rest" is an explicit action. | 2.4 |
| Q8 | Per-view paint on a part: it rides the part rigidly (it is in the part's body frame), correct? | Yes. All paint of a part, every view image, moves with the part. Stage 2 is where paint stops being rigid. | 5.1 |
| Q9 | Stage 2 model: which soft-body approach? | Control-point skinning (blend weights from a rigidity field) solved on the CPU as a small constraint relaxation, skinned in the vertex shader. Not FEM, not a GPU PBD. | 8 |
| Q10 | Does Stage 3 animate only joint angles (and Stage 2 control points), or also camera, opacity, anything? | Joint angles, control-point offsets, and part root pose in v1 of the timeline. Camera and opacity later. | 9 |
| Q11 | Export in Stage 3: video/GIF (rendered frames) vs animated glTF (skins and animations)? | Both, in that order: GIF/MP4 through the existing export path first, glTF animation second. | 9.4 |
| Q12 | Is puppetry available on desktop? | No. Desktop does not use `:core:common` (3d-space.md header). Same exclusion as all 3D. | 5.7 |
| Q13 | Do old readers have to open a project that has joints? | They open it read-only (existing `isNewerFormat` behaviour) and render the rest pose. Acceptable; no attempt to make them edit it. | 4.3 |
| Q14 | Default mobile budget numbers (section 10) acceptable as hard limits (e.g. 32 joints per project, 4k vertices per soft body)? | Yes, enforced with a visible message, not silently. | 10 |
| Q15 | Is glTF (nodes/skins/animations) the interchange and in-memory shape for rigs and clips, with our data only in `extras.GRAFFUX_joint`? | Yes (0B). | 0B |
| Q16 | Support importing rigged glTF/GLB models as poseable model layers before authored joints? | Yes: PR 1.2 (import and pose) ships before PR 1.5 (authoring). | 12 |
| Q17 | Extend the model layer to hold one `.glb` original plus a skeleton, and generalise `objFile` to `modelFile` (format 14)? | Yes, old name still read. | 0C |
| Q18 | Which on-device formats: glTF/GLB, OBJ(+MTL), STL now; PLY later; FBX/USD/others by external conversion? | As in the 0C table. | 0C |
| Q19 | Parser library: jgltf (JVM) vs cgltf (native) vs hand-rolled? | jgltf, decided by the PR 1.0 spike (APK delta, 20 MB GLB parse time, licence check). | 0C |
| Q20 | Export `.glb` in addition to `.gltf`, and validate our output with Khronos glTF-Validator in CI? | Yes to both. | 0C |
| Q21 | Cloud conversion service for FBX/USD? | No; document external converters. | 0C |
| Q22 | glTF with external files: refuse until a folder/zip import exists, or add a zip import now? | Refuse with "save as .glb" now. | 0C |
| Q23 | Compressed glTF (meshopt, Draco) and KTX2 textures: refuse in v1, meshopt first later? | Yes. | 0C |
| Q24 | Is `extras.GRAFFUX_joint` (unregistered, ignorable) acceptable, with `KHR_physics_rigid_bodies` joint limits as the future mapping once ratified? | Yes. | 0B |
| Q25 | Skinned models: GPU skinning in the depth-pass vertex shader with a CPU-skinned copy for picking and painting; cap 64 joints (uniform array) and 100k vertices on import? | Yes. | 3.8, 10 |

## 0A. What import and export exist today (audit, 2026-11)

Read from `origin/main` source, not from docs. Anything not listed here does not exist.

**Import (one format).**

- 3D models: **Wavefront OBJ only**, as text. Entry point `EditorViewModel.importModelLayer`
  (`:feature:editor`), reached by the 3D toolbox's "Import 3D model", whose picker is
  `OpenDocument` with `*/*` (any file is offered; a non-OBJ fails in the parser with "Couldn't read
  that model"). `ObjParser.parse(String)` (`:core:common`, `common/mesh`) reads `v`, `vt`, `vn`, `f`
  (all four corner spellings, negative indices, n-gons fan-triangulated) and **skips everything else**:
  `mtllib`/`usemtl` (so no materials, no MTL colours or textures), `o`/`g` (no groups or object
  names), `s`. Missing normals are generated; missing UVs become 0.
- The result is `Mesh` (`Mesh.kt`): an interleaved `[px,py,pz, nx,ny,nz, u,v]` float array (8 floats a
  vertex) plus an `IntArray` of triangle indices. **There is no place in `Mesh` for joints, weights,
  node hierarchy, skins, animations, morph targets, vertex colours, a second UV set, or materials.**
  `ModelMesh` wraps it with a bounding centre and radius. The OBJ bytes are copied unchanged into the
  project folder as `model_<layerId>.obj` and re-parsed on load (`ModelLayerContent.objFile`);
  an imported model has one mesh, one paint texture (`ModelTexture`, 1024 square by default, painted
  over a flat base colour), and no imported texture.
- Co-op carries the OBJ text deflated (`ModelCoopCodec`, 64 MB inflated cap, 4096-side texture cap).
- Other 3D-ish inputs, unrelated to models: images (including to build a relief surface from a photo
  via the depth model), vector shapes, and `.azp` extensions. No STL, PLY, glTF, GLB, FBX, USD, Collada.
- No third-party mesh/glTF/model library is in `gradle/libs.versions.toml`, the Cargo manifest of
  `core/wgpu-engine`, or the CMake of `core/nativebridge`. The only JSON stacks present are
  `kotlinx-serialization-json` (used for projects) and `gson`.

**Export (glTF 2.0 JSON only, write-only).**

- `SceneGltfExporter` (`common/scene`): "Export 3D scene (glTF)", `CreateDocument("model/gltf+json")`.
  One self-contained **`.gltf` (JSON with a base64 buffer), not `.glb`**. A **flat list of root nodes,
  one per exported thing: no parent/child hierarchy, no `skins`, no `animations`, no morph targets,
  no `extras`**. Contents: each paint layer a textured quad (PNG) or, on a group surface, its
  strips/hull patches with UVs; each vector layer rasterised to a quad; each 3D strokes layer a tube
  mesh (`SpatialGltfExporter.buildMesh`); each model layer its mesh (positions, normals, UVs, indices)
  with the painted texture as base colour; optionally the camera. Materials use
  `KHR_materials_unlit` for the paint quads. Axes y-up, metres, one metre = one spatial unit
  (`F/3` scene px).
- `SpatialGltfExporter` writes the same format for the strokes layer alone.
- PNG / PDF / GIF / share go through `ExportManager` and `SceneExport` (rendered images). `AnimatedGifEncoder`
  exists for animation frames.
- Export is verified by JVM tests that decode the JSON and accessors (`ModelLayerTest`: "glTF export
  carries the model's mesh and painted texture"); not opened in any external validator or viewer by CI.

**Rendering of models.** The GLES 3 `DepthCompositor` (the depth pass) draws a model's mesh with a
headlight shader, the paint texture over a base colour; the mesh is uploaded once per mesh, the
texture once per revision. There is **no vertex skinning**, no per-vertex bone data, no joint
uniform array. Picking and painting use CPU raycasts on the unskinned mesh (`ModelSpace.ray`,
`MeshPicker.raycast`) and UVs.

**Tests that cover this today.** `ModelLayerTest` (import placement, Move tool undo, UV painting,
save/load of the texture, old floating-window migration, flatten, tap-select, glTF export, co-op
model data), `ObjParser`/`Mesh` unit tests, glTF exporter tests. None involves a skin, a joint or an
animation, because none exists.

**Conclusion.** Today's "model" is *static geometry from OBJ*, and its export is a *flat scene dump*.
Rigged models cannot be imported at all, and nothing we author can leave the app as a skeleton.
That is exactly the gap puppetry should fill with the existing standard rather than a bespoke format.

## 0B. Standards first: glTF is the interchange for rigs, poses and clips

Principle: **where glTF 2.0 can express it, glTF is the format and the in-memory model mirrors
glTF's own structures.** Our own data is a thin layer only for what glTF cannot say. Concretely:

| Concern | Standard carrier | Our additions (only where glTF is silent) |
|---|---|---|
| Part hierarchy (parent/child, rest transform) | glTF **nodes** (`children`, local `translation`/`rotation`/`scale`) | none |
| A skinned mesh and its deformation | glTF **skin** (`joints[]`, `inverseBindMatrices`, `skeleton`), primitive attributes **`JOINTS_0`/`WEIGHTS_0`** (`JOINTS_1`/`WEIGHTS_1` for 8 influences), linear blend skinning per the glTF spec | none |
| A pose | Node local transforms (TRS), or `weights` for morph targets | pose persisted in our project (below) |
| A clip | glTF **animation**: `channels[]` (target node, path `translation`/`rotation`/`scale`/`weights`) and `samplers[]` (`input` times, `output` values, `LINEAR`/`STEP`/`CUBICSPLINE`) | `animation.extras` for frame rate and our easing |
| Rigid part moving with a joint | A mesh on a node. No skin is needed: glTF nodes are rigid-transformed | none |
| Hinge / ball / fixed, axis, limits | **Nothing in core glTF.** `KHR_physics_rigid_bodies` (a Khronos extension in development at time of writing; check its status before relying on it) defines joints with per-axis limits and is the future mapping | `node.extras.GRAFFUX_joint` (below) |
| Rigidity (stiffness) | Nothing (the nearest are physics/spring extensions, not an equivalent of our gain) | `node.extras.GRAFFUX_joint.rigidity` |
| Soft-body control points and zones (Stage 2) | A **skin whose joints are the control point nodes**, with weights baked from our kernel (section 8) | `extras` with the kernel/zone parameters to re-edit |
| Rigged humanoid semantics | VRM / `VRMC_vrm` (glTF-based) names humanoid bones | not in v1; imported as plain nodes |

`extras` is the glTF-sanctioned place for application data (the spec requires other tools to ignore
it and, ideally, preserve it). The JSON we put there is one namespaced object per node:

~~~
"extras": { "GRAFFUX_joint": {
    "schema": 1, "kind": "HINGE" | "BALL" | "FIXED",
    "axis": [x,y,z],                       // in the node's local frame
    "limits": { "min": deg, "max": deg } | { "swing": deg, "twistMin": deg, "twistMax": deg },
    "rigidity": 0..1 } }
~~~

We do not register an extension for this in v1 (a registered extension needs a Khronos process and
buys nothing for a private semantic); `extensionsUsed` stays unchanged and every viewer ignores it.

**Consequences for the design below (these supersede earlier text where they conflict):**

1. **The in-memory rig is a glTF-shaped node forest**, not a bespoke joint list. A *rig* is a set of
   **skeleton nodes** (name, parent, rest TRS, optional `GRAFFUX_joint` data) plus **skins**
   (joint list + inverse bind matrices) plus **bindings** from meshes (or surface groups) to skins or
   to single nodes. Section 2's `Joint` is then a *view* over a node with `GRAFFUX_joint` data: the
   parent/child relation is the node's parent; `parentAnchor`/`childAnchor` are the node's translation
   in the parent's frame and the child part's origin offset (a glTF node's origin *is* the joint
   position, which removes the two-anchor redundancy and the "out of rest" case when the rig comes
   from glTF; section 2.5 still applies to authored rigs on surface groups, whose anchors are expressed
   by creating a node at the joint with both parts as its mesh-bearing descendants).
2. **Imported rigged models are poseable with their own skeleton**, with no authoring. A glTF/GLB
   with skins imports as a model layer carrying the skeleton; every skeleton node is a poseable joint
   (default kind BALL, rigidity 0, unlimited, unless it carries `GRAFFUX_joint`, in which case the
   authored kind, limits and rigidity are honoured, which makes our own exports round-trip).
3. **Our authored joints export as a glTF skeleton and animation** through the existing
   `SceneGltfExporter` (extended, not replaced): parts become nodes with `children`, each part's
   surface mesh hangs on its node, joints carry `GRAFFUX_joint`, the current pose is the node local
   rotation, clips become `animations`. Another tool (Blender, a game engine, `model-viewer`)
   opens the result and poses/animates it. Rigid parts need no `skin`.
4. **One skinning runtime serves three things**: imported skinned models, Stage 2 soft bodies
   (authored weights over control-point nodes) and rigid parts (weight 1 to one node, if we ever
   skin them instead of using `partModel`). The glTF skinning formula is the only deformation maths
   added (section 3.8). Stage 2's "recommended" kernel becomes a *weight generator* feeding that
   same runtime, not a separate solver.
5. **Pose persistence** stays in our project (`GraffitiProject.rig`, format 14), because a glTF file
   embedded as a model is not edited in place: the stored `.glb` is the unmodified original (so
   re-export of the source is lossless and storage is the user's file), and the project holds only
   the *delta*: pose per node (by node index, with node name as a fallback key), kind/limits/rigidity
   overrides, and the animation clips we author. Section 4 is revised accordingly (section 4.4).

## 0C. Libraries and formats: what does the work

Constraints that decide: untrusted input from the file picker (memory safety matters); mobile size
budget; the project already has a JVM (Kotlin) editor core, a CMake native module (OpenCV/engine
glue, arm64-v8a and armeabi-v7a only) and a Rust wgpu crate that does not composite the 3D scene;
no existing mesh library. Licence and size figures below are from the libraries' public
documentation as I know them; **verify the current version, licence text, and size in a spike before
adopting** (the spike is PR 1.0).

### Import parsers evaluated

| Library | What | Licence | Size / build cost | Native vs JVM | Skins/anims | Verdict |
|---|---|---|---|---|---|---|
| **jgltf** (`de.javagl:jgltf-model`) | glTF 2.0 reader/writer, GLB, typed model, accessor reading, validation helpers | MIT | small (a few hundred KB) plus Jackson (Apache-2.0) if not already present | **JVM**, no NDK | skins, animations, morph targets, extensions as data | **Recommended for import** (and for validating our own export in tests). Check Jackson size/R8 rules and maintenance status |
| **cgltf** | single-header C glTF/GLB parser, no image decoding | MIT | ~7k lines C, tiny | native (JNI via existing CMake) | skins, animations, extensions | Good fallback if jgltf is rejected; native parse of untrusted files raises the memory-safety bar (fuzz it) |
| **tinygltf** | C++ header-only reader and writer | MIT | needs nlohmann json + stb_image | native | yes | heavier than cgltf, no advantage here |
| **Filament `gltfio`** | loader + animator + renderer integration | Apache-2.0 | several MB AARs; brings a second renderer | native + JVM | full | **Rejected**: it would own rendering, duplicating the depth pass and its occlusion/paint pipeline. Its skinning maths is not reusable without Filament |
| **Assimp** | 40+ formats incl. FBX, OBJ, STL, PLY, Collada, 3DS, glTF | BSD-3-Clause | large (many MB per ABI), long NDK build | native | skeletons, animations (FBX lossy) | **Rejected for v1**: size and attack surface (a long CVE history in importers) for formats better converted ahead of time; keep as a later option for FBX if a converter service is declined |
| **Hand-rolled glTF JSON reader on `kotlinx-serialization-json`** | the JSON is simple; accessors/bufferViews are ~300 lines | n/a | zero new dependency | JVM | whatever we write | Not preferred (the owner asked not to reinvent); the fallback if adding Jackson is unacceptable. Skinning maths is trivial either way |
| **Khronos glTF-Validator** | schema/semantic validator | Apache-2.0 | dev tool (Dart/JS/CLI) | CI only | n/a | **Use in CI/tests** to validate every glTF our exporter writes; it exists precisely so we do not trust our own JSON |

**Recommendation:** adopt **jgltf-model** (JVM) for import and for round-trip tests; keep
`ObjParser` (OBJ is a useful, working path and its absence of a dependency is the original reason it
exists); keep **hand-written JSON in `SceneGltfExporter`** and extend it with `children`, `skins`,
`animations`, `extras` (the exporter already owns the scene-to-glTF mapping and unlit materials;
jgltf's writer would not know our layers) and **run Khronos glTF-Validator over its output in a test or
CI step**. Reading with a library and writing with our existing exporter keeps the diff small.
Native parsers are held back until a measured JVM performance problem (a large GLB parse on a mid-range
phone) exists. Decide jgltf vs cgltf in the spike with: APK delta, parse time for a 20 MB GLB, R8
behaviour, licence file check (Open question Q19).

### Skinning maths: nothing to invent

The glTF 2.0 specification defines it and it is a few lines: for vertex `v` with joints
`j0..j3` and weights `w0..w3`, `skinMatrix = sum_i w_i * jointMatrix[j_i]`, where `jointMatrix[j] =
inverse(globalTransformOfNodeThatSkinIsOn) * globalJointTransform[j] * inverseBindMatrix[j]`;
`position' = skinMatrix * position`, normals by the inverse-transpose (or the rotation part when
uniform scale). Weights must sum to 1 within tolerance (normalise on load; the spec requires it).
Max influences 4 per attribute set; we import `*_0` and `*_1` (8) and re-normalise to the 4
largest. We implement exactly that, in one pure Kotlin function (`Skinning.kt`, `:core:common`) and
the same formula in the GLES 3 vertex shader. The reference for tests is the spec's own sample
models (Khronos `glTF-Sample-Assets`: `SimpleSkin`, `RiggedSimple`, `RiggedFigure`, `CesiumMan`, `Fox`, `BrainStem`;
CC0/CC-BY/permissive per asset, check each licence before vendoring any into `src/test/resources`).
Use tiny ones only (`SimpleSkin` is a few hundred bytes).

### Where each stage gets its work from a standard

- **Stage 1a (import and pose a rigged glTF):** glTF/GLB parsing (library), node hierarchy and TRS
  (glTF), skinning (glTF spec formula), pose = node local rotations (glTF), quaternion maths (our
  `Affine3` plus a small quaternion type). New: UI for posing, persistence of the pose delta.
- **Stage 1b (authored joints on surface groups):** FK is the node-hierarchy product (glTF), export is
  nodes + `extras` (glTF), limits and rigidity are our semantics (3.2, 3.4).
- **Stage 2 (soft bodies):** deformation is **linear blend skinning with authored weights** (glTF);
  control points are joint nodes; weights come from our kernel/zones (our authoring, section 8);
  export is a standard skin. Morph targets (glTF) are the option for baked per-frame shape.
- **Stage 3 (clips):** the clip **is** a glTF animation (samplers/channels); we add only the editing UI
  and the extras for fps/easing; playback samples the same data an exported file contains; export to
  glTF animation is direct, GIF/MP4 are renders of it. Interpolation `LINEAR`/`STEP`/`CUBICSPLINE` are
  glTF's; our smoothstep ease bakes to `CUBICSPLINE` (with tangents) or extra `LINEAR` samples.

### Other formats worth supporting, and via what

| Format | Support | How |
|---|---|---|
| glTF 2.0 `.gltf` (embedded data URIs) and `.glb` | **First-class import and export** | jgltf; exporter (also add `.glb` output: one file, binary chunk, no base64 inflation) |
| glTF with external `.bin`/image files | Import only via `.glb` or an embedded `.gltf`; a `.gltf` with external URIs is refused with "Save as .glb" until a folder/zip import exists | SAF returns one file URI; resolving siblings needs a tree grant (Open question Q22) |
| OBJ (+MTL) | Keep; **add MTL** diffuse colour/texture so imported OBJs are not flat grey (small, not in glTF's way) | `ObjParser` extension |
| STL (binary and ASCII) | Add; trivial, common from CAD/3D printing | ~100-line parser, or the library if it reads it; no UVs (paint disabled, auto-UV is a separate feature) |
| PLY (scans) | Add, low priority | small parser; vertex colours become a base-colour texture bake |
| FBX | **Convert**, do not parse | `FBX2glTF` (BSD-3, Meta; links the proprietary FBX SDK, so it is a build/CLI tool, never shipped in the app) or Blender CLI on the user's computer; the docs tell users to export glTF. Mixamo (FBX) is the main reason users ask |
| USD / USDZ | **Convert** | Blender, Apple Reality Converter, or the `usd` toolchain; no on-device parser |
| Collada, 3DS, Blend, etc. | **Convert** | Blender; Assimp only if a later decision accepts it |
| VRM 1.0 | Import as glTF; ignore `VRMC_*` in v1, read humanoid bone names later | it is a glTF file |
| Compressed glTF (`KHR_draco_mesh_compression`, `EXT_meshopt_compression`) | Detect and refuse with a message in v1; meshopt decoder (MIT, small) is the first to add because it is common in downloaded assets; Draco is large and last | library or vendored decoder |
| `KHR_texture_basisu` (KTX2 textures) | Refuse or ignore the texture (use base colour), message | later |
| `KHR_materials_*` | Import base colour factor/texture only; ignore the rest | |
| `KHR_mesh_quantization`, `KHR_texture_transform` | Quantised attributes: decode (accessor normalisation is already in the loader); texture transform: apply to UVs | simple |

A hosted converter (a Cloudflare worker exists in `workers/`) cannot run Blender or the FBX SDK;
"convert in the cloud" is therefore out of scope and listed as an open question (Q21) only so the
owner can decide not to.

### Mesh model changes this implies (still no code)

`Mesh` stays as is so every existing consumer (renderer, picker, painting, co-op, exporter) is
untouched. Skin data travels beside it: `SkinnedMesh(mesh: Mesh, joints: ShortArray /*4 per vertex*/, weights: FloatArray
/*4 per vertex*/, skin: Skin)`, where `Skin(jointNodes: IntArray, inverseBind: FloatArray /*16 per joint*/)`,
and the node forest in `Skeleton(nodes: List<SkelNode>)` with `SkelNode(name, parent, restT, restR, restS,
extras)`. A model layer holds `mesh` plus an optional `skeleton`/`skinned`. A static OBJ has neither
and takes today's code path byte for byte. Imported base-colour textures and multiple primitives
become several `ModelMesh` parts of one model layer (also a prerequisite for glTF with more than one
material), which is a change to `ModelLayerContent` (format 14: `modelFile` generalises `objFile`,
the old name read as before).

## 1. Concepts and vocabulary

Use these words in code, UI strings and docs. Do not introduce synonyms.

- **Part.** A group (`LayerType.GROUP` or `FRAME`) taking part in a rig. Usually a profiled group (it
  has a `SurfaceProfile`), but a flat group qualifies (Q1). A part is rigid in Stage 1: its shape and
  its paint never change under a pose. A paint layer inside a part is not itself a part; it rides its
  group, exactly as it rides the group's transform today (`transformTargetId`).
- **Rig.** The set of joints in a project and the forest they form over the parts.
- **Joint.** A named connection between exactly two parts: a **parent** and a **child**. It owns two
  **anchors** (one point in each part's body frame), a **kind** (hinge, ball, fixed), an axis (hinge),
  **limits**, a **rigidity**, and a **pose value** (the current bend).
- **Anchor.** A point fixed in a part's body frame (the frame that `SceneMath.model(pose, ...)` maps to
  the scene, `Rz*Ry*Rx` about the box centre). The parent anchor and the child anchor are coincident
  in the scene at all poses (Q3); that coincidence is the joint.
- **Rest pose.** The part transforms the document already stores: layer `offset`, `scale`,
  `rotationX/Y/Z`, `positionZ`. The rest pose is where the joints were placed and where every pose
  value is 0.
- **Pose.** The per-joint bend values layered on the rest pose. Pose is the only new mutable state
  (plus, in Stage 2, control-point offsets).
- **Pose mode / Edit mode.** Edit mode is today's editor (paint, transform, draw surface) plus joint
  placement and editing. Pose mode is a modal state where a one-finger drag on a part swings it about
  its joints and the paint tools are suspended. Section 7.
- **Rigidity.** A joint's stiffness in 0..1 (Q2). 0 = free (follows a drag with no resistance, passes
  nothing to its children beyond kinematics), 1 = locked (acts as a fixed joint for dragging
  purposes; angle still settable numerically). Precisely defined in 3.4.
- **Limit.** A closed angular interval a joint's pose value is clamped into.
- **Drag-follow.** Dragging a part rotates it about its parent joint, and its descendants follow
  because they are rigidly attached (forward kinematics), optionally with partial propagation to the
  parent side when the drag is applied to an interior part (3.5).
- **Soft body (Stage 2).** A single surface whose vertices move under control points, with rigidity
  zones deciding how much each region follows. Distinct from the rigid-parts model; both can coexist
  (a rigid arm part with a soft hand part).
- **Control point (Stage 2).** A user-placed handle on a surface (in the surface's own `(u, t)`
  coordinates) carrying a 3D offset. **Rigidity zone**: a painted/ellipse region with a rigidity value
  that resists deformation.
- **Keyframe / clip / track (Stage 3).** A keyframe is a pose snapshot at a time; a track is the
  per-joint (or per-control-point) curve; a clip is the set of tracks plus a duration.

Terms deliberately not used: "bone" (no skeleton skinning in Stage 1: parts are rigid and not
skinned), "constraint" for a joint (reserved for the solver), "pin" (the orbit-pivot relocation
already uses "rotation point").

## 2. Data model

Rev 2 note: read this section through 0B. For rigs that come from glTF, the node forest of 0B is the
model and `Joint` is a view over a node with `GRAFFUX_joint` data. For authored rigs on surface
groups the `Joint` below is kept as the editing model and is exported as nodes (0B.3).

All in `:core:common`, package `com.hereliesaz.graffitixr.common.scene` (new file `Rig.kt`, plus
`RigMath.kt` for kinematics). Pure Kotlin, no Android, like the rest of `scene/`.

### 2.1 What is a part

A part is identified by a layer id of a group. The rig does not add a field to `Layer`; it refers to
layers by id from `GraffitiProject.rig`. Rationale: layers are copied, duplicated, wrapped and
co-op-synced in many places that would each need to learn a new field, and deleting a layer already
has a central cleanup path. A rig that points at ids is cleaned in one place (section 2.5).

A part can be nested in a non-part group. The kinematic parent/child relation is the **rig graph**,
independent of the **layer tree**. The scene transform of a posed part is
`chainModel(layerChain) * poseDelta` (3.1): the layer-tree chain gives the rest placement as today,
the rig adds only the delta. Consequence: moving a group in the layers rail still works as before; it
moves the part's rest pose, and its rig descendants follow only if they are layer-tree descendants
too. The no-ancestry rule in 2.2 keeps rig edges and layer nesting from overlapping, so this cannot
double-move a part.

### 2.2 Graph rules

- A rig is a **forest**: every joint has one parent part and one child part; a part is the child of at
  most one joint (Q6) and the parent of any number.
- **Cycles are forbidden**, including through layer-tree ancestry. Creating a joint is refused (and
  the UI says why) if the child is already an ancestor of the parent in the rig, or if the child is
  the parent, or if either part is a layer-tree ancestor/descendant of the other (a part cannot be
  rigged to its own container or contents: both would then move twice for one drag). Check is
  `Rig.wouldCycle(parent, child)`; run on create, on load (a hand-edited or corrupt file) and in the
  property tests. On load a cycle-forming joint is dropped with a logged warning, rest pose stays.
- The **root** of each tree is a part with no parent joint. Dragging a root part in pose mode moves it
  as a whole (translation and rotation about the orbit pivot, i.e. today's behaviour). Joints below
  it swing.
- Layer-tree relation: for any rig edge the two parts must be in the same layer-tree parent or both
  top-level? Not required. The only layer-tree rule is the no-ancestry rule above. Each part's model
  is its own `chainModel` of layer ancestors; the rig delta is applied in the scene (3.1). This keeps
  the existing renderer, hit-testing and export untouched: they already take a world model per node.

### 2.3 Joint

~~~
data class Joint(
    val id: String,                  // stable, uuid
    val name: String = "",           // optional, for TalkBack and the list
    val parent: String,              // layer id of parent part
    val child: String,               // layer id of child part
    val kind: JointKind,             // HINGE, BALL, FIXED
    val parentAnchor: Vec3,          // parent BODY frame, in the part's box-local coordinates
    val childAnchor: Vec3,           // child BODY frame, same convention
    val axis: Vec3 = Vec3.Z,         // HINGE: unit axis in the CHILD's body frame at rest
    val limitMinDeg: Float = -180f,  // HINGE: bend range about axis. BALL: see swingMax/twist
    val limitMaxDeg: Float = 180f,
    val swingMaxDeg: Float = 180f,   // BALL: cone half-angle from rest
    val twistMinDeg: Float = -180f,  // BALL: twist about the child's rest-Z (the limb axis)
    val twistMaxDeg: Float = 180f,
    val rigidity: Float = 0f,        // 0..1, see 3.4
    val rest: JointValue = JointValue.Zero,   // the joint value at rest = 0 by definition; reserved
)

sealed interface JointValue {
    data object Zero : JointValue
    data class Angle(val deg: Float) : JointValue          // HINGE
    data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) : JointValue  // BALL
}
~~~

Notes that fix ambiguity:

- **Anchors are in each part's body frame**, i.e. box-local layer coordinates `L` (the same space as
  `SceneMath.model` input, whose origin convention is the full-canvas box with centre `C`). The
  anchor is stored as a 3-vector in that space so it survives any later change of the part's rest
  transform: moving a part in edit mode moves its anchors with it. For a profiled group the anchor is
  *on the surface* when placed by tap (2.6) and carries the hit's unrolled coordinates only as a
  convenience for the UI marker; the stored value is the 3D box-local point. The surface may later be
  redrawn; the anchor stays at its 3D point (it may then float off the surface; allowed, shown
  dimmed).
- **Hinge** has one rotational degree of freedom about `axis`; **ball** three (swing + twist); **fixed**
  zero (the child is welded; pose value is always identity). A fixed joint is still useful: it makes
  two groups move as one for drag purposes without nesting them in the layer tree, and it is the
  stiffness-1 limit.
- **Rest relation.** At rest (pose 0) `rest_parent_model * parentAnchor == rest_child_model *
  childAnchor` (coincident in the scene). Placement enforces this by *defining* it: the tool places
  one scene point and derives both anchors from it. If a part's rest transform is later edited in
  edit mode so that the anchors no longer coincide, the joint is **out of rest** (see 2.5).
- **Why one `rigidity` and not separate stiffness/damping/propagation.** Fewer controls; the single
  number must still drive three behaviours (resistance, propagation, snap-back) so that the three
  cannot disagree. 3.4 gives the formulas.

### 2.4 Pose vs rest pose (Q4, Q7)

The stored layer transform is the rest pose. The pose is separate state:

~~~
data class Rig(
    val joints: List<Joint> = emptyList(),
    val pose: Map<String, JointValue> = emptyMap(),   // joint id -> current value; missing = rest (0)
    val schema: Int = 1,
)
~~~

- `GraffitiProject.rig: Rig? = null` (new, format 14). Null and "no joints" are equivalent and the
  writer emits null for an empty rig, so a project that never used puppetry is byte-identical to today
  other than `formatVersion` (and see 4.1 for keeping even that unchanged until a rig exists).
- Pose is **persisted**: the owner wants the pose saved in the project. It is document state, not
  view state (unlike the camera), so it goes through history (section 6).
- Rendering reads `effectiveModel(part) = chainModel(layer chain) composed with rigDelta(part)`.
  Hit-testing, selection, painting-by-footprint, export and the depth pass all take their per-part
  world model from one function, `RigMath.partModel(...)`, so there is no second code path to keep in
  sync. With no rig it returns exactly `chainModel(...)`.
- **Bake pose into rest** (explicit menu action, one undo step) writes the posed transforms into the
  layers' stored `offset/rotation/positionZ`, zeroes the pose and recomputes anchors, so the pose
  becomes the new rest. Needed because Euler-angle storage cannot represent a ball joint's offset in
  the generic layer fields without the baked decomposition (`SceneMath.eulerOf`, whose gimbal fold
  at Y = +-90 degrees is accepted there; see 3.7). It also gives the user an escape hatch from a pose
  they like.
- **Reset pose** sets pose to empty (one undo step).

### 2.5 Integrity and cleanup

One function, `Rig.sanitize(layers)`, runs on load and after any layer-list edit that can delete or
re-parent layers (the reducer's layer ops, `LayerListOps`):

- joint whose parent or child layer no longer exists: removed, with its pose entry;
- joint that would now form a cycle or whose parts are layer-tree-related: removed;
- a part that stops being a group (cannot happen today; guard anyway): joint removed;
- duplicate of a part (duplicate layer): the copy is **not** rigged (copy has no joints). A
  "Duplicate with rig" action is out of scope.
- **Out of rest.** If `|rest_parent_model * parentAnchor - rest_child_model * childAnchor| > eps`
  (eps = 0.5 scene px) the joint is flagged `outOfRest` (computed, not stored). It still works in
  pose mode (the child is welded to the parent's anchor at pose 0 *by moving the child*, 3.1, so a
  visible jump appears on entering pose mode). Edit mode shows a warning marker on the joint;
  the joint editor offers "Snap child to joint" (moves the child's rest transform, one undo step) and
  "Move joint to child" (re-derives the child anchor).

### 2.6 Creating a joint (data side; UI in section 7)

`Rig.addJoint(parentId, childId, scenePoint, kind)`:

1. refuse on cycle/ancestry (2.2) or if the child already has a parent joint (UI offers "Replace");
2. `parentAnchor = inverse(partRestModel(parent)) * scenePoint`, `childAnchor = inverse(partRestModel(child)) * scenePoint`;
3. hinge axis defaults to the **camera's view direction at placement**, expressed in the child's body frame, so a hinge placed while looking at the artwork bends like a 2D
   pivot (an arm swinging in the picture plane), which is the 90% case; the user can change the axis
   (7.4);
4. default limits: hinge +-180, ball cone 180, rigidity 0.

## 3. Kinematics

### 3.1 Forward kinematics through the hierarchy

Work in the **scene frame**. For each part `p` let `M0(p) = chainModel(layer chain of p)` (rest, today's
value, an `Affine3`) and let `P(p)` be its posed model. Define recursively over the rig forest, roots
first:

- root part: `P(root) = M0(root)`.
- child `c` of parent `p` via joint `j`: see the definition below.

With all matrices acting on scene points:

~~~
Qp   = P(p) * M0(p)^-1                      // parent's rigid motion from rest (a rotation+translation)
Jr   = Qp * (M0(p) * parentAnchor)          // joint, posed
Bj   = rotation by pose[j] about the joint, in the scene: Bj = T(Jr) * Rot(Qp.R * axis_s, pose[j]) * T(-Jr)
P(c) = Bj * Qp * M0(c)                      // child rides the parent, then bends about the joint
~~~

where `axis_s = M0(c).R * j.axis / scale` is the hinge axis in the scene at rest (the axis is stored in
the child body frame, so editing the child's rest rotation turns the axis with it). Properties,
each a unit test:

1. pose 0 everywhere gives `P(c) == M0(c)` for every part (identity pose is exact, not approximate).
2. the child's anchor is coincident with the parent's anchor at every pose:
   `P(c) * childAnchor == P(p) * parentAnchor`, within float eps, *provided the joint was in rest*. If
   the joint is out of rest the identity is replaced by "coincident modulo the constant rest gap",
   and the weld in 3.1 is enforced by *adding* the gap correction `g = P(p)*parentAnchor - P(c)*childAnchor` to P(c)
   as a pure translation. (That is what causes the visible jump noted in 2.5.)
3. composition is associative over a chain: `P(grandchild)` computed recursively equals the matrix
   product along the path; no per-level re-orthonormalisation drifts the result (see 3.7).
4. a child of a hinge cannot move except by `pose[j]` and by whatever moves its ancestors: sampling
   random poses, `P(c)` differs from `P(p)`-rigid motion by a pure rotation about the joint axis
   through the joint.

Scale: `M0` may include uniform scale (a layer's `scale`). `Qp` is the *rigid part* of the parent's
motion; its scale is `P(p)`'s scale / `M0(p)`'s scale, which for pure pose changes is 1. If the user
scales a parent in pose mode (not offered), we use `Qp`'s uniform scale `s` about the joint too; the
formula holds with `Qp` an affine similarity. Non-uniform scale is not representable by `Affine3`
chains today and is not introduced.

The relation to the existing code: `SceneMath.model` builds `M0` per node and composes a chain with
`chainModel`; `SceneMath.rotation` builds `Rz*Ry*Rx`; `rotatePoseAbout` is the **single-part** version
of this (it turns one part's pose about a pivot and writes the result back into the Euler fields). The
rig generalises it: pose mode computes `P(c)` by the formula above *without* writing Euler angles
back, which is the key reason pose lives beside the rest pose (Q7) and does not go through
`eulerOf` (whose gimbal fold would corrupt a stored pose).

### 3.2 Hinge, ball, fixed

- **Hinge**: scalar `theta` in `[limitMin, limitMax]`. Rotation by `theta` about `axis_s` through the
  joint. Stored as a float. Sign: positive is counter-clockwise looking down the axis toward the
  origin (right-handed); the UI shows the sign with an arrow in the edit mode marker. Same sense as
  the rotation gizmo's positive (PR #753: "about the object's own body axis", right/up positive).
- **Ball**: orientation relative to rest as a unit quaternion `q` (not Euler: no gimbal, composes
  stably). Limits: swing-twist decomposition about the child's rest limb axis `u` (default body Z,
  settable): `q = q_swing * q_twist`; clamp swing angle to `swingMaxDeg` (a cone), twist to
  `[twistMin, twistMax]`. Clamp by projecting back to the nearest allowed orientation (shortest
  rotation), never by clamping quaternion components.
- **Fixed**: no value; `P(c) = Qp * M0(c)`.

Limits apply to the **pose value after solving** (3.5), then FK is recomputed from the clamped
values, so a displayed pose is always legal. A numerically loaded pose that violates limits (file
edited, limits shrunk later) is clamped on load and the clamp is reported (a snackbar "2 joints were
outside their limits and were moved to the nearest allowed pose"), one undo step is not created for
load-time clamps.

### 3.3 Why quaternions inside, Euler outside

Inside the rig: poses are scalars (hinge) or unit quaternions (ball) and `P` is a product of
rotation-plus-translation matrices (`Affine3`). Euler `rotationX/Y/Z` appears only when baking a pose
into rest (2.4) and when handing a part's final model to the existing renderers: the existing
renderers take `Affine3` models for most paths, but a few still take `PlanePose` fields (e.g.
`PlanePose` is what `viewPlanePose`, `CanvasHitTest` and the 2D Compose fallback read). Plan: add
`RigMath.partModel` returning an `Affine3`, and make those consumers take the `Affine3` instead of
rebuilding it from a `PlanePose` where they can; where they cannot (the 2D fallback needs a
`graphicsLayer` with `rotationX/Y/Z` fields), the `Affine3` is decomposed by the existing
`SceneMath.poseOf`, accepting its gimbal fold, because the fallback only exists for flat-ish layers
and a posed part with a large Y turn is on the scene path anyway. To be confirmed in the PR that
touches the renderer (risk R3).

### 3.4 Rigidity: numeric semantics (Q2)

Let `r` = joint rigidity in `[0,1]`. Three effects, one parameter, none of them physical simulation
(nothing integrates over time in Stage 1):

1. **Drag resistance (gain).** When a drag asks for a change of joint value `d` (degrees, hinge; or
   a rotation, ball), the joint takes `d_applied = (1 - r)^2 * d`. So `r = 0` follows the finger 1:1,
   `r = 0.5` follows at 25%, `r = 0.8` at 4%, `r = 1` not at all. The square gives a perceptible
   mid-range (a linear map feels dead above ~0.5). The remainder is *not lost*: it is passed to
   propagation, below.
2. **Propagation to the parent side.** The portion `d_rest = d - d_applied` is what the joint refused.
   For a drag started on a non-root part, that refused rotation is carried up to the parent
   joint as additional requested motion (a stiff elbow makes the shoulder turn instead), scaled by
   `1 - r_parent` at each level up, and stopped at the root, where the root takes a rigid rotation
   about the joint with the *root's* own `(1-r)^2` gain taken as 1 (the root has no joint; it moves
   freely only if the user has enabled "move root with drag", default off, in which case the
   leftover is dropped and the part stops at the limit, section 3.6 failure mode F2). This is the only
   place a drag affects an ancestor, and it is what makes rigidity visible without a physics engine:
   a limp joint bends; a stiff one transfers the pull.
3. **Nothing else.** Stage 1 poses do not relax or spring back, so rigidity has no "hold" meaning.
   In Stage 2 rigidity gains the soft meaning (zone resistance, section 8).

So everything is a deterministic function of (current pose, drag target, joint data): no velocity, no
time step, so there is no instability and a given drag produces the same pose each time
(testable as a property, section 11). Rigidity 0..1 is clamped at the API; a value outside it in a
file is clamped on load.

The UI shows rigidity as a slider labelled "Stiffness" with a 0 to 100% readout (the percentage is
`r * 100`); not "rigidity" in user copy, because "rigid" reads as binary. The model and spec say
rigidity.

### 3.5 Drag-follow algorithm

Input in pose mode: a one-finger drag that starts on part `d` at scene hit point `h0` (from
`CanvasHitTest`), current finger ray `ray_t`. Output: new pose values.

1. Resolve `d`'s parent joint `j` (if none: root drag, 3.6 F2).
2. Compute the **target direction**: the current finger point is intersected with the plane through
   the joint `Jr` whose normal is the hinge axis (hinge) or the camera view direction (ball): `h_t`.
   The desired rotation is the one that carries `h0 - Jr` to `h_t - Jr` about the axis (hinge: signed
   angle `theta_t = atan2(...)` projected on the plane; ball: shortest-arc quaternion between the two
   direction vectors, expressed in the joint's frame).
3. Express as a **delta from the pose at drag start** (not from the previous frame), so the result is
   path-independent and releasing at the start point restores the start pose exactly (property P4).
4. Apply gain `(1 - r)^2` (3.4.1), then clamp to limits (3.2). Whatever the clamp removes is *also*
   refused (it is not propagated; a limit is a wall, not a spring).
5. Propagate the rigidity-refused remainder to the parent joint (3.4.2), repeating steps 4-5 up the
   chain, to the root, at most `MAX_CHAIN = 16` levels.
6. Recompute FK for the subtree rooted at the highest changed ancestor. Descendants are not
   re-solved: they ride rigidly (that is the "swing its children").

Cost: O(chain depth + subtree size) matrices per frame, no allocation in the loop (3.7).

### 3.6 IK or drag-follow, and failure modes (Q5)

**Decision:** Stage 1 is forward drag-follow, as above. It cannot place a distant end effector at an
arbitrary target (a hand at a point), which is IK's job. Add an opt-in **Pull handle** at Stage 1.5
(PR 1.6): grab a part's pull point and drag; solve with **CCD** (cyclic coordinate descent) over the
chain from that part up to the root-or-limit (max 8 joints), 10 iterations, honouring limits and the
rigidity gain at each joint. CCD is chosen over FABRIK (does not handle ball limits and hinge axes
cleanly) and Jacobian methods (matrix inversion, more code, singularities to guard). Full IK
with multiple end effectors, pole targets and soft constraints is out of scope for every stage.

Failure modes, and what the code does:

- **F1: target out of reach.** The finger is further from the root joint than the chain's total
  length. CCD converges to the chain fully extended toward the target. We show the reach limit as the
  pose where it ends; no error. The part simply stops following. (Reachability test is O(1): compare
  distance to the sum of segment lengths; skip iterations if unreachable and extend straight.)
- **F2: root drag.** A root part has no joint. Dragging it in pose mode does the existing
  whole-body Move behaviour (translate/rotate about the orbit pivot) *including its subtree*, because
  the subtree is rigid relative to it. That is not IK; it is the existing transform edit, written to
  the rest pose, through the existing transform-gesture history entry. Chosen deliberately so that
  "move the whole puppet" works and is one familiar gesture. (Alternative rejected: pose mode never
  moves a root. Rejected because users will try, and a dead drag with no explanation fails
  discoverability.)
- **F3: singularity.** A hinge chain fully extended (all axes collinear) makes CCD's step rotation
  ill-defined (target direction parallel to the limb). Detect `|cross| < 1e-4 * |v|`, skip that
  joint's step this iteration (no division by a tiny norm), continue with the next joint up. Result: a
  slight kink may remain at the limit; accepted.
- **F4: limit deadlock / oscillation.** CCD can ping-pong between two clamped joints. Iteration cap
  (10) bounds time; keep the best (lowest distance) iterate, not the last.
- **F5: flip.** Passing a ball joint through 180 degrees swing flips the rotation direction between
  frames. Hold the solution **continuous**: solve against the previous frame's value as the starting
  point and take the shorter quaternion hemisphere (`dot(q, q_prev) < 0` negate). Always from the
  start-of-drag baseline (3.5.3), not accumulate.
- **F6: multiple parents.** Forbidden by the graph (2.2); there is no loop closure.
- **F7: the user moves the finger off the part and over another part.** The drag is locked to its
  start part (as the gizmo's drag "locks to its start edge", PR #753); hit-testing is only for
  picking the part at drag start.

### 3.8 Skinning (imported rigs and Stage 2)

Linear blend skinning exactly as the glTF 2.0 specification defines it (0C): per vertex up to 4
influences, `skinMatrix = sum w_i * jointMatrix[j_i]`. Runtime split:

- **GPU**: the depth pass vertex shader skins positions and normals; joint matrices upload as a
  uniform array of 4x3 matrices (GLES 3.0 guarantees at least 224 vec4 vertex uniform vectors, so 64
  joints at 3 vec4 each is safe; beyond that use a float texture fetch, which GLES 3 supports) once per
  pose change, not per frame. Cap 64 joints per skin in v1; a skin with more is refused with a message
  (or its 64 most-weighted joints kept: Open question Q25).
- **CPU copy**: a CPU-skinned copy of the positions is computed on pose change only, for
  picking, painting and the orbit pivot's bounds. Painting is unaffected by skinning in UV terms: the
  texture coordinate is per vertex and does not move, so `MeshPicker.raycast` on the *skinned* positions
  returns the correct UV and the brush paints where the user sees. This resolves the Stage 2 painting
  risk (R8) for any skinned mesh, imported or soft-body, on single-surface meshes.
- Vertex cap on import: 100,000 vertices per skinned model (CPU copy 1.2 MB of positions, skin in under
  8 ms on a mid device is the budget; verify in the spike).
- Pure Kotlin reference `Skinning.kt` (`:core:common`) used by tests and the CPU copy; the shader must
  match it (a test compares both on `SimpleSkin`-style fixtures; the GLSL itself is verified on device only).

### 3.7 Numerical stability

- Poses are stored as scalars/quaternions; matrices are rebuilt from them each frame; products are
  never accumulated frame-to-frame (no integration), so error does not grow in time. Only the
  length of the chain amplifies float error, and `MAX_CHAIN = 16` at float32 gives worst-case drift of
  about 16 * 1e-7 relative: invisible; a test asserts it.
- Renormalise: each `P` rotation block is re-orthonormalised (Gram-Schmidt on the first two columns,
  third by cross) when the cross-term deviates by more than 1e-4, which a freshly built product of
  16 unit rotations will not reach; the check is a guard against a bad file.
- Quaternions normalised after every composition; zero-norm quaternion (corrupt file) is replaced by
  identity.
- Non-finite values (NaN/Inf) in any joint field: the joint is dropped on load (2.5); at runtime a
  solve that produces non-finite output keeps the previous pose and reports.
- Euler is avoided inside the rig. Where it is needed (bake, fallback) the existing `eulerOf` / `poseOf`
  behaviour is accepted: Y within -90..90, gimbal fold. Bake detects `|Y| > 89.9` and warns that the
  part will be stored with its Z folded into X (visual result identical, slider readings differ), the
  same warning class the Transform panel has.
- Float32 everywhere to match `Affine3`; the solver does its few-joint math in Double and rounds once on
  output if profiling shows the float path drifts in CCD (open question R6, not expected).

## 4. Project format 14

### 4.1 Fields and migration

- `GraffitiProject.rig: Rig? = null` (JSON key `rig`). Nothing else in the project file changes for
  Stage 1. Stage 2 adds `OverlayLayer.softBody` (section 8.5) in a later format bump (15) or into
  this one if it ships first; keep each stage's persistence in the stage that needs it.
- `CURRENT_FORMAT_VERSION` becomes 14 when the first PR that **writes** a rig lands, not before. Until
  then a project that has no rig keeps format 13. Even afterwards, **write the lowest format that can
  hold the document**: a project with no rig stays at 13 (the writer decides from content). Rationale:
  every format bump makes the file read-only for older builds (`isNewerFormat`), so bumping for
  documents that do not need it needlessly strands users on mixed-version devices and co-op peers.
  (The existing code bumps globally; this is a deliberate narrowing. If the project has no precedent
  for content-dependent versions, fall back to the global bump and accept the cost: open question
  R5.)
- `ProjectManager.migrateFormat` 13 -> 14 only records the version (no data to transform), exactly like
  10, 11, 12 and 13 before it. A format 13 file has no `rig`: automatic, = no rig.
- Backward compatible reads in this build: all of a 13 file reads as before; `rig` absent = null.
- `Json { ignoreUnknownKeys = true }` (ProjectManager) already tolerates an unknown `rig` key in builds
  that predate it, but those builds also see `formatVersion = 14`.

### 4.2 Forward-compat hygiene inside `rig`

`Rig.schema` is an integer for the rig's own sub-format so that Stage 2/3 additions (new joint kinds,
new fields) do not need a project-format bump for the rig alone: unknown `JointKind` values decode to
`JointKind.UNKNOWN` (custom serializer with a default), and the joint is treated as `FIXED` for
kinematics and preserved on save. Unknown fields are dropped (limitation of `ignoreUnknownKeys`; the
read-only open of a newer file protects data in the common case).

### 4.3 What an old reader does (Q13)

- A build that predates 14: `isNewerFormat(14)` is true, so the project **opens read-only** (existing
  behaviour for any newer format, see `ProjectManager` writable verdicts and `EditorViewModel` line
  2471): it renders the **rest pose** (it ignores `rig` and reads the layers' stored transforms), cannot
  save, and therefore cannot destroy the rig. This is the correct behaviour and needs no code in old
  builds. The user-visible copy (existing banner) is unchanged.
- Co-op: a peer without puppetry sees the rest pose unless the transform messages carry the posed
  model. Stage 1 co-op decision: **pose is not synced** (a peer sees the rest pose of parts) and the
  pose editing UI is disabled while a co-op session is active, with a message. Syncing the rig
  (`Op.RigSet`) is a follow-up (open question R7); doing it wrong makes peers diverge on cycles. The
  transform codec keeps its 16 floats (spec/3d-space.md "Co-op").
- A `.fux` bundle: the rig lives in `project.json`, so it travels with the file.

### 4.4 Rev 2: what the project stores for imported models

- The original model file is stored unmodified in the project folder (today `model_<layerId>.obj`;
  format 14 generalises `ModelLayerContent.objFile` to `modelFile`, the old name still read, so a
  format 13 file loads as before). A `.glb` is one file, so it fits the existing `saveArtifact` path.
- `GraffitiProject.rig` stores only **deltas against the source**: per layer, the pose as node index
  -> local rotation/translation (name fallback when indices disagree after a re-import), `GRAFFUX_joint`
  overrides, and authored clips. Opening the project never rewrites the user's source model.
- Old readers (format < 14): read-only open, rest pose of the source (an old build cannot import a
  `.glb` at all, so such a layer loads as an empty model layer, the same degrade as for any layer
  type it does not know; the read-only open prevents it from being saved over).
- Co-op: `ModelCoopCodec` carries OBJ text; a `.glb` needs its own op (bytes, same 64 MB cap and
  validation). Out of scope until co-op has a transport; recorded in R7.

## 5. Interactions with the existing 3D system

### 5.1 Per-view paint (Q8)

Per-view paint images (format 11: hidden raster layers with `surfaceViewOf` / `surfaceView`) live in
the **same group** as the paint layer and use the view's own flat space; they are "not moved by the
paint layer's own transform". They are rendered through the group's surface mesh, whose world model
is the group's chain model. Since a rig part is a group and `partModel` replaces the group's chain
model, every view image of every layer of a part moves rigidly with the part in Stage 1: no change to
paint storage, `SurfaceViewImages`, `ViewStrokeFeed`, crease-crossing, caps or fill. Painting in
pose mode is disabled (7.2); painting in a posed part in edit mode is allowed and maps through the
**posed** model (`worldToViewFlat` takes the posed model), which is the right answer: the user paints
where they see.

Consequence to test: a stroke made while a part is posed must land on the same texels as one made at
rest at the corresponding point (property P7: `worldToViewFlat(P(c) * x)` under pose equals
`worldToViewFlat(M0(c) * x)` under rest).

### 5.2 Orbit pivot

`OrbitPivot.ofSurfaces` computes the centre of the box that holds every surface's world-space
bounds. With a pose, "world-space bounds" must use the posed model. Decision: **the orbit pivot
tracks the posed bounds**, recomputed at every use as it already is ("a pure function of the layers,
recomputed at every use"). Consequence: during a pose drag the pivot would move under the user, and
camera orbit pivots about a moving point. Mitigation, consistent with the existing "held for the
length of a gesture" rule: the pivot is **frozen for the length of a pose gesture** and recomputed
between gestures. Pose mode also offers "Rotate view about this part" is not needed; the existing
manual pivot ("Relocate rotation point") still overrides.

For a drag that turns a **root** part (F2), `rotatePoseAbout` is used as today, about the pivot.

`rotatePoseAbout` itself is unchanged and remains the edit-mode primitive for turning a part.

### 5.3 Wire mesh (surface grid)

`SurfaceMath.gridLines` and the `gridGroupId` rule draw the active group's grid through the scene
camera. It takes the group's model; it must take `partModel`. Also draw **joint markers** in the same
overlay layer (never part of an export, like the grid): a dot at each anchor (parent anchor filled,
child anchor ringed, coincident at rest), an arc for the hinge axis/range, a cone for a ball. MESH:
the in-progress `claude/mesh-all-views` work (wire mesh on all views) changes which grid lines are
drawn for a hull; the joint overlay must draw in the same coordinate pass and must not assume the
mesh's current front-view-only extents. The rig reads only the grid's *model matrix*, not its line set,
so the two do not conflict in code, only in the draw order (joints above mesh). Re-check when that
branch lands.

### 5.4 Hit-testing

`CanvasHitTest.topHit/allHits` casts a tap onto every scene-path layer's plane and orders hits by
camera depth. It needs the part's **posed** model for the plane/mesh of any layer in a posed part.
Mechanism: the hit tester receives a `modelOf(layerId)` function (it already derives per-layer
models); pass one that routes through `RigMath.partModel`. Pose-mode picking returns the **part**
(nearest rig group of the hit layer, via the layer tree), not the layer. Joint-marker picking
(edit mode) is screen-space: project anchors, pick within 24dp (the same band as the gizmo's 48dp
touch band, half-width), nearest wins; markers take priority over layers under them because a joint
marker that cannot be tapped is not editable.

Ambiguity: two parts' hits at nearly the same depth (the two sides of a hinge): the nearer wins as for
layers; the "pick from the layers rail" fallback exists for buried parts (a joint list in the edit
sheet is the equivalent for joints, 7.5).

### 5.5 glTF export

Rev 2: superseded by 0B/0C. The hierarchy, `extras`, skins and animations are written by extending
`SceneGltfExporter` (it writes a flat node list today), `.glb` output is added, and the result is checked
by Khronos glTF-Validator. The text below is the original, narrower plan.

`SceneGltfExporter` writes each layer quad/strip at its scene placement. Stage 1: export the
**currently displayed pose** by feeding it posed models (same `modelOf` seam). No skins, no animation
nodes, no joints in the glTF. A rigid part is exactly a mesh at a transform, which glTF expresses
natively. Optional: export a node hierarchy (parent node per part, child nodes with local transforms
= `P(parent)^-1 * P(child)`) so a downstream tool can re-pose; costs one more pass and proves the
tree is right, listed in the PR plan as a nice-to-have (PR 1.7). Stage 2 export needs skinned meshes
(JOINTS_0/WEIGHTS_0 and a skin of control-point nodes) or baked vertices per frame (morph targets);
Stage 3 uses glTF animation channels (9.4).

### 5.6 The Rust wgpu renderer and the JNI boundary

The wgpu engine **does not composite the stack**: it paints strokes into layers and presents the live
stroke (3d-space.md, "Why HWUI, not a wgpu pass"). 3D composition is HWUI homographies plus the GLES 3
`DepthCompositor`. Therefore Stage 1 puppetry crosses **no JNI boundary**: poses are applied as model
matrices to the existing Kotlin/GLES scene path. This is a deliberate non-decision to protect the
hot path and the Rust crate from churn. `WgpuNative` is not touched.

What would cross JNI, and when:

- Stage 2, if (and only if) soft-body skinning moves to the wgpu engine rather than the GLES vertex
  shader. Recommendation: **don't**; skin in the GLES vertex shader of the existing surface pass (8.4),
  so again nothing crosses. If the engine is later asked to paint in a posed/deformed space (stroke
  brushes on a deformed surface), the crossing would be a per-frame `float[]` of control-point
  offsets (at most 64 * 3 floats = 768 B) plus an index buffer rebuild signal: tiny, but it must go
  through `JniGuard`/`records`-style validation (`jni_api.rs`) like every other upload, never a
  raw pointer.
- Stroke painting on a posed part uses the existing path: touch -> ray -> surface (`worldToViewFlat`
  with the posed model) -> flat point -> engine. The engine sees flat points exactly as today. No new
  JNI call.

### 5.7 Desktop and other builds

Desktop does not link `:core:common` (3d-space.md header), so it has no 3D and no puppetry. The
`GraffitiProject` model is shared by file format only; a desktop open of a format 14 project follows
the desktop's own newer-format behaviour (unchanged by this work).

### 5.8 Co-op, history, flatten, composites

- **Flatten / composite / PNG / PDF**: these render what the canvas shows through the same
  `modelOf` seam, so they show the pose. "Flatten All" with a rig: the flattened layer replaces the
  parts; the rig's joints are removed by `sanitize` (their parts no longer exist). The command warns
  once: "Flatten removes the rig."
- **Inactive-layer cache**: `DepthSplit` classifies members by the active plane/ depth range; a
  posed part's plane/range comes from its posed model. Add the pose to the cache key
  (`rig.pose` identity) so a pose change invalidates (like transform changes already do).
- **Isolated groups**: unaffected (they are per group, using the group's model).

## 6. Undo, redo and the action path

History today: `EditHistory` (internal, `feature/editor`) holds `EditCommand` entries; property
snapshots (`pushProperty` of layer lists without bitmaps), `Draw`, `PlaneSwap`, and `chainedToPrevious`
for multi-part strokes. The reducer is intent-based (`EditorViewModel`).

Plan:

- New `EditCommand.RigChange(before: Rig?, after: Rig?)`. The rig is small (<= 32 joints, section 10)
  so a full before/after snapshot is cheaper and simpler than a patch, and avoids the redo-stale class
  of bug `pushProperty`'s comment describes. Deduplicate like `pushProperty` does (identical
  snapshots ignored, redo stack still cleared).
- **One pose drag = one undo step** (the same rule as the rotation gizmo's "one drag = one undo
  step" and the slider's). Gesture start captures `before`; gesture end pushes `RigChange(before,
  after)`; a cancelled gesture pushes nothing and restores `before`.
- Joint add/delete/edit = one step each. Slider drags in the joint editor coalesce per drag.
- A rig change that also edits layers (Snap child to joint, Bake pose into rest, delete a part with
  joints) is **chained** with the layer-list entry using `chainedToPrevious`, so one undo takes both.
- Deleting a layer that is a part: the layer-list entry and the sanitised rig share one step
  (the reducer applies `Rig.sanitize` as part of the same state transition).
- Undo of a pose gesture does not move the camera (view state, not history).
- Memory: `Rig` snapshots are immutable data classes sharing unchanged joint objects; a 32-joint rig
  is a few kB; the existing stack cap (`maxStackSize = 20`) holds.

## 7. UI flows

Judged on usability, consistency with the existing editor (rails, sheets, chips, MenuPanel),
and accessibility. The rule from the existing design: **no hidden mode that can strand the user**
(spec/3d-space.md: "no tool can strand the user"). Pose mode is a visible, dismissible mode with a
Back-key exit.

### 7.1 Entry points and discoverability

- Where: the `3D` button's menu on a surface group (PR #753 turns it into a "Surface drawings" menu:
  Draw surface, Draw/Redraw side, top). Add **Rig** entries below a divider: "Add joint", "Pose
  parts". On a flat 2D group the existing 3D button arms "Draw surface"; puppetry is not offered there
  until a second part exists (Q1: a flat group qualifies, but the entry only appears when the project
  has at least two groups, to avoid a dead control).
- The first time a project has two or more profiled groups, a one-time chip (dismissible, remembered in
  `localStorage`-equivalent preferences, not project data): "Connect parts with joints to pose them.
  Open the 3D menu > Add joint." Not a modal, not repeated.
- Empty state in the pose-mode tray when no joints exist: text + the "Add joint" button; never a blank
  panel.
- MESH: if the mesh work changes the `#` wire mesh menu, joint display toggle ("Show joints") belongs
  in that same long-press menu with the mesh toggle, not on a new rail button.

### 7.2 Edit mode vs pose mode

- **Edit mode** (default, today's editor): paint, transform, draw surfaces; joint markers visible when
  the active layer is a part or a joint is selected (and "Show joints" is on). One-finger drag on a
  joint marker moves its anchor (a conscious edit).
- **Pose mode**: entered from the 3D menu ("Pose parts") or the joint tray. A chip at the top reads
  "Pose mode: drag a part to swing it. Back to exit." with Exit and Reset pose. One-finger drag on a
  part swings it; the paint tools are suspended and the canvas drawing surface does not take strokes,
  so a drag can never paint by accident (the existing mechanism for the capture surfaces: "the
  capture surface lies over every tool and paints nothing"). Two-finger view navigation is unchanged
  (pan/zoom/orbit work in pose mode; a pose gesture is one finger, the existing table in 3d-space.md
  separates one-finger tool from two-finger view).
- The rotation gizmo (PR #753) stays available in pose mode for the active part as the **precise**
  alternative (below).

### 7.3 Placing a joint by tapping two surfaces

Flow ("Add joint"), a guided two-tap sequence with a chip at each step:

1. Chip: "Tap the first part." The user taps a part (a hit on a surface, via `CanvasHitTest`); the part
   is outlined cyan. The tap is a ray hit; its 3D point is remembered as a *candidate* point.
2. Chip: "Tap the part it connects to." The user taps the second part. If the two taps are on
   different parts, the **joint position** is chosen as: the midpoint of the two surface hit points if
   they are within 0.15 * the smaller part's bounding radius of each other (the user tapped near the
   seam), else the *second* tap's point projected onto the first part's surface along the view ray
   (documented rule, so it is predictable); the marker previews live under the finger between taps.
3. Direction: which part is the parent? Default: the part nearer the **rig root** (if one is already
   in a tree), else the first tapped. The sheet that follows shows "Parent: [name] / Child: [name]
   (Swap)". Swap is one tap.
4. The Joint editor sheet opens (7.4) on the new joint with kind = Hinge selected; Cancel at any step
   (Back key or chip button) leaves nothing changed. The whole thing is one undo step.

Same-part second tap: chip "Choose a different part." Tap on empty canvas: no-op with the same chip.
A tap that would form a cycle: refused with the reason ("This would connect the arm to itself
through the torso") and stays in step 2.

Alternative considered and rejected: tap one surface, then pick the second part from a list. A list
is the **accessibility alternative** (7.7) but a worse default for sighted touch users, who see the
parts.

### 7.4 Joint editing

A bottom sheet (consistent with the carousel sheet and the Transform panel), content:

- Name (optional text), Kind (Hinge / Ball / Fixed segmented control).
- Parent and Child (names, Swap).
- Axis (hinge): a small gizmo choice "X / Y / Z of the child" and "Match view" (set the axis to the
  camera direction now); the axis shows on canvas live.
- Limits: for hinge a two-handle range slider with degree readouts (-180..180) and a Reset; for ball a
  swing cone slider and twist range. Handles show detent haptics at 0 / +-45 / +-90 / +-180 like the
  rotation slider.
- **Stiffness** slider 0..100%, with helper text "How hard this joint resists being moved. At 100%
  it will not bend when dragged."
- Current angle (read-out + numeric field) so the pose is settable without dragging (7.7).
- Move joint (arms a tap to reposition the anchor), Snap child to joint (if out of rest), Delete joint.

Every change is live on the canvas; each slider drag is one undo step.

### 7.5 Joint list

Edit sheet has a "Joints" list (name, parent -> child, kind icon) because a buried or tiny joint cannot
be tapped; selecting a row selects the joint and highlights its marker. This is the equivalent of the
layers rail for hard-to-hit parts and the primary TalkBack route.

### 7.6 Gizmo reuse (PR #753)

The rotation triangle gizmo rotates the active surface group about X/Y/Z of its own body, with
+-15 degree TalkBack actions and one undo step per drag. Reuse, do not reinvent:

- In **pose mode**, with a part that has a parent joint active, the same triangle controls the
  **joint**: for a hinge only the edge matching the hinge axis is live (others dimmed, not removed,
  so the shape stays recognisable); for a ball all three edges map to swing X, swing Y, twist about
  the joint's frame; for a fixed joint the triangle is hidden and the sheet says why. The angle read-out
  ("X 37") becomes "Elbow 37".
- The triangle's "full edge = 180" scaling is kept; the joint's limits clamp at the end, with a brief
  limit indicator (edge turns solid and a light haptic) to give feedback that a limit stopped the
  motion.
- The **PoseGizmo** is the same composable `RotationGizmo` with a `target` parameter (a part's
  transform vs a joint), so the touch bands, a11y nodes and pass-through logic are shared. PR #753
  is a draft: this spec assumes its API is stable enough to parameterise; if it changes before
  merge, PR 1.5 absorbs the change. Do not start 1.5 before #753 merges.

### 7.7 Accessibility (TalkBack and motor)

Every pose operation has a non-drag alternative:

- **Pose**: each joint in the list is an a11y node "Elbow, hinge, 37 degrees, range -10 to 150",
  with custom actions "Bend +15", "Bend -15" (the gizmo's +-15 actions), a "Set angle" action opening
  the numeric field, and "Reset to rest". Limits announce when hit ("Elbow at limit").
- **Placing a joint**: "Add joint" opens a two-step dialog with two part pickers (lists of
  groups by name), a kind selector, and "Place at: centre of overlap / chosen point" (default the
  closest points of the two surfaces' bounding boxes); no canvas tap needed. A tap is the shortcut,
  not the only way.
- **Markers**: not the only indication of a joint: the list is.
- Touch targets: joint markers have a 48dp target regardless of drawn size; sheet controls follow the
  existing sheet standards.
- Colour is not the only encoding: parent anchor filled vs child ringed; hinge arc vs ball cone have
  different shapes; the limit indicator uses a solid edge, not just a colour change.
- Reduced motion: no animated swing-back on Reset pose; the change is immediate.
- TalkBack announcements for modal state changes ("Pose mode on / off") through the existing chip
  live region.

### 7.8 Consistency checklist

Chips for prompts, MenuPanel for menus (as `#` and 3D), bottom sheet for editing, Back key exits any
armed state, one drag = one undo step, view gestures never history, axis colours (X cyan, Y pink,
Z green) kept everywhere an axis appears.

## 8. Stage 2: a bendable single surface (soft body)

Rev 2 note: the deformation runtime is the glTF skinning of 3.8, not a separate solver. The kernel and
rigidity zones below are the *weight generator*: they produce `JOINTS_0`/`WEIGHTS_0` over control-point
nodes. Export is then a standard skinned mesh, and an imported skinned mesh is deformed by the same code.

### 8.1 Goal and scope

One surface (a profiled group's sheet or hull, or a relief) deforms under user-placed **control
points** with **rigidity zones**: a region with high rigidity resists bending and moves like a rigid
patch; a low rigidity region follows controls smoothly. Paint deforms with the surface (the paint is
in the surface's unrolled `(u, t)` / view-image space, which is parameterised on the surface; we
deform the surface, not the texture).

### 8.2 Options considered

| Option | What | Cost (mobile) | Verdict |
|---|---|---|---|
| A. **Control-point skinning / RBF warp** | Each control point has an offset; vertex displacement is a weighted blend (inverse-distance or radial basis, weights scaled by rigidity); fixed-size solve per frame. | O(V * C), C <= 32: 4k vertices * 32 = 128k mul-adds, trivial | **Recommended** |
| B. Position-based dynamics (PBD) / mass-spring | Distance + bending constraints iterated; interactive, springy. | 4k verts * ~6 constraints * 8 iters = 200k constraint projections / frame on CPU: ok; on GPU needs compute shaders (GLES 3.1+; the pass is GLES 3.0): not guaranteed | Possible Stage 2.5 for secondary motion |
| C. As-rigid-as-possible (ARAP) deformation | Local rotations + global sparse linear solve; best-looking handle dragging. | Factorising a sparse system per topology change, solve per drag (Cholesky): needs a sparse solver; ~4k verts feasible but needs a vetted library or new code | Best quality, highest risk; revisit if A looks poor |
| D. FEM (finite element) | Physical elasticity. | Heavy, instability handling, tet meshing of a surface | Rejected |
| E. Mesh-free "jelly" shader / GPU PBD | Compute-shader. | Needs GLES 3.1 / wgpu compute; JNI + shader complexity | Rejected for Stage 2 |

### 8.3 Recommendation: A (skinned control points) with a rigidity field

- **Control point** `c_i`: a position `(u, t)` on the surface (unrolled coordinates), a rest position
  `x_i` (3D), an offset `o_i` (3D, the pose), and a falloff radius `R_i`.
- **Rigidity field** `rho(x) in [0,1]` over the surface: a sum-of-ellipses painted by the user as
  zones (Stage 2 UI) and rasterised to a coarse per-vertex scalar at mesh build time. `rho = 1`: this
  vertex does not accept blending from far-away controls (it follows only its nearest rigid
  anchor); `rho = 0`: fully soft.
- **Vertex displacement**: `d(v) = sum_i w_i(v) * o_i`, `w_i(v) = normalise( phi(|v - x_i| / (R_i * (1 - rho(v)) + eps)) )` with `phi` a compact
  smooth kernel (Wendland C2: `(1 - s)^4 (4s + 1)` for `s < 1`, else 0). Dividing the radius by
  `(1 - rho)` shrinks influence in rigid zones, so a stiff zone is moved only by control points
  inside or adjacent to it. Normalising the weights to sum <= 1 guarantees `|d| <= max |o_i|`: no
  overshoot, no instability, and a vertex far from every control point is unmoved.
- **Rigid zones move rigidly**: for `rho(v) = 1` the displacement is the best-fit rigid transform of the
  controls inside the zone (computed per zone, not per vertex) so a stiff zone translates/rotates and
  does not shear. Implementation: the control points inside a zone define one transform (Kabsch /
  Procrustes on <= 8 points), applied to the zone's vertices, blended into soft vertices through the
  weights above.
- **Normals**: recomputed per frame on the CPU for the touched vertices (or finite-difference of the
  displaced tangents in the vertex shader for GPU skinning) to keep the lighting-free pass honest:
  the pass is unlit paint over base colour, so normals matter only for the headlight shade
  (3d-space.md "Rendering choice"). Wrong normals are a cosmetic risk only.

### 8.4 Where it runs (CPU vs GPU) and limits

- **Recommended: CPU solve, GPU draw.** Control points are few; the per-vertex blend is cheap; compute
  the displaced vertex positions on the CPU per frame *only while a control is being dragged or a
  clip is playing*, upload the dirty vertex range with `glBufferSubData` into the existing
  `surfaceVbo` (`DepthCompositor.surfaceVbo` already keeps big meshes in a VBO; relief meshes are
  already up to 64k vertices and rebuilt "each step" when strength changes, so a 4-16k vertex update
  per frame is within precedent and well under it).
- Alternative GPU path (vertex shader skinning with the control offsets as a uniform array and the
  rigidity as a per-vertex attribute): zero upload per frame, scales to the vertex cap trivially, but
  puts the Kotlin weights (needed for hit-testing and painting by footprint) and the shader in two
  places (consistency risk, and the CPU copy is needed anyway for `worldToViewFlat`). Keep as an
  optimisation behind the same API if profiling demands it (the CPU copy remains the source of truth
  for picking and export).
- **Vertex limits (Stage 2 hard cap)**: 8,192 vertices per soft body (a relief grid is capped at 64k
  vertices today; soft-body mode refuses to enable on a mesh above 8k and offers "Simplify" first), at
  most 32 control points and 8 zones per soft body, at most 2 soft bodies animating at once.
- **Picking/painting on the deformed surface**: `SurfaceHull.hit` and `SurfaceMath.hit` are analytic
  (strips/prisms); they do not know about vertex displacement. Options: (1) while a soft body is
  deformed, picking/painting go through a CPU **mesh raycast** of the displaced triangles
  (`MeshPicker.raycast` exists in `common/mesh`), with the existing `(u, t)` per vertex as the paint
  coordinate (no inverse analytic map needed because the texture coordinate is carried per vertex);
  or (2) disable painting while deformed ("Pose mode only"). Recommendation: (1) for Brush and Eraser
  in v1 of Stage 2 on the sheet case; the hull and crease-crossing/fill logic keep working at rest
  only, and deformed hulls are paint-disabled with a message. Biggest Stage 2 risk: R8.

### 8.5 Data model additions (Stage 2)

~~~
data class SoftBody(
    val controls: List<ControlPoint>,     // <= 32
    val zones: List<RigidityZone>,        // <= 8
    val schema: Int = 1,
)
data class ControlPoint(val id: String, val u: Float, val t: Float, val view: Int, val radius: Float, val offset: Vec3)
data class RigidityZone(val id: String, val center: Vec2, val radii: Vec2, val rotationDeg: Float, val rigidity: Float)
~~~

Stored on the profiled group as `Layer.softBody: SoftBody?` (null = rigid), persisted as
`OverlayLayer.softBody` in the next format. The surface's rest mesh is unchanged; the deformation is
a pure function (rest mesh, SoftBody) -> displaced vertices, so rest and pose never diverge and the
file stays small. Control point offsets are the pose (and what Stage 3 animates).

### 8.6 Rigid parts and soft bodies together

A soft body is a group; it can be the child of a joint (its whole deformed surface rides the part) and
a joint's anchor can sit on a soft body (the anchor is stored in rest body coordinates and follows the
displacement by evaluating `d(anchor)`). Allowing anchors on soft bodies is the integration point
that makes the two models one system; it is **not** in Stage 2 v1 (anchors on soft bodies are
rest-body-fixed there), to keep the first soft-body PR independent of the rig.

### 8.7 Cost summary

A: ~4 PRs, mostly UI (control placement, zone painting) over a small math core. B adds ~2. C is a
larger bet (sparse solver). Estimates in section 12.

## 9. Stage 3: keyframes, playback, export

### 9.1 Timeline model

~~~
data class Clip(
    val id: String, val name: String,
    val fps: Int = 24,                  // 12, 24, 30
    val durationFrames: Int,            // time is integer frames: no float time drift, exact export
    val tracks: List<Track>,
)
data class Track(val target: TrackTarget, val keys: List<Key>)   // keys sorted by frame, unique frames
data class Key(val frame: Int, val value: KeyValue, val interp: Interp = Interp.EASE)
~~~

`TrackTarget`: `JointAngle(jointId)`, `JointBall(jointId)` (quaternion), `ControlOffset(layerId, controlId)`,
`PartRoot(partId)` (rigid transform of a root). Interpolation: hinge angle and offsets lerp with
`HOLD | LINEAR | EASE` (smoothstep) in v1; quaternions slerp (shortest arc, normalised); Bezier
handles are a later addition. Value at frame `f` is a pure function of the clip, so scrubbing is
deterministic and requires no state. Evaluate -> `Rig.pose` + soft-body offsets -> the same `partModel`
path as Stage 1; **playback adds no render path**.

### 9.2 Editing

Record-style: in pose mode with a clip open, changing a pose at the playhead sets/updates a key for the
touched joint(s) (auto-key, toggleable, off = changes are a temporary pose that return on scrub). Timeline
strip docked at the bottom (replacing the rotation gizmo region, as the gizmo and sheet already share
that area; layout decision is part of the Stage 3 design review). Keys are draggable chips; long press
a key for interpolation. Undo: key edit = `RigChange` plus a new `ClipChange` snapshot (clips are small).

### 9.3 Playback

Timer driven by `Choreographer` frame callbacks, advancing by wall-clock elapsed time, mapped to
`frame = floor(t * fps)` with loop/once/ping-pong; **frame skipping, not slow motion**, if the device
cannot hold the rate. The pose is applied through the normal model path; the invalidation hooks
that exist (cache key includes pose) rebuild only what changed. While playing: the inactive-layer
cache is bypassed (view or transform changing already bypasses it), painting is disabled, and the
orbit pivot is frozen.

### 9.4 Export (Q11)

Rev 2: glTF animation is the clip format itself (0B), not an export step; items 1 and 2 below swap
priority accordingly (glTF first, GIF/MP4 are renders of it). Open question Q11 stands.

1. **GIF / MP4**: render N frames through the same off-screen depth pass used for PNG/PDF export
   (`OffscreenDepthRender` + `SceneExport`); `AnimatedGifEncoder.java` already exists in
   `common`; MP4 via `MediaCodec` + surface. Cost is N * (offscreen render + readback);
   at canvas size 1080 x 2400 a readback is ~10 MB, so GIF export is capped (e.g. 120 frames) and
   scaled; progress UI and cancellation required.
2. **glTF animation**: nodes per part (hierarchy from the rig), animation channels for rotation
   (quaternion) and translation sampled at the clip fps; soft bodies as morph targets baked per frame
   (vertex count x frames x 12 B: 4k verts * 60 frames = 2.9 MB) or skin joints; per-frame baking caps at
   the Stage 2 vertex limit and 240 frames.
3. **Project format**: `GraffitiProject.clips: List<Clip>` in a later format bump.

### 9.5 Stage 3 risks

Timeline UI is the largest part (touch editing of keys on a phone). Playback correctness with
the depth pass's uploads: textures upload on `generationId` change only; pose changes never touch
texture uploads, so cost per frame is matrices + draw, not GPU upload.

## 10. Performance budgets

Hard limits are enforced with a visible message, not silent truncation.

| Item | Limit | Why |
|---|---|---|
| Joints per project | 32 | UI list, history snapshot, FK < 0.1 ms |
| Rig chain depth | 16 | `MAX_CHAIN`, float error bound |
| Pose drag frame (FK + hit math) | < 1 ms CPU on a mid device | one 16 ms frame budget, leaves room for draw |
| Joint-marker overlay | < 1 ms | <= 64 markers, simple paths |
| Frame cost with a rig vs without | no measurable increase when no rig exists; < 5% when posed | identity path untouched (constraint in 3d-space.md: fast path stays fast) |
| Memory per joint | < 200 B, per `Rig` snapshot < 8 KB | history cap 20 entries |
| Soft body vertices | <= 8,192 | CPU blend 8k * 32 = 262k mul-add < 1 ms |
| Control points per body | <= 32 | |
| Soft bodies updating per frame | <= 2 | upload bandwidth: 2 * 8k * 12 B = 192 KB / frame |
| Soft vertex upload | `glBufferSubData` dirty range only | |
| Skinned model import | <= 64 joints per skin, <= 100,000 vertices, GLB <= 64 MB (the co-op cap) | uniform array limit; CPU copy cost |
| Skin on pose change | CPU copy < 8 ms, joint upload < 0.2 ms | not per frame |
| Clip | <= 240 frames, <= 128 tracks | |
| GIF export | <= 120 frames, <= 720p side | 10 MB readback each |
| No-allocation rule | FK, drag-follow, CCD, blend loops allocate nothing per frame (preallocated `FloatArray`s, as `Affine3` uses) | GC jank on drag |

Measure on a mid-range device with the existing benchmark style tests (`SurfaceFootprintBenchmarkTest`
precedent for pure-Kotlin timing guards); GL timing is on-device only (R1).

## 11. Test strategy

JVM tests in `:core:common` (pure Kotlin, no Android), property-based where noted. The repo already
has JUnit tests and unit tests of this kind (`OrbitPivotTest`, `SurfaceHullTest`); use a seeded
`java.util.Random` loop (e.g. 500 cases, seed fixed and printed on failure) unless a property-test
library is already a dependency (check `gradle/libs.versions.toml`; do not add one just for this).

Kinematics properties (Stage 1):

- **P1 identity**: pose 0 => `P(x) == M0(x)` for random rigs (random tree, random rest transforms).
- **P2 anchor coincidence**: for random in-rest rigs and random legal poses, `P(c)*childAnchor ==
  P(p)*parentAnchor` within 1e-3 scene px (scaled to coordinates).
- **P3 rigidity**: a part's shape is rigid: pairwise distances of random body points preserved under
  any pose (isometry).
- **P4 path independence / drag reversibility**: pose from drag start to `h_t` depends only on `h_t`
  and the start pose; returning to `h0` restores the start pose exactly.
- **P5 limits**: for any drag, resulting values lie in limits; solving twice is idempotent.
- **P6 rigidity gain**: `r = 1` => joint value unchanged; `r = 0` => joint tracks the target when
  within limits; applied/refused partition sums to the request.
- **P7 paint stability**: `worldToViewFlat(P(c) * x)` under pose equals `worldToViewFlat(M0(c) * x)`
  under rest, for random surface points.
- **P8 cycle freedom**: no sequence of `addJoint`/`removeJoint`/`sanitize` ever yields a cycle or a
  two-parent part; `wouldCycle` agrees with a brute-force reachability check.
- **P9 serialization**: encode/decode round trip; format 13 file decodes to `rig = null`; unknown
  `JointKind` decodes to UNKNOWN and is preserved; NaN joints dropped.
- **P10 agreement with existing code**: a one-joint rig with a root and a child, pose via a root drag
  of angle `a` about the pivot, equals `SceneMath.rotatePoseAbout(pose, axis, a, pivot, ...)` (the new
  FK reduces to the old primitive in the single-part case).
- **P11 numerical stability**: chain of 16 hinges, random angles: FK result vs a Double reference
  within 1e-3; rotation blocks stay orthonormal within 1e-4; no NaN for fully extended chains (F3).
- **P12 CCD**: reachable targets converge within tolerance in <= 10 iterations on random chains;
  unreachable targets end fully extended; deterministic.

Integration (JVM, `:feature:editor`, existing fake-depth-source pattern): add/remove/undo/redo of a
joint and of a pose gesture (one drag = one step; redo restores; `chainedToPrevious` chain
for delete-with-joints); `EditHistory` dedup; sanitise on layer delete/duplicate; load a format 13
project (no rig) and a 14 project; export a posed scene to glTF and check node transforms; hit-test
returns the posed part.

UI (Robolectric, like `RotationGizmoUiTest`): pose-mode chip appears, pass-through (a drag in pose
mode never paints), TalkBack custom actions change the joint, Back exits, two-tap placement and the
list-based placement produce the same joint.

On-device only (flag it in the PR description each time, as 3d-space.md does): GL drawing of posed
parts, marker overlay appearance, drag feel, performance.

Stage 2 adds: P13 weights sum <= 1 and displacement bounded by max offset; P14 zone with rigidity 1 is
an isometry of its vertices (rigid zone does not shear); P15 offsets 0 => rest mesh exactly; P16
`(u,t)` per vertex is invariant under deformation (paint sticks); benchmark guard for the blend loop.
Stage 3 adds: sampling is a pure function; key add/remove/move properties; slerp shortest-arc; export
frame count and timing exact (integer frames).

## 12. Staged PR breakdown (rev 2)

Constraint: each PR < 300 changed files (every one is far smaller). Re-cut so that **importing a
rigged glTF and posing it ships before, and independently of, authoring joints**; both then share
the same node/pose/history code. Sizes are estimated files / LOC (excluding tests unless noted).
"On-device only" means verified in JVM tests but not on a device, to be stated in each PR.

- **PR 0: this spec.** 3 files.

Stage 1 (rigs: imported and authored):

| PR | Contents | Files | LOC | Depends on |
|---|---|---|---|---|
| 1.0 | **Spike** (no merge to main of the dependency until accepted): jgltf vs cgltf on APK delta, 20 MB GLB parse, R8, licences; Khronos glTF-Validator wired as a test/CI step on the *existing* exporter output; record findings in this spec | ~5 | ~150 | none |
| 1.1 | Core model, pure Kotlin: `Skeleton`/`SkelNode`/`Skin`/`SkinnedMesh`, `Skinning.kt` (spec formula), quaternion type, FK over a node forest, pose delta, cycle/sanitise, limits and rigidity gain (3.2, 3.4); property tests P1-P12 + skinning fixtures | ~8 | ~1100 + 900 tests | none |
| 1.2 | **Import glTF/GLB**: loader adapter (library from 1.0) to `SkinnedMesh` + `Skeleton`; model layer holds `modelFile` + optional skeleton (format 14 field, persistence); refuse list (external files, Draco, KTX2) with clear messages; one primitive and base colour first | ~12 | ~700 | 1.0, 1.1 |
| 1.3 | **Render and pick skinned models**: GLES 3 vertex-shader skinning in the depth pass, joint upload on pose change, CPU-skinned copy for `MeshPicker`/`ModelSpace` picking and painting, pivot bounds from posed bounds; static models unchanged byte for byte. On-device only | ~10 | ~600 | 1.2 |
| 1.4 | **Pose an imported rig**: history (`RigChange`, one drag = one step), joint selection by tapping the mesh (nearest dominant joint of the hit triangle) and by a joint list, pose mode UI (chips, drag-follow, rigidity gain), rotation gizmo reuse (needs #753), TalkBack actions, reset pose, numeric entry. The "import a rigged model and pose it" milestone | ~14 | ~1300 | 1.3, #753 |
| 1.5 | **Authored joints on surface groups**: add-joint two-tap and list flows, joint sheet, markers overlay, `partModel` seam in renderer/hit-test/grid/pivot (rigid parts as nodes with meshes on them), project persistence | ~18 | ~1700 | 1.1, 1.4 |
| 1.6 | **Export our rig as glTF**: extend `SceneGltfExporter` with node hierarchy, `children`, `extras.GRAFFUX_joint`, the current pose as node rotations; `.glb` output; validator in tests; re-import round trip (our export imports back with kinds/limits/rigidity intact) | ~7 | ~600 | 1.1, 1.5 (1.2 for the round trip) |
| 1.7 | CCD pull handle (IK for chains of imported or authored joints) | ~6 | ~500 | 1.4 |
| 1.8 | OBJ+MTL materials, STL import, imported base-colour texture and multiple primitives per model | ~8 | ~600 | 1.2 |
| 1.9 | Co-op guard, docs, flag removal, spec status | ~5 | ~150 | 1.5 |

Stage 2 (soft body, on the skinning runtime):

| PR | Contents | Files | LOC |
|---|---|---|---|
| 2.1 | Weight generator: control points, rigidity field, kernel, normalised weights to `JOINTS_0`/`WEIGHTS_0`, rigid-zone transform; tests P13-P16 | ~5 | ~700 |
| 2.2 | Surface group meshes as `SkinnedMesh` (sheet and relief) through the 1.3 runtime; vertex cap | ~8 | ~600 |
| 2.3 | Paint and pick on the deformed sheet via skinned positions (hull gated) | ~8 | ~500 |
| 2.4 | Persistence, history, glTF export as a skin of control-point nodes (re-import round trip) | ~8 | ~400 |
| 2.5 | Control point and zone UI, a11y | ~12 | ~1100 |
| 2.6 | (optional) secondary motion (PBD) | ~6 | ~500 |

Stage 3 (clips, on glTF animation):

| PR | Contents | Files | LOC |
|---|---|---|---|
| 3.1 | `Clip` = glTF animation (samplers/channels), sampling (`LINEAR`/`STEP`/`CUBICSPLINE`, slerp), import of an imported file's own animations, tests | ~5 | ~600 |
| 3.2 | Persistence (clips, format bump), history | ~6 | ~300 |
| 3.3 | Playback engine (Choreographer, frame skip, cache interplay) | ~6 | ~400 |
| 3.4 | Timeline UI, auto-key, a11y | ~14 | ~1500 |
| 3.5 | Export glTF animation (reuses 1.6) and GIF/MP4 renders | ~8 | ~800 |

## 13. Risks

- **R14 (rev 2): untrusted glTF.** A hostile or corrupt file (huge accessors, cycles in `children`,
  out-of-range joint indices, NaN weights, bomb-sized buffers, external URIs). Mitigation: cap sizes
  before decoding (64 MB, 100k vertices, 64 joints, 1,024 nodes), reject cycles and bad indices, never
  resolve external URIs or `file:` URIs, run the parser off the main thread with a timeout, fuzz the
  adapter. A native parser would need the same plus memory-safety tooling.
- **R15 (rev 2): extras are not enforced.** Other tools may drop `GRAFFUX_joint` when re-saving; limits
  and rigidity are then lost on re-import (geometry and animation survive). Accepted.
- **R16 (rev 2): real-world rigs are messy** (hundreds of joints, non-uniform scale, negative scale,
  skeleton root not under the skinned node, multiple skins/scenes). v1 supports one scene, uniform positive
  scale, first skin per mesh; others are refused with a message.
- **R17 (rev 2): the dependency.** jgltf (or cgltf) is a new supply-chain and maintenance dependency;
  the spike records its licence, size and maintenance state, and the hand-rolled reader on
  `kotlinx-serialization-json` stays the fallback.

- **R1: nothing here is verified on a device.** GL paths are only JVM-tested in this project today;
  puppetry makes the seam changes (1.3) touch the depth pass. Mitigation: 1.3 changes models, not
  drawing; on-device verification is a stated gate before 1.6.
- **R2: "pose beside rest" is more state.** Two truths (layer transform + pose) is the price of
  non-destructive posing; if hit-testing or any consumer reads the layer transform directly it will
  show the rest pose. Mitigation: a lint-style test that every consumer of a part's model goes
  through `partModel` (grep-checked in CI by a test listing allowed call sites).
- **R3: Euler-based consumers.** Some paths still take `PlanePose`; decomposing a posed model with
  `poseOf` folds at gimbal. Mitigation: move consumers to `Affine3` in 1.3; document the leftovers.
- **R4: rigidity semantics may feel wrong in use.** The `(1 - r)^2` gain and parent propagation are a
  design bet, not physics. Mitigation: ship with the numbers in one named object (`RigTuning`) and a
  design review on-device before 1.6 merges; the model is data (`rigidity`), so retuning does not
  change files.
- **R5: content-dependent format version.** Narrowing the bump (4.1) deviates from precedent.
- **R6: float32 in CCD.** Watch drift; Double inside the solver if needed.
- **R7: co-op.** Not synced in Stage 1; a peer shows rest pose. Needs a real protocol (`Op.RigSet`).
- **R8: painting on a deformed surface** (Stage 2). The analytic hit paths do not apply; the mesh
  raycast path is new and the hull/crease logic assumes undeformed geometry. Biggest Stage 2 risk.
- **R9: MESH conflict.** `claude/mesh-all-views` is unmerged and changes wire mesh/hull drawing; the
  joint overlay and the `partModel` seam touch the same files (`DepthScene`, `SurfaceHullMesh`,
  grid). Sequence 1.3 after that branch merges, or rebase carefully; 1.1-1.2 are independent.
- **R10: PR #753 is a draft.** Gizmo reuse (1.6) depends on its API.
- **R11: discoverability.** Pose mode is hidden behind a menu; mitigated by the one-time chip and the
  empty state, but must be user-tested.
- **R12: parts that are paint layers in groups with per-view images moved by a stale layer-tree
  transform.** The per-view image layers "are not moved by the paint layer's own transform"; they are
  moved by the group's. Confirm in 1.3 with P7 that no view-image path reads the paint layer's own
  pose.
- **R13: the 2D Compose fallback** can show a posed flat group incorrectly if it reads layer fields.
  Covered by R3; keep flat parts on the scene path when posed.

## 14. Open questions for the owner (consolidated)

Beyond the table in section 0, these affect scope and need an answer before the PR that depends on
them:

1. Joint placement default for **parent vs child** when neither tapped part is in a tree: first tap =
   parent (proposed). Acceptable?
2. Should **Bake pose into rest** exist in Stage 1, or is Reset pose enough? (Proposed: yes; adds
   about 100 lines.)
3. **Hinge default axis** = camera view direction at placement (so first joint behaves like a 2D pivot).
   Acceptable, or default to the part's body Z?
4. Is "**Stiffness**" the right user-facing word for rigidity (the owner's own word is "rigidity")?
5. Do you want **mirrored/symmetric posing** (pose the left arm and have the right follow)? Not in this
   plan; it changes the data model (a symmetry pairing).
6. **Co-op**: acceptable that Stage 1 does not sync poses?
7. Should a part be allowed to be a **single layer** (not a group) so a lone paint layer can be posed
   without wrapping it? (Proposed: no; wrapping in a group is one tap and is how surfaces work.)
8. **Soft-body paint on hulls** (Stage 2): acceptable to disable painting on deformed multi-view
   hulls in v1 and support deformed single-view sheets only?
9. Stage 3 **export priority**: GIF/MP4 first (proposed) or animated glTF first?
10. Any **hard cap** on joints (32) or soft vertices (8,192) the owner wants different?
11. **Standards backbone (Q15-Q25 in section 0)**: confirm glTF as the interchange and `extras.GRAFFUX_joint`
    for rigidity and limits; parser library after the spike; formats supported on device vs converted
    externally; `.glb` export and glTF-Validator in CI; refusal of external-file glTF, compressed glTF and
    KTX2 in v1.
12. **Ordering**: confirm "import a rigged glTF and pose it" (PR 1.4) as the first user-visible puppetry
    milestone, with authored joints after it (PR 1.5).
13. **Caps**: 64 joints per skin and 100,000 vertices per skinned import acceptable, and whether over-cap
    models are refused or reduced.
14. **Source of truth**: confirm that the imported file is stored unmodified and only the pose, overrides and
    clips are saved in the project (4.4), at the cost that editing the source model elsewhere and
    re-importing can orphan pose entries (name fallback only).
