import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CodeComponent } from '../code/code.component';

@Component({
  selector: 'app-standards',
  imports: [CodeComponent, RouterLink],
  template: `
    <h2>PDF/A standards</h2>

    <p>
      Pick the output with the <code>standard</code> parameter. <code>NONE</code> produces a plain PDF;
      every other value produces a <strong>PDF/A</strong> with embedded fonts, an embedded sRGB output
      intent and a PDF/A identification block in the XMP metadata.
    </p>

    <p>The name encodes the <strong>part</strong> (1/2/3) and the <strong>conformance level</strong>:</p>
    <ul>
      <li><strong>Part 1</strong> — based on PDF 1.4 (classic cross-reference table, no transparency: pdfbox flattens it over white — semi-transparent colours and images with an alpha channel become their solid look on white paper).</li>
      <li><strong>Part 2</strong> — based on PDF 1.7 (JPEG2000, transparency, object streams).</li>
      <li><strong>Part 3</strong> — like part 2, but allows arbitrary embedded file attachments (see <em>Embedded files &amp; Factur-X</em> below).</li>
      <li><strong>Level B</strong> (basic) — guarantees the visual appearance is reproducible.</li>
      <li><strong>Level U</strong> — level B <em>plus</em> guaranteed Unicode text mapping.</li>
      <li><strong>Level A</strong> (accessible) — level U <em>plus</em> tagged structure (PDF/UA-style).</li>
    </ul>

    <table>
      <thead>
        <tr><th>Value</th><th>Part</th><th>Level</th><th>PDF version</th><th>Tagged / accessible</th></tr>
      </thead>
      <tbody>
        <tr><td><code>NONE</code></td><td>—</td><td>—</td><td>default</td><td>no</td></tr>
        <tr><td><code>PDF_A_1A</code></td><td>1</td><td>A</td><td>1.4</td><td>yes</td></tr>
        <tr><td><code>PDF_A_1B</code></td><td>1</td><td>B</td><td>1.4</td><td>no</td></tr>
        <tr><td><code>PDF_A_2A</code></td><td>2</td><td>A</td><td>1.7</td><td>yes</td></tr>
        <tr><td><code>PDF_A_2B</code></td><td>2</td><td>B</td><td>1.7</td><td>no</td></tr>
        <tr><td><code>PDF_A_2U</code></td><td>2</td><td>U</td><td>1.7</td><td>no</td></tr>
        <tr><td><code>PDF_A_3A</code></td><td>3</td><td>A</td><td>1.7</td><td>yes</td></tr>
        <tr><td><code>PDF_A_3B</code></td><td>3</td><td>B</td><td>1.7</td><td>no</td></tr>
        <tr><td><code>PDF_A_3U</code></td><td>3</td><td>U</td><td>1.7</td><td>no</td></tr>
        <tr><td><code>PDF_UA_1</code></td><td>—</td><td>—</td><td>1.7</td><td>yes (PDF/UA-1, not PDF/A)</td></tr>
      </tbody>
    </table>

    <h3>Which one should I pick?</h3>
    <div class="callout">
      <strong>Recommendation:</strong> the <code>B</code> (visual) and <code>U</code> (Unicode) levels
      are the fully-supported, drop-in targets. <code>PDF_A_2U</code> is a great default for archival
      with reliable copy/paste and search; <code>PDF_A_1B</code> (the service default) is the most
      broadly compatible. Use part 3 when you embed files inside the PDF (e.g. a Factur-X invoice), and
      <code>PDF_UA_1</code> when accessibility is the requirement but archiving is not.
    </div>

    <h3>The accessible “A” levels (tagged / PDF/UA)</h3>
    <p>
      The <code>A</code> levels additionally emit a <strong>tagged</strong> document tree. Their
      validity depends on the <em>input HTML being accessible</em> — a document language, <code>alt</code>
      text on images, a sensible heading order, and so on. As a safety net the service injects a
      <code>&lt;title&gt;</code>, an <code>&lt;html lang&gt;</code> and a
      <code>&lt;meta name="subject"&gt;</code> when the source omits them (see
      <a routerLink="/configuration">Configuration</a>), but generic defaults satisfy the
      <em>validator</em>, not a screen-reader user. For genuinely accessible output, supply accurate
      values in your HTML.
    </p>

    <h3>PDF/UA-1 alone</h3>
    <p>
      <code>PDF_UA_1</code> produces the same tagged, accessible structure as the <code>A</code> levels,
      identified as <strong>PDF/UA-1</strong> (ISO 14289-1, <code>pdfuaid:part = 1</code>) — without the
      PDF/A archiving constraints: no output intent, transparency and embedded files allowed. The same
      safety net applies, and an inline <code>&lt;svg&gt;</code> takes its alternate text from
      <code>aria-label</code> or its <code>&lt;title&gt;</code>. Need accessible <em>and</em> archival?
      Use <code>PDF_A_1A</code>, <code>PDF_A_2A</code> or <code>PDF_A_3A</code>.
    </p>

    <h3>Embedded files &amp; Factur-X</h3>
    <p>
      A link with a <code>download</code> attribute <strong>embeds</strong> the file it points to — an
      attached file (multipart <code>resources</code> part: <code>.xml</code>, <code>.json</code>,
      <code>.csv</code>, <code>.txt</code>, <code>.pdf</code>…) or a <code>data:</code> URI — shown as a
      paperclip annotation where the link is:
    </p>
    <app-code lang="markup">&lt;a href="factur-x.xml" download="factur-x.xml" title="Structured invoice"&gt;Invoice data (XML)&lt;/a&gt;</app-code>
    <table>
      <thead><tr><th>Attribute</th><th>Becomes</th></tr></thead>
      <tbody>
        <tr><td><code>download</code></td><td>the embedded file name</td></tr>
        <tr><td><code>title</code></td><td>its description</td></tr>
        <tr><td><code>relationship</code></td><td>its PDF/A-3 relationship: <code>Source</code>, <code>Data</code>, <code>Alternative</code>, <code>Supplement</code> or <code>Unspecified</code> (default)</td></tr>
        <tr><td><code>data-content-type</code></td><td>its MIME type (default: from the extension)</td></tr>
      </tbody>
    </table>
    <p>
      Allowed in <strong>PDF/A-3</strong>, <code>PDF_UA_1</code> and <code>NONE</code>. PDF/A-1 forbids
      embedded files and PDF/A-2 only allows PDF/A documents: there the link is refused like any
      unresolvable resource — a <strong>422</strong>, or the <code>X-Pdfbox-Unresolved-*</code> headers
      with <code>onMissingResource=report</code>.
    </p>
    <div class="callout">
      <strong>Factur-X / ZUGFeRD / XRechnung.</strong> In PDF/A-3, an embedded <code>factur-x.xml</code>
      (or <code>xrechnung.xml</code>) is declared as a hybrid invoice in the XMP metadata, with the
      conformance level read from the invoice itself (MINIMUM, BASIC WL, BASIC, EN 16931, EXTENDED,
      XRECHNUNG). The relationship defaults to <code>Data</code> for MINIMUM / BASIC WL and
      <code>Alternative</code> otherwise. A <code>factur-x.xml</code> without a known profile is a
      <strong>400</strong>. The XML is embedded as is: validate it against the Factur-X schematron on
      your side.
    </div>
    <app-code lang="bash">curl -X POST "http://localhost:8080/api/v1/pdf?standard=PDF_A_3B" \\
     -F "file=&#64;invoice.html;type=text/html" \\
     -F "resources=&#64;factur-x.xml" \\
     -o invoice.pdf</app-code>

    <h3>Validate your output</h3>
    <p>Always check critical documents with <a href="https://verapdf.org/" target="_blank" rel="noopener">veraPDF</a>:</p>
    <app-code lang="bash"># validate a PDF/A-1b file (or --flavour 3b, ua1…)
verapdf --flavour 1b document.pdf</app-code>

    <h3>Discover them at runtime</h3>
    <p>The list is served by the API, so a client never has to hard-code it:</p>
    <app-code lang="bash">curl http://localhost:8080/api/v1/standards</app-code>
    <p>See <a routerLink="/endpoints">API &amp; endpoints</a> for the response shape.</p>
  `,
})
export class StandardsComponent {}
