package com.labelixa;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Talks to the Labelixa API. Immutable and safe to share between threads.
 *
 * <pre>{@code
 * LabelixaClient client = LabelixaClient.builder()
 *     .apiKey(System.getenv("LABELIXA_API_KEY"))
 *     .clientName("erp-connector/2.1")
 *     .build();
 *
 * byte[] png = client.renderPng("^XA^FO50,50^A0N,40,40^FDHello^FS^XZ");
 * }</pre>
 *
 * <p>Every method maps 1:1 to a documented REST endpoint and there are no
 * hidden retries. Failures are {@link LabelixaException}; a used-up quota
 * is {@link QuotaExceededException}, its own type, so the caller can back
 * off for the delay the server asked for.
 */
public final class LabelixaClient {

    /** Version of this client, sent in the {@code User-Agent} header. */
    public static final String VERSION = "0.1.0";

    /**
     * The hosted API. Point {@link Builder#baseUrl(String)} at your own
     * instance for an on-premise deployment.
     */
    public static final String DEFAULT_BASE_URL = "https://api.labelixa.com";

    /** Languages the API renders and lints. ZPL is the default. */
    public static final List<String> LANGUAGES =
        Collections.unmodifiableList(Arrays.asList("zpl", "epl", "tspl", "cpcl"));

    private static final int ERROR_BODY_CAP = 500;

    private final String baseUrl;
    private final Map<String, String> headers;
    private final HttpClient http;
    private final Duration requestTimeout;

    /** Creates an anonymous client against the hosted API: free, rate limited per IP. */
    public LabelixaClient() {
        this(new Builder());
    }

    private LabelixaClient(Builder b) {
        String base = b.baseUrl == null || b.baseUrl.isEmpty() ? DEFAULT_BASE_URL : b.baseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base;
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", "labelixa-java/" + VERSION);
        boolean withKey = b.apiKey != null && !b.apiKey.isEmpty();
        if (withKey) {
            checkKeyTransport(base);
            h.put("X-API-Key", b.apiKey);
        }
        if (b.clientName != null && !b.clientName.isEmpty()) {
            h.put("X-Client", b.clientName);
        }
        this.headers = Collections.unmodifiableMap(h);
        // Headers go on each REQUEST, never on the HttpClient: a caller's
        // shared HttpClient must not start carrying this key on every other
        // call it makes. (java.net.http keeps no default headers anyway,
        // which makes that the natural shape here.)
        //
        // Redirects are never followed: a followed redirect re-sends the
        // request headers, X-API-Key included, to whatever host the
        // Location names. The default client is built with NEVER; a
        // caller's client that follows redirects cannot be changed (it is
        // never mutated), so it is refused when a key is set.
        if (withKey && b.httpClient != null
                && b.httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("the HttpClient follows redirects "
                + "(" + b.httpClient.followRedirects() + "); with an API key it must use "
                + "HttpClient.Redirect.NEVER so the key cannot be forwarded to another host");
        }
        this.http = b.httpClient != null ? b.httpClient
            : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.requestTimeout = b.requestTimeout;
    }

    /**
     * The API key travels only over https, or plain http to a loopback
     * address (local development). Anywhere else it would cross the
     * network readable, so the client refuses to be built.
     */
    private static void checkKeyTransport(String base) {
        URI u;
        try {
            u = URI.create(base);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid base URL: " + base, e);
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("https")) {
            return;
        }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        boolean loopback = host.equals("localhost") || host.equals("::1")
            || host.equals("0:0:0:0:0:0:0:1") || host.matches("127(\\.\\d{1,3}){3}");
        if (scheme.equals("http") && loopback) {
            return;
        }
        throw new IllegalArgumentException("refusing to send the API key to " + base
            + ": use an https base URL (plain http is accepted only for localhost)");
    }

    /**
     * Starts building a client.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Configures a {@link LabelixaClient}. Building never reaches the network. */
    public static final class Builder {
        private String apiKey;
        private String baseUrl;
        private String clientName;
        private HttpClient httpClient;
        private Duration requestTimeout = Duration.ofSeconds(60);

