# azphalt state reporting — `spec/state-reporting.md`

Spec version implemented by this host: **`0.1`** (`AZPHALT_SPEC_VERSION`, shared with
`spec/package-format.md` and `spec/store-app.md` — confirmed the same constant is reused for the
`EXTRA_COMPAT` extra this document describes in § 4).

This document reverse-engineers the install/uninstall state-reporting channel — the five-state
vocabulary, the state document's wire shape, the read-only `ContentProvider` a store app queries,
and the size-bounded inventory a host attaches to a browse request — strictly from what
`core/common/.../azphalt/ExtensionState.kt`, `core/data/.../azphalt/ExtensionStateProvider.kt`,
`core/data/.../azphalt/ExtensionStateStore.kt`, and the relevant parts of
`core/data/.../azphalt/AzphaltStoreHandoff.kt` actually implement and enforce. It does not invent
fields, constraints, or defaults beyond what the code demonstrates.

Both `spec/package-format.md` and `spec/store-app.md` already reference this document; this fills
that gap. Related normative documents this codebase references but which are still out of scope
here (and still missing from the repo): `spec/extension-manifest.md`, `spec/pack.md`,
`spec/companion-app.md`, `spec/mcp-server.md`, `spec/ui-schema.md`, `spec/repository-api.md` — see
`ARCHITECTURE.md`'s "Known documentation gaps" section.

---

## 1. Why this exists

Before this channel, a store app had no way to know what a host already had. `StoreWindow`'s own
history: "a store showed 'Get' on everything the user already had," because extension acquisition
is delegated (`spec/store-app.md` § 1) — the store never sees the host's install directory, only
what the host chooses to tell it. This document specifies that telling.

State reporting is deliberately **host-initiated and read-only from the store's side**: a store
never writes to the host's state, it only reads a snapshot the host publishes (§ 3) or receives
inline on a browse request (§ 4).

---

## 2. The five-state vocabulary

`ExtensionState` (`core/common/.../azphalt/ExtensionState.kt`), an enum with a `wire: String` value
distinct from its Kotlin name — the wire value is the normative one, the Kotlin name is not:

| State | Wire value | Meaning |
|---|---|---|
| `DOWNLOADED` | `"downloaded"` | Verified bytes are held; the install has not happened yet, or is waiting on something. |
| `INSTALLED` | `"installed"` | Installed and present, but not enabled. |
| `ACTIVE` | `"active"` | Installed and enabled — available to the user right now. |
| `FAILED` | `"failed"` | Acquisition or installation did not complete. Carries an optional `reason`. |
| `REMOVED` | `"removed"` | Distinct from never having had it, so a store can offer a reinstall. |

`DOWNLOADED` and `FAILED` exist as separate states, rather than being collapsed into "not
installed," specifically because acquisition and installation are separate events under delegated
acquisition (§ 1) — a package can be verified and held without ever being installed, or fail at
either step. Update availability is **deliberately not a state** here: whether a newer version
exists is the store's/repository's own concern, not something this host-side channel reports.

A wire value this build doesn't recognize does not sink the whole document — `parsedState`
resolves to `null` for it rather than throwing, so a state added by a newer spec version degrades
to "present but unparsed" instead of breaking every other entry in the same document.

---

## 3. The state document

`ExtensionStateEntry`:

```kotlin
@Serializable
data class ExtensionStateEntry(
    val id: String,
    val version: String,
    val state: String,   // one of § 2's wire values
    val at: String? = null,      // ISO-8601, not epoch millis
    val reason: String? = null,  // set only when state == "failed"; sanitized, see § 3.1
)
```

`at` is specified as an ISO-8601 string rather than a millisecond epoch on purpose: the wire format
and the provider's own column type are the same thing (§ 4), so there is exactly one timestamp
representation to reason about, not a serialize-side and a query-side one that could drift.

The document itself is a flat wrapper: `ExtensionStateDocument(entries: List<ExtensionStateEntry> =
emptyList())`, serialized as `{"entries": [...]}`.

### 3.1 Failure-reason sanitization

`reason` is bounded and scrubbed before it is ever persisted, in `ExtensionStateStore.record()`,
applied only when `state == FAILED`:

- Truncated to `MAX_REASON_LENGTH = 200` characters.
- Passed through `sanitizeReason()`, which replaces anything matching a URI-or-path pattern
  (`[a-zA-Z][a-zA-Z0-9+.-]*://\S+|/[\w.\-/]{3,}`) with `"[redacted]"`.

This exists because `reason` is populated from caught-exception messages, and an exported
`ContentProvider` (§ 4) is exactly the kind of surface that turns an incidental `content://` URI or
filesystem path inside an exception message into an information leak to anything on-device that
holds the read permission.

---

## 4. The `ExtensionStateProvider`

A read-only, exported `ContentProvider` a store app queries directly, independent of any browse
request being in flight.

**Manifest declaration** (`app/src/main/AndroidManifest.xml`):

