import test from "node:test";
import assert from "node:assert/strict";
import { gzipSync } from "node:zlib";
import { handle, MAX_BYTES } from "../src/index.js";

const session = gzipSync(
  Buffer.from('{"type":"session","schema":4,"model":"Pixel 5"}\n{"type":"stroke"}\n'),
);
const env = { GITHUB_TOKEN: "t", GH_REPO: "o/r", DATA_BRANCH: "stroke-data" };
const noSleep = { sleep: async () => {} };

function put(path, body = session, headers = {}) {
  return new Request(`https://relay.test${path}`, {
    method: "PUT",
    body,
    headers: { "Content-Length": String(body.length), ...headers },
  });
}

/** Replaces global fetch with a queue of canned GitHub responses; returns the recorded calls. */
function github(...responses) {
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url, init });
    const next = responses.length > 1 ? responses.shift() : responses[0];
    return new Response(next.body ?? "{}", { status: next.status });
  };
  return calls;
}

const OK = "/v1/stroke-data/Pixel-5/session-1790680692015.jsonl.gz";

test("a valid session is committed to the data branch", async () => {
  const calls = github({ status: 201 });
  const res = await handle(put(OK), env, noSleep);
  assert.equal(res.status, 201);
  assert.deepEqual(await res.json(), { status: "stored" });
  assert.equal(calls.length, 1);
  assert.equal(
    calls[0].url,
    "https://api.github.com/repos/o/r/contents/stroke-data/Pixel-5/session-1790680692015.jsonl.gz",
  );
  const sent = JSON.parse(calls[0].init.body);
  assert.equal(sent.branch, "stroke-data");
  assert.equal(sent.message, "stroke data: Pixel-5/session-1790680692015.jsonl.gz [skip ci]");
  assert.deepEqual(Buffer.from(sent.content, "base64"), session);
  assert.equal(calls[0].init.headers.Authorization, "Bearer t");
});

test("a file already on the branch is success, so retries are safe", async () => {
  github({ status: 422, body: '{"message":"Invalid request.\\n\\n\\"sha\\" wasn\'t supplied."}' });
  const res = await handle(put(OK), env, noSleep);
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { status: "exists" });
});

test("a branch conflict is retried, then succeeds", async () => {
  const calls = github({ status: 409 }, { status: 409 }, { status: 201 });
  const res = await handle(put(OK), env, noSleep);
  assert.equal(res.status, 201);
  assert.equal(calls.length, 3);
});

test("a conflict that never clears is a retryable 502", async () => {
  const calls = github({ status: 409 });
  const res = await handle(put(OK), env, noSleep);
  assert.equal(res.status, 502);
  assert.equal(calls.length, 4);
});

test("a missing data branch is reported, never re-created", async () => {
  const calls = github({ status: 404, body: '{"message":"Branch stroke-data not found"}' });
  const res = await handle(put(OK), env, noSleep);
  assert.equal(res.status, 503);
  assert.equal(calls.length, 1);
});

test("only PUT on the exact path is served", async () => {
  github({ status: 201 });
  assert.equal((await handle(new Request("https://relay.test/v1/other", { method: "GET" }), env)).status, 404);
  assert.equal((await handle(new Request(`https://relay.test${OK}`, { method: "GET" }), env)).status, 405);
  assert.equal((await handle(put("/other"), env)).status, 404);
});

