# azphalt store-app handoff — `spec/store-app.md`

This document reverse-engineers the azphalt "store-app" handoff spec from how it is actually
implemented and consumed in this repository: `AzphaltStoreHandoff`
(`core/data/.../azphalt/AzphaltStoreHandoff.kt`), `AzpInstaller`/`ExtensionRepository` (the
install/uninstall contract every acquisition path funnels through), `ExtensionStateProvider` /
`ExtensionStateStore` (the state-reporting side channel), `StoreWindow.kt` / `StoreChooserDialog.kt`
(the UI), and `MainActivity.kt` (the deep link + browse-for-result wiring), plus
`app/src/main/AndroidManifest.xml` (package-visibility `<queries>`, the exported provider, and the
`azphalt://install` intent filter).

Related normative document referenced throughout this code but out of scope here (and also missing
from this repo): `spec/state-reporting.md`, which fully specifies the inventory-document and
`ContentProvider` state-reporting channel this document summarizes only as far as the store handoff
needs it.

---

## 1. What this is: a host, not a marketplace

The governing idea, stated identically across several files in this codebase:

> "Graffux is a host, not a marketplace." (`StoreWindow.kt`, `StoreChooserDialog.kt`)
>
> "Rather than building its own browse/search catalog, a host launches a separate, installable store
> app to find and fetch a package, then verifies the bytes it gets back itself — the same
> [`AzpInstaller`] path any other install source goes through, since 'the store app saves the host
> work, never judgement' (spec § What this is not)." (`AzphaltStoreHandoff.kt`)

Concretely: Graffux implements **no** browsing, searching, or purchasing UI of its own for
extensions. `StoreWindow` — the in-app "Extensions" panel — only lists what is *already installed*
and lets the user uninstall something; its "Browse the Azphalt Store" button is the entire acquisition
surface, and it does nothing but hand off to one of two external destinations (§ 3). Every byte that
subsequently arrives, from whichever source, is verified by the exact same `AzpInstaller` path
described in `spec/package-format.md` — a store's metadata about a package is **advisory only** and
never substitutes for that verification (§ 4.2).

---

## 2. Discovery

Discovery is plain Android intent resolution, not a registry Graffux queries directly.

### 2.1 The browse action

```kotlin
const val ACTION_BROWSE: String = "store.azphalt.action.BROWSE"
```

`AzphaltStoreHandoff.isStoreAvailable(pm, appId)` asks `PackageManager` whether anything on the
device can handle `browseIntent(appId).resolveActivity(pm)`. Because `ACTION_BROWSE` is a **custom**
action, Android 11+ package visibility rules require it to be explicitly declared, or
`resolveActivity`/`startActivity` will silently fail to see an installed store app even when one is
present — implicit/standard actions get automatic visibility exemptions that a custom action does
not. `AndroidManifest.xml` therefore declares:

```xml
<queries>
    <intent>
        <action android:name="store.azphalt.action.BROWSE" />
    </intent>
    <!-- offering to *install* a store app resolves a market: intent -->
    <intent>
        <action android:name="android.intent.action.VIEW" />
        <data android:scheme="market" />
    </intent>
    <!-- and the web fallback, though https already has an automatic browser exemption -->
    <intent>
        <action android:name="android.intent.action.VIEW" />
        <data android:scheme="https" />
    </intent>
</queries>
```

### 2.2 Graceful degradation — no store app is not an error

> "A host with no store app installed is 'a host with no *browse* affordance, not a broken one' (spec
> § Discovery)." (`AzphaltStoreHandoff.kt`, `StoreChooserDialog.kt`)

The code comment on `MainActivity`'s browse launcher is explicit about the failure mode this
replaced: "the spec requires degrading gracefully here, not silently doing nothing or crashing on
`ActivityNotFoundException`." Concretely, when the user picks the Android route and no store app can
handle `ACTION_BROWSE`:

1. Try `AzphaltStoreHandoff.installStoreAppIntent()` — a `market://details?id=store.azphalt.storefront`
   intent, opening Play directly if present.
