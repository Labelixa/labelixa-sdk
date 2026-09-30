using System;
using System.Collections.Generic;
using System.Globalization;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace Labelixa
{
    /// <summary>
    /// Thin .NET client for the Labelixa REST API: render, validate and
    /// convert thermal printer label code (ZPL, EPL, TSPL, CPCL) without a
    /// printer.
    /// </summary>
    /// <remarks>
    /// <code>
    /// var c = new LabelixaClient();                     // anonymous
    /// var c = new LabelixaClient(apiKey: "lbx_...");    // account quota
    /// byte[] png = await c.RenderPngAsync(zpl);
    /// JsonElement report = await c.ValidateAsync(zpl);
    /// </code>
    /// Design notes, shared with the Python, Node, Go and PHP clients:
    /// <list type="bullet">
    /// <item>THIN wrapper. Every method maps 1:1 to a documented REST
    /// endpoint. No client-side magic and no hidden retries: a retry that
    /// swallows a quota response turns a clear signal into a slow mystery.
    /// https://labelixa.com/docs/api is the source of truth.</item>
    /// <item>Errors carry the server's own message. Quota exhaustion is
    /// its own exception with the retry delay and the server's action
    /// hint.</item>
    /// <item>No dependencies. Everything used here ships with .NET; a
    /// label client is not worth a package tree in someone else's build.</item>
    /// </list>
    /// </remarks>
    public sealed class LabelixaClient
    {
        /// <summary>Version of this client, sent in the User-Agent header.</summary>
        public const string Version = "0.1.0";

        /// <summary>
        /// The hosted API. Pass your own address for an on-premise
        /// deployment.
        /// </summary>
        public const string DefaultBaseUrl = "https://api.labelixa.com";

        /// <summary>Printer languages the API renders and lints; ZPL is the default.</summary>
        public static readonly IReadOnlyList<string> Languages =
            new[] { "zpl", "epl", "tspl", "cpcl" };

        private readonly string _baseUrl;
        private readonly HttpClient _http;
        private readonly Dictionary<string, string> _headers;

        /// <summary>Builds a client. It never fails and never reaches the network.</summary>
        /// <param name="apiKey">
        /// An lbx_ key. Null means anonymous: free, rate limited per IP.
        /// </param>
        /// <param name="baseUrl">Defaults to <see cref="DefaultBaseUrl"/>.</param>
        /// <param name="clientName">
        /// Sent as X-Client (for example "erp-connector/2.1") so usage is
        /// attributed to the integration, never to a person.
        /// </param>
        /// <param name="timeout">
        /// Applies only to a client built here; a supplied
        /// <paramref name="httpClient"/> keeps its own. Rendering a large
        /// label takes seconds, so the default has room.
        /// </param>
        /// <param name="httpClient">
        /// Your application's own client, for example one from
        /// IHttpClientFactory. It is used as-is and never mutated.
        /// </param>
        public LabelixaClient(
            string? apiKey = null,
            string? baseUrl = null,
            string? clientName = null,
            TimeSpan? timeout = null,
            HttpClient? httpClient = null)
        {
            _baseUrl = (baseUrl ?? DefaultBaseUrl).TrimEnd('/');
            if (!string.IsNullOrEmpty(apiKey))
            {
                CheckKeyTransport(_baseUrl);
            }

            // The default client never follows redirects: a followed
            // redirect keeps custom request headers, so X-API-Key would
            // reach whatever host the Location names. The API does not
            // redirect; a 3xx surfaces as a LabelixaException with its
            // status. A supplied HttpClient keeps its own handler settings.
            _http = httpClient ?? new HttpClient(new HttpClientHandler { AllowAutoRedirect = false })
            {
                Timeout = timeout ?? TimeSpan.FromSeconds(60),
            };
            _headers = new Dictionary<string, string>
            {
                ["User-Agent"] = "labelixa-dotnet/" + Version,
            };
            if (!string.IsNullOrEmpty(apiKey))
            {
                _headers["X-API-Key"] = apiKey!;
            }

            if (!string.IsNullOrEmpty(clientName))
            {
                _headers["X-Client"] = clientName!;
            }
        }

        // The API key travels only over https, or plain http to a loopback
        // address (local development). Anywhere else it would cross the
        // network readable, so the client refuses to be built.
        private static void CheckKeyTransport(string baseUrl)
        {
            if (!Uri.TryCreate(baseUrl, UriKind.Absolute, out var uri))
            {
                throw new ArgumentException("invalid base URL: " + baseUrl, nameof(baseUrl));
            }

            if (uri.Scheme == Uri.UriSchemeHttps)
            {
                return;
            }

            if (uri.Scheme == Uri.UriSchemeHttp && uri.IsLoopback)
            {
                return;
            }

            throw new ArgumentException(
                "refusing to send the API key to " + baseUrl + ": use an https base URL "
                + "(plain http is accepted only for localhost)", nameof(baseUrl));
        }

        /// <summary>
        /// Renders one label to PNG and returns the raw bytes.
        /// </summary>
        /// <remarks>
        /// Density and size apply to ZPL. For EPL, TSPL and CPCL the label
        /// size comes from the code itself and only the index applies.
        /// </remarks>
        public async Task<byte[]> RenderPngAsync(
            string code,
            string language = "zpl",
            int dpmm = 8,
            double widthIn = 4,
            double heightIn = 6,
            int index = 0,
            int rotation = 0,
            CancellationToken cancellationToken = default)
        {
            language = CheckLanguage(language);
            if (language != "zpl")
            {
                var other = string.Format(CultureInfo.InvariantCulture,
                    "/v1/{0}/render?index={1}", language, index);
                var otherResponse = await SendAsync(HttpMethod.Post, other, code,
                    null, cancellationToken).ConfigureAwait(false);
                return otherResponse.Body;
            }

            var extra = rotation != 0
                ? new Dictionary<string, string>
                {
                    ["X-Rotation"] = rotation.ToString(CultureInfo.InvariantCulture),
                }
                : null;
            var path = string.Format(CultureInfo.InvariantCulture,
                "/v1/printers/{0}dpmm/labels/{1}x{2}/{3}",
                dpmm, Number(widthIn), Number(heightIn), index);
            var response = await SendAsync(HttpMethod.Post, path, code, extra,
                cancellationToken).ConfigureAwait(false);
            return response.Body;
        }

        /// <summary>
        /// Renders ZPL to PDF.
        /// </summary>
        /// <remarks>
        /// A null <paramref name="index"/> puts every label of the code in
        /// one stream, which costs one quota unit PER LABEL (a single page
        /// costs one). That is a server rule; this client states it rather
        /// than softening it.
        /// </remarks>
        public async Task<byte[]> RenderPdfAsync(
            string zpl,
            int dpmm = 8,
            double widthIn = 4,
            double heightIn = 6,
            int? index = 0,
            CancellationToken cancellationToken = default)
        {
            var path = string.Format(CultureInfo.InvariantCulture,
                "/v1/printers/{0}dpmm/labels/{1}x{2}/{3}",
                dpmm, Number(widthIn), Number(heightIn),
                index.HasValue
                    ? index.Value.ToString(CultureInfo.InvariantCulture)
                    : string.Empty);
            var response = await SendAsync(HttpMethod.Post, path, zpl,
                new Dictionary<string, string> { ["Accept"] = "application/pdf" },
                cancellationToken).ConfigureAwait(false);
            return response.Body;
        }

        /// <summary>
        /// Lints label code and returns the server's structured report: a
        /// diagnostics list plus a summary with error, warning and info
        /// counts.
        /// </summary>
        /// <remarks>
        /// The report comes back as a <see cref="JsonElement"/> rather than
        /// a typed class on purpose. The server owns that shape and adds
        /// fields over time; a fixed class here would silently drop
        /// whatever this version has not heard of.
        /// https://labelixa.com/docs/api documents the fields.
        /// </remarks>
        public async Task<JsonElement> ValidateAsync(
            string code,
            string language = "zpl",
            int dpmm = 8,
            double widthIn = 4,
            double heightIn = 6,
            CancellationToken cancellationToken = default)
        {
            language = CheckLanguage(language);

            // The ZPL endpoint reads the label size from w and h. Any other
            // spelling is ignored silently by the server, so every check
            // would quietly run against the 4x6 default.
            var path = language == "zpl"
                ? string.Format(CultureInfo.InvariantCulture,
                    "/v1/diagnostics?dpmm={0}&w={1}&h={2}",
                    dpmm, Number(widthIn), Number(heightIn))
                : string.Format(CultureInfo.InvariantCulture,
                    "/v1/{0}/diagnostics", language);
            return await JsonAsync(path, code, cancellationToken)
                .ConfigureAwait(false);
        }

        /// <summary>
        /// Translates ZPL to EPL2. Fields the translation cannot carry over
        /// surface in the warnings of the returned document.
        /// </summary>
        public async Task<string> ToEplAsync(
            string zpl,
            int dpmm = 8,
            double widthIn = 4,
            double heightIn = 6,
            CancellationToken cancellationToken = default)
        {
            var path = string.Format(CultureInfo.InvariantCulture,
                "/v1/printers/{0}dpmm/labels/{1}x{2}/",
                dpmm, Number(widthIn), Number(heightIn));
            var response = await SendAsync(HttpMethod.Post, path, zpl,
                new Dictionary<string, string> { ["Accept"] = "application/epl" },
                cancellationToken).ConfigureAwait(false);
            return Encoding.UTF8.GetString(response.Body);
        }

        /// <summary>
        /// Generates a standalone barcode; SVG is UTF-8 text, PNG is image
        /// bytes.
        /// </summary>
        /// <remarks>
        /// Server contract worth knowing: invalid input is NOT a 4xx. The
        /// server answers 200 with an error image and an X-Warnings header.
        /// Handing that back as a generated barcode would be lying to the
        /// caller, so it becomes an exception carrying the warning verbatim.
        /// </remarks>
        public async Task<byte[]> BarcodeAsync(
            string data,
            string type = "code128",
            string format = "svg",
            CancellationToken cancellationToken = default)
        {
            var path = "/v1/barcodes?type=" + Uri.EscapeDataString(type)
                + "&data=" + Uri.EscapeDataString(data)
                + "&format=" + Uri.EscapeDataString(format);
            var response = await SendAsync(HttpMethod.Get, path, null, null,
                cancellationToken).ConfigureAwait(false);
            if (!string.IsNullOrEmpty(response.Warnings))
            {
                throw new LabelixaException(response.Status,
                    "Barcode not generated: " + response.Warnings);
            }

            return response.Body;
        }

        /// <summary>
        /// Detects the printer language of raw label code.
        /// </summary>
        /// <remarks>
        /// The result carries a confidence TIER (high, medium, low), not a
        /// probability: the server does not compute one and this client
        /// does not invent one.
        /// </remarks>
        public Task<JsonElement> DetectLanguageAsync(string code,
            CancellationToken cancellationToken = default)
        {
            return JsonAsync("/v1/language-detect", code, cancellationToken);
        }

        /// <summary>
        /// Analyses ZPL against a printer model given as manufacturer/model,
        /// for example "zebra/zd421". The server reads Manufacturer/Model
        /// case-insensitively; "zebra-zd421" is not a valid key and comes
        /// back as the server's own 404.
        /// </summary>
        /// <remarks>
        /// It reports RISK. It is not an emulator and never says "this
        /// works".
        /// </remarks>
        public Task<JsonElement> CompatibilityAsync(string zpl, string model,
            CancellationToken cancellationToken = default)
        {
            return JsonAsync("/v1/compatibility?model=" + Uri.EscapeDataString(model),
                zpl, cancellationToken);
        }

        /// <summary>
        /// Formats a dimension the way the API path expects: 4 not 4.0, and
        /// 2.25 unchanged.
        /// </summary>
        /// <remarks>
        /// Invariant culture is not decoration: a machine running with a
        /// comma decimal separator would otherwise address
        /// "/labels/2,25x4/", which is a different address, and the server
        /// would answer for a label nobody asked for.
        /// </remarks>
        private static string Number(double value)
        {
            return value.ToString("0.####", CultureInfo.InvariantCulture);
        }

        private static string CheckLanguage(string language)
        {
            foreach (var known in Languages)
            {
                if (known == language)
                {
                    return language;
                }
            }

            throw new LabelixaException(0, string.Format(CultureInfo.InvariantCulture,
                "Unknown language \"{0}\"; expected one of {1}",
                language, string.Join(", ", Languages)));
        }

        private async Task<JsonElement> JsonAsync(string path, string body,
            CancellationToken cancellationToken)
        {
            var response = await SendAsync(HttpMethod.Post, path, body, null,
                cancellationToken).ConfigureAwait(false);
            try
            {
                using (var document = JsonDocument.Parse(response.Body))
                {
                    // Clone detaches the element from the document being
                    // disposed here; without it the caller would read freed
                    // memory.
                    return document.RootElement.Clone();
                }
            }
            catch (JsonException)
            {
                throw new LabelixaException(response.Status,
                    "Response was not JSON");
            }
        }

        private async Task<Response> SendAsync(HttpMethod method, string path,
            string? body, IDictionary<string, string>? extraHeaders,
            CancellationToken cancellationToken)
        {
            // Headers go on the REQUEST, never on the HttpClient: a client
            // handed in by the caller may be shared across the application,
            // and stamping an API key onto it would leak the key into every
            // other call that client makes.
            using (var request = new HttpRequestMessage(method, _baseUrl + path))
            {
                foreach (var header in _headers)
                {
                    request.Headers.TryAddWithoutValidation(header.Key, header.Value);
                }

                if (body != null)
                {
                    // Exactly "text/plain", with no charset parameter: the
                    // other four clients send that byte for byte, and a
                    // client family whose requests differ per language is a
                    // family of four different bug reports.
                    request.Content = new StringContent(body, Encoding.UTF8,
                        "text/plain");
                    request.Content.Headers.ContentType!.CharSet = null;
                }

                if (extraHeaders != null)
                {
                    foreach (var header in extraHeaders)
                    {
                        request.Headers.TryAddWithoutValidation(
                            header.Key, header.Value);
                    }
                }

                using (var message = await _http
                    .SendAsync(request, cancellationToken).ConfigureAwait(false))
                {
                    var bytes = await message.Content.ReadAsByteArrayAsync()
                        .ConfigureAwait(false);
                    var status = (int)message.StatusCode;
                    if (status != (int)HttpStatusCode.OK)
                    {
                        throw ErrorFor(status, bytes, message);
                    }

                    return new Response(status, bytes, Header(message, "X-Warnings"));
                }
            }
        }

        private static async Task<byte[]> ReadBodyAsync(HttpResponseMessage message)
        {
#if NET8_0_OR_GREATER
            return await message.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
#else
            return await message.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
#endif
        }

        private static LabelixaException ErrorFor(int status, byte[] bytes,
            HttpResponseMessage message)
        {
            // The message is capped: an error is a sentence, and a broken
            // proxy can answer with a megabyte of HTML.
            var text = Encoding.UTF8.GetString(bytes);
            if (text.Length > 500)
            {
                text = text.Substring(0, 500);
            }

            if (status != 402 && status != 429)
            {
                return new LabelixaException(status, text);
            }

            var retryAfter = 60;
            var raw = Header(message, "Retry-After");
            if (raw != null && int.TryParse(raw, NumberStyles.Integer,
                    CultureInfo.InvariantCulture, out var parsed))
            {
                retryAfter = parsed;
            }

            return new QuotaExceededException(status, text, retryAfter,
                Header(message, "X-Quota-Action"));
        }

        private static string? Header(HttpResponseMessage message, string name)
        {
            IEnumerable<string>? values;
            if (message.Headers.TryGetValues(name, out values)
                || (message.Content != null
                    && message.Content.Headers.TryGetValues(name, out values)))
            {
                foreach (var value in values)
                {
                    return value;
                }
            }

            return null;
        }

        private readonly struct Response
        {
            public Response(int status, byte[] body, string? warnings)
            {
                Status = status;
                Body = body;
                Warnings = warnings;
            }

            public int Status { get; }

            public byte[] Body { get; }

            public string? Warnings { get; }
        }
    }
}
