# Release Notes

## NEXT RELEASE

---

## 1.1.0

### Added
- **`GET /api/v1/pdf-info?standard=<STANDARD>`** — returns a self-describing PDF that is itself
  generated in the requested standard and documents the constraints that standard enforces:
  the conformance level (B/U/A), the rules common to every PDF/A part, the part-specific rules
  (parts 1/2/3) and how the file satisfies them. Doubles as a living spec and an end-to-end smoke
  test. The response is served `inline` so it opens straight in the browser.

### Changed
- An invalid `standard` value now returns a clear **400** that names the offending value and lists
  every accepted standard, e.g. `Invalid value 'foo' for parameter 'standard'. Supported values:
  NONE, PDF_A_1A, PDF_A_1B, …`. The handler is generic for any enum query parameter.

### Tests
- Added [veraPDF](https://verapdf.org/) (the ISO reference validator) integration tests: each
  standard's `pdf-info` output is validated against that same standard — 8/8 conformant, including
  the accessible "A" levels — with a negative control proving the validator actually discriminates.
  veraPDF and the JAXB runtime it needs on JDK 21 are **test-scoped only**: no impact on the jar or
  the Docker image.

### CI / release
- Releases are now cut with the [`softwarity/release-flow`](https://github.com/softwarity/release-flow)
  action: curated notes are taken from this file's `## NEXT RELEASE` section and the `pom.xml` version
  is synced automatically from the tag (`maven_docker` mode), so no manual version bump is needed.
- The version tag is pushed with a PAT (so it actually triggers the Docker release).
- The Docker release workflow's manual (re)publish trigger was adjusted, and header documentation
  badges now link to their relevant external pages.

---

## 1.0.0

First stable release — a single, fully offline Spring Boot service that turns HTML into PDF.

- **PDF/A across the board**: parts 1/2/3 and levels A/B/U (`PDF_A_1A`, `PDF_A_1B`, `PDF_A_2A`,
  `PDF_A_2B`, `PDF_A_2U`, `PDF_A_3A`, `PDF_A_3B`, `PDF_A_3U`) plus plain `NONE`. Embedded fonts,
  embedded sRGB output intent, XMP PDF/A identification, PDF 1.4 + classic cross-reference table for
  part 1, PDF/UA tagging for the "A" levels.
- **Endpoints**: `POST /api/v1/pdf` (HTML body), `POST /api/v1/pdf/upload` (multipart),
  `GET /api/v1/standards`, always-on OpenAPI at `/v3/api-docs`, dev-only Swagger UI, an
  `/actuator/health` probe and a browser test page at `/`.
- **Offline multi-script rendering**: a broad bundled **Noto** font set (Latin/Vietnamese, Hebrew,
  Arabic, the major Indic and SE-Asian scripts, CJK, symbols, mono emoji) with per-render font
  selection by Unicode block and CJK Han disambiguation from the document `lang`.
- **Packaging & ops**: multi-arch **Docker** image (amd64 / arm64) on Docker Hub, a minimal **Helm**
  chart, and a GitHub Pages documentation site.

---