2. On failure, fall back to `AzphaltStoreHandoff.webStoreIntent()` — `https://azphalt.store` in a
   browser.
3. On failure of *that* too, show a warning explaining the device has no store app, no Play, and no
   browser, and that "Extensions can still be installed by hand from a file" via the in-app file
   picker.

> "Play first, by its `market:` scheme, which opens the Play app directly when it is present; the
> caller falls back to [`webStoreIntent`] when nothing resolves this, since a device without Play is
> exactly the device where a dead button would be most annoying." (`AzphaltStoreHandoff.kt`)

---

## 3. The two acquisition routes

`StoreChooserDialog` presents exactly two, described as "not interchangeable":

| Route | Mechanism | Round trip |
|---|---|---|
| **Android** | A real, installable store app handles `ACTION_BROWSE`, and hands verified bytes straight back to Graffux via an `Intent` result. | One round trip. |
| **Web** | `https://azphalt.store` opens in a browser. It "can show and sell anything in the catalogue," but getting a chosen package back into the app goes through the `azphalt://install` deep link (§ 5) rather than a direct handoff. | Two hops: browser → deep link → app. |

Only the web route is guaranteed to exist on every device (a browser). The dialog is shown rather
than silently picking one, because previously "silently given whichever happens to be present…
previously meant a toast saying 'no store app is installed' and no way forward at all" — i.e. asking
first is itself a fix for a prior dead-end.

`StoreChooserDialog`'s copy adapts to whether an Android store app is actually resolvable
(`storeAppInstalled`, from `isStoreAvailable`) — telling the user Android "will offer to get one"
when it isn't, rather than presenting a route that will just dead-end.

### 3.1 Constants (mirrors the reference storefront)

```kotlin
object AzphaltStoreHandoff {
    const val ACTION_BROWSE: String = "store.azphalt.action.BROWSE"
    const val WEB_STORE_URL: String = "https://azphalt.store"
    const val STORE_APP_ID: String = "store.azphalt.storefront"
    const val MIME: String = "application/vnd.azphalt.package"  // advisory hint on the returned content Uri
}
```

> "These constants mirror the reference storefront app's own `store.azphalt.storefront.Handoff`
> object (github.com/HereLiesAz/azphalt, `apps/storefront-cmp`) so the two sides agree on the wire
> contract without sharing code — any conforming store app, not just the reference one, understands
> them." (`AzphaltStoreHandoff.kt`)

`WEB_STORE_URL` — "the neutral marketplace on the web. One repository, not the only one — the
Repository API is self-hostable — but it is the one this host offers by name."

---

## 4. The browse request

`AzphaltStoreHandoff.browseIntent(appId, inventory)` builds the `Intent` sent with `ACTION_BROWSE`:

```kotlin
Intent(ACTION_BROWSE).apply {
    putExtra("app", appId)                      // this host's own applicationId
    putExtra("mediaDomains", arrayOf("image", "3d", "video"))
    putExtra("kinds", arrayOf("asset", "code", "mixed"))
    putExtra("compat", AZPHALT_SPEC_VERSION)     // "0.1"
    // inventory + authority — only when inventory is non-empty, see § 4.1/4.2
}
```

### 4.1 `mediaDomains` and `kinds` — self-declared capability filtering

`mediaDomains = ["image", "3d", "video"]` — "the media domains this host can actually use, so the
store never offers what Graffux structurally can't run — a pure audio or font pack doesn't match."

`kinds = ["asset", "code", "mixed"]` — "the package kinds this host does something with:
ASSET/MIXED contribute LUTs and brushes (`ExtensionRepository.installedLuts`/`installedBrushes`),
CODE/MIXED run in the sandbox (`ExtensionRepository.executeCodeExtension`). `app`/`mcp`/`pack` have
no consumer here yet." This filter is advisory to the store (it decides what to *offer*); it does not
prevent `AzpInstaller` from accepting other kinds if bytes for them arrive anyway (see
`spec/package-format.md` § 2.2 for the separate, stricter runtime-side rejection of `app`/`mcp`/`pack`
after install).

