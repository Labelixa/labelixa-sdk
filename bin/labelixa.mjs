#!/usr/bin/env node
/**
 * Labelixa CLI — a THIN shell over the SDK, built for CI pipelines.
 *
 * Design decisions:
 * - Every subcommand maps 1:1 to an SDK method; the CLI adds no second
 *   HTTP layer. The only retry is the visible one on 429 (Retry-After
 *   honored, at most 2 attempts, never silently).
 * - The API key is read ONLY from the environment (LABELIXA_API_KEY). It
 *   is not accepted as an argument: `ps` and shell history leak
 *   arguments, and convenience does not make up for that.
 * - LABELIXA_API_URL overrides the base URL (tests and on-prem setups).
 * - Exit codes are script-friendly and stable:
 *     0  success / no finding at or above --fail-on
 *     1  findings at or above --fail-on (validate) or a file failed
 *     2  usage error (unknown option, no matching file, bad value)
 *     3  API or network error (HTTP 4xx/5xx other than findings, 429
 *        after retries, connection refused)
 * - `--json` prints one machine-readable document on stdout and nothing
 *   else there; human messages go to stderr.
 * - Glob patterns are expanded by the CLI itself (`**`, `*`, `?`), so the
 *   same command works in CI shells that do not expand them (Windows
 *   runners, quoted arguments).
 * - Every request carries `X-Client: cli/<version>` so usage is
 *   attributed to the CLI, never to a person.
 */
import { readFileSync, writeFileSync, mkdirSync, readdirSync, statSync } from "node:fs";
import { basename, dirname, extname, join, resolve, sep } from "node:path";
import { Client, LabelixaError, LANGUAGES, QuotaExceeded } from "../index.js";

const PKG = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf-8"));
const VERSION = PKG.version;
const DEFAULT_API = "https://api.labelixa.com";
const EXIT = { OK: 0, FINDINGS: 1, USAGE: 2, API: 3 };
const SEVERITY_RANK = { none: 0, info: 1, warning: 2, error: 3 };
const MAX_RETRIES = 2;          // visible 429 retries per request
const MAX_RETRY_WAIT_S = 30;    // longer Retry-After -> fail fast (exit 3)
const EXT_LANGUAGE = { ".zpl": "zpl", ".prn": "zpl", ".epl": "epl", ".epl2": "epl",
                       ".tspl": "tspl", ".tsp": "tspl", ".cpcl": "cpcl" };

const USAGE = `labelixa <command> [files...] [options]

Commands:
  validate <files|globs|->   lint label code; exit 1 on findings (see --fail-on)
  render   <files|globs|->   render to PNG or PDF (--out for one file, --out-dir for many)
  preview  <file|->          render one label to PNG on stdout (alias of render)
  info                       CLI version, target API and auth mode (no network)

Options:
  --lang L        printer language: zpl|epl|tspl|cpcl (default: by file extension, else zpl)
  --dpmm N        printer density for ZPL: 6|8|12|24 (default 8)
  --width N       ZPL label width in inches (default 4)
  --height N      ZPL label height in inches (default 6)
  --index N       which label of a multi-label stream to render (default 0)
  --format F      render format: png|pdf (default png; pdf is ZPL only)
  --out PATH      write the single output to PATH (default: stdout)
  --out-dir DIR   write one output per input file into DIR
  --fail-on S     validate exit 1 threshold: error|warning|info|none (default error)
  --json          machine-readable JSON on stdout (human messages go to stderr)
  --version       print the CLI version

Globs: ** * ? are expanded by the CLI itself, e.g. "labels/**/*.zpl".
Auth: LABELIXA_API_KEY environment variable (NOT accepted as an argument).
Base URL: LABELIXA_API_URL (default ${DEFAULT_API}).
Docs: https://labelixa.com/docs/cli`;

class UsageError extends Error {}

