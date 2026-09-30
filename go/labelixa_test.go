package labelixa

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// The value of a thin client is that it hits the RIGHT address with the
// RIGHT headers. These tests measure exactly that against a stub server;
// nothing here reaches the network.

const zpl = "^XA^FO50,50^A0N,40,40^FDHello^FS^XZ"

type capture struct {
	method  string
	path    string
	query   string
	headers http.Header
	body    string
}

func stub(t *testing.T, handler func(w http.ResponseWriter,
	r *http.Request)) (*Client, *capture) {
	t.Helper()
	got := &capture{}
	srv := httptest.NewServer(http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			body, _ := io.ReadAll(r.Body)
			got.method, got.path = r.Method, r.URL.Path
			got.query, got.headers, got.body = r.URL.RawQuery, r.Header, string(body)
			handler(w, r)
		}))
	t.Cleanup(srv.Close)
	return New(Options{BaseURL: srv.URL, APIKey: "lbx_test",
		ClientName: "suite/1", HTTPClient: srv.Client()}), got
}

func TestRenderPNGHitsTheLabelaryShapedPath(t *testing.T) {
	c, got := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "image/png")
		_, _ = w.Write([]byte("\x89PNG"))
	})
	out, err := c.RenderPNG(context.Background(), zpl, RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if string(out) != "\x89PNG" {
		t.Fatalf("body not passed through: %q", out)
	}
	if got.path != "/v1/printers/8dpmm/labels/4x6/0" {
		t.Fatalf("path = %q", got.path)
	}
	if got.body != zpl {
		t.Fatalf("code not sent verbatim: %q", got.body)
	}
	if got.headers.Get("X-API-Key") != "lbx_test" {
		t.Fatal("api key not sent")
	}
	if got.headers.Get("X-Client") != "suite/1" {
		t.Fatal("client name not sent")
	}
	if ua := got.headers.Get("User-Agent"); !strings.HasPrefix(ua, "labelixa-go/") {
		t.Fatalf("user agent = %q", ua)
	}
}

func TestFractionalSizeKeepsItsDecimals(t *testing.T) {
	// 4.0 must become "4", 2.25 must stay "2.25": the path is parsed by
	// the server and "4.0x2.25" is a different address.
	c, got := stub(t, func(w http.ResponseWriter, _ *http.Request) {})
	_, _ = c.RenderPNG(context.Background(), zpl,
		RenderOptions{WidthIn: 2.25, HeightIn: 4})
	if got.path != "/v1/printers/8dpmm/labels/2.25x4/0" {
		t.Fatalf("path = %q", got.path)
	}
}

func TestRotationOnlySentWhenAsked(t *testing.T) {
	c, got := stub(t, func(w http.ResponseWriter, _ *http.Request) {})
	_, _ = c.RenderPNG(context.Background(), zpl, RenderOptions{})
	if got.headers.Get("X-Rotation") != "" {
		t.Fatal("rotation header sent without a rotation")
	}
	_, _ = c.RenderPNG(context.Background(), zpl, RenderOptions{Rotation: 90})
	if got.headers.Get("X-Rotation") != "90" {
		t.Fatalf("rotation = %q", got.headers.Get("X-Rotation"))
	}
}

func TestOtherLanguagesUseTheirOwnEndpoint(t *testing.T) {
	for _, language := range []string{"epl", "tspl", "cpcl"} {
		c, got := stub(t, func(w http.ResponseWriter, _ *http.Request) {})
		_, _ = c.RenderPNG(context.Background(), "N\nP1\n",
			RenderOptions{Language: language, Index: 2})
		if got.path != "/v1/"+language+"/render" {
			t.Fatalf("%s path = %q", language, got.path)
		}
		if got.query != "index=2" {
			t.Fatalf("%s query = %q", language, got.query)
		}
	}
}

func TestUnknownLanguageNeverReachesTheNetwork(t *testing.T) {
	reached := false
	c, _ := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		reached = true
	})
	_, err := c.RenderPNG(context.Background(), zpl,
		RenderOptions{Language: "pcl"})
	if err == nil {
		t.Fatal("unknown language accepted")
	}
	if reached {
		t.Fatal("request sent for an unknown language")
	}
}

