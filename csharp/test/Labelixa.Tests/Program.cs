using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Labelixa;

// What is measured here: a thin client goes to the RIGHT address with the
// RIGHT headers, and turns the server's answers into the right types.
//
// No network: a fake HttpMessageHandler catches every request. That is the
// natural seam in .NET, which is why the client takes an HttpClient rather
// than inventing a transport interface of its own.

internal sealed class FakeHandler : HttpMessageHandler
{
    private readonly HttpStatusCode _status;
    private readonly byte[] _body;
    private readonly IDictionary<string, string> _responseHeaders;

    public FakeHandler(HttpStatusCode status = HttpStatusCode.OK,
        string body = "OK", IDictionary<string, string>? responseHeaders = null)
    {
        _status = status;
        _body = Encoding.UTF8.GetBytes(body);
        _responseHeaders = responseHeaders ?? new Dictionary<string, string>();
    }

    public int Calls { get; private set; }

    public HttpRequestMessage? Request { get; private set; }

    public string? RequestBody { get; private set; }

    protected override async Task<HttpResponseMessage> SendAsync(
        HttpRequestMessage request, CancellationToken cancellationToken)
    {
        Calls++;
        Request = request;
        RequestBody = request.Content == null
            ? null
            : await request.Content.ReadAsStringAsync().ConfigureAwait(false);

        var response = new HttpResponseMessage(_status)
        {
            Content = new ByteArrayContent(_body),
        };
        foreach (var header in _responseHeaders)
        {
            response.Headers.TryAddWithoutValidation(header.Key, header.Value);
        }

        return response;
    }

    public string Url => Request!.RequestUri!.ToString();

    public string? Header(string name)
    {
        if (Request!.Headers.TryGetValues(name, out var values))
        {
            return values.First();
        }

        if (Request.Content != null
            && Request.Content.Headers.TryGetValues(name, out var contentValues))
        {
            return contentValues.First();
        }

        return null;
    }
}

internal static class Program
{
    private const string Zpl = "^XA^FO50,50^A0N,40,40^FDHello^FS^XZ";

    private static int _passed;
    private static readonly List<string> Failures = new();

    private static int Main()
    {
        RunAsync().GetAwaiter().GetResult();
        Console.WriteLine(_passed + " tests passed");
        if (Failures.Count == 0)
        {
            return 0;
        }

        foreach (var failure in Failures)
        {
            Console.Error.WriteLine("FAILED: " + failure);
        }

        return 1;
    }

