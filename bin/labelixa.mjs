#!/usr/bin/env node
/**
 * Labelixa CLI — a THIN shell over the SDK.
 *
 * Design decisions:
 * - Every subcommand maps 1:1 to an existing SDK method; the CLI adds no
 *   second HTTP layer and no hidden retries.
 * - The API key is read ONLY from the environment (LABELIXA_API_KEY). It
 *   is not accepted as an argument: `ps` and shell history leak
 *   arguments, and convenience does not make up for that.
 * - LABELIXA_API_URL overrides the base URL (tests and on-prem setups).
 * - Exit codes are script-friendly: validate exits 1 on error findings.
 */
import { readFileSync, writeFileSync } from "node:fs";
import { Client, LabelixaError } from "../index.js";

const USAGE = `labelixa <command> [file] [options]

Commands:
  preview <file|->    render ZPL to PNG (stdout unless --out is given)
  render  <file|->    produce PNG or PDF (--format png|pdf)
  validate <file|->   lint ZPL; exit code 1 when an error finding exists
  info                CLI/SDK version, target API and auth mode

Options:
  --dpmm N      printer density (6|8|12|24; default 8)
  --width N     label width in inches (default 4)
  --height N    label height in inches (default 6)
  --format F    render format: png|pdf (default png)
  --out PATH    write the output to a file (default: stdout)

Auth: LABELIXA_API_KEY environment variable (NOT accepted as an argument).
Base URL: LABELIXA_API_URL (default https://api.labelixa.com).`;

function parseArgs(argv) {
  const opts = { dpmm: 8, width: 4, height: 6, format: "png", out: null };
  const rest = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--dpmm") opts.dpmm = Number(argv[++i]);
    else if (a === "--width") opts.width = Number(argv[++i]);
    else if (a === "--height") opts.height = Number(argv[++i]);
    else if (a === "--format") opts.format = String(argv[++i]);
    else if (a === "--out") opts.out = String(argv[++i]);
    else if (a === "--key" || a === "--api-key") {
      // Deliberate refusal: a key given as an argument leaks (ps, shell history).
      console.error("The API key is not accepted as an argument; set the LABELIXA_API_KEY environment variable.");
      process.exit(2);
    } else rest.push(a);
  }
  return [opts, rest];
}

function readZpl(source) {
  if (!source) {
    console.error("Provide a ZPL file ('-' for stdin).\n\n" + USAGE);
    process.exit(2);
  }
  const raw = source === "-" ? readFileSync(0) : readFileSync(source);
  return raw.toString("utf-8");
}

function writeOutput(data, out) {
  if (out) writeFileSync(out, data);
  else process.stdout.write(data);
}

const [, , command, ...argv] = process.argv;
const [opts, rest] = parseArgs(argv);
const client = new Client({
  baseUrl: process.env.LABELIXA_API_URL || undefined,
  apiKey: process.env.LABELIXA_API_KEY || undefined,
});

try {
  if (command === "preview" || command === "render") {
    const zpl = readZpl(rest[0]);
    const renderOpts = { dpmm: opts.dpmm, widthIn: opts.width, heightIn: opts.height };
    const data = command === "render" && opts.format === "pdf"
      ? await client.renderPdf(zpl, renderOpts)
      : await client.renderPng(zpl, renderOpts);
    writeOutput(Buffer.from(data), opts.out);
    if (opts.out) console.error(`written: ${opts.out} (${data.length} bytes)`);
  } else if (command === "validate") {
    const zpl = readZpl(rest[0]);
    const report = await client.validate(zpl, {
      dpmm: opts.dpmm, widthIn: opts.width, heightIn: opts.height });
    const text = JSON.stringify(report, null, 1);
    writeOutput(text + "\n", opts.out);
    // `ozet` is the summary object of the API's diagnostics response
    // (https://labelixa.com/docs/api); `error` is its error count.
    if ((report.ozet?.error ?? 0) > 0) process.exit(1);
  } else if (command === "info") {
    // No network: version + target + auth MODE (never the key itself).
    const pkg = JSON.parse(readFileSync(
      new URL("../package.json", import.meta.url), "utf-8"));
    console.log(JSON.stringify({
      cli: pkg.version,
      api: process.env.LABELIXA_API_URL || "https://api.labelixa.com",
      auth: process.env.LABELIXA_API_KEY ? "api-key (env)" : "anonymous",
      docs: "https://labelixa.com/docs/api",
    }, null, 1));
  } else {
    console.error(USAGE);
    process.exit(command ? 2 : 0);
  }
} catch (e) {
  if (e instanceof LabelixaError) {
    // The server's own message, verbatim; the CLI does not invent errors.
    console.error(`labelixa: ${e.message}`);
    process.exit(1);
  }
  throw e;
}