### 4.2 The inventory extra and the state-authority extra

```kotlin
private const val EXTRA_INVENTORY: String = "azphalt.extra.INVENTORY"       // JSON document, see below
private const val EXTRA_STATE_AUTHORITY: String = "azphalt.extra.STATE_AUTHORITY"
```

The **why**: "sending [the inventory] is what lets a store card read *Open* rather than *Get* on
something the user already has — the store cannot work that out on its own, because acquisition and
installation are separate events and only the host saw the second one." A store app has no way to
know, on its own, what Graffux has already installed or previously failed to install; the host must
tell it.

- `EXTRA_INVENTORY` is a **JSON string, not a `Parcelable`** — "the extra crosses an application
  boundary: a custom Parcelable needs a class on both sides, which would make the contract a shared
  library instead of a document." It carries `ExtensionInventory.document(entries)`: a
  `{"entries":[...]}` document of `ExtensionStateEntry { id, version, state, at, reason? }` rows,
  hard-capped at **256 KiB** and preferring at most **500 entries**, because an oversized `Intent`
  extra crossing Binder throws `TransactionTooLargeException` **in the caller** — a host that ignores
  this crashes itself trying to open a store. Entries are trimmed from the end (not refused outright)
  when the document doesn't fit, and each entry's free-text `reason` field is bounded first so a
  single long reason can't blow the budget and force the trim loop to discard every good entry down
  to nothing.
- It is only attached **when the resulting document is non-empty** after trimming — "a host with
  nothing installed says nothing rather than asserting an empty inventory," since an empty document
  is a *different claim* ("this host has nothing") than "no document at all," and the store would act
  on that claim.
- `EXTRA_STATE_AUTHORITY` — the authority (`<applicationId>.azphalt.state`) of this host's
  `ExtensionStateProvider` (§ 6) — is sent **whenever inventory is non-empty**, "regardless [of
  whether the inventory extra itself fit], because it is the answer to the case the inventory extra
  cannot cover: the provider has no size limit, so a host whose inventory did not fit is exactly the
  host a store most needs to come back and read." A conforming store **MUST NOT** query an authority
  it was not handed this way.

What "inventory" actually contains, per `ExtensionRepository.stateInventory()`: the recorded
state-transition log (`ExtensionStateStore`) **folded together with** whatever is currently unpacked
on disk that predates the state-recording feature — an install this host can see right now on disk is
folded in as `state: "active"` with `at: null` ("unknown: it was installed before anything was
recording") rather than requiring a migration write, because "a migration that writes on first launch
has a failure mode (a bad write leaves a permanently wrong record) that deriving does not." The
recorded log wins wherever both know a package id.

---

## 5. The `azphalt://install` deep link

This is how a package chosen on the **web** storefront actually reaches the app, since a browser
cannot hand back an `Intent` result the way the Android store route can.

```xml
<intent-filter android:autoVerify="false">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="azphalt" android:host="install" />
</intent-filter>
```

> "The web storefront redirects here to install a package the user chose in the browser (spec §
> Web acquisition). The URI carries the package download URL; MainActivity reads it and feeds the
> stream to `ExtensionRepository.installFromStream` the same way the Android store handoff does."
> (`AndroidManifest.xml`)

`MainActivity.azphaltInstallUrl(intent)` extracts the payload:

```kotlin
// azphalt://install?url=<https-download-url>
if (intent?.action != Intent.ACTION_VIEW) return null
val data = intent.data ?: return null
if (data.scheme != "azphalt" || data.host != "install") return null
return data.getQueryParameter("url")
```

i.e. the URI shape is `azphalt://install?url=<url-encoded HTTPS download URL for the .azp>`.

### 5.1 Required user confirmation

