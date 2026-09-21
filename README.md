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