        private Builder() {
        }

        /**
         * Sets the {@code lbx_} key. Absent means anonymous: free, rate
         * limited per IP.
         *
         * @param apiKey the key
         * @return this
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the API address; defaults to {@link #DEFAULT_BASE_URL}.
         *
         * @param baseUrl the address, with or without a trailing slash
         * @return this
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets the {@code X-Client} value, for example
         * {@code "erp-connector/2.1"}, so usage is attributed to the
         * integration, never to a person.
         *
         * @param clientName the integration name
         * @return this
         */
        public Builder clientName(String clientName) {
            this.clientName = clientName;
            return this;
        }

        /**
         * Supplies the application's own {@link HttpClient} (proxy, executor,
         * SSL context). It is used as-is and never mutated.
         *
         * @param httpClient the client to send with
         * @return this
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /**
         * Sets the per-request timeout; defaults to 60 seconds. Rendering a
         * large label takes seconds, so the default has room.
         *
         * @param timeout the timeout
         * @return this
         */
        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = timeout;
            return this;
        }

        /**
         * Builds the client.
         *
         * @return the client
         */
        public LabelixaClient build() {
            return new LabelixaClient(this);
        }
    }

    // ----------------------------------------------------------- render --

    /**
     * Renders one ZPL label to PNG with the defaults (8 dpmm, 4x6 inches).
     *
     * @param code the label code
     * @return the PNG bytes
     * @throws LabelixaException on any failure, {@link QuotaExceededException} on 402/429
     */
    public byte[] renderPng(String code) {
        return renderPng(code, new RenderOptions());
    }

    /**
     * Renders one label to PNG.
     *
     * @param code the label code
     * @param o density, size, index and rotation
     * @return the PNG bytes
     * @throws LabelixaException on any failure, {@link QuotaExceededException} on 402/429
     */
    public byte[] renderPng(String code, RenderOptions o) {
        String language = checkLanguage(o.language);
        HttpResponse<byte[]> res;
        if (language.equals("zpl")) {
            Map<String, String> extra = new LinkedHashMap<>();
            if (o.rotation != 0) {
                extra.put("X-Rotation", Integer.toString(o.rotation));
            }
            String path = "/v1/printers/" + o.dpmm + "dpmm/labels/"
                + number(o.widthIn) + "x" + number(o.heightIn) + "/" + o.index;
            res = post(path, code, extra);
        } else {
            res = post("/v1/" + language + "/render?index=" + o.index, code, null);
        }
        return body(res);
    }

    /**
     * Renders a ZPL label to PDF with the defaults.
     *
     * @param zpl the ZPL code
     * @return the PDF bytes
     * @throws LabelixaException on any failure, {@link QuotaExceededException} on 402/429
     */
    public byte[] renderPdf(String zpl) {
        return renderPdf(zpl, new PdfOptions());
    }

    /**
     * Renders ZPL to PDF; {@link PdfOptions#allLabels(boolean)} puts every
     * label in one document at one quota unit per label.
     *
     * @param zpl the ZPL code
     * @param o density, size and which label(s)
     * @return the PDF bytes
     * @throws LabelixaException on any failure, {@link QuotaExceededException} on 402/429
     */
    public byte[] renderPdf(String zpl, PdfOptions o) {
        String suffix = o.allLabels ? "" : Integer.toString(o.index);
        String path = "/v1/printers/" + o.dpmm + "dpmm/labels/"
            + number(o.widthIn) + "x" + number(o.heightIn) + "/" + suffix;
        return body(post(path, zpl, Collections.singletonMap("Accept", "application/pdf")));
    }

    // --------------------------------------------------------- validate --

    /**
     * Lints ZPL with the default geometry.
     *
     * @param code the label code
     * @return the server's report, see {@link #validate(String, ValidateOptions)}
     * @throws LabelixaException on any failure, {@link QuotaExceededException} on 402/429
     */
    public Map<String, Object> validate(String code) {
        return validate(code, new ValidateOptions());
    }

    /**
     * Lints label code and returns the server's structured report: a
     * diagnostics list plus a summary with error, warning and info counts.
     *
     * <p>The report is a {@code Map} rather than a typed class on purpose:
     * the server owns that shape and adds fields over time, and a class
     * here would silently drop whatever this version has not heard of.
     * <a href="https://labelixa.com/docs/api">https://labelixa.com/docs/api</a>
     * documents the fields.
     *
     * @param code the label code
     * @param o the linter and, for ZPL, the geometry the checks assume
     * @return the report
     * @throws LabelixaException on any failure, {@link QuotaExceededException} on 402/429
     */
    public Map<String, Object> validate(String code, ValidateOptions o) {
        String language = checkLanguage(o.language);
        String path;
        if (language.equals("zpl")) {
            // The endpoint reads the label size from w and h. Any other
            // spelling is ignored silently by the server, so every check
            // would quietly run against the 4x6 default.
            path = "/v1/diagnostics?dpmm=" + o.dpmm
                + "&w=" + number(o.widthIn) + "&h=" + number(o.heightIn);
        } else {
            path = "/v1/" + language + "/diagnostics";
        }
        return json(post(path, code, null));
    }

    // ---------------------------------------------------------- convert --

    /**
     * Translates ZPL to EPL2 with the default geometry.
     *
     * @param zpl the ZPL code
     * @return the EPL2 document
     * @throws LabelixaException on any failure, {@link QuotaExceededException} on 402/429
     */
    public String toEpl(String zpl) {
        return toEpl(zpl, new RenderOptions());
    }

    /**
     * Translates ZPL to EPL2. Fields the translation cannot carry over
     * surface in the warnings of the returned document.
     *
     * @param zpl the ZPL code
     * @param o density and size (index and rotation do not apply)
     * @return the EPL2 document
     * @throws LabelixaException on any failure, {@link QuotaExceededException} on 402/429
     */
    public String toEpl(String zpl, RenderOptions o) {
        String path = "/v1/printers/" + o.dpmm + "dpmm/labels/"
            + number(o.widthIn) + "x" + number(o.heightIn) + "/";
        byte[] out = body(post(path, zpl, Collections.singletonMap("Accept", "application/epl")));
        return new String(out, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------- barcode --

    /**
     * Generates a standalone Code 128 barcode as SVG.
     *
     * @param data the encoded text
     * @return the SVG bytes
     * @throws LabelixaException on any failure, {@link QuotaExceededException} on 402/429
     */
    public byte[] barcode(String data) {
        return barcode(data, new BarcodeOptions());
    }

    /**
     * Generates a standalone barcode.
     *
     * <p>Server contract worth knowing: invalid input is NOT a 4xx. The
     * server answers 200 with an error image and an {@code X-Warnings}
     * header. Handing that back as a generated barcode would be lying to
     * the caller, so it becomes a {@link LabelixaException} carrying the
     * server's warning verbatim.
     *
     * @param data the encoded text
     * @param o symbology and format
     * @return the SVG or PNG bytes
     * @throws LabelixaException on any failure, {@link QuotaExceededException} on 402/429
     */
    public byte[] barcode(String data, BarcodeOptions o) {
        String query = "type=" + encode(o.type) + "&data=" + encode(data)
            + "&format=" + encode(o.format);
        HttpResponse<byte[]> res = send(request("/v1/barcodes?" + query, null).GET().build());
        checkStatus(res);
        String warning = res.headers().firstValue("X-Warnings").orElse("");
        if (!warning.isEmpty()) {
            throw new LabelixaException(res.statusCode(), "barcode not generated: " + warning);
        }
        return res.body();
    }

    // --------------------------------------------------------- analysis --

    /**
     * Guesses the printer language of raw label code.
     *
     * <p>The result carries a confidence TIER (high, medium, low), not a
     * probability: the server does not compute one, and this client does
     * not invent one.
     *
     * @param code the label code
     * @return the server's answer
     * @throws LabelixaException on any failure, {@link QuotaExceededException} on 402/429
     */
    public Map<String, Object> detectLanguage(String code) {
        return json(post("/v1/language-detect", code, null));
    }

    /**
     * Analyses ZPL against a printer model given as {@code manufacturer/model},
     * for example {@code "zebra/zd421"}. The server reads both parts
     * case-insensitively; {@code "zebra-zd421"} is not a valid key and
     * comes back as the server's own 404.
     *
     * <p>It reports RISK. It is not an emulator and never says "this works".
     *
     * @param zpl the ZPL code
     * @param model the printer, as {@code manufacturer/model}
     * @return the server's report
     * @throws LabelixaException on any failure, {@link QuotaExceededException} on 402/429
     */
    public Map<String, Object> compatibility(String zpl, String model) {
        return json(post("/v1/compatibility?model=" + encode(model), zpl, null));
    }

    // ---------------------------------------------------------- plumbing --

    private static String checkLanguage(String language) {
        if (language == null || language.isEmpty()) {
            return "zpl";
        }
        if (LANGUAGES.contains(language)) {
            return language;
        }
        throw new LabelixaException(0, "unknown language '" + language
            + "'; expected one of " + String.join(", ", LANGUAGES));
    }

    /**
     * Formats a dimension the way the API path expects: 4 not 4.0, 2.25
     * unchanged, and always with a dot. {@code String.valueOf(double)} would
     * write {@code 4.0}, and a locale-aware formatter would write
     * {@code 2,25} on a German or Turkish machine, which addresses a
     * different label.
     */
    static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private HttpRequest.Builder request(String path, Map<String, String> extra) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(requestTimeout);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            rb.header(e.getKey(), e.getValue());
        }
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                rb.header(e.getKey(), e.getValue());
            }
        }
        return rb;
    }

    private HttpResponse<byte[]> post(String path, String body, Map<String, String> extra) {
        // Exactly text/plain, no charset: the sibling clients send the same
        // bytes and the server reads the body as UTF-8 either way.
        HttpRequest req = request(path, extra)
            .header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8))
            .build();
        return send(req);
    }

    private HttpResponse<byte[]> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new LabelixaException(0, "transport error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LabelixaException(0, "interrupted while waiting for the API", e);
        }
    }

    private static byte[] body(HttpResponse<byte[]> res) {
        checkStatus(res);
        return res.body();
    }

    private static Map<String, Object> json(HttpResponse<byte[]> res) {
        checkStatus(res);
        String text = new String(res.body(), StandardCharsets.UTF_8);
        try {
            return Json.parseObject(text);
        } catch (IllegalArgumentException e) {
            throw new LabelixaException(res.statusCode(),
                "answer is not a JSON object: " + cap(text), e);
        }
    }

    /**
     * Picks the exception type from the status. The body is capped: an
     * error message is a sentence, and a broken proxy can answer with a
     * megabyte of HTML.
     */
    private static void checkStatus(HttpResponse<byte[]> res) {
        int status = res.statusCode();
        if (status == 200) {
            return;
        }
        String message = cap(new String(res.body(), StandardCharsets.UTF_8));
        if (status == 402 || status == 429) {
            int retry = 60;
            String header = res.headers().firstValue("Retry-After").orElse("");
            try {
                if (!header.isEmpty()) {
                    retry = Integer.parseInt(header.trim());
                }
            } catch (NumberFormatException ignored) {
                // A date-formatted Retry-After keeps the 60 second fallback.
            }
            throw new QuotaExceededException(status, message, retry,
                res.headers().firstValue("X-Quota-Action").orElse(""));
        }
        throw new LabelixaException(status, message);
    }

    private static String cap(String text) {
        return text.length() <= ERROR_BODY_CAP ? text : text.substring(0, ERROR_BODY_CAP);
    }
}