Because this filter is `exported`/`BROWSABLE`, **any** web page or installed app can launch Graffux
with an arbitrary `url` pointing at an unsigned package — a code comment in `MainActivity.kt` names
the risk directly:

> "Installing it unconditionally the moment the deep link lands was a drive-by-install primitive: the
> only signal the user ever got was a Toast *after* the extension was already on disk. Route it
> through a real confirmation instead, naming the URL so there's something to actually evaluate
> before it downloads and installs anything."

So the deep link **never** triggers an install directly. It only sets pending state
(`pendingAzphaltInstallUrl`), and a `ConfirmDialog` is shown before anything downloads:

```
Title:   "Install extension?"
Message: "Something wants Graffux to download and install an extension from:

          <url>

          Extensions can add code that runs inside Graffux. Only install one
          if you trust where this came from."
Confirm: "Install"  →  vm.installExtensionFromUrl(url)
Dismiss: →  discarded, nothing happens
```

Only on explicit confirmation does `EditorViewModel.installExtensionFromUrl(url)` open an
`HttpURLConnection` to `url` (15 s connect / 30 s read timeout) and feed the response stream into
`ExtensionRepository.installFromStream` — the identical verification path (§ 7) used for every other
install source. There is no `knownId`/`knownVersion` carried through this route (unlike the store
handoff, § 5.2/7.1), since the web deep link carries no store-provided metadata — only the raw URL.

---

## 6. State reporting's second channel: the exported `ContentProvider`

`ExtensionStateProvider` (`core/data/.../azphalt/ExtensionStateProvider.kt`) exists specifically for
the case the browse-intent inventory extra (§ 4.2) cannot cover: a user opening the store app
**directly**, not through Graffux's "Browse" button. In that case there is no browse request and
therefore no inventory extra travels anywhere — "every card would read 'Get' — including for
extensions already installed here." The provider "lets a store re-read the same states out of band."

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

Key properties, all directly asserted in the code:

- **Read-only, normatively.** `insert`/`update`/`delete` all throw
  `UnsupportedOperationException("azphalt extension state is read-only")` rather than failing
  quietly: "a store that believes it wrote something is worse than one that knows it cannot."
- **Gated by a `normal`-protection-level permission** — "any app can declare it and read with no user
  prompt." This is a deliberately low bar because of the next point:
- **Privacy**: "Nothing in a row identifies a device, a user, or an install." The exposed columns are
  exactly the five `ExtensionStateEntry` fields (`id`, `version`, `state`, `at`, `reason`), all
  `TEXT`, with `at` stored as the same ISO-8601 string the wire format uses (not millis), "so a row
  and a wire entry are the same thing." `selection`/`sortOrder` query params are deliberately ignored
  — the table is "tens of rows, not thousands," so client-side filtering costs nothing and a
  hand-rolled SQL-fragment parser on input from another app "would be a liability out of all
  proportion to what it buys."
- **`reason` sanitization**: `reason` is free text sourced from a caught exception's raw `.message` —
  which can leak a `content://` URI or an absolute file path (e.g. from a revoked document-permission
  grant). `ExtensionStateStore.sanitizeReason` strips anything matching a URI-or-absolute-path regex
  to `"[redacted]"` and truncates to 200 characters, applied "once, at the one place every reason
  actually gets written, rather than trusting every future caller to remember to" — specifically
  because the provider's privacy promise ("nothing in a row identifies a device, a user, or an
  install") would otherwise be false for exactly the failure case most likely to occur under
  delegated acquisition.
- **No `QUERY_ALL_PACKAGES`**: "this host reports its own extensions and nothing else, which is why
  no `QUERY_ALL_PACKAGES` permission appears anywhere in this feature."
- **Coordination**: the repository and the provider are two separate `ExtensionStateStore` instances
  over the same file — Android may construct the provider before the `Application` object exists, so
  it cannot share the repository's instance. The file lock is therefore keyed **per canonical file
  path**, not per instance, so "a store app querying the provider on a Binder thread" can't read the
  state file mid-write from an install happening on the repository's side.

