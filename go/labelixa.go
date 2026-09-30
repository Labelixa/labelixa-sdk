// Package labelixa is a thin Go client for the Labelixa REST API:
// render, validate and convert thermal printer label code (ZPL, EPL,
// TSPL, CPCL) without a printer.
//
//	c := labelixa.New(labelixa.Options{})              // anonymous
//	c := labelixa.New(labelixa.Options{APIKey: "lbx_"}) // account quota
//
//	png, err := c.RenderPNG(ctx, zpl, labelixa.RenderOptions{})
//	report, err := c.Validate(ctx, zpl, labelixa.ValidateOptions{})
//
// Design notes, shared with the Python, Node and PHP clients:
//
//   - THIN wrapper. Every method maps 1:1 to a documented REST endpoint.
//     No client-side magic and no hidden retries: a retry that swallows a
//     quota response turns a clear signal into a slow mystery.
//     https://labelixa.com/docs/api is the source of truth.
//   - Errors carry the server's own message. Quota exhaustion is a
//     distinct type (QuotaError) with RetryAfter and the server's action
//     hint, because a caller that cannot tell 429 from a generic failure
//     either never retries or retries immediately — both wrong.
//   - Standard library only. A label client is not worth a dependency
//     tree in someone else's build.
package labelixa

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// Version of this client, sent in the User-Agent header.
const Version = "0.1.0"

// DefaultBaseURL is the hosted API. Point Options.BaseURL at your own
// instance for an on-premise deployment.
const DefaultBaseURL = "https://api.labelixa.com"

// Languages the API renders and lints. ZPL is the default.
var Languages = []string{"zpl", "epl", "tspl", "cpcl"}

// Error carries the server's own message verbatim. Wrapping it in a
// friendlier sentence would hide the only text that says what is wrong.
type Error struct {
	Status        int
	ServerMessage string
}

func (e *Error) Error() string {
	return fmt.Sprintf("HTTP %d: %s", e.Status, e.ServerMessage)
}

// QuotaError is returned for 402 and 429. RetryAfter is the server's
// Retry-After in seconds; Action is its hint ("upgrade", "addon") when
// it sends one.
//
// The embedded Error is named rather than anonymous: an anonymous
// *Error would give this type both a field and a method called Error,
// which does not compile.
type QuotaError struct {
	Err        Error
	RetryAfter int
	Action     string
}

func (e *QuotaError) Error() string { return e.Err.Error() }

// Unwrap lets errors.As find the underlying *Error as well.
func (e *QuotaError) Unwrap() error { return &e.Err }

// Options configures a Client.
type Options struct {
	// APIKey is an lbx_ key. Empty means anonymous: free, rate limited
	// per IP.
	APIKey string
	// BaseURL defaults to DefaultBaseURL.
	BaseURL string
	// ClientName is sent as X-Client (for example "erp-connector/2.1")
	// so usage is attributed to the integration, never to a person.
	ClientName string
	// HTTPClient defaults to a client with a 60 second timeout. Rendering
	// a large label takes seconds, so the default has room; a caller with
	// its own budget passes its own client. The client never follows
	// redirects, also with a client passed here: a shallow copy with its
	// own CheckRedirect is used and the caller's client is not modified.
	HTTPClient *http.Client
}

// Client talks to the Labelixa API. It is safe for concurrent use.
type Client struct {
	baseURL string
	headers map[string]string
	http    *http.Client
	// setup is returned by every call when the options are unusable
	// (New itself never fails).
	setup error
}

// noRedirect stops at the first redirect and hands the 3xx back to the
// caller. Go re-sends custom headers when it follows a redirect and strips
// only Authorization and Cookie on a host change, so X-API-Key would reach
// whatever host a 3xx names. The API does not redirect; a 3xx surfaces as
// an *Error with its status.
func noRedirect(*http.Request, []*http.Request) error {
	return http.ErrUseLastResponse
}

// checkKeyTransport allows the API key over https, and over plain http
// only to a loopback address (local development). Anywhere else it would
// cross the network readable.
func checkKeyTransport(base string) error {
	u, err := url.Parse(base)
	if err != nil {
		return fmt.Errorf("labelixa: invalid base URL %q", base)
	}
	if u.Scheme == "https" {
		return nil
	}
	host := u.Hostname()
	loopback := strings.EqualFold(host, "localhost")
	if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
		loopback = true
	}
	if u.Scheme == "http" && loopback {
		return nil
	}
	return fmt.Errorf("labelixa: refusing to send the API key to %q: use an "+
		"https base URL (plain http is accepted only for localhost)", base)
}

