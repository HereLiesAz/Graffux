/**
 * Graffux stroke-data relay: the `/v1/*` routes of the `graffux` Cloudflare Worker (the root
 * wrangler.toml points `main` here and sends only those paths to this code; the rest of the site is
 * served from ./docs). It commits one stroke-model training session
 * (a gzip JSON Lines file written by the app's StrokeDataRecorder, see tools/stroke-model/SCHEMA.md)
 * to the `stroke-data` branch of the repository. The GitHub token lives here as a secret, never in
 * the shipped APK. The app only talks to this Worker, and only after the user has switched on
 * Settings -> "Contribute strokes to model training".
 *
 *   PUT /v1/stroke-data/<device>/<file>      body: the .jsonl.gz bytes
 *     201 {"status":"stored"}    committed
 *     200 {"status":"exists"}    that file is already on the branch (retries are safe)
 *     400/405/411/413/415        rejected before anything is written
 *     429                        rate limited (per client address); retry later
 *     502/503                    GitHub refused or the branch is missing; retry later
 *
 * Env:
 *   GITHUB_TOKENS (service binding, preferred)  the central `workflows` Worker's RepositoryTokens
 *                         entrypoint; `graffuxStrokeDataToken()` returns a short-lived token for
 *                         Graffux, Contents: write only, so this Worker stores no GitHub secret
 *   GITHUB_TOKEN (secret, fallback)  fine-grained token, Contents: read & write on GH_REPO only;
 *                         used only when the binding is absent or fails
 *   GH_REPO     (var)     "HereLiesAz/Graffux"
 *   DATA_BRANCH (var)     "stroke-data"; must already exist. The Worker never creates it, so a
 *                         deleted branch shows up as 503s instead of being silently re-cut.
 *   RATE_LIMITER (binding, optional)  Workers Rate Limiting binding; keyed by client address.
 */

export const MAX_BYTES = 8 * 1024 * 1024;
const HEAD_BYTES = 64 * 1024;
const DEVICE = /^[A-Za-z0-9-]{1,48}$/;
const FILE = /^session-\d{10,16}\.jsonl\.gz$/;
const CONFLICT_RETRIES = 3;

const json = (status, body) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

/** The first line of the (first member of the) gzip stream, or null if it isn't gzip at all. */
async function firstLine(bytes) {
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip"));
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let text = "";
  let read = 0;
  try {
    while (read < HEAD_BYTES && !text.includes("\n")) {
      const { value, done } = await reader.read();
      if (done) break;
      read += value.length;
      text += decoder.decode(value, { stream: true });
    }
  } catch {
    return null;
  } finally {
    reader.cancel().catch(() => {});
  }
  return text.split("\n", 1)[0];
}

/** True when the file opens with a stroke-data `session` line. Nothing else is accepted. */
export async function looksLikeStrokeData(bytes) {
  if (bytes.length < 2 || bytes[0] !== 0x1f || bytes[1] !== 0x8b) return false;
  const line = await firstLine(bytes);
  if (!line) return false;
  try {
    const head = JSON.parse(line);
    return head && head.type === "session" && Number.isInteger(head.schema);
  } catch {
    return false;
  }
}

function toBase64(bytes) {
  let out = "";
  for (let i = 0; i < bytes.length; i += 0x8000) {
    out += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  }
  return btoa(out);
}

/** A short-lived token from the central Worker if bound, else the fallback secret, else null. */
async function githubToken(env) {
  if (env.GITHUB_TOKENS) {
    try {
      const { token } = await env.GITHUB_TOKENS.graffuxStrokeDataToken();
      if (token) return token;
    } catch (error) {
      console.error("stroke relay: could not get a token from the central Worker", error);
    }
  }
  return env.GITHUB_TOKEN || null;
}

async function commit(env, token, path, bytes, message, sleep) {
  const url = `https://api.github.com/repos/${env.GH_REPO}/contents/${path}`;
  const body = JSON.stringify({ message, content: toBase64(bytes), branch: env.DATA_BRANCH || "stroke-data" });
  let last;
  for (let attempt = 0; attempt <= CONFLICT_RETRIES; attempt++) {
    last = await fetch(url, {
      method: "PUT",
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: "application/vnd.github+json",
        "User-Agent": "graffux-stroke-relay",
        "Content-Type": "application/json",
      },
      body,
    });
    // Two uploads moving the branch at once: GitHub answers 409. Back off and try again.
    if (last.status !== 409) return last;
    await sleep(200 * (attempt + 1) + Math.floor(Math.random() * 200));
  }
  return last;
}

export async function handle(request, env, { sleep = (ms) => new Promise((r) => setTimeout(r, ms)) } = {}) {
  const url = new URL(request.url);
  const match = /^\/v1\/stroke-data\/([^/]+)\/([^/]+)$/.exec(url.pathname);
  if (!match) return json(404, { error: "not found" });
  if (request.method !== "PUT") return json(405, { error: "PUT only" });

  const [, device, file] = match;
  if (!DEVICE.test(device) || !FILE.test(file)) return json(400, { error: "bad name" });

  const declared = Number(request.headers.get("Content-Length"));
  if (!request.headers.has("Content-Length") || !Number.isFinite(declared)) return json(411, { error: "length required" });
  if (declared > MAX_BYTES) return json(413, { error: "too large" });

  if (env.RATE_LIMITER) {
    const key = request.headers.get("CF-Connecting-IP") || "unknown";
    const { success } = await env.RATE_LIMITER.limit({ key });
    if (!success) return json(429, { error: "slow down" });
  }

  const bytes = new Uint8Array(await request.arrayBuffer());
  if (bytes.length === 0 || bytes.length > MAX_BYTES) return json(413, { error: "bad size" });
  if (!(await looksLikeStrokeData(bytes))) return json(415, { error: "not stroke data" });

  const token = await githubToken(env);
  if (!token) return json(503, { error: "no github credential" });

  const res = await commit(env, token, `stroke-data/${device}/${file}`, bytes, `stroke data: ${device}/${file} [skip ci]`, sleep);
  if (res.status === 201 || res.status === 200) return json(201, { status: "stored" });
  const detail = await res.text().catch(() => "");
  // PUT without a sha on a path that already exists: the file is already there, which is success.
  if (res.status === 422 && /sha/i.test(detail)) return json(200, { status: "exists" });
  if (res.status === 404 || res.status === 422) return json(503, { error: "data branch unavailable" });
  return json(502, { error: `github ${res.status}` });
}

export default {
  fetch: (request, env) => handle(request, env),
};
