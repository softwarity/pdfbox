# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A single Spring Boot 4 service (`com.softwarity.pdfbox`) that converts HTML to PDF, including
PDF/A (parts 1/2/3, levels A/B/U). Fully offline: no external services, no network access unless
`PDFBOX_FETCH_ALLOWED_HOSTS` lists a host, all fonts and the sRGB profile bundled. The public
contract is "POST HTML (optionally multipart with the files it cites), get an `application/pdf`
back". JDK 25 required.

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

Everything funnels through `PdfGenerationService.render(html, standard, attachments, policy)`
(`render(html, standard)` = no attachments, fail on unresolved). The flow:

```
HTML ──HtmlNormalizer(jsoup, SvgSanitizer)──► W3C DOM ──openhtmltopdf + Batik──► PDF (PDFBox)
     ──(PDF/A only) postProcessPdfA()──► bytes
          ▲ every resource load goes through RenderResources (one per render)
```

- **`HtmlNormalizer`** parses possibly-malformed HTML with jsoup, injects a UTF-8 `<meta>` and a
  lowest-priority default `font-family` style (prepended so author CSS wins), and — as a guard-rail
  for the accessible "A" levels (PDF/UA) — fills in a `<title>` (from the source title, else first
  `<h1>/<h2>/<h3>`, else `pdfbox.default-title`), an `<html lang>` (`pdfbox.default-lang`) and a
  `<meta name="subject">` when the source omits them. Note it must be `subject`, not `description`:
  openhtmltopdf maps the PDF/UA description to the PDF *Subject* (`<meta name="subject">`). Explicit
  source values always win. An inline `<svg>` without `alt` gets one from `aria-label` / its `<title>`
  (openhtmltopdf reads the `Figure` alternate text from `alt` only; PDF/UA §7.3). Then converts to the
  W3C DOM that openhtmltopdf consumes. JS never runs.
- **`PdfGenerationService`** drives `PdfRendererBuilder`: fast mode, register fonts, and for PDF/A
  set conformance + embed the sRGB output intent (+ PDF/UA tagging for the "A" levels and `PDF_UA_1`).
- **`postProcess()`** is a PDFBox post-pass that runs for PDF/A and PDF/UA-1. It clears `/Interpolate`
  on every image (forbidden by all PDF/A parts; Batik paints SVG group `opacity` as an interpolated
  image), runs the PDF/A-1 transparency safety net, and re-saves with `NO_COMPRESSION` — required
  because any re-save with compression would write object/xref streams, illegal in PDF/A-1.
  Version: openhtmltopdf 1.1.114 already writes PDF/A-1 as `%PDF-1.4` with a classic xref table, so
  part 1 needs no version work. Parts 2/3 come out as 1.6 and are raised to 1.7 — only when the
  **header** version (`getDocument().getVersion()`, not `PDDocument.getVersion()`, which also reads
  the catalog) differs: stale xref-stream trailer keys are dropped and the `%PDF-1.x` header bytes are
  **patched in place**, because PDFBox does not rewrite the header line (same length keeps xref
  offsets valid). Re-validate with veraPDF after touching this. It also runs `EmbeddedFiles.register`
  (see Resources).

## Resources (package `resource`) — deny by default

