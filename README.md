# pdfbox

A tiny, self-contained **Spring Boot** service that turns **HTML into PDF — including PDF/A**
(PDF/A-1a, 1b, 2a, 2b, 2u, 3a, 3b, 3u). Push HTML (and the images, SVG charts, stylesheets and fonts
it cites), get the PDF binary back. No external services, no runtime configuration, no network access
unless you allow it, fonts bundled for offline generation of exotic scripts (Vietnamese, Hebrew,
Arabic, Thai, Devanagari, Japanese/CJK…).

```
POST  your HTML  ──►  pdfbox  ──►  PDF/A binary
```

📖 **Full documentation:** <https://softwarity.github.io/pdfbox/> — getting started, the API & endpoints,
PDF/A standards, fonts & scripts, configuration, Docker (amd64 / arm64) and a Kubernetes (Helm) guide.

## Quick start (Docker)

```bash
docker run --rm -p 8080:8080 softwarity/pdfbox:latest
```

Then open <http://localhost:8080> for a small test page, or call the API directly:

```bash
# PDF/A-1b (default) — HTML in the request body, standard as a query param
curl -X POST "http://localhost:8080/api/v1/pdf?standard=PDF_A_1B" \
     -H "Content-Type: text/html" \
     --data '<h1>Hello</h1><p>Tiếng Việt · עברית · 日本語</p>' \
     -o document.pdf
```

That is the whole contract: send HTML, pick a standard, receive `application/pdf`.

## Build & run locally

Requires JDK 25.

```bash
mvn spring-boot:run          # run from sources
# or
mvn package && java -jar target/pdfbox.jar
```

> A broad Noto font set is bundled **inside the jar**, so multi-script rendering works offline
> the same way whether you run the jar, the IDE, or the Docker image — no system fonts required.

## API

| Method & path | Description |
|---|---|
| `POST /api/v1/pdf` | Body = HTML, **or** `multipart/form-data` (see below). Query params: `standard` (see below, default `PDF_A_1B`), `filename` (default `document.pdf`), `onMissingResource` (`fail` default, or `report`). Returns `application/pdf`. |
| `POST /api/v1/pdf/upload` | `multipart/form-data`: `file` = the HTML, `resources` = files it cites (repeatable), plus `standard`, `filename`, `onMissingResource`. Same as the multipart form of `/pdf`. This is the form Swagger UI shows with file pickers. |
| `GET /api/v1/standards` | Lists the supported standards. |
| `GET /api/v1/pdf-info` | Query param `standard`. Returns a PDF, **conforming to that standard**, that documents the constraints the standard enforces — a self-describing reference page and end-to-end check. |
| `GET /v3/api-docs` | OpenAPI 3 description (JSON). |
| `GET /swagger-ui.html` | Interactive Swagger UI — **dev profile only** (see below). |
| `GET /actuator/health` | Health probe. |
| `GET /` | Browser test page. |

Supported `standard` values: `NONE`, `PDF_A_1A`, `PDF_A_1B`, `PDF_A_2A`, `PDF_A_2B`, `PDF_A_2U`,
`PDF_A_3A`, `PDF_A_3B`, `PDF_A_3U`.

```bash
# Upload an HTML file (no JavaScript — it is not executed) and get a PDF/A-2b back:
curl -X POST "http://localhost:8080/api/v1/pdf/upload" \
     -F "file=@invoice.html;type=text/html" \
     -F "standard=PDF_A_2B" \
     -o invoice.pdf
```

### HTML with attached files (multipart)

Send the HTML as the `file` part and every file it cites as a `resources` part — stylesheets
(`.css`), images (`.png`, `.jpg`/`.jpeg`, `.gif`, `.webp`, `.svg`) and TrueType fonts (`.ttf`). The
HTML cites them by their **plain file name**; the part's file name is what counts:

```html
<link rel="stylesheet" href="page.css">
<style>@font-face { font-family: Logo; src: url(logo-font.ttf) }</style>
<img src="sigwx.svg" alt="Significant weather"> <img src="wind.png" alt="Winds aloft">
```

