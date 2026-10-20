# Stroke relay

A Cloudflare Worker that takes stroke-model training sessions from the app and commits them to the
`stroke-data` branch. It exists so the app carries **no GitHub credential**: the token is a secret of
this Worker.

~~~
app  --PUT /v1/stroke-data/<device>/<file>-->  graffux-stroke-relay  --Contents API-->  stroke-data branch
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

Files land at `stroke-data/<device>/<file>` with the commit message `stroke data: <device>/<file>`.

This is spam and abuse *limiting*, not authentication: nothing in an open-source app can prove a
request came from the app. If abuse shows up, the next step is Play Integrity attestation.

## Deploy

1. Create a fine-grained GitHub token for **HereLiesAz/Graffux only** with **Contents: read and write**
   and nothing else.
2. From this directory:

   ~~~
   npx wrangler secret put GH_TOKEN      # paste the token
   npx wrangler deploy
   ~~~

   The app's `StrokeRelayClient.DEFAULT_URL` is `https://graffux-stroke-relay.hereliesaz.workers.dev`;
   change it there if the Worker is deployed under another name.
3. Check it: `npx wrangler tail`, then upload from the app (Settings → Upload stroke data now).

`GH_REPO` and `DATA_BRANCH` are plain variables in `wrangler.toml`. Rate limiting uses the Workers
Rate Limiting binding (`namespace_id` 2001); pick another id if that one is taken in the account.

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