`RenderResources` is, for one render, the `FSUriResolver`, the post-resolution access controller
(`allow`, called for loads and file embeds — not for plain `<a href>` links) and the `FSStreamFactory` for
`data`/`http`/`https`/`pdfbox-attachment`/`pdfbox-missing` (`useProtocolsStreamImplementation`
**replaces** openhtmltopdf's default factory map). Order: attached file by plain name →
`data:` → `http(s)` on an allowed host (`ResourceFetcher`: manual redirects re-checked per hop,
one deadline, size cap, per-render cache). Anything else is refused and **recorded**
(`UnresolvedResource` + reason); `MissingResourcePolicy.FAIL` turns the list into
`UnresolvedResourcesException` → 422, `REPORT` returns it (headers `X-Pdfbox-Unresolved-*`).
**File embeds** (`<a href download>`, `ExternalResourceType.FILE_EMBED`): openhtmltopdf's default
*before-resolving* controller (`NaiveUserAgent.DefaultAccessController`) refuses them **silently**, before
our `allow` runs — so `PdfGenerationService` installs a pass-through before-controller and
`RenderResources` decides: refused + recorded when the standard forbids embedded files
(`PdfAStandard.allowsEmbeddedFiles()`: PDF/A-1 and PDF/A-2 → 422), otherwise resolved like any load and
served **raw** (no sanitising/flattening). `EmbeddedFiles` (post-pass) then adds what openhtmltopdf
leaves out: catalog `/AF`, the `EmbeddedFiles` name tree, default `AFRelationship`/MIME subtype/dates,
re-tags the `Link` struct element of the file-attachment annotation as `Annot` in tagged output
(PDF/UA §7.18.1) and, in PDF/A-3, `FacturX` declares an embedded `factur-x.xml`/`xrechnung.xml` in the
XMP (`fx:` properties + PDF/A extension schema, merged into an existing `pdfaExtension:schemas` bag
when the A levels already have one; profile read from `GuidelineSpecifiedDocumentContextParameter/ID`,
unknown → 400). HTML attributes: `download` (name), `title` (Desc), `relationship`,
`data-content-type`. `AttachmentValidator` accepts `.xml/.json/.csv/.txt` (UTF-8) and `.pdf` for this.
Fetched/attached SVG is passed through `SvgSanitizer` with `svgInliner(base)`, which rewrites every
external reference to a `data:` URI under the same policy; Batik runs as
`BatikSVGDrawer(SECURE, {"data"})`. Gotchas learned the hard way: Batik 1.17 only knows
`xlink:href` (bare `href` is normalised), and an `<image>`/`<use>` left without href makes Batik
drop the **whole** SVG (such elements are removed). WebP is converted to PNG (`MediaTypes`) because
PDFBox cannot embed it. Bundled fonts never go through the resolver; Batik only gets fonts with
`FSFontUseCase.SVG`, added by `FontService` only when the content mentions SVG.

## PdfAStandard

`PdfAStandard` is the single source of truth mapping public enum values → openhtmltopdf
`PdfAConformance` + the required PDF version (1.4 for part 1, 1.7 for parts 2/3 and PDF/UA-1) + whether
the level is accessible/tagged ("A", and `PDF_UA_1`) + `part()` (1/2/3, or 0 for `NONE`/`PDF_UA_1`) and
`level()` (`"A"`/`"B"`/`"U"`, `"—"` otherwise). `PDF_UA_1` is not PDF/A: `isPdfA()` is false
(`conformance == NONE`), `isPdfUa()` true — openhtmltopdf's `usePdfUaAccessibility` alone, which writes
`pdfuaid:part`; tests map it to veraPDF flavour `ua1` (`PdfTestSupport.flavour`). To
add or change a standard, edit this enum; the controller exposes its `values()` at
`GET /api/v1/standards` automatically, and `StandardInfoService` derives its constraint docs from
`part()`/`level()` (`PDF_UA_1` has its own branch there).

`StandardInfoService` builds a self-describing HTML page listing a standard's constraints, composed
from three orthogonal sources (rules common to all PDF/A + part-specific rules + level guarantees) so
it stays complete without a rule database. Rendered through `PdfGenerationService` with that same
standard, it yields a PDF that both documents and conforms to the standard — served by
`GET /api/v1/pdf-info?standard=...`. Its CSS is deliberately transparency-free (no `box-shadow` /
`rgba` / `opacity`) because the same markup is rendered to PDF/A-1, which forbids transparency.

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

- `PdfController` (`/api/v1`): `POST /pdf` (HTML body), `POST /pdf` + `POST /pdf/upload`
  (multipart: `file` = HTML, repeatable `resources` = attached files, validated by
  `AttachmentValidator`), `GET /standards`, and `GET /pdf-info` (self-describing PDF of a
  standard's constraints, served `inline`). They fall back to the configured default standard.
- `MultipartConfig` builds the `MultipartConfigElement` from `UploadProperties` (threshold = max part
  size by default: nothing spills to disk; `pdfbox.upload.memory-threshold` lowers it). An
  `Attachment` holds no bytes, only `Content` (the part's `getInputStream`): never call
  `MultipartFile.getBytes()` on attachments (whole copy) — `AttachmentStreamingTest` enforces it. `RenderLimitFilter` (a filter, so before the multipart body is read)
  admits at most `pdfbox.render.max-concurrent` renders, else 503 + `Retry-After`.
- `GlobalExceptionHandler` maps `PdfGenerationException` and the binding/argument exceptions to a
  plain-text (UTF-8) **400**, upload limits to **413**, unresolved resources to **422**. `MethodArgumentTypeMismatchException` has its own handler that, for any enum
  param (e.g. a bad `?standard=`), names the offending value and lists the accepted values via
  `getRequiredType().getEnumConstants()` — so it stays correct as standards are added. New user-input
  failure modes should map here, not bubble as 500s.
- Every PDF response carries `X-Pdfbox-Engine` (`EngineInfo.header()`: openhtmltopdf / PDFBox / Batik
  versions read from the classpath jars, never hard-coded); the same versions go into the PDF
  *Producer* via `withProducer` (openhtmltopdf writes it to both Info and XMP, so PDF/A stays valid).
  Error responses don't carry it.
- `server.servlet.context-path` (`PDFBOX_BASE_PATH`) prefixes **every** route and is fixed at
  launch — it is not a per-request value.

## Tests

`PdfGenerationIntegrationTest` is a `@SpringBootTest` + `MockMvc` test. `src/test/resources/
application.yaml` **shadows** the main one entirely (main yaml values are not loaded in tests — rely on
`@DefaultValue`s or code, not on main-yaml-only settings). It overrides the font config to a tiny Liberation Sans set (`target/test-classes/fonts`) for speed, so
the full Noto bundle isn't loaded during tests. Tests assert PDF structure by inspecting raw bytes
(header version, `pdfaid:part` in the XMP) rather than parsing — keep that style.

`StandardConformanceValidationTest` is the real conformance gate: it renders the `pdf-info` PDF for
every standard and validates it against that same standard with **veraPDF** (the ISO reference
validator), plus a negative control (a plain PDF must fail PDF/A-1B). veraPDF (`org.verapdf:
validation-model`, on Maven Central) and the JAXB runtime it needs on JDK 25 (`javax.xml.bind:
jaxb-api` + `org.glassfish.jaxb:jaxb-runtime`, since veraPDF declares JAXB as optional and reads its
profiles via `javax.xml.bind` / `com.sun.xml.bind.v2.ContextFactory`) are **test-scoped only** — no
impact on the jar or image. The greenfield foundry entry point is
`org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider` (note the `gf.foundry` package).

Network-related tests use `CountingHttpServer` (local, counts requests) — no test may reach the
real network. `PdfTestSupport` rasterises pages (colour pixel counts), lists embedded fonts/images and
wraps veraPDF. `TransparencyConformanceTest` requires every transparency case to conform in all 8
standards and checks the PDF/A-1 flattening visually (50 % red → pink, no `/SMask`).

`ShowcaseTest` renders the example documents of `src/test/resources/showcase/*.html` (shown in the
README and the doc site's Examples page) with the production Noto font stack (set via
`@SpringBootTest(properties=…)`, since the test yaml shadows it), and checks veraPDF conformance and
the exact page sizes. `-Dshowcase.write=true` rewrites `docs/src/assets/showcase/` (HTML, PDF, PNG
contact sheet); a PDF is only rewritten when its PNG changed (PDFs carry a date/ID). Learned while
building it: with named pages (`page: x` + `@page x { size: … }`) openhtmltopdf lays blocks out
against the **default** page's width — a block on a wider/narrower named page needs an explicit
`width` (`theLandscapeTableSpansTheWholeLandscapePage` guards the documented workaround). Two known
rendering gaps, left out of the showcase: Arabic is not shaped (no `openhtmltopdf-rtl-support`/ICU
configured — letters come out isolated) and Indic conjuncts (Devanagari) are not formed.

PDF/A-1 transparency is flattened in three layers (only when `standard.part() == 1`):
`MediaTypes.flattenOnWhite` for raster images loaded by `RenderResources` (incl. `data:` inside SVG,
which the sanitizer now hands to the inliner), `SvgTransparencyFlattener` for SVG opacities, and
`neutralizeTransparency` in the post-pass as a safety net (CA/ca → 1, SMask → None, BM → Normal,
transparency groups dropped).

## CI / release

- **The published artifact is the Docker image; the git tag is the source of truth for the version.**
  The Maven `<version>` in `pom.xml` is **synced automatically** by the release action (see below) —
  do not hand-bump it. It only lands in the jar manifest (`finalName` is the fixed `pdfbox.jar`, so
  the version never changes the filename).
- `.github/workflows/ci.yml`: `mvn verify` on every push/PR, then builds & pushes a Docker image to
  Docker Hub (`docker.io/softwarity/pdfbox`) on push. Requires repo secrets `DOCKERHUB_USERNAME`
  and `DOCKERHUB_RW` (a Docker Hub Read & Write access token).
- `.github/workflows/release.yml`: manual `workflow_dispatch` (bump = patch/minor/major), serialized
  by a `concurrency` group. It runs `mvn verify` first (no tag on a red main), then the
  `softwarity/release-flow@v1` action with `language: maven_docker` (explicit),
  `maven-image: maven:3-eclipse-temurin-25` and `helm-chart: helm/pdfbox` (auto-detection only looks at
  `helm/Chart.yaml`; this syncs the chart `version` + `appVersion`). In one commit on `main` it: derives the next `vX.Y.Z` from the latest tag, runs
  `mvn versions:set` **in a Maven container** to sync `pom.xml`, renames `## NEXT RELEASE` →
  `## X.Y.Z` in `RELEASE_NOTES.md`, tags + pushes, publishes the GitHub Release, and reopens a fresh
  `## NEXT RELEASE`. The tag is pushed via the **`PAT_TOKEN`** secret wired into `actions/checkout`
  (not the default `GITHUB_TOKEN`, which would not re-trigger the Docker release). `main` must stay
  pushable by that PAT (it's currently unprotected).
- `.github/workflows/docker-release.yml`: triggered on `v*` tags, runs `mvn verify` then calls the
  reusable `_docker.yml` to build & publish the multi-arch (amd64/arm64) image (`latest`, `X.Y.Z`,
  `X.Y`).
- `Dockerfile` is a two-stage Maven→JRE build; `/app/fonts` is the drop-in dir for extra fonts.