test("names that could escape the folder are rejected before GitHub is called", async () => {
  const calls = github({ status: 201 });
  for (const path of [
    "/v1/stroke-data/..%2Fx/session-1790680692015.jsonl.gz",
    "/v1/stroke-data/Pixel.5/session-1790680692015.jsonl.gz",
    "/v1/stroke-data/Pixel-5/session-1.jsonl.gz",
    "/v1/stroke-data/Pixel-5/notes.txt",
    `/v1/stroke-data/${"a".repeat(49)}/session-1790680692015.jsonl.gz`,
  ]) {
    assert.equal((await handle(put(path), env, noSleep)).status, 400, path);
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

test("a rate-limited client is turned away before the body is read or GitHub is called", async () => {
  const calls = github({ status: 201 });
  const keys = [];
  const limited = { limit: async ({ key }) => (keys.push(key), { success: false }) };
  const res = await handle(put(OK, session, { "CF-Connecting-IP": "203.0.113.9" }), { ...env, RATE_LIMITER: limited });
  assert.equal(res.status, 429);
  assert.deepEqual(keys, ["203.0.113.9"]);
  assert.equal(calls.length, 0);
});

test("a short-lived token from the central Worker is used in preference to the secret", async () => {
  const calls = github({ status: 201 });
  let minted = 0;
  const bound = { graffuxStrokeDataToken: async () => (minted++, { token: "minted", expiresAt: "2026-10-20T02:00:00Z" }) };
  const res = await handle(put(OK), { ...env, GITHUB_TOKENS: bound }, noSleep);
  assert.equal(res.status, 201);
  assert.equal(minted, 1);
  assert.equal(calls[0].init.headers.Authorization, "Bearer minted");
});

test("if the central Worker fails, the fallback secret is used", async () => {
  const calls = github({ status: 201 });
  const broken = { graffuxStrokeDataToken: async () => { throw new Error("service unavailable"); } };
  const res = await handle(put(OK), { ...env, GITHUB_TOKENS: broken }, noSleep);
  assert.equal(res.status, 201);
  assert.equal(calls[0].init.headers.Authorization, "Bearer t");
});

test("with no credential at all the upload is refused without calling GitHub", async () => {
  const calls = github({ status: 201 });
  const broken = { graffuxStrokeDataToken: async () => { throw new Error("service unavailable"); } };
  const res = await handle(put(OK), { GH_REPO: "o/r", GITHUB_TOKENS: broken }, noSleep);
  assert.equal(res.status, 503);
  assert.equal(calls.length, 0);
});

test("a token is only requested for an upload that passed validation", async () => {
  github({ status: 201 });
  let minted = 0;
  const bound = { graffuxStrokeDataToken: async () => (minted++, { token: "minted" }) };
  await handle(put(OK, Buffer.from("not gzip")), { ...env, GITHUB_TOKENS: bound }, noSleep);
  await handle(put("/v1/stroke-data/Pixel.5/session-1790680692015.jsonl.gz"), { ...env, GITHUB_TOKENS: bound }, noSleep);
  assert.equal(minted, 0);
});

test("rejects a valid header followed by non-record lines", async () => {
  github({ status: 201 });
  const body = gzipSync(Buffer.from('{"type":"session","schema":4}\n<html>not data</html>\n'));
  const res = await handle(put("/v1/stroke-data/Pixel-5/session-1700000000000.jsonl.gz", body), env, noSleep);
  assert.equal(res.status, 415);
});

test("rejects free-form text hidden in a record", async () => {
  github({ status: 201 });
  const long = "x".repeat(5000);
  const body = gzipSync(Buffer.from(`{"type":"session","schema":4}\n{"type":"stroke","note":"${long}"}\n`));
  const res = await handle(put("/v1/stroke-data/Pixel-5/session-1700000000000.jsonl.gz", body), env, noSleep);
  assert.equal(res.status, 415);
});

test("rejects a second gzip member that smuggles a payload", async () => {
  github({ status: 201 });
  const body = Buffer.concat([session, gzipSync(Buffer.from("arbitrary file contents\n"))]);
  const res = await handle(put("/v1/stroke-data/Pixel-5/session-1700000000000.jsonl.gz", body), env, noSleep);
  assert.equal(res.status, 415);
});

test("commits the re-encoded, validated text, not the uploaded bytes", async () => {
  const calls = github({ status: 201 });
  const res = await handle(put("/v1/stroke-data/Pixel-5/session-1700000000000.jsonl.gz"), env, noSleep);
  assert.equal(res.status, 201);
  const { gunzipSync } = await import("node:zlib");
  const sent = JSON.parse(calls[0].init.body).content;
  const text = gunzipSync(Buffer.from(sent, "base64")).toString();
  assert.equal(text, '{"type":"session","schema":4,"model":"Pixel 5"}\n{"type":"stroke"}\n');
});

const upload = (text) =>
  handle(put("/v1/stroke-data/Pixel-5/session-1700000000000.jsonl.gz", gzipSync(Buffer.from(text))), env, noSleep);

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
