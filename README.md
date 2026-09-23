# labelixa

Render, validate and convert **Zebra ZPL** label code from Node.js — no
printer required. Thin client for the [Labelixa](https://labelixa.com) API.
Zero dependencies (built on global `fetch`, Node 18+).

```bash
npm install labelixa
```

```js
import { Client, QuotaExceeded } from "labelixa";

const c = new Client();                       // anonymous: free, rate-limited
// const c = new Client({ apiKey: "lbx_..." }); // your quota — labelixa.com/panel

const zpl = "^XA^FO50,50^A0N,40^FDHello^FS^BY3^FO50,120^BCN,100,Y^FD12345678^FS^XZ";

// See what the label looks like
import { writeFile } from "node:fs/promises";
await writeFile("label.png", await c.renderPng(zpl, { widthIn: 4, heightIn: 6 }));

// Lint before printing
const report = await c.validate(zpl);
for (const d of report.diagnostics) console.log(d.severity, d.message);

// Multi-label PDF, ZPL -> EPL2 translation
const pdf = await c.renderPdf(zpl);
const epl = await c.toEpl(zpl);

// 0.2.0: barcode / language detection / printer compatibility
const svg = await c.barcode("HELLO-123", { type: "code128" });
const lang = await c.languageDetect(rawLabelCode);   // confidence TIER, not %
const risk = await c.compatibility(zpl, "zebra/zd421"); // manufacturer/model; risk report, not "it works"
```

## Command line (CI)

The package ships a `labelixa` command for pipelines. It reads the API
key only from `LABELIXA_API_KEY` (never from an argument) and expands
glob patterns itself, so the same line works on Linux, macOS and Windows
runners:

```bash
# lint every label in the repo; exit 1 on error findings
npx labelixa validate "labels/**/*.zpl"

# warnings also fail the build; machine-readable report for the job log
npx labelixa validate "labels/**/*.zpl" --fail-on warning --json > report.json

# render previews as build artifacts (one PNG per input)
npx labelixa render "labels/**/*.zpl" --dpmm 8 --width 4 --height 6 --out-dir out/

# EPL / TSPL / CPCL by extension or explicitly
npx labelixa validate receipts/*.tspl
npx labelixa validate raw.txt --lang cpcl
```

Exit codes: `0` success, `1` findings at or above `--fail-on`, `2` usage
error, `3` API or network error. A 429 is retried at most twice for the
server's `Retry-After`. Full reference and CI snippets (GitHub Actions,
GitLab CI, Azure Pipelines, Bitbucket): https://labelixa.com/docs/cli

### pre-commit

The repository also ships a [pre-commit](https://pre-commit.com) hook, so
label files are linted before they are committed. Add to
`.pre-commit-config.yaml`:

```yaml
repos:
  - repo: https://github.com/Labelixa/labelixa-sdk
    rev: v0.3.0
    hooks:
      - id: labelixa-validate
        # optional: fail on warnings too
        # args: [--fail-on, warning]
```

The hook runs `labelixa validate` on staged `.zpl`, `.prn`, `.epl`,
`.epl2`, `.tspl`, `.tsp` and `.cpcl` files (language by extension). No
secret is required: without `LABELIXA_API_KEY` in the environment the
anonymous rate limit applies; export the key for a busy repository. The
hook is serial by design — the API's rate limit is shared, so parallel
instances would only earn 429s.

Quota errors are first-class:

```js
try {
  await c.renderPng(zpl);
} catch (e) {
  if (e instanceof QuotaExceeded) {
    console.log(`retry in ${e.retryAfter}s (hint: ${e.action})`);
  } else throw e;
}
```

This SDK is a deliberately thin 1:1 wrapper over the documented REST
API — the [API reference](https://labelixa.com/docs/api) is the source
of truth. AI assistants can also use Labelixa directly over MCP:
point your client at `https://api.labelixa.com/mcp`.
