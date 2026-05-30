import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CodeComponent } from '../code/code.component';

@Component({
  selector: 'app-endpoints',
  imports: [CodeComponent, RouterLink],
  template: `
    <h2>API &amp; endpoints</h2>

    <p>
      Everything lives under the <code>/api/v1</code> prefix (plus the always-on OpenAPI and health
      routes). Both conversion endpoints share the same behaviour: they accept a <code>standard</code>
      and a <code>filename</code>, and return an <code>application/pdf</code> attachment.
    </p>

    <table>
      <thead>
        <tr><th>Method &amp; path</th><th>Purpose</th></tr>
      </thead>
      <tbody>
        <tr><td><code>POST /api/v1/pdf</code></td><td>Body = HTML. Returns the rendered PDF.</td></tr>
        <tr><td><code>POST /api/v1/pdf/upload</code></td><td><code>multipart/form-data</code> with an HTML file. Returns the rendered PDF.</td></tr>
        <tr><td><code>GET /api/v1/standards</code></td><td>Lists the supported output standards.</td></tr>
        <tr><td><code>GET /v3/api-docs</code></td><td>OpenAPI 3 description (JSON) — always served.</td></tr>
        <tr><td><code>GET /swagger-ui.html</code></td><td>Interactive Swagger UI — <strong>dev profile only</strong>.</td></tr>
        <tr><td><code>GET /actuator/health</code></td><td>Health probe (used by Docker / Kubernetes).</td></tr>
        <tr><td><code>GET /</code></td><td>Small browser test page.</td></tr>
      </tbody>
    </table>

    <div class="callout">
      All routes can be prefixed with a base path (e.g. <code>/pdfbox</code>) set at launch via
      <code>PDFBOX_BASE_PATH</code> — see <a routerLink="/configuration">Configuration</a>. The paths
      below assume no base path.
    </div>

    <h3>POST /api/v1/pdf — raw HTML</h3>
    <p>The HTML is the request body. Query parameters:</p>
    <table>
      <thead>
        <tr><th>Param</th><th>Default</th><th>Description</th></tr>
      </thead>
      <tbody>
        <tr><td><code>standard</code></td><td><code>PDF_A_1B</code></td><td>Output standard (see <a routerLink="/standards">PDF/A standards</a>).</td></tr>
        <tr><td><code>filename</code></td><td><code>document.pdf</code></td><td>Name in the <code>Content-Disposition</code> attachment header.</td></tr>
      </tbody>
    </table>
    <p>
      Accepted <code>Content-Type</code>s: <code>text/html</code>, <code>application/xhtml+xml</code>,
      <code>text/plain</code>, <code>application/octet-stream</code>.
    </p>
    <app-code lang="bash">curl -X POST "http://localhost:8080/api/v1/pdf?standard=PDF_A_2U&amp;filename=invoice.pdf" \\
     -H "Content-Type: text/html" \\
     --data-binary @invoice.html \\
     -o invoice.pdf</app-code>

    <h3>POST /api/v1/pdf/upload — file upload</h3>
    <p>
      A <code>multipart/form-data</code> form. This is the variant Swagger UI renders as a file picker.
      JavaScript in the file is <strong>not</strong> executed.
    </p>
    <table>
      <thead>
        <tr><th>Part / param</th><th>Required</th><th>Description</th></tr>
      </thead>
      <tbody>
        <tr><td><code>file</code></td><td>yes</td><td>A standalone HTML file (sent as the multipart <code>file</code> part).</td></tr>
        <tr><td><code>standard</code></td><td>no</td><td>Output standard; defaults to the configured default.</td></tr>
        <tr><td><code>filename</code></td><td>no</td><td>Output name; defaults to the uploaded name with a <code>.pdf</code> extension.</td></tr>
      </tbody>
    </table>
    <app-code lang="bash">curl -X POST "http://localhost:8080/api/v1/pdf/upload" \\
     -F "file=&#64;invoice.html;type=text/html" \\
     -F "standard=PDF_A_2B" \\
     -o invoice.pdf</app-code>

    <h3>GET /api/v1/standards</h3>
    <p>Returns the list of accepted <code>standard</code> values as a JSON array:</p>
    <app-code lang="json">[
  "NONE",
  "PDF_A_1A", "PDF_A_1B",
  "PDF_A_2A", "PDF_A_2B", "PDF_A_2U",
  "PDF_A_3A", "PDF_A_3B", "PDF_A_3U"
]</app-code>

    <h3>OpenAPI &amp; Swagger UI</h3>
    <p>
      The OpenAPI 3 description is <strong>always</strong> available at <code>/v3/api-docs</code>
      (handy for generating clients). The interactive Swagger UI is a <strong>dev-only</strong>
      convenience and is disabled by default — enable it with the <code>dev</code> profile:
    </p>
    <app-code lang="bash"># from sources
mvn spring-boot:run -Dspring-boot.run.profiles=dev
# packaged jar / Docker
SPRING_PROFILES_ACTIVE=dev java -jar target/pdfbox.jar
docker run --rm -p 8080:8080 -e SPRING_PROFILES_ACTIVE=dev softwarity/pdfbox:latest</app-code>
    <p>Then browse to <code>http://localhost:8080/swagger-ui.html</code>.</p>

    <h3>Errors</h3>
    <p>
      Bad input (malformed request, a standard that cannot be produced, an empty upload) maps to a
      plain-text <strong>HTTP 400</strong> with a short message — not a 500. A successful call always
      returns <code>200</code> with <code>Content-Type: application/pdf</code> and a
      <code>Content-Disposition: attachment</code> header.
    </p>
  `,
})
export class EndpointsComponent {}
