# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A single Spring Boot service (`com.softwarity.pdfbox`) that converts HTML to PDF, including
PDF/A (parts 1/2/3, levels A/B/U). Fully offline: no external services, no runtime config, all
fonts and the sRGB profile bundled. The whole public contract is "POST HTML, get an
`application/pdf` back". JDK 21 required.

## Commands

```bash
mvn spring-boot:run                                    # run from sources at :8080
mvn spring-boot:run -Dspring-boot.run.profiles=dev     # + Swagger UI at /swagger-ui.html
mvn package && java -jar target/pdfbox.jar             # build the fat jar, then run it
mvn verify                                             # full build + tests (what CI runs)
mvn test -Dtest=PdfGenerationIntegrationTest           # one test class
mvn test -Dtest=PdfGenerationIntegrationTest#rendersPlainPdf   # one test method
```

Swagger UI is **dev-profile only**; the OpenAPI JSON at `/v3/api-docs` is always served.

## The rendering pipeline

Everything funnels through `PdfGenerationService.render(html, standard, baseUri)`. The flow:

```
HTML ──HtmlNormalizer(jsoup)──► W3C DOM ──openhtmltopdf/PdfRendererBuilder──► PDF (PDFBox)
     ──(PDF/A only) normalizeVersion()──► bytes
```

- **`HtmlNormalizer`** parses possibly-malformed HTML with jsoup, injects a UTF-8 `<meta>` and a
  lowest-priority default `font-family` style (prepended so author CSS wins), then converts to the
  W3C DOM that openhtmltopdf consumes. JavaScript is never executed.
- **`PdfGenerationService`** drives `PdfRendererBuilder`: fast mode, register fonts, and for PDF/A
  set conformance + embed the sRGB output intent (+ PDF/UA tagging for the "A" levels).
- **`normalizeVersion()`** is a PDFBox post-pass that only runs for PDF/A. It re-saves with
  `NO_COMPRESSION` to force a classic cross-reference table (object/xref streams are illegal in
  PDF/A-1), strips stale xref-stream trailer keys, and **patches the `%PDF-1.x` header bytes
  in place** because PDFBox refuses to downgrade the header line. The in-place patch relies on the
  replacement string being the same length (1.4 ↔ 1.7) to keep xref byte offsets valid. Touch this
  carefully and re-validate output with veraPDF (`verapdf --flavour 1b document.pdf`).

## PdfAStandard

`PdfAStandard` is the single source of truth mapping public enum values → openhtmltopdf
`PdfAConformance` + the required PDF version (1.4 for part 1, 1.7 for parts 2/3) + whether the level
is accessible/tagged ("A"). To add or change a standard, edit this enum; the controller exposes its
`values()` at `GET /api/v1/standards` automatically.

## Fonts (the tricky part)

`FontService` discovers faces **once at startup** (`@PostConstruct`) from two sources and
re-registers them on every render so all glyphs get embedded (PDF/A requirement):
1. **Bundled** `.ttf` under `src/main/resources/fonts` (a broad Noto set), loaded from the classpath
   into memory — these ship inside the jar.
2. **Filesystem** dirs from `pdfbox.fonts.directories` (`/app/fonts,/usr/share/fonts,fonts`),
   walked recursively.

Hard constraints to respect:
- **Only `.ttf` is registered.** OpenType/CFF `.otf` files are deliberately skipped — the PDFBox
  renderer cannot embed CFF outlines and throws mid-document. Don't "fix" this by adding `.otf`.
- Weight and italic/normal style are inferred **from the filename** (`weightFromName` /
  `styleFromName`), not the font metadata.
- `FAMILY_ALIASES` maps the bundled `Noto Sans JP` to the canonical `Noto Sans CJK *` names so CJK
  text doesn't render as tofu when CSS asks for the CJK family.
- Per-glyph fallback walks the CSS font stack (the default stack lives in `application.yaml` →
  `pdfbox.default-font-family`), which is why one document can mix Latin/Hebrew/Arabic/Thai/CJK.

## Web layer

- `PdfController` (`/api/v1`): `POST /pdf` (HTML body), `POST /pdf/upload` (multipart, the form
  Swagger renders as a file picker), `GET /standards`. Both POSTs share `pdfResponse(...)` and fall
  back to the configured default standard when none is given.
- `GlobalExceptionHandler` maps `PdfGenerationException` and the binding/argument exceptions to a
  plain-text **400**. New user-input failure modes should map here, not bubble as 500s.
- `server.servlet.context-path` (`PDFBOX_BASE_PATH`) prefixes **every** route and is fixed at
  launch — it is not a per-request value.

## Tests

`PdfGenerationIntegrationTest` is a `@SpringBootTest` + `MockMvc` test. `src/test/resources/`
overrides the font config to a tiny Liberation Sans set (`target/test-classes/fonts`) for speed, so
the full Noto bundle isn't loaded during tests. Tests assert PDF structure by inspecting raw bytes
(header version, `pdfaid:part` in the XMP) rather than parsing — keep that style.

## CI / release

- `.github/workflows/ci.yml`: `mvn verify` on every push/PR, then builds & pushes a Docker image to
  GHCR on push.
- `.github/workflows/release.yml`: manual `workflow_dispatch` that bumps a semver tag on `main`,
  which triggers the Docker release workflow.
- `Dockerfile` is a two-stage Maven→JRE build; `/app/fonts` is the drop-in dir for extra fonts.
