# Changelog

All notable changes to this module are documented here. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the
project uses [semantic versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Security
- Redirects are never followed, also with a caller's `HTTPClient` (a
  shallow copy with its own `CheckRedirect` is used; the caller's client
  is not modified). Go strips only `Authorization` and `Cookie` when a
  redirect changes host, so the `X-API-Key` header would have been
  carried along. A 3xx surfaces as an `*Error` with its status.
- The API key is only sent over https. With a key and a plain `http` base
  URL other than `localhost` or a loopback address, every call returns an
  error before any request.

## [0.1.0] - 2026-09-22

### Added
- `Client` with `RenderPNG`, `RenderPDF`, `Validate`, `ToEPL`,
  `Barcode`, `DetectLanguage` and `Compatibility`, each mapping to one
  documented REST endpoint.
- `QuotaError` for 402 and 429, carrying `RetryAfter` and the server's
  action hint, so a caller can back off instead of guessing.
- Standard library only; every call takes a `context.Context`.
