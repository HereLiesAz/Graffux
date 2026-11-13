/**
 * Graffux stroke-data relay: the `/v1/*` routes of the `graffux` Cloudflare Worker (the root
 * wrangler.toml points `main` here and sends only those paths to this code; the rest of the site is
 * served from ./docs). It stores one stroke-model training session
 * (a gzip JSON Lines file written by the app's StrokeDataRecorder, see tools/stroke-model/SCHEMA.md)
 * in a PRIVATE Cloudflare R2 bucket. Nothing is published: the bucket has no public access and the
 * Worker has no read route. The app only talks to this Worker, and only after the user has switched on
 * Settings -> "Contribute strokes to model training" and declared they are 16 or older.
 *
 *   PUT /v1/stroke-data/<device>/<file>      body: the .jsonl.gz bytes
 *     201 {"status":"stored"}    stored
 *     200 {"status":"exists"}    that object is already stored (retries are safe)
 *     400/405/411/413/415        rejected before anything is written; 400 also when the
 *                                X-Graffux-Age-Declaration: 16+ header is missing (older app
 *                                versions); 415 unless every line is a
 *                                valid session/stroke/hover record (the whole file is checked and
 *                                the stored bytes are re-encoded from what was checked)
 *     429                        rate limited (per client address); retry later
 *     503                        the bucket binding is missing or failed; retry later
 *
 * Env:
 *   STROKE_BUCKET (R2 binding, required)  private bucket; key `stroke-data/<device>/<file>`. Without it
 *                         uploads are refused with 503. Removal requests are honoured by deleting the
 *                         matching keys (see README, "Removal").
 *   RATE_LIMITER (binding, required)  Workers Rate Limiting binding; keyed by client address. Without
 *                         it, or if it throws, uploads are refused with 503 rather than served unlimited.
 */

export const MAX_BYTES = 8 * 1024 * 1024;
/**
 * A recorded session inflates roughly 5-10x; anything far past that is not a session. Kept well
 * under the Worker's 128 MB isolate: the inflated text, its lines and the re-encoded copy all live
 * at once.
 */
export const MAX_INFLATED = 24 * 1024 * 1024;
/** One stroke line: samples, sensors at 200 Hz and up to 384 KiB of heatmap (512 KiB as base64). */
export const MAX_LINE = 2 * 1024 * 1024;
const MAX_STRING = 256;
/**
 * Nesting the schema can need is about 5 (record > sensors > name > rows > values). Anything past this
 * is a crafted value built to exhaust the stack or the CPU, not a recording.
 */
export const MAX_DEPTH = 12;
/** The recorder rolls to a new file at 100 strokes (StrokeDataStore): a header plus that many, with headroom. */
export const MAX_LINES = 1000;
/** `heatmap.frames`: base64 of at most 384 KiB of int16 cells (SCHEMA.md § heatmap). */
export const MAX_FRAMES = 4 * Math.ceil((384 * 1024) / 3);
const KEY = /^[A-Za-z][A-Za-z0-9_]{0,47}$/;
const BASE64 = /^[A-Za-z0-9+/]*={0,2}$/;
const RECORD_TYPES = new Set(["stroke", "hover"]);
/** Top-level fields per record type (tools/stroke-model/SCHEMA.md and what StrokeDataRecorder writes). */
const SESSION_KEYS = new Set([
  "type", "schema", "manufacturer", "model", "sdk", "displayHz", "widthPx", "heightPx", "xdpi", "ydpi",
  "density", "startedAtMs", "appVersion", "heatmap", "clockCheckNs",
]);
const STROKE_KEYS = new Set([
  "type", "tool", "inputDevice", "cancelled", "multiTouch", "pointerId", "canceled", "palm", "samples",
  "pointers", "hover", "clock", "clockOffsetNs", "uptimeToElapsedNs", "context", "sensors",
  "sensorsRegistered", "sensorStatus", "flush", "heatmap", "heatmapStatus",
]);
export const DECLARATION_HEADER = "X-Graffux-Age-Declaration";
export const DECLARATION_VALUE = "16+";
const DEVICE = /^[A-Za-z0-9-]{1,48}$/;
const FILE = /^session-\d{10,16}\.jsonl\.gz$/;

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
  // Decoded chunk by chunk, so the inflated bytes are never copied into one more buffer.
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let text = "";
  try {
    for (let i = 0; i < chunks.length; i++) {
      text += decoder.decode(chunks[i], { stream: true });
      chunks[i] = null;
    }
    return text + decoder.decode();
  } catch {
    return null;
  }
}

