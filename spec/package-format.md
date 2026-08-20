# azphalt package format — `spec/package-format.md`

Spec version implemented by this host: **`0.1`** (`AZPHALT_SPEC_VERSION` in
`core/common/src/main/java/com/hereliesaz/graffitixr/common/azphalt/AzphaltManifest.kt`). Graffux
is described in this codebase as "the standard's first conforming host."

This document reverse-engineers the `.azp` package format, its manifest schema, its signing/trust
model, the `.cube` LUT format extensions ship, and the sandbox capability model — strictly from what
`core/common` and `core/data`'s `azphalt` packages actually implement and enforce. It does not invent
fields, constraints, or defaults beyond what the code demonstrates.

Related normative documents this codebase references but which are out of scope here (also missing
from the repo, and not reconstructed by this pass): `spec/extension-manifest.md` (the manifest schema
in full detail), `spec/pack.md` (`kind: "pack"`), `spec/companion-app.md` (`kind: "app"`),
`spec/mcp-server.md` (`kind: "mcp"`), `spec/ui-schema.md` (native UI panel schemas), `spec/repository-api.md`
(the `.well-known/azphalt-repository.json` registry format), and `spec/state-reporting.md` (the
install/uninstall state-reporting channel — summarized here only where `store-app.md` needs it).

---

## 1. The `.azp` container

A `.azp` package is a ZIP archive. `AzpInstaller`
(`core/data/.../azphalt/AzpInstaller.kt`) reads it as a `ZipInputStream` and requires:

| Entry | Required | Notes |
|---|---|---|
| `manifest.json` | **Yes** | Root of the package; parsed as `AzphaltManifest`. Its own bytes, verbatim, are what a `signature.json` signs. |
| `LICENSE` | **Yes** | "The package format requires a LICENSE file; refuse a package that omits it." Content is not validated, only presence. |
| `signature.json` | No | Detached Ed25519 signature over `manifest.json` (§ 3). Exempt from the `files` digest map so provenance can be re-derived on rescan. |
| any path listed in `manifest.files` | Yes, per entry | Every payload file the manifest declares must be present and byte-verified (§ 2). |

The installer enforces, in order, on every install:

1. **Cumulative decompressed-size ceiling**: `MAX_PACKAGE_BYTES = 64 MiB`. This is a zip-bomb guard
   on untrusted input; per the code comment, "Asset packages are small (bundled multi-GB models use
   `remoteUrl`, not the archive)."
2. **Unsafe entry paths are rejected**: an entry name that is absolute (`/…`, `\…`), contains a drive
   letter (`:`), or contains a `..` path segment anywhere throws `InstallException`.
3. **Duplicate entry names are rejected.** Two ZIP entries under the same name is a "ZIP confusion"
   attack: different tools can disagree about which copy is real, so a scanner and an installer could
   verify/install different bytes under one digest. The installer refuses the whole package rather
   than silently keeping one copy.
4. **`manifest.json` must be present**, or install fails.
5. **The package id must not start with `.`** (§ 2.1).
6. **`kind` must not be `unknown`** — an unrecognized `kind` is never installable (§ 2.2).
7. **`compat` must be satisfied** against this host's `AZPHALT_SPEC_VERSION` (§ 2.3).
8. **`LICENSE` must be present** in the archive.
9. **Every file in `manifest.files` must be present and match its declared SHA-256 digest** (§ 2.4).
10. **Every payload entry in the archive (other than `manifest.json`/`signature.json`) must be
    listed in `manifest.files`.** An entry that rides along undeclared is an unverified payload —
    the manifest signature only covers the files map, so an unlisted file has nothing attesting to
    it. (This is asserted in the conformance suite as `unlisted-payload.azp` must fail `verify.ok`.)
11. **A present `signature.json` must verify**, or the whole package is refused as tampered/corrupt
    (§ 3). An *absent* signature, or a signature that verifies but whose signer isn't trusted, is
    still installable — it just carries weaker provenance.
12. **Publisher continuity** is enforced on a same-id reinstall (§ 2.5).

