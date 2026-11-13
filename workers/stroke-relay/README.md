# Stroke relay

The `/v1/*` routes of the `graffux` Cloudflare Worker. It takes stroke-model training sessions from the
app and stores them in a **private** Cloudflare R2 bucket (binding `STROKE_BUCKET`). Nothing is
published and the Worker has no read route. The app carries no secret of any kind.

The repository's root `wrangler.toml` sets `main` to `src/index.js` here and `run_worker_first =
["/v1/*"]`, so only those paths run this code; everything else is the static site in `./docs` and never
invokes the Worker.

~~~
app  --PUT /v1/stroke-data/<device>/<file>-->  graffux Worker  --R2 put-->  private bucket graffux-stroke-data
~~~

The app only sends after the user declares they are 16 or older and switches on Settings → "Contribute
strokes to model training" (off by default). What the files contain is in
[`tools/stroke-model/SCHEMA.md`](../../tools/stroke-model/SCHEMA.md); the privacy policy is
[`docs/PRIVACY_POLICY.md`](../../docs/PRIVACY_POLICY.md) § 4. Earlier versions wrote to a public `stroke-data` branch; this one never does (see "Retiring the public branch").

What leaves the device, and what stays: the relay upload is the only automatic, background path for stroke training data.
Stroke programs a project records for replay are a different thing: `strokes.json` is saved into the project folder
(`ProjectRepositoryImpl.saveArtifact`, under `projects/<id>`), so a project export (`ProjectManager.writeProjectArchive` zips the
whole folder) and a device-to-device transfer carry it, but only when you do those. Graffux's co-op emitter is a no-op (`app/.../di/CoopModule.kt`), so no strokes go to other devices. Before
upload, session files sit in the app's private `stroke-data` directory, which Android backup and device
transfer exclude (`app/src/main/res/xml/data_extraction_rules.xml`, `backup_rules.xml`); a file is deleted
once the relay has it, or when the relay rejects it for good (`StrokeDataUploader.kt`). The object key carries the device model and the session start time. The policy wording is in
`docs/PRIVACY_POLICY.md` § 4.

## What the Worker accepts

| Check | Result |
|---|---|
| anything but `PUT /v1/stroke-data/<device>/<file>` | 404 / 405 |
| `<device>` not `[A-Za-z0-9-]{1,48}`, or `<file>` not `session-<10 to 16 digits>.jsonl.gz` | 400 |
| no `Content-Length` | 411 |
| over 8 MiB, or empty (counted while reading, not trusted from `Content-Length`) | 413 |
| not gzip, the first line is not a `{"type":"session","schema":<n>}` line, more than 1000 lines, or a value nested more than 12 deep | 415 |
| more than 6 requests a minute from one address (per Cloudflare location) | 429 |
| the rate limiter binding is missing or throws | 503. The relay closes instead of running unlimited. |
| no `X-Graffux-Age-Declaration: 16+` header | 400 (app versions older than the declaration) |
| the object already exists | 200 `exists` (a conditional put, `If-None-Match: *`, that does not replace it) |
| the `STROKE_BUCKET` binding is missing or the put throws | 503 |
| stored | 201 `stored` |

Objects are stored at the key `stroke-data/<device>/<file>`.

**The `<device>` path segment is chosen by the client, so a hostile client can pre-claim keys (the
conditional put never replaces an existing key) or fill the bucket with well-formed sessions under any
device name. That cannot be fixed without authenticating callers.** The `X-Graffux-Age-Declaration: 16+`
header the app sends is likewise a marker that stops older app versions from being stored, not proof of
anything: a hostile client can send it.

**This is spam and abuse *limiting*, not authentication. The relay does not authenticate callers.** Nothing in an open-source app can prove a
request came from the app. What keeps the store clean is content, not identity: the whole file is
inflated (capped at 24 MB, each line at 2 MB), every line must be a `session`, `stroke` or `hover`
record whose top-level fields are the ones in SCHEMA.md, every key is a short identifier (at most 48
characters), no string is over 256 characters except the base64 `heatmap.frames` (at most the
recorder's 384 KiB per stroke), and the committed bytes are
re-encoded from the checked text, so nothing rides along in a second gzip member or after a valid
header. Uploads are limited to 6 a minute per client address. There is no per-client credential: a secret shipped in the app is readable by anyone, so it would only look like authentication.
If abuse still shows up, the next step is Play Integrity attestation, which needs a Google Cloud project and a server-side verification key and cannot be added from this repository alone.

## Deploy

It ships with the `graffux` Worker, so it deploys when that Worker does (Workers Builds on merge to
`main`, or `npx wrangler deploy` from the repository root). One-time setup, **before** the deploy:

1. Create the bucket: `npx wrangler r2 bucket create graffux-stroke-data`. Leave it private: no custom
   domain, and r2.dev public access off. Then uncomment the `[[r2_buckets]]` block in the root
   `wrangler.toml` and merge. It is commented out on purpose: that file builds the whole `graffux` Worker
   (website included), and a binding to a missing bucket fails every Workers Build. Until it is on, the
   relay answers 503 and the app keeps its files.
2. Make sure Workers Builds has an empty Build command (see the root `wrangler.toml`).
3. Make sure `/v1/stroke-data/*` is reachable without logging in (Cloudflare Access on the Worker's
   `workers.dev` address redirects every request to a login page and no upload can succeed).
   Check with `curl -i https://graffux.hereliesaz.workers.dev/v1/stroke-data/x/y`: a `404`/`400` JSON
   body means the Worker answered.
4. Nothing GitHub-related is needed any more: the `GITHUB_TOKENS` service binding, the `GITHUB_TOKEN`
   secret, `GH_REPO` and `DATA_BRANCH` are gone. Delete a `GITHUB_TOKEN` secret if one was set; the
   central `RepositoryTokens.graffuxStrokeDataToken()` grant in HereLiesAz/workflows can be retired.
5. The Kaggle mirror (`.github/workflows/stroke-data-kaggle.yml`, centrally maintained) reads the old
   branch. Until the central workflow reads the bucket instead, the private dataset gets no new data.

The Workers free plan allows 10 ms of CPU per request; the app keeps the file and retries on a failure.

### Removal requests

List the objects in the dashboard (R2, graffux-stroke-data, prefix `stroke-data/<device-model>/`; the
number in `session-<ms>.jsonl.gz` is the start time in epoch milliseconds) or with any S3 client. Delete
each matching key: `npx wrangler r2 object delete graffux-stroke-data/stroke-data/<device>/<file>`. Then
remove the same sessions from the private Kaggle dataset (publish a new version without them and delete
older versions that contain them).

### Retiring the public branch (owner action, not done by code)

Existing data on the public `stroke-data` branch stays readable, in its history and in any fork or
clone, until you act. Steps: (1) if you want to keep it for training, copy it to the private bucket
first (`git worktree add ../sd origin/stroke-data`, then upload `../sd/stroke-data/**` under the same
keys); (2) delete the branch: `git push origin --delete stroke-data`; (3) delete any ruleset that
protects it (Settings, Rules, Rulesets); (4) check Actions caches and releases for copies; (5) commits
of a deleted branch can stay reachable by SHA for a while: if that matters, ask GitHub Support to purge
cached views and run garbage collection. Copies already cloned or forked cannot be recalled.

## Test

~~~
cd workers/stroke-relay
npm test
~~~

Node 22. No dependencies; GitHub is replaced by a fake `fetch`.
