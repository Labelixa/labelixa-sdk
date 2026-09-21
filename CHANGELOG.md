# Changelog

All notable changes to the `labelixa` npm package are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project uses [Semantic Versioning](https://semver.org/).

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
