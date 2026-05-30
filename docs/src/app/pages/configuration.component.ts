import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CodeComponent } from '../code/code.component';

@Component({
  selector: 'app-configuration',
  imports: [CodeComponent, RouterLink],
  template: `
    <h2>Configuration</h2>

    <p>
      Sensible defaults mean you normally configure <strong>nothing</strong>. When you do need to,
      override via environment variables (shown), Spring command-line arguments, or any standard Spring
      property source.
    </p>

    <table>
      <thead>
        <tr><th>Variable</th><th>Default</th><th>Purpose</th></tr>
      </thead>
      <tbody>
        <tr><td><code>PDFBOX_DEFAULT_STANDARD</code></td><td><code>PDF_A_1B</code></td><td>Standard used when a request omits <code>standard</code>.</td></tr>
        <tr><td><code>PDFBOX_FONTS_DIRECTORIES</code></td><td><code>/app/fonts,/usr/share/fonts,fonts</code></td><td>Comma-separated font scan directories.</td></tr>
        <tr><td><code>PDFBOX_DEFAULT_FONT_FAMILY</code></td><td>Noto stack</td><td>CSS font stack applied when the HTML sets none.</td></tr>
        <tr><td><code>PDFBOX_DEFAULT_LANG</code></td><td><code>en</code></td><td><code>&lt;html lang&gt;</code> injected when the source omits it (needed by the “A” levels).</td></tr>
        <tr><td><code>PDFBOX_DEFAULT_TITLE</code></td><td><code>Document</code></td><td><code>&lt;title&gt;</code> injected when the source has none and no usable heading.</td></tr>
        <tr><td><code>PDFBOX_BASE_PATH</code></td><td><em>(empty)</em></td><td>Base path prefixed to <strong>every</strong> route, set at startup. Must start with <code>/</code>.</td></tr>
        <tr><td><code>SPRING_PROFILES_ACTIVE</code></td><td><em>(none)</em></td><td>Set to <code>dev</code> to enable Swagger UI.</td></tr>
        <tr><td><code>JAVA_OPTS</code></td><td><em>(empty)</em></td><td>Extra JVM flags passed to the process.</td></tr>
      </tbody>
    </table>

    <h3>Accessible-output defaults</h3>
    <p>
      <code>PDFBOX_DEFAULT_LANG</code> and <code>PDFBOX_DEFAULT_TITLE</code> are safety nets for the
      tagged <code>A</code> levels (PDF/UA needs a language and a title). Explicit source values always
      win. The injected <code>&lt;meta name="subject"&gt;</code> maps to the PDF <em>Subject</em> — see
      <a routerLink="/standards">PDF/A standards</a>.
    </p>

    <h3>Base path (set at launch)</h3>
    <p>
      The base path is a <strong>startup parameter</strong>, fixed for the lifetime of the process —
      not a per-request value. Provide it as an environment variable or a Spring argument:
    </p>
    <app-code lang="bash"># environment variable
PDFBOX_BASE_PATH=/pdfbox java -jar target/pdfbox.jar
docker run --rm -p 8080:8080 -e PDFBOX_BASE_PATH=/pdfbox softwarity/pdfbox:latest

# ...or as a launch argument
java -jar target/pdfbox.jar --server.servlet.context-path=/pdfbox</app-code>
    <p>Every route then lives under that prefix:</p>
    <app-code lang="text">POST http://localhost:8080/pdfbox/api/v1/pdf
GET  http://localhost:8080/pdfbox/v3/api-docs
GET  http://localhost:8080/pdfbox/actuator/health</app-code>

    <h3>Changing the default standard</h3>
    <app-code lang="bash">docker run --rm -p 8080:8080 \\
  -e PDFBOX_DEFAULT_STANDARD=PDF_A_2U \\
  softwarity/pdfbox:latest</app-code>

    <h3>Pointing at extra fonts</h3>
    <app-code lang="bash">docker run --rm -p 8080:8080 \\
  -e PDFBOX_FONTS_DIRECTORIES=/app/fonts,/usr/share/fonts,fonts \\
  -v "\$PWD/my-fonts:/app/fonts:ro" \\
  softwarity/pdfbox:latest</app-code>
    <p>Only <code>.ttf</code> is registered — see <a routerLink="/fonts">Fonts &amp; scripts</a> for the caveats.</p>
  `,
})
export class ConfigurationComponent {}
