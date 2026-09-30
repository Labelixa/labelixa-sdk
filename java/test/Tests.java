import com.labelixa.BarcodeOptions;
import com.labelixa.Json;
import com.labelixa.LabelixaClient;
import com.labelixa.LabelixaException;
import com.labelixa.PdfOptions;
import com.labelixa.QuotaExceededException;
import com.labelixa.RenderOptions;
import com.labelixa.ValidateOptions;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// What is measured here: a thin client goes to the RIGHT address with the
// RIGHT headers, and turns the server's answers into the right types.
//
// No JUnit: the package has no runtime dependency and adding one only for
// the tests would put a dependency resolution step in front of every
// build. No network either: a JDK HttpServer on 127.0.0.1 catches every
// request, the Java counterpart of Go's httptest.
public final class Tests {

    // ---- fake server ------------------------------------------------------

    static HttpServer server;
    static String base;

    static int status = 200;
    static byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
    static Map<String, String> responseHeaders = new LinkedHashMap<>();

    static int calls;
    static String method;
    static String path;       // path + query, exactly as the client sent it
    static Map<String, String> requestHeaders;
    static String requestBody;

    static void reset() {
        status = 200;
        body = "OK".getBytes(StandardCharsets.UTF_8);
        responseHeaders = new LinkedHashMap<>();
        calls = 0;
        method = null;
        path = null;
        requestHeaders = null;
        requestBody = null;
    }

    static void answer(int s, String b) {
        status = s;
        body = b.getBytes(StandardCharsets.UTF_8);
    }

    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            calls++;
            method = ex.getRequestMethod();
            path = ex.getRequestURI().getRawPath()
                + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
            requestHeaders = new LinkedHashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> requestHeaders.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
            try (InputStream in = ex.getRequestBody()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                in.transferTo(buf);
                requestBody = buf.toString(StandardCharsets.UTF_8);
            }
            for (Map.Entry<String, String> h : responseHeaders.entrySet()) {
                ex.getResponseHeaders().add(h.getKey(), h.getValue());
            }
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static LabelixaClient client() {
        return LabelixaClient.builder().baseUrl(base).apiKey("lbx_test").build();
    }

    // ---- tiny harness -----------------------------------------------------

    interface Body {
        void run() throws Exception;
    }

    static final List<String> failures = new ArrayList<>();
    static int passed;

    static void test(String name, Body b) {
        reset();
        try {
            b.run();
            passed++;
            System.out.println("ok   " + name);
        } catch (Throwable t) {
            failures.add(name + ": " + t);
            System.out.println("FAIL " + name + ": " + t);
        }
    }

    static void check(boolean cond, String what) {
        if (!cond) {
            throw new AssertionError(what);
        }
    }

