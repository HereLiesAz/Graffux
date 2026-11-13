import test from "node:test";
import assert from "node:assert/strict";
import { gzipSync } from "node:zlib";
import { handle, MAX_BYTES } from "../src/index.js";

const session = gzipSync(
  Buffer.from('{"type":"session","schema":4,"model":"Pixel 5"}\n{"type":"stroke"}\n'),
);
const allow = { limit: async () => ({ success: true }) };
/** An in-memory stand-in for the R2 bucket binding: records puts, honours onlyIf etagDoesNotMatch "*". */
function bucket({ fail = false } = {}) {
  const objects = new Map();
  const puts = [];
  return {
    objects, puts,
    async put(key, value, options) {
      if (fail) throw new Error("r2 down");
      puts.push({ key, options });
      if (options?.onlyIf?.etagDoesNotMatch === "*" && objects.has(key)) return null;
      objects.set(key, Buffer.from(value));
      return { key };
    },
  };
}
let store = bucket();
const base = { RATE_LIMITER: allow };
const env = new Proxy({}, { get: (_, k) => (k === "STROKE_BUCKET" ? store : base[k]), has: (_, k) => k === "STROKE_BUCKET" || k in base, ownKeys: () => ["STROKE_BUCKET", ...Object.keys(base)], getOwnPropertyDescriptor: (_, k) => ({ enumerable: true, configurable: true, value: k === "STROKE_BUCKET" ? store : base[k] }) });
const noSleep = undefined;

function put(path, body = session, headers = {}) {
  return new Request(`https://relay.test${path}`, {
    method: "PUT",
    body,
    headers: { "Content-Length": String(body.length), ...headers },
  });
}

/**
 * Resets the bucket and makes any outbound fetch fail the test: the relay must never call GitHub (or
 * anything else) any more. Returns the (always empty) call list so older assertions read the same.
 */
function github() {
  store = bucket();
  const calls = [];
  globalThis.fetch = async (url) => { calls.push(url); throw new Error(`unexpected fetch ${url}`); };
  return calls;
}

const OK = "/v1/stroke-data/Pixel-5/session-1790680692015.jsonl.gz";

test("a valid session is stored privately in the bucket, never sent to GitHub", async () => {
  const calls = github();
  const res = await handle(put(OK), env);
  assert.equal(res.status, 201);
  assert.deepEqual(await res.json(), { status: "stored" });
  assert.equal(calls.length, 0);
  assert.deepEqual([...store.objects.keys()], ["stroke-data/Pixel-5/session-1790680692015.jsonl.gz"]);
  assert.deepEqual(store.objects.get("stroke-data/Pixel-5/session-1790680692015.jsonl.gz"), session);
});

test("an object that is already stored is success, so retries are safe and never overwrite", async () => {
  github();
  assert.equal((await handle(put(OK), env)).status, 201);
  const again = await handle(put(OK), env);
  assert.equal(again.status, 200);
  assert.deepEqual(await again.json(), { status: "exists" });
  assert.equal(store.objects.size, 1);
});

test("a missing or failing bucket is a retryable 503, and nothing else is tried", async () => {
  const calls = github();
  const missing = { RATE_LIMITER: allow };
  assert.equal((await handle(put(OK), missing)).status, 503);
  store = bucket({ fail: true });
  assert.equal((await handle(put(OK), env)).status, 503);
  assert.equal(calls.length, 0);
});

test("the relay holds no GitHub path: no token, no public branch", async () => {
  const { readFileSync } = await import("node:fs");
  const src = readFileSync(new URL("../src/index.js", import.meta.url), "utf8");
  assert.doesNotMatch(src, /api\.github\.com|GITHUB_TOKEN|DATA_BRANCH|Authorization/);
});

test("only PUT on the exact path is served", async () => {
  github({ status: 201 });
  assert.equal((await handle(new Request("https://relay.test/v1/other", { method: "GET" }), env)).status, 404);
  assert.equal((await handle(new Request(`https://relay.test${OK}`, { method: "GET" }), env)).status, 405);
  assert.equal((await handle(put("/other"), env)).status, 404);
});

