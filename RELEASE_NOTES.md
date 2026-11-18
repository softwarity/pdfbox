# Release Notes

## NEXT RELEASE

### ⚠️ Breaking: remote resources are no longer fetched by default
Until now any `http(s)://` URL in the HTML (images, stylesheets, `@font-face`, SVG) was fetched — and
`file:`, `jar:` and `classpath:` URLs were read too. **Nothing is fetched anymore unless its host is
listed in `PDFBOX_FETCH_ALLOWED_HOSTS`** (empty by default), and `file:` / `jar:` / `classpath:` are
always refused. A document citing a resource that cannot be resolved now **fails with a 422** listing
each one and why (`not-allowed`, `not-found`, `timeout`, `too-large`, `fetch-error`). If you relied on
remote fetching: attach the files to the request (see below), inline them as `data:` URIs, or list the
hosts in `PDFBOX_FETCH_ALLOWED_HOSTS`; `?onMissingResource=report` restores "render anyway" and lists
the missing resources in the `X-Pdfbox-Unresolved-Resources` / `X-Pdfbox-Unresolved-Count` headers.

### Added
- **Attached resources (multipart).** `POST /api/v1/pdf` now also accepts `multipart/form-data`, like
  `POST /api/v1/pdf/upload`: the HTML as `file`, plus any number of `resources` parts — CSS, PNG,
  JPEG, GIF, WebP, SVG, TrueType fonts — cited by their plain file name
  (`<img src="sigwx.svg">`, `url(logo-font.ttf)`). Names, duplicates and content types are validated
  (400), sizes are capped by `PDFBOX_UPLOAD_MAX_PART_SIZE` (20MB) / `PDFBOX_UPLOAD_MAX_TOTAL_SIZE`
  (50MB) (413). Attachments live for one request and are read only when rendered (never copied as a
  whole); by default nothing is written to disk, and `PDFBOX_UPLOAD_MEMORY_THRESHOLD` can send larger
  parts to a temporary file instead of memory. A single `file` part works as before.
- **SVG**, inline or as a file, drawn as vector art (Apache Batik): scripts never run, Batik reads
  nothing by itself, references inside SVG follow the resource rules, SVG text uses the bundled fonts.
- **Resource allow-list**: `PDFBOX_FETCH_ALLOWED_HOSTS` (exact `host[:port]`), `PDFBOX_FETCH_TIMEOUT`
  (10s), `PDFBOX_FETCH_MAX_BYTES` (10MB). Redirects are re-checked hop by hop; a resource cited several
  times is fetched once.
- **Bounded rendering**: at most `PDFBOX_RENDER_MAX_CONCURRENT` renders at once (default: CPU count);
  beyond `PDFBOX_RENDER_QUEUE_TIMEOUT` (10s) of waiting, `503` with `Retry-After`. Memory use is now
  bounded by this × the upload limit.
- WebP images are converted to PNG (PDF cannot carry WebP).
- Helm chart: `fetch.*`, `upload.*` and `render.*` values.

### Changed
- Java **25** (was 21), Spring Boot **4.1.2** (was 3.5 — out of OSS support since 2026-06-30),
  springdoc 3.2.0. The Docker image runs on a Temurin 25 JRE.
- openhtmltopdf **1.1.114** (was 1.1.37; PDFBox 3.0.8 with the fix for CVE-2026-33929, PDF/UA structure
  fixes, CSS `opacity`/`rgba`/`box-shadow`, some layout refinements — documents may lay out slightly
  differently), jsoup 1.24.1. SVG is drawn by Apache Batik 1.20 (aligned above the 1.17 the engine
  pins) with xmlgraphics-commons 2.12.
- Error bodies are `text/plain;charset=UTF-8`.

### Fixed
- PDF/A output no longer carries `/Interpolate true` on images (forbidden in every PDF/A part).
- **PDF/A-1 and transparency**: PDF/A-1 forbids transparency, so it is now flattened over white —
  images with an alpha channel are composited on white, SVG opacities are folded into solid colours
  (50 % red → `#ff8080`), and anything left is made opaque — instead of producing a non-conformant
  file. Simple flattening: content over a non-white background is approximated as if over white.
  PDF/A-2 and PDF/A-3 keep real transparency.

### Tests
- No test touches the network: a local server counts requests. New suites cover the resource policy,
  multipart, SVG, transparency × every standard, concurrency limits, and a reference multi-script
  document with SVG, PNG and WebP validated with veraPDF in all 8 standards. veraPDF 1.30.4.

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
