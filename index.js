/**
 * Labelixa Node.js SDK — thin client for the Labelixa REST API.
 *
 * Design notes (deliberate, shared with the Python SDK):
 * - THIN wrapper: every method maps 1:1 to a documented REST endpoint;
 *   no client-side magic, no hidden retries that would mask quota
 *   signals. https://labelixa.com/docs/api is the source of truth.
 * - Errors carry the server's own message; quota exhaustion is a
 *   distinct class (QuotaExceeded) with retryAfter seconds and the
 *   server's action hint.
 * - Zero dependencies: built on the global fetch (Node 18+).
 */

import { createRequire } from "node:module";

const DEFAULT_BASE_URL = "https://api.labelixa.com";
// Single source of truth for the version: package.json.
const VERSION = createRequire(import.meta.url)("./package.json").version;

export class LabelixaError extends Error {
  /**
   * @param {number} status HTTP status code
   * @param {string} serverMessage the server's own error text (verbatim)
   */
  constructor(status, serverMessage) {
    super(`HTTP ${status}: ${serverMessage}`);
    this.name = "LabelixaError";
    this.status = status;
    this.serverMessage = serverMessage;
  }
}

export class QuotaExceeded extends LabelixaError {
  /**
   * Separate class on purpose: a client that cannot tell 429 from a
   * generic error either never retries or retries immediately — both
   * wrong.
   */
  constructor(status, serverMessage, retryAfter, action) {
    super(status, serverMessage);
    this.name = "QuotaExceeded";
    this.retryAfter = retryAfter;
    this.action = action;
  }
}

/** Builds the error for a non-200 response; quota responses get their own class. */
async function errorFromResponse(response) {
  const text = (await response.text()).slice(0, 500);
  if (response.status === 402 || response.status === 429) {
    const raw = response.headers.get("Retry-After");
    const retryAfter = Number.parseInt(raw ?? "60", 10);
    return new QuotaExceeded(response.status, text,
                             Number.isNaN(retryAfter) ? 60 : retryAfter,
                             response.headers.get("X-Quota-Action"));
  }
  return new LabelixaError(response.status, text);
}

/** g-format like Python's :g — 4.0 -> "4", 2.25 -> "2.25". */
const g = (n) => String(Number(n));

export class Client {
  /**
   * @param {object} [opts]
   * @param {string} [opts.apiKey] lbx_ API key; omit for anonymous use
   * @param {string} [opts.baseUrl]
   * @param {typeof fetch} [opts.fetch] test hook; normal use omits it
   */
  constructor({ apiKey, baseUrl = DEFAULT_BASE_URL, fetch: fetchImpl } = {}) {
    this._baseUrl = baseUrl.replace(/\/+$/, "");
    this._fetch = fetchImpl ?? globalThis.fetch;
    this._headers = { "User-Agent": `labelixa-node/${VERSION}` };
    if (apiKey) this._headers["X-API-Key"] = apiKey;
  }

  async _post(path, body, extraHeaders = {}) {
    return this._fetch(this._baseUrl + path, {
      method: "POST",
      headers: { ...this._headers, "Content-Type": "text/plain",
                 ...extraHeaders },
      body,
    });
  }

  /** Renders a single label to PNG. @returns {Promise<Uint8Array>} */
  async renderPng(zpl, { dpmm = 8, widthIn = 4, heightIn = 6, index = 0,
                         rotation = 0 } = {}) {
    const extra = rotation ? { "X-Rotation": String(rotation) } : {};
    const res = await this._post(
      `/v1/printers/${dpmm}dpmm/labels/${g(widthIn)}x${g(heightIn)}/${index}`,
      zpl, extra);
    if (res.status !== 200) throw await errorFromResponse(res);
    return new Uint8Array(await res.arrayBuffer());
  }