Only after all of the above succeeds does the installer unpack. It stages into a dot-prefixed
directory (`.staging-<id>-<nowMs>`, skipped by the repository's rescan because it starts with `.`),
writes every entry that is `manifest.json`, `signature.json`, or a `manifest.files`-declared path
(nothing else), then atomically swaps it into `<extensionsRoot>/<id>/` — moving any prior install
aside to `.backup-<id>-<nowMs>` first, not deleting it, so a `File.renameTo` failure (documented as
unreliable) can restore the previous working install instead of leaving the id permanently gone. Any
failure during staging deletes the staging directory and rethrows, so **there is no partial install**
for that id.

### 1.1 On-device layout

Once installed, an extension lives at `filesDir/extensions/<id>/` (`ExtensionRepository`). That
unpacked tree **is** the installed state — the in-memory `installed` list is rebuilt by rescanning
the directory, not by a separate index — so an install/uninstall survives process death with nothing
to fall out of sync. A directory is only recognized as an install if it does not start with `.` and
contains a `manifest.json`; `signature.json`, if present, is re-read on every rescan to re-derive
provenance (`AzpSignatures.evaluate`) against the host's current `TrustStore`, so an extension's
badge can flip from "Signed" to "Verified" purely because the host's trust store was refreshed later.

`id` values are only lightly sanitized for the filesystem: `safeId()` replaces anything outside
`[A-Za-z0-9._-]` with `_`. The convention documented on `PackEntry.id` is a **reverse-DNS id** (e.g.
`com.hereliesaz.invert`), but the only rule this host actually *enforces* on `id` is the dot-prefix
rejection below — there is no regex requiring reverse-DNS shape.

---

## 2. The manifest (`manifest.json`)

`AzphaltManifest` (`AzphaltManifest.kt`) is the parsed shape. Parsing uses a lenient JSON reader
(`ignoreUnknownKeys = true`) — a manifest field this host doesn't recognize is silently dropped
rather than failing the parse, and several enums (`ExtensionKind`, `Capability`, `AssetType`,
`Maturity`) map an unrecognized wire value to a documented `UNKNOWN`/default member instead of
throwing, so a package built against a newer spec revision still parses here; the host simply grants
or applies nothing it doesn't understand (fail-safe, least privilege).

### 2.0 Full field list

```kotlin
data class AzphaltManifest(
    val azphalt: String,              // spec version this manifest targets, e.g. "0.1"
    val id: String,                   // reverse-DNS convention; MUST NOT start with "."
    val name: String,
    val version: String,              // the package's own version (semver-shaped, not spec version)
    val kind: ExtensionKind,          // asset | code | mixed | app | mcp | pack (§ 2.2)
    val license: String,              // SPDX-ish identifier string
    val compat: String,               // host-compatibility comparator, e.g. ">=0.1" (§ 2.3)
    val author: String? = null,
    val description: String? = null,
    val homepage: String? = null,
    val maturity: Maturity = Maturity.GENERAL,   // GENERAL | MATURE
    val targetApps: List<String> = emptyList(),  // empty = universal
    val entry: String? = null,        // code kind: path to the executable payload
    val runtime: Runtime? = null,     // js | wasm
    val capabilities: List<Capability> = emptyList(),  // § 4
    val contributes: Contributes? = null,   // filters / tools / commands / transitions
    val assets: List<AssetContribution> = emptyList(),
    val preview: Preview? = null,     // store-card image/clip
    val app: JsonObject? = null,      // present only when kind == "app" (spec/companion-app.md)
    val mcp: JsonObject? = null,      // present only when kind == "mcp" (spec/mcp-server.md)
    val pack: PackManifest? = null,   // present only when kind == "pack" (spec/pack.md)
    val files: Map<String, String> = emptyMap(),  // payload path -> "sha256-<hex>"
)
```

### 2.1 `id` rules

The one rule actually enforced by `AzpInstaller`:

> An id starting with `.` (e.g. `".hidden"`) is refused at install time. `safeId()` keeps `.`
> characters, so such an id would land in a dot-prefixed directory — exactly the naming convention
> the installer's own staging/backup directories use, and exactly what `ExtensionRepository`'s
> rescan filter (`!f.name.startsWith(".")`) is built to skip. Left unrejected, such a package would
> "succeed," get recorded `ACTIVE`, and then be permanently invisible to every scan and unreachable
> by `uninstall(id)` — stranded on disk with no UI path back to it.

The documented (but not code-enforced) convention is a reverse-DNS id, e.g. `com.hereliesaz.invert`
or `com.filmluts.teal`.

### 2.2 `kind` — `ExtensionKind`

```kotlin
enum class ExtensionKind(val wire: String) {
    ASSET("asset"),
    CODE("code"),
    MIXED("mixed"),
    APP("app"),
    MCP("mcp"),
    PACK("pack"),
    UNKNOWN(""),   // a kind this host build does not recognize; never installable
}
```

| kind | Meaning | Payload |
|---|---|---|
| `asset` | Pure data contribution — LUTs, brushes, patterns, etc. No executable code. | `assets` entries |
| `code` | Sandboxed executable payload (`entry` + `runtime`), declaring `capabilities`. | `entry` file, run in `JsSandbox`/`WasmSandbox` |
| `mixed` | Both: code plus assets, where an asset may or may not need the code to be usable (`AssetContribution.standalone`, § 2.6). | `entry` + `assets` |
| `app` | A companion application (spec/companion-app.md). Header carries an `app` block. | none this host consumes |
| `mcp` | An MCP server (spec/mcp-server.md). Header carries an `mcp` block. | none this host consumes |
| `pack` | A **header-only** curated set that references other packages by id — carries no payload of its own (spec/pack.md, § 2.2.1). | none — `pack.entries` only |

`AzpInstaller.install()` will unpack **all six** known kinds — "GraffitiXR runs extension code in a
WASM sandbox rather than refusing non-asset kinds, so there is no asset-only policy to enforce" at
that layer. `unknown` is the one kind the installer itself refuses, with a clear message.

A second, narrower policy sits above the installer in `ExtensionRepository.installFromStream`: after
a successful install, if the resulting `kind` is `APP`, `MCP`, or `PACK`, the freshly-unpacked
directory is deleted and the call throws — *"'\<name\>' is a \<kind\> extension, which this app
cannot run."* Graffux's UI-driven install path (file picker, store handoff, deep link) therefore only
ever leaves `asset`/`code`/`mixed` packages actually installed on disk, even though the low-level
installer is kind-agnostic.

#### `pack` validation (`validatePackManifest`)

A `kind: "pack"` manifest is validated as header-only:

- must **not** declare `entry`/`runtime` (no code),
- must **not** declare `capabilities`,
- must **not** declare `assets`,
- `pack.entries` must be non-empty,
- each `PackEntry.id` must be non-blank and not equal to the pack's own `id` (no self-reference),
- each `PackEntry.version`, if present, must be non-blank,
- no two entries may share the same `id@version` pair.

```kotlin
data class PackEntry(
    val id: String,             // required: reverse-DNS id of the referenced package
    val version: String? = null, // absent = resolve the member's latest at install time
    val required: Boolean = false, // true = part of the base set; false/absent = recommended
    val note: String? = null,
)
```

### 2.3 `compat` grammar and `azphalt` vs. `version`

Three distinct version-shaped fields exist on a manifest — do not conflate them:

- **`azphalt`**: the spec version this manifest is written against (e.g. `"0.1"`).
- **`version`**: the *package's own* version (e.g. `"1.2.0"`) — what a store compares against a
  catalogue to decide *Update* vs. *Open*, and what `ExtensionStateEntry.version` records.
- **`compat`**: a single comparator over a host version, deciding whether *this host* is new/old
  enough to run the package. Default comparator is `>=` when the comparator prefix is omitted.

```
compat ::= [comparator] MAJOR["."MINOR["."PATCH]]
comparator ::= ">=" | "<=" | ">" | "<" | "="   (">=" is the default when omitted)
```

Deliberately **not** supported in `0.1`: ranges, unions (`||`), hyphen ranges, caret/tilde (`^`/`~`),
or prerelease tags. `parseCompat` mirrors the reference `@azphalt/azp` implementation exactly, "so
this host parses exactly what the reference implementation does." A `compat` string outside this
grammar (e.g. `^0.1`) fails closed — `compatSatisfies` returns `false`, so the package is refused as
incompatible rather than silently accepted by some looser fallback.

`isCompatibleSpec(compat) = compatSatisfies(AZPHALT_SPEC_VERSION, compat)` — i.e., does this host's
own `"0.1"` satisfy the package's declared `compat` expression.

### 2.4 `files` — the payload digest map

```json
"files": { "assets/teal.cube": "sha256-<64 lowercase hex chars>" }
```

Every payload path a package carries (other than `manifest.json`/`signature.json`, which are exempt)
must appear here, and the installer verifies each against a **SHA-256** digest. The digest format is
an **exact string match** against `"sha256-" + <lowercase hex>` — no other prefix form and no
case-insensitive comparison are accepted; a prior, more lenient implementation which supplied a
missing prefix and compared case-insensitively was found to accept manifests the reference installer
rejects, and was tightened to match it exactly.

### 2.5 Publisher continuity (trust-on-first-use)

Overwriting an existing install of the same `id` **is** an update, and the installer enforces that
an update comes from the same signer pinned at first install:

- No prior install, or a prior install that pinned nothing (was unsigned) → nothing to enforce; the
  update proceeds.
- Otherwise the new package's signer key (from its `signature.json`) **must equal** the key pinned on
  the prior install (re-derived from the prior install's retained `signature.json`).
- A missing signature on the update (a *signed → unsigned* regression), or a signature from a
  **different** key, is refused as a publisher change — **unless** the caller passes
  `allowPublisherChange = true` (an explicit, user-approved key rotation), in which case the overwrite
  proceeds and re-pins trust to the new key.

This defends against a third party replacing an already-installed extension by publishing a
same-id package of their own.

### 2.6 `assets` — `AssetContribution`

```kotlin
data class AssetContribution(
    val type: AssetType,              // brush | lut | pattern | stamp | shader | transition | … (§ 2.6.1)
    val path: String,                 // relative path inside the .azp; "" = not bundled (remote)
    val params: JsonObject? = null,   // per-type declarative params, read as raw JSON
    val ui: String? = null,           // optional native UI panel schema path
    val role: String? = null,         // semantic role, chiefly for model assets (e.g. "depth")
    val byteSize: Long? = null,
    val remoteUrl: String? = null,    // present when path == "" (not-bundled asset)
    val checksum: String? = null,     // "sha256-<hex>" of a remoteUrl payload
    val standalone: Boolean = true,   // usable without the package's code, in a "mixed" package
    val tags: List<String> = emptyList(),
    val contentRights: ContentRights? = null,
    val physical: PhysicalSize? = null,
    val io: ModelIo? = null,
    val files: List<ModelFile>? = null,
    val requirements: ModelRequirements? = null,
    val modelLicense: ModelLicense? = null,
)
```

`standalone` matters specifically for a `mixed`-kind package: an asset-only consumer (this host's LUT
and brush loaders) "MUST select assets where this is not false and skip the rest" — a code-dependent
asset in a mixed package is invisible to a host that only reads assets, and ignored for `asset`-kind
packages (always standalone there).

`path == ""` is the "not-bundled" / remote-asset form: the key stays present, and `remoteUrl` +
`checksum` carry the payload instead — a host fetches it lazily and must verify it against
`checksum` before use.

#### 2.6.1 `AssetType`

`brush`, `lut`, `pattern`, `stamp`, `shader`, `transition`, `mesh`, `material`, `hdri`, `motion`,
`palette`, `image`, `video`, `font`, `audio`, `vector`, `template`, `overlay`, plus the AI-model
family `tflite`, `litert`, `onnx`, `sherpa-bundle`, `model`, `task`, `vosk-bundle` (`isModel == true`
for these seven), and `unknown` (a type this build doesn't recognize — the contribution is retained
but not applied). Of these, this host only actually *consumes* `brush` and `lut` — see
`ExtensionRepository.installedLuts()`/`installedBrushes()` and `StoreWindow`'s
`SUPPORTED_ASSET_TYPES` — every other type parses and is retained on the manifest but has no
consumer yet.

For a `lut` asset, `params` may carry (§ 3, LUT application below):

```json
"params": { "strength": 0.85, "inputTransfer": "linear" }
```

### 2.7 `capabilities`, `contributes`, `maturity`, `targetApps`, `preview` — brief

- **`capabilities`**: only meaningful for `code`/`mixed` packages; see § 4 (Sandbox capability model).
- **`contributes`**: `{ filters, tools, commands, transitions }`, each a list of
  `{ id, name, entry, ui? }`. `transitions` are "two-input blends over a normalized progress, for
  temporal hosts."
- **`maturity`**: `general` (default) or `mature` — a developer self-attestation; "a store surfaces it
  and puts a `mature` listing behind an age-confirmation gate before revealing its card." An
  unrecognized value falls back to `general`.
- **`targetApps`**: host app ids this extension targets; empty means universal.
- **`preview`**: `{ image?, clip? }` — a static store-card still and/or short clip, each either an
  in-package path or an `https:` URL, so a store can render a browse grid without downloading or
  executing the package.

### 2.8 Sample manifests

An `asset`-kind LUT package:

```json
{
  "azphalt": "0.1",
  "id": "com.filmluts.teal",
  "name": "Teal LUT",
  "version": "1.0.0",
  "kind": "asset",
  "license": "CC-BY-4.0",
  "compat": ">=0.1",
  "assets": [
    { "type": "lut", "path": "assets/teal.cube", "params": { "strength": 0.85, "inputTransfer": "linear" } }
  ],
  "files": { "assets/teal.cube": "sha256-<hex>", "LICENSE": "sha256-<hex>" }
}
```

A `code`-kind filter package:

```json
{
  "azphalt": "0.1",
  "id": "com.hereliesaz.invert",
  "name": "Invert",
  "version": "1.0.0",
  "kind": "code",
  "license": "MIT",
  "author": "Az",
  "description": "Invert layer colors, by adjustable strength.",
  "compat": ">=0.1",
  "entry": "code/main.js",
  "runtime": "js",
  "capabilities": ["bitmap", "params", "canvas"],
  "contributes": {
    "filters": [{ "id": "invert", "name": "Invert", "entry": "invert", "ui": "ui/panel.json" }]
  },
  "files": { "code/main.js": "sha256-<hex>", "ui/panel.json": "sha256-<hex>", "LICENSE": "sha256-<hex>" }
}
```

---

## 3. Signing and trust

Source: `AzpSignature.kt`, `TrustStore.kt` — both under `core/common/.../azphalt`.

### 3.1 What is signed

A `signature.json` (if present) carries an **Ed25519** signature over **the exact `manifest.json`
byte sequence stored in the archive** — verbatim, with **no re-canonicalization** (no JCS/RFC 8785,
no whitespace normalization, no key reordering). Because the manifest's `files` map carries a SHA-256
digest of every payload file, "signing the manifest transitively signs the payload through the
`files` digests."

```json
{
  "alg": "ed25519",
  "publicKey": "<base64 SPKI DER SubjectPublicKeyInfo>",
  "signature": "<base64 Ed25519 signature over the verbatim manifest.json bytes>",
  "keyId": "optional-signer-chosen-identifier",
  "countersignature": {
    "publicKey": "<base64 SPKI of the registry/authority vouching>",
    "signature": "<base64 Ed25519 signature over the vouched-for key's SPKI DER bytes>",
    "keyId": "optional",
    "countersignature": null
  }
}
```

`keyId` is informational only — trust is matched on `publicKey`, never on `keyId`.

### 3.2 Tamper-evidence vs. identity (the trust split)

The codebase draws this distinction explicitly, and it is the organizing idea of the whole model:

> "A valid signature is *tamper-evidence*; this is the *identity* decision." (`TrustStore.kt`)

- **Tamper-evidence** (`AzpSignatures.isManifestSignatureValid`): does the Ed25519 signature verify
  against the exact manifest bytes and the claimed public key? This says nothing about *who* the key
  belongs to — only that the manifest hasn't been altered since whoever holds that key signed it.
- **Identity/trust** (`evaluateTrust`, against a `TrustStore`): is the signer's key one this host
  actually trusts, directly or transitively?

`SignatureStatus` is the combined result a host records per installed extension:

```kotlin
enum class SignatureStatus {
    UNSIGNED,          // no signature.json — integrity only, no established provenance
    SIGNED_UNTRUSTED,  // valid signature, but the signer is not in the trust store
    SIGNED_TRUSTED,     // valid signature AND the signer is trusted (direct or via registry)
    INVALID,           // signature.json present but does not verify — tampered/corrupt; MUST refuse
}
```

A package whose evaluated status is `INVALID` is **refused outright** by `AzpInstaller` (a
`signature.json` that is present-but-malformed and fails to parse is *also* treated as `INVALID`,
not `UNSIGNED` — "a mangled signature" must not "install as if it were merely unsigned"). `UNSIGNED`
and `SIGNED_UNTRUSTED` both install; the difference is only what the UI shows (`StoreWindow`'s
`SignatureBadge`: Unsigned / gray, Signed / amber, Verified / green, Invalid / red).

### 3.3 `TrustStore` and transitive (registry) trust

```kotlin
data class TrustedKey(val publicKey: String, val keyId: String? = null, val label: String? = null)
data class TrustStore(val keys: List<TrustedKey> = emptyList())
```

A package is **trusted** when either:

(a) its signer key is **directly** present in the store, or

(b) its signer key was **counter-signed** by a key that *is* in the store — walking the
`countersignature` chain from the author's key upward. Each hop's key signs the SPKI DER bytes of the
key immediately below it. Trust is granted the moment a hop's key is found in the store, provided
every signature down to that hop verifies; if every hop's key exhausts without a match, the package
is `SIGNED_UNTRUSTED`.

> "Transitive trust, so a host can trust one registry instead of every author."

The chain walk is capped at **`MAX_CHAIN_DEPTH = 10`** hops — "a DoS guard against attacker-crafted
deep chains" — beyond which trust evaluation fails closed.

Graffux bootstraps its `TrustStore` from a registry's well-known document
(`ExtensionRepository.refreshTrustStore`, fetching
`https://azphalt.store/.well-known/azphalt-repository.json`'s `signingKeys` array), caches it to
`azphalt-trust-keys.json` under `filesDir` so it survives offline launches, and refreshes it in the
background on every app start; a fetch failure (no network) simply keeps using whatever is cached.
Because trust is transitive through the registry, "a non-empty store transitively trusts every
author the registry counter-signs" — an already-installed extension's badge can silently upgrade
from `SIGNED_UNTRUSTED` to `SIGNED_TRUSTED` purely because the registry key arrived later, with
nothing else about the package changing.

> Key distribution itself (how a host first learns which registry key(s) to seed the store with) is
> "out-of-band by design; this only enforces the cryptography" — `TrustStore.kt` does not claim to
> solve that problem, only to evaluate signatures/chains against whatever keys the host already holds.

### 3.4 What "signing" buys a package, concretely

- `INVALID` (bad/corrupt signature) → always refused, regardless of trust.
- Any signature status other than `INVALID` → installs. Trust only affects the UI badge and the
  publisher-continuity rule below (§ 2.5), which is stricter for a *previously signed* package.
- An update to an already-installed, previously-signed extension **must** come from the same signer
  key, or is refused as a publisher change (§ 2.5) — this is the one place trust status has a hard
  install-time consequence beyond the badge.

---

## 4. The `.cube` LUT format

Source: `core/common/.../azphalt/CubeLut.kt`. This is the **normalized form azphalt asset extensions
ship 3D color grades as** — the Adobe/IRIDAS `.cube` text format, 3D LUTs only (a `LUT_1D_SIZE` header
throws `IllegalArgumentException("1D .cube LUTs are not supported")`). Pure Kotlin, Android-free, so
parse/apply are unit-testable independent of any Bitmap bridge.

### 4.1 File grammar

```
TITLE "optional title"          # ignored except as a comment
# comment lines starting with #
LUT_3D_SIZE 33                  # required; integer in [2, 256]
DOMAIN_MIN 0.0 0.0 0.0           # optional; default 0 0 0
DOMAIN_MAX 1.0 1.0 1.0           # optional; default 1 1 1
0.000000 0.000000 0.000000       # size^3 rows of "r g b" in [0,1], RED VARYING FASTEST
0.031250 0.000000 0.000000
...
```

Parsing requires `size^3 * 3` numeric values exactly, or it throws `IllegalArgumentException`
(`"Expected N LUT values, got M"`). `LUT_3D_SIZE` outside `[2, 256]` is rejected by the `CubeLut`
class invariant itself (`require(size in 2..256)`), independent of the parser.

### 4.2 `DOMAIN_MIN`/`DOMAIN_MAX`

These declare the input value range the LUT's grid indices correspond to. Sampling rescales an input
color linearly from `[domainMin, domainMax]` into the LUT's `[0, size-1]` grid index space before
trilinear interpolation (`rescale(v, min, max) = (v - min) / (max - min)`, guarded against a
degenerate `max <= min` by returning `v` unchanged). Default, when absent, is the identity `[0,1]`
domain on all three channels.

### 4.3 `inputTransfer` — the sampling domain

```kotlin
enum class LutInputTransfer { SRGB, LINEAR, LOG_C }
```

Declared via `params.inputTransfer` on the `lut` asset contribution (`"srgb"` | `"linear"` |
`"log-c"`/`"logc"`; anything else, or absent, defaults to `SRGB` — "the bare-`.cube` default"). This
is the transfer function a host **must** convert each pixel into *before* sampling the LUT, and back
out of *after*:

- **`SRGB`**: no conversion — the LUT samples directly against sRGB-encoded `[0,1]` values. This is
  the implicit assumption of a bare `.cube` file with no metadata.
- **`LINEAR`**: pixels are converted sRGB → scene-linear before sampling (`srgbToLinear`, the
  standard piecewise sRGB EOTF: linear segment below `0.04045`, gamma-2.4 power curve above), then
  the graded result is converted back (`linearToSrgb`).
- **`LOG_C`**: pixels are converted sRGB → linear → **ARRI ALEXA LogC v3 (EI 800)** before sampling,
  then LogC → linear → sRGB on the way out. LogC is "the widely-used log encoding for
  `inputTransfer: 'log-c'`" — implemented via the standard ARRI LogC v3 piecewise curve (constants
  `LOGC_A..LOGC_F`, `LOGC_CUT = 0.010591`).

`CubeLut.withInputTransfer(transfer)` produces a cheap copy that samples in a different transfer —
"the table is shared; only per-pixel conversion changes."

### 4.4 `strength` — dry/wet blend semantics

Declared via `params.strength` on the `lut` asset contribution, a float in `[0, 1]`, **default `1`**
(fully graded) when absent.

`CubeLut.withStrength(strength)` produces a table blended toward **identity** by the given amount, in
the LUT's own domain-aware grid coordinates:

```
identity(gridPoint) = domainMin + (gridIndex / (size-1)) * (domainMax - domainMin)   // per channel
blended(gridPoint)  = identity(gridPoint) + (originalTableEntry - identity(gridPoint)) * strength
```

Because trilinear sampling is **linear** in the table entries, and the identity table samples back to
exactly the input, sampling the *blended* table is mathematically equivalent to
`lerp(input, graded, strength)` — computed once per LUT build rather than per pixel, and "in the
LUT's own sampling domain — the domain the spec pins the blend to." `strength >= 1` returns the LUT
unchanged (no copy made); `strength <= 0` yields a pass-through identity grade.

### 4.5 Application

`CubeLut.applyPixel(argb)` grades one packed ARGB int (alpha preserved untouched), converting into
the declared `inputTransfer` domain, trilinearly sampling the 3D grid (8-corner interpolation,
`indexOf(r,g,b) = (r + g*size + b*size*size) * 3`, red varying fastest to match the `.cube` row
order), then converting back out. `applyPixels(pixels: IntArray)` grades an array in place, reusing
one scratch `FloatArray(3)` per call to avoid per-pixel allocation in the hot loop. A `CubeLut`
instance holds no mutable per-call state, so one instance may be applied concurrently from multiple
threads.

The code comment on why this is a separate path from GraffitiXR's other color adjustments: "A
ColorMatrix (what GraffitiXR's adjustments use) is a 4×5 affine transform and cannot represent a
general 3D LUT."

---

## 5. The sandbox capability model

Source: `core/data/.../azphalt/sandbox/{JsSandbox,WasmSandbox,AzphaltSandboxHost,SandboxExecution}.kt`.

Code (`kind: "code"` or `"mixed"`, `entry` + `runtime: "js"|"wasm"`) runs inside a
[Chicory](https://github.com/dylibso/chicory) WASM interpreter — pure-JVM, no native code execution.
`runtime: "wasm"` runs the guest module directly (`WasmSandbox`); `runtime: "js"` runs the guest's
JavaScript source inside a bundled `quickjs.wasm` module via Chicory (`JsSandbox`), so both runtimes
ultimately execute as WASM under the same interpreter and the same bounds below.

### 5.1 Deny-by-default posture

> "Each function represents an access-controlled capability. If an extension lacks a capability in
> its manifest, the corresponding functions are not mapped into the WASM environment at
> instantiation, causing it to fail immediately if it attempts to import them (deny by default)."
> (`AzphaltSandboxHost.kt`)

Concretely: `WasmSandbox.bindCapabilities(grantedCapabilities)` only adds a `HostFunction` import for
a capability's bridge functions **when that capability's wire name is present** in the manifest's
declared `capabilities` (matched against the *installed* extension's own `manifest.capabilities`,
converted to a `Set<String>` of wire values). A capability not declared is a capability whose host
functions are never linked into the guest's import table at all — not merely refused at call time.

Capabilities and the host functions each grants (from `WasmSandbox.bindCapabilities` and
`AzphaltSandboxHost`):

| Capability (wire) | Grants (host functions bound) |
|---|---|
| `canvas` | `requestRedraw()`, `canvasWidth()`, `canvasHeight()`, `canvasDpi()` |
| `layers` | `layerCount()` |
| `params` | `paramNumber(key)`, `paramBool(key)`, `paramString(key)` — reads declared extension parameters |
| `color` | `colorActive()` / `colorSetActive(rgba)` — the app's active RGBA color |
| `assets` | `assetRead(path)` — read a bundled asset file's bytes |
| `selection` | `selectionSize()`, `selectionRead()` — the current selection mask |
| `bitmap` | Declared on the manifest `Capability` enum; **no corresponding host functions are bound in `WasmSandbox`** — see note below. |
| `time` | See § 5.2 — handled specially, only via the WASI bridge in `JsSandbox`, not through `AzphaltSandboxHost` at all. |
| `audio` | Declared on the manifest `Capability` enum; **no corresponding host functions are bound in `WasmSandbox`**. |

An unrecognized capability string deserializes to `Capability.UNKNOWN` in the manifest parser — "the
host simply never grants what it doesn't understand (fail-safe: less privilege)."

**TODO: unconfirmed** — `bitmap` and `audio` are part of the `Capability` wire enum and are described
in `AzphaltSandboxHost`'s doc comment framing ("Each function represents an access-controlled
capability"), but no host-function binding for either exists in the current `WasmSandbox`/`JsSandbox`
source read for this document. Whether that is an intentional "declared for forward-compat, not yet
wired" gap or a documentation/implementation drift was not resolved from the code alone.

### 5.2 `time` — a special case (WASI clock/RNG denial)

`time` is not gated through `AzphaltSandboxHost` at all; it only exists as a `JsSandbox` concern,
because QuickJS's compiled WASI imports (`clock_time_get`, `clock_res_get`, `random_get`) are
**mandatory** imports the `quickjs.wasm` module needs just to instantiate — Chicory refuses to link a
module with an unsatisfied import. Simply omitting these functions when `time` isn't granted therefore
does not work; it makes *every* extension whose manifest doesn't request `time` fail to even start
(`UnlinkableException`), which the code comment calls "the exact opposite of 'denied the clock, still
runs.'"

Instead, denial is implemented by **substitution**:

- `time` granted → the real WASI implementations (`wasi.toHostFunctions()`) are linked, giving the
  guest the real wall clock and real system entropy.
- `time` **not** granted → the three time-sensitive WASI functions are linked to fixed, non-real
  stand-ins instead of being omitted:
  - `clock_time_get` / `clock_res_get` write a fixed timestamp (epoch `0`) / fixed resolution (`1`
    nanosecond) and return success (errno `0`).
  - `random_get` fills the guest's buffer with zero bytes rather than real entropy.

`WasmSandbox` (the raw-WASM runtime, not the JS-in-WASM one) uses no WASI layer at all — "No ambient
authority — the guest gets nothing beyond the capability-gated host functions bound in
[bindCapabilities], never filesystem/network/process access" — so this WASI-specific `time` handling
is unique to `JsSandbox`.

### 5.3 Memory bound

Both sandboxes cap guest linear memory at **`MAX_GUEST_MEMORY_PAGES = 4096`** WASM pages. A WASM page
is a fixed 64 KiB, so this is a **256 MiB** ceiling, applied via `Instance.Builder.withMemoryLimits`,
which "overrides BOTH initial and maximum when set" — so the guest module's own *declared initial*
page count is read from its memory section and preserved (only the *maximum* is actually being
capped down); a module declaring no memory section at all has nothing to cap.

For `JsSandbox` specifically, the reason for the cap is spelled out: "QuickJS allocates its own JS
heap out of this module's linear memory, so capping it here bounds how much memory a single
extension's JS can consume (256 MiB), regardless of what quickjs.wasm itself declares as its
maximum." The same 256 MiB ceiling applies to a raw WASM guest module in `WasmSandbox`.

### 5.4 Execution timeout

**`SANDBOX_EXECUTION_TIMEOUT_MS = 15_000` (15 seconds)** per invocation
(`JsSandbox.eval()` / `WasmSandbox.run()`), enforced by `runSandboxBounded` (`SandboxExecution.kt`):

> "Generous enough for legitimate heavy work (a filter over a large canvas), short enough that a
> runaway extension can't hang the app."

Mechanism: the guest call runs on a dedicated daemon `Thread` ("azphalt-sandbox-exec"), not on the
calling coroutine's own thread — deliberately, because "`dispatchers.io`'s pool reuses threads, and
interrupting one out from under a coroutine dispatcher risks corrupting unrelated work sharing that
pool." The caller `join()`s that worker thread for up to the timeout; if it's still alive afterward,
the worker is `interrupt()`ed. Chicory's interpreter checks
`Thread.currentThread().isInterrupted()` between guest bytecode steps and throws a clean
`ChicoryException("Thread interrupted")` when it sees the flag — "this is the runtime's own
supported cancellation mechanism, not a hack." A further 1-second grace `join()` follows the
interrupt, "for Chicory to actually unwind after the interrupt lands — the check happens between
bytecode steps, not instantly, and a host call the guest is blocked in (e.g. one of the capability
functions) needs to return first too." If the worker is still alive after the grace period, a
`TimeoutException` is thrown to the caller regardless.

Before this bound existed, per the code comment, an extension whose code never returned (an infinite
loop — one WASM instruction is enough) "pinned a thread at 100% forever, with no way to cancel it,"
because "a blocking, non-suspending call inside a coroutine can't be cancelled by cancelling the
coroutine."

### 5.5 Runtime selection and entry point

- `runtime: "wasm"` → `WasmSandbox`. If the module exports a conventional `"run"` function, it is
  invoked (bounded, § 5.4); a module relying solely on its WASM start-section side effects during
  instantiation (already executed by `Instance.builder().build()`) exports nothing under `"run"`, and
  that absence is a no-op, not a hard failure — "the azphalt spec doesn't mandate a 'run' export."
- `runtime: "js"` → `JsSandbox`, which evaluates the extension's JS source as a global-scope script
  inside the bundled `quickjs.wasm` (via `qjs_eval`, `JS_EVAL_TYPE_GLOBAL`). An uncaught JS exception
  is surfaced as a generic `RuntimeException("JavaScript execution failed inside QuickJS sandbox.")`
  — the actual JS error value is not currently propagated in detail.

`ExtensionRepository.executeCodeExtension(id, host)` is the entry point that resolves an installed
extension's `entry` file, builds the granted-capability set from `manifest.capabilities`, and
dispatches to the matching sandbox based on `manifest.runtime`; an unsupported/absent runtime value
is a silent no-op.

---

## 6. Path-traversal defense in depth

Two independent layers exist, at two different times:

1. **At install time** (`AzpInstaller.isUnsafePath`): every ZIP entry name is checked before
   unpacking — absolute paths, drive letters, and `..` segments are all rejected outright, and the
   resolved staging-directory write target is re-checked to still be inside the staging directory
   (`target.canonicalPath.startsWith(staging.canonicalPath + File.separator)`) as a second line of
   defense.
2. **At resolution time**, independently (`InstalledExtension.filePath(relative)`): manifest-declared
   relative paths (an asset's LUT path, a brush's stamp path, `entry` itself) are read back out and
   resolved against the extension's directory **long after install**, by callers unrelated to the
   installer. These strings were never checked by the install-time archive-entry-name defenses above
   — a signed-or-not manifest naming, e.g., `../../../../data/data/<pkg>/...` as an asset path bypassed
   every install-time check, because none of them touch this field. `filePath()` therefore
   canonicalizes both the extension root and the candidate path and refuses anything that resolves
   outside the root, returning `null` rather than a path a caller might use anyway.

---

## Open items (TODO: unconfirmed)

- **`bitmap` and `audio` capabilities**: declared in the `Capability` wire enum and framed generally
  by `AzphaltSandboxHost`'s doc comment, but no host-function bridge for either was found in
  `WasmSandbox`/`JsSandbox`. Not clear from the code alone whether this is deliberate ("declared,
  not yet wired") or a gap.
- **The normative `spec/extension-manifest.md`, `spec/pack.md`, `spec/companion-app.md`,
  `spec/mcp-server.md`, `spec/ui-schema.md`, and `spec/repository-api.md`** are referenced throughout
  the manifest/installer code as the authoritative sources for parts of the schema (the `app`/`mcp`
  blocks' internal shape, the `ui-schema.md` panel format, the registry's
  `.well-known/azphalt-repository.json` shape beyond its `signingKeys` array) but do not exist in this
  repository and were out of scope for this reconstruction pass.
- **JS exception detail**: `JsSandbox.eval()`'s exception path discards the actual QuickJS error
  value/message and throws a generic `RuntimeException` — whether the spec expects richer error
  propagation is not evidenced in this code.
