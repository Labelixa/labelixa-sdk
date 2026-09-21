// Node SDK smoke test against the REAL application (tests/test_sdk_node.py
// starts a uvicorn process and points this script at it). No mocked
// responses: if the API contract drifts, this breaks — the npm package
// cannot rot silently.
import { createServer } from "node:http";
import assert from "node:assert/strict";
import { Client, LabelixaError, QuotaExceeded } from "../index.js";

const baseUrl = process.argv[2];
assert.ok(baseUrl, "usage: node smoke.mjs http://127.0.0.1:PORT");

const ZPL = "^XA^FO20,20^A0N,30^FDSDK^FS^XZ";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// Anonymous rate limit is 3/s — requests are deliberately spaced.
const GAP = 450;

const c = new Client({ baseUrl });

// PNG
const png = await c.renderPng(ZPL, { widthIn: 2, heightIn: 1 });
assert.deepEqual([...png.slice(0, 4)], [0x89, 0x50, 0x4e, 0x47], "no PNG signature");
await sleep(GAP);

// PDF (single page)
const pdf = await c.renderPdf(ZPL, { widthIn: 2, heightIn: 1, index: 0 });
assert.equal(new TextDecoder().decode(pdf.slice(0, 4)), "%PDF");
await sleep(GAP);

// Diagnostics: unknown command shows up in the structured report
const report = await c.validate("^XA^QQ^FDx^XZ");
assert.ok(report.diagnostics.some((d) => d.code === "ZPL1001"), "ZPL1001 missing");
await sleep(GAP);

// The label size REALLY reaches the server: with an invalid size the
// server answers 400 only if the parameter names are the ones it reads.
try {
  await c.validate(ZPL, { widthIn: 999, heightIn: 999 });
  assert.fail("a 999 inch label should return 400 — size not sent");
} catch (e) {
  assert.ok(e instanceof LabelixaError, `expected LabelixaError: ${e}`);
  assert.equal(e.status, 400);
}
await sleep(GAP);

// EPL2 translation
const epl = await c.toEpl(ZPL, { widthIn: 2, heightIn: 1 });
assert.ok(epl.includes("N") && epl.includes("P1"), "unexpected EPL body");
await sleep(GAP);

// Errors CARRY the server message (400: invalid density)
try {
  await c.renderPng(ZPL, { dpmm: 7 });
  assert.fail("dpmm=7 should throw");
} catch (e) {
  assert.ok(e instanceof LabelixaError, `expected LabelixaError, got: ${e}`);
  assert.ok(!(e instanceof QuotaExceeded));
  assert.equal(e.status, 400);
  assert.ok(e.serverMessage.length > 0, "empty server text");
}

// 429 -> QuotaExceeded (Retry-After and X-Quota-Action are carried).
// This single case uses a fake server: producing a real 429 would mean
// deliberately exhausting the quota.
const fake = createServer((req, res) => {
  res.writeHead(429, { "Retry-After": "3600", "X-Quota-Action": "upgrade" });
  res.end("Monthly quota exhausted");
});
await new Promise((r) => fake.listen(0, "127.0.0.1", r));
const fakeBaseUrl = `http://127.0.0.1:${fake.address().port}`;
try {
  await new Client({ baseUrl: fakeBaseUrl }).renderPng(ZPL);
  assert.fail("429 should throw QuotaExceeded");
} catch (e) {
  assert.ok(e instanceof QuotaExceeded, `expected QuotaExceeded, got: ${e}`);
  assert.equal(e.retryAfter, 3600);
  assert.equal(e.action, "upgrade");
  assert.equal(e.serverMessage, "Monthly quota exhausted");
} finally {
  fake.close();
}

// The API key is sent as a header
const seen = {};
const fakeFetch = async (url, opts) => {
  seen.key = opts.headers["X-API-Key"];
  return new Response(new Uint8Array([0x89, 0x50, 0x4e, 0x47]), { status: 200 });
};
await new Client({ apiKey: "lbx_test", baseUrl: "http://x",
                   fetch: fakeFetch }).renderPng(ZPL);
assert.equal(seen.key, "lbx_test");


// ---- 0.2.0 ----------------------------------------------------------------
await sleep(1100);                       // fresh per-second bucket

// Barcode: SVG text; an error image (200 + X-Warnings) counts as an ERROR.
const svg = await c.barcode("HELLO-123", { type: "code128" });
assert.ok(svg.includes("<svg"), "no SVG returned");
await sleep(GAP);
await assert.rejects(
  () => c.barcode("12", { type: "ean13", format: "png" }),
  (e) => e instanceof LabelixaError && /not generated/.test(e.message),
  "error image passed through as a barcode");
await sleep(1100);

// Language detection (the flag is enabled by the test driver)
const lang = await c.languageDetect("SIZE 4,6\nCLS\nPRINT 1\n");
assert.ok(JSON.stringify(lang).toLowerCase().includes("tspl"), "tspl not detected");
await sleep(GAP);

// Compatibility: an unknown model surfaces as the server's 404
await assert.rejects(
  () => c.compatibility(ZPL, "no-such-model"),
  (e) => e instanceof LabelixaError && e.status === 404,
  "unknown model did not return 404");

console.log("smoke ok: 12 scenarios passed");
