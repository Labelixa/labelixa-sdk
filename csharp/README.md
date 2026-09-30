# Labelixa (.NET)

Thin .NET client for the Labelixa API: render, validate and convert
thermal printer label code — ZPL, EPL, TSPL and CPCL — without a printer.

```bash
dotnet add package Labelixa
```

```csharp
using Labelixa;

var client = new LabelixaClient();                                  // anonymous
var client = new LabelixaClient(apiKey: Environment.GetEnvironmentVariable("LABELIXA_API_KEY"));

byte[] png = await client.RenderPngAsync("^XA^FO50,50^A0N,40,40^FDHello^FS^XZ");
await File.WriteAllBytesAsync("label.png", png);

JsonElement report = await client.ValidateAsync(zpl);
Console.WriteLine(report.GetProperty("ozet").GetProperty("error").GetInt32() + " errors");
```

Requires .NET 8. No packages.

## Backing off correctly

Quota answers have their own exception, because a caller that cannot tell
429 from a generic failure either never retries or retries immediately —
and both are wrong.

```csharp
try
{
    byte[] png = await client.RenderPngAsync(zpl);
}
catch (QuotaExceededException error)
{
    await Task.Delay(TimeSpan.FromSeconds(error.RetryAfter));
    // error.Action is "upgrade" or "addon" when the server suggests one
}
```

## Using your application's HttpClient

Pass your own client — from `IHttpClientFactory`, with your own handlers,
timeout and policies. It is used as-is and never mutated, so the API key
stays on the requests this client makes and never leaks onto a client
shared with the rest of your application.

```csharp
public sealed class LabelService(IHttpClientFactory factory)
{
    private readonly LabelixaClient _labels = new(
        apiKey: "lbx_...",
        clientName: "erp-connector/2.1",
        httpClient: factory.CreateClient("labelixa"));
}
```

`clientName` is sent as `X-Client` so usage is attributed to the
integration, never to a person.

## What the client does

| Method | Endpoint |
|---|---|
| `RenderPngAsync` | one label as PNG (ZPL, EPL, TSPL, CPCL) |
| `RenderPdfAsync` | ZPL as PDF; `index: null` puts every label in one stream |
| `ValidateAsync` | the linter's structured report |
| `ToEplAsync` | ZPL translated to EPL2 |
| `BarcodeAsync` | a standalone barcode as SVG or PNG |
| `DetectLanguageAsync` | which printer language raw code is |
| `CompatibilityAsync` | risk against a printer model, e.g. `zebra/zd421` |

Every method maps 1:1 to a documented REST endpoint and there are no
hidden retries: a retry that swallows a quota answer turns a clear signal
into a slow mystery. <https://labelixa.com/docs/api> is the source of
truth.

Reports come back as `JsonElement` rather than typed classes on purpose.
The server owns those shapes and adds fields over time; a fixed class
here would silently drop whatever this version has not heard of.

## Two things worth knowing

**A rendered preview is not a print guarantee.** The render shows what the
code draws; the printer adds firmware, resident fonts, media and
calibration.

**An invalid barcode is not a 4xx.** The server answers 200 with an error
image and an `X-Warnings` header. Handing that back as a generated barcode
would be a lie, so this client turns it into an exception carrying the
warning verbatim.

## On-premise

```csharp
var client = new LabelixaClient(baseUrl: "https://labels.internal.example");
```

The API key is only sent over https: a client with a key and a plain
`http` base URL (other than `localhost`) throws `ArgumentException`. The
default `HttpClient` never follows redirects, so a server cannot forward
the key to another host. If you pass your own `HttpClient`, give it a
handler with `AllowAutoRedirect = false` for the same guarantee.

## Self-hosting and support

- Documentation: <https://labelixa.com/docs/api>
- Issues: <https://github.com/Labelixa/labelixa-sdk/issues>

MIT licensed.