// ---------------------------------------------------------------- arguments
function parseArgs(argv) {
  const opts = { lang: null, dpmm: 8, width: 4, height: 6, index: 0, format: "png",
                 out: null, outDir: null, failOn: "error", json: false };
  const files = [];
  const need = (i, name) => {
    if (i + 1 >= argv.length) throw new UsageError(`${name} needs a value`);
    return argv[i + 1];
  };
  const num = (raw, name) => {
    const n = Number(raw);
    if (!Number.isFinite(n)) throw new UsageError(`${name} expects a number, got "${raw}"`);
    return n;
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--lang") opts.lang = String(need(i++, a)).toLowerCase();
    else if (a === "--dpmm") opts.dpmm = num(need(i++, a), a);
    else if (a === "--width") opts.width = num(need(i++, a), a);
    else if (a === "--height") opts.height = num(need(i++, a), a);
    else if (a === "--index") opts.index = num(need(i++, a), a);
    else if (a === "--format") opts.format = String(need(i++, a)).toLowerCase();
    else if (a === "--out") opts.out = String(need(i++, a));
    else if (a === "--out-dir") opts.outDir = String(need(i++, a));
    else if (a === "--fail-on") opts.failOn = String(need(i++, a)).toLowerCase();
    else if (a === "--json") opts.json = true;
    else if (a === "--key" || a === "--api-key") {
      // Deliberate refusal: a key given as an argument leaks (ps, shell history).
      throw new UsageError("The API key is not accepted as an argument; set the LABELIXA_API_KEY environment variable.");
    } else if (a.startsWith("--")) throw new UsageError(`unknown option ${a}`);
    else files.push(a);
  }
  if (opts.lang !== null && !LANGUAGES.includes(opts.lang)) {
    throw new UsageError(`--lang must be one of ${LANGUAGES.join(", ")}`);
  }
  if (!["png", "pdf"].includes(opts.format)) throw new UsageError("--format must be png or pdf");
  if (!(opts.failOn in SEVERITY_RANK)) throw new UsageError("--fail-on must be error, warning, info or none");
  if (opts.out && opts.outDir) throw new UsageError("use either --out or --out-dir, not both");
  return [opts, files];
}