```bash
curl -X POST "http://localhost:8080/api/v1/pdf?standard=PDF_A_2U" \
     -F "file=@briefing.html;type=text/html" \
     -F "resources=@page.css" \
     -F "resources=@sigwx.svg" \
     -F "resources=@wind.png" \
     -F "resources=@logo-font.ttf" \
     -o briefing.pdf
```

Attached files live for that request only. They are never copied as a whole: each one is read when
the renderer needs it. By default they stay in memory and nothing is written to disk; set
`PDFBOX_UPLOAD_MEMORY_THRESHOLD` to send larger parts to a temporary file (deleted at the end of the
request) when RAM is tighter than disk. A name with `/`, `\`,
`..` or a control character, a duplicate name, an unknown part or a file whose content does not match
its type is a **400**; above `PDFBOX_UPLOAD_MAX_PART_SIZE` / `PDFBOX_UPLOAD_MAX_TOTAL_SIZE` it is a
**413**. WebP is converted to PNG (PDF cannot carry WebP). A request with only `file` behaves exactly
as before.

### Resources: resolved, or refused — never silently dropped

Everything the document cites — stylesheets, `@font-face`, images, SVG files and the references inside
SVGs — is resolved in this order:

1. an **attached file**, by plain name (`logo.png`, `./logo.png`);
2. a **`data:` URI**;
3. an **`http(s)` URL**, *only* if its host is in `PDFBOX_FETCH_ALLOWED_HOSTS` (empty by default:
   **nothing is fetched**). Every redirect hop is re-checked, the fetch is bounded by
   `PDFBOX_FETCH_TIMEOUT` and `PDFBOX_FETCH_MAX_BYTES`, and a resource cited several times is fetched
   once.

Everything else is refused: `file:`, `jar:`, `classpath:`, relative paths that name no attached file,
unlisted hosts. The bundled fonts are not concerned. By default an unresolved resource fails the
render with a **422** listing each one and why (`not-allowed`, `not-found`, `timeout`, `too-large`,
`fetch-error`):

```text
2 resource(s) could not be resolved:
- sigwx.svg : not-found — no attached file named 'sigwx.svg'
- https://tiles.example.org/fir.png : not-allowed — host 'tiles.example.org' is not in PDFBOX_FETCH_ALLOWED_HOSTS (empty: nothing is fetched)
```

With `?onMissingResource=report` the PDF is returned anyway and the list travels in response headers
(an RFC 8941 list; `%` and non-ASCII bytes of each reference are percent-encoded):

```text
X-Pdfbox-Unresolved-Count: 2
X-Pdfbox-Unresolved-Resources: "sigwx.svg";reason=not-found, "https://tiles.example.org/fir.png";reason=not-allowed
```

### SVG

Inline `<svg>` and attached / fetched `.svg` files are drawn as vector art (Apache Batik). Scripts and
`on*` handlers are removed and never run; Batik itself may only read `data:` URIs — every other
reference inside an SVG goes through the rules above. SVG text is drawn with the bundled fonts, never
with host fonts. PDF/A-2/3 accept SVG transparency; **PDF/A-1 forbids transparency**, see the
conformance notes below.

### Errors

An invalid `standard` returns a plain-text **400** that names the bad value and lists the accepted
ones, so you never have to guess:

```bash
curl "http://localhost:8080/api/v1/pdf-info?standard=foo"
# Invalid value 'foo' for parameter 'standard'. Supported values: NONE, PDF_A_1A, PDF_A_1B, ...
```

All error bodies are UTF-8 plain text: **400** bad input, **413** upload too large, **422**
unresolved resources, **503** + `Retry-After` when every render slot stays busy longer than
`PDFBOX_RENDER_QUEUE_TIMEOUT`.

### Standard, self-described

`GET /api/v1/pdf-info?standard=<STANDARD>` returns a PDF that is **both an example of and a
description of** that standard: the file conforms to the requested standard and its content lists the
constraints that standard enforces (conformance level, rules common to every PDF/A part, and the
part-specific rules). Handy as a reference and as a quick conformance check.

```bash
# A PDF/A-1a document that explains PDF/A-1a:
curl "http://localhost:8080/api/v1/pdf-info?standard=PDF_A_1A" -o pdf-info-1a.pdf
verapdf --flavour 1a pdf-info-1a.pdf      # and it validates
```

### OpenAPI & Swagger UI

The OpenAPI 3 description is always served at **`/v3/api-docs`** (handy for generating clients).

The interactive **Swagger UI is a dev-only convenience** and is **disabled by default**. Enable it by
activating the `dev` profile, then browse to <http://localhost:8080/swagger-ui.html>:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
# or, on the packaged jar / Docker:
SPRING_PROFILES_ACTIVE=dev java -jar target/pdfbox.jar
docker run --rm -p 8080:8080 -e SPRING_PROFILES_ACTIVE=dev softwarity/pdfbox:latest
```