  /**
   * Renders PDF; index=null includes ALL labels in the stream.
   * Note: the all-labels PDF costs one quota unit PER LABEL (a single
   * page costs 1) — server rule, the SDK does not soften it.
   * @returns {Promise<Uint8Array>}
   */
  async renderPdf(zpl, { dpmm = 8, widthIn = 4, heightIn = 6,
                         index = null } = {}) {
    const suffix = index === null ? "" : String(index);
    const res = await this._post(
      `/v1/printers/${dpmm}dpmm/labels/${g(widthIn)}x${g(heightIn)}/${suffix}`,
      zpl, { Accept: "application/pdf" });
    if (res.status !== 200) throw await errorFromResponse(res);
    return new Uint8Array(await res.arrayBuffer());
  }

  /** Lints ZPL; returns the structured diagnostics report. */
  async validate(zpl, { dpmm = 8, widthIn = 4, heightIn = 6 } = {}) {
    // The endpoint reads the label size from the `w`/`h` query
    // parameters (https://labelixa.com/docs/api). Unknown parameters are
    // ignored silently by the server, so any other spelling would make
    // every check run against the 4x6 default without an error.
    const q = `?dpmm=${dpmm}&w=${g(widthIn)}&h=${g(heightIn)}`;
    const res = await this._post(`/v1/diagnostics${q}`, zpl);
    if (res.status !== 200) throw await errorFromResponse(res);
    return JSON.parse(await res.text());
  }

  /** ZPL -> EPL2 translation; untranslatable fields surface in warnings. */
  async toEpl(zpl, { dpmm = 8, widthIn = 4, heightIn = 6 } = {}) {
    const res = await this._post(
      `/v1/printers/${dpmm}dpmm/labels/${g(widthIn)}x${g(heightIn)}/`,
      zpl, { Accept: "application/epl" });
    if (res.status !== 200) throw await errorFromResponse(res);
    return res.text();
  }

  /**
   * Generates a standalone barcode. format="svg" returns a string,
   * format="png" returns a Uint8Array.
   *
   * Server contract: invalid input is NOT a 4xx — the server returns
   * 200 with an error image plus an `X-Warnings` header. Passing that
   * through as a "generated barcode" would be lying to the caller, so
   * it throws a LabelixaError carrying the server's warning verbatim.
   * @returns {Promise<string|Uint8Array>}
   */
  async barcode(data, { type = "code128", format = "svg" } = {}) {
    const q = new URLSearchParams({ type, data, format });
    const res = await this._fetch(`${this._baseUrl}/v1/barcodes?${q}`, {
      method: "GET", headers: this._headers,
    });
    if (res.status !== 200) throw await errorFromResponse(res);
    const warning = res.headers.get("X-Warnings");
    if (warning) throw new LabelixaError(res.status,
      `Barcode not generated: ${warning}`);
    if (format === "png") return new Uint8Array(await res.arrayBuffer());
    return res.text();
  }

  /**
   * Detects the printer language of raw label code (ZPL/EPL/TSPL/CPCL).
   * Heuristic: the result carries a confidence TIER (high/medium/low),
   * not a probability — the server does not compute one and neither
   * does the SDK pretend to.
   */
  async languageDetect(code) {
    const res = await this._post("/v1/language-detect", code);
    if (res.status !== 200) throw await errorFromResponse(res);
    return JSON.parse(await res.text());
  }

  /**
   * Compatibility RISK analysis of ZPL against a printer model given as
   * manufacturer/model (e.g. "zebra/zd421" — the server reads
   * `Manufacturer/Model`, case-insensitive; "zebra-zd421" is NOT a valid
   * key and returns 404). Not an emulator and never says "it works";
   * an unknown model surfaces as the server's own 404.
   */
  async compatibility(zpl, model) {
    const res = await this._post(
      "/v1/compatibility?" + new URLSearchParams({ model }), zpl);
    if (res.status !== 200) throw await errorFromResponse(res);
    return JSON.parse(await res.text());
  }
}

export default Client;
