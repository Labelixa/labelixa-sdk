# Changelog

All notable changes to this package are documented here. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the
project uses [semantic versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Security
- Redirects are never followed. The default `HttpClient` is built with
  `HttpClient.Redirect.NEVER`; with an API key, a caller's `HttpClient`
  that follows redirects is refused with `IllegalArgumentException`,
  because a followed redirect re-sends `X-API-Key` to the new host.
- The API key is only sent over https. `build()` with a key and a plain
  `http` base URL throws `IllegalArgumentException`, except for
  `localhost` and loopback addresses (local development).

## [0.1.0] - 2026-09-23

### Added
- `LabelixaClient` with `renderPng`, `renderPdf`, `validate`, `toEpl`,
  `barcode`, `detectLanguage` and `compatibility`, each mapping to one
  documented REST endpoint.
- `QuotaExceededException` for 402 and 429, carrying the retry delay and
  the server's action hint, so a caller can back off instead of guessing.
- A builder that accepts the application's own `java.net.http.HttpClient`;
  it is used as-is and never mutated, so an API key cannot leak onto a
  client shared elsewhere.
- A small built-in JSON reader for the server's reports: the JDK has no
  JSON API and the package has no dependencies.
