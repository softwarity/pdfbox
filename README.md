# pdfbox

A tiny, self-contained **Spring Boot** service that turns **HTML into PDF — including PDF/A**
(PDF/A-1a, 1b, 2a, 2b, 2u, 3a, 3b, 3u). Push HTML, get the PDF binary back. No external services,
no runtime configuration, fonts bundled for offline generation of exotic scripts (Vietnamese,
Hebrew, Arabic, Thai, Devanagari, Japanese/CJK…).

```
POST  your HTML  ──►  pdfbox  ──►  PDF/A binary
```

## Quick start (Docker)

```bash
docker run --rm -p 8080:8080 ghcr.io/softwarity/pdfbox:latest
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

Requires JDK 21.

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
| `POST /api/v1/pdf` | Body = HTML. Query params: `standard` (see below, default `PDF_A_1B`), `filename` (default `document.pdf`). Returns `application/pdf`. |
| `POST /api/v1/pdf/upload` | `multipart/form-data`: `file` = a standalone HTML file, plus `standard` and `filename`. Returns `application/pdf`. This is the form Swagger UI shows as a file picker. |
| `GET /api/v1/standards` | Lists the supported standards. |
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

### OpenAPI & Swagger UI

The OpenAPI 3 description is always served at **`/v3/api-docs`** (handy for generating clients).

The interactive **Swagger UI is a dev-only convenience** and is **disabled by default**. Enable it by
activating the `dev` profile, then browse to <http://localhost:8080/swagger-ui.html>:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
# or, on the packaged jar / Docker:
SPRING_PROFILES_ACTIVE=dev java -jar target/pdfbox.jar
docker run --rm -p 8080:8080 -e SPRING_PROFILES_ACTIVE=dev ghcr.io/softwarity/pdfbox:latest
```

In Swagger UI, `POST /api/v1/pdf/upload` renders a file picker: choose a standalone HTML file, pick a
`standard`, and the response download is your PDF.

### Fonts & exotic scripts

All glyphs are **embedded** (required for PDF/A), so output is reproducible offline. A broad Noto set
is bundled inside the jar (`Noto Sans` for Latin/Vietnamese/Cyrillic/Greek, plus `Noto Sans Hebrew`,
`Noto Sans Arabic`, `Noto Sans Thai`, `Noto Sans Devanagari` and `Noto Sans JP` for Japanese/CJK). The
engine falls back **per glyph** through the default font stack, so a single document can freely mix
those scripts without any configuration. To target a specific family explicitly, use CSS:

```html
<p style="font-family:'Noto Sans Hebrew'">שלום עולם</p>
<p style="font-family:'Noto Sans JP'">日本語のテキスト</p>
```

Need a script that isn't bundled (e.g. Korean, Chinese-specific shapes, Tamil)? Drop extra **`.ttf`**
files into `/app/fonts` (Docker) or any directory listed in `PDFBOX_FONTS_DIRECTORIES`; they are picked
up at startup. Note: the PDFBox renderer only embeds TrueType outlines, so OpenType/CFF `.otf` files
are skipped — convert them to `.ttf` first.

### PDF/A conformance notes

- **`B` (visual) and `U` (Unicode) levels** are the recommended, fully-supported targets. Output
  carries the proper PDF version (1.4 for part 1, 1.7 for parts 2/3), an embedded sRGB output intent,
  embedded fonts and a PDF/A XMP identification block; PDF/A-1 is written with a classic
  cross-reference table.
- **`A` (accessible/tagged) levels** additionally enable tagged/PDF-UA output, whose validity depends
  on the *input* HTML being accessible (document language, image `alt` text, proper heading order…).
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
| `PDFBOX_BASE_PATH` | _(empty)_ | Base path prefixed to **every** route, set at startup. Must start with `/`. |
| `SPRING_PROFILES_ACTIVE` | _(none)_ | Set to `dev` to enable Swagger UI. |
| `JAVA_OPTS` | _(empty)_ | Extra JVM flags. |

### Base path (set at launch)

The base path is a **startup parameter**, fixed for the lifetime of the process — not a per-request
value. Provide it at launch as an environment variable or a Spring command-line argument:

```bash
# Environment variable
PDFBOX_BASE_PATH=/pdfbox java -jar target/pdfbox.jar
docker run --rm -p 8080:8080 -e PDFBOX_BASE_PATH=/pdfbox ghcr.io/softwarity/pdfbox:latest

# ...or as a launch argument
java -jar target/pdfbox.jar --server.servlet.context-path=/pdfbox
```

Every route then lives under that prefix, e.g. `POST http://localhost:8080/pdfbox/api/v1/pdf`,
`GET http://localhost:8080/pdfbox/v3/api-docs`, `GET http://localhost:8080/pdfbox/actuator/health`.

## How it works

```
HTML ──jsoup──► well-formed W3C DOM ──openhtmltopdf──► PDF (PDFBox) ──► PDF/A normalization ──► bytes
```

- **jsoup** parses arbitrary/malformed HTML into a clean DOM.
- **openhtmltopdf** renders the DOM to PDF/A on top of **Apache PDFBox**.
- A small PDFBox pass fixes the PDF/A-1 header version and cross-reference table.

## Built with open source

This project stands on the shoulders of these open-source projects — thank you:

- [openhtmltopdf](https://github.com/openhtmltopdf/openhtmltopdf) — HTML/CSS → PDF/A & PDF/UA engine (LGPL-2.1), a fork of
- [Flying Saucer](https://github.com/flyingsaucerproject/flyingsaucer) (LGPL-2.1)
- [Apache PDFBox](https://pdfbox.apache.org/) — PDF library (Apache-2.0)
- [jsoup](https://jsoup.org/) — HTML parser (MIT)
- [Spring Boot](https://spring.io/projects/spring-boot) (Apache-2.0)
- [Noto fonts](https://fonts.google.com/noto) (SIL Open Font License 1.1)
- PDF/A validation by [veraPDF](https://verapdf.org/)
