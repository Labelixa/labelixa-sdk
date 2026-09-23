# Changelog

All notable changes to the `labelixa` npm package are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project uses [Semantic Versioning](https://semver.org/).

## [0.3.0] - 2026-09-22

### Added
- CLI rewritten for CI pipelines (`npx labelixa validate "labels/**/*.zpl"`):
  several files and glob patterns per call (`**`, `*`, `?` are expanded by
  the CLI itself, so quoted patterns work on every runner), `--lang`
  (`zpl|epl|tspl|cpcl`, defaulting to the file extension), `--json`
  machine-readable output, `--fail-on error|warning|info|none`,
  `--out-dir` for batch rendering, `--index`, `--version`.
- Stable exit codes: 0 success, 1 findings at or above `--fail-on`,
  2 usage error, 3 API or network error.
- `.pre-commit-hooks.yaml`: a `labelixa-validate` hook for
  [pre-commit](https://pre-commit.com) (`language: node`, label files by
  extension, serial; key optional via `LABELIXA_API_KEY`).
- A 429 response is retried at most twice, visibly, for the server's
  `Retry-After` seconds (30 s cap; longer waits fail fast with exit 3).
- `Client` option `clientName`: sent as the `X-Client` header. The CLI
  sends `cli/<version>` so its usage is attributed to the tool, never to
  a person.
- `renderPng()` and `validate()` accept `language` (`zpl` default,
  `epl`, `tspl`, `cpcl`) and route to the language endpoints;
  `LANGUAGES` exported.

### Changed
- `preview` is now an alias of `render --format png` for a single file
  and still writes PNG to stdout by default.
- Package description and keywords mention the CLI and the four printer
  languages.

## [0.2.3] - 2026-09-13

### Changed
- Internal identifiers and comments translated to English; no API change.
- CLI help and error messages are in English.

### Fixed
- The `User-Agent` header now reports the package version from
  `package.json` instead of a fixed string.

### Added
- Publish guard (`scripts/check-publish.mjs`, run by `prepublishOnly`):
  publishing fails when a shipped file contains internal ticket ids,
  non-English text or unreleased markers.

## [0.2.2] - 2026-09-10

### Fixed
- The MIT license text is now included in the package (`LICENSE` added
  to `files`). No code change.

## [0.2.1] - 2026-08-27

### Fixed
- Documentation: the `compatibility()` example used `zebra-zd421`; the
  API reads `manufacturer/model`, so the example and the JSDoc now use
  `zebra/zd421`.

### Changed
- Package metadata: `repository.url` points to the project repository.

## [0.2.0] - 2026-08-25

### Added
- `labelixa` CLI: `npx labelixa preview|render|validate|info`. A thin
  shell over the SDK; the API key is read only from `LABELIXA_API_KEY`,
  `LABELIXA_API_URL` overrides the base URL, and `validate` exits with
  code 1 on error findings.
- `barcode()` (SVG/PNG; a 200 response carrying an error image and an
  `X-Warnings` header throws `LabelixaError` instead of returning the
  image).
- `languageDetect()` (returns a confidence tier, not a percentage).
- `compatibility()` (risk analysis against a printer model; not an
  emulator).

### Fixed
- `validate()` sends the label size as the `w`/`h` query parameters the
  endpoint reads; the previous spelling was ignored by the server and
  every check ran against the 4x6 default.

## [0.1.0] - 2026-08-10

### Added
- First release: `renderPng`, `renderPdf`, `validate`, `toEpl`.
