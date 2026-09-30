# Labelixa (Java)

Thin Java client for the Labelixa API: render, validate and convert
thermal printer label code — ZPL, EPL, TSPL and CPCL — without a printer.

```xml
<dependency>
  <groupId>com.labelixa</groupId>
  <artifactId>labelixa</artifactId>
  <version>0.1.0</version>
</dependency>
```

```java
import com.labelixa.LabelixaClient;
import com.labelixa.QuotaExceededException;

LabelixaClient client = new LabelixaClient();                                 // anonymous
LabelixaClient client = LabelixaClient.builder()
    .apiKey(System.getenv("LABELIXA_API_KEY"))
    .build();

byte[] png = client.renderPng("^XA^FO50,50^A0N,40,40^FDHello^FS^XZ");
Files.write(Path.of("label.png"), png);

Map<String, Object> report = client.validate(zpl);
Map<String, Object> summary = (Map<String, Object>) report.get("ozet");
System.out.println(summary.get("error") + " errors");
```

Requires Java 11. No dependencies.

## Backing off correctly

Quota answers have their own exception, because a caller that cannot tell
429 from a generic failure either never retries or retries immediately —
and both are wrong.

```java
try {
    byte[] png = client.renderPng(zpl);
} catch (QuotaExceededException e) {
    Thread.sleep(e.getRetryAfter() * 1000L);
    // e.getAction() is "upgrade" or "addon" when the server suggests one
}
```

Every other failure is a `LabelixaException` with the HTTP status and the
server's own message, verbatim. A transport failure (no connection,
timeout) has status `0` and the `IOException` as its cause. The
exceptions are unchecked: the quota answer is the one failure worth a
dedicated catch.

## Using your application's HttpClient

Pass your own `java.net.http.HttpClient` — with your proxy, executor or
SSL context. It is used as-is and never mutated; headers travel on each
request, so the API key stays on the requests this client makes and never
leaks onto a client shared with the rest of your application.

```java
LabelixaClient labels = LabelixaClient.builder()
    .apiKey("lbx_...")
    .clientName("erp-connector/2.1")
    .httpClient(myHttpClient)
    .requestTimeout(Duration.ofSeconds(30))
    .build();
```

`clientName` is sent as `X-Client` so usage is attributed to the
integration, never to a person.

Redirects are never followed: a followed redirect re-sends the request
headers, `X-API-Key` included, to whatever host the `Location` names. The
default client uses `HttpClient.Redirect.NEVER`; with an API key, a client
of your own must use it too, or `build()` throws
`IllegalArgumentException`. The key is only sent over https (plain `http`
is accepted for `localhost` only).

## What the client does

| Method | Endpoint |
|---|---|
| `renderPng` | one label as PNG (ZPL, EPL, TSPL, CPCL) |
| `renderPdf` | ZPL as PDF; `allLabels(true)` puts every label in one document |
| `validate` | the linter's structured report |
| `toEpl` | ZPL translated to EPL2 |
| `barcode` | a standalone barcode as SVG or PNG |
| `detectLanguage` | which printer language raw code is |
| `compatibility` | risk against a printer model, e.g. `zebra/zd421` |

Every method maps 1:1 to a documented REST endpoint and there are no
hidden retries: a retry that swallows a quota answer turns a clear signal
into a slow mystery. <https://labelixa.com/docs/api> is the source of
truth.

Reports come back as `Map<String, Object>` rather than typed classes on
purpose: the server owns that shape and adds fields over time, and a
class here would silently drop whatever this version has not heard of.
Objects are `Map`, arrays `List`, integral numbers `Long`, other numbers
`Double`. The reader is `com.labelixa.Json` and is part of the public API
should you need it for another Labelixa answer.

Two server rules the client states rather than softens: a whole-document
PDF costs one quota unit per label, and an invalid barcode is not a 4xx —
the server answers 200 with an error image and an `X-Warnings` header,
which the client turns into a `LabelixaException` so an unusable image is
never handed back as a barcode.

## Sizes

`size(2.25, 4)` addresses `2.25x4`; `size(4, 6)` addresses `4x6`. The
formatting is locale-independent: on a German or Turkish machine a
locale-aware formatter would write `2,25` and address a different label.

## Running the tests

The tests are a plain `main` class — no test framework, no network (a JDK
`HttpServer` on localhost plays the API):

```bash
mvn -q package
javac -d target/test-classes -cp target/classes test/Tests.java
java -cp target/classes:target/test-classes Tests
```

## Release

```bash
mvn -P release deploy
```

Needs a GPG key and Central Portal credentials under the server id
`central` in `~/.m2/settings.xml`. The profile builds the source and
Javadoc jars, signs everything and uploads to the portal without
auto-publishing; the last step is a click in the portal.

## License

MIT