test("names that could escape the folder are rejected before anything is stored", async () => {
  const calls = github({ status: 201 });
  for (const path of [
    "/v1/stroke-data/..%2Fx/session-1790680692015.jsonl.gz",
    "/v1/stroke-data/Pixel.5/session-1790680692015.jsonl.gz",
    "/v1/stroke-data/Pixel-5/session-1.jsonl.gz",
    "/v1/stroke-data/Pixel-5/notes.txt",
    `/v1/stroke-data/${"a".repeat(49)}/session-1790680692015.jsonl.gz`,
  ]) {
    assert.equal((await handle(put(path), env)).status, 400, path);
  }
  assert.equal(calls.length, 0);
});

test("size is enforced from the declared length and from the body", async () => {
  const calls = github({ status: 201 });
  const noLength = new Request(`https://relay.test${OK}`, { method: "PUT", body: session });
  noLength.headers.delete("Content-Length");
  assert.equal((await handle(noLength, env)).status, 411);
  assert.equal((await handle(put(OK, session, { "Content-Length": String(MAX_BYTES + 1) }), env)).status, 413);
  assert.equal((await handle(put(OK, new Uint8Array(0)), env)).status, 413);
  assert.equal(calls.length, 0);
});

test("only gzip files that open with a session line are accepted", async () => {
  const calls = github({ status: 201 });
  assert.equal((await handle(put(OK, Buffer.from("plain text, not gzip")), env)).status, 415);
  assert.equal((await handle(put(OK, gzipSync(Buffer.from('{"hello":"world"}\n'))), env)).status, 415);
  assert.equal((await handle(put(OK, gzipSync(Buffer.from("not json\n"))), env)).status, 415);
  assert.equal((await handle(put(OK, gzipSync(Buffer.from('{"type":"session"}\n'))), env)).status, 415);
  assert.equal(calls.length, 0);
});

test("a rate-limited client is turned away before the body is read or anything is stored", async () => {
  const calls = github({ status: 201 });
  const keys = [];
  const limited = { limit: async ({ key }) => (keys.push(key), { success: false }) };
  const res = await handle(put(OK, session, { "CF-Connecting-IP": "203.0.113.9" }), { STROKE_BUCKET: store, RATE_LIMITER: limited });
  assert.equal(res.status, 429);
  assert.deepEqual(keys, ["203.0.113.9"]);
  assert.equal(calls.length, 0);
});

test("rejects a valid header followed by non-record lines", async () => {
  github({ status: 201 });
  const body = gzipSync(Buffer.from('{"type":"session","schema":4}\n<html>not data</html>\n'));
  const res = await handle(put("/v1/stroke-data/Pixel-5/session-1700000000000.jsonl.gz", body), env);
  assert.equal(res.status, 415);
});

test("rejects free-form text hidden in a record", async () => {
  github({ status: 201 });
  const long = "x".repeat(5000);
  const body = gzipSync(Buffer.from(`{"type":"session","schema":4}\n{"type":"stroke","note":"${long}"}\n`));
  const res = await handle(put("/v1/stroke-data/Pixel-5/session-1700000000000.jsonl.gz", body), env);
  assert.equal(res.status, 415);
});

test("rejects a second gzip member that smuggles a payload", async () => {
  github({ status: 201 });
  const body = Buffer.concat([session, gzipSync(Buffer.from("arbitrary file contents\n"))]);
  const res = await handle(put("/v1/stroke-data/Pixel-5/session-1700000000000.jsonl.gz", body), env);
  assert.equal(res.status, 415);
});

test("stores the re-encoded, validated text, not the uploaded bytes", async () => {
  github();
  const res = await handle(put("/v1/stroke-data/Pixel-5/session-1700000000000.jsonl.gz"), env);
  assert.equal(res.status, 201);
  const { gunzipSync } = await import("node:zlib");
  const saved = store.objects.get("stroke-data/Pixel-5/session-1700000000000.jsonl.gz");
  assert.equal(gunzipSync(saved).toString(), '{"type":"session","schema":4,"model":"Pixel 5"}\n{"type":"stroke"}\n');
});

const upload = (text) =>
  handle(put("/v1/stroke-data/Pixel-5/session-1700000000000.jsonl.gz", gzipSync(Buffer.from(text))), env);

