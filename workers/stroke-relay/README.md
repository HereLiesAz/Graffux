# Stroke relay

The `/v1/*` routes of the `graffux` Cloudflare Worker. It takes stroke-model training sessions from the
app and commits them to the `stroke-data` branch. It exists so the app carries **no GitHub
credential**: the token is a secret of this Worker.

The repository's root `wrangler.toml` sets `main` to `src/index.js` here and `run_worker_first =
["/v1/*"]`, so only those paths run this code; everything else is the static site in `./docs` and never
invokes the Worker.

~~~
app  --PUT /v1/stroke-data/<device>/<file>-->  graffux Worker  --Contents API-->  stroke-data branch
~~~

The app only sends after the user switches on Settings → "Contribute strokes to model training"
(off by default). What the files contain is in
[`tools/stroke-model/SCHEMA.md`](../../tools/stroke-model/SCHEMA.md); the privacy policy is
[`docs/PRIVACY_POLICY.md`](../../docs/PRIVACY_POLICY.md) § 4. The branch is public.

## What the Worker accepts

| Check | Result |
|---|---|
| anything but `PUT /v1/stroke-data/<device>/<file>` | 404 / 405 |
| `<device>` not `[A-Za-z0-9-]{1,48}`, or `<file>` not `session-<digits>.jsonl.gz` | 400 |
| no `Content-Length` | 411 |
| over 8 MiB, or empty | 413 |
| not gzip, or the first line is not a `{"type":"session","schema":<n>}` line | 415 |
| more than 30 requests a minute from one address (per Cloudflare location) | 429 |
| GitHub conflict while two uploads move the branch | retried 3 times, then 502 |
| the file is already on the branch | 200 `exists` (retries are safe) |
| the branch is missing | 503. The Worker never creates it, so a deleted branch is visible. |
| stored | 201 `stored` |

Files land at `stroke-data/<device>/<file>` with the commit message `stroke data: <device>/<file> [skip ci]`. The `[skip ci]` matters: the `stroke-data` branch was cut from `main` and still carries an old workflow that builds on every push, so without it each upload would start a full app build.

This is spam and abuse *limiting*, not authentication: nothing in an open-source app can prove a
request came from the app. What keeps the branch clean is content, not identity: the whole file is
inflated (capped at 96 MB), every line must be a `session`, `stroke` or `hover` record with no
free-form strings over 256 characters (base64 heatmap `data` aside), and the committed bytes are
re-encoded from the checked text, so nothing rides along in a second gzip member or after a valid
header. Uploads are limited to 6 a minute per client address. If abuse still shows up, the next
step is Play Integrity attestation.

## Deploy

It ships with the `graffux` Worker, so it deploys when that Worker does (Workers Builds on merge to
`main`, or `npx wrangler deploy` from the repository root). One-time setup:

1. Merge the central `workflows` change that adds `RepositoryTokens.graffuxStrokeDataToken()`
   (HereLiesAz/workflows#153) and let it deploy. The root `wrangler.toml` binds it to this Worker as
   `GITHUB_TOKENS`; on each upload the relay asks it for a short-lived token for Graffux, Contents
   write only. **No GitHub secret is stored here, and there is nothing to rotate.** The central GitHub
   App must be installed on `HereLiesAz/Graffux` with Contents write (it already writes trackers
   there).
2. Optional fallback: a `GITHUB_TOKEN` secret (fine-grained, Graffux only, Contents read and write) on
   the `graffux` Worker is used only if the binding is missing or fails. Leave it unset once the
   binding works, so there is no long-lived credential. If it exists, the Worker can do anything it can.
3. Make sure Workers Builds has an empty Build command (see the root `wrangler.toml`), or the Worker
   does not deploy at all.
4. Make sure `/v1/stroke-data/*` is reachable without logging in. If Cloudflare Access protects the
   Worker's `workers.dev` address, every request is redirected to the Access login page and no upload
   can succeed (the app treats that as a failure and keeps the file). Either turn Access off for the
   Worker (Settings → Domains & Routes) or add an Access application for the path with a Bypass policy.
   Check with `curl -i https://graffux.hereliesaz.workers.dev/v1/stroke-data/x/y`: a `404`/`400` JSON
   body means the Worker answered; a `302` to `cloudflareaccess.com` means Access is in the way.

`GH_REPO` and `DATA_BRANCH` are plain variables in the root `wrangler.toml`; the Rate Limiting
binding (`namespace_id` 2001) is there too. The app's `StrokeRelayClient.DEFAULT_URL` is
`https://graffux.hereliesaz.workers.dev`.

Check it with the Worker's Logs tab (or `npx wrangler tail`) while uploading from the app.

The Workers free plan allows 10 ms of CPU per request. Base64-encoding a multi-megabyte session can
exceed that, in which case Cloudflare answers 1102 and the app keeps the file and retries; the paid
plan has no such limit.

### Protect the branch

In Graffux: Settings → Rules → Rulesets → new branch ruleset, target `stroke-data`, tick
**Restrict deletions** and **Block force pushes**. Do not require pull requests; the Worker pushes
directly.

## Test

~~~
cd workers/stroke-relay
npm test
~~~

Node 22. No dependencies; GitHub is replaced by a fake `fetch`.