// -------------------------------------------------------------------- globs
const GLOB_CHARS = /[*?[]/;

function segmentToRegExp(segment) {
  let re = "^";
  for (const ch of segment) {
    if (ch === "*") re += "[^/]*";
    else if (ch === "?") re += "[^/]";
    else if (/[.+^${}()|\\/[\]]/.test(ch)) re += "\\" + ch;
    else re += ch;
  }
  return new RegExp(re + "$");
}

/** Expands one pattern relative to cwd; a plain path is returned as-is. */
function expandGlob(pattern) {
  if (!GLOB_CHARS.test(pattern)) return [pattern];
  const parts = pattern.split(/[\\/]+/).filter((p) => p.length > 0);
  const absolute = /^([\\/]|[A-Za-z]:)/.test(pattern);
  const start = absolute ? (pattern.match(/^[A-Za-z]:/) ? parts.shift() + sep : sep) : ".";
  const out = [];
  const walk = (dir, idx) => {
    if (idx === parts.length) return;
    const part = parts[idx];
    const last = idx === parts.length - 1;
    let entries;
    try { entries = readdirSync(dir, { withFileTypes: true }); } catch { return; }
    if (part === "**") {
      // `**` matches zero or more directories.
      walk(dir, idx + 1);
      for (const e of entries) {
        if (e.isDirectory() && !e.name.startsWith(".")) walk(join(dir, e.name), idx);
      }
      return;
    }
    const re = segmentToRegExp(part);
    for (const e of entries) {
      if (!re.test(e.name)) continue;
      const full = join(dir, e.name);
      if (last) { if (e.isFile()) out.push(full); }
      else if (e.isDirectory()) walk(full, idx + 1);
    }
  };
  walk(start, 0);
  return out;
}

function resolveInputs(args) {
  if (args.length === 0) throw new UsageError("provide at least one file, glob or '-' for stdin");
  const seen = new Set();
  const inputs = [];
  for (const a of args) {
    if (a === "-") { if (!seen.has("-")) { seen.add("-"); inputs.push("-"); } continue; }
    const matches = expandGlob(a);
    if (matches.length === 0) throw new UsageError(`no file matches "${a}"`);
    for (const m of matches.sort()) {
      const key = resolve(m);
      if (seen.has(key)) continue;
      seen.add(key);
      try { if (!statSync(m).isFile()) throw new UsageError(`not a file: ${m}`); }
      catch (e) { if (e instanceof UsageError) throw e; throw new UsageError(`cannot read ${m}`); }
      inputs.push(m);
    }
  }
  return inputs;
}

function readInput(source) {
  const raw = source === "-" ? readFileSync(0) : readFileSync(source);
  return raw.toString("utf-8");
}

function languageFor(source, opts) {
  if (opts.lang) return opts.lang;
  if (source === "-") return "zpl";
  return EXT_LANGUAGE[extname(source).toLowerCase()] ?? "zpl";
}

// ------------------------------------------------------------------ network
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Runs one SDK call; on 429 waits Retry-After (visibly) up to MAX_RETRIES. */
async function withRetry(label, fn) {
  for (let attempt = 0; ; attempt++) {
    try {
      return await fn();
    } catch (e) {
      const retryable = e instanceof QuotaExceeded && e.status === 429
        && attempt < MAX_RETRIES && e.retryAfter <= MAX_RETRY_WAIT_S;
      if (!retryable) throw e;
      console.error(`labelixa: ${label}: rate limited, retrying in ${e.retryAfter}s (${attempt + 1}/${MAX_RETRIES})`);
      await sleep(e.retryAfter * 1000);
    }
  }
}

// ----------------------------------------------------------------- commands
function outputPathFor(source, opts) {
  if (opts.out) return opts.out;
  if (!opts.outDir) return null;
  const stem = source === "-" ? "stdin" : basename(source, extname(source));
  return join(opts.outDir, `${stem}.${opts.format}`);
}

async function cmdRender(client, inputs, opts, { forceStdout = false } = {}) {
  if (inputs.length > 1 && !opts.outDir) {
    throw new UsageError("several inputs need --out-dir");
  }
  if (opts.format === "pdf" && inputs.some((s) => languageFor(s, opts) !== "zpl")) {
    throw new UsageError("--format pdf is available for ZPL only");
  }
  if (opts.json && !opts.out && !opts.outDir) {
    throw new UsageError("--json needs --out or --out-dir for render output");
  }
  if (opts.outDir) mkdirSync(opts.outDir, { recursive: true });
  const results = [];
  let failed = 0;
  for (const source of inputs) {
    const language = languageFor(source, opts);
    const code = readInput(source);
    const renderOpts = { language, dpmm: opts.dpmm, widthIn: opts.width,
                         heightIn: opts.height, index: opts.index };
    const target = forceStdout ? null : outputPathFor(source, opts);
    try {
      const data = await withRetry(source, () => (opts.format === "pdf"
        ? client.renderPdf(code, renderOpts)
        : client.renderPng(code, renderOpts)));
      if (target) {
        mkdirSync(dirname(target), { recursive: true });
        writeFileSync(target, data);
        if (!opts.json) console.error(`written: ${target} (${data.length} bytes)`);
      } else {
        process.stdout.write(Buffer.from(data));
      }
      results.push({ file: source, language, ok: true, output: target, bytes: data.length });
    } catch (e) {
      if (!(e instanceof LabelixaError)) throw e;
      failed++;
      results.push({ file: source, language, ok: false, error: e.serverMessage, status: e.status });
      if (!opts.json) console.error(`labelixa: ${source}: ${e.message}`);
    }
  }
  if (opts.json) {
    process.stdout.write(JSON.stringify({ version: VERSION, command: "render",
                                          results, failed }, null, 1) + "\n");
  }
  return failed ? EXIT.API : EXIT.OK;
}

function formatFinding(source, d) {
  const where = d.line ? `:${d.line}${d.col ? `:${d.col}` : ""}` : "";
  const text = d.mesaj ?? d.message ?? d.message_key ?? "";
  // The server sends the rule page address for codes that have one
  // (https://labelixa.com/zpl/rules/ZPL2001); it is printed verbatim.
  const url = d.url ? ` ${d.url}` : "";
  return `${source}${where}: ${d.severity} ${d.code} ${text}${url}`;
}

async function cmdValidate(client, inputs, opts) {
  const results = [];
  const threshold = SEVERITY_RANK[opts.failOn];
  let failed = 0;
  let apiFailed = 0;
  const totals = { error: 0, warning: 0, info: 0 };
  for (const source of inputs) {
    const language = languageFor(source, opts);
    const code = readInput(source);
    try {
      const report = await withRetry(source, () => client.validate(code, {
        language, dpmm: opts.dpmm, widthIn: opts.width, heightIn: opts.height }));
      // `ozet` is the summary object of the API's diagnostics response
      // (https://labelixa.com/docs/api): error/warning/info counts.
      const summary = { error: report.ozet?.error ?? 0, warning: report.ozet?.warning ?? 0,
                        info: report.ozet?.info ?? 0 };
      for (const k of Object.keys(totals)) totals[k] += summary[k];
      const worst = Math.max(0, ...(report.diagnostics ?? []).map((d) => SEVERITY_RANK[d.severity] ?? 0));
      const ok = threshold === 0 || worst < threshold;
      if (!ok) failed++;
      results.push({ file: source, language, ok, summary, diagnostics: report.diagnostics ?? [] });
      if (!opts.json) {
        for (const d of report.diagnostics ?? []) console.log(formatFinding(source, d));
      }
    } catch (e) {
      if (!(e instanceof LabelixaError)) throw e;
      apiFailed++;
      results.push({ file: source, language, ok: false, error: e.serverMessage, status: e.status });
      if (!opts.json) console.error(`labelixa: ${source}: ${e.message}`);
    }
  }
  if (opts.json) {
    process.stdout.write(JSON.stringify({ version: VERSION, command: "validate",
                                          failOn: opts.failOn, results, failed,
                                          apiFailed }, null, 1) + "\n");
  } else {
    console.error(`labelixa: ${inputs.length} file(s), ${totals.error} error(s), `
                  + `${totals.warning} warning(s), ${totals.info} info`);
  }
  if (apiFailed) return EXIT.API;
  return failed ? EXIT.FINDINGS : EXIT.OK;
}

function cmdInfo(opts) {
  // No network: version + target + auth MODE (never the key itself).
  const info = {
    cli: VERSION,
    api: process.env.LABELIXA_API_URL || DEFAULT_API,
    auth: process.env.LABELIXA_API_KEY ? "api-key (env)" : "anonymous",
    languages: LANGUAGES,
    docs: "https://labelixa.com/docs/cli",
  };
  console.log(JSON.stringify(info, null, 1));
  return EXIT.OK;
}

// --------------------------------------------------------------------- main
async function main() {
  const [, , command, ...argv] = process.argv;
  if (command === "--version" || command === "-v") { console.log(VERSION); return EXIT.OK; }
  if (!command || command === "help" || command === "--help" || command === "-h") {
    console.log(USAGE);
    return EXIT.OK;
  }
  const [opts, files] = parseArgs(argv);
  if (command === "info") return cmdInfo(opts);
  if (!["validate", "render", "preview"].includes(command)) {
    throw new UsageError(`unknown command "${command}"\n\n${USAGE}`);
  }
  const client = new Client({
    baseUrl: process.env.LABELIXA_API_URL || undefined,
    apiKey: process.env.LABELIXA_API_KEY || undefined,
    clientName: `cli/${VERSION}`,
  });
  const inputs = resolveInputs(files);
  if (command === "validate") return cmdValidate(client, inputs, opts);
  if (command === "preview") {
    if (inputs.length !== 1) throw new UsageError("preview takes exactly one file (use render --out-dir for many)");
    return cmdRender(client, inputs, { ...opts, format: "png" }, { forceStdout: !opts.out });
  }
  return cmdRender(client, inputs, opts);
}

try {
  process.exitCode = await main();
} catch (e) {
  if (e instanceof UsageError) {
    console.error(`labelixa: ${e.message}`);
    process.exitCode = EXIT.USAGE;
  } else if (e instanceof LabelixaError) {
    // The server's own message, verbatim; the CLI does not invent errors.
    console.error(`labelixa: ${e.message}`);
    process.exitCode = EXIT.API;
  } else if (e && (e.code === "ECONNREFUSED" || e.code === "ENOTFOUND"
                   || (e.name === "TypeError" && /fetch/i.test(String(e.message))))) {
    console.error(`labelixa: network error: ${e.cause?.message ?? e.message}`);
    process.exitCode = EXIT.API;
  } else {
    throw e;
  }
}