func TestPDFAllLabelsDropsTheIndex(t *testing.T) {
	c, got := stub(t, func(w http.ResponseWriter, _ *http.Request) {})
	_, _ = c.RenderPDF(context.Background(), zpl, PDFOptions{AllLabels: true})
	if got.path != "/v1/printers/8dpmm/labels/4x6/" {
		t.Fatalf("path = %q", got.path)
	}
	if got.headers.Get("Accept") != "application/pdf" {
		t.Fatalf("accept = %q", got.headers.Get("Accept"))
	}
}

func TestValidateSendsTheGeometryTheServerReads(t *testing.T) {
	c, got := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{"ozet": map[string]any{}})
	})
	report, err := c.Validate(context.Background(), zpl,
		ValidateOptions{DPMM: 12, WidthIn: 3, HeightIn: 2})
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := report["ozet"]; !ok {
		t.Fatal("report not decoded")
	}
	if got.path != "/v1/diagnostics" || got.query != "dpmm=12&w=3&h=2" {
		t.Fatalf("path=%q query=%q", got.path, got.query)
	}
}

func TestQuotaResponseIsItsOwnError(t *testing.T) {
	c, _ := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Retry-After", "42")
		w.Header().Set("X-Quota-Action", "upgrade")
		w.WriteHeader(http.StatusTooManyRequests)
		_, _ = w.Write([]byte("daily quota used"))
	})
	_, err := c.RenderPNG(context.Background(), zpl, RenderOptions{})
	q, ok := AsQuotaError(err)
	if !ok {
		t.Fatalf("not a quota error: %v", err)
	}
	if q.RetryAfter != 42 || q.Action != "upgrade" {
		t.Fatalf("retry=%d action=%q", q.RetryAfter, q.Action)
	}
	if !strings.Contains(q.Error(), "daily quota used") {
		t.Fatalf("server message lost: %q", q.Error())
	}
}

func TestMissingRetryAfterFallsBackInsteadOfPanicking(t *testing.T) {
	c, _ := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusPaymentRequired)
	})
	_, err := c.RenderPNG(context.Background(), zpl, RenderOptions{})
	q, ok := AsQuotaError(err)
	if !ok || q.RetryAfter != 60 {
		t.Fatalf("err=%v", err)
	}
}

func TestOtherFailuresKeepTheServerMessage(t *testing.T) {
	c, _ := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte("label too large"))
	})
	_, err := c.RenderPNG(context.Background(), zpl, RenderOptions{})
	if _, quota := AsQuotaError(err); quota {
		t.Fatal("400 classed as a quota error")
	}
	if !strings.Contains(err.Error(), "label too large") {
		t.Fatalf("message lost: %v", err)
	}
}

func TestBarcodeWarningIsAFailureNotABarcode(t *testing.T) {
	// The server answers 200 with an error image and X-Warnings. Passing
	// that through as a generated barcode would be lying to the caller.
	c, _ := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("X-Warnings", "invalid data for ean13")
		_, _ = w.Write([]byte("<svg/>"))
	})
	_, err := c.Barcode(context.Background(), "abc",
		BarcodeOptions{Type: "ean13"})
	if err == nil {
		t.Fatal("warning swallowed")
	}
	if !strings.Contains(err.Error(), "invalid data for ean13") {
		t.Fatalf("warning lost: %v", err)
	}
}

func TestBarcodeDefaultsAndQuery(t *testing.T) {
	c, got := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("<svg/>"))
	})
	out, err := c.Barcode(context.Background(), "12345", BarcodeOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if string(out) != "<svg/>" {
		t.Fatalf("body = %q", out)
	}
	if got.method != http.MethodGet {
		t.Fatalf("method = %q", got.method)
	}
	for _, want := range []string{"type=code128", "data=12345", "format=svg"} {
		if !strings.Contains(got.query, want) {
			t.Fatalf("query %q missing %q", got.query, want)
		}
	}
}