```xml
<provider
    android:name="com.hereliesaz.graffitixr.data.azphalt.ExtensionStateProvider"
    android:authorities="${applicationId}.azphalt.state"
    android:exported="true"
    android:readPermission="azphalt.permission.READ_EXTENSION_STATE"
    android:grantUriPermissions="false" />

<permission
    android:name="azphalt.permission.READ_EXTENSION_STATE"
    android:protectionLevel="normal" />
```

`protectionLevel="normal"` — this is a low-sensitivity, install-time-granted permission, not a
runtime-prompted one, matching the fact that what it gates (§ 2's state vocabulary plus a scrubbed
failure reason) contains no package contents, no signing material, and no unsanitized paths.

**Contract**:

- Authority: `"${applicationId}.azphalt.state"` (helper: `ExtensionStateProvider.authority(applicationId)`).
- Content URI: `content://<authority>/extensions` (path segment `"extensions"`).
- Columns, all `TEXT`: `id`, `version`, `state`, `at`, `reason` — one row per `ExtensionStateEntry`,
  backed by a `MatrixCursor`.
- `selection`/`sortOrder` query parameters are accepted but **ignored** — the provider always
  returns every entry in its own order; a caller that needs filtering does it client-side.
- `insert`/`update`/`delete` all throw `UnsupportedOperationException("azphalt extension state is
  read-only")`. There is no mutation path through this provider, by design (§ 1).
- No `QUERY_ALL_PACKAGES` permission is declared or needed anywhere in this feature — the provider
  answers about this host's own extensions only, not the device's installed-package list.

---

## 5. Inline reporting on a browse request

Separately from the pull-based provider (§ 4), a host can push its current state inline when it
launches the store's browse intent (`spec/store-app.md` § 2.1), via two extras on the
`ACTION_BROWSE` intent:

- `EXTRA_COMPAT` — the host's `AZPHALT_SPEC_VERSION` (`"0.1"`), the same constant
  `spec/package-format.md` and `spec/store-app.md` both key off of.
- `EXTRA_INVENTORY` — the current `ExtensionStateDocument`, serialized, attached **only when
  non-empty after trimming** (§ 5.1). `EXTRA_STATE_AUTHORITY` (this provider's authority, § 4) is
  attached whenever the pre-trim inventory was non-empty, independent of whether the trimmed
  document made it into `EXTRA_INVENTORY` — so a store that receives an empty/absent inventory
  extra still knows where to pull the full picture from (§ 4).

### 5.1 The 256 KiB Binder budget

Android's Binder transaction buffer is a shared, small (historically ~1 MiB, host-wide) resource;
an `Intent` extra that is too large throws `TransactionTooLargeException` in the caller, not the
receiver. `ExtensionState` bounds the inventory it will attach well under that ceiling:

- `MAX_BYTES = 256 * 1024` (256 KiB) — measured as UTF-8 byte length, not character count.
- `MAX_ENTRIES = 500`.
- Each entry's own `reason` is bounded first (`MAX_REASON = 512` characters, via `.bounded()`) —
  specifically so one oversized `reason` can't consume the whole trim budget before the entry-count
  trim below ever runs.
- If the serialized document still exceeds `MAX_BYTES`, entries are dropped from the end in
  batches of `(kept.size / 10).coerceAtLeast(1)` per iteration, re-measuring after each batch,
  until it fits or the list is empty.

This is a **best-effort trim**, not a hard failure: nothing in this path throws on an oversized
document; it silently reports less than everything rather than not reporting at all. A store that
needs the complete, untrimmed picture uses the provider (§ 4) instead.

---

## 6. The report token

`EXTRA_REPORT_TOKEN` — minted by the store from its own download step, carried back on the
browse-result `Intent`, and exposed as `StoreResult.reportToken`. Per the code's own description:
"Authorises exactly one install report, and only the host knows whether an install actually
happened. Left unspent, the repository's count simply stays honest" — i.e. a store's own
install-count telemetry is meant to increment only when the host confirms an install actually
succeeded, using this token as the one-time authorization to do so.

**Open item, not resolved by this document**: nothing in this repository currently spends the
token. `EditorViewModel.installExtensionFromUri` reads `StoreResult.id`/`.version` from the same
result but never references `reportToken`, and no code anywhere calls out to a repository endpoint
with it. Whether that's an intentionally-deferred half of the feature, or a gap, is a product
question — noted in `ARCHITECTURE.md`'s "Known documentation gaps," not resolved here.

---

## 7. Multi-package browse results

A browse can return more than one package. `AzphaltStoreHandoff.packageUris(intent)` resolves the
full selection: `ClipData` wins whenever present and `itemCount > 0`; otherwise it falls back to
`intent.data` for a single-selection result. This replaced an earlier bug where only `intent.data`
was read, silently installing the first item of a multi-package selection and dropping the rest —
along with every other documented extra alongside it, including the report token (§ 6). Each
resolved URI is installed independently, one `installExtensionFromUri` call per package; per-package
metadata from the store (name, size, etc.) is attached only when the selection resolves to exactly
one package, per `spec/store-app.md` § 7.3.
