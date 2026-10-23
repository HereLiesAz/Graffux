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
 *     400/405/411/413/415        rejected before anything is written; 415 unless every line is a
 *                                valid session/stroke/hover record (the whole file is checked and
 *                                the committed bytes are re-encoded from what was checked)
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
/** A recorded session inflates roughly 5-10x; anything far past that is not a session. */
const MAX_INFLATED = 96 * 1024 * 1024;
const MAX_STRING = 256;
const BASE64 = /^[A-Za-z0-9+/]*={0,2}$/;
const RECORD_TYPES = new Set(["stroke", "hover"]);
const DEVICE = /^[A-Za-z0-9-]{1,48}$/;
const FILE = /^session-\d{10,16}\.jsonl\.gz$/;
const CONFLICT_RETRIES = 3;

const json = (status, body) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

/** Decompressed text of the whole gzip stream, or null if it isn't gzip or inflates past the cap. */
async function inflate(bytes) {
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip"));
  const reader = stream.getReader();
  const chunks = [];
  let size = 0;
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > MAX_INFLATED) return null;
      chunks.push(value);
    }
  } catch {
    return null;
  } finally {
    reader.cancel().catch(() => {});
  }
  const out = new Uint8Array(size);
  let at = 0;
  for (const c of chunks) {
    out.set(c, at);
    at += c.length;
  }
  return new TextDecoder("utf-8", { fatal: true }).decode(out);
}

/** True when every string in [value] is short, except base64 heatmap frames under `data`. */
function stringsAreData(value, key = "") {
  if (typeof value === "string") {
    if (key === "data") return BASE64.test(value);
    return value.length <= MAX_STRING;
  }
  if (Array.isArray(value)) return value.every((v) => stringsAreData(v, key));
  if (value && typeof value === "object") return Object.entries(value).every(([k, v]) => stringsAreData(v, k));
  return true;
}

/**
 * The validated session as JSON Lines, or null. Every line must parse: a `session` header first,
 * then only `stroke`/`hover` records (tools/stroke-model/SCHEMA.md), with no free-form text beyond
 * short labels. Checking only the first line let any payload ride along behind a valid header.
 */
export async function strokeDataText(bytes) {
  if (bytes.length < 2 || bytes[0] !== 0x1f || bytes[1] !== 0x8b) return null;
  const text = await inflate(bytes);
  if (text === null) return null;
  const lines = text.split("\n").filter((l) => l.length > 0);
  if (lines.length === 0) return null;
  for (let i = 0; i < lines.length; i++) {
    let record;
    try {
      record = JSON.parse(lines[i]);
    } catch {
      return null;
    }
    if (!record || typeof record !== "object" || Array.isArray(record)) return null;
    const ok = i === 0
      ? record.type === "session" && Number.isInteger(record.schema)
      : RECORD_TYPES.has(record.type);
    if (!ok || !stringsAreData(record)) return null;
  }
  return lines.join("\n") + "\n";
}

/** True when the file is a well-formed stroke-data session (see [strokeDataText]). */
export async function looksLikeStrokeData(bytes) {
  return (await strokeDataText(bytes)) !== null;
}

/** Re-encodes validated text, so only what was checked is ever committed. */
async function gzip(text) {
  const stream = new Blob([new TextEncoder().encode(text)]).stream().pipeThrough(new CompressionStream("gzip"));
  return new Uint8Array(await new Response(stream).arrayBuffer());
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
  const text = await strokeDataText(bytes);
  if (text === null) return json(415, { error: "not stroke data" });
  const stored = await gzip(text);

  const token = await githubToken(env);
  if (!token) return json(503, { error: "no github credential" });

  const res = await commit(env, token, `stroke-data/${device}/${file}`, stored, `stroke data: ${device}/${file} [skip ci]`, sleep);
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