func TestToEPLAsksForTheEPLMediaType(t *testing.T) {
	c, got := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("N\nA50,50,0,3,1,1,N,\"Hello\"\nP1\n"))
	})
	out, err := c.ToEPL(context.Background(), zpl, RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(out, "N\n") {
		t.Fatalf("out = %q", out)
	}
	if got.headers.Get("Accept") != "application/epl" {
		t.Fatalf("accept = %q", got.headers.Get("Accept"))
	}
	// The translation endpoint is the trailing-slash form.
	if got.path != "/v1/printers/8dpmm/labels/4x6/" {
		t.Fatalf("path = %q", got.path)
	}
}

func TestCompatibilityPassesTheModelAsGiven(t *testing.T) {
	c, got := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{"risk": []any{}})
	})
	_, err := c.Compatibility(context.Background(), zpl, "zebra/zd421")
	if err != nil {
		t.Fatal(err)
	}
	if got.query != "model=zebra%2Fzd421" {
		t.Fatalf("query = %q", got.query)
	}
}

func TestAnonymousClientSendsNoKey(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			if r.Header.Get("X-API-Key") != "" {
				t.Error("anonymous client sent an api key")
			}
		}))
	defer srv.Close()
	c := New(Options{BaseURL: srv.URL, HTTPClient: srv.Client()})
	_, _ = c.RenderPNG(context.Background(), zpl, RenderOptions{})
}

// Go re-sends custom headers on redirect and strips only Authorization and
// Cookie on a host change; X-API-Key must never reach the redirect target.
func TestRedirectIsNotFollowed(t *testing.T) {
	hits := 0
	other := httptest.NewServer(http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) { hits++ }))
	defer other.Close()
	origin := httptest.NewServer(http.HandlerFunc(
		func(w http.ResponseWriter, r *http.Request) {
			http.Redirect(w, r, other.URL+r.URL.Path, http.StatusTemporaryRedirect)
		}))
	defer origin.Close()

	// Also with a caller's own client, which is left unchanged.
	own := &http.Client{}
	c := New(Options{BaseURL: origin.URL, APIKey: "lbx_test", HTTPClient: own})
	_, err := c.RenderPNG(context.Background(), zpl, RenderOptions{})
	var e *Error
	if !errors.As(err, &e) || e.Status != http.StatusTemporaryRedirect {
		t.Fatalf("err = %v, want *Error with status 307", err)
	}
	if hits != 0 {
		t.Fatalf("redirect target received %d request(s)", hits)
	}
	if own.CheckRedirect != nil {
		t.Fatal("the caller's client was modified")
	}
}

func TestKeyOnlyOverHTTPSOrLoopback(t *testing.T) {
	for _, ok := range []string{"https://api.labelixa.com", "http://127.0.0.1:8000",
		"http://localhost:8000", "http://[::1]:8000"} {
		if New(Options{BaseURL: ok, APIKey: "lbx_test"}).setup != nil {
			t.Errorf("%s refused", ok)
		}
	}
	calls := 0
	for _, bad := range []string{"http://api.labelixa.com", "http://10.0.0.5",
		"http://localhost.evil.test"} {
		c := New(Options{BaseURL: bad, APIKey: "lbx_test",
			HTTPClient: &http.Client{Transport: roundTrip(func(*http.Request) {
				calls++
			})}})
		_, err := c.RenderPNG(context.Background(), zpl, RenderOptions{})
		if err == nil || !strings.Contains(err.Error(), "https") {
			t.Errorf("%s: err = %v", bad, err)
		}
	}
	if calls != 0 {
		t.Fatalf("%d request(s) went out over plain http with a key", calls)
	}
	if New(Options{BaseURL: "http://x"}).setup != nil {
		t.Error("anonymous plain http refused")
	}
}

type roundTrip func(*http.Request)

func (f roundTrip) RoundTrip(r *http.Request) (*http.Response, error) {
	f(r)
	return nil, errors.New("no network in tests")
}

func TestTrailingSlashInBaseURLDoesNotDoubleUp(t *testing.T) {
	c := New(Options{BaseURL: "https://example.test/"})
	if c.baseURL != "https://example.test" {
		t.Fatalf("baseURL = %q", c.baseURL)
	}
}
