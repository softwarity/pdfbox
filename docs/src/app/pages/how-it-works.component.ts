import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CodeComponent } from '../code/code.component';

@Component({
  selector: 'app-how-it-works',
  imports: [CodeComponent, RouterLink],
  template: `
    <h2>How it works</h2>

    <p>
      Everything funnels through a single rendering pipeline. Given some HTML and a target standard, the
      service produces the PDF bytes — no JavaScript ever runs, and no network call is made.
    </p>

    <app-code lang="text">HTML ──jsoup──► well-formed W3C DOM ──openhtmltopdf──► PDF (PDFBox) ──► PDF/A normalization ──► bytes</app-code>

    <h3>The stages</h3>
    <ul>
      <li>
        <strong>Normalize (jsoup).</strong> Possibly-malformed HTML is parsed into a clean DOM. A UTF-8
        <code>&lt;meta&gt;</code> and a lowest-priority default <code>font-family</code> are injected
        (author CSS always wins). For the accessible “A” levels, a <code>&lt;title&gt;</code>, an
        <code>&lt;html lang&gt;</code> and a <code>&lt;meta name="subject"&gt;</code> are filled in when
        the source omits them.
      </li>
      <li>
        <strong>Render (openhtmltopdf on PDFBox).</strong> The DOM is laid out and drawn to PDF. For
        PDF/A the conformance level is set, the sRGB output intent embedded, fonts embedded, and the
        “A” levels additionally tagged (PDF/UA).
      </li>
      <li>
        <strong>Font selection (per render).</strong> Only the faces a document actually needs are
        registered, picked by scanning its Unicode blocks — see <a routerLink="/fonts">Fonts &amp;
        scripts</a>.
      </li>
      <li>
        <strong>Normalize version (PDF/A only).</strong> A small PDFBox pass forces the classic
        cross-reference table and the correct <code>%PDF-1.x</code> header (1.4 for part 1, 1.7 for
        parts 2/3) required by PDF/A.
      </li>
    </ul>

    <h3>Design choices</h3>
    <ul>
      <li><strong>Stateless &amp; offline.</strong> No database, no broker, no outbound calls — easy to scale horizontally (see <a routerLink="/kubernetes">Kubernetes</a>).</li>
      <li><strong>One contract.</strong> POST HTML, get <code>application/pdf</code>. No per-request configuration surface to misuse.</li>
      <li><strong>Reproducible output.</strong> Bundled fonts + embedded sRGB mean the same input yields the same archival PDF everywhere.</li>
    </ul>

    <h3>Built with open source</h3>
    <p>This project stands on the shoulders of these projects — thank you:</p>
    <table>
      <thead>
        <tr><th>Dependency</th><th>Role</th><th>License</th></tr>
      </thead>
      <tbody>
        <tr><td><a href="https://github.com/openhtmltopdf/openhtmltopdf" target="_blank" rel="noopener">openhtmltopdf</a> (<code>openhtmltopdf-pdfbox</code>)</td><td>HTML/CSS → PDF/A &amp; PDF/UA engine — a fork of <a href="https://github.com/flyingsaucerproject/flyingsaucer" target="_blank" rel="noopener">Flying Saucer</a></td><td>LGPL-2.1</td></tr>
        <tr><td><a href="https://pdfbox.apache.org/" target="_blank" rel="noopener">Apache PDFBox</a></td><td>Low-level PDF library openhtmltopdf renders onto (pulled in transitively)</td><td>Apache-2.0</td></tr>
        <tr><td><a href="https://jsoup.org/" target="_blank" rel="noopener">jsoup</a></td><td>Lenient HTML parsing → the W3C DOM the engine consumes</td><td>MIT</td></tr>
        <tr><td><a href="https://spring.io/projects/spring-boot" target="_blank" rel="noopener">Spring Boot</a></td><td>Application framework — <code>web</code> + <code>actuator</code> starters</td><td>Apache-2.0</td></tr>
        <tr><td><a href="https://springdoc.org/" target="_blank" rel="noopener">springdoc-openapi</a></td><td>Serves <code>/v3/api-docs</code> and the dev-only Swagger UI</td><td>Apache-2.0</td></tr>
        <tr><td><a href="https://fonts.google.com/noto" target="_blank" rel="noopener">Noto fonts</a></td><td>Bundled multi-script font set (in the jar / image)</td><td>OFL-1.1</td></tr>
      </tbody>
    </table>

    <div class="callout">
      <strong>Not a dependency:</strong> <a href="https://verapdf.org/" target="_blank" rel="noopener">veraPDF</a>
      is <em>not</em> used, bundled or called by pdfbox. It's a separate, optional command-line
      validator you can run yourself on the output to check PDF/A conformance — see
      <a routerLink="/standards">PDF/A standards</a>.
    </div>

    <div class="callout">
      Runtime requirements are deliberately tiny: a <strong>JDK 21</strong> runtime (bundled in the
      image) and some memory for the JVM. No system fonts, services or config are required —
      see <a routerLink="/configuration">Configuration</a> for the few optional knobs.
    </div>
  `,
})
export class HowItWorksComponent {}
