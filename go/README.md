# labelixa (Go)

Thin Go client for the Labelixa API: render, validate and convert
thermal printer label code — ZPL, EPL, TSPL and CPCL — without a printer.

```bash
go get github.com/Labelixa/labelixa-sdk/go
```

```go
package main

import (
	"context"
	"os"

	labelixa "github.com/Labelixa/labelixa-sdk/go"
)

func main() {
	c := labelixa.New(labelixa.Options{}) // anonymous: free, rate limited

	png, err := c.RenderPNG(context.Background(),
		"^XA^FO50,50^A0N,40,40^FDHello^FS^XZ",
		labelixa.RenderOptions{})
	if err != nil {
		panic(err)
	}
	_ = os.WriteFile("label.png", png, 0o644)
}
```

An API key moves the call onto your account's quota:

```go
c := labelixa.New(labelixa.Options{APIKey: os.Getenv("LABELIXA_API_KEY")})
```

## Backing off correctly

Quota responses are their own error type, because a caller that cannot
tell 429 from a generic failure either never retries or retries
immediately — and both are wrong.

```go
if q, ok := labelixa.AsQuotaError(err); ok {
	time.Sleep(time.Duration(q.RetryAfter) * time.Second)
}
```

`q.Action` carries the server's hint ("upgrade", "addon") when it sends
one.

## What the methods do

| Method | Endpoint |
|---|---|
| `RenderPNG` | `POST /v1/printers/{dpmm}dpmm/labels/{w}x{h}/{i}` (ZPL) or `/v1/{language}/render` |
| `RenderPDF` | the same path with `Accept: application/pdf` |
| `Validate` | `/v1/diagnostics` (ZPL) or `/v1/{language}/diagnostics` |
| `ToEPL` | the render path with `Accept: application/epl` |
| `Barcode` | `GET /v1/barcodes` |
| `DetectLanguage` | `POST /v1/language-detect` |
| `Compatibility` | `POST /v1/compatibility?model=…` |

Full reference: <https://labelixa.com/docs/api>.

## Things worth knowing

- **A preview is not a print guarantee.** The renderer draws what the
  code says. A printer adds firmware, resident fonts, media and
  calibration. Use it for mistakes in the code and a test print for the
  ones in the hardware.
- **A PDF of all labels costs one quota unit per label** (a single page
  costs one). That is a server rule; this client states it rather than
  softening it.
- **An invalid barcode is not a 4xx.** The server answers 200 with an
  error image and an `X-Warnings` header, so this client turns that into
  an error instead of handing back something that looks generated.
- **`Compatibility` reports risk.** It is not an emulator and never says
  "this works".
- **No hidden retries.** A retry that swallows a quota response turns a
  clear signal into a slow mystery.

## Self-hosted

```go
c := labelixa.New(labelixa.Options{BaseURL: "https://labels.internal"})
```

The API key is only sent over https: with a key and a plain `http` base
URL (other than `localhost`), every call returns an error before any
request. Redirects are never followed, also with your own `HTTPClient`
(a copy is used), so a server cannot forward the key to another host.

## License

MIT