test("accepts a heatmap's base64 frames, and only there", async () => {
  github({ status: 201 });
  const frames = Buffer.alloc(300 * 1024).toString("base64");
  const ok = `{"type":"session","schema":4}\n{"type":"stroke","heatmap":{"w":16,"h":32,"frames":"${frames}"}}\n`;
  assert.equal((await upload(ok)).status, 201);
  const elsewhere = `{"type":"session","schema":4}\n{"type":"stroke","context":{"data":"${frames}"}}\n`;
  assert.equal((await upload(elsewhere)).status, 415);
});

test("caps heatmap frames at the recorder's 384 KiB", async () => {
  github({ status: 201 });
  const frames = Buffer.alloc(400 * 1024).toString("base64");
  const body = `{"type":"session","schema":4}\n{"type":"stroke","heatmap":{"frames":"${frames}"}}\n`;
  assert.equal((await upload(body)).status, 415);
});

test("rejects unknown top-level fields and long or odd keys", async () => {
  github({ status: 201 });
  assert.equal((await upload('{"type":"session","schema":4}\n{"type":"stroke","payload":1}\n')).status, 415);
  assert.equal((await upload('{"type":"session","schema":4,"extra":1}\n{"type":"stroke"}\n')).status, 415);
  const longKey = "k".repeat(5000);
  assert.equal((await upload(`{"type":"session","schema":4}\n{"type":"stroke","context":{"${longKey}":1}}\n`)).status, 415);
  assert.equal((await upload('{"type":"session","schema":4}\n{"type":"stroke","context":{"a b":1}}\n')).status, 415);
});

test("rejects a session that inflates past the cap", async () => {
  github({ status: 201 });
  const filler = '{"type":"stroke","samples":{"x":[' + "0,".repeat(1000) + "0]}}\n";
  const big = '{"type":"session","schema":4}\n' + filler.repeat(Math.ceil((25 * 1024 * 1024) / filler.length));
  assert.equal((await upload(big)).status, 415);
});

// --- depth, line count, streaming size cap and limiter failure (audit package 5) ---

test("a record nested far deeper than the schema is refused, never a crash", async () => {
  github({ status: 201 });
  const depth = 200_000;
  const deep = "[".repeat(depth) + "]".repeat(depth);
  const res = await upload(`{"type":"session","schema":4}\n{"type":"stroke","context":{"a":${deep}}}\n`);
  assert.equal(res.status, 415);
  const objects = '{"a":'.repeat(50_000) + "1" + "}".repeat(50_000);
  assert.equal((await upload(`{"type":"session","schema":4}\n{"type":"stroke","context":${objects}}\n`)).status, 415);
});

test("ordinary nesting still passes", async () => {
  github({ status: 201 });
  const ok = '{"type":"session","schema":4}\n{"type":"stroke","sensors":{"acc":{"v":[[1,2,3],[4,5,6]]}}}\n';
  assert.equal((await upload(ok)).status, 201);
});

test("a file with more records than the recorder ever writes is refused", async () => {
  github({ status: 201 });
  const many = '{"type":"session","schema":4}\n' + '{"type":"stroke"}\n'.repeat(5000);
  assert.equal((await upload(many)).status, 415);
});

test("the body is read with a cap: a lying Content-Length cannot make the Worker buffer it all", async () => {
  github({ status: 201 });
  let pulled = 0;
  const chunk = new Uint8Array(1024 * 1024);
  const body = new ReadableStream({
    pull(controller) {
      pulled += chunk.length;
      if (pulled > 64 * 1024 * 1024) controller.close();
      else controller.enqueue(chunk);
    },
  });
  const req = new Request(`https://relay.test${OK}`, {
    method: "PUT",
    body,
    duplex: "half",
    headers: { "Content-Length": "1000" },
  });
  const res = await handle(req, env);
  assert.equal(res.status, 413);
  assert.ok(pulled <= MAX_BYTES + 4 * chunk.length, `read ${pulled} bytes`);
});

test("without a working rate limiter the relay refuses rather than running unlimited", async () => {
  const calls = github({ status: 201 });
  assert.equal((await handle(put(OK), { STROKE_BUCKET: store }, noSleep)).status, 503);
  const throwing = { limit: async () => { throw new Error("limiter down"); } };
  assert.equal((await handle(put(OK), { STROKE_BUCKET: store, RATE_LIMITER: throwing }, noSleep)).status, 503);
  assert.equal(calls.length, 0);
});
