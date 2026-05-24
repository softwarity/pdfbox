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

> Locally the service uses whatever fonts are installed under `/usr/share/fonts`. For the full
> bundled font set (and guaranteed offline behaviour) use the Docker image, which ships Noto fonts.

## API

| Method & path | Description |
|---|---|
| `POST /api/v1/pdf` | Body = HTML. Query params: `standard` (see below, default `PDF_A_1B`), `filename` (default `document.pdf`). Returns `application/pdf`. |
| `GET /api/v1/standards` | Lists the supported standards. |
| `GET /actuator/health` | Health probe. |
| `GET /` | Browser test page. |

Supported `standard` values: `NONE`, `PDF_A_1A`, `PDF_A_1B`, `PDF_A_2A`, `PDF_A_2B`, `PDF_A_2U`,
`PDF_A_3A`, `PDF_A_3B`, `PDF_A_3U`.

### Fonts & exotic scripts

All glyphs are **embedded** (required for PDF/A), so output is reproducible offline. The Docker image
bundles the Noto family; the engine falls back **per glyph** through a default font stack, so a single
document can freely mix Latin, Vietnamese, Hebrew, Arabic, Thai, Devanagari and Japanese without any
configuration. To target a specific font explicitly, just use CSS:

```html
<p style="font-family:'Noto Sans Hebrew'">שלום עולם</p>
<p style="font-family:'Noto Sans JP'">日本語のテキスト</p>
```

Drop extra `.ttf` / `.otf` files into `/app/fonts` (Docker) or `./fonts` (local) and they are picked
up automatically at startup.

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
| `JAVA_OPTS` | _(empty)_ | Extra JVM flags. |

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