/**
 * True when every key is a short identifier, every string a short label, and nothing nests deeper than
 * [MAX_DEPTH], except the base64 heatmap frames at `heatmap.frames` (capped at [MAX_FRAMES]). Walked with
 * an explicit stack: a recursive walk of a hostile, deeply nested value overflowed the call stack and
 * took the request down with an exception.
 */
function stringsAreData(root) {
  const stack = [{ value: root, key: null, parent: null, depth: 0 }];
  while (stack.length > 0) {
    const { value, key, parent, depth } = stack.pop();
    if (typeof value === "string") {
      const ok = key === "frames" && parent === "heatmap"
        ? value.length <= MAX_FRAMES && BASE64.test(value)
        : value.length <= MAX_STRING;
      if (!ok) return false;
    } else if (value && typeof value === "object") {
      if (depth >= MAX_DEPTH) return false;
      if (Array.isArray(value)) {
        for (const item of value) stack.push({ value: item, key, parent, depth: depth + 1 });
      } else {
        for (const [k, v] of Object.entries(value)) {
          if (!KEY.test(k)) return false;
          stack.push({ value: v, key: k, parent: key, depth: depth + 1 });
        }
      }
    }
  }
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
  if (lines.length === 0 || lines.length > MAX_LINES) return null;
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].length > MAX_LINE) return null;
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
    const allowed = i === 0 ? SESSION_KEYS : STROKE_KEYS;
    if (!ok || !Object.keys(record).every((k) => allowed.has(k)) || !stringsAreData(record)) return null;
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

/**
 * The request body, or null once it passes [MAX_BYTES]. Read chunk by chunk and cancelled at the cap, so
 * a Content-Length that understates the body cannot make the Worker buffer an arbitrary amount.
 */
async function readCapped(request) {
  if (!request.body) return new Uint8Array(0);
  const reader = request.body.getReader();
  const chunks = [];
  let size = 0;
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    size += value.length;
    if (size > MAX_BYTES) {
      reader.cancel().catch(() => {});
      return null;
    }
    chunks.push(value);
  }
  const out = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.length;
  }
  return out;
}

export async function handle(request, env) {
  const url = new URL(request.url);
  const match = /^\/v1\/stroke-data\/([^/]+)\/([^/]+)$/.exec(url.pathname);
  if (!match) return json(404, { error: "not found" });
  if (request.method !== "PUT") return json(405, { error: "PUT only" });

  const [, device, file] = match;
  if (!DEVICE.test(device) || !FILE.test(file)) return json(400, { error: "bad name" });

  // The app sends this only after the person declared they are 16 or older. It is a marker, not proof: a
  // hostile client can send it too. Its job is to stop versions of the app that predate the declaration
  // (which never send it) from having their uploads stored.
  if (request.headers.get(DECLARATION_HEADER) !== DECLARATION_VALUE) {
    return json(400, { error: "age declaration required" });
  }

  const declared = Number(request.headers.get("Content-Length"));
  if (!request.headers.has("Content-Length") || !Number.isFinite(declared)) return json(411, { error: "length required" });
  if (declared > MAX_BYTES) return json(413, { error: "too large" });

  // The limit is the relay's only abuse control, so a missing or failing limiter closes the relay
  // instead of silently running it unlimited.
  if (!env.RATE_LIMITER) return json(503, { error: "rate limiter unavailable" });
  try {
    const key = request.headers.get("CF-Connecting-IP") || "unknown";
    const { success } = await env.RATE_LIMITER.limit({ key });
    if (!success) return json(429, { error: "slow down" });
  } catch (error) {
    console.error("stroke relay: rate limiter failed", error);
    return json(503, { error: "rate limiter unavailable" });
  }

  const bytes = await readCapped(request);
  if (bytes === null || bytes.length === 0) return json(413, { error: "bad size" });
  const text = await strokeDataText(bytes);
  if (text === null) return json(415, { error: "not stroke data" });
  const stored = await gzip(text);

  if (!env.STROKE_BUCKET) return json(503, { error: "store unavailable" });
  try {
    // A conditional put with If-None-Match: * (R2 documents Headers as an onlyIf form) returns null when
    // the key already exists: a retry of the same key does not replace what is stored.
    const written = await env.STROKE_BUCKET.put(`stroke-data/${device}/${file}`, stored, {
      onlyIf: new Headers({ "If-None-Match": "*" }),
      httpMetadata: { contentType: "application/gzip" },
    });
    return written === null ? json(200, { status: "exists" }) : json(201, { status: "stored" });
  } catch (error) {
    console.error("stroke relay: store failed", error);
    return json(503, { error: "store unavailable" });
  }
}

export default {
  fetch: (request, env) => handle(request, env),
};