// New builds a client. It never fails and never reaches the network. An
// API key with a plain http base URL (other than localhost) is not an
// error here: every call returns that error instead, before any request.
func New(o Options) *Client {
	base := o.BaseURL
	if base == "" {
		base = DefaultBaseURL
	}
	var setup error
	h := map[string]string{"User-Agent": "labelixa-go/" + Version}
	if o.APIKey != "" {
		setup = checkKeyTransport(base)
		h["X-API-Key"] = o.APIKey
	}
	if o.ClientName != "" {
		h["X-Client"] = o.ClientName
	}
	hc := http.Client{Timeout: 60 * time.Second}
	if o.HTTPClient != nil {
		hc = *o.HTTPClient
	}
	hc.CheckRedirect = noRedirect
	return &Client{
		baseURL: strings.TrimRight(base, "/"),
		headers: h,
		http:    &hc,
		setup:   setup,
	}
}

func checkLanguage(language string) (string, error) {
	if language == "" {
		return "zpl", nil
	}
	for _, l := range Languages {
		if l == language {
			return language, nil
		}
	}
	return "", &Error{Status: 0, ServerMessage: fmt.Sprintf(
		"unknown language %q; expected one of %s",
		language, strings.Join(Languages, ", "))}
}

// number formats a dimension the way the API path expects: 4 not 4.0,
// 2.25 unchanged.
func number(f float64) string {
	return strconv.FormatFloat(f, 'f', -1, 64)
}

func (c *Client) do(ctx context.Context, method, path string,
	body []byte, extra map[string]string) (*http.Response, error) {
	if c.setup != nil {
		return nil, c.setup
	}
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, reader)
	if err != nil {
		return nil, err
	}
	for k, v := range c.headers {
		req.Header.Set(k, v)
	}
	if body != nil {
		req.Header.Set("Content-Type", "text/plain")
	}
	for k, v := range extra {
		req.Header.Set(k, v)
	}
	return c.http.Do(req)
}

// errorFromResponse reads the body and picks the error type. The body is
// capped: an error message is a sentence, and a broken proxy can answer
// with a megabyte of HTML.
func errorFromResponse(res *http.Response) error {
	raw, _ := io.ReadAll(io.LimitReader(res.Body, 500))
	msg := string(raw)
	base := Error{Status: res.StatusCode, ServerMessage: msg}
	if res.StatusCode == http.StatusPaymentRequired ||
		res.StatusCode == http.StatusTooManyRequests {
		retry := 60
		if v, err := strconv.Atoi(res.Header.Get("Retry-After")); err == nil {
			retry = v
		}
		return &QuotaError{Err: base, RetryAfter: retry,
			Action: res.Header.Get("X-Quota-Action")}
	}
	return &base
}

// RenderOptions selects density, size and which label to draw.
//
// Zero values mean the defaults: 8 dpmm, 4x6 inches, first label, no
// rotation. Density and size apply to ZPL; for EPL, TSPL and CPCL the
// size comes from the code itself and only Index applies.
type RenderOptions struct {
	Language string
	DPMM     int
	WidthIn  float64
	HeightIn float64
	Index    int
	Rotation int
}

func (o RenderOptions) withDefaults() RenderOptions {
	if o.DPMM == 0 {
		o.DPMM = 8
	}
	if o.WidthIn == 0 {
		o.WidthIn = 4
	}
	if o.HeightIn == 0 {
		o.HeightIn = 6
	}
	return o
}

// RenderPNG renders one label to PNG.
func (c *Client) RenderPNG(ctx context.Context, code string,
	o RenderOptions) ([]byte, error) {
	language, err := checkLanguage(o.Language)
	if err != nil {
		return nil, err
	}
	o = o.withDefaults()
	var res *http.Response
	if language == "zpl" {
		extra := map[string]string{}
		if o.Rotation != 0 {
			extra["X-Rotation"] = strconv.Itoa(o.Rotation)
		}
		path := fmt.Sprintf("/v1/printers/%ddpmm/labels/%sx%s/%d",
			o.DPMM, number(o.WidthIn), number(o.HeightIn), o.Index)
		res, err = c.do(ctx, http.MethodPost, path, []byte(code), extra)
	} else {
		path := fmt.Sprintf("/v1/%s/render?index=%d", language, o.Index)
		res, err = c.do(ctx, http.MethodPost, path, []byte(code), nil)
	}
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return nil, errorFromResponse(res)
	}
	return io.ReadAll(res.Body)
}

// PDFOptions configures RenderPDF. AllLabels puts every label of the
// code in one stream.
//
// That costs one quota unit PER LABEL (a single page costs one) — a
// server rule, which this client states rather than softens.
type PDFOptions struct {
	DPMM      int
	WidthIn   float64
	HeightIn  float64
	Index     int
	AllLabels bool
}

// RenderPDF renders ZPL to PDF.
func (c *Client) RenderPDF(ctx context.Context, zpl string,
	o PDFOptions) ([]byte, error) {
	r := RenderOptions{DPMM: o.DPMM, WidthIn: o.WidthIn,
		HeightIn: o.HeightIn}.withDefaults()
	suffix := strconv.Itoa(o.Index)
	if o.AllLabels {
		suffix = ""
	}
	path := fmt.Sprintf("/v1/printers/%ddpmm/labels/%sx%s/%s",
		r.DPMM, number(r.WidthIn), number(r.HeightIn), suffix)
	res, err := c.do(ctx, http.MethodPost, path, []byte(zpl),
		map[string]string{"Accept": "application/pdf"})
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return nil, errorFromResponse(res)
	}
	return io.ReadAll(res.Body)
}

