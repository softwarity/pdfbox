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
      routes). Both conversion endpoints share the same behaviour: they accept a <code>standard</code>,
      a <code>filename</code> and an <code>onMissingResource</code> policy, and return an
      <code>application/pdf</code> attachment.
    </p>

    <table>
      <thead>
        <tr><th>Method &amp; path</th><th>Purpose</th></tr>
      </thead>
      <tbody>
        <tr><td><code>POST /api/v1/pdf</code></td><td>Body = HTML, <strong>or</strong> <code>multipart/form-data</code> with the HTML and its attached files. Returns the rendered PDF.</td></tr>
        <tr><td><code>POST /api/v1/pdf/upload</code></td><td><code>multipart/form-data</code> with the HTML and its attached files (same as the multipart form of <code>/pdf</code>). Returns the rendered PDF.</td></tr>
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
        <tr><td><code>onMissingResource</code></td><td><code>fail</code></td><td><code>fail</code> or <code>report</code> — see <a href="#resources">Resources</a>.</td></tr>
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

    <h3>Multipart — HTML with attached files</h3>
    <p>
      <code>POST /api/v1/pdf</code> and <code>POST /api/v1/pdf/upload</code> accept a
      <code>multipart/form-data</code> form: the HTML plus any number of files it cites — stylesheets,
      images, SVG charts, fonts. The HTML cites them by their <strong>plain file name</strong>
      (<code>&lt;img src="sigwx.svg"&gt;</code>, <code>&lt;link rel="stylesheet" href="page.css"&gt;</code>,
      <code>src: url(logo-font.ttf)</code> in a <code>&#64;font-face</code>); the file name of the part is
      what counts. Attached files only live in memory, for that request. JavaScript is
      <strong>not</strong> executed — neither in the HTML nor in SVG.
    </p>
    <table>
      <thead>
        <tr><th>Part / param</th><th>Required</th><th>Description</th></tr>
      </thead>
      <tbody>
        <tr><td><code>file</code></td><td>yes</td><td>The HTML document (exactly one).</td></tr>
        <tr><td><code>resources</code></td><td>no, repeatable</td><td>One part per attached file: <code>.css</code>, <code>.png</code>, <code>.jpg</code>/<code>.jpeg</code>, <code>.gif</code>, <code>.webp</code>, <code>.svg</code>, <code>.ttf</code>.</td></tr>
        <tr><td><code>standard</code></td><td>no</td><td>Output standard; defaults to the configured default.</td></tr>
        <tr><td><code>filename</code></td><td>no</td><td>Output name; defaults to the HTML file name with a <code>.pdf</code> extension.</td></tr>
        <tr><td><code>onMissingResource</code></td><td>no</td><td><code>fail</code> (default) or <code>report</code>.</td></tr>
      </tbody>
    </table>
    <app-code lang="bash">curl -X POST "http://localhost:8080/api/v1/pdf?standard=PDF_A_2U" \\
     -F "file=&#64;briefing.html;type=text/html" \\
     -F "resources=&#64;page.css" \\
     -F "resources=&#64;sigwx.svg" \\
     -F "resources=&#64;wind.png" \\
     -F "resources=&#64;logo-font.ttf" \\
     -o briefing.pdf</app-code>
    <p>
      A request with only the <code>file</code> part behaves exactly as before. Each attached file is
      checked: a name containing <code>/</code>, <code>\\</code>, <code>..</code> or a control
      character, a duplicate name, an unknown part, or a file whose content does not match its type
      (e.g. a <code>.png</code> that is not a PNG, an OpenType/CFF <code>.otf</code> renamed
      <code>.ttf</code>) is a <strong>400</strong>. Above <code>PDFBOX_UPLOAD_MAX_PART_SIZE</code> per part
      or <code>PDFBOX_UPLOAD_MAX_TOTAL_SIZE</code> in total, the answer is a <strong>413</strong>. WebP
      images are converted to PNG (PDF cannot carry WebP). In Swagger UI (dev profile) the form shows a
      file picker for the HTML and an “add item” list of pickers for <code>resources</code>.
    </p>

    <h3 id="resources">Resources: what is resolved, what is refused</h3>
    <p>Every resource the document cites — stylesheet, <code>&#64;font-face</code>, image, SVG, and the references inside an SVG — is resolved in this order:</p>
    <ol>
      <li>an <strong>attached file</strong>, by its plain name (<code>logo.png</code> or <code>./logo.png</code>);</li>
      <li>a <strong><code>data:</code> URI</strong>;</li>
      <li>an <strong><code>http(s)</code> URL</strong>, <em>only</em> if its host is in <code>PDFBOX_FETCH_ALLOWED_HOSTS</code> — empty by default, so nothing is fetched. Redirects are re-checked hop by hop; timeout and size are capped; a resource cited several times is fetched once.</li>
    </ol>
    <p>
      Everything else is refused: <code>file:</code>, <code>jar:</code>, <code>classpath:</code>,
      relative paths that name no attached file (<code>img/logo.png</code>), unlisted hosts. Bundled fonts
      are not concerned.
    </p>
    <p>
      <strong>An unresolved resource never disappears silently.</strong> By default the render fails with
      a <strong>422</strong> whose plain-text body lists each resource and the reason —
      <code>not-allowed</code>, <code>not-found</code>, <code>timeout</code>, <code>too-large</code> or
      <code>fetch-error</code>:
    </p>
    <app-code lang="text">2 resource(s) could not be resolved:
- sigwx.svg : not-found — no attached file named 'sigwx.svg'
- https://tiles.example.org/fir.png : not-allowed — host 'tiles.example.org' is not in PDFBOX_FETCH_ALLOWED_HOSTS (empty: nothing is fetched)</app-code>
    <p>
      With <code>?onMissingResource=report</code> the PDF is produced anyway, and the list comes back in
      response headers (an RFC 8941 list; in each reference <code>%</code> and non-ASCII bytes are
      percent-encoded):
    </p>
    <app-code lang="text">X-Pdfbox-Unresolved-Count: 2
X-Pdfbox-Unresolved-Resources: "sigwx.svg";reason=not-found, "https://tiles.example.org/fir.png";reason=not-allowed</app-code>

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
    <p>Errors are plain text (UTF-8) with an actionable message — never a 500 for bad input:</p>
    <table>
      <thead>
        <tr><th>Status</th><th>When</th></tr>
      </thead>
      <tbody>
        <tr><td><code>400</code></td><td>Malformed request, unknown <code>standard</code> or <code>onMissingResource</code>, empty upload, invalid / duplicate / unsupported attached file, unknown part.</td></tr>
        <tr><td><code>413</code></td><td>A part or the whole upload exceeds the configured size limits.</td></tr>
        <tr><td><code>422</code></td><td>The document cites resources that could not be resolved (<code>onMissingResource=fail</code>, the default).</td></tr>
        <tr><td><code>503</code></td><td>All render slots are busy (<code>PDFBOX_RENDER_MAX_CONCURRENT</code>) for longer than <code>PDFBOX_RENDER_QUEUE_TIMEOUT</code>; retry after the <code>Retry-After</code> delay.</td></tr>
      </tbody>
    </table>
    <p>
      A successful call always returns <code>200</code> with <code>Content-Type: application/pdf</code>
      and a <code>Content-Disposition: attachment</code> header.
    </p>
  `,
})
export class EndpointsComponent {}
