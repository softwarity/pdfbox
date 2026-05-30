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
      <li><strong>Part 1</strong> — based on PDF 1.4 (classic cross-reference table, no transparency).</li>
      <li><strong>Part 2</strong> — based on PDF 1.7 (JPEG2000, transparency, object streams).</li>
      <li><strong>Part 3</strong> — like part 2, but allows arbitrary embedded file attachments.</li>
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
      </tbody>
    </table>

    <h3>Which one should I pick?</h3>
    <div class="callout">
      <strong>Recommendation:</strong> the <code>B</code> (visual) and <code>U</code> (Unicode) levels
      are the fully-supported, drop-in targets. <code>PDF_A_2U</code> is a great default for archival
      with reliable copy/paste and search; <code>PDF_A_1B</code> (the service default) is the most
      broadly compatible. Use part 3 only if you need to embed source files inside the PDF.
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

    <h3>Validate your output</h3>
    <p>Always check critical documents with <a href="https://verapdf.org/" target="_blank" rel="noopener">veraPDF</a>:</p>
    <app-code lang="bash"># validate a PDF/A-1b file
verapdf --flavour 1b document.pdf</app-code>

    <h3>Discover them at runtime</h3>
    <p>The list is served by the API, so a client never has to hard-code it:</p>
    <app-code lang="bash">curl http://localhost:8080/api/v1/standards</app-code>
    <p>See <a routerLink="/endpoints">API &amp; endpoints</a> for the response shape.</p>
  `,
})
export class StandardsComponent {}