// ValidateOptions selects the linter and, for ZPL, the label geometry.
type ValidateOptions struct {
	Language string
	DPMM     int
	WidthIn  float64
	HeightIn float64
}

// Validate lints label code and returns the server's structured report
// as decoded JSON: a diagnostics list plus a summary with error, warning
// and info counts.
//
// The report is returned as map[string]any rather than a struct on
// purpose: the server owns that shape and adds fields over time, and a
// struct here would silently drop whatever this version has not heard
// of. https://labelixa.com/docs/api documents the fields.
func (c *Client) Validate(ctx context.Context, code string,
	o ValidateOptions) (map[string]any, error) {
	language, err := checkLanguage(o.Language)
	if err != nil {
		return nil, err
	}
	r := RenderOptions{DPMM: o.DPMM, WidthIn: o.WidthIn,
		HeightIn: o.HeightIn}.withDefaults()
	path := fmt.Sprintf("/v1/%s/diagnostics", language)
	if language == "zpl" {
		// The endpoint reads the label size from w and h. Any other
		// spelling is ignored silently by the server, so every check
		// would quietly run against the 4x6 default.
		path = fmt.Sprintf("/v1/diagnostics?dpmm=%d&w=%s&h=%s",
			r.DPMM, number(r.WidthIn), number(r.HeightIn))
	}
	res, err := c.do(ctx, http.MethodPost, path, []byte(code), nil)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return nil, errorFromResponse(res)
	}
	var out map[string]any
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		return nil, err
	}
	return out, nil
}

// ToEPL translates ZPL to EPL2. Fields the translation cannot carry
// over surface in the warnings of the returned document.
func (c *Client) ToEPL(ctx context.Context, zpl string,
	o RenderOptions) (string, error) {
	o = o.withDefaults()
	path := fmt.Sprintf("/v1/printers/%ddpmm/labels/%sx%s/",
		o.DPMM, number(o.WidthIn), number(o.HeightIn))
	res, err := c.do(ctx, http.MethodPost, path, []byte(zpl),
		map[string]string{"Accept": "application/epl"})
	if err != nil {
		return "", err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return "", errorFromResponse(res)
	}
	body, err := io.ReadAll(res.Body)
	return string(body), err
}

// BarcodeOptions selects the symbology and the output format.
type BarcodeOptions struct {
	Type   string // default "code128"
	Format string // "svg" (default) or "png"
}

// Barcode generates a standalone barcode.
//
// Server contract worth knowing: invalid input is NOT a 4xx. The server
// answers 200 with an error image and an X-Warnings header. Handing that
// back as a generated barcode would be lying to the caller, so it
// becomes an error carrying the server's warning verbatim.
func (c *Client) Barcode(ctx context.Context, data string,
	o BarcodeOptions) ([]byte, error) {
	if o.Type == "" {
		o.Type = "code128"
	}
	if o.Format == "" {
		o.Format = "svg"
	}
	q := url.Values{"type": {o.Type}, "data": {data}, "format": {o.Format}}
	res, err := c.do(ctx, http.MethodGet, "/v1/barcodes?"+q.Encode(), nil, nil)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return nil, errorFromResponse(res)
	}
	if w := res.Header.Get("X-Warnings"); w != "" {
		return nil, &Error{Status: res.StatusCode,
			ServerMessage: "barcode not generated: " + w}
	}
	return io.ReadAll(res.Body)
}

// DetectLanguage guesses the printer language of raw label code.
//
// The result carries a confidence TIER (high, medium, low), not a
// probability: the server does not compute one, and this client does not
// invent one.
func (c *Client) DetectLanguage(ctx context.Context,
	code string) (map[string]any, error) {
	return c.postJSON(ctx, "/v1/language-detect", code)
}

// Compatibility analyses ZPL against a printer model given as
// manufacturer/model, for example "zebra/zd421". The server reads
// Manufacturer/Model case-insensitively; "zebra-zd421" is not a valid
// key and comes back as the server's own 404.
//
// It reports RISK. It is not an emulator and never says "this works".
func (c *Client) Compatibility(ctx context.Context, zpl,
	model string) (map[string]any, error) {
	return c.postJSON(ctx,
		"/v1/compatibility?"+url.Values{"model": {model}}.Encode(), zpl)
}

func (c *Client) postJSON(ctx context.Context, path,
	body string) (map[string]any, error) {
	res, err := c.do(ctx, http.MethodPost, path, []byte(body), nil)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return nil, errorFromResponse(res)
	}
	var out map[string]any
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		return nil, err
	}
	return out, nil
}

// AsQuotaError reports whether err is a quota response and hands back the
// typed value, so callers can back off for RetryAfter seconds.
func AsQuotaError(err error) (*QuotaError, bool) {
	var q *QuotaError
	if errors.As(err, &q) {
		return q, true
	}
	return nil, false
}