    static void equal(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> got <" + actual + ">");
        }
    }

    static <T extends Throwable> T expect(Class<T> type, Body b) throws Exception {
        try {
            b.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
    }

    static final String ZPL = "^XA^FO50,50^ADN,36,20^FDHello^FS^XZ";

    // ---- the tests --------------------------------------------------------

    public static void main(String[] args) throws Exception {
        start();
        try {
            run();
        } finally {
            server.stop(0);
        }
        System.out.println();
        System.out.println(passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    static void run() throws Exception {
        test("render uses the Labelary-shaped path", () -> {
            answer(200, "PNG");
            byte[] png = client().renderPng(ZPL);
            equal("PNG", new String(png, StandardCharsets.UTF_8), "body");
            equal("POST", method, "method");
            equal("/v1/printers/8dpmm/labels/4x6/0", path, "path");
            equal("text/plain", requestHeaders.get("content-type"), "content type");
            equal(ZPL, requestBody, "request body");
            equal("lbx_test", requestHeaders.get("x-api-key"), "key");
            check(requestHeaders.get("user-agent").startsWith("labelixa-java/"), "user agent");
        });

        test("a fractional size keeps its decimals, an integer loses them", () -> {
            client().renderPng(ZPL, new RenderOptions().dpmm(12).size(2.25, 4).index(1));
            equal("/v1/printers/12dpmm/labels/2.25x4/1", path, "path");
        });

        test("a comma-decimal locale cannot change the address", () -> {
            Locale before = Locale.getDefault();
            Locale.setDefault(Locale.GERMANY);
            try {
                client().renderPng(ZPL, new RenderOptions().size(2.25, 4));
                equal("/v1/printers/8dpmm/labels/2.25x4/0", path, "path");
                equal("4", LabelixaClient_number(4.0), "integer");
                equal("0.5", LabelixaClient_number(0.5), "half");
            } finally {
                Locale.setDefault(before);
            }
        });

        test("the rotation header is sent only when asked for", () -> {
            client().renderPng(ZPL);
            check(!requestHeaders.containsKey("x-rotation"), "no header by default");
            client().renderPng(ZPL, new RenderOptions().rotation(90));
            equal("90", requestHeaders.get("x-rotation"), "header when asked");
        });

        test("EPL, TSPL and CPCL use their own endpoint", () -> {
            for (String lang : new String[] {"epl", "tspl", "cpcl"}) {
                client().renderPng("N\nP1", new RenderOptions().language(lang).index(2));
                equal("/v1/" + lang + "/render?index=2", path, lang + " path");
                check(!requestHeaders.containsKey("x-rotation"), "no rotation header");
            }
        });

        test("an unknown language never reaches the network", () -> {
            LabelixaException e = expect(LabelixaException.class,
                () -> client().renderPng(ZPL, new RenderOptions().language("dpl")));
            equal(0, calls, "calls");
            equal(0, e.getStatus(), "status");
            check(e.getServerMessage().contains("dpl"), "names the language");
            check(e.getServerMessage().contains("zpl, epl, tspl, cpcl"), "lists the choices");
            expect(LabelixaException.class,
                () -> client().validate(ZPL, new ValidateOptions().language("dpl")));
            equal(0, calls, "validate calls");
        });

        test("a PDF of every label drops the index", () -> {
            client().renderPdf(ZPL, new PdfOptions().allLabels(true));
            equal("/v1/printers/8dpmm/labels/4x6/", path, "path");
            equal("application/pdf", requestHeaders.get("accept"), "accept");
            client().renderPdf(ZPL, new PdfOptions().index(3));
            equal("/v1/printers/8dpmm/labels/4x6/3", path, "indexed path");
        });

        test("validation sends the parameters the server actually reads", () -> {
            answer(200, "{\"diagnostics\":[],\"ozet\":{\"error\":0}}");
            client().validate(ZPL, new ValidateOptions().dpmm(12).size(2.25, 4));
            equal("/v1/diagnostics?dpmm=12&w=2.25&h=4", path, "zpl path");
            equal(ZPL, requestBody, "body");
            client().validate("N\nP1", new ValidateOptions().language("tspl"));
            equal("/v1/tspl/diagnostics", path, "tspl path");
        });

        test("the report is the server's structure, nothing dropped", () -> {
            answer(200, "{\"diagnostics\":[{\"code\":\"ZPL-001\",\"line\":2}],"
                + "\"ozet\":{\"error\":2,\"warning\":0},\"new_field\":true}");
            Map<String, Object> report = client().validate(ZPL);
            @SuppressWarnings("unchecked")
            Map<String, Object> ozet = (Map<String, Object>) report.get("ozet");
            equal(2L, ozet.get("error"), "error count");
            equal(Boolean.TRUE, report.get("new_field"), "unknown field kept");
            @SuppressWarnings("unchecked")
            List<Object> diagnostics = (List<Object>) report.get("diagnostics");
            equal(1, diagnostics.size(), "diagnostics");
        });

        test("a quota answer is its own exception carrying delay and hint", () -> {
            answer(429, "Daily quota exhausted");
            responseHeaders.put("Retry-After", "30");
            responseHeaders.put("X-Quota-Action", "upgrade");
            QuotaExceededException e = expect(QuotaExceededException.class,
                () -> client().renderPng(ZPL));
            equal(429, e.getStatus(), "status");
            equal(30, e.getRetryAfter(), "retry after");
            equal("upgrade", e.getAction(), "action");
            equal("Daily quota exhausted", e.getServerMessage(), "message");
            check(e instanceof LabelixaException, "is a LabelixaException too");

            answer(402, "Add-on required");
            responseHeaders.put("Retry-After", "5");
            responseHeaders.put("X-Quota-Action", "addon");
            QuotaExceededException e2 = expect(QuotaExceededException.class,
                () -> client().validate(ZPL));
            equal(402, e2.getStatus(), "402 status");
            equal("addon", e2.getAction(), "402 action");
        });

        test("without Retry-After the delay falls back", () -> {
            answer(429, "slow down");
            QuotaExceededException e = expect(QuotaExceededException.class,
                () -> client().renderPng(ZPL));
            equal(60, e.getRetryAfter(), "fallback");
            equal("", e.getAction(), "no hint");
        });

        test("400 is not a quota answer and keeps the server's message", () -> {
            answer(400, "ZPL must start with ^XA");
            LabelixaException e = expect(LabelixaException.class, () -> client().renderPng(ZPL));
            check(!(e instanceof QuotaExceededException), "not quota");
            equal(400, e.getStatus(), "status");
            equal("ZPL must start with ^XA", e.getServerMessage(), "message");
            equal("HTTP 400: ZPL must start with ^XA", e.getMessage(), "toString form");
        });

        test("a long error body is capped", () -> {
            StringBuilder big = new StringBuilder();
            for (int i = 0; i < 2000; i++) {
                big.append('x');
            }
            answer(502, big.toString());
            LabelixaException e = expect(LabelixaException.class, () -> client().renderPng(ZPL));
            equal(500, e.getServerMessage().length(), "cap");
        });

        test("a barcode warning is an error, not a barcode", () -> {
            answer(200, "<svg/>");
            responseHeaders.put("X-Warnings", "EAN-13 needs 12 or 13 digits");
            LabelixaException e = expect(LabelixaException.class,
                () -> client().barcode("12", new BarcodeOptions().type("ean13")));
            equal(200, e.getStatus(), "status");
            check(e.getServerMessage().contains("EAN-13 needs 12 or 13 digits"), "warning verbatim");
        });

        test("barcode defaults and query", () -> {
            answer(200, "<svg/>");
            byte[] svg = client().barcode("ABC 123/4");
            equal("<svg/>", new String(svg, StandardCharsets.UTF_8), "body");
            equal("GET", method, "method");
            equal("/v1/barcodes?type=code128&data=ABC+123%2F4&format=svg", path, "path");
            client().barcode("x", new BarcodeOptions().type("qr").format("png"));
            equal("/v1/barcodes?type=qr&data=x&format=png", path, "explicit path");
        });

        test("the EPL translation asks for the EPL media type", () -> {
            answer(200, "N\nA50,50,0,4,1,1,N,\"Hello\"\nP1\n");
            String epl = client().toEpl(ZPL, new RenderOptions().dpmm(12).size(2.25, 4));
            check(epl.startsWith("N\n"), "document");
            equal("/v1/printers/12dpmm/labels/2.25x4/", path, "path");
            equal("application/epl", requestHeaders.get("accept"), "accept");
        });

        test("compatibility passes the model through", () -> {
            answer(200, "{\"risk\":\"low\",\"findings\":[]}");
            Map<String, Object> report = client().compatibility(ZPL, "zebra/zd421");
            equal("/v1/compatibility?model=zebra%2Fzd421", path, "path");
            equal("low", report.get("risk"), "risk");
            equal(ZPL, requestBody, "body");
        });

        test("language detection posts the code as-is", () -> {
            answer(200, "{\"language\":\"zpl\",\"confidence\":\"high\"}");
            Map<String, Object> out = client().detectLanguage(ZPL);
            equal("/v1/language-detect", path, "path");
            equal("high", out.get("confidence"), "tier, not a number");
        });

        test("an anonymous client sends no key and no client name", () -> {
            new LabelixaClientAnonymous().renderPng(ZPL);
            check(!requestHeaders.containsKey("x-api-key"), "no key");
            check(!requestHeaders.containsKey("x-client"), "no client name");
        });

        test("the client name travels as X-Client", () -> {
            LabelixaClient.builder().baseUrl(base).clientName("erp-connector/2.1").build().renderPng(ZPL);
            equal("erp-connector/2.1", requestHeaders.get("x-client"), "header");
        });

        test("a trailing slash on the base address is not doubled", () -> {
            LabelixaClient.builder().baseUrl(base + "/").build().renderPng(ZPL);
            equal("/v1/printers/8dpmm/labels/4x6/0", path, "path");
        });

        test("a non-JSON answer does not pass silently", () -> {
            answer(200, "<html>maintenance</html>");
            LabelixaException e = expect(LabelixaException.class, () -> client().validate(ZPL));
            check(e.getServerMessage().contains("not a JSON object"), "says why");
            answer(200, "[1,2]");
            expect(LabelixaException.class, () -> client().validate(ZPL));
        });

        test("the key only travels over https or to loopback", () -> {
            for (String ok : new String[] {"https://api.labelixa.com", "http://127.0.0.1:8000",
                    "http://localhost:8000", "http://[::1]:8000"}) {
                LabelixaClient.builder().baseUrl(ok).apiKey("lbx_test").build();
            }
            for (String bad : new String[] {"http://api.labelixa.com", "http://10.0.0.5",
                    "http://localhost.evil.test"}) {
                IllegalArgumentException e = expect(IllegalArgumentException.class,
                    () -> LabelixaClient.builder().baseUrl(bad).apiKey("lbx_test").build());
                check(e.getMessage().contains("https"), "says why: " + bad);
            }
            LabelixaClient.builder().baseUrl("http://x").build();   // anonymous: allowed
        });

        test("a redirect is not followed", () -> {
            answer(307, "");
            responseHeaders.put("Location", "http://127.0.0.1:1/elsewhere");
            LabelixaException e = expect(LabelixaException.class, () -> client().renderPng(ZPL));
            equal(307, e.getStatus(), "status");
            equal(1, calls, "only the original request");
        });

        test("a caller's HttpClient that follows redirects is refused with a key", () -> {
            java.net.http.HttpClient follows = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
            expect(IllegalArgumentException.class, () -> LabelixaClient.builder()
                .baseUrl(base).apiKey("lbx_test").httpClient(follows).build());
            LabelixaClient.builder().baseUrl(base).httpClient(follows).build();   // anonymous
        });

        test("a transport failure has status 0 and a cause", () -> {
            LabelixaClient dead = LabelixaClient.builder().baseUrl("http://127.0.0.1:1").build();
            LabelixaException e = expect(LabelixaException.class, () -> dead.renderPng(ZPL));
            equal(0, e.getStatus(), "status");
            check(e.getCause() != null, "cause kept");
        });

        test("the JSON reader handles the shapes the server sends", () -> {
            Object v = Json.parse(" {\"a\": [1, 2.5, -3, 1e2, \"x\\ny\\u00e9\", true, false, null], "
                + "\"b\": {\"c\": {}}, \"d\": []} ");
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            @SuppressWarnings("unchecked")
            List<Object> a = (List<Object>) m.get("a");
            equal(1L, a.get(0), "long");
            equal(2.5, a.get(1), "double");
            equal(-3L, a.get(2), "negative");
            equal(100.0, a.get(3), "exponent");
            equal("x\nyé", a.get(4), "escapes");
            equal(Boolean.TRUE, a.get(5), "true");
            equal(Boolean.FALSE, a.get(6), "false");
            equal(null, a.get(7), "null");
            check(m.get("b") instanceof Map, "nested object");
            check(m.get("d") instanceof List, "empty array");
            equal(9223372036854775807L, Json.parse("9223372036854775807"), "long max");
            for (String bad : new String[] {"", "{", "{\"a\":}", "[1,]", "tru", "{\"a\":1} x", "\"open"}) {
                expect(IllegalArgumentException.class, () -> Json.parse(bad));
            }
        });
    }

    // The client keeps number() package-private; the address tests above
    // already prove it through the path, this is the direct check for the
    // locale test.
    static String LabelixaClient_number(double v) throws Exception {
        java.lang.reflect.Method m = LabelixaClient.class.getDeclaredMethod("number", double.class);
        m.setAccessible(true);
        return (String) m.invoke(null, v);
    }

    // The no-argument constructor targets the hosted API; the anonymous
    // test needs the fake server, so it uses the builder without a key.
    static final class LabelixaClientAnonymous {
        byte[] renderPng(String code) {
            return LabelixaClient.builder().baseUrl(base).build().renderPng(code);
        }
    }
}