Content path: `content://<applicationId>.azphalt.state/extensions` (`ExtensionStateProvider.PATH_EXTENSIONS`).

---

## 7. The install/uninstall contract (`AzpInstaller`)

The full verification/unpack contract is documented in `spec/package-format.md` § 1; this section
covers only what the **store handoff specifically** contributes to it.

### 7.1 The result — what a conforming store hands back

`AzphaltStoreHandoff.resultOf(intent)` reads a fixed set of result extras:

```kotlin
data class StoreResult(
    val id: String? = null,             // EXTRA_ID
    val version: String? = null,        // EXTRA_VERSION
    val integrity: String? = null,      // EXTRA_INTEGRITY — "sha256-…" over the unsigned package
    val signed: Boolean? = null,        // EXTRA_SIGNED — tri-state: absent extra → null, not false
    val signerKey: String? = null,      // EXTRA_SIGNER_KEY — base64 SPKI, when signed
    val entitlement: String? = null,    // EXTRA_ENTITLEMENT — registry-signed token for a paid package
    val reportToken: String? = null,    // EXTRA_REPORT_TOKEN — see below
)
```

Every field is documented as **advisory**:

> "The store saves the host work, never judgement, so none of this relaxes a check: the package is
> verified here exactly as a file the user picked by hand would be, and a store claiming
> `signed = true` over bytes whose signature does not verify simply gets refused." (`AzphaltStoreHandoff.kt`)

What the metadata is actually *for*: naming a package in a `FAILED` state report before its manifest
has even been parsed (when the bytes themselves can't yet say what they are), and carrying
`reportToken` — "the `azphalt-report-token` from the download that produced these bytes… it travels
with the package because the store *downloaded* and the host *installs*: the token authorises exactly
one install report, and only the host knows whether an install actually happened. Left unspent, the
repository's count simply stays honest." (Consuming `reportToken` — actually spending it against a
repository endpoint — is part of `spec/state-reporting.md` § 4.2, not evidenced further in this
codebase.)

`EXTRA_SIGNED` is read as a genuine tri-state, not a boolean defaulting to `false`: absence of the
extra means `signed = null` ("the store did not say"), distinct from the store explicitly asserting
`false` ("the store said no") — `resultOf` checks `intent.hasExtra(EXTRA_SIGNED)` before reading the
boolean.

### 7.2 Multiple packages — `ClipData`, not just `intent.data`

A single browse request may resolve to **several** chosen packages. The spec's answer, per
`AzphaltStoreHandoff.packageUris(intent)`'s doc comment, is **one `ClipData` item per package**:

> "A host that reads only `intent.data` installs the first selection and silently drops the rest — the
> user picks five brushes and gets one, with nothing to say the other four were discarded."

Read order: `ClipData` wins whenever present and non-empty (`clip.itemCount > 0`), regardless of
whether `intent.data` is also set — "a store that sets both is describing the same selection twice;
the `ClipData` is the one that can express more than one package, so it is the authority." Only when
`ClipData` is absent/empty does `intent.data` (the single-selection form) apply. No result at all
(`null` intent, or an intent with neither) yields an empty list, never a crash.

The single-package `StoreResult` metadata (`id`/`version`/etc.) is **only** attached to the install
call when exactly one package came back (`MainActivity`: `handoff.takeIf { packages.size == 1 }`) —
for a multi-selection, per-package id/version metadata would mislabel every item but one, so none of
it is attached and each package installs with no `knownId`/`knownVersion`.

### 7.3 What happens after the `Intent` result lands

`MainActivity`'s `storeBrowser` launcher (`ActivityResultContracts.StartActivityForResult`):

- `resultCode != RESULT_OK` (the user backed out of the store) → a clean no-op. "RESULT_CANCELED (the
  user backed out) is a clean no-op per the spec."
- `resultCode == RESULT_OK` → for every URI in `AzphaltStoreHandoff.packageUris(result.data)`, call
  `vm.installExtensionFromUri(uri, metadata)`.

