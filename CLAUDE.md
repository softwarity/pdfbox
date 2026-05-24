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
  lowest-priority default `font-family` style (prepended so author CSS wins), and — as a guard-rail
  for the accessible "A" levels (PDF/UA) — fills in a `<title>` (from the source title, else first
  `<h1>/<h2>/<h3>`, else `pdfbox.default-title`), an `<html lang>` (`pdfbox.default-lang`) and a
  `<meta name="subject">` when the source omits them. Note it must be `subject`, not `description`:
  openhtmltopdf maps the PDF/UA description to the PDF *Subject* (`<meta name="subject">`). Explicit
  source values always win. Then converts to the W3C DOM that openhtmltopdf consumes. JS never runs.
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

`FontService` **indexes** faces once at startup (`@PostConstruct`) from the classpath (`.ttf` under
`src/main/resources/fonts`, in the jar) and the filesystem dirs in `pdfbox.fonts.directories`
(`/app/fonts,/usr/share/fonts,fonts`). It then **registers a per-render subset** on the builder via
`registerFonts(builder, html)` — not the whole set.

The non-obvious mechanics, all learned the hard way (don't regress them):
- **openhtmltopdf eagerly parses every font it is told about**, on *every* render — both the fallback
  store and every family named in the CSS stack. Registering all faces each time re-parses tens of MB
  per request. Hence per-render selection.
- **Per-render selection by content** (`detectScriptFamilies`): scan the source's codepoints, and for
  each non-Latin Unicode block register only the matching `Noto Sans <Script>` (see `SCRIPT_FAMILIES`,
  a family + `IntPredicate` over codepoints). Latin/Cyrillic/Greek are always covered by the always-on
  families. A family named in `pdfbox.default-font-family` but **not registered** is simply ignored by
  openhtmltopdf (not loaded) — that's why the default stack can list every script for free.
- **Shared CJK Han** (U+4E00…) is ambiguous across JP/KR/SC/TC; `hanFamily` picks the variant from the
  document `lang` (regex over `lang=`), defaulting to Japanese.
- **Fallback store = exactly `Noto Sans`/`Noto Serif`/`Noto Sans Mono`** (`FALLBACK_FAMILIES`,
  `FSFontUseCase.FALLBACK_PRE`). It's eager, so keep it tiny. A non-empty fallback is what silences
  the per-run "Font list is empty" warning and maps the CSS generics `serif`/`sans-serif`/`monospace`
  — openhtmltopdf does **not** consult a font registered under a generic *name*, so the generic mapping
  must go through the fallback store, not `FAMILY_ALIASES`.
- **Only `.ttf` is registered.** OpenType/CFF `.otf` is skipped — PDFBox can't embed CFF and throws.
- **Variable fonts embed at their default master.** For Noto CJK that's Thin, so the Docker
  `cjk-fonts` stage freezes them to a static `wght=400` instance with `fontTools varLib.instancer
  --update-name-table` before placing them under `/usr/share/fonts`. Don't ship a raw CJK VF.
- The big CJK faces are **not in the jar** — they live in the image (filesystem). The bare jar covers
  everything except CJK unless you add CJK `.ttf` yourself.
- Weight/italic are inferred **from the filename** (`weightFromName`/`styleFromName`), not metadata.
- Adding a script: drop its `.ttf` in the jar (light) or image (heavy), add a `SCRIPT_FAMILIES` entry
  with its Unicode block, and name it in `pdfbox.default-font-family`. Verify the family name Java
  reports (`fc-scan`) — e.g. it's `Noto Sans Symbols2`, no space.

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
  Docker Hub (`docker.io/softwarity/pdfbox`) on push. Requires repo secrets `DOCKERHUB_USERNAME`
  and `DOCKERHUB_RW` (a Docker Hub Read & Write access token).
- `.github/workflows/release.yml`: manual `workflow_dispatch` that bumps a semver tag on `main`,
  which triggers the Docker release workflow.
- `Dockerfile` is a two-stage Maven→JRE build; `/app/fonts` is the drop-in dir for extra fonts.
