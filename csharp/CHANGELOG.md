# Changelog

All notable changes to this package are documented here. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the
project uses [semantic versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Security
- The default `HttpClient` no longer follows redirects
  (`AllowAutoRedirect = false`). A followed redirect keeps custom request
  headers, so `X-API-Key` would have reached the new host. A 3xx surfaces
  as a `LabelixaException` with its status.
- The API key is only sent over https. A client with a key and a plain
  `http` base URL throws `ArgumentException`, except for `localhost` and
  loopback addresses (local development).

## [0.1.0] - 2026-09-22

### Added
- `LabelixaClient` with `RenderPngAsync`, `RenderPdfAsync`,
  `ValidateAsync`, `ToEplAsync`, `BarcodeAsync`, `DetectLanguageAsync`
  and `CompatibilityAsync`, each mapping to one documented REST endpoint.
- `QuotaExceededException` for 402 and 429, carrying the retry delay and
  the server's action hint, so a caller can back off instead of guessing.
- An optional `HttpClient` parameter for applications that bring their
  own HTTP stack; the supplied client is used as-is and never mutated,
  so an API key cannot leak onto a client shared elsewhere.
- No package references: everything used ships with .NET 8.