`EditorViewModel.installExtensionFromUri(uri, fromStore)` then, per package:

1. If a store-provided `id` is known, record `DOWNLOADED` state **only after** the input stream is
   successfully opened — not before — because `downloaded` asserts "the host holds verified bytes";
   recording it before opening the stream would claim something that could be false if a lapsed URI
   grant throws on open.
2. Run `ExtensionRepository.installFromStream(stream, now, knownId, knownVersion)` — the *exact same*
   `AzpInstaller.install()` path (`spec/package-format.md` § 1) that a user-picked file from the
   document picker goes through. There is no separate "trusted because it came from a store" branch.
3. On success: `stateStore.record(id, version, ExtensionState.ACTIVE, now)` — Graffux has no
   enable/disable toggle, so "the honest state is `active`, not `installed`" the instant a package is
   unpacked.
4. On failure (any exception besides `CancellationException`): if the store told us an `id` ahead of
   time, record `ExtensionState.FAILED` with a `reason` (the exception message, sanitized before
   persistence — § 6) — "a failure is worth reporting precisely because it is a user who wanted
   something and did not get it." The version recorded falls back through
   `knownVersion ?: stateStore.stateOf(knownId)?.version ?: ""` rather than an empty string, "so the
   entry meaningful when the store told us nothing either."

### 7.4 Uninstall

`ExtensionRepository.uninstall(id)` deletes `filesDir/extensions/<id>/` recursively and rescans, then
records `ExtensionState.REMOVED` (not simply forgotten) — "distinct from never having had it, so a
store can offer a reinstall instead of a first purchase." `StoreWindow` requires an explicit
`ConfirmDialog` before calling it ("Uninstall extension? … This can't be undone."), listing what will
be lost: "its brushes, LUTs, filters."

---

## 8. What this is not (explicitly, from the code's own framing)

- Graffux does not implement browsing, search, ranking, or purchase flows for extensions. `StoreWindow`
  only shows what's already installed.
- A store's claims about a package (`StoreResult`'s `signed`/`integrity`/etc.) never substitute for
  this host's own verification — every package, from every source (file picker, Android store
  handoff, or web deep link), goes through the identical `AzpInstaller` check.
- The `ContentProvider` (§ 6) is one-directional: a store may read a host's extension states; it may
  never write them.
- A store app must not query the state-provider authority unless this host handed it out on a browse
  request (`EXTRA_STATE_AUTHORITY`).
- `mediaDomains`/`kinds` in the browse request are the host telling a store what it can use — they are
  not an install-time enforcement mechanism; `AzpInstaller` accepts any of the six known kinds
  regardless (`spec/package-format.md` § 2.2).

---

## Open items (TODO: unconfirmed)

- **`spec/state-reporting.md`** itself (the full inventory-document/`ContentProvider` spec, and in
  particular how `EXTRA_REPORT_TOKEN` is actually *spent* against a repository endpoint per its
  § 4.2) is referenced repeatedly but does not exist in this repository; this document only reproduces
  what the store-handoff code demonstrates about that channel, not the full state-reporting contract.
- **`spec/repository-api.md`** (referenced for "Media domains" and the registry's
  `.well-known/azphalt-repository.json` trust-bootstrap format) is likewise missing; only the
  `signingKeys` array consumed by `ExtensionRepository.refreshTrustStore` is evidenced here (see
  `spec/package-format.md` § 3.3).
- **Entitlement redemption**: `EXTRA_ENTITLEMENT` ("registry-signed entitlement token for a paid
  package") is read into `StoreResult.entitlement` but no code path in this repository does anything
  further with it — whether/how Graffux is meant to validate or redeem it is not evidenced.
- **The reference storefront app** (`github.com/HereLiesAz/azphalt`, `apps/storefront-cmp`) is cited
  as the canonical implementation the wire contract mirrors, but its source is outside this repository
  and was not consulted for this document.