In Swagger UI, `POST /api/v1/pdf/upload` renders a file picker for the HTML and an “add item” list of
pickers for `resources`: pick the files, a `standard`, and the response download is your PDF.

### Fonts & exotic scripts

All glyphs are **embedded** (required for PDF/A), so output is reproducible offline. A very broad Noto
set ships with the service: `Noto Sans` (Latin/Vietnamese/Cyrillic/Greek), `Noto Serif`, `Noto Sans
Mono`, and per-script faces for Hebrew, Arabic, Thaana, all the major Indic scripts (Devanagari,
Bengali, Gurmukhi, Gujarati, Oriya, Tamil, Telugu, Kannada, Malayalam, Sinhala), Thai, Lao, Myanmar,
Khmer, Georgian, Armenian, Ethiopic, Japanese, Korean, Chinese (Simplified & Traditional), plus
symbols/math and monochrome emoji. The CSS generic families `sans-serif`/`serif`/`monospace` resolve
to `Noto Sans`/`Noto Serif`/`Noto Sans Mono`, so a stack ending in a generic (the usual
`font-family: Arial, sans-serif`) always lands on a real embedded face.

You normally write nothing: a single document can mix scripts freely and the right faces are chosen
automatically. To force a family explicitly, use CSS:

```html
<p style="font-family:'Noto Sans Hebrew'">שלום עולם</p>
<p style="font-family:'Noto Sans JP'">日本語のテキスト</p>
```

**Per-document font selection.** openhtmltopdf re-parses every font it is told about on *every*
render, so registering the whole set each time would re-parse tens of MB per request. Instead the
service scans the source for the Unicode blocks it actually contains and only registers the matching
faces — a Latin-only PDF never pays to parse Thai, Devanagari or CJK. Shared CJK Han ideographs are
disambiguated to Japanese / Korean / Simplified / Traditional from the document `lang`
(`ja` / `ko` / `zh-Hans` / `zh-Hant`), defaulting to Japanese. So **declare `lang`** on elements
mixing Chinese/Japanese/Korean to get the correct Han shapes.

**Where the fonts live.** The light faces are bundled in the jar. The big CJK faces (Korean, Chinese
SC/TC) are shipped as filesystem fonts in the Docker image under `/usr/share/fonts` instead, to keep
the jar small — running the bare jar therefore covers everything *except* CJK unless you add CJK
`.ttf` files yourself. Need another script, or CJK when running the jar directly? Drop extra **`.ttf`**
files into `/app/fonts` (Docker) or any directory in `PDFBOX_FONTS_DIRECTORIES`; they are indexed at
startup. Two caveats: the PDFBox renderer only embeds TrueType outlines, so OpenType/CFF `.otf` files
are skipped (convert to `.ttf` first); and a variable font is embedded at its *default* master, so
freeze it to a static instance first (the image does this for the Noto CJK variable fonts, forcing
`wght=400`, otherwise they would embed as Thin).

### PDF/A conformance notes

- **`B` (visual) and `U` (Unicode) levels** are the recommended, fully-supported targets. Output
  carries the proper PDF version (1.4 for part 1, 1.7 for parts 2/3), an embedded sRGB output intent,
  embedded fonts and a PDF/A XMP identification block; PDF/A-1 is written with a classic
  cross-reference table.