    private static async Task RunAsync()
    {
        await Test("render uses the Labelary-shaped path", async () =>
        {
            var handler = new FakeHandler(body: "\u0089PNG");
            var client = Client(handler, apiKey: "lbx_test", clientName: "suite/1");
            var png = await client.RenderPngAsync(Zpl);
            Equal("\u0089PNG", Encoding.UTF8.GetString(png));
            Equal("https://example.test/v1/printers/8dpmm/labels/4x6/0", handler.Url);
            Equal(Zpl, handler.RequestBody, "the code must travel unchanged");
            Equal("lbx_test", handler.Header("X-API-Key"));
            Equal("suite/1", handler.Header("X-Client"));
            Equal("text/plain", handler.Header("Content-Type"));
            Equal("labelixa-dotnet/" + LabelixaClient.Version,
                handler.Header("User-Agent"));
        }).ConfigureAwait(false);

        await Test("a fractional size keeps its decimals, an integer loses them",
            async () =>
        {
            var handler = new FakeHandler();
            await Client(handler).RenderPngAsync(Zpl, widthIn: 2.25, heightIn: 4)
                .ConfigureAwait(false);
            Equal("https://example.test/v1/printers/8dpmm/labels/2.25x4/0",
                handler.Url);
        }).ConfigureAwait(false);

        await Test("a comma-decimal culture cannot change the address", async () =>
        {
            // The defect this locks: under tr-TR or de-DE a plain
            // ToString() writes "2,25", the client addresses a label
            // nobody asked for, and the render comes back looking fine.
            var previous = CultureInfo.CurrentCulture;
            CultureInfo.CurrentCulture = new CultureInfo("tr-TR");
            try
            {
                var handler = new FakeHandler();
                await Client(handler)
                    .RenderPngAsync(Zpl, widthIn: 2.25, heightIn: 4)
                    .ConfigureAwait(false);
                Equal("https://example.test/v1/printers/8dpmm/labels/2.25x4/0",
                    handler.Url);
            }
            finally
            {
                CultureInfo.CurrentCulture = previous;
            }
        }).ConfigureAwait(false);

        await Test("the rotation header is sent only when asked for", async () =>
        {
            var handler = new FakeHandler();
            var client = Client(handler);
            await client.RenderPngAsync(Zpl).ConfigureAwait(false);
            Equal(null, handler.Header("X-Rotation"));
            await client.RenderPngAsync(Zpl, rotation: 90).ConfigureAwait(false);
            Equal("90", handler.Header("X-Rotation"));
        }).ConfigureAwait(false);

        await Test("EPL, TSPL and CPCL use their own endpoint", async () =>
        {
            foreach (var language in new[] { "epl", "tspl", "cpcl" })
            {
                var handler = new FakeHandler();
                await Client(handler)
                    .RenderPngAsync("N\nP1\n", language: language, index: 2)
                    .ConfigureAwait(false);
                Equal("https://example.test/v1/" + language + "/render?index=2",
                    handler.Url);
            }
        }).ConfigureAwait(false);

        await Test("an unknown language never reaches the network", async () =>
        {
            var handler = new FakeHandler();
            try
            {
                await Client(handler).RenderPngAsync(Zpl, language: "pcl")
                    .ConfigureAwait(false);
                throw new InvalidOperationException("unknown language accepted");
            }
            catch (LabelixaException error)
            {
                Equal(0, handler.Calls, "no request should have been sent");
                Equal(true, error.Message.Contains("pcl"));
            }
        }).ConfigureAwait(false);

        await Test("a PDF of every label drops the index", async () =>
        {
            var handler = new FakeHandler();
            await Client(handler).RenderPdfAsync(Zpl, index: null)
                .ConfigureAwait(false);
            Equal("https://example.test/v1/printers/8dpmm/labels/4x6/", handler.Url);
            Equal("application/pdf", handler.Header("Accept"));
        }).ConfigureAwait(false);

        await Test("validation sends the parameters the server actually reads",
            async () =>
        {
            // The endpoint reads w and h. Any other spelling is ignored
            // silently, so a wrong one would lint the 4x6 default while
            // the caller believes it measured their label.
            var handler = new FakeHandler(body: "{\"ozet\":{\"error\":0}}");
            var report = await Client(handler)
                .ValidateAsync(Zpl, dpmm: 12, widthIn: 3, heightIn: 2)
                .ConfigureAwait(false);
            Equal(0, report.GetProperty("ozet").GetProperty("error").GetInt32());
            Equal("https://example.test/v1/diagnostics?dpmm=12&w=3&h=2",
                handler.Url);
        }).ConfigureAwait(false);

        await Test("the report survives the document it was parsed from",
            async () =>
        {
            // JsonElement borrows memory from its JsonDocument; without the
            // Clone in the client this read would touch freed memory.
            var handler = new FakeHandler(body: "{\"summary\":{\"error\":2}}");
            var report = await Client(handler).ValidateAsync(Zpl)
                .ConfigureAwait(false);
            GC.Collect();
            GC.WaitForPendingFinalizers();
            Equal(2, report.GetProperty("summary").GetProperty("error").GetInt32());
        }).ConfigureAwait(false);

        await Test("a quota answer is its own exception carrying delay and hint",
            async () =>
        {
            var handler = new FakeHandler(HttpStatusCode.TooManyRequests,
                "daily quota used", new Dictionary<string, string>
                {
                    ["Retry-After"] = "42",
                    ["X-Quota-Action"] = "upgrade",
                });
            try
            {
                await Client(handler).RenderPngAsync(Zpl).ConfigureAwait(false);
                throw new InvalidOperationException("the quota answer was swallowed");
            }
            catch (QuotaExceededException error)
            {
                Equal(42, error.RetryAfter);
                Equal("upgrade", error.Action);
                Equal(true, error.Message.Contains("daily quota used"));
            }
        }).ConfigureAwait(false);

        await Test("without Retry-After the delay falls back", async () =>
        {
            var handler = new FakeHandler(HttpStatusCode.PaymentRequired, string.Empty);
            try
            {
                await Client(handler).RenderPngAsync(Zpl).ConfigureAwait(false);
                throw new InvalidOperationException("402 was swallowed");
            }
            catch (QuotaExceededException error)
            {
                Equal(60, error.RetryAfter);
                Equal(null, error.Action);
            }
        }).ConfigureAwait(false);

        await Test("400 is not a quota answer and keeps the server's message",
            async () =>
        {
            var handler = new FakeHandler(HttpStatusCode.BadRequest, "label too large");
            try
            {
                await Client(handler).RenderPngAsync(Zpl).ConfigureAwait(false);
                throw new InvalidOperationException("400 was swallowed");
            }
            catch (QuotaExceededException)
            {
                throw new InvalidOperationException("400 became a quota exception");
            }
            catch (LabelixaException error)
            {
                Equal(400, error.Status);
                Equal(true, error.Message.Contains("label too large"));
            }
        }).ConfigureAwait(false);

        await Test("a long error body is capped", async () =>
        {
            var handler = new FakeHandler(HttpStatusCode.BadGateway,
                new string('x', 4000));
            try
            {
                await Client(handler).RenderPngAsync(Zpl).ConfigureAwait(false);
                throw new InvalidOperationException("502 was swallowed");
            }
            catch (LabelixaException error)
            {
                Equal(500, error.ServerMessage.Length);
            }
        }).ConfigureAwait(false);

        await Test("a barcode warning is an error, not a barcode", async () =>
        {
            // The server answers 200 with an error image and X-Warnings;
            // handing that back as a generated barcode would be a lie.
            var handler = new FakeHandler(HttpStatusCode.OK, "<svg/>",
                new Dictionary<string, string>
                {
                    ["X-Warnings"] = "invalid data for ean13",
                });
            try
            {
                await Client(handler).BarcodeAsync("abc", type: "ean13")
                    .ConfigureAwait(false);
                throw new InvalidOperationException("the warning was swallowed");
            }
            catch (LabelixaException error)
            {
                Equal(true, error.Message.Contains("invalid data for ean13"));
            }
        }).ConfigureAwait(false);

        await Test("barcode defaults and query", async () =>
        {
            var handler = new FakeHandler(HttpStatusCode.OK, "<svg/>");
            var output = await Client(handler).BarcodeAsync("12345")
                .ConfigureAwait(false);
            Equal("<svg/>", Encoding.UTF8.GetString(output));
            Equal("GET", handler.Request!.Method.Method);
            foreach (var part in new[] { "type=code128", "data=12345", "format=svg" })
            {
                Equal(true, handler.Url.Contains(part), part);
            }
        }).ConfigureAwait(false);

        await Test("the EPL translation asks for the EPL media type", async () =>
        {
            var handler = new FakeHandler(HttpStatusCode.OK, "N\nP1\n");
            var epl = await Client(handler).ToEplAsync(Zpl).ConfigureAwait(false);
            Equal("N\nP1\n", epl);
            Equal("application/epl", handler.Header("Accept"));
            Equal("https://example.test/v1/printers/8dpmm/labels/4x6/", handler.Url);
        }).ConfigureAwait(false);

        await Test("compatibility passes the model through", async () =>
        {
            var handler = new FakeHandler(HttpStatusCode.OK, "{\"risk\":[]}");
            await Client(handler).CompatibilityAsync(Zpl, "zebra/zd421")
                .ConfigureAwait(false);
            Equal(true, handler.Url.Contains("model=zebra%2Fzd421"), handler.Url);
        }).ConfigureAwait(false);

        await Test("an anonymous client sends no key", async () =>
        {
            var handler = new FakeHandler();
            await Client(handler).RenderPngAsync(Zpl).ConfigureAwait(false);
            Equal(null, handler.Header("X-API-Key"));
            Equal(null, handler.Header("X-Client"));
        }).ConfigureAwait(false);

        await Test("a trailing slash on the base address is not doubled", async () =>
        {
            var handler = new FakeHandler();
            var client = new LabelixaClient(baseUrl: "https://example.test/",
                httpClient: new HttpClient(handler));
            await client.RenderPngAsync(Zpl).ConfigureAwait(false);
            Equal("https://example.test/v1/printers/8dpmm/labels/4x6/0", handler.Url);
        }).ConfigureAwait(false);

        await Test("a supplied HttpClient is never stamped with the key", async () =>
        {
            // An application's shared client would otherwise carry the key
            // into every other call it makes.
            var handler = new FakeHandler();
            var shared = new HttpClient(handler);
            var client = new LabelixaClient(apiKey: "lbx_secret",
                baseUrl: "https://example.test", httpClient: shared);
            await client.RenderPngAsync(Zpl).ConfigureAwait(false);
            Equal(false, shared.DefaultRequestHeaders.Contains("X-API-Key"));
        }).ConfigureAwait(false);

        await Test("the key only travels over https or to loopback", () =>
        {
            foreach (var ok in new[] { "https://api.labelixa.com", "http://127.0.0.1:8000",
                                       "http://localhost:8000", "http://[::1]:8000" })
            {
                _ = new LabelixaClient(apiKey: "lbx_test", baseUrl: ok,
                    httpClient: new HttpClient(new FakeHandler()));
            }

            foreach (var bad in new[] { "http://api.labelixa.com", "http://10.0.0.5",
                                        "http://localhost.evil.test" })
            {
                try
                {
                    _ = new LabelixaClient(apiKey: "lbx_test", baseUrl: bad,
                        httpClient: new HttpClient(new FakeHandler()));
                    throw new InvalidOperationException("key accepted for " + bad);
                }
                catch (ArgumentException error)
                {
                    Equal(true, error.Message.Contains("https"));
                }
            }

            _ = new LabelixaClient(baseUrl: "http://x",
                httpClient: new HttpClient(new FakeHandler()));   // anonymous: allowed
            return Task.CompletedTask;
        }).ConfigureAwait(false);

        await Test("a redirect surfaces as an error with its status", async () =>
        {
            var handler = new FakeHandler(HttpStatusCode.TemporaryRedirect, "",
                new Dictionary<string, string> { ["Location"] = "http://evil.test/" });
            try
            {
                await Client(handler, apiKey: "lbx_test").RenderPngAsync(Zpl).ConfigureAwait(false);
                throw new InvalidOperationException("a redirect was accepted");
            }
            catch (LabelixaException error)
            {
                Equal(307, error.Status);
                Equal(1, handler.Calls);
            }
        }).ConfigureAwait(false);

        await Test("a non-JSON answer does not pass silently", async () =>
        {
            var handler = new FakeHandler(HttpStatusCode.OK, "<html>maintenance</html>");
            try
            {
                await Client(handler).DetectLanguageAsync(Zpl).ConfigureAwait(false);
                throw new InvalidOperationException("a non-JSON answer was accepted");
            }
            catch (LabelixaException error)
            {
                Equal(true, error.Message.Contains("not JSON"));
            }
        }).ConfigureAwait(false);
    }

    private static LabelixaClient Client(FakeHandler handler,
        string? apiKey = null, string? clientName = null)
    {
        return new LabelixaClient(apiKey: apiKey, baseUrl: "https://example.test",
            clientName: clientName, httpClient: new HttpClient(handler));
    }

    private static async Task Test(string name, Func<Task> body)
    {
        try
        {
            await body().ConfigureAwait(false);
            _passed++;
        }
        catch (Exception error)
        {
            Failures.Add(name + " -> " + error.Message);
        }
    }

    private static void Equal(object? expected, object? actual, string note = "")
    {
        if (Equals(expected, actual))
        {
            return;
        }

        throw new InvalidOperationException(string.Format(CultureInfo.InvariantCulture,
            "{0}expected {1}, got {2}",
            note.Length == 0 ? string.Empty : note + ": ",
            expected ?? "null", actual ?? "null"));
    }
}