- **`A` (accessible/tagged) levels** additionally enable tagged/PDF-UA output, whose validity depends
  on the *input* HTML being accessible (document language, image `alt` text, proper heading order…).
  As a safety net the service injects a `<title>`, `<html lang>` and `<meta name="subject">` (the
  PDF/UA description maps to the PDF *Subject*, i.e. `<meta name="subject">`, not `description`) when
  the source omits them (see `PDFBOX_DEFAULT_LANG` / `PDFBOX_DEFAULT_TITLE`), but for genuinely
  accessible output you should supply accurate ones — generic defaults satisfy the validator, not a
  screen-reader user.
- **Transparency and PDF/A-1.** PDF/A-1 forbids transparency. CSS `rgba()` / `opacity` are already
  neutralised by the engine, but SVG `opacity`, `fill-opacity`, `stop-opacity` and PNG images with an
  alpha channel are currently rendered as-is and the file is then **not** PDF/A-1 conformant (veraPDF
  §6.4). Use PDF/A-2 or PDF/A-3 for such content, or flatten it beforehand. PDF/A-2/3 are fine.
- Always validate critical output with [veraPDF](https://verapdf.org/):
  ```bash
  verapdf --flavour 1b document.pdf
  ```

## Configuration

Sensible defaults mean you normally configure nothing. If needed, override via environment variables:

| Property | Default | Purpose |
|---|---|---|
| `PDFBOX_DEFAULT_STANDARD` | `PDF_A_1B` | Standard used when the request omits `standard`. |
| `PDFBOX_FONTS_DIRECTORIES` | `/app/fonts,/usr/share/fonts,fonts` | Comma-separated font scan directories. |
| `PDFBOX_DEFAULT_FONT_FAMILY` | Noto stack | CSS font stack applied when the HTML sets none. |
| `PDFBOX_DEFAULT_LANG` | `en` | `<html lang>` injected when the source omits it (required by the accessible "A" levels / PDF/UA). |
| `PDFBOX_DEFAULT_TITLE` | `Document` | `<title>` injected when the source has none and no usable `<h1>/<h2>/<h3>` (required by PDF/UA). |
| `PDFBOX_FETCH_ALLOWED_HOSTS` | _(empty)_ | Exact host names (optional `:port`) remote resources may be fetched from. Empty = **nothing is fetched**. No port = default port (80/443) only. |
| `PDFBOX_FETCH_TIMEOUT` | `10s` | Overall time per remote resource (connection, redirects, body). |
| `PDFBOX_FETCH_MAX_BYTES` | `10MB` | Maximum size of one remote resource. |
| `PDFBOX_UPLOAD_MAX_PART_SIZE` | `20MB` | Maximum size of one multipart part (the HTML or one attached file). |
| `PDFBOX_UPLOAD_MAX_TOTAL_SIZE` | `50MB` | Maximum size of a whole multipart request. |
| `PDFBOX_UPLOAD_MEMORY_THRESHOLD` | _(unset = max part size)_ | Parts above this size go to a temporary file for the duration of the request instead of memory. Unset: nothing is ever written to disk. |
| `PDFBOX_RENDER_MAX_CONCURRENT` | `0` (= CPUs) | Renders running at once; others wait for a slot. Bounds CPU and memory (≈ this × upload total). |
| `PDFBOX_RENDER_QUEUE_TIMEOUT` | `10s` | Longest wait for a render slot before a `503` + `Retry-After`. |
| `PDFBOX_BASE_PATH` | _(empty)_ | Base path prefixed to **every** route, set at startup. Must start with `/`. |
| `SPRING_PROFILES_ACTIVE` | _(none)_ | Set to `dev` to enable Swagger UI. |
| `JAVA_OPTS` | _(empty)_ | Extra JVM flags. |

### Base path (set at launch)

The base path is a **startup parameter**, fixed for the lifetime of the process — not a per-request
value. Provide it at launch as an environment variable or a Spring command-line argument:

```bash
# Environment variable
PDFBOX_BASE_PATH=/pdfbox java -jar target/pdfbox.jar
docker run --rm -p 8080:8080 -e PDFBOX_BASE_PATH=/pdfbox softwarity/pdfbox:latest

# ...or as a launch argument
java -jar target/pdfbox.jar --server.servlet.context-path=/pdfbox
```

Every route then lives under that prefix, e.g. `POST http://localhost:8080/pdfbox/api/v1/pdf`,
`GET http://localhost:8080/pdfbox/v3/api-docs`, `GET http://localhost:8080/pdfbox/actuator/health`.

## Kubernetes (Helm)

A minimal Helm chart ships under [`helm/pdfbox`](helm/pdfbox). It deploys the multi-arch image with a
Deployment + Service, health probes on `/actuator/health`, and an optional Ingress.

```bash
helm install pdfbox ./helm/pdfbox
# pin a release and run two replicas
helm install pdfbox ./helm/pdfbox --set image.tag=1.0.0 --set replicaCount=2
```

See the [chart README](helm/pdfbox/README.md) and the [Kubernetes docs page](https://softwarity.github.io/pdfbox/#/kubernetes)
for values (Ingress, base path, extra fonts, autoscaling).

## Releasing

The published **artifact is the multi-arch Docker image** (`docker.io/softwarity/pdfbox`), and the
**git tag is the source of truth for the version**. Releases are cut by the
[`softwarity/release-flow`](https://github.com/softwarity/release-flow) action.

1. Keep [`RELEASE_NOTES.md`](RELEASE_NOTES.md) up to date as you merge: write user-facing changes
   under the `## NEXT RELEASE` heading. That section becomes the published release notes.
2. Run the **Release** workflow (Actions → Release → Run workflow) and pick `patch` / `minor` /
   `major`. It first runs `mvn verify` (a red `main` is never tagged), then release-flow, in one
   commit on `main`: computes the next `vX.Y.Z` from the latest tag, **syncs `pom.xml`'s `<version>`**
   (`maven_docker` mode, `mvn versions:set` in a `maven:3-eclipse-temurin-25` container), **syncs the
   Helm chart** `helm/pdfbox/Chart.yaml` (`version` and `appVersion`, so `image.tag: ""` deploys the
   released image), renames `## NEXT RELEASE` → `## X.Y.Z`, tags and pushes, publishes the GitHub
   Release, and reopens a fresh `## NEXT RELEASE`.
3. The pushed tag triggers **Docker release**, which runs `mvn verify` then builds and publishes the
   `amd64` + `arm64` image, tagged `latest`, `X.Y.Z` and `X.Y`.

> The tag is pushed with a **PAT** (the `PAT_TOKEN` secret, used by `actions/checkout`), not the
> default `GITHUB_TOKEN` — GitHub does not re-trigger workflows for `GITHUB_TOKEN` pushes, so the
> Docker release would otherwise never start.

## How it works

```
HTML ──jsoup──► well-formed W3C DOM ──openhtmltopdf (+ Batik for SVG)──► PDF (PDFBox) ──► PDF/A post-pass ──► bytes
                      ▲ every resource: attached file │ data: │ allowed http(s) host — or refused & reported
```

- **jsoup** parses arbitrary/malformed HTML into a clean DOM; inline SVG is sanitized there.
- **openhtmltopdf** renders the DOM to PDF/A on top of **Apache PDFBox**, SVG through **Apache Batik**.
- One per-request resolver serves every stylesheet, font, image and SVG reference (see *Resources*).
- A small PDFBox pass fixes the PDF/A header version and cross-reference table and clears image
  interpolation flags (forbidden in every PDF/A part).

## Built with open source

This project stands on the shoulders of these open-source projects — thank you:

- [openhtmltopdf](https://github.com/openhtmltopdf/openhtmltopdf) — HTML/CSS → PDF/A & PDF/UA engine (LGPL-2.1), a fork of
- [Flying Saucer](https://github.com/flyingsaucerproject/flyingsaucer) (LGPL-2.1)
- [Apache PDFBox](https://pdfbox.apache.org/) — PDF library (Apache-2.0)
- [Apache Batik](https://xmlgraphics.apache.org/batik/) — SVG rendering (Apache-2.0)
- [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys) — WebP decoding (BSD-3-Clause)
- [jsoup](https://jsoup.org/) — HTML parser (MIT)
- [Spring Boot](https://spring.io/projects/spring-boot) (Apache-2.0)
- [Noto fonts](https://fonts.google.com/noto) (SIL Open Font License 1.1)
- PDF/A validation by [veraPDF](https://verapdf.org/)

## License

Apache-2.0
